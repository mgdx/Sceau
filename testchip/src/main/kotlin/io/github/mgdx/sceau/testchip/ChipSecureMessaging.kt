package io.github.mgdx.sceau.testchip

import io.github.mgdx.sceau.testchip.SimCrypto.Algorithm
import java.io.ByteArrayOutputStream

/** Plain APDU command, as the chip processes it (after unwrapping, if any). */
internal class PlainCommand(
    val ins: Int,
    val p1: Int,
    val p2: Int,
    val data: ByteArray,
    /** Expected length Ne (0: no Le). */
    val ne: Int,
    val secured: Boolean,
    /** Command chaining (CLA b5), used by the PACE steps. */
    val chained: Boolean = false,
)

/** The secured command is invalid (wrong MAC, missing object): the chip closes the session. */
internal class SecureMessagingError(
    message: String,
) : Exception(message)

/** Protocol that opened a secure messaging session of the simulated chip. */
enum class SessionKind {
    BAC,
    PACE,

    /** Chip Authentication via DG14 (keys derived from the DG14 static key). */
    CHIP_AUTHENTICATION,
}

/**
 * Chip-side secure messaging (ICAO 9303-11 §9.8): unwraps commands
 * (DO'87, DO'97, DO'8E), checks their MAC, and wraps responses (DO'87, DO'99, DO'8E).
 * The counter [ssc] is incremented before each command and before each response.
 */
internal class ChipSecureMessaging(
    val algorithm: Algorithm,
    private val kEnc: ByteArray,
    private val kMac: ByteArray,
    ssc: Long,
    /** Protocol that opened this session. */
    val kind: SessionKind,
) {
    var ssc: Long = ssc
        private set

    private val blockSize get() = algorithm.blockSize

    fun unwrap(apdu: ByteArray): PlainCommand {
        if (apdu.size < HEADER + 1) throw SecureMessagingError("secured command without data")
        val header = apdu.copyOf(HEADER)
        val lc = apdu[HEADER].toInt() and 0xFF
        if (lc == 0 || apdu.size < HEADER + 1 + lc) throw SecureMessagingError("Lc")
        val body = apdu.copyOfRange(HEADER + 1, HEADER + 1 + lc)

        var do87: Tlv? = null
        var do97: Tlv? = null
        var do8e: Tlv? = null
        for (tlv in Tlv.parseAll(body)) {
            when (tlv.tag) {
                TAG_DO87 -> do87 = tlv
                TAG_DO97 -> do97 = tlv
                TAG_DO8E -> do8e = tlv
                else -> throw SecureMessagingError("unexpected object")
            }
        }
        val cc = do8e?.value ?: throw SecureMessagingError("DO'8E missing")

        ssc++
        val macInput =
            ByteArrayOutputStream().apply {
                write(encodedSsc())
                write(SimCrypto.pad(header, blockSize))
                do87?.let { write(it.encoded) }
                do97?.let { write(it.encoded) }
            }
        val expected = SimCrypto.mac(algorithm, kMac, SimCrypto.pad(macInput.toByteArray(), blockSize))
        if (!expected.contentEquals(cc)) throw SecureMessagingError("invalid command MAC")

        val data =
            do87?.let {
                if (it.value.isEmpty() || it.value[0] != PADDING_INDICATOR) throw SecureMessagingError("DO'87 without indicator")
                val plain = SimCrypto.cbc(false, algorithm, kEnc, iv(), it.value.copyOfRange(1, it.value.size))
                SimCrypto.unpad(plain) ?: throw SecureMessagingError("padding")
            } ?: ByteArray(0)
        val ne =
            do97?.value?.let { le ->
                when (le.size) {
                    1 -> (le[0].toInt() and 0xFF).let { if (it == 0) SHORT_MAX_NE else it }
                    2 -> ((le[0].toInt() and 0xFF) shl 8 or (le[1].toInt() and 0xFF)).let { if (it == 0) EXTENDED_MAX_NE else it }
                    else -> throw SecureMessagingError("DO'97")
                }
            } ?: 0
        return PlainCommand(
            ins = header[1].toInt() and 0xFF,
            p1 = header[2].toInt() and 0xFF,
            p2 = header[3].toInt() and 0xFF,
            data = data,
            ne = ne,
            secured = true,
        )
    }

    fun wrap(
        data: ByteArray,
        sw: Int,
    ): ByteArray {
        ssc++
        val do87 =
            if (data.isEmpty()) {
                ByteArray(0)
            } else {
                val encrypted = SimCrypto.cbc(true, algorithm, kEnc, iv(), SimCrypto.pad(data, blockSize))
                Tlv(TAG_DO87, byteArrayOf(PADDING_INDICATOR) + encrypted).encoded
            }
        val do99 = Tlv(TAG_DO99, swBytes(sw)).encoded
        val macInput = encodedSsc() + do87 + do99
        val cc = SimCrypto.mac(algorithm, kMac, SimCrypto.pad(macInput, blockSize))
        return do87 + do99 + Tlv(TAG_DO8E, cc).encoded + swBytes(sw)
    }

    private fun encodedSsc(): ByteArray {
        val bytes = ByteArray(blockSize)
        for (i in 0 until Long.SIZE_BYTES) {
            bytes[blockSize - 1 - i] = (ssc ushr (Byte.SIZE_BITS * i)).toByte()
        }
        return bytes
    }

    /** IV: zero for 3DES, E(KSenc, SSC) for AES (ICAO 9303-11 §9.8.6.1). */
    private fun iv(): ByteArray =
        when (algorithm) {
            Algorithm.DESEDE -> ByteArray(blockSize)
            Algorithm.AES -> SimCrypto.aesBlock(kEnc, encodedSsc())
        }

    companion object {
        private const val HEADER = 4
        private const val TAG_DO87 = 0x87
        private const val TAG_DO97 = 0x97
        private const val TAG_DO99 = 0x99
        private const val TAG_DO8E = 0x8E
        private const val PADDING_INDICATOR: Byte = 0x01
        private const val SHORT_MAX_NE = 256
        private const val EXTENDED_MAX_NE = 65536

        fun swBytes(sw: Int) = byteArrayOf((sw shr Byte.SIZE_BITS).toByte(), sw.toByte())
    }
}

/** BER-TLV object with a one-byte tag (enough for secure messaging and EAC commands). */
internal class Tlv(
    val tag: Int,
    val value: ByteArray,
) {
    val encoded: ByteArray get() = byteArrayOf(tag.toByte()) + encodeLength(value.size) + value

    companion object {
        fun parseAll(bytes: ByteArray): List<Tlv> {
            val result = mutableListOf<Tlv>()
            var index = 0
            while (index < bytes.size) {
                val tag = bytes[index++].toInt() and 0xFF
                if (index >= bytes.size) throw SecureMessagingError("truncated TLV")
                var length = bytes[index++].toInt() and 0xFF
                if (length and 0x80 != 0) {
                    val count = length and 0x7F
                    if (count !in 1..2 || index + count > bytes.size) throw SecureMessagingError("TLV length")
                    length = 0
                    repeat(count) { length = (length shl Byte.SIZE_BITS) or (bytes[index++].toInt() and 0xFF) }
                }
                if (index + length > bytes.size) throw SecureMessagingError("truncated TLV")
                result += Tlv(tag, bytes.copyOfRange(index, index + length))
                index += length
            }
            return result
        }

        fun encodeLength(length: Int): ByteArray =
            when {
                length < 0x80 -> byteArrayOf(length.toByte())
                length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
                else -> byteArrayOf(0x82.toByte(), (length shr Byte.SIZE_BITS).toByte(), length.toByte())
            }
    }
}
