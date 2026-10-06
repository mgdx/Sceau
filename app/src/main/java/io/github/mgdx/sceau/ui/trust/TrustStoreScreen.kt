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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.core.trust.InvalidCertificateException
import io.github.mgdx.sceau.core.trust.InvalidMasterListException
import io.github.mgdx.sceau.core.trust.MasterListInfo
import io.github.mgdx.sceau.core.trust.TrustSource
import io.github.mgdx.sceau.trust.CertificateSummary
import io.github.mgdx.sceau.trust.Duplicate
import io.github.mgdx.sceau.trust.DuplicateImportException
import io.github.mgdx.sceau.trust.ImportDecision
import io.github.mgdx.sceau.trust.ImportLimitException
import io.github.mgdx.sceau.trust.ImportLimits
import io.github.mgdx.sceau.trust.ImportPreview
import io.github.mgdx.sceau.trust.ImportedItem
import io.github.mgdx.sceau.trust.TrustStoreRepository
import io.github.mgdx.sceau.trust.UnrecognizedFileException
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

/**
 * Types MIME proposés au sélecteur : Master Lists CMS (`.ml`, `.der`, `.p7b`), certificats
 * (`.der`, `.cer`, `.crt`, `.pem`, D33), et tout fichier en repli.
 */
private val IMPORT_TYPES =
    arrayOf(
        "application/octet-stream",
        "application/pkcs7-mime",
        "application/pkcs7-signature",
        "application/x-pkcs7-certificates",
        "application/pkix-cert",
        "application/x-x509-ca-cert",
        "application/x-pem-file",
        "*/*",
    )

private sealed interface StoreUi {
    data object Loading : StoreUi

    data object Failed : StoreUi

