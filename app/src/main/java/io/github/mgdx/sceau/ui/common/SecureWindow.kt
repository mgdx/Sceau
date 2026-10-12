package io.github.mgdx.sceau.ui.common

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.WeakHashMap

/**
 * Sets `FLAG_SECURE` on the activity window while this composable is in the composition
 * (no screenshots, no preview in the recent apps screen, SPEC §8), then clears it on exit,
 * unless the window is protected permanently ([secureForLifetime]).
 *
 * Requests are counted per window: during a Reading → Result transition, both screens are
 * briefly composed together and the exit of the first must not lift the protection set by
 * the second.
 */
@Composable
fun SecureWindow() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        SecureWindowCounter.acquire(window)
        onDispose { SecureWindowCounter.release(window) }
    }
}

/**
 * Sets `FLAG_SECURE` on [window] for its whole lifetime: a request that is never released,
 * which the exit of a [SecureWindow] screen therefore cannot lift. To be called in `onCreate`,
 * before the first draw, so that the recent apps preview never captures the home screen input
 * (audit V7). Main thread only.
 */
fun secureForLifetime(window: Window) {
    SecureWindowCounter.acquire(window)
}

/** Per-window request counter. Accessed only from the main thread. */
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
