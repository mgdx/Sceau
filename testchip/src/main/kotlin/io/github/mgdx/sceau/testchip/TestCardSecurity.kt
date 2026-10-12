package io.github.mgdx.sceau.testchip

import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.DERSet
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SecurityInfo
import java.math.BigInteger

/**
 * EF.CardSecurity factory (ICAO 9303-11 §4.4, PACE-CAM): `SET OF SecurityInfo` signed in CMS
 * by the DS, under the id-SecurityObject content type (BSI TR-03110), without an envelope tag.
 * The alterations of [SodOptions] (tampered signature, DS not embedded…) apply.
 */
object TestCardSecurity {
    /** id-SecurityObject. */
    const val CONTENT_TYPE_OID = "0.4.0.127.0.7.3.2.1"

    fun build(
        ds: TestCredential,
        securityInfos: List<SecurityInfo>,
        options: SodOptions = SodOptions(),
    ): ByteArray {
        val infos = DERSet(securityInfos.map<SecurityInfo, ASN1Encodable> { ASN1Primitive.fromByteArray(it.encoded) }.toTypedArray())
        val contentType = ASN1ObjectIdentifier(options.contentTypeOid ?: CONTENT_TYPE_OID)
        return TestSod.signedData(ds, contentType, infos.getEncoded(ASN1Encoding.DER), options)
    }

    /**
     * EF.CardSecurity of [document] for PACE-CAM: the `PACEInfo` of [pace] and the document's Chip
     * Authentication key (`ChipAuthenticationInfo`, `ChipAuthenticationPublicKeyInfo`),
     * signed by its DS, or by [signer] if provided.
     */
    fun of(
        document: TestDocument,
        pace: PaceSettings,
        options: SodOptions = SodOptions(),
        signer: TestCredential = document.ds,
    ): ByteArray {
        val caKeys = checkNotNull(document.caKeyPair) { "document without CA key" }
        val infos =
            listOf(
                PACEInfo(pace.protocolOid, PACE_VERSION, BigInteger.valueOf(pace.parameterId.toLong())),
                ChipAuthenticationInfo(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128, CA_VERSION, BigInteger.ONE),
                ChipAuthenticationPublicKeyInfo(caKeys.public, BigInteger.ONE),
            )
        return build(signer, infos, options)
    }

    private const val PACE_VERSION = 2
    private const val CA_VERSION = 1
}
