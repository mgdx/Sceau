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
import java.time.LocalDate

/** État de la lecture, partagé entre les écrans Lecture et Résultat. */
sealed interface ReadState {
    /** Aucune lecture en cours ni résultat en mémoire. */
    data object Idle : ReadState

    /** En attente du document ; [key] est présente jusqu'à la fin de la lecture. */
    data object WaitingForCard : ReadState

    /** [current] : étape en cours ; les précédentes sont cochées. */
    data class Reading(
        val current: Step,
    ) : ReadState

    /** Erreur interrompant la lecture ; [code] vient de SceauException.code. */
    data class Error(
        val code: String,
    ) : ReadState

    class Done(
        val report: VerificationReport,
    ) : ReadState
}

/**
 * ViewModel à portée d'activité. Contrat partagé entre les lots « lecture » (qui
 * l'implémente) et « résultat » (qui le consomme). Signatures figées.
 *
 * La clé d'accès et la saisie du formulaire ne vivent qu'ici, en mémoire : jamais dans un
 * Bundle ni un `SavedStateHandle`, qui survivent sur disque à la mort du processus (SPEC §8).
 */
class SessionViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository: TrustStoreRepository = (application as SceauApplication).trustStoreRepository

    private val _state = MutableStateFlow<ReadState>(ReadState.Idle)
    val state: StateFlow<ReadState> = _state

    /** Saisie de l'écran d'accueil, effacée par [clear]. */
    var form: AccessForm by mutableStateOf(AccessForm())
        private set

    /** Clé de la lecture en cours ou à venir ; oubliée après un succès ou par [clear]. */
    @Volatile
    private var key: AccessKey? = null

    /** Jeton de la lecture en cours : un rappel d'une lecture abandonnée ne touche plus l'état. */
    @Volatile
    private var currentRead: Any? = null

    @Volatile
    private var transport: CardTransport? = null

    private var job: Job? = null

    /**
     * Fermeture des transports : `IsoDep.close()` attend la fin de l'APDU en cours (jusqu'au délai
     * de 10 s), elle ne doit jamais s'exécuter sur le thread principal. Ce scope n'est jamais
     * annulé, pour que la fermeture ait lieu même après l'annulation de la lecture ou `onCleared`.
     */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun selectTab(tab: DocumentTab) {
        form = form.copy(tab = tab)
    }

    fun onCanChange(input: String) {
        form = form.copy(can = AccessForm.filterCan(input))
    }

    fun onDocumentNumberChange(input: String) {
        form = form.copy(documentNumber = AccessForm.normalizeDocumentNumber(input))
    }

    fun onDateOfBirthChange(date: LocalDate?) {
        form = form.copy(dateOfBirth = date)
    }

    fun onDateOfExpiryChange(date: LocalDate?) {
        form = form.copy(dateOfExpiry = date)
    }

    /** Mémorise la clé pour la prochaine lecture et passe en WaitingForCard. */
    fun prepare(key: AccessKey) {
        abortRead()
        (_state.value as? ReadState.Done)?.report?.wipe()
        this.key = key
        _state.value = ReadState.WaitingForCard
    }

    /**
     * Appelé par l'activité quand un tag IsoDep est détecté en WaitingForCard ou Error.
     * Peut être appelé depuis le thread du lecteur NFC.
     */
    fun onCardDetected(transport: CardTransport) {
        val current = _state.value
        val key = key
        if (key == null ||
            (current != ReadState.WaitingForCard && current !is ReadState.Error) ||
            !_state.compareAndSet(current, ReadState.Reading(Step.CONNECT))
        ) {
            // Lecture déjà en cours, ou aucune clé : on ignore ce document.
            closeInBackground(transport)
            return
        }
        val token = Any()
        currentRead = token
        this.transport = transport
        val launched =
            viewModelScope.launch {
                try {
                    val trustStore = repository.get()
                    val report =
                        withContext(Dispatchers.IO) {
                            readAndVerify(transport, key, trustStore) { step ->
                                if (currentRead === token) {
                                    _state.update { if (it is ReadState.Reading) ReadState.Reading(step) else it }
                                }
                            }
                        }
                    if (currentRead === token) {
                        currentRead = null
                        this@SessionViewModel.key = null
                        form = AccessForm(tab = form.tab)
                        _state.value = ReadState.Done(report)
                    } else {
                        report.wipe()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SceauException) {
                    finishWithError(token, e.code)
                } catch (e: Throwable) {
                    // Tout le reste, y compris les Error (NotImplementedError, OutOfMemoryError au
                    // décodage d'une image) : une lecture ne doit jamais faire planter l'app.
                    finishWithError(token, UNEXPECTED_PREFIX + (e.javaClass.simpleName.ifEmpty { "Throwable" }))
                }
            }
        job = launched
        launched.invokeOnCompletion {
            closeInBackground(transport)
            if (this.transport === transport) this.transport = null
        }
    }

    /** Recommence après une erreur avec la même clé (bouton « Réessayer »). */
    fun retry() {
        if (_state.value is ReadState.Error && key != null) {
            _state.value = ReadState.WaitingForCard
        }
    }

    /** Efface tout : rapport (wipe), clé, état → Idle. Bouton « Effacer », retour, arrière-plan. */
    fun clear() {
        abortRead()
        (_state.value as? ReadState.Done)?.report?.wipe()
        key = null
        form = AccessForm()
        _state.value = ReadState.Idle
    }

    /**
     * Mise en arrière-plan de l'application (hors changement de configuration). Une lecture en
     * cours est interrompue et un rapport est effacé (SPEC §8) ; la saisie et la clé, qui ne
     * vivent qu'en mémoire, sont gardées pour que l'utilisateur puisse passer par les réglages
     * NFC sans tout ressaisir (SPEC §5.1).
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

    /** Abandonne la lecture en cours ; fermer le transport interrompt une APDU bloquée. */
    private fun abortRead() {
        currentRead = null
        job?.cancel()
        job = null
        transport?.let(::closeInBackground)
        transport = null
    }

    /** Ferme [transport] hors du thread principal, sans attendre (voir [closeScope]). */
    private fun closeInBackground(transport: CardTransport) {
        closeScope.launch { transport.close() }
    }

    internal companion object {
        private const val UNEXPECTED_PREFIX = "UNEXPECTED-"

        /** Vrai si la mise en arrière-plan doit tout effacer : lecture en cours ou données lues. */
        fun shouldClearOnBackground(state: ReadState): Boolean = state is ReadState.Reading || state is ReadState.Done
    }
}
