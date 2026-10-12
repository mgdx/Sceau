package io.github.mgdx.sceau.ui.result

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import io.github.mgdx.sceau.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * Compose helpers shared by the "Result", "Trust store" and "About" screens.
 */

/** Current locale of the interface. */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty) Locale.ROOT else locales[0]
}

/** Date formatter for the current locale (e.g. "25 sept. 2026" or "Sep 25, 2026"). */
@Composable
fun rememberDateFormatter(): (LocalDate) -> String {
    val locale = currentLocale()
    return remember(locale) {
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val format: (LocalDate) -> String = { formatter.format(it) }
        format
    }
}

/** True if the current palette is dark (including with dynamic colors). */
@Composable
@ReadOnlyComposable
fun isDarkPalette(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Resolves a [UiText], including its arguments that are themselves [UiText]s. */
@Composable
fun UiText.resolve(): String {
    val resolved = args.map { if (it is UiText) it.resolve() else it }
    return stringResource(res, *resolved.toTypedArray())
}

/** Flag and localized name of a country, or the dedicated label of special ICAO codes, or the raw code. */
@Composable
fun countryLabel(country: CountryRef): String {
    val special = country.special
    val name =
        if (special != null) {
            stringResource(specialCountryLabel(special))
        } else {
            Countries.displayName(country.alpha2, currentLocale())
        }
    val flag = Countries.flagEmoji(country.alpha2)
    return when {
        name == null -> country.code.ifEmpty { stringResource(R.string.result_unknown_value) }
        flag != null -> "$flag $name"
        else -> name
    }
}

private fun specialCountryLabel(special: SpecialCountry): Int =
    when (special) {
        SpecialCountry.UNITED_NATIONS -> R.string.result_country_united_nations
        SpecialCountry.UN_SPECIALIZED_AGENCY -> R.string.result_country_un_agency
        SpecialCountry.UN_KOSOVO -> R.string.result_country_un_kosovo
        SpecialCountry.STATELESS -> R.string.result_country_stateless
        SpecialCountry.REFUGEE -> R.string.result_country_refugee
        SpecialCountry.REFUGEE_OTHER -> R.string.result_country_refugee_other
        SpecialCountry.UNSPECIFIED -> R.string.result_country_unspecified
    }
