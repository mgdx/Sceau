package io.github.mgdx.sceau.core.reading

/**
 * Check of the BER lengths of a chip file before handing it to a JMRTD parser.
 *
 * SCUBA's `TLVInputStream`, used by JMRTD, immediately allocates an array of the length
 * announced by an inner TLV (`readValue`): a file of a few bytes with one element that
 * announces 2 GB causes an `OutOfMemoryError`, even though the whole file respects its cap
 * (FileSizeLimits only checks the outer header). This check rejects such a file without
 * allocating anything.
 *
 * It reads headers like SCUBA (`00` and `FF` bytes skipped before a tag, multi-byte
 * tag, long-form length) and requires:
 * - every length to be encoded on four bytes at most;
 * - at the top level, each TLV to fit in the buffer (the bytes following the first TLV
 *   are examined too: a streaming parser may read them);
 * - in a constructed element, each element to fit exactly in its parent, header
 *   included. Whether a parser enters that element or skips its value, it therefore resumes at the same
 *   position as this check: no header it will read escapes the check.
 *
 * A header truncated at the end of the buffer stops the walk without rejection (the parser will fail at the end
 * of the stream, without allocation); an indefinite length is rejected (ICAO 9303 mandates DER). Beyond
 * [MAX_DEPTH] nesting levels, the file is rejected.
 */
internal object BerStructure {
    private const val MAX_DEPTH = 64
    private const val MAX_LENGTH_BYTES = 4
    private const val TAG_NUMBER_MASK = 0x1F
    private const val MORE_BIT = 0x80
    private const val LENGTH_COUNT_MASK = 0x7F
    private const val CONSTRUCTED_BIT = 0x20
    private const val BYTE_MASK = 0xFF
    private const val PADDING_ZERO = 0x00
    private const val PADDING_FF = 0xFF

    /**
     * True if all lengths in [bytes] are consistent (see the class) and if [primitive]
     * accepts the value of each primitive element walked: it receives the tag (big-endian
     * bytes, on four bytes at most) and the bounds of the value in [bytes].
     */
    fun lengthsFit(
        bytes: ByteArray,
        primitive: (tag: Int, valueStart: Int, valueEnd: Int) -> Boolean = { _, _, _ -> true },
    ): Boolean = walk(bytes, 0, bytes.size, 0, primitive)

    /** Walks the TLVs from [start] to [end]; at the top level ([depth] = 0), [end] is the end of the buffer. */
    private fun walk(
        bytes: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        primitive: (Int, Int, Int) -> Boolean,
    ): Boolean {
        if (depth > MAX_DEPTH) return false
        val nested = depth > 0
        var index = start
        while (index < end) {
            // SCUBA skips 00 and FF bytes before a tag.
            val first = bytes[index].toInt() and BYTE_MASK
            if (first == PADDING_ZERO || first == PADDING_FF) {
                index++
                continue
            }
            val header = header(bytes, index, end) ?: return !nested
            if (header.length == null) return false
            val valueEnd = header.valueStart.toLong() + header.length
            if (valueEnd > end) return false
            val valueEndInt = valueEnd.toInt()
            val accepted =
                if (first and CONSTRUCTED_BIT != 0) {
                    walk(bytes, header.valueStart, valueEndInt, depth + 1, primitive)
                } else {
                    primitive(header.tag, header.valueStart, valueEndInt)
                }
            if (!accepted) return false
            index = valueEndInt
        }
        return true
    }

    private class Header(
        val tag: Int,
        val valueStart: Int,
        /** Announced length; null if it is indefinite or does not fit on four bytes. */
        val length: Long?,
    )

    /** Header of the TLV at [start], or null if it is truncated before [end]. */
    private fun header(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Header? {
        var index = start
        val first = bytes[index++].toInt() and BYTE_MASK
        var tag = first
        if (first and TAG_NUMBER_MASK == TAG_NUMBER_MASK) {
            var more = true
            while (more) {
                if (index >= end) return null
                val b = bytes[index++].toInt() and BYTE_MASK
                tag = (tag shl Byte.SIZE_BITS) or b
                more = b and MORE_BIT != 0
            }
        }
        if (index >= end) return null
        val lengthByte = bytes[index++].toInt() and BYTE_MASK
        if (lengthByte < MORE_BIT) return Header(tag, index, lengthByte.toLong())
        val count = lengthByte and LENGTH_COUNT_MASK
        if (count == 0 || count > MAX_LENGTH_BYTES) return Header(tag, index, null)
        if (index + count > end) return null
        var length = 0L
        repeat(count) { length = (length shl Byte.SIZE_BITS) or (bytes[index++].toLong() and 0xFF) }
        return Header(tag, index, length)
    }
}
