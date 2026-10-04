package io.github.mgdx.sceau.core.reading

import net.sf.scuba.smartcards.APDUWrapper
import org.jmrtd.APDULevelReadBinaryCapable
import org.jmrtd.PassportService
import org.jmrtd.lds.LDSFileUtil

/**
 * Plafonds de taille des fichiers lus sur la puce (audit V11).
 *
 * JMRTD alloue d'emblée un tableau de la longueur annoncée par l'en-tête TLV du fichier, puis le
 * remplit bloc par bloc : une puce forgée qui annonce 2 Go provoque une `OutOfMemoryError`, et
 * 50 Mo quelque 235 000 APDU. La longueur annoncée (en-tête compris) est donc comparée au plafond
 * du fichier dès la lecture de l'en-tête, avant toute allocation.
 *
 * Plafonds, très au-dessus des documents réels (ICAO 9303-10) :
 * - EF.COM, DG1 : 1 Ko (EF.COM : quelques dizaines d'octets ; DG1 : MRZ de 90 caractères au plus) ;
 * - EF.SOD : 32 Ko (hachages des DG et certificat DS : 2 à 6 Ko en pratique) ;
 * - EF.CardAccess, DG11, DG14, DG15 : 16 Ko (SecurityInfos, clés publiques, texte : moins de 2 Ko) ;
 * - DG2, DG7, DG12 : 256 Ko (portrait JPEG 2000 d'une CNIe : 15 à 25 Ko ; signature manuscrite
 *   de DG7 : quelques Ko ; images recto et verso éventuelles dans DG12). DG7 reste au plafond
 *   des fichiers d'images : ICAO 9303-10 ne borne pas sa taille et il peut contenir plusieurs
 *   images (D37) ;
 * - tout autre fichier : 256 Ko.
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

    /** Plafond des fichiers d'images (DG2, DG7, DG12) et de tout fichier non listé. */
    private const val IMAGE_FILE_MAX = 256 * KIB

    /** Nom court du fichier pour les identifiants d'erreur (`SOD`, `DG1`…). */
    fun nameOf(fid: Short): String =
        when (fid) {
            PassportService.EF_COM -> "COM"
            PassportService.EF_SOD -> "SOD"
            PassportService.EF_CARD_ACCESS -> "CARD_ACCESS"
            else -> runCatching { "DG${LDSFileUtil.lookupDataGroupNumberByFID(fid)}" }.getOrDefault("EF")
        }

    /**
     * Taille totale (tag, longueur et valeur) annoncée par l'en-tête TLV [header], ou null si
     * l'en-tête est trop court ou de longueur indéfinie : JMRTD le traite alors lui-même. Une
     * longueur codée sur plus de quatre octets vaut [Long.MAX_VALUE].
     */
    fun announcedSize(header: ByteArray): Long? {
        var index = 0
        if (header.isEmpty()) return null
        // Tag : un octet, ou plusieurs si ses cinq bits de poids faible valent 1 (BER).
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

/** Fichier dont l'en-tête annonce une taille supérieure à son plafond ; sans message. */
internal class FileTooLargeException : RuntimeException()

/**
 * Émetteur de SELECT et READ BINARY pour le `DefaultFileSystem` de JMRTD : contrôle la taille
 * annoncée par l'en-tête de chaque fichier (lecture à l'offset 0) et lève
 * [FileTooLargeException] au-delà du plafond, avant que JMRTD n'alloue le tampon du fichier.
 */
internal class BoundedReadBinarySender(
    private val delegate: APDULevelReadBinaryCapable,
) : APDULevelReadBinaryCapable {
    private var selectedFid: Short = 0

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
        val response = delegate.sendReadBinary(wrapper, sfi, offset, le, isSFIEnabled, isTLVEncodedOffsetNeeded)
        if (offset == 0 && response != null) {
            val size = FileSizeLimits.announcedSize(response)
            if (size != null && size > FileSizeLimits.maxSize(selectedFid)) throw FileTooLargeException()
        }
        return response
    }
}
