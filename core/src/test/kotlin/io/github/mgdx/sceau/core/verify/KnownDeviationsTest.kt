package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.IssuanceDateSource
import io.github.mgdx.sceau.core.report.KnownDeviation
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.trust.TrustStore
import io.github.mgdx.sceau.testchip.SodOptions
import io.github.mgdx.sceau.testchip.TestCrypto
import io.github.mgdx.sceau.testchip.TestDocument
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import io.github.mgdx.sceau.testchip.TestSod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Known issuer deviations (decision D34): a hash mismatch is tolerated on DG11 or DG12, never
 * elsewhere, and only on a SOD whose signature and chain are valid.
 */
class KnownDeviationsTest {
    private val pki = TestPki(TestKeyType.RSA, name = "Italia Test", country = "IT", seed = SEED)

    /** DS in service during the period of the Italian Deviation List. */
    private val ds by lazy { pki.issueDs(notBefore = LocalDate.of(2017, 6, 1), notAfter = LocalDate.of(2028, 6, 1)) }

    private fun store(): TrustStore = pki.trustStore(pki.oldCsca)

    /** Dummy CIE 3.0 covered by the Italian criterion, with DG12 signed as [signedDg12]. */
    private fun cie(configure: TestDocument.Builder.() -> Unit = {}): TestDocument =
        pki.document {
            ds = this@KnownDeviationsTest.ds
            documentCode = "C"
            issuingState = "ITA"
            documentNumber = "CA12345XY"
            dateOfExpiry = EXPIRY
            extraDataGroups = mapOf(12 to signedDg12)
            sod = SodOptions(signingTime = TestCrypto.instant(LocalDate.of(2017, 12, 15)))
            configure()
        }

    private val signedDg12 = TestSod.tlv(DG12_TAG, byteArrayOf(0x5F, 0x19, 0x02, 0x41, 0x42))
    private val servedDg12 = TestSod.tlv(DG12_TAG, byteArrayOf(0x5F, 0x19, 0x02, 0x41, 0x43))

    private fun TestDocument.withWrongDg12(): TestDocument = withDataGroups(dataGroups + (12 to servedDg12))

    private fun verify(
        document: TestDocument,
        dg12DateOfIssue: LocalDate? = ItalianCie3Dg12.DG12_DATE_OF_ISSUE,
        trustStore: TrustStore = store(),
        sod: ByteArray = document.sod,
        deviations: KnownDeviations = KnownDeviations.REGISTRY,
        issuingState: String = "ITA",
        documentNumber: String = "CA12345XY",
    ): PassiveAuthResult =
        PassiveAuthenticator(trustStore, deviations).verify(
            sod = sod,
            dataGroups = document.dataGroups,
            dateOfIssue = dg12DateOfIssue,
            dateOfExpiry = document.dateOfExpiry,
            documentCode = document.documentCode,
            issuingState = issuingState,
            documentNumber = documentNumber,
        )

    private fun PassiveAuthResult.hashes() = dataGroupHashes.detail as CheckDetail.DataGroupHashes

    private fun PassiveAuthResult.verdict() = Verdicts.compute(VerifyTestSupport.checks(this, ca = CheckStatus.OK))

    @Test
    fun `covered document, wrong DG12 - mismatch tolerated, DG12 discarded, verdict Authentic`() {
        val result = verify(cie().withWrongDg12())

        assertEquals(CheckStatus.OK, result.dataGroupHashes.status)
        assertEquals(emptyList<Int>(), result.hashes().mismatched)
        assertEquals(listOf(KnownDeviation("IT-CIE3-DG12", 12)), result.hashes().deviations)
        assertTrue(12 in result.hashes().checked)
        assertEquals(setOf(12), result.discardedDataGroups)
        // The date of issue from the discarded DG12 is not used: falls back to signingTime.
        val validity = result.dsValidity.detail as CheckDetail.DsValidity
        assertEquals(IssuanceDateSource.SOD_SIGNING_TIME, validity.source)
        assertEquals(LocalDate.of(2017, 12, 15), validity.issuanceDate)
        assertEquals(CheckStatus.OK, result.dsValidity.status)
        assertEquals(Verdict.AUTHENTIC, result.verdict())
    }

