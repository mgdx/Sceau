package io.github.mgdx.sceau.mrz

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToInt

/** Police OCR-B de `mrz/fonts/`, pour les tests uniquement (jamais dans l'APK). */
internal object OcrbFont {
    val file: File = File(System.getProperty("sceau.mrz.fontDir") ?: "fonts", "ocrb10.otf")

    val font: Font by lazy {
        System.setProperty("java.awt.headless", "true")
        Font.createFont(Font.TRUETYPE_FONT, file)
    }

    /** Chasse d'un caractère, en fraction du corps. */
    const val ADVANCE = 0.723f
}

/** Contenu de MRZ de synthèse aux chiffres de contrôle justes (aucune donnée réelle). */
internal object MrzContent {
    private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun generate(
        format: MrzFormat,
        random: Random,
    ): List<String> {
        val country = word(random, 3)
        val nationality = if (random.nextBoolean()) country else word(random, 3)
        val number = documentNumber(random)
        val birth = date(random)
        val expiry = date(random)
        val sex = "MF<"[random.nextInt(3)].toString()
        return when (format) {
            MrzFormat.TD3 -> {
                val optional = optional(random, 14)
                val upper = number + check(number) + nationality + birth + check(birth) + sex + expiry + check(expiry)
                val composite = number + check(number) + birth + check(birth) + expiry + check(expiry) + optional + check(optional)
                listOf(
                    name("P" + "<O"[random.nextInt(2)] + country, 44, random),
                    upper + optional + check(optional) + check(composite),
                )
            }

            MrzFormat.TD2 -> {
                val optional = optional(random, 7)
                val composite = number + check(number) + birth + check(birth) + expiry + check(expiry) + optional
                listOf(
                    name("I<$country", 36, random),
                    number + check(number) + nationality + birth + check(birth) + sex + expiry + check(expiry) + optional +
                        check(composite),
                )
            }

            MrzFormat.TD1 -> {
                val optional1 = optional(random, 15)
                val optional2 = optional(random, 11)
                val line1 = "I" + "<D"[random.nextInt(2)] + country + number + check(number) + optional1
                val line2Head = birth + check(birth) + sex + expiry + check(expiry) + nationality + optional2
                val composite = number + check(number) + optional1 + birth + check(birth) + expiry + check(expiry) + optional2
                listOf(line1, line2Head + check(composite), name("", 30, random))
            }
        }
    }

    private fun word(
        random: Random,
        length: Int,
    ): String = String(CharArray(length) { LETTERS[random.nextInt(LETTERS.length)] })

    private fun documentNumber(random: Random): String =
        String(CharArray(9) { if (it < 2 || random.nextInt(4) == 0) ALNUM[random.nextInt(ALNUM.length)] else ('0' + random.nextInt(10)) })

    private fun date(random: Random): String = "%02d%02d%02d".format(random.nextInt(100), 1 + random.nextInt(12), 1 + random.nextInt(28))

    private fun optional(
        random: Random,
        length: Int,
    ): String {
        if (random.nextInt(3) == 0) return "<".repeat(length)
        val used = 1 + random.nextInt(length)
        return String(CharArray(used) { ALNUM[random.nextInt(ALNUM.length)] }) + "<".repeat(length - used)
    }

    private fun name(
        prefix: String,
        length: Int,
        random: Random,
    ): String {
        val surname = word(random, 3 + random.nextInt(8))
        val given = word(random, 3 + random.nextInt(7))
        val text = (prefix + surname + "<<" + given + if (random.nextBoolean()) "<" + word(random, 4) else "").take(length)
        return text.padEnd(length, '<')
    }

    fun check(field: String): Char {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        field.forEachIndexed { i, c ->
            val value =
                when (c) {
                    in '0'..'9' -> c - '0'
                    in 'A'..'Z' -> c - 'A' + 10
                    else -> 0
                }
            sum += value * weights[i % 3]
        }
        return '0' + sum % 10
    }

    /** Lignes utiles attendues de [RecognizedMrz] : jamais la ligne du nom. */
    fun usefulLines(
        format: MrzFormat,
        lines: List<String>,
    ): List<String> = if (format == MrzFormat.TD1) lines.subList(0, 2) else lines.subList(1, 2)
}

