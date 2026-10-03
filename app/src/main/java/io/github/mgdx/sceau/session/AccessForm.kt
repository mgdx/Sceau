package io.github.mgdx.sceau.session

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.mrz.MrzKeyFields
import java.time.DateTimeException
import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/** Onglets de l'écran d'accueil (SPEC §5.1), dans l'ordre d'affichage : le passeport d'abord (D29). */
enum class DocumentTab {
    PASSPORT,
    ID_CARD,
}

/** Clé d'accès saisie dans l'onglet Carte d'identité : le CAN par défaut, ou la MRZ (D31). */
enum class IdCardKey {
    CAN,
    MRZ,
}

/** Partie d'une date saisie au clavier, avec son nombre de chiffres. */
enum class DatePart(
    val length: Int,
) {
    DAY(2),
    MONTH(2),
    YEAR(4),
}

/** Ordre de saisie des dates, suivant la locale : JJ/MM/AAAA en français, MM/DD/YYYY en en-US. */
enum class DateOrder(
    val parts: List<DatePart>,
) {
    DAY_MONTH_YEAR(listOf(DatePart.DAY, DatePart.MONTH, DatePart.YEAR)),
    MONTH_DAY_YEAR(listOf(DatePart.MONTH, DatePart.DAY, DatePart.YEAR)),
    YEAR_MONTH_DAY(listOf(DatePart.YEAR, DatePart.MONTH, DatePart.DAY)),
    ;

    /** Positions, dans les chiffres saisis, où s'insère un séparateur (fin des deux premières parties). */
    val separatorPositions: List<Int> = listOf(parts[0].length, parts[0].length + parts[1].length)

    companion object {
        /** Ordre du format de date court de [locale] ; jour/mois/année par défaut. */
        fun forLocale(locale: Locale): DateOrder {
            val pattern =
                try {
                    DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
                } catch (e: IllegalArgumentException) {
                    return DAY_MONTH_YEAR
                }
            val day = pattern.indexOf('d')
            val month = pattern.indexOfFirst { it == 'M' || it == 'L' }
            val year = pattern.indexOfFirst { it == 'y' || it == 'u' }
            if (day < 0 || month < 0 || year < 0) return DAY_MONTH_YEAR
            return when {
                year < month && month < day -> YEAR_MONTH_DAY
                month < day && day < year -> MONTH_DAY_YEAR
                else -> DAY_MONTH_YEAR
            }
        }
    }
}

/** Raison pour laquelle une date complète est refusée. */
enum class DateError {
    /** La date n'existe pas (31/02, mois 13…). */
    INVALID,

    /** Date de naissance postérieure à aujourd'hui. */
    FUTURE,

    /** Date d'expiration antérieure à [AccessForm.MIN_EXPIRY_YEAR]. */
    TOO_OLD,
}

/**
 * Saisie de l'écran d'accueil. Contient la clé d'accès en clair : vit uniquement dans le
 * [SessionViewModel], jamais dans un Bundle ni sur disque, et son `toString` ne révèle rien.
 *
 * Les dates sont gardées telles que tapées ([DATE_DIGITS] chiffres au plus, sans séparateur) ;
 * leur interprétation dépend de l'ordre de saisie de la locale, fourni par l'écran.
 */
