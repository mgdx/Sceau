package io.github.mgdx.sceau.mrz

/** Classes candidates pour une position d'une ligne, triées par score décroissant (meilleur en tête). */
internal class GlyphCandidates(
    val ranked: List<Pair<Char, Float>>,
)

/**
 * Traitement d'image (lot B) : localise la MRZ dans [LumaFrame] et rend les lignes utiles à
 * la clé d'accès, chaque ligne étant la liste des candidats de chacune de ses positions.
 *
 * Contrat :
 * - `null` si aucune MRZ n'est localisée ;
 * - sinon un [RecognizedMrz] dont [RecognizedMrz.format] est déduit du nombre de lignes et de
 *   leur longueur, et dont [RecognizedMrz.lines] ne contient que les lignes utiles : en TD3 et
 *   TD2 la ligne 2 seule (44 ou 36 positions), en TD1 les lignes 1 et 2 (30 positions chacune).
 *   La ligne du nom n'est jamais classée.
 */
internal fun interface LineRecognizer {
    fun recognize(frame: LumaFrame): RecognizedMrz?
}

internal class RecognizedMrz(
    val format: MrzFormat,
    val lines: List<List<GlyphCandidates>>,
)

/** Implémentation du lot B. */
internal class TemplateLineRecognizer : LineRecognizer {
    override fun recognize(frame: LumaFrame): RecognizedMrz? = null
}

/**
 * Décodage des champs (lot A) : correction selon le type de champ, chiffres de contrôle,
 * stabilisation sur deux images successives. `null` en entrée = aucune MRZ dans l'image.
 */
internal class MrzFieldDecoder(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** Dernier résultat aux contrôles justes, en attente de confirmation. */
    private var pending: MrzKeyFields? = null
    private var lastMrzSeenNanos = 0L

    /**
     * `Found` quand deux décodages aux contrôles justes successifs sont identiques. Les images
     * sans MRZ ou aux contrôles faux ne rompent pas l'attente, mais elle est oubliée après
     * [FORGET_AFTER_NANOS] sans MRZ et sur [reset].
     */
    fun accept(recognized: RecognizedMrz?): MrzScanResult {
        val now = nanoTime()
        if (pending != null && now - lastMrzSeenNanos > FORGET_AFTER_NANOS) pending = null
        if (recognized == null) return MrzScanResult.NothingFound
        lastMrzSeenNanos = now
        return when (val decoded = MrzDecoding.decode(recognized)) {
            Decoded.Invalid -> {
                MrzScanResult.Unstable
            }

            Decoded.ExtendedNumber -> {
                pending = null
                MrzScanResult.UnsupportedDocumentNumber
            }

            is Decoded.Valid -> {
                if (decoded.fields == pending) {
                    MrzScanResult.Found(decoded.fields)
                } else {
                    pending = decoded.fields
                    MrzScanResult.Unstable
                }
            }
        }
    }

    fun reset() {
        pending = null
    }

    override fun toString(): String = "MrzFieldDecoder"

    companion object {
        const val FORGET_AFTER_NANOS = 2_000_000_000L
    }
}
