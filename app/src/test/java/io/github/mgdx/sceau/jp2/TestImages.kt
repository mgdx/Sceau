package io.github.mgdx.sceau.jp2

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream

/** Images réelles encodées par Android (rendu natif de Robolectric), pour les tests de décodage. */
internal object TestImages {
    fun encode(
        format: Bitmap.CompressFormat,
        width: Int = 40,
        height: Int = 30,
    ): ByteArray {
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(200, 120, 40))
        val out = ByteArrayOutputStream()
        check(bitmap.compress(format, 90, out))
        bitmap.recycle()
        return out.toByteArray()
    }
}
