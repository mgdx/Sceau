package io.github.mgdx.sceau.session

import io.github.mgdx.sceau.core.AccessKey
import java.time.DateTimeException
import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/** Home screen tabs (SPEC §5.1), in display order: the passport first (D29). */
enum class DocumentTab {
    PASSPORT,
    ID_CARD,
}

/** Access key entered in the "ID card" tab: the CAN by default, or the MRZ (D31). */
enum class IdCardKey {
    CAN,
    MRZ,
}

/** Part of a date typed on the keyboard, with its number of digits. */
enum class DatePart(
    val length: Int,
) {
    DAY(2),
    MONTH(2),
    YEAR(4),
}

/** Date input order, following the locale: DD/MM/YYYY in French, MM/DD/YYYY in en-US. */
enum class DateOrder(
    val parts: List<DatePart>,
) {
    DAY_MONTH_YEAR(listOf(DatePart.DAY, DatePart.MONTH, DatePart.YEAR)),
    MONTH_DAY_YEAR(listOf(DatePart.MONTH, DatePart.DAY, DatePart.YEAR)),
    YEAR_MONTH_DAY(listOf(DatePart.YEAR, DatePart.MONTH, DatePart.DAY)),
    ;

    /** Positions, within the typed digits, where a separator is inserted (end of the first two parts). */
    val separatorPositions: List<Int> = listOf(parts[0].length, parts[0].length + parts[1].length)

    companion object {
        /** Order of the short date format of [locale]; day/month/year by default. */
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

/** Reason why a complete date is rejected. */
enum class DateError {
    /** The date does not exist (31/02, month 13…). */
    INVALID,

    /** Date of birth later than today. */
    FUTURE,

    /** Expiry date earlier than [AccessForm.MIN_EXPIRY_YEAR]. */
    TOO_OLD,
}

/**
 * Home screen input. Contains the access key in clear: lives only in the [SessionViewModel],
 * never in a Bundle or on disk, and its `toString` reveals nothing.
 *
 * Dates are kept as typed ([DATE_DIGITS] digits at most, without separators); their
 * interpretation depends on the locale's input order, provided by the screen.
 */
class AccessForm(
    val tab: DocumentTab = DocumentTab.PASSPORT,
    /** Key chosen in the "ID card" tab; ignored in the "Passport" tab. */
    val idCardKey: IdCardKey = IdCardKey.CAN,
    /** CAN: digits only, at most [CAN_LENGTH]. */
    val can: String = "",
    /** Normalized document number: A-Z and 0-9, at most [DOCUMENT_NUMBER_MAX_LENGTH], without `<`. */
    val documentNumber: String = "",
    /** Digits of the date of birth, at most [DATE_DIGITS]. */
    val dateOfBirthDigits: String = "",
    /** Digits of the expiry date, at most [DATE_DIGITS]. */
    val dateOfExpiryDigits: String = "",
) {
    /** True if the current tab's form allows building an access key. */
    fun isComplete(
        order: DateOrder,
        today: LocalDate = LocalDate.now(),
    ): Boolean = toAccessKey(order, today) != null

    /** True if the current tab shows the CAN field, false if it shows the MRZ fields. */
    val usesCan: Boolean
        get() = tab == DocumentTab.ID_CARD && idCardKey == IdCardKey.CAN

    /** Access key of the current tab, or null if the input is incomplete or invalid. */
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

    override fun toString(): String = "AccessForm(tab=$tab, idCardKey=$idCardKey, ***)"

    companion object {
        const val CAN_LENGTH = 6
        const val DOCUMENT_NUMBER_MAX_LENGTH = 9

        /** Number of digits of a complete date: day (2), month (2), year (4). */
        const val DATE_DIGITS = 8

        /** No NFC-readable ICAO document expires before this year. */
        const val MIN_EXPIRY_YEAR = 1990

        /** Keeps only ASCII digits, truncated to [CAN_LENGTH]. */
        fun filterCan(input: String): String = input.filter { it in '0'..'9' }.take(CAN_LENGTH)

        /**
         * Forced to uppercase, only A-Z and 0-9 are kept (the MRZ `<` filler characters are
         * added by JMRTD), truncated to [DOCUMENT_NUMBER_MAX_LENGTH].
         */
        fun normalizeDocumentNumber(input: String): String =
            input
                .uppercase(Locale.ROOT)
                .filter { it in 'A'..'Z' || it in '0'..'9' }
                .take(DOCUMENT_NUMBER_MAX_LENGTH)

        /** Keeps only ASCII digits, truncated to [DATE_DIGITS]. */
        fun filterDateDigits(input: String): String = input.filter { it in '0'..'9' }.take(DATE_DIGITS)

        /** Actual date read from [digits] according to [order], or null if incomplete or nonexistent. */
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

        /** Error of a complete date of birth, or null if it is valid or not yet complete. */
        fun dateOfBirthError(
            digits: String,
            order: DateOrder,
            today: LocalDate = LocalDate.now(),
        ): DateError? {
            if (digits.length < DATE_DIGITS) return null
            val date = parseDate(digits, order) ?: return DateError.INVALID
            return if (date > today) DateError.FUTURE else null
        }

        /** Error of a complete expiry date, or null if it is valid or not yet complete. */
        fun dateOfExpiryError(
            digits: String,
            order: DateOrder,
        ): DateError? {
            if (digits.length < DATE_DIGITS) return null
            val date = parseDate(digits, order) ?: return DateError.INVALID
            return if (date.year < MIN_EXPIRY_YEAR) DateError.TOO_OLD else null
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
