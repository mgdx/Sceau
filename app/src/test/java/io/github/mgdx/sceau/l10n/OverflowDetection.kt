package io.github.mgdx.sceau.l10n

import android.content.res.Resources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import io.github.mgdx.sceau.R
import kotlin.math.roundToInt

/**
 * Overflowing text: [id] (string key, or raw text if no string matches) is the key in the list
 * of known anomalies (`language|id`).
 */
internal data class Overflow(
    val language: String,
    val screen: String,
    val id: String,
    val text: String,
    val detail: String,
) {
    val listKey: String get() = "$language|$id"
}

/**
 * Anomalies of a text node, or an empty list: text that exceeds its box (in width or height),
 * ellipsized last line, or a box that sticks out of a container or of the screen (window or
 * popup).
 */
internal fun inspectTextNode(
    node: SemanticsNode,
    density: Float,
): List<Pair<String, String>> {
    val layouts = mutableListOf<TextLayoutResult>()
    node.config
        .getOrNull(SemanticsActions.GetTextLayoutResult)
        ?.action
        ?.invoke(layouts)
    val layout = layouts.firstOrNull() ?: return emptyList()
    val text = layout.layoutInput.text.text
    if (text.isBlank()) return emptyList()
    val problems = mutableListOf<String>()

    fun dp(px: Float): Int = (px / density).roundToInt()
    // Width: widest line against the text box. `hasVisualOverflow` is of no use here: the result
    // provided by the semantics is laid out again over the whole available width
    // (`multiParagraph.width` = maximum constraint width), so `didOverflowWidth` is true for any
    // text narrower than its container. Height, on the other hand, is reliable.
    val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
    // 1 dp tolerance: `Layout.getLineLeft` and `getLineRight` round the position of a centered
    // line to whole pixels (floor and ceiling), which widens it by up to 2 px.
    if (widest > layout.size.width + density || layout.didOverflowHeight) {
        problems +=
            "exceeds its box: text ${dp(widest)}×${dp(layout.multiParagraph.height)} dp, " +
            "box ${dp(layout.size.width.toFloat())}×${dp(layout.size.height.toFloat())} dp"
    }
    if (layout.lineCount > 0 && layout.isLineEllipsized(layout.lineCount - 1)) {
        problems += "ellipsized (${layout.lineCount} line(s), box ${dp(layout.size.width.toFloat())} dp)"
    }
    brokenWord(layout)?.let { problems += "word broken at end of line, for lack of width ($it), box ${dp(layout.size.width.toFloat())} dp" }
    containerOverflow(node, density)?.let { problems += it }
    return problems.map { text to it }
}

/**
 * Word broken across two lines ("…ber|prüfung…"): without hyphenation, Android only breaks a
 * word if it does not fit on a line by itself, that is when the text is too wide for its
 * container (tab, button), which grows in height without clipping anything. Returns the two
 * pieces, or null. Scripts without spaces between words (Chinese, Japanese, Korean, Thai…),
 * which wrap between any two characters, are not affected.
 */
private fun brokenWord(layout: TextLayoutResult): String? {
    val text = layout.layoutInput.text.text
    for (line in 0 until layout.lineCount - 1) {
        val end = layout.getLineEnd(line)
        if (end <= 0 || end >= text.length) continue
        val before = text.codePointBefore(end)
        val after = text.codePointAt(end)
        if (Character.isLetterOrDigit(before) && Character.isLetterOrDigit(after) && breaksOnSpaces(before) && breaksOnSpaces(after)) {
            val start = layout.getLineStart(line)
            return "\"${text.substring(
                maxOf(start, end - WORD_CONTEXT),
                end,
            )}|${text.substring(end, minOf(text.length, end + WORD_CONTEXT))}\""
        }
    }
    return null
}

private const val WORD_CONTEXT = 8

private val NO_SPACE_SCRIPTS =
    setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR,
    )

private fun breaksOnSpaces(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) !in NO_SPACE_SCRIPTS

