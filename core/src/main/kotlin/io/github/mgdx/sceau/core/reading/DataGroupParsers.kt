package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.model.Dg11Data
import io.github.mgdx.sceau.core.model.Dg12Data
import io.github.mgdx.sceau.core.model.Dg1Data
import io.github.mgdx.sceau.core.model.DocumentData
import io.github.mgdx.sceau.core.model.EncodedImage
import io.github.mgdx.sceau.core.model.ImageFormat
import io.github.mgdx.sceau.core.model.Sex
import net.sf.scuba.data.Gender
import org.jmrtd.lds.ImageInfo
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.DG7File
import org.jmrtd.lds.iso19794.FaceInfo
import org.jmrtd.lds.iso39794.FaceImageDataBlock
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Conversion of raw DGs into models. None of these functions logs or exposes data in an
 * exception: JMRTD's errors propagate unchanged to the caller, which keeps only their class.
 */
internal object DataGroupParsers {
    /**
     * Assembles [DocumentData]. DG1 is required (exception if unreadable); an unreadable DG2,
     * DG7, DG11 or DG12 is simply absent. [dataGroups] is kept as is.
     */
    fun document(
        dataGroups: Map<Int, ByteArray>,
        today: LocalDate = LocalDate.now(),
    ): DocumentData {
        val dg1 = parseDg1(checkNotNull(dataGroups[1]) { "DG1" }, today)
        return DocumentData(
            dg1 = dg1,
            portrait = dataGroups[2]?.let { orNull { parseDg2(it) } },
            dg11 = dataGroups[11]?.let { orNull { parseDg11(it, today) } },
            dg12 = dataGroups[12]?.let { orNull { parseDg12(it, today) } },
            rawDataGroups = dataGroups,
            signature = dataGroups[7]?.let { orNull { parseDg7(it) } },
        )
    }

    fun parseDg1(
        bytes: ByteArray,
        today: LocalDate = LocalDate.now(),
    ): Dg1Data {
        val mrz = DG1File(StrictInputStream(bytes)).mrzInfo
        return Dg1Data(
            documentCode = mrz.documentCode.orEmpty(),
            issuingState = mrz.issuingState.orEmpty(),
            documentNumber = clean(mrz.documentNumber).orEmpty(),
            primaryIdentifier = clean(mrz.primaryIdentifier).orEmpty(),
            secondaryIdentifiers = mrz.secondaryIdentifierComponents.orEmpty().mapNotNull(::clean),
            nationality = mrz.nationality.orEmpty(),
            dateOfBirth = Dates.parsePast(mrz.dateOfBirth, today),
            sex =
                when (mrz.genderCode) {
                    Gender.MALE -> Sex.MALE
                    Gender.FEMALE -> Sex.FEMALE
                    else -> Sex.UNSPECIFIED
                },
            dateOfExpiry = Dates.parseExpiry(mrz.dateOfExpiry),
            optionalData = listOfNotNull(clean(mrz.optionalData1), clean(mrz.optionalData2)).joinToString(" ").ifEmpty { null },
        )
    }

    /**
     * First face image of DG2 (ISO 19794-5 or ISO 39794-5), or null. BER lengths and ISO 19794-5
     * image block lengths are checked before JMRTD ([lengthsFitDg2]).
     */
    fun parseDg2(bytes: ByteArray): EncodedImage? {
        require(lengthsFitDg2(bytes)) { "DG2_LENGTH" }
        val images =
            DG2File(StrictInputStream(bytes)).subRecords.orEmpty().asSequence().flatMap { record ->
                when (record) {
                    is FaceInfo -> record.faceImageInfos.orEmpty().asSequence()
                    is FaceImageDataBlock -> record.representationBlocks.orEmpty().asSequence()
                    else -> emptySequence<ImageInfo>()
                }
            }
        val image = images.firstOrNull() ?: return null
        val encoded = image.imageInputStream.use { it.readBytes() }
        return EncodedImage(formatOfMime(image.mimeType), encoded)
    }

    /**
     * First image of DG7 (holder's handwritten signature or usual mark, ICAO 9303-10 §4.7.7), or
     * null. ICAO allows several images, but documents carry only one, just as DG2 has only one
     * portrait: only the first is displayed (D37). The format is inferred from the bytes
     * ([sniffFormat]): JMRTD always announces `image/jpeg` for DG7.
     */
    fun parseDg7(bytes: ByteArray): EncodedImage? {
        // JMRTD allocates the length announced by each image (5F43): checked first.
        require(BerStructure.lengthsFit(bytes)) { "DG7_LENGTH" }
        val image = DG7File(StrictInputStream(bytes)).images.orEmpty().firstOrNull() ?: return null
        val encoded = image.imageInputStream.use { it.readBytes() }
        return encoded.takeIf { it.isNotEmpty() }?.let { EncodedImage(sniffFormat(it), it) }
    }

