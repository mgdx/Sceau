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

/** Passive Authentication (SPEC §6.1 step 5, §9.1), for each key type of the hierarchy. */
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
        issuingState: String? = null,
    ): PassiveAuthResult =
        PassiveAuthentication.verify(
            sod = sod,
            dataGroups = document.dataGroups,
            trustStore = store,
            dateOfIssue = dateOfIssue,
            dateOfExpiry = dateOfExpiry,
            documentCode = document.documentCode,
            issuingState = issuingState,
        )

    private fun PassiveAuthResult.statuses() =
        listOf(sodSignature.status, certificateChain.status, dsValidity.status, dataGroupHashes.status)

    @Test
    fun `nominal chain - PA fully OK`() {
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
    fun `declared signature algorithm - PKCS1, PSS or ECDSA depending on the type`() {
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
    fun `chain through a link certificate - DS signed by the new CSCA absent from the store`() {
        val ds = pki.issueDs(signedBy = CscaGeneration.NEW)
        val result = verify(pki.document { this.ds = ds }, store = pki.trustStore(pki.oldCsca, pki.link))

        assertEquals(CheckStatus.OK, result.certificateChain.status)
        val chain = result.chain!!
        assertEquals(listOf(pki.link.certificate.subjectX500Principal.name), chain.linkCertificates)
        assertEquals(sha256(pki.oldCsca.certificate.encoded), chain.cscaSha256)
    }

    @Test
    fun `new CSCA present in the store - no link traversed`() {
        val ds = pki.issueDs(signedBy = CscaGeneration.NEW)
        val result = verify(pki.document { this.ds = ds }, store = pki.trustStore(pki.oldCsca, pki.link, pki.newCsca))

        assertEquals(CheckStatus.OK, result.certificateChain.status)
        assertEquals(emptyList<String>(), result.chain!!.linkCertificates)
        assertEquals(sha256(pki.newCsca.certificate.encoded), result.chain!!.cscaSha256)
    }

    @Test
    fun `missing link - same CSCA name but another key - unknown issuer`() {
        val ds = pki.issueDs(signedBy = CscaGeneration.NEW)
        val result = verify(pki.document { this.ds = ds }, store = pki.trustStore(pki.oldCsca))

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
        assertNull(result.chain!!.cscaSubject)
    }

    @Test
    fun `tampered SOD signature - failure`() {
        val result = verify(pki.document { sod = SodOptions(tamperSignature = true) })

        assertEquals(CheckStatus.FAILED, result.sodSignature.status)
        assertEquals(CheckStatus.OK, result.certificateChain.status)
        assertEquals(CheckStatus.OK, result.dataGroupHashes.status)
    }

    @Test
    fun `DG2 modified after signing - hash failure`() {
        val result = verify(pki.document().withModifiedDataGroup(2))

        assertEquals(CheckStatus.FAILED, result.dataGroupHashes.status)
        assertEquals(listOf(2), (result.dataGroupHashes.detail as CheckDetail.DataGroupHashes).mismatched)
        assertEquals(CheckStatus.OK, result.sodSignature.status)
    }

    @Test
    fun `read DG missing from the SOD - non-compliant, unread SOD DGs ignored`() {
        val document = pki.document { sod = SodOptions(extraHashes = mapOf(3 to ByteArray(32))) }
        val result = verify(document.withDataGroups(document.dataGroups + (11 to byteArrayOf(0x6B, 0x00))))

        val detail = result.dataGroupHashes.detail as CheckDetail.DataGroupHashes
        assertEquals(listOf(1, 2, 11, 14, 15), detail.checked)
        assertEquals(listOf(11), detail.mismatched)
        assertEquals(CheckStatus.FAILED, result.dataGroupHashes.status)
    }

    @Test
    fun `SOD DGs not supplied by the chip - failure, missing DGs reported`() {
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
    fun `DG3 and DG4 from the SOD never read - not missing DGs`() {
        val document = pki.document { sod = SodOptions(extraHashes = mapOf(3 to ByteArray(32), 4 to ByteArray(32))) }
        val result = verify(document)

        assertEquals(CheckStatus.OK, result.dataGroupHashes.status)
        assertEquals(emptyList<Int>(), (result.dataGroupHashes.detail as CheckDetail.DataGroupHashes).missing)
    }

    @Test
    fun `issuing state consistent with the CSCA - chain OK`() {
        val document = pki.document { issuingState = "FRA" }
        val result = verify(document, issuingState = "FRA")

        assertEquals(List(4) { CheckStatus.OK }, result.statuses())
        assertTrue(result.certificateChain.detail is CheckDetail.Chain)
    }

    @Test
    fun `issuing state from a country other than the CSCA - chain fails`() {
        val result = verify(pki.document { issuingState = "DEU" }, issuingState = "DEU")

        assertEquals(CheckStatus.FAILED, result.certificateChain.status)
        assertEquals(CheckDetail.CountryMismatch("FR", "FR", "DEU"), result.certificateChain.detail)
        assertEquals("FR", result.chain!!.cscaCountry)
        assertEquals(CheckStatus.OK, result.sodSignature.status)
        assertEquals(Verdict.FAILED, Verdicts.compute(VerifyTestSupport.checks(result, ca = CheckStatus.OK)))
    }

    @Test
    fun `issuing state unknown to ICAO - chain fails`() {
        val result = verify(pki.document(), issuingState = "UTO")

        assertEquals(CheckStatus.FAILED, result.certificateChain.status)
        assertEquals(CheckDetail.CountryMismatch("FR", "FR", "UTO"), result.certificateChain.detail)
    }

    @Test
    fun `international organization - only CSCA and DS compared`() {
        val result = verify(pki.document { issuingState = "UNO" }, issuingState = "UNO")

        assertEquals(CheckStatus.OK, result.certificateChain.status)
    }

    @Test
    fun `DS from a country other than its CSCA - chain fails`() {
        val ds = pki.issueDs(dsCountry = "DE")
        val result = verify(pki.document { this.ds = ds }, issuingState = null)

        assertEquals(CheckStatus.FAILED, result.certificateChain.status)
        assertEquals(CheckDetail.CountryMismatch("FR", "DE", null), result.certificateChain.detail)
    }

    @Test
    fun `DS expired before the DG12 date of issue - validity failure`() {
        val ds = pki.issueDs(notBefore = LocalDate.of(2015, 1, 1), notAfter = LocalDate.of(2019, 1, 1))
        val result = verify(pki.document { this.ds = ds })

        assertEquals(CheckStatus.FAILED, result.dsValidity.status)
        assertEquals(CheckStatus.OK, result.sodSignature.status)
        assertEquals(CheckStatus.OK, result.certificateChain.status)
    }

    @Test
    fun `DS expired before the SOD signingTime - validity failure`() {
        val ds = pki.issueDs(notBefore = LocalDate.of(2015, 1, 1), notAfter = LocalDate.of(2019, 1, 1))
        val result = verify(pki.document { this.ds = ds }, dateOfIssue = null)

        assertEquals(CheckStatus.FAILED, result.dsValidity.status)
        assertEquals(IssuanceDateSource.SOD_SIGNING_TIME, (result.dsValidity.detail as CheckDetail.DsValidity).source)
    }

    @Test
    fun `estimated date outside the DS period - not available, never failure`() {
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
    fun `estimated date within the DS period - OK`() {
        val result = verify(pki.document { sod = SodOptions(signingTime = null) }, dateOfIssue = null)

        assertEquals(CheckStatus.OK, result.dsValidity.status)
        assertEquals(IssuanceDateSource.ESTIMATED, (result.dsValidity.detail as CheckDetail.DsValidity).source)
    }

    @Test
    fun `no date - validity not available`() {
        val result = verify(pki.document { sod = SodOptions(signingTime = null) }, dateOfIssue = null, dateOfExpiry = null)

        assertEquals(CheckStatus.NOT_AVAILABLE, result.dsValidity.status)
    }

    @Test
    fun `DS not linked to a known CSCA - unknown issuer`() {
        val rogue = TestPki(keyType, name = "Rogue", country = "XX")
        val result = verify(rogue.document(), store = pki.trustStore(pki.oldCsca, pki.newCsca, pki.link))

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
        // Audit V25: without a chain, the SOD signature is not presented as verified.
        assertEquals(CheckStatus.NOT_AVAILABLE, result.sodSignature.status)
        val chain = result.chain!!
        assertNull(chain.cscaSubject)
        assertNull(chain.cscaCountry)
        assertNull(chain.cscaSha256)
        assertNull(chain.cscaSource)
        assertEquals(Verdict.UNKNOWN_ISSUER, Verdicts.compute(VerifyTestSupport.checks(result)))
    }

    @Test
    fun `invalid DS signature by the CSCA - chain fails`() {
        val ds = pki.withTamperedSignature(pki.issueDs())
        val result = verify(pki.document { this.ds = ds })

        assertEquals(CheckStatus.FAILED, result.certificateChain.status)
        assertEquals(CheckStatus.OK, result.sodSignature.status)
        assertNotNull(result.chain!!.cscaSubject)
    }

    @Test
    fun `unknown SOD signature algorithm - UnsupportedAlgorithm`() {
        val result = verify(pki.document { sod = SodOptions(signatureAlgorithmOid = TestSod.UNKNOWN_OID) })

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, result.sodSignature.status)
        assertEquals(CheckDetail.UnsupportedAlgorithm(TestSod.UNKNOWN_OID), result.sodSignature.detail)
        assertEquals(Verdict.FAILED, Verdicts.compute(VerifyTestSupport.checks(result)))
    }

    @Test
    fun `unknown DG hash algorithm - UnsupportedAlgorithm`() {
        val result = verify(pki.document { sod = SodOptions(declaredDigestOid = TestSod.UNKNOWN_OID) })

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, result.dataGroupHashes.status)
        assertEquals(CheckDetail.UnsupportedAlgorithm(TestSod.UNKNOWN_OID), result.dataGroupHashes.detail)
    }

    @Test
    fun `SHA-512 hashes and missing signingTime`() {
        val result = verify(pki.document { sod = SodOptions(digestAlgorithm = "SHA-512", signingTime = null) })

        assertEquals(List(4) { CheckStatus.OK }, result.statuses())
        assertEquals("SHA512", (result.dataGroupHashes.detail as CheckDetail.DataGroupHashes).digestAlgorithm)
    }

    @Test
    fun `DS missing from the SOD but present in the store - OK`() {
        val result = verify(pki.document { sod = SodOptions(embedDs = false) }, store = pki.trustStore(pki.oldCsca, pki.ds))

        assertEquals(List(4) { CheckStatus.OK }, result.statuses())
        assertEquals(pki.ds.certificate.subjectX500Principal.name, result.chain!!.dsSubject)
    }

    @Test
    fun `DS missing from the SOD and the store - DsCertificateMissing`() {
        val result = verify(pki.document { sod = SodOptions(embedDs = false) })

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
        assertEquals(CheckDetail.DsCertificateMissing, result.certificateChain.detail)
        assertEquals(CheckStatus.NOT_AVAILABLE, result.sodSignature.status)
        assertEquals(CheckStatus.OK, result.dataGroupHashes.status)
        assertNull(result.chain)
        assertEquals(Verdict.UNKNOWN_ISSUER, Verdicts.compute(VerifyTestSupport.checks(result)))
    }

    @Test
    fun `negative DS serial number - embedded or found in the store`() {
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
    fun `malformed SOD - no exception, rows fail`() {
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
    fun `SOD content type other than ldsSecurityObject - SOD rejected`() {
        // Same LDSSecurityObject, correctly signed by the DS, but declared as id-data.
        val result = verify(pki.document { sod = SodOptions(contentTypeOid = "1.2.840.113549.1.7.1") })

        assertEquals(List(4) { CheckStatus.FAILED }, result.statuses())
        assertEquals(CheckDetail.Error("SOD_MALFORMED"), result.sodSignature.detail)
    }

    @Test
    fun `CSCA source carried into the chain`() {
        val store = TestTrustStore.of(listOf(pki.oldCsca.certificate), TrustSource.IMPORTED_MASTER_LIST)
        val result = verify(pki.document(), store = store)

        assertEquals(TrustSource.IMPORTED_MASTER_LIST, result.chain!!.cscaSource)
    }

    @Test
    fun `link cycle in the store - no infinite loop`() {
        // Link and reverse link, with no self-signed CSCA: the search must stop.
        val ds = pki.issueDs(signedBy = CscaGeneration.NEW)
        val result = verify(pki.document { this.ds = ds }, store = pki.trustStore(pki.link, pki.reverseLink))

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
    }

    @Test
    fun `document without DG14 or DG15 - valid signature, chip not verified`() {
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
    fun `nominal chain with AA then CA - authentic`() {
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
