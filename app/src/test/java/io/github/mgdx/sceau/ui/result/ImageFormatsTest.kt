package io.github.mgdx.sceau.ui.result

import io.github.mgdx.sceau.core.model.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageFormatsTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)
    private val jp2 =
        byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A, 0x00, 0x00)
    private val j2k = byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51, 0x00, 0x2F)

    @Test
    fun `JPEG et JPEG 2000 reconnus a leurs octets`() {
        assertEquals(ImageFormat.JPEG, detectImageFormat(jpeg))
        assertEquals(ImageFormat.JPEG2000, detectImageFormat(jp2))
        assertEquals(ImageFormat.JPEG2000, detectImageFormat(j2k))
    }

    @Test
    fun `autres formats refuses (audit V18)`() {
        for (other in listOf(HostileImages.PNG, HostileImages.WEBP, HostileImages.GIF, HostileImages.BMP)) {
            assertNull(detectImageFormat(other))
        }
    }

    @Test
    fun `octets insuffisants ou inconnus`() {
        assertNull(detectImageFormat(byteArrayOf()))
        assertNull(detectImageFormat(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertNull(detectImageFormat(byteArrayOf(1, 2, 3, 4, 5)))
    }
}

/** En-têtes d'images d'autres formats que JPEG et JPEG 2000, que `BitmapFactory` décoderait. */
internal object HostileImages {
    val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52)
    val WEBP = "RIFF$\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
    val GIF = "GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    val BMP = "BM\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
}
