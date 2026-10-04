package io.github.mgdx.sceau.demo

import io.github.mgdx.sceau.testchip.SimulatedDocuments

/**
 * Mode démo de l'APK de debug : CNIe simulée par le module `:testchip`, lue dans la vraie
 * interface sans carte. La version `release` de cet objet est vide et `:testchip` n'y est pas
 * embarqué (`debugImplementation`).
 */
object DemoMode {
    val isAvailable: Boolean = true

    /**
     * Nouvelle CNIe simulée (identité factice SPECIMEN, PKI de test générée à la volée).
     * Coûteux (génération de clés, signature) : à appeler hors du thread principal.
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
