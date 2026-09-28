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
 * Fabrique d'EF.CardSecurity (ICAO 9303-11 §4.4, PACE-CAM) : `SET OF SecurityInfo` signé en CMS
 * par le DS, sous le type de contenu id-SecurityObject (BSI TR-03110), sans tag d'enveloppe.
 * Les altérations de [SodOptions] (signature altérée, DS non embarqué…) s'appliquent.
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
     * EF.CardSecurity de [document] pour PACE-CAM : le `PACEInfo` de [pace] et la clé de Chip
     * Authentication du document (`ChipAuthenticationInfo`, `ChipAuthenticationPublicKeyInfo`),
     * signés par son DS, ou par [signer] s'il est fourni.
     */
    fun of(
        document: TestDocument,
        pace: PaceSettings,
        options: SodOptions = SodOptions(),
        signer: TestCredential = document.ds,
    ): ByteArray {
        val caKeys = checkNotNull(document.caKeyPair) { "document sans clé CA" }
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
