package io.github.mgdx.sceau.ui.home

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import io.github.mgdx.sceau.nfc.NfcBanner
import io.github.mgdx.sceau.nfc.rememberNfcAvailability
import io.github.mgdx.sceau.session.AccessForm
import io.github.mgdx.sceau.session.DateError
import io.github.mgdx.sceau.session.DateOrder
import io.github.mgdx.sceau.session.DatePart
import io.github.mgdx.sceau.session.DocumentTab
import io.github.mgdx.sceau.session.IdCardKey
import io.github.mgdx.sceau.session.SessionViewModel
import io.github.mgdx.sceau.ui.common.SceauIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home screen (SPEC §5.1). The input lives in the [SessionViewModel]: no
 * `rememberSaveable`, which would write it into the activity's saved state.
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
                    selected = form.tab == DocumentTab.PASSPORT,
                    onClick = { session.selectTab(DocumentTab.PASSPORT) },
                    text = { Text(stringResource(R.string.home_tab_passport)) },
                )
                Tab(
                    selected = form.tab == DocumentTab.ID_CARD,
                    onClick = { session.selectTab(DocumentTab.ID_CARD) },
                    text = { Text(stringResource(R.string.home_tab_id_card)) },
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                NfcBanner(nfc)
                if (form.tab == DocumentTab.ID_CARD) {
                    IdCardKeySelector(selected = form.idCardKey, onSelect = session::selectIdCardKey)
                }
                // Neither Copy nor Cut on the access key (V37, D45).
                NoCopyTextToolbarProvider {
                    if (form.usesCan) {
                        CanFields(form = form, onCanChange = session::onCanChange)
                    } else {
                        MrzFields(form = form, session = session, order = dateOrder)
                    }
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
 * Demo mode (debug APK only): the simulated CNIe is built off the main thread
 * (key generation), then read through the same path as a real document.
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

/** Choice of the access key of an identity card: CAN (default) or MRZ (D31). */
@Composable
private fun IdCardKeySelector(
    selected: IdCardKey,
    onSelect: (IdCardKey) -> Unit,
) {
    val keys = IdCardKey.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        keys.forEachIndexed { index, key ->
            SegmentedButton(
                selected = key == selected,
                onClick = { onSelect(key) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = keys.size),
            ) {
                Text(
                    stringResource(
                        when (key) {
                            IdCardKey.CAN -> R.string.home_can_label
                            IdCardKey.MRZ -> R.string.home_mrz_label
                        },
                    ),
                )
            }
        }
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
        // NumberPassword: the keyboard neither remembers nor suggests the input.
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = form.documentNumber,
        onValueChange = session::onDocumentNumberChange,
        label = { Text(stringResource(R.string.home_document_number_label)) },
        supportingText = {
            Text(
                stringResource(
                    if (form.tab == DocumentTab.ID_CARD) {
                        R.string.home_document_number_supporting_id_card
                    } else {
                        R.string.home_document_number_supporting
                    },
                ),
            )
        },
        singleLine = true,
        // Password: the keyboard neither remembers nor suggests the input.
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
        onValueChange = { input ->
            val wasComplete = form.dateOfExpiryDigits.length == AccessForm.DATE_DIGITS
            session.onDateOfExpiryChange(input)
            // Last digit of the last field: the keyboard is dismissed and uncovers the "Read" button.
            if (!wasComplete && session.form.dateOfExpiryDigits.length == AccessForm.DATE_DIGITS) {
                keyboard?.hide()
                focusManager.clearFocus()
            }
        },
    )
}

/**
 * Date typed on the numeric keyboard, without a calendar: separators are added on
 * display ([DateDigitsTransformation]), only the digits are kept in the ViewModel.
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
        // NumberPassword: numeric keyboard, which neither remembers nor suggests the input.
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
 * Access key being entered: on Android 14+, only accessibility services declared as
 * accessibility tools (`isAccessibilityTool`, like TalkBack) see the field and receive its
 * events; the other services, which could read the CAN or the MRZ, do not see them.
 * No effect before Android 14. The context menu offers neither Copy nor Cut (V37, D45).
 */
private fun Modifier.sensitiveInput(): Modifier = semantics { isSensitiveData = true }.noCopyContextMenu()
