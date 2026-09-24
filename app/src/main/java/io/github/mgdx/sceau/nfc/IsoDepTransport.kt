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
 */
class IsoDepTransport(
    private val isoDep: IsoDep,
    timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : CardTransport {
    @Volatile
    private var timeout: Int = timeoutMillis

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

    override fun transceive(apdu: ByteArray): ByteArray =
        try {
            ensureConnected()
            isoDep.transceive(apdu)
        } catch (e: TagLostException) {
            throw SceauException.ConnectionLost(e)
        } catch (e: IOException) {
            throw if (isTimeoutMessage(e.message)) SceauException.Timeout(e) else SceauException.ConnectionLost(e)
        } catch (e: SecurityException) {
            // « Tag is out of date » : le document a été retiré puis un autre présenté.
            throw SceauException.ConnectionLost(e)
        } catch (e: IllegalStateException) {
            // Transport fermé pendant la lecture (lecture annulée).
            throw SceauException.ConnectionLost(e)
        }

    override fun close() {
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

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000

        /** En-tête (4) + Lc (1) + données (255) + Le (1) d'une APDU courte. */
        private const val SHORT_APDU_MAX_LENGTH = 261

        /** Vrai si le message d'une [IOException] d'`IsoDep` évoque un délai dépassé. */
        internal fun isTimeoutMessage(message: String?): Boolean {
            if (message == null) return false
            val lower = message.lowercase(Locale.ROOT)
            return "timeout" in lower || "timed out" in lower || "time out" in lower
        }
    }
}
