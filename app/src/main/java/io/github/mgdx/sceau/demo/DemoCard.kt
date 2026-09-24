package io.github.mgdx.sceau.demo

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.trust.TrustStore

/**
 * Document simulé du mode démo (APK de debug uniquement, voir `DemoMode` dans les jeux de
 * sources `debug` et `release`). Une carte sert à une seule lecture : [transport] a un état.
 *
 * @property key clé d'accès imprimée sur le document simulé
 * @property trustStore magasin de test du document simulé ; il ne sert qu'à cette lecture et ne
 *   remplace jamais le magasin de confiance réel
 */
class DemoCard(
    val transport: CardTransport,
    val key: AccessKey,
    val trustStore: TrustStore,
)
