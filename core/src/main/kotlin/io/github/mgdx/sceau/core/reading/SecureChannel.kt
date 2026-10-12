package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.report.ChannelProtocol
import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.protocol.PACECAMResult
import org.jmrtd.protocol.PACEGMMappingResult
import org.jmrtd.protocol.PACEResult
import java.time.LocalDate
import java.util.Locale

/**
 * CONNECT and SECURE_CHANNEL steps (SPEC §6.1, steps 1 and 2).
 *
 * Order of exchanges:
 * 1. EF.CardAccess is read at MF level, before any applet selection. Absent or unreadable:
 *    no PACE.
 * 2. Without PACE, the ICAO applet is selected in clear right after connecting (6A82 →
 *    [SceauException.NotIcaoDocument]), then BAC with the MRZ key.
 * 3. With PACE, PACE is performed at MF level (ICAO 9303-11: the applet is selected after
 *    PACE, under secure messaging), trying each announced `PACEInfo` in order,
 *    PACE-CAM ones first (audit V20);
 *    the applet selection that follows then detects a missing ICAO application.
 * 4. If PACE fails with an MRZ key for a reason other than an explicit key refusal
 *    (SW 63xx) or a timeout, that is a lost connection, an unexpected SW or a JMRTD
 *    error, the connection is reset ([CardTransport.reconnect]) then BAC is performed
 *    (applet selection in clear then BAC): documents that announce PACE are still required
 *    to accept BAC until the end of the ICAO transition.
 *    With a CAN, no fallback is possible: the transport error comes out unchanged, a refusal or
 *    a PACE failure gives [SceauException.AccessDenied].
 * 5. During authentication (PACE, then BAC), the response timeout is raised to
 *    [AUTHENTICATION_TIMEOUT_MILLIS], then restored for what follows. After failed attempts, a
 *    chip may impose an increasing delay before answering the first authentication command
 *    (anti-brute-force countermeasure, observed on a French passport silent on the first
 *    GENERAL AUTHENTICATE as well as on BAC's EXTERNAL AUTHENTICATE):
 *    interrupting it earlier never lets it answer and may worsen the penalty. A timeout
 *    during PACE therefore stops the read, even with an MRZ key
 *    ([SceauException.Timeout] with diagnostic): retrying BAC on a chip under penalty would
 *    only add another interrupted attempt.
 * 6. PACE-CAM (decision D21): if PACE succeeds with the CAM mapping, EF.CardSecurity is read at
 *    the MF under the new secure messaging, before applet selection; its verification
 *    and that of the Chip Authentication data take place later
 *    ([ChipAuthenticationMapping]), once DG1 (issuing State) is known.
 * 7. After any successful PACE (GM, IM or CAM), EF.CardSecurity is read at the MF the same way
 *    ([cardSecurity], audit V32, decision D43): being signed, it tells whether the State announces
 *    PACE-CAM or a Chip Authentication key, which [ProtocolDowngrade] compares with the channel
 *    performed. Never under BAC.
 */
