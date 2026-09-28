package io.github.mgdx.sceau.core.fuzz

import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.verify.ActiveAuthentication
import org.junit.Test
import java.util.Random

/**
 * Fuzzing de la vérification de la réponse d'Active Authentication (RSA ISO/IEC 9796-2 et
 * ECDSA brut). Contrat : jamais d'exception (OK, FAILED ou UNSUPPORTED_ALGORITHM), et jamais
 * OK pour une réponse mutée.
 */
class ActiveAuthenticationFuzzTest {
    @Test
    fun mutatedResponse() {
        val documents = FuzzSeeds.documents.filter { it.document.aaKeyPair != null }
        val challenge = ByteArray(CHALLENGE).also { Random(2).nextBytes(it) }
        val responses = documents.map { checkNotNull(it.aaResponse(challenge)) }
        FuzzCampaign("AA", responses, MAX_RESPONSE, FuzzConfig.iterations(ITERATIONS)).run { input ->
            val document = documents[input.seedIndex].document
            val key = checkNotNull(document.aaKeyPair).public
            val check = ActiveAuthentication.verifyResponse(key, document.aaDigestAlgorithm, challenge, input.bytes)
            if (input.pristine) {
                property(check.status == CheckStatus.OK) { "graine AA : ${check.status}" }
            } else if (!input.bytes.contentEquals(responses[input.seedIndex])) {
                property(check.status != CheckStatus.OK) { "réponse AA mutée acceptée" }
            }
        }
    }

    private companion object {
        const val CHALLENGE = 8
        const val ITERATIONS = 3_000

        /** Une réponse tient dans une APDU courte. */
        const val MAX_RESPONSE = 256
    }
}
