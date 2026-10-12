package io.github.mgdx.sceau.jp2

/**
 * Protocol between [IsolatedJpeg2000Decoder] (app process) and [Jpeg2000Service]
 * (isolated process), decision D23. Pure Kotlin, without Android dependencies, tested on the JVM.
 *
 * The stream bytes and the decoded pixels go through binder, in memory only, in chunks small
 * enough to stay far from the limit of the binder transaction buffer (1 MB shared by all the
 * process's ongoing transactions):
 *  1. [TX_BEGIN] (total length of the stream);
 *  2. [TX_APPEND] (position, chunk of at most [BYTE_CHUNK] bytes), in order;
 *  3. [TX_DECODE]: reply with width and height;
 *  4. [TX_READ] (position, count): chunks of at most [INT_CHUNK] ARGB pixels, in order.
 * Each reply starts with a status ([STATUS_OK] or [STATUS_ERROR]).
 */
internal object Jpeg2000Protocol {
    /** Interface token checked by the service on each transaction. */
    const val DESCRIPTOR = "io.github.mgdx.sceau.jp2.Jpeg2000Service"

    // IBinder.FIRST_CALL_TRANSACTION equals 1.
    const val TX_BEGIN = 1
    const val TX_APPEND = 2
    const val TX_DECODE = 3
    const val TX_READ = 4

    const val STATUS_OK = 0
    const val STATUS_ERROR = 1

    /** Maximum stream size, identical to `SCEAU_JP2_MAX_INPUT_SIZE` (jp2_decode.h). */
    const val MAX_INPUT_BYTES = 16 * 1024 * 1024

    /** Maximum side of the decoded image, identical to `SCEAU_JP2_MAX_OUTPUT_DIMENSION`. */
    const val MAX_OUTPUT_SIDE = 2048

    /** Stream bytes per transaction (256 KiB). */
    const val BYTE_CHUNK = 256 * 1024

    /** Pixels per transaction (64 Ki pixels, i.e. 256 KiB). */
    const val INT_CHUNK = 64 * 1024

    /**
     * Maximum duration of a decoding, including isolated process startup and transfers. Beyond it,
     * the service is unbound (which terminates the isolated process) and the image is not shown.
     */
    const val TIMEOUT_MILLIS = 10_000L

    /** Chunks (position, length) covering `[0, total)`, each of at most [chunk] elements. */
    fun chunks(
        total: Int,
        chunk: Int,
    ): List<Pair<Int, Int>> {
        require(total >= 0 && chunk > 0)
        return (0 until total step chunk).map { start -> start to minOf(chunk, total - start) }
    }

    /** True if [length] is an acceptable stream length. */
    fun isValidInputLength(length: Int): Boolean = length in 1..MAX_INPUT_BYTES

    /** True if the dimensions announced by the isolated process are plausible and bounded. */
    fun isValidOutput(
        width: Int,
        height: Int,
    ): Boolean = width in 1..MAX_OUTPUT_SIDE && height in 1..MAX_OUTPUT_SIDE

    /** True if `[offset, offset + count)` is a valid chunk of an array of [total] elements. */
    fun isValidSlice(
        total: Int,
        offset: Int,
        count: Int,
        maxChunk: Int,
    ): Boolean = offset in 0..total && count in 1..maxChunk && count <= total - offset
}

/**
 * Byte buffer filled chunk by chunk, in strict order: a chunk that is out of sequence, empty
 * or that would overflow is refused. [wipe] zeroes the content.
 */
internal class SequentialBytes(
    size: Int,
) {
    val data = ByteArray(size)
    private var filled = 0

    val isComplete: Boolean get() = filled == data.size

    fun put(
        offset: Int,
        chunk: ByteArray,
    ): Boolean {
        if (offset != filled || chunk.isEmpty() || chunk.size > data.size - filled) return false
        chunk.copyInto(data, offset)
        filled += chunk.size
        return true
    }

    fun wipe() {
        data.fill(0)
        filled = 0
    }
}

/** Equivalent of [SequentialBytes] for the ARGB pixels of a [width] × [height] image. */
internal class SequentialPixels(
    val width: Int,
    val height: Int,
) {
    val data = IntArray(width * height)
    private var filled = 0

    val isComplete: Boolean get() = filled == data.size

    fun put(
        offset: Int,
        chunk: IntArray,
    ): Boolean {
        if (offset != filled || chunk.isEmpty() || chunk.size > data.size - filled) return false
        chunk.copyInto(data, offset)
        filled += chunk.size
        return true
    }

    fun wipe() {
        data.fill(0)
        filled = 0
    }
}

/**
 * Range of isolated UIDs of an Android user (`Process.FIRST_ISOLATED_UID` to
 * `LAST_ISOLATED_UID`, hidden API): used to recognize the isolated process on APIs 26 and
 * 27, where `Process.isIsolated()` does not exist yet.
 */
internal fun isIsolatedUid(uid: Int): Boolean = uid % 100_000 in 99_000..99_999
