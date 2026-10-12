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
import org.jmrtd.lds.DisplayedImageInfo
import org.jmrtd.lds.ImageInfo
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG7File
import org.jmrtd.lds.icao.MRZInfo
import java.security.KeyPair
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateKey
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Active Authentication key type (DG15). */
enum class AaKeyType { RSA, EC }

/**
 * Fake document: raw DGs, signed EF.SOD and the chip's private keys (CA, AA).
 *
 * The fields are the ones expected by [io.github.mgdx.sceau.core.verify.PassiveAuthentication.verify].
 */
class TestDocument(
    val pki: TestPki,
    val ds: TestCredential,
    val sod: ByteArray,
    val dataGroups: Map<Int, ByteArray>,
    /** Chip Authentication key pair (DG14), null if DG14 holds none. */
    val caKeyPair: KeyPair?,
    /** Active Authentication key pair (DG15), null without DG15. */
    val aaKeyPair: KeyPair?,
    /** ECDSA hash algorithm announced in DG14 for AA, null with RSA. */
    val aaDigestAlgorithm: String?,
    val dateOfIssue: LocalDate?,
    val dateOfExpiry: LocalDate,
    val documentCode: String,
) {
    /** Copy whose DG [number] differs by one byte from the signed one. */
    fun withModifiedDataGroup(number: Int): TestDocument {
        val modified = dataGroups.getValue(number).copyOf()
        modified[modified.size - 1] = (modified[modified.size - 1].toInt() xor 0x01).toByte()
        return withDataGroups(dataGroups + (number to modified))
    }

    fun withDataGroups(groups: Map<Int, ByteArray>): TestDocument =
        TestDocument(pki, ds, sod, groups, caKeyPair, aaKeyPair, aaDigestAlgorithm, dateOfIssue, dateOfExpiry, documentCode)

    /** Chip response to the AA challenge, signed with the DG15 key. */
    fun aaResponse(challenge: ByteArray): ByteArray =
        TestActiveAuthentication.respond(checkNotNull(aaKeyPair).private, challenge, aaDigestAlgorithm ?: "SHA-1", pki)

    /**
     * Document options. Defaults: DG1 (TD3 passport), DG2, DG14 (EC CA key),
     * DG15 (RSA 1024 AA key), SHA-256 SOD signed by [TestPki.ds] with the DS embedded.
     */
    class Builder(
        private val pki: TestPki,
    ) {
        var ds: TestCredential = pki.ds
        var sod: SodOptions = SodOptions()
        var chipAuthentication: Boolean = true
        var activeAuthentication: AaKeyType? = AaKeyType.RSA

        /** ECDSA hash algorithm declared in DG14 for an EC AA (null: no ActiveAuthenticationInfo). */
        var aaDigestAlgorithm: String? = "SHA-256"

        /** RSA key size for Active Authentication (DG15). */
        var aaRsaBits: Int = AA_RSA_BITS
        var dateOfIssue: LocalDate? = DEFAULT_DATE_OF_ISSUE
        var dateOfExpiry: LocalDate = DEFAULT_DATE_OF_EXPIRY
        var documentCode: String = "P"

        /** Issuing state in DG1 (three-letter ICAO code); defaults to the fictitious specimen country. */
        var issuingState: String = TestDataGroups.SPECIMEN_STATE

        /** Document number in DG1. */
        var documentNumber: String = TestDataGroups.SPECIMEN_DOCUMENT_NUMBER

        /** Additional DGs (DG11, DG12…), signed in the SOD like the others. */
        var extraDataGroups: Map<Int, ByteArray> = emptyMap()

        fun build(): TestDocument {
            val groups = sortedMapOf<Int, ByteArray>()
            groups[1] = TestDataGroups.dg1(documentCode, dateOfExpiry, issuingState, documentNumber)
            groups.putAll(extraDataGroups)
            groups[2] = TestDataGroups.dg2(pki)
            val caKeys = if (chipAuthentication) pki.generateEc(TestPki.CURVE) else null
            val aaKeys =
                when (activeAuthentication) {
                    AaKeyType.RSA -> pki.generateRsa(aaRsaBits)
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

/** Fake DGs. No real data: fictitious identity. */
object TestDataGroups {
    private val YYMMDD = DateTimeFormatter.ofPattern("yyMMdd")

    /** Valid DG1 built with JMRTD: TD1 MRZ (card) for code "A", "C" or "I", TD3 otherwise. */
    fun dg1(
        documentCode: String = "P",
        dateOfExpiry: LocalDate = TestDocument.DEFAULT_DATE_OF_EXPIRY,
        issuingState: String = SPECIMEN_STATE,
        documentNumber: String = SPECIMEN_DOCUMENT_NUMBER,
    ): ByteArray {
        val mrz =
            if (documentCode.first() in "ACI") {
                MRZInfo.createTD1MRZInfo(
                    documentCode,
                    issuingState,
                    documentNumber,
                    "",
                    "740812",
                    Gender.FEMALE,
                    dateOfExpiry.format(YYMMDD),
                    "UTO",
                    "",
                    "ERIKSSON",
                    "ANNA MARIA",
                )
            } else {
                MRZInfo.createTD3MRZInfo(
                    documentCode,
                    issuingState,
                    "ERIKSSON",
                    "ANNA MARIA",
                    documentNumber,
                    "UTO",
                    "740812",
                    Gender.FEMALE,
                    dateOfExpiry.format(YYMMDD),
                    "",
                )
            }
        return DG1File(mrz).encoded
    }

    /** DG2: arbitrary bytes under tag 0x75 (the biometric content is not checked here). */
    fun dg2(pki: TestPki): ByteArray {
        val payload = ByteArray(DG2_PAYLOAD).also { pki.random.nextBytes(it) }
        return TestSod.tlv(DG2_TAG, payload)
    }

    /**
     * DG7 (handwritten signature, ICAO 9303-10) built with JMRTD: one image per element of
     * [images], of type "signature or usual mark". Defaults to the synthetic signature of the
     * simulated CNIe.
     */
    fun dg7(vararg images: ByteArray = arrayOf(SimulatedDocuments.specimenSignature())): ByteArray =
        DG7File(images.map { DisplayedImageInfo(ImageInfo.TYPE_SIGNATURE_OR_MARK, it) }).encoded

    /** Fictitious country of the ICAO 9303 specimens (Utopia). */
    const val SPECIMEN_STATE = "UTO"

    /** Document number of the ICAO 9303 specimen. */
    const val SPECIMEN_DOCUMENT_NUMBER = "L898902C3"

    private const val DG2_TAG = 0x75
    private const val DG2_PAYLOAD = 512
}

/** Active Authentication responses as the chip would produce them. */
object TestActiveAuthentication {
    /** ecdsa-plain-SHAxxx OID (BSI TR-03111) of the DG14 `ActiveAuthenticationInfo` (ICAO 9303-11). */
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
     * RSA: ISO/IEC 9796-2 scheme 1, partial recovery; a random M1 fills the whole block
     * capacity, M2 = challenge. SHA-1 → implicit trailer 0xBC, explicit otherwise.
     * EC: raw ECDSA signature r‖s (each half the size of the curve's field).
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
