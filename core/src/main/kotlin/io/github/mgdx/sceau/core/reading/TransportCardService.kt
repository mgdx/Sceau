package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.SceauException
import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU

/**
 * Adaptateur [CardTransport] → [CardService] SCUBA, pour `org.jmrtd.PassportService`.
 *
 * JMRTD enveloppe les erreurs d'E/S dans des [CardServiceException], parfois plusieurs fois,
 * et en avale certaines (lecture binaire). La première [SceauException] levée par le
 * transport (document retiré, délai dépassé) est donc mémorisée dans [transportFailure] :
 * l'appelant la relance telle quelle, quelle que soit l'exception ressortie de JMRTD.
 * Les messages des exceptions JMRTD contiennent des APDU en hexadécimal (données
 * personnelles) : ils ne sont jamais propagés.
 */
internal class TransportCardService(
    private val transport: CardTransport,
) : CardService() {
    @Volatile
    var transportFailure: SceauException? = null
        private set

    @Volatile
    private var opened = false

    override fun open() {
        opened = true
    }

    override fun isOpen(): Boolean = opened

    override fun transmit(command: CommandAPDU): ResponseAPDU {
        val response =
            try {
                transport.transceive(command.bytes)
            } catch (e: SceauException) {
                if (transportFailure == null) transportFailure = e
                throw CardServiceException("Transport", e)
            }
        if (response.size < 2) throw CardServiceException("Malformed response")
        return ResponseAPDU(response)
    }

    override fun getATR(): ByteArray? = null

    override fun close() {
        opened = false
        transport.close()
    }

    override fun isConnectionLost(e: Exception): Boolean = transportFailure != null || e.findTransportFailure() != null
}

/** Première [SceauException] de la chaîne des causes. */
internal fun Throwable.findTransportFailure(): SceauException? = causeChain().filterIsInstance<SceauException>().firstOrNull()

/** Premier mot d'état (SW1 SW2) porté par une [CardServiceException] de la chaîne, ou null. */
internal fun Throwable.statusWord(): Int? =
    causeChain()
        .filterIsInstance<CardServiceException>()
        .map { it.sw }
        .firstOrNull { it != CardServiceException.SW_NONE }
        ?.and(0xFFFF)

/** Chaîne des causes, bornée pour résister aux cycles. */
internal fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this, Throwable::nextCause).take(MAX_CAUSES)

private fun Throwable.nextCause(): Throwable? = cause?.takeIf { it !== this }

private const val MAX_CAUSES = 16
