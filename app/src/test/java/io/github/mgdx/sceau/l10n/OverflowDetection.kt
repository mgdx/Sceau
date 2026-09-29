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
 * Texte qui déborde : [id] (clé de chaîne, ou texte brut si aucune chaîne ne correspond) sert
 * de clé à la liste des anomalies connues (`langue|id`).
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
 * Anomalies d'un nœud de texte, ou liste vide : texte qui dépasse sa boîte (en largeur ou en
 * hauteur), dernière ligne ellipsée, ou boîte qui sort d'un conteneur ou de l'écran (fenêtre
 * ou popup).
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
    // Largeur : ligne la plus large contre la boîte du texte. `hasVisualOverflow` ne sert pas ici :
    // le résultat fourni par la sémantique est remis en page sur toute la largeur disponible
    // (`multiParagraph.width` = largeur maximale des contraintes), si bien que `didOverflowWidth`
    // est vrai pour tout texte plus étroit que son conteneur. La hauteur, elle, est fiable.
    val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
    // Tolérance d'1 dp : `Layout.getLineLeft` et `getLineRight` arrondissent au pixel entier
    // (plancher et plafond) la position d'une ligne centrée, ce qui l'élargit jusqu'à 2 px.
    if (widest > layout.size.width + density || layout.didOverflowHeight) {
        problems +=
            "dépasse sa boîte : texte ${dp(widest)}×${dp(layout.multiParagraph.height)} dp, " +
            "boîte ${dp(layout.size.width.toFloat())}×${dp(layout.size.height.toFloat())} dp"
    }
    if (layout.lineCount > 0 && layout.isLineEllipsized(layout.lineCount - 1)) {
        problems += "ellipsé (${layout.lineCount} ligne(s), boîte ${dp(layout.size.width.toFloat())} dp)"
    }
    brokenWord(layout)?.let { problems += "mot coupé en fin de ligne, faute de largeur ($it), boîte ${dp(layout.size.width.toFloat())} dp" }
    containerOverflow(node, density)?.let { problems += it }
    return problems.map { text to it }
}

/**
 * Mot coupé entre deux lignes (« …ber|prüfung… ») : sans césure, Android ne coupe un mot que
 * s'il ne tient pas seul sur une ligne, c'est-à-dire quand le texte est trop large pour son
 * conteneur (onglet, bouton) qui, lui, s'agrandit en hauteur sans rien rogner. Renvoie les
 * deux morceaux, ou null. Les écritures sans espaces entre les mots (chinois, japonais,
 * coréen, thaï…), qui passent à la ligne entre deux caractères, ne sont pas concernées.
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
            return "« ${text.substring(
                maxOf(start, end - WORD_CONTEXT),
                end,
            )}|${text.substring(end, minOf(text.length, end + WORD_CONTEXT))} »"
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
 * Boîte du texte qui sort de celle d'un ancêtre : texte coupé par un conteneur de taille fixe
 * (onglet, barre d'application) ou sorti de l'écran (la racine est le dernier ancêtre).
 * Positions non rognées (`positionInRoot` et `size`). Exceptions voulues par Material 3 :
 * - un conteneur qui défile verticalement (ou horizontalement) arrête le contrôle sur cet
 *   axe, lui compris : son contenu le dépasse par construction ;
 * - un champ de saisie n'est pas comparé à son étiquette, posée à cheval sur le contour.
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
        val where = if (ancestor.parent == null) "l'écran" else "son conteneur"
        val isTextField = config.contains(SemanticsProperties.EditableText)
        if (!isTextField && checkHorizontal && (left < aLeft - density || right > aRight + density)) {
            return "sort de $where : texte de ${dp(left)} à ${dp(right)} dp, conteneur de ${dp(aLeft)} à ${dp(aRight)} dp"
        }
        if (!isTextField && checkVertical && (top < aTop - density || bottom > aBottom + density)) {
            return "sort de $where : texte de ${dp(top)} à ${dp(bottom)} dp en hauteur, conteneur de ${dp(aTop)} à ${dp(aBottom)} dp"
        }
        ancestor = ancestor.parent
    }
    return null
}

/**
 * Retrouve la clé d'une chaîne affichée dans [resources] (déjà dans la langue testée) :
 * égalité exacte, puis motif où chaque paramètre de format (`%1$s`, `%d`…) accepte n'importe
 * quel texte. Pluriels compris.
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
     * Clé de [text], ou null si aucune chaîne de l'application ne correspond (donnée affichée).
     * Si plusieurs chaînes ont ce texte, celle qui commence par [preferredPrefix] (écran affiché).
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