internal class SecureChannel(
    private val chip: Chip,
    private val key: AccessKey,
) {
    private val service get() = chip.service

    /**
     * EF.CardSecurity read at the MF after the last successful PACE (audit V32); null if absent or
     * unreadable, and always null after BAC.
     */
    var cardSecurity: ByteArray? = null
        private set

    /** CONNECT step. Returns the announced `PACEInfo`s (empty: no PACE). */
    fun connect(): List<PACEInfo> {
        service.open()
        val paceInfos = readPaceInfos()
        if (paceInfos.isEmpty()) selectApplet(secure = false)
        return paceInfos
    }

    /**
     * SECURE_CHANNEL step. Returns the established protocol and, if PACE succeeded with the
     * Chip Authentication Mapping (PACE-CAM, ICAO 9303-11 §4.4), the evidence to verify:
     * EF.CardSecurity, read at the MF under the new secure messaging before applet selection,
     * and the Chip Authentication data decrypted by JMRTD (see [ChipAuthenticationMapping],
     * decision D21).
     */
    fun establish(paceInfos: List<PACEInfo>): EstablishedChannel {
        cardSecurity = null
        if (paceInfos.isNotEmpty()) {
            val pace = withAuthenticationTimeout { tryPace(paceInfos) }
            if (pace != null) {
                // At the MF, before applet selection (same FID as EF.SOD in the applet).
                val cardSecurity = chip.readOptionalSecuredMasterFile(PassportService.EF_CARD_SECURITY)
                this.cardSecurity = cardSecurity
                val cam = if (pace.mappingType == PACEInfo.MappingType.CAM) camEvidence(pace, cardSecurity) else null
                selectApplet(secure = true)
                return EstablishedChannel(ChannelProtocol.PACE, cam)
            }
            if (key is AccessKey.Can) throw SceauException.AccessDenied()
            // PACE may have left the chip mid-protocol, or even silent: start over from scratch.
            chip.reconnect()
            selectApplet(secure = false)
        } else if (key is AccessKey.Can) {
            throw SceauException.CanWithoutPace()
        }
        withAuthenticationTimeout { doBac(key as AccessKey.Mrz) }
        return EstablishedChannel(ChannelProtocol.BAC, cam = null)
    }

    /**
     * PACE-CAM: EF.CardSecurity (MF, under PACE secure messaging), Chip Authentication data
     * decrypted by JMRTD (CA_IC) and the chip's mapping public key (PK_Map,IC). Any missing
     * element is left null: verification will report it.
     */
    private fun camEvidence(
        pace: PACEResult,
        cardSecurity: ByteArray?,
    ): PaceCamEvidence =
        PaceCamEvidence(
            chipAuthenticationData = (pace as? PACECAMResult)?.chipAuthenticationData,
            mappingKey = (pace.mappingResult as? PACEGMMappingResult)?.piccMappingPublicKey,
            cardSecurity = cardSecurity,
        )

    /**
     * Re-establishes a secure channel over a fresh connection, with the same key, within the
     * same read: after a failed Chip Authentication, the chip may have closed secure messaging
     * (invalid MAC under the new keys). Reconnects, then [connect] and [establish] as at the
     * start of the read, PACE → BAC fallback included. Errors come out as on the first
     * establishment (key refused, timeout, reconnection impossible).
     */
    fun reestablish(): EstablishedChannel {
        chip.reconnect()
        return establish(connect())
    }

    /** Runs [block] with the [AUTHENTICATION_TIMEOUT_MILLIS] response timeout, then restores the timeout. */
    private inline fun <T> withAuthenticationTimeout(block: () -> T): T {
        val transport = chip.transport
        val savedTimeout = transport.timeoutMillis
        transport.timeoutMillis = maxOf(savedTimeout, AUTHENTICATION_TIMEOUT_MILLIS)
        try {
            return block()
        } finally {
            transport.timeoutMillis = savedTimeout
        }
    }

    private fun readPaceInfos(): List<PACEInfo> {
        val bytes = chip.readOptionalFile(PassportService.EF_CARD_ACCESS) ?: return emptyList()
        val file =
            try {
                CardAccessFile(bytes.inputStream())
            } catch (e: Exception) {
                return emptyList()
            }
        return file.securityInfos.orEmpty().filterIsInstance<PACEInfo>()
    }

    /**
     * PACE result if it succeeded with one of the announced `PACEInfo`s, null if it failed without
     * the key being refused. Throws [SceauException.AccessDenied] if the chip refuses the key
     * (SW 63xx), and rethrows a timeout as well as, with a CAN, any transport error. With an MRZ
     * key, a lost connection stops the attempts (the connection must be reset) and returns
     * null.
     */
    private fun tryPace(paceInfos: List<PACEInfo>): PACEResult? {
        val paceKey =
            when (key) {
                is AccessKey.Can -> PACEKeySpec.createCANKey(key.value)
                is AccessKey.Mrz -> PACEKeySpec.createMRZKey(bacKey(key))
            }
        // PACE-CAM first if announced: a channel established without CAM while DG14 announces it
        // counts as a downgrade (audit V20, ProtocolDowngrade). Announced order kept otherwise.
        for (info in paceInfos.sortedByDescending { SecurityInfoProtocols.isPaceCam(it.objectIdentifier) }) {
            val parameterId = info.parameterId ?: continue
            val parameters =
                try {
                    PACEInfo.toParameterSpec(parameterId)
                } catch (e: Exception) {
                    // Proprietary parameters (PACEDomainParameterInfo): not supported.
                    continue
                }
            try {
                return service.doPACE(paceKey, info.objectIdentifier, parameters, parameterId)
            } catch (e: Exception) {
                chip.cardService.transportFailure?.let { failure ->
                    // Timeout: chip silent or under penalty, a new attempt would change nothing.
                    if (key is AccessKey.Can || failure is SceauException.Timeout) throw failure
                    return null
                }
                if (e.isKeyRefused()) throw SceauException.AccessDenied()
            }
        }
        return null
    }

    /** Explicit key refusal by the chip: SW 63xx (ICAO 9303-11, BSI TR-03110). */
    private fun Exception.isKeyRefused(): Boolean = statusWord()?.let { it and SW1_MASK == SW1_AUTHENTICATION_FAILED } ?: false

    private fun doBac(mrz: AccessKey.Mrz) {
        try {
            service.doBAC(bacKey(mrz))
        } catch (e: CardServiceException) {
            chip.rethrowTransportFailure()
            throw SceauException.AccessDenied()
        }
    }

    private fun selectApplet(secure: Boolean) {
        try {
            service.sendSelectApplet(secure)
        } catch (e: CardServiceException) {
            chip.rethrowTransportFailure()
            if (e.statusWord() == SW_FILE_NOT_FOUND) throw SceauException.NotIcaoDocument()
            // D36: ICAO application reserved for government terminals, refused despite an
            // established channel. A refused selection in clear (before BAC) keeps its technical code.
            if (secure && e.statusWord() == DocumentReader.SW_SECURITY_STATUS_NOT_SATISFIED) {
                throw SceauException.AccessRestricted(SELECT_APPLET)
            }
            throw StepFailure(SELECT_APPLET, e)
        }
    }

    companion object {
        private const val SW_FILE_NOT_FOUND = 0x6A82
        private const val SELECT_APPLET = "SELECT_APPLET"
        private const val SW1_MASK = 0xFF00
        private const val SW1_AUTHENTICATION_FAILED = 0x6300

        /**
         * Response timeout during authentication (PACE, BAC): long enough for a chip that makes
         * the reader wait after failed attempts to eventually answer.
         */
        const val AUTHENTICATION_TIMEOUT_MILLIS = 60_000

        fun bacKey(mrz: AccessKey.Mrz): BACKey = BACKey(mrz.documentNumber, yymmdd(mrz.dateOfBirth), yymmdd(mrz.dateOfExpiry))

        private fun yymmdd(date: LocalDate): String =
            String.format(Locale.ROOT, "%02d%02d%02d", date.year % 100, date.monthValue, date.dayOfMonth)
    }
}
