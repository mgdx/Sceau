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
    fun `intro is not seen on first launch`() {
        assertFalse(IntroPreferences(context).isIntroSeen)
    }

    @Test
    fun `seen intro stays seen for a new instance`() {
        IntroPreferences(context).markIntroSeen()
        assertTrue(IntroPreferences(context).isIntroSeen)
    }
}
