package io.github.mgdx.sceau.mrz

/**
 * Image en niveaux de gris (plan Y d'une image de la caméra), [width] × [height] pixels,
 * [rowStride] octets par ligne. [data] appartient à l'appelant, qui la remet à zéro après
 * l'analyse : `:mrz` n'en garde aucune référence ni copie au-delà de [MrzScanner.analyze].
 * [rotationDegrees] (0, 90, 180 ou 270) est la rotation à appliquer pour remettre l'image à
 * l'endroit.
 */
class LumaFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val rotationDegrees: Int,
)

/** Format de MRZ (ICAO 9303-4 et 9303-5) : TD1 3 × 30, TD2 2 × 36, TD3 2 × 44. */
enum class MrzFormat {
    TD1,
    TD2,
    TD3,
}

/**
 * Seuls champs de la MRZ exposés hors du module (D32) : ceux de la clé d'accès.
 * [documentNumber] : 1 à 9 caractères A-Z0-9, sans `<` de remplissage.
 * [dateOfBirth] et [dateOfExpiry] : AAMMJJ, dates existantes.
 * `toString` ne révèle rien.
 */
class MrzKeyFields(
    val format: MrzFormat,
    val documentNumber: String,
    val dateOfBirth: String,
    val dateOfExpiry: String,
) {
    override fun equals(other: Any?): Boolean =
        other is MrzKeyFields &&
            format == other.format &&
            documentNumber == other.documentNumber &&
            dateOfBirth == other.dateOfBirth &&
            dateOfExpiry == other.dateOfExpiry

    override fun hashCode(): Int = listOf(format, documentNumber, dateOfBirth, dateOfExpiry).hashCode()

    override fun toString(): String = "MrzKeyFields($format)"
}

/** Résultat de l'analyse d'une image. */
sealed interface MrzScanResult {
    /** Aucune MRZ dans l'image. */
    data object NothingFound : MrzScanResult

    /** MRZ vue, mais chiffres de contrôle faux ou résultat pas encore confirmé par une seconde image. */
    data object Unstable : MrzScanResult

    /** MRZ lisible, mais numéro de document étendu (TD1, plus de 9 caractères) : saisie manuelle. */
    data object UnsupportedDocumentNumber : MrzScanResult

    /** MRZ reconnue, contrôles justes, confirmée par deux images successives identiques. */
    class Found(
        val fields: MrzKeyFields,
    ) : MrzScanResult {
        override fun toString(): String = "Found($fields)"
    }
}

/**
 * Point d'entrée de `:app`. Garde l'état de stabilisation entre images ; à appeler depuis un
 * seul fil à la fois.
 */
class MrzScanner internal constructor(
    private val recognizer: LineRecognizer,
    private val decoder: MrzFieldDecoder,
) {
    constructor() : this(TemplateLineRecognizer(), MrzFieldDecoder())

    fun analyze(frame: LumaFrame): MrzScanResult {
        val lines = recognizer.recognize(frame)
        return decoder.accept(lines)
    }

    /** Oublie l'état de stabilisation (sortie de l'écran, changement de document). */
    fun reset() {
        decoder.reset()
    }
}
