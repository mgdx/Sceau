package io.github.mgdx.sceau.l10n

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.nfc.NfcAdapter
import android.os.LocaleList
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.SceauApplication
import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.demo.DemoCard
import io.github.mgdx.sceau.demo.DemoMode
import io.github.mgdx.sceau.session.DateOrder
import io.github.mgdx.sceau.session.DatePart
import io.github.mgdx.sceau.session.DocumentTab
import io.github.mgdx.sceau.session.IdCardKey
import io.github.mgdx.sceau.session.ReadState
import io.github.mgdx.sceau.session.SessionViewModel
import io.github.mgdx.sceau.testchip.SimulatedChip
import io.github.mgdx.sceau.testchip.SimulatedDocuments
import io.github.mgdx.sceau.testchip.TestPki
import io.github.mgdx.sceau.trust.TrustStoreRepository
import io.github.mgdx.sceau.ui.about.AboutScreen
import io.github.mgdx.sceau.ui.home.HomeScreen
import io.github.mgdx.sceau.ui.learn.ChipContentsScreen
import io.github.mgdx.sceau.ui.learn.IntroPage
import io.github.mgdx.sceau.ui.learn.IntroScreen
import io.github.mgdx.sceau.ui.reading.ReadingScreen
import io.github.mgdx.sceau.ui.result.Countries
import io.github.mgdx.sceau.ui.result.ResultScreen
import io.github.mgdx.sceau.ui.theme.SceauTheme
import io.github.mgdx.sceau.ui.trust.TrustStoreScreen
import io.github.mgdx.sceau.ui.trust.groupByCountry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNfcAdapter
import org.xmlpull.v1.XmlPullParser
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CountDownLatch

