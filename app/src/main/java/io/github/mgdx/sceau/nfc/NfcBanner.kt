package io.github.mgdx.sceau.nfc

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mgdx.sceau.R

/**
 * Bandeau affiché quand le NFC est absent ou désactivé, sur l'accueil et sur l'écran de lecture
 * (le NFC peut être coupé depuis le volet rapide pendant l'attente du document). Les chaînes
 * restent dans `strings_home.xml`, où elles sont nées.
 */
@Composable
fun NfcBanner(
    nfc: NfcAvailability,
    modifier: Modifier = Modifier,
) {
    if (nfc == NfcAvailability.ENABLED) return
    val context = LocalContext.current
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text =
                    stringResource(
                        if (nfc == NfcAvailability.ABSENT) R.string.home_nfc_absent else R.string.home_nfc_disabled,
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (nfc == NfcAvailability.DISABLED) {
                TextButton(onClick = { openNfcSettings(context) }) {
                    Text(stringResource(R.string.home_nfc_settings))
                }
            }
        }
    }
}

private fun openNfcSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
    } catch (e: ActivityNotFoundException) {
        // Certains constructeurs n'exposent pas l'écran NFC dédié.
        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }
}
