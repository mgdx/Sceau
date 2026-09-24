package io.github.mgdx.sceau

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.mgdx.sceau.nfc.IsoDepTransport
import io.github.mgdx.sceau.session.ReadState
import io.github.mgdx.sceau.session.SessionViewModel
import io.github.mgdx.sceau.ui.SceauNavHost
import io.github.mgdx.sceau.ui.theme.SceauTheme

class MainActivity : ComponentActivity() {
    private val session: SessionViewModel by viewModels()

    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
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

    override fun onResume() {
        super.onResume()
        val options = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_DELAY_MILLIS) }
        nfcAdapter?.enableReaderMode(this, ::onTagDiscovered, READER_FLAGS, options)
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Mise en arrière-plan : lecture interrompue et données lues effacées (SPEC §8).
        if (!isChangingConfigurations) session.onBackground()
    }

    /** Appelé sur le thread du lecteur NFC. */
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

        /** Espacement des tests de présence : assez long pour ne pas gêner les calculs PACE. */
        const val PRESENCE_CHECK_DELAY_MILLIS = 1_000
    }
}
