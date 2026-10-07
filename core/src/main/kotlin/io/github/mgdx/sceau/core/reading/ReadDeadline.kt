package io.github.mgdx.sceau.core.reading

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Délai global d'une lecture, hors authentification (audit V29).
 *
 * Le budget [budgetMillis] ne s'écoule que pendant que le délai est en cours ([resume]) ; il est
 * suspendu pendant PACE et BAC ([pause]), qui ont leur propre délai par réponse (60 s, D12). Les
 * E/S de la lecture sont bloquantes : à l'échéance, un minuteur lancé dans [scope] appelle
 * [onExpiry] (qui ferme le transport pour débloquer l'APDU en cours) et [expired] devient vrai ;
 * c'est à l'appelant de transformer la fin de la lecture en erreur.
 */
internal class ReadDeadline(
    private val scope: CoroutineScope,
    budgetMillis: Long,
    private val onExpiry: () -> Unit,
) {
    private var remainingNanos = budgetMillis * NANOS_PER_MILLI
    private var startedAt = 0L
    private var timer: Job? = null

    @Volatile
    var expired = false
        private set

    /** Fait (re)partir le décompte du budget restant ; sans effet s'il est déjà en cours. */
    fun resume() {
        if (timer != null || expired) return
        startedAt = System.nanoTime()
        val remainingMillis = (remainingNanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI
        timer =
            scope.launch {
                delay(remainingMillis.coerceAtLeast(0))
                expired = true
                onExpiry()
            }
    }

    /** Suspend le décompte (authentification) ; le budget déjà consommé est retenu. */
    fun pause() {
        val running = timer ?: return
        running.cancel()
        timer = null
        remainingNanos -= System.nanoTime() - startedAt
    }

    /** Arrête le minuteur en fin de lecture. */
    fun stop() {
        timer?.cancel()
        timer = null
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
