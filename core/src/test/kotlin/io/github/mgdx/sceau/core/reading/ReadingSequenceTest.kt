package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.file
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.hexToBytes
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.missingFile
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.raise
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.respond
import io.github.mgdx.sceau.core.reading.TestFixtures.AID_SELECT
import io.github.mgdx.sceau.core.reading.TestFixtures.EXTERNAL_AUTHENTICATE
import io.github.mgdx.sceau.core.reading.TestFixtures.FID_CARD_ACCESS
import io.github.mgdx.sceau.core.reading.TestFixtures.GET_CHALLENGE
import kotlinx.coroutines.test.runTest
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SecurityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/** Séquence de `readAndVerify` jusqu'au canal sécurisé, sur transport simulé. */
class ReadingSequenceTest {
    private val mrz = AccessKey.Mrz("12AB34567", LocalDate.of(1965, 2, 12), LocalDate.of(2030, 12, 31))
    private val can = AccessKey.Can("123456")

    /** EF.CardAccess annonçant PACE ECDH-GM AES-128 sur brainpoolP256r1 (paramètre 13). */
    private val cardAccess =
        CardAccessFile(listOf<SecurityInfo>(PACEInfo(SecurityInfo.ID_PACE_ECDH_GM_AES_CBC_CMAC_128, 2, 13))).encoded

    private suspend inline fun <reified E : SceauException> expect(
        transport: ScriptedTransport,
        key: AccessKey,
        steps: MutableList<Step> = mutableListOf(),
    ): E {
        try {
            readAndVerify(transport, key, TestFixtures.EMPTY_TRUST_STORE) { steps += it }
        } catch (e: SceauException) {
            if (e !is E) throw AssertionError("${E::class.simpleName} attendue, reçu ${e.code}", e)
            assertTrue("transport fermé", transport.closed)
            return e
        }
        fail("${E::class.simpleName} attendue")
        throw IllegalStateException()
    }

    @Test
    fun appletIcaoAbsente_NotIcaoDocument() =
        runTest {
            val transport = ScriptedTransport(missingFile(FID_CARD_ACCESS), respond(AID_SELECT, "6A82"))
            val steps = mutableListOf<Step>()
            expect<SceauException.NotIcaoDocument>(transport, mrz, steps)
            assertEquals(listOf(Step.CONNECT), steps)
            assertTrue(transport.exhausted)
        }

    @Test
    fun canSansCardAccess_CanWithoutPace() =
        runTest {
            val transport = ScriptedTransport(missingFile(FID_CARD_ACCESS), respond(AID_SELECT, "9000"))
            val steps = mutableListOf<Step>()
            expect<SceauException.CanWithoutPace>(transport, can, steps)
            assertEquals(listOf(Step.CONNECT, Step.SECURE_CHANNEL), steps)
            assertTrue(transport.exhausted)
        }

    @Test
    fun documentRetirePendantBac_ConnectionLostInchangee() =
        runTest {
            val lost = SceauException.ConnectionLost()
            val transport =
                ScriptedTransport(missingFile(FID_CARD_ACCESS), respond(AID_SELECT, "9000"), raise(GET_CHALLENGE, lost))
            assertSame(lost, expect<SceauException.ConnectionLost>(transport, mrz))
        }

    @Test
    fun documentRetirePendantLectureCardAccess_ConnectionLostInchangee() =
        runTest {
            // EF.CardAccess est facultatif : son absence est tolérée, pas la perte du document.
            val lost = SceauException.ConnectionLost()
            val transport = ScriptedTransport(raise("00A4020C02$FID_CARD_ACCESS", lost))
            assertSame(lost, expect<SceauException.ConnectionLost>(transport, mrz))
        }

    @Test
    fun delaiDepassePendantSelection_TimeoutInchange() =
        runTest {
            val timeout = SceauException.Timeout()
            val transport = ScriptedTransport(missingFile(FID_CARD_ACCESS), raise(AID_SELECT, timeout))
            assertSame(timeout, expect<SceauException.Timeout>(transport, mrz))
        }

    @Test
    fun mrzRefuseeParBac_AccessDenied() =
        runTest {
            val transport =
                ScriptedTransport(
                    missingFile(FID_CARD_ACCESS),
                    respond(AID_SELECT, "9000"),
                    respond(GET_CHALLENGE, "01020304050607089000"),
                    // JMRTD réessaie EXTERNAL AUTHENTICATE avec Le=00 après un refus.
                    ScriptedTransport.Exchange(EXTERNAL_AUTHENTICATE, repeatable = true) { "6300".hexToBytes() },
                )
            val error = expect<SceauException.AccessDenied>(transport, mrz)
            assertEquals("ACCESS_DENIED", error.code)
            assertTrue(transport.exhausted)
        }

    @Test
    fun canRefuseParPace_AccessDeniedSansRepliBac() =
        runTest {
            val transport =
                ScriptedTransport(
                    *file(FID_CARD_ACCESS, cardAccess),
                    // MSE:Set AT, puis General Authenticate : nonce chiffré, puis refus au mapping.
                    respond("0022C1A4", "9000"),
                    respond("10860000", "7C12801000112233445566778899AABBCCDDEEFF9000"),
                    respond("10860000", "6300"),
                )
            expect<SceauException.AccessDenied>(transport, can)
            assertTrue(transport.exhausted)
            // PACE se fait au niveau MF : pas de sélection en clair de l'applet.
            assertFalse(transport.sent.any { it.startsWith(AID_SELECT) })
        }

    @Test
    fun mrzRefuseeParPace_RepliSurBac() =
        runTest {
            val transport =
                ScriptedTransport(
                    *file(FID_CARD_ACCESS, cardAccess),
                    respond("0022C1A4", "6A80"),
                    respond(AID_SELECT, "9000"),
                    respond(GET_CHALLENGE, "01020304050607089000"),
                    // JMRTD réessaie EXTERNAL AUTHENTICATE avec Le=00 après un refus.
                    ScriptedTransport.Exchange(EXTERNAL_AUTHENTICATE, repeatable = true) { "6300".hexToBytes() },
                )
            val steps = mutableListOf<Step>()
            expect<SceauException.AccessDenied>(transport, mrz, steps)
            assertEquals(listOf(Step.CONNECT, Step.SECURE_CHANNEL), steps)
            assertTrue(transport.exhausted)
        }

    @Test
    fun erreurInattendue_CodeTechniqueSansDonneePersonnelle() =
        runTest {
            val transport = ScriptedTransport(missingFile(FID_CARD_ACCESS), respond(AID_SELECT, "6F00"))
            val error = expect<SceauException.Unexpected>(transport, mrz)
            assertEquals("UNEXPECTED-CONNECT-SELECT_APPLET-CardServiceException-6F00", error.code)
            assertEquals(null, error.cause)
        }
}
