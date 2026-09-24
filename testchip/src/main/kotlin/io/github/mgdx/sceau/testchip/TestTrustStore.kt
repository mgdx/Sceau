package io.github.mgdx.sceau.testchip

import io.github.mgdx.sceau.core.trust.MasterListInfo
import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustSource
import io.github.mgdx.sceau.core.trust.TrustStore
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * Magasin de confiance de test, indépendant de `TrustStores` : n'utilise que les membres
 * de [TrustAnchor] qui ne dépendent pas d'une implémentation (certificat et source).
 */
class TestTrustStore(
    override val anchors: List<TrustAnchor>,
    override val embeddedMasterList: MasterListInfo? = null,
) : TrustStore {
    override fun findBySubject(issuer: X500Principal): List<TrustAnchor> = anchors.filter { it.certificate.subjectX500Principal == issuer }

    override fun findBySubjectKeyId(keyIdentifier: ByteArray): List<TrustAnchor> =
        anchors.filter { subjectKeyId(it.certificate)?.contentEquals(keyIdentifier) == true }

    companion object {
        fun of(
            certificates: List<X509Certificate>,
            source: TrustSource = TrustSource.ANTS,
        ): TestTrustStore = TestTrustStore(certificates.map { TrustAnchor(it, source) })

        fun subjectKeyId(certificate: X509Certificate): ByteArray? {
            val extension = certificate.getExtensionValue(Extension.subjectKeyIdentifier.id) ?: return null
            return SubjectKeyIdentifier.getInstance(ASN1OctetString.getInstance(extension).octets).keyIdentifier
        }
    }
}
