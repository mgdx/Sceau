package io.github.mgdx.sceau.ui.home

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import io.github.mgdx.sceau.session.DateOrder

/**
 * Affiche les chiffres d'une date avec des séparateurs au fil de la frappe : « 1705 » devient
 * « 17/05 », « 17051990 » devient « 17/05/1990 » (positions selon [order]). Le séparateur
 * n'apparaît qu'une fois le chiffre suivant tapé, pour que l'effacement reste naturel.
 */
internal class DateDigitsTransformation(
    private val order: DateOrder,
    private val separator: Char = SEPARATOR,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val positions = order.separatorPositions.filter { it < digits.length }
        val formatted =
            buildString {
                digits.forEachIndexed { index, c ->
                    if (index in positions) append(separator)
                    append(c)
                }
            }
        val mapping =
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = offset + positions.count { it < offset }

                override fun transformedToOriginal(offset: Int): Int {
                    // Le i-ème séparateur est à l'indice positions[i] + i du texte affiché.
                    val separatorsBefore = positions.withIndex().count { (i, position) -> position + i < offset }
                    return (offset - separatorsBefore).coerceIn(0, digits.length)
                }
            }
        return TransformedText(AnnotatedString(formatted), mapping)
    }

    override fun equals(other: Any?): Boolean = other is DateDigitsTransformation && other.order == order && other.separator == separator

    override fun hashCode(): Int = 31 * order.hashCode() + separator.hashCode()

    companion object {
        const val SEPARATOR = '/'
    }
}