    class Loaded(
        val groups: List<CountryGroup>,
        val total: Int,
    ) : StoreUi
}

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

    // Chargés à part du magasin fusionné (audit V22) : un import qui bloque ou fait échouer le
    // chargement reste supprimable.
    var imported by remember { mutableStateOf<List<ImportedItem>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ImportPreview?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var toRemove by remember { mutableStateOf<ImportedItem?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(reloadKey) {
        imported =
            try {
                repository.importedItems()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
    }

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
                    // Master List ou certificat seul, reconnu automatiquement (D33) ; limites
                    // vérifiées avant la confirmation (D22).
                    pending = repository.previewImport(bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DuplicateImportException) {
                    showMessage(duplicateMessage(resources, e.duplicate))
                } catch (e: ImportLimitException) {
                    showMessage(importLimitMessage(resources, e.decision))
                } catch (_: FileTooLargeException) {
                    showMessage(resources.getString(R.string.trust_import_too_large))
                } catch (e: InvalidMasterListException) {
                    showMessage(invalidMasterListMessage(resources, e.code))
                } catch (e: InvalidCertificateException) {
                    showMessage(resources.getString(R.string.trust_import_certificate_invalid, e.code))
                } catch (_: UnrecognizedFileException) {
                    showMessage(resources.getString(R.string.trust_import_unrecognized))
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
                PendingStore(stringResource(R.string.trust_loading), imported, busy, { toRemove = it }, padding, progress = true)
            }

            StoreUi.Failed -> {
                PendingStore(stringResource(R.string.trust_load_error), imported, busy, { toRemove = it }, padding) {
                    Button(onClick = { reloadKey++ }) { Text(stringResource(R.string.trust_retry)) }
                }
            }

            is StoreUi.Loaded -> {
                StoreList(
                    store = current,
                    imported = imported,
                    query = query,
                    onQueryChange = { query = it },
                    busy = busy,
                    expanded = expanded,
                    onImport = { launcher.launch(IMPORT_TYPES) },
                    onDelete = { confirmDelete = true },
                    onRemove = { toRemove = it },
                    contentPadding = padding,
                )
            }
        }
    }

    pending?.let { request ->
        val onConfirm: () -> Unit = {
            pending = null
            scope.launch {
                busy = true
                try {
                    when (request) {
                        is ImportPreview.MasterList -> {
                            repository.import(request.bytes)
                            showMessage(
                                resources.getQuantityString(
                                    R.plurals.trust_import_done,
                                    request.info.certificateCount,
                                    request.info.certificateCount,
                                ),
                            )
                        }

                        is ImportPreview.Certificate -> {
                            repository.importCertificate(request.bytes)
                            showMessage(resources.getString(R.string.trust_import_certificate_done))
                        }
                    }
                    reloadKey++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DuplicateImportException) {
                    showMessage(duplicateMessage(resources, e.duplicate))
                } catch (e: ImportLimitException) {
                    showMessage(importLimitMessage(resources, e.decision))
                } catch (e: InvalidMasterListException) {
                    showMessage(invalidMasterListMessage(resources, e.code))
                } catch (e: InvalidCertificateException) {
                    showMessage(resources.getString(R.string.trust_import_certificate_invalid, e.code))
                } catch (_: Exception) {
                    val error =
                        if (request is ImportPreview.Certificate) {
                            R.string.trust_import_certificate_write_error
                        } else {
                            R.string.trust_import_write_error
                        }
                    showMessage(resources.getString(error))
                } finally {
                    busy = false
                }
            }
        }
        when (request) {
            is ImportPreview.MasterList -> {
                ImportConfirmDialog(request.info, request.newCertificates, onConfirm, onDismiss = {
                    pending =
                        null
                })
            }

            is ImportPreview.Certificate -> {
                CertificateConfirmDialog(request.summary, onConfirm, onDismiss = { pending = null })
            }
        }
    }

    toRemove?.let { item ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text(stringResource(R.string.trust_imported_delete_title)) },
            text = { Text(stringResource(R.string.trust_imported_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        toRemove = null
                        scope.launch {
                            busy = true
                            try {
                                repository.removeImported(item.id)
                                showMessage(resources.getString(R.string.trust_imported_delete_done))
                                reloadKey++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                showMessage(resources.getString(R.string.trust_imported_delete_error))
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.trust_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { toRemove = null }) { Text(stringResource(R.string.trust_cancel)) }
            },
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

/** Message d'un import refusé par les limites de [ImportLimits] (D22, D33). */
private fun importLimitMessage(
    resources: Resources,
    decision: ImportDecision,
): String =
    when (decision) {
        ImportDecision.TOO_LARGE_TOTAL -> {
            resources.getString(R.string.trust_import_limit_size, ImportLimits.MAX_IMPORTED_TOTAL_BYTES / BYTES_PER_MB)
        }

        ImportDecision.TOO_MANY_CERTIFICATES -> {
            resources.getQuantityString(
                R.plurals.trust_import_certificate_limit_count,
                ImportLimits.MAX_IMPORTED_CERTIFICATES,
                ImportLimits.MAX_IMPORTED_CERTIFICATES,
            )
        }

        ImportDecision.CERTIFICATE_TOO_LARGE -> {
            resources.getString(R.string.trust_import_certificate_too_large, ImportLimits.MAX_CERTIFICATE_BYTES / BYTES_PER_KB)
        }

        else -> {
            resources.getQuantityString(
                R.plurals.trust_import_limit_count,
                ImportLimits.MAX_IMPORTED_LISTS,
                ImportLimits.MAX_IMPORTED_LISTS,
            )
        }
    }

/** Message d'un fichier déjà présent dans le magasin, refusé dès l'aperçu (D33). */
private fun duplicateMessage(
    resources: Resources,
    duplicate: Duplicate,
): String =
    resources.getString(
        when (duplicate) {
            Duplicate.ImportedCertificate -> {
                R.string.trust_import_duplicate_certificate
            }

            Duplicate.ImportedMasterList -> {
                R.string.trust_import_duplicate_master_list
            }

            Duplicate.EmbeddedMasterList -> {
                R.string.trust_import_duplicate_embedded_master_list
            }

            is Duplicate.KnownCertificate -> {
                when (duplicate.source) {
                    TrustSource.ANTS -> R.string.trust_import_duplicate_known_ants
                    TrustSource.NATIONAL -> R.string.trust_import_duplicate_known_national
                    TrustSource.EMBEDDED_MASTER_LIST -> R.string.trust_import_duplicate_known_embedded_master_list
                    TrustSource.IMPORTED_MASTER_LIST -> R.string.trust_import_duplicate_known_imported_master_list
                    TrustSource.IMPORTED_CERTIFICATE -> R.string.trust_import_duplicate_certificate
                }
            }
        },
    )

private const val BYTES_PER_MB = 1024L * 1024L
private const val BYTES_PER_KB = 1024

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

/**
 * Magasin en cours de chargement ou en échec : [message], puis les éléments importés, qui restent
 * supprimables à l'unité (audit V22). Sans élément importé, le message seul, centré.
 */
@Composable
private fun PendingStore(
    message: String,
    imported: List<ImportedItem>,
    busy: Boolean,
    onRemove: (ImportedItem) -> Unit,
    contentPadding: PaddingValues,
    progress: Boolean = false,
    action: @Composable () -> Unit = {},
) {
    if (imported.isEmpty()) {
        CenteredMessage(message, Modifier.padding(contentPadding), progress, action)
        return
    }
    val formatDate = rememberDateFormatter()
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "pending") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (progress) CircularProgressIndicator()
                Text(message, style = MaterialTheme.typography.bodyLarge)
                action()
            }
        }
        importedItems(imported, busy, formatDate, onRemove)
    }
}

