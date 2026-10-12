package io.github.mgdx.sceau.ui.reading

import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.session.ReadState

/**
 * "The document's chip is making you wait" message of the reading screen: displayed when opening
 * the secure channel takes longer than [DELAY_MILLIS]. After failed attempts, a chip may impose
 * an increasing delay before answering (`:core` waits for it up to 60 s).
 */
internal object SlowChipHint {
    const val DELAY_MILLIS = 5_000L

    /** True during the step in which the chip may make the user wait: the timer is running. */
    fun isTimed(state: ReadState): Boolean = state is ReadState.Reading && state.current == Step.SECURE_CHANNEL
}
