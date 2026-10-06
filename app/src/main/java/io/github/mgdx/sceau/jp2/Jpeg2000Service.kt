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
 * Service de décodage des images de la puce (JPEG 2000 et JPEG), déclaré
 * `android:isolatedProcess="true"` dans le manifeste (décision D23) : il tourne dans un
 * processus dédié, sous un UID isolé, sans aucune permission ni accès aux fichiers de l'app.
 * C'est le seul endroit où `libsceau_jp2.so` (OpenJPEG) est chargée et où [Jpeg2000Decoder] est
 * appelé, et le seul où `BitmapFactory` reçoit des octets venus de la puce (audit V18).
 *
 * Le format est reconnu aux seuls octets du flux ([detectImageFormat]) : JPEG (`FF D8 FF`) vers
 * `BitmapFactory`, avec les bornes de [DecodeLimits] ; JP2 ou J2K vers OpenJPEG ; tout le
 * reste est refusé sans être décodé.
 *
 * Un processus neuf sert un seul décodage : [IsolatedJpeg2000Decoder] se lie, transfère le
 * flux, lit les pixels puis se détache ; [onDestroy] termine alors le processus, avec tout ce
 * que le décodeur a pu laisser dans son tas. Protocole : [Jpeg2000Protocol].
 */
class Jpeg2000Service : Service() {
    private val binder = DecoderBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Jamais d'attente du verrou ici (audit V26) : après un délai dépassé, le thread binder
        // le tient pendant tout le décodage, et le processus survivrait jusqu'à sa fin. Les
        // tampons ne sont effacés que s'ils sont libres ; sinon ils disparaissent avec le
        // processus, terminé dans tous les cas.
        binder.wipeIfIdle()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    internal class DecoderBinder : Binder() {
        /** Tenu pendant chaque transaction, décodage compris. */
        internal val lock = ReentrantLock()
        private var input: SequentialBytes? = null
        private var pixels: IntArray? = null

        fun wipe() = lock.withLock { wipeLocked() }

        /** Efface les tampons si aucune transaction n'est en cours, sans jamais attendre. */
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
                // Dernier morceau : les pixels ne restent plus que chez l'appelant.
                argb.fill(0)
                pixels = null
            }
            return true
        }
    }
}

/**
 * Décodage JPEG par `BitmapFactory`, dans le processus isolé seulement. Bornes de l'audit V2 :
 * dimensions lues d'abord (`inJustDecodeBounds`), refus au-delà de
 * [DecodeLimits.MAX_DECLARED_PIXELS], sous-échantillonnage puis réduction pour qu'aucun côté ne
 * dépasse [DecodeLimits.MAX_SIDE], donc [Jpeg2000Protocol.MAX_OUTPUT_SIDE].
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
    // Filet de sécurité : le décodeur ne doit jamais rendre plus grand qu'annoncé.
    return limitSide(bitmap)
}
