package io.github.mgdx.sceau.ui.scan

import io.github.mgdx.sceau.mrz.MrzScanResult

/** État affiché sous le cadre de visée. */
enum class ScanStatus {
    /** Aucune MRZ dans le cadre. */
    SEARCHING,

    /** MRZ vue, pas encore lue de façon sûre. */
    SEEN,

    /** MRZ lisible mais numéro de document étendu : saisie manuelle. */
    UNSUPPORTED,
}

/**
 * Lisse l'état affiché : une image sans MRZ isolée (flou, mouvement) ne fait pas revenir à
 * « recherche » tant que la MRZ a été vue il y a moins de [HOLD_MILLIS]. Logique pure, testée
 * sur JVM.
 */
class ScanStatusTracker {
    var status: ScanStatus = ScanStatus.SEARCHING
        private set

    private var lastSeenAt = 0L

    /** Prend en compte un résultat d'analyse reçu à [nowMillis] ; renvoie l'état à afficher. */
    fun onResult(
        result: MrzScanResult,
        nowMillis: Long,
    ): ScanStatus {
        when (result) {
            MrzScanResult.Unstable -> {
                status = ScanStatus.SEEN
                lastSeenAt = nowMillis
            }

            MrzScanResult.UnsupportedDocumentNumber -> {
                status = ScanStatus.UNSUPPORTED
                lastSeenAt = nowMillis
            }

            MrzScanResult.NothingFound -> {
                if (nowMillis - lastSeenAt >= HOLD_MILLIS) status = ScanStatus.SEARCHING
            }

            is MrzScanResult.Found -> {
                Unit
            }
        }
        return status
    }

    fun reset() {
        status = ScanStatus.SEARCHING
        lastSeenAt = 0L
    }

    companion object {
        const val HOLD_MILLIS = 1_500L
    }
}
