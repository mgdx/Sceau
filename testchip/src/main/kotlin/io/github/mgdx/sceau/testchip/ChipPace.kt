package io.github.mgdx.sceau.testchip

import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.jce.interfaces.ECPrivateKey
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.SecureRandom

/**
 * PACE configuration of a simulated chip: what its EF.CardAccess announces and the CAN printed
 * on the document.
 *
 * Supported mappings, ECDH with AES encryption: generic
 * (id-PACE-ECDH-GM-AES-CBC-CMAC-128/192/256) and Chip Authentication Mapping
 * (id-PACE-ECDH-CAM-AES-CBC-CMAC-128/192/256, see [isChipAuthenticationMapping]), with
 * standardized domain parameters (ICAO 9303-11 §9.5.1, identifiers 8 to 18). Default:
 * generic mapping, AES-128 on brainpoolP256r1 (identifier 13), like the French CNIe.
 *
 * @property can card access number (6 digits on a CNIe)
 * @property protocolOid OID of the PACE protocol announced in the `PACEInfo`
 * @property parameterId identifier of the standardized domain parameters
 */
class PaceSettings(
    val can: String,
    val protocolOid: String = ID_PACE_ECDH_GM_AES_CBC_CMAC_128,
    val parameterId: Int = PARAM_ID_BRAINPOOL_P256_R1,
) {
    init {
        require(protocolOid in AES_KEY_BITS) { "PACE protocol not simulated: $protocolOid" }
        require(parameterId in CURVES) { "domain parameters not simulated: $parameterId" }
    }

    /**
     * PACE-CAM mapping (ICAO 9303-11 §4.4.3.3.3): at the last step, the chip also returns its
     * encrypted Chip Authentication data, and serves EF.CardSecurity at the MF.
     */
    val isChipAuthenticationMapping: Boolean get() = protocolOid in CAM_PROTOCOLS

    /**
     * Content of EF.CardAccess (ICAO 9303-11 §9.2): `SET OF SecurityInfo` reduced to a single
     * `PACEInfo { protocol, version 2, parameterId }`, DER-encoded with BouncyCastle.
     */
    val cardAccess: ByteArray
        get() {
            val paceInfo = ASN1EncodableVector()
            paceInfo.add(ASN1ObjectIdentifier(protocolOid))
            paceInfo.add(ASN1Integer(PACE_VERSION))
            paceInfo.add(ASN1Integer(parameterId.toLong()))
            return DERSet(DERSequence(paceInfo)).getEncoded(ASN1Encoding.DER)
        }

    companion object {
        const val ID_PACE_ECDH_GM_AES_CBC_CMAC_128 = "0.4.0.127.0.7.2.2.4.2.2"
        const val ID_PACE_ECDH_GM_AES_CBC_CMAC_192 = "0.4.0.127.0.7.2.2.4.2.3"
        const val ID_PACE_ECDH_GM_AES_CBC_CMAC_256 = "0.4.0.127.0.7.2.2.4.2.4"
        const val ID_PACE_ECDH_CAM_AES_CBC_CMAC_128 = "0.4.0.127.0.7.2.2.4.6.2"
        const val ID_PACE_ECDH_CAM_AES_CBC_CMAC_192 = "0.4.0.127.0.7.2.2.4.6.3"
        const val ID_PACE_ECDH_CAM_AES_CBC_CMAC_256 = "0.4.0.127.0.7.2.2.4.6.4"
        const val PARAM_ID_NIST_P256_R1 = 12
        const val PARAM_ID_BRAINPOOL_P256_R1 = 13

        private const val PACE_VERSION = 2L

        private val CAM_PROTOCOLS =
            setOf(ID_PACE_ECDH_CAM_AES_CBC_CMAC_128, ID_PACE_ECDH_CAM_AES_CBC_CMAC_192, ID_PACE_ECDH_CAM_AES_CBC_CMAC_256)

        internal val AES_KEY_BITS =
            mapOf(
                ID_PACE_ECDH_GM_AES_CBC_CMAC_128 to 128,
                ID_PACE_ECDH_GM_AES_CBC_CMAC_192 to 192,
                ID_PACE_ECDH_GM_AES_CBC_CMAC_256 to 256,
                ID_PACE_ECDH_CAM_AES_CBC_CMAC_128 to 128,
                ID_PACE_ECDH_CAM_AES_CBC_CMAC_192 to 192,
                ID_PACE_ECDH_CAM_AES_CBC_CMAC_256 to 256,
            )

        /** Standardized ECDH domain parameters (ICAO 9303-11 §9.5.1, table 6). */
        internal val CURVES =
            mapOf(
                8 to "secp192r1",
                9 to "brainpoolP192r1",
                10 to "secp224r1",
                11 to "brainpoolP224r1",
                PARAM_ID_NIST_P256_R1 to "secp256r1",
                PARAM_ID_BRAINPOOL_P256_R1 to "brainpoolP256r1",
                14 to "brainpoolP320r1",
                15 to "secp384r1",
                16 to "brainpoolP384r1",
                17 to "brainpoolP512r1",
                18 to "secp521r1",
            )
    }
}

