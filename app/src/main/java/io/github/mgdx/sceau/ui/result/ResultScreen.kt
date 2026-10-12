package io.github.mgdx.sceau.ui.result

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.core.model.Dg1Data
import io.github.mgdx.sceau.core.model.DocumentData
import io.github.mgdx.sceau.core.model.EncodedImage
import io.github.mgdx.sceau.core.model.Sex
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.report.VerificationReport
import io.github.mgdx.sceau.session.ReadState
import io.github.mgdx.sceau.session.SessionViewModel
import io.github.mgdx.sceau.ui.common.SceauIcons
import io.github.mgdx.sceau.ui.common.SecureWindow
import io.github.mgdx.sceau.ui.theme.LightOnSurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Maximum number of lines for a field value or a detail line (audit V12). */
private const val FIELD_MAX_LINES = 10
private const val DETAIL_MAX_LINES = 8

/**
 * Result screen (SPEC §5.3). The displayed data lives only in the session:
 * no saveable state, no image cache, bitmaps wiped on exit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    session: SessionViewModel,
    onClear: () -> Unit,
) {
    SecureWindow()
    val state by session.state.collectAsState()
    val done = state as? ReadState.Done
    val report = done?.report

    // Avoids leaving the screen twice ("Clear", then the state switching to Idle).
    var left by remember { mutableStateOf(false) }
    val leave = {
        if (!left) {
            left = true
            onClear()
        }
    }
    val clearAndLeave = {
        session.clear()
        leave()
    }
    BackHandler { clearAndLeave() }

    if (report == null) {
        // Data cleared (background, process death): nothing to display, back to the home screen.
        LaunchedEffect(Unit) { leave() }
        Surface(modifier = Modifier.fillMaxSize()) {}
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.result_title)) },
                actions = {
                    TextButton(onClick = clearAndLeave) {
                        Icon(SceauIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.result_clear))
                    }
                },
            )
        },
    ) { padding ->
        ResultContent(
            report = report,
            isDemo = done?.isDemo == true,
            bitmapOwner = remember(session, report) { BitmapOwner(session, report) },
            onClear = clearAndLeave,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun ResultContent(
    report: VerificationReport,
    isDemo: Boolean,
    bitmapOwner: BitmapOwner,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatDate = rememberDateFormatter()
    val document = report.document
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isDemo) DemoBanner()
        VerdictCard(report.verdict, importedAnchor = CheckFormatting.isImportedAnchor(report.chain, report.cardSecurityChain))
        when (IdentityPresentation.mode(report)) {
            // Decision D36: eID-UB card, with no identity data in its chip. Neither empty fields nor
            // an eID logo presented as the holder's photo.
            IdentityPresentation.Mode.IDENTITYLESS_EID_UB -> {
                NoIdentitySection(document.dg1)
                document.signature?.let { SignatureSection(it, bitmapOwner) }
                AdditionalSection(document, formatDate, bitmapOwner)
            }

            // Audit V24: nothing was read; neither empty fields nor a photo placeholder.
            IdentityPresentation.Mode.NOT_READ -> {
                DataNotReadNotice()
            }

            IdentityPresentation.Mode.FULL -> {
                PortraitSection(document.portrait, bitmapOwner)
                IdentitySection(document.dg1, formatDate)
                document.signature?.let { SignatureSection(it, bitmapOwner) }
                AdditionalSection(document, formatDate, bitmapOwner)
            }
        }
        ChecksSection(report.checks, formatDate)
        Button(
            onClick = onClear,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Icon(SceauIcons.Delete, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.result_clear))
        }
    }
}

// --- Verdict ---------------------------------------------------------------------------

/** Report of a simulated document (demo mode of the debug APK): never mistaken for a real one. */
@Composable
private fun DemoBanner() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.result_demo_banner),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun VerdictCard(
    verdict: Verdict,
    importedAnchor: Boolean,
) {
    val palette = VerdictColors.palette(verdict, isDarkPalette())
    val (icon, title, explanation) =
        when (verdict) {
            Verdict.AUTHENTIC -> {
                Triple(SceauIcons.CheckCircle, R.string.result_verdict_authentic, R.string.result_verdict_authentic_explanation)
            }

            Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED -> {
                Triple(
                    SceauIcons.Warning,
                    R.string.result_verdict_chip_unverified,
                    R.string.result_verdict_chip_unverified_explanation,
                )
            }

            Verdict.UNKNOWN_ISSUER -> {
                Triple(SceauIcons.Help, R.string.result_verdict_unknown_issuer, R.string.result_verdict_unknown_issuer_explanation)
            }

            Verdict.FAILED -> {
                Triple(SceauIcons.Cancel, R.string.result_verdict_failed, R.string.result_verdict_failed_explanation)
            }
        }
    Surface(
        color = Color(palette.container),
        contentColor = Color(palette.content),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(text = stringResource(explanation), style = MaterialTheme.typography.bodyMedium)
            if (importedAnchor) ImportedAnchorNotice()
        }
    }
}

