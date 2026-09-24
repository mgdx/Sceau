package io.github.mgdx.sceau.core.trust

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger

class MasterListTest {
    private val fixture = TestMasterLists.Fixture()

    private fun assertRejected(
        expectedCode: String,
        bytes: ByteArray,
    ) {
        try {
            TrustStores.parseMasterList(bytes)
            fail("Master List acceptée à tort")
        } catch (e: InvalidMasterListException) {
            assertEquals(expectedCode, e.code)
        }
    }

    @Test
    fun validMasterListIsAccepted() {
        val list = TrustStores.parseMasterList(fixture.bytes)

        assertEquals(2, list.certificates.size)
        assertEquals(2, list.info.certificateCount)
        // SET OF : l'ordre DER n'est pas celui d'insertion.
        assertEquals(
            setOf(fixture.rsaCsca.encoded.toList(), fixture.ecCsca.encoded.toList()),
            list.certificates.map { it.encoded.toList() }.toSet(),
        )
        assertEquals(TrustCrypto.sha256Hex(fixture.signer.encoded), list.info.signerSha256)
        assertTrue(list.info.signerSubject.contains("CN=Master List Signer"))
        assertNotNull(list.info.signingTime)
    }

    @Test
    fun explicitCurveAndNegativeSerialAreRead() {
        val ec = TrustStores.parseMasterList(fixture.bytes).certificates.single { it.subjectX500Principal.name.contains("CSCA EC") }

        assertEquals(BigInteger.valueOf(-4242), ec.serialNumber)
        val spki = SubjectPublicKeyInfo.getInstance(ec.publicKey.encoded)
        assertTrue("paramètres explicites attendus", spki.algorithm.parameters is ASN1Sequence)
        val anchor = TrustAnchor(ec, TrustSource.IMPORTED_MASTER_LIST)
        assertEquals("YY", anchor.country)
        assertTrue(anchor.isSelfSigned)
    }

    @Test
    fun alteredContentIsRejected() {
        val tampered = fixture.bytes.copyOf()
        // Un octet au début du contenu signé. BC découpe l'OCTET STRING encapsulé en segments
        // de 1000 octets : on cherche donc le début du premier segment.
        val offset = indexOf(tampered, fixture.content.copyOf(64)) + 60
        tampered[offset] = (tampered[offset].toInt() xor 0x01).toByte()

        assertRejected("BAD_SIGNATURE", tampered)
    }

    @Test
    fun wrongContentTypeIsRejected() {
        val bytes = TestMasterLists.signedMasterList(fixture.content, fixture.signer, fixture.signerKey, CMSObjectIdentifiers.data)

        assertRejected("BAD_CONTENT_TYPE", bytes)
    }

    @Test
    fun nonCmsFileIsRejected() {
        assertRejected("NOT_CMS", "pas une Master List".toByteArray())
        assertRejected("NOT_CMS", fixture.rsaCsca.encoded)
    }

    @Test
    fun masterListWithoutSignerIsRejected() {
        val bytes = TestMasterLists.signedMasterList(fixture.content, null, null, extraCertificates = listOf(fixture.signer))

        assertRejected("NO_SIGNER", bytes)
    }

    @Test
    fun malformedContentIsRejected() {
        val bytes = TestMasterLists.signedMasterList(byteArrayOf(0x04, 0x01, 0x00), fixture.signer, fixture.signerKey)

        assertRejected("BAD_CONTENT", bytes)
    }

    @Test
    fun unreadableCertificateIsSkipped() {
        // Une SEQUENCE vide, illisible comme Certificate, à côté d'un CSCA valide.
        val certList = DERSet(arrayOf(DERSequence(), fixture.ecCsca.toASN1Structure()))
        val content = DERSequence(arrayOf(ASN1Integer(0), certList)).encoded
        val list = TrustStores.parseMasterList(TestMasterLists.signedMasterList(content, fixture.signer, fixture.signerKey))

        assertEquals(1, list.certificates.size)
        assertEquals(1, list.info.certificateCount)
    }

    @Test
    fun anchoredSignerIsRequiredForEmbeddedList() {
        val parsed = MasterListParser.parse(fixture.bytes)
        val rsaCscaSha = TrustCrypto.sha256Hex(fixture.rsaCsca.encoded)

        // Signataire émis par le CSCA épinglé, présent dans la liste : accepté.
        MasterListParser.requireAnchoredSigner(parsed, setOf(rsaCscaSha))

        // Épingle sur un autre certificat : rejeté.
        try {
            MasterListParser.requireAnchoredSigner(parsed, setOf(TrustCrypto.sha256Hex(fixture.ecCsca.encoded)))
            fail("signataire non ancré accepté")
        } catch (e: InvalidMasterListException) {
            assertEquals("UNTRUSTED_SIGNER", e.code)
        }
    }

    @Test
    fun signerFromUnpinnedCscaIsRejectedByAnchoring() {
        // Liste valide dont le signataire est émis par un CSCA non épinglé.
        val rogueKey = TestMasterLists.rsaKeyPair()
        val rogueCsca = TestMasterLists.selfSigned("C=ZZ,O=Test,CN=CSCA RSA", rogueKey)
        val signerKey = TestMasterLists.rsaKeyPair()
        val signer =
            TestMasterLists.certificate(
                subject = "C=ZZ,O=Test,CN=Master List Signer",
                issuer = "C=ZZ,O=Test,CN=CSCA RSA",
                subjectKey = SubjectPublicKeyInfo.getInstance(signerKey.public.encoded),
                signingKey = rogueKey,
                ca = false,
            )
        val content = TestMasterLists.content(listOf(fixture.rsaCsca, rogueCsca))
        val parsed = MasterListParser.parse(TestMasterLists.signedMasterList(content, signer, signerKey))

        try {
            MasterListParser.requireAnchoredSigner(parsed, setOf(TrustCrypto.sha256Hex(fixture.rsaCsca.encoded)))
            fail("signataire non ancré accepté")
        } catch (e: InvalidMasterListException) {
            assertEquals("UNTRUSTED_SIGNER", e.code)
        }
        try {
            MasterListParser.requireAnchoredSigner(parsed, TrustStoreLoader.EMBEDDED_MASTER_LIST_ANCHORS)
            fail("signataire non ancré accepté")
        } catch (e: InvalidMasterListException) {
            assertEquals("UNTRUSTED_SIGNER", e.code)
        }
    }

    @Test
    fun keyIdentifierIsIndexed() {
        val list = TrustStores.parseMasterList(fixture.bytes)
        val store = TrustStoreLoader.merge(emptyList(), null, listOf(list))
        val ski = TrustCrypto.subjectKeyId(list.certificates[1])

        assertNotNull(ski)
        val found = store.findBySubjectKeyId(ski!!)
        assertEquals(1, found.size)
        assertArrayEquals(list.certificates[1].encoded, found[0].certificate.encoded)
    }

    private fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        error("contenu introuvable")
    }
}
