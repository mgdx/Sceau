package io.github.mgdx.sceau.testchip

import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.testchip.SimCrypto.Algorithm
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.jce.interfaces.ECPrivateKey
import org.jmrtd.lds.LDSFileUtil
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.icao.COMFile
import org.jmrtd.lds.icao.DG1File
import java.security.PrivateKey

/**
 * Puce ICAO 9303 simulée, à état, pour les tests de bout en bout de `readAndVerify`.
 *
 * Ce n'est pas un rejeu : la puce tire ses propres aléas, dérive ses clés de la MRZ de son DG1,
 * vérifie le MAC de chaque commande et chiffre ses réponses (voir [SimCrypto] et
 * [ChipSecureMessaging], écrits d'après ICAO 9303-11 sans le code de JMRTD).
 *
 * - Pas d'EF.CardAccess (6A82) : Sceau doit choisir BAC. Applet ICAO `A0 00 00 02 47 10 01`.
 * - BAC (ICAO 9303-11 §4.3) : GET CHALLENGE, EXTERNAL/MUTUAL AUTHENTICATE ; une clé MRZ fausse
 *   donne 6300. Puis messagerie sécurisée 3DES avec SSC = RND.ICC[4..8] ‖ RND.IFD[4..8].
 * - Fichiers : EF.COM (construit à partir de [comDataGroups]), EF.SOD, et les DG de
 *   [document]. Toute sélection de DG3 ou DG4 lève une [AssertionError], qui traverse Sceau
 *   et JMRTD (ils n'attrapent que des `Exception`) et fait échouer le test.
 * - Chip Authentication (ICAO 9303-11 §6.2, BSI TR-03110 §3.4) : MSE:Set KAT (3DES), ou
 *   MSE:Set AT + GENERAL AUTHENTICATE (AES), ECDH avec [caPrivateKey], puis nouvelle session
 *   de messagerie sécurisée (SSC = 0).
 * - Active Authentication : INTERNAL AUTHENTICATE sous messagerie sécurisée → [aaResponder].
 * - Retrait du document : dès que [removeAfterApdus] APDU ont été reçues, chaque APDU
 *   suivante lève [SceauException.ConnectionLost].
 *
 * Une commande sécurisée invalide (MAC faux) ou une commande en clair pendant la session
 * reçoit 6988 en clair et clôt la session, comme une vraie puce.
 */