    fun parseDg11(
        bytes: ByteArray,
        today: LocalDate = LocalDate.now(),
    ): Dg11Data {
        // JMRTD allocates the length announced by each field: checked first (BerStructure).
        require(BerStructure.lengthsFit(bytes)) { "DG11_LENGTH" }
        val dg11 = DG11File(StrictInputStream(bytes))
        return Dg11Data(
            fullName = cleanName(dg11.nameOfHolder),
            otherNames = dg11.otherNames.orEmpty().mapNotNull(::cleanName),
            personalNumber = clean(dg11.personalNumber),
            fullDateOfBirth = Dates.parsePast(dg11.fullDateOfBirth, today),
            placeOfBirth = dg11.placeOfBirth.orEmpty().mapNotNull(::clean),
            address = dg11.permanentAddress.orEmpty().mapNotNull(::clean),
            telephone = clean(dg11.telephone),
            profession = clean(dg11.profession),
            title = clean(dg11.title),
            personalSummary = clean(dg11.personalSummary),
            otherValidTdNumbers = dg11.otherValidTDNumbers.orEmpty().mapNotNull(::clean),
            custodyInformation = clean(dg11.custodyInformation),
        )
    }

    fun parseDg12(
        bytes: ByteArray,
        today: LocalDate = LocalDate.now(),
    ): Dg12Data {
        // Same as DG11: fields and images (5F2F…) are read at the announced length.
        require(BerStructure.lengthsFit(bytes)) { "DG12_LENGTH" }
        val dg12 = DG12File(StrictInputStream(bytes))
        return Dg12Data(
            issuingAuthority = clean(dg12.issuingAuthority),
            dateOfIssue = Dates.parsePast(dg12.dateOfIssue, today),
            namesOfOtherPersons = dg12.namesOfOtherPersons.orEmpty().mapNotNull(::cleanName),
            endorsementsAndObservations = clean(dg12.endorsementsAndObservations),
            taxOrExitRequirements = clean(dg12.taxOrExitRequirements),
            frontImage = dg12.imageOfFront?.takeIf { it.isNotEmpty() }?.let { EncodedImage(sniffFormat(it), it) },
            rearImage = dg12.imageOfRear?.takeIf { it.isNotEmpty() }?.let { EncodedImage(sniffFormat(it), it) },
            personalizationTime = clean(dg12.dateAndTimeOfPersonalization),
            personalizationDeviceSerial = clean(dg12.personalizationSystemSerialNumber),
        )
    }

    fun formatOfMime(mime: String?): ImageFormat =
        when (mime?.lowercase()) {
            "image/jp2", "image/jpeg2000" -> ImageFormat.JPEG2000
            "image/jpeg" -> ImageFormat.JPEG
            else -> ImageFormat.UNKNOWN
        }

