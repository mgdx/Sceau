package io.github.mgdx.sceau.jp2

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import androidx.core.graphics.createBitmap
import io.github.mgdx.sceau.jp2.Jpeg2000Protocol.STATUS_OK
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decoding of the chip's images (DG2 portrait, DG7 signature, DG12 images), in JPEG 2000 as well
 * as JPEG, delegated to the isolated process of [Jpeg2000Service] (decision D23, audit V18):
 * neither OpenJPEG nor Android's JPEG decoder receives bytes from the chip in the app's
 * process. The service accepts only JPEG and JPEG 2000, recognized by their bytes.
 *
 * Returns a mutable ARGB_8888 [Bitmap], or null if the stream cannot be decoded, if the
 * isolated process dies (bomb, native bug), if decoding exceeds
 * [Jpeg2000Protocol.TIMEOUT_MILLIS] or if the service is unavailable: never an exception
 * (except cancellation of the calling coroutine). Decodings are serialized; each one binds the
 * service, thus starts a fresh isolated process, and unbinds it as soon as it is done.
 */
object IsolatedJpeg2000Decoder {
    /** Only one decoding at a time in the app's process. */
    private val mutex = Mutex()

    /**
     * Scope of the blocking binder transfers: detached from the caller so that a timeout or a
     * cancellation does not wait for a transaction to finish (unbinding the service terminates
     * the isolated process, even in the middle of decoding, which unblocks it).
     */
    private val transferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun decode(
        context: Context,
        bytes: ByteArray,
    ): Bitmap? {
        if (!Jpeg2000Protocol.isValidInputLength(bytes.size)) return null
        return mutex.withLock { decodeLocked(context.applicationContext, bytes) }
    }

    private suspend fun decodeLocked(
        context: Context,
        bytes: ByteArray,
    ): Bitmap? {
        val connection = Connection()
        val bound =
            try {
                context.bindService(Intent(context, Jpeg2000Service::class.java), connection, Context.BIND_AUTO_CREATE)
            } catch (_: SecurityException) {
                false
            }
        var work: Deferred<SequentialPixels?>? = null
        var result: SequentialPixels? = null
        try {
            if (!bound) return null
            val job = transferScope.async { connection.binder.await()?.let { transfer(it, bytes) } }
            work = job
            result = withTimeoutOrNull(Jpeg2000Protocol.TIMEOUT_MILLIS) { job.await() }
            val pixels = result?.takeIf { it.isComplete } ?: return null
            return toBitmap(pixels)
        } finally {
            result?.wipe()
            work?.let { abandoned ->
                // Timeout or cancellation: pixels received later are wiped immediately.
                abandoned.invokeOnCompletion { cause -> if (cause == null) abandoned.getCompletedOrNull()?.wipe() }
                abandoned.cancel()
            }
            try {
                context.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                // Binding refused: nothing to unbind.
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Deferred<SequentialPixels?>.getCompletedOrNull(): SequentialPixels? =
        try {
            getCompleted()
        } catch (_: Exception) {
            null
        }

    private fun toBitmap(pixels: SequentialPixels): Bitmap {
        val bitmap = createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels.data, 0, pixels.width, 0, 0, pixels.width, pixels.height)
        return bitmap
    }

    /**
     * Transfers the stream, has it decoded and brings back the pixels. Blocking (synchronous
     * binder transactions); null on the slightest protocol deviation or if the isolated process dies.
     */
    private fun transfer(
        binder: IBinder,
        bytes: ByteArray,
    ): SequentialPixels? {
        if (call(binder, Jpeg2000Protocol.TX_BEGIN, { writeInt(bytes.size) }) { true } != true) return null
        for ((start, length) in Jpeg2000Protocol.chunks(bytes.size, Jpeg2000Protocol.BYTE_CHUNK)) {
            val sent =
                call(binder, Jpeg2000Protocol.TX_APPEND, {
                    writeInt(start)
                    writeByteArray(bytes, start, length)
                }) { true }
            if (sent != true) return null
        }
        val size =
            call(binder, Jpeg2000Protocol.TX_DECODE, {}) {
                val width = readInt()
                val height = readInt()
                if (Jpeg2000Protocol.isValidOutput(width, height)) width to height else null
            } ?: return null
        val (width, height) = size
        val pixels = SequentialPixels(width, height)
        for ((start, count) in Jpeg2000Protocol.chunks(width * height, Jpeg2000Protocol.INT_CHUNK)) {
            val received =
                call(binder, Jpeg2000Protocol.TX_READ, {
                    writeInt(start)
                    writeInt(count)
                }) {
                    val chunk = createIntArray()
                    try {
                        chunk != null && chunk.size == count && pixels.put(start, chunk)
                    } finally {
                        chunk?.fill(0)
                    }
                }
            if (received != true) {
                pixels.wipe()
                return null
            }
        }
        return pixels
    }

    /**
     * One transaction: [write] fills the request, [read] reads the reply after an OK status.
     * Returns null on an error status, a malformed reply or a dead process.
     */
    private fun <T> call(
        binder: IBinder,
        code: Int,
        write: Parcel.() -> Unit,
        read: Parcel.() -> T,
    ): T? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(Jpeg2000Protocol.DESCRIPTOR)
            data.write()
            if (!binder.transact(code, data, reply, 0)) return null
            if (reply.readInt() != STATUS_OK) return null
            return reply.read()
        } catch (_: RemoteException) {
            return null
        } catch (_: RuntimeException) {
            // Malformed reply (BadParcelableException, inconsistent size…).
            return null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Binding to the service: [binder] receives the binder, or null if the service is unavailable. */
    private class Connection : ServiceConnection {
        val binder = CompletableDeferred<IBinder?>()

        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?,
        ) {
            binder.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder.complete(null)
        }

        override fun onBindingDied(name: ComponentName?) {
            binder.complete(null)
        }

        override fun onNullBinding(name: ComponentName?) {
            binder.complete(null)
        }
    }
}
