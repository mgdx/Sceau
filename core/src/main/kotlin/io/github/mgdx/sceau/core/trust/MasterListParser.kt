package io.github.mgdx.sceau.core.trust

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.SignedData
import org.bouncycastle.asn1.cms.Time
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

    /** id-icao-cscaMasterListSigningKey : usage étendu du certificat signataire d'une Master List. */
    val MASTER_LIST_SIGNING_KEY_OID = ASN1ObjectIdentifier("2.23.136.1.1.3")

    /** Nombre maximal de certificats d'une Master List, et du bloc `certificates` de son CMS (V22). */
    const val MAX_CERTIFICATES = TrustStores.MAX_MASTER_LIST_CERTIFICATES

    /**
     * Lève [InvalidMasterListException], et elle seule, si la liste est invalide : c'est la seule
     * exception que `TrustStoreLoader.load` rattrape pour un import. BouncyCastle ne décode
     * `signerInfos` et `certificates` qu'à la demande : une structure malformée y lève
     * `IllegalArgumentException` ou `NoSuchElementException`, rendues ici en `NOT_CMS`.
     *
     * Le coût est borné quel que soit le contenu (audit V22) : exactement un SignerInfo
     * (`NO_SIGNER`, `MULTIPLE_SIGNERS`), et au plus [MAX_CERTIFICATES] certificats dans la liste
     * comme dans le bloc `certificates` du CMS (`TOO_MANY_CERTIFICATES`), comptés avant d'être
     * lus. [checkpoint] est appelé entre les étapes coûteuses : il peut lever
     * `CancellationException` pour abandonner l'analyse, exception alors propagée telle quelle.
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

        // Un seul signataire, comme pour le SOD : chaque SignerInfo parcourrait tout le bloc `certificates`.
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
     * Exige que le signataire de [parsed] soit signé par un certificat dont l'empreinte SHA-256
     * figure dans [pinnedSha256]. Le certificat épinglé est cherché dans la liste elle-même et
     * dans le bloc `certificates` du CMS. Sans cet ancrage, vérifier une Master List avec le seul
     * signataire qu'elle transporte ne prouve rien sur son origine.
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
     * Vrai si [signer] est émis par l'un des [anchors] : même nom que l'émetteur et signature
     * vérifiée avec sa clé (audit V34). Un CSCA transporté par la liste elle-même ne doit jamais
     * figurer dans [anchors] : la liste s'ancrerait sur elle-même.
     */
    fun isSignerAnchored(
        signer: X509Certificate,
        anchors: Collection<X509Certificate>,
    ): Boolean =
        anchors.any { anchor ->
            signer.issuerX500Principal == anchor.subjectX500Principal && TrustCrypto.isSignedBy(signer, anchor.publicKey)
        }

    /**
     * Usage étendu du signataire (audit V34) : contrôlé seulement s'il est présent, car certaines
     * Master Lists réelles ne le portent pas ; présent, il doit contenir
     * `id-icao-cscaMasterListSigningKey` (ou `anyExtendedKeyUsage`), sinon `BAD_SIGNER_USAGE`.
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
     * Compte, sur la structure ASN.1 déjà décodée et avant tout calcul, les SignerInfo et les
     * certificats du bloc `certificates` du CMS.
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

    /** Un certificat individuellement illisible est ignoré ; seule une structure invalide rejette la liste. */
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
