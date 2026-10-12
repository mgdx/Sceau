package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.ChipAuthenticationMethod
import io.github.mgdx.sceau.core.verify.ActiveAuthentication
import net.sf.scuba.smartcards.CommandAPDU
import org.jmrtd.Util
import org.jmrtd.lds.ActiveAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import java.security.GeneralSecurityException
import java.security.SecureRandom
import io.github.mgdx.sceau.core.verify.Crypto as KeyBounds

/** Chip Authentication outcome: its check line, and the channel state for what follows. */
internal class ChipAuthenticationOutcome(
    val check: Check,
    /**
     * False if the chip no longer responds under the current secure messaging (CA failed after
     * the key change, or the chip closed the session): the channel must be re-established before
     * reading the DGs.
     */
    val channelUsable: Boolean,
)

/**
 * Chip Authentication (SPEC §6.1 step 6, performed during READ_DATA per decision D20) if
 * DG14 announces a key, then, at the VERIFY_CHIP step (step 7), Active Authentication if DG15
 * is present.
 *
 * A line is "not available" only if the corresponding DG is absent from the SOD: a DG14
 * or DG15 that the SOD announces but the chip did not provide is a failure (audit V1),
 * otherwise a clone withholding them would pass for a chip without CA or AA.
 *
 * A lost connection during either one is rethrown (the read fails); any other chip error is
 * recorded in the corresponding check line.
 */
