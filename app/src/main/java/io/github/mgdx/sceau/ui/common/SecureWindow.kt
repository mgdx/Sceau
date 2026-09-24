package io.github.mgdx.sceau.ui.common

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.WeakHashMap

/**
 * Pose `FLAG_SECURE` sur la fenêtre de l'activité tant que ce composable est dans la
 * composition (pas de capture d'écran, pas d'aperçu dans le multitâche, SPEC §8), puis le
 * retire à la sortie.
 *
 * Les demandes sont comptées par fenêtre : pendant une transition Lecture → Résultat, les
 * deux écrans sont brièvement composés ensemble et la sortie du premier ne doit pas lever
 * la protection posée par le second.
 */
@Composable
fun SecureWindow() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        SecureWindowCounter.acquire(window)
        onDispose { SecureWindowCounter.release(window) }
    }
}

/** Compteur de demandes par fenêtre. Accédé uniquement depuis le thread principal. */
private object SecureWindowCounter {
    private val counts = WeakHashMap<Window, Int>()

    fun acquire(window: Window) {
        val count = counts[window] ?: 0
        if (count == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        counts[window] = count + 1
    }

    fun release(window: Window) {
        val count = (counts[window] ?: 0) - 1
        if (count <= 0) {
            counts.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            counts[window] = count
        }
    }
}
