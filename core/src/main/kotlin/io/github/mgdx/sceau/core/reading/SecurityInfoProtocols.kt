package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.verify.Tlv
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set

/**
 * Protocol OIDs of the DG14 `SecurityInfo`s, read from the raw bytes (ICAO 9303-11 §9.2),
 * without going through JMRTD: `SecurityInfo.getInstance` silently discards (null) an entry it
 * cannot rebuild, for example a Chip Authentication key with unusual parameters
 * (audit V19). These OIDs state what the signed DG14 announces, whether JMRTD understood it or not. Same
 * reading for the signed content of EF.CardSecurity ([ofSet], audit V32).
 */
internal object SecurityInfoProtocols {
    /** Prefix of `ChipAuthenticationPublicKeyInfo`: id-PK-DH (…1.1), id-PK-ECDH (…1.2). */
    private const val ID_PK_PREFIX = "0.4.0.127.0.7.2.2.1."

    /** Prefix of `ChipAuthenticationInfo`: id-CA-DH-* (…3.1.x), id-CA-ECDH-* (…3.2.x). */
    private const val ID_CA_PREFIX = "0.4.0.127.0.7.2.2.3."

    /** PACE prefix: `PACEInfo` and `PACEDomainParameterInfo` (GM, IM, CAM). */
    private const val ID_PACE_PREFIX = "0.4.0.127.0.7.2.2.4."

    /** id-PACE-ECDH-CAM (…4.6), and its id-PACE-ECDH-CAM-AES-CBC-CMAC-* protocols (…4.6.x). */
    private const val ID_PACE_ECDH_CAM = "0.4.0.127.0.7.2.2.4.6"

    private const val DG14_TAG = 0x6E

    /** Protocol OID of each `SecurityInfo` of [dg14]; null if DG14 is unreadable. */
    fun of(dg14: ByteArray): List<String>? =
        try {
            ofSet(Tlv.value(dg14, DG14_TAG))
        } catch (e: Exception) {
            null
        }

    /**
     * Protocol OID of each `SecurityInfo` of a bare `SET OF SecurityInfo`, such as the signed
     * content of EF.CardSecurity (audit V32); null if it is unreadable.
     */
    fun ofSet(securityInfos: ByteArray): List<String>? =
        try {
            ASN1Set
                .getInstance(ASN1Primitive.fromByteArray(securityInfos))
                .map { ASN1ObjectIdentifier.getInstance(ASN1Sequence.getInstance(it).getObjectAt(0)).id }
        } catch (e: Exception) {
            null
        }

    /** First id-PK-* OID in [oids]: DG14 announces a Chip Authentication key. */
    fun chipAuthenticationKey(oids: List<String>): String? = oids.firstOrNull { it.startsWith(ID_PK_PREFIX) }

    /** First id-CA-* OID in [oids]: the Chip Authentication protocol is announced (audit V33). */
    fun chipAuthenticationProtocol(oids: List<String>): String? = oids.firstOrNull { it.startsWith(ID_CA_PREFIX) }

    /** True if [oids] announces the Chip Authentication protocol (`ChipAuthenticationInfo`). */
    fun announcesChipAuthentication(oids: List<String>): Boolean = chipAuthenticationProtocol(oids) != null

    /** True if [oids] announces PACE, whatever the mapping. */
    fun announcesPace(oids: List<String>): Boolean = oids.any { it.startsWith(ID_PACE_PREFIX) }

    /** True if [oids] announces PACE with the Chip Authentication Mapping (PACE-CAM). */
    fun announcesPaceCam(oids: List<String>): Boolean = oids.any(::isPaceCam)

    /** True if [oid] is a PACE-CAM protocol. */
    fun isPaceCam(oid: String?): Boolean = oid != null && (oid == ID_PACE_ECDH_CAM || oid.startsWith("$ID_PACE_ECDH_CAM."))
}
