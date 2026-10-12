package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.CheckStatus.FAILED
import io.github.mgdx.sceau.core.report.CheckStatus.NOT_AVAILABLE
import io.github.mgdx.sceau.core.report.CheckStatus.OK
import io.github.mgdx.sceau.core.report.CheckStatus.UNSUPPORTED_ALGORITHM
import io.github.mgdx.sceau.core.report.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** Decision table for the overall verdict (SPEC §5.3). */
class VerdictsTest {
    /** Full list, all OK, where [overrides] replaces some rows. */
    private fun checks(vararg overrides: Pair<CheckId, CheckStatus>): List<Check> {
        val map = overrides.toMap()
        return CheckId.entries.map { Check(it, map[it] ?: OK) }
    }

    private fun verdict(vararg overrides: Pair<CheckId, CheckStatus>) = Verdicts.compute(checks(*overrides))

    @Test
    fun `PA and CA and AA OK - authentic`() = assertEquals(Verdict.AUTHENTIC, verdict())

    @Test
    fun `CA alone or AA alone is enough`() {
        assertEquals(Verdict.AUTHENTIC, verdict(CheckId.ACTIVE_AUTHENTICATION to NOT_AVAILABLE))
        assertEquals(Verdict.AUTHENTIC, verdict(CheckId.CHIP_AUTHENTICATION to NOT_AVAILABLE))
    }

    @Test
    fun `DS validity not available - still authentic`() = assertEquals(Verdict.AUTHENTIC, verdict(CheckId.DS_VALIDITY to NOT_AVAILABLE))

    @Test
    fun `neither CA nor AA - valid signature, chip not verified`() =
        assertEquals(
            Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED,
            verdict(CheckId.CHIP_AUTHENTICATION to NOT_AVAILABLE, CheckId.ACTIVE_AUTHENTICATION to NOT_AVAILABLE),
        )

    @Test
    fun `chain not available - unknown issuer`() {
        assertEquals(Verdict.UNKNOWN_ISSUER, verdict(CheckId.CERTIFICATE_CHAIN to NOT_AVAILABLE))
        assertEquals(
            Verdict.UNKNOWN_ISSUER,
            verdict(CheckId.CERTIFICATE_CHAIN to NOT_AVAILABLE, CheckId.SOD_SIGNATURE to NOT_AVAILABLE),
        )
    }

    @Test
    fun `a failed row or unknown algorithm - failure, even if the chain is unknown`() {
        for (id in CheckId.entries) {
            for (status in listOf(FAILED, UNSUPPORTED_ALGORITHM)) {
                assertEquals("$id $status", Verdict.FAILED, verdict(id to status))
                if (id != CheckId.CERTIFICATE_CHAIN) {
                    assertEquals("$id $status", Verdict.FAILED, verdict(id to status, CheckId.CERTIFICATE_CHAIN to NOT_AVAILABLE))
                }
            }
        }
    }

    @Test
    fun `required rows not available - failure as a precaution`() {
        for (id in listOf(CheckId.SECURE_CHANNEL, CheckId.SOD_SIGNATURE, CheckId.DG_HASHES)) {
            assertEquals("$id", Verdict.FAILED, verdict(id to NOT_AVAILABLE))
        }
    }

    @Test
    fun `missing row - failure as a precaution`() {
        assertEquals(Verdict.FAILED, Verdicts.compute(checks().filter { it.id != CheckId.DG_HASHES }))
        assertEquals(
            Verdict.FAILED,
            Verdicts.compute(
                checks().filter {
                    it.id != CheckId.ACTIVE_AUTHENTICATION &&
                        it.id != CheckId.CHIP_AUTHENTICATION
                },
            ),
        )
        assertEquals(Verdict.FAILED, Verdicts.compute(emptyList()))
    }
}
