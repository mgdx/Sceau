package io.github.mgdx.sceau.testchip

import io.github.mgdx.sceau.core.AccessKey
import net.sf.scuba.data.Gender
import org.jmrtd.lds.ActiveAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.MRZInfo
import org.jmrtd.lds.iso19794.FaceImageInfo
import org.jmrtd.lds.iso19794.FaceInfo
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Document simulé prêt à l'emploi : le document signé, sa PKI, et de quoi le lire.
 *
 * @property document DG, EF.SOD et clés privées de la puce
 * @property pace configuration PACE de la puce (null : BAC seul)
 */
class SimulatedIdentityDocument(
    val document: TestDocument,
    val pace: PaceSettings?,
    val documentNumber: String,
    val dateOfBirth: LocalDate,
) {
    val pki: TestPki get() = document.pki

    /** Magasin de confiance ne contenant que le CSCA de test de ce document. */
    val trustStore: TestTrustStore get() = pki.trustStore(pki.oldCsca)

    /** Clé CAN imprimée sur le document ; null sans PACE. */
    val canKey: AccessKey.Can? get() = pace?.let { AccessKey.Can(it.can) }

    /** Clé MRZ du document (numéro, date de naissance, date d'expiration). */
    val mrzKey: AccessKey.Mrz get() = AccessKey.Mrz(documentNumber, dateOfBirth, document.dateOfExpiry)

    /** Nouvelle puce simulée pour une lecture (une puce a un état : une par lecture). */
    fun chip(): SimulatedChip = SimulatedChip(document, pace)
}

/**
 * Profils de documents simulés. Toutes les identités sont factices et le signalent (nom
 * « SPECIMEN »), la PKI est générée à la volée, et le portrait est une silhouette synthétique.
 */
object SimulatedDocuments {
    /** CAN de la CNIe simulée. */
    const val SPECIMEN_CAN = "123456"

    const val SPECIMEN_DOCUMENT_NUMBER = "SPEC12345"
    val SPECIMEN_DATE_OF_BIRTH: LocalDate = LocalDate.of(1990, 1, 1)
    val SPECIMEN_DATE_OF_ISSUE: LocalDate = LocalDate.of(2024, 3, 15)
    val SPECIMEN_DATE_OF_EXPIRY: LocalDate = LocalDate.of(2034, 3, 14)

    /** Nom de ressource du portrait synthétique (JPEG 2000, voir testchip/README.md). */
    const val PORTRAIT_RESOURCE = "/specimen-portrait.jp2"
    const val PORTRAIT_WIDTH = 240
    const val PORTRAIT_HEIGHT = 320

    private const val FRENCH_PKI_SEED = 20_210_314L

    /** PKI de test « française » : CSCA-TEST-FRANCE (EC brainpoolP256r1), clés reproductibles. */
    fun frenchTestPki(seed: Long = FRENCH_PKI_SEED): TestPki =
        TestPki(TestKeyType.EC, name = "SCEAU TEST", country = "FR", seed = seed, cscaCommonName = "CSCA-TEST-FRANCE")

