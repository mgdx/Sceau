package io.github.mgdx.sceau.ui.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ScanGeometryTest {
    private fun frame(
        viewWidth: Int,
        viewHeight: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = ViewfinderGeometry(viewWidth, viewHeight, left, top, right, bottom)

    @Test
    fun `portrait viewfinder is a wide band with the MRZ aspect ratio`() {
        val g = ScanGeometry.viewfinderFor(1080, 1920)
        assertEquals(0.05f, g.left, 1e-4f)
        assertEquals(0.95f, g.right, 1e-4f)
        val ratio = (g.right - g.left) * 1080 / ((g.bottom - g.top) * 1920)
        assertEquals(ScanGeometry.FRAME_ASPECT, ratio, 1e-3f)
        assertTrue(g.top > 0f && g.bottom < 1f)
    }

    @Test
    fun `landscape viewfinder height is capped`() {
        val g = ScanGeometry.viewfinderFor(1920, 1080)
        assertEquals(0.4f, g.bottom - g.top, 1e-4f)
        val ratio = (g.right - g.left) * 1920 / ((g.bottom - g.top) * 1080)
        assertEquals(ScanGeometry.FRAME_ASPECT, ratio, 1e-3f)
    }

    @Test
    fun `no rotation maps the frame directly`() {
        val crop = ScanGeometry.sensorCrop(1280, 720, 0, frame(1280, 720, 0.25f, 0.25f, 0.75f, 0.5f), margin = 0f)
        assertEquals(PixelRect(320, 180, 640, 180), crop)
    }

    @Test
    fun `rotation 90 maps the upright frame back to the sensor`() {
        // Capteur paysage 1280 × 720, affiché en portrait 720 × 1280.
        val crop = ScanGeometry.sensorCrop(1280, 720, 90, frame(720, 1280, 0.1f, 0.2f, 0.9f, 0.3f), margin = 0f)
        assertEquals(PixelRect(256, 72, 128, 576), crop)
        // Contrôle indépendant : le centre de la zone, tourné de 90°, tombe dans le cadre.
        val x = crop.left + crop.width / 2
        val y = crop.top + crop.height / 2
        val u = 720 - y
        val v = x
        assertTrue(u in 72..648 && v in 256..384)
    }

    @Test
    fun `rotation 270 maps to the opposite side of the sensor`() {
        val crop = ScanGeometry.sensorCrop(1280, 720, 270, frame(720, 1280, 0.1f, 0.2f, 0.9f, 0.3f), margin = 0f)
        assertEquals(PixelRect(896, 72, 128, 576), crop)
    }

    @Test
    fun `rotation 180 flips both axes`() {
        val crop = ScanGeometry.sensorCrop(1280, 720, 180, frame(1280, 720, 0.25f, 0.25f, 0.75f, 0.5f), margin = 0f)
        assertEquals(PixelRect(320, 360, 640, 180), crop)
    }

    @Test
    fun `preview crop is taken into account`() {
        // Image à l'endroit 720 × 1280 affichée dans une vue carrée : seule la bande centrale
        // de 720 lignes est visible.
        val crop = ScanGeometry.sensorCrop(1280, 720, 90, frame(1080, 1080, 0f, 0f, 1f, 1f), margin = 0f)
        assertEquals(PixelRect(280, 0, 720, 720), crop)
    }

    @Test
    fun `margin is added and clamped to the image`() {
        val inner = ScanGeometry.sensorCrop(1280, 720, 0, frame(1280, 720, 0.25f, 0.25f, 0.75f, 0.5f), margin = 0.1f)
        assertEquals(PixelRect(256, 162, 768, 216), inner)
        val whole = ScanGeometry.sensorCrop(1280, 720, 0, frame(1280, 720, 0f, 0f, 1f, 1f), margin = 0.1f)
        assertEquals(PixelRect(0, 0, 1280, 720), whole)
    }

    @Test
    fun `visible region offsets the crop`() {
        val crop =
            ScanGeometry.sensorCrop(
                640,
                480,
                0,
                frame(200, 100, 0f, 0f, 1f, 1f),
                visible = PixelRect(100, 100, 200, 100),
                margin = 0f,
            )
        assertEquals(PixelRect(100, 100, 200, 100), crop)
    }

    private fun plane(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): ByteBuffer {
        // Dernière ligne tronquée, comme les tampons de la caméra.
        val size = (height - 1) * rowStride + (width - 1) * pixelStride + 1
        val bytes = ByteArray(size) { 0x7F }
        for (y in 0 until height) {
            for (x in 0 until width) bytes[y * rowStride + x * pixelStride] = (y * 16 + x).toByte()
        }
        return ByteBuffer.wrap(bytes)
    }

    @Test
    fun `copy honours rowStride`() {
        val buffer = plane(width = 6, height = 4, rowStride = 8, pixelStride = 1)
        val dest = ByteArray(6)
        ScanGeometry.copyLuma(buffer, 8, 1, PixelRect(2, 1, 3, 2), dest)
        assertArrayEquals(byteArrayOf(18, 19, 20, 34, 35, 36), dest)
        assertEquals(0, buffer.position())
    }

    @Test
    fun `copy honours pixelStride`() {
        val buffer = plane(width = 6, height = 4, rowStride = 16, pixelStride = 2)
        val dest = ByteArray(6)
        ScanGeometry.copyLuma(buffer, 16, 2, PixelRect(3, 2, 3, 2), dest)
        assertArrayEquals(byteArrayOf(35, 36, 37, 51, 52, 53), dest)
    }

    @Test
    fun `copy reaches the truncated last row`() {
        val buffer = plane(width = 6, height = 4, rowStride = 8, pixelStride = 1)
        val dest = ByteArray(24)
        ScanGeometry.copyLuma(buffer, 8, 1, PixelRect(0, 0, 6, 4), dest)
        assertEquals(53.toByte(), dest[23])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `copy outside the plane is refused`() {
        val buffer = plane(width = 6, height = 4, rowStride = 8, pixelStride = 1)
        ScanGeometry.copyLuma(buffer, 8, 1, PixelRect(2, 2, 6, 2), ByteArray(12))
    }
}
