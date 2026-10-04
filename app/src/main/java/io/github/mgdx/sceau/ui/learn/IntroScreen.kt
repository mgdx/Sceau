package io.github.mgdx.sceau.ui.learn

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.ui.common.SceauIcons
import kotlinx.coroutines.launch

/** Pages de l'introduction, dans l'ordre (D35). */
internal enum class IntroPage {
    CHIP,
    KEY,
    VERIFY,
    PRIVACY,
}

/**
 * Introduction du premier lancement (D35), rejouable depuis l'écran À propos.
 *
 * « Passer l'introduction » (toutes les pages sauf la dernière), « Commencer » (dernière page) et le
 * retour arrière du système appellent tous [onFinish], qui mémorise l'introduction comme vue et
 * quitte l'écran : vers l'accueil au premier lancement, vers À propos quand elle est rejouée.
 */
@Composable
fun IntroScreen(
    onFinish: () -> Unit,
    onOpenChipContents: () -> Unit,
) {
    val pages = IntroPage.entries
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == pages.lastIndex

    BackHandler(onBack = onFinish)

    Scaffold { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            ) { index ->
                IntroPageContent(pages[index], onOpenChipContents)
            }
            PageIndicator(
                current = pagerState.currentPage,
                count = pages.size,
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 8.dp),
            )
            // FlowRow : à grande taille de police, les boutons passent à la ligne au lieu d'être
            // tronqués ; l'espaceur pousse Précédent et Suivant à droite de leur ligne.
            FlowRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isLastPage) {
                    TextButton(onClick = onFinish) { Text(stringResource(R.string.learn_intro_skip)) }
                }
                Spacer(Modifier.weight(1f))
                if (pagerState.currentPage > 0) {
                    // Navigation sans balayage (accès par contacteur, TalkBack).
                    OutlinedButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                    ) { Text(stringResource(R.string.learn_intro_previous)) }
                }
                if (isLastPage) {
                    Button(onClick = onFinish) { Text(stringResource(R.string.learn_intro_start)) }
                } else {
                    Button(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                    ) { Text(stringResource(R.string.learn_intro_next)) }
                }
            }
        }
    }
}

@Composable
private fun IntroPageContent(
    page: IntroPage,
    onOpenChipContents: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (page) {
            IntroPage.CHIP -> {
                PageHeader(SceauIcons.Nfc, stringResource(R.string.learn_intro_chip_title))
                LearnParagraph(stringResource(R.string.learn_intro_chip_body))
                LearnParagraph(stringResource(R.string.learn_intro_chip_copy))
                Note(stringResource(R.string.learn_intro_chip_icao))
            }

            IntroPage.KEY -> {
                PageHeader(LearnIcons.Lock, stringResource(R.string.learn_intro_key_title))
                LearnParagraph(stringResource(R.string.learn_intro_key_body))
                LearnBullet(stringResource(R.string.learn_intro_key_can))
                LearnBullet(stringResource(R.string.learn_intro_key_mrz))
                LearnParagraph(stringResource(R.string.learn_intro_key_channel))
            }

            IntroPage.VERIFY -> {
                PageHeader(LearnIcons.VerifiedUser, stringResource(R.string.learn_intro_verify_title))
                LearnBullet(stringResource(R.string.learn_intro_verify_signature))
                LearnBullet(stringResource(R.string.learn_intro_verify_clone))
                LearnParagraph(stringResource(R.string.learn_intro_verify_verdicts))
                // Mêmes libellés et explications que la carte du verdict de l'écran de résultat.
                Verdict(R.string.result_verdict_authentic, R.string.result_verdict_authentic_explanation)
                Verdict(R.string.result_verdict_chip_unverified, R.string.result_verdict_chip_unverified_explanation)
                Verdict(R.string.result_verdict_unknown_issuer, R.string.result_verdict_unknown_issuer_explanation)
                Verdict(R.string.result_verdict_failed, R.string.result_verdict_failed_explanation)
            }

            IntroPage.PRIVACY -> {
                PageHeader(LearnIcons.CloudOff, stringResource(R.string.learn_intro_privacy_title))
                LearnBullet(stringResource(R.string.learn_intro_privacy_offline))
                LearnBullet(stringResource(R.string.learn_intro_privacy_nothing_saved))
                LearnBullet(stringResource(R.string.learn_intro_privacy_screenshots))
                OutlinedButton(onClick = onOpenChipContents) { Text(stringResource(R.string.learn_chip_title)) }
            }
        }
    }
}

@Composable
private fun PageHeader(
    icon: ImageVector,
    title: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            modifier =
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            // Décorative : le titre qui suit dit tout.
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun Verdict(
    @StringRes name: Int,
    @StringRes explanation: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(name), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(explanation), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Points de pagination ; annoncé « Page 2 sur 4 » par les lecteurs d'écran. */
@Composable
private fun PageIndicator(
    current: Int,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.learn_intro_page, current + 1, count)
    Row(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                contentDescription = description
                // TalkBack annonce chaque changement de page, balayage compris.
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(if (selected) 24.dp else 8.dp, label = "largeur")
            val color by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                label = "couleur",
            )
            Box(
                Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
