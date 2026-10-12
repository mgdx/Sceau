package io.github.mgdx.sceau.session

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mgdx.sceau.SceauApplication
import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.report.VerificationReport
import io.github.mgdx.sceau.core.trust.TrustStore
import io.github.mgdx.sceau.demo.DemoCard
import io.github.mgdx.sceau.trust.TrustStoreRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Read state, shared between the Reading and Result screens. */
sealed interface ReadState {
    /** No read in progress and no result in memory. */
    data object Idle : ReadState

    /** Waiting for the document; [key] is present until the read ends. */
    data object WaitingForCard : ReadState

    /** [current]: step in progress; the previous ones are checked. */
    data class Reading(
        val current: Step,
    ) : ReadState

    /** Error that interrupted the read; [code] comes from SceauException.code. */
    data class Error(
        val code: String,
    ) : ReadState

    /** [isDemo]: report of a simulated document from demo mode (debug APK), never of a real one. */
    class Done(
        val report: VerificationReport,
        val isDemo: Boolean = false,
    ) : ReadState
}

/**
 * Activity-scoped ViewModel. Contract shared between the "reading" work package (which
 * implements it) and the "result" work package (which consumes it). Signatures are frozen.
 *
 * The access key and the form input live only here, in memory: never in a Bundle or a
 * `SavedStateHandle`, which survive on disk when the process dies (SPEC §8).
 */
class SessionViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository: TrustStoreRepository = (application as SceauApplication).trustStoreRepository

    private val _state = MutableStateFlow<ReadState>(ReadState.Idle)
    val state: StateFlow<ReadState> = _state

    /** Home screen input, cleared by [clear]. */
    var form: AccessForm by mutableStateOf(AccessForm())
        private set

    /** Key for the current or upcoming read; forgotten after a success or by [clear]. */
    @Volatile
    private var key: AccessKey? = null

    /** Token of the current read: a callback from an abandoned read no longer touches the state. */
    @Volatile
    private var currentRead: Any? = null

    @Volatile
    private var transport: CardTransport? = null

    /** Read and written only on the main thread ([launchRead], [abortRead]). */
    private var job: Job? = null

    /**
     * Closing transports: `IsoDep.close()` waits for the current APDU to finish (up to the timeout
     * of 10 s, 60 s during authentication), so it must never run on the main thread. This scope is never
     * cancelled, so that closing happens even after the read is cancelled or after `onCleared`.
     */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun selectTab(tab: DocumentTab) {
        form = form.copy(tab = tab)
    }

    /** Choice between CAN and MRZ in the "ID card" tab; the input for the other key is kept. */
    fun selectIdCardKey(key: IdCardKey) {
        form = form.copy(idCardKey = key)
    }

    fun onCanChange(input: String) {
        form = form.copy(can = AccessForm.filterCan(input))
    }

    fun onDocumentNumberChange(input: String) {
        form = form.copy(documentNumber = AccessForm.normalizeDocumentNumber(input))
    }

    /** Keyboard input of the date of birth: only digits are kept. */
    fun onDateOfBirthChange(input: String) {
        form = form.copy(dateOfBirthDigits = AccessForm.filterDateDigits(input))
    }

    /** Keyboard input of the expiry date: only digits are kept. */
    fun onDateOfExpiryChange(input: String) {
        form = form.copy(dateOfExpiryDigits = AccessForm.filterDateDigits(input))
    }

    /** Stores the key for the next read and switches to WaitingForCard. */
    fun prepare(key: AccessKey) {
        abortRead()
        (_state.value as? ReadState.Done)?.report?.wipe()
        this.key = key
        _state.value = ReadState.WaitingForCard
    }

    /**
     * Called by the activity when an IsoDep tag is detected in WaitingForCard or Error.
     * May be called from the NFC reader thread.
     *
     * The start is posted to the main thread, where [clear], [prepare] and [startDemo] also
     * run: a clear can no longer slip in between the state check and the registration of the
     * read (audit V16).
     */
    fun onCardDetected(transport: CardTransport) {
        if (!canStartRead(_state.value, key)) {
            // Read already in progress, or no key: this document is ignored.
            closeInBackground(transport)
            return
        }
        val start =
            viewModelScope.launch(Dispatchers.Main.immediate) {
                val key = key
                if (key == null || !canStartRead(_state.value, key)) {
                    // Cleared or already started in the meantime (another detection of the same document).
                    closeInBackground(transport)
                    return@launch
                }
                _state.value = ReadState.Reading(Step.CONNECT)
                launchRead(transport, key, isDemo = false) { repository.get() }
            }
        // Start never executed (ViewModel destroyed in the meantime): the transport is closed here.
        start.invokeOnCompletion { cause -> if (cause != null) closeInBackground(transport) }
    }

    /**
     * Reads the simulated CNIe of demo mode (debug APK only) like a real document, with its own
     * key and test trust store. This store is used only for this read: it never replaces the real
     * store. The entered key is forgotten, so that a real document presented afterwards is never
     * read with the demo key or store. Same clearing rules.
     */
    fun startDemo(card: DemoCard) {
        abortRead()
        (_state.value as? ReadState.Done)?.report?.wipe()
        key = null
        _state.value = ReadState.Reading(Step.CONNECT)
        launchRead(card.transport, card.key, isDemo = true) { card.trustStore }
    }

    /** Starts reading [transport]; the state must already be Reading. Main thread only. */
    private fun launchRead(
        transport: CardTransport,
        key: AccessKey,
        isDemo: Boolean,
        trustStore: suspend () -> TrustStore,
    ) {
        val token = Any()
        currentRead = token
        this.transport = transport
        val launched =
            viewModelScope.launch {
                try {
                    val store = trustStore()
                    val report =
                        withContext(Dispatchers.IO) {
                            readAndVerify(transport, key, store) { step ->
                                if (currentRead === token) {
                                    _state.update { if (it is ReadState.Reading) ReadState.Reading(step) else it }
                                }
                            }
                        }
                    // Published only if the read was neither abandoned nor cleared; otherwise the
                    // report, which no screen would wipe, is wiped here.
                    if (currentRead === token && _state.value is ReadState.Reading) {
                        currentRead = null
                        this@SessionViewModel.key = null
                        form = AccessForm(tab = form.tab, idCardKey = form.idCardKey)
                        _state.value = ReadState.Done(report, isDemo)
                    } else {
                        report.wipe()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SceauException) {
                    finishWithError(token, e.code)
                } catch (e: Throwable) {
                    // Everything else, including Errors (NotImplementedError, OutOfMemoryError while
                    // decoding an image): a read must never crash the app.
                    finishWithError(token, UNEXPECTED_PREFIX + (e.javaClass.simpleName.ifEmpty { "Throwable" }))
                }
            }
        job = launched
        launched.invokeOnCompletion {
            closeInBackground(transport)
            if (this.transport === transport) this.transport = null
        }
    }

    /** Starts over after an error with the same key ("Try again" button). */
    fun retry() {
        if (_state.value is ReadState.Error && key != null) {
            _state.value = ReadState.WaitingForCard
        }
    }

    /** Clears everything: report (wipe), key, state → Idle. "Clear" button, back, background. */
    fun clear() {
        abortRead()
        (_state.value as? ReadState.Done)?.report?.wipe()
        key = null
        form = AccessForm()
        _state.value = ReadState.Idle
        closeDisposables()
    }

    /** Display resources of the current report (Result screen bitmaps), closed by [clear]. */
    private val disposables = mutableListOf<AutoCloseable>()

    /**
     * Hands [resource] (derived from [report]) over to the session, so that [clear] closes it
     * directly, without waiting for a recomposition (audit V13). If [report] is no longer the
     * displayed report, [resource] is closed immediately and the method returns false.
     */
    fun registerDisposable(
        report: VerificationReport,
        resource: AutoCloseable,
    ): Boolean {
        val accepted =
            synchronized(disposables) {
                ((_state.value as? ReadState.Done)?.report === report).also { if (it) disposables += resource }
            }
        if (!accepted) resource.close()
        return accepted
    }

    /** Removes [resource] from the session without closing it (the caller takes care of that). */
    fun unregisterDisposable(resource: AutoCloseable) {
        synchronized(disposables) { disposables.remove(resource) }
    }

    private fun closeDisposables() {
        val toClose = synchronized(disposables) { disposables.toList().also { disposables.clear() } }
        toClose.forEach { it.close() }
    }

    /**
     * The app goes to the background (excluding configuration changes). A read in progress is
     * interrupted and a report is wiped (SPEC §8); the input and the key, which live only in
     * memory, are kept so that the user can go through the NFC settings without re-entering
     * everything (SPEC §5.1).
     */
    fun onBackground() {
        if (shouldClearOnBackground(_state.value)) clear()
    }

    override fun onCleared() {
        clear()
    }

    private fun finishWithError(
        token: Any,
        code: String,
    ) {
        if (currentRead !== token) return
        currentRead = null
        _state.value = ReadState.Error(code)
    }

    /** Abandons the current read; closing the transport interrupts a blocked APDU. */
    private fun abortRead() {
        currentRead = null
        job?.cancel()
        job = null
        transport?.let(::closeInBackground)
        transport = null
    }

    /** Closes [transport] off the main thread, without waiting (see [closeScope]). */
    private fun closeInBackground(transport: CardTransport) {
        closeScope.launch { transport.close() }
    }

    internal companion object {
        private const val UNEXPECTED_PREFIX = "UNEXPECTED-"

        /** True if going to the background must clear everything: read in progress or data read. */
        fun shouldClearOnBackground(state: ReadState): Boolean = state is ReadState.Reading || state is ReadState.Done

        /** True if a detected document can start a read: key present, waiting or after an error. */
        fun canStartRead(
            state: ReadState,
            key: AccessKey?,
        ): Boolean = key != null && (state == ReadState.WaitingForCard || state is ReadState.Error)
    }
}
