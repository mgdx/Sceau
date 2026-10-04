package io.github.mgdx.sceau.mrz

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Localisation de la MRZ et classement de ses caractères par comparaison à des modèles OCR-B
 * (lot B de D32). Chaîne de traitement :
 *
 * 1. remise à l'endroit ([LumaFrame.rotationDegrees]) et réduction par moyenne de blocs ;
 * 2. binarisation adaptative (moyenne locale sur image intégrale, avec un contraste minimal) ;
 * 3. estimation de l'inclinaison par maximisation de la variance du profil horizontal
 *    (±[MAX_SKEW_DEGREES]), puis redressement de l'image en niveaux de gris ;
 * 4. profil horizontal → bandes de texte ; pour chaque bande, profil vertical → blocs
 *    (caractères), pas médian, chaîne de blocs réguliers et nombre de positions (30, 36 ou 44) ;
 * 5. groupe de 2 ou 3 lignes alignées, de même pas et d'espacement régulier → format ;
 * 6. grille au pas fixe recalée sur les blocs, ligne de base et hauteur par ligne, puis
 *    corrélation normalisée de chaque cellule contre les 37 modèles, avec un petit décalage.
 *
 * La ligne du nom (TD3 et TD2 ligne 1, TD1 ligne 3) sert à reconnaître le format mais n'est
 * jamais découpée ni classée. Les tampons de travail sont réutilisés d'un appel à l'autre et
 * remis à zéro avant le retour ; aucune référence à [LumaFrame.data] n'est conservée. Un seul
 * fil à la fois.
 */
