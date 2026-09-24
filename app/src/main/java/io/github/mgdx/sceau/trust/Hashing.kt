package io.github.mgdx.sceau.trust

import java.security.MessageDigest

private const val HEX_DIGITS = "0123456789abcdef"

/** Empreinte SHA-256 en hexadécimal minuscule, sans séparateur. */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return buildString(digest.size * 2) {
        digest.forEach {
            val value = it.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }
}
