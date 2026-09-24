package io.github.mgdx.sceau.trust

import android.content.Context
import io.github.mgdx.sceau.core.trust.MasterList
import io.github.mgdx.sceau.core.trust.TrustStore

/**
 * Accès au magasin de confiance depuis l'app. Singleton applicatif (voir SceauApplication).
 * Les Master Lists importées sont stockées telles quelles dans `filesDir/trust/` : ce ne
 * sont pas des données personnelles. Toutes les fonctions s'exécutent sur Dispatchers.IO.
 */
class TrustStoreRepository(
    private val context: Context,
) {
    /** Magasin fusionné, chargé une fois puis mis en cache en mémoire jusqu'au prochain import. */
    suspend fun get(): TrustStore = TODO("lot E")

    /** Parse et vérifie une Master List sans l'importer (pour afficher l'empreinte du signataire). */
    suspend fun preview(bytes: ByteArray): MasterList = TODO("lot E")

    /** Importe une Master List déjà prévisualisée et confirmée ; invalide le cache. */
    suspend fun import(bytes: ByteArray): Unit = TODO("lot E")

    /** Supprime toutes les Master Lists importées ; invalide le cache. */
    suspend fun clearImported(): Unit = TODO("lot E")
}
