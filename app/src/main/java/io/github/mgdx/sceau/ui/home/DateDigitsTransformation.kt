package io.github.mgdx.sceau.ui.home

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import io.github.mgdx.sceau.session.DateOrder

/**
 * Displays the digits of a date with separators as they are typed: "1705" becomes
 * "17/05", "17051990" becomes "17/05/1990" (positions according to [order]). The separator
 * appears only once the next digit is typed, so that deleting stays natural.
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
                    // The i-th separator is at index positions[i] + i of the displayed text.
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
