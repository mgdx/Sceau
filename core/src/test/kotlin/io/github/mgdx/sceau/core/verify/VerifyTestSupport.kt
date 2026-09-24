package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.ChannelProtocol
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus

/** Assemble les sept lignes de la liste de contrôle à partir d'une PA et des challenges. */
object VerifyTestSupport {
    val notAvailableAa = Check(CheckId.ACTIVE_AUTHENTICATION, CheckStatus.NOT_AVAILABLE)

    fun checks(
        passiveAuth: PassiveAuthResult,
        ca: CheckStatus = CheckStatus.NOT_AVAILABLE,
        aa: Check = notAvailableAa,
        secureChannel: CheckStatus = CheckStatus.OK,
    ): List<Check> =
        listOf(
            Check(CheckId.SECURE_CHANNEL, secureChannel, CheckDetail.SecureChannel(ChannelProtocol.PACE)),
            passiveAuth.sodSignature,
            passiveAuth.certificateChain,
            passiveAuth.dsValidity,
            passiveAuth.dataGroupHashes,
            Check(CheckId.CHIP_AUTHENTICATION, ca),
            aa,
        )
}
