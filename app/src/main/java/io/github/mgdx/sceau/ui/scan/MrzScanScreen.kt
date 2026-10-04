package io.github.mgdx.sceau.ui.scan

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.mrz.MrzKeyFields
import io.github.mgdx.sceau.mrz.MrzScanResult
import io.github.mgdx.sceau.mrz.MrzScanner
import io.github.mgdx.sceau.session.DocumentTab
import io.github.mgdx.sceau.session.SessionViewModel
import io.github.mgdx.sceau.ui.common.SceauIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** État de la permission caméra, vu par l'écran. */
private enum class CameraPermission {
    /** Pas encore demandée dans cet écran (dialogue système éventuellement affiché). */
    UNKNOWN,
    GRANTED,

    /** Refusée ; l'application peut encore la redemander. */
    DENIED,

    /** Refusée définitivement : seuls les réglages de l'application permettent de l'accorder. */
    PERMANENTLY_DENIED,
}

/** Ce qu'affiche la ligne d'état sous le cadre. */
internal enum class StatusLine { SEARCHING, SEEN, UNSUPPORTED, SUCCESS, CAMERA_UNAVAILABLE }

/** Délai entre le succès et le retour à l'accueil, le temps de l'annonce TalkBack. */
private const val SUCCESS_DELAY_MILLIS = 900L

/**
 * Écran de scan de la MRZ (D32) : aperçu de la caméra arrière, cadre de visée, consigne,
 * torche. Au succès, transmet les champs à [session] puis appelle [onDone]. Le résultat ne
 * passe ni par l'état sauvegardé ni par la navigation.
 */
