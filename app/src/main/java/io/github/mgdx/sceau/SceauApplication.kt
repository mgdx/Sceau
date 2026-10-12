package io.github.mgdx.sceau

import android.app.Application
import io.github.mgdx.sceau.jp2.isIsolatedProcess
import io.github.mgdx.sceau.trust.TrustStoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SceauApplication : Application() {
    /** Single instance of the trust store repository, shared by all screens. */
    val trustStoreRepository: TrustStoreRepository by lazy { TrustStoreRepository(this) }

    /** Application scope: lives as long as the process. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Isolated process of the JPEG 2000 decoder (D23): nothing to preload there.
        if (isIsolatedProcess()) return
        // Preloading of the trust store (several seconds on an old phone), so that neither the
        // first read nor the "Trust store" screen has to wait. An error is ignored: the next
        // get() will retry loading and report it.
        applicationScope.launch {
            try {
                trustStoreRepository.get()
            } catch (_: Exception) {
                // Deliberately ignored, see above.
            }
        }
    }
}
