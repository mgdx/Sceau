package io.github.mgdx.sceau.ui.result

import io.github.mgdx.sceau.core.model.ImageFormat

private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

/** Boîte de signature JP2 (ISO 15444-1, annexe I). */
private val JP2_MAGIC =
    byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A)

/** Début d'un flux de code JPEG 2000 brut : marqueurs SOC puis SIZ. */
private val J2K_MAGIC = byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51)

/**
 * Format d'une image de la puce, déduit de ses seuls premiers octets : [ImageFormat.JPEG]
 * (`FF D8 FF`), [ImageFormat.JPEG2000] (boîte de signature JP2 ou flux J2K brut), sinon null,
 * et l'image n'est pas décodée. Le format déclaré par la puce n'est jamais pris en compte
 * (audit V18) : les décodeurs d'image détectent le format au contenu, et une puce forgée
 * pourrait sinon leur soumettre du PNG, du WebP, du GIF ou tout autre format sous l'étiquette
 * JPEG. Appliqué par l'écran de résultat et, de nouveau, par le service de décodage isolé.
 */
fun detectImageFormat(bytes: ByteArray): ImageFormat? =
    when {
        bytes.startsWith(JPEG_MAGIC) -> ImageFormat.JPEG
        bytes.startsWith(JP2_MAGIC) || bytes.startsWith(J2K_MAGIC) -> ImageFormat.JPEG2000
        else -> null
    }

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
