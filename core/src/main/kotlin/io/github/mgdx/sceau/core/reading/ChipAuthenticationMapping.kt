package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.report.ChainInfo
import io.github.mgdx.sceau.core.report.ChannelProtocol
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.ChipAuthenticationMethod
import io.github.mgdx.sceau.core.trust.TrustStore
import io.github.mgdx.sceau.core.verify.CardSecurityVerification
import io.github.mgdx.sceau.core.verify.PassiveAuthenticator
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.jcajce.provider.asymmetric.util.EC5Util
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.SecurityInfo
import java.math.BigInteger
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import javax.crypto.interfaces.DHPublicKey
import io.github.mgdx.sceau.core.verify.Crypto as KeyBounds

/** Channel established by [SecureChannel]: protocol, and PACE-CAM evidence if it was performed. */
internal class EstablishedChannel(
    val protocol: ChannelProtocol,
    /** Non-null if PACE succeeded with the CAM mapping: CA via DG14 is then not performed. */
    val cam: PaceCamEvidence?,
)

/** PACE-CAM evidence collected while establishing the channel. No personal data. */
internal class PaceCamEvidence(
    /** CA_IC, decrypted by JMRTD with KSenc; null if absent or undecryptable. */
    val chipAuthenticationData: ByteArray?,
    /** PK_Map,IC: mapping public key sent by the chip in the second step of PACE. */
    val mappingKey: PublicKey?,
    /** EF.CardSecurity, read at the MF under PACE secure messaging; null if absent or unreadable. */
    val cardSecurity: ByteArray?,
)

/**
 * Verification of PACE-CAM, Chip Authentication Mapping (ICAO 9303-11 §4.4.3.5; decision D21):
 *
 * 1. EF.CardSecurity is verified like the SOD, by [PassiveAuthenticator.verifyCardSecurity]
 *    (CMS signature, DS → CSCA chain, country, accepted algorithms);
 * 2. the chip's static key, PK_IC, is taken from its `ChipAuthenticationPublicKeyInfo`;
 * 3. the chip proves it holds SK_IC if PK_Map,IC = KA(CA_IC, PK_IC, D_IC), that is
 *    CA_IC · PK_IC in ECDH (PK_IC^CA_IC mod p in DH), over the domain parameters of PK_IC,
 *    which must be those of PK_Map,IC. Algorithms come from the keys, never hard-coded:
 *    a key that is neither EC nor DH gives `UNSUPPORTED_ALGORITHM`.
 *
 * Result: `CHIP_AUTHENTICATION` line, detail [CheckDetail.ChipAuthentication] (PACE-CAM)
 * if it succeeds. Any missing element, a wrong signature or chain, or a wrong KA check give
 * `FAILED` (codes `VERIFY_CHIP-CAM-…`). An EF.CardSecurity whose issuer is unknown to the
 * store gives `NOT_AVAILABLE` if the SOD chain also has no known CSCA ("Unknown issuer"
 * verdict, as for any document from a country absent from the store), and `FAILED`
 * otherwise: a document whose SOD chains up to a known CSCA must have a verifiable
 * EF.CardSecurity.
 *
 * Audit V17 (decision D40): EF.CardSecurity and the SOD are issued by the same State for the same
 * document; the EF.CardSecurity chain must end at the same CSCA as the SOD chain (same
 * certificate, or same public key), otherwise `FAILED` (`VERIFY_CHIP-CAM-CARD_SECURITY_ANCHOR`).
 * Without this constraint, a clone could anchor its own chip key on a CSCA imported by the
 * user, while the copied SOD remains anchored on a built-in CSCA.
 */
internal object ChipAuthenticationMapping {
    /** `CHIP_AUTHENTICATION` line and EF.CardSecurity chain, passed up into the report (audit V17). */
    class Result(
        val check: Check,
        val cardSecurityChain: ChainInfo?,
    )