    /**
     * Carte nationale d'identité électronique française simulée (CNIe, format TD1) :
     *
     * - EF.CardAccess : `PACEInfo` id-PACE-ECDH-GM-AES-CBC-CMAC-128, brainpoolP256r1 ;
     * - DG1 : MRZ TD1 « ID », FRA, SPECIMEN / MARIANNE ;
     * - DG2 : portrait JPEG 2000 synthétique (ISO 19794-5) ;
     * - DG11 : nom complet, lieu de naissance et adresse fictifs ; DG12 : autorité et date de
     *   délivrance ;
     * - DG14 : clé de Chip Authentication ECDH brainpoolP256r1 (CA AES-128) et
     *   `ActiveAuthenticationInfo` ECDSA SHA-256 ; DG15 : clé d'Active Authentication EC ;
     * - EF.SOD : SHA-256, signé par un DS EC « DS-TEST-FRANCE » émis par CSCA-TEST-FRANCE.
     */
    fun frenchIdCard(
        can: String = SPECIMEN_CAN,
        pki: TestPki = frenchTestPki(),
    ): SimulatedIdentityDocument {
        val ds = pki.issueDs(dsKeyType = TestKeyType.EC, commonName = "DS-TEST-FRANCE")
        val groups = sortedMapOf<Int, ByteArray>()
        groups[DG1] = frenchDg1()
        groups[DG2] = portraitDg2()
        groups[DG11] = frenchDg11()
        groups[DG12] = frenchDg12()
        val caKeys = pki.generateEc(TestPki.CURVE)
        val aaKeys = pki.generateEc(TestPki.CURVE)
        val aaDigest = "SHA-256"
        groups[DG14] =
            DG14File(
                listOf<SecurityInfo>(
                    ChipAuthenticationPublicKeyInfo(caKeys.public),
                    ChipAuthenticationInfo(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128, CA_VERSION),
                    ActiveAuthenticationInfo(TestActiveAuthentication.ecdsaOid(aaDigest)),
                ),
            ).encoded
        groups[DG15] = DG15File(aaKeys.public).encoded
        val sod = TestSod.build(ds, groups, SodOptions(signingTime = TestCrypto.instant(SPECIMEN_DATE_OF_ISSUE)))
        val document =
            TestDocument(
                pki = pki,
                ds = ds,
                sod = sod,
                dataGroups = groups,
                caKeyPair = caKeys,
                aaKeyPair = aaKeys,
                aaDigestAlgorithm = aaDigest,
                dateOfIssue = SPECIMEN_DATE_OF_ISSUE,
                dateOfExpiry = SPECIMEN_DATE_OF_EXPIRY,
                documentCode = "ID",
            )
        return SimulatedIdentityDocument(document, PaceSettings(can), SPECIMEN_DOCUMENT_NUMBER, SPECIMEN_DATE_OF_BIRTH)
    }

    private const val GERMAN_PKI_SEED = 20_201_101L

    /** CAN de la carte eID-UB simulée. */
    const val EID_UB_CAN = "654321"

    /** PKI de test « allemande » : CSCA-TEST-GERMANY (EC brainpoolP256r1, pays DE), clés reproductibles. */
    fun germanTestPki(seed: Long = GERMAN_PKI_SEED): TestPki =
        TestPki(TestKeyType.EC, name = "SCEAU TEST", country = "DE", seed = seed, cscaCommonName = "CSCA-TEST-GERMANY")

    /**
     * Carte eID allemande pour citoyens de l'Union simulée (eID-UB, BSI TR-03127 v1.40, décision
     * D36) : l'application ICAO ne porte **aucune donnée d'identité**.
     *
     * - EF.CardAccess : même `PACEInfo` que la CNIe ; lecture avec le CAN [EID_UB_CAN] ;
     * - DG1 : MRZ TD1 de remplacement, écrite octet par octet ([eidUbDg1]) : code « UB », État
     *   « D », « < » partout ailleurs (ni nom, ni numéro, ni dates, ni chiffres de contrôle) ;
     * - DG2 : image statique, la même pour toutes les cartes (logo eID sur une vraie carte ; ici
     *   l'image synthétique des spécimens) ;
     * - DG14 : clé de Chip Authentication ECDH (CA AES-128). Ni DG15 (pas d'Active
     *   Authentication, TR-03127 §3.3), ni DG11, ni DG12 ;
     * - EF.SOD : SHA-256, sans `signingTime`, signé par « DS-TEST-GERMANY ». Sans aucune date
     *   (ni DG12, ni signingTime, ni date d'expiration), la validité du DS n'est pas évaluable.
     *
     * Seule la clé CAN permet la lecture : la clé MRZ de [SimulatedIdentityDocument] n'a pas de
     * sens pour ce document (numéro vide).
     */
    fun germanEidUb(
        can: String = EID_UB_CAN,
        pki: TestPki = germanTestPki(),
    ): SimulatedIdentityDocument {
        val ds = pki.issueDs(dsKeyType = TestKeyType.EC, commonName = "DS-TEST-GERMANY")
        val groups = sortedMapOf<Int, ByteArray>()
        groups[DG1] = eidUbDg1()
        groups[DG2] = portraitDg2()
        val caKeys = pki.generateEc(TestPki.CURVE)
        groups[DG14] =
            DG14File(
                listOf<SecurityInfo>(
                    ChipAuthenticationPublicKeyInfo(caKeys.public),
                    ChipAuthenticationInfo(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128, CA_VERSION),
                ),
            ).encoded
        val sod = TestSod.build(ds, groups, SodOptions(signingTime = null))
        val document =
            TestDocument(
                pki = pki,
                ds = ds,
                sod = sod,
                dataGroups = groups,
                caKeyPair = caKeys,
                aaKeyPair = null,
                aaDigestAlgorithm = null,
                dateOfIssue = null,
                dateOfExpiry = SPECIMEN_DATE_OF_EXPIRY,
                documentCode = "UB",
            )
        return SimulatedIdentityDocument(document, PaceSettings(can), documentNumber = "", dateOfBirth = SPECIMEN_DATE_OF_BIRTH)
    }

