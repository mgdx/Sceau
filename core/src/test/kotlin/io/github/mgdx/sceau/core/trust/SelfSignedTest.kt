package io.github.mgdx.sceau.core.trust

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.util.Date

/** [TrustAnchor.isSelfSigned]: root CSCA versus link certificate with the same DN. */
class SelfSignedTest {
    private val name = "C=ZZ,O=Test,CN=CSCA"
    private val oldKey = TestMasterLists.ecKeyPair()
    private val newKey = TestMasterLists.ecKeyPair()

    private fun anchor(
        subjectKey: KeyPair,
        signingKey: KeyPair,
        withKeyIds: Boolean,
        subject: String = name,
    ): TrustAnchor {
        val spki = SubjectPublicKeyInfo.getInstance(subjectKey.public.encoded)
        val builder =
            X509v3CertificateBuilder(
                X500Name(name),
                BigInteger.valueOf(System.nanoTime()),
                Date(1_700_000_000_000L),
                Date(2_000_000_000_000L),
                X500Name(subject),
                spki,
            )
        if (withKeyIds) {
            val utils = JcaX509ExtensionUtils()
            builder.addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(spki))
            builder.addExtension(Extension.authorityKeyIdentifier, false, utils.createAuthorityKeyIdentifier(signingKey.public))
        }
        val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider(TestMasterLists.provider).build(signingKey.private)
        return TrustAnchor(TrustCrypto.toX509(builder.build(signer)), TrustSource.ANTS)
    }

    @Test
    fun `self-signed CSCA with AKI equal to SKI`() {
        assertTrue(anchor(newKey, newKey, withKeyIds = true).isSelfSigned)
    }

    @Test
    fun `link with the same DN signed by the old key`() {
        assertFalse(anchor(newKey, oldKey, withKeyIds = true).isSelfSigned)
    }

    @Test
    fun `subject different from the issuer`() {
        assertFalse(anchor(newKey, newKey, withKeyIds = true, subject = "C=ZZ,O=Test,CN=Autre").isSelfSigned)
    }

    @Test
    fun `without AKI or SKI, self-signed CSCA recognized by its signature`() {
        assertTrue(anchor(newKey, newKey, withKeyIds = false).isSelfSigned)
    }

    @Test
    fun `without AKI or SKI, link with the same DN recognized by its signature`() {
        assertFalse(anchor(newKey, oldKey, withKeyIds = false).isSelfSigned)
    }
}
