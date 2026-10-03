package io.github.mgdx.sceau.demo

import io.github.mgdx.sceau.mrz.MrzFormat
import io.github.mgdx.sceau.mrz.MrzKeyFields
import io.github.mgdx.sceau.testchip.SimulatedDocuments
import java.time.format.DateTimeFormatter

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

    /**
     * Champs de la MRZ (TD1) de la CNIe simulée, tels que l'écran de scan les rendrait, pour
     * tester le remplissage de l'accueil sans caméra. Constantes du spécimen : aucune PKI générée.
     */
    fun simulatedMrzScan(): MrzKeyFields? {
        val yymmdd = DateTimeFormatter.ofPattern("yyMMdd")
        return MrzKeyFields(
            format = MrzFormat.TD1,
            documentNumber = SimulatedDocuments.SPECIMEN_DOCUMENT_NUMBER,
            dateOfBirth = SimulatedDocuments.SPECIMEN_DATE_OF_BIRTH.format(yymmdd),
            dateOfExpiry = SimulatedDocuments.SPECIMEN_DATE_OF_EXPIRY.format(yymmdd),
        )
    }
}
