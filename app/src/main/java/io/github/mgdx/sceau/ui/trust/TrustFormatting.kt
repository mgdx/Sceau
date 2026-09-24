package io.github.mgdx.sceau.ui.trust

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/** Taille maximale d'une Master List importée : 20 Mo. */
const val MAX_MASTER_LIST_BYTES: Int = 20 * 1024 * 1024

/** Fichier refusé car plus gros que la limite. */
class FileTooLargeException : Exception("TOO_LARGE")

/**
 * Lit tout [input] en refusant au-delà de [limit] octets, sans jamais allouer plus que
 * `limit + 1` octets. Lève [FileTooLargeException] si la limite est dépassée.
 */
fun readAtMost(
    input: InputStream,
    limit: Int,
): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw FileTooLargeException()
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val BUFFER_SIZE = 64 * 1024

/** Empreinte hexadécimale regroupée par blocs de quatre caractères, en majuscules. */
fun formatFingerprint(hex: String): String = hex.uppercase(Locale.ROOT).chunked(4).joinToString(" ")