/** Paramètres d'une image de synthèse. Toutes les dégradations sont désactivées par défaut. */
internal data class Scene(
    val format: MrzFormat,
    val seed: Long,
    val width: Int = 1280,
    val height: Int = 400,
    /** Largeur de la MRZ en fraction de la largeur de l'image. */
    val mrzWidth: Float = 0.9f,
    /** Position verticale du centre de la MRZ, en fraction de la hauteur. */
    val mrzCenter: Float = 0.6f,
    val angleDegrees: Double = 0.0,
    val blurRadius: Int = 0,
    val noiseSigma: Double = 0.0,
    val paper: Int = 230,
    val inkLevel: Int = 30,
    /** Éclairage : facteur multiplicatif minimal d'un dégradé diagonal (1 = uniforme). */
    val shading: Double = 1.0,
    /** Bande de reflet : luminosité ajoutée (0 = aucune), largeur en fraction de l'image. */
    val glare: Int = 0,
    val glareWidth: Float = 0.12f,
    /** Perspective : rapport de largeur entre le haut et le bas de l'image (1 = aucune). */
    val keystone: Double = 1.0,
    /** Graisse ajoutée au trait, en fraction du corps (encre qui bave). */
    val bold: Float = 0f,
    val pageText: Boolean = true,
    /** Faux : page sans MRZ, du texte en majuscules à sa place. */
    val withMrz: Boolean = true,
    val rotationDegrees: Int = 0,
    val rowPadding: Int = 0,
)

internal class RenderedScene(
    val scene: Scene,
    val frame: LumaFrame,
    val lines: List<String>,
) {
    val useful: List<String> get() = MrzContent.usefulLines(scene.format, lines)
}

