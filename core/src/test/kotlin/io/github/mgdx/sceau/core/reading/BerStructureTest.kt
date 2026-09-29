package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.testchip.SimulatedDocuments
import org.jmrtd.lds.icao.COMFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BerStructureTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun fichiersReels_Acceptes() {
        val card = SimulatedDocuments.frenchIdCard()
        val files = card.document.dataGroups.values + card.document.sod + COMFile("1.7", "4.0.0", intArrayOf(0x61, 0x75, 0x6E)).encoded
        for (file in files) assertTrue(BerStructure.lengthsFit(file))
    }

    @Test
    fun longueurAuDelaDuTampon_Refusee() {
        // 60 07 { 5F01 : 2 Go }
        assertFalse(BerStructure.lengthsFit(hex("6007" + "5F01847FFFFFFF")))
        // Élément qui dépasse la fin du tampon.
        assertFalse(BerStructure.lengthsFit(hex("3003" + "0405AABB")))
        // Longueur externe au-delà du fichier.
        assertFalse(BerStructure.lengthsFit(hex("0405AABB")))
        // Longueur codée sur plus de quatre octets.
        assertFalse(BerStructure.lengthsFit(hex("048500000000010000")))
    }

    @Test
    fun enfantAuDelaDeSonParentRaccourci_Refuse() {
        // Un lecteur en flux ignore la longueur du parent : 60 raccourci à 3 octets, puis une
        // liste de tags (5C) de 2 Go lue quand même (fuzzing, campagne COM).
        assertFalse(BerStructure.lengthsFit(hex("6003" + "5F010430313037" + "5C847FFFFFFF")))
        // Imbrication profonde bien formée : acceptée, sans récursion.
        var nested = hex("0500")
        repeat(60) { nested = byteArrayOf(0x30, nested.size.toByte()) + nested }
        assertTrue(BerStructure.lengthsFit(nested))
    }

    @Test
    fun casLaissesAuParseur_Acceptes() {
        assertTrue(BerStructure.lengthsFit(ByteArray(0)))
        // En-tête tronqué en fin de tampon, octets 00 et FF ignorés avant une étiquette (SCUBA).
        assertTrue(BerStructure.lengthsFit(hex("30")))
        assertTrue(BerStructure.lengthsFit(hex("0401AA" + "FFFF00")))
        assertTrue(BerStructure.lengthsFit(hex("3005" + "00" + "0402AABB")))
    }

    @Test
    fun enteteQuiDiffereDeSaLectureParScuba_Refuse() {
        // Fuzzing (DG11, seed=0x5cea2026 index=35761) : SCUBA ignore FF, lit l'étiquette D3 puis
        // une longueur sur 0x44 octets ; le contrôle lit comme SCUBA.
        assertFalse(BerStructure.lengthsFit(hex("A00B" + "FFD3C4A1E9990A0E" + "535045")))
        // En-tête d'un élément tronqué par la fin de son parent, longueur indéfinie.
        assertFalse(BerStructure.lengthsFit(hex("3002" + "5F01" + "04303130")))
        assertFalse(BerStructure.lengthsFit(hex("3080" + "0400" + "0000")))
    }
}
