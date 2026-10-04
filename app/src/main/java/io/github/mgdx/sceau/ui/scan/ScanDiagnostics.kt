package io.github.mgdx.sceau.ui.scan

import io.github.mgdx.sceau.mrz.MrzScanResult

/** Issue d'une analyse, sans son contenu : jamais un caractère reconnu. */
enum class ScanOutcome {
    NOTHING_FOUND,
    UNSTABLE,
    UNSUPPORTED,
    FOUND,
    ;

    companion object {
        fun of(result: MrzScanResult): ScanOutcome =
            when (result) {
                MrzScanResult.NothingFound -> NOTHING_FOUND
                MrzScanResult.Unstable -> UNSTABLE
                MrzScanResult.UnsupportedDocumentNumber -> UNSUPPORTED
                is MrzScanResult.Found -> FOUND
            }
    }
}

/**
 * Mesures d'une image analysée, pour le bandeau de diagnostic de l'APK de debug : dimensions
 * de l'image et du recadrage, durée, issue. Aucune donnée de l'image.
 */
data class FrameStats(
    val imageWidth: Int,
    val imageHeight: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val durationNanos: Long,
    val outcome: ScanOutcome,
)

/** État du diagnostic sur la fenêtre glissante, prêt à afficher. */
data class ScanDiagnosticsSnapshot(
    val framesPerSecond: Float,
    val meanAnalysisMillis: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val nothingFound: Int,
    val unstable: Int,
    val unsupported: Int,
    val found: Int,
)

/**
 * Agrège les [FrameStats] des [windowSize] dernières images (APK de debug uniquement). Logique
 * pure, testée sur JVM ; à utiliser depuis un seul fil (celui de l'analyse).
 */
class ScanDiagnostics(
    private val windowSize: Int = WINDOW_SIZE,
) {
    init {
        require(windowSize > 0)
    }

    private val times = LongArray(windowSize)
    private val durations = LongArray(windowSize)
    private val outcomes = arrayOfNulls<ScanOutcome>(windowSize)
    private var next = 0
    private var count = 0
    private var last: FrameStats? = null

    /** Ajoute l'image [stats] terminée à [nowNanos] ; renvoie l'état de la fenêtre. */
    fun add(
        stats: FrameStats,
        nowNanos: Long,
    ): ScanDiagnosticsSnapshot {
        times[next] = nowNanos
        durations[next] = stats.durationNanos
        outcomes[next] = stats.outcome
        next = (next + 1) % windowSize
        if (count < windowSize) count++
        last = stats
        return snapshot()
    }

    fun reset() {
        times.fill(0)
        durations.fill(0)
        outcomes.fill(null)
        next = 0
        count = 0
        last = null
    }

    fun snapshot(): ScanDiagnosticsSnapshot {
        val oldest = times[(next - count + windowSize) % windowSize]
        val newest = times[(next - 1 + windowSize) % windowSize]
        val span = newest - oldest
        val fps = if (count >= 2 && span > 0) (count - 1) * NANOS_PER_SECOND / span else 0f
        var total = 0L
        val tally = IntArray(ScanOutcome.entries.size)
        for (i in 0 until count) {
            val index = (next - 1 - i + windowSize) % windowSize
            total += durations[index]
            outcomes[index]?.let { tally[it.ordinal]++ }
        }
        val mean = if (count > 0) total.toFloat() / count / NANOS_PER_MILLI else 0f
        val l = last
        return ScanDiagnosticsSnapshot(
            framesPerSecond = fps,
            meanAnalysisMillis = mean,
            imageWidth = l?.imageWidth ?: 0,
            imageHeight = l?.imageHeight ?: 0,
            cropWidth = l?.cropWidth ?: 0,
            cropHeight = l?.cropHeight ?: 0,
            nothingFound = tally[ScanOutcome.NOTHING_FOUND.ordinal],
            unstable = tally[ScanOutcome.UNSTABLE.ordinal],
            unsupported = tally[ScanOutcome.UNSUPPORTED.ordinal],
            found = tally[ScanOutcome.FOUND.ordinal],
        )
    }

    companion object {
        const val WINDOW_SIZE = 30
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val NANOS_PER_MILLI = 1_000_000f
    }
}
