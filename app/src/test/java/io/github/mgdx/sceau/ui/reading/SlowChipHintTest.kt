package io.github.mgdx.sceau.ui.reading

import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.session.ReadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowChipHintTest {
    @Test
    fun `timer runs only while the secure channel is being opened`() {
        assertTrue(SlowChipHint.isTimed(ReadState.Reading(Step.SECURE_CHANNEL)))
        Step.entries.filter { it != Step.SECURE_CHANNEL }.forEach {
            assertFalse(it.name, SlowChipHint.isTimed(ReadState.Reading(it)))
        }
        assertFalse(SlowChipHint.isTimed(ReadState.WaitingForCard))
        assertFalse(SlowChipHint.isTimed(ReadState.Error("TIMEOUT")))
        assertFalse(SlowChipHint.isTimed(ReadState.Idle))
    }

    @Test
    fun `message appears after five seconds`() {
        assertEquals(5_000L, SlowChipHint.DELAY_MILLIS)
    }
}
