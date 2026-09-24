package io.github.mgdx.sceau.demo

/** Mode démo absent de l'APK release : voir la version `debug` de cet objet. */
object DemoMode {
    val isAvailable: Boolean = false

    fun newSimulatedCnie(): DemoCard? = null
}
