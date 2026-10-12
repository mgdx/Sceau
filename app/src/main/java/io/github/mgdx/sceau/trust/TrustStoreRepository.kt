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
 * Access to the trust store from the app. Application-wide singleton (see SceauApplication).
 * Imported Master Lists are stored as is in `filesDir/trust/<sha256>.ml`, standalone imported
 * certificates in DER in `filesDir/trust/<sha256 of the DER>.der` (D33): these are not personal
 * data. All functions run on Dispatchers.IO.
 *
 * Two locks (audit V22): [mutex] protects the files and the caches, and is never held while the
 * store is being merged; [loadMutex] only prevents merging twice in parallel. Listing and removing
 * imported items therefore never wait for an ongoing load, and remain possible if that load
 * fails. [loadStore] is replaced only by the tests.
 */
class TrustStoreRepository internal constructor(
    private val context: Context,
    private val loadStore: (masterLists: List<ByteArray>, certificates: List<ByteArray>) -> TrustStore,
) {
    constructor(context: Context) : this(context, { masterLists, certificates -> TrustStores.load(masterLists, certificates) })

    private val mutex = Mutex()

    /** Store merge in progress; always acquired before [mutex], never the other way around. */
    private val loadMutex = Mutex()

    /** Incremented on every file modification: a store merged in the meantime is not kept. */
    private var generation = 0L

    /** Merged store in memory; null until it is loaded or after an import. */
    private var cached: TrustStore? = null

    /** Imported items, reread and reverified once, then kept until the next modification. */
    private var cachedItems: List<ImportedItem>? = null

    private val directory: File get() = File(context.filesDir, IMPORT_DIRECTORY)

    /**
     * SHA-256 fingerprints of the Master Lists embedded in `:core`, enumerated as the store
     * loader does (`trust/index.txt`), computed once on first request.
     */
    private val embeddedMasterListHashes: Set<String> by lazy { readEmbeddedMasterListHashes() }

    /**
     * Merged store, loaded once and then cached in memory until the next import.
     * The files are read under [mutex]; the merge happens outside that lock.
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
     * Parses and verifies a Master List without importing it (to display the signer's fingerprint).
     * Cancellable: parsing stops when the caller is cancelled, for example when the user leaves
     * the screen (audit V22).
     */
    suspend fun preview(bytes: ByteArray): MasterList =
        withContext(Dispatchers.IO) {
            val caller = coroutineContext
            TrustStores.parseMasterList(bytes) { caller.ensureActive() }
        }

    /**
     * Recognizes the file chosen by the user and verifies it without importing it (D33): first as
     * a standalone certificate if it does not exceed [ImportLimits.MAX_CERTIFICATE_BYTES], then,
     * if it is larger or is not a readable certificate, as a Master List. Throws
     * [InvalidCertificateException] for a rejected certificate, [ImportLimitException] beyond the
     * limits, [InvalidMasterListException] for an invalid Master List,
     * [UnrecognizedFileException] for a file that is neither, and
     * [DuplicateImportException] for a file already present in the store.
     */
    suspend fun previewImport(bytes: ByteArray): ImportPreview {
        if (bytes.size <= ImportLimits.MAX_CERTIFICATE_BYTES) {
            try {
                return ImportPreview.Certificate(bytes, previewCertificate(bytes))
            } catch (e: InvalidCertificateException) {
                if (e.code != CODE_UNREADABLE) throw e
            }
        }
        // Duplicate check, then limits, both verified before parsing and confirmation (D22, D33).
        checkImportAllowed(bytes)
        val masterList =
            try {
                preview(bytes)
            } catch (e: InvalidMasterListException) {
                if (e.code == CODE_NOT_CMS) throw UnrecognizedFileException()
                throw e
            }
        val store = get()
        // Same certificates as those that will anchor the list at load time (audit V34).
        val importedCertificates = withContext(Dispatchers.IO) { mutex.withLock { read(certificateFiles()) } }
        return withContext(Dispatchers.Default) {
            val newCertificates =
                ImportDuplicates.newCertificateCount(
                    masterList.certificates.map { sha256Hex(it.encoded) },
                    store.anchors.mapTo(HashSet()) { it.sha256 },
                )
            val anchored = TrustStores.isMasterListAnchored(masterList, store, importedCertificates)
            ImportPreview.MasterList(bytes, masterList.info, newCertificates, anchored)
        }
    }

    /**
     * Reads and verifies a standalone certificate without importing it (D33), limits included: throws
     * [InvalidCertificateException], [DuplicateImportException] or [ImportLimitException].
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
     * Imports an already previewed and confirmed certificate, converted to DER; invalidates the cache.
     * Reverified here: the caller must not be able to write an invalid certificate. A certificate
     * already present throws [DuplicateImportException]; beyond the limits, [ImportLimitException].
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
     * Imported items, Master Lists then certificates, each in import order. A file that has become
     * unreadable stays listed, without details, so that it can be removed. Does not depend on the
     * merged store: the list can be obtained while it is loading or after a failure (audit V22).
     */
    suspend fun importedItems(): List<ImportedItem> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                cachedItems ?: readItems().also { cachedItems = it }
            }
        }

    /**
     * Removes the imported item [id] (file name returned by [importedItems]); invalidates the
     * cache. Any other identifier is rejected with [IllegalArgumentException]: it must never
     * designate a file outside `filesDir/trust/`.
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
     * Checks, before any parsing, that [bytes] is not already in the store (D33) and could be
     * imported without exceeding the limits of [ImportLimits]; otherwise throws
     * [DuplicateImportException] or [ImportLimitException].
     */
    suspend fun checkImportAllowed(bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                requireNewMasterList(bytes)
                requireAllowed(bytes)
            }
        }

    /**
     * Imports an already previewed and confirmed Master List; invalidates the cache. A list already
     * imported or embedded throws [DuplicateImportException]; beyond the limits of
     * [ImportLimits], [ImportLimitException].
     */
    suspend fun import(bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            // Reverified here: the caller must not be able to write an invalid list.
            TrustStores.parseMasterList(bytes)
            mutex.withLock {
                requireNewMasterList(bytes)
                if (requireAllowed(bytes) == ImportDecision.ALLOWED) {
                    write(importFileName(bytes), bytes)
                }
            }
        }

    /** Import decision for [bytes]; throws [ImportLimitException] if the import is refused. */
    private fun requireAllowed(bytes: ByteArray): ImportDecision {
        val existing = masterListFiles().map { ImportedFile(it.name, it.length()) }
        val decision = ImportLimits.decide(existing, importFileName(bytes), bytes.size.toLong())
        if (decision == ImportDecision.TOO_MANY || decision == ImportDecision.TOO_LARGE_TOTAL) {
            throw ImportLimitException(decision)
        }
        return decision
    }

    /** Throws [DuplicateImportException] if the certificate [der] is already imported or in [store] (D33). */
    private fun requireNewCertificate(
        der: ByteArray,
        store: TrustStore,
    ) {
        val imported = certificateFiles().mapTo(HashSet()) { it.name.removeSuffix(CERTIFICATE_EXTENSION) }
        val duplicate = ImportDuplicates.certificate(sha256Hex(der), imported, ImportDuplicates.sourcesBySha256(store.anchors))
        if (duplicate != null) throw DuplicateImportException(duplicate)
    }

    /** Throws [DuplicateImportException] if the Master List [bytes] is already imported or embedded (D33). */
    private fun requireNewMasterList(bytes: ByteArray) {
        val imported = masterListFiles().mapTo(HashSet()) { it.name.removeSuffix(IMPORT_EXTENSION) }
        val duplicate = ImportDuplicates.masterList(sha256Hex(bytes), imported, embeddedMasterListHashes)
        if (duplicate != null) throw DuplicateImportException(duplicate)
    }

    private fun requireCertificateSize(bytes: ByteArray) {
        if (bytes.size > ImportLimits.MAX_CERTIFICATE_BYTES) throw ImportLimitException(ImportDecision.CERTIFICATE_TOO_LARGE)
    }

    /** Import decision for the certificate [der]; throws [ImportLimitException] if the import is refused. */
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

    /** Removes all imported Master Lists and certificates; invalidates the cache. */
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

    /** Writes [bytes] as [name] in the imports directory; invalidates the cache. */
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

    /** Writes to a temporary file in the same directory, syncs, then renames. */
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

        /** Imported item identifier: SHA-256 in lowercase hexadecimal followed by `.ml` or `.der`. */
        private val IMPORT_ID = Regex("[0-9a-f]{64}\\.(ml|der)")

        /** File name of an imported Master List: SHA-256 of the content in hexadecimal, suffixed `.ml`. */
        fun importFileName(bytes: ByteArray): String = sha256Hex(bytes) + IMPORT_EXTENSION

        /** File name of an imported certificate: SHA-256 of its DER in hexadecimal, suffixed `.der`. */
        fun certificateFileName(der: ByteArray): String = sha256Hex(der) + CERTIFICATE_EXTENSION

        /**
         * True if [id] is an expected import file name: no separator, no `..`, no other
         * extension. Sole safeguard of [removeImported] against path traversal.
         */
        fun isValidImportId(id: String): Boolean = IMPORT_ID.matches(id)
    }
}
