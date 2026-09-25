package io.github.mgdx.sceau.testchip

import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.AttributeTable
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.cms.SignedData
import org.bouncycastle.asn1.cms.SignerInfo
import org.bouncycastle.asn1.cms.Time
import org.bouncycastle.asn1.icao.DataGroupHash
import org.bouncycastle.asn1.icao.ICAOObjectIdentifiers
import org.bouncycastle.asn1.icao.LDSSecurityObject
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.cms.CMSAttributeTableGenerator
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Date

/** Options de construction d'un EF.SOD de test, y compris les altérations pour les cas d'échec. */
data class SodOptions(
    /** Algorithme de hachage des DG, déclaré dans le LDSSecurityObject. */
    val digestAlgorithm: String = "SHA-256",
    /** Algorithme de signature CMS ; par défaut celui du type de clé du DS. */
    val signatureAlgorithm: String? = null,
    /** Attribut signé signingTime ; null pour l'omettre. */
    val signingTime: Instant? = TestCrypto.instant(TestDocument.DEFAULT_DATE_OF_ISSUE),
    /** Embarque le certificat DS dans le SignedData. */
    val embedDs: Boolean = true,
    /** Altère un octet de la signature après signature. */
    val tamperSignature: Boolean = false,
    /** Remplace l'OID de l'algorithme de signature du SignerInfo (algorithme inconnu). */
    val signatureAlgorithmOid: String? = null,
    /** Déclare cet OID comme algorithme de hachage des DG (les empreintes restent en [digestAlgorithm]). */
    val declaredDigestOid: String? = null,
    /** Empreintes supplémentaires signées, pour des DG non lus (DG3…). */
    val extraHashes: Map<Int, ByteArray> = emptyMap(),
    /** Type de contenu encapsulé (eContentType) ; par défaut id-icao-ldsSecurityObject. */
    val contentTypeOid: String? = null,
)

/** Fabrique d'EF.SOD : LDSSecurityObject signé en CMS par le DS, encapsulé sous le tag 0x77. */
object TestSod {
    const val SOD_TAG = 0x77

    /** OID fictif utilisé pour simuler un algorithme inconnu de BouncyCastle. */
    const val UNKNOWN_OID = "1.3.6.1.4.1.99999.42.1"

    fun build(
        ds: TestCredential,
        dataGroups: Map<Int, ByteArray>,
        options: SodOptions = SodOptions(),
    ): ByteArray {
        val provider = TestCrypto.provider
        val digestId = DefaultDigestAlgorithmIdentifierFinder().find(options.digestAlgorithm)
        val declaredDigestId = options.declaredDigestOid?.let { AlgorithmIdentifier(ASN1ObjectIdentifier(it)) } ?: digestId
        val hashes =
            dataGroups.toSortedMap().map { (number, bytes) ->
                DataGroupHash(number, DEROctetString(MessageDigest.getInstance(options.digestAlgorithm, provider).digest(bytes)))
            } + options.extraHashes.map { (number, hash) -> DataGroupHash(number, DEROctetString(hash)) }
        val securityObject = LDSSecurityObject(declaredDigestId, hashes.sortedBy { it.dataGroupNumber }.toTypedArray())

        val signatureAlgorithm = options.signatureAlgorithm ?: ds.keyType.signatureAlgorithm
        val contentSigner = JcaContentSignerBuilder(signatureAlgorithm).setProvider(provider).build(ds.privateKey)
        val digests = JcaDigestCalculatorProviderBuilder().setProvider(provider).build()
        val signerInfo =
            JcaSignerInfoGeneratorBuilder(digests)
                .setSignedAttributeGenerator(signedAttributes(options.signingTime))
                .build(contentSigner, ds.holder)
        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(signerInfo)
        if (options.embedDs) generator.addCertificate(ds.holder)
        val content =
            CMSProcessableByteArray(
                options.contentTypeOid?.let(::ASN1ObjectIdentifier) ?: ICAOObjectIdentifiers.id_icao_ldsSecurityObject,
                securityObject.getEncoded(ASN1Encoding.DER),
            )
        var contentInfo = generator.generate(content, true).toASN1Structure()

        if (options.tamperSignature || options.signatureAlgorithmOid != null) {
            contentInfo = alterSignerInfo(contentInfo, options.tamperSignature, options.signatureAlgorithmOid)
        }
        return tlv(SOD_TAG, contentInfo.getEncoded(ASN1Encoding.DER))
    }

    /** Attributs signés d'un SOD : contentType, messageDigest et, si fourni, signingTime. */
    private fun signedAttributes(signingTime: Instant?) =
        CMSAttributeTableGenerator { parameters ->
            val attributes = ASN1EncodableVector()
            attributes.add(
                Attribute(CMSAttributes.contentType, DERSet(parameters[CMSAttributeTableGenerator.CONTENT_TYPE] as ASN1Encodable)),
            )
            attributes.add(
                Attribute(CMSAttributes.messageDigest, DERSet(DEROctetString(parameters[CMSAttributeTableGenerator.DIGEST] as ByteArray))),
            )
            if (signingTime != null) attributes.add(Attribute(CMSAttributes.signingTime, DERSet(Time(Date.from(signingTime)))))
            AttributeTable(attributes)
        }

    private fun alterSignerInfo(
        contentInfo: ContentInfo,
        tamperSignature: Boolean,
        signatureAlgorithmOid: String?,
    ): ContentInfo {
        val signedData = SignedData.getInstance(contentInfo.content)
        val original = SignerInfo.getInstance(signedData.signerInfos.getObjectAt(0))
        val signature = original.encryptedDigest.octets.copyOf()
        if (tamperSignature) signature[signature.size / 2] = (signature[signature.size / 2].toInt() xor 0x01).toByte()
        val algorithm =
            signatureAlgorithmOid?.let { AlgorithmIdentifier(ASN1ObjectIdentifier(it)) } ?: original.digestEncryptionAlgorithm
        val altered =
            SignerInfo(
                original.sid,
                original.digestAlgorithm,
                original.authenticatedAttributes,
                algorithm,
                DEROctetString(signature),
                original.unauthenticatedAttributes,
            )
        val alteredSignedData =
            SignedData(
                signedData.digestAlgorithms,
                signedData.encapContentInfo,
                signedData.certificates,
                signedData.crLs,
                DERSet(altered),
            )
        return ContentInfo(CMSObjectIdentifiers.signedData, alteredSignedData)
    }

    /** Encode un TLV à tag sur un octet, longueur DER. */
    fun tlv(
        tag: Int,
        value: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val length = value.size
        when {
            length < 0x80 -> {
                out.write(length)
            }

            length <= 0xFF -> {
                out.write(0x81)
                out.write(length)
            }

            length <= 0xFFFF -> {
                out.write(0x82)
                out.write(length shr 8)
                out.write(length and 0xFF)
            }

            else -> {
                out.write(0x83)
                out.write(length shr 16)
                out.write((length shr 8) and 0xFF)
                out.write(length and 0xFF)
            }
        }
        out.write(value)
        return out.toByteArray()
    }
}
