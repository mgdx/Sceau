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
 * Orchestration de `readAndVerify` (SPEC §6.1). Une instance par lecture.
 *
 * Erreurs : les [SceauException] traversent telles quelles (celle du transport en priorité,
 * même si JMRTD l'a enveloppée ou avalée, avec l'étape en tête du code si c'est une erreur
 * technique), [CancellationException] aussi ; toute autre exception devient
 * [SceauException.Unexpected] avec un identifiant technique (étape, classe, SW), sans cause
 * attachée, car les messages de JMRTD contiennent des APDU en clair.
 * Les données déjà lues sont remises à zéro si la lecture échoue, y compris sur une `Error`
 * (relancée telle quelle).
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

    /** DG bruts lus, effacés en cas d'échec même si leur analyse n'a pas abouti. */
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
            try {
                execute(chip)
            } catch (e: Throwable) {
                // Effacement sur toute erreur, `Error` comprises (OutOfMemoryError…) ;
                // CancellationException est relancée telle quelle, après effacement.
                wipeRead()
                if (e !is Exception || e is CancellationException) throw e
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
     * `TIMEOUT-SECURE_CHANNEL-INS86-L10`), et pour un accès réservé (`ACCESS_RESTRICTED-SOD`
     * devient `ACCESS_RESTRICTED-READ_DATA-SOD`, D36). Les autres codes (`ACCESS_DENIED`…) sont
     * renvoyés tels quels.
     */
    private fun SceauException.withStep(step: Step): SceauException =
        when {
            this is SceauException.Unexpected -> SceauException.Unexpected("${step.name}-$detail", this)
            this is SceauException.Timeout && detail != null -> SceauException.Timeout(this, "${step.name}-$detail")
            this is SceauException.ConnectionLost && detail != null -> SceauException.ConnectionLost(this, "${step.name}-$detail")
            this is SceauException.AccessRestricted -> SceauException.AccessRestricted("${step.name}-$detail")
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
        val established = channel.establish(paceInfos)
        val secureChannel = Check(CheckId.SECURE_CHANNEL, CheckStatus.OK, CheckDetail.SecureChannel(established.protocol))
        // PACE-CAM (D21) : la puce s'est authentifiée pendant PACE, et tout ce qui suit est déjà
        // lu sous les clés de ce canal ; la CA via DG14 n'est pas refaite.
        val cam = established.cam

        // Décision D20 : EF.COM, EF.SOD et DG14, puis Chip Authentication, puis les autres DG
        // sous la messagerie sécurisée de la CA, qui lie ainsi les données lues à la puce.
        step(Step.READ_DATA)
        val reader = DocumentReader(chip)
        val objects = reader.readSecurityObjects()
        val dataGroups = LinkedHashMap<Int, ByteArray>()
        rawDataGroups = dataGroups
        objects.dg14?.let { dataGroups[DocumentReader.SECURITY_DATA_GROUP] = it }

        val verifier = ChipVerifier(chip, random)
        val dg14Signed = DocumentReader.SECURITY_DATA_GROUP in objects.signedDataGroups
        val caOutcome = if (cam == null) verifier.chipAuthentication(objects.dg14, signedInSod = dg14Signed) else null
        // CA ratée et puce muette sous la messagerie courante : canal rétabli avec la même clé,
        // pour lire quand même les données (la ligne CA reste en échec, donc le verdict aussi).
        // Audit V24 : la CA a déjà échoué ; une erreur au rétablissement (y compris l'accès
        // réservé de D36, qu'une puce hostile peut répondre à dessein) donne un rapport « Échec »
        // sans données, et non une erreur de lecture.
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
        // Décision D34 : un DG dont l'écart d'empreinte est toléré (anomalie connue de l'émetteur)
        // n'est pas digne de foi ; il est retiré du rapport et remis à zéro.
        val shown = if (pa.discardedDataGroups.isEmpty()) data else data.discarding(pa.discardedDataGroups)
        document = shown

        // La CA est déjà faite (READ_DATA) : l'étape VERIFY_CHIP ne couvre plus que l'AA, et la
        // vérification hors ligne de PACE-CAM, qui a besoin de l'État émetteur de DG1.
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
        // Audit V20 : protocole mené comparé à ce qu'annonce DG14, signé (EF.CardAccess ne l'est pas).
        val protocol =
            ProtocolDowngrade.apply(
                dg14 = content.dataGroups[DocumentReader.SECURITY_DATA_GROUP],
                dg14Verified = pa.dataGroupHashes.verifies(DocumentReader.SECURITY_DATA_GROUP),
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
     * Rapport d'une lecture interrompue au rétablissement du canal après une CA ratée (audit V24) :
     * ligne CA en échec ([chipAuthentication]), donc verdict « Échec » ; les autres vérifications
     * n'ont pas eu lieu et portent le code du rétablissement raté, sans donnée personnelle. Aucune
     * donnée d'identité n'a été lue : DG1 est vide, et seul DG14 (déjà lu) figure dans
     * [dataGroups], remis à zéro avec le rapport.
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

    /** Vrai si l'empreinte du DG [number] figure dans le SOD et y est conforme. */
    private fun Check.verifies(number: Int): Boolean =
        (detail as? CheckDetail.DataGroupHashes)?.let { number in it.checked && number !in it.mismatched } ?: false

    /** Copie sans les DG [numbers] (11 ou 12), dont les octets et les images sont remis à zéro. */
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

    private companion object {
        const val DG11 = 11
        const val DG12 = 12
    }
}
