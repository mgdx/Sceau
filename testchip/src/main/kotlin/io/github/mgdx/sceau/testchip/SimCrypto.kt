package io.github.mgdx.sceau.testchip

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.DESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.macs.ISO9797Alg3Mac
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitives cryptographiques côté puce, écrites d'après ICAO 9303 partie 11 (§9.7, §9.8) et
 * BSI TR-03110 partie 3 (§A.2.3), sans passer par le code de JMRTD utilisé par Sceau : seuls
 * les moteurs de chiffrement de BouncyCastle sont réutilisés.
 */
object SimCrypto {
    /** Chiffrement de la messagerie sécurisée : 3DES (BAC, CA 3DES) ou AES (CA AES). */
    enum class Algorithm(
        val blockSize: Int,
    ) {
        DESEDE(8),
        AES(16),
    }

    /** Remplissage ISO/IEC 9797-1 méthode 2 : 0x80 puis des zéros jusqu'au multiple de [blockSize]. */
    fun pad(
        data: ByteArray,
        blockSize: Int,
    ): ByteArray {
        val length = (data.size / blockSize + 1) * blockSize
        return data.copyOf(length).also { it[data.size] = 0x80.toByte() }
    }

    /** Retire le remplissage méthode 2 ; null s'il est mal formé. */
    fun unpad(data: ByteArray): ByteArray? {
        var index = data.size - 1
        while (index >= 0 && data[index] == 0.toByte()) index--
        if (index < 0 || data[index] != 0x80.toByte()) return null
        return data.copyOf(index)
    }

    /**
     * Fonction de dérivation de clé KDF(K, c) = H(K ‖ c) (ICAO 9303-11 §9.7.1) : SHA-1 pour
     * 3DES et AES-128, SHA-256 pour AES-192 et AES-256. Pour 3DES, renvoie Ka ‖ Kb (16 octets).
     */
    fun deriveKey(
        secret: ByteArray,
        counter: Int,
        algorithm: Algorithm,
        keyLength: Int,
    ): ByteArray {
        val digest = MessageDigest.getInstance(if (keyLength <= AES_128) "SHA-1" else "SHA-256")
        digest.update(secret)
        digest.update(byteArrayOf(0, 0, 0, counter.toByte()))
        val hash = digest.digest()
        return if (algorithm == Algorithm.DESEDE) hash.copyOf(DESEDE_KEY_SEED) else hash.copyOf(keyLength / Byte.SIZE_BITS)
    }

    /** Graine de clé BAC (ICAO 9303-11 §9.7.2) : SHA-1 des champs MRZ et de leurs chiffres de contrôle, tronqué à 16 octets. */
    fun bacKeySeed(
        documentNumber: String,
        dateOfBirth: String,
        dateOfExpiry: String,
    ): ByteArray = mrzPassword(documentNumber, dateOfBirth, dateOfExpiry).copyOf(DESEDE_KEY_SEED)

    /**
     * Mot de passe PACE dérivé de la MRZ, f(π) (ICAO 9303-11 §9.7.3) : SHA-1 des champs MRZ et
     * de leurs chiffres de contrôle, sur 20 octets (non tronqué, contrairement à BAC).
     */
    fun mrzPassword(
        documentNumber: String,
        dateOfBirth: String,
        dateOfExpiry: String,
    ): ByteArray {
        val number = documentNumber.padEnd(MRZ_DOCUMENT_NUMBER_LENGTH, '<')
        val info = number + checkDigit(number) + dateOfBirth + checkDigit(dateOfBirth) + dateOfExpiry + checkDigit(dateOfExpiry)
        return MessageDigest.getInstance("SHA-1").digest(info.toByteArray(Charsets.US_ASCII))
    }

    /** Chiffre de contrôle MRZ (ICAO 9303-3 §4.9) : pondération 7-3-1, modulo 10. */
    fun checkDigit(field: String): Char {
        val weights = intArrayOf(7, 3, 1)
        val sum =
            field.withIndex().sumOf { (index, char) ->
                val value =
                    when (char) {
                        in '0'..'9' -> char - '0'
                        in 'A'..'Z' -> char - 'A' + 10
                        else -> 0
                    }
                value * weights[index % 3]
            }
        return '0' + sum % 10
    }

    /** Chiffrement ou déchiffrement CBC sans remplissage. */
    fun cbc(
        encrypt: Boolean,
        algorithm: Algorithm,
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val (transformation, keySpec) =
            when (algorithm) {
                Algorithm.DESEDE -> "DESede/CBC/NoPadding" to SecretKeySpec(key + key.copyOf(DES_KEY), "DESede")
                Algorithm.AES -> "AES/CBC/NoPadding" to SecretKeySpec(key, "AES")
            }
        val cipher = Cipher.getInstance(transformation, TestCrypto.provider)
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /**
     * AES-ECB d'un bloc : vecteur d'initialisation de la messagerie sécurisée AES, E(KSenc, SSC).
     * ECB est imposé par ICAO 9303-11 pour ce seul bloc (lint GetInstance, vu par le lint de
     * `:app` depuis que le mode démo de debug dépend de ce module).
     */
    @Suppress("GetInstance")
    fun aesBlock(
        key: ByteArray,
        block: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding", TestCrypto.provider)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    /**
     * MAC de 8 octets sur des données déjà remplies (méthode 2) : ISO/IEC 9797-1 algorithme 3
     * (DES « retail MAC ») pour 3DES, AES-CMAC tronqué à 8 octets pour AES.
     */
    fun mac(
        algorithm: Algorithm,
        key: ByteArray,
        paddedData: ByteArray,
    ): ByteArray {
        require(paddedData.size % algorithm.blockSize == 0) { "données non remplies" }
        val mac =
            when (algorithm) {
                Algorithm.DESEDE -> ISO9797Alg3Mac(DESEngine())
                Algorithm.AES -> CMac(AESEngine.newInstance(), MAC_LENGTH * Byte.SIZE_BITS)
            }
        mac.init(KeyParameter(key))
        mac.update(paddedData, 0, paddedData.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    /** AES-CMAC tronqué à 8 octets, sans remplissage préalable (jetons d'authentification PACE). */
    fun cmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = CMac(AESEngine.newInstance(), MAC_LENGTH * Byte.SIZE_BITS)
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    const val MAC_LENGTH = 8
    private const val AES_128 = 128
    private const val DES_KEY = 8
    private const val DESEDE_KEY_SEED = 16
    private const val MRZ_DOCUMENT_NUMBER_LENGTH = 9
}
