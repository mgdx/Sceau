package io.github.mgdx.sceau.nfc

import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.SceauException
import java.io.IOException
import java.util.Locale

/**
 * [CardTransport] au-dessus d'[IsoDep]. Se connecte à la première APDU si besoin.
 * Le délai par défaut est long : PACE sur la CNIe demande plusieurs secondes de calcul
 * côté puce.
 *
 * Erreurs : seule une vraie perte du document (ou une fermeture demandée) donne
 * [SceauException.ConnectionLost]. Toute autre erreur d'E/S devient une erreur technique dont
 * l'identifiant (code d'E/S, INS, longueur de l'APDU) permet le diagnostic sans journal.
 */
class IsoDepTransport(
    private val isoDep: IsoDep,
    timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : CardTransport {
    @Volatile
    private var timeout: Int = timeoutMillis

    /** Positionné par [close] : seule une fermeture demandée donne une perte de connexion. */
    @Volatile
    private var closed = false

    override val maxTransceiveLength: Int
        get() =
            if (isoDep.isExtendedLengthApduSupported) {
                isoDep.maxTransceiveLength
            } else {
                minOf(isoDep.maxTransceiveLength, SHORT_APDU_MAX_LENGTH)
            }

    override var timeoutMillis: Int
        get() = timeout
        set(value) {
            timeout = value
            // IsoDep remet le délai à sa valeur par défaut à la connexion : il est aussi
            // réappliqué dans ensureConnected().
            if (isoDep.isConnected) isoDep.timeout = value
        }

    override fun transceive(apdu: ByteArray): ByteArray {
        // Lecture annulée : ne jamais rouvrir la connexion.
        if (closed) throw SceauException.ConnectionLost()
        return try {
            ensureConnected()
            isoDep.transceive(apdu)
        } catch (e: TagLostException) {
            throw SceauException.ConnectionLost(e)
        } catch (e: IOException) {
            throw classifyIoFailure(
                message = e.message,
                tagLost = e.cause is TagLostException,
                connected = isConnectedQuietly(),
                closed = closed,
                apdu = apdu,
                cause = e,
            )
        } catch (e: SecurityException) {
            // « Tag is out of date » : le document a été retiré puis un autre présenté.
            throw SceauException.ConnectionLost(e)
        } catch (e: IllegalStateException) {
            // Transport fermé pendant la lecture : perte de connexion seulement si la fermeture
            // a été demandée (lecture annulée) ; sinon, c'est une anomalie à diagnostiquer.
            throw if (closed) {
                SceauException.ConnectionLost(e)
            } else {
                SceauException.Unexpected(technicalDetail(STATE_PREFIX, apdu), e)
            }
        }
    }

    /** Peut bloquer jusqu'à la fin d'une APDU en cours : jamais sur le thread principal. */
    override fun close() {
        closed = true
        try {
            isoDep.close()
        } catch (e: IOException) {
            // Déjà déconnecté : rien à faire.
        }
    }

    private fun ensureConnected() {
        if (!isoDep.isConnected) {
            isoDep.connect()
            isoDep.timeout = timeout
        }
    }

    /** `IsoDep.isConnected` interroge le service NFC : il ne doit pas masquer l'erreur d'origine. */
    private fun isConnectedQuietly(): Boolean =
        try {
            isoDep.isConnected
        } catch (e: RuntimeException) {
            false
        }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000

        /** En-tête (4) + Lc (1) + données (255) + Le (1) d'une APDU courte. */
        private const val SHORT_APDU_MAX_LENGTH = 261

        private const val IO_PREFIX = "IO-"
        private const val STATE_PREFIX = "STATE"
        private const val LENGTH_ROUNDING = 10

        /**
         * Messages connus des `IOException` d'`android.nfc` (liste fermée) et leur code court.
         * Le message brut n'est jamais repris dans un identifiant d'erreur.
         */
        private val IO_MESSAGE_CODES =
            mapOf(
                "Transceive failed" to "TRANSCEIVE_FAILED",
                "Transceive length exceeds supported maximum" to "TOO_LONG",
                "NFC service died" to "SERVICE_DIED",
                "NFC service dead" to "SERVICE_DIED",
            )

        /** Vrai si le message d'une [IOException] d'`IsoDep` évoque un délai dépassé. */
        internal fun isTimeoutMessage(message: String?): Boolean {
            if (message == null) return false
            val lower = message.lowercase(Locale.ROOT)
            return "timeout" in lower || "timed out" in lower || "time out" in lower
        }

        /** Code court d'un message d'`IOException` : `OTHER` s'il est hors de la liste fermée. */
        internal fun ioMessageCode(message: String?): String =
            if (message == null) "NO_MESSAGE" else IO_MESSAGE_CODES[message.trim()] ?: "OTHER"

        /**
         * Classe une `IOException` d'`IsoDep` : document retiré ou transport fermé → perte de
         * connexion ; délai → [SceauException.Timeout] ; sinon erreur technique
         * `IO-<code>-INS<xx>-L<n>`.
         */
        internal fun classifyIoFailure(
            message: String?,
            tagLost: Boolean,
            connected: Boolean,
            closed: Boolean,
            apdu: ByteArray,
            cause: Throwable? = null,
        ): SceauException =
            when {
                closed || tagLost || !connected -> SceauException.ConnectionLost(cause)
                isTimeoutMessage(message) -> SceauException.Timeout(cause)
                else -> SceauException.Unexpected(technicalDetail(IO_PREFIX + ioMessageCode(message), apdu), cause)
            }

        /**
         * `<prefix>-INS<xx>-L<n>` : octet INS de la commande en hexadécimal et longueur de
         * l'APDU arrondie à la dizaine. Ni l'un ni l'autre n'est une donnée personnelle.
         */
        internal fun technicalDetail(
            prefix: String,
            apdu: ByteArray,
        ): String {
            val ins = if (apdu.size >= 2) String.format(Locale.ROOT, "%02X", apdu[1].toInt() and 0xFF) else "NA"
            val length = (apdu.size + LENGTH_ROUNDING / 2) / LENGTH_ROUNDING * LENGTH_ROUNDING
            return "$prefix-INS$ins-L$length"
        }
    }
}
