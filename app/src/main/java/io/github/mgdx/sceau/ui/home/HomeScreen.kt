package io.github.mgdx.sceau.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.nfc.NfcAvailability
import io.github.mgdx.sceau.nfc.rememberNfcAvailability
import io.github.mgdx.sceau.session.AccessForm
import io.github.mgdx.sceau.session.DocumentTab
import io.github.mgdx.sceau.session.SessionViewModel
import io.github.mgdx.sceau.ui.common.SceauIcons
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { OverflowMenu(onOpenTrustStore = onOpenTrustStore, onOpenAbout = onOpenAbout) },
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
                    DocumentTab.PASSPORT -> MrzFields(form = form, session = session)
                }
                Text(
                    text = stringResource(R.string.home_holder_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        form.toAccessKey()?.let { key ->
                            session.prepare(key)
                            onRead()
                        }
                    },
                    enabled = form.isComplete && nfc != NfcAvailability.ABSENT,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.home_read))
                }
            }
        }
    }
}

@Composable
private fun OverflowMenu(
    onOpenTrustStore: () -> Unit,
    onOpenAbout: () -> Unit,
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
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MrzFields(
    form: AccessForm,
    session: SessionViewModel,
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
                imeAction = ImeAction.Done,
            ),
        modifier = Modifier.fillMaxWidth(),
    )
    DateField(
        date = form.dateOfBirth,
        label = stringResource(R.string.home_date_of_birth_label),
        chooseDescription = stringResource(R.string.home_date_of_birth_choose),
        initialDisplayMode = DisplayMode.Input,
        pastOnly = true,
        onDateChange = session::onDateOfBirthChange,
    )
    DateField(
        date = form.dateOfExpiry,
        label = stringResource(R.string.home_date_of_expiry_label),
        chooseDescription = stringResource(R.string.home_date_of_expiry_choose),
        initialDisplayMode = DisplayMode.Picker,
        pastOnly = false,
        onDateChange = session::onDateOfExpiryChange,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    date: LocalDate?,
    label: String,
    chooseDescription: String,
    initialDisplayMode: DisplayMode,
    pastOnly: Boolean,
    onDateChange: (LocalDate?) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { if (it is PressInteraction.Release) showDialog = true }
    }

    OutlinedTextField(
        value = date?.format(formatter).orEmpty(),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = { showDialog = true }) {
                Icon(imageVector = SceauIcons.Calendar, contentDescription = chooseDescription)
            }
        },
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth(),
    )

    if (showDialog) {
        // Le sélecteur Material 3 mémorise sa saisie par rememberSaveable : sans registre,
        // la date ne peut pas finir dans l'état sauvegardé de l'activité (SPEC §8).
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            val pickerState =
                remember {
                    DatePickerState(
                        locale = locale,
                        initialSelectedDateMillis = date?.let(AccessForm::dateToPickerMillis),
                        initialDisplayMode = initialDisplayMode,
                        selectableDates = if (pastOnly) PastOrToday else AnyDate,
                    )
                }
            DatePickerDialog(
                onDismissRequest = { showDialog = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDialog = false
                            onDateChange(pickerState.selectedDateMillis?.let(AccessForm::dateFromPickerMillis))
                        },
                        enabled = pickerState.selectedDateMillis != null,
                    ) {
                        Text(stringResource(R.string.home_date_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text(stringResource(R.string.home_date_cancel))
                    }
                },
            ) {
                DatePicker(
                    state = pickerState,
                    title = {
                        Text(
                            text = label,
                            modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp).semantics { heading() },
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private object PastOrToday : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        AccessForm.dateFromPickerMillis(utcTimeMillis) <= LocalDate.now(ZoneOffset.UTC)

    override fun isSelectableYear(year: Int): Boolean = year <= LocalDate.now(ZoneOffset.UTC).year
}

@OptIn(ExperimentalMaterial3Api::class)
private object AnyDate : SelectableDates
