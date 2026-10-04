package io.github.mgdx.sceau.ui.trust

import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustSource
import io.github.mgdx.sceau.ui.result.Countries
import java.text.Collator
import java.text.Normalizer
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

    /** Nom localisé normalisé par [searchForm], calculé une fois pour la recherche. */
    internal val searchName: String? = displayName?.let(::searchForm)

    /** Code ISO 3166-1 alpha-3, accepté par la recherche comme le code alpha-2. */
    internal val alpha3: String? = alpha2.takeIf { it.isNotEmpty() }?.let(Countries::alpha3)
}

/**
 * Garde les pays dont le nom localisé contient [query], sans tenir compte de la casse ni des
 * accents, ou dont le code ISO alpha-2 ou alpha-3 est exactement [query]. Une requête vide
 * ou blanche rend la liste entière.
 */
fun filterGroups(
    groups: List<CountryGroup>,
    query: String,
): List<CountryGroup> {
    val needle = searchForm(query)
    if (needle.isEmpty()) return groups
    val code = needle.uppercase(Locale.ROOT)
    return groups.filter { group ->
        group.searchName?.contains(needle) == true || code == group.alpha2 || code == group.alpha3
    }
}

/** Forme de comparaison : décomposée, sans diacritiques, en minuscules, espaces réduits. */
private fun searchForm(text: String): String =
    Normalizer
        .normalize(text, Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(Locale.ROOT)
        .trim()
        .replace(BLANKS, " ")

private val DIACRITICS = Regex("\\p{Mn}+")
private val BLANKS = Regex("\\s+")

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
