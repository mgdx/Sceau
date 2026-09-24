package io.github.mgdx.sceau.testchip

import net.sf.scuba.data.Gender
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.bsi.BSIObjectIdentifiers
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.ISO9796d2Signer
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.jmrtd.lds.ActiveAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.MRZInfo
import java.security.KeyPair
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateKey
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Type de la clé d'Active Authentication (DG15). */
enum class AaKeyType { RSA, EC }

/**
 * Document factice : DG bruts, EF.SOD signé et clés privées de la puce (CA, AA).
 *
 * Les champs sont ceux qu'attend [io.github.mgdx.sceau.core.verify.PassiveAuthentication.verify].
 */
class TestDocument(
    val pki: TestPki,
    val ds: TestCredential,
    val sod: ByteArray,
    val dataGroups: Map<Int, ByteArray>,
    /** Paire de clés de Chip Authentication (DG14), null si DG14 n'en contient pas. */
    val caKeyPair: KeyPair?,
    /** Paire de clés d'Active Authentication (DG15), null sans DG15. */
    val aaKeyPair: KeyPair?,
    /** Algorithme de hachage ECDSA annoncé dans DG14 pour l'AA, null en RSA. */
    val aaDigestAlgorithm: String?,
    val dateOfIssue: LocalDate?,
    val dateOfExpiry: LocalDate,
    val documentCode: String,
) {
    /** Copie dont le DG [number] diffère d'un octet de celui qui a été signé. */
    fun withModifiedDataGroup(number: Int): TestDocument {
        val modified = dataGroups.getValue(number).copyOf()
        modified[modified.size - 1] = (modified[modified.size - 1].toInt() xor 0x01).toByte()
        return withDataGroups(dataGroups + (number to modified))
    }

    fun withDataGroups(groups: Map<Int, ByteArray>): TestDocument =
        TestDocument(pki, ds, sod, groups, caKeyPair, aaKeyPair, aaDigestAlgorithm, dateOfIssue, dateOfExpiry, documentCode)

    /** Réponse de la puce au challenge AA, signée avec la clé de DG15. */
    fun aaResponse(challenge: ByteArray): ByteArray =
        TestActiveAuthentication.respond(checkNotNull(aaKeyPair).private, challenge, aaDigestAlgorithm ?: "SHA-1", pki)

    /**
     * Options du document. Par défaut : DG1 (passeport TD3), DG2, DG14 (clé CA EC),
     * DG15 (clé AA RSA 1024), SOD SHA-256 signé par [TestPki.ds] avec DS embarqué.
     */
    class Builder(
        private val pki: TestPki,
    ) {
        var ds: TestCredential = pki.ds
        var sod: SodOptions = SodOptions()
        var chipAuthentication: Boolean = true
        var activeAuthentication: AaKeyType? = AaKeyType.RSA

        /** Algorithme de hachage ECDSA déclaré dans DG14 pour une AA EC (null : pas d'ActiveAuthenticationInfo). */
        var aaDigestAlgorithm: String? = "SHA-256"
        var dateOfIssue: LocalDate? = DEFAULT_DATE_OF_ISSUE
        var dateOfExpiry: LocalDate = DEFAULT_DATE_OF_EXPIRY
        var documentCode: String = "P"

        fun build(): TestDocument {
            val groups = sortedMapOf<Int, ByteArray>()
            groups[1] = TestDataGroups.dg1(documentCode, dateOfExpiry)
            groups[2] = TestDataGroups.dg2(pki)
            val caKeys = if (chipAuthentication) pki.generateEc(TestPki.CURVE) else null
            val aaKeys =
                when (activeAuthentication) {
                    AaKeyType.RSA -> pki.generateRsa(AA_RSA_BITS)
                    AaKeyType.EC -> pki.generateEc(TestPki.CURVE)
                    null -> null
                }
            val aaDigest = if (activeAuthentication == AaKeyType.EC) aaDigestAlgorithm else null
            val dg14Infos = mutableListOf<SecurityInfo>()
            caKeys?.let { dg14Infos += ChipAuthenticationPublicKeyInfo(it.public) }
            aaDigest?.let { dg14Infos += ActiveAuthenticationInfo(TestActiveAuthentication.ecdsaOid(it)) }
            if (dg14Infos.isNotEmpty()) groups[14] = DG14File(dg14Infos).encoded
            aaKeys?.let { groups[15] = DG15File(it.public).encoded }

            val sodBytes = TestSod.build(ds, groups, sod)
            return TestDocument(pki, ds, sodBytes, groups, caKeys, aaKeys, aaDigest, dateOfIssue, dateOfExpiry, documentCode)
        }
    }

    companion object {
        val DEFAULT_DATE_OF_ISSUE: LocalDate = LocalDate.of(2021, 6, 1)
        val DEFAULT_DATE_OF_EXPIRY: LocalDate = LocalDate.of(2031, 5, 31)
        const val AA_RSA_BITS = 1024
    }
}

