package io.github.mgdx.sceau.ui.result

/** Longueur maximale, en points de code, d'un texte venu de la puce ou d'un certificat. */
const val DISPLAY_MAX_LENGTH = 512

private const val ELLIPSIS = '…'

/**
 * Assainit pour l'affichage un texte venu de la puce ou d'un certificat (audit V12) :
 * - retire les caractères de contrôle (Cc) et de formatage (Cf : marques et isolats
 *   bidirectionnels U+202A–U+202E, U+2066–U+2069, caractères de largeur nulle…) ;
 * - remplace sauts de ligne, tabulations et séparateurs de ligne ou de paragraphe (Zl, Zp)
 *   par une espace, sans doubler les espaces ;
 * - tronque à [maxLength] points de code, le dernier devenant « … ».
 *
 * Le texte n'est parcouru que jusqu'à la limite : un texte démesuré ne coûte rien de plus.
 */
fun displaySafe(
    text: String,
    maxLength: Int = DISPLAY_MAX_LENGTH,
): String {
    require(maxLength >= 1) { "maxLength" }
    val out = StringBuilder(minOf(text.length, maxLength * 2))
    var count = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        index += Character.charCount(codePoint)
        val kept: Int =
            when {
                isBreak(codePoint) -> ' '.code
                isInvisible(codePoint) -> continue
                else -> codePoint
            }
        if (kept == ' '.code && (out.isEmpty() || out.last() == ' ')) continue
        if (count == maxLength) {
            // Un point de code de plus que la limite : on remplace le dernier par l'ellipse.
            out.setLength(out.length - Character.charCount(out.codePointBefore(out.length)))
            out.append(ELLIPSIS)
            return out.toString().trimEnd()
        }
        out.appendCodePoint(kept)
        count++
    }
    return out.toString().trimEnd()
}

private fun isBreak(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
        Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        Character.CONTROL -> codePoint == '\n'.code || codePoint == '\r'.code || codePoint == '\t'.code || codePoint == 0x85
        else -> false
    }

private fun isInvisible(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE -> true
        else -> false
    }
