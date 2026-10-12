package io.github.mgdx.sceau.nfc

import android.content.Context
import android.nfc.NfcAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/** NFC availability on the device. */
enum class NfcAvailability {
    /** The device has no NFC. */
    ABSENT,

    /** NFC present but turned off in the settings. */
    DISABLED,

    ENABLED,
}

fun Context.nfcAvailability(): NfcAvailability {
    val adapter = NfcAdapter.getDefaultAdapter(this) ?: return NfcAvailability.ABSENT
    return if (adapter.isEnabled) NfcAvailability.ENABLED else NfcAvailability.DISABLED
}

/**
 * Current NFC state, read again at regular intervals as long as it is collected. No receiver for
 * `ACTION_ADAPTER_STATE_CHANGED`: the broadcast comes from the NFC process and not from the system,
 * so a non-exported receiver does not always receive the return to ON (observed on a real
 * device); polling avoids exposing an exported receiver.
 */
fun Context.nfcAvailabilityFlow(): Flow<NfcAvailability> =
    flow {
        while (true) {
            emit(nfcAvailability())
            delay(NFC_POLL_INTERVAL_MILLIS)
        }
    }.distinctUntilChanged()

/** Current NFC state for the UI, read again as long as the screen is visible. */
@Composable
fun rememberNfcAvailability(): State<NfcAvailability> {
    val context = LocalContext.current
    val availability = remember(context) { context.nfcAvailabilityFlow() }
    return availability.collectAsStateWithLifecycle(initialValue = remember(context) { context.nfcAvailability() })
}

/** Maximum delay between an NFC state change and its being taken into account. */
private const val NFC_POLL_INTERVAL_MILLIS = 1_000L
