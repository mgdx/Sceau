package io.github.mgdx.sceau.ui.result

import io.github.mgdx.sceau.core.model.Dg1Data
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.VerificationReport

/** Presentation of the identity on the "Result" screen. */
internal object IdentityPresentation {
    enum class Mode {
        /** Photo, identity, signature and additional data. */
        FULL,

        /** Authenticated eID-UB card, with no identity data in its chip ([isIdentitylessEidUb]). */
        IDENTITYLESS_EID_UB,

        /**
         * No data read (reading interrupted after a failed Chip Authentication, audit V24):
         * no photo, no identity, no additional data, only a sentence saying so.
         */
        NOT_READ,
    }

    fun mode(report: VerificationReport): Mode =
        when {
            !report.identityRead -> Mode.NOT_READ
            isIdentitylessEidUb(report) -> Mode.IDENTITYLESS_EID_UB
            else -> Mode.FULL
        }

    /**
     * German eID card for Union citizens (eID-UB, BSI TR-03127, decision D36) whose DG1 is
     * authenticated: valid SOD signature, SOD certificate chain valid up to a CSCA of the trust
     * store, matching DG1 hash, document code "UB" and state "D". Its chip carries no identity
     * data (placeholder MRZ made of "<", DG2 reduced to the eID logo): the screen says so,
     * instead of displaying empty fields and the logo in place of the holder's photo.
     *
     * An unauthenticated "UB" DG1 is still displayed like the others: nothing then guarantees
     * what it claims to be. The chain is required in addition to the signature (audit V21): a SOD
     * signed by a DS that the chip supplies itself proves nothing, and a forged chip could
     * otherwise get the photo and identity hidden behind a reassuring text.
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
