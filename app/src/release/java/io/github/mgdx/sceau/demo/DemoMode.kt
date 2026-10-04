package io.github.mgdx.sceau.demo

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.mgdx.sceau.mrz.MrzKeyFields
import io.github.mgdx.sceau.ui.scan.ScanDiagnosticsSnapshot

/** Mode démo absent de l'APK release : voir la version `debug` de cet objet. */
object DemoMode {
    val isAvailable: Boolean = false

    fun newSimulatedCnie(): DemoCard? = null

    fun simulatedMrzScan(): MrzKeyFields? = null

    /** Constante : le compilateur retire le code du diagnostic de l'APK release. */
    const val SCAN_DIAGNOSTICS: Boolean = false

    /** Jamais appelé en release ([SCAN_DIAGNOSTICS] est faux) ; pas de chaîne associée. */
    @Composable
    fun ScanDiagnosticsBanner(
        snapshot: ScanDiagnosticsSnapshot,
        modifier: Modifier = Modifier,
    ) = Unit
}