/** PACE password chosen by the reader in MSE:Set AT (ICAO 9303-11 §4.4.4.1, tag 83). */
enum class PacePassword(
    internal val reference: Int,
) {
    MRZ(1),
    CAN(2),
}

/**
 * Chip-side PACE (ICAO 9303-11 §4.4, BSI TR-03110 part 2 §3.2 and part 3 §A.3, §B.1),
 * ECDH generic mapping, written with BouncyCastle's curve arithmetic and without JMRTD's PACE
 * code:
 *
 * 1. MSE:Set AT (00 22 C1 A4): protocol OID (80), password (83: 01 MRZ, 02 CAN),
 *    parameters (84, optional).
 * 2. GENERAL AUTHENTICATE in four chained steps (CLA 10, except the last one):
 *    - 7C{} → 7C{80 z}, z = E(Kπ, s) in AES-CBC with a zero IV, Kπ = KDF(f(π), 3), s random;
 *    - 7C{81 PK.PCD.map} → 7C{82 PK.PICC.map}, then G' = s·G + SK.PICC.map·PK.PCD.map;
 *    - 7C{83 PK.PCD} → 7C{84 PK.PICC}, ephemeral keys on G', K = x(SK.PICC·PK.PCD);
 *    - 7C{85 T.PCD} → 7C{86 T.PICC}, AES-CMAC tokens (8 bytes) over the other party's
 *      public key object 7F49{06 OID, 86 point}, under KSmac = KDF(K, 2).
 * 3. AES secure messaging with KSenc = KDF(K, 1), KSmac, SSC = 0.
 *
 * f(π): the CAN characters (ISO 8859-1); for the MRZ, SHA-1 of the MRZ key fields and their
 * check digits (20 bytes, not truncated). A wrong T.PCD token (wrong password) gives 63Cx,
 * x = remaining tries, and resets PACE.
 *
 * PACE-CAM (ICAO 9303-11 §4.4.3.3.3): the last response also contains
 * 8A A.IC, A.IC = E(KSenc, CA.IC) in AES-CBC with IV = E(KSenc, −1) (all-ones block) and
 * ISO/IEC 9797-1 padding method 2, CA.IC = SK.IC⁻¹ · SK.PICC.map mod n, where SK.IC is
 * the chip's static private key ([camKey]) and SK.PICC.map its mapping key: the reader
 * checks PK.PICC.map = CA.IC · PK.IC.
 */
