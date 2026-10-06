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

/** Disponibilité du NFC sur l'appareil. */
enum class NfcAvailability {
    /** L'appareil n'a pas de NFC. */
    ABSENT,

    /** NFC présent mais désactivé dans les réglages. */
    DISABLED,

    ENABLED,
}

fun Context.nfcAvailability(): NfcAvailability {
    val adapter = NfcAdapter.getDefaultAdapter(this) ?: return NfcAvailability.ABSENT
    return if (adapter.isEnabled) NfcAvailability.ENABLED else NfcAvailability.DISABLED
}

/**
 * État NFC courant, relu à intervalle régulier tant qu'il est collecté. Pas de récepteur de
 * `ACTION_ADAPTER_STATE_CHANGED` : la diffusion vient du processus NFC et non du système, si
 * bien qu'un récepteur non exporté ne reçoit pas toujours le retour à ON (constaté sur
 * appareil réel) ; la relecture évite d'exposer un récepteur exporté.
 */
fun Context.nfcAvailabilityFlow(): Flow<NfcAvailability> =
    flow {
        while (true) {
            emit(nfcAvailability())
            delay(NFC_POLL_INTERVAL_MILLIS)
        }
    }.distinctUntilChanged()

/** État NFC courant pour l'interface, relu tant que l'écran est visible. */
@Composable
fun rememberNfcAvailability(): State<NfcAvailability> {
    val context = LocalContext.current
    val availability = remember(context) { context.nfcAvailabilityFlow() }
    return availability.collectAsStateWithLifecycle(initialValue = remember(context) { context.nfcAvailability() })
}

/** Délai maximal entre un changement d'état du NFC et sa prise en compte. */
private const val NFC_POLL_INTERVAL_MILLIS = 1_000L
