package io.github.mgdx.sceau.nfc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

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
 * État NFC courant, mis à jour quand l'adaptateur change d'état
 * (`ACTION_ADAPTER_STATE_CHANGED`) et à chaque reprise de l'écran (retour des réglages).
 */
@Composable
fun rememberNfcAvailability(): State<NfcAvailability> {
    val context = LocalContext.current
    val availability = remember(context) { mutableStateOf(context.nfcAvailability()) }

    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context,
                    intent: Intent,
                ) {
                    availability.value = context.nfcAvailability()
                }
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    LifecycleResumeEffect(context) {
        availability.value = context.nfcAvailability()
        onPauseOrDispose { }
    }

    return availability
}
