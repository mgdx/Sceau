package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.report.ChannelProtocol
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.trust.TrustStore
import io.github.mgdx.sceau.core.verify.PassiveAuthenticator

/**
 * Déclassement du protocole d'accès (audit V20, décision D40 ; audit V32, décision D43). Le choix
 * entre PACE et BAC, et le mapping de PACE, se décident d'après EF.CardAccess, qui n'est pas
 * signé : un clone peut y retirer PACE-CAM, ou PACE tout entier. Le protocole mené est donc
 * comparé, après la Passive Authentication, à ce qu'annoncent les éléments signés : DG14, dont
 * l'empreinte est vérifiée, et EF.CardSecurity, lu après tout PACE et vérifié comme le SOD :
 *
 * - DG14 ou EF.CardSecurity annonce PACE-CAM, le canal a été établi sans CAM :
 *   `CHIP_AUTHENTICATION` `FAILED` (`VERIFY_CHIP-DOWNGRADE-CAM`) ;
 * - EF.CardSecurity est présent mais invalide (signature, chaîne, structure) : ce qu'il annonce
 *   n'est pas vérifiable, `CHIP_AUTHENTICATION` `FAILED` (`VERIFY_CHIP-DOWNGRADE-CARD_SECURITY`) ;
 * - EF.CardSecurity annonce une clé ou le protocole de Chip Authentication, que ni CAM ni la CA
 *   via DG14 n'ont mis en œuvre : `CHIP_AUTHENTICATION` `FAILED` (`VERIFY_CHIP-DOWNGRADE-CA`) ;
 * - DG14 annonce PACE, le canal a été établi par BAC : `SECURE_CHANNEL` `FAILED`
 *   (`VERIFY_CHIP-DOWNGRADE-BAC`). EF.CardSecurity n'est jamais lu sous BAC.
 *
 * Ces règles ne s'appliquent que si la puce n'a pas été authentifiée autrement : une Chip
 * Authentication via DG14 ou une Active Authentication réussie prouve déjà qu'elle est
 * l'originale, et le protocole mené ne change rien à cette preuve. Le repli PACE → BAC (D13) et un
 * `PACEInfo` CAM écarté par JMRTD restent ainsi sans effet sur un document authentique dont la
 * puce répond à la CA ou à l'AA ; ils ne coûtent le verdict qu'à une puce que rien d'autre ne
 * vérifie, précisément le cas du clone. Avec PACE-CAM, EF.CardSecurity est vérifié par
 * [ChipAuthenticationMapping] (D21) et n'est pas examiné ici.
 */
internal object ProtocolDowngrade {
    class Result(
        val secureChannel: Check,
        val chipAuthentication: Check,
    )

    /** Ce qu'EF.CardSecurity, lu après PACE sans CAM, permet d'établir. */
    sealed interface CardSecurity {
        /** Absent (BAC, fichier introuvable), ou PACE-CAM mené : rien à comparer. */
        data object Absent : CardSecurity

        /** Présent, mais sa signature, sa chaîne ou sa structure ne sont pas vérifiables. */
        data object Unverifiable : CardSecurity

        /** OID de protocole de ses `SecurityInfo`. */
        class Announced(
            val oids: List<String>,
        ) : CardSecurity
    }

    /**
     * Vérifie [cardSecurity] comme le SOD ([PassiveAuthenticator.verifyCardSecurity] : signature,
     * chaîne DS → CSCA, pays de [issuingState]). Son contenu ne sert qu'à mettre la CA en échec,
     * jamais à la faire réussir : un contenu dont le DS ou le CSCA est introuvable
     * (`NOT_AVAILABLE`) est donc pris tel quel, et son CSCA n'a pas à être celui du SOD (V17) ;
     * un clone n'a rien à gagner à présenter un tel fichier plutôt qu'aucun.
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
     * [dg14] : DG14 lu, ou null ; [dg14Verified] : son empreinte est dans le SOD et conforme ;
     * [cardSecurity] : EF.CardSecurity lu après PACE sans CAM (voir [cardSecurity]) ;
     * [established] : canal mené ; [secureChannel], [chipAuthentication], [activeAuthentication] :
     * lignes calculées jusque-là.
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
        // Seul un « non disponible » peut cacher un déclassement : un échec reste un échec.
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
