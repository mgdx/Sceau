package io.github.mgdx.sceau.jp2

import android.content.Intent
import android.graphics.Bitmap
import android.os.Parcel
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowProcess
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Isolated decoding service, called directly through its binder protocol (the isolated process
 * does not exist under Robolectric, nor does OpenJPEG, so only the JPEG path decodes here).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class Jpeg2000ServiceTest {
    private val service = Robolectric.buildService(Jpeg2000Service::class.java).create().get()
    private val binder = service.onBind(Intent()) as Jpeg2000Service.DecoderBinder

    @Test
    fun `JPEG decoded in the service`() {
        val size = decode(TestImages.encode(Bitmap.CompressFormat.JPEG))
        assertEquals(40 to 30, size)
    }

    @Test
    fun `oversized JPEG is scaled down below the protocol bound`() {
        val (width, height) = checkNotNull(decode(TestImages.encode(Bitmap.CompressFormat.JPEG, width = 4100, height = 8)))
        assertTrue(Jpeg2000Protocol.isValidOutput(width, height))
    }

    @Test
    fun `formats other than JPEG and JPEG 2000 are refused (audit V18)`() {
        val others =
            listOf(
                TestImages.encode(Bitmap.CompressFormat.PNG),
                TestImages.encode(Bitmap.CompressFormat.WEBP_LOSSLESS),
                TestImages.encode(Bitmap.CompressFormat.WEBP_LOSSY),
            )
        for (bytes in others) assertEquals(null, decode(bytes))
    }

    @Test
    fun `onDestroy does not wait for an ongoing transaction (audit V26)`() {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val decoding =
            thread {
                binder.lock.lock()
                try {
                    held.countDown()
                    release.await()
                } finally {
                    binder.lock.unlock()
                }
            }
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val executor = Executors.newSingleThreadExecutor()
            try {
                // Before the fix, onDestroy waited for the lock until decoding finished.
                executor.submit { service.onDestroy() }.get(5, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
            assertTrue(ShadowProcess.wasKilled(Process.myPid()))
        } finally {
            release.countDown()
            decoding.join()
        }
    }

    @Test
    fun `onDestroy wipes the received stream when the service is idle`() {
        val bytes = TestImages.encode(Bitmap.CompressFormat.JPEG)
        assertTrue(send(bytes))
        service.onDestroy()
        assertTrue(ShadowProcess.wasKilled(Process.myPid()))
        assertEquals(null, call(Jpeg2000Protocol.TX_DECODE) {})
    }

    /** Sends [bytes] then requests decoding: returns the dimensions, or null if refused. */
    private fun decode(bytes: ByteArray): Pair<Int, Int>? {
        check(send(bytes))
        return call(Jpeg2000Protocol.TX_DECODE) {}?.let { reply -> reply.readInt() to reply.readInt() }
    }

    private fun send(bytes: ByteArray): Boolean {
        if (call(Jpeg2000Protocol.TX_BEGIN) { writeInt(bytes.size) } == null) return false
        for ((start, length) in Jpeg2000Protocol.chunks(bytes.size, Jpeg2000Protocol.BYTE_CHUNK)) {
            val sent =
                call(Jpeg2000Protocol.TX_APPEND) {
                    writeInt(start)
                    writeByteArray(bytes, start, length)
                }
            if (sent == null) return false
        }
        return true
    }

    /** One transaction; returns the reply positioned after the status, or null on an error status. */
    private fun call(
        code: Int,
        write: Parcel.() -> Unit,
    ): Parcel? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        data.writeInterfaceToken(Jpeg2000Protocol.DESCRIPTOR)
        data.write()
        data.setDataPosition(0)
        check(binder.transact(code, data, reply, 0))
        data.recycle()
        reply.setDataPosition(0)
        return reply.takeIf { it.readInt() == Jpeg2000Protocol.STATUS_OK }
    }
}
