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
 *    (SW 63xx), c'est-à-dire délai, perte de liaison, SW inattendu ou erreur de JMRTD, la liaison
 *    est réinitialisée ([CardTransport.reconnect]) puis BAC est mené (sélection en clair de
 *    l'applet puis BAC) : les documents qui annoncent PACE restent tenus d'accepter BAC jusqu'à
 *    la fin de la transition ICAO, et certaines puces ou certains lecteurs NFC ne mènent pas
 *    PACE à terme (passeport français en ISO 14443-B resté muet au premier GENERAL
 *    AUTHENTICATE). Le délai de réponse est abaissé à [PACE_TIMEOUT_MILLIS] pendant PACE pour
 *    ne pas faire attendre l'utilisateur avant le repli.
 *    Avec un CAN, aucun repli possible : l'erreur du transport ressort telle quelle, un refus ou
 *    un échec de PACE donne [SceauException.AccessDenied].
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

    /** Étape SECURE_CHANNEL. Renvoie le protocole établi. */
    fun establish(paceInfos: List<PACEInfo>): ChannelProtocol {
        if (paceInfos.isNotEmpty()) {
            if (tryPace(paceInfos)) {
                selectApplet(secure = true)
                return ChannelProtocol.PACE
            }
            if (key is AccessKey.Can) throw SceauException.AccessDenied()
            // PACE a pu laisser la puce au milieu du protocole, voire muette : on repart de zéro.
            chip.reconnect()
            selectApplet(secure = false)
        } else if (key is AccessKey.Can) {
            throw SceauException.CanWithoutPace()
        }
        doBac(key as AccessKey.Mrz)
        return ChannelProtocol.BAC
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
     * Vrai si PACE a abouti avec l'un des `PACEInfo` annoncés, faux s'il a échoué sans refus de
     * la clé. Lève [SceauException.AccessDenied] si la puce refuse la clé (SW 63xx) ; avec un
     * CAN, relance aussi l'erreur du transport. Avec une clé MRZ, une erreur du transport
     * arrête les essais (la liaison est à réinitialiser) et renvoie faux.
     */
    private fun tryPace(paceInfos: List<PACEInfo>): Boolean {
        val paceKey =
            when (key) {
                is AccessKey.Can -> PACEKeySpec.createCANKey(key.value)
                is AccessKey.Mrz -> PACEKeySpec.createMRZKey(bacKey(key))
            }
        val transport = chip.transport
        val savedTimeout = transport.timeoutMillis
        // Sans repli possible (CAN), aucune raison d'écourter l'attente.
        if (key is AccessKey.Mrz) transport.timeoutMillis = minOf(savedTimeout, PACE_TIMEOUT_MILLIS)
        try {
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
                    service.doPACE(paceKey, info.objectIdentifier, parameters, parameterId)
                    return true
                } catch (e: Exception) {
                    if (chip.cardService.transportFailure != null) {
                        if (key is AccessKey.Can) chip.rethrowTransportFailure()
                        return false
                    }
                    if (e.isKeyRefused()) throw SceauException.AccessDenied()
                }
            }
            return false
        } finally {
            transport.timeoutMillis = savedTimeout
        }
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

        /** Délai de réponse pendant PACE avec une clé MRZ, avant le repli sur BAC. */
        const val PACE_TIMEOUT_MILLIS = 5_000

        fun bacKey(mrz: AccessKey.Mrz): BACKey = BACKey(mrz.documentNumber, yymmdd(mrz.dateOfBirth), yymmdd(mrz.dateOfExpiry))

        private fun yymmdd(date: LocalDate): String =
            String.format(Locale.ROOT, "%02d%02d%02d", date.year % 100, date.monthValue, date.dayOfMonth)
    }
}
