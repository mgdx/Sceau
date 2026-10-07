package io.github.mgdx.sceau.core.verify

import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1String
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.RSASSAPSSparams
import org.bouncycastle.asn1.teletrust.TeleTrusTObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9FieldID
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.DefaultAlgorithmNameFinder
import org.bouncycastle.operator.OperatorCreationException
import org.bouncycastle.util.encoders.Hex
import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import javax.crypto.interfaces.DHPublicKey
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.pkcs.RSAPublicKey as Asn1RsaPublicKey
import org.bouncycastle.jce.interfaces.ECPublicKey as BcECPublicKey

/**
 * Outils cryptographiques communs à la vérification.
 *
 * Le fournisseur BouncyCastle est une instance explicite passée à chaque API : sur Android,
 * le fournisseur système nommé "BC" est une version tronquée qui ne connaît ni les courbes
 * Brainpool ni les paramètres EC explicites.
 */
internal object Crypto {
    val provider: Provider by lazy { BouncyCastleProvider() }

    private val nameFinder = DefaultAlgorithmNameFinder()

    /** Nom lisible d'un algorithme (ex. "SHA256WITHECDSA"), ou l'OID s'il est inconnu. */
    fun algorithmName(oid: ASN1ObjectIdentifier): String = nameFinder.getAlgorithmName(oid)

    /** Vrai si BouncyCastle connaît un nom pour cet OID. */
    fun isKnownAlgorithm(oid: ASN1ObjectIdentifier): Boolean = nameFinder.hasAlgorithmName(oid)

    /** Empreinte SHA-256 d'un encodage DER, en hexadécimal minuscule sans séparateur. */
    fun sha256Hex(encoded: ByteArray): String = Hex.toHexString(MessageDigest.getInstance("SHA-256").digest(encoded))

    fun fingerprint(certificate: X509Certificate): String = sha256Hex(certificate.encoded)

    /** Code pays (attribut C du sujet) en majuscules, ou null s'il est absent. */
    fun country(certificate: X509Certificate): String? =
        runCatching { country(X500Name.getInstance(certificate.subjectX500Principal.encoded)) }.getOrNull()

    /** Code pays (attribut C) d'un nom X.500 en majuscules, ou null s'il est absent. */
    fun country(name: X500Name): String? =
        runCatching {
            name
                .getRDNs(BCStyle.C)
                .firstOrNull()
                ?.first
                ?.value
                ?.let { (it as? ASN1String)?.string ?: it.toString() }
                ?.trim()
                ?.uppercase()
        }.getOrNull()

    /**
     * Fonctions de hachage cassées, refusées pour les empreintes des DG, la signature du SOD, les
     * certificats et l'AA (audit V9) : MD2, MD4, MD5, RIPEMD-128, seules ou dans un OID de
     * signature. SHA-1 reste accepté : des documents encore valides l'emploient.
     */
    private val WEAK_ALGORITHMS: Set<ASN1ObjectIdentifier> =
        setOf(
            PKCSObjectIdentifiers.md2,
            PKCSObjectIdentifiers.md4,
            PKCSObjectIdentifiers.md5,
            TeleTrusTObjectIdentifiers.ripemd128,
            PKCSObjectIdentifiers.md2WithRSAEncryption,
            PKCSObjectIdentifiers.md4WithRSAEncryption,
            PKCSObjectIdentifiers.md5WithRSAEncryption,
            TeleTrusTObjectIdentifiers.rsaSignatureWithripemd128,
        )

    /** Noms JCA des mêmes fonctions de hachage (AA, où l'algorithme est désigné par son nom). */
    private val WEAK_DIGEST_NAMES = setOf("MD2", "MD4", "MD5", "RIPEMD128")

    /** Taille minimale d'une clé RSA de signature (DS, émetteurs de la chaîne, AA). */
    const val MIN_RSA_BITS = 1024

    /**
     * Bornes hautes des clés publiques (audit V30), le double au moins des plus grandes clés
     * réelles : modulus RSA, exposant public RSA, nombre premier p de DH, corps de base EC.
     */
    const val MAX_RSA_BITS = 8192
    const val MAX_RSA_EXPONENT_BITS = 64
    const val MAX_DH_BITS = 8192
    const val MAX_EC_FIELD_BITS = 1024

    /** Vrai si [algorithm] (hachage ou signature, y compris le hachage d'un RSASSA-PSS) est cassé. */
    fun isWeakAlgorithm(algorithm: AlgorithmIdentifier): Boolean {
        if (algorithm.algorithm in WEAK_ALGORITHMS) return true
        if (algorithm.algorithm != PKCSObjectIdentifiers.id_RSASSA_PSS) return false
        val parameters = runCatching { RSASSAPSSparams.getInstance(algorithm.parameters) }.getOrNull() ?: return false
        return parameters.hashAlgorithm.algorithm in WEAK_ALGORITHMS
    }

    /** Vrai si [digestName] ("MD5", "RIPEMD-128"…) désigne une fonction de hachage cassée. */
    fun isWeakDigestName(digestName: String): Boolean = digestName.uppercase().replace("-", "") in WEAK_DIGEST_NAMES

