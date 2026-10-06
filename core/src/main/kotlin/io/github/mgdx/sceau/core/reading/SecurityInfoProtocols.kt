package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.verify.Tlv
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set

/**
 * OID de protocole des `SecurityInfo` de DG14, lus dans les octets bruts (ICAO 9303-11 §9.2),
 * sans passer par JMRTD : `SecurityInfo.getInstance` écarte en silence (null) une entrée qu'il ne
 * sait pas reconstruire, par exemple une clé de Chip Authentication à paramètres inhabituels
 * (audit V19). Ces OID disent ce que DG14, signé, annonce, que JMRTD l'ait compris ou non.
 */
internal object SecurityInfoProtocols {
    /** Préfixe des `ChipAuthenticationPublicKeyInfo` : id-PK-DH (…1.1), id-PK-ECDH (…1.2). */
    private const val ID_PK_PREFIX = "0.4.0.127.0.7.2.2.1."

    /** Préfixe de PACE : `PACEInfo` et `PACEDomainParameterInfo` (GM, IM, CAM). */
    private const val ID_PACE_PREFIX = "0.4.0.127.0.7.2.2.4."

    /** id-PACE-ECDH-CAM (…4.6), et ses protocoles id-PACE-ECDH-CAM-AES-CBC-CMAC-* (…4.6.x). */
    private const val ID_PACE_ECDH_CAM = "0.4.0.127.0.7.2.2.4.6"

    private const val DG14_TAG = 0x6E

    /** OID de protocole de chaque `SecurityInfo` de [dg14] ; null si DG14 est illisible. */
    fun of(dg14: ByteArray): List<String>? =
        try {
            ASN1Set
                .getInstance(ASN1Primitive.fromByteArray(Tlv.value(dg14, DG14_TAG)))
                .map { ASN1ObjectIdentifier.getInstance(ASN1Sequence.getInstance(it).getObjectAt(0)).id }
        } catch (e: Exception) {
            null
        }

    /** Premier OID id-PK-* de [oids] : DG14 annonce une clé de Chip Authentication. */
    fun chipAuthenticationKey(oids: List<String>): String? = oids.firstOrNull { it.startsWith(ID_PK_PREFIX) }

    /** Vrai si [oids] annonce PACE, quel que soit le mapping. */
    fun announcesPace(oids: List<String>): Boolean = oids.any { it.startsWith(ID_PACE_PREFIX) }

    /** Vrai si [oids] annonce PACE avec le mapping Chip Authentication Mapping (PACE-CAM). */
    fun announcesPaceCam(oids: List<String>): Boolean = oids.any(::isPaceCam)

    /** Vrai si [oid] est un protocole PACE-CAM. */
    fun isPaceCam(oid: String?): Boolean = oid != null && (oid == ID_PACE_ECDH_CAM || oid.startsWith("$ID_PACE_ECDH_CAM."))
}
