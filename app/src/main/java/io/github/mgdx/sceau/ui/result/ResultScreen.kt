package io.github.mgdx.sceau.ui.result

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
import io.github.mgdx.sceau.session.SessionViewModel
import io.github.mgdx.sceau.ui.common.SecureWindow

/** Écran de résultat (SPEC §5.3). Stub du lot Socle, implémenté par un autre lot. */
@Composable
fun ResultScreen(
    session: SessionViewModel,
    onClear: () -> Unit,
) {
    SecureWindow()
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.result_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}
