package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.ChainInfo
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.trust.TrustStore
import java.security.PublicKey
import java.time.LocalDate

/** Passive Authentication result: four lines of the checklist. */
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
     * DGs whose hash mismatch is tolerated as a known issuer anomaly
     * (`CheckDetail.DataGroupHashes.deviations`, decision D34): 11 or 12 only. Their data is
     * not trustworthy: it must be removed from the report and zeroed.
     */
    val discardedDataGroups: Set<Int> = emptySet(),
)

object PassiveAuthentication {
    /**
     * SPEC §6.1 step 5. Never accesses the chip.
     *
     * @param sod raw bytes of EF.SOD (including tag 0x77)
     * @param dataGroups raw bytes of each DG read, key = DG number
     * @param dateOfIssue date of issue read from DG12, if available
     * @param dateOfExpiry date of expiry read from DG1, for the estimate
     * @param documentCode DG1 document code ("P", "ID"…), to choose the usual validity period
     * @param issuingState issuing state read from DG1 (three-letter ICAO code, "FRA", "D<<"…):
     *   it must match the country of the CSCA and of the DS (see [IcaoCountries]), otherwise the
     *   chain fails with `CheckDetail.CountryMismatch`. Null: only the CSCA and the DS are compared.
     * @param documentNumber document number read from DG1, to recognize a known issuer anomaly
     *   (decision D34); only taken into account if the DG1 hash is verified.
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
     * Verifies the chip's response to the AA challenge with the DG15 public key
     * (RSA ISO/IEC 9796-2 scheme 1, or ECDSA with the hash algorithm declared in DG14).
     *
     * @param digestAlgorithm BouncyCastle name of the ECDSA hash algorithm ("SHA-256"…); ignored for RSA
     * @return ACTIVE_AUTHENTICATION Check: OK, FAILED or UNSUPPORTED_ALGORITHM
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
     * Computes the overall verdict from the seven lines of the checklist (SPEC §5.3).
     *
     * Decision table, applied in order (the first rule that applies wins):
     *
     * | # | Condition                                                                 | Verdict                                   |
     * |---|---------------------------------------------------------------------------|-------------------------------------------|
     * | 1 | any line FAILED or UNSUPPORTED_ALGORITHM                                  | [Verdict.FAILED]                          |
     * | 2 | CERTIFICATE_CHAIN NOT_AVAILABLE                                           | [Verdict.UNKNOWN_ISSUER]                  |
     * | 3 | complete PA (a), and CHIP_AUTHENTICATION OK or ACTIVE_AUTHENTICATION OK   | [Verdict.AUTHENTIC]                       |
     * | 4 | complete PA (a), CHIP_AUTHENTICATION and ACTIVE_AUTHENTICATION NOT_AVAILABLE | [Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED] |
     * | 5 | any other case (missing line, SOD_SIGNATURE NOT_AVAILABLE…)               | [Verdict.FAILED], out of caution          |
     *
     * (a) Complete PA: SECURE_CHANNEL, SOD_SIGNATURE, CERTIFICATE_CHAIN and DG_HASHES OK,
     * DS_VALIDITY OK or NOT_AVAILABLE (date of issue unknown or only estimated).
     *
     * A CA or AA line NOT_AVAILABLE alongside the other one OK gives AUTHENTIC (rule 3):
     * a single one of the two challenges is enough.
     *
     * CA (resp. AA) is NOT_AVAILABLE only if DG14 (resp. DG15) is absent from the SOD, or if DG14
     * declares neither a Chip Authentication key nor protocol. A DG
     * declared by the SOD but not provided by the chip sets DG_HASHES (detail
     * `DataGroupHashes.missing`) and the CA or AA line to FAILED: rule 4 therefore only applies
     * to a document whose signed SOD contains neither DG14 nor DG15 (audit V1). A signed DG14
     * that declares a CA key (OID id-PK-*) the app cannot use sets the CA line to
     * UNSUPPORTED_ALGORITHM, never to NOT_AVAILABLE (audit V19); likewise for a DG14 that
     * declares the CA protocol (OID id-CA-*) without any key (audit V33, decision D43).
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
