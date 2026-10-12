package io.github.mgdx.sceau.ui.trust

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.core.trust.MasterListInfo
import io.github.mgdx.sceau.ui.theme.SceauTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Master List import preview: warning and separate confirmation when the signer is not anchored (audit V34). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "fr-rFR")
class ImportConfirmDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val info =
        MasterListInfo(
            signingTime = null,
            signerSubject = "CN=CSCA XY,C=XY",
            signerSha256 = "ab".repeat(32),
            certificateCount = 3,
        )

    private var confirmed = false

    private fun show(anchored: Boolean) {
        compose.setContent {
            SceauTheme(darkTheme = false) {
                ImportConfirmDialog(info, newCertificates = 3, anchored = anchored, onConfirm = { confirmed = true }, onDismiss = {})
            }
        }
    }

    private fun text(id: Int) = compose.activity.getString(id)

    @Test
    fun `unanchored signer - warning and separate confirmation required`() {
        show(anchored = false)

        compose.onNodeWithText(text(R.string.trust_import_unanchored_warning)).assertExists()
        val confirm = compose.onNodeWithText(text(R.string.trust_import_confirm))
        confirm.assertIsNotEnabled()
        confirm.performClick()
        assertFalse(confirmed)

        compose.onNodeWithText(text(R.string.trust_import_unanchored_acknowledge)).performScrollTo().performClick()
        confirm.assertIsEnabled()
        confirm.performClick()
        assertTrue(confirmed)
    }

    @Test
    fun `anchored signer - no warning and no checkbox`() {
        show(anchored = true)

        compose.onNodeWithText(text(R.string.trust_import_unanchored_warning)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.trust_import_unanchored_acknowledge)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.trust_import_confirm)).assertIsEnabled().performClick()
        assertTrue(confirmed)
    }
}
