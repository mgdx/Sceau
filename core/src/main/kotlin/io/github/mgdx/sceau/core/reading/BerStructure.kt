package io.github.mgdx.sceau.core.reading

/**
 * Contrôle des longueurs BER d'un fichier de la puce avant de le confier à un parseur de JMRTD.
 *
 * Le `TLVInputStream` de JMRTD alloue d'emblée un tableau de la longueur annoncée par un TLV
 * interne (`readValue`) : un fichier de quelques octets dont un élément annonce 2 Go provoque
 * une `OutOfMemoryError`, bien que le fichier entier respecte son plafond (FileSizeLimits ne
 * contrôle que l'en-tête externe). Ce contrôle refuse un tel fichier sans rien allouer.
 *
 * Le parcours suit celui d'un lecteur en flux comme JMRTD, qui ignore les bornes des éléments
 * construits : il entre dans chaque élément construit (sa valeur est une suite de TLV) et saute
 * la valeur de chaque élément primitif, jusqu'à la fin du tampon. Chaque longueur annoncée doit
 * tenir entre le début de sa valeur et la fin du tampon, et être codée sur quatre octets au plus.
 * Une longueur indéfinie est parcourue comme un élément construit ; un en-tête tronqué en fin de
 * tampon arrête le parcours sans refus (le parseur échouera en fin de flux, sans allocation).
 */
internal object BerStructure {
    private const val MAX_LENGTH_BYTES = 4
    private const val TAG_NUMBER_MASK = 0x1F
    private const val MORE_BIT = 0x80
    private const val LENGTH_COUNT_MASK = 0x7F
    private const val CONSTRUCTED_BIT = 0x20
    private const val BYTE_MASK = 0xFF

    /** Vrai si toutes les longueurs de [bytes] tiennent dans le tampon (voir la classe). */
    fun lengthsFit(bytes: ByteArray): Boolean {
        val end = bytes.size
        var index = 0
        while (index < end) {
            val first = bytes[index++].toInt() and BYTE_MASK
            if (first and TAG_NUMBER_MASK == TAG_NUMBER_MASK) {
                while (index < end && bytes[index].toInt() and MORE_BIT != 0) index++
                index++
            }
            if (index >= end) return true
            val lengthByte = bytes[index++].toInt() and BYTE_MASK
            val length: Long
            if (lengthByte < MORE_BIT) {
                length = lengthByte.toLong()
            } else {
                val count = lengthByte and LENGTH_COUNT_MASK
                if (count > MAX_LENGTH_BYTES) return false
                if (index + count > end) return true
                var value = 0L
                repeat(count) { value = (value shl Byte.SIZE_BITS) or (bytes[index++].toLong() and 0xFF) }
                // Longueur indéfinie (count = 0) : le contenu est parcouru comme une suite de TLV.
                if (count == 0) continue
                length = value
            }
            if (index + length > end) return false
            if (first and CONSTRUCTED_BIT == 0) index += length.toInt()
        }
        return true
    }
}
