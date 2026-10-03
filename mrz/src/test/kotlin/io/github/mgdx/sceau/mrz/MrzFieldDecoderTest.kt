package io.github.mgdx.sceau.mrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MrzFieldDecoderTest {
    private var now = 0L
    private val decoder = MrzFieldDecoder { now }

    private val specimenTd3 = MrzKeyFields(MrzFormat.TD3, "L898902C3", "740812", "120415")

    /** Deux appels successifs : le second doit confirmer. */
    private fun confirmed(recognized: RecognizedMrz): MrzKeyFields? {
        decoder.reset()
        val first = decoder.accept(recognized)
        if (first !== MrzScanResult.Unstable) return null
        return (decoder.accept(recognized) as? MrzScanResult.Found)?.fields
    }

    private fun assertRefused(recognized: RecognizedMrz) {
        decoder.reset()
        repeat(3) { assertSame(MrzScanResult.Unstable, decoder.accept(recognized)) }
    }

    // --- Spécimens ---

    @Test
    fun `specimen TD3`() {
        assertEquals(specimenTd3, confirmed(td3(Specimens.TD3_LINE2)))
    }

    @Test
    fun `specimen TD2`() {
        assertEquals(MrzKeyFields(MrzFormat.TD2, "D23145890", "740812", "120415"), confirmed(td2(Specimens.TD2_LINE2)))
    }

    @Test
    fun `specimen TD1`() {
        assertEquals(
            MrzKeyFields(MrzFormat.TD1, "D23145890", "740812", "120415"),
            confirmed(td1(Specimens.TD1_LINE1, Specimens.TD1_LINE2)),
        )
    }

    @Test
    fun `numero court sans remplissage`() {
        val line = syntheticTd3("AB12", "760229", "300101", "X12<<<<<<<<<<<")
        assertEquals(MrzKeyFields(MrzFormat.TD3, "AB12", "760229", "300101"), confirmed(td3(line)))
    }

    // --- Corrections selon le type de champ ---

    @Test
    fun `chaque confusion est corrigee sur une position numerique`() {
        val line = Specimens.TD3_LINE2
        // Positions numériques du spécimen TD3 (à partir de 0) : 9 contrôle, 13-19 naissance, 21-27 expiration.
        val numericPositions = listOf(9) + (13..19) + (21..27)
        val confusions = mapOf('O' to '0', 'D' to '0', 'Q' to '0', 'I' to '1', 'L' to '1', 'Z' to '2', 'S' to '5', 'G' to '6', 'B' to '8')
        for ((letter, digit) in confusions) {
            val position = numericPositions.first { line[it] == digit }
            assertEquals("$letter", specimenTd3, confirmed(td3(line.replaceAt(position, letter))))
        }
    }

    @Test
    fun `plusieurs confusions dans une meme date`() {
        assertEquals(
            specimenTd3,
            confirmed(
                td3(
                    Specimens.TD3_LINE2
                        .replaceAt(15, 'O')
                        .replaceAt(16, 'B')
                        .replaceAt(18, 'Z'),
                ),
            ),
        )
    }

    @Test
    fun `confusion lettre chiffre explorée dans le numero`() {
        // L898902C3 : '0' lu 'O' ; une seule correction simple satisfait le contrôle.
        assertEquals(specimenTd3, confirmed(td3(Specimens.TD3_LINE2.replaceAt(5, 'O'))))
        // '8' lu 'B' ou '2' lu 'Z' : deux corrections simples différentes satisfont le contrôle
        // (LB9890ZC3 et L898902C3 ont le même chiffre de contrôle), on ne devine pas…
        assertRefused(td3(Specimens.TD3_LINE2.replaceAt(1, 'B')))
        assertRefused(td3(Specimens.TD3_LINE2.replaceAt(6, 'Z')))
        // … sauf si le classement désigne la bonne lecture en rang 2.
        assertEquals(specimenTd3, confirmed(td3(Specimens.TD3_LINE2.replaceAt(1, 'B'), mapOf(1 to ('8' to TOP_SCORE - 0.1f)))))
        assertEquals(specimenTd3, confirmed(td3(Specimens.TD3_LINE2.replaceAt(6, 'Z'), mapOf(6 to ('2' to TOP_SCORE - 0.1f)))))
        // Limite des chiffres de contrôle : 'L' (21) et '1' ont le même poids modulo 10 en tête
        // de champ, la lecture fausse 1898902C3 passe tous les contrôles. L'utilisateur relit.
        assertEquals("1898902C3", confirmed(td3(Specimens.TD3_LINE2.replaceAt(0, '1')))?.documentNumber)
    }

    @Test
    fun `nationalite corrigee vers une lettre et refusee si impossible`() {
        assertEquals(specimenTd3, confirmed(td3(Specimens.TD3_LINE2.replaceAt(12, '0'))))
        assertRefused(td3(Specimens.TD3_LINE2.replaceAt(11, '7')))
        assertRefused(td2(Specimens.TD2_LINE2.replaceAt(10, '4')))
        assertRefused(td1(Specimens.TD1_LINE1, Specimens.TD1_LINE2.replaceAt(16, '3')))
    }

    @Test
    fun `lettre impossible sur une position numerique`() {
        assertRefused(td3(Specimens.TD3_LINE2.replaceAt(14, 'X')))
    }

    // --- Candidat de rang 2 ---

    @Test
    fun `candidat de rang 2 proche retenu quand les controles l'exigent`() {
        // Naissance 740812 : le '8' (indice 16) lu '3', avec '8' en rang 2 à faible écart.
        val misread = Specimens.TD3_LINE2.replaceAt(16, '3')
        assertEquals(specimenTd3, confirmed(td3(misread, mapOf(16 to ('8' to TOP_SCORE - 0.05f)))))
        // Numéro : 'C' (indice 7) lu 'G', 'C' en rang 2.
        val number = Specimens.TD3_LINE2.replaceAt(7, 'G')
        assertEquals(specimenTd3, confirmed(td3(number, mapOf(7 to ('C' to TOP_SCORE - 0.1f)))))
    }

    @Test
    fun `candidat de rang 2 trop eloigne ignore`() {
        val misread = Specimens.TD3_LINE2.replaceAt(16, '3')
        assertRefused(td3(misread, mapOf(16 to ('8' to TOP_SCORE - 0.5f))))
    }

    @Test
    fun `candidat de rang 2 utilise quand le premier est impossible pour le type`() {
        val misread = Specimens.TD3_LINE2.replaceAt(16, 'X')
        assertEquals(specimenTd3, confirmed(td3(misread, mapOf(16 to ('8' to 0.1f)))))
    }

    @Test
    fun `combinaison la plus probable choisie`() {
        // Rang 2 plausible mais contrôles faux : la lecture de rang 1 reste.
        assertEquals(specimenTd3, confirmed(td3(Specimens.TD3_LINE2, mapOf(14 to ('8' to TOP_SCORE - 0.01f)))))
    }

    // --- Contrôles ---

    @Test
    fun `controle de champ faux refuse`() {
        assertRefused(td3(Specimens.TD3_LINE2.replaceAt(9, '7')))
        assertRefused(td3(Specimens.TD3_LINE2.replaceAt(19, '3')))
        assertRefused(td3(Specimens.TD3_LINE2.replaceAt(27, '8')))
        assertRefused(td3(Specimens.TD3_LINE2.replaceAt(42, '2')))
        assertRefused(td2(Specimens.TD2_LINE2.replaceAt(9, '1')))
        assertRefused(td1(Specimens.TD1_LINE1.replaceAt(14, '1'), Specimens.TD1_LINE2))
        assertRefused(td1(Specimens.TD1_LINE1, Specimens.TD1_LINE2.replaceAt(6, '1')))
    }

    @Test
    fun `chiffre errone dans un champ refuse`() {
        // Numéro sans caractère confondable : aucune correction ne peut compenser l'erreur.
        assertRefused(td3(syntheticTd3("XK47XK47X", "740812", "300101").replaceAt(2, '7')))
        assertRefused(td3(Specimens.TD3_LINE2.replaceAt(15, '5')))
    }

    @Test
    fun `composite faux refuse`() {
        assertRefused(td3(Specimens.TD3_LINE2.replaceAt(43, '1')))
        assertRefused(td2(Specimens.TD2_LINE2.replaceAt(35, '7')))
        assertRefused(td1(Specimens.TD1_LINE1, Specimens.TD1_LINE2.replaceAt(29, '7')))
        // Donnée facultative modifiée : seul le composite la couvre en TD2 et TD1.
        assertRefused(td2(Specimens.TD2_LINE2.replaceAt(30, 'C')))
        assertRefused(td1(Specimens.TD1_LINE1.replaceAt(20, 'C'), Specimens.TD1_LINE2))
        assertRefused(td1(Specimens.TD1_LINE1, Specimens.TD1_LINE2.replaceAt(20, 'C')))
    }

    @Test
    fun `dates impossibles refusees`() {
        for (date in listOf("741312", "740001", "740230", "750229", "740431", "740100", "740132", "010229")) {
            assertRefused(td3(syntheticTd3("AB1234567", date, "300101")))
            assertRefused(td3(syntheticTd3("AB1234567", "740812", date)))
        }
    }

    @Test
    fun `29 fevrier accepte les annees multiples de 4`() {
        for (date in listOf("000229", "760229", "240229")) {
            assertEquals(date, confirmed(td3(syntheticTd3("AB1234567", date, "300101")))?.dateOfBirth)
        }
    }

    @Test
    fun `numero invalide refuse`() {
        assertRefused(td3(syntheticTd3("<<<<<<<<<", "740812", "300101")))
        assertRefused(td3(syntheticTd3("AB<12", "740812", "300101")))
    }

    // --- Numéro étendu ---

    @Test
    fun `numero etendu TD1`() {
        val line1 = "I<UTOD23145890<AB112<<<<<<<<<<"
        decoder.reset()
        assertSame(MrzScanResult.UnsupportedDocumentNumber, decoder.accept(td1(line1, Specimens.TD1_LINE2)))
        assertSame(MrzScanResult.UnsupportedDocumentNumber, decoder.accept(td1(line1, Specimens.TD1_LINE2)))
        // Dates illisibles : pas de diagnostic de numéro étendu.
        assertSame(MrzScanResult.Unstable, decoder.accept(td1(line1, Specimens.TD1_LINE2.replaceAt(6, '0'))))
    }

    // --- Stabilisation ---

    @Test
    fun `stabilisation sur deux appels`() {
        val recognized = td3(Specimens.TD3_LINE2)
        assertSame(MrzScanResult.Unstable, decoder.accept(recognized))
        assertEquals(specimenTd3, (decoder.accept(recognized) as MrzScanResult.Found).fields)
        // Après un Found, un nouvel appel identique redonne Found.
        assertTrue(decoder.accept(recognized) is MrzScanResult.Found)
    }

    @Test
    fun `resultat different redemande une confirmation`() {
        val other = td3(syntheticTd3("AB1234567", "740812", "300101"))
        assertSame(MrzScanResult.Unstable, decoder.accept(td3(Specimens.TD3_LINE2)))
        assertSame(MrzScanResult.Unstable, decoder.accept(other))
        assertTrue(decoder.accept(other) is MrzScanResult.Found)
        assertSame(MrzScanResult.Unstable, decoder.accept(td3(Specimens.TD3_LINE2)))
    }

    @Test
    fun `reset oublie le resultat en attente`() {
        val recognized = td3(Specimens.TD3_LINE2)
        assertSame(MrzScanResult.Unstable, decoder.accept(recognized))
        decoder.reset()
        assertSame(MrzScanResult.Unstable, decoder.accept(recognized))
        assertTrue(decoder.accept(recognized) is MrzScanResult.Found)
    }

    @Test
    fun `image sans MRZ`() {
        assertSame(MrzScanResult.NothingFound, decoder.accept(null))
    }

    @Test
    fun `attente oubliee apres 2 s sans MRZ`() {
        val recognized = td3(Specimens.TD3_LINE2)
        assertSame(MrzScanResult.Unstable, decoder.accept(recognized))
        now += 1_000_000_000L
        assertSame(MrzScanResult.NothingFound, decoder.accept(null))
        now += 500_000_000L
        assertTrue(decoder.accept(recognized) is MrzScanResult.Found)

        decoder.reset()
        assertSame(MrzScanResult.Unstable, decoder.accept(recognized))
        now += 2_100_000_000L
        assertSame(MrzScanResult.NothingFound, decoder.accept(null))
        assertSame(MrzScanResult.Unstable, decoder.accept(recognized))
        assertTrue(decoder.accept(recognized) is MrzScanResult.Found)
    }

    @Test
    fun `image aux controles faux n'interrompt pas l'attente`() {
        val recognized = td3(Specimens.TD3_LINE2)
        assertSame(MrzScanResult.Unstable, decoder.accept(recognized))
        assertSame(MrzScanResult.Unstable, decoder.accept(td3(Specimens.TD3_LINE2.replaceAt(43, '1'))))
        assertTrue(decoder.accept(recognized) is MrzScanResult.Found)
    }

    // --- Entrées incohérentes ---

    @Test
    fun `entrees incoherentes sans exception`() {
        val inputs =
            listOf(
                RecognizedMrz(MrzFormat.TD3, emptyList()),
                RecognizedMrz(MrzFormat.TD3, listOf(glyphs(Specimens.TD3_LINE2), glyphs(Specimens.TD3_LINE2))),
                RecognizedMrz(MrzFormat.TD3, listOf(glyphs(Specimens.TD2_LINE2))),
                RecognizedMrz(MrzFormat.TD2, listOf(glyphs(Specimens.TD3_LINE2))),
                RecognizedMrz(MrzFormat.TD1, listOf(glyphs(Specimens.TD1_LINE1))),
                RecognizedMrz(MrzFormat.TD1, listOf(glyphs(Specimens.TD1_LINE1), glyphs(Specimens.TD3_LINE2))),
                RecognizedMrz(
                    MrzFormat.TD3,
                    listOf(
                        glyphs(Specimens.TD3_LINE2).mapIndexed { i, g ->
                            if (i ==
                                5
                            ) {
                                GlyphCandidates(emptyList())
                            } else {
                                g
                            }
                        },
                    ),
                ),
                RecognizedMrz(MrzFormat.TD3, listOf(glyphs(Specimens.TD3_LINE2.lowercase()))),
                RecognizedMrz(MrzFormat.TD3, listOf(glyphs(Specimens.TD3_LINE2.replaceAt(3, '#')))),
                RecognizedMrz(MrzFormat.TD3, listOf(glyphs(Specimens.TD3_LINE2.replace('<', '\u0000')))),
                RecognizedMrz(MrzFormat.TD1, listOf(glyphs("<".repeat(30)), glyphs("<".repeat(30)))),
                RecognizedMrz(MrzFormat.TD3, listOf(glyphs("Z".repeat(44)))),
            )
        for (input in inputs) {
            decoder.reset()
            repeat(2) {
                val result = decoder.accept(input)
                assertTrue(result === MrzScanResult.Unstable || result === MrzScanResult.NothingFound)
            }
        }
    }

    @Test
    fun `scores non finis sans exception`() {
        val line =
            Specimens.TD3_LINE2.map { c -> GlyphCandidates(listOf(c to Float.NaN, '8' to Float.NEGATIVE_INFINITY)) }
        val recognized = RecognizedMrz(MrzFormat.TD3, listOf(line))
        decoder.accept(recognized)
        assertEquals(specimenTd3, (decoder.accept(recognized) as? MrzScanResult.Found)?.fields)
    }

    @Test
    fun `MRZ ambigue partout reste bornee`() {
        val line = Specimens.TD3_LINE2.map { c -> GlyphCandidates(listOf(c to 0.5f, '8' to 0.49f, 'B' to 0.48f)) }
        val start = System.nanoTime()
        decoder.accept(RecognizedMrz(MrzFormat.TD3, listOf(line)))
        if (System.nanoTime() - start > 5_000_000_000L) fail("decodage trop long")
    }

    // --- Hygiène ---

    @Test
    fun `toString ne revele aucun caractere`() {
        val recognized = td3(Specimens.TD3_LINE2)
        decoder.accept(recognized)
        val found = decoder.accept(recognized) as MrzScanResult.Found
        val texts =
            listOf(
                found.toString(),
                found.fields.toString(),
                decoder.toString(),
                MrzDecoding.decode(recognized).toString(),
                FieldCandidate("L898902C36".toCharArray(), 0f).toString(),
                MrzDecoding.optionsFor(GlyphCandidates(listOf('L' to 1f)), PositionKind.ALNUM).toString(),
            )
        for (text in texts) {
            for (secret in listOf("898", "740812", "120415", "UTO", "ZE18")) {
                assertFalse(text, text.contains(secret))
            }
            assertFalse(text, Regex("[0-9]{3,}").containsMatchIn(text))
        }
    }
}
