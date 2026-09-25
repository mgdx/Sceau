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
 * Profil des certificats de la chaîne (audit V8) : un émetteur doit pouvoir signer des
 * certificats (basicConstraints cA=TRUE, keyUsage keyCertSign, quand ces extensions sont
 * présentes), et le DS doit pouvoir signer (keyUsage digitalSignature, s'il est présent).
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
    fun `DS d une Master List importee utilise comme emetteur - ecarte`() {
        // pki.ds : keyUsage digitalSignature seul, sans basicConstraints.
        val forged = pki.issueCustom(pki.ds, "DS SIGNE PAR UN DS", basicConstraintsCa = null, keyUsage = KeyUsage.digitalSignature)

        val result = chainOf(forged, pki.oldCsca, pki.ds)

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
        assertEquals(CheckStatus.OK, result.sodSignature.status)
    }

    @Test
    fun `intermediaire avec basicConstraints cA FALSE - ecarte`() {
        val intermediate = pki.issueCustom(pki.oldCsca, "PAS UNE CA", basicConstraintsCa = false, keyUsage = KeyUsage.keyCertSign)
        val ds = pki.issueCustom(intermediate, "DS", basicConstraintsCa = null, keyUsage = KeyUsage.digitalSignature)

        assertEquals(CheckStatus.NOT_AVAILABLE, chainOf(ds, pki.oldCsca, intermediate).certificateChain.status)
    }

    @Test
    fun `intermediaire CA conforme - chaine OK`() {
        val intermediate = pki.issueCustom(pki.oldCsca, "CA", basicConstraintsCa = true, keyUsage = KeyUsage.keyCertSign)
        val ds = pki.issueCustom(intermediate, "DS", basicConstraintsCa = null, keyUsage = KeyUsage.digitalSignature)

        assertEquals(CheckStatus.OK, chainOf(ds, pki.oldCsca, intermediate).certificateChain.status)
    }

    @Test
    fun `intermediaire sans basicConstraints ni keyUsage - tolere`() {
        // Cas réel (lien belge sans basicConstraints, lien chinois sans keyUsage).
        val intermediate = pki.issueCustom(pki.oldCsca, "LIEN ANCIEN", basicConstraintsCa = null, keyUsage = null)
        val ds = pki.issueCustom(intermediate, "DS", basicConstraintsCa = null, keyUsage = KeyUsage.digitalSignature)

        assertEquals(CheckStatus.OK, chainOf(ds, pki.oldCsca, intermediate).certificateChain.status)
    }

    @Test
    fun `DS sans digitalSignature dans son keyUsage - chaine en echec`() {
        val ds = pki.issueCustom(pki.oldCsca, "DS", basicConstraintsCa = null, keyUsage = KeyUsage.keyCertSign)

        val result = chainOf(ds, pki.oldCsca)

        assertEquals(CheckStatus.FAILED, result.certificateChain.status)
        assertEquals(CheckDetail.Error("DS_KEY_USAGE"), result.certificateChain.detail)
    }

    @Test
    fun `magasin embarque - tous les CSCA auto-signes peuvent emettre`() {
        val anchors = TrustStores.load().anchors
        val refused = anchors.filterNot { CertificateChains.canIssueCertificates(JcaX509CertificateHolder(it.certificate)) }

        assertEquals(emptyList<String>(), refused.filter { it.isSelfSigned }.map { it.subject })
        // Sept certificats de lien portent cA=FALSE : chacun partage clé et SKI avec un CSCA
        // auto-signé conforme du magasin, qui ancre directement les DS de cette clé.
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
    fun `DS sans keyUsage - tolere`() {
        val ds = pki.issueCustom(pki.oldCsca, "DS", basicConstraintsCa = null, keyUsage = null)

        assertEquals(CheckStatus.OK, chainOf(ds, pki.oldCsca).certificateChain.status)
    }
}
