package io.github.mgdx.sceau.ui.trust

import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.core.trust.InvalidMasterListException
import io.github.mgdx.sceau.core.trust.MasterListInfo
import io.github.mgdx.sceau.core.trust.TrustSource
import io.github.mgdx.sceau.trust.ImportDecision
import io.github.mgdx.sceau.trust.ImportLimitException
import io.github.mgdx.sceau.trust.ImportLimits
import io.github.mgdx.sceau.trust.TrustStoreRepository
import io.github.mgdx.sceau.ui.common.SceauIcons
import io.github.mgdx.sceau.ui.result.Countries
import io.github.mgdx.sceau.ui.result.currentLocale
import io.github.mgdx.sceau.ui.result.rememberDateFormatter
import io.github.mgdx.sceau.ui.result.toUtcDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

/** Types MIME proposés au sélecteur : Master Lists CMS (`.ml`, `.der`, `.p7b`), et tout fichier en repli. */
private val MASTER_LIST_TYPES =
    arrayOf(
        "application/octet-stream",
        "application/pkcs7-mime",
        "application/pkcs7-signature",
        "application/x-pkcs7-certificates",
        "*/*",
    )

private sealed interface StoreUi {
    data object Loading : StoreUi

    data object Failed : StoreUi

    class Loaded(
        val groups: List<CountryGroup>,
        val total: Int,
        val hasImported: Boolean,
    ) : StoreUi
}

/** Master List vérifiée, en attente de confirmation. Ses octets ne sont pas des données personnelles. */
private class PendingImport(
    val bytes: ByteArray,
    val info: MasterListInfo,
)

/** Écran « Magasin de confiance » (SPEC §5.4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustStoreScreen(
    repository: TrustStoreRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = currentLocale()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var reloadKey by remember { mutableIntStateOf(0) }
    var store by remember { mutableStateOf<StoreUi>(StoreUi.Loading) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(reloadKey, locale) {
        store = StoreUi.Loading
        store =
            try {
                val trustStore = repository.get()
                withContext(Dispatchers.Default) {
                    val anchors = trustStore.anchors
                    StoreUi.Loaded(
                        groups = groupByCountry(anchors, locale),
                        total = anchors.size,
                        hasImported = anchors.any { it.source == TrustSource.IMPORTED_MASTER_LIST },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                StoreUi.Failed
            } catch (_: NotImplementedError) {
                StoreUi.Failed
            }
    }

    fun showMessage(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                busy = true
                try {
                    val bytes =
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_MASTER_LIST_BYTES) }
                                ?: throw IOException("OPEN")
                        }
                    // Limites vérifiées avant l'analyse et la confirmation (D22).
                    repository.checkImportAllowed(bytes)
                    pending = PendingImport(bytes, repository.preview(bytes).info)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ImportLimitException) {
                    showMessage(importLimitMessage(resources, e.decision))
                } catch (_: FileTooLargeException) {
                    showMessage(resources.getString(R.string.trust_import_too_large))
                } catch (e: InvalidMasterListException) {
                    showMessage(resources.getString(R.string.trust_import_invalid, e.code))
                } catch (_: Exception) {
                    showMessage(resources.getString(R.string.trust_import_read_error))
                } catch (_: NotImplementedError) {
                    showMessage(resources.getString(R.string.trust_import_read_error))
                } finally {
                    busy = false
                }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trust_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(SceauIcons.ArrowBack, contentDescription = stringResource(R.string.trust_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = store) {
            StoreUi.Loading -> {
                CenteredMessage(stringResource(R.string.trust_loading), Modifier.padding(padding), progress = true)
            }

            StoreUi.Failed -> {
                CenteredMessage(stringResource(R.string.trust_load_error), Modifier.padding(padding)) {
                    Button(onClick = { reloadKey++ }) { Text(stringResource(R.string.trust_retry)) }
                }
            }

            is StoreUi.Loaded -> {
                StoreList(
                    store = current,
                    query = query,
                    onQueryChange = { query = it },
                    busy = busy,
                    expanded = expanded,
                    onImport = { launcher.launch(MASTER_LIST_TYPES) },
                    onDelete = { confirmDelete = true },
                    contentPadding = padding,
                )
            }
        }
    }

    pending?.let { request ->
        ImportConfirmDialog(
            info = request.info,
            onConfirm = {
                pending = null
                scope.launch {
                    busy = true
                    try {
                        repository.import(request.bytes)
                        showMessage(
                            resources.getQuantityString(
                                R.plurals.trust_import_done,
                                request.info.certificateCount,
                                request.info.certificateCount,
                            ),
                        )
                        reloadKey++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ImportLimitException) {
                        showMessage(importLimitMessage(resources, e.decision))
                    } catch (e: InvalidMasterListException) {
                        showMessage(resources.getString(R.string.trust_import_invalid, e.code))
                    } catch (_: Exception) {
                        showMessage(resources.getString(R.string.trust_import_write_error))
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { pending = null },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.trust_delete_confirm_title)) },
            text = { Text(stringResource(R.string.trust_delete_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            busy = true
                            try {
                                repository.clearImported()
                                showMessage(resources.getString(R.string.trust_delete_done))
                                reloadKey++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                showMessage(resources.getString(R.string.trust_delete_error))
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.trust_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.trust_cancel)) }
            },
        )
    }
}

/** Message d'un import refusé par les limites de [ImportLimits] (D22). */
private fun importLimitMessage(
    resources: Resources,
    decision: ImportDecision,
): String =
    if (decision == ImportDecision.TOO_LARGE_TOTAL) {
        resources.getString(R.string.trust_import_limit_size, ImportLimits.MAX_IMPORTED_TOTAL_BYTES / BYTES_PER_MB)
    } else {
        resources.getQuantityString(
            R.plurals.trust_import_limit_count,
            ImportLimits.MAX_IMPORTED_LISTS,
            ImportLimits.MAX_IMPORTED_LISTS,
        )
    }

