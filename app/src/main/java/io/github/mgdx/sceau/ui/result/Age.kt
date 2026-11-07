package io.github.mgdx.sceau.ui.result

import java.time.LocalDate
import java.time.Period

/** Age in full years at [today], or null when it cannot be shown. */
internal object Age {
    /** Beyond this, the date is treated as wrong and no age is shown. */
    const val MAX_YEARS = 130

    fun years(
        birth: LocalDate,
        today: LocalDate,
    ): Int? {
        if (birth.isAfter(today)) return null
        val years = Period.between(birth, today).years
        return if (years > MAX_YEARS) null else years
    }
}
