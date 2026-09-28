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
     * DG11 ou DG12 illisible est simplement absent. [dataGroups] est conservé tel quel.
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

    /** Première image de visage de DG2 (ISO 19794-5 ou ISO 39794-5), ou null. */
    fun parseDg2(bytes: ByteArray): EncodedImage? {
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

    fun parseDg11(
        bytes: ByteArray,
        today: LocalDate = LocalDate.now(),
    ): Dg11Data {
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

    /** Format d'après la signature des premiers octets (DG12 ne déclare pas de type MIME). */
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
     * Résultat de [block], ou null s'il échoue. Une longueur interne forgée (en-tête d'image de
     * DG2…) fait allouer à JMRTD un tableau démesuré : `OutOfMemoryError` et
     * `NegativeArraySizeException` rendent donc aussi le DG absent (audit V11) ; les autres
     * `Error` traversent.
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
