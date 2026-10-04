package io.github.mgdx.sceau.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.ui.scan.ScanDiagnostics
import io.github.mgdx.sceau.ui.scan.ScanDiagnosticsSnapshot

/** Bandeau de diagnostic de l'écran de scan (APK de debug) : mesures et compteurs, aucun caractère lu. */
@Composable
internal fun ScanDiagnosticsBannerContent(
    snapshot: ScanDiagnosticsSnapshot,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = BACKGROUND_ALPHA))
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.scan_debug_rate, snapshot.framesPerSecond, snapshot.meanAnalysisMillis),
            color = Color.White,
            style = style,
        )
        Text(
            text =
                stringResource(
                    R.string.scan_debug_size,
                    snapshot.imageWidth,
                    snapshot.imageHeight,
                    snapshot.cropWidth,
                    snapshot.cropHeight,
                ),
            color = Color.White,
            style = style,
        )
        Text(
            text =
                stringResource(
                    R.string.scan_debug_outcomes,
                    ScanDiagnostics.WINDOW_SIZE,
                    snapshot.nothingFound,
                    snapshot.unstable,
                    snapshot.unsupported,
                    snapshot.found,
                ),
            color = Color.White,
            style = style,
        )
    }
}

private const val BACKGROUND_ALPHA = 0.6f
