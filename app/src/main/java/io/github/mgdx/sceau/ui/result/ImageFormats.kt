package io.github.mgdx.sceau.ui.result

import io.github.mgdx.sceau.core.model.ImageFormat

private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

/** Boîte de signature JP2 (ISO 15444-1, annexe I). */
private val JP2_MAGIC =
    byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A)

/** Début d'un flux de code JPEG 2000 brut : marqueurs SOC puis SIZ. */
private val J2K_MAGIC = byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51)

/**
 * Format à utiliser pour décoder une image de la puce. Le format déclaré prime ; s'il est
 * inconnu, il est déduit des premiers octets.
 */
fun resolveImageFormat(
    declared: ImageFormat,
    bytes: ByteArray,
): ImageFormat {
    if (declared != ImageFormat.UNKNOWN) return declared
    return when {
        bytes.startsWith(JPEG_MAGIC) -> ImageFormat.JPEG
        bytes.startsWith(JP2_MAGIC) || bytes.startsWith(J2K_MAGIC) -> ImageFormat.JPEG2000
        else -> ImageFormat.UNKNOWN
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