    /**
     * [documentChain]: `CERTIFICATE_CHAIN` line of the SOD; [documentChainInfo]: its chain, whose
     * CSCA must be that of EF.CardSecurity.
     */
    fun verify(
        evidence: PaceCamEvidence,
        trustStore: TrustStore,
        issuingState: String?,
        documentChain: CheckStatus,
        documentChainInfo: ChainInfo?,
    ): Result {
        val cardSecurity = evidence.cardSecurity ?: return Result(failed("CARD_SECURITY_MISSING"), null)
        val signed = PassiveAuthenticator(trustStore).verifyCardSecurity(cardSecurity, issuingState)
        return Result(check(evidence, signed, trustStore, documentChain, documentChainInfo), signed.chain)
    }

    private fun check(
        evidence: PaceCamEvidence,
        signed: CardSecurityVerification,
        trustStore: TrustStore,
        documentChain: CheckStatus,
        documentChainInfo: ChainInfo?,
    ): Check {
        when (signed.status) {
            CheckStatus.UNSUPPORTED_ALGORITHM -> {
                return Check(CheckId.CHIP_AUTHENTICATION, CheckStatus.UNSUPPORTED_ALGORITHM, signed.unsupported ?: CheckDetail.None)
            }

            CheckStatus.FAILED -> {
                return failed("CARD_SECURITY_${signed.reason ?: CardSecurityVerification.MALFORMED}")
            }

            CheckStatus.OK -> {
                if (!sameAnchor(signed.chain, documentChainInfo, trustStore)) return failed(CARD_SECURITY_ANCHOR)
            }

            CheckStatus.NOT_AVAILABLE -> {}
        }
        val content = signed.content ?: return failed("CARD_SECURITY_${CardSecurityVerification.MALFORMED}")
        val chipKeys = chipAuthenticationKeys(content)
        if (chipKeys.keys.isEmpty()) return chipKeys.unsupported?.let(::unsupported) ?: failed("NO_CHIP_KEY")
        val caIc = evidence.chipAuthenticationData?.takeIf { it.isNotEmpty() } ?: return failed("NO_CHIP_AUTHENTICATION_DATA")
        val mappingKey = evidence.mappingKey ?: return failed("NO_MAPPING_KEY")
        // Audit V30: out-of-bounds mapping key rejected before any computation.
        KeyBounds.unsupportedKey(mappingKey)?.let { return unsupported(it) }

        when (val outcome = keyAgreementMatches(chipKeys, BigInteger(1, caIc), mappingKey)) {
            KeyAgreement.Match -> {}

            KeyAgreement.Mismatch -> {
                return failed("KEY_MISMATCH")
            }

            is KeyAgreement.Unsupported -> {
                return unsupported(outcome.algorithm)
            }
        }
        val method = CheckDetail.ChipAuthentication(ChipAuthenticationMethod.PACE_CAM)
        return when {
            signed.status == CheckStatus.OK -> Check(CheckId.CHIP_AUTHENTICATION, CheckStatus.OK, method)
            documentChain == CheckStatus.NOT_AVAILABLE -> Check(CheckId.CHIP_AUTHENTICATION, CheckStatus.NOT_AVAILABLE, method)
            else -> failed("CARD_SECURITY_${signed.reason ?: CardSecurityVerification.UNKNOWN_ISSUER}")
        }
    }

    /**
     * True if the [cardSecurity] and [document] chains end at the same CSCA: same fingerprint,
     * or same public key (a single CSCA may appear under two certificates, for example reissued).
     * False if either has no CSCA.
     */
    private fun sameAnchor(
        cardSecurity: ChainInfo?,
        document: ChainInfo?,
        trustStore: TrustStore,
    ): Boolean {
        val first = cardSecurity?.cscaSha256 ?: return false
        val second = document?.cscaSha256 ?: return false
        if (first == second) return true
        val firstKey = anchorKey(first, trustStore) ?: return false
        val secondKey = anchorKey(second, trustStore) ?: return false
        return firstKey.contentEquals(secondKey)
    }

