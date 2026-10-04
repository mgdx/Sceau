package io.github.mgdx.sceau.trust

import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustSource

/** Fichier choisi qui n'apporterait rien au magasin, reconnu dès l'aperçu (D33). */
sealed interface Duplicate {
    /** Certificat déjà importé seul (même DER, quel que soit le format du fichier choisi). */
    data object ImportedCertificate : Duplicate

    /** Certificat déjà présent par une autre [source] : embarquée ou Master List importée. */
    data class KnownCertificate(
        val source: TrustSource,
    ) : Duplicate

    /** Master List identique, octet pour octet, à une Master List déjà importée. */
    data object ImportedMasterList : Duplicate

    /** Master List identique, octet pour octet, à la Master List embarquée. */
    data object EmbeddedMasterList : Duplicate
}

/** Import refusé car le fichier est déjà dans le magasin ; le message ne contient qu'un code stable. */
class DuplicateImportException(
    val duplicate: Duplicate,
) : Exception("IMPORT_DUPLICATE")

/**
 * Détection des doublons à l'import (D33), sur l'empreinte SHA-256 du DER d'un certificat ou du
 * fichier d'une Master List. Fonctions pures : les empreintes sont en hexadécimal minuscule.
 */
object ImportDuplicates {
    /**
     * Doublon éventuel du certificat d'empreinte [sha256], au regard des certificats importés
     * seuls ([importedCertificates], empreintes des fichiers `.der`) et du magasin fusionné
     * ([storeSources], source retenue par empreinte). Null si le certificat est nouveau.
     */
    fun certificate(
        sha256: String,
        importedCertificates: Set<String>,
        storeSources: Map<String, TrustSource>,
    ): Duplicate? {
        if (sha256 in importedCertificates) return Duplicate.ImportedCertificate
        val source = storeSources[sha256] ?: return null
        return if (source == TrustSource.IMPORTED_CERTIFICATE) Duplicate.ImportedCertificate else Duplicate.KnownCertificate(source)
    }

    /**
     * Doublon éventuel de la Master List d'empreinte [sha256], au regard des Master Lists
     * importées ([importedMasterLists]) et embarquées ([embeddedMasterLists]). Null si le fichier
     * est nouveau, même si tous ses certificats sont déjà connus : il peut être plus récent.
     */
    fun masterList(
        sha256: String,
        importedMasterLists: Set<String>,
        embeddedMasterLists: Set<String>,
    ): Duplicate? =
        when (sha256) {
            in importedMasterLists -> Duplicate.ImportedMasterList
            in embeddedMasterLists -> Duplicate.EmbeddedMasterList
            else -> null
        }

    /** Nombre de certificats distincts parmi [certificates] (empreintes) absents de [store]. */
    fun newCertificateCount(
        certificates: Collection<String>,
        store: Set<String>,
    ): Int = certificates.toSet().count { it !in store }

    /** Source retenue pour chaque empreinte du magasin : la plus prioritaire en cas de doublon. */
    fun sourcesBySha256(anchors: List<TrustAnchor>): Map<String, TrustSource> =
        anchors.groupBy { it.sha256 }.mapValues { (_, list) -> list.minOf { it.source } }
}
