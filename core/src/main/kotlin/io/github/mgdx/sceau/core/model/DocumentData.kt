package io.github.mgdx.sceau.core.model

import java.time.LocalDate

/*
 * Modèles des données lues. Ce sont des données personnelles : aucune classe ici n'est
 * une `data class` (pas de toString / equals révélateurs), rien n'est jamais journalisé.
 */

enum class Sex { MALE, FEMALE, UNSPECIFIED }

enum class ImageFormat { JPEG, JPEG2000, UNKNOWN }

/** Image brute telle que stockée dans la puce (DG2, DG12). Remise à zéro par [wipe]. */
class EncodedImage(
    val format: ImageFormat,
    val bytes: ByteArray,
) {
    fun wipe() = bytes.fill(0)

    override fun toString(): String = "EncodedImage($format, ${bytes.size} octets)"
}

/** Image décodée en pixels ARGB 32 bits (sortie du décodeur JPEG 2000). */
class ArgbImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    fun wipe() = pixels.fill(0)

    override fun toString(): String = "ArgbImage(${width}x$height)"
}

/** DG1 : zone MRZ. Les dates sont converties depuis AAMMJJ (null si illisibles). */
class Dg1Data(
    /** Code de document MRZ : "P", "ID", "I<"… */
    val documentCode: String,
    /** Code pays ICAO à trois lettres (ex. "FRA", "D<<"). */
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

/** DG11 : seuls les champs présents dans la puce sont non nuls / non vides. */
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

/** DG12 : seuls les champs présents dans la puce sont non nuls / non vides. */
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
 * Ensemble des données lues. [rawDataGroups] conserve les octets bruts des DG lus
 * (clé = numéro de DG) ; [wipe] les remet à zéro, ainsi que les images.
 */
class DocumentData(
    val dg1: Dg1Data,
    val portrait: EncodedImage?,
    val dg11: Dg11Data?,
    val dg12: Dg12Data?,
    val rawDataGroups: Map<Int, ByteArray>,
) {
    fun wipe() {
        rawDataGroups.values.forEach { it.fill(0) }
        portrait?.wipe()
        dg12?.frontImage?.wipe()
        dg12?.rearImage?.wipe()
    }

    override fun toString(): String = "DocumentData(***)"
}