/** Warning notice: the chain relies on an imported certificate (audit V6). */
@Composable
private fun ImportedAnchorNotice() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(SceauIcons.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.result_verdict_imported_anchor),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

// --- Images ----------------------------------------------------------------------------

private sealed interface ImageLoad {
    data object Loading : ImageLoad

    data object Failed : ImageLoad

    class Loaded(
        val image: ImageBitmap,
    ) : ImageLoad
}

/** Displayed bitmap, handed to the session: closing it wipes it, then releases it. */
private class DecodedBitmap(
    val bitmap: Bitmap,
) : AutoCloseable {
    override fun close() = bitmap.wipeAndRecycle()
}

/** Holds the decoded bitmap so that it can be wiped when it leaves the composition. */
private class BitmapHolder {
    var decoded: DecodedBitmap? = null
}

/**
 * Session and report to which decoded bitmaps are handed (audit V13): clearing the session
 * (button, back, background) wipes them immediately, without waiting for a recomposition.
 */
private class BitmapOwner(
    private val session: SessionViewModel,
    private val report: VerificationReport,
) {
    /** False if the report is no longer displayed: [bitmap] has then already been wiped. */
    fun adopt(bitmap: DecodedBitmap): Boolean = session.registerDisposable(report, bitmap)

    fun release(bitmap: DecodedBitmap) = session.unregisterDisposable(bitmap)
}

/**
 * A single image decode at a time across the whole process (audit V4): DG2, DG7 and the two
 * DG12 images never add up their decoding memory.
 */
private val decodeMutex = Mutex()

/** Decoding state and the modifier to apply to the image slot. */
private class DecodedImage(
    val load: ImageLoad,
    /** Triggers decoding as soon as the slot appears at least partly on screen. */
    val trigger: Modifier,
)

/**
 * Decodes [image] off the main thread, in memory only, once its slot is displayed and after
 * the decodes already in progress. The bitmap is wiped, then released, when the image leaves
 * the composition, when the session is cleared, or as soon as it is decoded if the screen
 * has gone or the session has been cleared in the meantime.
 */
@Composable
private fun rememberDecodedImage(
    image: EncodedImage?,
    owner: BitmapOwner,
): DecodedImage {
    val context = LocalContext.current.applicationContext
    val holder = remember(image) { BitmapHolder() }
    var load by remember(image) { mutableStateOf(if (image == null) ImageLoad.Failed else ImageLoad.Loading) }
    var shown by remember(image) { mutableStateOf(false) }
    val trigger =
        Modifier.onGloballyPositioned { coordinates ->
            // Bounds clipped by the scrolling container: empty as long as the image is off screen.
            if (!shown && !coordinates.boundsInWindow().isEmpty) shown = true
        }
    LaunchedEffect(image, shown) {
        if (image == null || !shown) return@LaunchedEffect
        // Assigned inside the block itself: a bitmap produced during cancellation is not lost.
        var bitmap: Bitmap? = null
        var kept = false
        try {
            decodeMutex.withLock { withContext(Dispatchers.Default) { bitmap = decodeToBitmap(context, image) } }
            val decoded = bitmap?.let(::DecodedBitmap)
            if (decoded != null && owner.adopt(decoded)) {
                kept = true
                holder.decoded = decoded
                load = ImageLoad.Loaded(decoded.bitmap.asImageBitmap())
            } else {
                load = ImageLoad.Failed
            }
        } finally {
            // Coroutine cancelled (screen left) or session cleared during decoding.
            if (!kept) bitmap?.wipeAndRecycle()
        }
    }
    DisposableEffect(holder) {
        onDispose {
            holder.decoded?.let {
                owner.release(it)
                it.close()
            }
            holder.decoded = null
        }
    }
    return DecodedImage(load, trigger)
}

