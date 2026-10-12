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
 * Simulated stateful ICAO 9303 chip, for end-to-end tests of `readAndVerify`.
 *
 * This is not a replay: the chip draws its own random values, derives its keys from the MRZ of
 * its DG1, checks the MAC of each command and encrypts its responses (see [SimCrypto] and
 * [ChipSecureMessaging], written from ICAO 9303-11 without JMRTD code).
 *
 * - Without [pace], no EF.CardAccess (6A82): Sceau must choose BAC. With [pace], EF.CardAccess
 *   (011C) is readable in plain at MF level and announces a `PACEInfo`; PACE (see [ChipPace])
 *   opens AES secure messaging, under which the applet is then selected.
 *   ICAO applet `A0 00 00 02 47 10 01`.
 * - PACE-CAM (CAM mapping in [pace]): the chip proves it holds [caPrivateKey] during PACE, and
 *   serves [cardSecurity] (011D at the MF) under secure messaging only.
 * - BAC (ICAO 9303-11 §4.3): GET CHALLENGE, EXTERNAL/MUTUAL AUTHENTICATE; a wrong MRZ key
 *   gives 6300. Then 3DES secure messaging with SSC = RND.ICC[4..8] ‖ RND.IFD[4..8]. BAC
 *   is still accepted when PACE is announced, as ICAO requires during the transition.
 * - Files: EF.COM (built from [comDataGroups]), EF.SOD, and the DGs of [document].
 *   Any selection of DG3 or DG4 throws an [AssertionError], which goes through Sceau
 *   and JMRTD (they only catch `Exception`) and fails the test.
 * - Chip Authentication (ICAO 9303-11 §6.2, BSI TR-03110 §3.4): MSE:Set KAT (3DES), or
 *   MSE:Set AT + GENERAL AUTHENTICATE (AES), ECDH with [caPrivateKey], then a new secure
 *   messaging session (SSC = 0).
 *   [refuseChipAuthentication]: the chip answers 6A80 to the CA MSE, without changing session.
 * - Access restricted to government terminals (D36): [restrictedApplet] (6982 when the applet
 *   is selected) and [restrictedFiles] (6982 when these files are selected, even under secure
 *   messaging).
 * - Active Authentication: INTERNAL AUTHENTICATE under secure messaging → [aaResponder].
 * - Document removal: once [removeAfterApdus] APDUs have been received, each following APDU
 *   throws [SceauException.ConnectionLost].
 * - [reconnect] (link reset): the chip starts over, with no session and no applet
 *   selected; [reconnections] counts them.
 * - [fileReads] records, for each READ BINARY served, the file and the session under which it
 *   was served: the tests thus check which data are read under the CA keys.
 *
 * An invalid secured command (wrong MAC) or a plain command during the session receives
 * 6988 in plain and closes the session, like a real chip.
 */
