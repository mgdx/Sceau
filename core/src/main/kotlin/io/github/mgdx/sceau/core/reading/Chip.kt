package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.SceauException
import net.sf.scuba.smartcards.CardFileInputStream
import org.jmrtd.DefaultFileSystem
import org.jmrtd.PassportService
import org.jmrtd.lds.LDSFileUtil
import org.jmrtd.protocol.ReadBinaryAPDUSender
import java.util.Locale

/**
 * Chip as seen by JMRTD: a [PassportService] on top of the app's transport.
 *
 * - Maximum APDU length: the transport's, capped at 256 (short APDUs) so as never to send
 *   an extended APDU, which many chips refuse.
 * - Reads in blocks of [BLOCK_SIZE] bytes (223, JMRTD's proven value).
 * - SFI disabled: SELECT then READ BINARY, supported by all chips.
 * - Response MAC under secure messaging always verified.
 * - Size of each file capped before reading ([FileSizeLimits], audit V11): files are read
 *   through dedicated `DefaultFileSystem`s, on top of the same secure messaging sender as
 *   [service], whose READ BINARY sender checks the header.
 * - [reconnect] starts from a fresh JMRTD state (service, secure messaging, file systems
 *   and their cache): nothing from the previous connection is reused.
 */
internal class Chip(
    val transport: CardTransport,
) {
    val cardService = TransportCardService(transport)

    private val maxTransceiveLength =
        transport.maxTransceiveLength
            .coerceAtMost(PassportService.NORMAL_MAX_TRANCEIVE_LENGTH)
            .coerceAtLeast(MIN_TRANSCEIVE_LENGTH)

    /** JMRTD state of a connection, replaced on each [reconnect]. */
    private inner class Link {
        val service =
            PassportService(
                cardService,
                maxTransceiveLength,
                maxTransceiveLength,
                BLOCK_SIZE,
                false,
                true,
            )

        private val boundedSender = BoundedReadBinarySender(ReadBinaryAPDUSender(service.secureMessagingAPDUSender))

        /** EF.CardAccess, at MF level: always in clear, like JMRTD's root file system. */
        val rootFileSystem = DefaultFileSystem(boundedSender, false)

        /** ICAO applet files, under the current secure messaging of [service]. */
        val appletFileSystem = DefaultFileSystem(boundedSender, false)

        /**
         * MF files read under secure messaging (EF.CardSecurity, PACE-CAM). Cache separate from
         * the applet's: EF.CardSecurity (MF) and EF.SOD (applet) share FID 011D.
         */
        val securedMasterFileSystem = DefaultFileSystem(boundedSender, false)
    }

    private var link = Link()

    /** JMRTD service of the current connection (replaced by [reconnect]). */
    val service: PassportService get() = link.service

    /** Rethrows the transport error (document removed, timeout) if one occurred. */
    fun rethrowTransportFailure() {
        cardService.transportFailure?.let { throw it }
    }

    /**
     * Resets the connection ([CardTransport.reconnect]) and forgets the transport error that
     * prompted it: the chip starts over, without secure messaging or a selected applet, and
     * so does Sceau (new JMRTD state, without the session keys or file cache of the previous
     * connection).
     */
    fun reconnect() {
        // Forgotten first: a failure of the reconnection itself must come out unchanged.
        cardService.clearTransportFailure()
        transport.reconnect()
        link = Link().also { it.service.open() }
    }

    /**
     * Reads a whole file (EF.CardAccess at MF level, or a file of the ICAO applet). A file
     * whose header announces more than its cap throws [SceauException.Unexpected]
     * (`<file>-TOO_LARGE`) without being read; a read that stops progressing throws
     * [SceauException.ReadStalled] (`<file>`, audit V29).
     */
    fun readFile(fid: Short): ByteArray {
        val current = link
        val fileSystem =
            if (fid == PassportService.EF_CARD_ACCESS) {
                current.rootFileSystem
            } else {
                current.appletFileSystem.withCurrentWrapper()
            }
        return read(fileSystem, fid)
    }

    /**
     * Reads an MF file under the current secure messaging: EF.CardSecurity, after PACE and
     * before selecting the ICAO applet (ICAO 9303-11 §4.4, PACE-CAM). Null if absent or
     * unreadable; a transport error, or a read that stops progressing (V29), is rethrown.
     */
    fun readOptionalSecuredMasterFile(fid: Short): ByteArray? =
        try {
            read(link.securedMasterFileSystem.withCurrentWrapper(), fid)
        } catch (e: Exception) {
            rethrowTransportFailure()
            rethrowReadStalled(e)
            null
        }

    /**
     * Audit V29: a chip that stops delivering a file, even an optional one, interrupts the read
     * ([SceauException.ReadStalled]) instead of making it look absent.
     */
    private fun rethrowReadStalled(e: Exception) {
        if (e is SceauException.ReadStalled) throw e
    }

    private fun DefaultFileSystem.withCurrentWrapper(): DefaultFileSystem =
        also { fs -> service.wrapper.let { if (fs.wrapper !== it) fs.setWrapper(it) } }

    private fun read(
        fileSystem: DefaultFileSystem,
        fid: Short,
    ): ByteArray =
        try {
            fileSystem.selectFile(fid)
            CardFileInputStream(BLOCK_SIZE, fileSystem).use { it.readBytes() }
        } catch (e: Exception) {
            if (e.causeChain().any { it is FileTooLargeException }) {
                throw SceauException.Unexpected("${FileSizeLimits.nameOf(fid)}-TOO_LARGE")
            }
            if (e.causeChain().any { it is FileReadStalledException }) {
                throw SceauException.ReadStalled(FileSizeLimits.nameOf(fid))
            }
            throw e
        }

    /**
     * Reads a file; null if absent or unreadable. A transport error, or a read that stops
     * progressing (audit V29), is rethrown.
     */
    fun readOptionalFile(fid: Short): ByteArray? =
        try {
            readFile(fid)
        } catch (e: Exception) {
            rethrowTransportFailure()
            rethrowReadStalled(e)
            null
        }

    fun close() {
        link.service.close()
    }

    companion object {
        const val BLOCK_SIZE = PassportService.DEFAULT_MAX_BLOCKSIZE
        private const val MIN_TRANSCEIVE_LENGTH = 64

        fun fidOf(dataGroup: Int): Short = LDSFileUtil.lookupFIDByDataGroupNumber(dataGroup)
    }
}

/** Technical failure of a step, with a short label and no personal data. */
internal class StepFailure(
    val tag: String,
    cause: Throwable,
) : Exception(tag, cause)

/**
 * Stable technical identifier of an unexpected error: step, optional label, exception
 * class and SW in hexadecimal. Never the exception message.
 */
internal fun technicalCode(
    step: String,
    error: Throwable,
): String {
    val tagged = error as? StepFailure
    val root = tagged?.cause ?: error
    val parts = mutableListOf(step)
    tagged?.let { parts += it.tag }
    parts += root.javaClass.simpleName.ifEmpty { "Exception" }
    root.statusWord()?.let { parts += String.format(Locale.ROOT, "%04X", it) }
    return parts.joinToString("-")
}