    @Test
    fun `unreadable DG12 - the restriction on its date does not apply`() {
        val result = verify(cie().withWrongDg12(), dg12DateOfIssue = null)

        assertEquals(setOf(12), result.discardedDataGroups)
        assertEquals(CheckStatus.OK, result.dataGroupHashes.status)
    }

    @Test
    fun `covered document, correct DG12 - no deviation reported`() {
        val result = verify(cie())

        assertEquals(CheckStatus.OK, result.dataGroupHashes.status)
        assertEquals(emptyList<KnownDeviation>(), result.hashes().deviations)
        assertEquals(emptySet<Int>(), result.discardedDataGroups)
        assertEquals(IssuanceDateSource.DG12, (result.dsValidity.detail as CheckDetail.DsValidity).source)
    }

    @Test
    fun `document not covered - Failure`() {
        val outside = cie { sod = SodOptions(signingTime = TestCrypto.instant(LocalDate.of(2018, 3, 6))) }
        val before = cie { sod = SodOptions(signingTime = TestCrypto.instant(LocalDate.of(2017, 8, 31))) }
        val noSigningTime = cie { sod = SodOptions(signingTime = null) }
        val passport = cie { documentCode = "P" }
        val otherNumber = cie { documentNumber = "AB1234567" }
        val cases =
            mapOf(
                "signingTime after the period" to { verify(outside.withWrongDg12()) },
                "signingTime before the period" to { verify(before.withWrongDg12()) },
                "signingTime missing" to { verify(noSigningTime.withWrongDg12()) },
                "passport" to { verify(passport.withWrongDg12()) },
                "number with another format" to { verify(otherNumber.withWrongDg12(), documentNumber = "AB1234567") },
                "DG12 date other than 2015-12-05" to { verify(cie().withWrongDg12(), dg12DateOfIssue = LocalDate.of(2017, 12, 15)) },
                "empty registry" to { verify(cie().withWrongDg12(), deviations = KnownDeviations.NONE) },
            )
        for ((name, run) in cases) {
            val result = run()
            assertEquals(name, CheckStatus.FAILED, result.dataGroupHashes.status)
            assertEquals(name, listOf(12), result.hashes().mismatched)
            assertEquals(name, emptyList<KnownDeviation>(), result.hashes().deviations)
            assertEquals(name, emptySet<Int>(), result.discardedDataGroups)
            assertEquals(name, Verdict.FAILED, result.verdict())
        }
    }

    @Test
    fun `CSCA and state from another country - no tolerance`() {
        val french = TestPki(TestKeyType.RSA, name = "France Test", country = "FR", seed = SEED + 1)
        val ds = french.issueDs(notBefore = LocalDate.of(2017, 6, 1), notAfter = LocalDate.of(2028, 6, 1))
        val document =
            french
                .document {
                    this.ds = ds
                    documentCode = "C"
                    issuingState = "FRA"
                    documentNumber = "CA12345XY"
                    dateOfExpiry = EXPIRY
                    extraDataGroups = mapOf(12 to signedDg12)
                    sod = SodOptions(signingTime = TestCrypto.instant(LocalDate.of(2017, 12, 15)))
                }.withWrongDg12()

        val result = verify(document, trustStore = french.trustStore(french.oldCsca), issuingState = "FRA")

        assertEquals(CheckStatus.OK, result.certificateChain.status)
        assertEquals(CheckStatus.FAILED, result.dataGroupHashes.status)
        assertEquals(emptySet<Int>(), result.discardedDataGroups)
    }

