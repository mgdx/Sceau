package io.github.mgdx.sceau.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.demo.DemoMode
import io.github.mgdx.sceau.nfc.NfcAvailability
import io.github.mgdx.sceau.nfc.rememberNfcAvailability
import io.github.mgdx.sceau.session.AccessForm
import io.github.mgdx.sceau.session.DateError
import io.github.mgdx.sceau.session.DateOrder
import io.github.mgdx.sceau.session.DatePart
import io.github.mgdx.sceau.session.DocumentTab
import io.github.mgdx.sceau.session.SessionViewModel
import io.github.mgdx.sceau.ui.common.SceauIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran d'accueil (SPEC §5.1). La saisie vit dans le [SessionViewModel] : aucun
 * `rememberSaveable`, qui l'écrirait dans l'état sauvegardé de l'activité.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    session: SessionViewModel,
    onRead: () -> Unit,
    onOpenTrustStore: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val nfc by rememberNfcAvailability()
    val form = session.form
    val locale = LocalConfiguration.current.locales[0]
    val dateOrder = remember(locale) { DateOrder.forLocale(locale) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    OverflowMenu(
                        onOpenTrustStore = onOpenTrustStore,
                        onOpenAbout = onOpenAbout,
                        onStartDemo = { startDemo(session, scope, onRead) },
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
        ) {
            PrimaryTabRow(selectedTabIndex = form.tab.ordinal) {
                Tab(
                    selected = form.tab == DocumentTab.ID_CARD,
                    onClick = { session.selectTab(DocumentTab.ID_CARD) },
                    text = { Text(stringResource(R.string.home_tab_id_card)) },
                )
                Tab(
                    selected = form.tab == DocumentTab.PASSPORT,
                    onClick = { session.selectTab(DocumentTab.PASSPORT) },
                    text = { Text(stringResource(R.string.home_tab_passport)) },
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                NfcBanner(nfc)
                when (form.tab) {
                    DocumentTab.ID_CARD -> CanFields(form = form, onCanChange = session::onCanChange)
                    DocumentTab.PASSPORT -> MrzFields(form = form, session = session, order = dateOrder)
                }
                Text(
                    text = stringResource(R.string.home_holder_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        form.toAccessKey(dateOrder)?.let { key ->
                            session.prepare(key)
                            onRead()
                        }
                    },
                    enabled = form.isComplete(dateOrder) && nfc != NfcAvailability.ABSENT,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.home_read))
                }
            }
        }
    }
}

/**
 * Mode démo (APK de debug uniquement) : la CNIe simulée est fabriquée hors du thread principal
 * (génération de clés), puis lue par le même chemin qu'un vrai document.
 */
private fun startDemo(
    session: SessionViewModel,
    scope: CoroutineScope,
    onRead: () -> Unit,
) {
    scope.launch {
        val card = withContext(Dispatchers.Default) { DemoMode.newSimulatedCnie() } ?: return@launch
        session.startDemo(card)
        onRead()
    }
}

@Composable
private fun OverflowMenu(
    onOpenTrustStore: () -> Unit,
    onOpenAbout: () -> Unit,
    onStartDemo: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(imageVector = SceauIcons.MoreVert, contentDescription = stringResource(R.string.home_menu_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_menu_trust_store)) },
                onClick = {
                    expanded = false
                    onOpenTrustStore()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_menu_about)) },
                onClick = {
                    expanded = false
                    onOpenAbout()
                },
            )
            if (DemoMode.isAvailable) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_demo)) },
                    onClick = {
                        expanded = false
                        onStartDemo()
                    },
                )
            }
        }
    }
}

@Composable
private fun NfcBanner(nfc: NfcAvailability) {
    if (nfc == NfcAvailability.ENABLED) return
    val context = LocalContext.current
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        modifier = Modifier.fillMaxWidth(),
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

@Composable
private fun CanFields(
    form: AccessForm,
    onCanChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = form.can,
        onValueChange = onCanChange,
        label = { Text(stringResource(R.string.home_can_label)) },
        supportingText = { Text(stringResource(R.string.home_can_supporting)) },
        singleLine = true,
        // NumberPassword : le clavier ne mémorise ni ne suggère la saisie.
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
        modifier = Modifier.fillMaxWidth().sensitiveInput(),
    )
}

@Composable
private fun MrzFields(
    form: AccessForm,
    session: SessionViewModel,
    order: DateOrder,
) {
    OutlinedTextField(
        value = form.documentNumber,
        onValueChange = session::onDocumentNumberChange,
        label = { Text(stringResource(R.string.home_document_number_label)) },
        supportingText = { Text(stringResource(R.string.home_document_number_supporting)) },
        singleLine = true,
        // Password : le clavier ne mémorise ni ne suggère la saisie.
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
        modifier = Modifier.fillMaxWidth().sensitiveInput(),
    )
    DateField(
        digits = form.dateOfBirthDigits,
        label = stringResource(R.string.home_date_of_birth_label),
        order = order,
        error = AccessForm.dateOfBirthError(form.dateOfBirthDigits, order),
        imeAction = ImeAction.Next,
        onValueChange = session::onDateOfBirthChange,
    )
    DateField(
        digits = form.dateOfExpiryDigits,
        label = stringResource(R.string.home_date_of_expiry_label),
        order = order,
        error = AccessForm.dateOfExpiryError(form.dateOfExpiryDigits, order),
        imeAction = ImeAction.Done,
        onValueChange = session::onDateOfExpiryChange,
    )
}

/**
 * Date saisie au clavier numérique, sans calendrier : les séparateurs sont ajoutés à
 * l'affichage ([DateDigitsTransformation]), seuls les chiffres sont gardés dans le ViewModel.
 */
@Composable
private fun DateField(
    digits: String,
    label: String,
    order: DateOrder,
    error: DateError?,
    imeAction: ImeAction,
    onValueChange: (String) -> Unit,
) {
    val partLabels =
        mapOf(
            DatePart.DAY to stringResource(R.string.home_date_day),
            DatePart.MONTH to stringResource(R.string.home_date_month),
            DatePart.YEAR to stringResource(R.string.home_date_year),
        )
    val format = order.parts.joinToString(DateDigitsTransformation.SEPARATOR.toString()) { partLabels.getValue(it) }
    val supporting =
        when (error) {
            null -> stringResource(R.string.home_date_format, format)
            DateError.INVALID -> stringResource(R.string.home_date_error_invalid)
            DateError.FUTURE -> stringResource(R.string.home_date_error_future)
            DateError.TOO_OLD -> stringResource(R.string.home_date_error_too_old, AccessForm.MIN_EXPIRY_YEAR)
        }
    val transformation = remember(order) { DateDigitsTransformation(order) }
    OutlinedTextField(
        value = digits,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(format) },
        supportingText = { Text(supporting) },
        isError = error != null,
        singleLine = true,
        visualTransformation = transformation,
        // NumberPassword : clavier numérique, qui ne mémorise ni ne suggère la saisie.
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                autoCorrectEnabled = false,
                imeAction = imeAction,
            ),
        modifier = Modifier.fillMaxWidth().sensitiveInput(),
    )
}

/**
 * Clé d'accès en cours de saisie : sur Android 14+, seuls les services d'accessibilité déclarés
 * outils d'accessibilité (`isAccessibilityTool`, comme TalkBack) voient le champ et reçoivent
 * ses événements ; les autres services, qui pourraient lire le CAN ou la MRZ, ne les voient pas.
 * Sans effet avant Android 14.
 */
private fun Modifier.sensitiveInput(): Modifier = semantics { isSensitiveData = true }
