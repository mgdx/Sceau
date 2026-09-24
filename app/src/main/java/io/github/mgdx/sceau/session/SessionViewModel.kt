package io.github.mgdx.sceau.session

import androidx.lifecycle.ViewModel
import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.report.VerificationReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
 */
class SessionViewModel : ViewModel() {
    private val _state = MutableStateFlow<ReadState>(ReadState.Idle)
    val state: StateFlow<ReadState> = _state

    /** Mémorise la clé pour la prochaine lecture et passe en WaitingForCard. */
    fun prepare(key: AccessKey): Unit = TODO("lot D")

    /** Appelé par l'activité quand un tag IsoDep est détecté en WaitingForCard ou Error. */
    fun onCardDetected(transport: CardTransport): Unit = TODO("lot D")

    /** Recommence après une erreur avec la même clé (bouton « Réessayer »). */
    fun retry(): Unit = TODO("lot D")

    /** Efface tout : rapport (wipe), clé, état → Idle. Bouton « Effacer », retour, arrière-plan. */
    fun clear(): Unit = TODO("lot D")
}
