package io.github.mgdx.sceau.ui.reading

import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.session.ReadState

/**
 * Message « la puce fait patienter » de l'écran de lecture : affiché quand l'ouverture du canal
 * sécurisé dure plus de [DELAY_MILLIS]. Après des essais ratés, une puce peut imposer un délai
 * croissant avant de répondre (`:core` l'attend jusqu'à 60 s).
 */
internal object SlowChipHint {
    const val DELAY_MILLIS = 5_000L

    /** Vrai pendant l'étape où la puce peut faire patienter : le minuteur tourne. */
    fun isTimed(state: ReadState): Boolean = state is ReadState.Reading && state.current == Step.SECURE_CHANNEL
}
