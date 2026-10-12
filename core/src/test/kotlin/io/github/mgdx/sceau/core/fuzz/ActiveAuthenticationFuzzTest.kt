package io.github.mgdx.sceau.core.fuzz

import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.verify.ActiveAuthentication
import org.junit.Test
import java.util.Random

/**
 * Fuzzing of the Active Authentication response check (RSA ISO/IEC 9796-2 and raw ECDSA).
 * Contract: never an exception (OK, FAILED or UNSUPPORTED_ALGORITHM), and never OK for a
 * mutated response.
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
                property(check.status == CheckStatus.OK) { "AA seed: ${check.status}" }
            } else if (!input.bytes.contentEquals(responses[input.seedIndex])) {
                property(check.status != CheckStatus.OK) { "mutated AA response accepted" }
            }
        }
    }

    private companion object {
        const val CHALLENGE = 8
        const val ITERATIONS = 3_000

        /** A response fits in a short APDU. */
        const val MAX_RESPONSE = 256
    }
}
