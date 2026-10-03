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

    /** [isDemo] : rapport d'un document simulé du mode démo (APK de debug), jamais d'un vrai. */
    class Done(
        val report: VerificationReport,
        val isDemo: Boolean = false,
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

    /** Lu et écrit uniquement sur le thread principal ([launchRead], [abortRead]). */
    private var job: Job? = null

    /**
     * Fermeture des transports : `IsoDep.close()` attend la fin de l'APDU en cours (jusqu'au délai
     * de 10 s, 60 s pendant l'authentification), elle ne doit jamais s'exécuter sur le thread principal. Ce scope n'est jamais
     * annulé, pour que la fermeture ait lieu même après l'annulation de la lecture ou `onCleared`.
     */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun selectTab(tab: DocumentTab) {
        form = form.copy(tab = tab)
    }

    /** Choix entre CAN et MRZ dans l'onglet Carte d'identité ; la saisie de l'autre clé est gardée. */
    fun selectIdCardKey(key: IdCardKey) {
        form = form.copy(idCardKey = key)
    }

    fun onCanChange(input: String) {
        form = form.copy(can = AccessForm.filterCan(input))
    }

    fun onDocumentNumberChange(input: String) {
        form = form.copy(documentNumber = AccessForm.normalizeDocumentNumber(input))
    }

    /** Saisie clavier de la date de naissance : seuls les chiffres sont gardés. */
    fun onDateOfBirthChange(input: String) {
        form = form.copy(dateOfBirthDigits = AccessForm.filterDateDigits(input))
    }

    /** Saisie clavier de la date d'expiration : seuls les chiffres sont gardés. */
    fun onDateOfExpiryChange(input: String) {
        form = form.copy(dateOfExpiryDigits = AccessForm.filterDateDigits(input))
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
     *
     * Le démarrage est posté sur le thread principal, où s'exécutent aussi [clear], [prepare]
     * et [startDemo] : un effacement ne peut plus s'intercaler entre la vérification de l'état
     * et l'enregistrement de la lecture (audit V16).
     */
    fun onCardDetected(transport: CardTransport) {
        if (!canStartRead(_state.value, key)) {
            // Lecture déjà en cours, ou aucune clé : on ignore ce document.
            closeInBackground(transport)
            return
        }
        val start =
            viewModelScope.launch(Dispatchers.Main.immediate) {
                val key = key
                if (key == null || !canStartRead(_state.value, key)) {
                    // Effacée ou déjà démarrée entre-temps (autre détection du même document).
                    closeInBackground(transport)
                    return@launch
                }
                _state.value = ReadState.Reading(Step.CONNECT)
                launchRead(transport, key, isDemo = false) { repository.get() }
            }
        // Démarrage jamais exécuté (ViewModel détruit entre-temps) : le transport est fermé ici.
        start.invokeOnCompletion { cause -> if (cause != null) closeInBackground(transport) }
    }

    /**
     * Lit la CNIe simulée du mode démo (APK de debug uniquement) comme un document réel, avec sa
     * clé et son magasin de test. Ce magasin ne sert qu'à cette lecture : il ne remplace jamais
     * le magasin réel. La clé saisie est oubliée, pour qu'un vrai document présenté ensuite ne
     * soit jamais lu avec la clé ou le magasin de la démo. Mêmes règles d'effacement.
     */
    fun startDemo(card: DemoCard) {
        abortRead()
        (_state.value as? ReadState.Done)?.report?.wipe()
        key = null
        _state.value = ReadState.Reading(Step.CONNECT)
        launchRead(card.transport, card.key, isDemo = true) { card.trustStore }
    }

    /** Lance la lecture de [transport] ; l'état doit déjà être Reading. Thread principal uniquement. */
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
                    // Publié seulement si la lecture n'a été ni abandonnée ni effacée ; sinon le
                    // rapport, qu'aucun écran n'effacerait, l'est ici.
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
        closeDisposables()
    }

    /** Ressources d'affichage du rapport courant (bitmaps de l'écran Résultat), fermées par [clear]. */
    private val disposables = mutableListOf<AutoCloseable>()

    /**
     * Confie [resource] (tirée de [report]) à la session, pour que [clear] la ferme directement,
     * sans attendre une recomposition (audit V13). Si [report] n'est plus le rapport affiché,
     * [resource] est fermée aussitôt et la méthode renvoie false.
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

    /** Retire [resource] de la session sans la fermer (l'appelant s'en charge). */
    fun unregisterDisposable(resource: AutoCloseable) {
        synchronized(disposables) { disposables.remove(resource) }
    }

    private fun closeDisposables() {
        val toClose = synchronized(disposables) { disposables.toList().also { disposables.clear() } }
        toClose.forEach { it.close() }
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

        /** Vrai si un document détecté peut lancer une lecture : clé présente, en attente ou après une erreur. */
        fun canStartRead(
            state: ReadState,
            key: AccessKey?,
        ): Boolean = key != null && (state == ReadState.WaitingForCard || state is ReadState.Error)
    }
}
