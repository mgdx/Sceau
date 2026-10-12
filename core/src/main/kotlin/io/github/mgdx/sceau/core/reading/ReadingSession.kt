package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.model.Dg1Data
import io.github.mgdx.sceau.core.model.DocumentData
import io.github.mgdx.sceau.core.model.Sex
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.VerificationReport
import io.github.mgdx.sceau.core.trust.TrustStore
import io.github.mgdx.sceau.core.verify.PassiveAuthentication
import io.github.mgdx.sceau.core.verify.Verdicts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException

/**
 * Orchestration of `readAndVerify` (SPEC §6.1). One instance per read.
 *
 * Errors: [SceauException]s pass through unchanged (the transport's one first, even if JMRTD
 * wrapped or swallowed it, with the step prepended to the code if it is a technical error),
 * and so does [CancellationException]; any other exception becomes
 * [SceauException.Unexpected] with a technical identifier (step, class, SW), with no cause
 * attached, because JMRTD's messages contain APDUs in clear.
 * Data already read is zeroed if the read fails, including on an `Error` (rethrown
 * unchanged).
 *
 * The Active Authentication nonce is drawn from [random], a `SecureRandom` (SPEC §8).
 *
 * Global timeout (audit V29): outside authentication (the SECURE_CHANNEL step, which has its
 * own timeouts), the read has [globalTimeoutMillis] in total. When it expires, the transport is
 * closed and the read ends with [SceauException.Timeout] (`TIMEOUT-<STEP>-GLOBAL`), even if a
 * report was produced in the meantime: that report is then wiped.
 */
