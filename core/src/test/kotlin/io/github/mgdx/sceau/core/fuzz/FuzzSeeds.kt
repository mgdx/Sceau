package io.github.mgdx.sceau.core.fuzz

import io.github.mgdx.sceau.core.reading.LibraryLogging
import io.github.mgdx.sceau.core.trust.TestMasterLists
import io.github.mgdx.sceau.testchip.AaKeyType
import io.github.mgdx.sceau.testchip.PaceSettings
import io.github.mgdx.sceau.testchip.SimulatedDocuments
import io.github.mgdx.sceau.testchip.TestCrypto
import io.github.mgdx.sceau.testchip.TestDataGroups
import io.github.mgdx.sceau.testchip.TestDocument
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import io.github.mgdx.sceau.testchip.TestSod
import io.github.mgdx.sceau.testchip.TestTrustStore
import org.bouncycastle.asn1.icao.ICAOObjectIdentifiers
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSSignedData
import org.jmrtd.lds.icao.COMFile
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.time.LocalDate

/**
 * Document signé servant de graine, avec le magasin qui en connaît le CSCA. Le certificat DS
 * et EF.SOD sont resignés de façon déterministe ([DeterministicSigning]) par [cscaKey] et la
 * clé du DS : mêmes contenus que ceux de `:testchip`, mêmes octets à chaque exécution.
 */
internal class SeedDocument(
    val name: String,
    val document: TestDocument,
    val trustStore: TestTrustStore,
    cscaKey: PrivateKey,
) {
    val dsKey: PrivateKey get() = document.ds.privateKey

    /** Certificat DS de `:testchip`, resigné par le CSCA. */
    val ds: X509CertificateHolder = DeterministicSigning.reissue(document.ds.holder, cscaKey)

    /** Contenu signé du SOD de `:testchip` (LDSSecurityObject DER). */
    val securityObject: ByteArray =
        CMSSignedData(document.sod.copyOfRange(FuzzSeeds.tlvHeaderSize(document.sod), document.sod.size)).signedContent.content
            as ByteArray

    /** EF.SOD (tag 0x77) signant [content], par défaut le LDSSecurityObject d'origine. */
    fun sod(content: ByteArray = securityObject): ByteArray =
        TestSod.tlv(TestSod.SOD_TAG, DeterministicSigning.signedData(ICAOObjectIdentifiers.id_icao_ldsSecurityObject, content, ds, dsKey))

    val sod: ByteArray = sod()

    /** Réponse d'Active Authentication reproductible à [challenge], ou null sans DG15. */
    fun aaResponse(challenge: ByteArray): ByteArray? =
        document.aaKeyPair?.let { DeterministicSigning.aaResponse(it.private, document.aaDigestAlgorithm, challenge) }
}

/** Master List de test reproductible : CSCA RSA, CSCA EC à paramètres explicites, signataire RSA. */
internal class MasterListSeed {
    private val random = TestCrypto.seededRandom(SEED)
    val rsaCscaKey: KeyPair = rsa()
    private val ecCscaKey =
        KeyPairGenerator
            .getInstance(
                "EC",
                TestCrypto.provider,
            ).apply { initialize(ECGenParameterSpec("secp256r1"), random) }
            .generateKeyPair()
    val signerKey: KeyPair = rsa()
    val rsaCsca: X509CertificateHolder =
        TestMasterLists.selfSigned("C=ZZ,O=Test,CN=CSCA RSA", rsaCscaKey, serial = BigInteger.ONE)
    val ecCsca: X509CertificateHolder =
        DeterministicSigning.reissue(
            TestMasterLists.selfSigned(
                "C=YY,O=Test,CN=CSCA EC",
                ecCscaKey,
                subjectKey = TestMasterLists.explicitEcPublicKeyInfo(ecCscaKey.public),
                serial = BigInteger.valueOf(-4242),
            ),
            ecCscaKey.private,
        )
    val signer: X509CertificateHolder =
        TestMasterLists.certificate(
            subject = "C=ZZ,O=Test,CN=Master List Signer",
            issuer = "C=ZZ,O=Test,CN=CSCA RSA",
            subjectKey = SubjectPublicKeyInfo.getInstance(signerKey.public.encoded),
            signingKey = rsaCscaKey,
            serial = BigInteger.TWO,
            ca = false,
        )
    val content: ByteArray = TestMasterLists.content(listOf(rsaCsca, ecCsca))
    val bytes: ByteArray = sign(content)

    /** Master List dont le contenu signé est [content]. */
    fun sign(content: ByteArray): ByteArray =
        DeterministicSigning.signedData(TestMasterLists.CSCA_MASTER_LIST, content, signer, signerKey.private)

