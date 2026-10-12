package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustSource
import io.github.mgdx.sceau.testchip.CscaGeneration
import io.github.mgdx.sceau.testchip.TestCredential
import io.github.mgdx.sceau.testchip.TestCrypto
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import io.github.mgdx.sceau.testchip.TestTrustStore
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.cert.X509Certificate

/**
 * Chain building against a hostile store (audit V15): cross-signed certificates sharing their
 * AKIs, with no self-signed CSCA. Without a global visited set or a budget, the search cost up to
 * k^8 signature verifications.
 */
class ChainSearchBudgetTest {
    private val pki = TestPki(TestKeyType.RSA, seed = 70_707L)

    private fun ca(
        issuer: TestCredential,
        name: String,
        subject: TestCredential? = null,
    ) = pki.issueCustom(issuer, name, basicConstraintsCa = true, keyUsage = KeyUsage.keyCertSign, keyPair = subject?.keyPair)

    private fun verify(
        ds: TestCredential,
        store: List<TestCredential>,
    ): PassiveAuthResult {
        val document = pki.document { this.ds = ds }
        return PassiveAuthentication.verify(
            sod = document.sod,
            dataGroups = document.dataGroups,
            trustStore = pki.trustStore(*store.toTypedArray(), source = TrustSource.IMPORTED_MASTER_LIST),
            dateOfIssue = document.dateOfIssue,
            dateOfExpiry = document.dateOfExpiry,
            documentCode = document.documentCode,
        )
    }

    /**
     * [count] "A" certificates (key KA) signed by key KB and [count] "B" certificates (key KB)
     * signed by KA: every A certificate has all the B ones as possible issuers, and vice versa.
     * The DS is signed by KA.
     */
    private fun crossSigned(count: Int): Pair<TestCredential, List<TestCredential>> {
        val a = ca(pki.oldCsca, "A")
        val b = ca(pki.oldCsca, "B")
        val aCerts = List(count) { ca(b, "A", subject = a) }
        val bCerts = List(count) { ca(a, "B", subject = b) }
        val ds = pki.issueCustom(a, "DS", basicConstraintsCa = null, keyUsage = KeyUsage.digitalSignature)
        return ds to aCerts + bCerts
    }

    @Test(timeout = 20_000)
    fun `cross-signed certificates - budget exhausted, chain fails`() {
        val (ds, store) = crossSigned(count = 12)

        val result = verify(ds, store)

        assertEquals(CheckStatus.FAILED, result.certificateChain.status)
        assertEquals(CheckDetail.Error("CHAIN_BUDGET"), result.certificateChain.detail)
    }

    @Test(timeout = 20_000)
    fun `a few cross-signed certificates - unknown issuer, without repeated exploration`() {
        val (ds, store) = crossSigned(count = 3)

        assertEquals(CheckStatus.NOT_AVAILABLE, verify(ds, store).certificateChain.status)
    }

    @Test
    fun `chain through a link with the CSCA - still found`() {
        val link = ca(pki.oldCsca, "NOUVEAU CSCA")
        val ds = pki.issueCustom(link, "DS", basicConstraintsCa = null, keyUsage = KeyUsage.digitalSignature)

        assertEquals(CheckStatus.OK, verify(ds, listOf(pki.oldCsca, link)).certificateChain.status)
    }

    // --- Audit V31: imported decoys with the SKI of an embedded CSCA ---------------------

    /**
     * Decoy from a hostile imported Master List: self-signed CSCA (cA=TRUE, keyCertSign) with the
     * subject and SKI of [target], but with its own key.
     */
    private fun decoy(target: TestCredential): X509Certificate {
        val keys = pki.generateRsa(Crypto.MIN_RSA_BITS)
        val spki = SubjectPublicKeyInfo.getInstance(keys.public.encoded)
        val subject = X500Name.getInstance(target.certificate.subjectX500Principal.encoded)
        val builder =
            X509v3CertificateBuilder(
                subject,
                pki.nextSerial(),
                TestCrypto.date(TestPki.CSCA_NOT_BEFORE),
                TestCrypto.date(TestPki.CSCA_NOT_AFTER),
                subject,
                spki,
            )
        builder.addExtension(Extension.subjectKeyIdentifier, false, SubjectKeyIdentifier(TestTrustStore.subjectKeyId(target.certificate)))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign))
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(TestCrypto.provider).build(keys.private)
        return JcaX509CertificateConverter().setProvider(TestCrypto.provider).getCertificate(builder.build(signer))
    }

    private fun decoys(target: TestCredential): List<TrustAnchor> =
        List(DECOYS) { TrustAnchor(decoy(target), TrustSource.IMPORTED_MASTER_LIST) }

    private fun verify(
        ds: TestCredential,
        store: TestTrustStore,
    ): PassiveAuthResult {
        val document = pki.document { this.ds = ds }
        return PassiveAuthentication.verify(
            sod = document.sod,
            dataGroups = document.dataGroups,
            trustStore = store,
            dateOfIssue = document.dateOfIssue,
            dateOfExpiry = document.dateOfExpiry,
            documentCode = document.documentCode,
        )
    }

    @Test(timeout = 60_000)
    fun `64 imported decoys before the embedded CSCA - chain OK to the real CSCA`() {
        val store = TestTrustStore(decoys(pki.oldCsca) + TrustAnchor(pki.oldCsca.certificate, TrustSource.ANTS))

        val result = verify(pki.ds, store)

        assertEquals(CheckStatus.OK, result.certificateChain.status)
        assertEquals(TrustSource.ANTS, result.chain?.cscaSource)
        assertEquals(Crypto.fingerprint(pki.oldCsca.certificate), result.chain?.cscaSha256)
    }

    @Test(timeout = 60_000)
    fun `64 imported decoys and chain through an embedded link - chain OK`() {
        val ds = pki.issueDs(signedBy = CscaGeneration.NEW)
        val embedded = listOf(pki.oldCsca, pki.link).map { TrustAnchor(it.certificate, TrustSource.ANTS) }
        val store = TestTrustStore(decoys(pki.newCsca) + embedded)

        val result = verify(ds, store)

        assertEquals(CheckStatus.OK, result.certificateChain.status)
        assertEquals(Crypto.fingerprint(pki.oldCsca.certificate), result.chain?.cscaSha256)
    }

    @Test(timeout = 60_000)
    fun `real CSCA imported before decoys from the same source - chain OK`() {
        val store = TestTrustStore(listOf(TrustAnchor(pki.oldCsca.certificate, TrustSource.IMPORTED_MASTER_LIST)) + decoys(pki.oldCsca))

        assertEquals(CheckStatus.OK, verify(pki.ds, store).certificateChain.status)
    }

    @Test(timeout = 60_000)
    fun `real CSCA imported after the decoys with no embedded source - budget exhausted`() {
        val store = TestTrustStore(decoys(pki.oldCsca) + TrustAnchor(pki.oldCsca.certificate, TrustSource.IMPORTED_MASTER_LIST))

        val result = verify(pki.ds, store)

        assertEquals(CheckStatus.FAILED, result.certificateChain.status)
        assertEquals(CheckDetail.Error("CHAIN_BUDGET"), result.certificateChain.detail)
    }

    private companion object {
        const val DECOYS = 64
    }
}
