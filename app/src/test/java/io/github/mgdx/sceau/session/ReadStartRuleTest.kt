package io.github.mgdx.sceau.session

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.Step
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule for starting a read when a document is detected, checked twice by
 * [SessionViewModel.onCardDetected]: on the NFC thread, then on the main thread (audit V16).
 */
class ReadStartRuleTest {
    private val key = AccessKey.Can("123456")

    @Test
    fun `a card starts a read when waiting or after an error, with a key`() {
        assertTrue(SessionViewModel.canStartRead(ReadState.WaitingForCard, key))
        assertTrue(SessionViewModel.canStartRead(ReadState.Error("ACCESS_DENIED"), key))
    }

    @Test
    fun `a card is ignored once cleared, without a key`() {
        // clear(): key forgotten and Idle state, separately or together.
        assertFalse(SessionViewModel.canStartRead(ReadState.WaitingForCard, null))
        assertFalse(SessionViewModel.canStartRead(ReadState.Error("ACCESS_DENIED"), null))
        assertFalse(SessionViewModel.canStartRead(ReadState.Idle, key))
        assertFalse(SessionViewModel.canStartRead(ReadState.Idle, null))
    }

    @Test
    fun `a second detection never starts a second read`() {
        assertFalse(SessionViewModel.canStartRead(ReadState.Reading(Step.CONNECT), key))
        assertFalse(SessionViewModel.canStartRead(ReadState.Reading(Step.READ_DATA), key))
    }
}
