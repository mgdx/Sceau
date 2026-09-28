package io.github.mgdx.sceau.trust

import android.content.Context
import io.github.mgdx.sceau.core.trust.MasterList
import io.github.mgdx.sceau.core.trust.TrustStore
import io.github.mgdx.sceau.core.trust.TrustStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Accès au magasin de confiance depuis l'app. Singleton applicatif (voir SceauApplication).
 * Les Master Lists importées sont stockées telles quelles dans `filesDir/trust/` : ce ne
 * sont pas des données personnelles. Toutes les fonctions s'exécutent sur Dispatchers.IO.
 */
class TrustStoreRepository(
    private val context: Context,
) {
    private val mutex = Mutex()

    /** Magasin fusionné en mémoire ; null tant qu'il n'est pas chargé ou après un import. */
    private var cached: TrustStore? = null

    private val directory: File get() = File(context.filesDir, IMPORT_DIRECTORY)

    /** Magasin fusionné, chargé une fois puis mis en cache en mémoire jusqu'au prochain import. */
    suspend fun get(): TrustStore =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                cached ?: TrustStores.load(readImported()).also { cached = it }
            }
        }

    /** Parse et vérifie une Master List sans l'importer (pour afficher l'empreinte du signataire). */
    suspend fun preview(bytes: ByteArray): MasterList = withContext(Dispatchers.IO) { TrustStores.parseMasterList(bytes) }

    /**
     * Vérifie, avant toute analyse, que [bytes] pourrait être importée sans dépasser les limites
     * de [ImportLimits] ; lève [ImportLimitException] sinon.
     */
    suspend fun checkImportAllowed(bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock { requireAllowed(bytes) }
        }

    /**
     * Importe une Master List déjà prévisualisée et confirmée ; invalide le cache. Une liste déjà
     * importée est laissée telle quelle ; au-delà des limites de [ImportLimits], lève
     * [ImportLimitException].
     */
    suspend fun import(bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            // Revérifiée ici : l'appelant ne doit pas pouvoir écrire une liste invalide.
            TrustStores.parseMasterList(bytes)
            mutex.withLock {
                if (requireAllowed(bytes) == ImportDecision.ALLOWED) {
                    val dir = directory
                    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("TRUST_DIR")
                    writeAtomically(dir, File(dir, importFileName(bytes)), bytes)
                    cached = null
                }
            }
        }

    /** Décision d'import de [bytes] ; lève [ImportLimitException] si l'import est refusé. */
    private fun requireAllowed(bytes: ByteArray): ImportDecision {
        val existing = importedFiles().map { ImportedFile(it.name, it.length()) }
        val decision = ImportLimits.decide(existing, importFileName(bytes), bytes.size.toLong())
        if (decision == ImportDecision.TOO_MANY || decision == ImportDecision.TOO_LARGE_TOTAL) {
            throw ImportLimitException(decision)
        }
        return decision
    }

    /** Supprime toutes les Master Lists importées ; invalide le cache. */
    suspend fun clearImported(): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                directory.listFiles()?.forEach { file ->
                    if (file.isFile && !file.delete()) throw IOException("TRUST_DELETE")
                }
                cached = null
            }
        }

    private fun readImported(): List<ByteArray> = importedFiles().sortedBy { it.name }.map { it.readBytes() }

    private fun importedFiles(): List<File> =
        directory
            .listFiles { file -> file.isFile && file.name.endsWith(IMPORT_EXTENSION) }
            .orEmpty()
            .toList()

    /** Écrit dans un fichier temporaire du même dossier, synchronise, puis renomme. */
    private fun writeAtomically(
        dir: File,
        target: File,
        bytes: ByteArray,
    ) {
        val temp = File.createTempFile("import-", TEMP_EXTENSION, dir)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!temp.renameTo(target)) throw IOException("TRUST_RENAME")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    companion object {
        private const val IMPORT_DIRECTORY = "trust"
        private const val IMPORT_EXTENSION = ".ml"
        private const val TEMP_EXTENSION = ".tmp"

        /** Nom de fichier d'une Master List importée : SHA-256 du contenu en hexadécimal, suffixé `.ml`. */
        fun importFileName(bytes: ByteArray): String = sha256Hex(bytes) + IMPORT_EXTENSION
    }
}