class AccessForm(
    val tab: DocumentTab = DocumentTab.PASSPORT,
    /** Clé choisie dans l'onglet Carte d'identité ; ignorée dans l'onglet Passeport. */
    val idCardKey: IdCardKey = IdCardKey.CAN,
    /** CAN : uniquement des chiffres, au plus [CAN_LENGTH]. */
    val can: String = "",
    /** Numéro de document normalisé : A-Z et 0-9, au plus [DOCUMENT_NUMBER_MAX_LENGTH], sans `<`. */
    val documentNumber: String = "",
    /** Chiffres de la date de naissance, au plus [DATE_DIGITS]. */
    val dateOfBirthDigits: String = "",
    /** Chiffres de la date d'expiration, au plus [DATE_DIGITS]. */
    val dateOfExpiryDigits: String = "",
) {
    /** Vrai si le formulaire de l'onglet courant permet de construire une clé d'accès. */
    fun isComplete(
        order: DateOrder,
        today: LocalDate = LocalDate.now(),
    ): Boolean = toAccessKey(order, today) != null

    /** Vrai si l'onglet courant affiche le champ CAN, faux s'il affiche les champs de la MRZ. */
    val usesCan: Boolean
        get() = tab == DocumentTab.ID_CARD && idCardKey == IdCardKey.CAN

    /** Clé d'accès de l'onglet courant, ou null si la saisie est incomplète ou invalide. */
    fun toAccessKey(
        order: DateOrder,
        today: LocalDate = LocalDate.now(),
    ): AccessKey? {
        if (usesCan) return if (can.length == CAN_LENGTH) AccessKey.Can(can) else null
        val birth = validDateOfBirth(dateOfBirthDigits, order, today)
        val expiry = validDateOfExpiry(dateOfExpiryDigits, order)
        return if (documentNumber.isNotEmpty() && birth != null && expiry != null) {
            AccessKey.Mrz(documentNumber, birth, expiry)
        } else {
            null
        }
    }

    fun copy(
        tab: DocumentTab = this.tab,
        idCardKey: IdCardKey = this.idCardKey,
        can: String = this.can,
        documentNumber: String = this.documentNumber,
        dateOfBirthDigits: String = this.dateOfBirthDigits,
        dateOfExpiryDigits: String = this.dateOfExpiryDigits,
    ): AccessForm = AccessForm(tab, idCardKey, can, documentNumber, dateOfBirthDigits, dateOfExpiryDigits)

    /**
     * Formulaire rempli avec la MRZ lue par la caméra (D32) : numéro et dates remplacés, onglet,
     * segment et CAN gardés. Les dates `AAMMJJ` deviennent [DATE_DIGITS] chiffres dans l'ordre
     * [dateOrder] de la locale (D10) ; elles sont ensuite validées comme une saisie au clavier
     * (une date inexistante reste affichée en erreur). Siècle de naissance : 20AA si la date ne
     * dépasse pas [today], 19AA sinon ; expiration toujours en 20AA.
     */
    fun fillFromMrz(
        fields: MrzKeyFields,
        today: LocalDate,
        dateOrder: DateOrder,
    ): AccessForm {
        val todayValue = today.year * 10_000 + today.monthValue * 100 + today.dayOfMonth
        return copy(
            documentNumber = normalizeDocumentNumber(fields.documentNumber),
            dateOfBirthDigits =
                mrzDateDigits(fields.dateOfBirth, dateOrder) { yy, mm, dd ->
                    if ((2000 + yy) * 10_000 + mm * 100 + dd <= todayValue) 2000 + yy else 1900 + yy
                },
            dateOfExpiryDigits = mrzDateDigits(fields.dateOfExpiry, dateOrder) { yy, _, _ -> 2000 + yy },
        )
    }

    override fun toString(): String = "AccessForm(tab=$tab, idCardKey=$idCardKey, ***)"

    companion object {
        const val CAN_LENGTH = 6
        const val DOCUMENT_NUMBER_MAX_LENGTH = 9

        /** Nombre de chiffres d'une date complète : jour (2), mois (2), année (4). */
        const val DATE_DIGITS = 8

        /** Aucun document ICAO lisible par NFC n'expire avant cette année. */
        const val MIN_EXPIRY_YEAR = 1990

        /** Longueur d'une date de la MRZ (`AAMMJJ`). */
        private const val MRZ_DATE_LENGTH = 6

        /** Ne garde que les chiffres ASCII, tronqués à [CAN_LENGTH]. */
        fun filterCan(input: String): String = input.filter { it in '0'..'9' }.take(CAN_LENGTH)

        /**
         * Majuscules forcées, seuls A-Z et 0-9 sont gardés (les `<` de remplissage de la MRZ
         * sont ajoutés par JMRTD), tronqué à [DOCUMENT_NUMBER_MAX_LENGTH].
         */
        fun normalizeDocumentNumber(input: String): String =
            input
                .uppercase(Locale.ROOT)
                .filter { it in 'A'..'Z' || it in '0'..'9' }
                .take(DOCUMENT_NUMBER_MAX_LENGTH)

        /** Ne garde que les chiffres ASCII, tronqués à [DATE_DIGITS]. */
        fun filterDateDigits(input: String): String = input.filter { it in '0'..'9' }.take(DATE_DIGITS)

        /** Date réelle lue dans [digits] selon [order], ou null si incomplète ou inexistante. */
        fun parseDate(
            digits: String,
            order: DateOrder,
        ): LocalDate? {
            if (digits.length != DATE_DIGITS || digits.any { it !in '0'..'9' }) return null
            var start = 0
            val values = HashMap<DatePart, Int>()
            for (part in order.parts) {
                values[part] = digits.substring(start, start + part.length).toInt()
                start += part.length
            }
            return try {
                LocalDate.of(values.getValue(DatePart.YEAR), values.getValue(DatePart.MONTH), values.getValue(DatePart.DAY))
            } catch (e: DateTimeException) {
                null
            }
        }

        /** Erreur d'une date de naissance complète, ou null si elle est valide ou pas encore complète. */
        fun dateOfBirthError(
            digits: String,
            order: DateOrder,
            today: LocalDate = LocalDate.now(),
        ): DateError? {
            if (digits.length < DATE_DIGITS) return null
            val date = parseDate(digits, order) ?: return DateError.INVALID
            return if (date > today) DateError.FUTURE else null
        }

        /** Erreur d'une date d'expiration complète, ou null si elle est valide ou pas encore complète. */
        fun dateOfExpiryError(
            digits: String,
            order: DateOrder,
        ): DateError? {
            if (digits.length < DATE_DIGITS) return null
            val date = parseDate(digits, order) ?: return DateError.INVALID
            return if (date.year < MIN_EXPIRY_YEAR) DateError.TOO_OLD else null
        }

        /**
         * Date `AAMMJJ` de la MRZ en [DATE_DIGITS] chiffres dans l'ordre [order], l'année complète
         * étant choisie par [fullYear] ; chaîne vide si [yymmdd] n'est pas fait de six chiffres.
         */
        private fun mrzDateDigits(
            yymmdd: String,
            order: DateOrder,
            fullYear: (yy: Int, mm: Int, dd: Int) -> Int,
        ): String {
            if (yymmdd.length != MRZ_DATE_LENGTH || yymmdd.any { it !in '0'..'9' }) return ""
            val yy = yymmdd.substring(0, 2).toInt()
            val mm = yymmdd.substring(2, 4).toInt()
            val dd = yymmdd.substring(4, 6).toInt()
            val year = fullYear(yy, mm, dd)
            return order.parts.joinToString("") { part ->
                when (part) {
                    DatePart.DAY -> "%02d".format(Locale.ROOT, dd)
                    DatePart.MONTH -> "%02d".format(Locale.ROOT, mm)
                    DatePart.YEAR -> "%04d".format(Locale.ROOT, year)
                }
            }
        }

        private fun validDateOfBirth(
            digits: String,
            order: DateOrder,
            today: LocalDate,
        ): LocalDate? = parseDate(digits, order)?.takeIf { dateOfBirthError(digits, order, today) == null }

        private fun validDateOfExpiry(
            digits: String,
            order: DateOrder,
        ): LocalDate? = parseDate(digits, order)?.takeIf { dateOfExpiryError(digits, order) == null }
    }
}
