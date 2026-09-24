package io.github.mgdx.sceau.core.trust

import java.security.cert.X509Certificate
import java.time.Instant

enum class TrustSource {
    /** Certificats DER de l'ANTS embarqués (passeport et e-ID). */
    ANTS,

    /** Master List embarquée dans `core/src/main/resources/trust/`. */
    EMBEDDED_MASTER_LIST,

    /** Master List importée par l'utilisateur. */
    IMPORTED_MASTER_LIST,
}

/** Un CSCA (ou certificat de lien) connu, avec sa provenance. */
class TrustAnchor(
    val certificate: X509Certificate,
    val source: TrustSource,
) {
    /** Code pays ISO 3166-1 alpha-2 (attribut C du sujet), en majuscules. */
    val country: String get() = TODO("lot A")

    val subject: String get() = certificate.subjectX500Principal.name

    /** Empreinte SHA-256 de l'encodage DER, hexadécimal minuscule sans séparateur. */
    val sha256: String get() = TODO("lot A")

    val notBefore: Instant get() = certificate.notBefore.toInstant()
    val notAfter: Instant get() = certificate.notAfter.toInstant()

    /** Vrai pour un certificat auto-signé (CSCA racine), faux pour un certificat de lien. */
    val isSelfSigned: Boolean get() = TODO("lot A")
}

/** Magasin de confiance fusionné (ANTS + Master List embarquée + imports), en lecture seule. */
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
)

/** Master List rejetée : CMS illisible ou signature invalide. [code] sans donnée personnelle. */
class InvalidMasterListException(
    val code: String,
    cause: Throwable? = null,
) : Exception(code, cause)

object TrustStores {
    /**
     * Parse une Master List CMS (`.ml`, `.der`, `.p7b`) et vérifie sa signature avec le
     * certificat signataire embarqué. Lève [InvalidMasterListException] si invalide.
     */
    fun parseMasterList(bytes: ByteArray): MasterList = TODO("lot A")

    /**
     * Charge le magasin embarqué (ressources `trust/` du classpath) et le fusionne avec
     * les Master Lists importées, fournies en octets bruts par l'app. Une Master List
     * embarquée invalide lève [InvalidMasterListException] ; un import invalide est ignoré.
     */
    fun load(importedMasterLists: List<ByteArray> = emptyList()): TrustStore = TODO("lot A")
}
