package io.github.mgdx.sceau.core.report

import io.github.mgdx.sceau.core.model.DocumentData
import io.github.mgdx.sceau.core.trust.TrustSource
import java.time.Instant
import java.time.LocalDate

/** Verdict global (SPEC §5.3). */
enum class Verdict {
    /** PA réussie et CA ou AA réussie. */
    AUTHENTIC,

    /** PA réussie, ni DG14 ni DG15 : la puce pourrait être un clone. */
    SIGNATURE_VALID_CHIP_UNVERIFIED,

    /** La chaîne du SOD ne remonte à aucun CSCA du magasin. */
    UNKNOWN_ISSUER,

    /** Signature invalide, empreinte non conforme, challenge raté, algorithme non pris en charge. */
    FAILED,
}

/** Lignes de la liste de contrôle, dans l'ordre d'affichage (SPEC §5.3). */
enum class CheckId {
    SECURE_CHANNEL,
    SOD_SIGNATURE,
    CERTIFICATE_CHAIN,
    DS_VALIDITY,
    DG_HASHES,
    CHIP_AUTHENTICATION,
    ACTIVE_AUTHENTICATION,
}

enum class CheckStatus {
    OK,
    FAILED,

    /** Étape non disponible (DG14/DG15 absent, étape non atteinte) : distincte d'un échec. */
    NOT_AVAILABLE,

    /** Algorithme inconnu de BouncyCastle : `UnsupportedAlgorithm` (SPEC §6.2). */
    UNSUPPORTED_ALGORITHM,
}

enum class ChannelProtocol { PACE, BAC }

enum class IssuanceDateSource {
    /** Date de délivrance lue dans DG12. */
    DG12,

    /** Attribut signingTime du SOD. */
    SOD_SIGNING_TIME,

    /** Expiration moins la durée de validité usuelle : affichée « estimée ». */
    ESTIMATED,
}

/** Détail structuré d'une vérification : l'app le met en forme avec ses propres chaînes. */
sealed class CheckDetail {
    data object None : CheckDetail()

    data class SecureChannel(
        val protocol: ChannelProtocol,
    ) : CheckDetail()

    /** [algorithm] : nom d'algorithme déclaré (ex. "SHA256withECDSA", ou OID si inconnu). */
    data class Signature(
        val algorithm: String,
    ) : CheckDetail()

    data class Chain(
        val chain: ChainInfo,
    ) : CheckDetail()

    /** Chaîne introuvable : le DS n'est ni dans le SOD ni dans le magasin (`DsCertificateMissing`). */
    data object DsCertificateMissing : CheckDetail()

    data class DsValidity(
        val notBefore: Instant,
        val notAfter: Instant,
        val issuanceDate: LocalDate?,
        val source: IssuanceDateSource?,
    ) : CheckDetail()

    /** Numéros des DG contrôlés et de ceux dont l'empreinte diffère du SOD. */
    data class DataGroupHashes(
        val digestAlgorithm: String,
        val checked: List<Int>,
        val mismatched: List<Int>,
    ) : CheckDetail()

    data class UnsupportedAlgorithm(
        val algorithm: String,
    ) : CheckDetail()

    /** Erreur technique pendant l'étape, sans donnée personnelle. */
    data class Error(
        val code: String,
    ) : CheckDetail()
}

data class Check(
    val id: CheckId,
    val status: CheckStatus,
    val detail: CheckDetail = CheckDetail.None,
)

/** Métadonnées de la chaîne de certification DS → (liens) → CSCA. Aucune donnée personnelle. */
data class ChainInfo(
    val dsSubject: String,
    val dsSerialNumber: String,
    val dsNotBefore: Instant,
    val dsNotAfter: Instant,
    val signatureAlgorithm: String,
    /** Null si aucun CSCA du magasin ne correspond (verdict UNKNOWN_ISSUER). */
    val cscaSubject: String?,
    /** Code pays ISO 3166-1 alpha-2 du CSCA, en majuscules. */
    val cscaCountry: String?,
    val cscaSha256: String?,
    val cscaSource: TrustSource?,
    /** Sujets des certificats de lien traversés, du plus proche du DS au plus proche du CSCA. */
    val linkCertificates: List<String>,
)

/**
 * Rapport immuable produit par `readAndVerify`. Contient les données personnelles lues
 * dans [document] : il n'est jamais persisté, et [wipe] doit être appelé à la sortie
 * de l'écran de résultat et à la mise en arrière-plan.
 */
class VerificationReport(
    val verdict: Verdict,
    /** Exactement une entrée par [CheckId], dans l'ordre de l'énumération. */
    val checks: List<Check>,
    val chain: ChainInfo?,
    val document: DocumentData,
) {
    fun check(id: CheckId): Check = checks.first { it.id == id }

    /** Remet à zéro tous les tableaux d'octets des données lues. */
    fun wipe() = document.wipe()

    override fun toString(): String = "VerificationReport(verdict=$verdict)"
}
