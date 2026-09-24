package io.github.mgdx.sceau

import android.app.Application
import io.github.mgdx.sceau.trust.TrustStoreRepository

class SceauApplication : Application() {
    /** Instance unique du dépôt de magasin de confiance, partagée par tous les écrans. */
    val trustStoreRepository: TrustStoreRepository by lazy { TrustStoreRepository(this) }
}