/** DG factices. Aucune donnée réelle : identité fictive. */
object TestDataGroups {
    private val YYMMDD = DateTimeFormatter.ofPattern("yyMMdd")

    /** DG1 valide (MRZ TD3) construit avec JMRTD. */
    fun dg1(
        documentCode: String = "P",
        dateOfExpiry: LocalDate = TestDocument.DEFAULT_DATE_OF_EXPIRY,
    ): ByteArray {
        val mrz =
            MRZInfo.createTD3MRZInfo(
                documentCode,
                "UTO",
                "ERIKSSON",
                "ANNA MARIA",
                "L898902C3",
                "UTO",
                "740812",
                Gender.FEMALE,
                dateOfExpiry.format(YYMMDD),
                "",
            )
        return DG1File(mrz).encoded
    }

    /** DG2 : octets quelconques sous le tag 0x75 (le contenu biométrique n'est pas vérifié ici). */
    fun dg2(pki: TestPki): ByteArray {
        val payload = ByteArray(DG2_PAYLOAD).also { pki.random.nextBytes(it) }
        return TestSod.tlv(DG2_TAG, payload)
    }

    private const val DG2_TAG = 0x75
    private const val DG2_PAYLOAD = 512
}

/** Réponses d'Active Authentication telles que les produirait la puce. */
object TestActiveAuthentication {
    /** OID ecdsa-plain-SHAxxx (BSI TR-03111) de l'`ActiveAuthenticationInfo` de DG14 (ICAO 9303-11). */
    fun ecdsaOid(digestAlgorithm: String): String =
        when (digestAlgorithm.uppercase().replace("-", "")) {
            "SHA1" -> BSIObjectIdentifiers.ecdsa_plain_SHA1.id
            "SHA224" -> BSIObjectIdentifiers.ecdsa_plain_SHA224.id
            "SHA256" -> BSIObjectIdentifiers.ecdsa_plain_SHA256.id
            "SHA384" -> BSIObjectIdentifiers.ecdsa_plain_SHA384.id
            "SHA512" -> BSIObjectIdentifiers.ecdsa_plain_SHA512.id
            else -> error("digest")
        }

    /**
     * RSA : ISO/IEC 9796-2 schéma 1, récupération partielle ; M1 aléatoire remplit toute la
     * capacité du bloc, M2 = challenge. SHA-1 → trailer implicite 0xBC, sinon explicite.
     * EC : signature ECDSA brute r‖s (chaque moitié de la taille du corps de la courbe).
     */
    fun respond(
        key: PrivateKey,
        challenge: ByteArray,
        digestAlgorithm: String,
        pki: TestPki,
    ): ByteArray =
        when (key) {
            is RSAPrivateKey -> respondRsa(key, challenge, digestAlgorithm, pki)
            is ECPrivateKey -> respondEc(key, challenge, digestAlgorithm)
            else -> error("key")
        }

    private fun respondRsa(
        key: RSAPrivateKey,
        challenge: ByteArray,
        digestAlgorithm: String,
        pki: TestPki,
    ): ByteArray {
        val digest: Digest = if (digestAlgorithm.replace("-", "").uppercase() == "SHA1") SHA1Digest() else SHA256Digest()
        val implicit = digest is SHA1Digest
        val signer = ISO9796d2Signer(RSAEngine(), digest, implicit)
        signer.init(true, PrivateKeyFactory.createKey(key.encoded) as RSAKeyParameters)
        val blockLength = (key.modulus.bitLength() + 7) / 8
        val m1 = ByteArray(blockLength - digest.digestSize - if (implicit) 2 else 3).also { pki.random.nextBytes(it) }
        signer.update(m1, 0, m1.size)
        signer.update(challenge, 0, challenge.size)
        return signer.generateSignature()
    }

    private fun respondEc(
        key: ECPrivateKey,
        challenge: ByteArray,
        digestAlgorithm: String,
    ): ByteArray {
        val signature = Signature.getInstance("${digestAlgorithm.replace("-", "")}withECDSA", TestCrypto.provider)
        signature.initSign(key)
        signature.update(challenge)
        val der = ASN1Sequence.getInstance(signature.sign())
        val size = (key.params.curve.field.fieldSize + 7) / 8
        return unsigned(der.getObjectAt(0), size) + unsigned(der.getObjectAt(1), size)
    }

    private fun unsigned(
        value: ASN1Encodable,
        size: Int,
    ): ByteArray {
        val bytes = (value as ASN1Integer).value.toByteArray()
        val stripped = if (bytes.size > size) bytes.copyOfRange(bytes.size - size, bytes.size) else bytes
        return ByteArray(size - stripped.size) + stripped
    }
}
