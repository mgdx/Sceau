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
internal class MrzFieldDecoder {
    fun accept(recognized: RecognizedMrz?): MrzScanResult = MrzScanResult.NothingFound

    fun reset() {}
}
