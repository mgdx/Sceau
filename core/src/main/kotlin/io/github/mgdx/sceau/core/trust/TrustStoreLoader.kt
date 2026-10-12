package io.github.mgdx.sceau.core.trust

import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * Loading of the embedded store. Listing a classpath directory is not reliable in an
 * APK: the files are enumerated by `trust/index.txt` (one name per line, `#` for comments).
 * `ants-*.der`: ANTS certificate; other `*.der`: certificate published by its own State (D26);
 * `*.ml`: embedded Master List.
 */
internal object TrustStoreLoader {
    private const val DIRECTORY = "/trust/"
    private const val INDEX = DIRECTORY + "index.txt"
    private const val ANTS_PREFIX = "ants-"

    /** Embedded sources, which can anchor the signer of an imported Master List (audit V34). */
    val EMBEDDED_SOURCES: Set<TrustSource> = setOf(TrustSource.ANTS, TrustSource.NATIONAL, TrustSource.EMBEDDED_MASTER_LIST)

    /**
     * SHA-256 fingerprints of the certificates allowed to sign the signer of an embedded
     * Master List: the German CSCA and its link certificate, published by the BSI.
     */
    val EMBEDDED_MASTER_LIST_ANCHORS: Set<String> =
        setOf(
            "2084aed7a991b3158e63ad750d3dc38bc6c9dcf5958f28d1162f49884e91ada8",
            "5f28cb692fb346ba2d23674c8778f2810c24a35869e5b517de3abd67d5db20bd",
        )

    fun load(
        importedMasterLists: List<ByteArray>,
        importedCertificates: List<ByteArray> = emptyList(),
    ): TrustStore {
        val ants = mutableListOf<X509Certificate>()
        val national = mutableListOf<X509Certificate>()
        var embedded: MasterList? = null
        for (name in embeddedFileNames()) {
            val bytes = readResource(DIRECTORY + name)
            when {
                name.endsWith(".der") && name.startsWith(ANTS_PREFIX) -> {
                    ants += TrustCrypto.toX509(bytes)
                }

                name.endsWith(".der") -> {
                    national += TrustCrypto.toX509(bytes)
                }

                name.endsWith(".ml") -> {
                    check(embedded == null) { "TRUST_MULTIPLE_MASTER_LISTS" }
                    val parsed = MasterListParser.parse(bytes)
                    MasterListParser.requireAnchoredSigner(parsed, EMBEDDED_MASTER_LIST_ANCHORS)
                    embedded = parsed.masterList
                }

                else -> {
                    error("TRUST_UNKNOWN_FILE_TYPE")
                }
            }
        }
        val imported =
            importedMasterLists.mapNotNull { bytes ->
                try {
                    TrustStores.parseMasterList(bytes)
                } catch (ignored: InvalidMasterListException) {
                    null
                }
            }
        val certificates = TrustStores.parseCertificates(importedCertificates)
        // Anchoring of each imported list's signer (audit V34): only embedded certificates and
        // those imported on their own count, never those of an imported list.
        val known = ants + national + embedded?.certificates.orEmpty() + certificates
        val (anchored, unanchored) = imported.partition { MasterListParser.isSignerAnchored(it.signerCertificate, known) }
        return merge(ants, national, embedded, anchored, certificates, unanchored)
    }

    /**
     * Merge in priority order: ANTS, national publications, embedded Master List,
     * anchored imported Master Lists, imported certificates, unanchored imported Master Lists.
     * A certificate already present keeps its source.
     */
    fun merge(
        ants: List<X509Certificate>,
        national: List<X509Certificate>,
        embedded: MasterList?,
        imported: List<MasterList>,
        importedCertificates: List<X509Certificate> = emptyList(),
        unanchored: List<MasterList> = emptyList(),
    ): TrustStore {
        val bySha256 = LinkedHashMap<String, TrustAnchor>()

        fun add(
            certificate: X509Certificate,
            source: TrustSource,
        ) {
            val anchor = TrustAnchor(certificate, source)
            bySha256.putIfAbsent(anchor.sha256, anchor)
        }
        ants.forEach { add(it, TrustSource.ANTS) }
        national.forEach { add(it, TrustSource.NATIONAL) }
        embedded?.certificates?.forEach { add(it, TrustSource.EMBEDDED_MASTER_LIST) }
        imported.forEach { list -> list.certificates.forEach { add(it, TrustSource.IMPORTED_MASTER_LIST) } }
        importedCertificates.forEach { add(it, TrustSource.IMPORTED_CERTIFICATE) }
        unanchored.forEach { list -> list.certificates.forEach { add(it, TrustSource.IMPORTED_MASTER_LIST_UNANCHORED) } }
        return IndexedTrustStore(bySha256.values.toList(), embedded?.info)
    }

    private fun embeddedFileNames(): List<String> =
        readResource(INDEX)
            .toString(Charsets.UTF_8)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

    private fun readResource(path: String): ByteArray {
        val stream = TrustStoreLoader::class.java.getResourceAsStream(path) ?: error("TRUST_RESOURCE_MISSING")
        return stream.use { it.readBytes() }
    }
}

/** Immutable store, hence thread-safe, indexed by canonical subject, SKI and fingerprint. */
internal class IndexedTrustStore(
    override val anchors: List<TrustAnchor>,
    override val embeddedMasterList: MasterListInfo?,
) : TrustStore {
    private val bySubject: Map<String, List<TrustAnchor>> =
        anchors.groupBy { canonical(it.certificate.subjectX500Principal) }

    private val bySubjectKeyId: Map<String, List<TrustAnchor>> =
        anchors
            .mapNotNull { anchor -> TrustCrypto.subjectKeyId(anchor.certificate)?.let { TrustCrypto.hex(it) to anchor } }
            .groupBy({ it.first }, { it.second })

    private val bySha256: Map<String, TrustAnchor> = anchors.associateBy { it.sha256 }

    override fun findBySubject(issuer: X500Principal): List<TrustAnchor> = bySubject[canonical(issuer)].orEmpty()

    override fun findBySubjectKeyId(keyIdentifier: ByteArray): List<TrustAnchor> = bySubjectKeyId[TrustCrypto.hex(keyIdentifier)].orEmpty()

    fun findBySha256(sha256: String): TrustAnchor? = bySha256[sha256.lowercase()]

    private companion object {
        fun canonical(principal: X500Principal): String = principal.getName(X500Principal.CANONICAL)
    }
}