@Composable
private fun PortraitSection(
    portrait: EncodedImage?,
    bitmapOwner: BitmapOwner,
) {
    val decoded = rememberDecodedImage(portrait, bitmapOwner)
    val load = decoded.load
    var fullScreen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ImageBox(
            load = load,
            description = stringResource(R.string.result_photo_description),
            unavailable = stringResource(R.string.result_photo_unavailable),
            modifier = Modifier.width(200.dp).height(260.dp).then(decoded.trigger),
        )
        if (load is ImageLoad.Loaded) {
            OutlinedButton(onClick = { fullScreen = true }) {
                Icon(SceauIcons.Fullscreen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.result_photo_fullscreen))
            }
            if (fullScreen) {
                FullScreenImage(load.image, onDismiss = { fullScreen = false })
            }
        }
    }
}

@Composable
private fun ImageBox(
    load: ImageLoad,
    description: String,
    unavailable: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (load) {
                ImageLoad.Loading -> {
                    CircularProgressIndicator()
                }

                ImageLoad.Failed -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Icon(
                            SceauIcons.Person,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = contentColor,
                        )
                        Text(
                            text = unavailable,
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor,
                        )
                    }
                }

                is ImageLoad.Loaded -> {
                    Image(
                        bitmap = load.image,
                        contentDescription = description,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** Full-screen photo. The dialog window also gets `FLAG_SECURE`. */
@Composable
private fun FullScreenImage(
    image: ImageBitmap,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                securePolicy = SecureFlagPolicy.SecureOn,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .safeDrawingPadding(),
        ) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.result_photo_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(SceauIcons.Close, contentDescription = stringResource(R.string.result_photo_close), tint = Color.White)
            }
        }
    }
}

// --- Identity --------------------------------------------------------------------------

@Composable
private fun IdentitySection(
    dg1: Dg1Data,
    formatDate: (LocalDate) -> String,
) {
    val unknown = stringResource(R.string.result_unknown_value)
    val expiry = dg1.dateOfExpiry
    val expired = expiry != null && expiry.isBefore(LocalDate.now())
    SectionCard(title = stringResource(R.string.result_section_identity)) {
        if (expired) ExpiredBanner()
        FieldRow(R.string.result_field_surname, dg1.primaryIdentifier.ifBlank { unknown })
        val givenNames = dg1.secondaryIdentifiers.filter { it.isNotBlank() }.joinToString(" ")
        if (givenNames.isNotEmpty()) FieldRow(R.string.result_field_given_names, givenNames)
        FieldRow(R.string.result_field_sex, stringResource(sexLabel(dg1.sex)))
        FieldRow(R.string.result_field_birth_date, dg1.dateOfBirth?.let(formatDate) ?: unknown)
        FieldRow(R.string.result_field_nationality, countryWithCode(dg1.nationality))
        FieldRow(
            R.string.result_field_document_type,
            stringResource(
                R.string.result_value_with_code,
                stringResource(DocumentKind.fromMrzCode(dg1.documentCode).label),
                dg1.documentCode.replace("<", "").trim(),
            ),
        )
        FieldRow(R.string.result_field_document_number, dg1.documentNumber.replace("<", "").ifBlank { unknown })
        FieldRow(R.string.result_field_issuing_state, countryWithCode(dg1.issuingState))
        FieldRow(R.string.result_field_expiry_date, expiry?.let(formatDate) ?: unknown)
    }
}

/** eID-UB card (D36): only the document kind and the issuing state are real data. */
@Composable
private fun NoIdentitySection(dg1: Dg1Data) {
    SectionCard(title = stringResource(R.string.result_section_identity)) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.result_no_identity_data),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
        FieldRow(
            R.string.result_field_document_type,
            stringResource(
                R.string.result_value_with_code,
                stringResource(DocumentKind.fromMrzCode(dg1.documentCode).label),
                dg1.documentCode.replace("<", "").trim(),
            ),
        )
        FieldRow(R.string.result_field_issuing_state, countryWithCode(dg1.issuingState))
    }
}

/** No data read after the Chip Authentication failed (audit V24, decision D40). */
@Composable
private fun DataNotReadNotice() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.result_data_not_read),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun countryWithCode(icaoCode: String): String {
    val country = Countries.fromIcao(icaoCode)
    val label = countryLabel(country)
    return if (country.code.isEmpty() || label == country.code) {
        label
    } else {
        stringResource(R.string.result_value_with_code, label, country.code)
    }
}

private fun sexLabel(sex: Sex): Int =
    when (sex) {
        Sex.MALE -> R.string.result_sex_male
        Sex.FEMALE -> R.string.result_sex_female
        Sex.UNSPECIFIED -> R.string.result_sex_unspecified
    }

@Composable
private fun ExpiredBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(SceauIcons.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.result_expired),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

// --- Signature (DG7) ----------------------------------------------------------------------

/**
 * Holder's handwritten signature (DG7, decision D37), decoded like the photo. It is usually
 * black on a white background: displayed on a white background in both themes, so that it
 * stays legible in dark mode.
 */
