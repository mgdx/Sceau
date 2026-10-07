package io.github.mgdx.sceau.core.trust

import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPair

/** Ancrage du signataire d'une Master List importée et usage étendu du signataire (audit V34). */
class MasterListAnchoringTest {
    private val fixture = TestMasterLists.Fixture()

    private fun x509(holder: X509CertificateHolder) = TrustCrypto.toX509(holder.encoded)

    /** Magasin dont seul [csca] est embarqué, comme une publication nationale. */
    private fun storeWith(csca: X509CertificateHolder) = TrustStoreLoader.merge(emptyList(), listOf(x509(csca)), null, emptyList())

    private fun signer(
        issuerKey: KeyPair,
        extendedKeyUsage: List<KeyPurposeId>? = null,
    ): Pair<X509CertificateHolder, KeyPair> {
        val key = TestMasterLists.rsaKeyPair()
        val certificate =
            TestMasterLists.certificate(
                subject = "C=ZZ,O=Test,CN=Master List Signer",
                issuer = "C=ZZ,O=Test,CN=CSCA RSA",
                subjectKey = SubjectPublicKeyInfo.getInstance(key.public.encoded),
                signingKey = issuerKey,
                ca = false,
                extendedKeyUsage = extendedKeyUsage,
            )
        return certificate to key
    }

    private fun sources(store: TrustStore) =
        store.anchors
            .filter { it.country == "ZZ" || it.country == "YY" }
            .map { it.source }
            .toSet()

    @Test
    fun `signataire emis par un CSCA embarque - liste ancree`() {
        val list = TrustStores.parseMasterList(fixture.bytes)

        assertTrue(TrustStores.isMasterListAnchored(list, storeWith(fixture.rsaCsca)))
    }

    @Test
    fun `liste auto-signee inconnue - non ancree, source distincte`() {
        val list = TrustStores.parseMasterList(fixture.bytes)

        // Le CSCA émetteur du signataire est dans la liste elle-même : elle ne s'ancre pas sur elle-même.
        assertFalse(TrustStores.isMasterListAnchored(list, TrustStores.load()))
        val store = TrustStores.load(listOf(fixture.bytes))
        assertEquals(setOf(TrustSource.IMPORTED_MASTER_LIST_UNANCHORED), sources(store))
    }

    @Test
    fun `certificat importe seul - liste ancree au chargement comme a l'apercu`() {
        val list = TrustStores.parseMasterList(fixture.bytes)
        val csca = listOf(fixture.rsaCsca.encoded)

        assertTrue(TrustStores.isMasterListAnchored(list, TrustStores.load(), csca))
        val store = TrustStores.load(listOf(fixture.bytes), csca)
        assertEquals(TrustSource.IMPORTED_MASTER_LIST, store.anchors.single { it.subject.contains("CSCA EC") }.source)
    }

    @Test
    fun `CSCA venu d'une autre liste importee - pas d'ancrage`() {
        val other = TestMasterLists.signedMasterList(TestMasterLists.content(listOf(fixture.ecCsca)), fixture.signer, fixture.signerKey)

        val store = TrustStores.load(listOf(fixture.bytes, other))

        assertEquals(setOf(TrustSource.IMPORTED_MASTER_LIST_UNANCHORED), sources(store))
    }

    @Test
    fun `meme nom d'emetteur, autre cle - non ancree`() {
        val (rogue, rogueKey) = signer(TestMasterLists.rsaKeyPair())
        val list = TrustStores.parseMasterList(TestMasterLists.signedMasterList(fixture.content, rogue, rogueKey))

        assertFalse(TrustStores.isMasterListAnchored(list, storeWith(fixture.rsaCsca)))
    }

    @Test
    fun `usage etendu de signataire de Master List accepte`() {
        val masterListSigning = KeyPurposeId.getInstance(MasterListParser.MASTER_LIST_SIGNING_KEY_OID)
        for (usage in listOf(listOf(masterListSigning), listOf(KeyPurposeId.anyExtendedKeyUsage))) {
            val (certificate, key) = signer(fixture.rsaCscaKey, usage)
            val list = TrustStores.parseMasterList(TestMasterLists.signedMasterList(fixture.content, certificate, key))
            assertTrue(TrustStores.isMasterListAnchored(list, storeWith(fixture.rsaCsca)))
        }
    }

    @Test
    fun `usage etendu sans signature de Master List refuse`() {
        val (certificate, key) = signer(fixture.rsaCscaKey, listOf(KeyPurposeId.id_kp_serverAuth))

        try {
            TrustStores.parseMasterList(TestMasterLists.signedMasterList(fixture.content, certificate, key))
            fail("signataire à usage étendu incompatible accepté")
        } catch (e: InvalidMasterListException) {
            assertEquals("BAD_SIGNER_USAGE", e.code)
        }
    }

    @Test
    fun `liste embarquee du BSI toujours acceptee`() {
        val store = TrustStores.load()

        assertTrue(store.anchors.any { it.source == TrustSource.EMBEDDED_MASTER_LIST })
    }
}
