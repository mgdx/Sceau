package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.trust.TrustSource
import io.github.mgdx.sceau.core.trust.TrustStores
import io.github.mgdx.sceau.testchip.TestCredential
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile of the chain certificates (audit V8): an issuer must be allowed to sign certificates
 * (basicConstraints cA=TRUE, keyUsage keyCertSign, when these extensions are present), and the
 * DS must be allowed to sign (keyUsage digitalSignature, if present).
 */
class CertificateProfileTest {
    private val pki = TestPki(TestKeyType.RSA, seed = 80_808L)

    private fun chainOf(
        ds: TestCredential,
        vararg store: TestCredential,
    ): PassiveAuthResult {
        val document = pki.document { this.ds = ds }
        return PassiveAuthentication.verify(
            sod = document.sod,
            dataGroups = document.dataGroups,
            trustStore = pki.trustStore(*store, source = TrustSource.IMPORTED_MASTER_LIST),
            dateOfIssue = document.dateOfIssue,
            dateOfExpiry = document.dateOfExpiry,
            documentCode = document.documentCode,
        )
    }

    @Test
    fun `DS from an imported Master List used as an issuer - discarded`() {
        // pki.ds: keyUsage digitalSignature only, no basicConstraints.
        val forged = pki.issueCustom(pki.ds, "DS SIGNE PAR UN DS", basicConstraintsCa = null, keyUsage = KeyUsage.digitalSignature)

        val result = chainOf(forged, pki.oldCsca, pki.ds)

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
        // Audit V25: without a chain, the SOD signature is not presented as verified.
        assertEquals(CheckStatus.NOT_AVAILABLE, result.sodSignature.status)
    }

    @Test
    fun `intermediate with basicConstraints cA FALSE - discarded`() {
        val intermediate = pki.issueCustom(pki.oldCsca, "PAS UNE CA", basicConstraintsCa = false, keyUsage = KeyUsage.keyCertSign)
        val ds = pki.issueCustom(intermediate, "DS", basicConstraintsCa = null, keyUsage = KeyUsage.digitalSignature)

        assertEquals(CheckStatus.NOT_AVAILABLE, chainOf(ds, pki.oldCsca, intermediate).certificateChain.status)
    }

    @Test
    fun `compliant intermediate CA - chain OK`() {
        val intermediate = pki.issueCustom(pki.oldCsca, "CA", basicConstraintsCa = true, keyUsage = KeyUsage.keyCertSign)
        val ds = pki.issueCustom(intermediate, "DS", basicConstraintsCa = null, keyUsage = KeyUsage.digitalSignature)

        assertEquals(CheckStatus.OK, chainOf(ds, pki.oldCsca, intermediate).certificateChain.status)
    }

    @Test
    fun `intermediate without basicConstraints or keyUsage - tolerated`() {
        // Real-world case (Belgian link without basicConstraints, Chinese link without keyUsage).
        val intermediate = pki.issueCustom(pki.oldCsca, "LIEN ANCIEN", basicConstraintsCa = null, keyUsage = null)
        val ds = pki.issueCustom(intermediate, "DS", basicConstraintsCa = null, keyUsage = KeyUsage.digitalSignature)

        assertEquals(CheckStatus.OK, chainOf(ds, pki.oldCsca, intermediate).certificateChain.status)
    }

    @Test
    fun `DS without digitalSignature in its keyUsage - chain fails`() {
        val ds = pki.issueCustom(pki.oldCsca, "DS", basicConstraintsCa = null, keyUsage = KeyUsage.keyCertSign)

        val result = chainOf(ds, pki.oldCsca)

        assertEquals(CheckStatus.FAILED, result.certificateChain.status)
        assertEquals(CheckDetail.Error("DS_KEY_USAGE"), result.certificateChain.detail)
    }

    @Test
    fun `embedded store - all self-signed CSCAs can issue`() {
        val anchors = TrustStores.load().anchors
        val refused = anchors.filterNot { CertificateChains.canIssueCertificates(JcaX509CertificateHolder(it.certificate)) }

        assertEquals(emptyList<String>(), refused.filter { it.isSelfSigned }.map { it.subject })
        // Seven link certificates carry cA=FALSE: each shares its key and SKI with a compliant
        // self-signed CSCA in the store, which directly anchors the DSs of that key.
        for (link in refused) {
            val twin =
                anchors.any {
                    it.isSelfSigned &&
                        it.certificate.publicKey == link.certificate.publicKey &&
                        CertificateChains.canIssueCertificates(JcaX509CertificateHolder(it.certificate))
                }
            assertTrue(link.subject, twin)
        }
    }

    @Test
    fun `DS without keyUsage - tolerated`() {
        val ds = pki.issueCustom(pki.oldCsca, "DS", basicConstraintsCa = null, keyUsage = null)

        assertEquals(CheckStatus.OK, chainOf(ds, pki.oldCsca).certificateChain.status)
    }
}
