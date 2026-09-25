package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.IssuanceDateSource
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.trust.TrustSource
import io.github.mgdx.sceau.core.trust.TrustStore
import io.github.mgdx.sceau.testchip.AaKeyType
import io.github.mgdx.sceau.testchip.CscaGeneration
import io.github.mgdx.sceau.testchip.SodOptions
import io.github.mgdx.sceau.testchip.TestCrypto
import io.github.mgdx.sceau.testchip.TestDocument
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import io.github.mgdx.sceau.testchip.TestSod
import io.github.mgdx.sceau.testchip.TestTrustStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.math.BigInteger
import java.security.MessageDigest
import java.time.LocalDate

/** Passive Authentication (SPEC §6.1 étape 5, §9.1), pour chaque type de clé de la hiérarchie. */
@RunWith(Parameterized::class)
class PassiveAuthenticationTest(
    private val keyType: TestKeyType,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun keyTypes(): List<TestKeyType> = TestKeyType.entries
    }

    private val pki = TestPki(keyType)

    private fun verify(
        document: TestDocument,
        store: TrustStore = pki.trustStore(pki.oldCsca),
        dateOfIssue: LocalDate? = document.dateOfIssue,
        dateOfExpiry: LocalDate? = document.dateOfExpiry,
        sod: ByteArray = document.sod,
    ): PassiveAuthResult =
        PassiveAuthentication.verify(
            sod = sod,
            dataGroups = document.dataGroups,
            trustStore = store,
            dateOfIssue = dateOfIssue,
            dateOfExpiry = dateOfExpiry,
            documentCode = document.documentCode,
        )

    private fun PassiveAuthResult.statuses() =
        listOf(sodSignature.status, certificateChain.status, dsValidity.status, dataGroupHashes.status)

    @Test
    fun `chaine nominale - PA entierement OK`() {
        val result = verify(pki.document())

        assertEquals(List(4) { CheckStatus.OK }, result.statuses())
        val chain = result.chain!!
        assertEquals(pki.oldCsca.certificate.subjectX500Principal.name, chain.cscaSubject)
        assertEquals("FR", chain.cscaCountry)
        assertEquals(sha256(pki.oldCsca.certificate.encoded), chain.cscaSha256)
        assertEquals(TrustSource.ANTS, chain.cscaSource)
        assertEquals(emptyList<String>(), chain.linkCertificates)
        assertEquals(pki.ds.certificate.subjectX500Principal.name, chain.dsSubject)
        assertEquals(
            pki.ds.certificate.serialNumber
                .toString(16)
                .uppercase(),
            chain.dsSerialNumber,
        )
        assertEquals(CheckDetail.Chain(chain), result.certificateChain.detail)
        assertTrue(result.sodSignature.detail is CheckDetail.Signature)
        assertEquals(CheckDetail.DataGroupHashes("SHA256", listOf(1, 2, 14, 15), emptyList()), result.dataGroupHashes.detail)
        val validity = result.dsValidity.detail as CheckDetail.DsValidity
        assertEquals(IssuanceDateSource.DG12, validity.source)
        assertEquals(TestDocument.DEFAULT_DATE_OF_ISSUE, validity.issuanceDate)
    }

    @Test
    fun `algorithme de signature declare - PKCS1, PSS ou ECDSA selon le type`() {
        val algorithm = (verify(pki.document()).sodSignature.detail as CheckDetail.Signature).algorithm.uppercase()
        val expected =
            when (keyType) {
                TestKeyType.RSA -> "SHA256WITHRSA"
                TestKeyType.RSA_PSS -> "SHA256WITHRSAANDMGF1"
                TestKeyType.EC, TestKeyType.EC_EXPLICIT -> "SHA256WITHECDSA"
            }
        assertEquals(expected, algorithm)
    }

    @Test
    fun `chaine via certificat de lien - DS signe par le nouveau CSCA absent du magasin`() {
        val ds = pki.issueDs(signedBy = CscaGeneration.NEW)
        val result = verify(pki.document { this.ds = ds }, store = pki.trustStore(pki.oldCsca, pki.link))

        assertEquals(CheckStatus.OK, result.certificateChain.status)
        val chain = result.chain!!
        assertEquals(listOf(pki.link.certificate.subjectX500Principal.name), chain.linkCertificates)
        assertEquals(sha256(pki.oldCsca.certificate.encoded), chain.cscaSha256)
    }

    @Test
    fun `nouveau CSCA present dans le magasin - pas de lien traverse`() {
        val ds = pki.issueDs(signedBy = CscaGeneration.NEW)
        val result = verify(pki.document { this.ds = ds }, store = pki.trustStore(pki.oldCsca, pki.link, pki.newCsca))

        assertEquals(CheckStatus.OK, result.certificateChain.status)
        assertEquals(emptyList<String>(), result.chain!!.linkCertificates)
        assertEquals(sha256(pki.newCsca.certificate.encoded), result.chain!!.cscaSha256)
    }

    @Test
    fun `lien absent - meme nom de CSCA mais autre cle - emetteur inconnu`() {
        val ds = pki.issueDs(signedBy = CscaGeneration.NEW)
        val result = verify(pki.document { this.ds = ds }, store = pki.trustStore(pki.oldCsca))

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
        assertNull(result.chain!!.cscaSubject)
    }

    @Test
    fun `signature du SOD alteree - echec`() {
        val result = verify(pki.document { sod = SodOptions(tamperSignature = true) })

        assertEquals(CheckStatus.FAILED, result.sodSignature.status)
        assertEquals(CheckStatus.OK, result.certificateChain.status)
        assertEquals(CheckStatus.OK, result.dataGroupHashes.status)
    }

    @Test
    fun `DG2 modifie apres signature - echec sur l empreinte`() {
        val result = verify(pki.document().withModifiedDataGroup(2))

        assertEquals(CheckStatus.FAILED, result.dataGroupHashes.status)
        assertEquals(listOf(2), (result.dataGroupHashes.detail as CheckDetail.DataGroupHashes).mismatched)
        assertEquals(CheckStatus.OK, result.sodSignature.status)
    }

    @Test
    fun `DG lu absent du SOD - non conforme, DG du SOD non lus ignores`() {
        val document = pki.document { sod = SodOptions(extraHashes = mapOf(3 to ByteArray(32))) }
        val result = verify(document.withDataGroups(document.dataGroups + (11 to byteArrayOf(0x6B, 0x00))))

        val detail = result.dataGroupHashes.detail as CheckDetail.DataGroupHashes
        assertEquals(listOf(1, 2, 11, 14, 15), detail.checked)
        assertEquals(listOf(11), detail.mismatched)
        assertEquals(CheckStatus.FAILED, result.dataGroupHashes.status)
    }

    @Test
    fun `DG du SOD non fournis par la puce - echec, DG manquants signales`() {
        val document = pki.document()
        val result = verify(document.withDataGroups(document.dataGroups - setOf(2, 14, 15)))

        val detail = result.dataGroupHashes.detail as CheckDetail.DataGroupHashes
        assertEquals(CheckStatus.FAILED, result.dataGroupHashes.status)
        assertEquals(listOf(1), detail.checked)
        assertEquals(emptyList<Int>(), detail.mismatched)
        assertEquals(listOf(2, 14, 15), detail.missing)
        assertEquals(Verdict.FAILED, Verdicts.compute(VerifyTestSupport.checks(result)))
    }

    @Test
    fun `DG3 et DG4 du SOD jamais lus - pas des DG manquants`() {
        val document = pki.document { sod = SodOptions(extraHashes = mapOf(3 to ByteArray(32), 4 to ByteArray(32))) }
        val result = verify(document)

        assertEquals(CheckStatus.OK, result.dataGroupHashes.status)
        assertEquals(emptyList<Int>(), (result.dataGroupHashes.detail as CheckDetail.DataGroupHashes).missing)
    }

    @Test
    fun `DS expire avant la date de delivrance DG12 - echec sur la validite`() {
        val ds = pki.issueDs(notBefore = LocalDate.of(2015, 1, 1), notAfter = LocalDate.of(2019, 1, 1))
        val result = verify(pki.document { this.ds = ds })

        assertEquals(CheckStatus.FAILED, result.dsValidity.status)
        assertEquals(CheckStatus.OK, result.sodSignature.status)
        assertEquals(CheckStatus.OK, result.certificateChain.status)
    }

    @Test
    fun `DS expire avant le signingTime du SOD - echec sur la validite`() {
        val ds = pki.issueDs(notBefore = LocalDate.of(2015, 1, 1), notAfter = LocalDate.of(2019, 1, 1))
        val result = verify(pki.document { this.ds = ds }, dateOfIssue = null)

        assertEquals(CheckStatus.FAILED, result.dsValidity.status)
        assertEquals(IssuanceDateSource.SOD_SIGNING_TIME, (result.dsValidity.detail as CheckDetail.DsValidity).source)
    }

    @Test
    fun `date estimee hors periode du DS - non disponible, jamais echec`() {
        val ds = pki.issueDs(notBefore = LocalDate.of(2024, 1, 1), notAfter = LocalDate.of(2035, 1, 1))
        val document =
            pki.document {
                this.ds = ds
                sod = SodOptions(signingTime = null)
            }
        val result = verify(document, dateOfIssue = null)

        assertEquals(CheckStatus.NOT_AVAILABLE, result.dsValidity.status)
        val detail = result.dsValidity.detail as CheckDetail.DsValidity
        assertEquals(IssuanceDateSource.ESTIMATED, detail.source)
        assertEquals(TestDocument.DEFAULT_DATE_OF_EXPIRY.minusYears(10), detail.issuanceDate)
    }

    @Test
    fun `date estimee dans la periode du DS - OK`() {
        val result = verify(pki.document { sod = SodOptions(signingTime = null) }, dateOfIssue = null)

        assertEquals(CheckStatus.OK, result.dsValidity.status)
        assertEquals(IssuanceDateSource.ESTIMATED, (result.dsValidity.detail as CheckDetail.DsValidity).source)
    }

    @Test
    fun `aucune date - validite non disponible`() {
        val result = verify(pki.document { sod = SodOptions(signingTime = null) }, dateOfIssue = null, dateOfExpiry = null)

        assertEquals(CheckStatus.NOT_AVAILABLE, result.dsValidity.status)
    }

    @Test
    fun `DS non rattache a un CSCA connu - emetteur inconnu`() {
        val rogue = TestPki(keyType, name = "Rogue", country = "XX")
        val result = verify(rogue.document(), store = pki.trustStore(pki.oldCsca, pki.newCsca, pki.link))

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
        assertEquals(CheckStatus.OK, result.sodSignature.status)
        val chain = result.chain!!
        assertNull(chain.cscaSubject)
        assertNull(chain.cscaCountry)
        assertNull(chain.cscaSha256)
        assertNull(chain.cscaSource)
        assertEquals(Verdict.UNKNOWN_ISSUER, Verdicts.compute(VerifyTestSupport.checks(result)))
    }

    @Test
    fun `signature du DS par le CSCA invalide - chaine en echec`() {
        val ds = pki.withTamperedSignature(pki.issueDs())
        val result = verify(pki.document { this.ds = ds })

        assertEquals(CheckStatus.FAILED, result.certificateChain.status)
        assertEquals(CheckStatus.OK, result.sodSignature.status)
        assertNotNull(result.chain!!.cscaSubject)
    }

    @Test
    fun `algorithme de signature du SOD inconnu - UnsupportedAlgorithm`() {
        val result = verify(pki.document { sod = SodOptions(signatureAlgorithmOid = TestSod.UNKNOWN_OID) })

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, result.sodSignature.status)
        assertEquals(CheckDetail.UnsupportedAlgorithm(TestSod.UNKNOWN_OID), result.sodSignature.detail)
        assertEquals(Verdict.FAILED, Verdicts.compute(VerifyTestSupport.checks(result)))
    }

    @Test
    fun `algorithme de hachage des DG inconnu - UnsupportedAlgorithm`() {
        val result = verify(pki.document { sod = SodOptions(declaredDigestOid = TestSod.UNKNOWN_OID) })

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, result.dataGroupHashes.status)
        assertEquals(CheckDetail.UnsupportedAlgorithm(TestSod.UNKNOWN_OID), result.dataGroupHashes.detail)
    }

    @Test
    fun `empreintes SHA-512 et signingTime absent`() {
        val result = verify(pki.document { sod = SodOptions(digestAlgorithm = "SHA-512", signingTime = null) })

        assertEquals(List(4) { CheckStatus.OK }, result.statuses())
        assertEquals("SHA512", (result.dataGroupHashes.detail as CheckDetail.DataGroupHashes).digestAlgorithm)
    }

    @Test
    fun `DS absent du SOD mais present dans le magasin - OK`() {
        val result = verify(pki.document { sod = SodOptions(embedDs = false) }, store = pki.trustStore(pki.oldCsca, pki.ds))

        assertEquals(List(4) { CheckStatus.OK }, result.statuses())
        assertEquals(pki.ds.certificate.subjectX500Principal.name, result.chain!!.dsSubject)
    }

    @Test
    fun `DS absent du SOD et du magasin - DsCertificateMissing`() {
        val result = verify(pki.document { sod = SodOptions(embedDs = false) })

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
        assertEquals(CheckDetail.DsCertificateMissing, result.certificateChain.detail)
        assertEquals(CheckStatus.NOT_AVAILABLE, result.sodSignature.status)
        assertEquals(CheckStatus.OK, result.dataGroupHashes.status)
        assertNull(result.chain)
        assertEquals(Verdict.UNKNOWN_ISSUER, Verdicts.compute(VerifyTestSupport.checks(result)))
    }

    @Test
    fun `numero de serie de DS negatif - embarque ou retrouve dans le magasin`() {
        val ds = pki.issueDs(serial = BigInteger.valueOf(-4_242_424_242L))
        val embedded = verify(pki.document { this.ds = ds })
        val fromStore =
            verify(
                pki.document {
                    this.ds = ds
                    sod = SodOptions(embedDs = false)
                },
                store = pki.trustStore(pki.oldCsca, ds),
            )

        assertEquals(List(4) { CheckStatus.OK }, embedded.statuses())
        assertEquals(List(4) { CheckStatus.OK }, fromStore.statuses())
        assertEquals(
            ds.certificate.serialNumber
                .toString(16)
                .uppercase(),
            embedded.chain!!.dsSerialNumber,
        )
        assertTrue(embedded.chain!!.dsSerialNumber.startsWith("-"))
    }

    @Test
    fun `SOD mal forme - aucune exception, lignes en echec`() {
        val document = pki.document()
        for (sod in listOf(
            ByteArray(0),
            byteArrayOf(0x77, 0x05, 0x30),
            document.sod.copyOf(document.sod.size / 2),
            ByteArray(64) { 0x77 },
        )) {
            val result = verify(document, sod = sod)
            assertEquals(List(4) { CheckStatus.FAILED }, result.statuses())
            assertTrue(result.sodSignature.detail is CheckDetail.Error)
        }
    }

    @Test
    fun `source du CSCA reportee dans la chaine`() {
        val store = TestTrustStore.of(listOf(pki.oldCsca.certificate), TrustSource.IMPORTED_MASTER_LIST)
        val result = verify(pki.document(), store = store)

        assertEquals(TrustSource.IMPORTED_MASTER_LIST, result.chain!!.cscaSource)
    }

    @Test
    fun `cycle de liens dans le magasin - pas de boucle infinie`() {
        // Lien et lien inverse, sans aucun CSCA auto-signé : la recherche doit s'arrêter.
        val ds = pki.issueDs(signedBy = CscaGeneration.NEW)
        val result = verify(pki.document { this.ds = ds }, store = pki.trustStore(pki.link, pki.reverseLink))

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
    }

    @Test
    fun `document sans DG14 ni DG15 - signature valide, puce non verifiee`() {
        val document =
            pki.document {
                chipAuthentication = false
                activeAuthentication = null
            }
        val result = verify(document)

        assertEquals(listOf(1, 2), (result.dataGroupHashes.detail as CheckDetail.DataGroupHashes).checked)
        assertEquals(Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED, Verdicts.compute(VerifyTestSupport.checks(result)))
    }

    @Test
    fun `chaine nominale avec AA puis CA - authentique`() {
        for (aa in AaKeyType.entries) {
            val document = pki.document { activeAuthentication = aa }
            val result = verify(document)
            val challenge = ByteArray(8).also { TestCrypto.seededRandom(7).nextBytes(it) }
            val activeAuth =
                ActiveAuthentication.verifyResponse(
                    document.aaKeyPair!!.public,
                    document.aaDigestAlgorithm,
                    challenge,
                    document.aaResponse(challenge),
                )

            assertEquals(CheckStatus.OK, activeAuth.status)
            assertEquals(Verdict.AUTHENTIC, Verdicts.compute(VerifyTestSupport.checks(result, aa = activeAuth)))
            assertEquals(
                Verdict.AUTHENTIC,
                Verdicts.compute(VerifyTestSupport.checks(result, ca = CheckStatus.OK, aa = VerifyTestSupport.notAvailableAa)),
            )
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
