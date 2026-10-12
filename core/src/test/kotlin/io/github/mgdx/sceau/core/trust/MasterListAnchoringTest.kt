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

/** Anchoring of an imported Master List's signer and the signer's extended key usage (audit V34). */
class MasterListAnchoringTest {
    private val fixture = TestMasterLists.Fixture()

    private fun x509(holder: X509CertificateHolder) = TrustCrypto.toX509(holder.encoded)

    /** Store in which only [csca] is embedded, like a national publication. */
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
    fun `signer issued by an embedded CSCA - list anchored`() {
        val list = TrustStores.parseMasterList(fixture.bytes)

        assertTrue(TrustStores.isMasterListAnchored(list, storeWith(fixture.rsaCsca)))
    }

    @Test
    fun `unknown self-signed list - not anchored, separate source`() {
        val list = TrustStores.parseMasterList(fixture.bytes)

        // The CSCA that issued the signer is in the list itself: a list does not anchor on itself.
        assertFalse(TrustStores.isMasterListAnchored(list, TrustStores.load()))
        val store = TrustStores.load(listOf(fixture.bytes))
        assertEquals(setOf(TrustSource.IMPORTED_MASTER_LIST_UNANCHORED), sources(store))
    }

    @Test
    fun `standalone imported certificate - list anchored at load time as in the preview`() {
        val list = TrustStores.parseMasterList(fixture.bytes)
        val csca = listOf(fixture.rsaCsca.encoded)

        assertTrue(TrustStores.isMasterListAnchored(list, TrustStores.load(), csca))
        val store = TrustStores.load(listOf(fixture.bytes), csca)
        assertEquals(TrustSource.IMPORTED_MASTER_LIST, store.anchors.single { it.subject.contains("CSCA EC") }.source)
    }

    @Test
    fun `CSCA from another imported list - no anchoring`() {
        val other = TestMasterLists.signedMasterList(TestMasterLists.content(listOf(fixture.ecCsca)), fixture.signer, fixture.signerKey)

        val store = TrustStores.load(listOf(fixture.bytes, other))

        assertEquals(setOf(TrustSource.IMPORTED_MASTER_LIST_UNANCHORED), sources(store))
    }

    @Test
    fun `same issuer name, different key - not anchored`() {
        val (rogue, rogueKey) = signer(TestMasterLists.rsaKeyPair())
        val list = TrustStores.parseMasterList(TestMasterLists.signedMasterList(fixture.content, rogue, rogueKey))

        assertFalse(TrustStores.isMasterListAnchored(list, storeWith(fixture.rsaCsca)))
    }

    @Test
    fun `Master List signer extended key usage accepted`() {
        val masterListSigning = KeyPurposeId.getInstance(MasterListParser.MASTER_LIST_SIGNING_KEY_OID)
        for (usage in listOf(listOf(masterListSigning), listOf(KeyPurposeId.anyExtendedKeyUsage))) {
            val (certificate, key) = signer(fixture.rsaCscaKey, usage)
            val list = TrustStores.parseMasterList(TestMasterLists.signedMasterList(fixture.content, certificate, key))
            assertTrue(TrustStores.isMasterListAnchored(list, storeWith(fixture.rsaCsca)))
        }
    }

    @Test
    fun `extended key usage without Master List signing rejected`() {
        val (certificate, key) = signer(fixture.rsaCscaKey, listOf(KeyPurposeId.id_kp_serverAuth))

        try {
            TrustStores.parseMasterList(TestMasterLists.signedMasterList(fixture.content, certificate, key))
            fail("signer with incompatible extended key usage accepted")
        } catch (e: InvalidMasterListException) {
            assertEquals("BAD_SIGNER_USAGE", e.code)
        }
    }

    @Test
    fun `embedded BSI list always accepted`() {
        val store = TrustStores.load()

        assertTrue(store.anchors.any { it.source == TrustSource.EMBEDDED_MASTER_LIST })
    }
}
