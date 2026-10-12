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
 * Access key without Copy or Cut (audit V37, D45). The home screen fields use a
 * password-type keyboard without `PasswordVisualTransformation` (the MRZ must stay
 * readable): Compose would therefore offer Copy and Cut there, which would send the CAN or the
 * document number to the clipboard. Two barriers, one per Compose path:
 *  - the current context menu (`ComposeFoundationFlags.isNewContextMenuEnabled`, true by
 *    default) keeps only Paste and Select all ([keepPasteAndSelectAll]): neither Copy,
 *    nor Cut, nor actions from other apps (Translate, Share…) or from text
 *    classification (Call) that would pass the text on to them;
 *  - the legacy toolbar (`LocalTextToolbar`, if the option is turned off) receives
 *    null copy and cut callbacks ([NoCopyTextToolbar]).
 */

/** Context menu filter: only Paste and Select all remain. */
internal fun keepPasteAndSelectAll(component: TextContextMenuComponent): Boolean =
    component.key == TextContextMenuKeys.PasteKey || component.key == TextContextMenuKeys.SelectAllKey

/** Field context menu reduced to Paste and Select all. */
internal fun Modifier.noCopyContextMenu(): Modifier = filterTextContextMenuComponents(::keepPasteAndSelectAll)

/** Selection toolbar that delegates to the system, without Copy or Cut. */
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

/** Provides [NoCopyTextToolbar] to the fields of [content]. */
@Composable
internal fun NoCopyTextToolbarProvider(content: @Composable () -> Unit) {
    val system = LocalTextToolbar.current
    val toolbar = remember(system) { NoCopyTextToolbar(system) }
    CompositionLocalProvider(LocalTextToolbar provides toolbar, content = content)
}
