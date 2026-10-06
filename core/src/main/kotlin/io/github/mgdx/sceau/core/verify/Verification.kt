package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.ChainInfo
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.trust.TrustStore
import java.security.PublicKey
import java.time.LocalDate

/** Résultat de la Passive Authentication : quatre lignes de la liste de contrôle. */
class PassiveAuthResult(
    /** [io.github.mgdx.sceau.core.report.CheckId.SOD_SIGNATURE] */
    val sodSignature: Check,
    /** [io.github.mgdx.sceau.core.report.CheckId.CERTIFICATE_CHAIN] */
    val certificateChain: Check,
    /** [io.github.mgdx.sceau.core.report.CheckId.DS_VALIDITY] */
    val dsValidity: Check,
    /** [io.github.mgdx.sceau.core.report.CheckId.DG_HASHES] */
    val dataGroupHashes: Check,
    val chain: ChainInfo?,
    /**
     * DG dont l'écart d'empreinte est toléré au titre d'une anomalie connue de l'émetteur
     * (`CheckDetail.DataGroupHashes.deviations`, décision D34) : 11 ou 12 seulement. Leurs données
     * ne sont pas dignes de foi : elles doivent être retirées du rapport et remises à zéro.
     */
    val discardedDataGroups: Set<Int> = emptySet(),
)

object PassiveAuthentication {
    /**
     * SPEC §6.1 étape 5. N'accède jamais à la puce.
     *
     * @param sod octets bruts de EF.SOD (tag 0x77 compris)
     * @param dataGroups octets bruts de chaque DG lu, clé = numéro de DG
     * @param dateOfIssue date de délivrance lue dans DG12, si disponible
     * @param dateOfExpiry date d'expiration lue dans DG1, pour l'estimation
     * @param documentCode code de document DG1 ("P", "ID"…), pour choisir la durée usuelle
     * @param issuingState État émetteur lu dans DG1 (code ICAO à trois lettres, "FRA", "D<<"…) :
     *   il doit correspondre au pays du CSCA et du DS (voir [IcaoCountries]), sinon la chaîne
     *   est en échec avec `CheckDetail.CountryMismatch`. Null : seuls le CSCA et le DS sont comparés.
     * @param documentNumber numéro du document lu dans DG1, pour reconnaître une anomalie connue
     *   de l'émetteur (décision D34) ; n'est pris en compte que si l'empreinte de DG1 est vérifiée.
     */
    fun verify(
        sod: ByteArray,
        dataGroups: Map<Int, ByteArray>,
        trustStore: TrustStore,
        dateOfIssue: LocalDate?,
        dateOfExpiry: LocalDate?,
        documentCode: String,
        issuingState: String? = null,
        documentNumber: String? = null,
    ): PassiveAuthResult =
        PassiveAuthenticator(trustStore).verify(sod, dataGroups, dateOfIssue, dateOfExpiry, documentCode, issuingState, documentNumber)
}

object ActiveAuthentication {
    /**
     * Vérifie la réponse de la puce au challenge AA avec la clé publique de DG15
     * (RSA ISO/IEC 9796-2 schéma 1, ou ECDSA avec l'algorithme de hachage annoncé dans DG14).
     *
     * @param digestAlgorithm nom BouncyCastle de l'algorithme de hachage ECDSA ("SHA-256"…) ; ignoré pour RSA
     * @return Check ACTIVE_AUTHENTICATION : OK, FAILED ou UNSUPPORTED_ALGORITHM
     */
    fun verifyResponse(
        publicKey: PublicKey,
        digestAlgorithm: String?,
        challenge: ByteArray,
        response: ByteArray,
    ): Check = ActiveAuthenticator.verify(publicKey, digestAlgorithm, challenge, response)
}

object Verdicts {
    /**
     * Calcule le verdict global à partir des sept lignes de la liste de contrôle (SPEC §5.3).
     *
     * Table de décision, appliquée dans l'ordre (la première règle qui s'applique gagne) :
     *
     * | # | Condition                                                                 | Verdict                           |
     * |---|---------------------------------------------------------------------------|-----------------------------------|
     * | 1 | une ligne quelconque FAILED ou UNSUPPORTED_ALGORITHM                      | [Verdict.FAILED]                  |
     * | 2 | CERTIFICATE_CHAIN NOT_AVAILABLE                                           | [Verdict.UNKNOWN_ISSUER]          |
     * | 3 | PA complète (a), et CHIP_AUTHENTICATION OK ou ACTIVE_AUTHENTICATION OK       | [Verdict.AUTHENTIC]               |
     * | 4 | PA complète (a), CHIP_AUTHENTICATION et ACTIVE_AUTHENTICATION NOT_AVAILABLE  | [Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED] |
     * | 5 | tout autre cas (ligne manquante, SOD_SIGNATURE NOT_AVAILABLE…)            | [Verdict.FAILED], par prudence    |
     *
     * (a) PA complète : SECURE_CHANNEL, SOD_SIGNATURE, CERTIFICATE_CHAIN et DG_HASHES OK,
     * DS_VALIDITY OK ou NOT_AVAILABLE (date de délivrance inconnue ou seulement estimée).
     *
     * Une ligne CA ou AA NOT_AVAILABLE aux côtés de l'autre OK donne AUTHENTIC (règle 3) :
     * un seul des deux challenges suffit.
     *
     * CA (resp. AA) ne vaut NOT_AVAILABLE que si DG14 (resp. DG15) est absent du SOD, ou si DG14
     * n'annonce aucune clé de Chip Authentication. Un DG
     * annoncé par le SOD mais non fourni par la puce met DG_HASHES (détail
     * `DataGroupHashes.missing`) et la ligne CA ou AA à FAILED : la règle 4 ne s'applique
     * donc qu'à un document dont le SOD, signé, ne contient ni DG14 ni DG15 (audit V1). Un DG14
     * signé qui annonce une clé de CA (OID id-PK-*) que l'app ne sait pas exploiter met la ligne CA
     * à UNSUPPORTED_ALGORITHM, jamais à NOT_AVAILABLE (audit V19).
     */
    fun compute(checks: List<Check>): Verdict {
        if (checks.any { it.status == CheckStatus.FAILED || it.status == CheckStatus.UNSUPPORTED_ALGORITHM }) {
            return Verdict.FAILED
        }
        val status = checks.associate { it.id to it.status }
        if (status[CheckId.CERTIFICATE_CHAIN] == CheckStatus.NOT_AVAILABLE) return Verdict.UNKNOWN_ISSUER

        val passiveAuthOk =
            listOf(CheckId.SECURE_CHANNEL, CheckId.SOD_SIGNATURE, CheckId.CERTIFICATE_CHAIN, CheckId.DG_HASHES)
                .all { status[it] == CheckStatus.OK } &&
                status[CheckId.DS_VALIDITY] in setOf(CheckStatus.OK, CheckStatus.NOT_AVAILABLE)
        if (!passiveAuthOk) return Verdict.FAILED

        val ca = status[CheckId.CHIP_AUTHENTICATION]
        val aa = status[CheckId.ACTIVE_AUTHENTICATION]
        return when {
            ca == CheckStatus.OK || aa == CheckStatus.OK -> Verdict.AUTHENTIC
            ca == CheckStatus.NOT_AVAILABLE && aa == CheckStatus.NOT_AVAILABLE -> Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED
            else -> Verdict.FAILED
        }
    }
}
