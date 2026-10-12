package io.github.mgdx.sceau.testchip

/**
 * File forged by a hostile chip: its TLV header (tag [tag], four-byte length) announces
 * [valueLength] bytes of value, served as zeros on demand without ever being allocated.
 */
class OversizedFile(
    val tag: Int,
    val valueLength: Long,
) {
    init {
        require(tag in 1..MAX_ONE_BYTE && valueLength in 0..MAX_LENGTH)
    }

    /** Header: tag, 0x84, four-byte length. */
    val header: ByteArray =
        byteArrayOf(tag.toByte(), LONG_FORM_4.toByte()) +
            ByteArray(LENGTH_BYTES) { ((valueLength shr (Byte.SIZE_BITS * (LENGTH_BYTES - 1 - it))) and BYTE_MASK).toByte() }

    /** Total announced size, header included. */
    val size: Long get() = header.size + valueLength

    /** [length] bytes from [offset] (fewer at the end of the file). */
    fun read(
        offset: Int,
        length: Int,
    ): ByteArray {
        val end = minOf(offset.toLong() + length, size)
        if (offset >= end) return ByteArray(0)
        return ByteArray((end - offset).toInt()) { index -> header.getOrElse(offset + index) { 0 } }
    }

    private companion object {
        const val MAX_ONE_BYTE = 0xFE
        const val LONG_FORM_4 = 0x84
        const val LENGTH_BYTES = 4
        const val BYTE_MASK = 0xFFL
        const val MAX_LENGTH = 0xFFFFFFFFL
    }
}
