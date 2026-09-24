package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.model.DocumentData
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
 * Orchestration de `readAndVerify` (SPEC §6.1). Une instance par lecture.
 *
 * Erreurs : les [SceauException] traversent telles quelles (celle du transport en priorité,
 * même si JMRTD l'a enveloppée ou avalée, avec l'étape en tête du code si c'est une erreur
 * technique), [CancellationException] aussi ; toute autre exception devient
 * [SceauException.Unexpected] avec un identifiant technique (étape, classe, SW), sans cause
 * attachée, car les messages de JMRTD contiennent des APDU en clair.
 * Les données déjà lues sont remises à zéro si la lecture échoue.
 *
 * Le nonce d'Active Authentication est tiré de [random], un `SecureRandom` (SPEC §8).
 */
internal class ReadingSession(
    private val transport: CardTransport,
    private val key: AccessKey,
    private val trustStore: TrustStore,
    private val progress: (Step) -> Unit,
    private val random: SecureRandom = SecureRandom(),
) {
    private var currentStep = Step.CONNECT
    private var document: DocumentData? = null

    suspend fun run(): VerificationReport =
        withContext(Dispatchers.IO) {
            Crypto.ensureInstalled()
            LibraryLogging.silence()
            val chip = Chip(transport)
            try {
                execute(chip)
            } catch (e: CancellationException) {
                document?.wipe()
                throw e
            } catch (e: Exception) {
                document?.wipe()
                val failure =
                    chip.cardService.transportFailure
                        ?: e as? SceauException
                        ?: e.findTransportFailure()
                throw failure?.withStep(currentStep) ?: SceauException.Unexpected(technicalCode(currentStep.name, e))
            } finally {
                try {
                    chip.close()
                } catch (e: Exception) {
                    // La fermeture ne doit pas masquer le résultat ou l'erreur de la lecture.
                }
            }
        }

    /**
     * Une erreur technique du transport (`UNEXPECTED-IO-…`) ne dit pas à quelle étape elle
     * s'est produite : l'étape est ajoutée en tête de son identifiant. De même pour un délai ou
     * une perte de liaison porteurs d'un diagnostic (`TIMEOUT-INS86-L10` devient
     * `TIMEOUT-SECURE_CHANNEL-INS86-L10`). Les autres codes (`ACCESS_DENIED`…) sont renvoyés
     * tels quels.
     */
    private fun SceauException.withStep(step: Step): SceauException =
        when {
            this is SceauException.Unexpected -> SceauException.Unexpected("${step.name}-$detail", this)
            this is SceauException.Timeout && detail != null -> SceauException.Timeout(this, "${step.name}-$detail")
            this is SceauException.ConnectionLost && detail != null -> SceauException.ConnectionLost(this, "${step.name}-$detail")
            else -> this
        }

    private fun CoroutineScope.step(step: Step) {
        ensureActive()
        currentStep = step
        progress(step)
    }

    private fun CoroutineScope.execute(chip: Chip): VerificationReport {
        val channel = SecureChannel(chip, key)

        step(Step.CONNECT)
        val paceInfos = channel.connect()

        step(Step.SECURE_CHANNEL)
        val protocol = channel.establish(paceInfos)
        val secureChannel = Check(CheckId.SECURE_CHANNEL, CheckStatus.OK, CheckDetail.SecureChannel(protocol))

        step(Step.READ_DATA)
        val content = DocumentReader(chip).read()
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
            )

        step(Step.VERIFY_CHIP)
        val verifier = ChipVerifier(chip, random)
        val ca = verifier.chipAuthentication(content.dataGroups[14])
        ensureActive()
        val aa = verifier.activeAuthentication(content.dataGroups[15], content.dataGroups[14])

        val checks = listOf(secureChannel, pa.sodSignature, pa.certificateChain, pa.dsValidity, pa.dataGroupHashes, ca, aa)
        return VerificationReport(
            verdict = Verdicts.compute(checks),
            checks = checks,
            chain = pa.chain,
            document = data,
        )
    }
}
