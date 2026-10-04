package io.github.mgdx.sceau.mrz

import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Image « appareil » : ce que `:app` envoie à `:mrz` avec la caméra d'un téléphone tenu en
 * portrait (lot I1 de D32). L'image est le recadrage du cadre de visée (rapport largeur/hauteur
 * de 2,8 à 3,8), où la MRZ n'occupe qu'une partie de la largeur ; le pas des caractères, en
 * pixels du capteur, est petit (10 à 22 px).
 *
 * Chaîne de dégradation, dans l'ordre physique :
 * 1. rendu suréchantillonné ×[DeviceSceneRenderer.SUPERSAMPLING] puis moyenne par pixel
 *    (intégration du capteur), guilloches du fond de sécurité ;
 * 2. flou optique gaussien (mise au point à 10-15 cm) ;
 * 3. sous-échantillonnage puis sur-échantillonnage bilinéaire (dématriçage, lissage du pipeline) ;
 * 4. renforcement de netteté du traitement de l'image (masque flou), parfois ;
 * 5. éclairage non uniforme, reflet, bruit du capteur, quantification sur 8 bits ;
 * 6. image « capteur » tournée de [DeviceScene.rotationDegrees] (90 : téléphone en portrait).
 */
internal data class DeviceScene(
    val format: MrzFormat,
    /** Contenu de la MRZ et mise en page. */
    val seed: Long,
    /** Tirage propre à l'image : bruit, petits mouvements de la main. Deux images d'une même scène diffèrent. */
    val frameSeed: Long = seed,
    /** Pas des caractères, en pixels du capteur. */
    val pitch: Float = 14f,
    /** Largeur de la MRZ en fraction de la largeur du cadre. */
    val mrzFraction: Float = 0.85f,
    /** Rapport largeur/hauteur du cadre recadré. */
    val aspect: Float = 3.5f,
    val angleDegrees: Double = 0.0,
    /** Écart type du flou optique, en pixels du capteur. */
    val blurSigma: Double = 1.0,
    /** Facteur du sous-échantillonnage (dématriçage) ; 1 = aucun. */
    val resample: Double = 1.5,
    /** Renforcement de netteté (0 = aucun). */
    val sharpen: Double = 0.0,
    val noiseSigma: Double = 4.0,
    val paper: Int = 210,
    val inkLevel: Int = 45,
    val shading: Double = 1.0,
    val glare: Int = 0,
    /** Fond de sécurité (guilloches) : amplitude en niveaux de gris (0 = aucun). */
    val guilloche: Double = 0.0,
    /** Bord de la page sous la MRZ (fond sombre), comme sur un passeport tenu ouvert. */
    val pageEdge: Boolean = false,
    val rotationDegrees: Int = 90,
)

internal object DeviceSceneRenderer {
    const val SUPERSAMPLING = 4

    /** Tranches de pas mesurées : [début, fin[. */
    val PITCH_BUCKETS = listOf(10f to 12f, 12f to 14f, 14f to 16f, 16f to 18f, 18f to 22f)

    /** Scène tirée au hasard dans la tranche de pas [bucket]. */
    fun randomScene(
        format: MrzFormat,
        seed: Long,
        bucket: Pair<Float, Float>,
    ): DeviceScene {
        val random = Random(seed * 7919 + format.ordinal * 104_729L + (bucket.first * 10).toLong())
        val lowContrast = random.nextInt(4) == 0
        return DeviceScene(
            format = format,
            seed = seed,
            frameSeed = seed * 1_000_003,
            pitch = bucket.first + (bucket.second - bucket.first) * random.nextFloat(),
            mrzFraction = 0.6f + 0.32f * random.nextFloat(),
            aspect = 2.8f + 1.0f * random.nextFloat(),
            angleDegrees = -5 + 10 * random.nextDouble(),
            blurSigma = 0.5 + random.nextDouble(),
            resample = 1.0 + random.nextDouble(),
            sharpen = if (random.nextBoolean()) 0.8 * random.nextDouble() else 0.0,
            noiseSigma = 2 + 8 * random.nextDouble(),
            paper = if (lowContrast) 140 + random.nextInt(50) else 190 + random.nextInt(50),
            inkLevel = if (lowContrast) 70 + random.nextInt(30) else 25 + random.nextInt(40),
            shading = if (random.nextBoolean()) 0.55 + 0.45 * random.nextDouble() else 1.0,
            glare = if (random.nextInt(4) == 0) 60 + random.nextInt(80) else 0,
            guilloche = if (random.nextBoolean()) 4 + 10 * random.nextDouble() else 0.0,
            pageEdge = random.nextBoolean(),
        )
    }

