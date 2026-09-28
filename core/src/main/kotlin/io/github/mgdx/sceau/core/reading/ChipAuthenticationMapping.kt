package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.report.ChannelProtocol
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.ChipAuthenticationMethod
import io.github.mgdx.sceau.core.trust.TrustStore
import io.github.mgdx.sceau.core.verify.CardSecurityVerification
import io.github.mgdx.sceau.core.verify.PassiveAuthenticator
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.jcajce.provider.asymmetric.util.EC5Util
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.SecurityInfo
import java.math.BigInteger
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import javax.crypto.interfaces.DHPublicKey

/** Canal établi par [SecureChannel] : protocole, et éléments de PACE-CAM s'il a été mené. */
internal class EstablishedChannel(
    val protocol: ChannelProtocol,
    /** Non null si PACE a abouti avec le mapping CAM : la CA via DG14 n'est alors pas menée. */
    val cam: PaceCamEvidence?,
)

/** Éléments de PACE-CAM recueillis pendant l'établissement du canal. Aucune donnée personnelle. */
internal class PaceCamEvidence(
    /** CA_IC, déchiffré par JMRTD avec KSenc ; null si absent ou indéchiffrable. */
    val chipAuthenticationData: ByteArray?,
    /** PK_Map,IC : clé publique de mapping envoyée par la puce à la deuxième étape de PACE. */
    val mappingKey: PublicKey?,
    /** EF.CardSecurity, lu au MF sous la messagerie de PACE ; null s'il est absent ou illisible. */
    val cardSecurity: ByteArray?,
)

/**
 * Vérification de PACE-CAM, Chip Authentication Mapping (ICAO 9303-11 §4.4.3.5 ; décision D21) :
 *
 * 1. EF.CardSecurity est vérifié comme le SOD, par [PassiveAuthenticator.verifyCardSecurity]
 *    (signature CMS, chaîne DS → CSCA, pays, algorithmes admis) ;
 * 2. la clé statique de la puce, PK_IC, est prise dans ses `ChipAuthenticationPublicKeyInfo` ;
 * 3. la puce prouve détenir SK_IC si PK_Map,IC = KA(CA_IC, PK_IC, D_IC), c'est-à-dire
 *    CA_IC · PK_IC en ECDH (PK_IC^CA_IC mod p en DH), sur les paramètres de domaine de PK_IC,
 *    qui doivent être ceux de PK_Map,IC. Les algorithmes viennent des clés, jamais codés en dur :
 *    une clé ni EC ni DH donne `UNSUPPORTED_ALGORITHM`.
 *
 * Résultat : ligne `CHIP_AUTHENTICATION`, détail [CheckDetail.ChipAuthentication] (PACE-CAM)
 * si elle réussit. Tout élément manquant, une signature ou une chaîne fausse, un contrôle KA
 * faux donnent `FAILED` (codes `VERIFY_CHIP-CAM-…`). EF.CardSecurity dont l'émetteur est
 * inconnu du magasin donne `NOT_AVAILABLE` si la chaîne du SOD est elle aussi sans CSCA connu
 * (verdict « Émetteur inconnu », comme pour tout document d'un pays absent du magasin), et
 * `FAILED` sinon : un document dont le SOD remonte à un CSCA connu doit avoir un
 * EF.CardSecurity vérifiable.
 */
internal object ChipAuthenticationMapping {
    fun verify(
        evidence: PaceCamEvidence,
        trustStore: TrustStore,
        issuingState: String?,
        documentChain: CheckStatus,
    ): Check {
        val cardSecurity = evidence.cardSecurity ?: return failed("CARD_SECURITY_MISSING")
        val signed = PassiveAuthenticator(trustStore).verifyCardSecurity(cardSecurity, issuingState)
        when (signed.status) {
            CheckStatus.UNSUPPORTED_ALGORITHM -> {
                return Check(CheckId.CHIP_AUTHENTICATION, CheckStatus.UNSUPPORTED_ALGORITHM, signed.unsupported ?: CheckDetail.None)
            }

            CheckStatus.FAILED -> {
                return failed("CARD_SECURITY_${signed.reason ?: CardSecurityVerification.MALFORMED}")
            }

            else -> {}
        }
        val content = signed.content ?: return failed("CARD_SECURITY_${CardSecurityVerification.MALFORMED}")
        val chipKeys = chipAuthenticationKeys(content)
        if (chipKeys.isEmpty()) return failed("NO_CHIP_KEY")
        val caIc = evidence.chipAuthenticationData?.takeIf { it.isNotEmpty() } ?: return failed("NO_CHIP_AUTHENTICATION_DATA")
        val mappingKey = evidence.mappingKey ?: return failed("NO_MAPPING_KEY")

        when (val outcome = keyAgreementMatches(chipKeys, BigInteger(1, caIc), mappingKey)) {
            KeyAgreement.Match -> {}

            KeyAgreement.Mismatch -> {
                return failed("KEY_MISMATCH")
            }

            is KeyAgreement.Unsupported -> {
                return Check(
                    CheckId.CHIP_AUTHENTICATION,
                    CheckStatus.UNSUPPORTED_ALGORITHM,
                    CheckDetail.UnsupportedAlgorithm(outcome.algorithm),
                )
            }
        }
        val method = CheckDetail.ChipAuthentication(ChipAuthenticationMethod.PACE_CAM)
        return when {
            signed.status == CheckStatus.OK -> Check(CheckId.CHIP_AUTHENTICATION, CheckStatus.OK, method)
            documentChain == CheckStatus.NOT_AVAILABLE -> Check(CheckId.CHIP_AUTHENTICATION, CheckStatus.NOT_AVAILABLE, method)
            else -> failed("CARD_SECURITY_${signed.reason ?: CardSecurityVerification.UNKNOWN_ISSUER}")
        }
    }