    private fun rsa(): KeyPair =
        KeyPairGenerator
            .getInstance("RSA", TestCrypto.provider)
            .apply {
                initialize(RSA_BITS, random)
            }.generateKeyPair()

    private companion object {
        const val SEED = 20_260_929L
        const val RSA_BITS = 2048
    }
}

/**
 * Graines valides, produites par la PKI factice et la puce simulée de `:testchip` (identités
 * fictives), plus la Master List embarquée. Construites une fois : la génération des clés
 * RSA et EC coûte plusieurs secondes.
 */
internal object FuzzSeeds {
    val TODAY: LocalDate = LocalDate.of(2026, 9, 25)

    init {
        // Comme readAndVerify : JMRTD journalise chaque tag inconnu, soit des Mo par campagne.
        LibraryLogging.silence()
    }

    /** Passeport TD3, hiérarchie RSA, AA RSA 1024 ; CNIe TD1 EC ; passeport EC à courbe explicite, AA EC. */
    val documents: List<SeedDocument> by lazy {
        val rsa = TestPki(TestKeyType.RSA)
        val explicit = TestPki(TestKeyType.EC_EXPLICIT)
        val card = SimulatedDocuments.frenchIdCard()
        listOf(
            SeedDocument("rsa-td3", TestDocument.Builder(rsa).build(), rsa.trustStore(rsa.oldCsca), rsa.oldCsca.privateKey),
            SeedDocument("cnie-td1", card.document, card.trustStore, card.pki.oldCsca.privateKey),
            SeedDocument(
                "ec-explicit-td3",
                TestDocument.Builder(explicit).apply { activeAuthentication = AaKeyType.EC }.build(),
                explicit.trustStore(explicit.oldCsca),
                explicit.oldCsca.privateKey,
            ),
        )
    }

    /** Toutes les variantes d'un DG parmi les documents graines, et quelques DG construits ici. */
    fun dataGroup(number: Int): List<ByteArray> =
        documents.mapNotNull { it.document.dataGroups[number] } +
            when (number) {
                7 -> listOf(richDg7)
                11 -> listOf(richDg11)
                12 -> listOf(richDg12)
                else -> emptyList()
            }

    val com: List<ByteArray> by lazy {
        listOf(
            COMFile("1.7", "4.0.0", intArrayOf(0x61, 0x75, 0x6B, 0x6C, 0x6E, 0x6F)).encoded,
            COMFile("1.8", "4.0.0", intArrayOf(0x61, 0x75)).encoded,
        )
    }

    val cardAccess: List<ByteArray> by lazy {
        listOf(
            PaceSettings("123456").cardAccess,
            PaceSettings("123456", PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_256, PaceSettings.PARAM_ID_NIST_P256_R1).cardAccess,
        )
    }

    val testMasterList: MasterListSeed by lazy { MasterListSeed() }

    val embeddedMasterList: ByteArray by lazy {
        checkNotNull(FuzzSeeds::class.java.getResourceAsStream("/trust/de-bsi-master-list.ml")).use { it.readBytes() }
    }

    /** DG11 dont tous les champs sont renseignés (identité fictive). */
    private val richDg11: ByteArray by lazy {
        DG11File(
            "SPECIMEN<<ANNA<MARIA",
            listOf("SPECIMEN<<ANNE"),
            "123456789",
            "19740812",
            listOf("UTOPIA", "VILLE"),
            listOf("1 RUE FICTIVE", "99999 UTOPIA"),
            "+00 0000000",
            "TESTEUSE",
            "DR",
            "RESUME FICTIF",
            ByteArray(PROOF_OF_CITIZENSHIP) { it.toByte() },
            listOf("L898902C3", "X0000000"),
            "AUCUNE",
        ).encoded
    }

    /** DG7 à deux images (JPEG et JPEG 2000 réduits à leur signature). */
    private val richDg7: ByteArray by lazy {
        TestDataGroups.dg7(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10),
            byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51, 0, 0x2F),
        )
    }

    /** DG12 avec images recto et verso (JPEG et JPEG 2000 réduits à leur signature). */
    private val richDg12: ByteArray by lazy {
        DG12File(
            "AUTORITE FICTIVE",
            "20210601",
            listOf("SPECIMEN<<PAUL"),
            "AUCUNE",
            "AUCUNE",
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10),
            byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51, 0, 0x2F),
            "20210601120000",
            "SERIE-0001",
        ).encoded
    }

    private const val PROOF_OF_CITIZENSHIP = 16

    /** Taille de l'en-tête (tag sur un octet, longueur) d'un TLV bien formé. */
    fun tlvHeaderSize(bytes: ByteArray): Int {
        val first = bytes[1].toInt() and 0xFF
        return if (first < 0x80) 2 else 2 + (first and 0x7F)
    }
}