class SimulatedChip(
    private val document: TestDocument,
    /** DG annoncés dans EF.COM ; par défaut, ceux du document. */
    comDataGroups: Collection<Int> = document.dataGroups.keys,
    /** Clé privée de Chip Authentication ; par défaut celle de DG14. */
    private val caPrivateKey: PrivateKey? = document.caKeyPair?.private,
    /** Réponse à INTERNAL AUTHENTICATE ; par défaut signée avec la clé de DG15. */
    private val aaResponder: ((ByteArray) -> ByteArray)? = document.aaKeyPair?.let { { challenge -> document.aaResponse(challenge) } },
    seed: Long = DEFAULT_SEED,
) : CardTransport {
    override val maxTransceiveLength: Int = MAX_TRANSCEIVE
    override var timeoutMillis: Int = DEFAULT_TIMEOUT

    /** Nombre d'APDU au-delà duquel le document est considéré comme retiré (null : jamais). */
    @Volatile
    var removeAfterApdus: Int? = null

    /** Nombre d'APDU reçues. */
    @Volatile
    var apduCount: Int = 0
        private set

    var closed: Boolean = false
        private set

    /** FID de chaque SELECT de fichier reçu (en clair ou sécurisé), dans l'ordre. */
    val selectedFids: List<Int> get() = selected.toList()

    /** Vrai si BAC a abouti. */
    var bacCompleted: Boolean = false
        private set

    /** Vrai si une Chip Authentication a abouti, et avec quel chiffrement. */
    var caAlgorithm: Algorithm? = null
        private set

    /** Challenges reçus en INTERNAL AUTHENTICATE. */
    val aaChallenges: List<ByteArray> get() = challenges.toList()

    private val random = TestCrypto.seededRandom(seed)
    private val selected = mutableListOf<Int>()
    private val challenges = mutableListOf<ByteArray>()
    private val files: Map<Int, ByteArray>
    private val bacSeed: ByteArray

    private var appletSelected = false
    private var rndIcc: ByteArray? = null
    private var session: ChipSecureMessaging? = null
    private var currentFile: ByteArray? = null
    private var pendingCaOid: String? = null

    init {
        val tags = comDataGroups.sorted().map(LDSFileUtil::lookupTagByDataGroupNumber).toIntArray()
        val lds = mutableMapOf(FID_COM to COMFile(LDS_VERSION, UNICODE_VERSION, tags).encoded, FID_SOD to document.sod)
        document.dataGroups.forEach { (number, bytes) -> lds[LDSFileUtil.lookupFIDByDataGroupNumber(number).toInt()] = bytes }
        files = lds
        // La puce dérive sa clé BAC de sa propre MRZ, pas de ce que saisit le lecteur.
        val mrz = DG1File(document.dataGroups.getValue(1).inputStream()).mrzInfo
        bacSeed = SimCrypto.bacKeySeed(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry)
    }

    override fun transceive(apdu: ByteArray): ByteArray {
        apduCount++
        removeAfterApdus?.let { if (apduCount > it) throw SceauException.ConnectionLost() }
        if (closed) throw SceauException.ConnectionLost()
        if (apdu.size < HEADER) return sw(SW_WRONG_LENGTH)

        val secured = apdu[0].toInt() and CLA_SM == CLA_SM
        val active = session
        if (!secured) {
            if (active != null) return abortSession()
            return respondPlain(parsePlain(apdu) ?: return sw(SW_WRONG_LENGTH))
        }
        if (active == null) return abortSession()
        val command =
            try {
                active.unwrap(apdu)
            } catch (e: SecureMessagingError) {
                return abortSession()
            }
        val reply = process(command)
        val response = active.wrap(reply.data, reply.sw)
        reply.next?.let { session = it }
        return response
    }

    override fun close() {
        closed = true
    }

    private class Reply(
        val data: ByteArray,
        val sw: Int,
        /** Session de messagerie sécurisée qui prend effet après l'envoi de cette réponse. */
        val next: ChipSecureMessaging? = null,
    )

    private fun respondPlain(command: PlainCommand): ByteArray {
        val reply = process(command)
        reply.next?.let { session = it }
        return reply.data + sw(reply.sw)
    }

    private fun abortSession(): ByteArray {
        session = null
        currentFile = null
        return sw(SW_SM_OBJECTS_INCORRECT)
    }

    private fun process(command: PlainCommand): Reply =
        when (command.ins) {
            INS_SELECT -> select(command)
            INS_READ_BINARY -> readBinary(command)
            INS_GET_CHALLENGE -> getChallenge(command)
            INS_EXTERNAL_AUTHENTICATE -> externalAuthenticate(command)
            INS_MSE -> manageSecurityEnvironment(command)
            INS_GENERAL_AUTHENTICATE -> generalAuthenticate(command)
            INS_INTERNAL_AUTHENTICATE -> internalAuthenticate(command)
            else -> Reply(ByteArray(0), SW_INS_NOT_SUPPORTED)
        }

    private fun select(command: PlainCommand): Reply {
        if (command.p2 != P2_NO_FCI) return status(SW_WRONG_P1P2)
        return when (command.p1) {
            P1_SELECT_BY_AID -> {
                if (!command.data.contentEquals(ICAO_AID)) return status(SW_FILE_NOT_FOUND)
                appletSelected = true
                currentFile = null
                status(SW_OK)
            }

            P1_SELECT_MF -> {
                appletSelected = false
                currentFile = null
                status(SW_OK)
            }

            P1_SELECT_EF -> {
                if (command.data.size != 2) return status(SW_WRONG_DATA)
                val fid = ((command.data[0].toInt() and 0xFF) shl Byte.SIZE_BITS) or (command.data[1].toInt() and 0xFF)
                selected += fid
                if (fid == FID_DG3 || fid == FID_DG4) {
                    throw AssertionError("Sceau a demandé DG${fid - FID_DG_BASE} (FID ${Integer.toHexString(fid)}) : interdit")
                }
                selectEf(fid, command.secured)
            }

            else -> {
                status(SW_WRONG_P1P2)
            }
        }
    }

    private fun selectEf(
        fid: Int,
        secured: Boolean,
    ): Reply {
        currentFile = null
        // EF.CardAccess absent : pas de PACE, Sceau doit se rabattre sur BAC.
        if (!appletSelected) return status(SW_FILE_NOT_FOUND)
        val content = files[fid] ?: return status(SW_FILE_NOT_FOUND)
        // Les fichiers de l'applet ne sont accessibles qu'après BAC, sous messagerie sécurisée.
        if (!secured) return status(SW_SECURITY_STATUS)
        currentFile = content
        return status(SW_OK)
    }

    private fun readBinary(command: PlainCommand): Reply {
        if (command.p1 and P1_SFI != 0) return status(SW_WRONG_P1P2)
        val content = currentFile ?: return status(SW_NO_CURRENT_EF)
        if (!command.secured) return status(SW_SECURITY_STATUS)
        val offset = (command.p1 shl Byte.SIZE_BITS) or command.p2
        if (offset > content.size) return status(SW_WRONG_P1P2)
        val length = if (command.ne == 0) SHORT_MAX_NE else command.ne
        val end = minOf(offset + length, content.size)
        return Reply(content.copyOfRange(offset, end), if (end - offset < length) SW_END_OF_FILE else SW_OK)
    }

    private fun getChallenge(command: PlainCommand): Reply {
        if (command.secured || command.ne != RND_LENGTH) return status(SW_WRONG_LENGTH)
        val challenge = ByteArray(RND_LENGTH).also(random::nextBytes)
        rndIcc = challenge
        return Reply(challenge, SW_OK)
    }

    /** EXTERNAL AUTHENTICATE de BAC (ICAO 9303-11 §9.7.2) : E.IFD ‖ M.IFD → E.ICC ‖ M.ICC. */
    private fun externalAuthenticate(command: PlainCommand): Reply {
        val challenge = rndIcc
        if (command.secured || !appletSelected || challenge == null) return status(SW_CONDITIONS)
        if (command.data.size != CRYPTOGRAM + SimCrypto.MAC_LENGTH) return status(SW_WRONG_DATA)
        val kEnc = SimCrypto.deriveKey(bacSeed, KDF_ENC, Algorithm.DESEDE, AES_128_BITS)
        val kMac = SimCrypto.deriveKey(bacSeed, KDF_MAC, Algorithm.DESEDE, AES_128_BITS)
        val eIfd = command.data.copyOf(CRYPTOGRAM)
        val mIfd = command.data.copyOfRange(CRYPTOGRAM, command.data.size)
        val expected = SimCrypto.mac(Algorithm.DESEDE, kMac, SimCrypto.pad(eIfd, Algorithm.DESEDE.blockSize))
        if (!expected.contentEquals(mIfd)) return status(SW_AUTHENTICATION_FAILED)
        val s = SimCrypto.cbc(false, Algorithm.DESEDE, kEnc, ByteArray(Algorithm.DESEDE.blockSize), eIfd)
        val rndIfd = s.copyOf(RND_LENGTH)
        if (!s.copyOfRange(RND_LENGTH, 2 * RND_LENGTH).contentEquals(challenge)) return status(SW_AUTHENTICATION_FAILED)
        val kIfd = s.copyOfRange(2 * RND_LENGTH, CRYPTOGRAM)
        rndIcc = null

        val kIcc = ByteArray(KEY_MATERIAL).also(random::nextBytes)
        val eIcc =
            SimCrypto.cbc(true, Algorithm.DESEDE, kEnc, ByteArray(Algorithm.DESEDE.blockSize), challenge + rndIfd + kIcc)
        val mIcc = SimCrypto.mac(Algorithm.DESEDE, kMac, SimCrypto.pad(eIcc, Algorithm.DESEDE.blockSize))

        val seed = ByteArray(KEY_MATERIAL) { (kIfd[it].toInt() xor kIcc[it].toInt()).toByte() }
        val ssc = (challenge.copyOfRange(RND_LENGTH / 2, RND_LENGTH) + rndIfd.copyOfRange(RND_LENGTH / 2, RND_LENGTH)).toLong()
        val next =
            ChipSecureMessaging(
                Algorithm.DESEDE,
                SimCrypto.deriveKey(seed, KDF_ENC, Algorithm.DESEDE, AES_128_BITS),
                SimCrypto.deriveKey(seed, KDF_MAC, Algorithm.DESEDE, AES_128_BITS),
                ssc,
            )
        bacCompleted = true
        return Reply(eIcc + mIcc, SW_OK, next)
    }

    /** MSE:Set KAT (CA 3DES) ou MSE:Set AT pour l'authentification interne (CA AES). */
    private fun manageSecurityEnvironment(command: PlainCommand): Reply {
        if (!command.secured) return status(SW_SECURITY_STATUS)
        val objects =
            try {
                Tlv.parseAll(command.data).associateBy { it.tag }
            } catch (e: SecureMessagingError) {
                return status(SW_WRONG_DATA)
            }
        return when ((command.p1 shl Byte.SIZE_BITS) or command.p2) {
            MSE_SET_KAT -> {
                val publicKey = objects[TAG_EPHEMERAL_KEY]?.value ?: return status(SW_WRONG_DATA)
                val secret = sharedSecret(publicKey) ?: return status(SW_WRONG_DATA)
                caAlgorithm = Algorithm.DESEDE
                Reply(ByteArray(0), SW_OK, caSession(secret, Algorithm.DESEDE, AES_128_BITS))
            }

            MSE_SET_AT_INTERNAL_AUTH -> {
                val oid = objects[TAG_MECHANISM]?.value ?: return status(SW_WRONG_DATA)
                val name = ASN1ObjectIdentifier.fromContents(oid).id
                if (name !in AES_KEY_LENGTHS) return status(SW_WRONG_DATA)
                pendingCaOid = name
                status(SW_OK)
            }

            else -> {
                status(SW_WRONG_P1P2)
            }
        }
    }

    /** GENERAL AUTHENTICATE de la CA AES : 7C { 80 clé éphémère } → 7C { }. */
    private fun generalAuthenticate(command: PlainCommand): Reply {
        val oid = pendingCaOid
        if (!command.secured || oid == null) return status(SW_CONDITIONS)
        pendingCaOid = null
        val publicKey =
            try {
                Tlv
                    .parseAll(command.data)
                    .singleOrNull { it.tag == TAG_DYNAMIC_AUTH }
                    ?.let { Tlv.parseAll(it.value).singleOrNull { inner -> inner.tag == TAG_MECHANISM } }
                    ?.value
            } catch (e: SecureMessagingError) {
                null
            } ?: return status(SW_WRONG_DATA)
        val secret = sharedSecret(publicKey) ?: return status(SW_WRONG_DATA)
        caAlgorithm = Algorithm.AES
        return Reply(Tlv(TAG_DYNAMIC_AUTH, ByteArray(0)).encoded, SW_OK, caSession(secret, Algorithm.AES, AES_KEY_LENGTHS.getValue(oid)))
    }

    /** ECDH : abscisse de d.ICC × Q.IFD, sur toute la taille du corps (BSI TR-03111 §4.3.1). */
    private fun sharedSecret(encodedPoint: ByteArray): ByteArray? {
        val key = caPrivateKey as? ECPrivateKey ?: return null
        val point =
            try {
                key.parameters.curve.decodePoint(encodedPoint)
            } catch (e: IllegalArgumentException) {
                return null
            }
        val shared = point.multiply(key.d).normalize()
        if (shared.isInfinity) return null
        return shared.affineXCoord.encoded
    }

    private fun caSession(
        secret: ByteArray,
        algorithm: Algorithm,
        keyBits: Int,
    ) = ChipSecureMessaging(
        algorithm,
        SimCrypto.deriveKey(secret, KDF_ENC, algorithm, keyBits),
        SimCrypto.deriveKey(secret, KDF_MAC, algorithm, keyBits),
        0,
    )

    private fun internalAuthenticate(command: PlainCommand): Reply {
        if (!command.secured) return status(SW_SECURITY_STATUS)
        val responder = aaResponder ?: return status(SW_INS_NOT_SUPPORTED)
        if (command.data.size != RND_LENGTH) return status(SW_WRONG_DATA)
        challenges += command.data.copyOf()
        return Reply(responder(command.data), SW_OK)
    }

    private fun parsePlain(apdu: ByteArray): PlainCommand? {
        val body = apdu.copyOfRange(HEADER, apdu.size)
        val (data, ne) =
            when {
                body.isEmpty() -> {
                    ByteArray(0) to 0
                }

                body.size == 1 -> {
                    ByteArray(0) to ((body[0].toInt() and 0xFF).let { if (it == 0) SHORT_MAX_NE else it })
                }

                else -> {
                    val lc = body[0].toInt() and 0xFF
                    when (body.size) {
                        1 + lc -> body.copyOfRange(1, 1 + lc) to 0
                        2 + lc -> body.copyOfRange(1, 1 + lc) to ((body[1 + lc].toInt() and 0xFF).let { if (it == 0) SHORT_MAX_NE else it })
                        else -> return null
                    }
                }
            }
        return PlainCommand(apdu[1].toInt() and 0xFF, apdu[2].toInt() and 0xFF, apdu[3].toInt() and 0xFF, data, ne, secured = false)
    }

    private fun status(sw: Int) = Reply(ByteArray(0), sw)

    private fun sw(sw: Int) = ChipSecureMessaging.swBytes(sw)

    private fun ByteArray.toLong(): Long = fold(0L) { acc, byte -> (acc shl Byte.SIZE_BITS) or (byte.toLong() and 0xFF) }

    companion object {
        const val DEFAULT_SEED = 9303L
        const val FID_COM = 0x011E
        const val FID_SOD = 0x011D
        const val FID_DG_BASE = 0x0100
        const val FID_DG3 = 0x0103
        const val FID_DG4 = 0x0104

        private val ICAO_AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01)
        private const val LDS_VERSION = "1.7"
        private const val UNICODE_VERSION = "4.0.0"
        private const val MAX_TRANSCEIVE = 261
        private const val DEFAULT_TIMEOUT = 1000

        private const val HEADER = 4
        private const val CLA_SM = 0x0C
        private const val INS_SELECT = 0xA4
        private const val INS_READ_BINARY = 0xB0
        private const val INS_GET_CHALLENGE = 0x84
        private const val INS_EXTERNAL_AUTHENTICATE = 0x82
        private const val INS_MSE = 0x22
        private const val INS_GENERAL_AUTHENTICATE = 0x86
        private const val INS_INTERNAL_AUTHENTICATE = 0x88
        private const val P1_SELECT_MF = 0x00
        private const val P1_SELECT_EF = 0x02
        private const val P1_SELECT_BY_AID = 0x04
        private const val P2_NO_FCI = 0x0C
        private const val P1_SFI = 0x80
        private const val MSE_SET_KAT = 0x41A6
        private const val MSE_SET_AT_INTERNAL_AUTH = 0x41A4
        private const val TAG_EPHEMERAL_KEY = 0x91
        private const val TAG_MECHANISM = 0x80
        private const val TAG_DYNAMIC_AUTH = 0x7C

        private const val RND_LENGTH = 8
        private const val KEY_MATERIAL = 16
        private const val CRYPTOGRAM = 32
        private const val SHORT_MAX_NE = 256
        private const val KDF_ENC = 1
        private const val KDF_MAC = 2
        private const val AES_128_BITS = 128
        private val AES_KEY_LENGTHS =
            mapOf(
                SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128 to 128,
                SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_192 to 192,
                SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_256 to 256,
            )

        private const val SW_OK = 0x9000
        private const val SW_END_OF_FILE = 0x6282
        private const val SW_AUTHENTICATION_FAILED = 0x6300
        private const val SW_WRONG_LENGTH = 0x6700
        private const val SW_SECURITY_STATUS = 0x6982
        private const val SW_CONDITIONS = 0x6985
        private const val SW_NO_CURRENT_EF = 0x6986
        private const val SW_SM_OBJECTS_INCORRECT = 0x6988
        private const val SW_WRONG_DATA = 0x6A80
        private const val SW_FILE_NOT_FOUND = 0x6A82
        private const val SW_WRONG_P1P2 = 0x6B00
        private const val SW_INS_NOT_SUPPORTED = 0x6D00
    }
}