@Composable
private fun StoreList(
    store: StoreUi.Loaded,
    imported: List<ImportedItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    busy: Boolean,
    expanded: SnapshotStateMap<String, Boolean>,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    onRemove: (ImportedItem) -> Unit,
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
                    StoreHeader(store, imported.isNotEmpty(), busy, onImport, onDelete)
                }
                importedItems(imported, busy, formatDate, onRemove)
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
    hasImported: Boolean,
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
        OutlinedButton(onClick = onDelete, enabled = !busy && hasImported, modifier = Modifier.fillMaxWidth()) {
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
            name == null -> displayCountryCode(group.alpha2)
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
        SubjectText(anchor.subject, MaterialTheme.typography.bodyMedium)
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
        TrustSource.IMPORTED_CERTIFICATE -> R.string.trust_source_imported_certificate
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
    newCertificates: Int,
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
                    SubjectText(info.signerSubject, MaterialTheme.typography.bodyMedium)
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
                    Text(
                        text =
                            pluralStringResource(
                                R.plurals.trust_import_new_certificates,
                                newCertificates,
                                newCertificates,
                                info.certificateCount,
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
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

/** Section « Éléments importés » (D33) : une ligne par Master List ou certificat, supprimable à l'unité. */
private fun LazyListScope.importedItems(
    items: List<ImportedItem>,
    busy: Boolean,
    formatDate: (LocalDate) -> String,
    onRemove: (ImportedItem) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "imported-title") {
        Text(
            text = stringResource(R.string.trust_imported_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
    }
    items(items, key = { "imported:${it.id}" }, contentType = { "imported" }) { item ->
        ImportedItemRow(item, busy, formatDate) { onRemove(item) }
    }
    item(key = "imported-end") { Spacer(Modifier.padding(bottom = 16.dp)) }
}

@Composable
private fun ImportedItemRow(
    item: ImportedItem,
    busy: Boolean,
    formatDate: (LocalDate) -> String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (item) {
                is ImportedItem.MasterListItem -> MasterListItemContent(item.info, formatDate)
                is ImportedItem.CertificateItem -> CertificateItemContent(item.summary, formatDate)
            }
        }
        IconButton(onClick = onRemove, enabled = !busy) {
            Icon(SceauIcons.Delete, contentDescription = stringResource(R.string.trust_imported_delete))
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun MasterListItemContent(
    info: MasterListInfo?,
    formatDate: (LocalDate) -> String,
) {
    Badge(stringResource(R.string.trust_imported_master_list))
    if (info == null) {
        UnreadableItem()
        return
    }
    SubjectText(info.signerSubject, MaterialTheme.typography.bodyMedium)
    val details =
        listOfNotNull(
            info.signingTime?.let { stringResource(R.string.trust_imported_signed_on, formatDate(it.toUtcDate())) },
            pluralStringResource(R.plurals.trust_country_count, info.certificateCount, info.certificateCount),
        )
    details.forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CertificateItemContent(
    summary: CertificateSummary?,
    formatDate: (LocalDate) -> String,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Badge(stringResource(R.string.trust_source_imported_certificate))
        if (summary?.isLink == true) Badge(stringResource(R.string.trust_link_certificate))
    }
    if (summary == null) {
        UnreadableItem()
        return
    }
    SubjectText(summary.subject, MaterialTheme.typography.bodyMedium)
    Text(
        text = countryLabel(summary.country),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(R.string.trust_validity, formatDate(summary.notBefore.toUtcDate()), formatDate(summary.notAfter.toUtcDate())),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SelectionContainer {
        Text(
            text = formatFingerprint(summary.sha256),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun UnreadableItem() {
    Text(
        text = stringResource(R.string.trust_imported_unreadable),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * Sujet X.500 lu dans un fichier importé ou un certificat du magasin, assaini (audit V23) : une
 * seule ligne logique, sans caractère de contrôle ni de formatage bidirectionnel, longueur et
 * nombre de lignes affichées bornés.
 */
@Composable
private fun SubjectText(
    subject: String,
    style: TextStyle,
) {
    Text(
        text = displaySubject(subject),
        style = style,
        maxLines = SUBJECT_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Nom localisé du pays [alpha2], le code assaini s'il est inconnu, ou « Pays non indiqué ». */
@Composable
private fun countryLabel(alpha2: String): String {
    if (alpha2.isEmpty()) return stringResource(R.string.trust_country_unknown)
    return Countries.displayName(alpha2, currentLocale()) ?: displayCountryCode(alpha2)
}

@Composable
private fun CertificateConfirmDialog(
    summary: CertificateSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val formatDate = rememberDateFormatter()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trust_import_certificate_confirm_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.trust_import_certificate_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                DialogField(stringResource(R.string.trust_import_certificate_type)) {
                    Text(
                        text =
                            stringResource(
                                if (summary.isLink) R.string.trust_link_certificate else R.string.trust_import_certificate_type_csca,
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                DialogField(stringResource(R.string.trust_import_certificate_subject)) {
                    SubjectText(summary.subject, MaterialTheme.typography.bodyMedium)
                }
                DialogField(stringResource(R.string.trust_import_certificate_country)) {
                    Text(countryLabel(summary.country), style = MaterialTheme.typography.bodyMedium)
                }
                DialogField(stringResource(R.string.trust_import_certificate_validity)) {
                    Text(
                        text =
                            stringResource(
                                R.string.trust_validity,
                                formatDate(summary.notBefore.toUtcDate()),
                                formatDate(summary.notAfter.toUtcDate()),
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                DialogField(stringResource(R.string.trust_fingerprint)) {
                    SelectionContainer {
                        Text(
                            text = formatFingerprint(summary.sha256),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.trust_import_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.trust_cancel)) } },
    )
}
