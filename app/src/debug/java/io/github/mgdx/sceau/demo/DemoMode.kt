package io.github.mgdx.sceau.demo

import io.github.mgdx.sceau.testchip.SimulatedDocuments

/**
 * Demo mode of the debug APK: CNIe simulated by the `:testchip` module, read in the real
 * UI without a card. The `release` version of this object is empty and `:testchip` is not
 * bundled in it (`debugImplementation`).
 */
object DemoMode {
    val isAvailable: Boolean = true

    /**
     * New simulated CNIe (SPECIMEN dummy identity, test PKI generated on the fly).
     * Expensive (key generation, signing): to be called off the main thread.
     */
    fun newSimulatedCnie(): DemoCard? {
        val card = SimulatedDocuments.frenchIdCard()
        return DemoCard(
            transport = card.chip(),
            key = checkNotNull(card.canKey),
            trustStore = card.trustStore,
        )
    }
}
