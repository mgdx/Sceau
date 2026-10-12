package io.github.mgdx.sceau.trust

import org.junit.Assert.assertEquals
import org.junit.Test

class HashingTest {
    @Test
    fun `SHA-256 in lowercase hexadecimal (FIPS 180-2 vector)`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `import file name is derived from the content`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855.ml",
            TrustStoreRepository.importFileName(ByteArray(0)),
        )
    }
}
