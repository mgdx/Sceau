package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.trust.MasterListInfo
import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustStore
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.COMFile
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.MRZInfo
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Données factices (personne fictive) pour les tests de lecture. */
object TestFixtures {
    val TODAY: LocalDate = LocalDate.of(2026, 9, 25)

    const val AID_SELECT = "00A4040C07A0000002471001"
    const val FID_CARD_ACCESS = "011C"
    const val FID_COM = "011E"
    const val FID_SOD = "011D"
    const val GET_CHALLENGE = "0084000008"
    const val EXTERNAL_AUTHENTICATE = "0082000028"

    fun fid(dataGroup: Int): String = String.format(java.util.Locale.ROOT, "%04X", 0x0100 + dataGroup)

    /** Tag LDS de chaque DG (ICAO 9303-10). */
    private val TAGS = mapOf(1 to 0x61, 2 to 0x75, 3 to 0x63, 4 to 0x76, 11 to 0x6B, 12 to 0x6C, 14 to 0x6E, 15 to 0x6F)

    fun com(vararg dataGroups: Int): ByteArray = COMFile("1.7", "4.0.0", dataGroups.map { TAGS.getValue(it) }.toIntArray()).encoded

    /** Passeport TD3 fictif. */
    fun td3Mrz(): MRZInfo = MRZInfo.createTD3MRZInfo("P", "FRA", "MARTIN", "CLAIRE ANNE", "12AB34567", "FRA", "650212", "F", "301231", "")

    /** Carte d'identité TD1 fictive, née en 2010. */
    fun td1Mrz(dateOfBirth: String = "100212"): MRZInfo =
        MRZInfo.createTD1MRZInfo("ID", "FRA", "X4RTBPFW4", "", dateOfBirth, "M", "301231", "FRA", "", "DUPONT", "JEAN PIERRE")

    fun dg1(mrz: MRZInfo = td3Mrz()): ByteArray = DG1File(mrz).encoded

    /** Octets TLV quelconques avec le tag du DG (contenu non analysable). */
    fun opaqueDataGroup(dataGroup: Int): ByteArray = byteArrayOf(TAGS.getValue(dataGroup).toByte(), 0x03, 0x01, 0x02, 0x03)

    /** SOD signé par un DS jetable, annonçant les DG donnés (empreintes sur des octets factices). */
    fun sod(vararg dataGroups: Int): ByteArray {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Test DS, C=UT")
        val now = System.currentTimeMillis()
        val certificate =
            JcaX509CertificateConverter().getCertificate(
                JcaX509v3CertificateBuilder(
                    name,
                    BigInteger.ONE,
                    Date(now - 86_400_000L),
                    Date(now + 86_400_000L),
                    name,
                    keyPair.public,
                ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)),
            )
        val digest = MessageDigest.getInstance("SHA-256")
        val hashes = dataGroups.associateWith { digest.digest(byteArrayOf(it.toByte())) }
        return SODFile("SHA-256", "SHA256withRSA", hashes, keyPair.private, certificate).encoded
    }

    /** Magasin vide : la lecture n'y accède pas avant la Passive Authentication. */
    val EMPTY_TRUST_STORE =
        object : TrustStore {
            override val anchors: List<TrustAnchor> = emptyList()
            override val embeddedMasterList: MasterListInfo? = null

            override fun findBySubject(issuer: X500Principal): List<TrustAnchor> = emptyList()

            override fun findBySubjectKeyId(keyIdentifier: ByteArray): List<TrustAnchor> = emptyList()
        }
}
