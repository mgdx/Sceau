package io.github.mgdx.sceau.ui.learn

import android.content.Context
import androidx.core.content.edit

/**
 * "Introduction seen" flag (D35), the app's only preference. It is neither data read from
 * the chip nor a key (SPEC §8): a boolean, written when the user leaves the first-launch
 * introduction.
 */
class IntroPreferences(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** True once the introduction has been skipped or completed. */
    val isIntroSeen: Boolean
        get() = preferences.getBoolean(KEY_INTRO_SEEN, false)

    fun markIntroSeen() {
        preferences.edit { putBoolean(KEY_INTRO_SEEN, true) }
    }

    private companion object {
        const val FILE_NAME = "sceau_preferences"
        const val KEY_INTRO_SEEN = "intro_seen"
    }
}
