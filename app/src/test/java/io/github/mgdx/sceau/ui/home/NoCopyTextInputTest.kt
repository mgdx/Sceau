package io.github.mgdx.sceau.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModelProvider
import io.github.mgdx.sceau.session.DocumentTab
import io.github.mgdx.sceau.session.IdCardKey
import io.github.mgdx.sceau.session.SessionViewModel
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Audit V37 (D45): neither Copy nor Cut on the access key fields of the home screen. */
@RunWith(RobolectricTestRunner::class)
// Native rendering: the selection Magnifier fails without a surface in legacy mode.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h640dp-xxhdpi")
class NoCopyTextInputTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Context menu (floating toolbar): records the offered items instead of showing them. */
    private class RecordingMenu : TextContextMenuProvider {
        val shown = mutableListOf<List<Any>>()

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            shown += dataProvider.data().components.map { it.key }
            awaitCancellation()
        }
    }

    /** Long press on input field number [index], returns the items of the offered menu. */
    private fun longPressMenu(
        menu: RecordingMenu,
        index: Int,
        text: String?,
    ): List<Any> {
        val field = compose.onAllNodes(hasSetTextAction())[index]
        if (text != null) field.performTextInput(text)
        compose.waitForIdle()
        menu.shown.clear()
        // On the text (start of the field): a long press selects a word there, as on a device.
        field.performTouchInput { longClick(percentOffset(0.12f, 0.5f)) }
        compose.waitForIdle()
        val selection = field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertFalse("long press without selection", selection.collapsed)
        assertTrue("no context menu shown", menu.shown.isNotEmpty())
        return menu.shown.last()
    }

    private fun setContent(
        menu: RecordingMenu,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides menu, content = content)
        }
    }

    @Test
    fun `a password-type field without the filter offers Copy (control)`() {
        val menu = RecordingMenu()
        setContent(menu) {
            var value by remember { mutableStateOf("") }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        }
        val keys = longPressMenu(menu, index = 0, text = "123456")
        assertTrue("control: Copy expected, menu $keys", TextContextMenuKeys.CopyKey in keys)
    }

    @Test
    fun `home access key fields offer neither Copy nor Cut`() {
        val menu = RecordingMenu()
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        session.clear()
        session.selectTab(DocumentTab.ID_CARD)
        session.selectIdCardKey(IdCardKey.CAN)
        setContent(menu) { HomeScreen(session, onRead = {}, onOpenTrustStore = {}, onOpenAbout = {}) }

        assertNoCopyNorCut(longPressMenu(menu, index = 0, text = "123456"), "CAN")

        compose.runOnIdle { session.selectTab(DocumentTab.PASSPORT) }
        compose.waitForIdle()
        assertNoCopyNorCut(longPressMenu(menu, index = 0, text = "AB1234567"), "document number")
        assertNoCopyNorCut(longPressMenu(menu, index = 1, text = "17051990"), "date of birth")
        assertNoCopyNorCut(longPressMenu(menu, index = 2, text = "1705203"), "date of expiry")
    }

    private fun assertNoCopyNorCut(
        keys: List<Any>,
        field: String,
    ) {
        assertFalse("$field: Copy offered, menu $keys", TextContextMenuKeys.CopyKey in keys)
        assertFalse("$field: Cut offered, menu $keys", TextContextMenuKeys.CutKey in keys)
        assertTrue(
            "$field: unexpected item, menu $keys",
            keys.all {
                it == TextContextMenuKeys.PasteKey ||
                    it == TextContextMenuKeys.SelectAllKey
            },
        )
    }

    @Test
    fun `legacy text toolbar never receives copy or cut callbacks`() {
        val recorder = RecordingToolbar()
        val toolbar = NoCopyTextToolbar(recorder)
        val paste = {}
        val selectAll = {}
        toolbar.showMenu(Rect.Zero, onCopyRequested = {}, onPasteRequested = paste, onCutRequested = {}, onSelectAllRequested = selectAll)
        assertNull(recorder.copy)
        assertNull(recorder.cut)
        assertEquals(paste, recorder.paste)
        assertEquals(selectAll, recorder.selectAll)

        recorder.paste = null
        toolbar.showMenu(Rect.Zero, {}, paste, {}, selectAll, onAutofillRequested = {})
        assertNull(recorder.copy)
        assertNull(recorder.cut)
        assertNotNull(recorder.paste)
        assertNotNull(recorder.autofill)
    }

    private class RecordingToolbar : TextToolbar {
        var copy: (() -> Unit)? = null
        var paste: (() -> Unit)? = null
        var cut: (() -> Unit)? = null
        var selectAll: (() -> Unit)? = null
        var autofill: (() -> Unit)? = null

        override val status = TextToolbarStatus.Hidden

        override fun hide() = Unit

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            copy = onCopyRequested
            paste = onPasteRequested
            cut = onCutRequested
            selectAll = onSelectAllRequested
        }

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
            onAutofillRequested: (() -> Unit)?,
        ) {
            showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
            autofill = onAutofillRequested
        }
    }
}
