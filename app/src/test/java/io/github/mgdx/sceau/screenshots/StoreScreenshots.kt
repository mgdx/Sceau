package io.github.mgdx.sceau.screenshots

import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.nfc.NfcAdapter
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.graphics.createBitmap
import androidx.lifecycle.ViewModelProvider
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.SceauApplication
import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.demo.DemoCard
import io.github.mgdx.sceau.demo.DemoMode
import io.github.mgdx.sceau.jp2.Jpeg2000Protocol
import io.github.mgdx.sceau.jp2.Jpeg2000Service
import io.github.mgdx.sceau.session.DocumentTab
import io.github.mgdx.sceau.session.ReadState
import io.github.mgdx.sceau.session.SessionViewModel
import io.github.mgdx.sceau.testchip.SimulatedDocuments
import io.github.mgdx.sceau.trust.TrustStoreRepository
import io.github.mgdx.sceau.ui.home.HomeScreen
import io.github.mgdx.sceau.ui.learn.IntroScreen
import io.github.mgdx.sceau.ui.reading.ReadingScreen
import io.github.mgdx.sceau.ui.result.ResultScreen
import io.github.mgdx.sceau.ui.theme.SceauTheme
import io.github.mgdx.sceau.ui.trust.TrustStoreScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNfcAdapter
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.math.abs

