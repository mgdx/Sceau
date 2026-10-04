package io.github.mgdx.sceau.core.trust

import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.verify.PassiveAuthentication
import io.github.mgdx.sceau.testchip.CscaGeneration
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import org.bouncycastle.asn1.x509.KeyUsage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** Import d'un certificat seul (D33) : lecture DER et PEM, contrôles, fusion dans le magasin. */
class CertificateImportTest {
    private val rsa = TestPki(TestKeyType.RSA, name = "Import RSA")
    private val ec = TestPki(TestKeyType.EC, name = "Import EC")

    private fun pem(der: ByteArray): ByteArray {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n".toByteArray()
    }

    private fun rejected(bytes: ByteArray): String =
        try {
            TrustStores.parseCertificate(bytes)
            fail("certificat accepté à tort")
            ""
        } catch (e: InvalidCertificateException) {
            e.code
        }

    @Test
    fun `CSCA auto-signe en DER accepte, RSA et EC`() {
        for (pki in listOf(rsa, ec)) {
            val der = pki.oldCsca.certificate.encoded
            assertArrayEquals(der, TrustStores.parseCertificate(der).encoded)
        }
    }

    @Test
    fun `CSCA en PEM accepte, texte autour toléré`() {
        val der = rsa.oldCsca.certificate.encoded
        val withText = "Subject: CSCA\n".toByteArray() + pem(der)
        assertArrayEquals(der, TrustStores.parseCertificate(pem(der)).encoded)
        assertArrayEquals(der, TrustStores.parseCertificate(withText).encoded)
    }

    @Test
    fun `certificat de lien accepte`() {
        val der = ec.link.certificate.encoded
        assertArrayEquals(der, TrustStores.parseCertificate(der).encoded)
    }

    @Test
    fun `plusieurs certificats rejetes`() {
        val a = rsa.oldCsca.certificate.encoded
        val b = rsa.newCsca.certificate.encoded
        assertEquals("UNREADABLE", rejected(a + b))
        assertEquals("UNREADABLE", rejected(pem(a) + pem(b)))
    }

    @Test
    fun `fichier illisible rejete`() {
        assertEquals("UNREADABLE", rejected(ByteArray(0)))
        assertEquals("UNREADABLE", rejected("pas un certificat".toByteArray()))
        assertEquals("UNREADABLE", rejected("-----BEGIN CERTIFICATE-----\n@@@\n-----END CERTIFICATE-----".toByteArray()))
        assertEquals("UNREADABLE", rejected("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----".toByteArray()))
        // Octets superflus après le certificat.
        assertEquals("UNREADABLE", rejected(rsa.oldCsca.certificate.encoded + byteArrayOf(0)))
    }

    @Test
    fun `Master List rejetee comme certificat`() {
        val fixture = TestMasterLists.Fixture()
        val list = TestMasterLists.signedMasterList(TestMasterLists.content(listOf(fixture.rsaCsca)), fixture.signer, fixture.signerKey)
        assertEquals("UNREADABLE", rejected(list))
    }

    @Test
    fun `certificat non CA rejete`() {
        // DS : keyUsage digitalSignature, pas de basicConstraints.
        assertEquals("NOT_CA", rejected(rsa.ds.certificate.encoded))
        // basicConstraints cA=FALSE.
        assertEquals("NOT_CA", rejected(rsa.issueCustom(rsa.oldCsca, "CA false", false, null).certificate.encoded))
        // basicConstraints absent, keyUsage keyCertSign.
        assertEquals("NOT_CA", rejected(rsa.issueCustom(rsa.oldCsca, "sans BC", null, KeyUsage.keyCertSign).certificate.encoded))
        // cA=TRUE mais keyUsage sans keyCertSign.
        val noCertSign = rsa.issueCustom(rsa.oldCsca, "sans keyCertSign", true, KeyUsage.digitalSignature)
        assertEquals("NOT_CA", rejected(noCertSign.certificate.encoded))
    }

    @Test
    fun `CA sans keyUsage accepte`() {
        val der = rsa.issueCustom(rsa.oldCsca, "CA sans KU", true, null).certificate.encoded
        assertArrayEquals(der, TrustStores.parseCertificate(der).encoded)
    }

