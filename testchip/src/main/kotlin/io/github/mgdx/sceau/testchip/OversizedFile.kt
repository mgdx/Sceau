package io.github.mgdx.sceau.testchip

/**
 * Fichier forgé par une puce hostile : son en-tête TLV (tag [tag], longueur sur quatre octets)
 * annonce [valueLength] octets de valeur, servis à zéro à la demande sans jamais être alloués.
 */
class OversizedFile(
    val tag: Int,
    val valueLength: Long,
) {
    init {
        require(tag in 1..MAX_ONE_BYTE && valueLength in 0..MAX_LENGTH)
    }

    /** En-tête : tag, 0x84, longueur sur quatre octets. */
    val header: ByteArray =
        byteArrayOf(tag.toByte(), LONG_FORM_4.toByte()) +
            ByteArray(LENGTH_BYTES) { ((valueLength shr (Byte.SIZE_BITS * (LENGTH_BYTES - 1 - it))) and BYTE_MASK).toByte() }

    /** Taille totale annoncée, en-tête compris. */
    val size: Long get() = header.size + valueLength

    /** [length] octets à partir de [offset] (moins en fin de fichier). */
    fun read(
        offset: Int,
        length: Int,
    ): ByteArray {
        val end = minOf(offset.toLong() + length, size)
        if (offset >= end) return ByteArray(0)
        return ByteArray((end - offset).toInt()) { index -> header.getOrElse(offset + index) { 0 } }
    }

    private companion object {
        const val MAX_ONE_BYTE = 0xFE
        const val LONG_FORM_4 = 0x84
        const val LENGTH_BYTES = 4
        const val BYTE_MASK = 0xFFL
        const val MAX_LENGTH = 0xFFFFFFFFL
    }
}
