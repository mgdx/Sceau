package io.github.mgdx.sceau.jp2

import android.os.Build
import android.os.Process

/**
 * Vrai dans le processus isolé de [Jpeg2000Service] (décision D23). Android y crée aussi
 * l'`Application` de Sceau : elle s'en sert pour n'y rien initialiser (pas de magasin de
 * confiance, qu'un UID isolé ne pourrait d'ailleurs pas lire sur disque).
 */
fun isIsolatedProcess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Process.isIsolated()
    } else {
        isIsolatedUid(Process.myUid())
    }
