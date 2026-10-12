package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.SceauException
import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU

/**
 * [CardTransport] → SCUBA [CardService] adapter, for `org.jmrtd.PassportService`.
 *
 * JMRTD wraps I/O errors in [CardServiceException]s, sometimes several times over, and
 * swallows some of them (binary reads). The first [SceauException] thrown by the transport
 * (document removed, timeout) is therefore stored in [transportFailure]: the caller rethrows
 * it unchanged, whatever exception came out of JMRTD.
 * JMRTD exception messages contain APDUs in hexadecimal (personal data): they are never
 * propagated.
 *
 * Each response is checked ([ResponseShape], audit V36) before being handed to JMRTD; a
 * malformed response throws a [CardServiceException], handled like any chip error.
 */
internal class TransportCardService(
    private val transport: CardTransport,
) : CardService() {
    @Volatile
    var transportFailure: SceauException? = null
        private set

    @Volatile
    private var opened = false

    /** Forgets the transport error, once the connection has been reset (PACE fallback to BAC). */
    fun clearTransportFailure() {
        transportFailure = null
    }

    override fun open() {
        opened = true
    }

    override fun isOpen(): Boolean = opened

    override fun transmit(command: CommandAPDU): ResponseAPDU {
        val response =
            try {
                transport.transceive(command.bytes)
            } catch (e: SceauException) {
                if (transportFailure == null) transportFailure = e
                throw CardServiceException("Transport", e)
            }
        if (!ResponseShape.isWellFormed(command.cla, command.ins, response)) throw CardServiceException("Malformed response")
        return ResponseAPDU(response)
    }

    override fun getATR(): ByteArray? = null

    override fun close() {
        opened = false
        transport.close()
    }

    override fun isConnectionLost(e: Exception): Boolean = transportFailure != null || e.findTransportFailure() != null
}

/**
 * Checks chip responses before JMRTD (audit V36).
 *
 * JMRTD allocates the announced length of a secure messaging DO'85 or DO'87
 * (`SecureMessagingWrapper.readDO87`) **before** verifying the MAC, and that of the objects in the
 * PACE GENERAL AUTHENTICATE response (`TLVInputStream.readValue`): a response of a few bytes
 * announcing 2 GB causes an `OutOfMemoryError`, which passes through the core's
 * `catch (Exception)` blocks. The following are therefore rejected:
 * - any response shorter than 2 bytes (no SW) or with more than 64 KiB of data (short APDUs
 *   only: at most 256 in practice; otherwise JMRTD's `FragmentBuffer` doubles its buffers
 *   without bound);
 * - for a protected command (CLA with the secure messaging bits `0C`, which JMRTD sets on
 *   every wrapped command), a response in which a DO'85 or DO'87 does not fit in the data.
 *   The scan reproduces JMRTD's, byte by byte (an unknown byte is skipped on its own, the scan
 *   stops at DO'8E): an ordinary TLV check (`BerStructure`) can be bypassed by a DO'87 hidden
 *   in the value of another TLV, which JMRTD reads anyway;
 * - for GENERAL AUTHENTICATE in clear (PACE steps), a response whose BER lengths do not fit
 *   ([BerStructure.lengthsFit], which reads headers the way SCUBA does).
 *
 * Responses in clear to other commands (SELECT, READ BINARY of EF.CardAccess, GET
 * CHALLENGE, BAC's EXTERNAL AUTHENTICATE) are not interpreted: their data is arbitrary
 * (a random challenge may start with `87`).
 */
internal object ResponseShape {
    /** Maximum response data (excluding SW), well beyond short APDUs (256). */
    const val MAX_RESPONSE_DATA = 64 * 1024

    private const val SW_LENGTH = 2
    private const val SECURE_MESSAGING_CLA = 0x0C
    private const val GENERAL_AUTHENTICATE = 0x86
    private const val DO85 = 0x85
    private const val DO87 = 0x87
    private const val DO8E = 0x8E
    private const val DO99 = 0x99
    private const val DO99_LENGTH = 2
    private const val BYTE_MASK = 0xFF
    private const val LONG_FORM = 0x80
    private const val LENGTH_COUNT_MASK = 0x7F
    private const val MAX_LENGTH_BYTES = 4

    fun isWellFormed(
        cla: Int,
        ins: Int,
        response: ByteArray,
    ): Boolean {
        if (response.size < SW_LENGTH || response.size > MAX_RESPONSE_DATA + SW_LENGTH) return false
        val dataEnd = response.size - SW_LENGTH
        if (dataEnd == 0) return true
        return when {
            cla and SECURE_MESSAGING_CLA == SECURE_MESSAGING_CLA -> secureMessagingLengthsFit(response, dataEnd)
            ins == GENERAL_AUTHENTICATE -> BerStructure.lengthsFit(response.copyOf(dataEnd))
            else -> true
        }
    }

    /**
     * True if every DO'85 and DO'87 that JMRTD would read in [bytes] (up to [end], SW excluded) fits
     * in the data, including the header and the DO'87 padding byte `01`.
     */
    fun secureMessagingLengthsFit(
        bytes: ByteArray,
        end: Int = bytes.size,
    ): Boolean {
        var index = 0
        while (index < end) {
            when (val tag = bytes[index].toInt() and BYTE_MASK) {
                DO85, DO87 -> {
                    index++
                    if (index >= end) return false
                    val first = bytes[index++].toInt() and BYTE_MASK
                    var length = first.toLong()
                    if (first and LONG_FORM != 0) {
                        val count = first and LENGTH_COUNT_MASK
                        if (count == 0 || count > MAX_LENGTH_BYTES || index + count > end) return false
                        length = 0
                        repeat(count) { length = (length shl Byte.SIZE_BITS) or (bytes[index++].toLong() and BYTE_MASK.toLong()) }
                    }
                    if (tag == DO87) {
                        // `01` (padding) byte included in the length.
                        if (length < 1 || index >= end) return false
                        index++
                        length--
                    }
                    if (index + length > end) return false
                    index += length.toInt()
                }

                // JMRTD stops at the MAC (8 or 16 bytes, any other length rejected).
                DO8E -> {
                    return true
                }

                DO99 -> {
                    index++
                    // Length other than 2: JMRTD rejects the response without allocating anything.
                    if (index >= end || bytes[index].toInt() and BYTE_MASK != DO99_LENGTH) return true
                    index += 1 + DO99_LENGTH
                }

                else -> {
                    index++
                }
            }
        }
        return true
    }
}

/** First [SceauException] in the cause chain. */
internal fun Throwable.findTransportFailure(): SceauException? = causeChain().filterIsInstance<SceauException>().firstOrNull()

/** First status word (SW1 SW2) carried by a [CardServiceException] in the chain, or null. */
internal fun Throwable.statusWord(): Int? =
    causeChain()
        .filterIsInstance<CardServiceException>()
        .map { it.sw }
        .firstOrNull { it != CardServiceException.SW_NONE }
        ?.and(0xFFFF)

/** Cause chain, bounded to withstand cycles. */
internal fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this, Throwable::nextCause).take(MAX_CAUSES)

private fun Throwable.nextCause(): Throwable? = cause?.takeIf { it !== this }

private const val MAX_CAUSES = 16
