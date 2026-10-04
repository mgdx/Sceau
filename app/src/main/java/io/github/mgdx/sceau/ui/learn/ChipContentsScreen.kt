package io.github.mgdx.sceau.ui.learn

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.ui.common.SceauIcons

/**
 * Écran « Ce que contient la puce » (D35) : ce que Sceau lit et affiche, ce qu'il lit pour la
 * seule vérification, ce qu'il ne lit jamais et ce qu'il ne fait pas. La liste des fichiers
 * reprend exactement ceux que demande `:core` (docs/protocol.md §2) : EF.CardAccess, EF.COM,
 * EF.SOD, DG14, DG1, DG2, DG15, DG11, DG12, et EF.CardSecurity après PACE-CAM seulement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipContentsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.learn_chip_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(SceauIcons.ArrowBack, contentDescription = stringResource(R.string.learn_chip_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LearnParagraph(stringResource(R.string.learn_chip_intro))

            Section(SceauIcons.CheckCircle, MaterialTheme.colorScheme.secondary, R.string.learn_chip_section_displayed) {
                FileEntry(R.string.learn_chip_label_dg1, R.string.learn_chip_dg1)
                FileEntry(R.string.learn_chip_label_dg2, R.string.learn_chip_dg2)
                FileEntry(R.string.learn_chip_label_dg11, R.string.learn_chip_dg11)
                FileEntry(R.string.learn_chip_label_dg12, R.string.learn_chip_dg12)
            }

            Section(LearnIcons.VerifiedUser, MaterialTheme.colorScheme.primary, R.string.learn_chip_section_verification) {
                Text(stringResource(R.string.learn_chip_verification_intro), style = MaterialTheme.typography.bodyMedium)
                FileEntry(R.string.learn_chip_label_ef_card_access, R.string.learn_chip_ef_card_access)
                FileEntry(R.string.learn_chip_label_ef_com, R.string.learn_chip_ef_com)
                FileEntry(R.string.learn_chip_label_ef_sod, R.string.learn_chip_ef_sod)
                FileEntry(R.string.learn_chip_label_dg14, R.string.learn_chip_dg14)
                FileEntry(R.string.learn_chip_label_dg15, R.string.learn_chip_dg15)
                FileEntry(R.string.learn_chip_label_ef_card_security, R.string.learn_chip_ef_card_security)
            }

            Section(SceauIcons.Cancel, MaterialTheme.colorScheme.error, R.string.learn_chip_section_never) {
                FileEntry(R.string.learn_chip_label_dg3, R.string.learn_chip_dg3)
                FileEntry(R.string.learn_chip_label_dg4, R.string.learn_chip_dg4)
                Text(stringResource(R.string.learn_chip_eac), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.learn_chip_others), style = MaterialTheme.typography.bodyMedium)
            }

            Section(LearnIcons.CloudOff, MaterialTheme.colorScheme.primary, R.string.learn_chip_section_never_does) {
                LearnBullet(stringResource(R.string.learn_chip_no_storage))
                LearnBullet(stringResource(R.string.learn_chip_no_screenshot))
                LearnBullet(stringResource(R.string.learn_chip_no_write))
                LearnBullet(stringResource(R.string.learn_chip_no_other_data))
            }
        }
    }
}

@Composable
private fun Section(
    icon: ImageVector,
    tint: Color,
    @StringRes title: Int,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // Décorative : le titre de la section porte le sens.
                Icon(icon, contentDescription = null, tint = tint)
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            content()
        }
    }
}

@Composable
private fun FileEntry(
    @StringRes label: Int,
    @StringRes description: Int,
) {
    LearnFileRow(stringResource(label), stringResource(description))
}
