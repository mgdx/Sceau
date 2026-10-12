package io.github.mgdx.sceau.core

import io.github.mgdx.sceau.core.reading.ReadingSession
import io.github.mgdx.sceau.core.report.VerificationReport
import io.github.mgdx.sceau.core.trust.TrustStore
import java.time.LocalDate

/**
 * Raw APDU channel to the chip. Implemented in `:app` on top of `IsoDep`,
 * and in tests by a simulated transport.
 */
interface CardTransport {
    /** Maximum size of an APDU accepted by the link (IsoDep.maxTransceiveLength). */
    val maxTransceiveLength: Int

    /** Response timeout, in milliseconds. */
    var timeoutMillis: Int

    /**
     * Sends a command APDU and returns the complete response (data + SW1 SW2).
     * Throws [SceauException.ConnectionLost] if the document has been removed.
     */
    fun transceive(apdu: ByteArray): ByteArray

    /**
     * Resets the link with the document still on the reader (the ISO 14443 session is dropped
     * and resumed): the chip starts from scratch, without a secure channel or a selected
     * application. Used for the fallback from PACE to BAC. Throws [SceauException.ConnectionLost] if the
     * document is no longer there or if the transport has been closed. Does nothing by default.
     */
    fun reconnect() {}

    fun close()
}

/**
 * Chip access key. Has no revealing `toString`: no personal data
 * must appear in a log or an exception.
 */
sealed class AccessKey {
    /** CAN: 6 digits printed on the document. */
    class Can(
        val value: String,
    ) : AccessKey() {
        override fun toString(): String = "AccessKey.Can(***)"
    }

    /** MRZ key: document number (without the `<` filler characters), date of birth, date of expiry. */
    class Mrz(
        val documentNumber: String,
        val dateOfBirth: LocalDate,
        val dateOfExpiry: LocalDate,
    ) : AccessKey() {
        override fun toString(): String = "AccessKey.Mrz(***)"
    }
}

/**
 * Steps shown on the reading screen, in order. `progress(step)` is called
 * at the **start** of each step: all previous steps are then complete.
 * The return of [readAndVerify] marks the end of the last one.
 */
enum class Step {
    CONNECT,
    SECURE_CHANNEL,
    READ_DATA,
    VERIFY_SIGNATURE,
    VERIFY_CHIP,
}

/**
 * Errors that interrupt the reading. Messages never contain personal
 * data; [code] is a stable technical identifier, displayable to the user.
 */
sealed class SceauException(
    val code: String,
    cause: Throwable? = null,
) : Exception(code, cause) {
    /** No ICAO application (AID A0 00 00 02 47 10 01) on the chip. */
    class NotIcaoDocument : SceauException("NOT_ICAO")

    /** PACE or BAC failure: incorrect CAN or MRZ. */
    class AccessDenied(
        cause: Throwable? = null,
    ) : SceauException("ACCESS_DENIED", cause)

    /** A CAN was supplied but EF.CardAccess does not announce PACE. */
    class CanWithoutPace : SceauException("CAN_WITHOUT_PACE")

    /**
     * Decision D36: secure channel established (PACE or BAC succeeded), but the chip denies access
     * (SW 6982, *security status not satisfied*) to the ICAO application or to an essential
     * file (EF.SOD, DG1). It reserves them for government inspection terminals
     * (Terminal Authentication), like German identity cards issued before
     * 2021-08-02: retrying will not change anything. [detail] is a diagnostic without personal
     * data (step then denied element: `READ_DATA-SOD`); [code] is then
     * `ACCESS_RESTRICTED-<detail>`.
     */
    class AccessRestricted(
        val detail: String,
    ) : SceauException("ACCESS_RESTRICTED-$detail")

    /**
     * The document has been removed (TagLostException or equivalent). [detail], optional, is a
     * diagnostic suffix without personal data (step, INS and APDU length):
     * [code] is then `CONNECTION_LOST-<detail>`.
     */
    class ConnectionLost(
        cause: Throwable? = null,
        val detail: String? = null,
    ) : SceauException(withDetail("CONNECTION_LOST", detail), cause)

    /**
     * Audit V29: the chip no longer delivers the file being read (repeated empty READ BINARY
     * responses, or more exchanges than the maximum file size justifies).
     * [detail] is a diagnostic without personal data (step then file: `READ_DATA-DG2`);
     * [code] is then `READ_STALLED-<detail>`.
     */
    class ReadStalled(
        val detail: String,
    ) : SceauException("READ_STALLED-$detail")

    /**
     * Timeout exceeded. [code] is `TIMEOUT-<detail>` if [detail] is given (see [ConnectionLost]);
     * the global reading deadline (audit V29) gives `TIMEOUT-<STEP>-GLOBAL`.
     */
    class Timeout(
        cause: Throwable? = null,
        val detail: String? = null,
    ) : SceauException(withDetail("TIMEOUT", detail), cause)

    /**
     * Unexpected error. [code] = "UNEXPECTED-" + [detail], short technical identifier (step,
     * exception class, SW, or transport I/O code with INS and APDU length).
     */
    class Unexpected(
        val detail: String,
        cause: Throwable? = null,
    ) : SceauException("UNEXPECTED-$detail", cause)
}

private fun withDetail(
    code: String,
    detail: String?,
): String = if (detail == null) code else "$code-$detail"

/**
 * Single entry point of `:core` (SPEC §6). Blocking on I/O: runs on
 * `Dispatchers.IO`. Throws [SceauException] for errors that interrupt the reading;
 * any verification failure is instead recorded in the report.
 */
suspend fun readAndVerify(
    transport: CardTransport,
    key: AccessKey,
    trustStore: TrustStore,
    progress: (Step) -> Unit,
): VerificationReport = ReadingSession(transport, key, trustStore, progress).run()