    /**
     * DG1 d'une eID-UB : tag 61, puis 5F1F et les 90 caractères de la MRZ TD1 « UBD<<<… ».
     * Écrit à la main : les constructeurs de MRZ de JMRTD calculent des chiffres de contrôle là
     * où la carte ne porte que des « < ».
     */
    fun eidUbDg1(): ByteArray {
        val mrz = "UBD".padEnd(TD1_MRZ_LENGTH, '<').toByteArray(Charsets.US_ASCII)
        val inner = byteArrayOf(TAG_MRZ_1, TAG_MRZ_2, TD1_MRZ_LENGTH.toByte()) + mrz
        return byteArrayOf(TAG_DG1, inner.size.toByte()) + inner
    }

    /** Octets du portrait synthétique JPEG 2000 embarqué. */
    fun specimenPortrait(): ByteArray =
        checkNotNull(SimulatedDocuments::class.java.getResourceAsStream(PORTRAIT_RESOURCE)) { PORTRAIT_RESOURCE }
            .use { it.readBytes() }

    private fun frenchDg1(): ByteArray {
        val mrz =
            MRZInfo.createTD1MRZInfo(
                "ID",
                "FRA",
                SPECIMEN_DOCUMENT_NUMBER,
                "",
                SPECIMEN_DATE_OF_BIRTH.format(YYMMDD),
                Gender.FEMALE,
                SPECIMEN_DATE_OF_EXPIRY.format(YYMMDD),
                "FRA",
                "",
                "SPECIMEN",
                "MARIANNE",
            )
        return DG1File(mrz).encoded
    }

    private fun portraitDg2(): ByteArray {
        val jp2 = specimenPortrait()
        val image =
            FaceImageInfo(
                Gender.FEMALE,
                FaceImageInfo.EyeColor.UNSPECIFIED,
                0,
                FaceImageInfo.HAIR_COLOR_UNSPECIFIED,
                FaceImageInfo.EXPRESSION_NEUTRAL.toInt(),
                IntArray(POSE_ANGLES),
                IntArray(POSE_ANGLES),
                FaceImageInfo.FACE_IMAGE_TYPE_FULL_FRONTAL,
                FaceImageInfo.IMAGE_COLOR_SPACE_RGB24,
                FaceImageInfo.SOURCE_TYPE_STATIC_PHOTO_DIGITAL_CAM,
                0,
                0,
                emptyArray(),
                PORTRAIT_WIDTH,
                PORTRAIT_HEIGHT,
                jp2.inputStream(),
                jp2.size,
                FaceImageInfo.IMAGE_DATA_TYPE_JPEG2000,
            )
        return DG2File.createISO19794DG2File(listOf(FaceInfo(listOf(image)))).encoded
    }

    private fun frenchDg11(): ByteArray =
        DG11File(
            "SPECIMEN<<MARIANNE",
            emptyList(),
            null,
            SPECIMEN_DATE_OF_BIRTH.format(YYYYMMDD),
            listOf("VILLE-SPECIMEN"),
            listOf("1 RUE DU SPECIMEN", "99999 VILLE-TEST"),
            null,
            null,
            null,
            null,
            null,
            emptyList(),
            null,
        ).encoded

    private fun frenchDg12(): ByteArray =
        DG12File(
            "PREFECTURE DE TEST",
            SPECIMEN_DATE_OF_ISSUE.format(YYYYMMDD),
            emptyList(),
            null,
            null,
            null,
            null,
            null,
            null,
        ).encoded

    private val YYMMDD = DateTimeFormatter.ofPattern("yyMMdd")
    private val YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd")
    private const val POSE_ANGLES = 3
    private const val CA_VERSION = 1
    private const val DG1 = 1
    private const val DG2 = 2
    private const val DG11 = 11
    private const val DG12 = 12
    private const val DG14 = 14
    private const val DG15 = 15
    private const val TD1_MRZ_LENGTH = 90
    private const val TAG_DG1: Byte = 0x61
    private const val TAG_MRZ_1: Byte = 0x5F
    private const val TAG_MRZ_2: Byte = 0x1F
}
