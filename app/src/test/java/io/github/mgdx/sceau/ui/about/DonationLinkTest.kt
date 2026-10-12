package io.github.mgdx.sceau.ui.about

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.mgdx.sceau.trust.TrustStoreRepository
import io.github.mgdx.sceau.ui.theme.SceauTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * D52: "About" offers a donation on Liberapay, opened in the browser through an ACTION_VIEW
 * Intent (no network permission), in French as well as in English.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h640dp-xxhdpi")
class DonationLinkTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun assertDonationOpensLiberapay(
        title: String,
        text: String,
        button: String,
    ) {
        // Trust store that fails to load: the donation section does not depend on it.
        val repository = TrustStoreRepository(compose.activity) { _, _ -> throw IllegalStateException("TEST_LOAD") }
        compose.setContent {
            SceauTheme { AboutScreen(repository, onBack = {}, onReplayIntro = {}, onOpenChipContents = {}) }
        }
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(button).performScrollTo().performClick()

        val intent = shadowOf(compose.activity).nextStartedActivity
        assertNotNull("no activity started", intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://liberapay.com/mgdx", intent.dataString)
    }

    @Test
    @Config(qualifiers = "fr")
    fun `donation link in French`() {
        assertDonationOpensLiberapay(
            title = "Soutenir Sceau",
            text = "Vous pouvez soutenir son développement par un don sur Liberapay.",
            button = "Faire un don",
        )
    }

    @Test
    @Config(qualifiers = "en")
    fun `donation link in English`() {
        assertDonationOpensLiberapay(
            title = "Support Sceau",
            text = "You can support its development with a donation on Liberapay.",
            button = "Donate",
        )
    }
}
