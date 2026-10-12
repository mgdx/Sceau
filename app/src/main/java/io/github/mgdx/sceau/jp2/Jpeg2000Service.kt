package io.github.mgdx.sceau.jp2

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import io.github.mgdx.sceau.core.model.ImageFormat
import io.github.mgdx.sceau.jp2.Jpeg2000Protocol.STATUS_ERROR
import io.github.mgdx.sceau.jp2.Jpeg2000Protocol.STATUS_OK
import io.github.mgdx.sceau.ui.result.DecodeLimits
import io.github.mgdx.sceau.ui.result.detectImageFormat
import io.github.mgdx.sceau.ui.result.limitSide
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Decoding service for the chip's images (JPEG 2000 and JPEG), declared
 * `android:isolatedProcess="true"` in the manifest (decision D23): it runs in a dedicated
 * process, under an isolated UID, without any permission or access to the app's files.
 * It is the only place where `libsceau_jp2.so` (OpenJPEG) is loaded and where [Jpeg2000Decoder] is
 * called, and the only one where `BitmapFactory` receives bytes coming from the chip (audit V18).
 *
 * The format is recognized from the stream's bytes alone ([detectImageFormat]): JPEG (`FF D8 FF`) to
 * `BitmapFactory`, with the bounds of [DecodeLimits]; JP2 or J2K to OpenJPEG; everything else
 * is refused without being decoded.
 *
 * A fresh process serves a single decoding: [IsolatedJpeg2000Decoder] binds, transfers the
 * stream, reads the pixels then unbinds; [onDestroy] then terminates the process, along with
 * everything the decoder may have left in its heap. Protocol: [Jpeg2000Protocol].
 */
class Jpeg2000Service : Service() {
    private val binder = DecoderBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Never wait for the lock here (audit V26): after a timeout, the binder thread holds
        // it for the whole decoding, and the process would survive until it ends. The buffers
        // are wiped only if they are free; otherwise they disappear with the process, which is
        // terminated in every case.
        binder.wipeIfIdle()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    internal class DecoderBinder : Binder() {
        /** Held during each transaction, decoding included. */
        internal val lock = ReentrantLock()
        private var input: SequentialBytes? = null
        private var pixels: IntArray? = null

        fun wipe() = lock.withLock { wipeLocked() }

        /** Wipes the buffers if no transaction is in progress, without ever waiting. */
        fun wipeIfIdle() {
            if (!lock.tryLock()) return
            try {
                wipeLocked()
            } finally {
                lock.unlock()
            }
        }

        private fun wipeLocked() {
            input?.wipe()
            input = null
            pixels?.fill(0)
            pixels = null
        }

        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code !in Jpeg2000Protocol.TX_BEGIN..Jpeg2000Protocol.TX_READ || reply == null) {
                return super.onTransact(code, data, reply, flags)
            }
            try {
                data.enforceInterface(Jpeg2000Protocol.DESCRIPTOR)
                lock.withLock {
                    val handled =
                        when (code) {
                            Jpeg2000Protocol.TX_BEGIN -> begin(data.readInt())
                            Jpeg2000Protocol.TX_APPEND -> append(data)
                            Jpeg2000Protocol.TX_DECODE -> decode(reply)
                            else -> read(data.readInt(), data.readInt(), reply)
                        }
                    if (!handled) writeError(reply)
                }
            } catch (_: Exception) {
                wipe()
                writeError(reply)
            } catch (_: OutOfMemoryError) {
                wipe()
                writeError(reply)
            }
            return true
        }

        private fun writeError(reply: Parcel) {
            reply.setDataSize(0)
            reply.setDataPosition(0)
            reply.writeInt(STATUS_ERROR)
        }

        private fun begin(length: Int): Boolean {
            wipeLocked()
            if (!Jpeg2000Protocol.isValidInputLength(length)) return false
            input = SequentialBytes(length)
            return true
        }

        private fun append(data: Parcel): Boolean {
            val offset = data.readInt()
            val chunk = data.createByteArray() ?: return false
            try {
                if (chunk.size > Jpeg2000Protocol.BYTE_CHUNK) return false
                return input?.put(offset, chunk) ?: false
            } finally {
                chunk.fill(0)
            }
        }

        private fun decode(reply: Parcel): Boolean {
            val stream = input?.takeIf { it.isComplete } ?: return false
            val bitmap =
                try {
                    when (detectImageFormat(stream.data)) {
                        ImageFormat.JPEG -> decodeJpeg(stream.data)
                        ImageFormat.JPEG2000 -> Jpeg2000Decoder.decode(stream.data)
                        else -> null
                    }
                } finally {
                    stream.wipe()
                    input = null
                } ?: return false
            try {
                val w = bitmap.width
                val h = bitmap.height
                if (!Jpeg2000Protocol.isValidOutput(w, h)) return false
                val argb = IntArray(w * h)
                bitmap.getPixels(argb, 0, w, 0, 0, w, h)
                pixels = argb
                reply.writeInt(STATUS_OK)
                reply.writeInt(w)
                reply.writeInt(h)
                return true
            } finally {
                if (bitmap.isMutable) bitmap.eraseColor(0)
                bitmap.recycle()
            }
        }

        private fun read(
            offset: Int,
            count: Int,
            reply: Parcel,
        ): Boolean {
            val argb = pixels ?: return false
            if (!Jpeg2000Protocol.isValidSlice(argb.size, offset, count, Jpeg2000Protocol.INT_CHUNK)) return false
            val chunk = argb.copyOfRange(offset, offset + count)
            try {
                reply.writeInt(STATUS_OK)
                reply.writeIntArray(chunk)
            } finally {
                chunk.fill(0)
            }
            if (offset + count == argb.size) {
                // Last chunk: the pixels now remain only with the caller.
                argb.fill(0)
                pixels = null
            }
            return true
        }
    }
}

/**
 * JPEG decoding by `BitmapFactory`, in the isolated process only. Bounds from audit V2:
 * dimensions read first (`inJustDecodeBounds`), refusal beyond
 * [DecodeLimits.MAX_DECLARED_PIXELS], subsampling then downscaling so that no side
 * exceeds [DecodeLimits.MAX_SIDE], hence [Jpeg2000Protocol.MAX_OUTPUT_SIDE].
 */
private fun decodeJpeg(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sample = DecodeLimits.sampleSize(bounds.outWidth, bounds.outHeight) ?: return null
    val options =
        BitmapFactory.Options().apply {
            inMutable = true
            inSampleSize = sample
        }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    // Safety net: the decoder must never return something larger than announced.
    return limitSide(bitmap)
}
