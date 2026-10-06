package io.github.mgdx.sceau.trust

import android.content.Context
import io.github.mgdx.sceau.core.trust.InvalidCertificateException
import io.github.mgdx.sceau.core.trust.InvalidMasterListException
import io.github.mgdx.sceau.core.trust.MasterList
import io.github.mgdx.sceau.core.trust.TrustStore
import io.github.mgdx.sceau.core.trust.TrustStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Accès au magasin de confiance depuis l'app. Singleton applicatif (voir SceauApplication).
 * Les Master Lists importées sont stockées telles quelles dans `filesDir/trust/<sha256>.ml`, les
 * certificats importés seuls en DER dans `filesDir/trust/<sha256 du DER>.der` (D33) : ce ne sont
 * pas des données personnelles. Toutes les fonctions s'exécutent sur Dispatchers.IO.
 *
 * Deux verrous (audit V22) : [mutex] protège les fichiers et les caches, et n'est jamais tenu
 * pendant la fusion du magasin ; [loadMutex] évite seulement de fusionner deux fois en parallèle.
 * La liste des éléments importés et leur suppression n'attendent donc jamais un chargement en
 * cours, et restent possibles si ce chargement échoue. [loadStore] n'est remplacé que par les tests.
 */
class TrustStoreRepository internal constructor(
    private val context: Context,
    private val loadStore: (masterLists: List<ByteArray>, certificates: List<ByteArray>) -> TrustStore,
) {
    constructor(context: Context) : this(context, { masterLists, certificates -> TrustStores.load(masterLists, certificates) })

    private val mutex = Mutex()

    /** Fusion du magasin en cours ; toujours pris avant [mutex], jamais l'inverse. */
    private val loadMutex = Mutex()

    /** Incrémenté à chaque modification des fichiers : un magasin fusionné entre-temps n'est pas gardé. */
    private var generation = 0L

    /** Magasin fusionné en mémoire ; null tant qu'il n'est pas chargé ou après un import. */
    private var cached: TrustStore? = null

    /** Éléments importés, relus et revérifiés une fois puis gardés jusqu'à la prochaine modification. */
    private var cachedItems: List<ImportedItem>? = null

    private val directory: File get() = File(context.filesDir, IMPORT_DIRECTORY)

    /**
     * Empreintes SHA-256 des Master Lists embarquées dans `:core`, énumérées comme par le
     * chargeur du magasin (`trust/index.txt`), calculées une fois à la première demande.
     */
    private val embeddedMasterListHashes: Set<String> by lazy { readEmbeddedMasterListHashes() }

    /**
     * Magasin fusionné, chargé une fois puis mis en cache en mémoire jusqu'au prochain import.
     * Les fichiers sont lus sous [mutex], la fusion se fait hors de ce verrou.
     */
    suspend fun get(): TrustStore =
        withContext(Dispatchers.IO) {
            loadMutex.withLock {
                var readGeneration = 0L
                var masterLists: List<ByteArray> = emptyList()
                var certificates: List<ByteArray> = emptyList()
                val ready =
                    mutex.withLock {
                        cached ?: run {
                            readGeneration = generation
                            masterLists = read(masterListFiles())
                            certificates = read(certificateFiles())
                            null
                        }
                    }
                ready ?: loadStore(masterLists, certificates).also { store ->
                    mutex.withLock { if (generation == readGeneration) cached = store }
                }
            }
        }

    /**
     * Parse et vérifie une Master List sans l'importer (pour afficher l'empreinte du signataire).
     * Annulable : l'analyse s'interrompt quand l'appelant est annulé, par exemple quand
     * l'utilisateur quitte l'écran (audit V22).
     */
    suspend fun preview(bytes: ByteArray): MasterList =
        withContext(Dispatchers.IO) {
            val caller = coroutineContext
            TrustStores.parseMasterList(bytes) { caller.ensureActive() }
        }

    /**
     * Reconnaît le fichier choisi par l'utilisateur et le vérifie sans l'importer (D33) : d'abord
     * comme certificat seul s'il ne dépasse pas [ImportLimits.MAX_CERTIFICATE_BYTES], puis, s'il
     * est plus gros ou n'est pas un certificat lisible, comme Master List. Lève
     * [InvalidCertificateException] pour un certificat refusé, [ImportLimitException] au-delà des
     * limites, [InvalidMasterListException] pour une Master List invalide,
     * [UnrecognizedFileException] pour un fichier qui n'est ni l'un ni l'autre et
     * [DuplicateImportException] pour un fichier déjà présent dans le magasin.
     */
    suspend fun previewImport(bytes: ByteArray): ImportPreview {
        if (bytes.size <= ImportLimits.MAX_CERTIFICATE_BYTES) {
            try {
                return ImportPreview.Certificate(bytes, previewCertificate(bytes))
            } catch (e: InvalidCertificateException) {
                if (e.code != CODE_UNREADABLE) throw e
            }
        }
        // Doublon puis limites, vérifiés avant l'analyse et la confirmation (D22, D33).
        checkImportAllowed(bytes)
        val masterList =
            try {
                preview(bytes)
            } catch (e: InvalidMasterListException) {
                if (e.code == CODE_NOT_CMS) throw UnrecognizedFileException()
                throw e
            }
        val store = get()
        val newCertificates =
            withContext(Dispatchers.Default) {
                ImportDuplicates.newCertificateCount(
                    masterList.certificates.map { sha256Hex(it.encoded) },
                    store.anchors.mapTo(HashSet()) { it.sha256 },
                )
            }
        return ImportPreview.MasterList(bytes, masterList.info, newCertificates)
    }

    /**
     * Lit et vérifie un certificat seul sans l'importer (D33), limites comprises : lève
     * [InvalidCertificateException], [DuplicateImportException] ou [ImportLimitException].
     */
    suspend fun previewCertificate(bytes: ByteArray): CertificateSummary =
        withContext(Dispatchers.IO) {
            requireCertificateSize(bytes)
            val certificate = TrustStores.parseCertificate(bytes)
            val store = get()
            mutex.withLock {
                requireNewCertificate(certificate.encoded, store)
                requireCertificateAllowed(certificate.encoded, bytes.size.toLong())
            }
            CertificateSummary.of(certificate)
        }

    /**
     * Importe un certificat déjà prévisualisé et confirmé, converti en DER ; invalide le cache.
     * Revérifié ici : l'appelant ne doit pas pouvoir écrire un certificat invalide. Un certificat
     * déjà présent lève [DuplicateImportException] ; au-delà des limites, [ImportLimitException].
     */
    suspend fun importCertificate(bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            requireCertificateSize(bytes)
            val der = TrustStores.parseCertificate(bytes).encoded
            val store = get()
            mutex.withLock {
                requireNewCertificate(der, store)
                if (requireCertificateAllowed(der, bytes.size.toLong()) == ImportDecision.ALLOWED) {
                    write(certificateFileName(der), der)
                }
            }
        }

    /**
     * Éléments importés, Master Lists puis certificats, chacun dans l'ordre d'import. Un fichier
     * devenu illisible reste listé, sans détail, pour pouvoir être supprimé. Ne dépend pas du
     * magasin fusionné : la liste s'obtient pendant son chargement ou après un échec (audit V22).
     */
    suspend fun importedItems(): List<ImportedItem> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                cachedItems ?: readItems().also { cachedItems = it }
            }
        }

    /**
     * Supprime l'élément importé [id] (nom de fichier rendu par [importedItems]) ; invalide le
     * cache. Tout autre identifiant est refusé par [IllegalArgumentException] : il ne doit jamais
     * désigner un fichier hors de `filesDir/trust/`.
     */
    suspend fun removeImported(id: String): Unit =
        withContext(Dispatchers.IO) {
            require(isValidImportId(id)) { "TRUST_BAD_ID" }
            mutex.withLock {
                val file = File(directory, id)
                if (file.exists() && !file.delete()) throw IOException("TRUST_DELETE")
                invalidate()
            }
        }

    /**
     * Vérifie, avant toute analyse, que [bytes] n'est pas déjà dans le magasin (D33) et pourrait
     * être importée sans dépasser les limites de [ImportLimits] ; lève [DuplicateImportException]
     * ou [ImportLimitException] sinon.
     */
    suspend fun checkImportAllowed(bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                requireNewMasterList(bytes)
                requireAllowed(bytes)
            }
        }

    /**
     * Importe une Master List déjà prévisualisée et confirmée ; invalide le cache. Une liste déjà
     * importée ou embarquée lève [DuplicateImportException] ; au-delà des limites de
     * [ImportLimits], [ImportLimitException].
     */
    suspend fun import(bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            // Revérifiée ici : l'appelant ne doit pas pouvoir écrire une liste invalide.
            TrustStores.parseMasterList(bytes)
            mutex.withLock {
                requireNewMasterList(bytes)
                if (requireAllowed(bytes) == ImportDecision.ALLOWED) {
                    write(importFileName(bytes), bytes)
                }
            }
        }

    /** Décision d'import de [bytes] ; lève [ImportLimitException] si l'import est refusé. */
    private fun requireAllowed(bytes: ByteArray): ImportDecision {
        val existing = masterListFiles().map { ImportedFile(it.name, it.length()) }
        val decision = ImportLimits.decide(existing, importFileName(bytes), bytes.size.toLong())
        if (decision == ImportDecision.TOO_MANY || decision == ImportDecision.TOO_LARGE_TOTAL) {
            throw ImportLimitException(decision)
        }
        return decision
    }

    /** Lève [DuplicateImportException] si le certificat [der] est déjà importé ou dans [store] (D33). */
    private fun requireNewCertificate(
        der: ByteArray,
        store: TrustStore,
    ) {
        val imported = certificateFiles().mapTo(HashSet()) { it.name.removeSuffix(CERTIFICATE_EXTENSION) }
        val duplicate = ImportDuplicates.certificate(sha256Hex(der), imported, ImportDuplicates.sourcesBySha256(store.anchors))
        if (duplicate != null) throw DuplicateImportException(duplicate)
    }

    /** Lève [DuplicateImportException] si la Master List [bytes] est déjà importée ou embarquée (D33). */
    private fun requireNewMasterList(bytes: ByteArray) {
        val imported = masterListFiles().mapTo(HashSet()) { it.name.removeSuffix(IMPORT_EXTENSION) }
        val duplicate = ImportDuplicates.masterList(sha256Hex(bytes), imported, embeddedMasterListHashes)
        if (duplicate != null) throw DuplicateImportException(duplicate)
    }

    private fun requireCertificateSize(bytes: ByteArray) {
        if (bytes.size > ImportLimits.MAX_CERTIFICATE_BYTES) throw ImportLimitException(ImportDecision.CERTIFICATE_TOO_LARGE)
    }

    /** Décision d'import du certificat [der] ; lève [ImportLimitException] si l'import est refusé. */
    private fun requireCertificateAllowed(
        der: ByteArray,
        size: Long,
    ): ImportDecision {
        val existing = certificateFiles().map { ImportedFile(it.name, it.length()) }
        val decision = ImportLimits.decideCertificate(existing, certificateFileName(der), size)
        if (decision != ImportDecision.ALLOWED && decision != ImportDecision.ALREADY_PRESENT) {
            throw ImportLimitException(decision)
        }
        return decision
    }

    /** Supprime toutes les Master Lists et tous les certificats importés ; invalide le cache. */
    suspend fun clearImported(): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                directory.listFiles()?.forEach { file ->
                    if (file.isFile && !file.delete()) throw IOException("TRUST_DELETE")
                }
                invalidate()
            }
        }

    private fun invalidate() {
        generation++
        cached = null
        cachedItems = null
    }

    /** Écrit [bytes] sous [name] dans le dossier des imports ; invalide le cache. */
    private fun write(
        name: String,
        bytes: ByteArray,
    ) {
        val dir = directory
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("TRUST_DIR")
        writeAtomically(dir, File(dir, name), bytes)
        invalidate()
    }

    private fun read(files: List<File>): List<ByteArray> = files.sortedBy { it.name }.map { it.readBytes() }

    private fun readItems(): List<ImportedItem> {
        val lists =
            masterListFiles().sortedBy { it.lastModified() }.map { file ->
                val info =
                    try {
                        TrustStores.parseMasterList(file.readBytes()).info
                    } catch (_: InvalidMasterListException) {
                        null
                    }
                ImportedItem.MasterListItem(file.name, info)
            }
        val certificates =
            certificateFiles().sortedBy { it.lastModified() }.map { file ->
                val summary =
                    try {
                        CertificateSummary.of(TrustStores.parseCertificate(file.readBytes()))
                    } catch (_: InvalidCertificateException) {
                        null
                    }
                ImportedItem.CertificateItem(file.name, summary)
            }
        return lists + certificates
    }

    private fun masterListFiles(): List<File> = files(IMPORT_EXTENSION)

    private fun certificateFiles(): List<File> = files(CERTIFICATE_EXTENSION)

    private fun files(extension: String): List<File> =
        directory
            .listFiles { file -> file.isFile && file.name.endsWith(extension) }
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

    private fun readEmbeddedMasterListHashes(): Set<String> {
        val index = readEmbeddedResource(EMBEDDED_INDEX) ?: return emptySet()
        return index
            .toString(Charsets.UTF_8)
            .lineSequence()
            .map { it.trim() }
            .filter { !it.startsWith("#") && it.endsWith(IMPORT_EXTENSION) }
            .mapNotNull { name -> readEmbeddedResource(EMBEDDED_DIRECTORY + name)?.let(::sha256Hex) }
            .toSet()
    }

    private fun readEmbeddedResource(path: String): ByteArray? = TrustStores::class.java.getResourceAsStream(path)?.use { it.readBytes() }

    companion object {
        private const val EMBEDDED_DIRECTORY = "/trust/"
        private const val EMBEDDED_INDEX = EMBEDDED_DIRECTORY + "index.txt"
        private const val IMPORT_DIRECTORY = "trust"
        private const val IMPORT_EXTENSION = ".ml"
        private const val CERTIFICATE_EXTENSION = ".der"
        private const val TEMP_EXTENSION = ".tmp"
        private const val CODE_UNREADABLE = "UNREADABLE"
        private const val CODE_NOT_CMS = "NOT_CMS"

        /** Identifiant d'élément importé : SHA-256 en hexadécimal minuscule suivi de `.ml` ou `.der`. */
        private val IMPORT_ID = Regex("[0-9a-f]{64}\\.(ml|der)")

        /** Nom de fichier d'une Master List importée : SHA-256 du contenu en hexadécimal, suffixé `.ml`. */
        fun importFileName(bytes: ByteArray): String = sha256Hex(bytes) + IMPORT_EXTENSION

        /** Nom de fichier d'un certificat importé : SHA-256 de son DER en hexadécimal, suffixé `.der`. */
        fun certificateFileName(der: ByteArray): String = sha256Hex(der) + CERTIFICATE_EXTENSION

        /**
         * Vrai si [id] est un nom de fichier d'import attendu : ni séparateur, ni `..`, ni autre
         * extension. Seul garde-fou de [removeImported] contre la traversée de chemin.
         */
        fun isValidImportId(id: String): Boolean = IMPORT_ID.matches(id)
    }
}
