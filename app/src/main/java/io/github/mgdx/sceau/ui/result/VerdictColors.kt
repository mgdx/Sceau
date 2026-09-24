package io.github.mgdx.sceau.ui.result

import io.github.mgdx.sceau.core.report.Verdict
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Couple fond / texte d'un verdict, en ARGB 32 bits. Indépendant du thème dynamique. */
data class VerdictPalette(
    val container: Long,
    val content: Long,
)

/**
 * Couleurs des verdicts (SPEC §5.3), fixes pour garder leur sens quelle que soit la palette
 * dynamique du système. Le contraste texte / fond dépasse 4,5:1 en clair comme en sombre ;
 * le verdict est aussi porté par une icône et un libellé, jamais par la seule couleur.
 */
object VerdictColors {
    fun palette(
        verdict: Verdict,
        dark: Boolean,
    ): VerdictPalette =
        when (verdict) {
            Verdict.AUTHENTIC -> {
                if (dark) VerdictPalette(0xFF8FD19E, 0xFF00210B) else VerdictPalette(0xFF1B5E20, 0xFFFFFFFF)
            }

            Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED -> {
                if (dark) VerdictPalette(0xFFFFB870, 0xFF2E1500) else VerdictPalette(0xFFA04000, 0xFFFFFFFF)
            }

            Verdict.UNKNOWN_ISSUER -> {
                if (dark) VerdictPalette(0xFFC4CDD2, 0xFF1A1F22) else VerdictPalette(0xFF4A5459, 0xFFFFFFFF)
            }

            Verdict.FAILED -> {
                if (dark) VerdictPalette(0xFFFFB4AB, 0xFF410002) else VerdictPalette(0xFFB3261E, 0xFFFFFFFF)
            }
        }

    /** Rapport de contraste WCAG 2.x entre deux couleurs ARGB opaques. */
    fun contrastRatio(
        first: Long,
        second: Long,
    ): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun relativeLuminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
