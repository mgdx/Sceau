package io.github.mgdx.sceau.testchip

import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Configuration PACE d'une puce simulée : ce qu'annonce son EF.CardAccess et le CAN imprimé
 * sur le document.
 *
 * Seul le mapping générique ECDH avec chiffrement AES est pris en charge
 * (id-PACE-ECDH-GM-AES-CBC-CMAC-128/192/256), avec des paramètres de domaine standardisés
 * (ICAO 9303-11 §9.5.1, identifiants 8 à 18). Par défaut : AES-128 sur brainpoolP256r1
 * (identifiant 13), comme la CNIe française.
 *
 * @property can numéro d'accès à la carte (6 chiffres sur une CNIe)
 * @property protocolOid OID du protocole PACE annoncé dans le `PACEInfo`
 * @property parameterId identifiant des paramètres de domaine standardisés
 */
class PaceSettings(
    val can: String,
    val protocolOid: String = ID_PACE_ECDH_GM_AES_CBC_CMAC_128,
    val parameterId: Int = PARAM_ID_BRAINPOOL_P256_R1,
) {
    init {
        require(protocolOid in AES_KEY_BITS) { "protocole PACE non simulé : $protocolOid" }
        require(parameterId in CURVES) { "paramètres de domaine non simulés : $parameterId" }
    }

    /**
     * Contenu d'EF.CardAccess (ICAO 9303-11 §9.2) : `SET OF SecurityInfo` réduit à un
     * `PACEInfo { protocol, version 2, parameterId }`, encodé en DER avec BouncyCastle.
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
        const val PARAM_ID_NIST_P256_R1 = 12
        const val PARAM_ID_BRAINPOOL_P256_R1 = 13

        private const val PACE_VERSION = 2L

        internal val AES_KEY_BITS =
            mapOf(
                ID_PACE_ECDH_GM_AES_CBC_CMAC_128 to 128,
                ID_PACE_ECDH_GM_AES_CBC_CMAC_192 to 192,
                ID_PACE_ECDH_GM_AES_CBC_CMAC_256 to 256,
            )

        /** Paramètres de domaine ECDH standardisés (ICAO 9303-11 §9.5.1, tableau 6). */
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

/** Mot de passe PACE choisi par le lecteur dans MSE:Set AT (ICAO 9303-11 §4.4.4.1, tag 83). */
enum class PacePassword(
    internal val reference: Int,
) {
    MRZ(1),
    CAN(2),
}

/**
 * PACE côté puce (ICAO 9303-11 §4.4, BSI TR-03110 partie 2 §3.2 et partie 3 §A.3, §B.1),
 * mapping générique ECDH, écrit avec l'arithmétique de courbes de BouncyCastle et sans le code
 * PACE de JMRTD :
 *
 * 1. MSE:Set AT (00 22 C1 A4) : OID du protocole (80), mot de passe (83 : 01 MRZ, 02 CAN),
 *    paramètres (84, facultatif).
 * 2. GENERAL AUTHENTICATE en quatre étapes chaînées (CLA 10, sauf la dernière) :
 *    - 7C{} → 7C{80 z}, z = E(Kπ, s) en AES-CBC à IV nul, Kπ = KDF(f(π), 3), s aléatoire ;
 *    - 7C{81 PK.PCD.map} → 7C{82 PK.PICC.map}, puis G' = s·G + SK.PICC.map·PK.PCD.map ;
 *    - 7C{83 PK.PCD} → 7C{84 PK.PICC}, clés éphémères sur G', K = x(SK.PICC·PK.PCD) ;
 *    - 7C{85 T.PCD} → 7C{86 T.PICC}, jetons AES-CMAC (8 octets) sur l'objet clé publique
 *      7F49{06 OID, 86 point} de l'autre partie, sous KSmac = KDF(K, 2).
 * 3. Messagerie sécurisée AES avec KSenc = KDF(K, 1), KSmac, SSC = 0.
 *
 * f(π) : les caractères du CAN (ISO 8859-1) ; pour la MRZ, SHA-1 des champs de la clé MRZ et de
 * leurs chiffres de contrôle (20 octets, non tronqué). Un jeton T.PCD faux (mauvais mot de passe)
 * donne 63Cx, x = essais restants, et remet PACE à zéro.
 */
internal class ChipPace(
    private val settings: PaceSettings,
    /** f(π) pour la clé MRZ : SHA-1 de l'information MRZ de la puce. */
    private val mrzSecret: ByteArray,
    private val random: SecureRandom,
) {
    /** Résultat d'une commande PACE : données de réponse, SW, et session ouverte en cas de succès. */
    class Outcome(
        val data: ByteArray,
        val sw: Int,
        val session: ChipSecureMessaging? = null,
    )

    private val domain: X9ECParameters = ECNamedCurveTable.getByName(PaceSettings.CURVES.getValue(settings.parameterId))
    private val keyBits = PaceSettings.AES_KEY_BITS.getValue(settings.protocolOid)
    private val oid = ASN1ObjectIdentifier(settings.protocolOid)

    /**
     * Compteur d'essais renvoyé dans le SW 63Cx après un jeton faux. La puce simulée ne se
     * bloque jamais (un CAN n'est pas suspendu) ; le compteur revient à [MAX_RETRIES] après succès.
     */
    var retries: Int = MAX_RETRIES
        private set

    /** Mot de passe de la dernière PACE réussie. */
    var completedWith: PacePassword? = null
        private set

    private var password: PacePassword? = null
    private var step = 0
    private var nonce: BigInteger? = null
    private var generator: ECPoint? = null
    private var piccPublic: ECPoint? = null
    private var pcdPublic: ECPoint? = null
    private var kMac: ByteArray? = null
    private var kEnc: ByteArray? = null

    val inProgress: Boolean get() = password != null

    /** MSE:Set AT de PACE. [objects] : objets TLV de la commande, par étiquette. */
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

    /** GENERAL AUTHENTICATE de PACE ; [last] : CLA sans chaînage (dernière étape attendue). */
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
        // La puce refuse une clé éphémère du lecteur identique à la sienne (TR-03110 partie 3 §A.3.2).
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
        val session = ChipSecureMessaging(SimCrypto.Algorithm.AES, checkNotNull(kEnc), macKey, 0)
        reset()
        retries = MAX_RETRIES
        completedWith = chosen
        return Outcome(Tlv(TAG_DYNAMIC_AUTH, Tlv(TAG_PICC_TOKEN, piccToken).encoded).encoded, SW_OK, session)
    }

    /** Objet clé publique ECDH de TR-03110 partie 3 §D.3.3, réduit à l'OID et au point (contexte connu). */
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

    private fun reset() {
        password = null
        step = 0
        nonce = null
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

        private const val SW_OK = 0x9000
        private const val SW_COUNTER = 0x63C0
        private const val SW_CONDITIONS = 0x6985
        private const val SW_WRONG_DATA = 0x6A80
        private const val SW_REFERENCE_NOT_FOUND = 0x6A88
    }
}
