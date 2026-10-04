package io.github.mgdx.sceau.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/** Point de mise au point et d'exposition, dans le repère du tampon d'analyse (lot I2 de D32). */
class MeteringPointTest {
    private val tolerance = 1.5f

    @Test
    fun `metering point is the centre of the analysed crop for every rotation`() {
        // Téléphone en portrait (90, 270), en paysage (0, 180), image 1080p 16:9.
        listOf(0, 90, 180, 270).forEach { rotation ->
            val swap = rotation == 90 || rotation == 270
            val view = if (swap) ScanGeometry.viewfinderFor(1080, 2000) else ScanGeometry.viewfinderFor(2000, 1080)
            val crop = ScanGeometry.sensorCrop(1920, 1080, rotation, view, margin = 0f)
            val point = ScanGeometry.meteringPoint(1920, 1080, rotation, view)
            assertEquals("x, rotation $rotation", crop.left + crop.width / 2f, point.x, tolerance)
            assertEquals("y, rotation $rotation", crop.top + crop.height / 2f, point.y, tolerance)
        }
    }

    @Test
    fun `portrait centre of the frame lands left of the sensor centre line`() {
        // Rotation 90 : le haut de l'image à l'endroit est le bord gauche du tampon. Le cadre est
        // centré horizontalement et placé au-dessus du milieu de la vue (42 % de la hauteur).
        val view = ScanGeometry.viewfinderFor(1080, 1920)
        val point = ScanGeometry.meteringPoint(1920, 1080, 90, view)
        assertEquals(0.42f * 1920, point.x, tolerance)
        assertEquals(540f, point.y, tolerance)
    }

    @Test
    fun `view corners map to buffer corners when the view has the image format`() {
        // Vue 1080 × 1920 et image 1920 × 1080 tournée de 90 : aucun recadrage de l'aperçu.
        val topLeft = ScanGeometry.viewToBuffer(1920, 1080, 90, 1080, 1920, 0f, 0f)
        assertEquals(PixelPoint(0f, 1080f), topLeft)
        val bottomRight = ScanGeometry.viewToBuffer(1920, 1080, 90, 1080, 1920, 1080f, 1920f)
        assertEquals(PixelPoint(1920f, 0f), bottomRight)
        val landscape = ScanGeometry.viewToBuffer(1920, 1080, 0, 1920, 1080, 480f, 270f)
        assertEquals(PixelPoint(480f, 270f), landscape)
    }

    @Test
    fun `taller view crops the preview sides and keeps the centre`() {
        // Vue plus étroite que 9:16 : les bords gauche et droit de l'image à l'endroit sont coupés.
        val centre = ScanGeometry.viewToBuffer(1920, 1080, 90, 1000, 2400, 500f, 1200f)
        assertEquals(PixelPoint(960f, 540f), centre)
        val left = ScanGeometry.viewToBuffer(1920, 1080, 90, 1000, 2400, 0f, 1200f)
        // Échelle 2400 / 1920 = 1,25 : 1000 px de vue montrent 800 px des 1080, 140 coupés de chaque côté.
        assertEquals(960f, left.x, tolerance)
        assertEquals(1080f - 140f, left.y, tolerance)
    }

    @Test
    fun `touches outside the view are clamped to the image`() {
        val point = ScanGeometry.viewToBuffer(1920, 1080, 0, 1920, 1080, -50f, 5000f)
        assertEquals(PixelPoint(0f, 1080f), point)
    }
}