    private sealed interface KeyAgreement {
        data object Match : KeyAgreement

        data object Mismatch : KeyAgreement

        class Unsupported(
            val algorithm: String,
        ) : KeyAgreement
    }

    /** Clés `ChipAuthenticationPublicKeyInfo` du `SET OF SecurityInfo` signé. */
    private fun chipAuthenticationKeys(content: ByteArray): List<PublicKey> =
        try {
            ASN1Set
                .getInstance(ASN1Primitive.fromByteArray(content))
                .mapNotNull { runCatching { SecurityInfo.getInstance(it) }.getOrNull() }
                .filterIsInstance<ChipAuthenticationPublicKeyInfo>()
                .mapNotNull { it.subjectPublicKey }
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * Vrai si l'une des clés de la puce vérifie PK_Map,IC = KA(CA_IC, PK_IC, D_IC). Une clé dont
     * le type ou les paramètres de domaine diffèrent de ceux de PK_Map,IC ne convient pas.
     */
    private fun keyAgreementMatches(
        chipKeys: List<PublicKey>,
        caIc: BigInteger,
        mappingKey: PublicKey,
    ): KeyAgreement {
        var unsupported: String? = null
        for (chipKey in chipKeys) {
            val matches =
                when {
                    chipKey is ECPublicKey && mappingKey is ECPublicKey -> ecMatches(chipKey, caIc, mappingKey)
                    chipKey is DHPublicKey && mappingKey is DHPublicKey -> dhMatches(chipKey, caIc, mappingKey)
                    chipKey is ECPublicKey || chipKey is DHPublicKey -> false
                    else -> null
                }
            when (matches) {
                true -> {
                    return KeyAgreement.Match
                }

                false -> {}

                null -> {
                    unsupported = chipKey.algorithm ?: "CAM"
                }
            }
        }
        return unsupported?.let { KeyAgreement.Unsupported(it) } ?: KeyAgreement.Mismatch
    }

    /** ECDH : CA_IC · PK_IC = PK_Map,IC, CA_IC dans [1, n − 1], mêmes paramètres de domaine. */
    private fun ecMatches(
        chipKey: ECPublicKey,
        caIc: BigInteger,
        mappingKey: ECPublicKey,
    ): Boolean {
        val chipDomain = EC5Util.convertSpec(chipKey.params)
        val mappingDomain = EC5Util.convertSpec(mappingKey.params)
        val sameDomain =
            chipDomain.curve.equals(mappingDomain.curve) &&
                chipDomain.g.equals(mappingDomain.g) &&
                chipDomain.n == mappingDomain.n
        if (!sameDomain || caIc.signum() <= 0 || caIc >= chipDomain.n) return false
        val chipPoint = EC5Util.convertPoint(chipKey.params, chipKey.w)
        val mappingPoint = EC5Util.convertPoint(mappingKey.params, mappingKey.w)
        if (!chipPoint.isValid || chipPoint.isInfinity) return false
        return chipPoint.multiply(caIc).normalize().equals(mappingPoint.normalize())
    }

    /** DH : PK_IC^CA_IC mod p = PK_Map,IC, mêmes p et g. */
    private fun dhMatches(
        chipKey: DHPublicKey,
        caIc: BigInteger,
        mappingKey: DHPublicKey,
    ): Boolean {
        val p = chipKey.params.p
        if (p != mappingKey.params.p || chipKey.params.g != mappingKey.params.g) return false
        if (caIc.signum() <= 0 || caIc >= p) return false
        return chipKey.y.modPow(caIc, p) == mappingKey.y
    }

    private fun failed(reason: String) =
        Check(CheckId.CHIP_AUTHENTICATION, CheckStatus.FAILED, CheckDetail.Error("${Step.VERIFY_CHIP.name}-CAM-$reason"))
}
