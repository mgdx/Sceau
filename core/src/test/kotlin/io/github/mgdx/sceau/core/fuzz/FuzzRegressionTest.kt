package io.github.mgdx.sceau.core.fuzz

import io.github.mgdx.sceau.core.reading.DocumentReader
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

/**
 * Entrées minimales tirées des campagnes de fuzzing pour des bogues hors du périmètre du lot
 * fuzzing, désactivées jusqu'à leur correction. Les bogues corrigés ont leur test de
 * non-régression à côté du code concerné (`trust/MasterListTest`).
 */
class FuzzRegressionTest {
    /**
     * COM : EF.COM de 9 octets (sous le plafond de 1 Ko) dont la version LDS (tag 5F01) annonce
     * 2 Go. JMRTD alloue la valeur d'emblée : OutOfMemoryError, que parseComDataGroups ne
     * rattrape pas (elle ne rattrape que Exception), alors qu'EF.COM illisible doit donner un
     * ensemble vide. Hors du périmètre du lot fuzzing (reading/DocumentReader.kt).
     */
    @Ignore("Bogue connu, DocumentReader.parseComDataGroups : OutOfMemoryError non rattrapée (rapport du lot fuzzing)")
    @Test
    fun comWithHugeInnerLengthIsUnreadable() {
        val com = byteArrayOf(0x60, 0x07, 0x5F, 0x01, 0x84.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        assertEquals(emptySet<Int>(), DocumentReader.parseComDataGroups(com))
    }
}
