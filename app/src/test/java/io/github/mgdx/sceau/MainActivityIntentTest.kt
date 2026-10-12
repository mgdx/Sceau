package io.github.mgdx.sceau

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.mgdx.sceau.ui.Routes
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * An intent from outside does not drive navigation (audit V27): Navigation deep link extras
 * (`deepLinkIds`...) are removed before composition, and Sceau opens on the introduction on first
 * launch (or the home screen afterwards), never directly on a screen chosen by the caller.
 *
 * Navigation 2.10 already drops these extras when the caller is not Sceau itself
 * (`NavController.shouldTrustIntent`, based on the calling package or the referrer). The tests
 * therefore cover the worst case, an intent that Navigation would accept (calling package =
 * Sceau): the activity itself must neutralize them, without relying on this internal check of
 * the library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class MainActivityIntentTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Explicit intent as another app can build it: the graph ID (0, graph without a route), then
     * the ID of the target destination, computed the way Navigation does.
     */
    private fun deepLinkIntent(route: String): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(KEY_DEEP_LINK_IDS, intArrayOf(ROOT_GRAPH_ID, "android-app://androidx.navigation/$route".hashCode()))

    private fun launch(intent: Intent): ActivityController<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        shadowOf(controller.get()).setCallingPackage(context.packageName)
        return controller.setup()
    }

    private fun assertIntroShownAndNotTrustStore() {
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.learn_intro_skip)).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.trust_title)).assertCountEquals(0)
    }

    @Test
    fun `deepLinkIds to the trust store are ignored at launch`() {
        val controller = launch(deepLinkIntent(Routes.TRUST))
        assertIntroShownAndNotTrustStore()
        assertNull(controller.get().intent.extras)
        controller.pause().stop().destroy()
    }

    @Test
    fun `deepLinkIds are ignored when the already open activity receives a new intent`() {
        val controller = launch(Intent(context, MainActivity::class.java))
        compose.waitForIdle()
        controller.newIntent(deepLinkIntent(Routes.TRUST))
        assertIntroShownAndNotTrustStore()
        assertNull(controller.get().intent.extras)
        controller.pause().stop().destroy()
    }

    private companion object {
        /** `NavController.KEY_DEEP_LINK_IDS`. */
        const val KEY_DEEP_LINK_IDS = "android-support-nav:controller:deepLinkIds"
        const val ROOT_GRAPH_ID = 0
    }
}
