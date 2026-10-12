package io.github.mgdx.sceau.jp2

import android.os.Build
import android.os.Process

/**
 * True in the isolated process of [Jpeg2000Service] (decision D23). Android also creates
 * Sceau's `Application` there: it uses this to initialize nothing in it (no trust store,
 * which an isolated UID could not read from disk anyway).
 */
fun isIsolatedProcess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Process.isIsolated()
    } else {
        isIsolatedUid(Process.myUid())
    }
