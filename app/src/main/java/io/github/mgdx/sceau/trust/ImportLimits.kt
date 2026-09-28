package io.github.mgdx.sceau.trust

import io.github.mgdx.sceau.ui.trust.MAX_MASTER_LIST_BYTES

/** Master List déjà importée : nom de fichier (empreinte du contenu) et taille en octets. */
class ImportedFile(
    val name: String,
    val size: Long,
)

/** Issue d'une demande d'import au regard des limites (décision D22). */
enum class ImportDecision {
    /** Même contenu déjà importé : rien à écrire, ne compte pas dans les limites. */
    ALREADY_PRESENT,

    /** Import permis. */
    ALLOWED,

    /** Refusé : [ImportLimits.MAX_IMPORTED_LISTS] Master Lists déjà importées. */
    TOO_MANY,

    /** Refusé : la taille cumulée dépasserait [ImportLimits.MAX_IMPORTED_TOTAL_BYTES]. */
    TOO_LARGE_TOTAL,
}

/** Import refusé par les limites ; le message ne contient qu'un code stable. */
class ImportLimitException(
    val decision: ImportDecision,
) : Exception("IMPORT_LIMIT_${decision.name}")

/**
 * Limites des Master Lists importées (décision D22) : toutes sont relues et fusionnées au
 * démarrage (D16), leur nombre et leur taille cumulée bornent donc la mémoire et le temps de
 * préchargement.
 */
object ImportLimits {
    /** Nombre maximal de Master Lists importées. */
    const val MAX_IMPORTED_LISTS = 10

    /** Taille cumulée maximale des Master Lists importées : deux fichiers de taille maximale. */
    const val MAX_IMPORTED_TOTAL_BYTES: Long = 2L * MAX_MASTER_LIST_BYTES

    /** Décide si une Master List nommée [name], de [size] octets, peut s'ajouter à [existing]. */
    fun decide(
        existing: List<ImportedFile>,
        name: String,
        size: Long,
    ): ImportDecision =
        when {
            existing.any { it.name == name } -> ImportDecision.ALREADY_PRESENT
            existing.size >= MAX_IMPORTED_LISTS -> ImportDecision.TOO_MANY
            existing.sumOf { it.size } + size > MAX_IMPORTED_TOTAL_BYTES -> ImportDecision.TOO_LARGE_TOTAL
            else -> ImportDecision.ALLOWED
        }
}