    @Test
    fun `deviation on DG1, DG2, DG14 or DG15 rejected`() {
        for (dataGroup in listOf(1, 2, 3, 14, 15)) {
            assertThrows(IllegalArgumentException::class.java) {
                KnownDeviationRule(KnownDeviation("TEST", dataGroup), "test", LocalDate.of(2020, 1, 1), "00", FAR_FUTURE) { true }
            }
        }
    }

    @Test
    fun `a DG12 deviation covers neither DG1 nor DG2`() {
        val always =
            KnownDeviations(
                listOf(KnownDeviationRule(KnownDeviation("TEST-DG12", 12), "test", LocalDate.of(2020, 1, 1), "00", FAR_FUTURE) { true }),
            )

        val dg2 = verify(cie().withWrongDg12().withModifiedDataGroup(2), deviations = always)
        assertEquals(CheckStatus.FAILED, dg2.dataGroupHashes.status)
        assertEquals(listOf(2), dg2.hashes().mismatched)
        assertEquals(Verdict.FAILED, dg2.verdict())

        // Wrong DG1: its elements are not verified, no tolerance, not even for DG12.
        val dg1 = verify(cie().withWrongDg12().withModifiedDataGroup(1), deviations = always)
        assertEquals(listOf(1, 12), dg1.hashes().mismatched)
        assertEquals(emptySet<Int>(), dg1.discardedDataGroups)
    }

    @Test
    fun `invalid SOD or unknown chain - no tolerance`() {
        val tampered = cie { sod = SodOptions(signingTime = TestCrypto.instant(LocalDate.of(2017, 12, 15)), tamperSignature = true) }
        val badSignature = verify(tampered.withWrongDg12())
        assertEquals(CheckStatus.FAILED, badSignature.sodSignature.status)
        assertEquals(listOf(12), badSignature.hashes().mismatched)
        assertEquals(emptySet<Int>(), badSignature.discardedDataGroups)

        val other = TestPki(TestKeyType.RSA, name = "Autre", country = "IT", seed = SEED + 2)
        val unknownIssuer = verify(cie().withWrongDg12(), trustStore = other.trustStore(other.oldCsca))
        // Audit V25: Italian certificates are known, no chain: failure.
        assertEquals(CheckStatus.FAILED, unknownIssuer.certificateChain.status)
        assertEquals(listOf(12), unknownIssuer.hashes().mismatched)
        assertEquals(emptySet<Int>(), unknownIssuer.discardedDataGroups)
    }

    @Test
    fun `expiry after the obsolescence date or unreadable - no tolerance`() {
        val late = cie { dateOfExpiry = ItalianCie3Dg12.OBSOLETE_AFTER.plusDays(1) }
        val tooLate = verify(late.withWrongDg12())
        assertEquals(CheckStatus.FAILED, tooLate.dataGroupHashes.status)
        assertEquals(emptySet<Int>(), tooLate.discardedDataGroups)

        val lastDay = cie { dateOfExpiry = ItalianCie3Dg12.OBSOLETE_AFTER }
        assertEquals(setOf(12), verify(lastDay.withWrongDg12()).discardedDataGroups)

        val document = cie().withWrongDg12()
        val unreadable =
            PassiveAuthenticator(store()).verify(
                sod = document.sod,
                dataGroups = document.dataGroups,
                dateOfIssue = ItalianCie3Dg12.DG12_DATE_OF_ISSUE,
                dateOfExpiry = null,
                documentCode = "C",
                issuingState = "ITA",
                documentNumber = "CA12345XY",
            )
        assertEquals(CheckStatus.OK, unreadable.sodSignature.status)
        assertEquals(emptySet<Int>(), unreadable.discardedDataGroups)
    }

    @Test
    fun `obsolescence - no rule in the real registry is obsolete`() {
        val messages = obsolescenceMessages(KnownDeviations.REGISTRY, LocalDate.now(ZoneOffset.UTC))
        assertTrue(messages.joinToString("\n"), messages.isEmpty())
    }

