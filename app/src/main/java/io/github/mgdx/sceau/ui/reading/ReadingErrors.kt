package io.github.mgdx.sceau.ui.reading

import androidx.annotation.StringRes
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.core.Step

/** Correspondance entre les codes de `SceauException` et les messages de l'écran de lecture. */
internal object ReadingErrors {
    /**
     * `TIMEOUT` et `CONNECTION_LOST` peuvent porter un suffixe de diagnostic
     * (`TIMEOUT-SECURE_CHANNEL-INS86-L10`) : seul le code de base choisit le message.
     */
    @StringRes
    fun messageFor(code: String): Int =
        when (code.substringBefore(DETAIL_SEPARATOR)) {
            "ACCESS_DENIED" -> R.string.reading_error_access_denied
            "CONNECTION_LOST" -> R.string.reading_error_connection_lost
            "NOT_ICAO" -> R.string.reading_error_not_icao
            "TIMEOUT" -> R.string.reading_error_timeout
            "CAN_WITHOUT_PACE" -> R.string.reading_error_can_without_pace
            else -> R.string.reading_error_unexpected
        }

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
