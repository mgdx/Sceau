package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustStore
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder

/** Issue de la recherche d'une chaîne DS → (liens) → CSCA auto-signé du magasin. */
internal sealed interface ChainOutcome {
    /** Chaîne complète : [links] du plus proche du DS au plus proche de [root]. */
    class Found(
        val links: List<TrustAnchor>,
        val root: TrustAnchor,
    ) : ChainOutcome

    /** Un émetteur du magasin correspond, mais la signature ne se vérifie pas. */
    class BadSignature(
        val issuer: TrustAnchor,
    ) : ChainOutcome

    class Unsupported(
        val algorithm: String,
    ) : ChainOutcome

    /** Aucun certificat du magasin ne correspond : émetteur inconnu. */
    data object NotFound : ChainOutcome

    /** Recherche abandonnée, budget de vérifications épuisé : magasin hostile (audit V15). */
    data object BudgetExceeded : ChainOutcome
}

/**
 * Construit la chaîne d'un certificat vers un CSCA auto-signé du magasin, en traversant
 * les certificats de lien (sujet du nouveau CSCA, signés par l'ancienne clé).
 *
 * La validité calendaire des CSCA et des liens n'est pas exigée, un document restant valide
 * jusqu'à dix ans après l'expiration de son CSCA.
 *
 * Profil des émetteurs (audit V8) : un certificat du magasin n'est retenu comme émetteur que
 * s'il peut signer des certificats ([canIssueCertificates]) : `basicConstraints` avec cA=TRUE
 * et `keyUsage` avec keyCertSign, **quand ces extensions sont présentes**. Leur absence est
 * tolérée : mesuré sur les 590 ancres embarquées (ANTS et Master List BSI), tous les CSCA
 * auto-signés sont conformes ; seuls des certificats de lien ne le sont pas (un sans
 * `basicConstraints`, un sans `keyUsage`, sept avec cA=FALSE — Cameroun, Italie, Portugal,
 * Turquie ×2, Luxembourg ×2). Ces sept liens sont écartés, sans effet : chacun a pour clé et
 * SKI ceux d'un CSCA auto-signé conforme du magasin, qui ancre la chaîne directement. Un DS
 * (keyUsage digitalSignature seul, sans keyCertSign) glissé dans une Master List importée ne
 * peut donc plus servir d'émetteur.
 *
 * Coût borné (audit V15) : chaque certificat du magasin est exploré au plus une fois par
 * recherche (ensemble global, et non par chemin), et au-delà de [MAX_SIGNATURE_CHECKS]
 * vérifications de signature la recherche s'arrête sur [ChainOutcome.BudgetExceeded]. Des
 * certificats croisés partageant un AKI (Master List importée hostile) coûtaient sinon
 * jusqu'à k^8 vérifications. Une chaîne réelle en demande quelques-unes.
 *
 * Une instance par recherche : [build] n'est pas réentrant.
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

    /** Empreintes des certificats du magasin déjà explorés pendant la recherche en cours. */
    private val explored = mutableSetOf<String>()
    private var signatureChecks = 0

    /** Levée quand le budget de vérifications est épuisé ; ne sort jamais de [build]. */
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

        var failure: ChainOutcome = ChainOutcome.NotFound
        val verified = mutableListOf<Pair<TrustAnchor, X509CertificateHolder>>()
        for (candidate in candidates) {
            val holder = JcaX509CertificateHolder(candidate.certificate)
            when (val check = verifySignature(certificate, holder)) {
                SignatureCheck.Valid -> verified += candidate to holder
                SignatureCheck.Invalid -> failure = worst(failure, ChainOutcome.BadSignature(candidate))
                is SignatureCheck.Unsupported -> failure = worst(failure, ChainOutcome.Unsupported(check.algorithm))
            }
        }

        for ((candidate, holder) in verified) {
            if (holder.subject != holder.issuer) continue
            when (val self = verifySignature(holder, holder)) {
                SignatureCheck.Valid -> return ChainOutcome.Found(emptyList(), candidate)

                SignatureCheck.Invalid -> Unit

                // CSCA auto-signé avec un algorithme refusé (audit V9) : échec, pas « émetteur inconnu ».
                is SignatureCheck.Unsupported -> failure = worst(failure, ChainOutcome.Unsupported(self.algorithm))
            }
        }
        if (depth >= MAX_DEPTH) return failure

        for ((link, holder) in verified) {
            // Déjà exploré (par ce chemin ou un autre) : son issue est connue, inutile d'y revenir.
            if (!explored.add(Crypto.fingerprint(link.certificate))) continue
            when (val next = search(holder, depth + 1)) {
                is ChainOutcome.Found -> return ChainOutcome.Found(listOf(link) + next.links, next.root)
                else -> failure = worst(failure, next)
            }
        }
        return failure
    }

    /**
     * Candidats émetteurs : par Authority Key Identifier, puis par nom d'émetteur. Un
     * candidat trouvé par le nom mais dont l'identifiant de clé diffère de l'AKI est écarté
     * (même nom de CSCA, autre génération de clé : ce n'est pas l'émetteur).
     */
    private fun issuerCandidates(certificate: X509CertificateHolder): List<TrustAnchor> {
        val aki = AuthorityKeyIdentifier.fromExtensions(certificate.extensions)?.keyIdentifierOctets
        val byKeyId = aki?.let { store.findBySubjectKeyId(it) }.orEmpty()
        val bySubject =
            store.findBySubject(Crypto.principal(certificate.issuer)).filter { anchor ->
                val ski = subjectKeyId(anchor)
                aki == null || ski == null || ski.contentEquals(aki)
            }
        return (byKeyId + bySubject)
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
        // Audit V9 : hachage cassé (MD5, RIPEMD-128…) ou clé RSA de moins de 1024 bits refusés.
        if (Crypto.isWeakAlgorithm(certificate.signatureAlgorithm)) {
            return SignatureCheck.Unsupported(Crypto.algorithmName(certificate.signatureAlgorithm.algorithm))
        }
        val weakKey =
            runCatching { Crypto.weakKey(JcaX509CertificateConverter().setProvider(Crypto.provider).getCertificate(issuer).publicKey) }
                .getOrNull()
        if (weakKey != null) return SignatureCheck.Unsupported(weakKey)
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
        /** Profondeur maximale de liens traversés : largement au-delà des chaînes réelles. */
        private const val MAX_DEPTH = 8

        /** Vérifications de signature par recherche : une chaîne réelle en demande quelques-unes. */
        const val MAX_SIGNATURE_CHECKS = 64

        /** Vrai si [holder] peut émettre des certificats (voir la règle de la classe, audit V8). */
        fun canIssueCertificates(holder: X509CertificateHolder): Boolean =
            runCatching {
                val basicConstraints = BasicConstraints.fromExtensions(holder.extensions)
                val keyUsage = KeyUsage.fromExtensions(holder.extensions)
                (basicConstraints == null || basicConstraints.isCA) && (keyUsage == null || keyUsage.hasUsages(KeyUsage.keyCertSign))
            }.getOrDefault(false)
    }
}
