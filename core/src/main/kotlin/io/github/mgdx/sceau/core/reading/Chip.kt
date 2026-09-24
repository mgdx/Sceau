package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.CardTransport
import org.jmrtd.PassportService
import org.jmrtd.lds.LDSFileUtil
import java.util.Locale

/**
 * Puce vue par JMRTD : un [PassportService] au-dessus du transport de l'app.
 *
 * - Longueur maximale des APDU : celle du transport, plafonnée à 256 (APDU courtes) pour ne
 *   jamais émettre d'APDU étendue, que beaucoup de puces refusent.
 * - Lecture par blocs de [BLOCK_SIZE] octets (223, valeur éprouvée de JMRTD).
 * - SFI désactivé : SELECT puis READ BINARY, pris en charge par toutes les puces.
 * - MAC des réponses en messagerie sécurisée toujours vérifié.
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

    /** Lit un fichier entier (EF.CardAccess au niveau MF, ou un fichier de l'applet ICAO). */
    fun readFile(fid: Short): ByteArray = service.getInputStream(fid, BLOCK_SIZE).use { it.readBytes() }

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
