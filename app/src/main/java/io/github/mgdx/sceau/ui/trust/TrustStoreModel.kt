package io.github.mgdx.sceau.ui.trust

import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustSource
import io.github.mgdx.sceau.ui.result.Countries
import java.text.Collator
import java.time.Instant
import java.util.Locale

/** Ligne de la liste, précalculée hors du thread principal. */
class AnchorRow(
    val subject: String,
    val notBefore: Instant,
    val notAfter: Instant,
    val sha256: String,
    val source: TrustSource,
    val isLink: Boolean,
) {
    /** Clé stable pour LazyColumn : un même certificat peut venir de deux sources. */
    val key: String get() = "cert:$sha256:${source.name}"
}

/** Certificats d'un pays ; [alpha2] vide si le certificat n'indique pas de pays. */
class CountryGroup(
    val alpha2: String,
    val displayName: String?,
    val anchors: List<AnchorRow>,
) {
    val key: String get() = "country:$alpha2"
}

/**
 * Regroupe les ancres par pays, triés par nom localisé (pays sans nom connu à la fin),
 * et dans chaque pays par source puis par date de début de validité décroissante.
 * Les doublons exacts (même empreinte, même source) sont fusionnés.
 */
fun groupByCountry(
    anchors: List<TrustAnchor>,
    locale: Locale,
): List<CountryGroup> {
    val rows =
        anchors.map {
            it.country.trim().uppercase(Locale.ROOT) to
                AnchorRow(
                    subject = it.subject,
                    notBefore = it.notBefore,
                    notAfter = it.notAfter,
                    sha256 = it.sha256,
                    source = it.source,
                    isLink = !it.isSelfSigned,
                )
        }
    return groupRows(rows, locale)
}

/** Partie pure de [groupByCountry], testable sans certificat. */
fun groupRows(
    rows: List<Pair<String, AnchorRow>>,
    locale: Locale,
): List<CountryGroup> {
    val collator = Collator.getInstance(locale)
    return rows
        .groupBy({ it.first }, { it.second })
        .map { (alpha2, list) ->
            CountryGroup(
                alpha2 = alpha2,
                displayName = Countries.displayName(alpha2, locale),
                anchors =
                    list
                        .distinctBy { it.key }
                        .sortedWith(compareBy<AnchorRow> { it.source.ordinal }.thenByDescending { it.notBefore }),
            )
        }.sortedWith(
            compareBy<CountryGroup> { it.displayName == null }
                .thenComparator { a, b -> collator.compare(a.displayName ?: a.alpha2, b.displayName ?: b.alpha2) },
        )
}
