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
 * Chip-side cryptographic primitives, written from ICAO 9303 part 11 (§9.7, §9.8) and
 * BSI TR-03110 part 3 (§A.2.3), without going through the JMRTD code used by Sceau: only
 * the BouncyCastle cipher engines are reused.
 */
object SimCrypto {
    /** Secure messaging cipher: 3DES (BAC, 3DES CA) or AES (AES CA). */
    enum class Algorithm(
        val blockSize: Int,
    ) {
        DESEDE(8),
        AES(16),
    }

    /** ISO/IEC 9797-1 padding method 2: 0x80 then zeros up to the multiple of [blockSize]. */
    fun pad(
        data: ByteArray,
        blockSize: Int,
    ): ByteArray {
        val length = (data.size / blockSize + 1) * blockSize
        return data.copyOf(length).also { it[data.size] = 0x80.toByte() }
    }

    /** Removes method 2 padding; null if it is malformed. */
    fun unpad(data: ByteArray): ByteArray? {
        var index = data.size - 1
        while (index >= 0 && data[index] == 0.toByte()) index--
        if (index < 0 || data[index] != 0x80.toByte()) return null
        return data.copyOf(index)
    }

    /**
     * Key derivation function KDF(K, c) = H(K ‖ c) (ICAO 9303-11 §9.7.1): SHA-1 for
     * 3DES and AES-128, SHA-256 for AES-192 and AES-256. For 3DES, returns Ka ‖ Kb (16 bytes).
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

    /** BAC key seed (ICAO 9303-11 §9.7.2): SHA-1 of the MRZ fields and their check digits, truncated to 16 bytes. */
    fun bacKeySeed(
        documentNumber: String,
        dateOfBirth: String,
        dateOfExpiry: String,
    ): ByteArray = mrzPassword(documentNumber, dateOfBirth, dateOfExpiry).copyOf(DESEDE_KEY_SEED)

    /**
     * PACE password derived from the MRZ, f(π) (ICAO 9303-11 §9.7.3): SHA-1 of the MRZ fields and
     * their check digits, on 20 bytes (not truncated, unlike BAC).
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

    /** MRZ check digit (ICAO 9303-3 §4.9): 7-3-1 weighting, modulo 10. */
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

    /** CBC encryption or decryption without padding. */
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
     * Single-block AES-ECB: initialization vector of AES secure messaging, E(KSenc, SSC).
     * ECB is mandated by ICAO 9303-11 for this single block (lint GetInstance, seen by the lint of
     * `:app` since the debug demo mode depends on this module).
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
     * 8-byte MAC over already padded data (method 2): ISO/IEC 9797-1 algorithm 3
     * (DES “retail MAC”) for 3DES, AES-CMAC truncated to 8 bytes for AES.
     */
    fun mac(
        algorithm: Algorithm,
        key: ByteArray,
        paddedData: ByteArray,
    ): ByteArray {
        require(paddedData.size % algorithm.blockSize == 0) { "data not padded" }
        val mac =
            when (algorithm) {
                Algorithm.DESEDE -> ISO9797Alg3Mac(DESEngine())
                Algorithm.AES -> CMac(AESEngine.newInstance(), MAC_LENGTH * Byte.SIZE_BITS)
            }
        mac.init(KeyParameter(key))
        mac.update(paddedData, 0, paddedData.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    /** AES-CMAC truncated to 8 bytes, without prior padding (PACE authentication tokens). */
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
