package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
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

/**
 * Étape VERIFY_CHIP (SPEC §6.1, étapes 6 et 7) : Chip Authentication si DG14 annonce une clé,
 * puis Active Authentication si DG15 est présent.
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
     * Chip Authentication. Après succès, JMRTD remplace la messagerie sécurisée par celle
     * dérivée de la clé de DG14 : toute commande ultérieure (dont l'AA) passe par ce canal.
     * L'échange ne prouve rien tant que la puce n'a pas répondu sous le nouveau canal : une
     * lecture courte de DG1 le confirme (le MAC de la réponse est vérifié).
     */
    fun chipAuthentication(dg14Bytes: ByteArray?): Check {
        if (dg14Bytes == null) return check(CheckId.CHIP_AUTHENTICATION, CheckStatus.NOT_AVAILABLE)
        val dg14 =
            try {
                DG14File(dg14Bytes.inputStream())
            } catch (e: Exception) {
                return unsupported(CheckId.CHIP_AUTHENTICATION, "DG14")
            }
        val publicKeyInfo =
            dg14.securityInfos
                .orEmpty()
                .filterIsInstance<ChipAuthenticationPublicKeyInfo>()
                .firstOrNull()
                ?: return check(CheckId.CHIP_AUTHENTICATION, CheckStatus.NOT_AVAILABLE)
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
            if (isUnsupportedAlgorithm(e)) {
                return unsupported(CheckId.CHIP_AUTHENTICATION, protocolOid ?: publicKeyInfo.objectIdentifier ?: "CA")
            }
            return failed(CheckId.CHIP_AUTHENTICATION, "CA", e)
        }
        return confirmNewChannel()
    }

    /** Active Authentication : challenge de 8 octets tiré de [random], réponse vérifiée par le lot B. */
    fun activeAuthentication(
        dg15Bytes: ByteArray?,
        dg14Bytes: ByteArray?,
    ): Check {
        if (dg15Bytes == null) return check(CheckId.ACTIVE_AUTHENTICATION, CheckStatus.NOT_AVAILABLE)
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
                return failed(CheckId.ACTIVE_AUTHENTICATION, "AA", e)
            }
        // JMRTD renvoie les données reçues quel que soit le SW : une réponse vide est un refus.
        if (response == null || response.isEmpty()) {
            return check(CheckId.ACTIVE_AUTHENTICATION, CheckStatus.FAILED, CheckDetail.Error("${Step.VERIFY_CHIP.name}-AA-EmptyResponse"))
        }
        return ActiveAuthentication.verifyResponse(publicKey, digestAlgorithm, challenge, response)
    }

    private fun confirmNewChannel(): Check {
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
                    return failed(CheckId.CHIP_AUTHENTICATION, "CA_CONFIRM", e)
                }
            if (sw != SW_OK) {
                return check(CheckId.CHIP_AUTHENTICATION, CheckStatus.FAILED, CheckDetail.Error(technicalCodeForSw("CA_CONFIRM", sw)))
            }
        }
        return check(CheckId.CHIP_AUTHENTICATION, CheckStatus.OK)
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

    private fun unsupported(
        id: CheckId,
        algorithm: String,
    ) = check(id, CheckStatus.UNSUPPORTED_ALGORITHM, CheckDetail.UnsupportedAlgorithm(algorithm))

    private fun failed(
        id: CheckId,
        tag: String,
        error: Throwable,
    ) = check(id, CheckStatus.FAILED, CheckDetail.Error(technicalCode(Step.VERIFY_CHIP.name, StepFailure(tag, error))))

    private fun technicalCodeForSw(
        tag: String,
        sw: Int,
    ) = "${Step.VERIFY_CHIP.name}-$tag-${String.format(java.util.Locale.ROOT, "%04X", sw)}"

    private fun fidBytes(fid: Short) = byteArrayOf((fid.toInt() shr 8).toByte(), fid.toByte())

    companion object {
        const val AA_CHALLENGE_LENGTH = 8
        private const val SW_OK = 0x9000
        private const val INS_SELECT = 0xA4
        private const val INS_READ_BINARY = 0xB0
    }
}
