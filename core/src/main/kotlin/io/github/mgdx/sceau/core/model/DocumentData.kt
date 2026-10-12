package io.github.mgdx.sceau.core.model

import java.time.LocalDate

/*
 * Models of the data read. This is personal data: no class here is
 * a `data class` (no revealing toString / equals), nothing is ever logged.
 */

enum class Sex { MALE, FEMALE, UNSPECIFIED }

enum class ImageFormat { JPEG, JPEG2000, UNKNOWN }

/** Raw image as stored in the chip (DG2, DG7, DG12). Zeroed by [wipe]. */
class EncodedImage(
    val format: ImageFormat,
    val bytes: ByteArray,
) {
    fun wipe() = bytes.fill(0)

    override fun toString(): String = "EncodedImage($format, ${bytes.size} bytes)"
}

/** Image decoded into 32-bit ARGB pixels (output of the JPEG 2000 decoder). */
class ArgbImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    fun wipe() = pixels.fill(0)

    override fun toString(): String = "ArgbImage(${width}x$height)"
}

/** DG1: MRZ. Dates are converted from YYMMDD (null if unreadable). */
class Dg1Data(
    /** MRZ document code: "P", "ID", "I<"… */
    val documentCode: String,
    /** Three-letter ICAO country code (e.g. "FRA", "D<<"). */
    val issuingState: String,
    val documentNumber: String,
    val primaryIdentifier: String,
    val secondaryIdentifiers: List<String>,
    val nationality: String,
    val dateOfBirth: LocalDate?,
    val sex: Sex,
    val dateOfExpiry: LocalDate?,
    val optionalData: String?,
) {
    override fun toString(): String = "Dg1Data(***)"
}

/** DG11: only the fields present in the chip are non-null / non-empty. */
class Dg11Data(
    val fullName: String?,
    val otherNames: List<String>,
    val personalNumber: String?,
    val fullDateOfBirth: LocalDate?,
    val placeOfBirth: List<String>,
    val address: List<String>,
    val telephone: String?,
    val profession: String?,
    val title: String?,
    val personalSummary: String?,
    val otherValidTdNumbers: List<String>,
    val custodyInformation: String?,
) {
    override fun toString(): String = "Dg11Data(***)"
}

/** DG12: only the fields present in the chip are non-null / non-empty. */
class Dg12Data(
    val issuingAuthority: String?,
    val dateOfIssue: LocalDate?,
    val namesOfOtherPersons: List<String>,
    val endorsementsAndObservations: String?,
    val taxOrExitRequirements: String?,
    val frontImage: EncodedImage?,
    val rearImage: EncodedImage?,
    val personalizationTime: String?,
    val personalizationDeviceSerial: String?,
) {
    override fun toString(): String = "Dg12Data(***)"
}

/**
 * All the data read. [rawDataGroups] keeps the raw bytes of the DGs read
 * (key = DG number); [wipe] zeroes them, as well as the images.
 */
class DocumentData(
    val dg1: Dg1Data,
    val portrait: EncodedImage?,
    val dg11: Dg11Data?,
    val dg12: Dg12Data?,
    val rawDataGroups: Map<Int, ByteArray>,
    /** DG7: image of the holder's handwritten signature, if the State records it (D37). */
    val signature: EncodedImage? = null,
) {
    fun wipe() {
        rawDataGroups.values.forEach { it.fill(0) }
        portrait?.wipe()
        signature?.wipe()
        dg12?.frontImage?.wipe()
        dg12?.rearImage?.wipe()
    }

    override fun toString(): String = "DocumentData(***)"
}
