package io.github.mgdx.sceau.ui.result

/** Maximum length, in code points, of a text coming from the chip or from a certificate. */
const val DISPLAY_MAX_LENGTH = 512

private const val ELLIPSIS = '…'

/**
 * Sanitizes for display a text coming from the chip or from a certificate (audit V12):
 * - removes control (Cc) and format (Cf: bidirectional marks and isolates
 *   U+202A–U+202E, U+2066–U+2069, zero-width characters…) characters;
 * - replaces line breaks, tabs and line or paragraph separators (Zl, Zp)
 *   with a space, without doubling spaces;
 * - truncates to [maxLength] code points, the last one becoming "…".
 *
 * The text is only scanned up to the limit: an oversized text costs nothing more.
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
            // One code point beyond the limit: the last one is replaced with the ellipsis.
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
