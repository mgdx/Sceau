package io.github.mgdx.sceau.ui.result

import io.github.mgdx.sceau.core.model.ImageFormat

private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

/** JP2 signature box (ISO 15444-1, annex I). */
private val JP2_MAGIC =
    byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A)

/** Start of a raw JPEG 2000 codestream: SOC then SIZ markers. */
private val J2K_MAGIC = byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51)

/**
 * Format of an image from the chip, inferred from its first bytes only: [ImageFormat.JPEG]
 * (`FF D8 FF`), [ImageFormat.JPEG2000] (JP2 signature box or raw J2K codestream), otherwise null,
 * and the image is not decoded. The format declared by the chip is never taken into account
 * (audit V18): image decoders detect the format from the content, and a forged chip could
 * otherwise feed them PNG, WebP, GIF or any other format under the JPEG label. Applied by the
 * result screen and, once again, by the isolated decoding service.
 */
fun detectImageFormat(bytes: ByteArray): ImageFormat? =
    when {
        bytes.startsWith(JPEG_MAGIC) -> ImageFormat.JPEG
        bytes.startsWith(JP2_MAGIC) || bytes.startsWith(J2K_MAGIC) -> ImageFormat.JPEG2000
        else -> null
    }

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
