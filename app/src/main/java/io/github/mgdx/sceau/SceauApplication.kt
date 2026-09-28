package io.github.mgdx.sceau

import android.app.Application
import io.github.mgdx.sceau.jp2.isIsolatedProcess
import io.github.mgdx.sceau.trust.TrustStoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SceauApplication : Application() {
    /** Instance unique du dépôt de magasin de confiance, partagée par tous les écrans. */
    val trustStoreRepository: TrustStoreRepository by lazy { TrustStoreRepository(this) }

    /** Portée applicative : vit aussi longtemps que le processus. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Processus isolé du décodeur JPEG 2000 (D23) : rien à y précharger.
        if (isIsolatedProcess()) return
        // Préchargement du magasin de confiance (plusieurs secondes sur un téléphone ancien),
        // pour que ni la première lecture ni l'écran Magasin de confiance n'attendent. Une
        // erreur est ignorée : le prochain get() retentera le chargement et la signalera.
        applicationScope.launch {
            try {
                trustStoreRepository.get()
            } catch (_: Exception) {
                // Ignorée volontairement, voir ci-dessus.
            }
        }
    }
}
