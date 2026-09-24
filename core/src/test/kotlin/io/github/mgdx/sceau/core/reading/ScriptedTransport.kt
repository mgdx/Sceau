package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.CardTransport
import java.util.Locale

/**
 * Transport simulé : rejoue une liste d'échanges APDU dans l'ordre. Chaque échange attend une
 * commande commençant par un préfixe hexadécimal et produit une réponse (ou lève une
 * exception). Un échange [Exchange.repeatable] répond à toutes les commandes consécutives qui
 * correspondent à son préfixe. Toute commande inattendue fait échouer le test
 * ([AssertionError] : JMRTD n'attrape que des `Exception`, elle traverse donc tout).
 */
class ScriptedTransport(
    vararg exchanges: Exchange,
    override val maxTransceiveLength: Int = 261,
) : CardTransport {
    class Exchange(
        val prefix: String,
        val repeatable: Boolean = false,
        val reply: (ByteArray) -> ByteArray,
    )

    private val script = exchanges.toList()
    private var index = 0

    /** Commandes reçues, en hexadécimal majuscule. */
    val sent = mutableListOf<String>()
    var closed = false
        private set

    override var timeoutMillis: Int = 1000

    override fun transceive(apdu: ByteArray): ByteArray {
        val hex = apdu.toHex()
        sent += hex
        while (index < script.size) {
            val exchange = script[index]
            if (hex.startsWith(exchange.prefix)) {
                if (!exchange.repeatable) index++
                return exchange.reply(apdu)
            }
            if (!exchange.repeatable) break
            index++
        }
        throw AssertionError("Commande inattendue n°${sent.size} : $hex (attendu : ${script.getOrNull(index)?.prefix})")
    }

    override fun close() {
        closed = true
    }

    /** Vrai si tous les échanges non répétables ont été consommés. */
    val exhausted: Boolean get() = script.drop(index).all { it.repeatable }

    companion object {
        const val SW_OK = "9000"

        fun respond(
            prefix: String,
            response: String,
        ) = Exchange(prefix) { response.hexToBytes() }

        fun raise(
            prefix: String,
            error: Exception,
        ) = Exchange(prefix) { throw error }

        /** SELECT d'un EF (P1=02, P2=0C) répondu 9000, puis READ BINARY servis depuis [content]. */
        fun file(
            fid: String,
            content: ByteArray,
        ): Array<Exchange> =
            arrayOf(
                respond("00A4020C02$fid", SW_OK),
                Exchange("00B0", repeatable = true) { command -> readBinary(content, command) },
            )

        /** SELECT d'un EF répondu par [sw] (fichier absent, accès refusé…). */
        fun missingFile(
            fid: String,
            sw: String = "6A82",
        ) = respond("00A4020C02$fid", sw)

        private fun readBinary(
            content: ByteArray,
            command: ByteArray,
        ): ByteArray {
            val offset = ((command[2].toInt() and 0x7F) shl 8) or (command[3].toInt() and 0xFF)
            val le = (command[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val end = minOf(offset + le, content.size)
            return content.copyOfRange(offset, end) + SW_OK.hexToBytes()
        }

        fun ByteArray.toHex(): String = joinToString("") { String.format(Locale.ROOT, "%02X", it) }

        fun String.hexToBytes(): ByteArray {
            val clean = replace(" ", "")
            return ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }
    }
}
