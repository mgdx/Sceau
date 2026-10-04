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
 * Conversion des DG bruts en modèles. Aucune de ces fonctions ne journalise ni n'expose de
 * donnée dans une exception : les erreurs de JMRTD remontent telles quelles à l'appelant,
 * qui n'en garde que la classe.
 */
internal object DataGroupParsers {
    /**
     * Assemble [DocumentData]. DG1 est indispensable (exception s'il est illisible) ; un DG2,
     * DG7, DG11 ou DG12 illisible est simplement absent. [dataGroups] est conservé tel quel.
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
     * Première image de visage de DG2 (ISO 19794-5 ou ISO 39794-5), ou null. Longueurs BER et
     * longueurs des blocs d'image ISO 19794-5 contrôlées avant JMRTD ([lengthsFitDg2]).
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
     * Première image de DG7 (signature manuscrite ou marque usuelle du titulaire, ICAO 9303-10
     * §4.7.7), ou null. ICAO permet plusieurs images, mais les documents n'en portent qu'une,
     * comme DG2 n'a qu'un portrait : seule la première est affichée (D37). Le format est déduit
     * des octets ([sniffFormat]) : JMRTD annonce toujours `image/jpeg` pour DG7.
     */
    fun parseDg7(bytes: ByteArray): EncodedImage? {
        // JMRTD alloue la longueur annoncée par chaque image (5F43) : contrôlée d'abord.
        require(BerStructure.lengthsFit(bytes)) { "DG7_LENGTH" }
        val image = DG7File(StrictInputStream(bytes)).images.orEmpty().firstOrNull() ?: return null
        val encoded = image.imageInputStream.use { it.readBytes() }
        return encoded.takeIf { it.isNotEmpty() }?.let { EncodedImage(sniffFormat(it), it) }
    }

    fun parseDg11(
        bytes: ByteArray,
        today: LocalDate = LocalDate.now(),
    ): Dg11Data {
        // JMRTD alloue la longueur annoncée par chaque champ : contrôlée d'abord (BerStructure).
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
        // Idem DG11 : champs et images (5F2F…) lus à la longueur annoncée.
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

    /** Format d'après la signature des premiers octets (DG7 et DG12 ne déclarent pas de type MIME fiable). */
    fun sniffFormat(bytes: ByteArray): ImageFormat {
        fun startsWith(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
        return when {
            startsWith(0xFF, 0xD8, 0xFF) -> ImageFormat.JPEG

            // Boîte de signature JP2, ou flux de code JPEG 2000 brut (marqueurs SOC puis SIZ).
            startsWith(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20) -> ImageFormat.JPEG2000

            startsWith(0xFF, 0x4F, 0xFF, 0x51) -> ImageFormat.JPEG2000

            else -> ImageFormat.UNKNOWN
        }
    }

    /** Remplace les `<` de remplissage par des espaces, normalise les blancs ; null si vide. */
    fun clean(value: String?): String? =
        value
            ?.replace('<', ' ')
            ?.trim()
            ?.replace(WHITESPACE, " ")
            ?.ifEmpty { null }

    /** Nom au format ICAO `PRIMAIRE<<SECONDAIRE` → `PRIMAIRE, SECONDAIRE`. */
    fun cleanName(value: String?): String? {
        if (value == null) return null
        val parts = value.split("<<", limit = 2).mapNotNull(::clean)
        return parts.joinToString(", ").ifEmpty { null }
    }

    /**
     * DG2 : longueurs BER ([BerStructure]) et, dans chaque bloc biométrique (5F2E) au format
     * ISO 19794-5 (« FAC\0 »), longueur de chaque bloc d'image de visage au plus égale aux octets
     * restants du bloc biométrique. JMRTD alloue sinon cette longueur d'emblée (2 Go possibles,
     * fuzzing). Un bloc d'un autre format (ISO 39794-5) est laissé au parseur, avec
     * [orNull] pour filet.
     */
    internal fun lengthsFitDg2(bytes: ByteArray): Boolean =
        BerStructure.lengthsFit(bytes) { tag, start, end ->
            tag != TAG_BIOMETRIC_DATA_BLOCK || faceBlocksFit(bytes, start, end)
        }

    /** En-tête ISO 19794-5 : « FAC\0 », version (4), longueur d'enregistrement (4), nombre d'images (2). */
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
            if (offset + FACE_BLOCK_LENGTH > end) return true // tronqué : JMRTD échouera en fin de flux
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
     * Résultat de [block], ou null s'il échoue. Les longueurs démesurées connues sont refusées
     * avant JMRTD ([BerStructure], [lengthsFitDg2]) ; `OutOfMemoryError` et
     * `NegativeArraySizeException` restent rattrapées en dernier filet et rendent le DG absent
     * (audit V11) ; les autres `Error` traversent.
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
 * Flux d'un DG en mémoire dont `skip` lève [EOFException] en fin de flux au lieu de renvoyer 0.
 * JMRTD saute des octets par des boucles `while (skipped < n) skipped += in.skip(n)` (octets
 * réservés des points caractéristiques ISO 19794-5, notamment) : sur un DG tronqué, un `skip`
 * qui renvoie 0 les faisait tourner sans fin.
 */
internal class StrictInputStream(
    bytes: ByteArray,
) : ByteArrayInputStream(bytes) {
    override fun skip(n: Long): Long {
        if (n > 0 && available() == 0) throw EOFException()
        return super.skip(n)
    }
}

/** Dates MRZ (AAMMJJ) et dates complètes (AAAAMMJJ) des DG. */
internal object Dates {
    /**
     * Date passée (naissance, délivrance) : AAAAMMJJ, ou AAMMJJ avec le siècle tel que la date
     * ne soit pas dans le futur. Null si illisible (chiffres manquants, `<`, date invalide).
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

    /** Date d'expiration MRZ (AAMMJJ) : toujours 20AA. */
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
