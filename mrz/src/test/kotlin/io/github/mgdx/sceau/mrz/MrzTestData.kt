package io.github.mgdx.sceau.mrz

/** Score du meilleur candidat dans les lignes de synthèse. */
internal const val TOP_SCORE = 0.9f

/**
 * Transforme une ligne en candidats : chaque caractère est le meilleur candidat ([TOP_SCORE]).
 * [second] ajoute, à certaines positions (comptées à partir de 0), un candidat de rang 2 et son
 * score.
 */
internal fun glyphs(
    line: String,
    second: Map<Int, Pair<Char, Float>> = emptyMap(),
): List<GlyphCandidates> =
    line.mapIndexed { i, c ->
        GlyphCandidates(listOf(c to TOP_SCORE) + listOfNotNull(second[i]))
    }

internal fun td3(
    line2: String,
    second: Map<Int, Pair<Char, Float>> = emptyMap(),
) = RecognizedMrz(MrzFormat.TD3, listOf(glyphs(line2, second)))

internal fun td2(line2: String) = RecognizedMrz(MrzFormat.TD2, listOf(glyphs(line2)))

internal fun td1(
    line1: String,
    line2: String,
) = RecognizedMrz(MrzFormat.TD1, listOf(glyphs(line1), glyphs(line2)))

/** Remplace le caractère d'indice [index] (à partir de 0). */
internal fun String.replaceAt(
    index: Int,
    c: Char,
): String = substring(0, index) + c + substring(index + 1)

private fun check(s: String): Char = '0' + CheckDigit.compute(s.toCharArray())

/** Ligne 2 de TD3 de synthèse, chiffres de contrôle calculés. */
internal fun syntheticTd3(
    number: String,
    birth: String,
    expiry: String,
    optional: String = "<<<<<<<<<<<<<<",
): String {
    val numberField = number.padEnd(9, '<')
    val optionalCheck = if (optional.all { it == '<' }) '<' else check(optional)
    val head = numberField + check(numberField) + "UTO" + birth + check(birth) + "M" + expiry + check(expiry)
    val composite = numberField + check(numberField) + birth + check(birth) + expiry + check(expiry) + optional + optionalCheck
    return head + optional + optionalCheck + check(composite)
}

/** Spécimens ICAO 9303-4 (TD3) et 9303-5 (TD1, TD2) : seules les lignes utiles. */
internal object Specimens {
    const val TD3_LINE2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"
    const val TD2_LINE2 = "D231458907UTO7408122F1204159<<<<<<<6"
    const val TD1_LINE1 = "I<UTOD231458907<<<<<<<<<<<<<<<"
    const val TD1_LINE2 = "7408122F1204159UTO<<<<<<<<<<<6"
}
