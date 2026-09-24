package io.github.mgdx.sceau.core.trust

import org.bouncycastle.cert.X509CertificateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import javax.security.auth.x500.X500Principal

class TrustStoreTest {
    private val antsSha256 =
        setOf(
            "8d4d8939b9b31c0b45907bf0622f791fe90cf20e85cbbc2d368ee143b4a453ab",
            "5ba9a2069f34cd93fab9dcb39c6a7a7be62141f8bcb608aff815167c813ecf92",
            "28df42a7a0ed1b20f994cc96999060619e095b09764159703438ec60b88ee856",
            "d628b5100ddcbed8f3e5fa05e53b6b80bebb1e6264a12583319ef955c91d9349",
            "b33ea63b9be01082d98071a29111757c72257eba80d7205d21fa35436c29fe7c",
        )

    private fun ants(store: TrustStore) = store.anchors.filter { it.source == TrustSource.ANTS }

    @Test
    fun loadsAntsAnchors() {
        val store = TrustStores.load()
        val ants = ants(store)

        assertEquals(5, ants.size)
        assertEquals(antsSha256, ants.map { it.sha256 }.toSet())
        ants.forEach {
            assertEquals("FR", it.country)
            assertTrue(it.isSelfSigned)
        }
    }

    @Test
    fun loadsEmbeddedGermanMasterList() {
        val store = TrustStores.load()
        val info = store.embeddedMasterList

        assertNotNull(info)
        assertEquals(Instant.parse("2026-05-28T06:28:45Z"), info!!.signingTime)
        assertEquals(588, info.certificateCount)
        assertTrue(info.signerSubject.contains("CN=CSCA Master List Signer"))
        val embedded = store.anchors.filter { it.source == TrustSource.EMBEDDED_MASTER_LIST }
        // 588 certificats, dont 3 déjà fournis par l'ANTS (qui garde la priorité).
        assertEquals(585, embedded.size)
        assertEquals(590, store.anchors.size)
        val countries = embedded.map { it.country }.toSet()
        listOf("DE", "IT", "ES", "BE", "NL", "AT", "PL", "LU").forEach { assertTrue("CSCA $it attendu", it in countries) }
        assertTrue(store.findBySubject(X500Principal("CN=csca-germany, OU=bsi, O=bund, C=DE")).isNotEmpty())
    }

    @Test
    fun findsBySubject() {
        val store = TrustStores.load()

        val passport = store.findBySubject(X500Principal("CN=CSCA-FRANCE, O=Gouv, C=FR")).filter { it.source == TrustSource.ANTS }
        assertEquals(4, passport.size)
        // Comparaison sous forme canonique : casse et espaces indifférents.
        val eid = store.findBySubject(X500Principal("cn=eid-france,o=gouv,c=fr")).filter { it.source == TrustSource.ANTS }
        assertEquals(1, eid.size)
        assertEquals("b33ea63b9be01082d98071a29111757c72257eba80d7205d21fa35436c29fe7c", eid[0].sha256)
        assertTrue(store.findBySubject(X500Principal("CN=Inconnu, C=ZZ")).isEmpty())
    }

    @Test
    fun findsBySubjectKeyId() {
        val store = TrustStores.load()
        // SKI du CSCA-FRANCE 2020 et du CSCA eID-FRANCE (lus avec openssl).
        val csca2020 = store.findBySubjectKeyId(hex("be8a2ed6c9f9204e3a270308974decfdd97dc5e6"))
        val eid = store.findBySubjectKeyId(hex("9e973664572b5e12b712a936f861e097c913e8d3"))

        assertEquals(
            "28df42a7a0ed1b20f994cc96999060619e095b09764159703438ec60b88ee856",
            csca2020.single { it.source == TrustSource.ANTS }.sha256,
        )
        assertEquals(
            "b33ea63b9be01082d98071a29111757c72257eba80d7205d21fa35436c29fe7c",
            eid.single { it.source == TrustSource.ANTS }.sha256,
        )
        assertTrue(store.findBySubjectKeyId(ByteArray(20)).isEmpty())
    }

    @Test
    fun invalidImportIsIgnored() {
        val store = TrustStores.load(listOf("pas une Master List".toByteArray()))

        assertEquals(5, ants(store).size)
        assertTrue(store.anchors.none { it.source == TrustSource.IMPORTED_MASTER_LIST })
    }

    @Test
    fun validImportIsMergedAndDeduplicated() {
        val fixture = TestMasterLists.Fixture()
        val antsCertificate = ants(TrustStores.load()).first()
        val content =
            TestMasterLists.content(
                listOf(fixture.rsaCsca, fixture.ecCsca, fixture.rsaCsca, X509CertificateHolder(antsCertificate.certificate.encoded)),
            )
        val imported = TestMasterLists.signedMasterList(content, fixture.signer, fixture.signerKey)

        val store = TrustStores.load(listOf(imported, imported))

        val importedAnchors = store.anchors.filter { it.source == TrustSource.IMPORTED_MASTER_LIST }
        assertEquals(setOf("ZZ", "YY"), importedAnchors.map { it.country }.toSet())
        assertEquals(2, importedAnchors.size)
        // Le certificat ANTS également présent dans l'import reste marqué ANTS.
        assertEquals(TrustSource.ANTS, store.anchors.single { it.sha256 == antsCertificate.sha256 }.source)
        assertEquals(
            store.anchors.size,
            store.anchors
                .map { it.sha256 }
                .toSet()
                .size,
        )
    }

    @Test
    fun anchorPropertiesAreConsistent() {
        val anchor = ants(TrustStores.load()).first()

        assertEquals(64, anchor.sha256.length)
        assertEquals(anchor.sha256.lowercase(), anchor.sha256)
        assertNotNull(anchor.notBefore)
        assertFalse(anchor.subject.isEmpty())
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
