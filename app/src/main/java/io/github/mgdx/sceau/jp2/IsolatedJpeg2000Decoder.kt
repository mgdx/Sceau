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
 * Décodage des images de la puce (portrait DG2, signature DG7, images DG12), en JPEG 2000 comme
 * en JPEG, confié au processus isolé de [Jpeg2000Service] (décision D23, audit V18) : ni
 * OpenJPEG ni le décodeur JPEG d'Android ne reçoivent d'octets de la puce dans le processus de
 * l'app. Le service n'accepte que le JPEG et le JPEG 2000, reconnus à leurs octets.
 *
 * Renvoie un [Bitmap] ARGB_8888 mutable, ou null si le flux n'est pas décodable, si le
 * processus isolé meurt (bombe, bug natif), si le décodage dépasse
 * [Jpeg2000Protocol.TIMEOUT_MILLIS] ou si le service est indisponible : jamais d'exception
 * (hors annulation de la coroutine appelante). Les décodages sont sérialisés ; chacun lie le
 * service, donc démarre un processus isolé neuf, et le détache dès la fin.
 */
object IsolatedJpeg2000Decoder {
    /** Un seul décodage à la fois dans le processus de l'app. */
    private val mutex = Mutex()

    /**
     * Portée des transferts binder, bloquants : détachée de l'appelant pour qu'un délai dépassé
     * ou une annulation n'attendent pas la fin d'une transaction (le détachement du service
     * termine le processus isolé, ce qui la débloque).
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
                // Délai dépassé ou annulation : des pixels reçus plus tard sont effacés aussitôt.
                abandoned.invokeOnCompletion { cause -> if (cause == null) abandoned.getCompletedOrNull()?.wipe() }
                abandoned.cancel()
            }
            try {
                context.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                // Liaison refusée : rien à détacher.
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
     * Transfère le flux, fait décoder et rapatrie les pixels. Bloquant (transactions binder
     * synchrones) ; null au moindre écart de protocole ou si le processus isolé meurt.
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
     * Une transaction : [write] remplit la requête, [read] lit la réponse après un statut OK.
     * Renvoie null sur statut d'erreur, réponse malformée ou processus mort.
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
            // Réponse malformée (BadParcelableException, taille incohérente…).
            return null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Liaison au service : [binder] reçoit le binder, ou null si le service est indisponible. */
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