internal class ReadingSession(
    private val transport: CardTransport,
    private val key: AccessKey,
    private val trustStore: TrustStore,
    private val progress: (Step) -> Unit,
    private val random: SecureRandom = SecureRandom(),
    private val globalTimeoutMillis: Long = GLOBAL_TIMEOUT_MILLIS,
) {
    @Volatile
    private var currentStep = Step.CONNECT
    private var deadline: ReadDeadline? = null

    /** Step in progress when the global timeout expired, null if it has not expired. */
    @Volatile
    private var expiredDuring: Step? = null
    private var document: DocumentData? = null

    /** Raw DGs read, wiped on failure even if their parsing did not complete. */
    private var rawDataGroups: Map<Int, ByteArray>? = null

    private fun wipeRead() {
        rawDataGroups?.values?.forEach { it.fill(0) }
        document?.wipe()
    }

    suspend fun run(): VerificationReport =
        withContext(Dispatchers.IO) {
            Crypto.ensureInstalled()
            LibraryLogging.silence()
            val chip = Chip(transport)
            deadline =
                ReadDeadline(this, globalTimeoutMillis) {
                    expiredDuring = currentStep
                    closeQuietly(transport)
                }
            try {
                execute(chip).also { checkDeadline() }
            } catch (e: Throwable) {
                // Wipe on any error, `Error`s included (OutOfMemoryError…);
                // CancellationException is rethrown unchanged, after wiping.
                wipeRead()
                if (e !is Exception || e is CancellationException) throw e
                // Global timeout exceeded: the error raised (closed transport…) is only a consequence of it.
                expiredDuring?.let { throw globalTimeout(it) }
                val failure =
                    chip.cardService.transportFailure
                        ?: e as? SceauException
                        ?: e.findTransportFailure()
                throw failure?.withStep(currentStep) ?: SceauException.Unexpected(technicalCode(currentStep.name, e))
            } finally {
                deadline?.stop()
                try {
                    chip.close()
                } catch (e: Exception) {
                    // Closing must not mask the result or the error of the read.
                }
            }
        }

    /** Throws the global timeout if it expired during the read (the report produced is then wiped). */
    private fun checkDeadline() {
        expiredDuring?.let { throw globalTimeout(it) }
    }

    private fun globalTimeout(step: Step) = SceauException.Timeout(detail = "${step.name}-$GLOBAL_TIMEOUT_DETAIL")

    private fun closeQuietly(transport: CardTransport) {
        try {
            transport.close()
        } catch (e: Exception) {
            // Already closed: the read will fail on the global timeout anyway.
        }
    }

    /**
     * A technical transport error (`UNEXPECTED-IO-…`) does not say at which step it occurred:
     * the step is prepended to its identifier. The same goes for a timeout or a lost connection
     * carrying a diagnostic (`TIMEOUT-INS86-L10` becomes `TIMEOUT-SECURE_CHANNEL-INS86-L10`), and
     * for restricted access (`ACCESS_RESTRICTED-SOD` becomes `ACCESS_RESTRICTED-READ_DATA-SOD`,
     * D36). Other codes (`ACCESS_DENIED`…) are returned unchanged.
     */
    private fun SceauException.withStep(step: Step): SceauException =
        when {
            this is SceauException.Unexpected -> SceauException.Unexpected("${step.name}-$detail", this)
            this is SceauException.Timeout && detail != null -> SceauException.Timeout(this, "${step.name}-$detail")
            this is SceauException.ConnectionLost && detail != null -> SceauException.ConnectionLost(this, "${step.name}-$detail")
            this is SceauException.AccessRestricted -> SceauException.AccessRestricted("${step.name}-$detail")
            this is SceauException.ReadStalled -> SceauException.ReadStalled("${step.name}-$detail")
            else -> this
        }

    private fun CoroutineScope.step(step: Step) {
        ensureActive()
        currentStep = step
        // Global timeout (V29): suspended during authentication, which has its own timeouts.
        if (step == Step.SECURE_CHANNEL) deadline?.pause() else deadline?.resume()
        progress(step)
    }

    private fun CoroutineScope.execute(chip: Chip): VerificationReport {
        val channel = SecureChannel(chip, key)

        step(Step.CONNECT)
        val paceInfos = channel.connect()

        step(Step.SECURE_CHANNEL)
        val established = channel.establish(paceInfos)
        val secureChannel = Check(CheckId.SECURE_CHANNEL, CheckStatus.OK, CheckDetail.SecureChannel(established.protocol))
        // PACE-CAM (D21): the chip authenticated itself during PACE, and everything that follows is
        // already read under this channel's keys; CA via DG14 is not repeated.
        val cam = established.cam

        // Decision D20: EF.COM, EF.SOD and DG14, then Chip Authentication, then the other DGs
        // under CA secure messaging, which thereby binds the data read to the chip.
        step(Step.READ_DATA)
        val reader = DocumentReader(chip)
        val objects = reader.readSecurityObjects()
        val dataGroups = LinkedHashMap<Int, ByteArray>()
        rawDataGroups = dataGroups
        objects.dg14?.let { dataGroups[DocumentReader.SECURITY_DATA_GROUP] = it }

        val verifier = ChipVerifier(chip, random)
        val dg14Signed = DocumentReader.SECURITY_DATA_GROUP in objects.signedDataGroups
        val caOutcome = if (cam == null) verifier.chipAuthentication(objects.dg14, signedInSod = dg14Signed) else null
        // CA failed and the chip is silent under the current secure messaging: channel re-established
        // with the same key, to read the data anyway (the CA line stays failed, and so does the verdict).
        // Audit V24: CA has already failed; an error while re-establishing (including D36's
        // restricted access, which a hostile chip can return on purpose) yields a "Failure" report
        // without data, not a read error.
        if (caOutcome?.channelUsable == false) {
            try {
                channel.reestablish()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return failedChipAuthenticationReport(chip, secureChannel, caOutcome.check, dataGroups, e)
            }
        }
        ensureActive()

        val content = reader.readDataGroups(objects, dataGroups)
        val data = DataGroupParsers.document(content.dataGroups)
        document = data

        step(Step.VERIFY_SIGNATURE)
        val pa =
            PassiveAuthentication.verify(
                sod = content.sod,
                dataGroups = content.dataGroups,
                trustStore = trustStore,
                dateOfIssue = data.dg12?.dateOfIssue,
                dateOfExpiry = data.dg1.dateOfExpiry,
                documentCode = data.dg1.documentCode,
                issuingState = data.dg1.issuingState,
                documentNumber = data.dg1.documentNumber,
            )
        // Decision D34: a DG whose hash mismatch is tolerated (known issuer deviation) is not
        // trustworthy; it is removed from the report and zeroed.
        val shown = if (pa.discardedDataGroups.isEmpty()) data else data.discarding(pa.discardedDataGroups)
        document = shown

        // CA is already done (READ_DATA): the VERIFY_CHIP step now only covers AA, and the offline
        // verification of PACE-CAM, which needs the issuing State from DG1.
        step(Step.VERIFY_CHIP)
        val mapping =
            cam?.let {
                ChipAuthenticationMapping.verify(
                    it,
                    trustStore,
                    data.dg1.issuingState,
                    pa.certificateChain.status,
                    pa.chain,
                )
            }
        val aa = verifier.activeAuthentication(content.dataGroups[15], content.dataGroups[14], signedInSod = 15 in content.signedDataGroups)
        // Audits V20 and V32: protocol performed compared with what DG14 and EF.CardSecurity announce,
        // both signed (EF.CardAccess is not). With PACE-CAM, EF.CardSecurity is verified above.
        val protocol =
            ProtocolDowngrade.apply(
                dg14 = content.dataGroups[DocumentReader.SECURITY_DATA_GROUP],
                dg14Verified = pa.dataGroupHashes.verifies(DocumentReader.SECURITY_DATA_GROUP),
                cardSecurity =
                    if (cam == null) {
                        ProtocolDowngrade.cardSecurity(channel.cardSecurity, trustStore, data.dg1.issuingState)
                    } else {
                        ProtocolDowngrade.CardSecurity.Absent
                    },
                established = established,
                secureChannel = secureChannel,
                chipAuthentication = mapping?.check ?: checkNotNull(caOutcome).check,
                activeAuthentication = aa,
            )
        val ca = protocol.chipAuthentication

        val checks = listOf(protocol.secureChannel, pa.sodSignature, pa.certificateChain, pa.dsValidity, pa.dataGroupHashes, ca, aa)
        return VerificationReport(
            verdict = Verdicts.compute(checks),
            checks = checks,
            chain = pa.chain,
            document = shown,
            cardSecurityChain = mapping?.cardSecurityChain,
        )
    }

    /**
     * Report of a read interrupted while re-establishing the channel after a failed CA (audit V24):
     * CA line failed ([chipAuthentication]), hence a "Failure" verdict; the other checks did not
     * take place and carry the code of the failed re-establishment, without personal data. No
     * identity data was read: DG1 is empty, and only DG14 (already read) is in [dataGroups],
     * zeroed along with the report.
     */
    private fun failedChipAuthenticationReport(
        chip: Chip,
        secureChannel: Check,
        chipAuthentication: Check,
        dataGroups: Map<Int, ByteArray>,
        error: Exception,
    ): VerificationReport {
        val failure = chip.cardService.transportFailure ?: error as? SceauException ?: error.findTransportFailure()
        val code = "${Step.READ_DATA.name}-REESTABLISH-${failure?.code ?: technicalCode(Step.READ_DATA.name, error)}"
        val notDone = { id: CheckId -> Check(id, CheckStatus.NOT_AVAILABLE, CheckDetail.Error(code)) }
        val checks =
            listOf(
                secureChannel,
                notDone(CheckId.SOD_SIGNATURE),
                notDone(CheckId.CERTIFICATE_CHAIN),
                notDone(CheckId.DS_VALIDITY),
                notDone(CheckId.DG_HASHES),
                chipAuthentication,
                notDone(CheckId.ACTIVE_AUTHENTICATION),
            )
        val empty =
            DocumentData(
                dg1 = Dg1Data("", "", "", "", emptyList(), "", null, Sex.UNSPECIFIED, null, null),
                portrait = null,
                dg11 = null,
                dg12 = null,
                rawDataGroups = dataGroups,
            )
        document = empty
        return VerificationReport(
            verdict = Verdicts.compute(checks),
            checks = checks,
            chain = null,
            document = empty,
            identityRead = false,
        )
    }

    /** True if the hash of DG [number] is in the SOD and matches. */
    private fun Check.verifies(number: Int): Boolean =
        (detail as? CheckDetail.DataGroupHashes)?.let { number in it.checked && number !in it.mismatched } ?: false

    /** Copy without DGs [numbers] (11 or 12), whose bytes and images are zeroed. */
    private fun DocumentData.discarding(numbers: Set<Int>): DocumentData {
        numbers.forEach { rawDataGroups[it]?.fill(0) }
        if (DG12 in numbers) {
            dg12?.frontImage?.wipe()
            dg12?.rearImage?.wipe()
        }
        return DocumentData(
            dg1 = dg1,
            portrait = portrait,
            dg11 = dg11.takeUnless { DG11 in numbers },
            dg12 = dg12.takeUnless { DG12 in numbers },
            rawDataGroups = rawDataGroups - numbers,
            signature = signature,
        )
    }

    internal companion object {
        private const val DG11 = 11
        private const val DG12 = 12

        /**
         * Global timeout of a read outside authentication (audit V29): a complete real read
         * (including a 40 KB DG2) takes from a few seconds to about twenty seconds.
         */
        const val GLOBAL_TIMEOUT_MILLIS = 120_000L
        private const val GLOBAL_TIMEOUT_DETAIL = "GLOBAL"
    }
}
