package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustStore
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder

/** Outcome of the search for a DS → (links) → self-signed CSCA chain in the store. */
internal sealed interface ChainOutcome {
    /** Complete chain: [links] from the closest to the DS to the closest to [root]. */
    class Found(
        val links: List<TrustAnchor>,
        val root: TrustAnchor,
    ) : ChainOutcome

    /** An issuer in the store matches, but the signature does not verify. */
    class BadSignature(
        val issuer: TrustAnchor,
    ) : ChainOutcome

    class Unsupported(
        val algorithm: String,
    ) : ChainOutcome

    /** No certificate in the store matches: unknown issuer. */
    data object NotFound : ChainOutcome

    /** Search abandoned, verification budget exhausted: hostile store (audit V15). */
    data object BudgetExceeded : ChainOutcome
}

/**
 * Builds the chain from a certificate to a self-signed CSCA in the store, traversing
 * link certificates (subject of the new CSCA, signed by the old key).
 *
 * Calendar validity of CSCAs and links is not required, since a document remains valid
 * up to ten years after its CSCA expires.
 *
 * Issuer profile (audit V8): a certificate in the store is only accepted as an issuer if it
 * can sign certificates ([canIssueCertificates]): `basicConstraints` with cA=TRUE and
 * `keyUsage` with keyCertSign, **when these extensions are present**. Their absence is
 * tolerated: measured on the 590 embedded anchors (ANTS and BSI Master List; the 7 national
 * publications added later, D26, are compliant), all self-signed CSCAs are compliant; only
 * some link certificates are not (one without `basicConstraints`, one without `keyUsage`,
 * seven with cA=FALSE — Cameroon, Italy, Portugal, Turkey ×2, Luxembourg ×2). These seven
 * links are discarded, with no effect: each has the key and SKI of a compliant self-signed
 * CSCA in the store, which anchors the chain directly. A DS (keyUsage digitalSignature only,
 * without keyCertSign) slipped into an imported Master List therefore can no longer serve as
 * an issuer.
 *
 * Bounded cost (audit V15): each certificate in the store is explored at most once per
 * search (global set, not per path), and beyond [MAX_SIGNATURE_CHECKS] signature
 * verifications the search stops with [ChainOutcome.BudgetExceeded]. Otherwise, cross
 * certificates sharing an AKI (hostile imported Master List) cost up to k^8 verifications.
 * A real chain needs a few.
 *
 * Candidate order (audit V31): possible issuers are examined source by source (ANTS,
 * national publications, embedded Master List, then imports), and the search stops at the
 * first chain found. Without this, an imported Master List containing dozens of decoys with
 * the SKI of an embedded CSCA exhausted the budget before the real CSCA, already verified,
 * was accepted: "Failure" on every authentic document of the targeted country.
 *
 * One instance per search: [build] is not reentrant.
 */