/**
 * Overflowing or truncated text, in every language of `locales_config.xml` (D28).
 * `FLAG_SECURE` prevents any screenshot on a device: this test renders the real screens under
 * Robolectric, with Android's real text engine and fonts (`GraphicsMode.NATIVE`), on a narrow
 * phone (360 × 640 dp, 100% font size), and records every text node that exceeds its box, is
 * ellipsized or sticks out of the screen.
 *
 * All anomalies are collected, then compared with `l10n/known-overflows.txt` (lines
 * `language|key`): the test fails if an anomaly is missing from it, or if a listed anomaly has
 * disappeared (the list must be kept up to date).
 *
 * The language is switched through the configuration `CompositionLocal`s (context, resources,
 * layout direction) above the screens: a single activity and a single loaded trust store, which
 * keeps the run time reasonable for 45 languages.
 *
 * Excluded data: none for now. Chip values (`FieldRow`, check details) have an explicit
 * `maxLines` with a deliberate ellipsis; the specimen data is short and does not reach it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h640dp-xxhdpi")
class TextOverflowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var language by mutableStateOf(Language("fr", Locale.FRENCH))
    private var scene by mutableStateOf<Scene?>(null)

    private val overflows = mutableListOf<Overflow>()

    private lateinit var session: SessionViewModel
    private lateinit var repository: TrustStoreRepository
    private lateinit var languages: List<Language>
    private lateinit var french: Language

    private class Language(
        val tag: String,
        val locale: Locale,
    )

    private class Scene(
        val name: String,
        val content: @Composable () -> Unit,
    )

    @Test
    fun `no text overflows, in any language`() {
        val activity = compose.activity
        session = ViewModelProvider(activity)[SessionViewModel::class.java]
        repository = (activity.application as SceauApplication).trustStoreRepository
        languages = declaredLanguages(activity)
        french = languages.first { it.tag == "fr" }
        language = french
        checkLanguagesAreLoaded(activity)

        compose.setContent {
            Localized(language) {
                scene?.let { current -> key(current.name) { current.content() } }
            }
        }
        // Manual clock: infinite progress indicators would keep Compose from ever being
        // "idle".
        compose.mainClock.autoAdvance = false
        settle()

        homeScenes()
        readingScenes()
        resultScenes()
        trustStoreScene()
        aboutScene()
        learnScenes()
        session.clear()
        settle()

        compare(overflows)
    }

    /** Control: the detector does report every kind of overflow, on dummy texts. */
    @Test
    fun `the detector reports every kind of overflow`() {
        val offScreen = "Off the screen, ".repeat(OFF_SCREEN_REPEAT)
        compose.setContent {
            SceauTheme {
                Column {
                    Text("Ellipsis ellipsis ellipsis", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(80.dp))
                    Text("Height height height", modifier = Modifier.width(80.dp).height(20.dp))
                    Box(Modifier.width(80.dp).semantics { }) { Text("Reisepassdokumentenüberprüfung") }
                    Box(Modifier.height(20.dp).semantics { }) {
                        Text("Clipped clipped clipped", modifier = Modifier.width(80.dp).wrapContentHeight(unbounded = true))
                    }
                    Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(1000.dp)) {
                        Text(offScreen, softWrap = false)
                    }
                    Text("Text that fits")
                }
            }
        }
        compose.mainClock.autoAdvance = false
        settle()
        val density = compose.activity.resources.displayMetrics.density
        val found =
            compose
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .flatMap { inspectTextNode(it, density) }
                .groupBy({ it.first }, { it.second })

        fun assertFound(
            text: String,
            problem: String,
        ) = assertTrue("$text: expected $problem, found ${found[text]}", found[text].orEmpty().any { it.startsWith(problem) })
        assertFound("Ellipsis ellipsis ellipsis", "ellipsized")
        assertFound("Height height height", "exceeds its box")
        assertFound("Reisepassdokumentenüberprüfung", "word broken")
        assertFound("Clipped clipped clipped", "sticks out of its container")
        assertFound(offScreen, "sticks out of the screen")
        assertEquals(null, found["Text that fits"])
    }

    // --- Scenes ------------------------------------------------------------------------------

    private fun homeScenes() {
        val homeContent: @Composable () -> Unit = { HomeScreen(session, onRead = {}, onOpenTrustStore = {}, onOpenAbout = {}) }

        setNfc(present = true, enabled = false)
        session.clear()
        session.selectTab(DocumentTab.ID_CARD)
        render("Home, ID card, NFC disabled", homeContent)

        render("Home, menu", homeContent, afterShow = {
            compose
                .onNode(hasContentDescriptionRes(R.string.home_menu_more))
                .performSemanticsAction(SemanticsActions.OnClick)
            settle(frames = 30)
            assertTrue(
                "menu not open",
                compose.onAllNodes(hasTextRes(R.string.home_menu_about), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
            )
        })

        setNfc(present = true, enabled = true)
        session.selectIdCardKey(IdCardKey.MRZ)
        render("Home, ID card, MRZ", homeContent)
        session.selectIdCardKey(IdCardKey.CAN)

        setNfc(present = false, enabled = false)
        session.selectTab(DocumentTab.PASSPORT)
        session.onDocumentNumberChange("AB1234567")
        render("Home, passport, future and too old dates, no NFC", homeContent, perLanguage = { lang ->
            val order = DateOrder.forLocale(lang.locale)
            session.onDateOfBirthChange(dateDigits(order, day = "01", month = "01", year = "2099"))
            session.onDateOfExpiryChange(dateDigits(order, day = "01", month = "01", year = "1980"))
        })
        session.onDateOfBirthChange("99999999")
        session.onDateOfExpiryChange("99999999")
        render("Home, passport, invalid dates, no NFC", homeContent)
        session.clear()
    }

    private fun readingScenes() {
        val readingContent: @Composable () -> Unit = { ReadingScreen(session, onDone = {}, onCancel = {}) }
        val card = SimulatedDocuments.frenchIdCard()
        val can = checkNotNull(card.canKey)

        // Slow chip: reading the simulated CNIe is held as soon as the secure channel opens,
        // and the clock is advanced past the message delay.
        val slow = HoldingTransport(card.chip()) { (session.state.value as? ReadState.Reading)?.current == Step.SECURE_CHANNEL }
        session.startDemo(DemoCard(slow, can, card.trustStore))
        awaitState("secure channel") { (it as? ReadState.Reading)?.current == Step.SECURE_CHANNEL }
        render("Reading in progress, slow chip", readingContent, afterShow = {
            compose.mainClock.advanceTimeBy(SLOW_CHIP_WAIT_MILLIS)
            settle()
            assertTrue(
                "\"slow chip\" message missing",
                compose.onAllNodes(hasTextRes(R.string.reading_slow_chip)).fetchSemanticsNodes().isNotEmpty(),
            )
        })
        session.clear()

        val errors =
            listOf(
                "ACCESS_DENIED" to DemoCard(card.chip(), AccessKey.Can("000000"), card.trustStore),
                "CAN_WITHOUT_PACE" to DemoCard(SimulatedChip(card.document, null), can, card.trustStore),
                "NOT_ICAO" to DemoCard(FailingTransport { STATUS_FILE_NOT_FOUND }, card.mrzKey, card.trustStore),
                "CONNECTION_LOST" to DemoCard(FailingTransport { throw SceauException.ConnectionLost() }, can, card.trustStore),
                "TIMEOUT" to DemoCard(FailingTransport { throw SceauException.Timeout() }, can, card.trustStore),
                "UNEXPECTED" to DemoCard(FailingTransport { throw IllegalStateException() }, can, card.trustStore),
                // D36: EF.SOD reserved for government terminals (German cards issued before 2021-08). Code
                // ACCESS_RESTRICTED-READ_DATA-SOD: the SELECT_APPLET variant, a single unbroken word, is
                // wider than the screen and breaks at the end of the line, like the long UNEXPECTED codes.
                "ACCESS_RESTRICTED" to
                    DemoCard(SimulatedChip(card.document, card.pace, restrictedFiles = setOf(SimulatedChip.FID_SOD)), can, card.trustStore),
            )
        errors.forEach { (code, demoCard) ->
            session.startDemo(demoCard)
            awaitState("error $code") { it is ReadState.Error }
            val actual = (session.state.value as ReadState.Error).code
            assertTrue("code $actual instead of $code", actual.startsWith(code))
            render("Reading, error $code", readingContent)
            session.clear()
        }
    }

    private fun resultScenes() {
        val resultContent: @Composable () -> Unit = { ResultScreen(session, onClear = {}) }

        session.startDemo(checkNotNull(DemoMode.newSimulatedCnie()))
        awaitState("demo report") { it is ReadState.Done }
        assertEquals(Verdict.AUTHENTIC, (session.state.value as ReadState.Done).report.verdict)
        render("Result, authentic (demo)", resultContent, afterShow = {
            showResultDetails()
            // Complete report: identity shown, without the data-not-read sentence (audit V24).
            assertTrue(
                "identity missing from a complete report",
                compose.onAllNodes(hasTextRes(R.string.result_field_surname)).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(hasTextRes(R.string.result_data_not_read)).fetchSemanticsNodes().isEmpty(),
            )
        })
        session.clear()

        // Same simulated CNIe read with a store without any French certificate (audit V25: a
        // country known to the store without a chain gives "Failed", not "Unknown issuer").
        val card = SimulatedDocuments.frenchIdCard()
        val foreignStore = SimulatedDocuments.germanTestPki().let { it.trustStore(it.oldCsca) }
        session.startDemo(DemoCard(card.chip(), checkNotNull(card.canKey), foreignStore))
        awaitState("unknown issuer report") { it is ReadState.Done }
        assertEquals(Verdict.UNKNOWN_ISSUER, (session.state.value as ReadState.Done).report.verdict)
        render("Result, unknown issuer", resultContent, afterShow = ::showResultDetails)
        session.clear()

        // Same CNIe read with the real store: France is known there, but not this test CSCA.
        val store = runBlocking { repository.get() }
        session.startDemo(DemoCard(SimulatedDocuments.frenchIdCard().chip(), checkNotNull(card.canKey), store))
        awaitState("known country without chain report") { it is ReadState.Done }
        assertEquals(Verdict.FAILED, (session.state.value as ReadState.Done).report.verdict)
        render("Result, known country without chain", resultContent, afterShow = ::showResultDetails)
        session.clear()

        // D36: eID-UB card, without identity data; neither a photo nor an empty field.
        val eidUb = SimulatedDocuments.germanEidUb()
        session.startDemo(DemoCard(eidUb.chip(), checkNotNull(eidUb.canKey), eidUb.trustStore))
        awaitState("eID-UB report") { it is ReadState.Done }
        assertEquals(Verdict.AUTHENTIC, (session.state.value as ReadState.Done).report.verdict)
        render("Result, eID-UB without identity data", resultContent, afterShow = {
            settle()
            assertTrue(
                "\"no identity data\" banner missing",
                compose.onAllNodes(hasTextRes(R.string.result_no_identity_data)).fetchSemanticsNodes().isNotEmpty(),
            )
            assertTrue(
                "photo shown for an eID-UB",
                compose.onAllNodes(hasTextRes(R.string.result_photo_unavailable)).fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodes(hasContentDescriptionRes(R.string.result_photo_description)).fetchSemanticsNodes().isEmpty(),
            )
            assertTrue(
                "name shown for an eID-UB",
                compose.onAllNodes(hasTextRes(R.string.result_field_surname)).fetchSemanticsNodes().isEmpty(),
            )
            expandChecks()
        })
        session.clear()

        // Audit V24: failed CA, then access denied when the channel is reestablished; "Failed"
        // report without any data read. No identity, no photo slot: a sentence says so.
        val clone =
            SimulatedChip(
                card.document,
                card.pace,
                caPrivateKey = card.pki.generateEc(TestPki.CURVE).private,
                restrictAppletAfterReconnect = true,
            )
        session.startDemo(DemoCard(clone, checkNotNull(card.canKey), card.trustStore))
        awaitState("report without data read") { it is ReadState.Done }
        val notRead = (session.state.value as ReadState.Done).report
        assertEquals(Verdict.FAILED, notRead.verdict)
        assertFalse(notRead.identityRead)
        render("Result, failure without data read", resultContent, afterShow = {
            settle()
            assertTrue(
                "\"data not read\" sentence missing",
                compose.onAllNodes(hasTextRes(R.string.result_data_not_read)).fetchSemanticsNodes().isNotEmpty(),
            )
            assertTrue(
                "photo or photo slot shown without data read",
                compose.onAllNodes(hasTextRes(R.string.result_photo_unavailable)).fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodes(hasContentDescriptionRes(R.string.result_photo_description)).fetchSemanticsNodes().isEmpty(),
            )
            assertTrue(
                "Identity section shown without data read",
                compose.onAllNodes(hasTextRes(R.string.result_section_identity)).fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodes(hasTextRes(R.string.result_field_surname)).fetchSemanticsNodes().isEmpty(),
            )
            assertTrue(
                "Clear button missing",
                compose.onAllNodes(hasTextRes(R.string.result_clear)).fetchSemanticsNodes().isNotEmpty(),
            )
            // Checks left collapsed: their details are technical codes
            // (READ_DATA-REESTABLISH-…), with no translated text specific to this scene.
        })
        session.clear()
    }

    /** Waits for the portrait decoding to finish (unavailable under Robolectric), then expands the checks. */
    private fun showResultDetails() {
        awaitUi("portrait decoded or unavailable") {
            compose.onAllNodes(hasTextRes(R.string.result_photo_unavailable)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasContentDescriptionRes(R.string.result_photo_description)).fetchSemanticsNodes().isNotEmpty()
        }
        expandChecks()
    }

    /** Expands all the checks of the Result screen. */
    private fun expandChecks() {
        val expand =
            SemanticsMatcher("expand") {
                it.config.getOrNull(SemanticsActions.OnClick)?.label ==
                    string(R.string.result_check_expand)
            }
        val count = compose.onAllNodes(expand).fetchSemanticsNodes().size
        assertTrue("no check to expand", count > 0)
        repeat(count) {
            compose.onAllNodes(expand)[0].performSemanticsAction(SemanticsActions.OnClick)
            settle()
        }
    }

    private fun trustStoreScene() {
        val store = runBlocking { repository.get() }
        render(
            "Trust store, France and United Kingdom expanded",
            {
                // Oversized height: the LazyColumn then composes all its items, without scrolling.
                Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).requiredHeight(TALL_LIST_HEIGHT)) {
                    TrustStoreScreen(repository, onBack = {})
                }
            },
            afterShow = {
                awaitTrustStoreLoaded()
                listOf("FR", "GB").forEach { alpha2 ->
                    val name = checkNotNull(Countries.displayName(alpha2, french.locale))
                    compose
                        .onAllNodes(hasText(name, substring = true) and hasClickAction())[0]
                        .performSemanticsAction(SemanticsActions.OnClick)
                    settle()
                }
            },
            ready = { lang ->
                awaitTrustStoreLoaded()
                val groups = groupByCountry(store.anchors, lang.locale).size
                val headers =
                    compose
                        .onAllNodes(
                            SemanticsMatcher("country header") {
                                val label = it.config.getOrNull(SemanticsActions.OnClick)?.label
                                label == string(R.string.trust_country_expand) || label == string(R.string.trust_country_collapse)
                            },
                        ).fetchSemanticsNodes()
                        .size
                assertEquals("composed country headers (${lang.tag})", groups, headers)
            },
        )
    }

    private fun awaitTrustStoreLoaded() {
        awaitUi("store loaded") { compose.onAllNodes(hasTextRes(R.string.trust_import)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun aboutScene() {
        render("About", { AboutScreen(repository, onBack = {}, onReplayIntro = {}, onOpenChipContents = {}) }, ready = {
            awaitUi("embedded store described") {
                compose.onAllNodes(hasTextRes(R.string.about_trust_ants_and_bsi)).fetchSemanticsNodes().isNotEmpty()
            }
        })
    }

    /**
     * Introduction, page by page (D35), then the "What the chip contains" screen, at 100% then
     * 200% font size (introduction buttons that must wrap).
     */
    private fun learnScenes() {
        learnScenes(fontScale = 1f, suffix = "")
        learnScenes(fontScale = LARGE_FONT_SCALE, suffix = ", font 200%")
    }

    private fun learnScenes(
        fontScale: Float,
        suffix: String,
    ) {
        val scaled: (@Composable () -> Unit) -> @Composable () -> Unit = { content ->
            {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
            }
        }
        IntroPage.entries.forEachIndexed { index, _ ->
            render("Introduction, page ${index + 1}$suffix", scaled { IntroScreen(onFinish = {}, onOpenChipContents = {}) }, afterShow = {
                repeat(index) {
                    compose.onNode(hasTextRes(R.string.learn_intro_next)).performSemanticsAction(SemanticsActions.OnClick)
                    settle(frames = PAGE_ANIMATION_FRAMES)
                }
                val last = index == IntroPage.entries.lastIndex
                val button = if (last) R.string.learn_intro_start else R.string.learn_intro_skip
                assertTrue(
                    "page ${index + 1} not shown",
                    compose.onAllNodes(hasTextRes(button), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
                )
            })
        }
        render("Chip contents$suffix", scaled { ChipContentsScreen(onBack = {}) })
    }

    // --- Rendering and collection -------------------------------------------------------------

    /**
     * Shows [content] in French, runs [afterShow] (menus, expanding), then, for each language,
     * applies [perLanguage], waits for [ready] and records the overflowing texts.
     */
    private fun render(
        name: String,
        content: @Composable () -> Unit,
        afterShow: () -> Unit = {},
        perLanguage: (Language) -> Unit = {},
        ready: (Language) -> Unit = {},
    ) {
        language = french
        perLanguage(french)
        scene = Scene(name, content)
        settle()
        afterShow()
        languages.forEach { lang ->
            language = lang
            perLanguage(lang)
            settle()
            ready(lang)
            collect(name, lang)
        }
        scene = null
        settle()
    }

    private fun collect(
        screen: String,
        lang: Language,
    ) {
        val resolver = StringKeyResolver(resources(lang))
        val density = compose.activity.resources.displayMetrics.density
        val nodes =
            compose
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
                .fetchSemanticsNodes()
        assertTrue("no text shown: $screen (${lang.tag})", nodes.size >= MIN_TEXT_NODES)
        nodes.forEach { node ->
            inspectTextNode(node, density).forEach { (text, detail) ->
                overflows += Overflow(lang.tag, screen, resolver.keyOf(text, screenPrefix(screen)) ?: "text:$text", text, detail)
            }
        }
    }

    /** String key prefix of the [screen] screen (D5: strings split by screen). */
    private fun screenPrefix(screen: String): String =
        when {
            screen.startsWith("Home") -> "home_"
            screen.startsWith("Reading") -> "reading_"
            screen.startsWith("Result") -> "result_"
            screen.startsWith("Trust store") -> "trust_"
            screen.startsWith("Introduction") || screen.startsWith("Chip contents") -> "learn_"
            else -> "about_"
        }

    private fun compare(found: List<Overflow>) {
        val known =
            checkNotNull(javaClass.getResourceAsStream(KNOWN_OVERFLOWS)) { KNOWN_OVERFLOWS }
                .bufferedReader()
                .readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        val byKey = found.groupBy { it.listKey }
        val unexpected = byKey.keys - known
        val gone = known - byKey.keys
        if (unexpected.isEmpty() && gone.isEmpty()) return
        val report =
            buildString {
                if (unexpected.isNotEmpty()) {
                    appendLine("${unexpected.size} overflow(s) missing from $KNOWN_OVERFLOWS:")
                    unexpected.sorted().forEach { key ->
                        val occurrences = byKey.getValue(key)
                        val first = occurrences.first()
                        appendLine("$key")
                        appendLine("    \"${first.text}\"")
                        occurrences.distinctBy { it.screen to it.detail }.forEach { appendLine("    [${it.screen}] ${it.detail}") }
                    }
                }
                if (gone.isNotEmpty()) {
                    appendLine("${gone.size} listed anomaly(ies) that no longer occur (remove from $KNOWN_OVERFLOWS):")
                    gone.sorted().forEach { appendLine(it) }
                }
            }
        fail(report)
    }

    // --- Languages -----------------------------------------------------------------------------

    /** Languages offered by `locales_config.xml`, read on every run. */
    private fun declaredLanguages(context: Context): List<Language> {
        val tags = mutableListOf<String>()
        context.resources.getXml(R.xml.locales_config).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    tags += checkNotNull(parser.getAttributeValue(ANDROID_NS, "name"))
                }
            }
        }
        assertTrue("locales_config.xml is empty", tags.size > 1)
        return tags.map { Language(it, Locale.forLanguageTag(it)) }
    }

    /**
     * Safeguard: each language must resolve its own strings (and not fall back to French), and
     * Arabic and Hebrew must be laid out right to left.
     */
    private fun checkLanguagesAreLoaded(context: Context) {
        val samples = listOf(R.string.home_read, R.string.reading_title, R.string.result_title, R.string.trust_title, R.string.about_title)
        val frenchSamples = samples.map { resources(french).getString(it) }
        languages.filter { it.tag != "fr" }.forEach { lang ->
            val localized = samples.map { resources(lang).getString(it) }
            assertTrue("French strings for ${lang.tag}", localized != frenchSamples)
        }
        listOf("ar", "iw").forEach { tag ->
            val lang = languages.firstOrNull { it.tag == tag } ?: return@forEach
            assertEquals("layout direction of $tag", View.LAYOUT_DIRECTION_RTL, configuration(context, lang).layoutDirection)
        }
    }

    private val resourcesCache = HashMap<String, Resources>()

    private fun resources(lang: Language): Resources =
        resourcesCache.getOrPut(lang.tag) {
            compose.activity.createConfigurationContext(configuration(compose.activity, lang)).resources
        }

    private fun string(id: Int): String = resources(language).getString(id)

    private fun hasTextRes(id: Int): SemanticsMatcher = hasText(string(id))

    private fun hasContentDescriptionRes(id: Int): SemanticsMatcher =
        SemanticsMatcher("contentDescription") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(string(id)) == true
        }

    // --- Synchronization -------------------------------------------------------------------------

    /** A few frames, then the main thread queue drained. */
    private fun settle(frames: Int = 5) {
        repeat(frames) {
            compose.mainClock.advanceTimeByFrame()
            // Robolectric clock: Choreographer, hence measure and layout of the Compose view.
            shadowOf(Looper.getMainLooper()).idleFor(FRAME)
        }
        compose.waitForIdle()
    }

    /** Waits (in real time: reading and loading run off the main thread). */
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

    private fun awaitState(
        what: String,
        condition: (ReadState) -> Boolean,
    ) = awaitUi(what) { condition(session.state.value) }

    private fun setNfc(
        present: Boolean,
        enabled: Boolean,
    ) {
        ShadowNfcAdapter.setNfcHardwareExists(present)
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, present)
        if (present) shadowOf(NfcAdapter.getDefaultAdapter(compose.activity)).setEnabled(enabled)
    }

    private fun dateDigits(
        order: DateOrder,
        day: String,
        month: String,
        year: String,
    ): String =
        order.parts.joinToString("") {
            when (it) {
                DatePart.DAY -> day
                DatePart.MONTH -> month
                DatePart.YEAR -> year
            }
        }

    @Composable
    private fun Localized(
        lang: Language,
        content: @Composable () -> Unit,
    ) {
        val base = LocalContext.current
        val configuration = remember(lang.tag) { configuration(base, lang) }
        val context = remember(configuration) { LocalizedContext(base, configuration) }
        val direction = if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalResources provides context.resources,
            LocalLayoutDirection provides direction,
        ) {
            SceauTheme(darkTheme = false, content = content)
        }
    }

    private companion object {
        const val KNOWN_OVERFLOWS = "/l10n/known-overflows.txt"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val SLOW_CHIP_WAIT_MILLIS = 6_000L
        const val AWAIT_TIMEOUT_NANOS = 60_000_000_000L
        const val POLL_MILLIS = 10L
        const val MIN_TEXT_NODES = 3
        const val OFF_SCREEN_REPEAT = 6

        /** Frames allowed for the animated scrolling of an introduction page. */
        const val PAGE_ANIMATION_FRAMES = 60

        /** Font scale of the "font 200%" scenes (maximum setting of Android 14). */
        const val LARGE_FONT_SCALE = 2f
        val FRAME: Duration = Duration.ofMillis(16)
        val TALL_LIST_HEIGHT = 25_000.dp
        val STATUS_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())

        fun configuration(
            context: Context,
            lang: Language,
        ): Configuration =
            Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(lang.locale))
                setLayoutDirection(lang.locale)
                fontScale = 1f
            }
    }
}

/** Activity context with the resources of another language. */
private class LocalizedContext(
    base: Context,
    configuration: Configuration,
) : ContextWrapper(base) {
    private val localized = base.createConfigurationContext(configuration).resources

    override fun getResources(): Resources = localized
}

/** Transport that answers (or throws) through [respond] for each APDU. */
private class FailingTransport(
    private val respond: () -> ByteArray,
) : CardTransport {
    override val maxTransceiveLength: Int = MAX_APDU
    override var timeoutMillis: Int = TIMEOUT_MILLIS

    override fun transceive(apdu: ByteArray): ByteArray = respond()

    override fun close() = Unit

    private companion object {
        const val MAX_APDU = 261
        const val TIMEOUT_MILLIS = 10_000
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
