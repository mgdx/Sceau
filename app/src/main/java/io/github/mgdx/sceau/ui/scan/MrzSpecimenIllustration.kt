package io.github.mgdx.sceau.ui.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import io.github.mgdx.sceau.R

/** Document dessiné par [MrzSpecimenIllustration]. */
enum class SpecimenKind {
    /** Page de données d'un passeport (TD3, 2 lignes de 44 caractères). */
    PASSPORT,

    /** Verso d'une carte au format ID-1 (TD1, 3 lignes de 30 caractères). */
    ID_CARD,
}

/*
 * MRZ des spécimens fictifs de la norme ICAO 9303 (État « Utopia », code UTO) : ICAO 9303-4
 * (passeport TD3) et 9303-5 (carte TD1), annexes « Utopia ». Données publiées par l'ICAO,
 * aucune donnée personnelle.
 */
private const val TD3_LINE_1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
private const val TD3_LINE_2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"
private const val TD1_LINE_1 = "I<UTOD231458907<<<<<<<<<<<<<<<"
private const val TD1_LINE_2 = "7408122F1204159UTO<<<<<<<<<<<6"
private const val TD1_LINE_3 = "ERIKSSON<<ANNA<MARIA<<<<<<<<<<"

/** Lignes de la MRZ du spécimen [kind]. */
internal fun specimenMrz(kind: SpecimenKind): List<String> =
    when (kind) {
        SpecimenKind.PASSPORT -> listOf(TD3_LINE_1, TD3_LINE_2)
        SpecimenKind.ID_CARD -> listOf(TD1_LINE_1, TD1_LINE_2, TD1_LINE_3)
    }

/** Rapport largeur / hauteur du document : page de passeport 125 × 88 mm, carte ID-1 85,6 × 54 mm. */
internal fun specimenAspect(kind: SpecimenKind): Float =
    when (kind) {
        SpecimenKind.PASSPORT -> 125f / 88f
        SpecimenKind.ID_CARD -> 85.6f / 54f
    }

/**
 * Taille de police, en pixels, d'une police à chasse fixe dont chaque caractère avance de
 * [advancePerEm] fois la taille, pour que [charCount] caractères tiennent dans [widthPx].
 * Une petite marge absorbe les arrondis du moteur de texte.
 */
internal fun monospaceFontSizePx(
    widthPx: Float,
    charCount: Int,
    advancePerEm: Float,
): Float {
    require(charCount > 0 && advancePerEm > 0f)
    return widthPx * FONT_FIT / (charCount * advancePerEm)
}

/**
 * Schéma neutre d'un document (D32) qui montre la MRZ à placer dans le cadre de visée : document
 * atténué, MRZ du spécimen ICAO encadrée comme le cadre de visée de l'écran de scan. Dessiné en
 * Compose, sans image ; TalkBack n'en lit que la description, jamais les caractères.
 */
@Composable
fun MrzSpecimenIllustration(
    kind: SpecimenKind,
    modifier: Modifier = Modifier,
) {
    val description =
        stringResource(
            when (kind) {
                SpecimenKind.PASSPORT -> R.string.scan_specimen_passport
                SpecimenKind.ID_CARD -> R.string.scan_specimen_id_card
            },
        )
    val colors = MaterialTheme.colorScheme
    val palette =
        SpecimenPalette(
            body = colors.surfaceContainerHighest.copy(alpha = BODY_ALPHA),
            edge = colors.outline,
            portrait = colors.surfaceVariant,
            faded = colors.onSurfaceVariant.copy(alpha = FADED_ALPHA),
            band = colors.surfaceContainerLowest,
            mrz = colors.onSurface,
        )
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier =
            modifier
                .aspectRatio(specimenAspect(kind))
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Image
                },
    ) {
        val docCorner = CornerRadius(size.height * DOC_CORNER)
        drawRoundRect(color = palette.body, cornerRadius = docCorner)
        drawRoundRect(color = palette.edge, cornerRadius = docCorner, style = Stroke(width = 1.dp.toPx()))
        when (kind) {
            SpecimenKind.PASSPORT -> drawPassportPage(palette)
            SpecimenKind.ID_CARD -> drawCardBack(palette)
        }
        val band = mrzBand(kind)
        drawMrz(measurer, specimenMrz(kind), band, palette)
    }
}

private class SpecimenPalette(
    val body: Color,
    val edge: Color,
    val portrait: Color,
    val faded: Color,
    val band: Color,
    val mrz: Color,
)

/** Cadre autour de la MRZ, en fractions du document. */
private fun mrzBand(kind: SpecimenKind): FractionRect =
    when (kind) {
        SpecimenKind.PASSPORT -> FractionRect(0.03f, 0.72f, 0.97f, 0.96f)
        SpecimenKind.ID_CARD -> FractionRect(0.035f, 0.57f, 0.965f, 0.95f)
    }

private class FractionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

private fun DrawScope.rectOf(f: FractionRect): Rect =
    Rect(
        f.left * size.width,
        f.top * size.height,
        f.right * size.width,
        f.bottom * size.height,
    )

