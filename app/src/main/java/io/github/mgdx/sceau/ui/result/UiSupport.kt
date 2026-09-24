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
 * Aides Compose partagées par les écrans Résultat, Magasin de confiance et À propos.
 */

/** Locale courante de l'interface. */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty) Locale.ROOT else locales[0]
}

/** Formateur de dates selon la locale courante (ex. « 25 sept. 2026 » ou « Sep 25, 2026 »). */
@Composable
fun rememberDateFormatter(): (LocalDate) -> String {
    val locale = currentLocale()
    return remember(locale) {
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val format: (LocalDate) -> String = { formatter.format(it) }
        format
    }
}

/** Vrai si la palette courante est sombre (y compris avec les couleurs dynamiques). */
@Composable
@ReadOnlyComposable
fun isDarkPalette(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Résout un [UiText], y compris ses arguments qui sont eux-mêmes des [UiText]. */
@Composable
fun UiText.resolve(): String {
    val resolved = args.map { if (it is UiText) it.resolve() else it }
    return stringResource(res, *resolved.toTypedArray())
}

/** Drapeau et nom localisé d'un pays, ou libellé propre des codes ICAO spéciaux, ou code brut. */
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
