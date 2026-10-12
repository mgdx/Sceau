package io.github.mgdx.sceau.core.trust

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.SignedData
import org.bouncycastle.asn1.cms.Time
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.RSAPublicKey
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSVerifierCertificateNotValidException
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.operator.OperatorCreationException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.CancellationException

/** Master List whose CMS signature has been verified, together with the signer certificate. */
internal class ParsedMasterList(
    val masterList: MasterList,
    val signerCertificate: X509Certificate,
    /** Certificates from the CMS `certificates` block (signer included). */
    val cmsCertificates: List<X509Certificate>,
)

/**
 * Reading of a CSCA Master List (ICAO 9303 part 12):
 * CMS SignedData whose content, of type `id-icao-cscaMasterList`, is
 * `CscaMasterList ::= SEQUENCE { version INTEGER, certList SET OF Certificate }`.
 */
internal object MasterListParser {
    /** id-icao-cscaMasterList. */
    val CSCA_MASTER_LIST_OID = ASN1ObjectIdentifier("2.23.136.1.1.2")

    /** id-icao-cscaMasterListSigningKey: extended key usage of a Master List signer certificate. */
    val MASTER_LIST_SIGNING_KEY_OID = ASN1ObjectIdentifier("2.23.136.1.1.3")

    /** Maximum number of certificates in a Master List, and in the `certificates` block of its CMS (V22). */
    const val MAX_CERTIFICATES = TrustStores.MAX_MASTER_LIST_CERTIFICATES

    /**
     * Throws [InvalidMasterListException], and only that, if the list is invalid: it is the only
     * exception that `TrustStoreLoader.load` catches for an import. BouncyCastle decodes
     * `signerInfos` and `certificates` only on demand: a malformed structure there throws
     * `IllegalArgumentException` or `NoSuchElementException`, turned here into `NOT_CMS`.
     *
     * The cost is bounded whatever the content (audit V22): exactly one SignerInfo
     * (`NO_SIGNER`, `MULTIPLE_SIGNERS`), and at most [MAX_CERTIFICATES] certificates in the list
     * as well as in the CMS `certificates` block (`TOO_MANY_CERTIFICATES`), counted before being
     * read. [checkpoint] is called between costly steps: it may throw
     * `CancellationException` to abort the parsing, an exception then propagated as is.
     */
    fun parse(
        bytes: ByteArray,
        checkpoint: () -> Unit = {},
    ): ParsedMasterList =
        try {
            parseCms(bytes, checkpoint)
        } catch (e: InvalidMasterListException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw InvalidMasterListException("NOT_CMS", e)
        }

    private fun parseCms(
        bytes: ByteArray,
        checkpoint: () -> Unit,
    ): ParsedMasterList {
        val signed =
            try {
                CMSSignedData(bytes)
            } catch (e: Exception) {
                throw InvalidMasterListException("NOT_CMS", e)
            }
        if (signed.signedContentTypeOID != CSCA_MASTER_LIST_OID.id) {
            throw InvalidMasterListException("BAD_CONTENT_TYPE")
        }
        val content = signed.signedContent?.content as? ByteArray ?: throw InvalidMasterListException("BAD_CONTENT")
        requireBoundedStructure(signed)
        checkpoint()

        // A single signer, as for the SOD: each SignerInfo would scan the whole `certificates` block.
        val signer = signed.signerInfos.signers.singleOrNull() ?: throw InvalidMasterListException("NO_SIGNER")
        val signerHolder = verifySigner(signed, signer)
        requireMasterListSigningUsage(signerHolder)
        checkpoint()

        val certificates = parseContent(content, checkpoint)
        val cmsCertificates =
            signed.certificates.getMatches(null).mapNotNull {
                checkpoint()
                toX509OrNull(it)
            }
        val info =
            MasterListInfo(
                signingTime = signingTime(signer),
                signerSubject = TrustCrypto.toX509(signerHolder).subjectX500Principal.name,
                signerSha256 = TrustCrypto.sha256Hex(signerHolder.encoded),
                certificateCount = certificates.size,
            )
        val signerCertificate = TrustCrypto.toX509(signerHolder)
        return ParsedMasterList(
            masterList = MasterList(info, certificates, signerCertificate),
            signerCertificate = signerCertificate,
            cmsCertificates = cmsCertificates,
        )
    }

    /**
     * Requires the signer of [parsed] to be signed by a certificate whose SHA-256 fingerprint
     * is in [pinnedSha256]. The pinned certificate is looked up in the list itself and
     * in the CMS `certificates` block. Without this anchoring, verifying a Master List with only
     * the signer it carries proves nothing about its origin.
     */
    fun requireAnchoredSigner(
        parsed: ParsedMasterList,
        pinnedSha256: Set<String>,
    ) {
        val pinned =
            (parsed.masterList.certificates + parsed.cmsCertificates)
                .filter { TrustCrypto.sha256Hex(it.encoded) in pinnedSha256 }
        if (!isSignerAnchored(parsed.signerCertificate, pinned)) throw InvalidMasterListException("UNTRUSTED_SIGNER")
    }

    /**
     * True if [signer] is issued by one of the [anchors]: same name as the issuer and signature
     * verified with its key (audit V34). A CSCA carried by the list itself must never
     * be in [anchors]: the list would anchor itself.
     */
    fun isSignerAnchored(
        signer: X509Certificate,
        anchors: Collection<X509Certificate>,
    ): Boolean =
        anchors.any { anchor ->
            signer.issuerX500Principal == anchor.subjectX500Principal && TrustCrypto.isSignedBy(signer, anchor.publicKey)
        }

