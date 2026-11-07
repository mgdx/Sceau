package io.github.mgdx.sceau.ui.trust

import io.github.mgdx.sceau.ui.result.toUtcDate
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Age of the built-in Master List, decision D58. */
internal object MasterListAge {
    /** From this age on, the store screen warns that the list may be outdated. */
    const val WARNING_MONTHS = 12L

    /** Whole months elapsed between [signingTime] (UTC date) and [today]; 0 if signingTime is in the future. */
    fun months(
        signingTime: Instant,
        today: LocalDate,
    ): Long = ChronoUnit.MONTHS.between(signingTime.toUtcDate(), today).coerceAtLeast(0L)

    /** True when [months] is at least [WARNING_MONTHS]. */
    fun isStale(
        signingTime: Instant,
        today: LocalDate,
    ): Boolean = months(signingTime, today) >= WARNING_MONTHS
}
