package io.github.mgdx.sceau.core.reading

/**
 * Contrôle des longueurs BER d'un fichier de la puce avant de le confier à un parseur de JMRTD.
 *
 * Le `TLVInputStream` de SCUBA, qu'utilise JMRTD, alloue d'emblée un tableau de la longueur
 * annoncée par un TLV interne (`readValue`) : un fichier de quelques octets dont un élément
 * annonce 2 Go provoque une `OutOfMemoryError`, bien que le fichier entier respecte son plafond
 * (FileSizeLimits ne contrôle que l'en-tête externe). Ce contrôle refuse un tel fichier sans
 * rien allouer.
 *
 * Il lit les en-têtes comme SCUBA (octets `00` et `FF` ignorés avant une étiquette, étiquette
 * multi-octet, longueur longue) et impose :
 * - toute longueur codée sur quatre octets au plus ;
 * - au premier niveau, chaque TLV tient dans le tampon (les octets qui suivent le premier TLV
 *   sont examinés aussi : un parseur en flux peut les lire) ;
 * - dans un élément construit, chaque élément tient exactement dans son parent, en-tête
 *   compris. Qu'un parseur entre dans cet élément ou saute sa valeur, il reprend donc à la même
 *   position que ce contrôle : aucun en-tête qu'il lira n'échappe au contrôle.
 *
 * Un en-tête tronqué en fin de tampon arrête le parcours sans refus (le parseur échouera en fin
 * de flux, sans allocation) ; une longueur indéfinie est refusée (ICAO 9303 impose DER). Au-delà
 * de [MAX_DEPTH] niveaux d'imbrication, le fichier est refusé.
 */
internal object BerStructure {
    private const val MAX_DEPTH = 64
    private const val MAX_LENGTH_BYTES = 4
    private const val TAG_NUMBER_MASK = 0x1F
    private const val MORE_BIT = 0x80
    private const val LENGTH_COUNT_MASK = 0x7F
    private const val CONSTRUCTED_BIT = 0x20
    private const val BYTE_MASK = 0xFF
    private const val PADDING_ZERO = 0x00
    private const val PADDING_FF = 0xFF

    /**
     * Vrai si toutes les longueurs de [bytes] sont cohérentes (voir la classe) et si [primitive]
     * accepte la valeur de chaque élément primitif parcouru : il reçoit l'étiquette (octets
     * big-endian, sur quatre octets au plus) et les bornes de la valeur dans [bytes].
     */
    fun lengthsFit(
        bytes: ByteArray,
        primitive: (tag: Int, valueStart: Int, valueEnd: Int) -> Boolean = { _, _, _ -> true },
    ): Boolean = walk(bytes, 0, bytes.size, 0, primitive)

    /** Parcourt les TLV de [start] à [end] ; au premier niveau ([depth] = 0), [end] est la fin du tampon. */
    private fun walk(
        bytes: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        primitive: (Int, Int, Int) -> Boolean,
    ): Boolean {
        if (depth > MAX_DEPTH) return false
        val nested = depth > 0
        var index = start
        while (index < end) {
            // SCUBA ignore les octets 00 et FF avant une étiquette.
            val first = bytes[index].toInt() and BYTE_MASK
            if (first == PADDING_ZERO || first == PADDING_FF) {
                index++
                continue
            }
            val header = header(bytes, index, end) ?: return !nested
            if (header.length == null) return false
            val valueEnd = header.valueStart.toLong() + header.length
            if (valueEnd > end) return false
            val valueEndInt = valueEnd.toInt()
            val accepted =
                if (first and CONSTRUCTED_BIT != 0) {
                    walk(bytes, header.valueStart, valueEndInt, depth + 1, primitive)
                } else {
                    primitive(header.tag, header.valueStart, valueEndInt)
                }
            if (!accepted) return false
            index = valueEndInt
        }
        return true
    }

    private class Header(
        val tag: Int,
        val valueStart: Int,
        /** Longueur annoncée ; null si elle est indéfinie ou ne tient pas sur quatre octets. */
        val length: Long?,
    )

    /** En-tête du TLV en [start], ou null s'il est tronqué avant [end]. */
    private fun header(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Header? {
        var index = start
        val first = bytes[index++].toInt() and BYTE_MASK
        var tag = first
        if (first and TAG_NUMBER_MASK == TAG_NUMBER_MASK) {
            var more = true
            while (more) {
                if (index >= end) return null
                val b = bytes[index++].toInt() and BYTE_MASK
                tag = (tag shl Byte.SIZE_BITS) or b
                more = b and MORE_BIT != 0
            }
        }
        if (index >= end) return null
        val lengthByte = bytes[index++].toInt() and BYTE_MASK
        if (lengthByte < MORE_BIT) return Header(tag, index, lengthByte.toLong())
        val count = lengthByte and LENGTH_COUNT_MASK
        if (count == 0 || count > MAX_LENGTH_BYTES) return Header(tag, index, null)
        if (index + count > end) return null
        var length = 0L
        repeat(count) { length = (length shl Byte.SIZE_BITS) or (bytes[index++].toLong() and 0xFF) }
        return Header(tag, index, length)
    }
}
