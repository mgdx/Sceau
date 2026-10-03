package io.github.mgdx.sceau.demo

import io.github.mgdx.sceau.mrz.MrzKeyFields

/** Mode démo absent de l'APK release : voir la version `debug` de cet objet. */
object DemoMode {
    val isAvailable: Boolean = false

    fun newSimulatedCnie(): DemoCard? = null

    fun simulatedMrzScan(): MrzKeyFields? = null
}
