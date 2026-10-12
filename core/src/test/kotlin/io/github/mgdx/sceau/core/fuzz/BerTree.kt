package io.github.mgdx.sceau.core.fuzz

import java.io.ByteArrayOutputStream

/**
 * Lenient BER tree, for TLV-aware mutations. Only definite lengths (at most four bytes) are
 * decoded; a region that does not split exactly into TLVs stays an opaque value. The content
 * of an OCTET STRING or a BIT STRING that is itself DER (SOD eContent, certificate public key)
 * is decoded as children.
 *
 * [lengthEncoding] replaces the minimal DER length encoding: ancestors are always recomputed
 * from the actual content, so only this node's length can lie.
 */
internal class BerNode(
    var tag: ByteArray,
    var children: MutableList<BerNode>?,
    var value: ByteArray,
    /** Unused-bits byte of a BIT STRING decoded as children. */
    var prefix: ByteArray = ByteArray(0),
    var lengthEncoding: LengthEncoding? = null,
) {
    val isConstructedTag: Boolean get() = tag[0].toInt() and CONSTRUCTED != 0

    fun encode(): ByteArray = ByteArrayOutputStream().also { encodeTo(it) }.toByteArray()

    fun encodeTo(out: ByteArrayOutputStream) {
        val content =
            children?.let { list ->
                ByteArrayOutputStream()
                    .also { buffer ->
                        buffer.write(prefix)
                        list.forEach { it.encodeTo(buffer) }
                    }.toByteArray()
            } ?: value
        out.write(tag)
        val encoding = lengthEncoding ?: LengthEncoding.Minimal()
        out.write(encoding.header(content.size))
        out.write(content)
        if (encoding is LengthEncoding.Indefinite) out.write(byteArrayOf(0, 0))
    }

    /** This node then all its descendants, in preorder. */
    fun flatten(into: MutableList<BerNode>) {
        into += this
        children?.forEach { it.flatten(into) }
    }

    companion object {
        const val CONSTRUCTED = 0x20
    }
}

/** Length encoding of a node, possibly lying. */
internal sealed class LengthEncoding {
    abstract fun header(actual: Int): ByteArray

    /** Minimal DER form; [declared] replaces the actual length if given. */
    class Minimal(
        private val declared: Long? = null,
    ) : LengthEncoding() {
        override fun header(actual: Int): ByteArray = definite(declared ?: actual.toLong(), minimalBytes(declared ?: actual.toLong()))
    }

    /** Long form on [bytes] bytes (0x80 + bytes, then the length). */
    class LongForm(
        private val bytes: Int,
        private val declared: Long? = null,
    ) : LengthEncoding() {
        override fun header(actual: Int): ByteArray = definite(declared ?: actual.toLong(), bytes, forceLong = true)
    }

    /** Indefinite length (0x80), content terminated by 00 00. */
    object Indefinite : LengthEncoding() {
        override fun header(actual: Int): ByteArray = byteArrayOf(0x80.toByte())
    }

    companion object {
        private const val SHORT_FORM_MAX = 0x7F

        fun minimalBytes(length: Long): Int {
            if (length in 0..SHORT_FORM_MAX) return 0
            var count = 0
            var rest = length
            while (rest != 0L) {
                count++
                rest = rest ushr Byte.SIZE_BITS
            }
            return count
        }

        fun definite(
            length: Long,
            bytes: Int,
            forceLong: Boolean = false,
        ): ByteArray {
            if (bytes == 0 && !forceLong) return byteArrayOf(length.toByte())
            val out = ByteArray(bytes + 1)
            out[0] = (0x80 or bytes).toByte()
            for (i in 0 until bytes) out[bytes - i] = (length ushr (Byte.SIZE_BITS * i)).toByte()
            return out
        }
    }
}

/** Input split into top-level TLVs, plus the trailing bytes that do not split. */
internal class BerDocument(
    val roots: MutableList<BerNode>,
    val trailer: ByteArray,
) {
    fun encode(): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                roots.forEach { it.encodeTo(out) }
                out.write(trailer)
            }.toByteArray()

    fun nodes(): List<BerNode> = mutableListOf<BerNode>().also { list -> roots.forEach { it.flatten(list) } }

    companion object {
        private const val MAX_DEPTH = 64
        private const val OCTET_STRING = 0x04
        private const val BIT_STRING = 0x03
        private const val HIGH_TAG = 0x1F
        private const val MORE = 0x80
        private const val MAX_LENGTH_BYTES = 4

        /** Null if the input does not start with a decodable TLV. */
        fun parse(bytes: ByteArray): BerDocument? {
            val roots = mutableListOf<BerNode>()
            var offset = 0
            while (offset < bytes.size) {
                val (node, next) = parseOne(bytes, offset, bytes.size, 0) ?: break
                roots += node
                offset = next
            }
            if (roots.isEmpty()) return null
            return BerDocument(roots, bytes.copyOfRange(offset, bytes.size))
        }

        private fun parseAll(
            bytes: ByteArray,
            from: Int,
            to: Int,
            depth: Int,
        ): MutableList<BerNode>? {
            val list = mutableListOf<BerNode>()
            var offset = from
            while (offset < to) {
                val (node, next) = parseOne(bytes, offset, to, depth) ?: return null
                list += node
                offset = next
            }
            return list
        }

        private fun parseOne(
            bytes: ByteArray,
            start: Int,
            end: Int,
            depth: Int,
        ): Pair<BerNode, Int>? {
            var offset = start
            if (offset >= end) return null
            if (bytes[offset++].toInt() and HIGH_TAG == HIGH_TAG) {
                while (offset < end && bytes[offset].toInt() and MORE != 0) offset++
                offset++
            }
            if (offset >= end) return null
            val tag = bytes.copyOfRange(start, offset)
            val first = bytes[offset++].toInt() and 0xFF
            val length: Int
            var encoding: LengthEncoding? = null
            if (first < MORE) {
                length = first
            } else {
                val count = first and 0x7F
                if (count == 0 || count > MAX_LENGTH_BYTES || offset + count > end) return null
                var value = 0L
                repeat(count) { value = (value shl Byte.SIZE_BITS) or (bytes[offset++].toLong() and 0xFF) }
                if (value > end - offset) return null
                length = value.toInt()
                // Non-minimal length in the original: kept as is when re-encoding.
                if (count != LengthEncoding.minimalBytes(value)) encoding = LengthEncoding.LongForm(count)
            }
            if (length > end - offset) return null
            val valueEnd = offset + length
            val node = BerNode(tag, null, bytes.copyOfRange(offset, valueEnd), lengthEncoding = encoding)
            if (depth < MAX_DEPTH && length > 0) {
                val tagByte = tag[0].toInt() and 0xFF
                when {
                    tagByte and BerNode.CONSTRUCTED != 0 -> {
                        node.children = parseAll(bytes, offset, valueEnd, depth + 1)
                    }

                    tagByte == OCTET_STRING -> {
                        node.children = parseAll(bytes, offset, valueEnd, depth + 1)
                    }

                    tagByte == BIT_STRING && length > 1 && bytes[offset].toInt() == 0 -> {
                        node.children = parseAll(bytes, offset + 1, valueEnd, depth + 1)
                        if (node.children != null) node.prefix = byteArrayOf(0)
                    }
                }
            }
            return node to valueEnd
        }
    }
}