internal class ChipPace(
    private val settings: PaceSettings,
    /** f(π) for the MRZ key: SHA-1 of the chip's MRZ information. */
    private val mrzSecret: ByteArray,
    private val random: SecureRandom,
    /** Static Chip Authentication private key, for PACE-CAM (same curve as PACE). */
    private val camKey: ECPrivateKey? = null,
    /** PACE-CAM: the chip sends CA.IC + 1 instead of CA.IC (a chip that does not hold SK.IC). */
    private val tamperCam: Boolean = false,
) {
    /** Outcome of a PACE command: response data, SW, and the session opened on success. */
    class Outcome(
        val data: ByteArray,
        val sw: Int,
        val session: ChipSecureMessaging? = null,
    )

    private val domain: X9ECParameters = ECNamedCurveTable.getByName(PaceSettings.CURVES.getValue(settings.parameterId))
    private val keyBits = PaceSettings.AES_KEY_BITS.getValue(settings.protocolOid)
    private val oid = ASN1ObjectIdentifier(settings.protocolOid)

    /**
     * Retry counter returned in SW 63Cx after a wrong token. The simulated chip never blocks
     * (a CAN is not suspended); the counter goes back to [MAX_RETRIES] after a success.
     */
    var retries: Int = MAX_RETRIES
        private set

    /** Password of the last successful PACE. */
    var completedWith: PacePassword? = null
        private set

    private var password: PacePassword? = null
    private var step = 0
    private var nonce: BigInteger? = null
    private var mappingSecret: BigInteger? = null
    private var generator: ECPoint? = null
    private var piccPublic: ECPoint? = null
    private var pcdPublic: ECPoint? = null
    private var kMac: ByteArray? = null
    private var kEnc: ByteArray? = null

    val inProgress: Boolean get() = password != null

    /** PACE MSE:Set AT. [objects]: TLV objects of the command, by tag. */
    fun setAuthenticationTemplate(objects: Map<Int, Tlv>): Int {
        reset()
        val protocol = objects[TAG_OID]?.value ?: return SW_WRONG_DATA
        if (!ASN1ObjectIdentifier.fromContents(protocol).equals(oid)) return SW_WRONG_DATA
        val reference = objects[TAG_PASSWORD]?.value?.singleOrNull()?.toInt() ?: return SW_WRONG_DATA
        val chosen = PacePassword.entries.firstOrNull { it.reference == reference } ?: return SW_REFERENCE_NOT_FOUND
        objects[TAG_DOMAIN]?.let { parameter ->
            if (BigInteger(1, parameter.value).toInt() != settings.parameterId) return SW_WRONG_DATA
        }
        password = chosen
        return SW_OK
    }

    /** PACE GENERAL AUTHENTICATE; [last]: CLA without chaining (last step expected). */
    fun generalAuthenticate(
        data: ByteArray,
        last: Boolean,
    ): Outcome {
        val chosen = password ?: return status(SW_CONDITIONS)
        val content =
            try {
                Tlv.parseAll(data).singleOrNull { it.tag == TAG_DYNAMIC_AUTH }?.let { Tlv.parseAll(it.value) }
            } catch (e: SecureMessagingError) {
                null
            } ?: return abort(SW_WRONG_DATA)
        if (last != (step == LAST_STEP)) return abort(SW_CONDITIONS)
        return when (step) {
            0 -> encryptedNonce(content, chosen)
            1 -> mapping(content)
            2 -> keyAgreement(content)
            else -> mutualAuthentication(content, chosen)
        }
    }

    private fun encryptedNonce(
        content: List<Tlv>,
        chosen: PacePassword,
    ): Outcome {
        if (content.isNotEmpty()) return abort(SW_WRONG_DATA)
        val secret =
            when (chosen) {
                PacePassword.CAN -> settings.can.toByteArray(Charsets.ISO_8859_1)
                PacePassword.MRZ -> mrzSecret
            }
        val kPi = SimCrypto.deriveKey(secret, KDF_PI, SimCrypto.Algorithm.AES, keyBits)
        val s = ByteArray(NONCE_LENGTH).also(random::nextBytes)
        nonce = BigInteger(1, s)
        val z = SimCrypto.cbc(true, SimCrypto.Algorithm.AES, kPi, ByteArray(NONCE_LENGTH), s)
        step = 1
        return reply(Tlv(TAG_NONCE, z))
    }

    private fun mapping(content: List<Tlv>): Outcome {
        val pcdMapping = point(content, TAG_PCD_MAPPING) ?: return abort(SW_WRONG_DATA)
        val secret = randomScalar()
        val shared = pcdMapping.multiply(secret).normalize()
        if (shared.isInfinity) return abort(SW_WRONG_DATA)
        mappingSecret = secret
        generator =
            domain.g
                .multiply(checkNotNull(nonce))
                .add(shared)
                .normalize()
        step = 2
        return reply(
            Tlv(
                TAG_PICC_MAPPING,
                domain.g
                    .multiply(secret)
                    .normalize()
                    .getEncoded(false),
            ),
        )
    }

    private fun keyAgreement(content: List<Tlv>): Outcome {
        val pcd = point(content, TAG_PCD_KEY) ?: return abort(SW_WRONG_DATA)
        val secret = randomScalar()
        val picc = checkNotNull(generator).multiply(secret).normalize()
        // The chip rejects a reader ephemeral key identical to its own (TR-03110 part 3 §A.3.2).
        if (picc == pcd) return abort(SW_WRONG_DATA)
        val shared = pcd.multiply(secret).normalize()
        if (shared.isInfinity) return abort(SW_WRONG_DATA)
        val k = shared.affineXCoord.encoded
        kEnc = SimCrypto.deriveKey(k, KDF_ENC, SimCrypto.Algorithm.AES, keyBits)
        kMac = SimCrypto.deriveKey(k, KDF_MAC, SimCrypto.Algorithm.AES, keyBits)
        piccPublic = picc
        pcdPublic = pcd
        step = LAST_STEP
        return reply(Tlv(TAG_PICC_KEY, picc.getEncoded(false)))
    }

    private fun mutualAuthentication(
        content: List<Tlv>,
        chosen: PacePassword,
    ): Outcome {
        val token = content.singleOrNull { it.tag == TAG_PCD_TOKEN }?.value ?: return abort(SW_WRONG_DATA)
        val macKey = checkNotNull(kMac)
        val expected = SimCrypto.cmac(macKey, publicKeyDataObject(checkNotNull(piccPublic)))
        if (!expected.contentEquals(token)) {
            retries = (retries - 1).coerceAtLeast(0)
            return abort(SW_COUNTER or retries)
        }
        val piccToken = SimCrypto.cmac(macKey, publicKeyDataObject(checkNotNull(pcdPublic)))
        val encKey = checkNotNull(kEnc)
        var response = Tlv(TAG_PICC_TOKEN, piccToken).encoded
        if (settings.isChipAuthenticationMapping) {
            response += Tlv(TAG_CAM_DATA, encryptedChipAuthenticationData(encKey)).encoded
        }
        val session = ChipSecureMessaging(SimCrypto.Algorithm.AES, encKey, macKey, 0, SessionKind.PACE)
        reset()
        retries = MAX_RETRIES
        completedWith = chosen
        return Outcome(Tlv(TAG_DYNAMIC_AUTH, response).encoded, SW_OK, session)
    }

    /** A.IC = E(KSenc, CA.IC), IV = E(KSenc, −1), CA.IC = SK.IC⁻¹ · SK.PICC.map mod n. */
    private fun encryptedChipAuthenticationData(encKey: ByteArray): ByteArray {
        val staticKey = checkNotNull(camKey) { "PACE-CAM without a Chip Authentication key" }
        require(staticKey.parameters.curve.equals(domain.curve)) { "CA key and PACE on different curves" }
        val order = domain.n
        var caIc =
            staticKey.d
                .modInverse(order)
                .multiply(checkNotNull(mappingSecret))
                .mod(order)
        if (tamperCam) caIc = caIc.add(BigInteger.ONE).mod(order)
        val length = (order.bitLength() + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS
        val bytes = caIc.toByteArray()
        val encoded =
            if (bytes.size >
                length
            ) {
                bytes.copyOfRange(bytes.size - length, bytes.size)
            } else {
                ByteArray(length - bytes.size) + bytes
            }
        val iv = SimCrypto.aesBlock(encKey, ByteArray(NONCE_LENGTH) { ALL_BITS })
        return SimCrypto.cbc(true, SimCrypto.Algorithm.AES, encKey, iv, SimCrypto.pad(encoded, NONCE_LENGTH))
    }

    /** ECDH public key object of TR-03110 part 3 §D.3.3, reduced to the OID and the point (known context). */
    private fun publicKeyDataObject(point: ECPoint): ByteArray {
        val value = oid.getEncoded(ASN1Encoding.DER) + Tlv(TAG_EC_POINT, point.getEncoded(false)).encoded
        return byteArrayOf(0x7F, 0x49) + Tlv.encodeLength(value.size) + value
    }

    private fun point(
        content: List<Tlv>,
        tag: Int,
    ): ECPoint? {
        val encoded = content.singleOrNull { it.tag == tag }?.value ?: return null
        val point =
            try {
                domain.curve.decodePoint(encoded).normalize()
            } catch (e: IllegalArgumentException) {
                return null
            }
        return point.takeUnless { it.isInfinity || !it.isValid }
    }

    private fun randomScalar(): BigInteger {
        val order = domain.n
        var value: BigInteger
        do {
            value = BigInteger(order.bitLength(), random)
        } while (value.signum() == 0 || value >= order)
        return value
    }

    private fun reply(tlv: Tlv) = Outcome(Tlv(TAG_DYNAMIC_AUTH, tlv.encoded).encoded, SW_OK)

    private fun status(sw: Int) = Outcome(ByteArray(0), sw)

    private fun abort(sw: Int): Outcome {
        reset()
        return status(sw)
    }

    /** Aborts a PACE in progress (error, or link reset). */
    fun reset() {
        password = null
        step = 0
        nonce = null
        mappingSecret = null
        generator = null
        piccPublic = null
        pcdPublic = null
        kEnc = null
        kMac = null
    }

    companion object {
        const val MAX_RETRIES = 3

        private const val LAST_STEP = 3
        private const val NONCE_LENGTH = 16
        private const val KDF_ENC = 1
        private const val KDF_MAC = 2
        private const val KDF_PI = 3

        private const val TAG_OID = 0x80
        private const val TAG_PASSWORD = 0x83
        private const val TAG_DOMAIN = 0x84
        private const val TAG_DYNAMIC_AUTH = 0x7C
        private const val TAG_NONCE = 0x80
        private const val TAG_PCD_MAPPING = 0x81
        private const val TAG_PICC_MAPPING = 0x82
        private const val TAG_PCD_KEY = 0x83
        private const val TAG_PICC_KEY = 0x84
        private const val TAG_PCD_TOKEN = 0x85
        private const val TAG_PICC_TOKEN = 0x86
        private const val TAG_EC_POINT = 0x86
        private const val TAG_CAM_DATA = 0x8A
        private const val ALL_BITS: Byte = -1

        private const val SW_OK = 0x9000
        private const val SW_COUNTER = 0x63C0
        private const val SW_CONDITIONS = 0x6985
        private const val SW_WRONG_DATA = 0x6A80
        private const val SW_REFERENCE_NOT_FOUND = 0x6A88
    }
}