@Composable
private fun SignatureSection(
    signature: EncodedImage,
    bitmapOwner: BitmapOwner,
) {
    val decoded = rememberDecodedImage(signature, bitmapOwner)
    val title = stringResource(R.string.result_section_signature)
    SectionCard(title = title) {
        ImageBox(
            load = decoded.load,
            description = title,
            unavailable = stringResource(R.string.result_image_unavailable),
            modifier = Modifier.fillMaxWidth().height(120.dp).then(decoded.trigger),
            containerColor = Color.White,
            contentColor = LightOnSurfaceVariant,
        )
    }
}

// --- Additional data ---------------------------------------------------------------------

@Composable
private fun AdditionalSection(
    document: DocumentData,
    formatDate: (LocalDate) -> String,
    bitmapOwner: BitmapOwner,
) {
    val dg11 = document.dg11
    val dg12 = document.dg12
    if (dg11 == null && dg12 == null) return
    val fields = additionalFields(dg11, dg12, formatDate)
    val front = dg12?.frontImage
    val rear = dg12?.rearImage
    if (fields.isEmpty() && front == null && rear == null) return
    SectionCard(title = stringResource(R.string.result_section_additional)) {
        fields.forEach { FieldRow(it.label, it.value) }
        if (front != null) DocumentImage(front, R.string.result_image_front, bitmapOwner)
        if (rear != null) DocumentImage(rear, R.string.result_image_rear, bitmapOwner)
    }
}

@Composable
private fun DocumentImage(
    image: EncodedImage,
    label: Int,
    bitmapOwner: BitmapOwner,
) {
    val decoded = rememberDecodedImage(image, bitmapOwner)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ImageBox(
            load = decoded.load,
            description = stringResource(label),
            unavailable = stringResource(R.string.result_image_unavailable),
            modifier = Modifier.fillMaxWidth().height(200.dp).then(decoded.trigger),
        )
    }
}

// --- Checks ------------------------------------------------------------------------------

@Composable
private fun ChecksSection(
    checks: List<Check>,
    formatDate: (LocalDate) -> String,
) {
    SectionCard(title = stringResource(R.string.result_section_checks)) {
        CheckFormatting.orderedChecks(checks).forEachIndexed { index, check ->
            if (index > 0) HorizontalDivider()
            CheckRow(check, formatDate)
        }
    }
}

@Composable
private fun CheckRow(
    check: Check,
    formatDate: (LocalDate) -> String,
) {
    var expanded by remember { mutableStateOf(false) }
    val dark = isDarkPalette()
    val (icon, tint) =
        when (check.status) {
            CheckStatus.OK -> {
                SceauIcons.CheckCircle to Color(VerdictColors.palette(Verdict.AUTHENTIC, dark).container)
            }

            CheckStatus.FAILED -> {
                SceauIcons.Cancel to Color(VerdictColors.palette(Verdict.FAILED, dark).container)
            }

            CheckStatus.NOT_AVAILABLE -> {
                SceauIcons.RemoveCircle to MaterialTheme.colorScheme.onSurfaceVariant
            }

            CheckStatus.UNSUPPORTED_ALGORITHM -> {
                SceauIcons.Warning to Color(VerdictColors.palette(Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED, dark).container)
            }
        }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel =
                            stringResource(if (expanded) R.string.result_check_collapse else R.string.result_check_expand),
                    ) { expanded = !expanded }
                    .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(CheckFormatting.title(check.id)), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(CheckFormatting.status(check.status)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) SceauIcons.ExpandLess else SceauIcons.ExpandMore,
                contentDescription = null,
            )
        }
        if (expanded) {
            val countryLabels = CheckFormatting.countryCodes(check).associateWith { countryLabel(Countries.fromAlpha2(it)) }
            val lines = CheckFormatting.detailLines(check, formatDate) { countryLabels[it] ?: it }.map { it.resolve() }
            Column(
                modifier = Modifier.padding(start = 36.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                lines.forEach {
                    // Certificate subjects and codes coming from the chip: sanitized (audit V12).
                    Text(
                        text = displaySafe(it),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = DETAIL_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// --- Common components ---------------------------------------------------------------------

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun FieldRow(
    label: Int,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Values coming from the chip (DG1, DG11, DG12, unknown country code): sanitized (audit V12).
        // Restricted to accessibility tools (TalkBack) on Android 14+: any other accessibility
        // service sees neither the value nor its events.
        Text(
            text = displaySafe(value),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = FIELD_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { isSensitiveData = true },
        )
    }
}