    @Test
    fun `auto-signature cassee rejetee`() {
        for (pki in listOf(rsa, ec)) {
            assertEquals("BAD_SIGNATURE", rejected(pki.withTamperedSignature(pki.oldCsca).certificate.encoded))
        }
    }

    @Test
    fun `certificats importes fusionnes avec leur source`() {
        val store =
            TrustStores.load(
                importedCertificates = listOf(rsa.oldCsca.certificate.encoded, pem(ec.link.certificate.encoded)),
            )

        val imported = store.anchors.filter { it.source == TrustSource.IMPORTED_CERTIFICATE }
        assertEquals(
            setOf(TrustCrypto.sha256Hex(rsa.oldCsca.certificate.encoded), TrustCrypto.sha256Hex(ec.link.certificate.encoded)),
            imported.map { it.sha256 }.toSet(),
        )
        assertTrue(imported.single { it.sha256 == TrustCrypto.sha256Hex(rsa.oldCsca.certificate.encoded) }.isSelfSigned)
    }

    @Test
    fun `certificat importe invalide ignore`() {
        val reference = TrustStores.load()
        val store =
            TrustStores.load(
                importedCertificates =
                    listOf(
                        "illisible".toByteArray(),
                        rsa.ds.certificate.encoded,
                        ec.withTamperedSignature(ec.oldCsca).certificate.encoded,
                    ),
            )

        assertTrue(store.anchors.none { it.source == TrustSource.IMPORTED_CERTIFICATE })
        assertEquals(reference.anchors.size, store.anchors.size)
    }

    @Test
    fun `priorite et doublons - un certificat deja present garde sa source`() {
        val ants = TrustStores.load().anchors.first { it.source == TrustSource.ANTS }
        val fixture = TestMasterLists.Fixture()
        val list = TestMasterLists.signedMasterList(TestMasterLists.content(listOf(fixture.rsaCsca)), fixture.signer, fixture.signerKey)
        val inList = fixture.rsaCsca.encoded

        val store =
            TrustStores.load(
                importedMasterLists = listOf(list),
                importedCertificates =
                    listOf(
                        ants.certificate.encoded,
                        inList,
                        rsa.oldCsca.certificate.encoded,
                        rsa.oldCsca.certificate.encoded,
                    ),
            )

        assertEquals(TrustSource.ANTS, store.anchors.single { it.sha256 == ants.sha256 }.source)
        assertEquals(TrustSource.IMPORTED_MASTER_LIST, store.anchors.single { it.sha256 == TrustCrypto.sha256Hex(inList) }.source)
        val own = store.anchors.filter { it.sha256 == TrustCrypto.sha256Hex(rsa.oldCsca.certificate.encoded) }
        assertEquals(listOf(TrustSource.IMPORTED_CERTIFICATE), own.map { it.source })
    }

    @Test
    fun `chaine DS vers CSCA importe aboutie`() {
        for (pki in listOf(rsa, ec)) {
            val document = pki.document()
            val store = TrustStores.load(importedCertificates = listOf(pem(pki.oldCsca.certificate.encoded)))

            val result = verify(document.sod, document.dataGroups, store, document)

            assertEquals(CheckStatus.OK, result.certificateChain.status)
            assertEquals(TrustSource.IMPORTED_CERTIFICATE, result.chain!!.cscaSource)
        }
    }

    @Test
    fun `chaine DS vers lien importe vers CSCA importe aboutie`() {
        val ds = rsa.issueDs(signedBy = CscaGeneration.NEW)
        val document = rsa.document { this.ds = ds }
        val store =
            TrustStores.load(
                importedCertificates = listOf(rsa.link.certificate.encoded, rsa.oldCsca.certificate.encoded),
            )

        val result = verify(document.sod, document.dataGroups, store, document)

        assertEquals(CheckStatus.OK, result.certificateChain.status)
        assertEquals(TrustSource.IMPORTED_CERTIFICATE, result.chain!!.cscaSource)
    }

    private fun verify(
        sod: ByteArray,
        dataGroups: Map<Int, ByteArray>,
        store: TrustStore,
        document: io.github.mgdx.sceau.testchip.TestDocument,
    ) = PassiveAuthentication.verify(
        sod = sod,
        dataGroups = dataGroups,
        trustStore = store,
        dateOfIssue = document.dateOfIssue,
        dateOfExpiry = document.dateOfExpiry,
        documentCode = document.documentCode,
    )
}
