package io.github.mgdx.sceau.ui.result

import io.github.mgdx.sceau.core.model.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageFormatsTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)
    private val jp2 =
        byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A, 0x00, 0x00)
    private val j2k = byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51, 0x00, 0x2F)

    @Test
    fun `le format declare prime`() {
        assertEquals(ImageFormat.JPEG2000, resolveImageFormat(ImageFormat.JPEG2000, jpeg))
        assertEquals(ImageFormat.JPEG, resolveImageFormat(ImageFormat.JPEG, jp2))
    }

    @Test
    fun `format inconnu deduit des premiers octets`() {
        assertEquals(ImageFormat.JPEG, resolveImageFormat(ImageFormat.UNKNOWN, jpeg))
        assertEquals(ImageFormat.JPEG2000, resolveImageFormat(ImageFormat.UNKNOWN, jp2))
        assertEquals(ImageFormat.JPEG2000, resolveImageFormat(ImageFormat.UNKNOWN, j2k))
    }

    @Test
    fun `octets insuffisants ou inconnus`() {
        assertEquals(ImageFormat.UNKNOWN, resolveImageFormat(ImageFormat.UNKNOWN, byteArrayOf()))
        assertEquals(ImageFormat.UNKNOWN, resolveImageFormat(ImageFormat.UNKNOWN, byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertEquals(ImageFormat.UNKNOWN, resolveImageFormat(ImageFormat.UNKNOWN, byteArrayOf(1, 2, 3, 4, 5)))
    }
}
