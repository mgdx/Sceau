package io.github.mgdx.sceau.session

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.Step
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Règle de démarrage d'une lecture à la détection d'un document, vérifiée deux fois par
 * [SessionViewModel.onCardDetected] : sur le thread NFC, puis sur le thread principal (audit V16).
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
        // clear() : clé oubliée et état Idle, séparément ou ensemble.
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
