package io.github.mgdx.sceau.jp2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Jpeg2000ProtocolTest {
    @Test
    fun `les morceaux couvrent exactement le tableau`() {
        for (total in listOf(0, 1, 255, 256, 257, 1000, 4096 * 4096)) {
            val chunks = Jpeg2000Protocol.chunks(total, 256)
            var expected = 0
            for ((start, length) in chunks) {
                assertEquals(expected, start)
                assertTrue(length in 1..256)
                expected += length
            }
            assertEquals(total, expected)
        }
    }

    @Test
    fun `un morceau binder reste loin de la limite de transaction`() {
        // Tampon de transaction : 1 Mo partagé par tout le processus.
        assertTrue(Jpeg2000Protocol.BYTE_CHUNK <= 256 * 1024)
        assertTrue(Jpeg2000Protocol.INT_CHUNK * Int.SIZE_BYTES <= 256 * 1024)
    }

    @Test
    fun `un portrait est reassemble au pixel pres`() {
        val width = 480
        val height = 640
        val source = IntArray(width * height) { it * 31 + 7 }
        val pixels = SequentialPixels(width, height)
        for ((start, count) in Jpeg2000Protocol.chunks(source.size, Jpeg2000Protocol.INT_CHUNK)) {
            assertTrue(pixels.put(start, source.copyOfRange(start, start + count)))
        }
        assertTrue(pixels.isComplete)
        assertArrayEquals(source, pixels.data)
        pixels.wipe()
        assertTrue(pixels.data.all { it == 0 })
        assertFalse(pixels.isComplete)
    }

    @Test
    fun `un flux est reassemble a l'octet pres puis efface`() {
        val source = ByteArray(600_000) { (it % 251).toByte() }
        val bytes = SequentialBytes(source.size)
        for ((start, length) in Jpeg2000Protocol.chunks(source.size, Jpeg2000Protocol.BYTE_CHUNK)) {
            assertTrue(bytes.put(start, source.copyOfRange(start, start + length)))
        }
        assertTrue(bytes.isComplete)
        assertArrayEquals(source, bytes.data)
        bytes.wipe()
        assertTrue(bytes.data.all { it == 0.toByte() })
    }

    @Test
    fun `morceau hors sequence, vide ou debordant refuse`() {
        val bytes = SequentialBytes(10)
        assertFalse(bytes.put(1, ByteArray(2)))
        assertFalse(bytes.put(0, ByteArray(0)))
        assertFalse(bytes.put(0, ByteArray(11)))
        assertTrue(bytes.put(0, ByteArray(6)))
        assertFalse(bytes.put(0, ByteArray(2)))
        assertFalse(bytes.put(6, ByteArray(5)))
        assertTrue(bytes.put(6, ByteArray(4)))
        assertTrue(bytes.isComplete)
        assertFalse(bytes.put(10, ByteArray(1)))

        val pixels = SequentialPixels(2, 2)
        assertFalse(pixels.put(-1, IntArray(1)))
        assertFalse(pixels.put(0, IntArray(5)))
        assertTrue(pixels.put(0, IntArray(4)))
    }

    @Test
    fun `bornes du flux et de l'image`() {
        assertFalse(Jpeg2000Protocol.isValidInputLength(0))
        assertTrue(Jpeg2000Protocol.isValidInputLength(1))
        assertTrue(Jpeg2000Protocol.isValidInputLength(16 * 1024 * 1024))
        assertFalse(Jpeg2000Protocol.isValidInputLength(16 * 1024 * 1024 + 1))

        assertTrue(Jpeg2000Protocol.isValidOutput(1, 1))
        assertTrue(Jpeg2000Protocol.isValidOutput(2048, 2048))
        assertFalse(Jpeg2000Protocol.isValidOutput(2049, 1))
        assertFalse(Jpeg2000Protocol.isValidOutput(1, 0))
        assertFalse(Jpeg2000Protocol.isValidOutput(-1, 10))
        assertFalse(Jpeg2000Protocol.isValidOutput(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `lecture de pixels bornee`() {
        val max = Jpeg2000Protocol.INT_CHUNK
        assertTrue(Jpeg2000Protocol.isValidSlice(100, 0, 100, max))
        assertTrue(Jpeg2000Protocol.isValidSlice(100, 99, 1, max))
        assertFalse(Jpeg2000Protocol.isValidSlice(100, 99, 2, max))
        assertFalse(Jpeg2000Protocol.isValidSlice(100, -1, 1, max))
        assertFalse(Jpeg2000Protocol.isValidSlice(100, 0, 0, max))
        assertFalse(Jpeg2000Protocol.isValidSlice(100, Int.MAX_VALUE, 1, max))
        assertFalse(Jpeg2000Protocol.isValidSlice(1_000_000, 0, max + 1, max))
    }

    @Test
    fun `reconnaissance des UID isoles`() {
        assertFalse(isIsolatedUid(10_123))
        assertFalse(isIsolatedUid(1_000))
        assertTrue(isIsolatedUid(99_000))
        assertTrue(isIsolatedUid(99_999))
        assertTrue(isIsolatedUid(1_099_123))
        assertFalse(isIsolatedUid(1_010_123))
    }
}
