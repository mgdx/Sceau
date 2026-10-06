package io.github.mgdx.sceau.ui.trust

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.mgdx.sceau.core.trust.TrustStores
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Message d'une Master List refusée par `MasterListParser` (D22, audit V22). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "fr")
class InvalidMasterListMessageTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `une Master List de trop de certificats donne le message dedie avec le plafond`() {
        assertEquals(2000, TrustStores.MAX_MASTER_LIST_CERTIFICATES)
        assertEquals(
            "Master List refusée : elle contient trop de certificats (2000 au plus).",
            invalidMasterListMessage(resources, "TOO_MANY_CERTIFICATES"),
        )
    }

    @Test
    fun `les autres codes gardent le message generique`() {
        for (code in listOf("MULTIPLE_SIGNERS", "NO_SIGNER", "BAD_SIGNATURE", "UNREADABLE")) {
            assertEquals(
                "Ce fichier n’est pas une Master List valide : format illisible ou signature incorrecte ($code).",
                invalidMasterListMessage(resources, code),
            )
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun `message dedie traduit`() {
        assertEquals(
            "Master List rejected: it contains too many certificates (2000 at most).",
            invalidMasterListMessage(resources, "TOO_MANY_CERTIFICATES"),
        )
    }
}
