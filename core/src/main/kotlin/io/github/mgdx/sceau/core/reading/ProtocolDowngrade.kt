package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.report.ChannelProtocol
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckStatus

/**
 * Déclassement du protocole d'accès (audit V20, décision D40). Le choix entre PACE et BAC, et le
 * mapping de PACE, se décident d'après EF.CardAccess, qui n'est pas signé : un clone peut y
 * retirer PACE-CAM, ou PACE tout entier. Le protocole mené est donc comparé, après la Passive
 * Authentication, à ce qu'annonce DG14, signé, dont l'empreinte est vérifiée :
 *
 * - DG14 annonce PACE-CAM, le canal a été établi sans CAM : `CHIP_AUTHENTICATION` `FAILED`
 *   (`VERIFY_CHIP-DOWNGRADE-CAM`) ;
 * - DG14 annonce PACE, le canal a été établi par BAC : `SECURE_CHANNEL` `FAILED`
 *   (`VERIFY_CHIP-DOWNGRADE-BAC`).
 *
 * Ces règles ne s'appliquent que si la puce n'a pas été authentifiée autrement : une Chip
 * Authentication via DG14 ou une Active Authentication réussie prouve déjà qu'elle est
 * l'originale, et le protocole mené ne change rien à cette preuve. Le repli PACE → BAC (D13) et un
 * `PACEInfo` CAM écarté par JMRTD restent ainsi sans effet sur un document authentique dont la
 * puce répond à la CA ou à l'AA ; ils ne coûtent le verdict qu'à une puce que rien d'autre ne
 * vérifie, précisément le cas du clone.
 */
internal object ProtocolDowngrade {
    class Result(
        val secureChannel: Check,
        val chipAuthentication: Check,
    )

    /**
     * [dg14] : DG14 lu, ou null ; [dg14Verified] : son empreinte est dans le SOD et conforme ;
     * [established] : canal mené ; [secureChannel], [chipAuthentication], [activeAuthentication] :
     * lignes calculées jusque-là.
     */
    fun apply(
        dg14: ByteArray?,
        dg14Verified: Boolean,
        established: EstablishedChannel,
        secureChannel: Check,
        chipAuthentication: Check,
        activeAuthentication: Check,
    ): Result {
        val unchanged = Result(secureChannel, chipAuthentication)
        if (dg14 == null || !dg14Verified) return unchanged
        if (chipAuthentication.status == CheckStatus.OK || activeAuthentication.status == CheckStatus.OK) return unchanged
        val announced = SecurityInfoProtocols.of(dg14) ?: return unchanged
        val ca =
            if (SecurityInfoProtocols.announcesPaceCam(announced) &&
                established.cam == null &&
                chipAuthentication.status == CheckStatus.NOT_AVAILABLE
            ) {
                downgraded(chipAuthentication, "CAM")
            } else {
                chipAuthentication
            }
        val channel =
            if (SecurityInfoProtocols.announcesPace(announced) && established.protocol == ChannelProtocol.BAC) {
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
