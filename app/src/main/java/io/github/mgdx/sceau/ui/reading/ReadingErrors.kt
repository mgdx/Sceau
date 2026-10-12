package io.github.mgdx.sceau.ui.reading

import androidx.annotation.StringRes
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.core.Step

/** Mapping between `SceauException` codes and the reading screen messages. */
internal object ReadingErrors {
    /**
     * `TIMEOUT`, `CONNECTION_LOST` and `READ_STALLED` may carry a diagnostic suffix
     * (`TIMEOUT-SECURE_CHANNEL-INS86-L10`, `TIMEOUT-READ_DATA-GLOBAL`, `READ_STALLED-READ_DATA-DG2`):
     * only the base code selects the message. `READ_STALLED` (audit V29: the chip no longer
     * delivers the current file) reuses the timeout message.
     */
    @StringRes
    fun messageFor(code: String): Int =
        when (code.substringBefore(DETAIL_SEPARATOR)) {
            "ACCESS_DENIED" -> R.string.reading_error_access_denied
            "CONNECTION_LOST" -> R.string.reading_error_connection_lost
            "NOT_ICAO" -> R.string.reading_error_not_icao
            "TIMEOUT" -> R.string.reading_error_timeout
            "READ_STALLED" -> R.string.reading_error_timeout
            "CAN_WITHOUT_PACE" -> R.string.reading_error_can_without_pace
            ACCESS_RESTRICTED -> R.string.reading_error_access_restricted
            else -> R.string.reading_error_unexpected
        }

    /**
     * False if a new attempt cannot change anything: chip reserved for authorized authorities
     * (`ACCESS_RESTRICTED`, decision D36). The screen then does not offer "Try again".
     */
    fun canRetry(code: String): Boolean = code.substringBefore(DETAIL_SEPARATOR) != ACCESS_RESTRICTED

    private const val ACCESS_RESTRICTED = "ACCESS_RESTRICTED"
    private const val DETAIL_SEPARATOR = '-'
}

@StringRes
internal fun Step.labelRes(): Int =
    when (this) {
        Step.CONNECT -> R.string.reading_step_connect
        Step.SECURE_CHANNEL -> R.string.reading_step_secure_channel
        Step.READ_DATA -> R.string.reading_step_read_data
        Step.VERIFY_SIGNATURE -> R.string.reading_step_verify_signature
        Step.VERIFY_CHIP -> R.string.reading_step_verify_chip
    }
