package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.report.ChannelProtocol
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.trust.TrustStore
import io.github.mgdx.sceau.core.verify.PassiveAuthenticator

/**
 * Downgrade of the access protocol (audit V20, decision D40; audit V32, decision D43). The choice
 * between PACE and BAC, and the PACE mapping, are decided from EF.CardAccess, which is not
 * signed: a clone can remove PACE-CAM from it, or PACE altogether. The protocol performed is therefore
 * compared, after Passive Authentication, with what the signed elements announce: DG14, whose
 * hash is verified, and EF.CardSecurity, read after any PACE and verified like the SOD:
 *
 * - DG14 or EF.CardSecurity announces PACE-CAM, the channel was established without CAM:
 *   `CHIP_AUTHENTICATION` `FAILED` (`VERIFY_CHIP-DOWNGRADE-CAM`);
 * - EF.CardSecurity is present but invalid (signature, chain, structure): what it announces
 *   cannot be verified, `CHIP_AUTHENTICATION` `FAILED` (`VERIFY_CHIP-DOWNGRADE-CARD_SECURITY`);
 * - EF.CardSecurity announces a Chip Authentication key or protocol, which neither CAM nor CA
 *   via DG14 carried out: `CHIP_AUTHENTICATION` `FAILED` (`VERIFY_CHIP-DOWNGRADE-CA`);
 * - DG14 announces PACE, the channel was established with BAC: `SECURE_CHANNEL` `FAILED`
 *   (`VERIFY_CHIP-DOWNGRADE-BAC`). EF.CardSecurity is never read under BAC.
 *
 * These rules apply only if the chip has not been authenticated otherwise: a successful Chip
 * Authentication via DG14 or Active Authentication already proves that it is
 * the original, and the protocol performed changes nothing to that proof. The PACE → BAC fallback (D13) and a
 * CAM `PACEInfo` discarded by JMRTD thus have no effect on a genuine document whose
 * chip answers CA or AA; they cost the verdict only for a chip that nothing else
 * verifies, precisely the clone case. With PACE-CAM, EF.CardSecurity is verified by
 * [ChipAuthenticationMapping] (D21) and is not examined here.
 */
internal object ProtocolDowngrade {
    class Result(
        val secureChannel: Check,
        val chipAuthentication: Check,
    )

    /** What EF.CardSecurity, read after PACE without CAM, makes it possible to establish. */
    sealed interface CardSecurity {
        /** Absent (BAC, file not found), or PACE-CAM performed: nothing to compare. */
        data object Absent : CardSecurity

        /** Present, but its signature, its chain or its structure cannot be verified. */
        data object Unverifiable : CardSecurity

        /** Protocol OIDs of its `SecurityInfo`s. */
        class Announced(
            val oids: List<String>,
        ) : CardSecurity
    }

    /**
     * Verifies [cardSecurity] like the SOD ([PassiveAuthenticator.verifyCardSecurity]: signature,
     * DS → CSCA chain, country of [issuingState]). Its content only serves to make CA fail,
     * never to make it succeed: content whose DS or CSCA cannot be found
     * (`NOT_AVAILABLE`) is therefore taken as is, and its CSCA need not be the SOD's (V17);
     * a clone has nothing to gain from presenting such a file rather than none.
     */
    fun cardSecurity(
        cardSecurity: ByteArray?,
        trustStore: TrustStore,
        issuingState: String?,
    ): CardSecurity {
        if (cardSecurity == null) return CardSecurity.Absent
        val verified = PassiveAuthenticator(trustStore).verifyCardSecurity(cardSecurity, issuingState)
        val content =
            verified.content?.takeIf { verified.status == CheckStatus.OK || verified.status == CheckStatus.NOT_AVAILABLE }
                ?: return CardSecurity.Unverifiable
        val oids = SecurityInfoProtocols.ofSet(content) ?: return CardSecurity.Unverifiable
        return CardSecurity.Announced(oids)
    }

    /**
     * [dg14]: DG14 read, or null; [dg14Verified]: its hash is in the SOD and matches;
     * [cardSecurity]: EF.CardSecurity read after PACE without CAM (see [cardSecurity]);
     * [established]: channel performed; [secureChannel], [chipAuthentication], [activeAuthentication]:
     * rows computed so far.
     */
    fun apply(
        dg14: ByteArray?,
        dg14Verified: Boolean,
        cardSecurity: CardSecurity,
        established: EstablishedChannel,
        secureChannel: Check,
        chipAuthentication: Check,
        activeAuthentication: Check,
    ): Result {
        val unchanged = Result(secureChannel, chipAuthentication)
        if (chipAuthentication.status == CheckStatus.OK || activeAuthentication.status == CheckStatus.OK) return unchanged
        val fromDg14 = dg14?.takeIf { dg14Verified }?.let(SecurityInfoProtocols::of).orEmpty()
        val fromCardSecurity = (cardSecurity as? CardSecurity.Announced)?.oids.orEmpty()
        // Only a "not available" can hide a downgrade: a failure remains a failure.
        val caOpen = established.cam == null && chipAuthentication.status == CheckStatus.NOT_AVAILABLE
        val ca =
            when {
                !caOpen -> {
                    chipAuthentication
                }

                SecurityInfoProtocols.announcesPaceCam(fromDg14 + fromCardSecurity) -> {
                    downgraded(chipAuthentication, "CAM")
                }

                cardSecurity is CardSecurity.Unverifiable -> {
                    downgraded(chipAuthentication, "CARD_SECURITY")
                }

                SecurityInfoProtocols.chipAuthenticationKey(fromCardSecurity) != null ||
                    SecurityInfoProtocols.announcesChipAuthentication(fromCardSecurity) -> {
                    downgraded(chipAuthentication, "CA")
                }

                else -> {
                    chipAuthentication
                }
            }
        val channel =
            if (SecurityInfoProtocols.announcesPace(fromDg14) && established.protocol == ChannelProtocol.BAC) {
                downgraded(secureChannel, "BAC")
            } else {
                secureChannel
            }
        return Result(channel, ca)
    }

    private fun downgraded(
        check: Check,
        protocol: String,
    ) = Check(check.id, CheckStatus.FAILED, CheckDetail.Error("${Step.VERIFY_CHIP.name}-DOWNGRADE-$protocol"))
}
