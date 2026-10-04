package io.github.mgdx.sceau.ui.learn

import android.content.Context
import androidx.core.content.edit

/**
 * Indicateur « introduction vue » (D35), seule préférence de l'application. Ce n'est ni une
 * donnée lue ni une clé (SPEC §8) : un booléen, écrit quand l'utilisateur quitte
 * l'introduction du premier lancement.
 */
class IntroPreferences(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Vrai une fois l'introduction passée ou terminée. */
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