private const val BYTES_PER_MB = 1024L * 1024L

@Composable
private fun CenteredMessage(
    message: String,
    modifier: Modifier = Modifier,
    progress: Boolean = false,
    action: @Composable () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (progress) CircularProgressIndicator()
            Text(message, style = MaterialTheme.typography.bodyLarge)
            action()
        }
    }
}

@Composable
private fun StoreList(
    store: StoreUi.Loaded,
    query: String,
    onQueryChange: (String) -> Unit,
    busy: Boolean,
    expanded: SnapshotStateMap<String, Boolean>,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    contentPadding: PaddingValues,
) {
    val formatDate = rememberDateFormatter()
    val groups = remember(store, query) { filterGroups(store.groups, query) }
    val searching = query.isNotBlank()
    // Sans cela, la liste garde l'élément qui était en tête et masque l'en-tête rétabli.
    val listState = rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }
    Column(modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        // Hors de la liste pour rester visible quand on la fait défiler.
        SearchField(query, onQueryChange)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            // Pendant une recherche, les résultats viennent juste sous le champ.
            if (!searching) {
                item(key = "header") {
                    StoreHeader(store, busy, onImport, onDelete)
                }
            }
            if (groups.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text =
                            if (store.groups.isEmpty()) {
                                stringResource(R.string.trust_empty)
                            } else {
                                stringResource(R.string.trust_search_no_result)
                            },
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            countryItems(groups, expanded, formatDate)
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.trust_search_hint)) },
        leadingIcon = { Icon(SceauIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(SceauIcons.Close, contentDescription = stringResource(R.string.trust_search_clear))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

private fun LazyListScope.countryItems(
    groups: List<CountryGroup>,
    expanded: SnapshotStateMap<String, Boolean>,
    formatDate: (LocalDate) -> String,
) {
    groups.forEach { group ->
        val isExpanded = expanded[group.key] == true
        stickyHeader(key = group.key, contentType = "country") {
            CountryHeader(group, isExpanded) { expanded[group.key] = !isExpanded }
        }
        if (isExpanded) {
            items(group.anchors, key = { it.key }, contentType = { "anchor" }) { anchor ->
                AnchorItem(anchor, formatDate)
            }
        }
    }
}

@Composable
private fun StoreHeader(
    store: StoreUi.Loaded,
    busy: Boolean,
    onImport: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.trust_intro), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = pluralStringResource(R.plurals.trust_summary, store.total, store.total, store.groups.size),
            style = MaterialTheme.typography.titleSmall,
        )
        Button(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.trust_import))
        }
        OutlinedButton(onClick = onDelete, enabled = !busy && store.hasImported, modifier = Modifier.fillMaxWidth()) {
            Icon(SceauIcons.Delete, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.trust_delete_imported))
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                Text(stringResource(R.string.trust_busy), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CountryHeader(
    group: CountryGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    // Nom localisé déjà résolu hors du thread principal par groupByCountry : pas de
    // Locale.getDisplayCountry à chaque composition d'un en-tête.
    val name = group.displayName
    val flag = Countries.flagEmoji(group.alpha2)
    val label =
        when {
            group.alpha2.isEmpty() -> stringResource(R.string.trust_country_unknown)
            name == null -> group.alpha2
            flag != null -> "$flag $name"
            else -> name
        }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel =
                            stringResource(if (expanded) R.string.trust_country_collapse else R.string.trust_country_expand),
                        onClick = onToggle,
                    ).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = pluralStringResource(R.plurals.trust_country_count, group.anchors.size, group.anchors.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(if (expanded) SceauIcons.ExpandLess else SceauIcons.ExpandMore, contentDescription = null)
        }
    }
}