/** Barres grises qui figurent des lignes de texte, à partir de [left] (fraction de la largeur). */
private fun DrawScope.textBars(
    color: Color,
    left: Float,
    rows: List<Pair<Float, Float>>,
) {
    val barHeight = size.height * BAR_HEIGHT
    rows.forEach { (top, width) ->
        drawRoundRect(
            color = color,
            topLeft = Offset(left * size.width, top * size.height),
            size = Size(width * size.width, barHeight),
            cornerRadius = CornerRadius(barHeight / 2f),
        )
    }
}

/** Page de données : bandeau de titre, portrait, champs figurés. */
private fun DrawScope.drawPassportPage(palette: SpecimenPalette) {
    textBars(palette.faded, 0.06f, listOf(0.07f to 0.5f))
    val portrait = rectOf(FractionRect(0.05f, 0.18f, 0.3f, 0.68f))
    drawRoundRect(
        color = palette.portrait,
        topLeft = portrait.topLeft,
        size = portrait.size,
        cornerRadius = CornerRadius(portrait.width * 0.06f),
    )
    // Silhouette : tête et épaules.
    val headRadius = portrait.width * 0.2f
    drawCircle(color = palette.faded, radius = headRadius, center = Offset(portrait.center.x, portrait.top + portrait.height * 0.38f))
    val shoulders = Size(portrait.width * 0.76f, portrait.height * 0.5f)
    drawArc(
        color = palette.faded,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(portrait.center.x - shoulders.width / 2f, portrait.bottom - shoulders.height / 2f),
        size = shoulders,
    )
    textBars(
        palette.faded,
        0.36f,
        listOf(0.2f to 0.34f, 0.3f to 0.5f, 0.4f to 0.28f, 0.5f to 0.42f, 0.6f to 0.3f),
    )
}

/** Verso d'une carte : champs figurés au-dessus de la MRZ. */
private fun DrawScope.drawCardBack(palette: SpecimenPalette) {
    textBars(
        palette.faded,
        0.06f,
        listOf(0.1f to 0.55f, 0.22f to 0.4f, 0.34f to 0.62f, 0.46f to 0.3f),
    )
}

/** Fond clair de la MRZ, ses lignes en chasse fixe, puis le cadre de visée autour. */
private fun DrawScope.drawMrz(
    measurer: TextMeasurer,
    lines: List<String>,
    frameFractions: FractionRect,
    palette: SpecimenPalette,
) {
    // Même couleur que le cadre de visée, épaisseur et coins mis à l'échelle de la bande.
    val frame = rectOf(frameFractions)
    val scale = frame.width / REFERENCE_VIEWFINDER_WIDTH.toPx()
    val stroke = (VIEWFINDER_STROKE.toPx() * scale).coerceAtLeast(MIN_FRAME_STROKE.toPx())
    // Un liseré du document sépare le cadre du fond de la MRZ, clair en thème clair.
    val band = frame.deflate(stroke * FRAME_GAP)
    drawRect(color = palette.band, topLeft = band.topLeft, size = band.size)

    val inset = band.width * BAND_INSET
    val textWidth = band.width - 2f * inset
    val chars = lines.maxOf { it.length }
    val pitch = (band.height - 2f * inset) / lines.size
    val sample = measurer.measure("<".repeat(SAMPLE_CHARS), style = mrzStyle(SAMPLE_FONT_PX), softWrap = false)
    val advancePerEm = sample.size.width / (SAMPLE_CHARS * SAMPLE_FONT_PX)
    val heightPerEm = sample.size.height / SAMPLE_FONT_PX
    val fontPx = minOf(monospaceFontSizePx(textWidth, chars, advancePerEm), pitch / heightPerEm)
    val style = mrzStyle(fontPx)
    lines.forEachIndexed { index, line ->
        val layout = measurer.measure(line, style = style, softWrap = false)
        val top = band.top + inset + pitch * index + (pitch - layout.size.height) / 2f
        drawText(layout, color = palette.mrz, topLeft = Offset(band.left + inset, top))
    }

    drawRoundRect(
        color = VIEWFINDER_COLOR,
        topLeft = frame.topLeft,
        size = frame.size,
        cornerRadius = CornerRadius(VIEWFINDER_CORNER.toPx() * scale),
        style = Stroke(width = stroke),
    )
}

private fun DrawScope.mrzStyle(fontPx: Float): TextStyle =
    TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = fontPx.toSp(),
        textDirection = TextDirection.Ltr,
    )

/** Largeur typique du cadre de visée (téléphone de 360 dp), pour mettre son trait à l'échelle. */
private val REFERENCE_VIEWFINDER_WIDTH = 324.dp
private val MIN_FRAME_STROKE = 1.5.dp

private const val FONT_FIT = 0.97f
private const val BODY_ALPHA = 0.8f
private const val FADED_ALPHA = 0.45f
private const val DOC_CORNER = 0.05f
private const val BAR_HEIGHT = 0.045f
private const val BAND_INSET = 0.015f
private const val FRAME_GAP = 1.5f
private const val SAMPLE_CHARS = 10
private const val SAMPLE_FONT_PX = 100f
