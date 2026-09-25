package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA224Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA384Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.digests.SHA512tDigest
import org.bouncycastle.crypto.digests.WhirlpoolDigest
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.ISO9796d2Signer
import org.bouncycastle.crypto.signers.ISOTrailers
import java.math.BigInteger
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

/** Vérification de la réponse de la puce au challenge d'Active Authentication. */
internal object ActiveAuthenticator {
    /**
     * Algorithmes de hachage essayés, dans l'ordre, pour une clé EC dont DG14 n'annonce
     * pas d'`ActiveAuthenticationInfo` (cas non prévu par ICAO 9303, rencontré en pratique).
     */
    private val EC_FALLBACK_DIGESTS = listOf("SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512")

    private const val TRAILER_IMPLICIT = 0xBC
    private const val TRAILER_EXPLICIT_MARK = 0xCC
    private const val HEADER_MASK = 0xC0
    private const val HEADER_SCHEME_1 = 0x40
    private const val BYTE_BITS = 8

    /**
     * Trailer explicite ISO/IEC 10118-3 → fonction de hachage (ISO/IEC 9796-2). RIPEMD-128 n'y
     * figure pas : fonction cassée, refusée comme algorithme non pris en charge (audit V9).
     */
    private val TRAILER_DIGESTS: Map<Int, () -> Digest> =
        mapOf(
            ISOTrailers.TRAILER_SHA1 to { SHA1Digest() },
            ISOTrailers.TRAILER_SHA224 to { SHA224Digest() },
            ISOTrailers.TRAILER_SHA256 to { SHA256Digest() },
            ISOTrailers.TRAILER_SHA384 to { SHA384Digest() },
            ISOTrailers.TRAILER_SHA512 to { SHA512Digest() },
            ISOTrailers.TRAILER_SHA512_224 to { SHA512tDigest(224) },
            ISOTrailers.TRAILER_SHA512_256 to { SHA512tDigest(256) },
            ISOTrailers.TRAILER_RIPEMD160 to { RIPEMD160Digest() },
            ISOTrailers.TRAILER_WHIRLPOOL to { WhirlpoolDigest() },
        )

    fun verify(
        publicKey: PublicKey,
        digestAlgorithm: String?,
        challenge: ByteArray,
        response: ByteArray,
    ): Check =
        try {
            when {
                publicKey is RSAPublicKey -> {
                    // Audit V9 : clé RSA de moins de 1024 bits refusée.
                    Crypto.weakKey(publicKey)?.let(::unsupported) ?: verifyRsa(publicKey, challenge, response)
                }

                digestAlgorithm != null && Crypto.isWeakDigestName(digestAlgorithm) -> {
                    unsupported(digestAlgorithm)
                }

                publicKey is ECPublicKey || publicKey.algorithm in setOf("EC", "ECDSA") -> {
                    verifyEc(publicKey, digestAlgorithm, challenge, response)
                }

                else -> {
                    unsupported(publicKey.algorithm ?: "?")
                }
            }
        } catch (e: Exception) {
            if (Crypto.isUnsupportedAlgorithm(e)) unsupported(digestAlgorithm ?: publicKey.algorithm ?: "?") else failed()
        }

    // --- RSA : ISO/IEC 9796-2 schéma 1, récupération partielle du message ---------------

    private fun verifyRsa(
        key: RSAPublicKey,
        challenge: ByteArray,
        response: ByteArray,
    ): Check {
        val keyParameters = RSAKeyParameters(false, key.modulus, key.publicExponent)
        if (BigInteger(1, response) >= key.modulus) return failed()

        // Déchiffrement brut pour lire l'en-tête et le trailer, qui désigne le hachage.
        val engine = RSAEngine().apply { init(false, keyParameters) }
        val raw = BigInteger(1, engine.processBlock(response, 0, response.size))
        val block = raw.toByteArray().let { if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
        val blockLength = (key.modulus.bitLength() + BYTE_BITS - 1) / BYTE_BITS
        if (block.size != blockLength || block[0].toInt() and HEADER_MASK != HEADER_SCHEME_1) return failed()

        val last = block[block.size - 1].toInt() and 0xFF
        val (digest, implicit) =
            when {
                last == TRAILER_IMPLICIT -> {
                    SHA1Digest() to true
                }

                last == TRAILER_EXPLICIT_MARK -> {
                    val trailer = ((block[block.size - 2].toInt() and 0xFF) shl BYTE_BITS) or last
                    val factory = TRAILER_DIGESTS[trailer] ?: return unsupported("ISO9796-2 trailer %04X".format(trailer))
                    factory() to false
                }

                else -> {
                    return failed()
                }
            }

        val signer = ISO9796d2Signer(RSAEngine(), digest, implicit)
        signer.init(false, keyParameters)
        signer.updateWithRecoveredMessage(response)
        signer.update(challenge, 0, challenge.size)
        val algorithm = "${digest.algorithmName.replace("-", "")}withRSA/ISO9796-2"
        return if (signer.verifySignature(response)) ok(algorithm) else failed()
    }

    // --- ECDSA : réponse brute r‖s ------------------------------------------------------

    private fun verifyEc(
        key: PublicKey,
        digestAlgorithm: String?,
        challenge: ByteArray,
        response: ByteArray,
    ): Check {
        if (response.isEmpty() || response.size % 2 != 0) return failed()
        val half = response.size / 2
        val der =
            DERSequence(
                arrayOf(
                    ASN1Integer(BigInteger(1, response.copyOfRange(0, half))),
                    ASN1Integer(BigInteger(1, response.copyOfRange(half, response.size))),
                ),
            ).encoded

        val digests = digestAlgorithm?.let { listOf(it) } ?: EC_FALLBACK_DIGESTS
        for (digest in digests) {
            val algorithm = "${digest.replace("-", "")}withECDSA"
            val signature = Signature.getInstance(algorithm, Crypto.provider)
            signature.initVerify(key)
            signature.update(challenge)
            if (signature.verify(der)) return ok(algorithm)
        }
        return failed()
    }

    private fun ok(algorithm: String) = Check(CheckId.ACTIVE_AUTHENTICATION, CheckStatus.OK, CheckDetail.Signature(algorithm))

    private fun failed() = Check(CheckId.ACTIVE_AUTHENTICATION, CheckStatus.FAILED)

    private fun unsupported(algorithm: String) =
        Check(CheckId.ACTIVE_AUTHENTICATION, CheckStatus.UNSUPPORTED_ALGORITHM, CheckDetail.UnsupportedAlgorithm(algorithm))
}
