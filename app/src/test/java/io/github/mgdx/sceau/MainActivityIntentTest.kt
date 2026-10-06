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
 * Un intent venu de l'extérieur ne pilote pas la navigation (audit V27) : les extras de deep link
 * de Navigation (`deepLinkIds`…) sont retirés avant la composition, Sceau s'ouvre sur
 * l'introduction au premier lancement (ou l'accueil ensuite), jamais directement sur un écran
 * choisi par l'appelant.
 *
 * Navigation 2.10 écarte déjà ces extras quand l'appelant n'est pas Sceau lui-même
 * (`NavController.shouldTrustIntent`, d'après le paquet appelant ou le referrer). Les tests se
 * placent donc dans le cas le plus défavorable, celui d'un intent que Navigation accepterait
 * (paquet appelant = Sceau) : c'est l'activité qui doit les neutraliser, sans dépendre de ce
 * contrôle interne à la bibliothèque.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class MainActivityIntentTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Intent explicite tel qu'une autre application peut le construire : identifiant du graphe
     * (0, graphe sans route), puis celui de la destination visée, calculé comme Navigation.
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
    fun `deepLinkIds vers le magasin de confiance ignore au lancement`() {
        val controller = launch(deepLinkIntent(Routes.TRUST))
        assertIntroShownAndNotTrustStore()
        assertNull(controller.get().intent.extras)
        controller.pause().stop().destroy()
    }

    @Test
    fun `deepLinkIds ignore quand l'activite deja ouverte recoit un nouvel intent`() {
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
