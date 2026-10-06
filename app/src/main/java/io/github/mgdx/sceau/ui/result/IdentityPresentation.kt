package io.github.mgdx.sceau.ui.result

import io.github.mgdx.sceau.core.model.Dg1Data
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.VerificationReport

/** Présentation de l'identité sur l'écran Résultat. */
internal object IdentityPresentation {
    /**
     * Carte eID allemande pour citoyens de l'Union (eID-UB, BSI TR-03127, décision D36) dont DG1
     * est authentifié : signature du SOD valide, chaîne de certificats du SOD valide jusqu'à un
     * CSCA du magasin de confiance, empreinte de DG1 conforme, code de document « UB » et
     * État « D ». Sa puce ne porte aucune donnée d'identité (MRZ de remplacement faite
     * de « < », DG2 réduit au logo eID) : l'écran le dit, au lieu d'afficher des champs vides
     * et le logo à la place de la photo du titulaire.
     *
     * Un DG1 « UB » non authentifié reste affiché comme les autres : rien ne garantit alors ce
     * qu'il prétend être. La chaîne est exigée en plus de la signature (audit V21) : un SOD
     * signé par un DS que la puce fournit elle-même ne prouve rien, et une puce forgée pourrait
     * sinon faire masquer photo et identité derrière un texte rassurant.
     */
    fun isIdentitylessEidUb(report: VerificationReport): Boolean {
        val checks = report.checks.associateBy { it.id }
        return isIdentitylessEidUb(
            report.document.dg1,
            checks[CheckId.SOD_SIGNATURE]?.status ?: CheckStatus.NOT_AVAILABLE,
            checks[CheckId.CERTIFICATE_CHAIN]?.status ?: CheckStatus.NOT_AVAILABLE,
            checks[CheckId.DG_HASHES]?.detail,
        )
    }

    fun isIdentitylessEidUb(
        dg1: Dg1Data,
        sodSignature: CheckStatus,
        certificateChain: CheckStatus,
        hashes: CheckDetail?,
    ): Boolean {
        val dg1Verified =
            sodSignature == CheckStatus.OK &&
                certificateChain == CheckStatus.OK &&
                hashes is CheckDetail.DataGroupHashes &&
                DG1 in hashes.checked &&
                DG1 !in hashes.mismatched
        return dg1Verified && dg1.documentCode.trimFiller() == EID_UB_CODE && dg1.issuingState.trimFiller() == GERMANY
    }

    private fun String.trimFiller(): String = replace('<', ' ').trim()

    private const val DG1 = 1
    private const val EID_UB_CODE = "UB"
    private const val GERMANY = "D"
}
