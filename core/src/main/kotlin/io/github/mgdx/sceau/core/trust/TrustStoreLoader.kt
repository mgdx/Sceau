package io.github.mgdx.sceau.core.trust

import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * Chargement du magasin embarqué. Lister un répertoire du classpath n'est pas fiable dans un
 * APK : les fichiers sont énumérés par `trust/index.txt` (un nom par ligne, `#` pour commenter).
 * `*.der` : certificat ANTS ; `*.ml` : Master List embarquée.
 */
internal object TrustStoreLoader {
    private const val DIRECTORY = "/trust/"
    private const val INDEX = DIRECTORY + "index.txt"

    /**
     * Empreintes SHA-256 des certificats autorisés à signer le signataire d'une Master List
     * embarquée : CSCA Allemagne et son certificat de lien, publiés par le BSI.
     */
    val EMBEDDED_MASTER_LIST_ANCHORS: Set<String> =
        setOf(
            "2084aed7a991b3158e63ad750d3dc38bc6c9dcf5958f28d1162f49884e91ada8",
            "5f28cb692fb346ba2d23674c8778f2810c24a35869e5b517de3abd67d5db20bd",
        )

    fun load(importedMasterLists: List<ByteArray>): TrustStore {
        val ants = mutableListOf<X509Certificate>()
        var embedded: MasterList? = null
        for (name in embeddedFileNames()) {
            val bytes = readResource(DIRECTORY + name)
            when {
                name.endsWith(".der") -> {
                    ants += TrustCrypto.toX509(bytes)
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
        return merge(ants, embedded, imported)
    }

    /** Fusion par ordre de priorité : ANTS, puis Master List embarquée, puis imports. */
    fun merge(
        ants: List<X509Certificate>,
        embedded: MasterList?,
        imported: List<MasterList>,
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
        embedded?.certificates?.forEach { add(it, TrustSource.EMBEDDED_MASTER_LIST) }
        imported.forEach { list -> list.certificates.forEach { add(it, TrustSource.IMPORTED_MASTER_LIST) } }
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

/** Magasin immuable, donc sûr entre threads, indexé par sujet canonique, SKI et empreinte. */
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
