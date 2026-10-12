package io.github.mgdx.sceau.core.reading

import net.sf.scuba.smartcards.APDUWrapper
import org.jmrtd.APDULevelReadBinaryCapable
import org.jmrtd.PassportService
import org.jmrtd.lds.LDSFileUtil

/**
 * Size caps for the files read from the chip (audit V11).
 *
 * JMRTD immediately allocates an array of the length announced by the file's TLV header, then
 * fills it block by block: a forged chip that announces 2 GB causes an `OutOfMemoryError`, and
 * 50 MB some 235,000 APDUs. The announced length (header included) is therefore compared with the file's
 * cap as soon as the header is read, before any allocation.
 *
 * Caps, far above real documents (ICAO 9303-10):
 * - EF.COM, DG1: 1 KB (EF.COM: a few dozen bytes; DG1: MRZ of 90 characters at most);
 * - EF.SOD: 32 KB (DG hashes and DS certificate: 2 to 6 KB in practice);
 * - EF.CardAccess, DG11, DG14, DG15: 16 KB (SecurityInfos, public keys, text: less than 2 KB);
 * - DG2, DG7, DG12: 256 KB (JPEG 2000 portrait of a CNIe: 15 to 25 KB; handwritten signature
 *   in DG7: a few KB; optional front and back images in DG12). DG7 stays at the image file
 *   cap: ICAO 9303-10 does not bound its size and it may contain several
 *   images (D37);
 * - any other file: 256 KB.
 */
internal object FileSizeLimits {
    private const val KIB = 1024L

    fun maxSize(fid: Short): Long =
        when (fid) {
            PassportService.EF_COM, PassportService.EF_DG1 -> 1 * KIB
            PassportService.EF_SOD -> 32 * KIB
            PassportService.EF_CARD_ACCESS, PassportService.EF_DG11, PassportService.EF_DG14, PassportService.EF_DG15 -> 16 * KIB
            PassportService.EF_DG2, PassportService.EF_DG7, PassportService.EF_DG12 -> IMAGE_FILE_MAX
            else -> IMAGE_FILE_MAX
        }

    /** Cap for image files (DG2, DG7, DG12) and for any unlisted file. */
    private const val IMAGE_FILE_MAX = 256 * KIB

    /**
     * Audit V29: maximum number of READ BINARY commands to read file [fid]: its cap read in
     * blocks of [Chip.BLOCK_SIZE] bytes, plus a margin (header read, chip that responds
     * with shorter blocks). A real file needs far fewer: a 40 KB DG2, 180.
     */
    fun maxReadBinaryCommands(fid: Short): Long = maxSize(fid) / Chip.BLOCK_SIZE + READ_BINARY_MARGIN

    /** Consecutive empty READ BINARY responses beyond which the reading is abandoned (V29). */
    const val MAX_CONSECUTIVE_EMPTY_RESPONSES = 3

    private const val READ_BINARY_MARGIN = 16L

    /** Short file name for error identifiers (`SOD`, `DG1`…). */
    fun nameOf(fid: Short): String =
        when (fid) {
            PassportService.EF_COM -> "COM"
            PassportService.EF_SOD -> "SOD"
            PassportService.EF_CARD_ACCESS -> "CARD_ACCESS"
            else -> runCatching { "DG${LDSFileUtil.lookupDataGroupNumberByFID(fid)}" }.getOrDefault("EF")
        }

    /**
     * Total size (tag, length and value) announced by the TLV [header], or null if
     * the header is too short or of indefinite length: JMRTD then handles it itself. A
     * length encoded on more than four bytes yields [Long.MAX_VALUE].
     */
    fun announcedSize(header: ByteArray): Long? {
        var index = 0
        if (header.isEmpty()) return null
        // Tag: one byte, or several if its five low-order bits are all 1 (BER).
        if (header[index++].toInt() and TAG_NUMBER_MASK == TAG_NUMBER_MASK) {
            while (index < header.size && header[index].toInt() and MORE_BIT != 0) index++
            index++
        }
        if (index >= header.size) return null
        val first = header[index++].toInt() and BYTE_MASK
        if (first < MORE_BIT) return index + first.toLong()
        val count = first and LENGTH_COUNT_MASK
        if (count == 0) return null
        if (count > MAX_LENGTH_BYTES) return Long.MAX_VALUE
        if (index + count > header.size) return null
        var length = 0L
        repeat(count) { length = (length shl Byte.SIZE_BITS) or (header[index++].toLong() and BYTE_MASK.toLong()) }
        return index + length
    }

    private const val BYTE_MASK = 0xFF
    private const val TAG_NUMBER_MASK = 0x1F
    private const val MORE_BIT = 0x80
    private const val LENGTH_COUNT_MASK = 0x7F
    private const val MAX_LENGTH_BYTES = 4
}

/** File whose header announces a size above its cap; no message. */
internal class FileTooLargeException : RuntimeException()

/** Reading of a file that no longer progresses (audit V29); no message. */
internal class FileReadStalledException : RuntimeException()

/**
 * SELECT and READ BINARY sender for JMRTD's `DefaultFileSystem`: checks the size
 * announced by the header of each file (read at offset 0) and throws
 * [FileTooLargeException] above the cap, before JMRTD allocates the file buffer.
 *
 * Audit V29: SCUBA (`CardFileInputStream`) retries a READ BINARY as long as the response is empty,
 * and JMRTD accepts an empty response; a forged chip that answers `90 00` without data would
 * therefore make the reading loop forever. Exchanges are counted per file (reset at each
 * SELECT): beyond [FileSizeLimits.MAX_CONSECUTIVE_EMPTY_RESPONSES] consecutive empty
 * responses, or [FileSizeLimits.maxReadBinaryCommands] READ BINARY commands, the reading throws
 * [FileReadStalledException] without sending any further command.
 */
internal class BoundedReadBinarySender(
    private val delegate: APDULevelReadBinaryCapable,
) : APDULevelReadBinaryCapable {
    private var selectedFid: Short = 0
    private var readCommands = 0L
    private var consecutiveEmpty = 0

    override fun sendSelectApplet(
        wrapper: APDUWrapper?,
        aid: ByteArray?,
    ) = delegate.sendSelectApplet(wrapper, aid)

    override fun sendSelectMF() = delegate.sendSelectMF()

    override fun sendSelectFile(
        wrapper: APDUWrapper?,
        fid: Short,
    ) {
        selectedFid = fid
        readCommands = 0
        consecutiveEmpty = 0
        delegate.sendSelectFile(wrapper, fid)
    }

    override fun sendReadBinary(
        wrapper: APDUWrapper?,
        sfi: Int,
        offset: Int,
        le: Int,
        isSFIEnabled: Boolean,
        isTLVEncodedOffsetNeeded: Boolean,
    ): ByteArray? {
        if (++readCommands > FileSizeLimits.maxReadBinaryCommands(selectedFid)) throw FileReadStalledException()
        val response = delegate.sendReadBinary(wrapper, sfi, offset, le, isSFIEnabled, isTLVEncodedOffsetNeeded)
        if (response == null || response.isEmpty()) {
            if (++consecutiveEmpty >= FileSizeLimits.MAX_CONSECUTIVE_EMPTY_RESPONSES) throw FileReadStalledException()
        } else {
            consecutiveEmpty = 0
        }
        if (offset == 0 && response != null) {
            val size = FileSizeLimits.announcedSize(response)
            if (size != null && size > FileSizeLimits.maxSize(selectedFid)) throw FileTooLargeException()
        }
        return response
    }
}