internal object SceneRenderer {
    fun render(scene: Scene): RenderedScene {
        val random = Random(scene.seed)
        val lines = MrzContent.generate(scene.format, random)
        val w = scene.width
        val h = scene.height
        val image = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.color = Color(scene.paper, scene.paper, scene.paper)
        g.fillRect(0, 0, w, h)

        val chars = lines[0].length
        val size = scene.mrzWidth * w / (chars * OcrbFont.ADVANCE)
        val font = OcrbFont.font.deriveFont(size)
        val pitch = size * OcrbFont.ADVANCE
        val spacing = pitch * (1.45f + 0.3f * random.nextFloat())
        val left = (w - pitch * chars) / 2f + (random.nextFloat() - 0.5f) * pitch
        val centerY = scene.mrzCenter * h
        val firstBaseline = centerY - spacing * (lines.size - 1) / 2f + size * 0.37f
        g.transform(AffineTransform.getRotateInstance(Math.toRadians(scene.angleDegrees), w / 2.0, centerY.toDouble()))

        val ink = Color(scene.inkLevel, scene.inkLevel, scene.inkLevel)
        if (scene.pageText) {
            // Fond de page : texte plus petit au-dessus de la MRZ et un aplat (photo).
            g.color = Color(90, 90, 90)
            g.font = OcrbFont.font.deriveFont(size * 0.55f)
            var y = firstBaseline - spacing * 1.3f
            while (y > -h) {
                val text =
                    MrzContent
                        .generate(MrzFormat.TD3, random)[0]
                        .replace('<', ' ')
                        .lowercase()
                        .take(20 + random.nextInt(20))
                g.drawString(text, left + w * 0.25f, y)
                y -= size * 0.9f
            }
            g.color = Color(120, 110, 100)
            g.fillRect((left).toInt(), (firstBaseline - spacing * 1.3f - size * 4).toInt(), (w * 0.2).toInt(), (size * 3.5).toInt())
        }
        g.color = ink
        if (!scene.withMrz) {
            // Texte de page à la place de la MRZ : majuscules OCR-B, lignes plus courtes qu'une MRZ.
            g.font = font.deriveFont(size * (0.6f + 0.5f * random.nextFloat()))
            var y = firstBaseline - spacing
            while (y < 2 * h) {
                val text = MrzContent.generate(MrzFormat.TD3, random)[0].replace('<', ' ').take(8 + random.nextInt(18))
                g.drawString(text, left, y)
                y += spacing
            }
        }
        g.font = font
        val drawn = if (scene.withMrz) lines else emptyList()
        drawn.forEachIndexed { i, line ->
            val y = firstBaseline + i * spacing
            if (scene.bold > 0f) {
                val vector = font.createGlyphVector(g.fontRenderContext, line)
                val outline = vector.getOutline(left, y)
                g.stroke = BasicStroke(scene.bold * size)
                g.draw(outline)
                g.fill(outline)
            } else {
                g.drawString(line, left, y)
            }
        }
        g.dispose()

        val pixels = DoubleArray(w * h)
        val raster = image.raster
        for (y in 0 until h) for (x in 0 until w) pixels[y * w + x] = raster.getSample(x, y, 0).toDouble()
        var current = pixels
        if (scene.keystone != 1.0) current = keystone(current, w, h, scene.keystone)
        if (scene.blurRadius > 0) current = blur(blur(current, w, h, scene.blurRadius), w, h, scene.blurRadius)
        val glareCenter = w * (0.2f + 0.6f * random.nextFloat())
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = current[y * w + x]
                if (scene.shading < 1.0) v *= scene.shading + (1 - scene.shading) * (x.toDouble() / w + y.toDouble() / h) / 2
                if (scene.glare > 0) {
                    val d = abs(x + 0.3 * y - glareCenter) / (scene.glareWidth * w / 2)
                    if (d < 1) v += scene.glare * (1 - d * d)
                }
                if (scene.noiseSigma > 0) v += random.nextGaussian() * scene.noiseSigma
                current[y * w + x] = v.coerceIn(0.0, 255.0)
            }
        }
        return RenderedScene(scene, toFrame(current, w, h, scene.rotationDegrees, scene.rowPadding, random), lines)
    }

    /** Applique une perspective (trapèze) : le haut de l'image est rétréci d'un facteur [ratio]. */
    private fun keystone(
        source: DoubleArray,
        w: Int,
        h: Int,
        ratio: Double,
    ): DoubleArray {
        val out = DoubleArray(w * h)
        val cx = w / 2.0
        for (y in 0 until h) {
            val scale = ratio + (1 - ratio) * y / (h - 1)
            for (x in 0 until w) {
                val sx = cx + (x - cx) / scale
                val ix = sx.roundToInt()
                out[y * w + x] = if (ix in 0 until w) source[y * w + ix] else source[y * w + x]
            }
        }
        return out
    }

    private fun blur(
        source: DoubleArray,
        w: Int,
        h: Int,
        r: Int,
    ): DoubleArray {
        val tmp = DoubleArray(w * h)
        val out = DoubleArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0.0
                for (d in -r..r) s += source[y * w + (x + d).coerceIn(0, w - 1)]
                tmp[y * w + x] = s / (2 * r + 1)
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0.0
                for (d in -r..r) s += tmp[(y + d).coerceIn(0, h - 1) * w + x]
                out[y * w + x] = s / (2 * r + 1)
            }
        }
        return out
    }

    /**
     * Image « capteur » : l'image à l'endroit tournée de [rotation] degrés dans le sens inverse
     * des aiguilles d'une montre, pour qu'une rotation horaire de [rotation] la remette à
     * l'endroit (convention de `ImageInfo.getRotationDegrees` de CameraX).
     */
    fun toFrame(
        upright: DoubleArray,
        w: Int,
        h: Int,
        rotation: Int,
        padding: Int,
        random: Random,
    ): LumaFrame {
        val quarter = rotation == 90 || rotation == 270
        val sw = if (quarter) h else w
        val sh = if (quarter) w else h
        val stride = sw + padding
        val data = ByteArray(stride * sh)
        random.nextBytes(data)
        for (v in 0 until h) {
            for (u in 0 until w) {
                // Rotation antihoraire de l'image à l'endroit.
                val (x, y) =
                    when (rotation) {
                        90 -> v to (w - 1 - u)
                        180 -> (w - 1 - u) to (h - 1 - v)
                        270 -> (h - 1 - v) to u
                        else -> u to v
                    }
                data[y * stride + x] = upright[v * w + u].roundToInt().toByte()
            }
        }
        return LumaFrame(data, sw, sh, stride, rotation)
    }
}
