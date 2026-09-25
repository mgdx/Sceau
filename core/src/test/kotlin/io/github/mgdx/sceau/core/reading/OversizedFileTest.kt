package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.testchip.OversizedFile
import io.github.mgdx.sceau.testchip.SimulatedChip
import io.github.mgdx.sceau.testchip.SimulatedDocuments
import io.github.mgdx.sceau.testchip.SimulatedIdentityDocument
import io.github.mgdx.sceau.testchip.resigned
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Audit V11 : longueurs annoncées par une puce forgée. Un fichier dont l'en-tête dépasse son
 * plafond est refusé avant toute allocation et sans lecture massive ; un DG2 dont l'en-tête
 * d'image est corrompu donne un portrait absent sans faire échouer la lecture.
 */
class OversizedFileTest {
    private val card: SimulatedIdentityDocument by lazy { SimulatedDocuments.frenchIdCard() }

    private fun chip(
        card: SimulatedIdentityDocument = this.card,
        oversized: Map<Int, OversizedFile> = emptyMap(),
    ) = SimulatedChip(card.document, card.pace, oversizedFiles = oversized)

    /** APDU d'une lecture normale de la CNIe simulée, pour borner les lectures forgées. */
    private suspend fun baselineApdus(): Int {
        val chip = chip()
        readAndVerify(chip, checkNotNull(card.canKey), card.trustStore) {}
        return chip.apduCount
    }

    @Test
    fun sodAnnoncant2Go_TooLargeSansLectureMassive() =
        runTest {
            val baseline = baselineApdus()
            val chip = chip(oversized = mapOf(FID_SOD to OversizedFile(SOD_TAG, 0x7FFF_FFFFL)))
            try {
                readAndVerify(chip, checkNotNull(card.canKey), card.trustStore) {}
                fail("Unexpected attendue")
            } catch (e: SceauException.Unexpected) {
                assertEquals("UNEXPECTED-READ_DATA-SOD-TOO_LARGE", e.code)
            }
            assertTrue("transport fermé", chip.closed)
            // SELECT et lecture de l'en-tête seulement : bien moins qu'une lecture complète.
            assertTrue("${chip.apduCount} APDU", chip.apduCount < baseline)
        }

    @Test
    fun dg11Annoncant50Mo_AbsentEtLectureContinue() =
        runTest {
            val baseline = baselineApdus()
            val chip = chip(oversized = mapOf(FID_DG11 to OversizedFile(DG11_TAG, 50L * 1024 * 1024)))

            val report = readAndVerify(chip, checkNotNull(card.canKey), card.trustStore) {}

            assertNull(report.document.dg11)
            assertNotNull(report.document.dg12)
            assertNotNull(report.document.portrait)
            // Le vrai DG11 (quelques lectures) est remplacé par SELECT et en-tête : pas de lecture massive.
            assertTrue("${chip.apduCount} APDU pour $baseline", chip.apduCount <= baseline + 2)
        }

    @Test
    fun dg2AEnteteDImageCorrompu_PortraitAbsentVerdictInchange() =
        runTest {
            val normal = readAndVerify(chip(), checkNotNull(card.canKey), card.trustStore) {}
            assertEquals(Verdict.AUTHENTIC, normal.verdict)

            val corrupted = card.withDataGroup2(corruptImageLength(card.document.dataGroups.getValue(2)))
            val report = readAndVerify(chip(corrupted), checkNotNull(corrupted.canKey), corrupted.trustStore) {}

            assertNull(report.document.portrait)
            assertEquals(normal.verdict, report.verdict)
            assertEquals(normal.checks.map { it.id to it.status }, report.checks.map { it.id to it.status })
        }

    @Test
    fun parseurDg2_EnteteDImageCorrompu_Null() {
        val dg2 = corruptImageLength(card.document.dataGroups.getValue(2))
        val data = DataGroupParsers.document(card.document.dataGroups + (2 to dg2))
        assertNull(data.portrait)
    }

    @Test
    fun enTeteTlv_TailleAnnoncee() {
        assertEquals(2L + 0x10, FileSizeLimits.announcedSize(byteArrayOf(0x61, 0x10)))
        assertEquals(4L + 0x0123, FileSizeLimits.announcedSize(byteArrayOf(0x77, 0x82.toByte(), 0x01, 0x23)))
        assertEquals(6L + 0x7FFF_FFFF, FileSizeLimits.announcedSize(byteArrayOf(0x77, 0x84.toByte(), 0x7F, -1, -1, -1)))
        assertEquals(Long.MAX_VALUE, FileSizeLimits.announcedSize(byteArrayOf(0x77, 0x85.toByte(), 1, 0, 0, 0, 0)))
        // Tag sur deux octets (7F61…) et en-têtes inexploitables.
        assertEquals(3L + 5, FileSizeLimits.announcedSize(byteArrayOf(0x7F, 0x61, 0x05)))
        assertNull(FileSizeLimits.announcedSize(byteArrayOf(0x77, 0x82.toByte(), 0x01)))
        assertNull(FileSizeLimits.announcedSize(byteArrayOf(0x77, 0x80.toByte())))
        assertNull(FileSizeLimits.announcedSize(ByteArray(0)))
    }

    /** Même CNIe, DG2 remplacé et SOD signé à nouveau : seul le contenu de DG2 diffère. */
    private fun SimulatedIdentityDocument.withDataGroup2(dg2: ByteArray): SimulatedIdentityDocument =
        SimulatedIdentityDocument(document.resigned(document.dataGroups + (2 to dg2)), pace, documentNumber, dateOfBirth)

    /**
     * DG2 ISO 19794-5 dont la longueur du bloc de données faciales (4 octets suivant l'en-tête
     * « FAC\0 », version, longueur d'enregistrement et nombre de visages) annonce près de 2 Go.
     */
    private fun corruptImageLength(dg2: ByteArray): ByteArray {
        val magic = byteArrayOf('F'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), 0)
        val start = (0..dg2.size - magic.size).first { i -> magic.indices.all { dg2[i + it] == magic[it] } }
        val blockLength = start + FACE_HEADER_LENGTH
        return dg2.copyOf().also { bytes ->
            byteArrayOf(0x7F, -1, -1, 0x00).copyInto(bytes, blockLength)
        }
    }

    private companion object {
        const val FID_SOD = 0x011D
        const val FID_DG11 = 0x010B
        const val SOD_TAG = 0x77
        const val DG11_TAG = 0x6B

        /** « FAC\0 » (4), version (4), longueur d'enregistrement (4), nombre de visages (2). */
        const val FACE_HEADER_LENGTH = 14
    }
}
