package io.github.mgdx.sceau.ui.learn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class IntroPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `l'introduction n'est pas vue au premier lancement`() {
        assertFalse(IntroPreferences(context).isIntroSeen)
    }

    @Test
    fun `l'introduction vue le reste pour une nouvelle instance`() {
        IntroPreferences(context).markIntroSeen()
        assertTrue(IntroPreferences(context).isIntroSeen)
    }
}
