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
 * Ready-to-use simulated document: the signed document, its PKI, and what is needed to read it.
 *
 * @property document DGs, EF.SOD and the chip's private keys
 * @property pace PACE configuration of the chip (null: BAC only)
 */
class SimulatedIdentityDocument(
    val document: TestDocument,
    val pace: PaceSettings?,
    val documentNumber: String,
    val dateOfBirth: LocalDate,
) {
    val pki: TestPki get() = document.pki

    /** Trust store holding only this document's test CSCA. */
    val trustStore: TestTrustStore get() = pki.trustStore(pki.oldCsca)

    /** CAN key printed on the document; null without PACE. */
    val canKey: AccessKey.Can? get() = pace?.let { AccessKey.Can(it.can) }

    /** MRZ key of the document (number, date of birth, date of expiry). */
    val mrzKey: AccessKey.Mrz get() = AccessKey.Mrz(documentNumber, dateOfBirth, document.dateOfExpiry)

    /** New simulated chip for one read (a chip is stateful: one per read). */
    fun chip(): SimulatedChip = SimulatedChip(document, pace)
}

/**
 * Simulated document profiles. All identities are fictitious and say so (name
 * "SPECIMEN"), the PKI is generated on the fly, and the portrait is a synthetic silhouette.
 */
object SimulatedDocuments {
    /** CAN of the simulated CNIe. */
    const val SPECIMEN_CAN = "123456"

    const val SPECIMEN_DOCUMENT_NUMBER = "SPEC12345"
    val SPECIMEN_DATE_OF_BIRTH: LocalDate = LocalDate.of(1990, 1, 1)
    val SPECIMEN_DATE_OF_ISSUE: LocalDate = LocalDate.of(2024, 3, 15)
    val SPECIMEN_DATE_OF_EXPIRY: LocalDate = LocalDate.of(2034, 3, 14)

    /** Resource name of the synthetic portrait (JPEG 2000, see testchip/README.md). */
    const val PORTRAIT_RESOURCE = "/specimen-portrait.jp2"
    const val PORTRAIT_WIDTH = 240
    const val PORTRAIT_HEIGHT = 320

    /** Resource name of the synthetic signature (JPEG, see testchip/README.md). */
    const val SIGNATURE_RESOURCE = "/specimen-signature.jpg"

    private const val FRENCH_PKI_SEED = 20_210_314L

    /** "French" test PKI: CSCA-TEST-FRANCE (EC brainpoolP256r1), reproducible keys. */
    fun frenchTestPki(seed: Long = FRENCH_PKI_SEED): TestPki =
        TestPki(TestKeyType.EC, name = "SCEAU TEST", country = "FR", seed = seed, cscaCommonName = "CSCA-TEST-FRANCE")

    /**
     * Simulated French electronic national identity card (CNIe, TD1 format):
     *
     * - EF.CardAccess: `PACEInfo` id-PACE-ECDH-GM-AES-CBC-CMAC-128, brainpoolP256r1;
     * - DG1: TD1 MRZ "ID", FRA, SPECIMEN / MARIANNE;
     * - DG2: synthetic JPEG 2000 portrait (ISO 19794-5);
     * - DG7: synthetic handwritten signature (JPEG, D37);
     * - DG11: fictitious full name, place of birth and address; DG12: issuing authority and date
     *   of issue;
     * - DG14: ECDH brainpoolP256r1 Chip Authentication key (CA AES-128) and ECDSA SHA-256
     *   `ActiveAuthenticationInfo`; DG15: EC Active Authentication key;
     * - EF.SOD: SHA-256, signed by an EC DS "DS-TEST-FRANCE" issued by CSCA-TEST-FRANCE.
     */
    fun frenchIdCard(
        can: String = SPECIMEN_CAN,
        pki: TestPki = frenchTestPki(),
    ): SimulatedIdentityDocument {
        val ds = pki.issueDs(dsKeyType = TestKeyType.EC, commonName = "DS-TEST-FRANCE")
        val groups = sortedMapOf<Int, ByteArray>()
        groups[DG1] = frenchDg1()
        groups[DG2] = portraitDg2()
        groups[DG7] = TestDataGroups.dg7(specimenSignature())
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

    /** CAN of the simulated eID-UB card. */
    const val EID_UB_CAN = "654321"

    /** "German" test PKI: CSCA-TEST-GERMANY (EC brainpoolP256r1, country DE), reproducible keys. */
    fun germanTestPki(seed: Long = GERMAN_PKI_SEED): TestPki =
        TestPki(TestKeyType.EC, name = "SCEAU TEST", country = "DE", seed = seed, cscaCommonName = "CSCA-TEST-GERMANY")

    /**
     * Simulated German eID card for Union citizens (eID-UB, BSI TR-03127 v1.40, decision
     * D36): the ICAO application holds **no identity data**.
     *
     * - EF.CardAccess: same `PACEInfo` as the CNIe; read with the CAN [EID_UB_CAN];
     * - DG1: placeholder TD1 MRZ, written byte by byte ([eidUbDg1]): code "UB", state
     *   "D", "<" everywhere else (no name, number, dates or check digits);
     * - DG2: static image, the same for every card (eID logo on a real card; here the
     *   synthetic specimen image);
     * - DG14: ECDH Chip Authentication key (CA AES-128). No DG15 (no Active
     *   Authentication, TR-03127 §3.3), no DG11, no DG12;
     * - EF.SOD: SHA-256, without `signingTime`, signed by "DS-TEST-GERMANY". With no date at all
     *   (no DG12, no signingTime, no date of expiry), the DS validity cannot be assessed.
     *
     * Only the CAN key allows reading: the MRZ key of [SimulatedIdentityDocument] is
     * meaningless for this document (empty number).
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
     * DG1 of an eID-UB: tag 61, then 5F1F and the 90 characters of the TD1 MRZ "UBD<<<…".
     * Written by hand: the JMRTD MRZ builders compute check digits where the card only
     * holds "<".
     */
    fun eidUbDg1(): ByteArray {
        val mrz = "UBD".padEnd(TD1_MRZ_LENGTH, '<').toByteArray(Charsets.US_ASCII)
        val inner = byteArrayOf(TAG_MRZ_1, TAG_MRZ_2, TD1_MRZ_LENGTH.toByte()) + mrz
        return byteArrayOf(TAG_DG1, inner.size.toByte()) + inner
    }

    /** Bytes of the embedded synthetic JPEG 2000 portrait. */
    fun specimenPortrait(): ByteArray =
        checkNotNull(SimulatedDocuments::class.java.getResourceAsStream(PORTRAIT_RESOURCE)) { PORTRAIT_RESOURCE }
            .use { it.readBytes() }

    /** Bytes of the embedded synthetic JPEG signature (DG7). */
    fun specimenSignature(): ByteArray =
        checkNotNull(SimulatedDocuments::class.java.getResourceAsStream(SIGNATURE_RESOURCE)) { SIGNATURE_RESOURCE }
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
    private const val DG7 = 7
    private const val DG11 = 11
    private const val DG12 = 12
    private const val DG14 = 14
    private const val DG15 = 15
    private const val TD1_MRZ_LENGTH = 90
    private const val TAG_DG1: Byte = 0x61
    private const val TAG_MRZ_1: Byte = 0x5F
    private const val TAG_MRZ_2: Byte = 0x1F
}
