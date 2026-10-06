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
 * Anomalies connues des émetteurs (décision D34) : tolérance d'un écart d'empreinte sur DG11 ou
 * DG12, jamais ailleurs, seulement sur un SOD dont la signature et la chaîne sont valides.
 */
class KnownDeviationsTest {
    private val pki = TestPki(TestKeyType.RSA, name = "Italia Test", country = "IT", seed = SEED)

    /** DS en service pendant la période de la Deviation List italienne. */
    private val ds by lazy { pki.issueDs(notBefore = LocalDate.of(2017, 6, 1), notAfter = LocalDate.of(2028, 6, 1)) }

    private fun store(): TrustStore = pki.trustStore(pki.oldCsca)

    /** CIE 3.0 factice couverte par le critère italien, DG12 signé tel que [signedDg12]. */
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
    fun `document couvert, DG12 faux - ecart tolere, DG12 ecarte, verdict Authentique`() {
        val result = verify(cie().withWrongDg12())

        assertEquals(CheckStatus.OK, result.dataGroupHashes.status)
        assertEquals(emptyList<Int>(), result.hashes().mismatched)
        assertEquals(listOf(KnownDeviation("IT-CIE3-DG12", 12)), result.hashes().deviations)
        assertTrue(12 in result.hashes().checked)
        assertEquals(setOf(12), result.discardedDataGroups)
        // La date de délivrance de DG12 écarté n'est pas utilisée : repli sur signingTime.
        val validity = result.dsValidity.detail as CheckDetail.DsValidity
        assertEquals(IssuanceDateSource.SOD_SIGNING_TIME, validity.source)
        assertEquals(LocalDate.of(2017, 12, 15), validity.issuanceDate)
        assertEquals(CheckStatus.OK, result.dsValidity.status)
        assertEquals(Verdict.AUTHENTIC, result.verdict())
    }

    @Test
    fun `DG12 illisible - la restriction sur sa date ne joue pas`() {
        val result = verify(cie().withWrongDg12(), dg12DateOfIssue = null)

        assertEquals(setOf(12), result.discardedDataGroups)
        assertEquals(CheckStatus.OK, result.dataGroupHashes.status)
    }

    @Test
    fun `document couvert, DG12 correct - aucune deviation signalee`() {
        val result = verify(cie())

        assertEquals(CheckStatus.OK, result.dataGroupHashes.status)
        assertEquals(emptyList<KnownDeviation>(), result.hashes().deviations)
        assertEquals(emptySet<Int>(), result.discardedDataGroups)
        assertEquals(IssuanceDateSource.DG12, (result.dsValidity.detail as CheckDetail.DsValidity).source)
    }