@Composable
fun MrzScanScreen(
    session: SessionViewModel,
    onDone: () -> Unit,
) {
    // Onglet Passeport : page de passeport ; onglet Carte d'identité (segment MRZ, D31) : carte TD1.
    val specimen = if (session.form.tab == DocumentTab.PASSPORT) SpecimenKind.PASSPORT else SpecimenKind.ID_CARD
    val context = LocalContext.current
    val activity = LocalActivity.current
    var permission by remember {
        mutableStateOf(if (hasCameraPermission(context)) CameraPermission.GRANTED else CameraPermission.UNKNOWN)
    }
    var requested by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permission =
                when {
                    granted -> CameraPermission.GRANTED
                    activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true -> CameraPermission.DENIED
                    else -> CameraPermission.PERMANENTLY_DENIED
                }
        }
    LaunchedEffect(Unit) {
        if (permission != CameraPermission.GRANTED && !requested) {
            requested = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
    // Retour des réglages de l'application : la permission a pu être accordée ou retirée.
    LifecycleResumeEffect(Unit) {
        val granted = hasCameraPermission(context)
        if (granted) {
            permission = CameraPermission.GRANTED
        } else if (permission == CameraPermission.GRANTED) {
            permission = CameraPermission.DENIED
        }
        onPauseOrDispose { }
    }

    var status by remember { mutableStateOf(StatusLine.SEARCHING) }
    var success by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val onFound: (MrzKeyFields) -> Unit = { fields ->
        if (!success) {
            success = true
            status = StatusLine.SUCCESS
            session.onMrzScanned(fields)
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
    LaunchedEffect(success) {
        if (success) {
            delay(SUCCESS_DELAY_MILLIS)
            onDone()
        }
    }

    MrzScanScaffold(onBack = onDone) {
        val effective =
            if (permission == CameraPermission.UNKNOWN && requested) CameraPermission.DENIED else permission
        when (effective) {
            CameraPermission.UNKNOWN -> {
                Unit
            }

            CameraPermission.GRANTED -> {
                ScanContent(
                    specimen = specimen,
                    cameraActive = !success,
                    status = status,
                    onStatus = { if (!success) status = it },
                    onFound = onFound,
                    onManualEntry = onDone,
                )
            }

            CameraPermission.DENIED, CameraPermission.PERMANENTLY_DENIED -> {
                PermissionPanel(
                    permanentlyDenied = effective == CameraPermission.PERMANENTLY_DENIED,
                    onRequest = { launcher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = { openAppSettings(context) },
                    onBack = onDone,
                )
            }
        }
    }
}

/**
 * Barre d'application et fond noir de l'écran de scan, sans caméra : réutilisés par le contrôle
 * des débordements de texte (D28), qui affiche chaque état de l'écran sous Robolectric.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MrzScanScaffold(
    onBack: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(SceauIcons.ArrowBack, contentDescription = stringResource(R.string.scan_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Black),
            content = content,
        )
    }
}

/** Aperçu, cadre de visée, consigne, état et torche. */
@Composable
private fun ScanContent(
    specimen: SpecimenKind,
    cameraActive: Boolean,
    status: StatusLine,
    onStatus: (StatusLine) -> Unit,
    onFound: (MrzKeyFields) -> Unit,
    onManualEntry: () -> Unit,
) {
    val viewfinder = remember { AtomicReference<ViewfinderGeometry?>(null) }
    var geometry by remember { mutableStateOf<ViewfinderGeometry?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    ScanViewfinderLayout(
        geometry = geometry,
        status = status,
        specimen = specimen,
        onManualEntry = onManualEntry,
        modifier =
            Modifier.onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    val g = ScanGeometry.viewfinderFor(size.width, size.height)
                    viewfinder.set(g)
                    geometry = g
                }
            },
        preview = {
            if (cameraActive) {
                CameraPreview(
                    viewfinder = viewfinder,
                    onCamera = { camera = it },
                    onStatus = onStatus,
                    onFound = onFound,
                )
            }
        },
        torch = {
            val activeCamera = camera
            if (cameraActive && activeCamera != null && activeCamera.cameraInfo.hasFlashUnit()) {
                TorchButton(activeCamera)
            }
        },
    )
}

/**
 * Partie purement visuelle du scan, affichable sans caméra (D28) : [preview] en fond, cadre de
 * visée, consigne accompagnée du document [specimen], ligne d'état, [torch] et bouton de saisie
 * manuelle.
 *
 * L'illustration accompagne la recherche, la MRZ vue et le succès (l'écran se ferme aussitôt,
 * sans saut de mise en page). Elle est masquée sur les erreurs, où le remède n'est plus de
 * mieux cadrer mais de passer à la saisie manuelle : le message et son bouton gardent la place.
 */
@Composable
internal fun ScanViewfinderLayout(
    geometry: ViewfinderGeometry?,
    status: StatusLine,
    specimen: SpecimenKind,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit = {},
    torch: @Composable () -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val showSpecimen = status == StatusLine.SEARCHING || status == StatusLine.SEEN || status == StatusLine.SUCCESS
        val specimenWidth = if (maxWidth >= WIDE_PANEL) SPECIMEN_WIDTH_WIDE else SPECIMEN_WIDTH_COMPACT
        preview()
        ViewfinderOverlay(geometry)
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Illustration à côté de la consigne : la hauteur du panneau n'augmente presque pas,
            // et le panneau ne remonte pas sur le cadre de visée, même sur un petit écran.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showSpecimen) MrzSpecimenIllustration(specimen, Modifier.width(specimenWidth))
                Text(
                    text = stringResource(R.string.scan_instruction),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = if (showSpecimen) TextAlign.Start else TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                text = stringResource(statusText(status)),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            torch()
            if (status == StatusLine.UNSUPPORTED || status == StatusLine.CAMERA_UNAVAILABLE) {
                Button(onClick = onManualEntry) { Text(stringResource(R.string.scan_manual_entry)) }
            }
        }
    }
}

/** Lie Preview et ImageAnalysis (jamais ImageCapture) au cycle de vie de l'écran. */
@Composable
private fun CameraPreview(
    viewfinder: AtomicReference<ViewfinderGeometry?>,
    onCamera: (Camera?) -> Unit,
    onStatus: (StatusLine) -> Unit,
    onFound: (MrzKeyFields) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnStatus by rememberUpdatedState(onStatus)
    val currentOnFound by rememberUpdatedState(onFound)
    val currentOnCamera by rememberUpdatedState(onCamera)
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    val scanner = remember { MrzScanner() }
    val tracker = remember { ScanStatusTracker() }

    LaunchedEffect(Unit) {
        provider =
            try {
                ProcessCameraProvider.awaitInstance(context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentOnStatus(StatusLine.CAMERA_UNAVAILABLE)
                null
            }
    }

    DisposableEffect(provider, lifecycleOwner) {
        val cameraProvider = provider ?: return@DisposableEffect onDispose { }
        val alive = AtomicBoolean(true)
        val delivered = AtomicBoolean(false)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val executor = Executors.newSingleThreadExecutor()
        val analyzer =
            MrzFrameAnalyzer(scanner::analyze, scanner::reset, viewfinder) { result ->
                mainExecutor.execute {
                    if (!alive.get()) return@execute
                    if (result is MrzScanResult.Found) {
                        if (delivered.compareAndSet(false, true)) currentOnFound(result.fields)
                    } else {
                        currentOnStatus(statusLineOf(tracker.onResult(result, SystemClock.uptimeMillis())))
                    }
                }
            }
        val aspect = AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        val preview =
            Preview
                .Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
                .build()
        preview.setSurfaceProvider { request -> surfaceRequest = request }
        val analysis =
            ImageAnalysis
                .Builder()
                .setResolutionSelector(
                    ResolutionSelector
                        .Builder()
                        .setAspectRatioStrategy(aspect)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        ).build(),
                ).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
        analysis.setAnalyzer(executor, analyzer)
        val camera =
            try {
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: IllegalArgumentException) {
                // Pas de caméra arrière.
                null
            } catch (_: IllegalStateException) {
                null
            }
        if (camera == null) currentOnStatus(StatusLine.CAMERA_UNAVAILABLE)
        currentOnCamera(camera)

        // Mise en arrière-plan : CameraX ferme la caméra ; on oublie aussi l'état du scan.
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    executor.execute { analyzer.reset() }
                    tracker.reset()
                    currentOnStatus(StatusLine.SEARCHING)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            alive.set(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
            analysis.clearAnalyzer()
            cameraProvider.unbind(preview, analysis)
            surfaceRequest = null
            currentOnCamera(null)
            executor.execute { analyzer.release() }
            executor.shutdown()
        }
    }

    surfaceRequest?.let { request ->
        CameraXViewfinder(surfaceRequest = request, modifier = Modifier.fillMaxSize())
    }
}

/** Assombrit tout sauf le cadre de visée, puis trace le cadre. */
@Composable
private fun ViewfinderOverlay(geometry: ViewfinderGeometry?) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val g = geometry ?: return@Canvas
        val frame = Rect(g.left * size.width, g.top * size.height, g.right * size.width, g.bottom * size.height)
        val corner = CornerRadius(VIEWFINDER_CORNER.toPx())
        val path = Path().apply { addRoundRect(RoundRect(frame, corner)) }
        clipPath(path, clipOp = ClipOp.Difference) {
            drawRect(Color.Black.copy(alpha = SCRIM_ALPHA))
        }
        drawRoundRect(
            color = VIEWFINDER_COLOR,
            topLeft = frame.topLeft,
            size = frame.size,
            cornerRadius = corner,
            style = Stroke(width = VIEWFINDER_STROKE.toPx()),
        )
    }
}

