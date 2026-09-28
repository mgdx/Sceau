package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.report.ChannelProtocol
import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.protocol.PACECAMResult
import org.jmrtd.protocol.PACEGMMappingResult
import org.jmrtd.protocol.PACEResult
import java.time.LocalDate
import java.util.Locale

/**
 * Étapes CONNECT et SECURE_CHANNEL (SPEC §6.1, étapes 1 et 2).
 *
 * Ordre des échanges :
 * 1. EF.CardAccess est lu au niveau MF, avant toute sélection d'applet. Absent ou illisible :
 *    pas de PACE.
 * 2. Sans PACE, l'applet ICAO est sélectionnée en clair dès la connexion (6A82 →
 *    [SceauException.NotIcaoDocument]), puis BAC avec la clé MRZ.
 * 3. Avec PACE, PACE est mené au niveau MF (ICAO 9303-11 : l'applet est sélectionnée après
 *    PACE, sous messagerie sécurisée), en essayant chaque `PACEInfo` annoncé dans l'ordre ;
 *    la sélection de l'applet qui suit détecte alors l'absence d'application ICAO.
 * 4. Si PACE échoue avec une clé MRZ pour une autre raison qu'un refus explicite de la clé
 *    (SW 63xx) ou qu'un délai dépassé, c'est-à-dire perte de liaison, SW inattendu ou erreur de
 *    JMRTD, la liaison est réinitialisée ([CardTransport.reconnect]) puis BAC est mené
 *    (sélection en clair de l'applet puis BAC) : les documents qui annoncent PACE restent tenus
 *    d'accepter BAC jusqu'à la fin de la transition ICAO.
 *    Avec un CAN, aucun repli possible : l'erreur du transport ressort telle quelle, un refus ou
 *    un échec de PACE donne [SceauException.AccessDenied].
 * 5. Pendant l'authentification (PACE, puis BAC), le délai de réponse est porté à
 *    [AUTHENTICATION_TIMEOUT_MILLIS], puis rétabli pour la suite. Après des essais ratés, une
 *    puce peut imposer un délai croissant avant de répondre à la première commande
 *    d'authentification (contre-mesure anti-force brute, observée sur un passeport français
 *    muet au premier GENERAL AUTHENTICATE comme à l'EXTERNAL AUTHENTICATE de BAC) :
 *    l'interrompre plus tôt ne la laisse jamais répondre et peut aggraver la pénalité. Un délai
 *    dépassé pendant PACE arrête donc la lecture, même avec une clé MRZ
 *    ([SceauException.Timeout] avec diagnostic) : relancer BAC sur une puce en pénalité ne
 *    ferait qu'ajouter un essai interrompu.
 * 6. PACE-CAM (décision D21) : si PACE aboutit avec le mapping CAM, EF.CardSecurity est lu au
 *    MF sous la nouvelle messagerie sécurisée, avant la sélection de l'applet ; sa vérification
 *    et celle des données de Chip Authentication ont lieu plus tard
 *    ([ChipAuthenticationMapping]), quand DG1 (État émetteur) est connu.
 */
