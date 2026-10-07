package io.github.mgdx.sceau.ui.home

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuComponent
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar

/*
 * Clé d'accès sans Copier ni Couper (audit V37, D45). Les champs de l'accueil utilisent un
 * clavier de type mot de passe sans `PasswordVisualTransformation` (la MRZ doit rester
 * lisible) : Compose y proposerait donc Copier et Couper, qui enverraient le CAN ou le numéro
 * de document au presse-papier. Deux barrières, une par chemin de Compose :
 *  - le menu contextuel actuel (`ComposeFoundationFlags.isNewContextMenuEnabled`, vrai par
 *    défaut) ne garde que Coller et Tout sélectionner ([keepPasteAndSelectAll]) : ni Copier,
 *    ni Couper, ni les actions d'autres applications (Traduire, Partager…) ou de
 *    classification (Appeler) qui leur transmettraient le texte ;
 *  - l'ancienne barre d'outils (`LocalTextToolbar`, si l'option est désactivée) reçoit
 *    des rappels de copie et de coupe nuls ([NoCopyTextToolbar]).
 */

/** Filtre du menu contextuel : seuls Coller et Tout sélectionner restent. */
internal fun keepPasteAndSelectAll(component: TextContextMenuComponent): Boolean =
    component.key == TextContextMenuKeys.PasteKey || component.key == TextContextMenuKeys.SelectAllKey

/** Menu contextuel du champ réduit à Coller et Tout sélectionner. */
internal fun Modifier.noCopyContextMenu(): Modifier = filterTextContextMenuComponents(::keepPasteAndSelectAll)

/** Barre d'outils de sélection qui délègue au système, sans Copier ni Couper. */
internal class NoCopyTextToolbar(
    private val delegate: TextToolbar,
) : TextToolbar by delegate {
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = delegate.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested)

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
        onAutofillRequested: (() -> Unit)?,
    ) = delegate.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested, onAutofillRequested)
}

/** Fournit [NoCopyTextToolbar] aux champs de [content]. */
@Composable
internal fun NoCopyTextToolbarProvider(content: @Composable () -> Unit) {
    val system = LocalTextToolbar.current
    val toolbar = remember(system) { NoCopyTextToolbar(system) }
    CompositionLocalProvider(LocalTextToolbar provides toolbar, content = content)
}
