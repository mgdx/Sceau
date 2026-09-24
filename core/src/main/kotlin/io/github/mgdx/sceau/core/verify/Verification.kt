package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.ChainInfo
import io.github.mgdx.sceau.core.report.Check
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
     */
    fun verify(
        sod: ByteArray,
        dataGroups: Map<Int, ByteArray>,
        trustStore: TrustStore,
        dateOfIssue: LocalDate?,
        dateOfExpiry: LocalDate?,
        documentCode: String,
    ): PassiveAuthResult = TODO("lot B")
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
    ): Check = TODO("lot B")
}

object Verdicts {
    /** Calcule le verdict global à partir des sept lignes de la liste de contrôle (SPEC §5.3). */
    fun compute(checks: List<Check>): Verdict = TODO("lot B")
}
