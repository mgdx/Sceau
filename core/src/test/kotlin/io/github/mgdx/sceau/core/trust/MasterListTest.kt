package io.github.mgdx.sceau.core.trust

import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.util.concurrent.CancellationException

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

    /** Audit V22 : chaque SignerInfo parcourait tout le bloc `certificates` ; un seul est admis. */
    @Test
    fun masterListWithTwoSignersIsRejected() {
        val secondKey = TestMasterLists.rsaKeyPair()
        val secondSigner =
            TestMasterLists.certificate(
                subject = "C=ZZ,O=Test,CN=Second Master List Signer",
                issuer = "C=ZZ,O=Test,CN=CSCA RSA",
                subjectKey = SubjectPublicKeyInfo.getInstance(secondKey.public.encoded),
                signingKey = fixture.rsaCscaKey,
                ca = false,
            )
        val generator = CMSSignedDataGenerator()
        val digests = JcaDigestCalculatorProviderBuilder().setProvider(TestMasterLists.provider).build()
        listOf(fixture.signer to fixture.signerKey, secondSigner to secondKey).forEach { (certificate, key) ->
            val contentSigner = JcaContentSignerBuilder("SHA256withRSA").setProvider(TestMasterLists.provider).build(key.private)
            generator.addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(digests).build(contentSigner, certificate))
            generator.addCertificate(certificate)
        }
        val bytes = generator.generate(CMSProcessableByteArray(TestMasterLists.CSCA_MASTER_LIST, fixture.content), true).encoded

        assertRejected("MULTIPLE_SIGNERS", bytes)
    }

    /** Audit V22 : au-delà du plafond, la liste est refusée avant toute lecture de certificat. */
    @Test
    fun masterListAboveCertificateCapIsRejected() {
        val content = contentWithFillers(TrustStores.MAX_MASTER_LIST_CERTIFICATES + 1)

        assertRejected("TOO_MANY_CERTIFICATES", TestMasterLists.signedMasterList(content, fixture.signer, fixture.signerKey))
    }

    /** Exactement le plafond : acceptée (les éléments de remplissage, illisibles, sont ignorés). */
    @Test
    fun masterListAtCertificateCapIsAccepted() {
        val content = contentWithFillers(TrustStores.MAX_MASTER_LIST_CERTIFICATES)
        val list = TrustStores.parseMasterList(TestMasterLists.signedMasterList(content, fixture.signer, fixture.signerKey))

        assertArrayEquals(fixture.rsaCsca.encoded, list.certificates.single().encoded)
    }

    /** Audit V22 : le bloc `certificates` du CMS est plafonné lui aussi. */
    @Test
    fun cmsCertificatesAboveCapAreRejected() {
        val fillers = (0..TrustStores.MAX_MASTER_LIST_CERTIFICATES).map { ASN1Integer(it.toLong()) }

        assertRejected("TOO_MANY_CERTIFICATES", rebuiltSignedData(certificates = DERSet(fillers.toTypedArray())))
    }

    /** La Master List réelle du BSI (608 certificats) reste acceptée à l'import, sous le plafond. */
    @Test
    fun bsiMasterListIsAcceptedAsImport() {
        val bytes = checkNotNull(TrustStores::class.java.getResourceAsStream("/trust/de-bsi-master-list.ml")).use { it.readBytes() }
        val list = TrustStores.parseMasterList(bytes)

        assertEquals(608, list.info.certificateCount)
        assertTrue(list.info.certificateCount < TrustStores.MAX_MASTER_LIST_CERTIFICATES)
    }

    /** Audit V22 : l'analyse est abandonnable, et l'abandon n'est pas déguisé en liste invalide. */
    @Test
    fun cancellationFromCheckpointPropagates() {
        try {
            TrustStores.parseMasterList(fixture.bytes) { throw CancellationException("test") }
            fail("analyse non interrompue")
        } catch (_: CancellationException) {
            // attendu
        }
    }

    /** Contenu `CscaMasterList` de [size] éléments : le CSCA RSA et des entiers de remplissage. */
    private fun contentWithFillers(size: Int): ByteArray {
        val elements = ASN1EncodableVector()
        elements.add(fixture.rsaCsca.toASN1Structure())
        for (i in 1 until size) elements.add(ASN1Integer(i.toLong()))
        return DERSequence(arrayOf(ASN1Integer(0), DERSet(elements))).encoded
    }

    /**
     * Fuzzing (ML-test) : bloc `certificates` du CMS qui ne contient pas de certificat.
     * BouncyCastle ne le décode qu'à la demande et levait IllegalArgumentException, que
     * `TrustStoreLoader.load` ne rattrape pas.
     */
    @Test
    fun malformedCmsCertificateIsRejected() {
        assertRejected("NOT_CMS", rebuiltSignedData(certificates = DERSet(DERSequence(ASN1Integer(1)))))
    }

    /** Fuzzing (ML-test) : `signerInfos` qui ne contient pas de SignerInfo (NoSuchElementException). */
    @Test
    fun malformedSignerInfoIsRejected() {
        assertRejected("NOT_CMS", rebuiltSignedData(signerInfos = DERSet(DERSequence(ASN1Integer(1)))))
    }

    /**
     * Fuzzing (ML-content) : certificat signé dont la clé publique est illisible. BouncyCastle ne
     * la décode qu'à la demande : le certificat entrait dans le magasin, puis `isSelfSigned`
     * levait IllegalStateException. Il est ignoré comme tout certificat illisible.
     */
    @Test
    fun certificateWithUnreadablePublicKeyIsSkipped() {
        val badKey = SubjectPublicKeyInfo(AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE), byteArrayOf(1, 2, 3))
        assertOnlyRsaCscaKept(TestMasterLists.selfSigned("C=ZZ,O=Test,CN=CSCA cle illisible", fixture.rsaCscaKey, subjectKey = badKey))
    }

    /**
     * Fuzzing (ML-content, mode long) : clé d'algorithme inconnu de BouncyCastle, pour laquelle
     * `publicKey` renvoie null ; `isSelfSigned` levait NullPointerException.
     */
    @Test
    fun certificateWithUnknownKeyAlgorithmIsSkipped() {
        val unknownKey = SubjectPublicKeyInfo(AlgorithmIdentifier(ASN1ObjectIdentifier("1.3.6.1.4.1.99999.42.2")), byteArrayOf(1, 2, 3))
        assertOnlyRsaCscaKept(TestMasterLists.selfSigned("C=ZZ,O=Test,CN=CSCA cle inconnue", fixture.rsaCscaKey, subjectKey = unknownKey))
    }

    private fun assertOnlyRsaCscaKept(bad: X509CertificateHolder) {
        val content = TestMasterLists.content(listOf(fixture.rsaCsca, bad))
        val list = TrustStores.parseMasterList(TestMasterLists.signedMasterList(content, fixture.signer, fixture.signerKey))

        assertEquals(1, list.certificates.size)
        assertArrayEquals(fixture.rsaCsca.encoded, list.certificates.single().encoded)
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
        val store = TrustStoreLoader.merge(emptyList(), emptyList(), null, listOf(list))
        val ski = TrustCrypto.subjectKeyId(list.certificates[1])

        assertNotNull(ski)
        val found = store.findBySubjectKeyId(ski!!)
        assertEquals(1, found.size)
        assertArrayEquals(list.certificates[1].encoded, found[0].certificate.encoded)
    }

    /**
     * Master List de la fixture dont le SignedData est réassemblé élément par élément, avec
     * [certificates] (bloc [0] IMPLICIT) ou [signerInfos] (dernier élément) remplacés.
     */
    private fun rebuiltSignedData(
        certificates: ASN1Set? = null,
        signerInfos: ASN1Set? = null,
    ): ByteArray {
        val signedData = ASN1Sequence.getInstance(ContentInfo.getInstance(fixture.bytes).content)
        val elements = ASN1EncodableVector()
        signedData.forEachIndexed { index, element ->
            elements.add(
                when {
                    index == signedData.size() - 1 && signerInfos != null -> signerInfos
                    element is ASN1TaggedObject && element.tagNo == 0 && certificates != null -> DERTaggedObject(false, 0, certificates)
                    else -> element
                },
            )
        }
        return ContentInfo(CMSObjectIdentifiers.signedData, DERSequence(elements)).encoded
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
