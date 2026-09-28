package io.github.mgdx.sceau.core.trust

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.Time
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSVerifierCertificateNotValidException
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.operator.OperatorCreationException
import java.security.cert.X509Certificate
import java.time.Instant

/** Master List dont la signature CMS est vérifiée, avec le certificat signataire. */
internal class ParsedMasterList(
    val masterList: MasterList,
    val signerCertificate: X509Certificate,
    /** Certificats du bloc `certificates` du CMS (signataire compris). */
    val cmsCertificates: List<X509Certificate>,
)

/**
 * Lecture d'une Master List CSCA (ICAO 9303 partie 12) :
 * CMS SignedData dont le contenu, de type `id-icao-cscaMasterList`, est
 * `CscaMasterList ::= SEQUENCE { version INTEGER, certList SET OF Certificate }`.
 */
internal object MasterListParser {
    /** id-icao-cscaMasterList. */
    val CSCA_MASTER_LIST_OID = ASN1ObjectIdentifier("2.23.136.1.1.2")

    /**
     * Lève [InvalidMasterListException], et elle seule, si la liste est invalide : c'est la seule
     * exception que `TrustStoreLoader.load` rattrape pour un import. BouncyCastle ne décode
     * `signerInfos` et `certificates` qu'à la demande : une structure malformée y lève
     * `IllegalArgumentException` ou `NoSuchElementException`, rendues ici en `NOT_CMS`.
     */
    fun parse(bytes: ByteArray): ParsedMasterList =
        try {
            parseCms(bytes)
        } catch (e: InvalidMasterListException) {
            throw e
        } catch (e: Exception) {
            throw InvalidMasterListException("NOT_CMS", e)
        }

    private fun parseCms(bytes: ByteArray): ParsedMasterList {
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

        val signers = signed.signerInfos.signers
        if (signers.isEmpty()) throw InvalidMasterListException("NO_SIGNER")
        val signerHolders = signers.map { verifySigner(signed, it) }

        val certificates = parseContent(content)
        val cmsCertificates = signed.certificates.getMatches(null).mapNotNull { toX509OrNull(it) }
        val signerHolder = signerHolders.first()
        val info =
            MasterListInfo(
                signingTime = signingTime(signers.first()),
                signerSubject = TrustCrypto.toX509(signerHolder).subjectX500Principal.name,
                signerSha256 = TrustCrypto.sha256Hex(signerHolder.encoded),
                certificateCount = certificates.size,
            )
        return ParsedMasterList(
            masterList = MasterList(info, certificates),
            signerCertificate = TrustCrypto.toX509(signerHolder),
            cmsCertificates = cmsCertificates,
        )
    }

    /**
     * Exige que le signataire de [parsed] soit signé par un certificat dont l'empreinte SHA-256
     * figure dans [pinnedSha256]. Le certificat épinglé est cherché dans la liste elle-même et
     * dans le bloc `certificates` du CMS. Sans cet ancrage, vérifier une Master List avec le seul
     * signataire qu'elle transporte ne prouve rien sur son origine.
     */
    fun requireAnchoredSigner(
        parsed: ParsedMasterList,
        pinnedSha256: Set<String>,
    ) {
        val signer = parsed.signerCertificate
        val anchored =
            (parsed.masterList.certificates + parsed.cmsCertificates)
                .asSequence()
                .filter { TrustCrypto.sha256Hex(it.encoded) in pinnedSha256 }
                .any { pinned ->
                    signer.issuerX500Principal == pinned.subjectX500Principal &&
                        TrustCrypto.isSignedBy(signer, pinned.publicKey)
                }
        if (!anchored) throw InvalidMasterListException("UNTRUSTED_SIGNER")
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

    /** Un certificat individuellement illisible est ignoré ; seule une structure invalide rejette la liste. */
    private fun parseContent(content: ByteArray): List<X509Certificate> {
        val certList =
            try {
                val sequence = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(content))
                ASN1Integer.getInstance(sequence.getObjectAt(0))
                ASN1Set.getInstance(sequence.getObjectAt(1))
            } catch (e: Exception) {
                throw InvalidMasterListException("BAD_CONTENT", e)
            }
        return certList.mapNotNull { element ->
            try {
                toX509OrNull(X509CertificateHolder(Certificate.getInstance(element)))
            } catch (ignored: Exception) {
                null
            }
        }
    }

    /**
     * Certificat utilisable, ou null. BouncyCastle ne décode la clé publique qu'au premier
     * `publicKey`, qui lève `IllegalStateException` pour une clé illisible et renvoie null pour
     * un algorithme inconnu : la clé est donc lue ici, pour ne jamais faire entrer dans le
     * magasin un certificat sans clé utilisable.
     */
    private fun toX509OrNull(holder: X509CertificateHolder): X509Certificate? =
        try {
            TrustCrypto.toX509(holder).takeIf { it.publicKey != null }
        } catch (ignored: Exception) {
            null
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