    /**
     * Extended key usage of the signer (audit V34): checked only if present, because some
     * real Master Lists do not carry it; when present, it must contain
     * `id-icao-cscaMasterListSigningKey` (or `anyExtendedKeyUsage`), otherwise `BAD_SIGNER_USAGE`.
     */
    private fun requireMasterListSigningUsage(signer: X509CertificateHolder) {
        val extensions = signer.extensions ?: return
        val usage =
            try {
                ExtendedKeyUsage.fromExtensions(extensions)
            } catch (e: Exception) {
                throw InvalidMasterListException("BAD_SIGNER_USAGE", e)
            } ?: return
        val allowed =
            usage.hasKeyPurposeId(KeyPurposeId.getInstance(MASTER_LIST_SIGNING_KEY_OID)) ||
                usage.hasKeyPurposeId(KeyPurposeId.anyExtendedKeyUsage)
        if (!allowed) throw InvalidMasterListException("BAD_SIGNER_USAGE")
    }

    /**
     * Counts, on the already decoded ASN.1 structure and before any computation, the SignerInfos and the
     * certificates of the CMS `certificates` block.
     */
    private fun requireBoundedStructure(signed: CMSSignedData) {
        val signedData = SignedData.getInstance(signed.toASN1Structure().content)
        when (signedData.signerInfos.size()) {
            0 -> throw InvalidMasterListException("NO_SIGNER")
            1 -> Unit
            else -> throw InvalidMasterListException("MULTIPLE_SIGNERS")
        }
        val cmsCertificates = signedData.certificates?.size() ?: 0
        if (cmsCertificates > MAX_CERTIFICATES) throw InvalidMasterListException("TOO_MANY_CERTIFICATES")
    }

    private fun verifySigner(
        signed: CMSSignedData,
        signer: SignerInformation,
    ): X509CertificateHolder {
        val holder =
            signed.certificates
                .getMatches(null)
                .firstOrNull { signer.sid.match(it) } ?: throw InvalidMasterListException("NO_SIGNER")
        val valid =
            try {
                val verifier = JcaSimpleSignerInfoVerifierBuilder().setProvider(TrustCrypto.provider).build(holder)
                signer.verify(verifier)
            } catch (e: CMSVerifierCertificateNotValidException) {
                throw InvalidMasterListException("SIGNER_NOT_VALID", e)
            } catch (e: OperatorCreationException) {
                throw InvalidMasterListException("UNSUPPORTED_ALGORITHM", e)
            } catch (e: Exception) {
                throw InvalidMasterListException("BAD_SIGNATURE", e)
            }
        if (!valid) throw InvalidMasterListException("BAD_SIGNATURE")
        return holder
    }

    /** An individually unreadable certificate is skipped; only an invalid structure rejects the list. */
    private fun parseContent(
        content: ByteArray,
        checkpoint: () -> Unit,
    ): List<X509Certificate> {
        val certList =
            try {
                val sequence = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(content))
                ASN1Integer.getInstance(sequence.getObjectAt(0))
                ASN1Set.getInstance(sequence.getObjectAt(1))
            } catch (e: Exception) {
                throw InvalidMasterListException("BAD_CONTENT", e)
            }
        if (certList.size() > MAX_CERTIFICATES) throw InvalidMasterListException("TOO_MANY_CERTIFICATES")
        return certList.mapNotNull { element ->
            checkpoint()
            try {
                toX509OrNull(X509CertificateHolder(Certificate.getInstance(element)))
            } catch (ignored: Exception) {
                null
            }
        }
    }

    /**
     * Usable certificate, or null. BouncyCastle decodes the public key only on the first
     * `publicKey` call, which throws `IllegalStateException` for an unreadable key and returns null for
     * an unknown algorithm: the key is therefore read here, so that a certificate without a usable
     * key never enters the store.
     */
    private fun toX509OrNull(holder: X509CertificateHolder): X509Certificate? =
        try {
            TrustCrypto.toX509(holder).takeIf { hasReadableKey(holder, it) }
        } catch (ignored: Exception) {
            null
        }

    /**
     * RSA key: only its structure is read here (odd modulus, positive exponent). Decoding it
     * makes BouncyCastle run a Miller-Rabin test on the modulus (it must not be
     * prime) which cost, for the store's roughly 430 RSA keys, more than 40 s of computation
     * on a Fairphone 3 at every startup (D47). This check is not removed but deferred:
     * an anchor's key is decoded, and therefore checked, only when it is used to verify a
     * signature, and a failure there counts as a rejection. Other keys: decoded here, as before.
     */
    private fun hasReadableKey(
        holder: X509CertificateHolder,
        certificate: X509Certificate,
    ): Boolean {
        val keyInfo = holder.subjectPublicKeyInfo
        if (keyInfo.algorithm.algorithm != PKCSObjectIdentifiers.rsaEncryption) return certificate.publicKey != null
        val key = RSAPublicKey.getInstance(keyInfo.parsePublicKey())
        return key.modulus.signum() > 0 && key.modulus.testBit(0) && key.publicExponent.signum() > 0
    }

    private fun signingTime(signer: SignerInformation): Instant? {
        val attribute = signer.signedAttributes?.get(CMSAttributes.signingTime) ?: return null
        return try {
            Time.getInstance(attribute.attrValues.getObjectAt(0)).date.toInstant()
        } catch (ignored: Exception) {
            null
        }
    }
}
