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

/** Table de décision du verdict global (SPEC §5.3). */
class VerdictsTest {
    /** Liste complète, tout OK, où [overrides] remplace certaines lignes. */
    private fun checks(vararg overrides: Pair<CheckId, CheckStatus>): List<Check> {
        val map = overrides.toMap()
        return CheckId.entries.map { Check(it, map[it] ?: OK) }
    }

    private fun verdict(vararg overrides: Pair<CheckId, CheckStatus>) = Verdicts.compute(checks(*overrides))

    @Test
    fun `PA et CA et AA OK - authentique`() = assertEquals(Verdict.AUTHENTIC, verdict())

    @Test
    fun `CA seule ou AA seule suffit`() {
        assertEquals(Verdict.AUTHENTIC, verdict(CheckId.ACTIVE_AUTHENTICATION to NOT_AVAILABLE))
        assertEquals(Verdict.AUTHENTIC, verdict(CheckId.CHIP_AUTHENTICATION to NOT_AVAILABLE))
    }

    @Test
    fun `validite du DS non disponible - toujours authentique`() =
        assertEquals(Verdict.AUTHENTIC, verdict(CheckId.DS_VALIDITY to NOT_AVAILABLE))

    @Test
    fun `ni CA ni AA - signature valide, puce non verifiee`() =
        assertEquals(
            Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED,
            verdict(CheckId.CHIP_AUTHENTICATION to NOT_AVAILABLE, CheckId.ACTIVE_AUTHENTICATION to NOT_AVAILABLE),
        )

    @Test
    fun `chaine non disponible - emetteur inconnu`() {
        assertEquals(Verdict.UNKNOWN_ISSUER, verdict(CheckId.CERTIFICATE_CHAIN to NOT_AVAILABLE))
        assertEquals(
            Verdict.UNKNOWN_ISSUER,
            verdict(CheckId.CERTIFICATE_CHAIN to NOT_AVAILABLE, CheckId.SOD_SIGNATURE to NOT_AVAILABLE),
        )
    }

    @Test
    fun `une ligne en echec ou algorithme inconnu - echec, meme si la chaine est inconnue`() {
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
    fun `lignes indispensables non disponibles - echec par prudence`() {
        for (id in listOf(CheckId.SECURE_CHANNEL, CheckId.SOD_SIGNATURE, CheckId.DG_HASHES)) {
            assertEquals("$id", Verdict.FAILED, verdict(id to NOT_AVAILABLE))
        }
    }

    @Test
    fun `ligne manquante - echec par prudence`() {
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
