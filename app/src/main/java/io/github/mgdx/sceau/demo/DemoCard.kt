package io.github.mgdx.sceau.demo

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.trust.TrustStore

/**
 * Simulated document of demo mode (debug APK only, see `DemoMode` in the `debug` and `release`
 * source sets). A card is used for a single read: [transport] is stateful.
 *
 * @property key access key printed on the simulated document
 * @property trustStore test trust store of the simulated document; it is used only for this read
 *   and never replaces the real trust store
 */
class DemoCard(
    val transport: CardTransport,
    val key: AccessKey,
    val trustStore: TrustStore,
)
