package io.github.mgdx.sceau

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.mgdx.sceau.nfc.IsoDepTransport
import io.github.mgdx.sceau.nfc.NfcAvailability
import io.github.mgdx.sceau.nfc.nfcAvailabilityFlow
import io.github.mgdx.sceau.session.ReadState
import io.github.mgdx.sceau.session.SessionViewModel
import io.github.mgdx.sceau.ui.SceauNavHost
import io.github.mgdx.sceau.ui.common.secureForLifetime
import io.github.mgdx.sceau.ui.theme.SceauTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val session: SessionViewModel by viewModels()

    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Exported activity (launcher): no other app must be able to choose the opening screen
        // through the deep link extras that Navigation reads from the intent on the first
        // setGraph (audit V27). Sceau expects nothing from its intent: it is replaced, before
        // composition, by the launcher one, without extras.
        intent = launcherIntent()
        super.onCreate(savedInstanceState)
        // FLAG_SECURE on the whole activity, before the first draw: the home screen shows the
        // entered CAN and MRZ, which neither the recents preview (written to disk by the system)
        // nor a screenshot must contain (audit V7). No screen needs to be captured.
        secureForLifetime(window)
        // No field of the app must be offered to an autofill service (synced password
        // manager): CAN, document number and dates (audit V14). Compose forces
        // IMPORTANT_FOR_AUTOFILL_YES on its own view; the exclusion is therefore set on the
        // root view, which View.isImportantForAutofill() takes into account for all its
        // descendants, virtual nodes of the composition included.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        // Reader mode armed on every resume and every time NFC switches back to ON in the
        // foreground: an enableReaderMode called while NFC is off is lost, and the quick settings
        // panel does not take the activity through onResume again. Without this, no card is detected anymore.
        if (nfcAdapter != null) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    nfcAvailabilityFlow().collect { if (it == NfcAvailability.ENABLED) enableReaderMode() }
                }
            }
        }
        val trustStoreRepository = (application as SceauApplication).trustStoreRepository
        setContent {
            SceauTheme {
                SceauNavHost(
                    session = session,
                    trustStoreRepository = trustStoreRepository,
                )
            }
        }
    }

    /** `singleTop`: a new external intent is neutralized the same way (audit V27). */
    override fun onNewIntent(intent: Intent) {
        val launcher = launcherIntent()
        setIntent(launcher)
        super.onNewIntent(launcher)
    }

    private fun launcherIntent(): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(this, MainActivity::class.java)

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    private fun enableReaderMode() {
        val options = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_DELAY_MILLIS) }
        nfcAdapter?.enableReaderMode(this, ::onTagDiscovered, READER_FLAGS, options)
    }

    override fun onStop() {
        super.onStop()
        // Going to the background: read interrupted and data read cleared (SPEC §8).
        if (!isChangingConfigurations) session.onBackground()
    }

    /** Called on the NFC reader thread. */
    private fun onTagDiscovered(tag: Tag) {
        val isoDep = IsoDep.get(tag) ?: return
        when (session.state.value) {
            ReadState.WaitingForCard, is ReadState.Error -> session.onCardDetected(IsoDepTransport(isoDep))
            else -> Unit
        }
    }

    private companion object {
        const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        /**
         * Interval between the NFC service's presence checks while a tag is connected. On
         * some NXP chips, a presence check slipped in during the PACE computations of a slow chip
         * can make the ongoing exchange fail: 2 s spaces them out more (1 s previously), without
         * noticeably delaying the detection of a document removed between two reads. To be
         * tuned based on tests on real devices.
         */
        const val PRESENCE_CHECK_DELAY_MILLIS = 2_000
    }
}
