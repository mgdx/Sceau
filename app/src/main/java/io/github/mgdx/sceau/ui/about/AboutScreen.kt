package io.github.mgdx.sceau.ui.about

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.trust.TrustStoreRepository

/** Écran « À propos » (SPEC §5.5). Stub du lot Socle, implémenté par un autre lot. */
@Composable
fun AboutScreen(
    repository: TrustStoreRepository,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.about_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}