    /**
     * Description (algorithme et taille, jamais la clé) de [key] si sa taille sort des bornes
     * admises, sinon null (audits V9 et V30). Borne basse : RSA de moins de [MIN_RSA_BITS] bits.
     * Bornes hautes, contrôlées avant tout calcul : une clé géante fournie par la puce ou par un
     * certificat coûterait des minutes de calcul non interruptible (`modPow`), alors qu'aucune
     * clé réelle n'en approche (RSA de 2 048 à 4 096 bits, courbes de 256 à 521 bits). Un type de
     * clé inconnu donne null : la vérification qui suit le traite, comme avant.
     */
    fun unsupportedKey(key: PublicKey): String? =
        when (key) {
            is RSAPublicKey -> {
                rsaBounds(key.modulus, key.publicExponent)
            }

            is ECPublicKey -> {
                key.params
                    ?.curve
                    ?.field
                    ?.fieldSize
                    ?.let(::ecBounds)
            }

            is BcECPublicKey -> {
                key.parameters
                    ?.curve
                    ?.fieldSize
                    ?.let(::ecBounds)
            }

            is DHPublicKey -> {
                key.params?.p?.let { dhBounds(it.bitLength()) }
            }

            else -> {
                null
            }
        }

    /**
     * Même contrôle que [unsupportedKey] sur une clé encore encodée (certificat, `SecurityInfo`),
     * sans la reconstruire : le fournisseur valide la clé en la reconstruisant (tests de primalité,
     * puissance modulaire), à un coût qui croît avec sa taille. Une structure illisible donne null :
     * la vérification qui suit la refuse.
     */
    fun unsupportedKey(info: SubjectPublicKeyInfo): String? =
        runCatching {
            when (info.algorithm.algorithm) {
                PKCSObjectIdentifiers.rsaEncryption, PKCSObjectIdentifiers.id_RSASSA_PSS -> {
                    val key = Asn1RsaPublicKey.getInstance(info.parsePublicKey())
                    rsaBounds(key.modulus, key.publicExponent)
                }

                X9ObjectIdentifiers.id_ecPublicKey -> {
                    explicitFieldBits(info.algorithm.parameters)?.let(::ecBounds)
                }

                PKCSObjectIdentifiers.dhKeyAgreement, X9ObjectIdentifiers.dhpublicnumber -> {
                    // DHParameter (PKCS #3) comme DomainParameters (X9.42) commencent par p.
                    val p = ASN1Integer.getInstance(ASN1Sequence.getInstance(info.algorithm.parameters).getObjectAt(0))
                    dhBounds(p.positiveValue.bitLength())
                }

                else -> {
                    null
                }
            }
        }.getOrNull()

    /**
     * Taille du corps de base de paramètres EC explicites (`ECParameters` de X9.62), lue dans
     * `fieldID` sans construire la courbe ; null pour une courbe nommée (taille connue et bornée).
     */
    private fun explicitFieldBits(parameters: ASN1Encodable?): Int? {
        val sequence = parameters as? ASN1Sequence ?: return null
        val field = X9FieldID.getInstance(sequence.getObjectAt(1))
        return when (field.identifier) {
            X9ObjectIdentifiers.prime_field -> {
                ASN1Integer.getInstance(field.parameters).positiveValue.bitLength()
            }

            // Caractéristique 2 : la taille du corps est le degré m, premier élément.
            X9ObjectIdentifiers.characteristic_two_field -> {
                val m = ASN1Integer.getInstance(ASN1Sequence.getInstance(field.parameters).getObjectAt(0)).positiveValue
                if (m.bitLength() < Int.SIZE_BITS) m.toInt() else Int.MAX_VALUE
            }

            else -> {
                null
            }
        }
    }

    private fun rsaBounds(
        modulus: BigInteger,
        publicExponent: BigInteger,
    ): String? {
        val bits = modulus.bitLength()
        val exponentBits = publicExponent.bitLength()
        return when {
            bits < MIN_RSA_BITS || bits > MAX_RSA_BITS -> "RSA-$bits"
            exponentBits > MAX_RSA_EXPONENT_BITS -> "RSA-$bits/e-$exponentBits"
            else -> null
        }
    }

    private fun ecBounds(fieldBits: Int): String? = "EC-$fieldBits".takeIf { fieldBits > MAX_EC_FIELD_BITS }

    private fun dhBounds(primeBits: Int): String? = "DH-$primeBits".takeIf { primeBits > MAX_DH_BITS }

    fun principal(name: X500Name): X500Principal = X500Principal(name.encoded)

    fun subject(holder: X509CertificateHolder): String = principal(holder.subject).name

    /**
     * Vrai si l'exception traduit un algorithme inconnu du fournisseur (et non une
     * signature invalide ou une donnée mal formée).
     */
    fun isUnsupportedAlgorithm(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(MAX_CAUSES).any {
            it is NoSuchAlgorithmException ||
                it is OperatorCreationException ||
                (it is IllegalArgumentException && it.message.orEmpty().startsWith("Unknown signature type"))
        }

    private const val MAX_CAUSES = 10
}
