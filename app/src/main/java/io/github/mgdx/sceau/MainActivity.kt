package io.github.mgdx.sceau

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
        super.onCreate(savedInstanceState)
        // FLAG_SECURE sur toute l'activité, avant le premier dessin : l'accueil affiche le CAN
        // et la MRZ saisis, que ni l'aperçu du multitâche (écrit sur disque par le système) ni
        // une capture ne doivent contenir (audit V7). Aucun écran n'a besoin d'être capturé.
        secureForLifetime(window)
        // Aucun champ de l'appli ne doit être proposé à un service d'autofill (gestionnaire de
        // mots de passe synchronisé) : CAN, numéro de document et dates (audit V14). Compose
        // force IMPORTANT_FOR_AUTOFILL_YES sur sa propre vue ; l'exclusion est donc posée sur
        // la vue racine, dont View.isImportantForAutofill() tient compte pour tous ses
        // descendants, nœuds virtuels de la composition compris.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        // Mode lecteur armé à chaque reprise et chaque fois que le NFC repasse à ON au premier
        // plan : un enableReaderMode appelé NFC éteint est perdu, et le volet rapide ne fait pas
        // repasser l'activité par onResume. Sans cela, plus aucune carte n'est détectée.
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

        /**
         * Espacement des tests de présence du service NFC pendant qu'un tag est connecté. Sur
         * certaines puces NXP, un test de présence intercalé pendant les calculs PACE d'une puce
         * lente peut faire échouer l'échange en cours : 2 s les espace davantage (1 s
         * auparavant), sans retarder notablement la détection d'un document retiré entre deux
         * lectures. À ajuster d'après les essais sur appareil réel.
         */
        const val PRESENCE_CHECK_DELAY_MILLIS = 2_000
    }
}