    private fun anchorKey(
        sha256: String,
        trustStore: TrustStore,
    ): ByteArray? =
        trustStore.anchors
            .firstOrNull { it.sha256 == sha256 }
            ?.certificate
            ?.publicKey
            ?.encoded

    private const val CARD_SECURITY_ANCHOR = "CARD_SECURITY_ANCHOR"

    private sealed interface KeyAgreement {
        data object Match : KeyAgreement

        data object Mismatch : KeyAgreement

        class Unsupported(
            val algorithm: String,
        ) : KeyAgreement
    }

    /**
     * Chip keys: [keys] rebuilt by JMRTD; [unsupported] describes the last key discarded
     * because its size is out of bounds (audit V30), null if there is none.
     */
    private class ChipKeys(
        val keys: List<PublicKey>,
        val unsupported: String?,
    )

    /**
     * `ChipAuthenticationPublicKeyInfo` keys of the signed `SET OF SecurityInfo`. Audit V30: the size
     * of each key is checked on its encoded form, before JMRTD rebuilds it (rebuilding validates
     * the key, at a cost that grows with its size).
     */
    private fun chipAuthenticationKeys(content: ByteArray): ChipKeys {
        var unsupported: String? = null
        val keys =
            try {
                ASN1Set
                    .getInstance(ASN1Primitive.fromByteArray(content))
                    .filter { element -> oversizedKey(element)?.also { unsupported = it } == null }
                    .mapNotNull { runCatching { SecurityInfo.getInstance(it) }.getOrNull() }
                    .filterIsInstance<ChipAuthenticationPublicKeyInfo>()
                    .mapNotNull { it.subjectPublicKey }
            } catch (e: Exception) {
                emptyList()
            }
        return ChipKeys(keys, unsupported)
    }

    /** Out-of-bounds key size of an encoded `ChipAuthenticationPublicKeyInfo`, or null. */
    private fun oversizedKey(element: ASN1Encodable): String? =
        runCatching {
            val sequence = ASN1Sequence.getInstance(element)
            val protocol = ASN1ObjectIdentifier.getInstance(sequence.getObjectAt(0)).id
            if (SecurityInfoProtocols.chipAuthenticationKey(listOf(protocol)) == null) return null
            KeyBounds.unsupportedKey(SubjectPublicKeyInfo.getInstance(sequence.getObjectAt(1)))
        }.getOrNull()

    /**
     * True if one of the chip keys satisfies PK_Map,IC = KA(CA_IC, PK_IC, D_IC). A key whose
     * type or domain parameters differ from those of PK_Map,IC does not qualify. An
     * out-of-bounds key (audit V30) is not tried.
     */
    private fun keyAgreementMatches(
        chipKeys: ChipKeys,
        caIc: BigInteger,
        mappingKey: PublicKey,
    ): KeyAgreement {
        var unsupported: String? = chipKeys.unsupported
        for (chipKey in chipKeys.keys) {
            val oversized = KeyBounds.unsupportedKey(chipKey)
            val matches =
                when {
                    oversized != null -> null
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
                    unsupported = oversized ?: chipKey.algorithm ?: "CAM"
                }
            }
        }
        return unsupported?.let { KeyAgreement.Unsupported(it) } ?: KeyAgreement.Mismatch
    }

    /** ECDH: CA_IC · PK_IC = PK_Map,IC, CA_IC in [1, n − 1], same domain parameters. */
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

    /** DH: PK_IC^CA_IC mod p = PK_Map,IC, same p and g. */
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

    private fun unsupported(algorithm: String) =
        Check(CheckId.CHIP_AUTHENTICATION, CheckStatus.UNSUPPORTED_ALGORITHM, CheckDetail.UnsupportedAlgorithm(algorithm))

    private fun failed(reason: String) =
        Check(CheckId.CHIP_AUTHENTICATION, CheckStatus.FAILED, CheckDetail.Error("${Step.VERIFY_CHIP.name}-CAM-$reason"))
}
