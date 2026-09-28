package io.github.mgdx.sceau.core.fuzz

import io.github.mgdx.sceau.core.reading.DocumentReader
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Entrées minimales tirées des campagnes de fuzzing : longueurs internes démesurées dans des
 * fichiers de la puce de quelques octets (sous leur plafond, que FileSizeLimits ne contrôle que
 * sur l'en-tête externe).
 */
class FuzzRegressionTest {
    /** Longueur BER sur quatre octets : 2 Go. */
    private val huge = byteArrayOf(0x84.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

    private fun tlv(
        tag: Int,
        value: ByteArray,
    ) = byteArrayOf(tag.toByte(), value.size.toByte()) + value

    /** 5F01 = "0107", 5F36 = "040000" : en-tête valide d'EF.COM. */
    private val comVersions =
        byteArrayOf(0x5F, 0x01, 0x04, 0x30, 0x31, 0x30, 0x37, 0x5F, 0x36, 0x06, 0x30, 0x34, 0x30, 0x30, 0x30, 0x30)

    /** Entrée d'origine de la campagne COM : la version LDS (5F01) annonce 2 Go. */
    @Test
    fun comWithHugeInnerLengthIsUnreadable() {
        val com = byteArrayOf(0x60, 0x07, 0x5F, 0x01, 0x84.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        assertEquals(emptySet<Int>(), DocumentReader.parseComDataGroups(com))
    }

    /**
     * Même bogue, par la liste de tags (5C), que JMRTD lit sans contrôler sa longueur :
     * `OutOfMemoryError` avant le contrôle des longueurs de `BerStructure`.
     */
    @Test
    fun comWithHugeTagListIsUnreadable() {
        val com = tlv(0x60, comVersions + byteArrayOf(0x5C) + huge)

        assertEquals(emptySet<Int>(), DocumentReader.parseComDataGroups(com))
    }

    /**
     * Campagne COM, seed=0x5cea2026 index=39536 (length-inflate, length-deflate, set-byte) : la
     * longueur de 60 est raccourcie, et JMRTD, qui lit en flux, lit quand même la liste de tags
     * de 2 Go qui la suit. Le contrôle suit donc le flux, pas les bornes des éléments construits.
     */
    @Test
    fun comWithShortenedOuterLengthAndHugeTagListIsUnreadable() {
        val com = byteArrayOf(0x60, 0x03) + comVersions + byteArrayOf(0x5C) + huge

        assertEquals(emptySet<Int>(), DocumentReader.parseComDataGroups(com))
    }

    /**
     * Les autres fichiers de la puce parsés par JMRTD dans reading/ passent par l'ASN1InputStream
     * de BouncyCastle, qui refuse une longueur au-delà du tampon : exception, jamais
     * `OutOfMemoryError` (EF.CardSecurity est lu par `CMSSignedData`, de même).
     */
    @Test
    fun otherChipFilesWithHugeInnerLengthRaiseNoError() {
        val octetString = byteArrayOf(0x04) + huge + byteArrayOf(0x00)
        val sequence = byteArrayOf(0x30, octetString.size.toByte()) + octetString
        val set = byteArrayOf(0x31, octetString.size.toByte()) + octetString

        assertEquals(emptySet<Int>(), DocumentReader.parseSodDataGroups(tlv(0x77, sequence)))
        assertEquals(emptySet<Int>(), DocumentReader.parseSodDataGroups(tlv(0x77, octetString)))
        exceptionAtMost { DG14File(tlv(0x6E, set).inputStream()) }
        exceptionAtMost { CardAccessFile(set.inputStream()) }
        // DG15 : JMRTD alloue la longueur externe, bornée par la lecture (16 Ko) ; l'interne est
        // décodée par BouncyCastle.
        exceptionAtMost { DG15File(tlv(0x6F, sequence).inputStream()).publicKey }
    }

    /** Exécute [block] : une exception est admise, une `Error` (OutOfMemoryError) fait échouer le test. */
    private fun exceptionAtMost(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            return
        }
    }
}