    @Test
    fun `obsolescence - an obsolete dummy rule is reported`() {
        val rule =
            KnownDeviationRule(KnownDeviation("TEST-OLD", 12), "test", LocalDate.of(2010, 1, 1), "00", LocalDate.of(2020, 6, 30)) { true }
        val registry = KnownDeviations(listOf(rule))

        assertEquals(emptyList<String>(), obsolescenceMessages(registry, LocalDate.of(2020, 6, 30)))
        assertEquals(
            listOf("Rule TEST-OLD obsolete since 2020-06-30: remove the rule and its decision (D34), see docs/trust-sources.md §2.4"),
            obsolescenceMessages(registry, LocalDate.of(2020, 7, 1)),
        )
    }

    @Test
    fun `real registry - Italian rule pinned to the documented Deviation List`() {
        val rule = KnownDeviations.REGISTRY.rules.single { it.deviation.id == "IT-CIE3-DG12" }
        assertEquals(12, rule.deviation.dataGroup)
        assertEquals(LocalDate.of(2018, 5, 30), rule.listDate)
        assertEquals("1219d8c74a7acf13c55e265fa8125230167160f4f999b64b28644275a084d31d", rule.listSha256)
        assertEquals("https://csca-ita.interno.gov.it/certificatiCSCA/IT_CIE_DeviationList.zip", rule.source)
        // Issuance period from the list (2017-10-01 to 2018-02-05) widened by one month.
        assertEquals(LocalDate.of(2017, 9, 1), ItalianCie3Dg12.SIGNING_TIME_FROM)
        assertEquals(LocalDate.of(2018, 3, 5), ItalianCie3Dg12.SIGNING_TIME_TO)
        // 2018-03-05 + 10 years, extended by at most one year to the holder's birthday.
        assertEquals(LocalDate.of(2029, 3, 5), rule.obsoleteAfter)
        assertTrue(KnownDeviations.REGISTRY.rules.all { it.deviation.dataGroup in setOf(11, 12) })

        val doc = listOf(File("../docs/trust-sources.md"), File("docs/trust-sources.md")).first { it.isFile }.readText()
        assertTrue("TDDL fingerprint missing from docs/trust-sources.md", rule.listSha256 in doc)
    }

    @Test
    fun `safeguard - applicable ignores a non-tolerable DG even if it mismatches`() {
        val registry = KnownDeviations(listOf(ItalianCie3Dg12.RULE))
        val evidence =
            DeviationEvidence(
                cscaCountry = "IT",
                issuingState = "ITA",
                documentCode = "C",
                documentNumber = "CA12345XY",
                dateOfExpiry = EXPIRY,
                sodSigningTime = LocalDate.of(2017, 12, 15),
                dg12DateOfIssue = null,
            )
        assertEquals(emptyList<KnownDeviation>(), registry.applicable(evidence, listOf(1, 2, 14, 15)))
        assertEquals(listOf(ItalianCie3Dg12.DEVIATION), registry.applicable(evidence, listOf(12)))
        assertNull(registry.applicable(evidence, emptyList()).firstOrNull())
    }

    private companion object {
        const val SEED = 0x17A1L
        const val DG12_TAG = 0x6C

        /** Expiry of a CIE issued in late 2017: 10 years, extended to the next birthday. */
        val EXPIRY: LocalDate = LocalDate.of(2028, 12, 15)
        val FAR_FUTURE: LocalDate = LocalDate.of(2100, 1, 1)

        /** Failure messages of the obsolescence test for [registry] on [today]. */
        fun obsolescenceMessages(
            registry: KnownDeviations,
            today: LocalDate,
        ): List<String> =
            registry.obsoleteRules(today).map {
                "Rule ${it.deviation.id} obsolete since ${it.obsoleteAfter}: remove the rule and its decision (D34), " +
                    "see docs/trust-sources.md §2.4"
            }
    }
}
