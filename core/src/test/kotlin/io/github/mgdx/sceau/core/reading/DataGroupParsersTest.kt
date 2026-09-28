package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.model.ImageFormat
import io.github.mgdx.sceau.core.model.Sex
import io.github.mgdx.sceau.core.reading.TestFixtures.TODAY
import net.sf.scuba.data.Gender
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.iso19794.FaceImageInfo
import org.jmrtd.lds.iso19794.FaceInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Conversion des DG construits avec JMRTD vers les modèles de `:core` (personnes fictives). */
class DataGroupParsersTest {
    @Test
    fun dg1Passeport() {
        val dg1 = DataGroupParsers.parseDg1(TestFixtures.dg1(), TODAY)
        assertEquals("P", dg1.documentCode)
        assertEquals("FRA", dg1.issuingState)
        assertEquals("12AB34567", dg1.documentNumber)
        assertEquals("MARTIN", dg1.primaryIdentifier)
        assertEquals(listOf("CLAIRE", "ANNE"), dg1.secondaryIdentifiers)
        assertEquals("FRA", dg1.nationality)
        assertEquals(LocalDate.of(1965, 2, 12), dg1.dateOfBirth)
        assertEquals(Sex.FEMALE, dg1.sex)
        assertEquals(LocalDate.of(2030, 12, 31), dg1.dateOfExpiry)
        assertNull(dg1.optionalData)
        assertEquals("Dg1Data(***)", dg1.toString())
    }

    @Test
    fun dg1CarteIdentite_SiecleDeNaissance() {
        val born2010 = DataGroupParsers.parseDg1(TestFixtures.dg1(TestFixtures.td1Mrz("100212")), TODAY)
        assertEquals("ID", born2010.documentCode)
        assertEquals("X4RTBPFW4", born2010.documentNumber)
        assertEquals("DUPONT", born2010.primaryIdentifier)
        assertEquals(listOf("JEAN", "PIERRE"), born2010.secondaryIdentifiers)
        assertEquals(Sex.MALE, born2010.sex)
        assertEquals(LocalDate.of(2010, 2, 12), born2010.dateOfBirth)
        assertEquals(LocalDate.of(2030, 12, 31), born2010.dateOfExpiry)

        // 2030 serait dans le futur : naissance en 1930.
        val born1930 = DataGroupParsers.parseDg1(TestFixtures.dg1(TestFixtures.td1Mrz("300101")), TODAY)
        assertEquals(LocalDate.of(1930, 1, 1), born1930.dateOfBirth)
    }

    @Test
    fun datesMrz() {
        assertEquals(LocalDate.of(2026, 9, 25), Dates.parsePast("260925", TODAY))
        assertEquals(LocalDate.of(1926, 9, 26), Dates.parsePast("260926", TODAY))
        assertEquals(LocalDate.of(2015, 3, 21), Dates.parsePast("20150321", TODAY))
        assertNull(Dates.parsePast("65<<12", TODAY))
        assertNull(Dates.parsePast("651312", TODAY))
        assertNull(Dates.parsePast(null, TODAY))
        assertEquals(LocalDate.of(2099, 1, 1), Dates.parseExpiry("990101"))
        assertNull(Dates.parseExpiry("2030123"))
    }

    @Test
    fun dg2_PremiereImageDuVisage() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
        val face =
            FaceImageInfo(
                Gender.UNSPECIFIED,
                FaceImageInfo.EyeColor.UNSPECIFIED,
                0,
                0,
                0,
                intArrayOf(0, 0, 0),
                intArrayOf(0, 0, 0),
                FaceImageInfo.FACE_IMAGE_TYPE_FULL_FRONTAL,
                FaceImageInfo.IMAGE_COLOR_SPACE_RGB24,
                FaceImageInfo.SOURCE_TYPE_STATIC_PHOTO_DIGITAL_CAM,
                0,
                0,
                emptyArray(),
                2,
                2,
                jpeg.inputStream(),
                jpeg.size,
                FaceImageInfo.IMAGE_DATA_TYPE_JPEG,
            )
        val dg2 = DG2File.createISO19794DG2File(listOf(FaceInfo(listOf(face)))).encoded

        val portrait = DataGroupParsers.parseDg2(dg2)

