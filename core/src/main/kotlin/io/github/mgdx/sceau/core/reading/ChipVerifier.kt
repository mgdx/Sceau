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

/** Résultat de la Chip Authentication : sa ligne de contrôle, et l'état du canal pour la suite. */
internal class ChipAuthenticationOutcome(
    val check: Check,
    /**
     * Faux si la puce ne répond plus sous la messagerie sécurisée courante (CA ratée après le
     * changement de clés, ou puce qui a clos la session) : le canal est à rétablir avant de lire
     * les DG.
     */
    val channelUsable: Boolean,
)

/**
 * Chip Authentication (SPEC §6.1 étape 6, menée pendant READ_DATA selon la décision D20) si
 * DG14 annonce une clé, puis, à l'étape VERIFY_CHIP (étape 7), Active Authentication si DG15
 * est présent.
 *
 * Une ligne n'est « non disponible » que si le DG correspondant est absent du SOD : un DG14
 * ou un DG15 que le SOD annonce mais que la puce n'a pas fourni est un échec (audit V1), sans
 * quoi un clone qui les retient passerait pour une puce sans CA ni AA.
 *
 * Une perte de connexion pendant l'une ou l'autre est relancée (la lecture échoue) ; toute
 * autre erreur de la puce est consignée dans la ligne de contrôle correspondante.
 */
internal class ChipVerifier(
    private val chip: Chip,
    private val random: SecureRandom,
) {
    private val service get() = chip.service

    /**
     * Chip Authentication, avant la lecture de DG1, DG2, DG15, DG11, DG12 et DG7 (décisions D20, D37).
     * Après succès, JMRTD remplace la messagerie sécurisée par celle dérivée de la clé de DG14 :
     * toute commande ultérieure (lecture des DG, AA) passe par ce canal.
     *
     * L'échange ne prouve rien tant que la puce n'a pas répondu sous le nouveau canal : une
     * lecture courte de DG1 le confirme (le MAC de la réponse est vérifié), avant toute lecture
     * de données. Cette confirmation explicite, et non la lecture de DG1 qui suit, décide de la
     * ligne CA : un échec y est un échec de la CA, et non une erreur de lecture qui
     * interromprait la lecture ; il indique aussi que le canal est à rétablir
     * ([ChipAuthenticationOutcome.channelUsable]).
     *
     * Si la puce refuse l'échange lui-même, JMRTD garde l'ancienne messagerie ; la même
     * confirmation vérifie alors que la puce y répond encore.
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
                ?: return unchanged(check(CheckId.CHIP_AUTHENTICATION, CheckStatus.NOT_AVAILABLE))
        val keyId = publicKeyInfo.keyId
        val caInfo =
            dg14.securityInfos.orEmpty().filterIsInstance<ChipAuthenticationInfo>().let { infos ->
                infos.firstOrNull { keyId != null && it.keyId == keyId } ?: infos.firstOrNull()
            }
        // Sans ChipAuthenticationInfo, JMRTD déduit l'OID du protocole de celui de la clé.
        val protocolOid = caInfo?.objectIdentifier
        try {
            service.doEACCA(keyId, protocolOid, publicKeyInfo.objectIdentifier, publicKeyInfo.subjectPublicKey)
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

    /** Active Authentication : challenge de 8 octets tiré de [random], réponse vérifiée par le lot B. */
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
        // JMRTD renvoie les données reçues quel que soit le SW : une réponse vide est un refus.
        if (response == null || response.isEmpty()) {
            return check(CheckId.ACTIVE_AUTHENTICATION, CheckStatus.FAILED, CheckDetail.Error("${Step.VERIFY_CHIP.name}-AA-EmptyResponse"))
        }
        return ActiveAuthentication.verifyResponse(publicKey, digestAlgorithm, challenge, response)
    }

    /**
     * Vérifie que la puce répond sous la messagerie sécurisée courante : SELECT de DG1 puis
     * READ BINARY d'un octet, réponses au MAC vérifié. Null si elle répond, sinon le détail de
     * l'échec (étiquette [tag]). Une erreur du transport est relancée.
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

    /** Algorithme de signature ECDSA annoncé par l'ActiveAuthenticationInfo de DG14, s'il existe. */
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

    /** DG absent : non disponible s'il n'est pas dans le SOD, échec s'il y est (puce qui le retient). */
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

        /** Étape pendant laquelle la CA est menée (D20) : préfixe de ses codes d'erreur. */
        private val CA_STEP = Step.READ_DATA
        private const val SW_OK = 0x9000
        private const val INS_SELECT = 0xA4
        private const val INS_READ_BINARY = 0xB0
    }
}
