package io.github.mgdx.sceau.session

import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.model.Dg1Data
import io.github.mgdx.sceau.core.model.DocumentData
import io.github.mgdx.sceau.core.model.Sex
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.report.VerificationReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRuleTest {
    @Test
    fun `reading and read data are cleared in background`() {
        assertTrue(SessionViewModel.shouldClearOnBackground(ReadState.Reading(Step.READ_DATA)))
        assertTrue(SessionViewModel.shouldClearOnBackground(ReadState.Done(report())))
    }

    @Test
    fun `form and key survive background before any data is read`() {
        assertFalse(SessionViewModel.shouldClearOnBackground(ReadState.Idle))
        assertFalse(SessionViewModel.shouldClearOnBackground(ReadState.WaitingForCard))
        assertFalse(SessionViewModel.shouldClearOnBackground(ReadState.Error("ACCESS_DENIED")))
    }

    private fun report() =
        VerificationReport(
            verdict = Verdict.FAILED,
            checks = emptyList(),
            chain = null,
            document =
                DocumentData(
                    dg1 = Dg1Data("P", "UTO", "X1", "DOE", emptyList(), "UTO", null, Sex.UNSPECIFIED, null, null),
                    portrait = null,
                    dg11 = null,
                    dg12 = null,
                    rawDataGroups = emptyMap(),
                ),
        )
}
