package io.github.mgdx.sceau.core.fuzz

import io.github.mgdx.sceau.core.reading.DataGroupParsers
import io.github.mgdx.sceau.core.reading.DocumentReader
import io.github.mgdx.sceau.testchip.SimulatedDocuments
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

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
     * DG11 et DG12 : JMRTD lit chaque champ (et les images de DG12) à la longueur annoncée ; un
     * champ de 2 Go dans un DG de quelques octets donnait `OutOfMemoryError`, rattrapée
     * seulement par le filet de `DataGroupParsers.orNull`. Refusé désormais avant JMRTD.
     */
    @Test
    fun dg11AndDg12WithHugeFieldAreUnreadable() {
        val cases =
            listOf<() -> Unit>(
                { DataGroupParsers.parseDg11(hex("6B075C") + huge, TODAY) },
                { DataGroupParsers.parseDg11(hex("6B0D5C025F0E5F0E") + huge, TODAY) },
                { DataGroupParsers.parseDg12(hex("6C0D5C025F195F19") + huge, TODAY) },
                { DataGroupParsers.parseDg12(hex("6C0D5C025F1D5F1D") + huge, TODAY) },
            )
        for (case in cases) assertIllegalArgument(case)
    }

    /**
     * Campagne DG11, seed=0x5cea2026 index=35761 (unexpected-tag, set-byte) : dans la liste des
     * autres noms (A0), SCUBA ignore l'octet FF, lit l'étiquette D3 puis une longueur codée sur
     * 0x44 octets, qui déborde en une valeur démesurée. Le contrôle lit les en-têtes comme SCUBA.
     */
    @Test
    fun dg11WithPaddedTagIsUnreadable() {
        val dg11 =
            hex(
                "6B81F05C1A5F0E5F0F5F105F2B5F115F425F125F135F145F155F165F175F185F0E1453504543494D454E3C3C414E4E413C4D41524941" +
                    "A019020101FFD3C4A1E9990A0E53504543494D454E3C3C414E4E455F10093132333435363738395F2B083139373430383132" +
                    "5F110C55544F5049413C56494C4C455F421A312052554520464943544956453C39393939392055544F5049415F120B2B3030" +
                    "20303030303030305F130854455354455553455F140244525F150D524553554DFD204649435449465F1610000102030405060708" +
                    "090A0B0C0D0E0F5F17124C38393839303243333C58303030303030305F1806415543554E45",
            )

        assertIllegalArgument { DataGroupParsers.parseDg11(dg11, TODAY) }
    }

    /**
     * DG2 : bloc d'image de visage ISO 19794-5 dont la longueur (4 octets après l'en-tête
     * « FAC\0 ») annonce 2 Go dans un DG2 réel : `OutOfMemoryError` dans JMRTD, rattrapée
     * seulement par `orNull`. Refusé désormais avant JMRTD.
     */
    @Test
    fun dg2WithHugeFaceImageBlockIsUnreadable() {
        val dg2 =
            SimulatedDocuments
                .frenchIdCard()
                .document.dataGroups
                .getValue(2)
                .copyOf()
        val blockLength = String(dg2, Charsets.ISO_8859_1).indexOf("FAC\u0000") + FACE_HEADER
        byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xF0.toByte()).copyInto(dg2, blockLength)

        assertIllegalArgument { DataGroupParsers.parseDg2(dg2) }
        assertNull(DataGroupParsers.document(mapOf(1 to FuzzSeeds.dataGroup(1).first(), 2 to dg2), TODAY).portrait)
    }

    /** DG1 et les autres longueurs BER de DG2 : JMRTD lève déjà une exception, sans allocation. */
    @Test
    fun dg1AndDg2WithHugeBerLengthRaiseNoError() {
        exceptionAtMost { DataGroupParsers.parseDg1(hex("610A5F1F") + huge + hex("0000"), TODAY) }
        exceptionAtMost { DataGroupParsers.parseDg2(tlv(0x75, hex("7F61") + huge)) }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("IllegalArgumentException attendue")
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

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

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 29)

        /** En-tête ISO 19794-5 jusqu'à la longueur du premier bloc d'image. */
        const val FACE_HEADER = 14
    }
}
