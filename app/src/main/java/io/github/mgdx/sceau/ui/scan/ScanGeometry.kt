package io.github.mgdx.sceau.ui.scan

import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Rectangle entier [left, left + width) × [top, top + height), en pixels. */
data class PixelRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/** Point en pixels, coordonnées non entières. */
data class PixelPoint(
    val x: Float,
    val y: Float,
)

/**
 * Cadre de visée exprimé en fractions (0 à 1) de la vue d'aperçu, plus la taille de cette vue
 * en pixels. Publié par l'interface, lu par l'analyseur sur son propre fil.
 */
data class ViewfinderGeometry(
    val viewWidth: Int,
    val viewHeight: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Calculs purs du cadrage, séparés de CameraX pour être testés sur JVM. */
object ScanGeometry {
    /** Rapport largeur / hauteur du cadre de visée : bande large et basse, comme une MRZ. */
    const val FRAME_ASPECT = 3.5f

    /** Part de la largeur de la vue occupée par le cadre. */
    private const val FRAME_WIDTH_FRACTION = 0.9f

    /** Part maximale de la hauteur de la vue occupée par le cadre (paysage). */
    private const val FRAME_MAX_HEIGHT_FRACTION = 0.4f

    /** Centre vertical du cadre, en fraction de la hauteur de la vue. */
    private const val FRAME_CENTER_Y = 0.42f

    /** Marge ajoutée autour du cadre lors du recadrage, en fraction de ses dimensions. */
    const val CROP_MARGIN = 0.08f

    /** Place le cadre de visée dans une vue de [viewWidth] × [viewHeight] pixels. */
    fun viewfinderFor(
        viewWidth: Int,
        viewHeight: Int,
    ): ViewfinderGeometry {
        require(viewWidth > 0 && viewHeight > 0)
        var frameWidth = viewWidth * FRAME_WIDTH_FRACTION
        var frameHeight = frameWidth / FRAME_ASPECT
        val maxHeight = viewHeight * FRAME_MAX_HEIGHT_FRACTION
        if (frameHeight > maxHeight) {
            frameHeight = maxHeight
            frameWidth = frameHeight * FRAME_ASPECT
        }
        val left = (viewWidth - frameWidth) / 2f
        val top = (viewHeight * FRAME_CENTER_Y - frameHeight / 2f).coerceAtLeast(0f)
        return ViewfinderGeometry(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            left = left / viewWidth,
            top = top / viewHeight,
            right = (left + frameWidth) / viewWidth,
            bottom = min(top + frameHeight, viewHeight.toFloat()) / viewHeight,
        )
    }

    /**
     * Zone du capteur (repère du tampon de l'image, avant rotation) qui correspond au cadre de
     * visée, marge comprise.
     *
     * L'image de [imageWidth] × [imageHeight] pixels doit être tournée de [rotationDegrees]
     * (sens horaire) pour être à l'endroit ; l'aperçu l'affiche alors centrée et recadrée pour
     * remplir la vue (`ContentScale.Crop`). Seule la partie [visible] de l'image (par défaut
     * l'image entière) est prise en compte.
     */
    fun sensorCrop(
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        viewfinder: ViewfinderGeometry,
        visible: PixelRect = PixelRect(0, 0, imageWidth, imageHeight),
        margin: Float = CROP_MARGIN,
    ): PixelRect {
        require(imageWidth > 0 && imageHeight > 0)
        require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270)
        val vis = clamp(visible, imageWidth, imageHeight)
        val swap = rotationDegrees == 90 || rotationDegrees == 270
        // Dimensions de la partie visible une fois à l'endroit.
        val uprightWidth = (if (swap) vis.height else vis.width).toFloat()
        val uprightHeight = (if (swap) vis.width else vis.height).toFloat()

        // Recadrage centré de l'aperçu : partie de l'image à l'endroit réellement affichée.
        val scale = max(viewfinder.viewWidth / uprightWidth, viewfinder.viewHeight / uprightHeight)
        val shownWidth = viewfinder.viewWidth / scale
        val shownHeight = viewfinder.viewHeight / scale
        val offsetX = (uprightWidth - shownWidth) / 2f
        val offsetY = (uprightHeight - shownHeight) / 2f

        val frameWidth = (viewfinder.right - viewfinder.left) * shownWidth
        val frameHeight = (viewfinder.bottom - viewfinder.top) * shownHeight
        val u0 = (offsetX + viewfinder.left * shownWidth - margin * frameWidth).coerceIn(0f, uprightWidth)
        val u1 = (offsetX + viewfinder.right * shownWidth + margin * frameWidth).coerceIn(0f, uprightWidth)
        val v0 = (offsetY + viewfinder.top * shownHeight - margin * frameHeight).coerceIn(0f, uprightHeight)
        val v1 = (offsetY + viewfinder.bottom * shownHeight + margin * frameHeight).coerceIn(0f, uprightHeight)

        // Retour au repère du tampon : inverse de la rotation horaire.
        val w = vis.width.toFloat()
        val h = vis.height.toFloat()
        val x0: Float
        val x1: Float
        val y0: Float
        val y1: Float
        when (rotationDegrees) {
            90 -> {
                x0 = v0
                x1 = v1
                y0 = h - u1
                y1 = h - u0
            }

            180 -> {
                x0 = w - u1
                x1 = w - u0
                y0 = h - v1
                y1 = h - v0
            }

            270 -> {
                x0 = w - v1
                x1 = w - v0
                y0 = u0
                y1 = u1
            }

            else -> {
                x0 = u0
                x1 = u1
                y0 = v0
                y1 = v1
            }
        }
        val left = floor(x0).toInt().coerceIn(0, vis.width)
        val top = floor(y0).toInt().coerceIn(0, vis.height)
        val right = ceil(x1).toInt().coerceIn(left, vis.width)
        val bottom = ceil(y1).toInt().coerceIn(top, vis.height)
        return PixelRect(vis.left + left, vis.top + top, right - left, bottom - top)
    }

    /**
     * Point du tampon de l'image (repère avant rotation, en pixels) affiché au point
     * ([viewX], [viewY]) de la vue d'aperçu de [viewWidth] × [viewHeight] pixels. Même modèle
     * d'affichage que [sensorCrop] : image tournée de [rotationDegrees], centrée et recadrée pour
     * remplir la vue. Sert à placer la mise au point sous le doigt ou au centre du cadre.
     */
    fun viewToBuffer(
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
        viewX: Float,
        viewY: Float,
        visible: PixelRect = PixelRect(0, 0, imageWidth, imageHeight),
    ): PixelPoint {
        require(imageWidth > 0 && imageHeight > 0 && viewWidth > 0 && viewHeight > 0)
        require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270)
        val vis = clamp(visible, imageWidth, imageHeight)
        val swap = rotationDegrees == 90 || rotationDegrees == 270
        val uprightWidth = (if (swap) vis.height else vis.width).toFloat()
        val uprightHeight = (if (swap) vis.width else vis.height).toFloat()
        val scale = max(viewWidth / uprightWidth, viewHeight / uprightHeight)
        val u = ((uprightWidth - viewWidth / scale) / 2f + viewX.coerceIn(0f, viewWidth.toFloat()) / scale).coerceIn(0f, uprightWidth)
        val v = ((uprightHeight - viewHeight / scale) / 2f + viewY.coerceIn(0f, viewHeight.toFloat()) / scale).coerceIn(0f, uprightHeight)
        val w = vis.width.toFloat()
        val h = vis.height.toFloat()
        val (x, y) =
            when (rotationDegrees) {
                90 -> v to h - u
                180 -> w - u to h - v
                270 -> w - v to u
                else -> u to v
            }
        return PixelPoint(vis.left + x, vis.top + y)
    }

    /** Centre du cadre de visée [viewfinder], dans le repère du tampon de l'image (voir [viewToBuffer]). */
    fun meteringPoint(
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        viewfinder: ViewfinderGeometry,
        visible: PixelRect = PixelRect(0, 0, imageWidth, imageHeight),
    ): PixelPoint =
        viewToBuffer(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            rotationDegrees = rotationDegrees,
            viewWidth = viewfinder.viewWidth,
            viewHeight = viewfinder.viewHeight,
            viewX = (viewfinder.left + viewfinder.right) / 2f * viewfinder.viewWidth,
            viewY = (viewfinder.top + viewfinder.bottom) / 2f * viewfinder.viewHeight,
            visible = visible,
        )

    /**
     * Copie la zone [crop] du plan de luminance [plane] ([rowStride] octets par ligne,
     * [pixelStride] octets entre deux pixels) dans [dest], ligne après ligne, sans espace entre
     * les lignes (`crop.width` octets par ligne). Ne modifie ni la position ni la limite de
     * [plane].
     */
    fun copyLuma(
        plane: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        crop: PixelRect,
        dest: ByteArray,
    ) {
        require(rowStride > 0 && pixelStride > 0)
        require(crop.left >= 0 && crop.top >= 0 && crop.width >= 0 && crop.height >= 0)
        require(dest.size >= crop.width * crop.height)
        if (crop.width == 0 || crop.height == 0) return
        val lastIndex = (crop.top + crop.height - 1) * rowStride + (crop.left + crop.width - 1) * pixelStride
        require(lastIndex < plane.limit())
        if (pixelStride == 1) {
            val source = plane.duplicate()
            for (row in 0 until crop.height) {
                source.position((crop.top + row) * rowStride + crop.left)
                source.get(dest, row * crop.width, crop.width)
            }
        } else {
            var out = 0
            for (row in 0 until crop.height) {
                var index = (crop.top + row) * rowStride + crop.left * pixelStride
                repeat(crop.width) {
                    dest[out++] = plane.get(index)
                    index += pixelStride
                }
            }
        }
    }

    private fun clamp(
        rect: PixelRect,
        imageWidth: Int,
        imageHeight: Int,
    ): PixelRect {
        val left = rect.left.coerceIn(0, imageWidth - 1)
        val top = rect.top.coerceIn(0, imageHeight - 1)
        val width = rect.width.coerceIn(1, imageWidth - left)
        val height = rect.height.coerceIn(1, imageHeight - top)
        return PixelRect(left, top, width, height)
    }
}
