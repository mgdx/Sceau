package io.github.mgdx.sceau.ui.trust

import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustSource
import io.github.mgdx.sceau.ui.result.Countries
import java.text.Collator
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

/** List row, precomputed off the main thread. */
class AnchorRow(
    val subject: String,
    val notBefore: Instant,
    val notAfter: Instant,
    val sha256: String,
    val source: TrustSource,
    val isLink: Boolean,
) {
    /** Stable key for LazyColumn: the same certificate may come from two sources. */
    val key: String get() = "cert:$sha256:${source.name}"
}

/** Certificates of one country; [alpha2] is empty if the certificate does not specify a country. */
class CountryGroup(
    val alpha2: String,
    val displayName: String?,
    val anchors: List<AnchorRow>,
) {
    val key: String get() = "country:$alpha2"

    /** Localized name normalized by [searchForm], computed once for searching. */
    internal val searchName: String? = displayName?.let(::searchForm)

    /** ISO 3166-1 alpha-3 code, accepted by the search just like the alpha-2 code. */
    internal val alpha3: String? = alpha2.takeIf { it.isNotEmpty() }?.let(Countries::alpha3)
}

/**
 * Keeps the countries whose localized name contains [query], ignoring case and accents, or
 * whose ISO alpha-2 or alpha-3 code is exactly [query]. An empty or blank query returns the
 * whole list.
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

/** Comparison form: decomposed, without diacritics, lowercase, whitespace collapsed. */
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
 * Groups the anchors by country, sorted by localized name (countries without a known name last),
 * and within each country by source, then by descending validity start date.
 * Exact duplicates (same fingerprint, same source) are merged.
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

/** Pure part of [groupByCountry], testable without a certificate. */
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
