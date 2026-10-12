package io.github.mgdx.sceau.nfc

import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.SceauException
import java.io.IOException
import java.util.Locale

/**
 * [CardTransport] on top of [IsoDep]. Connects on the first APDU if needed.
 * The default timeout is long: PACE on the CNIe takes several seconds of computation
 * on the chip side.
 *
 * Errors: a failure that occurs after 90% of the timeout is a [SceauException.Timeout]; only an
 * actual loss of the document (or a requested close) gives [SceauException.ConnectionLost].
 * Any other I/O error becomes a technical error. Each identifier carries the INS and the
 * length of the current APDU, for diagnosis without logs.
 */
class IsoDepTransport(
    private val isoDep: IsoDep,
    timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : CardTransport {
    @Volatile
    private var timeout: Int = timeoutMillis

    /**
     * Timeout actually applied by the NFC service, read back after each assignment: it may
     * cap the requested value. Used to classify a failure as a timeout or as a loss.
     */
    @Volatile
    private var effectiveTimeout: Int = timeoutMillis

    /** Set by [close]: only a requested close gives a connection loss. */
    @Volatile
    private var closed = false

    override val maxTransceiveLength: Int
        get() =
            if (isoDep.isExtendedLengthApduSupported) {
                isoDep.maxTransceiveLength
            } else {
                minOf(isoDep.maxTransceiveLength, SHORT_APDU_MAX_LENGTH)
            }

    override var timeoutMillis: Int
        get() = timeout
        set(value) {
            timeout = value
            // IsoDep resets the timeout to its default value on connection: it is also
            // reapplied in ensureConnected().
            if (isoDep.isConnected) applyTimeout() else effectiveTimeout = value
        }

    override fun transceive(apdu: ByteArray): ByteArray {
        // Read cancelled: never reopen the connection.
        if (closed) throw SceauException.ConnectionLost(detail = commandDetail(apdu))
        val start = System.nanoTime()
        return try {
            ensureConnected()
            isoDep.transceive(apdu)
        } catch (e: IOException) {
            // TagLostException included: after a timeout, IsoDep often reports a loss.
            throw classifyIoFailure(
                message = e.message,
                tagLost = e is TagLostException || e.cause is TagLostException,
                connected = isConnectedQuietly(),
                closed = closed,
                apdu = apdu,
                elapsedMillis = (System.nanoTime() - start) / NANOS_PER_MILLI,
                // Effective timeout, read after ensureConnected(), which may have reapplied it.
                timeoutMillis = effectiveTimeout,
                cause = e,
            )
        } catch (e: SecurityException) {
            // "Tag is out of date": the document was removed, then another one was presented.
            throw SceauException.ConnectionLost(e, commandDetail(apdu))
        } catch (e: IllegalStateException) {
            // Transport closed during the read: connection loss only if the close was
            // requested (read cancelled); otherwise, it is an anomaly to diagnose.
            throw if (closed) {
                SceauException.ConnectionLost(e, commandDetail(apdu))
            } else {
                SceauException.Unexpected(technicalDetail(STATE_PREFIX, apdu), e)
            }
        }
    }

    /**
     * Drops then re-establishes the link with the same document (`IsoDep.close()` resets the
     * chip on the NFC service side), timeout reapplied. Does not mark the transport as closed.
     */
    override fun reconnect() {
        if (closed) throw SceauException.ConnectionLost(detail = RECONNECT_DETAIL)
        try {
            isoDep.close()
        } catch (e: IOException) {
            // Already disconnected: reconnect anyway.
        }
        try {
            isoDep.connect()
            applyTimeout()
        } catch (e: IOException) {
            throw SceauException.ConnectionLost(e, RECONNECT_DETAIL)
        } catch (e: SecurityException) {
            throw SceauException.ConnectionLost(e, RECONNECT_DETAIL)
        } catch (e: IllegalStateException) {
            throw SceauException.ConnectionLost(e, RECONNECT_DETAIL)
        }
    }

    /** May block until a current APDU finishes: never on the main thread. */
    override fun close() {
        closed = true
        try {
            isoDep.close()
        } catch (e: IOException) {
            // Already disconnected: nothing to do.
        }
    }

    private fun ensureConnected() {
        if (!isoDep.isConnected) {
            isoDep.connect()
            applyTimeout()
        }
    }

    /** Applies the requested timeout to the connected link, then reads back the one the system kept. */
    private fun applyTimeout() {
        val requested = timeout
        isoDep.timeout = requested
        val readBack =
            try {
                isoDep.timeout
            } catch (e: RuntimeException) {
                0
            }
        effectiveTimeout = effectiveTimeout(requested, readBack)
    }

    /** `IsoDep.isConnected` queries the NFC service: it must not mask the original error. */
    private fun isConnectedQuietly(): Boolean =
        try {
            isoDep.isConnected
        } catch (e: RuntimeException) {
            false
        }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000

        /** Header (4) + Lc (1) + data (255) + Le (1) of a short APDU. */
        private const val SHORT_APDU_MAX_LENGTH = 261

        private const val IO_PREFIX = "IO-"
        private const val STATE_PREFIX = "STATE"
        private const val LENGTH_ROUNDING = 10
        private const val RECONNECT_DETAIL = "RECONNECT"
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Fraction of the timeout (9/10) beyond which a failure is a timeout. */
        private const val TIMEOUT_RATIO_NUMERATOR = 9
        private const val TIMEOUT_RATIO_DENOMINATOR = 10

        /**
         * Known messages of `android.nfc` `IOException`s (closed list) and their short code.
         * The raw message is never included in an error identifier.
         */
        private val IO_MESSAGE_CODES =
            mapOf(
                "Transceive failed" to "TRANSCEIVE_FAILED",
                "Transceive length exceeds supported maximum" to "TOO_LONG",
                "NFC service died" to "SERVICE_DIED",
                "NFC service dead" to "SERVICE_DIED",
            )

        /** True if the message of an `IsoDep` [IOException] suggests a timeout. */
        internal fun isTimeoutMessage(message: String?): Boolean {
            if (message == null) return false
            val lower = message.lowercase(Locale.ROOT)
            return "timeout" in lower || "timed out" in lower || "time out" in lower
        }

        /** Short code of an `IOException` message: `OTHER` if it is outside the closed list. */
        internal fun ioMessageCode(message: String?): String =
            if (message == null) "NO_MESSAGE" else IO_MESSAGE_CODES[message.trim()] ?: "OTHER"

        /**
         * Classifies an `IsoDep` `IOException`: transport closed → connection loss; failure
         * that occurred after at least 90% of the timeout → [SceauException.Timeout], even if
         * IsoDep then reports a loss (it resets the link after a timeout); document removed →
         * connection loss; timeout message → timeout; otherwise technical error
         * `IO-<code>-INS<xx>-L<n>`. Timeout and loss carry the `INS<xx>-L<n>` diagnostic.
         */
        internal fun classifyIoFailure(
            message: String?,
            tagLost: Boolean,
            connected: Boolean,
            closed: Boolean,
            apdu: ByteArray,
            elapsedMillis: Long = 0,
            timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
            cause: Throwable? = null,
        ): SceauException =
            when {
                closed -> SceauException.ConnectionLost(cause, commandDetail(apdu))
                isTimeoutElapsed(elapsedMillis, timeoutMillis) -> SceauException.Timeout(cause, commandDetail(apdu))
                tagLost || !connected -> SceauException.ConnectionLost(cause, commandDetail(apdu))
                isTimeoutMessage(message) -> SceauException.Timeout(cause, commandDetail(apdu))
                else -> SceauException.Unexpected(technicalDetail(IO_PREFIX + ioMessageCode(message), apdu), cause)
            }

        /**
         * Effective timeout based on reading back `IsoDep.getTimeout()`: [readBack] if it is
         * positive (the system may have capped [requested], for example below 60 s), otherwise
         * [requested] (`getTimeout()` returns 0 if the NFC service is unreachable).
         */
        internal fun effectiveTimeout(
            requested: Int,
            readBack: Int,
        ): Int = if (readBack > 0) readBack else requested

        /** True if [elapsedMillis] reaches at least 90% of [timeoutMillis]. */
        internal fun isTimeoutElapsed(
            elapsedMillis: Long,
            timeoutMillis: Int,
        ): Boolean = timeoutMillis > 0 && elapsedMillis * TIMEOUT_RATIO_DENOMINATOR >= timeoutMillis.toLong() * TIMEOUT_RATIO_NUMERATOR

        /** `<prefix>-INS<xx>-L<n>` (see [commandDetail]). */
        internal fun technicalDetail(
            prefix: String,
            apdu: ByteArray,
        ): String = "$prefix-${commandDetail(apdu)}"

        /**
         * `INS<xx>-L<n>`: INS byte of the command in hexadecimal and APDU length rounded to the
         * nearest ten. Neither is personal data.
         */
        internal fun commandDetail(apdu: ByteArray): String {
            val ins = if (apdu.size >= 2) String.format(Locale.ROOT, "%02X", apdu[1].toInt() and 0xFF) else "NA"
            val length = (apdu.size + LENGTH_ROUNDING / 2) / LENGTH_ROUNDING * LENGTH_ROUNDING
            return "INS$ins-L$length"
        }
    }
}
