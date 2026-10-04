package io.github.mgdx.sceau.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.core.trust.MasterListInfo
import io.github.mgdx.sceau.core.trust.TrustSource
import io.github.mgdx.sceau.trust.TrustStoreRepository
import io.github.mgdx.sceau.ui.common.SceauIcons
import io.github.mgdx.sceau.ui.result.Countries
import io.github.mgdx.sceau.ui.result.currentLocale
import io.github.mgdx.sceau.ui.result.rememberDateFormatter
import io.github.mgdx.sceau.ui.result.toUtcDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Contenu embarqué du magasin, pour la section correspondante. */
private sealed interface EmbeddedUi {
    data object Loading : EmbeddedUi

    data object Failed : EmbeddedUi

    class Loaded(
        val masterList: MasterListInfo?,
        /** Codes pays des certificats publiés par leur propre État (source NATIONAL), triés. */
        val nationalCountries: List<String>,
    ) : EmbeddedUi
}

/** Écran « À propos » (SPEC §5.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    repository: TrustStoreRepository,
    onBack: () -> Unit,
    onReplayIntro: () -> Unit,
    onOpenChipContents: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val version = remember(context) { versionName(context) }
    val repositoryUrl = stringResource(R.string.about_repository_url)
    val noBrowser = stringResource(R.string.about_no_browser)

    var embedded by remember { mutableStateOf<EmbeddedUi>(EmbeddedUi.Loading) }
    LaunchedEffect(repository) {
        embedded =
            try {
                val store = repository.get()
                EmbeddedUi.Loaded(
                    masterList = store.embeddedMasterList,
                    nationalCountries =
                        store.anchors
                            .filter { it.source == TrustSource.NATIONAL }
                            .map { it.country }
                            .distinct()
                            .sorted(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                EmbeddedUi.Failed
            } catch (_: NotImplementedError) {
                EmbeddedUi.Failed
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(SceauIcons.ArrowBack, contentDescription = stringResource(R.string.about_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = version?.let { stringResource(R.string.about_version, it) } ?: stringResource(R.string.about_version_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyLarge)
            }

            // Écrans pédagogiques (D35).
            Section(stringResource(R.string.learn_about_section)) {
                OutlinedButton(onClick = onOpenChipContents) { Text(stringResource(R.string.learn_chip_title)) }
                OutlinedButton(onClick = onReplayIntro) { Text(stringResource(R.string.learn_about_replay_intro)) }
            }

            Section(stringResource(R.string.about_section_license)) {
                Paragraph(stringResource(R.string.about_license))
                Paragraph(repositoryUrl)
                OutlinedButton(
                    onClick = {
                        if (!openUrl(context, repositoryUrl)) scope.launch { snackbar.showSnackbar(noBrowser) }
                    },
                ) { Text(stringResource(R.string.about_open_repository)) }
            }

            Section(stringResource(R.string.about_section_trust)) {
                EmbeddedTrustStore(embedded)
            }

            Section(stringResource(R.string.about_section_limits)) {
                Paragraph(stringResource(R.string.about_limit_unknown_issuer))
                Paragraph(stringResource(R.string.about_limit_revocation))
            }

            Section(stringResource(R.string.about_section_usage)) {
                Paragraph(stringResource(R.string.about_usage_holder))
                Paragraph(stringResource(R.string.about_usage_biometrics))
                Paragraph(stringResource(R.string.about_usage_privacy))
                Paragraph(stringResource(R.string.about_usage_no_warranty))
                Paragraph(stringResource(R.string.about_usage_not_official))
            }
        }
    }
}

@Composable
private fun EmbeddedTrustStore(embedded: EmbeddedUi) {
    when (embedded) {
        EmbeddedUi.Loading -> {
            Paragraph(stringResource(R.string.about_trust_loading))
        }

        EmbeddedUi.Failed -> {
            Paragraph(stringResource(R.string.about_trust_error))
        }

        is EmbeddedUi.Loaded -> {
            val masterList = embedded.masterList
            if (masterList == null) {
                Paragraph(stringResource(R.string.about_trust_ants_only))
            } else {
                val formatDate = rememberDateFormatter()
                Paragraph(stringResource(R.string.about_trust_ants_and_bsi))
                Paragraph(
                    masterList.signingTime?.let { stringResource(R.string.about_trust_master_list_date, formatDate(it.toUtcDate())) }
                        ?: stringResource(R.string.about_trust_master_list_date_unknown),
                )
                Paragraph(stringResource(R.string.about_trust_bsi_notice))
            }
            if (embedded.nationalCountries.isNotEmpty()) {
                val locale = currentLocale()
                val names = embedded.nationalCountries.map { Countries.displayName(it, locale) ?: it }
                Paragraph(stringResource(R.string.about_trust_national, names.joinToString(", ")))
            }
            if ("GB" in embedded.nationalCountries) {
                Paragraph(stringResource(R.string.about_trust_ogl_notice))
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

/** Nom de version lu dans le PackageManager (pas de BuildConfig), ou null s'il est introuvable. */
private fun versionName(context: Context): String? =
    try {
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        info.versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

/** Ouvre [url] dans le navigateur ; faux si aucune application ne peut l'ouvrir. */
private fun openUrl(
    context: Context,
    url: String,
): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
