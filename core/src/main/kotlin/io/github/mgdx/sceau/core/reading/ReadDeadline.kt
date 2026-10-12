package io.github.mgdx.sceau.core.reading

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Global deadline of a reading, excluding authentication (audit V29).
 *
 * The [budgetMillis] budget only elapses while the deadline is running ([resume]); it is
 * suspended during PACE and BAC ([pause]), which have their own per-response timeout (60 s, D12). The
 * reading's I/O is blocking: when the deadline expires, a timer launched in [scope] calls
 * [onExpiry] (which closes the transport to unblock the pending APDU) and [expired] becomes true;
 * it is up to the caller to turn the end of the reading into an error.
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

    /** (Re)starts the countdown of the remaining budget; no effect if it is already running. */
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

    /** Suspends the countdown (authentication); the budget already consumed is kept. */
    fun pause() {
        val running = timer ?: return
        running.cancel()
        timer = null
        remainingNanos -= System.nanoTime() - startedAt
    }

    /** Stops the timer at the end of the reading. */
    fun stop() {
        timer?.cancel()
        timer = null
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