internal class SecureChannel(
    private val chip: Chip,
    private val key: AccessKey,
) {
    private val service get() = chip.service

    /** Étape CONNECT. Renvoie les `PACEInfo` annoncés (vide : pas de PACE). */
    fun connect(): List<PACEInfo> {
        service.open()
        val paceInfos = readPaceInfos()
        if (paceInfos.isEmpty()) selectApplet(secure = false)
        return paceInfos
    }

    /**
     * Étape SECURE_CHANNEL. Renvoie le protocole établi et, si PACE a abouti avec le mapping
     * Chip Authentication Mapping (PACE-CAM, ICAO 9303-11 §4.4), les éléments à vérifier :
     * EF.CardSecurity, lu au MF sous la nouvelle messagerie sécurisée avant la sélection de
     * l'applet, et les données de Chip Authentication déchiffrées par JMRTD (voir
     * [ChipAuthenticationMapping], décision D21).
     */
    fun establish(paceInfos: List<PACEInfo>): EstablishedChannel {
        if (paceInfos.isNotEmpty()) {
            val pace = withAuthenticationTimeout { tryPace(paceInfos) }
            if (pace != null) {
                val cam = if (pace.mappingType == PACEInfo.MappingType.CAM) camEvidence(pace) else null
                selectApplet(secure = true)
                return EstablishedChannel(ChannelProtocol.PACE, cam)
            }
            if (key is AccessKey.Can) throw SceauException.AccessDenied()
            // PACE a pu laisser la puce au milieu du protocole, voire muette : on repart de zéro.
            chip.reconnect()
            selectApplet(secure = false)
        } else if (key is AccessKey.Can) {
            throw SceauException.CanWithoutPace()
        }
        withAuthenticationTimeout { doBac(key as AccessKey.Mrz) }
        return EstablishedChannel(ChannelProtocol.BAC, cam = null)
    }

    /**
     * PACE-CAM : EF.CardSecurity (MF, sous la messagerie de PACE), données de Chip
     * Authentication déchiffrées par JMRTD (CA_IC) et clé publique de mapping de la puce
     * (PK_Map,IC). Tout élément manquant est laissé à null : la vérification le signalera.
     */
    private fun camEvidence(pace: PACEResult): PaceCamEvidence =
        PaceCamEvidence(
            chipAuthenticationData = (pace as? PACECAMResult)?.chipAuthenticationData,
            mappingKey = (pace.mappingResult as? PACEGMMappingResult)?.piccMappingPublicKey,
            cardSecurity = chip.readOptionalSecuredMasterFile(PassportService.EF_CARD_SECURITY),
        )

    /**
     * Rétablit un canal sécurisé sur une liaison neuve, avec la même clé, dans la même lecture :
     * après une Chip Authentication ratée, la puce a pu clore la messagerie sécurisée (MAC
     * invalide sous les nouvelles clés). Reconnexion, puis [connect] et [establish] comme au
     * début de la lecture, repli PACE → BAC compris. Les erreurs ressortent comme au premier
     * établissement (clé refusée, délai, reconnexion impossible).
     */
    fun reestablish(): EstablishedChannel {
        chip.reconnect()
        return establish(connect())
    }

    /** Exécute [block] avec le délai de réponse [AUTHENTICATION_TIMEOUT_MILLIS], puis rétablit le délai. */
    private inline fun <T> withAuthenticationTimeout(block: () -> T): T {
        val transport = chip.transport
        val savedTimeout = transport.timeoutMillis
        transport.timeoutMillis = maxOf(savedTimeout, AUTHENTICATION_TIMEOUT_MILLIS)
        try {
            return block()
        } finally {
            transport.timeoutMillis = savedTimeout
        }
    }

    private fun readPaceInfos(): List<PACEInfo> {
        val bytes = chip.readOptionalFile(PassportService.EF_CARD_ACCESS) ?: return emptyList()
        val file =
            try {
                CardAccessFile(bytes.inputStream())
            } catch (e: Exception) {
                return emptyList()
            }
        return file.securityInfos.orEmpty().filterIsInstance<PACEInfo>()
    }

    /**
     * Résultat de PACE s'il a abouti avec l'un des `PACEInfo` annoncés, null s'il a échoué sans
     * refus de la clé. Lève [SceauException.AccessDenied] si la puce refuse la clé (SW 63xx), et
     * relance un délai dépassé ainsi que, avec un CAN, toute erreur du transport. Avec une clé
     * MRZ, une perte de liaison arrête les essais (la liaison est à réinitialiser) et renvoie
     * null.
     */
    private fun tryPace(paceInfos: List<PACEInfo>): PACEResult? {
        val paceKey =
            when (key) {
                is AccessKey.Can -> PACEKeySpec.createCANKey(key.value)
                is AccessKey.Mrz -> PACEKeySpec.createMRZKey(bacKey(key))
            }
        for (info in paceInfos) {
            val parameterId = info.parameterId ?: continue
            val parameters =
                try {
                    PACEInfo.toParameterSpec(parameterId)
                } catch (e: Exception) {
                    // Paramètres propriétaires (PACEDomainParameterInfo) : non pris en charge.
                    continue
                }
            try {
                return service.doPACE(paceKey, info.objectIdentifier, parameters, parameterId)
            } catch (e: Exception) {
                chip.cardService.transportFailure?.let { failure ->
                    // Délai dépassé : puce muette ou en pénalité, un nouvel essai n'y changerait rien.
                    if (key is AccessKey.Can || failure is SceauException.Timeout) throw failure
                    return null
                }
                if (e.isKeyRefused()) throw SceauException.AccessDenied()
            }
        }
        return null
    }

    /** Refus explicite de la clé par la puce : SW 63xx (ICAO 9303-11, BSI TR-03110). */
    private fun Exception.isKeyRefused(): Boolean = statusWord()?.let { it and SW1_MASK == SW1_AUTHENTICATION_FAILED } ?: false

    private fun doBac(mrz: AccessKey.Mrz) {
        try {
            service.doBAC(bacKey(mrz))
        } catch (e: CardServiceException) {
            chip.rethrowTransportFailure()
            throw SceauException.AccessDenied()
        }
    }

    private fun selectApplet(secure: Boolean) {
        try {
            service.sendSelectApplet(secure)
        } catch (e: CardServiceException) {
            chip.rethrowTransportFailure()
            if (e.statusWord() == SW_FILE_NOT_FOUND) throw SceauException.NotIcaoDocument()
            throw StepFailure("SELECT_APPLET", e)
        }
    }

    companion object {
        private const val SW_FILE_NOT_FOUND = 0x6A82
        private const val SW1_MASK = 0xFF00
        private const val SW1_AUTHENTICATION_FAILED = 0x6300

        /**
         * Délai de réponse pendant l'authentification (PACE, BAC) : assez long pour qu'une puce
         * qui fait patienter après des essais ratés finisse par répondre.
         */
        const val AUTHENTICATION_TIMEOUT_MILLIS = 60_000

        fun bacKey(mrz: AccessKey.Mrz): BACKey = BACKey(mrz.documentNumber, yymmdd(mrz.dateOfBirth), yymmdd(mrz.dateOfExpiry))

        private fun yymmdd(date: LocalDate): String =
            String.format(Locale.ROOT, "%02d%02d%02d", date.year % 100, date.monthValue, date.dayOfMonth)
    }
}