    fun render(scene: DeviceScene): RenderedScene {
        val layout = Random(scene.seed)
        val lines = MrzContent.generate(scene.format, layout)
        val random = Random(scene.frameSeed)
        val chars = lines[0].length
        val w = (chars * scene.pitch / scene.mrzFraction).roundToInt()
        val h = (w / scene.aspect).roundToInt()
        val s = SUPERSAMPLING
        val big = BufferedImage(w * s, h * s, BufferedImage.TYPE_BYTE_GRAY)
        val g = big.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.color = Color(scene.paper, scene.paper, scene.paper)
        g.fillRect(0, 0, w * s, h * s)

        // Géométrie en pixels du capteur, rendue ×s ; petit mouvement de la main entre deux images.
        val pitch = scene.pitch * (1 + 0.01f * (random.nextFloat() - 0.5f))
        val size = pitch / OcrbFont.ADVANCE
        val spacing = size * (1.45f + 0.3f * layout.nextFloat())
        val left = (w - pitch * chars) / 2f + (layout.nextFloat() - 0.5f) * pitch * 2 + (random.nextFloat() - 0.5f) * 3
        val centerY = h * (0.5f + 0.1f * (layout.nextFloat() - 0.5f)) + (random.nextFloat() - 0.5f) * 3
        val firstBaseline = centerY - spacing * (lines.size - 1) / 2f + size * 0.37f
        val angle = scene.angleDegrees + 0.4 * (random.nextDouble() - 0.5)
        g.transform(AffineTransform.getScaleInstance(s.toDouble(), s.toDouble()))
        g.transform(AffineTransform.getRotateInstance(Math.toRadians(angle), w / 2.0, centerY.toDouble()))

        // Texte de page au-dessus de la MRZ, et bord de la page en dessous.
        g.color = Color(95, 95, 95)
        g.font = OcrbFont.font.deriveFont(size * (0.5f + 0.15f * layout.nextFloat()))
        var y = firstBaseline - spacing * (1.2f + 0.3f * layout.nextFloat())
        while (y > -h) {
            val text =
                MrzContent
                    .generate(MrzFormat.TD3, layout)[0]
                    .replace('<', ' ')
                    .lowercase()
                    .take(15 + layout.nextInt(25))
            g.drawString(text, left + w * 0.2f * layout.nextFloat(), y)
            y -= size * (0.8f + 0.3f * layout.nextFloat())
        }
        if (scene.pageEdge) {
            val edge = firstBaseline + (lines.size - 1) * spacing + size * (0.5f + 0.8f * layout.nextFloat())
            val level = 30 + layout.nextInt(70)
            g.color = Color(level, level, level)
            g.fillRect(-w, edge.toInt(), 3 * w, 2 * h)
        }
        g.color = Color(scene.inkLevel, scene.inkLevel, scene.inkLevel)
        g.font = OcrbFont.font.deriveFont(size)
        lines.forEachIndexed { i, line -> g.drawString(line, left, firstBaseline + i * spacing) }
        g.dispose()

        // 1. Intégration du capteur ; guilloches imprimées sous le texte (assombrissement).
        val raster = big.raster
        var current = DoubleArray(w * h)
        val period = pitch * (0.6 + 0.8 * layout.nextDouble())
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                var sum = 0
                for (dy in 0 until s) for (dx in 0 until s) sum += raster.getSample(xx * s + dx, yy * s + dy, 0)
                var v = sum.toDouble() / (s * s)
                if (scene.guilloche > 0) {
                    val wave = sin((xx + 3 * sin(yy / period)) / period) * sin((yy + 2 * sin(xx / (1.7 * period))) / (0.8 * period))
                    v *= 1 - scene.guilloche * (1 + wave) / 2 / 255
                }
                current[yy * w + xx] = v
            }
        }
        // 2. Flou optique.
        current = gaussian(current, w, h, scene.blurSigma)
        // 3. Dématriçage et lissage.
        if (scene.resample > 1.01) current = resample(current, w, h, scene.resample)
        // 4. Renforcement de netteté.
        if (scene.sharpen > 0) {
            val smooth = gaussian(current, w, h, 1.0)
            for (i in current.indices) current[i] += scene.sharpen * (current[i] - smooth[i])
        }
        // 5. Éclairage, reflet, bruit.
        val glareCenter = w * (0.2 + 0.6 * random.nextDouble())
        val glareWidth = w * (0.08 + 0.08 * random.nextDouble())
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                var v = current[yy * w + xx]
                if (scene.shading < 1.0) v *= scene.shading + (1 - scene.shading) * (xx.toDouble() / w + yy.toDouble() / h) / 2
                if (scene.glare > 0) {
                    val d = abs(xx + 0.4 * yy - glareCenter) / glareWidth
                    if (d < 1) v += scene.glare * (1 - d * d)
                }
                v += random.nextGaussian() * scene.noiseSigma
                current[yy * w + xx] = v.coerceIn(0.0, 255.0)
            }
        }
        val frame = SceneRenderer.toFrame(current, w, h, scene.rotationDegrees, 0, random)
        return RenderedScene(Scene(scene.format, scene.seed), frame, lines)
    }

    private fun gaussian(
        source: DoubleArray,
        w: Int,
        h: Int,
        sigma: Double,
    ): DoubleArray {
        if (sigma <= 0.0) return source
        val r = ceil(3 * sigma).toInt()
        val kernel = DoubleArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        val total = kernel.sum()
        for (i in kernel.indices) kernel[i] /= total
        val tmp = DoubleArray(w * h)
        val out = DoubleArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0.0
                for (d in -r..r) acc += kernel[d + r] * source[y * w + (x + d).coerceIn(0, w - 1)]
                tmp[y * w + x] = acc
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0.0
                for (d in -r..r) acc += kernel[d + r] * tmp[(y + d).coerceIn(0, h - 1) * w + x]
                out[y * w + x] = acc
            }
        }
        return out
    }

    /** Réduit d'un facteur [factor] (moyenne de zone), puis remet à la taille d'origine (bilinéaire). */
    private fun resample(
        source: DoubleArray,
        w: Int,
        h: Int,
        factor: Double,
    ): DoubleArray {
        val sw = maxOf(2, (w / factor).toInt())
        val sh = maxOf(2, (h / factor).toInt())
        val small = DoubleArray(sw * sh)
        val fx = w.toDouble() / sw
        val fy = h.toDouble() / sh
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val x0 = (x * fx).toInt()
                val x1 = maxOf(x0 + 1, ((x + 1) * fx).toInt().coerceAtMost(w))
                val y0 = (y * fy).toInt()
                val y1 = maxOf(y0 + 1, ((y + 1) * fy).toInt().coerceAtMost(h))
                var acc = 0.0
                for (yy in y0 until y1) for (xx in x0 until x1) acc += source[yy * w + xx]
                small[y * sw + x] = acc / ((x1 - x0) * (y1 - y0))
            }
        }
        val out = DoubleArray(w * h)
        for (y in 0 until h) {
            val sy = ((y + 0.5) / fy - 0.5).coerceIn(0.0, sh - 1.0)
            val y0 = sy.toInt()
            val y1 = minOf(y0 + 1, sh - 1)
            val ty = sy - y0
            for (x in 0 until w) {
                val sx = ((x + 0.5) / fx - 0.5).coerceIn(0.0, sw - 1.0)
                val x0 = sx.toInt()
                val x1 = minOf(x0 + 1, sw - 1)
                val tx = sx - x0
                val top = small[y0 * sw + x0] * (1 - tx) + small[y0 * sw + x1] * tx
                val bottom = small[y1 * sw + x0] * (1 - tx) + small[y1 * sw + x1] * tx
                out[y * w + x] = top * (1 - ty) + bottom * ty
            }
        }
        return out
    }
}
