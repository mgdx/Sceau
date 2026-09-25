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
 * Puce vue par JMRTD : un [PassportService] au-dessus du transport de l'app.
 *
 * - Longueur maximale des APDU : celle du transport, plafonnée à 256 (APDU courtes) pour ne
 *   jamais émettre d'APDU étendue, que beaucoup de puces refusent.
 * - Lecture par blocs de [BLOCK_SIZE] octets (223, valeur éprouvée de JMRTD).
 * - SFI désactivé : SELECT puis READ BINARY, pris en charge par toutes les puces.
 * - MAC des réponses en messagerie sécurisée toujours vérifié.
 * - Taille de chaque fichier plafonnée avant lecture ([FileSizeLimits], audit V11) : les
 *   fichiers sont lus par des `DefaultFileSystem` propres, au-dessus du même émetteur de
 *   messagerie sécurisée que [service], dont l'émetteur de READ BINARY contrôle l'en-tête.
 */
internal class Chip(
    val transport: CardTransport,
) {
    val cardService = TransportCardService(transport)

    private val maxTransceiveLength =
        transport.maxTransceiveLength
            .coerceAtMost(PassportService.NORMAL_MAX_TRANCEIVE_LENGTH)
            .coerceAtLeast(MIN_TRANSCEIVE_LENGTH)

    val service =
        PassportService(
            cardService,
            maxTransceiveLength,
            maxTransceiveLength,
            BLOCK_SIZE,
            false,
            true,
        )

    /** Relance l'erreur du transport (document retiré, délai) si elle s'est produite. */
    fun rethrowTransportFailure() {
        cardService.transportFailure?.let { throw it }
    }

    /**
     * Réinitialise la liaison ([CardTransport.reconnect]) et oublie l'erreur de transport qui l'a
     * motivée : la puce repart de zéro, sans messagerie sécurisée ni applet sélectionnée.
     */
    fun reconnect() {
        // D'abord oubliée : un échec de la reconnexion elle-même doit ressortir tel quel.
        cardService.clearTransportFailure()
        transport.reconnect()
    }

    private val boundedSender = BoundedReadBinarySender(ReadBinaryAPDUSender(service.secureMessagingAPDUSender))

    /** EF.CardAccess, au niveau MF : toujours en clair, comme le système de fichiers racine de JMRTD. */
    private val rootFileSystem = DefaultFileSystem(boundedSender, false)

    /** Fichiers de l'applet ICAO, sous la messagerie sécurisée courante de [service]. */
    private val appletFileSystem = DefaultFileSystem(boundedSender, false)

    /**
     * Lit un fichier entier (EF.CardAccess au niveau MF, ou un fichier de l'applet ICAO). Un
     * fichier dont l'en-tête annonce plus que son plafond lève [SceauException.Unexpected]
     * (`<fichier>-TOO_LARGE`) sans être lu.
     */
    fun readFile(fid: Short): ByteArray {
        val fileSystem =
            if (fid == PassportService.EF_CARD_ACCESS) {
                rootFileSystem
            } else {
                appletFileSystem.also { fs -> service.wrapper.let { if (fs.wrapper !== it) fs.setWrapper(it) } }
            }
        return try {
            fileSystem.selectFile(fid)
            CardFileInputStream(BLOCK_SIZE, fileSystem).use { it.readBytes() }
        } catch (e: Exception) {
            if (e.causeChain().any { it is FileTooLargeException }) {
                throw SceauException.Unexpected("${FileSizeLimits.nameOf(fid)}-TOO_LARGE")
            }
            throw e
        }
    }

    /** Lit un fichier ; null s'il est absent ou illisible. Une erreur de transport est relancée. */
    fun readOptionalFile(fid: Short): ByteArray? =
        try {
            readFile(fid)
        } catch (e: Exception) {
            rethrowTransportFailure()
            null
        }

    fun close() {
        service.close()
    }

    companion object {
        const val BLOCK_SIZE = PassportService.DEFAULT_MAX_BLOCKSIZE
        private const val MIN_TRANSCEIVE_LENGTH = 64

        fun fidOf(dataGroup: Int): Short = LDSFileUtil.lookupFIDByDataGroupNumber(dataGroup)
    }
}

/** Échec technique d'une étape, avec une étiquette courte et sans donnée personnelle. */
internal class StepFailure(
    val tag: String,
    cause: Throwable,
) : Exception(tag, cause)

/**
 * Identifiant technique stable d'une erreur inattendue : étape, étiquette éventuelle,
 * classe d'exception et SW en hexadécimal. Jamais le message de l'exception.
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