@Composable
private fun TorchButton(camera: Camera) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var torchOn by remember(camera) { mutableStateOf(false) }
    DisposableEffect(camera, lifecycleOwner) {
        val torchState = camera.cameraInfo.torchState
        val observer = Observer<Int> { torchOn = it == TorchState.ON }
        torchState.observe(lifecycleOwner, observer)
        onDispose { torchState.removeObserver(observer) }
    }
    FilledTonalIconToggleButton(
        checked = torchOn,
        onCheckedChange = { camera.cameraControl.enableTorch(it) },
    ) {
        Icon(
            imageVector = if (torchOn) ScanIcons.FlashlightOff else ScanIcons.FlashlightOn,
            contentDescription = stringResource(if (torchOn) R.string.scan_torch_off else R.string.scan_torch_on),
        )
    }
}

/** Permission refusée : explication, nouvelle demande ou réglages, retour à la saisie manuelle. */
@Composable
internal fun PermissionPanel(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.scan_permission_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text =
                stringResource(
                    if (permanentlyDenied) R.string.scan_permission_permanently_denied else R.string.scan_permission_denied,
                ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (permanentlyDenied) {
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.scan_open_settings)) }
        } else {
            Button(onClick = onRequest) { Text(stringResource(R.string.scan_permission_grant)) }
        }
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.scan_manual_entry)) }
    }
}

private fun statusLineOf(status: ScanStatus): StatusLine =
    when (status) {
        ScanStatus.SEARCHING -> StatusLine.SEARCHING
        ScanStatus.SEEN -> StatusLine.SEEN
        ScanStatus.UNSUPPORTED -> StatusLine.UNSUPPORTED
    }

private fun statusText(status: StatusLine): Int =
    when (status) {
        StatusLine.SEARCHING -> R.string.scan_status_searching
        StatusLine.SEEN -> R.string.scan_status_seen
        StatusLine.UNSUPPORTED -> R.string.scan_status_unsupported
        StatusLine.SUCCESS -> R.string.scan_status_success
        StatusLine.CAMERA_UNAVAILABLE -> R.string.scan_camera_unavailable
    }

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    } catch (_: ActivityNotFoundException) {
        // Réglages introuvables : la saisie manuelle reste proposée.
    }
}

private const val ANALYSIS_WIDTH = 1280
private const val ANALYSIS_HEIGHT = 720
private const val SCRIM_ALPHA = 0.6f

/** Trait du cadre de visée, repris à l'échelle par [MrzSpecimenIllustration]. */
internal val VIEWFINDER_COLOR = Color.White
internal val VIEWFINDER_STROKE = 3.dp
internal val VIEWFINDER_CORNER = 12.dp

/** Largeur de l'illustration du document, selon la largeur disponible pour la consigne. */
private val SPECIMEN_WIDTH_COMPACT = 120.dp
private val SPECIMEN_WIDTH_WIDE = 160.dp
private val WIDE_PANEL = 480.dp
