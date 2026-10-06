package io.github.mgdx.sceau.ui.result

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.mgdx.sceau.core.model.EncodedImage
import io.github.mgdx.sceau.core.model.ImageFormat
import io.github.mgdx.sceau.jp2.Jpeg2000Service
import io.github.mgdx.sceau.jp2.TestImages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Audit V18 : aucune image de la puce n'est décodée dans le processus de l'app. Le service de
 * décodage isolé est remplacé ici par une instance locale du vrai service.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class ImageDecodingTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun bindLocalService() {
        val service = Robolectric.buildService(Jpeg2000Service::class.java).create().get()
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, Jpeg2000Service::class.java),
            service.onBind(Intent()),
        )
    }

    @Test
    fun `JPEG decode par le service isole, quel que soit le format declare`() {
        val jpeg = TestImages.encode(Bitmap.CompressFormat.JPEG)
        for (declared in ImageFormat.entries) {
            val bitmap = checkNotNull(decode(EncodedImage(declared, jpeg.copyOf())))
            assertEquals(40 to 30, bitmap.width to bitmap.height)
            bitmap.wipeAndRecycle()
        }
        // Chaque décodage a lié puis détaché le service : rien dans le processus de l'app.
        assertEquals(ImageFormat.entries.size, shadowOf(app).unboundServiceConnections.size)
    }

    @Test
    fun `autres formats declares JPEG jamais decodes ni transmis`() {
        val others =
            listOf(
                TestImages.encode(Bitmap.CompressFormat.PNG),
                TestImages.encode(Bitmap.CompressFormat.WEBP_LOSSLESS),
                HostileImages.GIF,
                HostileImages.BMP,
            )
        for (bytes in others) {
            for (declared in ImageFormat.entries) assertNull(decode(EncodedImage(declared, bytes.copyOf())))
        }
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
        assertTrue(shadowOf(app).unboundServiceConnections.isEmpty())
    }

    /**
     * Appelle [decodeToBitmap] hors du thread principal, en faisant tourner la boucle principale
     * qui délivre la connexion au service.
     */
    private fun decode(image: EncodedImage): Bitmap? {
        val result = AtomicReference<Bitmap?>()
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        thread {
            try {
                result.set(runBlocking { decodeToBitmap(app, image) })
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                done.countDown()
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!done.await(10, TimeUnit.MILLISECONDS)) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "décodage bloqué" }
        }
        failure.get()?.let { throw it }
        return result.get()
    }
}