/**
 * Screenshots for the F-Droid metadata (D39), written to
 * `fastlane/metadata/android/<language>/images/phoneScreenshots/`.
 *
 * `FLAG_SECURE` is set on the whole activity (SPEC §8): no screenshot is possible on a device,
 * and this flag is not lifted in any variant. The real screens are therefore rendered off-device
 * by Robolectric, with Android's native rendering (`GraphicsMode.NATIVE`), like
 * `TextOverflowTest` (D28), on a 1080 × 2400 px phone (360 × 800 dp at xxhdpi), light theme.
 * Only demo mode data appears: the specimen CNIe from `:testchip`.
 *
 * In production, chip images are decoded in an isolated process (D23, audit V18), the specimen
 * portrait in JPEG 2000 by OpenJPEG, which is absent under Robolectric. Here, and only here, the
 * service is replaced by [SpecimenPortraitBinder], which speaks the same binder protocol and
 * returns the pixels of the original synthetic silhouette
 * (`testchip/tools/generate-specimen-portrait.sh`) or of the specimen signature.
 *
 * Excluded from `./gradlew check`: the test task only runs it when `SCEAU_SCREENSHOTS=1`
 * (see `app/build.gradle.kts`), which also provides the output directory.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class StoreScreenshots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var scene by mutableStateOf<Scene?>(null)

    private lateinit var session: SessionViewModel
    private lateinit var repository: TrustStoreRepository
    private lateinit var outputDir: File

    private class Scene(
        val name: String,
        val content: @Composable () -> Unit,
    )

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h800dp-notnight-xxhdpi")
    fun french() = generate("fr-FR")

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h800dp-notnight-xxhdpi")
    fun english() = generate("en-US")

    private fun generate(language: String) {
        val root = System.getProperty(OUTPUT_PROPERTY)
        assumeTrue("$OUTPUT_PROPERTY missing: run with SCEAU_SCREENSHOTS=1", !root.isNullOrBlank())
        outputDir = File(root, "$language/images/phoneScreenshots").apply { mkdirs() }

        val activity = compose.activity
        session = ViewModelProvider(activity)[SessionViewModel::class.java]
        repository = (activity.application as SceauApplication).trustStoreRepository
        shadowOf(activity.application)
            .setComponentNameAndServiceForBindService(ComponentName(activity, Jpeg2000Service::class.java), SpecimenPortraitBinder())
        setNfcEnabled(activity)

        compose.setContent {
            SceauTheme(darkTheme = false) {
                scene?.let { current -> key(current.name) { current.content() } }
            }
        }
        // Manual clock, like TextOverflowTest: infinite progress indicators would keep Compose
        // from ever being "idle".
        compose.mainClock.autoAdvance = false
        settle()

        home()
        reading()
        result()
        trustStore()
        introduction()
        session.clear()
        settle()
    }

    // --- Scenes ------------------------------------------------------------------------------

    private fun home() {
        session.clear()
        session.selectTab(DocumentTab.PASSPORT)
        show("1_home") { HomeScreen(session, onRead = {}, onOpenTrustStore = {}, onOpenAbout = {}) }
    }

    /** Reading held at the start of data reading: two steps checked, one in progress. */
    private fun reading() {
        val card = SimulatedDocuments.frenchIdCard()
        val held = HoldingTransport(card.chip()) { (session.state.value as? ReadState.Reading)?.current == Step.READ_DATA }
        session.startDemo(DemoCard(held, checkNotNull(card.canKey), card.trustStore))
        awaitUi("data reading") { (session.state.value as? ReadState.Reading)?.current == Step.READ_DATA }
        show("2_reading") { ReadingScreen(session, onDone = {}, onCancel = {}) }
        session.clear()
    }

    private fun result() {
        session.startDemo(checkNotNull(DemoMode.newSimulatedCnie()))
        awaitUi("demo report") { session.state.value is ReadState.Done }
        assertEquals(Verdict.AUTHENTIC, (session.state.value as ReadState.Done).report.verdict)
        show("3_result", ready = {
            awaitUi("portrait shown") { hasNode(contentDescription(R.string.result_photo_description)) }
            assertTrue("portrait unavailable", !hasNode(hasText(string(R.string.result_photo_unavailable))))
        }) { ResultScreen(session, onClear = {}) }
        session.clear()
    }

    /** Trust store, first country of the list expanded. */
    private fun trustStore() {
        show("4_trust_store", ready = {
            awaitUi("store loaded") { hasNode(hasText(string(R.string.trust_import))) }
            val expand =
                SemanticsMatcher("expand a country") {
                    it.config.getOrNull(SemanticsActions.OnClick)?.label == string(R.string.trust_country_expand)
                }
            compose.onAllNodes(expand)[0].performSemanticsAction(SemanticsActions.OnClick)
            settle(frames = ANIMATION_FRAMES)
        }) { TrustStoreScreen(repository, onBack = {}) }
    }

    private fun introduction() {
        show("5_introduction", ready = {
            assertTrue("introduction missing", hasNode(hasText(string(R.string.learn_intro_skip))))
        }) { IntroScreen(onFinish = {}, onOpenChipContents = {}) }
    }

    // --- Rendering and capture -----------------------------------------------------------------

    private fun show(
        name: String,
        ready: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        scene = Scene(name, content)
        settle(frames = ANIMATION_FRAMES)
        ready()
        settle(frames = ANIMATION_FRAMES)
        capture(name)
        scene = null
        settle()
    }

    private fun capture(name: String) {
        // PixelCopy of the window, rendered by Robolectric. `captureToImage()` waits for a new
        // draw, which never comes with the manual clock.
        val window = compose.activity.window
        val bitmap = createBitmap(window.decorView.width, window.decorView.height)
        var status = -1
        PixelCopy.request(window, bitmap, { status = it }, Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("copy of $name", PixelCopy.SUCCESS, status)
        assertEquals("width of $name", SCREEN_WIDTH_PX, bitmap.width)
        assertEquals("height of $name", SCREEN_HEIGHT_PX, bitmap.height)
        File(outputDir, "$name.png").outputStream().use { out ->
            assertTrue("writing $name", bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out))
        }
        bitmap.recycle()
    }

    // --- Synchronization -------------------------------------------------------------------------

    private fun settle(frames: Int = 5) {
        repeat(frames) {
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(FRAME)
        }
        compose.waitForIdle()
    }

    /** Waits in real time: reading, loading and decoding run off the main thread. */
    private fun awaitUi(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + AWAIT_TIMEOUT_NANOS
        while (true) {
            settle(frames = 1)
            if (condition()) return
            if (System.nanoTime() > deadline) fail("timed out: $what")
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun hasNode(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun contentDescription(id: Int): SemanticsMatcher =
        SemanticsMatcher("contentDescription") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(string(id)) == true
        }

    private fun setNfcEnabled(activity: ComponentActivity) {
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        shadowOf(NfcAdapter.getDefaultAdapter(activity)).setEnabled(true)
    }

    private companion object {
        /** The `fastlane/metadata/android` directory, provided by Gradle when `SCEAU_SCREENSHOTS=1`. */
        const val OUTPUT_PROPERTY = "sceau.screenshots.dir"
        const val SCREEN_WIDTH_PX = 1080
        const val SCREEN_HEIGHT_PX = 2400
        const val PNG_QUALITY = 100
        const val ANIMATION_FRAMES = 60
        const val AWAIT_TIMEOUT_NANOS = 60_000_000_000L
        const val POLL_MILLIS = 10L
        val FRAME: Duration = Duration.ofMillis(16)
    }
}

/** Relays to [delegate], but blocks every APDU while [hold] is true, until closed. */
private class HoldingTransport(
    private val delegate: CardTransport,
    private val hold: () -> Boolean,
) : CardTransport {
    private val closed = CountDownLatch(1)

    override val maxTransceiveLength: Int get() = delegate.maxTransceiveLength
    override var timeoutMillis: Int
        get() = delegate.timeoutMillis
        set(value) {
            delegate.timeoutMillis = value
        }

    override fun transceive(apdu: ByteArray): ByteArray {
        if (hold()) closed.await()
        if (closed.count == 0L) throw SceauException.ConnectionLost()
        return delegate.transceive(apdu)
    }

    override fun reconnect() = delegate.reconnect()

    override fun close() {
        closed.countDown()
        delegate.close()
    }
}

/**
 * Stand-in for `Jpeg2000Service`, for screenshots only: same binder protocol
 * ([Jpeg2000Protocol]), but only accepts the two images of the `:testchip` specimen CNIe. For
 * the portrait (JPEG 2000), it returns the pixels of the synthetic silhouette it encodes; the
 * signature (JPEG, also decoded by the service since audit V18) is decoded by `BitmapFactory`,
 * as in the real service.
 */
private class SpecimenPortraitBinder : Binder() {
    private val expected = SimulatedDocuments.specimenPortrait()
    private val signature = SimulatedDocuments.specimenSignature()
    private var received = ByteArray(0)
    private var pixels = IntArray(0)

    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (code !in Jpeg2000Protocol.TX_BEGIN..Jpeg2000Protocol.TX_READ || reply == null) {
            return super.onTransact(code, data, reply, flags)
        }
        data.enforceInterface(Jpeg2000Protocol.DESCRIPTOR)
        when (code) {
            Jpeg2000Protocol.TX_BEGIN -> {
                received = ByteArray(0)
                reply.writeInt(Jpeg2000Protocol.STATUS_OK)
            }

            Jpeg2000Protocol.TX_APPEND -> {
                data.readInt()
                received += checkNotNull(data.createByteArray())
                reply.writeInt(Jpeg2000Protocol.STATUS_OK)
            }

            Jpeg2000Protocol.TX_DECODE -> {
                val decoded =
                    when {
                        received.contentEquals(expected) -> Triple(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, specimenSilhouette())
                        received.contentEquals(signature) -> decodeSignature()
                        else -> null
                    }
                if (decoded != null) {
                    pixels = decoded.third
                    reply.writeInt(Jpeg2000Protocol.STATUS_OK)
                    reply.writeInt(decoded.first)
                    reply.writeInt(decoded.second)
                } else {
                    reply.writeInt(Jpeg2000Protocol.STATUS_ERROR)
                }
            }

            else -> {
                val offset = data.readInt()
                val count = data.readInt()
                reply.writeInt(Jpeg2000Protocol.STATUS_OK)
                reply.writeIntArray(pixels.copyOfRange(offset, offset + count))
            }
        }
        return true
    }

    private fun decodeSignature(): Triple<Int, Int, IntArray> {
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(signature, 0, signature.size))
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Triple(bitmap.width, bitmap.height, argb).also { bitmap.recycle() }
    }

    private companion object {
        const val PORTRAIT_WIDTH = 240
        const val PORTRAIT_HEIGHT = 320

        /** Same drawing as `testchip/tools/generate-specimen-portrait.sh`, in ARGB. */
        fun specimenSilhouette(): IntArray {
            val w = PORTRAIT_WIDTH
            val h = PORTRAIT_HEIGHT
            val argb = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val t = y / (h - 1.0)
                    var r = (214 - 30 * t).toInt()
                    var g = (222 - 26 * t).toInt()
                    var b = (232 - 18 * t).toInt()
                    val sx = (x - w / 2.0) / 110.0
                    val sy = (y - h) / 95.0
                    val neck = abs(x - w / 2.0) < 26 && y in 171..239
                    val hx = (x - w / 2.0) / 62.0
                    val hy = (y - 125) / 80.0
                    if (sx * sx + sy * sy <= 1.0) {
                        r = 70
                        g = 78
                        b = 96
                    } else if (hx * hx + hy * hy <= 1.0 || neck) {
                        val shade = (18 * maxOf(0.0, hx)).toInt()
                        r = 150 - shade
                        g = 150 - shade
                        b = 158 - shade
                    }
                    argb[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return argb
        }
    }
}
