package io.github.mgdx.sceau.ui.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.github.mgdx.sceau.mrz.LumaFrame
import io.github.mgdx.sceau.mrz.MrzScanResult
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Analyseur CameraX : recadre chaque image sur le cadre de visée, en copie le plan de
 * luminance dans un tableau réutilisé, le passe à [analyzeFrame] (`MrzScanner.analyze`), puis
 * remet le tableau à zéro (D32). Toutes les méthodes s'exécutent sur le fil unique de
 * l'analyse. [viewfinder] est écrit par l'interface quand la vue d'aperçu change de taille.
 * Aucune image n'est conservée ni écrite.
 */
internal class MrzFrameAnalyzer(
    private val analyzeFrame: (LumaFrame) -> MrzScanResult,
    private val resetScanner: () -> Unit,
    private val viewfinder: AtomicReference<ViewfinderGeometry?>,
    private val onResult: (MrzScanResult) -> Unit,
) : ImageAnalysis.Analyzer {
    private var luma = ByteArray(0)
    private var found = false

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val visible = image.cropRect
            processFrame(
                width = image.width,
                height = image.height,
                rotationDegrees = image.imageInfo.rotationDegrees,
                visible = PixelRect(visible.left, visible.top, visible.width(), visible.height()),
                plane = plane.buffer,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
            )
        } finally {
            image.close()
        }
    }

    /** Cœur de [analyze], sans dépendance à `ImageProxy` pour être testé sur JVM. */
    fun processFrame(
        width: Int,
        height: Int,
        rotationDegrees: Int,
        visible: PixelRect,
        plane: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
    ) {
        val geometry = viewfinder.get()
        if (found || geometry == null) return
        val crop = ScanGeometry.sensorCrop(width, height, rotationDegrees, geometry, visible)
        if (crop.width == 0 || crop.height == 0) return
        val size = crop.width * crop.height
        if (luma.size != size) {
            luma.fill(0)
            luma = ByteArray(size)
        }
        val result =
            try {
                ScanGeometry.copyLuma(plane, rowStride, pixelStride, crop, luma)
                analyzeFrame(LumaFrame(luma, crop.width, crop.height, crop.width, rotationDegrees))
            } finally {
                luma.fill(0)
            }
        if (result is MrzScanResult.Found) found = true
        onResult(result)
    }

    /** Oublie l'état (mise en arrière-plan) : tableau remis à zéro, stabilisation oubliée. */
    fun reset() {
        luma.fill(0)
        resetScanner()
    }

    /** Sortie de l'écran : tableau remis à zéro et libéré, état oublié. */
    fun release() {
        reset()
        luma = ByteArray(0)
    }
}