        assertNotNull(portrait)
        assertEquals(ImageFormat.JPEG, portrait!!.format)
        assertArrayEquals(jpeg, portrait.bytes)
    }

    @Test
    fun formatsImage() {
        assertEquals(ImageFormat.JPEG2000, DataGroupParsers.formatOfMime("image/jp2"))
        assertEquals(ImageFormat.JPEG2000, DataGroupParsers.formatOfMime("image/jpeg2000"))
        assertEquals(ImageFormat.JPEG, DataGroupParsers.formatOfMime("image/jpeg"))
        assertEquals(ImageFormat.UNKNOWN, DataGroupParsers.formatOfMime("image/x-wsq"))
        assertEquals(ImageFormat.JPEG2000, DataGroupParsers.sniffFormat(byteArrayOf(0, 0, 0, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D)))
        assertEquals(ImageFormat.UNKNOWN, DataGroupParsers.sniffFormat(byteArrayOf(1)))
    }

    @Test
    fun dg11_ChampsVidesAbsents() {
        val bytes =
            DG11File(
                "DUPONT<<JEAN<PIERRE",
                null,
                "1234567890",
                "19650212",
                listOf("PARIS", ""),
                null,
                null,
                "",
                null,
                null,
                null,
                null,
                null,
            ).encoded

        val dg11 = DataGroupParsers.parseDg11(bytes, TODAY)

        assertEquals("DUPONT, JEAN PIERRE", dg11.fullName)
        assertEquals("1234567890", dg11.personalNumber)
        assertEquals(LocalDate.of(1965, 2, 12), dg11.fullDateOfBirth)
        assertEquals(listOf("PARIS"), dg11.placeOfBirth)
        assertTrue(dg11.otherNames.isEmpty())
        assertTrue(dg11.address.isEmpty())
        assertNull(dg11.telephone)
        assertNull(dg11.profession)
        assertNull(dg11.title)
        assertNull(dg11.custodyInformation)
    }

    @Test
    fun dg12_DateDeDelivrance() {
        val front = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
        val full = DG12File("PREFECTURE DE TEST", "20210315", null, null, null, front, null, null, null).encoded
        val dg12 = DataGroupParsers.parseDg12(full, TODAY)
        assertEquals("PREFECTURE DE TEST", dg12.issuingAuthority)
        assertEquals(LocalDate.of(2021, 3, 15), dg12.dateOfIssue)
        assertEquals(ImageFormat.JPEG, dg12.frontImage?.format)
        assertNull(dg12.rearImage)
        assertTrue(dg12.namesOfOtherPersons.isEmpty())
        assertNull(dg12.endorsementsAndObservations)

        val short = DG12File(null, "210315", null, null, null, null, null, null, null).encoded
        assertEquals(LocalDate.of(2021, 3, 15), DataGroupParsers.parseDg12(short, TODAY).dateOfIssue)
    }

    @Test
    fun document_Dg11IllisibleAbsent() {
        val raw = mapOf(1 to TestFixtures.dg1(), 2 to TestFixtures.opaqueDataGroup(2), 11 to TestFixtures.opaqueDataGroup(11))
        val document = DataGroupParsers.document(raw, TODAY)
        assertEquals("MARTIN", document.dg1.primaryIdentifier)
        assertNull(document.portrait)
        assertNull(document.dg11)
        assertNull(document.dg12)
        assertEquals(raw.keys, document.rawDataGroups.keys)
    }

    /**
     * Fuzzing (DG2) : enregistrement de visage ISO 19794-5 annonçant un point caractéristique
     * mais qui s'arrête avant ses deux octets réservés. JMRTD les saute par
     * `while (skipped < 2) skipped += in.skip(2)` : `skip` renvoie 0 en fin de flux et la
     * lecture bouclait sans fin. Le DG2 doit être illisible, donc absent.
     */
    @Test(timeout = 10_000)
    fun dg2_PointCaracteristiqueTronque_PasDeBoucleSansFin() {
        val faceImage =
            hex("0000001A" + "0001" + "00" + "00" + "00" + "000000" + "0000" + "000000" + "000000") +
                hex("01" + "01" + "0000" + "0000")
        val facialRecord = "FAC\u0000".toByteArray() + "010\u0000".toByteArray() + hex("00000028" + "0001") + faceImage
        val header = hex("A10E" + "810102" + "82010087020101" + "88020008")
        val bit = tlv(0x7F60, header + tlv(0x5F2E, facialRecord))
        val dg2 = tlv(0x75, tlv(0x7F61, hex("020101") + bit))

        assertNull(DataGroupParsers.document(mapOf(1 to TestFixtures.dg1(), 2 to dg2), TODAY).portrait)
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** TLV à longueur courte (moins de 128 octets), tag sur un ou deux octets. */
    private fun tlv(
        tag: Int,
        value: ByteArray,
    ): ByteArray {
        require(value.size < 0x80)
        val tagBytes = if (tag > 0xFF) byteArrayOf((tag shr 8).toByte(), tag.toByte()) else byteArrayOf(tag.toByte())
        return tagBytes + byteArrayOf(value.size.toByte()) + value
    }
}
