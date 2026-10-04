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
import io.github.mgdx.sceau.mrz.MrzFormat
import io.github.mgdx.sceau.mrz.MrzKeyFields
import io.github.mgdx.sceau.session.DateOrder
import io.github.mgdx.sceau.session.DatePart
import io.github.mgdx.sceau.session.DocumentTab
import io.github.mgdx.sceau.session.IdCardKey
import io.github.mgdx.sceau.session.ReadState
import io.github.mgdx.sceau.session.SessionViewModel
import io.github.mgdx.sceau.testchip.SimulatedChip
import io.github.mgdx.sceau.testchip.SimulatedDocuments
import io.github.mgdx.sceau.trust.TrustStoreRepository
import io.github.mgdx.sceau.ui.about.AboutScreen
import io.github.mgdx.sceau.ui.home.HomeScreen
import io.github.mgdx.sceau.ui.reading.ReadingScreen
import io.github.mgdx.sceau.ui.result.Countries
import io.github.mgdx.sceau.ui.result.ResultScreen
import io.github.mgdx.sceau.ui.scan.MrzScanScaffold
import io.github.mgdx.sceau.ui.scan.PermissionPanel
import io.github.mgdx.sceau.ui.scan.ScanViewfinderLayout
import io.github.mgdx.sceau.ui.scan.StatusLine
import io.github.mgdx.sceau.ui.theme.SceauTheme
import io.github.mgdx.sceau.ui.trust.TrustStoreScreen
import io.github.mgdx.sceau.ui.trust.groupByCountry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * Textes qui débordent ou sont tronqués, dans chaque langue de `locales_config.xml` (D28).
 * `FLAG_SECURE` interdit toute capture d'écran sur appareil : ce test rend les vrais écrans sous
 * Robolectric, avec le vrai moteur de texte d'Android et ses polices (`GraphicsMode.NATIVE`),
 * sur un téléphone étroit (360 × 640 dp, police à 100 %), et relève chaque nœud de texte qui
 * dépasse sa boîte, est ellipsé ou sort de l'écran.
 *
 * Toutes les anomalies sont collectées puis comparées à `l10n/known-overflows.txt` (lignes
 * `langue|clé`) : le test échoue si une anomalie n'y est pas, ou si une anomalie listée a
 * disparu (liste à tenir à jour).
 *
 * La langue est changée par les `CompositionLocal` de configuration (contexte, ressources,
 * sens d'écriture) au-dessus des écrans : une seule activité, un seul magasin de confiance
 * chargé, ce qui garde la durée raisonnable pour 45 langues.
 *
 * Données exclues : aucune pour l'instant. Les valeurs de la puce (`FieldRow`, détails des
 * contrôles) ont un `maxLines` explicite avec ellipse volontaire ; les données du spécimen sont
 * courtes et ne l'atteignent pas.
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
    fun `aucun texte ne deborde, dans aucune langue`() {
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
        // Horloge manuelle : les indicateurs de progression infinis empêcheraient Compose d'être
        // jamais « au repos ».
        compose.mainClock.autoAdvance = false
        settle()

        homeScenes()
        scanScenes()
        readingScenes()
        resultScenes()
        trustStoreScene()
        aboutScene()
        session.clear()
        settle()

        compare(overflows)
    }

    /** Témoin : le détecteur signale bien chaque sorte de débordement, sur des textes factices. */
    @Test
    fun `le detecteur signale chaque sorte de debordement`() {
        val offScreen = "Hors de l'écran, ".repeat(OFF_SCREEN_REPEAT)
        compose.setContent {
            SceauTheme {
                Column {
                    Text("Ellipse ellipse ellipse", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(80.dp))
                    Text("Hauteur hauteur hauteur", modifier = Modifier.width(80.dp).height(20.dp))
                    Box(Modifier.width(80.dp).semantics { }) { Text("Reisepassdokumentenüberprüfung") }
                    Box(Modifier.height(20.dp).semantics { }) {
                        Text("Rogné rogné rogné", modifier = Modifier.width(80.dp).wrapContentHeight(unbounded = true))
                    }
                    Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(1000.dp)) {
                        Text(offScreen, softWrap = false)
                    }
                    Text("Texte qui tient")
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
        ) = assertTrue("$text : $problem attendu, trouvé ${found[text]}", found[text].orEmpty().any { it.startsWith(problem) })
        assertFound("Ellipse ellipse ellipse", "ellipsé")
        assertFound("Hauteur hauteur hauteur", "dépasse sa boîte")
        assertFound("Reisepassdokumentenüberprüfung", "mot coupé")
        assertFound("Rogné rogné rogné", "sort de son conteneur")
        assertFound(offScreen, "sort de l'écran")
        assertEquals(null, found["Texte qui tient"])
    }

    // --- Scènes ------------------------------------------------------------------------------

    private fun homeScenes() {
        val homeContent: @Composable () -> Unit = {
            HomeScreen(
                session,
                onRead = {},
                onOpenTrustStore = {},
                onOpenAbout = {},
                onScanMrz = {},
            )
        }

        // Caméra déclarée (absente par défaut sous Robolectric) : bouton « Scanner la MRZ » affiché.
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        setNfc(present = true, enabled = false)
        session.clear()
        session.selectTab(DocumentTab.ID_CARD)
        render("Accueil, carte d'identité, NFC désactivé", homeContent)

        render("Accueil, menu", homeContent, afterShow = {
            compose
                .onNode(hasContentDescriptionRes(R.string.home_menu_more))
                .performSemanticsAction(SemanticsActions.OnClick)
            settle(frames = 30)
            assertTrue(
                "menu non ouvert",
                compose.onAllNodes(hasTextRes(R.string.home_menu_about), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
            )
        })

        setNfc(present = true, enabled = true)
        session.selectIdCardKey(IdCardKey.MRZ)
        render("Accueil, carte d'identité, MRZ", homeContent)
        session.selectIdCardKey(IdCardKey.CAN)

        setNfc(present = false, enabled = false)
        session.selectTab(DocumentTab.PASSPORT)
        session.onDocumentNumberChange("AB1234567")
        render("Accueil, passeport, dates future et trop ancienne, sans NFC", homeContent, perLanguage = { lang ->
            val order = DateOrder.forLocale(lang.locale)
            session.onDateOfBirthChange(dateDigits(order, day = "01", month = "01", year = "2099"))
            session.onDateOfExpiryChange(dateDigits(order, day = "01", month = "01", year = "1980"))
        })
        session.onDateOfBirthChange("99999999")
        session.onDateOfExpiryChange("99999999")
        render("Accueil, passeport, dates invalides, sans NFC", homeContent)
        session.clear()

        // Retour du scan : champs remplis et message « MRZ lue ». Le message est figé dans la
        // langue où il a été émis : il est retiré (délai écoulé) puis réémis dans chaque langue.
        setNfc(present = true, enabled = true)
        session.selectTab(DocumentTab.PASSPORT)
        render(
            "Accueil, passeport, MRZ scannée",
            homeContent,
            perLanguage = {
                compose.mainClock.advanceTimeBy(SNACKBAR_DISMISS_MILLIS)
                session.onMrzScanned(SCANNED_MRZ)
            },
            ready = {
                settle(frames = 30)
                assertTrue(
                    "message « MRZ lue » absent",
                    compose.onAllNodes(hasTextRes(R.string.home_mrz_scanned), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
                )
                assertTrue(
                    "bouton « Scanner la MRZ » absent",
                    compose.onAllNodes(hasTextRes(R.string.home_scan_mrz), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
                )
            },
        )
        session.clear()
    }

    /** Écran de scan sans caméra : chaque ligne d'état, puis la permission refusée (D32). */
    private fun scanScenes() {
        StatusLine.entries.forEach { status ->
            render("Scan, état $status", {
                MrzScanScaffold(onBack = {}) { ScanViewfinderLayout(geometry = null, status = status, onManualEntry = {}) }
            })
        }
        listOf(false to "Scan, permission refusée", true to "Scan, permission refusée définitivement").forEach { (permanently, name) ->
            render(name, {
                MrzScanScaffold(onBack = {}) {
                    PermissionPanel(permanentlyDenied = permanently, onRequest = {}, onOpenSettings = {}, onBack = {})
                }
            })
        }
    }

    private fun readingScenes() {
        val readingContent: @Composable () -> Unit = { ReadingScreen(session, onDone = {}, onCancel = {}) }
        val card = SimulatedDocuments.frenchIdCard()
        val can = checkNotNull(card.canKey)

        // Puce qui fait patienter : la lecture de la CNIe simulée est retenue dès l'ouverture
        // du canal sécurisé, et l'horloge avancée au-delà du délai du message.
        val slow = HoldingTransport(card.chip()) { (session.state.value as? ReadState.Reading)?.current == Step.SECURE_CHANNEL }
        session.startDemo(DemoCard(slow, can, card.trustStore))
        awaitState("canal sécurisé") { (it as? ReadState.Reading)?.current == Step.SECURE_CHANNEL }
        render("Lecture en cours, puce qui fait patienter", readingContent, afterShow = {
            compose.mainClock.advanceTimeBy(SLOW_CHIP_WAIT_MILLIS)
            settle()
            assertTrue(
                "message « puce qui fait patienter » absent",
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
            )
        errors.forEach { (code, demoCard) ->
            session.startDemo(demoCard)
            awaitState("erreur $code") { it is ReadState.Error }
            val actual = (session.state.value as ReadState.Error).code
            assertTrue("code $actual au lieu de $code", actual.startsWith(code))
            render("Lecture, erreur $code", readingContent)
            session.clear()
        }
    }

    private fun resultScenes() {
        val resultContent: @Composable () -> Unit = { ResultScreen(session, onClear = {}) }

        session.startDemo(checkNotNull(DemoMode.newSimulatedCnie()))
        awaitState("rapport de la démo") { it is ReadState.Done }
        assertEquals(Verdict.AUTHENTIC, (session.state.value as ReadState.Done).report.verdict)
        render("Résultat, authentique (démo)", resultContent, afterShow = ::showResultDetails)
        session.clear()

        // Même CNIe simulée lue avec le vrai magasin, qui ne connaît pas son CSCA de test.
        val card = SimulatedDocuments.frenchIdCard()
        val store = runBlocking { repository.get() }
        session.startDemo(DemoCard(card.chip(), checkNotNull(card.canKey), store))
        awaitState("rapport émetteur inconnu") { it is ReadState.Done }
        assertEquals(Verdict.UNKNOWN_ISSUER, (session.state.value as ReadState.Done).report.verdict)
        render("Résultat, émetteur inconnu", resultContent, afterShow = ::showResultDetails)
        session.clear()
    }

    /** Attend la fin du décodage du portrait (indisponible sous Robolectric), puis déplie les contrôles. */
    private fun showResultDetails() {
        awaitUi("portrait décodé ou indisponible") {
            compose.onAllNodes(hasTextRes(R.string.result_photo_unavailable)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasContentDescriptionRes(R.string.result_photo_description)).fetchSemanticsNodes().isNotEmpty()
        }
        val expand =
            SemanticsMatcher("déplier") {
                it.config.getOrNull(SemanticsActions.OnClick)?.label ==
                    string(R.string.result_check_expand)
            }
        val count = compose.onAllNodes(expand).fetchSemanticsNodes().size
        assertTrue("aucun contrôle à déplier", count > 0)
        repeat(count) {
            compose.onAllNodes(expand)[0].performSemanticsAction(SemanticsActions.OnClick)
            settle()
        }
    }

    private fun trustStoreScene() {
        val store = runBlocking { repository.get() }
        render(
            "Magasin de confiance, France et Royaume-Uni dépliés",
            {
                // Hauteur démesurée : la LazyColumn compose alors tous ses éléments, sans défilement.
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
                            SemanticsMatcher("en-tête de pays") {
                                val label = it.config.getOrNull(SemanticsActions.OnClick)?.label
                                label == string(R.string.trust_country_expand) || label == string(R.string.trust_country_collapse)
                            },
                        ).fetchSemanticsNodes()
                        .size
                assertEquals("en-têtes de pays composés (${lang.tag})", groups, headers)
            },
        )
    }

    private fun awaitTrustStoreLoaded() {
        awaitUi("magasin chargé") { compose.onAllNodes(hasTextRes(R.string.trust_import)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun aboutScene() {
        render("À propos", { AboutScreen(repository, onBack = {}) }, ready = {
            awaitUi("magasin embarqué décrit") {
                compose.onAllNodes(hasTextRes(R.string.about_trust_ants_and_bsi)).fetchSemanticsNodes().isNotEmpty()
            }
        })
    }

    // --- Rendu et relevé -----------------------------------------------------------------------

    /**
     * Affiche [content] en français, exécute [afterShow] (menus, dépliage), puis, pour chaque
     * langue, applique [perLanguage], attend [ready] et relève les textes qui débordent.
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
        assertTrue("aucun texte affiché : $screen (${lang.tag})", nodes.size >= MIN_TEXT_NODES)
        nodes.forEach { node ->
            inspectTextNode(node, density).forEach { (text, detail) ->
                overflows += Overflow(lang.tag, screen, resolver.keyOf(text, screenPrefix(screen)) ?: "texte:$text", text, detail)
            }
        }
    }

    /** Préfixe des clés de chaînes de l'écran [screen] (D5 : chaînes réparties par écran). */
    private fun screenPrefix(screen: String): String =
        when {
            screen.startsWith("Accueil") -> "home_"
            screen.startsWith("Scan") -> "scan_"
            screen.startsWith("Lecture") -> "reading_"
            screen.startsWith("Résultat") -> "result_"
            screen.startsWith("Magasin") -> "trust_"
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
                    appendLine("${unexpected.size} débordement(s) absent(s) de $KNOWN_OVERFLOWS :")
                    unexpected.sorted().forEach { key ->
                        val occurrences = byKey.getValue(key)
                        val first = occurrences.first()
                        appendLine("$key")
                        appendLine("    « ${first.text} »")
                        occurrences.distinctBy { it.screen to it.detail }.forEach { appendLine("    [${it.screen}] ${it.detail}") }
                    }
                }
                if (gone.isNotEmpty()) {
                    appendLine("${gone.size} anomalie(s) listée(s) qui ne se produisent plus (à retirer de $KNOWN_OVERFLOWS) :")
                    gone.sorted().forEach { appendLine(it) }
                }
            }
        fail(report)
    }

    // --- Langues -------------------------------------------------------------------------------

    /** Langues proposées par `locales_config.xml`, lu à chaque exécution. */
    private fun declaredLanguages(context: Context): List<Language> {
        val tags = mutableListOf<String>()
        context.resources.getXml(R.xml.locales_config).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    tags += checkNotNull(parser.getAttributeValue(ANDROID_NS, "name"))
                }
            }
        }
        assertTrue("locales_config.xml vide", tags.size > 1)
        return tags.map { Language(it, Locale.forLanguageTag(it)) }
    }

    /**
     * Garde-fou : chaque langue doit résoudre ses propres chaînes (et non retomber sur le
     * français), et l'arabe et l'hébreu doivent s'écrire de droite à gauche.
     */
    private fun checkLanguagesAreLoaded(context: Context) {
        val samples = listOf(R.string.home_read, R.string.reading_title, R.string.result_title, R.string.trust_title, R.string.about_title)
        val frenchSamples = samples.map { resources(french).getString(it) }
        languages.filter { it.tag != "fr" }.forEach { lang ->
            val localized = samples.map { resources(lang).getString(it) }
            assertTrue("chaînes françaises pour ${lang.tag}", localized != frenchSamples)
        }
        listOf("ar", "iw").forEach { tag ->
            val lang = languages.firstOrNull { it.tag == tag } ?: return@forEach
            assertEquals("sens d'écriture de $tag", View.LAYOUT_DIRECTION_RTL, configuration(context, lang).layoutDirection)
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

    // --- Synchronisation -------------------------------------------------------------------------

    /** Quelques images, puis la file du thread principal vidée. */
    private fun settle(frames: Int = 5) {
        repeat(frames) {
            compose.mainClock.advanceTimeByFrame()
            // Horloge de Robolectric : Choreographer, donc mesure et placement de la vue Compose.
            shadowOf(Looper.getMainLooper()).idleFor(FRAME)
        }
        compose.waitForIdle()
    }

    /** Attend (en temps réel : lecture et chargements tournent hors du thread principal). */
    private fun awaitUi(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + AWAIT_TIMEOUT_NANOS
        while (true) {
            settle(frames = 1)
            if (condition()) return
            if (System.nanoTime() > deadline) fail("délai dépassé : $what")
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

        // Au-delà de SnackbarDuration.Short (4 s).
        const val SNACKBAR_DISMISS_MILLIS = 5_000L

        // Champs factices, sans lien avec un vrai document.
        val SCANNED_MRZ = MrzKeyFields(MrzFormat.TD3, documentNumber = "AB1234567", dateOfBirth = "900101", dateOfExpiry = "300101")
        const val AWAIT_TIMEOUT_NANOS = 60_000_000_000L
        const val POLL_MILLIS = 10L
        const val MIN_TEXT_NODES = 3
        const val OFF_SCREEN_REPEAT = 6
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

/** Contexte de l'activité avec les ressources d'une autre langue. */
private class LocalizedContext(
    base: Context,
    configuration: Configuration,
) : ContextWrapper(base) {
    private val localized = base.createConfigurationContext(configuration).resources

    override fun getResources(): Resources = localized
}

/** Transport qui répond (ou lève) par [respond] à chaque APDU. */
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

/** Relaie [delegate], mais bloque toute APDU tant que [hold] est vrai, jusqu'à la fermeture. */
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
