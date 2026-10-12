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

/** Build options for a test EF.SOD, including the alterations for failure cases. */
data class SodOptions(
    /** DG hash algorithm, declared in the LDSSecurityObject. */
    val digestAlgorithm: String = "SHA-256",
    /** CMS signature algorithm; defaults to the one of the DS key type. */
    val signatureAlgorithm: String? = null,
    /** signingTime signed attribute; null to omit it. */
    val signingTime: Instant? = TestCrypto.instant(TestDocument.DEFAULT_DATE_OF_ISSUE),
    /** Embeds the DS certificate in the SignedData. */
    val embedDs: Boolean = true,
    /** Alters one byte of the signature after signing. */
    val tamperSignature: Boolean = false,
    /** Replaces the SignerInfo signature algorithm OID (unknown algorithm). */
    val signatureAlgorithmOid: String? = null,
    /** Declares this OID as the DG hash algorithm (the hashes stay in [digestAlgorithm]). */
    val declaredDigestOid: String? = null,
    /** Additional signed hashes, for DGs that are not read (DG3…). */
    val extraHashes: Map<Int, ByteArray> = emptyMap(),
    /** Encapsulated content type (eContentType); defaults to id-icao-ldsSecurityObject. */
    val contentTypeOid: String? = null,
)

/** EF.SOD factory: LDSSecurityObject CMS-signed by the DS, wrapped under tag 0x77. */
object TestSod {
    const val SOD_TAG = 0x77

    /** Fictitious OID used to simulate an algorithm unknown to BouncyCastle. */
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
        val contentType = options.contentTypeOid?.let(::ASN1ObjectIdentifier) ?: ICAOObjectIdentifiers.id_icao_ldsSecurityObject
        return tlv(SOD_TAG, signedData(ds, contentType, securityObject.getEncoded(ASN1Encoding.DER), options))
    }

    /**
     * DER-encoded CMS `SignedData` ContentInfo: [content] encapsulated under [contentType], signed
     * by [ds] with the attributes and alterations of [options] (except the SOD-specific options).
     */
    internal fun signedData(
        ds: TestCredential,
        contentType: ASN1ObjectIdentifier,
        content: ByteArray,
        options: SodOptions,
    ): ByteArray {
        val provider = TestCrypto.provider
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
        var contentInfo = generator.generate(CMSProcessableByteArray(contentType, content), true).toASN1Structure()

        if (options.tamperSignature || options.signatureAlgorithmOid != null) {
            contentInfo = alterSignerInfo(contentInfo, options.tamperSignature, options.signatureAlgorithmOid)
        }
        return contentInfo.getEncoded(ASN1Encoding.DER)
    }

    /** Signed attributes of a SOD: contentType, messageDigest and, if given, signingTime. */
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

    /** Encodes a TLV with a one-byte tag and DER length. */
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