class SimulatedChip(
    private val document: TestDocument,
    /** PACE announced in EF.CardAccess; null: no EF.CardAccess, BAC only. */
    pace: PaceSettings? = null,
    /** DGs announced in EF.COM; by default, those of the document. */
    comDataGroups: Collection<Int> = document.dataGroups.keys,
    /** Chip Authentication private key; by default the DG14 one. */
    private val caPrivateKey: PrivateKey? = document.caKeyPair?.private,
    /** Response to INTERNAL AUTHENTICATE; by default signed with the DG15 key. */
    private val aaResponder: ((ByteArray) -> ByteArray)? = document.aaKeyPair?.let { { challenge -> document.aaResponse(challenge) } },
    seed: Long = DEFAULT_SEED,
    /**
     * Applet files served by a hostile chip, by FID: their header announces an oversized
     * length (see [OversizedFile]). They replace the file with the same FID.
     */
    private val oversizedFiles: Map<Int, OversizedFile> = emptyMap(),
    /**
     * DGs the chip refuses to provide (6A82 on selection) although they are in the SOD:
     * a clone that withholds DG14/DG15 to evade CA and AA.
     */
    withheldDataGroups: Collection<Int> = emptySet(),
    /** The chip refuses Chip Authentication (6A80 to MSE:Set KAT or MSE:Set AT). */
    private val refuseChipAuthentication: Boolean = false,
    /**
     * EF.CardSecurity (FID 011D at the MF), served under secure messaging after PACE (PACE-CAM;
     * see [TestCardSecurity]). Null: absent.
     */
    private val cardSecurity: ByteArray? = null,
    /** PACE-CAM: the chip sends wrong Chip Authentication data (CA.IC + 1). */
    tamperChipAuthenticationMapping: Boolean = false,
    /**
     * ICAO application restricted to government terminals (German identity card issued
     * before 2021-08-02, decision D36): 6982 on its selection, in plain as well as under secure
     * messaging.
     */
    private val restrictedApplet: Boolean = false,
    /** FIDs of applet files restricted to government terminals: 6982 on their selection, even under secure messaging (D36). */
    private val restrictedFiles: Collection<Int> = emptySet(),
    /**
     * Hostile chip (audit V24): 6982 on applet selection after a link reset ([reconnect]),
     * to pass itself off as a card restricted to the authorities when the channel is
     * re-established after a failed Chip Authentication.
     */
    private val restrictAppletAfterReconnect: Boolean = false,
) : CardTransport {
    /** File read served by the chip: FID, and the session it was served under (null: in plain). */
    data class FileRead(
        val fid: Int,
        val session: SessionKind?,
    )

    override val maxTransceiveLength: Int = MAX_TRANSCEIVE
    override var timeoutMillis: Int = DEFAULT_TIMEOUT

    /** Number of APDUs beyond which the document is considered removed (null: never). */
    @Volatile
    var removeAfterApdus: Int? = null

    /** Number of APDUs received. */
    @Volatile
    var apduCount: Int = 0
        private set

    var closed: Boolean = false
        private set

    /** FID of each file SELECT received (plain or secured), in order. */
    val selectedFids: List<Int> get() = selected.toList()

    /** True if BAC succeeded. */
    var bacCompleted: Boolean = false
        private set

    /** Password (CAN or MRZ) of the last successful PACE, null if PACE did not succeed. */
    val paceCompletedWith: PacePassword? get() = chipPace?.completedWith

    /** PACE retry counter (63Cx); null without PACE. */
    val paceRetries: Int? get() = chipPace?.retries

    /** Set if a Chip Authentication succeeded, with the cipher used. */
    var caAlgorithm: Algorithm? = null
        private set

    /** Challenges received in INTERNAL AUTHENTICATE. */
    val aaChallenges: List<ByteArray> get() = challenges.toList()

    /** READ BINARY commands served (data returned), in order. */
    val fileReads: List<FileRead> get() = reads.toList()

    /** Sessions under which the file [fid] was served. */
    fun sessionsServing(fid: Int): Set<SessionKind?> = reads.filter { it.fid == fid }.map { it.session }.toSet()

    /** Number of link resets ([reconnect]). */
    var reconnections: Int = 0
        private set

    private val random = TestCrypto.seededRandom(seed)
    private val selected = mutableListOf<Int>()
    private val reads = mutableListOf<FileRead>()
    private var currentFid: Int? = null
    private val challenges = mutableListOf<ByteArray>()
    private val files: Map<Int, ByteArray>
    private val bacSeed: ByteArray
    private val cardAccess: ByteArray? = pace?.cardAccess
    private val chipPace: ChipPace?

    private var appletSelected = false
    private var rndIcc: ByteArray? = null
    private var session: ChipSecureMessaging? = null
    private var currentFile: ByteArray? = null
    private var currentOversized: OversizedFile? = null

    /** The current file is EF.CardAccess, readable without secure messaging. */
    private var currentFileIsPublic = false
    private var pendingCaOid: String? = null

    init {
        val tags = comDataGroups.sorted().map(LDSFileUtil::lookupTagByDataGroupNumber).toIntArray()
        val lds = mutableMapOf(FID_COM to COMFile(LDS_VERSION, UNICODE_VERSION, tags).encoded, FID_SOD to document.sod)
        document.dataGroups
            .filterKeys { it !in withheldDataGroups }
            .forEach { (number, bytes) -> lds[LDSFileUtil.lookupFIDByDataGroupNumber(number).toInt()] = bytes }
        files = lds
        // The chip derives its BAC key from its own MRZ, not from what the reader enters.
        val mrz = DG1File(document.dataGroups.getValue(1).inputStream()).mrzInfo
        bacSeed = SimCrypto.bacKeySeed(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry)
        chipPace =
            pace?.let {
                ChipPace(
                    it,
                    SimCrypto.mrzPassword(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry),
                    random,
                    camKey = if (it.isChipAuthenticationMapping) caPrivateKey as? ECPrivateKey else null,
                    tamperCam = tamperChipAuthenticationMapping,
                )
            }
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

    /** Link reset (field turned off then back on): no more session, applet or current file. */
    override fun reconnect() {
        reconnections++
        session = null
        appletSelected = false
        rndIcc = null
        pendingCaOid = null
        chipPace?.reset()
        clearCurrentFile()
    }

    private fun clearCurrentFile() {
        currentFile = null
        currentFid = null
        currentOversized = null
        currentFileIsPublic = false
    }

    private class Reply(
        val data: ByteArray,
        val sw: Int,
        /** Secure messaging session that takes effect after this response is sent. */
        val next: ChipSecureMessaging? = null,
    )

    private fun respondPlain(command: PlainCommand): ByteArray {
        val reply = process(command)
        reply.next?.let { session = it }
        return reply.data + sw(reply.sw)
    }

    private fun abortSession(): ByteArray {
        session = null
        clearCurrentFile()
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
                if (restrictedApplet || (restrictAppletAfterReconnect && reconnections > 0)) return status(SW_SECURITY_STATUS)
                appletSelected = true
                clearCurrentFile()
                status(SW_OK)
            }

            P1_SELECT_MF -> {
                appletSelected = false
                clearCurrentFile()
                status(SW_OK)
            }

            P1_SELECT_EF -> {
                if (command.data.size != 2) return status(SW_WRONG_DATA)
                val fid = ((command.data[0].toInt() and 0xFF) shl Byte.SIZE_BITS) or (command.data[1].toInt() and 0xFF)
                selected += fid
                if (fid == FID_DG3 || fid == FID_DG4) {
                    throw AssertionError("Sceau requested DG${fid - FID_DG_BASE} (FID ${Integer.toHexString(fid)}): forbidden")
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
        clearCurrentFile()
        currentFid = fid
        if (!appletSelected) {
            // EF.CardSecurity (PACE-CAM): at the MF, under secure messaging only.
            if (fid == FID_CARD_SECURITY && cardSecurity != null) {
                if (!secured) return status(SW_SECURITY_STATUS)
                currentFile = cardSecurity
                return status(SW_OK)
            }
            // Otherwise, at MF level, only EF.CardAccess exists, and only if PACE is announced;
            // when it is absent, Sceau must fall back to BAC.
            val access = cardAccess?.takeIf { fid == FID_CARD_ACCESS } ?: return status(SW_FILE_NOT_FOUND)
            currentFile = access
            currentFileIsPublic = true
            return status(SW_OK)
        }
        oversizedFiles[fid]?.let { oversized ->
            if (!secured) return status(SW_SECURITY_STATUS)
            currentOversized = oversized
            return status(SW_OK)
        }
        val content = files[fid] ?: return status(SW_FILE_NOT_FOUND)
        if (fid in restrictedFiles) return status(SW_SECURITY_STATUS)
        // The applet files are only accessible after BAC, under secure messaging.
        if (!secured) return status(SW_SECURITY_STATUS)
        currentFile = content
        return status(SW_OK)
    }

    private fun readBinary(command: PlainCommand): Reply {
        if (command.p1 and P1_SFI != 0) return status(SW_WRONG_P1P2)
        currentOversized?.let { oversized ->
            if (!command.secured) return status(SW_SECURITY_STATUS)
            val offset = (command.p1 shl Byte.SIZE_BITS) or command.p2
            return Reply(oversized.read(offset, if (command.ne == 0) SHORT_MAX_NE else command.ne), SW_OK)
        }
        val content = currentFile ?: return status(SW_NO_CURRENT_EF)
        if (!command.secured && !currentFileIsPublic) return status(SW_SECURITY_STATUS)
        val offset = (command.p1 shl Byte.SIZE_BITS) or command.p2
        if (offset > content.size) return status(SW_WRONG_P1P2)
        val length = if (command.ne == 0) SHORT_MAX_NE else command.ne
        val end = minOf(offset + length, content.size)
        currentFid?.let { reads += FileRead(it, if (command.secured) session?.kind else null) }
        return Reply(content.copyOfRange(offset, end), if (end - offset < length) SW_END_OF_FILE else SW_OK)
    }

    private fun getChallenge(command: PlainCommand): Reply {
        if (command.secured || command.ne != RND_LENGTH) return status(SW_WRONG_LENGTH)
        val challenge = ByteArray(RND_LENGTH).also(random::nextBytes)
        rndIcc = challenge
        return Reply(challenge, SW_OK)
    }

    /** BAC EXTERNAL AUTHENTICATE (ICAO 9303-11 §9.7.2): E.IFD ‖ M.IFD → E.ICC ‖ M.ICC. */
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
                SessionKind.BAC,
            )
        bacCompleted = true
        return Reply(eIcc + mIcc, SW_OK, next)
    }

    /**
     * MSE:Set AT for mutual authentication (PACE, in plain), MSE:Set KAT (3DES CA) or
     * MSE:Set AT for internal authentication (AES CA).
     */
    private fun manageSecurityEnvironment(command: PlainCommand): Reply {
        val p1p2 = (command.p1 shl Byte.SIZE_BITS) or command.p2
        val pace = chipPace
        if (p1p2 == MSE_SET_AT_MUTUAL_AUTH) {
            if (pace == null || command.secured) return status(SW_WRONG_P1P2)
        } else if (!command.secured) {
            return status(SW_SECURITY_STATUS)
        }
        val objects =
            try {
                Tlv.parseAll(command.data).associateBy { it.tag }
            } catch (e: SecureMessagingError) {
                return status(SW_WRONG_DATA)
            }
        return when (p1p2) {
            MSE_SET_AT_MUTUAL_AUTH -> {
                status(checkNotNull(pace).setAuthenticationTemplate(objects))
            }

            MSE_SET_KAT -> {
                if (refuseChipAuthentication) return status(SW_WRONG_DATA)
                val publicKey = objects[TAG_EPHEMERAL_KEY]?.value ?: return status(SW_WRONG_DATA)
                val secret = sharedSecret(publicKey) ?: return status(SW_WRONG_DATA)
                caAlgorithm = Algorithm.DESEDE
                Reply(ByteArray(0), SW_OK, caSession(secret, Algorithm.DESEDE, AES_128_BITS))
            }

            MSE_SET_AT_INTERNAL_AUTH -> {
                if (refuseChipAuthentication) return status(SW_WRONG_DATA)
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

    /** GENERAL AUTHENTICATE: PACE in plain (see [ChipPace]), or AES CA under secure messaging. */
    private fun generalAuthenticate(command: PlainCommand): Reply {
        val pace = chipPace
        if (!command.secured && pace != null && pace.inProgress) {
            val outcome = pace.generalAuthenticate(command.data, last = !command.chained)
            return Reply(outcome.data, outcome.sw, outcome.session)
        }
        return caGeneralAuthenticate(command)
    }

    /** AES CA GENERAL AUTHENTICATE: 7C { 80 ephemeral key } → 7C { }. */
    private fun caGeneralAuthenticate(command: PlainCommand): Reply {
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

    /** ECDH: x-coordinate of d.ICC × Q.IFD, over the full field size (BSI TR-03111 §4.3.1). */
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
        SessionKind.CHIP_AUTHENTICATION,
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
        return PlainCommand(
            apdu[1].toInt() and 0xFF,
            apdu[2].toInt() and 0xFF,
            apdu[3].toInt() and 0xFF,
            data,
            ne,
            secured = false,
            chained = apdu[0].toInt() and CLA_CHAINING != 0,
        )
    }

    private fun status(sw: Int) = Reply(ByteArray(0), sw)

    private fun sw(sw: Int) = ChipSecureMessaging.swBytes(sw)

    private fun ByteArray.toLong(): Long = fold(0L) { acc, byte -> (acc shl Byte.SIZE_BITS) or (byte.toLong() and 0xFF) }

    companion object {
        const val DEFAULT_SEED = 9303L
        const val FID_CARD_ACCESS = 0x011C

        /** EF.CardSecurity, at the MF: same FID as EF.SOD in the applet. */
        const val FID_CARD_SECURITY = 0x011D
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
        private const val CLA_CHAINING = 0x10
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
        private const val MSE_SET_AT_MUTUAL_AUTH = 0xC1A4
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