@Composable
private fun AnchorItem(
    anchor: AnchorRow,
    formatDate: (LocalDate) -> String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Badge(stringResource(sourceBadge(anchor.source)))
            if (anchor.isLink) Badge(stringResource(R.string.trust_link_certificate))
        }
        Text(anchor.subject, style = MaterialTheme.typography.bodyMedium)
        Text(
            text =
                stringResource(
                    R.string.trust_validity,
                    formatDate(anchor.notBefore.toUtcDate()),
                    formatDate(anchor.notAfter.toUtcDate()),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.trust_fingerprint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = formatFingerprint(anchor.sha256),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

private fun sourceBadge(source: TrustSource): Int =
    when (source) {
        TrustSource.ANTS -> R.string.trust_source_ants
        TrustSource.NATIONAL -> R.string.trust_source_national
        TrustSource.EMBEDDED_MASTER_LIST -> R.string.trust_source_master_list
        TrustSource.IMPORTED_MASTER_LIST -> R.string.trust_source_imported
    }

@Composable
private fun Badge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun ImportConfirmDialog(
    info: MasterListInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val formatDate = rememberDateFormatter()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trust_import_confirm_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.trust_import_confirm_text), style = MaterialTheme.typography.bodyMedium)
                DialogField(stringResource(R.string.trust_import_signer)) {
                    Text(info.signerSubject, style = MaterialTheme.typography.bodyMedium)
                }
                DialogField(stringResource(R.string.trust_import_signer_fingerprint)) {
                    SelectionContainer {
                        Text(
                            text = formatFingerprint(info.signerSha256),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                DialogField(stringResource(R.string.trust_import_signing_date)) {
                    Text(
                        text =
                            info.signingTime?.let { formatDate(it.toUtcDate()) }
                                ?: stringResource(R.string.trust_import_signing_date_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                DialogField(stringResource(R.string.trust_import_certificate_count)) {
                    Text(info.certificateCount.toString(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.trust_import_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.trust_cancel)) } },
    )
}

@Composable
private fun DialogField(
    label: String,
    value: @Composable () -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        value()
    }
}
