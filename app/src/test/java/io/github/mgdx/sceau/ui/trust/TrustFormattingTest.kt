package io.github.mgdx.sceau.ui.trust

import io.github.mgdx.sceau.core.trust.TrustSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.Locale

class TrustFormattingTest {
    @Test
    fun `lecture sous la limite`() {
        val bytes = ByteArray(100_000) { it.toByte() }
        assertArrayEquals(bytes, readAtMost(ByteArrayInputStream(bytes), 100_000))
    }

    @Test(expected = FileTooLargeException::class)
    fun `lecture au-dela de la limite refusee`() {
        readAtMost(ByteArrayInputStream(ByteArray(100_001)), 100_000)
    }

    @Test
    fun `empreinte regroupee par quatre`() {
        assertEquals("AB12 CD34 EF", formatFingerprint("ab12cd34ef"))
    }

    private fun row(
        sha: String,
        source: TrustSource,
        start: Long,
    ) = AnchorRow("CN=$sha", Instant.ofEpochSecond(start), Instant.ofEpochSecond(start + 1), sha, source, isLink = false)

    @Test
    fun `groupes par pays tries par nom localise, doublons fusionnes`() {
        val rows =
            listOf(
                "FR" to row("a", TrustSource.EMBEDDED_MASTER_LIST, 10),
                "FR" to row("b", TrustSource.ANTS, 5),
                "FR" to row("c", TrustSource.ANTS, 20),
                "FR" to row("c", TrustSource.ANTS, 20),
                "DE" to row("d", TrustSource.EMBEDDED_MASTER_LIST, 1),
                "" to row("e", TrustSource.IMPORTED_MASTER_LIST, 1),
            )
        val groups = groupRows(rows, Locale.FRENCH)
        assertEquals(listOf("DE", "FR", ""), groups.map { it.alpha2 })
        assertEquals("Allemagne", groups[0].displayName)
        assertNull(groups[2].displayName)
        // Source d'abord (ANTS avant Master List), puis le plus récent en premier.
        assertEquals(listOf("c", "b", "a"), groups[1].anchors.map { it.sha256 })
    }

    @Test
    fun `recherche par nom sans casse ni accents, par code alpha-2 ou alpha-3`() {
        val groups =
            groupRows(
                listOf(
                    "FR" to row("a", TrustSource.ANTS, 1),
                    "DE" to row("b", TrustSource.EMBEDDED_MASTER_LIST, 1),
                    "EE" to row("c", TrustSource.EMBEDDED_MASTER_LIST, 1),
                    "" to row("d", TrustSource.IMPORTED_MASTER_LIST, 1),
                ),
                Locale.FRENCH,
            )

        fun search(query: String) = filterGroups(groups, query).map { it.alpha2 }

        assertEquals(listOf("DE", "EE", "FR", ""), search("  "))
        assertEquals(listOf("DE"), search("ALLEM"))
        assertEquals(listOf("EE"), search("estonie"))
        assertEquals(listOf("EE"), search("Éston"))
        assertEquals(listOf("FR"), search("fr"))
        assertEquals(listOf("DE"), search("deu"))
        assertEquals(emptyList<String>(), search("zzz"))
    }
}