/**
 * Text box sticking out of an ancestor's box: text clipped by a fixed-size container (tab,
 * app bar) or pushed off the screen (the root is the last ancestor). Unclipped positions
 * (`positionInRoot` and `size`). Exceptions intended by Material 3:
 * - a vertically (or horizontally) scrolling container stops the check on that axis,
 *   itself included: its content exceeds it by design;
 * - a text field is not compared with its label, which straddles the outline.
 */
private fun containerOverflow(
    node: SemanticsNode,
    density: Float,
): String? {
    fun dp(px: Float): Int = (px / density).roundToInt()
    val left = node.positionInRoot.x
    val top = node.positionInRoot.y
    val right = left + node.size.width
    val bottom = top + node.size.height
    var checkHorizontal = true
    var checkVertical = true
    var ancestor = node.parent
    while (ancestor != null) {
        val config = ancestor.config
        if (config.contains(SemanticsProperties.HorizontalScrollAxisRange)) checkHorizontal = false
        if (config.contains(SemanticsProperties.VerticalScrollAxisRange)) checkVertical = false
        if (!checkHorizontal && !checkVertical) return null
        val aLeft = ancestor.positionInRoot.x
        val aTop = ancestor.positionInRoot.y
        val aRight = aLeft + ancestor.size.width
        val aBottom = aTop + ancestor.size.height
        val where = if (ancestor.parent == null) "the screen" else "its container"
        val isTextField = config.contains(SemanticsProperties.EditableText)
        if (!isTextField && checkHorizontal && (left < aLeft - density || right > aRight + density)) {
            return "sticks out of $where: text ${dp(left)} to ${dp(right)} dp, container ${dp(aLeft)} to ${dp(aRight)} dp"
        }
        if (!isTextField && checkVertical && (top < aTop - density || bottom > aBottom + density)) {
            return "sticks out of $where: text ${dp(top)} to ${dp(bottom)} dp vertically, container ${dp(aTop)} to ${dp(aBottom)} dp"
        }
        ancestor = ancestor.parent
    }
    return null
}

/**
 * Finds the key of a displayed string in [resources] (already in the tested language): exact
 * match, then a pattern where each format argument (`%1$s`, `%d`…) accepts any text. Plurals
 * included.
 */
internal class StringKeyResolver(
    resources: Resources,
) {
    private val exact = HashMap<String, MutableList<String>>()
    private val patterns = mutableListOf<Triple<String, Regex, Int>>()

    init {
        R.string::class.java.fields.forEach { field ->
            val text = resources.getString(field.getInt(null))
            add(field.name, text)
        }
        R.plurals::class.java.fields.forEach { field ->
            val id = field.getInt(null)
            QUANTITIES.map { resources.getQuantityText(id, it).toString() }.distinct().forEach { add(field.name, it) }
        }
    }

    private fun add(
        key: String,
        text: String,
    ) {
        if (!FORMAT.containsMatchIn(text)) {
            exact.getOrPut(text) { mutableListOf() } += key
            return
        }
        val parts = FORMAT.split(text)
        val regex = parts.joinToString("(.*)") { Regex.escape(it.replace("%%", "%")) }
        patterns += Triple(key, Regex(regex, RegexOption.DOT_MATCHES_ALL), parts.sumOf { it.length })
    }

    /**
     * Key of [text], or null if no app string matches (displayed data). If several strings have
     * this text, the one starting with [preferredPrefix] (displayed screen).
     */
    fun keyOf(
        text: String,
        preferredPrefix: String,
    ): String? {
        val keys =
            exact[text] ?: patterns
                .filter { it.second.matches(text) }
                .sortedByDescending { it.third }
                .map { it.first }
        return keys.firstOrNull { it.startsWith(preferredPrefix) } ?: keys.firstOrNull()
    }

    private companion object {
        val FORMAT = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxXc]")
        val QUANTITIES = listOf(0, 1, 2, 3, 4, 5, 6, 7, 11, 12, 21, 22, 25, 100, 101, 102, 111, 1000)
    }
}
