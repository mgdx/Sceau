package io.github.mgdx.sceau.core.trust

import java.security.cert.X509Certificate
import java.time.Instant

enum class TrustSource {
    /** Certificats DER de l'ANTS embarqués (passeport et e-ID). */
    ANTS,

    /**
     * Certificats DER embarqués, publiés par un autre État que la France pour ses propres
     * documents (CSCA et certificats de lien absents de la Master List embarquée, D26).
     */
    NATIONAL,

    /** Master List embarquée dans `core/src/main/resources/trust/`. */
    EMBEDDED_MASTER_LIST,

    /**
     * Master List importée par l'utilisateur, dont le signataire est émis par un CSCA déjà connu
     * (embarqué ou certificat importé seul, audit V34).
     */
    IMPORTED_MASTER_LIST,

    /**
     * Certificat (CSCA auto-signé ou certificat de lien) importé seul par l'utilisateur : aucune
     * signature d'État ne le garantit, c'est l'utilisateur qui lui accorde sa confiance (D33).
     */
    IMPORTED_CERTIFICATE,

    /**
     * Master List importée dont le signataire n'est émis par aucun CSCA déjà connu (embarqué ou
     * certificat importé seul) : sa signature ne prouve rien sur son origine, c'est l'utilisateur
     * qui lui accorde sa confiance, après un avertissement à l'import (audit V34, D44). Dernière
     * en priorité : un certificat déjà présent par une autre source garde cette source.
     */
    IMPORTED_MASTER_LIST_UNANCHORED,
}

/** Un CSCA (ou certificat de lien) connu, avec sa provenance. */
class TrustAnchor(
    val certificate: X509Certificate,
    val source: TrustSource,
) {
    /** Code pays ISO 3166-1 alpha-2 (attribut C du sujet), en majuscules. */
    val country: String by lazy { TrustCrypto.country(certificate) }

    val subject: String get() = certificate.subjectX500Principal.name

    /** Empreinte SHA-256 de l'encodage DER, hexadécimal minuscule sans séparateur. */
    val sha256: String by lazy { TrustCrypto.sha256Hex(certificate.encoded) }

    val notBefore: Instant get() = certificate.notBefore.toInstant()
    val notAfter: Instant get() = certificate.notAfter.toInstant()

    /** Vrai pour un certificat auto-signé (CSCA racine), faux pour un certificat de lien. */
    val isSelfSigned: Boolean by lazy { TrustCrypto.isSelfSigned(certificate) }
}

/** Magasin de confiance fusionné (ANTS, publications nationales, Master List embarquée, imports), en lecture seule. */
interface TrustStore {
    val anchors: List<TrustAnchor>

    /** Métadonnées de la Master List embarquée, pour l'écran « À propos ». */
    val embeddedMasterList: MasterListInfo?

    /** Certificats dont le sujet est égal à [issuer] (nom X.500 comparé sous forme canonique). */
    fun findBySubject(issuer: javax.security.auth.x500.X500Principal): List<TrustAnchor>

    /** Certificats dont le Subject Key Identifier vaut [keyIdentifier]. */
    fun findBySubjectKeyId(keyIdentifier: ByteArray): List<TrustAnchor>
}

class MasterListInfo(
    val signingTime: Instant?,
    val signerSubject: String,
    /** Empreinte SHA-256 du certificat signataire, hexadécimal minuscule. */
    val signerSha256: String,
    val certificateCount: Int,
)

/** Contenu d'une Master List dont la signature CMS a été vérifiée. */
class MasterList(
    val info: MasterListInfo,
    val certificates: List<X509Certificate>,
    /** Certificat signataire, pris dans le bloc `certificates` du CMS. */
    val signerCertificate: X509Certificate,
)

/** Master List rejetée : CMS illisible ou signature invalide. [code] sans donnée personnelle. */
class InvalidMasterListException(
    val code: String,
    cause: Throwable? = null,
) : Exception(code, cause)

/**
 * Certificat importé rejeté. [code] stable, sans donnée personnelle : `UNREADABLE` (pas un
 * certificat X.509 unique, DER ou PEM, ou clé publique illisible), `NOT_CA` (ne peut pas signer
 * de certificats), `BAD_SIGNATURE` (auto-signature invalide).
 */
class InvalidCertificateException(
    val code: String,
    cause: Throwable? = null,
) : Exception(code, cause)

object TrustStores {
    /**
     * Nombre maximal de certificats d'une Master List (audit V22, décision D22) : plus de trois
     * fois la plus grosse Master List réelle connue (Suède, 646 certificats ; BSI embarquée : 608).
     * Au-delà, la liste est refusée par `TOO_MANY_CERTIFICATES`.
     */
    const val MAX_MASTER_LIST_CERTIFICATES = 2000

    /**
     * Parse une Master List CMS (`.ml`, `.der`, `.p7b`) et vérifie sa signature avec le
     * certificat signataire embarqué. Lève [InvalidMasterListException] si invalide.
     * [checkpoint] est appelé entre les étapes coûteuses ; une `CancellationException` qu'il lève
     * abandonne l'analyse et se propage telle quelle.
     */
    fun parseMasterList(
        bytes: ByteArray,
        checkpoint: () -> Unit = {},
    ): MasterList = MasterListParser.parse(bytes, checkpoint).masterList

    /**
     * Vrai si le signataire de [masterList] est émis par un CSCA déjà connu (audit V34) : un
     * certificat embarqué de [store] (ANTS, publication nationale, Master List embarquée) ou l'un
     * des certificats importés seuls [importedCertificates] (octets bruts, les illisibles sont
     * ignorés). Les certificats d'une autre Master List importée ne comptent pas. Même règle que
     * celle qui attribue au chargement la source [TrustSource.IMPORTED_MASTER_LIST] ou
     * [TrustSource.IMPORTED_MASTER_LIST_UNANCHORED].
     */
    fun isMasterListAnchored(
        masterList: MasterList,
        store: TrustStore,
        importedCertificates: List<ByteArray> = emptyList(),
    ): Boolean {
        val embedded = store.anchors.filter { it.source in TrustStoreLoader.EMBEDDED_SOURCES }.map { it.certificate }
        return MasterListParser.isSignerAnchored(masterList.signerCertificate, embedded + parseCertificates(importedCertificates))
    }

    /** Certificats importés seuls, lus comme au chargement ; les illisibles sont ignorés. */
    internal fun parseCertificates(certificates: List<ByteArray>): List<X509Certificate> =
        certificates.mapNotNull { bytes ->
            try {
                CertificateParser.parse(bytes)
            } catch (ignored: InvalidCertificateException) {
                null
            }
        }

    /**
     * Lit un certificat CSCA ou de lien importé seul, en DER ou en PEM (un seul certificat), et
     * vérifie qu'il peut signer des certificats et, s'il se dit auto-signé, son auto-signature.
     * Lève [InvalidCertificateException] sinon.
     */
    fun parseCertificate(bytes: ByteArray): X509Certificate = CertificateParser.parse(bytes)

    /**
     * Charge le magasin embarqué (ressources `trust/` du classpath) et le fusionne avec
     * les Master Lists et les certificats importés, fournis en octets bruts par l'app. Une Master
     * List embarquée invalide lève [InvalidMasterListException] ; un import invalide est ignoré.
     */
    fun load(
        importedMasterLists: List<ByteArray> = emptyList(),
        importedCertificates: List<ByteArray> = emptyList(),
    ): TrustStore = TrustStoreLoader.load(importedMasterLists, importedCertificates)
}