    /** Format from the signature of the first bytes (DG7 and DG12 do not declare a reliable MIME type). */
    fun sniffFormat(bytes: ByteArray): ImageFormat {
        fun startsWith(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
        return when {
            startsWith(0xFF, 0xD8, 0xFF) -> ImageFormat.JPEG

            // JP2 signature box, or raw JPEG 2000 codestream (SOC then SIZ markers).
            startsWith(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20) -> ImageFormat.JPEG2000

            startsWith(0xFF, 0x4F, 0xFF, 0x51) -> ImageFormat.JPEG2000

            else -> ImageFormat.UNKNOWN
        }
    }

    /** Replaces filler `<` with spaces, normalizes whitespace; null if empty. */
    fun clean(value: String?): String? =
        value
            ?.replace('<', ' ')
            ?.trim()
            ?.replace(WHITESPACE, " ")
            ?.ifEmpty { null }

    /** Name in ICAO format `PRIMARY<<SECONDARY` → `PRIMARY, SECONDARY`. */
    fun cleanName(value: String?): String? {
        if (value == null) return null
        val parts = value.split("<<", limit = 2).mapNotNull(::clean)
        return parts.joinToString(", ").ifEmpty { null }
    }

    /**
     * DG2: BER lengths ([BerStructure]) and, in each biometric data block (5F2E) in ISO 19794-5
     * format ("FAC\0"), length of each face image block at most equal to the remaining bytes of
     * the biometric data block. Otherwise JMRTD allocates that length upfront (up to 2 GB,
     * fuzzing). A block in another format (ISO 39794-5) is left to the parser, with [orNull] as
     * a safety net.
     */
    internal fun lengthsFitDg2(bytes: ByteArray): Boolean =
        BerStructure.lengthsFit(bytes) { tag, start, end ->
            tag != TAG_BIOMETRIC_DATA_BLOCK || faceBlocksFit(bytes, start, end)
        }

    /** ISO 19794-5 header: "FAC\0", version (4), record length (4), number of images (2). */
    private fun faceBlocksFit(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Boolean {
        val isIso19794 = end - start >= FACE_HEADER && FACE_MAGIC.indices.all { bytes[start + it] == FACE_MAGIC[it] }
        if (!isIso19794) return true
        val count = uint(bytes, start + FACE_COUNT_OFFSET, 2)
        var offset = start + FACE_HEADER.toLong()
        repeat(count.toInt()) {
            if (offset + FACE_BLOCK_LENGTH > end) return true // truncated: JMRTD will fail at end of stream
            val blockLength = uint(bytes, offset.toInt(), FACE_BLOCK_LENGTH)
            if (offset + blockLength > end) return false
            if (blockLength == 0L) return true
            offset += blockLength
        }
        return true
    }

    private fun uint(
        bytes: ByteArray,
        start: Int,
        size: Int,
    ): Long = (0 until size).fold(0L) { acc, i -> (acc shl Byte.SIZE_BITS) or (bytes[start + i].toLong() and 0xFF) }

    private const val TAG_BIOMETRIC_DATA_BLOCK = 0x5F2E
    private val FACE_MAGIC = byteArrayOf(0x46, 0x41, 0x43, 0x00)
    private const val FACE_COUNT_OFFSET = 12
    private const val FACE_HEADER = 14
    private const val FACE_BLOCK_LENGTH = 4

    /**
     * Result of [block], or null if it fails. Known oversized lengths are rejected before JMRTD
     * ([BerStructure], [lengthsFitDg2]); `OutOfMemoryError` and `NegativeArraySizeException` are
     * still caught as a last safety net and make the DG absent (audit V11); other `Error`s pass
     * through.
     */
    private inline fun <T> orNull(block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * In-memory DG stream whose `skip` throws [EOFException] at end of stream instead of returning 0.
 * JMRTD skips bytes with `while (skipped < n) skipped += in.skip(n)` loops (reserved bytes of
 * ISO 19794-5 feature points, in particular): on a truncated DG, a `skip` returning 0 made them
 * loop forever.
 */
internal class StrictInputStream(
    bytes: ByteArray,
) : ByteArrayInputStream(bytes) {
    override fun skip(n: Long): Long {
        if (n > 0 && available() == 0) throw EOFException()
        return super.skip(n)
    }
}

/** MRZ dates (YYMMDD) and full dates (YYYYMMDD) of the DGs. */
internal object Dates {
    /**
     * Past date (birth, issue): YYYYMMDD, or YYMMDD with the century chosen so that the date is
     * not in the future. Null if unreadable (missing digits, `<`, invalid date).
     */
    fun parsePast(
        value: String?,
        today: LocalDate,
    ): LocalDate? {
        val digits = value?.trim() ?: return null
        if (!digits.all { it in '0'..'9' }) return null
        return when (digits.length) {
            8 -> {
                date(digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt())
            }

            6 -> {
                val yy = digits.substring(0, 2).toInt()
                val month = digits.substring(2, 4).toInt()
                val day = digits.substring(4, 6).toInt()
                date(2000 + yy, month, day)?.takeUnless { it.isAfter(today) } ?: date(1900 + yy, month, day)
            }

            else -> {
                null
            }
        }
    }

    /** MRZ expiry date (YYMMDD): always 20YY. */
    fun parseExpiry(value: String?): LocalDate? {
        val digits = value?.trim() ?: return null
        if (digits.length != 6 || !digits.all { it in '0'..'9' }) return null
        return date(2000 + digits.substring(0, 2).toInt(), digits.substring(2, 4).toInt(), digits.substring(4, 6).toInt())
    }

    private fun date(
        year: Int,
        month: Int,
        day: Int,
    ): LocalDate? =
        try {
            LocalDate.of(year, month, day)
        } catch (e: DateTimeException) {
            null
        }
}
