package io.github.mgdx.sceau.ui.reading

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.session.ReadState
import io.github.mgdx.sceau.session.SessionViewModel
import io.github.mgdx.sceau.ui.common.SceauIcons
import io.github.mgdx.sceau.ui.common.SecureWindow
import kotlinx.coroutines.delay

/** Écran de lecture (SPEC §5.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    session: SessionViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    SecureWindow()
    val state by session.state.collectAsStateWithLifecycle()
    val currentOnDone by rememberUpdatedState(onDone)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val cancel = {
        session.clear()
        onCancel()
    }
    BackHandler(onBack = cancel)

    LaunchedEffect(state) {
        when (state) {
            is ReadState.Done -> currentOnDone()

            // Rien à lire (effacement en arrière-plan, mort du processus) : retour à l'accueil.
            ReadState.Idle -> currentOnCancel()

            else -> Unit
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.reading_title)) }) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val current = state) {
                is ReadState.Error -> ErrorContent(code = current.code, onRetry = session::retry, onCancel = cancel)
                else -> ProgressContent(state = current, onCancel = cancel)
            }
        }
    }
}

@Composable
private fun ProgressContent(
    state: ReadState,
    onCancel: () -> Unit,
) {
    PulsingNfcIcon()
    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.reading_prompt),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(24.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Step.entries.forEach { step -> StepRow(step = step, status = stepStatus(state, step)) }
    }
    SlowChipMessage(state)
    Spacer(Modifier.height(24.dp))
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(stringResource(R.string.reading_cancel))
    }
}

/** Message affiché si l'ouverture du canal sécurisé dure (voir [SlowChipHint]). */
@Composable
private fun SlowChipMessage(state: ReadState) {
    val timed = SlowChipHint.isTimed(state)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(timed) {
        shown = false
        if (timed) {
            delay(SlowChipHint.DELAY_MILLIS)
            shown = true
        }
    }
    if (shown) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.reading_slow_chip),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private enum class StepStatus { PENDING, IN_PROGRESS, DONE }

private fun stepStatus(
    state: ReadState,
    step: Step,
): StepStatus =
    when (state) {
        is ReadState.Reading -> {
            when {
                step.ordinal < state.current.ordinal -> StepStatus.DONE
                step == state.current -> StepStatus.IN_PROGRESS
                else -> StepStatus.PENDING
            }
        }

        is ReadState.Done -> {
            StepStatus.DONE
        }

        else -> {
            StepStatus.PENDING
        }
    }

@Composable
private fun StepRow(
    step: Step,
    status: StepStatus,
) {
    val statusLabel =
        stringResource(
            when (status) {
                StepStatus.PENDING -> R.string.reading_step_pending
                StepStatus.IN_PROGRESS -> R.string.reading_step_in_progress
                StepStatus.DONE -> R.string.reading_step_done
            },
        )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics(mergeDescendants = true) { stateDescription = statusLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (status) {
                StepStatus.DONE -> {
                    Icon(
                        imageVector = SceauIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                StepStatus.IN_PROGRESS -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }

                StepStatus.PENDING -> {
                    Unit
                }
            }
        }
        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(step.labelRes()),
            style = MaterialTheme.typography.bodyLarge,
            color =
                if (status == StepStatus.PENDING) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
    }
}

/** Pulsation lente de l'icône NFC ; immobile si les animations sont désactivées dans le système. */
@Composable
private fun PulsingNfcIcon() {
    val context = LocalContext.current
    val animationsEnabled =
        remember(context) {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }
    val modifier =
        if (animationsEnabled) {
            val transition = rememberInfiniteTransition(label = "nfc")
            val scale by transition.animateFloat(
                initialValue = 1f,
                targetValue = PULSE_SCALE,
                animationSpec = infiniteRepeatable(tween(PULSE_MILLIS, easing = LinearEasing), RepeatMode.Reverse),
                label = "nfcScale",
            )
            Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        } else {
            Modifier
        }
    Icon(
        imageVector = SceauIcons.Nfc,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(96.dp),
    )
}

@Composable
private fun ErrorContent(
    code: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Icon(
        imageVector = SceauIcons.Error,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(64.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.reading_error_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(ReadingErrors.messageFor(code)),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    // Code technique complet, pour toutes les erreurs : seul moyen de diagnostic, aucun journal
    // n'étant tenu (SPEC §8). Sélectionnable pour être recopié.
    Spacer(Modifier.height(8.dp))
    SelectionContainer {
        Text(
            text = stringResource(R.string.reading_error_code, code),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(stringResource(R.string.reading_retry))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(stringResource(R.string.reading_cancel))
    }
}

private const val PULSE_SCALE = 1.12f
private const val PULSE_MILLIS = 900
