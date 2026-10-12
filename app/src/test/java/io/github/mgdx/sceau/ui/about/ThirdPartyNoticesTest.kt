package io.github.mgdx.sceau.ui.about

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.mgdx.sceau.trust.TrustStoreRepository
import io.github.mgdx.sceau.ui.theme.SceauTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit F3 (D50): "About" lists the bundled libraries and reproduces their license notices,
 * including the OpenJPEG BSD-2-Clause notice, in French as well as in English.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h640dp-xxhdpi")
class ThirdPartyNoticesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showAbout() {
        // Trust store that fails to load: the libraries section does not depend on it.
        val repository = TrustStoreRepository(compose.activity) { _, _ -> throw IllegalStateException("TEST_LOAD") }
        compose.setContent {
            SceauTheme { AboutScreen(repository, onBack = {}, onReplayIntro = {}, onOpenChipContents = {}) }
        }
    }

    /** Opens the [title] section with its [show] button, then checks the OpenJPEG notice. */
    private fun assertNoticesShown(
        title: String,
        intro: String,
        show: String,
        hide: String,
    ) {
        showAbout()
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(intro, substring = true).performScrollTo().assertIsDisplayed()
        assertTrue(
            "notice shown before the tap",
            compose.onAllNodes(hasText("Universite catholique de Louvain", substring = true)).fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithText(show).performScrollTo().performClick()
        compose.onNodeWithText(hide).assertExists()
        OPENJPEG_NOTICE.forEach { line ->
            compose.onNodeWithText(line, substring = true).assertExists()
        }
        LIBRARIES.forEach { name ->
            compose.onNodeWithText(name, substring = true).assertExists()
        }
    }

    @Test
    @Config(qualifiers = "fr")
    fun `libraries section in French with the OpenJPEG notice`() {
        assertNoticesShown(
            title = "Bibliothèques libres",
            intro = "JMRTD, SCUBA, Bouncy Castle, OpenJPEG, AndroidX, Kotlin",
            show = "Afficher les mentions de licence",
            hide = "Masquer les mentions de licence",
        )
    }

    @Test
    @Config(qualifiers = "en")
    fun `libraries section in English with the OpenJPEG notice`() {
        assertNoticesShown(
            title = "Open-source libraries",
            intro = "Sceau includes the following open-source libraries",
            show = "Show license notices",
            hide = "Hide license notices",
        )
    }

    private companion object {
        /** Excerpts from `app/src/main/cpp/openjpeg/LICENSE` that the BSD-2-Clause license requires reproducing. */
        val OPENJPEG_NOTICE =
            listOf(
                "Copyright (c) 2002-2014, Universite catholique de Louvain (UCL), Belgium",
                "Copyright (c) 2012, CS Systemes d'Information, France",
                "2. Redistributions in binary form must reproduce the above copyright notice",
                "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS `AS IS'",
            )

        val LIBRARIES =
            listOf(
                "Copyright (C) The JMRTD team",
                "Copyright (C) The SCUBA team",
                "The Legion of the Bouncy Castle Inc.",
                "Copyright (C) The Android Open Source Project",
                "Apache License, Version 2.0",
            )
    }
}