internal class ChipVerifier(
    private val chip: Chip,
    private val random: SecureRandom,
) {
    private val service get() = chip.service

    /**
     * Chip Authentication, before reading DG1, DG2, DG15, DG11, DG12 and DG7 (decisions D20, D37).
     * On success, JMRTD replaces secure messaging with the one derived from the DG14 key:
     * every subsequent command (DG reads, AA) goes through this channel.
     *
     * The exchange proves nothing until the chip has responded under the new channel: a
     * short read of DG1 confirms it (the response MAC is verified), before any data is read.
     * This explicit confirmation, not the DG1 read that follows, decides the CA line: a failure
     * there is a CA failure, not a read error that would interrupt the read; it also indicates
     * that the channel must be re-established ([ChipAuthenticationOutcome.channelUsable]).
     *
     * If the chip rejects the exchange itself, JMRTD keeps the old secure messaging; the same
     * confirmation then checks that the chip still responds under it.
     */
    fun chipAuthentication(
        dg14Bytes: ByteArray?,
        signedInSod: Boolean,
    ): ChipAuthenticationOutcome {
        if (dg14Bytes == null) return unchanged(missing(CheckId.CHIP_AUTHENTICATION, CA_STEP, signedInSod, "CA-DG14Missing"))
        val dg14 =
            try {
                DG14File(dg14Bytes.inputStream())
            } catch (e: Exception) {
                return unchanged(unsupported(CheckId.CHIP_AUTHENTICATION, "DG14"))
            }
        val publicKeyInfo =
            dg14.securityInfos
                .orEmpty()
                .filterIsInstance<ChipAuthenticationPublicKeyInfo>()
                .firstOrNull()
                ?: return unchanged(withoutChipAuthenticationKey(dg14Bytes))
        // JMRTD keeps the entry but without a key when it cannot rebuild it (unknown curve or
        // algorithm): nothing to send to the chip (audit V19).
        val chipKey =
            publicKeyInfo.subjectPublicKey
                ?: return unchanged(unsupported(CheckId.CHIP_AUTHENTICATION, publicKeyInfo.objectIdentifier ?: "CA"))
        // Audit V30: out-of-bounds key rejected before key agreement, nothing is sent to the chip.
        KeyBounds.unsupportedKey(chipKey)?.let { return unchanged(unsupported(CheckId.CHIP_AUTHENTICATION, it)) }
        val keyId = publicKeyInfo.keyId
        val caInfo =
            dg14.securityInfos.orEmpty().filterIsInstance<ChipAuthenticationInfo>().let { infos ->
                infos.firstOrNull { keyId != null && it.keyId == keyId } ?: infos.firstOrNull()
            }
        // Without ChipAuthenticationInfo, JMRTD infers the protocol OID from the key's OID.
        val protocolOid = caInfo?.objectIdentifier
        try {
            service.doEACCA(keyId, protocolOid, publicKeyInfo.objectIdentifier, chipKey)
        } catch (e: Exception) {
            chip.rethrowTransportFailure()
            val check =
                if (isUnsupportedAlgorithm(e)) {
                    unsupported(CheckId.CHIP_AUTHENTICATION, protocolOid ?: publicKeyInfo.objectIdentifier ?: "CA")
                } else {
                    failed(CheckId.CHIP_AUTHENTICATION, CA_STEP, "CA", e)
                }
            return ChipAuthenticationOutcome(check, channelUsable = confirmChannel("CA_CONFIRM_OLD") == null)
        }
        val failure =
            confirmChannel("CA_CONFIRM")
                ?: return unchanged(
                    check(CheckId.CHIP_AUTHENTICATION, CheckStatus.OK, CheckDetail.ChipAuthentication(ChipAuthenticationMethod.DG14)),
                )
        return ChipAuthenticationOutcome(check(CheckId.CHIP_AUTHENTICATION, CheckStatus.FAILED, failure), channelUsable = false)
    }

    private fun unchanged(check: Check) = ChipAuthenticationOutcome(check, channelUsable = true)

    /**
     * DG14 without a `ChipAuthenticationPublicKeyInfo` recognized by JMRTD (audit V19): "not
     * available" only if its bytes announce neither a key (no id-PK-* OID) nor a Chip
     * Authentication protocol (no id-CA-* OID). A key announced but that JMRTD could not
     * rebuild (`SecurityInfo.getInstance` then returns null) gives `UNSUPPORTED_ALGORITHM`,
     * hence the "Failure" verdict, not a silent failure. The same goes for a
     * `ChipAuthenticationInfo` without any key (audit V33): the signed DG14 announces a CA that
     * the application cannot perform; this is not a document without CA.
     */
    private fun withoutChipAuthenticationKey(dg14Bytes: ByteArray): Check {
        val oids = SecurityInfoProtocols.of(dg14Bytes) ?: return unsupported(CheckId.CHIP_AUTHENTICATION, "DG14")
        val announced =
            SecurityInfoProtocols.chipAuthenticationKey(oids)
                ?: SecurityInfoProtocols.chipAuthenticationProtocol(oids)
                ?: return check(CheckId.CHIP_AUTHENTICATION, CheckStatus.NOT_AVAILABLE)
        return unsupported(CheckId.CHIP_AUTHENTICATION, announced)
    }

    /** Active Authentication: 8-byte challenge drawn from [random], response verified by batch B. */
    fun activeAuthentication(
        dg15Bytes: ByteArray?,
        dg14Bytes: ByteArray?,
        signedInSod: Boolean,
    ): Check {
        if (dg15Bytes == null) return missing(CheckId.ACTIVE_AUTHENTICATION, Step.VERIFY_CHIP, signedInSod, "AA-DG15Missing")
        val publicKey =
            try {
                DG15File(dg15Bytes.inputStream()).publicKey
            } catch (e: Exception) {
                null
            } ?: return unsupported(CheckId.ACTIVE_AUTHENTICATION, "DG15")
        val signatureAlgorithm = aaSignatureAlgorithm(dg14Bytes)
        val digestAlgorithm = signatureAlgorithm?.let { it.mnemonic?.let(Util::inferDigestAlgorithmFromSignatureAlgorithm) ?: it.oid }
        val challenge = ByteArray(AA_CHALLENGE_LENGTH).also(random::nextBytes)
        val response =
            try {
                service.doAA(publicKey, digestAlgorithm, signatureAlgorithm?.mnemonic, challenge).response
            } catch (e: Exception) {
                chip.rethrowTransportFailure()
                return failed(CheckId.ACTIVE_AUTHENTICATION, Step.VERIFY_CHIP, "AA", e)
            }
        // JMRTD returns the data received whatever the SW: an empty response is a refusal.
        if (response == null || response.isEmpty()) {
            return check(CheckId.ACTIVE_AUTHENTICATION, CheckStatus.FAILED, CheckDetail.Error("${Step.VERIFY_CHIP.name}-AA-EmptyResponse"))
        }
        return ActiveAuthentication.verifyResponse(publicKey, digestAlgorithm, challenge, response)
    }

    /**
     * Checks that the chip responds under the current secure messaging: SELECT of DG1 then
     * READ BINARY of one byte, responses with verified MAC. Null if it responds, otherwise the
     * failure detail (label [tag]). A transport error is rethrown.
     */
    private fun confirmChannel(tag: String): CheckDetail.Error? {
        val commands =
            listOf(
                CommandAPDU(0x00, INS_SELECT, 0x02, 0x0C, fidBytes(Chip.fidOf(1)), 0),
                CommandAPDU(0x00, INS_READ_BINARY, 0x00, 0x00, 1),
            )
        for (command in commands) {
            val sw =
                try {
                    service.secureMessagingAPDUSender.transmit(service.wrapper, command).sw and 0xFFFF
                } catch (e: Exception) {
                    chip.rethrowTransportFailure()
                    return CheckDetail.Error(technicalCode(CA_STEP.name, StepFailure(tag, e)))
                }
            if (sw != SW_OK) return CheckDetail.Error(technicalCodeForSw(CA_STEP, tag, sw))
        }
        return null
    }

    private class AaAlgorithm(
        val oid: String,
        val mnemonic: String?,
    )

    /** ECDSA signature algorithm announced by the ActiveAuthenticationInfo of DG14, if any. */
    private fun aaSignatureAlgorithm(dg14Bytes: ByteArray?): AaAlgorithm? {
        if (dg14Bytes == null) return null
        val oid =
            try {
                DG14File(dg14Bytes.inputStream())
                    .securityInfos
                    .orEmpty()
                    .filterIsInstance<ActiveAuthenticationInfo>()
                    .firstOrNull()
                    ?.signatureAlgorithmOID
            } catch (e: Exception) {
                null
            } ?: return null
        val mnemonic =
            try {
                ActiveAuthenticationInfo.lookupMnemonicByOID(oid)
            } catch (e: GeneralSecurityException) {
                null
            }
        return AaAlgorithm(oid, mnemonic)
    }

    private fun isUnsupportedAlgorithm(error: Throwable): Boolean =
        error.causeChain().any {
            it is IllegalArgumentException ||
                it is java.security.NoSuchAlgorithmException ||
                it is java.security.InvalidAlgorithmParameterException
        }

    private fun check(
        id: CheckId,
        status: CheckStatus,
        detail: CheckDetail = CheckDetail.None,
    ) = Check(id, status, detail)

    /** Missing DG: not available if it is not in the SOD, failure if it is (chip withholding it). */
    private fun missing(
        id: CheckId,
        step: Step,
        signedInSod: Boolean,
        tag: String,
    ) = if (signedInSod) {
        check(id, CheckStatus.FAILED, CheckDetail.Error("${step.name}-$tag"))
    } else {
        check(id, CheckStatus.NOT_AVAILABLE)
    }

    private fun unsupported(
        id: CheckId,
        algorithm: String,
    ) = check(id, CheckStatus.UNSUPPORTED_ALGORITHM, CheckDetail.UnsupportedAlgorithm(algorithm))

    private fun failed(
        id: CheckId,
        step: Step,
        tag: String,
        error: Throwable,
    ) = check(id, CheckStatus.FAILED, CheckDetail.Error(technicalCode(step.name, StepFailure(tag, error))))

    private fun technicalCodeForSw(
        step: Step,
        tag: String,
        sw: Int,
    ) = "${step.name}-$tag-${String.format(java.util.Locale.ROOT, "%04X", sw)}"

    private fun fidBytes(fid: Short) = byteArrayOf((fid.toInt() shr 8).toByte(), fid.toByte())

    companion object {
        const val AA_CHALLENGE_LENGTH = 8

        /** Step during which CA is performed (D20): prefix of its error codes. */
        private val CA_STEP = Step.READ_DATA
        private const val SW_OK = 0x9000
        private const val INS_SELECT = 0xA4
        private const val INS_READ_BINARY = 0xB0
    }
}
