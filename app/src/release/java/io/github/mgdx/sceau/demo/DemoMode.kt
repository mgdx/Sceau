package io.github.mgdx.sceau.demo

/** Demo mode absent from the release APK: see the `debug` version of this object. */
object DemoMode {
    val isAvailable: Boolean = false

    fun newSimulatedCnie(): DemoCard? = null
}