internal class CertificateChains(
    private val store: TrustStore,
) {
    private sealed interface SignatureCheck {
        data object Valid : SignatureCheck

        data object Invalid : SignatureCheck

        class Unsupported(
            val algorithm: String,
        ) : SignatureCheck
    }

    /** Fingerprints of the store certificates already explored during the current search. */
    private val explored = mutableSetOf<String>()
    private var signatureChecks = 0

    /** Thrown when the verification budget is exhausted; never escapes [build]. */
    private class BudgetExhausted : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    fun build(certificate: X509CertificateHolder): ChainOutcome {
        explored.clear()
        signatureChecks = 0
        return try {
            search(certificate, depth = 0)
        } catch (e: BudgetExhausted) {
            ChainOutcome.BudgetExceeded
        }
    }

    private fun search(
        certificate: X509CertificateHolder,
        depth: Int,
    ): ChainOutcome {
        val candidates = issuerCandidates(certificate).filter { Crypto.fingerprint(it.certificate) !in explored }
        if (candidates.isEmpty()) return ChainOutcome.NotFound

        // Audit V31: candidates examined source by source, in [TrustSource] order (ANTS,
        // national, embedded Master List, imports); the search stops as soon as a chain
        // completes. Imported decoys therefore do not consume the budget of a chain that an
        // embedded source resolves.
        var failure: ChainOutcome = ChainOutcome.NotFound
        for (group in candidates.groupBy { it.source }.toSortedMap().values) {
            when (val outcome = searchGroup(certificate, group, depth)) {
                is ChainOutcome.Found -> return outcome
                else -> failure = worst(failure, outcome)
            }
        }
        return failure
    }

    /**
     * Search among the candidates [group] of a single source: a self-signed CSCA that verifies
     * [certificate] ends the search immediately (audit V31); otherwise the verified links are
     * explored in turn.
     */
    private fun searchGroup(
        certificate: X509CertificateHolder,
        group: List<TrustAnchor>,
        depth: Int,
    ): ChainOutcome {
        var failure: ChainOutcome = ChainOutcome.NotFound
        val verified = mutableListOf<Pair<TrustAnchor, X509CertificateHolder>>()
        for (candidate in group) {
            val holder = JcaX509CertificateHolder(candidate.certificate)
            when (val check = verifySignature(certificate, holder)) {
                SignatureCheck.Valid -> {
                    verified += candidate to holder
                    if (holder.subject != holder.issuer) continue
                    when (val self = verifySignature(holder, holder)) {
                        SignatureCheck.Valid -> return ChainOutcome.Found(emptyList(), candidate)

                        SignatureCheck.Invalid -> Unit

                        // Self-signed CSCA with a rejected algorithm (audit V9): failure, not "unknown issuer".
                        is SignatureCheck.Unsupported -> failure = worst(failure, ChainOutcome.Unsupported(self.algorithm))
                    }
                }

                SignatureCheck.Invalid -> {
                    failure = worst(failure, ChainOutcome.BadSignature(candidate))
                }

                is SignatureCheck.Unsupported -> {
                    failure = worst(failure, ChainOutcome.Unsupported(check.algorithm))
                }
            }
        }
        if (depth >= MAX_DEPTH) return failure

        for ((link, holder) in verified) {
            // Already explored (via this path or another): its outcome is known, no need to revisit.
            if (!explored.add(Crypto.fingerprint(link.certificate))) continue
            when (val next = search(holder, depth + 1)) {
                is ChainOutcome.Found -> return ChainOutcome.Found(listOf(link) + next.links, next.root)
                else -> failure = worst(failure, next)
            }
        }
        return failure
    }

    /**
     * Issuer candidates: by Authority Key Identifier, then by issuer name. A candidate found
     * by name but whose key identifier differs from the AKI is discarded (same CSCA name,
     * another key generation: it is not the issuer).
     */
    private fun issuerCandidates(certificate: X509CertificateHolder): List<TrustAnchor> {
        val aki = AuthorityKeyIdentifier.fromExtensions(certificate.extensions)?.keyIdentifierOctets
        val byKeyId = aki?.let { store.findBySubjectKeyId(it) }.orEmpty()
        val bySubject =
            store.findBySubject(Crypto.principal(certificate.issuer)).filter { anchor ->
                val ski = subjectKeyId(anchor)
                aki == null || ski == null || ski.contentEquals(aki)
            }
        // Stable sort by source before deduplication: a certificate both embedded and imported
        // keeps its embedded origin (audit V31).
        return (byKeyId + bySubject)
            .sortedBy { it.source }
            .distinctBy { Crypto.fingerprint(it.certificate) }
            .filter { canIssueCertificates(JcaX509CertificateHolder(it.certificate)) }
    }

    private fun subjectKeyId(anchor: TrustAnchor): ByteArray? =
        runCatching {
            SubjectKeyIdentifier.fromExtensions(JcaX509CertificateHolder(anchor.certificate).extensions)?.keyIdentifier
        }.getOrNull()

    private fun verifySignature(
        certificate: X509CertificateHolder,
        issuer: X509CertificateHolder,
    ): SignatureCheck {
        if (++signatureChecks > MAX_SIGNATURE_CHECKS) throw BudgetExhausted()
        // Audit V9: broken hash (MD5, RIPEMD-128…) or RSA key under 1024 bits rejected.
        if (Crypto.isWeakAlgorithm(certificate.signatureAlgorithm)) {
            return SignatureCheck.Unsupported(Crypto.algorithmName(certificate.signatureAlgorithm.algorithm))
        }
        // Audit V30: out-of-bounds issuer key, read encoded, rejected before any reconstruction.
        Crypto.unsupportedKey(issuer.subjectPublicKeyInfo)?.let { return SignatureCheck.Unsupported(it) }
        return try {
            val verifierProvider = JcaContentVerifierProviderBuilder().setProvider(Crypto.provider).build(issuer)
            if (certificate.isSignatureValid(verifierProvider)) SignatureCheck.Valid else SignatureCheck.Invalid
        } catch (e: Exception) {
            if (Crypto.isUnsupportedAlgorithm(e)) {
                SignatureCheck.Unsupported(Crypto.algorithmName(certificate.signatureAlgorithm.algorithm))
            } else {
                SignatureCheck.Invalid
            }
        }
    }

    private fun worst(
        a: ChainOutcome,
        b: ChainOutcome,
    ): ChainOutcome = if (rank(b) > rank(a)) b else a

    private fun rank(outcome: ChainOutcome): Int =
        when (outcome) {
            ChainOutcome.NotFound -> 0
            is ChainOutcome.Unsupported -> 1
            is ChainOutcome.BadSignature -> 2
            is ChainOutcome.Found -> 3
            ChainOutcome.BudgetExceeded -> 4
        }

    companion object {
        /** Maximum depth of traversed links: well beyond real chains. */
        private const val MAX_DEPTH = 8

        /** Signature verifications per search: a real chain needs a few. */
        const val MAX_SIGNATURE_CHECKS = 64

        /** True if [holder] can issue certificates (see the class rule, audit V8). */
        fun canIssueCertificates(holder: X509CertificateHolder): Boolean =
            runCatching {
                val basicConstraints = BasicConstraints.fromExtensions(holder.extensions)
                val keyUsage = KeyUsage.fromExtensions(holder.extensions)
                (basicConstraints == null || basicConstraints.isCA) && (keyUsage == null || keyUsage.hasUsages(KeyUsage.keyCertSign))
            }.getOrDefault(false)
    }
}
