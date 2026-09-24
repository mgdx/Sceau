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
 * Erreurs : un échec survenu après 90 % du délai est un [SceauException.Timeout] ; seule une
 * vraie perte du document (ou une fermeture demandée) donne [SceauException.ConnectionLost].
 * Toute autre erreur d'E/S devient une erreur technique. Chaque identifiant porte l'INS et la
 * longueur de l'APDU en cours, pour le diagnostic sans journal.
 */
class IsoDepTransport(
    private val isoDep: IsoDep,
    timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : CardTransport {
    @Volatile
    private var timeout: Int = timeoutMillis

    /**
     * Délai réellement appliqué par le service NFC, relu après chaque affectation : il peut
     * borner la valeur demandée. Sert à classer un échec en délai dépassé ou en perte.
     */
    @Volatile
    private var effectiveTimeout: Int = timeoutMillis

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
            if (isoDep.isConnected) applyTimeout() else effectiveTimeout = value
        }

    override fun transceive(apdu: ByteArray): ByteArray {
        // Lecture annulée : ne jamais rouvrir la connexion.
        if (closed) throw SceauException.ConnectionLost(detail = commandDetail(apdu))
        val start = System.nanoTime()
        return try {
            ensureConnected()
            isoDep.transceive(apdu)
        } catch (e: IOException) {
            // TagLostException compris : après un délai dépassé, IsoDep signale souvent une perte.
            throw classifyIoFailure(
                message = e.message,
                tagLost = e is TagLostException || e.cause is TagLostException,
                connected = isConnectedQuietly(),
                closed = closed,
                apdu = apdu,
                elapsedMillis = (System.nanoTime() - start) / NANOS_PER_MILLI,
                // Délai effectif, lu après ensureConnected() qui l'a éventuellement réappliqué.
                timeoutMillis = effectiveTimeout,
                cause = e,
            )
        } catch (e: SecurityException) {
            // « Tag is out of date » : le document a été retiré puis un autre présenté.
            throw SceauException.ConnectionLost(e, commandDetail(apdu))
        } catch (e: IllegalStateException) {
            // Transport fermé pendant la lecture : perte de connexion seulement si la fermeture
            // a été demandée (lecture annulée) ; sinon, c'est une anomalie à diagnostiquer.
            throw if (closed) {
                SceauException.ConnectionLost(e, commandDetail(apdu))
            } else {
                SceauException.Unexpected(technicalDetail(STATE_PREFIX, apdu), e)
            }
        }
    }

    /**
     * Coupe puis rétablit la liaison avec le même document (`IsoDep.close()` réinitialise la
     * puce côté service NFC), délai réappliqué. Ne marque pas le transport comme fermé.
     */
    override fun reconnect() {
        if (closed) throw SceauException.ConnectionLost(detail = RECONNECT_DETAIL)
        try {
            isoDep.close()
        } catch (e: IOException) {
            // Déjà déconnecté : on se reconnecte quand même.
        }
        try {
            isoDep.connect()
            applyTimeout()
        } catch (e: IOException) {
            throw SceauException.ConnectionLost(e, RECONNECT_DETAIL)
        } catch (e: SecurityException) {
            throw SceauException.ConnectionLost(e, RECONNECT_DETAIL)
        } catch (e: IllegalStateException) {
            throw SceauException.ConnectionLost(e, RECONNECT_DETAIL)
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
            applyTimeout()
        }
    }

    /** Applique le délai demandé à la liaison connectée, puis relit celui que le système a retenu. */
    private fun applyTimeout() {
        val requested = timeout
        isoDep.timeout = requested
        val readBack =
            try {
                isoDep.timeout
            } catch (e: RuntimeException) {
                0
            }
        effectiveTimeout = effectiveTimeout(requested, readBack)
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
        private const val RECONNECT_DETAIL = "RECONNECT"
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Part du délai (9/10) au-delà de laquelle un échec est un délai dépassé. */
        private const val TIMEOUT_RATIO_NUMERATOR = 9
        private const val TIMEOUT_RATIO_DENOMINATOR = 10

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
         * Classe une `IOException` d'`IsoDep` : transport fermé → perte de connexion ; échec
         * survenu après au moins 90 % du délai → [SceauException.Timeout], même si
         * IsoDep signale ensuite une perte (il réinitialise la liaison après un délai dépassé) ;
         * document retiré → perte de connexion ; message de délai → délai ; sinon erreur
         * technique `IO-<code>-INS<xx>-L<n>`. Délai et perte portent le diagnostic `INS<xx>-L<n>`.
         */
        internal fun classifyIoFailure(
            message: String?,
            tagLost: Boolean,
            connected: Boolean,
            closed: Boolean,
            apdu: ByteArray,
            elapsedMillis: Long = 0,
            timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
            cause: Throwable? = null,
        ): SceauException =
            when {
                closed -> SceauException.ConnectionLost(cause, commandDetail(apdu))
                isTimeoutElapsed(elapsedMillis, timeoutMillis) -> SceauException.Timeout(cause, commandDetail(apdu))
                tagLost || !connected -> SceauException.ConnectionLost(cause, commandDetail(apdu))
                isTimeoutMessage(message) -> SceauException.Timeout(cause, commandDetail(apdu))
                else -> SceauException.Unexpected(technicalDetail(IO_PREFIX + ioMessageCode(message), apdu), cause)
            }

        /**
         * Délai effectif d'après la relecture d'`IsoDep.getTimeout()` : [readBack] s'il est
         * positif (le système a pu borner [requested], par exemple sous 60 s), sinon [requested]
         * (`getTimeout()` renvoie 0 si le service NFC est injoignable).
         */
        internal fun effectiveTimeout(
            requested: Int,
            readBack: Int,
        ): Int = if (readBack > 0) readBack else requested

        /** Vrai si [elapsedMillis] atteint au moins 90 % de [timeoutMillis]. */
        internal fun isTimeoutElapsed(
            elapsedMillis: Long,
            timeoutMillis: Int,
        ): Boolean = timeoutMillis > 0 && elapsedMillis * TIMEOUT_RATIO_DENOMINATOR >= timeoutMillis.toLong() * TIMEOUT_RATIO_NUMERATOR

        /** `<prefix>-INS<xx>-L<n>` (voir [commandDetail]). */
        internal fun technicalDetail(
            prefix: String,
            apdu: ByteArray,
        ): String = "$prefix-${commandDetail(apdu)}"

        /**
         * `INS<xx>-L<n>` : octet INS de la commande en hexadécimal et longueur de l'APDU arrondie
         * à la dizaine. Ni l'un ni l'autre n'est une donnée personnelle.
         */
        internal fun commandDetail(apdu: ByteArray): String {
            val ins = if (apdu.size >= 2) String.format(Locale.ROOT, "%02X", apdu[1].toInt() and 0xFF) else "NA"
            val length = (apdu.size + LENGTH_ROUNDING / 2) / LENGTH_ROUNDING * LENGTH_ROUNDING
            return "INS$ins-L$length"
        }
    }
}
