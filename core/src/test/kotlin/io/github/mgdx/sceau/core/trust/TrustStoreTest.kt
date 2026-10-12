package io.github.mgdx.sceau.core.trust

import io.github.mgdx.sceau.core.verify.Crypto
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

    /** Certificates published by their own State (D26): fingerprint to country and self-signed flag. */
    private val nationalSha256 =
        mapOf(
            "737ce248b62d8b6dc1c9e3aaa8b937334406bac17bb37235c1c50281e6ad7edb" to ("GB" to true),
            "81d9f6bf3026a04e6e3847808cf094e34f9473a92ff66eb86a975f4c42055b5d" to ("GB" to false),
            "2ef9e14c6155c315b8d7681168774a063e6fd2639f51f7d5c904ef862933c844" to ("GE" to true),
            "277fcd17b53b3e2f20cfee967db499189bf23e0ef0a9e1040c5a24c8b09cde3c" to ("GE" to true),
            "5acb0c0264ad18912ad80139b318ab507d00dc77a86ac4eb832d3510ff6ef95c" to ("LU" to false),
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
        assertEquals(Instant.parse("2026-10-01T09:50:52Z"), info!!.signingTime)
        assertEquals(609, info.certificateCount)
        assertTrue(info.signerSubject.contains("CN=CSCA Master List Signer"))
        val embedded = store.anchors.filter { it.source == TrustSource.EMBEDDED_MASTER_LIST }
        // 609 certificates, of which 3 are already provided by ANTS and 2 by the UK national
        // publication (GB 2026 and its link), which keep priority.
        assertEquals(604, embedded.size)
        assertEquals(614, store.anchors.size)
        // Greek residence permits (CSCAeRP-HELLAS 003 and its link): provided by the Master List
        // since the national files were removed (D48).
        val embeddedSha256 = embedded.map { it.sha256 }.toSet()
        listOf(
            "8f01ced4c95d9ee5cc6915e1151432e18fa1615ad6d8e61bb6ea1af025368e3e",
            "5bbdb09b12bc25f9373142409d9b52da806b764294baf4f028a2c13da15bdfc5",
        ).forEach { assertTrue("Greek certificate $it expected in the Master List", it in embeddedSha256) }
        val countries = embedded.map { it.country }.toSet()
        listOf("DE", "IT", "ES", "BE", "NL", "AT", "PL", "LU").forEach { assertTrue("CSCA $it expected", it in countries) }
        assertTrue(store.findBySubject(X500Principal("CN=csca-germany, OU=bsi, O=bund, C=DE")).isNotEmpty())
    }

    @Test
    fun loadsNationalAnchors() {
        val store = TrustStores.load()
        val national = store.anchors.filter { it.source == TrustSource.NATIONAL }

        assertEquals(nationalSha256.keys, national.map { it.sha256 }.toSet())
        national.forEach {
            val (country, selfSigned) = nationalSha256.getValue(it.sha256)
            assertEquals(country, it.country)
            assertEquals(selfSigned, it.isSelfSigned)
        }
    }

    @Test
    fun nationalLinksAreSignedByAnEmbeddedAnchor() {
        val store = TrustStores.load()
        // Each added link certificate verifies with the key of a CSCA already in the store
        // (GBR_2021, Luxembourg ePassport CSCA).
        store.anchors
            .filter { it.source == TrustSource.NATIONAL && !it.isSelfSigned }
            .forEach { link ->
                val issuers = store.findBySubject(link.certificate.issuerX500Principal).filter { it.sha256 != link.sha256 }
                assertTrue(
                    "issuer of link ${link.sha256} missing or invalid signature",
                    issuers.any { issuer ->
                        runCatching { link.certificate.verify(issuer.certificate.publicKey, Crypto.provider) }.isSuccess
                    },
                )
            }
    }

    @Test
    fun findsBySubject() {
        val store = TrustStores.load()

        val passport = store.findBySubject(X500Principal("CN=CSCA-FRANCE, O=Gouv, C=FR")).filter { it.source == TrustSource.ANTS }
        assertEquals(4, passport.size)
        // Canonical comparison: case and whitespace do not matter.
        val eid = store.findBySubject(X500Principal("cn=eid-france,o=gouv,c=fr")).filter { it.source == TrustSource.ANTS }
        assertEquals(1, eid.size)
        assertEquals("b33ea63b9be01082d98071a29111757c72257eba80d7205d21fa35436c29fe7c", eid[0].sha256)
        assertTrue(store.findBySubject(X500Principal("CN=Inconnu, C=ZZ")).isEmpty())
    }

    @Test
    fun findsBySubjectKeyId() {
        val store = TrustStores.load()
        // SKI of CSCA-FRANCE 2020 and of the eID-FRANCE CSCA (read with openssl).
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

        // Signer issued by a CSCA carried by the list itself: list not anchored (audit V34).
        val importedAnchors = store.anchors.filter { it.source == TrustSource.IMPORTED_MASTER_LIST_UNANCHORED }
        assertEquals(setOf("ZZ", "YY"), importedAnchors.map { it.country }.toSet())
        assertEquals(2, importedAnchors.size)
        // The ANTS certificate also present in the import stays marked ANTS.
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
