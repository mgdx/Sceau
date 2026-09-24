package io.github.mgdx.sceau.ui.home

import androidx.compose.ui.text.AnnotatedString
import io.github.mgdx.sceau.session.DateOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DateDigitsTransformationTest {
    private fun format(
        digits: String,
        order: DateOrder = DateOrder.DAY_MONTH_YEAR,
    ) = DateDigitsTransformation(order).filter(AnnotatedString(digits))

    @Test
    fun `separators appear as digits are typed`() {
        assertEquals("", format("").text.text)
        assertEquals("17", format("17").text.text)
        assertEquals("17/0", format("170").text.text)
        assertEquals("17/05", format("1705").text.text)
        assertEquals("17/05/1", format("17051").text.text)
        assertEquals("17/05/1990", format("17051990").text.text)
        assertEquals("1990/05/17", format("19900517", DateOrder.YEAR_MONTH_DAY).text.text)
    }

    @Test
    fun `offset mapping is consistent in both directions`() {
        for (order in DateOrder.entries) {
            for (length in 0..8) {
                val digits = "12345678".take(length)
                val transformed = format(digits, order)
                val mapping = transformed.offsetMapping
                for (offset in 0..length) {
                    val t = mapping.originalToTransformed(offset)
                    assertTrue(t in 0..transformed.text.length)
                    assertEquals(offset, mapping.transformedToOriginal(t))
                }
                for (t in 0..transformed.text.length) {
                    assertTrue(mapping.transformedToOriginal(t) in 0..length)
                }
            }
        }
    }

    @Test
    fun `cursor at the end stays at the end`() {
        val transformed = format("17051990")
        assertEquals(10, transformed.offsetMapping.originalToTransformed(8))
        assertEquals(8, transformed.offsetMapping.transformedToOriginal(10))
        // Juste après le premier séparateur : on reste après le 2e chiffre.
        assertEquals(2, transformed.offsetMapping.transformedToOriginal(3))
    }
}