internal class TemplateLineRecognizer(
    private val templates: OcrbTemplates = OcrbTemplates.default,
) : LineRecognizer {
    private var width = 0
    private var height = 0

    /** Image remise à l'endroit et réduite, en niveaux de gris ; [straight] : tampon de redressement. */
    private var gray = ByteArray(0)
    private var straight = ByteArray(0)
    private var cosine = 1f
    private var sine = 0f
    private var centerX = 0f
    private var centerY = 0f

    /** Encre (1) ou papier (0), après binarisation. */
    private var ink = ByteArray(0)
    private var integral = IntArray(0)
    private var samples = IntArray(0)
    private var profile = IntArray(0)
    private var columns = IntArray(0)
    private var blobStart = IntArray(0)
    private var blobEnd = IntArray(0)
    private var blobMass = IntArray(0)
    private var diffs = FloatArray(0)
    private var cellTop = IntArray(0)
    private var cellBottom = IntArray(0)

    private val patchWidth = templates.width + 2 * SHIFT_X
    private val patchHeight = templates.height + 2 * SHIFT_Y
    private val patch = FloatArray(patchWidth * patchHeight)
    private val scores = FloatArray(templates.classes.size)

    override fun recognize(frame: LumaFrame): RecognizedMrz? {
        if (!isValid(frame)) return null
        try {
            if (!prepare(frame)) return null
            return analyze()
        } finally {
            wipe()
        }
    }

    // --- 1. Entrée -------------------------------------------------------------------------

    private fun isValid(frame: LumaFrame): Boolean {
        if (frame.rotationDegrees != 0 && frame.rotationDegrees != 90 && frame.rotationDegrees != 180 &&
            frame.rotationDegrees != 270
        ) {
            return false
        }
        if (frame.width !in 1..MAX_INPUT_SIZE || frame.height !in 1..MAX_INPUT_SIZE) return false
        if (frame.rowStride < frame.width) return false
        val needed = (frame.height - 1).toLong() * frame.rowStride + frame.width
        return needed <= frame.data.size
    }

    /** Remplit [gray] : image remise à l'endroit et réduite d'un facteur entier. Faux si trop petite. */
    private fun prepare(frame: LumaFrame): Boolean {
        val quarter = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
        val uprightWidth = if (quarter) frame.height else frame.width
        val uprightHeight = if (quarter) frame.width else frame.height
        var factor = 1
        while (uprightWidth / factor > MAX_WORK_WIDTH ||
            (uprightWidth / factor).toLong() * (uprightHeight / factor) > MAX_WORK_PIXELS
        ) {
            factor++
        }
        width = uprightWidth / factor
        height = uprightHeight / factor
        if (width < MIN_WORK_WIDTH || height < MIN_WORK_HEIGHT) return false
        ensureCapacity()

        // Index source = base + U·stepU + V·stepV, (U, V) coordonnées dans l'image à l'endroit.
        val stride = frame.rowStride
        val lastRow = (frame.height - 1) * stride
        val lastColumn = frame.width - 1
        val base: Int
        val stepU: Int
        val stepV: Int
        when (frame.rotationDegrees) {
            90 -> {
                base = lastRow
                stepU = -stride
                stepV = 1
            }

            180 -> {
                base = lastRow + lastColumn
                stepU = -1
                stepV = -stride
            }

            270 -> {
                base = lastColumn
                stepU = stride
                stepV = -1
            }

            else -> {
                base = 0
                stepU = 1
                stepV = stride
            }
        }
        val data = frame.data
        if (factor == 1) {
            for (v in 0 until height) {
                var index = base + v * stepV
                val offset = v * width
                for (u in 0 until width) {
                    gray[offset + u] = data[index]
                    index += stepU
                }
            }
            return true
        }
        val area = factor * factor
        for (v in 0 until height) {
            for (u in 0 until width) {
                var sum = 0
                val origin = base + u * factor * stepU + v * factor * stepV
                for (dv in 0 until factor) {
                    var index = origin + dv * stepV
                    for (du in 0 until factor) {
                        sum += data[index].toInt() and 0xFF
                        index += stepU
                    }
                }
                gray[v * width + u] = (sum / area).toByte()
            }
        }
        return true
    }

    private fun ensureCapacity() {
        val pixels = width * height
        if (gray.size < pixels) {
            gray = ByteArray(pixels)
            straight = ByteArray(pixels)
            ink = ByteArray(pixels)
        }
        if (integral.size < (width + 1) * (height + 1)) integral = IntArray((width + 1) * (height + 1))
        val samplesNeeded = (width / SKEW_COLUMN_STEP + 1) * height
        if (samples.size < samplesNeeded) samples = IntArray(samplesNeeded)
        val bins = height + 2 * skewOffset() + 2
        if (profile.size < maxOf(bins, height)) profile = IntArray(maxOf(bins, height))
        if (columns.size < width) {
            columns = IntArray(width)
            blobStart = IntArray(width / 2 + 1)
            blobEnd = IntArray(width / 2 + 1)
            blobMass = IntArray(width / 2 + 1)
            diffs = FloatArray(width / 2 + 1)
        }
        if (cellTop.size < MAX_POSITIONS) {
            cellTop = IntArray(MAX_POSITIONS)
            cellBottom = IntArray(MAX_POSITIONS)
        }
    }

    private fun wipe() {
        gray.fill(0)
        straight.fill(0)
        ink.fill(0)
        integral.fill(0)
        samples.fill(0)
        profile.fill(0)
        columns.fill(0)
        patch.fill(0f)
        scores.fill(0f)
    }

    // --- 2. Binarisation ---------------------------------------------------------------------

    /** Binarise [source] dans [ink] : encre si plus sombre que la moyenne locale d'un écart suffisant. */
    private fun binarize(source: ByteArray) {
        val w = width
        val h = height
        val row = w + 1
        for (x in 0..w) integral[x] = 0
        for (y in 1..h) {
            var running = 0
            integral[y * row] = 0
            val offset = (y - 1) * w
            for (x in 1..w) {
                running += source[offset + x - 1].toInt() and 0xFF
                integral[y * row + x] = integral[(y - 1) * row + x] + running
            }
        }
        val radius = maxOf(MIN_WINDOW_RADIUS, w / WINDOW_DIVISOR)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - radius)
            val y1 = minOf(h, y + radius + 1)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - radius)
                val x1 = minOf(w, x + radius + 1)
                val count = (y1 - y0) * (x1 - x0)
                val sum = integral[y1 * row + x1] - integral[y0 * row + x1] - integral[y1 * row + x0] + integral[y0 * row + x0]
                // Pixel lissé sur 3 × 3 (bruit du capteur divisé par trois).
                val px0 = maxOf(0, x - 1)
                val px1 = minOf(w, x + 2)
                val py0 = maxOf(0, y - 1)
                val py1 = minOf(h, y + 2)
                val local = (px1 - px0) * (py1 - py0)
                val pixels =
                    integral[py1 * row + px1] - integral[py0 * row + px1] - integral[py1 * row + px0] + integral[py0 * row + px0]
                // moyenne − pixel > max(contraste minimal, K · moyenne), en entiers (facteur commun
                // count · local) : la fenêtre compte au plus (2 · 44 + 1)² pixels, les produits
                // tiennent dans un Int.
                val darker = sum * local - pixels * count
                val isInk =
                    darker > MIN_CONTRAST * count * local && darker * CONTRAST_SCALE > sum * local * RELATIVE_CONTRAST
                ink[y * w + x] = if (isInk) 1 else 0
            }
        }
    }

    // --- 3. Inclinaison -------------------------------------------------------------------------

    private fun skewOffset(): Int = (width * tan(Math.toRadians(MAX_SKEW_DEGREES))).toInt() + 2

    /** Angle (degrés) des lignes de texte : y = y0 + x · tan(angle). */
    private fun estimateSkew(): Double {
        var count = 0
        // Toutes les rangées (un pas vertical créerait un pic artificiel à 0°), une colonne sur trois.
        for (y in 0 until height) {
            for (x in 0 until width step SKEW_COLUMN_STEP) {
                if (ink[y * width + x].toInt() != 0) samples[count++] = (y shl 16) or x
            }
        }
        if (count < MIN_SKEW_SAMPLES) return 0.0
        var best = 0.0
        var bestScore = -1L
        var angle = -MAX_SKEW_DEGREES
        while (angle <= MAX_SKEW_DEGREES + 1e-9) {
            val score = projectionScore(angle, count)
            if (score > bestScore) {
                bestScore = score
                best = angle
            }
            angle += COARSE_SKEW_STEP
        }
        val center = best
        angle = center - COARSE_SKEW_STEP + FINE_SKEW_STEP
        while (angle <= center + COARSE_SKEW_STEP - FINE_SKEW_STEP + 1e-9) {
            val score = projectionScore(angle, count)
            if (score > bestScore) {
                bestScore = score
                best = angle
            }
            angle += FINE_SKEW_STEP
        }
        return best
    }

    private fun projectionScore(
        degrees: Double,
        count: Int,
    ): Long {
        val slope = tan(Math.toRadians(degrees)).toFloat()
        val offset = skewOffset()
        val bins = height + 2 * offset
        val shift = offset + 0.5f
        profile.fill(0, 0, bins)
        for (i in 0 until count) {
            val packed = samples[i]
            val bin = ((packed ushr 16) - (packed and 0xFFFF) * slope + shift).toInt()
            if (bin in 0 until bins) profile[bin]++
        }
        var score = 0L
        for (b in 0 until bins) score += profile[b].toLong() * profile[b]
        return score
    }

    /**
     * Redresse l'encre d'une rotation de −[degrees] autour du centre (plus proche voisin) : après
     * l'appel, [ink] contient l'image binaire redressée. [gray] n'est pas redressée : les cellules
     * y sont échantillonnées par [toGrayX] et [toGrayY].
     */
    private fun deskew(degrees: Double) {
        val w = width
        val h = height
        val radians = if (abs(degrees) < MIN_SKEW_CORRECTION) 0.0 else Math.toRadians(degrees)
        cosine = cos(radians).toFloat()
        sine = sin(radians).toFloat()
        centerX = (w - 1) / 2f
        centerY = (h - 1) / 2f
        if (radians == 0.0) return
        for (v in 0 until h) {
            var x = toGrayX(0f, v.toFloat()) + 0.5f
            var y = toGrayY(0f, v.toFloat()) + 0.5f
            for (u in 0 until w) {
                val ix = x.toInt()
                val iy = y.toInt()
                straight[v * w + u] = if (x >= 0f && y >= 0f && ix < w && iy < h) ink[iy * w + ix] else 0
                x += cosine
                y += sine
            }
        }
        val rotated = straight
        straight = ink
        ink = rotated
    }

    /** Abscisse dans [gray] du point (x, y) de l'image redressée. */
    private fun toGrayX(
        x: Float,
        y: Float,
    ): Float = centerX + (x - centerX) * cosine - (y - centerY) * sine

    /** Ordonnée dans [gray] du point (x, y) de l'image redressée. */
    private fun toGrayY(
        x: Float,
        y: Float,
    ): Float = centerY + (x - centerX) * sine + (y - centerY) * cosine

    private fun sampleGray(
        x: Float,
        y: Float,
    ): Float = bilinear(gray, toGrayX(x, y), toGrayY(x, y))

    private fun bilinear(
        image: ByteArray,
        x: Float,
        y: Float,
    ): Float {
        val w = width
        val h = height
        val cx = x.coerceIn(0f, (w - 1).toFloat())
        val cy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = minOf(x0 + 1, w - 1)
        val y1 = minOf(y0 + 1, h - 1)
        val fx = cx - x0
        val fy = cy - y0
        val a = image[y0 * w + x0].toInt() and 0xFF
        val b = image[y0 * w + x1].toInt() and 0xFF
        val c = image[y1 * w + x0].toInt() and 0xFF
        val d = image[y1 * w + x1].toInt() and 0xFF
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return top + (bottom - top) * fy
    }

    // --- 4. et 5. Lignes et format ------------------------------------------------------------

    private class Band(
        val top: Int,
        val bottom: Int,
    ) {
        val height: Int get() = bottom - top + 1
        val center: Float get() = (top + bottom) / 2f
    }

    /** Ligne de caractères régulièrement espacés : [positions] cellules de pas [pitch] à partir de [x0]. */
    private class TextLine(
        val band: Band,
        val bandIndex: Int,
        val positions: Int,
        val x0: Float,
        val pitch: Float,
    )

    private fun analyze(): RecognizedMrz? {
        binarize(gray)
        val skew = estimateSkew()
        deskew(skew)

        var maxProfile = 0
        for (y in 0 until height) {
            var sum = 0
            val offset = y * width
            for (x in 0 until width) sum += ink[offset + x]
            profile[y] = sum
            if (sum > maxProfile) maxProfile = sum
        }
        if (maxProfile < width / MIN_ROW_INK_DIVISOR) return null
        for (fraction in BAND_THRESHOLDS) {
            val threshold = maxOf(width / MIN_ROW_INK_DIVISOR, (maxProfile * fraction).toInt())
            val bands = findBands(threshold)
            val lines = ArrayList<TextLine>()
            for ((index, band) in bands.withIndex()) {
                analyzeLine(band, index)?.let(lines::add)
            }
            val result = selectFormat(lines)?.let { (format, useful) -> classify(format, useful, bands) }
            if (result != null) return result
        }
        return null
    }

    private fun findBands(threshold: Int): List<Band> {
        val bands = ArrayList<Band>()
        var y = 0
        while (y < height) {
            if (profile[y] < threshold) {
                y++
                continue
            }
            val top = y
            var bottom = y
            while (y < height) {
                if (profile[y] >= threshold) {
                    bottom = y
                } else if (y - bottom > BAND_GAP_TOLERANCE) {
                    break
                }
                y++
            }
            if (bottom - top + 1 >= MIN_BAND_HEIGHT && bands.size < MAX_BANDS) bands += Band(top, bottom)
            y = bottom + 1
        }
        return bands
    }

    /** Cherche dans [band] une chaîne de 30, 36 ou 44 caractères au pas régulier. */
    private fun analyzeLine(
        band: Band,
        bandIndex: Int,
    ): TextLine? {
        val bandHeight = band.height
        for (x in 0 until width) columns[x] = 0
        for (y in band.top..band.bottom) {
            val offset = y * width
            for (x in 0 until width) columns[x] += ink[offset + x]
        }
        val minMass = maxOf(2, bandHeight * bandHeight / MIN_BLOB_MASS_DIVISOR)
        // Colonne encrée : au moins un trait, pas un pixel de bruit isolé.
        val minColumn = maxOf(1, bandHeight / MIN_COLUMN_INK_DIVISOR)
        var blobs = 0
        var x = 0
        while (x < width) {
            if (columns[x] < minColumn) {
                x++
                continue
            }
            val start = x
            var mass = 0
            while (x < width && columns[x] >= minColumn) mass += columns[x++]
            if (mass >= minMass && blobs < blobStart.size) {
                blobStart[blobs] = start
                blobEnd[blobs] = x - 1
                blobMass[blobs] = mass
                blobs++
            }
        }
        if (blobs < MIN_LINE_BLOBS) return null

        // Taches nettement plus légères qu'un caractère médian (bruit, poussière) : écartées.
        for (i in 0 until blobs) diffs[i] = blobMass[i].toFloat()
        diffs.sort(0, blobs)
        val lightest = diffs[blobs / 2] * MIN_RELATIVE_BLOB_MASS
        var kept = 0
        for (i in 0 until blobs) {
            if (blobMass[i] < lightest) continue
            blobStart[kept] = blobStart[i]
            blobEnd[kept] = blobEnd[i]
            blobMass[kept] = blobMass[i]
            kept++
        }
        blobs = kept
        if (blobs < MIN_LINE_BLOBS) return null

        // Pas : médiane des écarts entre centres de blocs voisins de la taille d'un caractère.
        var diffCount = 0
        for (i in 1 until blobs) {
            if (blobWidth(i) > bandHeight * SINGLE_WIDTH_RATIO || blobWidth(i - 1) > bandHeight * SINGLE_WIDTH_RATIO) continue
            val d = blobCenter(i) - blobCenter(i - 1)
            if (d >= bandHeight * MIN_PITCH_RATIO && d <= bandHeight * MAX_PITCH_RATIO) diffs[diffCount++] = d
        }
        if (diffCount < MIN_LINE_BLOBS / 2) return null
        diffs.sort(0, diffCount)
        val pitch = diffs[diffCount / 2]

        // Chaînes de blocs : écart ≤ MAX_CHAIN_GAP pas (un reflet peut effacer quelques caractères),
        // aucun bloc plus large que deux caractères, au moins la moitié des positions présentes.
        var best: TextLine? = null
        var bestBlobs = 0
        var i = 0
        while (i < blobs) {
            if (blobWidth(i) > pitch * MAX_BLOB_PITCHES) {
                i++
                continue
            }
            val first = i
            var last = i
            i++
            while (i < blobs && blobWidth(i) <= pitch * MAX_BLOB_PITCHES &&
                blobCenter(i) - blobCenter(last) <= pitch * MAX_CHAIN_GAP
            ) {
                last = i++
            }
            val count = last - first + 1
            if (count < MIN_LINE_BLOBS || count <= bestBlobs) continue
            val estimated = (blobCenter(last) - blobCenter(first)) / pitch + 1
            val positions = LINE_LENGTHS.firstOrNull { abs(estimated - it) <= it * LENGTH_TOLERANCE + 0.5f } ?: continue
            if (count < positions / 2) continue
            val fitted = fitGrid(first, last, positions, pitch) ?: continue
            best = TextLine(band, bandIndex, positions, fitted.first, fitted.second)
            bestBlobs = count
        }
        return best
    }

    private fun blobWidth(i: Int): Int = blobEnd[i] - blobStart[i] + 1

    private fun blobCenter(i: Int): Float = (blobStart[i] + blobEnd[i]) / 2f

    /** Recale la grille (origine, pas) sur les centres des blocs d'un seul caractère, par moindres carrés. */
    private fun fitGrid(
        first: Int,
        last: Int,
        positions: Int,
        medianPitch: Float,
    ): Pair<Float, Float>? {
        var x0 = blobCenter(first)
        var pitch = (blobCenter(last) - x0) / (positions - 1)
        if (abs(pitch - medianPitch) > medianPitch * LENGTH_TOLERANCE) return null
        repeat(GRID_ITERATIONS) {
            var n = 0
            var sk = 0.0
            var sc = 0.0
            var skk = 0.0
            var skc = 0.0
            for (i in first..last) {
                if (blobWidth(i) > pitch * SINGLE_PITCH_RATIO) continue
                val c = blobCenter(i)
                val k = ((c - x0) / pitch).roundToInt()
                if (k !in 0 until positions) continue
                if (abs(c - (x0 + k * pitch)) > pitch * MAX_GRID_RESIDUAL) continue
                n++
                sk += k
                sc += c
                skk += k.toDouble() * k
                skc += k * c.toDouble()
            }
            val det = n * skk - sk * sk
            if (n < MIN_LINE_BLOBS / 2 || abs(det) < 1e-6) return null
            pitch = ((n * skc - sk * sc) / det).toFloat()
            x0 = ((sc - pitch * sk) / n).toFloat()
            if (pitch <= 0f) return null
        }
        return x0 to pitch
    }

    /** Groupe de lignes formant une MRZ, et les lignes utiles (jamais celle du nom). */
    private fun selectFormat(lines: List<TextLine>): Pair<MrzFormat, List<TextLine>>? {
        for (i in 0..lines.size - 3) {
            val a = lines[i]
            val b = lines[i + 1]
            val c = lines[i + 2]
            if (a.positions == TD1_LENGTH && consistent(a, b) && consistent(b, c)) {
                val d1 = b.band.center - a.band.center
                val d2 = c.band.center - b.band.center
                if (abs(d1 - d2) <= maxOf(d1, d2) * MAX_SPACING_DIFFERENCE) return MrzFormat.TD1 to listOf(a, b)
            }
        }
        for (i in lines.size - 2 downTo 0) {
            val a = lines[i]
            val b = lines[i + 1]
            if (a.positions == TD1_LENGTH || !consistent(a, b)) continue
            val format = if (a.positions == TD3_LENGTH) MrzFormat.TD3 else MrzFormat.TD2
            return format to listOf(b)
        }
        return null
    }

    private fun consistent(
        a: TextLine,
        b: TextLine,
    ): Boolean {
        if (a.positions != b.positions) return false
        val pitch = (a.pitch + b.pitch) / 2
        if (abs(a.pitch - b.pitch) > pitch * MAX_PITCH_DIFFERENCE) return false
        if (abs(a.x0 - b.x0) > pitch * MAX_ALIGNMENT_PITCHES) return false
        val spacing = b.band.center - a.band.center
        val lineHeight = (a.band.height + b.band.height) / 2f
        return spacing >= lineHeight * MIN_LINE_SPACING && spacing <= lineHeight * MAX_LINE_SPACING
    }

    // --- 6. Classement ---------------------------------------------------------------------------

    private fun classify(
        format: MrzFormat,
        useful: List<TextLine>,
        bands: List<Band>,
    ): RecognizedMrz? {
        val lines = ArrayList<List<GlyphCandidates>>(useful.size)
        var total = 0f
        var cells = 0
        for (line in useful) {
            val upper = if (line.bandIndex > 0) (bands[line.bandIndex - 1].bottom + line.band.top) / 2 else 0
            val lower = if (line.bandIndex < bands.size - 1) (line.band.bottom + bands[line.bandIndex + 1].top) / 2 else height - 1
            val geometry = verticalGeometry(line, upper, lower) ?: return null
            val candidates = ArrayList<GlyphCandidates>(line.positions)
            for (k in 0 until line.positions) {
                val glyph = classifyCell(line.x0 + k * line.pitch, line.pitch, geometry)
                total += glyph.ranked[0].second
                cells++
                candidates += glyph
            }
            lines += candidates
        }
        if (total / cells < MIN_MEAN_SCORE) return null
        return RecognizedMrz(format, lines)
    }

    /** Ligne de base (ordonnée à l'origine, pente) et hauteur des glyphes d'une ligne. */
    private class Geometry(
        val baseIntercept: Float,
        val baseSlope: Float,
        val glyphHeight: Float,
    )

    private fun verticalGeometry(
        line: TextLine,
        upper: Int,
        lower: Int,
    ): Geometry? {
        val half = line.pitch * CELL_INK_HALF_WIDTH
        val heights = FloatArray(line.positions)
        var found = 0
        for (k in 0 until line.positions) {
            val center = line.x0 + k * line.pitch
            val x0 = maxOf(0, (center - half).roundToInt())
            val x1 = minOf(width - 1, (center + half).roundToInt())
            // Rangées encrées contiguës autour de la première rangée encrée de la bande.
            var top = -1
            for (y in line.band.top..line.band.bottom) {
                if (rowHasInk(y, x0, x1)) {
                    top = y
                    break
                }
            }
            var bottom = top
            if (top >= 0) {
                while (top > upper && rowHasInk(top - 1, x0, x1)) top--
                while (bottom < lower && rowHasInk(bottom + 1, x0, x1)) bottom++
            }
            cellTop[k] = top
            cellBottom[k] = bottom
            if (top >= 0) heights[found++] = (bottom - top + 1).toFloat()
        }
        if (found < line.positions / 3) return null
        heights.sort(0, found)
        val tall = heights[(found * TALL_PERCENTILE).toInt().coerceAtMost(found - 1)] * TALL_RATIO
        var n = 0
        var tallHeights = 0
        for (k in 0 until line.positions) {
            if (cellTop[k] >= 0 &&
                cellBottom[k] - cellTop[k] + 1 >= tall
            ) {
                heights[tallHeights++] = (cellBottom[k] - cellTop[k] + 1).toFloat()
            }
        }
        heights.sort(0, tallHeights)
        val glyphHeight = heights[tallHeights / 2]
        // Ligne de base : moindres carrés sur le bas des glyphes hauts, une passe d'élagage.
        var intercept = 0f
        var slope = 0f
        repeat(BASELINE_PASSES) { pass ->
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var sxy = 0.0
            n = 0
            for (k in 0 until line.positions) {
                if (cellTop[k] < 0 || cellBottom[k] - cellTop[k] + 1 < tall) continue
                val x = line.x0 + k * line.pitch
                val y = cellBottom[k] + 0.5f
                if (pass > 0 && abs(y - (intercept + slope * x)) > glyphHeight * MAX_BASELINE_RESIDUAL) continue
                n++
                sx += x
                sy += y
                sxx += x.toDouble() * x
                sxy += x.toDouble() * y
            }
            if (n == 0) return null
            val det = n * sxx - sx * sx
            if (n >= 3 && abs(det) > 1e-6) {
                slope = ((n * sxy - sx * sy) / det).toFloat()
                intercept = ((sy - slope * sx) / n).toFloat()
            } else {
                slope = 0f
                intercept = (sy / n).toFloat()
            }
        }
        if (abs(slope) > MAX_BASELINE_SLOPE) return null
        // Le flou et l'encre qui bave grossissent les glyphes binarisés d'un même trait en haut
        // et en bas ; le pas, lui, n'en dépend pas. Hauteur déduite du pas (proportions de la
        // police), et ligne de base remontée de la moitié de l'excédent mesuré.
        val expected = line.pitch * OcrbTemplates.REFERENCE_HEIGHT / OcrbTemplates.ADVANCE
        val spread = ((glyphHeight - expected) / 2).coerceIn(-expected * MAX_SPREAD, expected * MAX_SPREAD)
        return Geometry(intercept - spread, slope, expected)
    }

    /** Vrai si la rangée [y] compte au moins [MIN_ROW_INK] pixels d'encre entre [x0] et [x1] (bruit isolé ignoré). */
    private fun rowHasInk(
        y: Int,
        x0: Int,
        x1: Int,
    ): Boolean {
        val offset = y * width
        var count = 0
        for (x in x0..x1) {
            if (ink[offset + x].toInt() != 0 && ++count >= MIN_ROW_INK) return true
        }
        return false
    }

    /** Corrélation normalisée de la cellule centrée en [center] contre chaque modèle. */
    private fun classifyCell(
        center: Float,
        pitch: Float,
        geometry: Geometry,
    ): GlyphCandidates {
        val tw = templates.width
        val th = templates.height
        val baseline = geometry.baseIntercept + geometry.baseSlope * center
        val unit = geometry.glyphHeight / OcrbTemplates.REFERENCE_HEIGHT
        val boxTop = baseline - OcrbTemplates.REFERENCE_HEIGHT * (1 + OcrbTemplates.MARGIN) * unit
        val stepX = pitch / tw
        val stepY = OcrbTemplates.REFERENCE_HEIGHT * (1 + 2 * OcrbTemplates.MARGIN) * unit / th
        val left = center - pitch / 2
        for (j in 0 until patchHeight) {
            for (i in 0 until patchWidth) {
                val x = left + (i - SHIFT_X + 0.5f) * stepX
                val y = boxTop + (j - SHIFT_Y + 0.5f) * stepY
                val qx = stepX / 4
                val qy = stepY / 4
                val v =
                    sampleGray(x - qx, y - qy) + sampleGray(x + qx, y - qy) +
                        sampleGray(x - qx, y + qy) + sampleGray(x + qx, y + qy)
                patch[j * patchWidth + i] = 255f - v / 4
            }
        }
        scores.fill(-1f)
        val size = tw * th
        val normalized = templates.normalized
        for (oy in 0..2 * SHIFT_Y) {
            for (ox in 0..2 * SHIFT_X) {
                var sum = 0f
                var squares = 0f
                for (j in 0 until th) {
                    val offset = (j + oy) * patchWidth + ox
                    for (i in 0 until tw) {
                        val v = patch[offset + i]
                        sum += v
                        squares += v * v
                    }
                }
                val variance = squares - sum * sum / size
                if (variance < MIN_CELL_VARIANCE * size) continue
                val inverseNorm = 1f / sqrt(variance)
                for (c in scores.indices) {
                    var dot = 0f
                    var t = c * size
                    for (j in 0 until th) {
                        val offset = (j + oy) * patchWidth + ox
                        for (i in 0 until tw) dot += normalized[t++] * patch[offset + i]
                    }
                    val score = dot * inverseNorm
                    if (score > scores[c]) scores[c] = score
                }
            }
        }
        return GlyphCandidates(topCandidates())
    }

    private fun topCandidates(): List<Pair<Char, Float>> {
        val ranked = ArrayList<Pair<Char, Float>>(CANDIDATES)
        val taken = BooleanArray(scores.size)
        repeat(CANDIDATES) {
            var best = -1
            for (c in scores.indices) {
                if (!taken[c] && (best < 0 || scores[c] > scores[best])) best = c
            }
            taken[best] = true
            ranked += templates.classes[best] to scores[best].coerceIn(0f, 1f)
        }
        return ranked
    }

    private companion object {
        const val MAX_INPUT_SIZE = 4096
        const val MAX_WORK_WIDTH = 1600
        const val MAX_WORK_PIXELS = 1L shl 20
        const val MIN_WORK_WIDTH = 120
        const val MIN_WORK_HEIGHT = 16

        const val MIN_WINDOW_RADIUS = 6
        const val WINDOW_DIVISOR = 36
        const val MIN_CONTRAST = 10
        const val CONTRAST_SCALE = 100
        const val RELATIVE_CONTRAST = 12

        const val MAX_SKEW_DEGREES = 10.0
        const val COARSE_SKEW_STEP = 1.0
        const val FINE_SKEW_STEP = 0.1
        const val SKEW_COLUMN_STEP = 3
        const val MIN_SKEW_CORRECTION = 0.05
        const val MIN_SKEW_SAMPLES = 50

        const val MIN_ROW_INK_DIVISOR = 60
        val BAND_THRESHOLDS = floatArrayOf(0.45f, 0.3f, 0.18f)
        const val BAND_GAP_TOLERANCE = 1
        const val MIN_BAND_HEIGHT = 5
        const val MAX_BANDS = 256

        const val MIN_BLOB_MASS_DIVISOR = 40
        const val MIN_COLUMN_INK_DIVISOR = 12
        const val MIN_LINE_BLOBS = 16
        const val MIN_RELATIVE_BLOB_MASS = 0.2f
        const val SINGLE_WIDTH_RATIO = 1.1f
        const val MIN_PITCH_RATIO = 0.5f
        const val MAX_PITCH_RATIO = 2.0f
        const val MAX_BLOB_PITCHES = 2.2f
        const val MAX_CHAIN_GAP = 8f
        const val LENGTH_TOLERANCE = 0.06f
        const val SINGLE_PITCH_RATIO = 1.25f
        const val MAX_GRID_RESIDUAL = 0.35f
        const val GRID_ITERATIONS = 3

        const val TD1_LENGTH = 30
        const val TD2_LENGTH = 36
        const val TD3_LENGTH = 44
        val LINE_LENGTHS = intArrayOf(TD1_LENGTH, TD2_LENGTH, TD3_LENGTH)
        const val MAX_POSITIONS = TD3_LENGTH

        const val MAX_PITCH_DIFFERENCE = 0.06f
        const val MAX_ALIGNMENT_PITCHES = 1.0f
        const val MIN_LINE_SPACING = 1.0f
        const val MAX_LINE_SPACING = 3.5f
        const val MAX_SPACING_DIFFERENCE = 0.3f

        const val CELL_INK_HALF_WIDTH = 0.4f
        const val TALL_PERCENTILE = 0.8f
        const val TALL_RATIO = 0.9f
        const val MAX_BASELINE_RESIDUAL = 0.1f
        const val MAX_BASELINE_SLOPE = 0.1f
        const val BASELINE_PASSES = 3
        const val MAX_SPREAD = 0.1f
        const val MIN_ROW_INK = 2

        /** Décalage maximal, en pixels de modèle, de la cellule par rapport à la grille. */
        const val SHIFT_X = 1
        const val SHIFT_Y = 0
        const val MIN_CELL_VARIANCE = 4f
        const val CANDIDATES = 5
        const val MIN_MEAN_SCORE = 0.5f
    }
}