    @Test
    fun `document non couvert - Echec`() {
        val outside = cie { sod = SodOptions(signingTime = TestCrypto.instant(LocalDate.of(2018, 3, 6))) }
        val before = cie { sod = SodOptions(signingTime = TestCrypto.instant(LocalDate.of(2017, 8, 31))) }
        val noSigningTime = cie { sod = SodOptions(signingTime = null) }
        val passport = cie { documentCode = "P" }
        val otherNumber = cie { documentNumber = "AB1234567" }
        val cases =
            mapOf(
                "signingTime après la période" to { verify(outside.withWrongDg12()) },
                "signingTime avant la période" to { verify(before.withWrongDg12()) },
                "signingTime absent" to { verify(noSigningTime.withWrongDg12()) },
                "passeport" to { verify(passport.withWrongDg12()) },
                "numéro d'une autre forme" to { verify(otherNumber.withWrongDg12(), documentNumber = "AB1234567") },
                "date de DG12 autre que 2015-12-05" to { verify(cie().withWrongDg12(), dg12DateOfIssue = LocalDate.of(2017, 12, 15)) },
                "registre vide" to { verify(cie().withWrongDg12(), deviations = KnownDeviations.NONE) },
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
    fun `CSCA et Etat d'un autre pays - pas de tolerance`() {
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
    fun `deviation sur DG1, DG2, DG14 ou DG15 refusee`() {
        for (dataGroup in listOf(1, 2, 3, 14, 15)) {
            assertThrows(IllegalArgumentException::class.java) {
                KnownDeviationRule(KnownDeviation("TEST", dataGroup), "test", LocalDate.of(2020, 1, 1), "00", FAR_FUTURE) { true }
            }
        }
    }

    @Test
    fun `une deviation DG12 ne couvre ni DG1 ni DG2`() {
        val always =
            KnownDeviations(
                listOf(KnownDeviationRule(KnownDeviation("TEST-DG12", 12), "test", LocalDate.of(2020, 1, 1), "00", FAR_FUTURE) { true }),
            )

        val dg2 = verify(cie().withWrongDg12().withModifiedDataGroup(2), deviations = always)
        assertEquals(CheckStatus.FAILED, dg2.dataGroupHashes.status)
        assertEquals(listOf(2), dg2.hashes().mismatched)
        assertEquals(Verdict.FAILED, dg2.verdict())

        // DG1 faux : ses éléments ne sont pas vérifiés, aucune tolérance, même pour DG12.
        val dg1 = verify(cie().withWrongDg12().withModifiedDataGroup(1), deviations = always)
        assertEquals(listOf(1, 12), dg1.hashes().mismatched)
        assertEquals(emptySet<Int>(), dg1.discardedDataGroups)
    }

    @Test
    fun `SOD invalide ou chaine inconnue - aucune tolerance`() {
        val tampered = cie { sod = SodOptions(signingTime = TestCrypto.instant(LocalDate.of(2017, 12, 15)), tamperSignature = true) }
        val badSignature = verify(tampered.withWrongDg12())
        assertEquals(CheckStatus.FAILED, badSignature.sodSignature.status)
        assertEquals(listOf(12), badSignature.hashes().mismatched)
        assertEquals(emptySet<Int>(), badSignature.discardedDataGroups)

        val other = TestPki(TestKeyType.RSA, name = "Autre", country = "IT", seed = SEED + 2)
        val unknownIssuer = verify(cie().withWrongDg12(), trustStore = other.trustStore(other.oldCsca))
        // Audit V25 : des certificats italiens sont connus, aucune chaîne : échec.
        assertEquals(CheckStatus.FAILED, unknownIssuer.certificateChain.status)
        assertEquals(listOf(12), unknownIssuer.hashes().mismatched)
        assertEquals(emptySet<Int>(), unknownIssuer.discardedDataGroups)
    }

    @Test
    fun `expiration au-dela de la peremption ou illisible - pas de tolerance`() {
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
    fun `peremption - aucune regle du registre reel n'est perimee`() {
        val messages = obsolescenceMessages(KnownDeviations.REGISTRY, LocalDate.now(ZoneOffset.UTC))
        assertTrue(messages.joinToString("\n"), messages.isEmpty())
    }

    @Test
    fun `peremption - une regle factice perimee est signalee`() {
        val rule =
            KnownDeviationRule(KnownDeviation("TEST-OLD", 12), "test", LocalDate.of(2010, 1, 1), "00", LocalDate.of(2020, 6, 30)) { true }
        val registry = KnownDeviations(listOf(rule))

        assertEquals(emptyList<String>(), obsolescenceMessages(registry, LocalDate.of(2020, 6, 30)))
        assertEquals(
            listOf("Règle TEST-OLD périmée depuis 2020-06-30 : supprimer la règle et sa décision (D34), voir docs/trust-sources.md §2.4"),
            obsolescenceMessages(registry, LocalDate.of(2020, 7, 1)),
        )
    }

    @Test
    fun `registre reel - regle italienne fixee sur la Deviation List documentee`() {
        val rule = KnownDeviations.REGISTRY.rules.single { it.deviation.id == "IT-CIE3-DG12" }
        assertEquals(12, rule.deviation.dataGroup)
        assertEquals(LocalDate.of(2018, 5, 30), rule.listDate)
        assertEquals("1219d8c74a7acf13c55e265fa8125230167160f4f999b64b28644275a084d31d", rule.listSha256)
        assertEquals("https://csca-ita.interno.gov.it/certificatiCSCA/IT_CIE_DeviationList.zip", rule.source)
        // Période de délivrance de la liste (2017-10-01 au 2018-02-05) élargie d'un mois.
        assertEquals(LocalDate.of(2017, 9, 1), ItalianCie3Dg12.SIGNING_TIME_FROM)
        assertEquals(LocalDate.of(2018, 3, 5), ItalianCie3Dg12.SIGNING_TIME_TO)
        // 2018-03-05 + 10 ans, reporté au plus d'un an jusqu'à l'anniversaire du titulaire.
        assertEquals(LocalDate.of(2029, 3, 5), rule.obsoleteAfter)
        assertTrue(KnownDeviations.REGISTRY.rules.all { it.deviation.dataGroup in setOf(11, 12) })

        val doc = listOf(File("../docs/trust-sources.md"), File("docs/trust-sources.md")).first { it.isFile }.readText()
        assertTrue("empreinte du TDDL absente de docs/trust-sources.md", rule.listSha256 in doc)
    }

    @Test
    fun `garde-fou - applicable ignore un DG non tolerable meme s'il est en ecart`() {
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

        /** Expiration d'une CIE délivrée fin 2017 : 10 ans, reportée à l'anniversaire suivant. */
        val EXPIRY: LocalDate = LocalDate.of(2028, 12, 15)
        val FAR_FUTURE: LocalDate = LocalDate.of(2100, 1, 1)

        /** Messages d'échec du test de péremption pour [registry] à la date [today]. */
        fun obsolescenceMessages(
            registry: KnownDeviations,
            today: LocalDate,
        ): List<String> =
            registry.obsoleteRules(today).map {
                "Règle ${it.deviation.id} périmée depuis ${it.obsoleteAfter} : supprimer la règle et sa décision (D34), " +
                    "voir docs/trust-sources.md §2.4"
            }
    }
}
