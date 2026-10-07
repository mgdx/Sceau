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
 *
 * Chaque réponse est contrôlée ([ResponseShape], audit V36) avant d'être rendue à JMRTD ; une
 * réponse malformée lève une [CardServiceException], traitée comme toute erreur de la puce.
 */
internal class TransportCardService(
    private val transport: CardTransport,
) : CardService() {
    @Volatile
    var transportFailure: SceauException? = null
        private set

    @Volatile
    private var opened = false

    /** Oublie l'erreur du transport, une fois la liaison réinitialisée (repli de PACE sur BAC). */
    fun clearTransportFailure() {
        transportFailure = null
    }

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
        if (!ResponseShape.isWellFormed(command.cla, command.ins, response)) throw CardServiceException("Malformed response")
        return ResponseAPDU(response)
    }

    override fun getATR(): ByteArray? = null

    override fun close() {
        opened = false
        transport.close()
    }

    override fun isConnectionLost(e: Exception): Boolean = transportFailure != null || e.findTransportFailure() != null
}

/**
 * Contrôle des réponses de la puce avant JMRTD (audit V36).
 *
 * JMRTD alloue la longueur annoncée d'un DO'85 ou DO'87 de messagerie sécurisée
 * (`SecureMessagingWrapper.readDO87`) **avant** de vérifier le MAC, et celle des objets de la
 * réponse de GENERAL AUTHENTICATE de PACE (`TLVInputStream.readValue`) : une réponse de quelques
 * octets annonçant 2 Go provoque une `OutOfMemoryError`, qui traverse les `catch (Exception)` du
 * cœur. Sont donc refusées :
 * - toute réponse de moins de 2 octets (pas de SW) ou de plus de 64 Kio de données (APDU
 *   courtes seulement : 256 au plus en pratique ; `FragmentBuffer` de JMRTD double sinon ses
 *   tampons sans borne) ;
 * - pour une commande protégée (CLA avec les bits de messagerie sécurisée `0C`, que JMRTD pose
 *   sur toute commande emballée), une réponse dont un DO'85 ou DO'87 ne tient pas dans les
 *   données. Le parcours reproduit celui de JMRTD, octet par octet (un octet inconnu est sauté
 *   seul, le parcours s'arrête au DO'8E) : un contrôle TLV ordinaire (`BerStructure`) se laisse
 *   contourner par un DO'87 caché dans la valeur d'un autre TLV, que JMRTD lit quand même ;
 * - pour GENERAL AUTHENTICATE en clair (étapes de PACE), une réponse dont les longueurs BER ne
 *   tiennent pas ([BerStructure.lengthsFit], qui lit les en-têtes comme SCUBA).
 *
 * Les réponses en clair des autres commandes (SELECT, READ BINARY d'EF.CardAccess, GET
 * CHALLENGE, EXTERNAL AUTHENTICATE de BAC) ne sont pas interprétées : leurs données sont
 * quelconques (un défi aléatoire peut commencer par `87`).
 */
internal object ResponseShape {
    /** Données d'une réponse au plus (hors SW), bien au-delà des APDU courtes (256). */
    const val MAX_RESPONSE_DATA = 64 * 1024

    private const val SW_LENGTH = 2
    private const val SECURE_MESSAGING_CLA = 0x0C
    private const val GENERAL_AUTHENTICATE = 0x86
    private const val DO85 = 0x85
    private const val DO87 = 0x87
    private const val DO8E = 0x8E
    private const val DO99 = 0x99
    private const val DO99_LENGTH = 2
    private const val BYTE_MASK = 0xFF
    private const val LONG_FORM = 0x80
    private const val LENGTH_COUNT_MASK = 0x7F
    private const val MAX_LENGTH_BYTES = 4

    fun isWellFormed(
        cla: Int,
        ins: Int,
        response: ByteArray,
    ): Boolean {
        if (response.size < SW_LENGTH || response.size > MAX_RESPONSE_DATA + SW_LENGTH) return false
        val dataEnd = response.size - SW_LENGTH
        if (dataEnd == 0) return true
        return when {
            cla and SECURE_MESSAGING_CLA == SECURE_MESSAGING_CLA -> secureMessagingLengthsFit(response, dataEnd)
            ins == GENERAL_AUTHENTICATE -> BerStructure.lengthsFit(response.copyOf(dataEnd))
            else -> true
        }
    }

    /**
     * Vrai si chaque DO'85 et DO'87 que JMRTD lirait dans [bytes] (jusqu'à [end], SW exclu) tient
     * dans les données, en-tête et octet de bourrage `01` du DO'87 compris.
     */
    fun secureMessagingLengthsFit(
        bytes: ByteArray,
        end: Int = bytes.size,
    ): Boolean {
        var index = 0
        while (index < end) {
            when (val tag = bytes[index].toInt() and BYTE_MASK) {
                DO85, DO87 -> {
                    index++
                    if (index >= end) return false
                    val first = bytes[index++].toInt() and BYTE_MASK
                    var length = first.toLong()
                    if (first and LONG_FORM != 0) {
                        val count = first and LENGTH_COUNT_MASK
                        if (count == 0 || count > MAX_LENGTH_BYTES || index + count > end) return false
                        length = 0
                        repeat(count) { length = (length shl Byte.SIZE_BITS) or (bytes[index++].toLong() and BYTE_MASK.toLong()) }
                    }
                    if (tag == DO87) {
                        // Octet `01` (bourrage) compris dans la longueur.
                        if (length < 1 || index >= end) return false
                        index++
                        length--
                    }
                    if (index + length > end) return false
                    index += length.toInt()
                }

                // JMRTD s'arrête au MAC (8 ou 16 octets, toute autre longueur refusée).
                DO8E -> {
                    return true
                }

                DO99 -> {
                    index++
                    // Longueur autre que 2 : JMRTD refuse la réponse sans rien allouer.
                    if (index >= end || bytes[index].toInt() and BYTE_MASK != DO99_LENGTH) return true
                    index += 1 + DO99_LENGTH
                }

                else -> {
                    index++
                }
            }
        }
        return true
    }
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
