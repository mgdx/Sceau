package io.github.mgdx.sceau.core

import io.github.mgdx.sceau.core.reading.ReadingSession
import io.github.mgdx.sceau.core.report.VerificationReport
import io.github.mgdx.sceau.core.trust.TrustStore
import java.time.LocalDate

/**
 * Canal APDU brut vers la puce. Implémenté dans `:app` au-dessus d'`IsoDep`,
 * et dans les tests par un transport simulé.
 */
interface CardTransport {
    /** Taille maximale d'une APDU acceptée par le lien (IsoDep.maxTransceiveLength). */
    val maxTransceiveLength: Int

    /** Délai d'attente d'une réponse, en millisecondes. */
    var timeoutMillis: Int

    /**
     * Envoie une APDU de commande et renvoie la réponse complète (données + SW1 SW2).
     * Lève [SceauException.ConnectionLost] si le document a été retiré.
     */
    fun transceive(apdu: ByteArray): ByteArray

    /**
     * Réinitialise la liaison avec le document resté sur le lecteur (coupure puis reprise de la
     * session ISO 14443) : la puce repart de zéro, sans canal sécurisé ni application
     * sélectionnée. Sert au repli de PACE sur BAC. Lève [SceauException.ConnectionLost] si le
     * document n'est plus là ou si le transport a été fermé. Par défaut, ne fait rien.
     */
    fun reconnect() {}

    fun close()
}

/**
 * Clé d'accès à la puce. N'a pas de `toString` révélateur : aucune donnée
 * personnelle ne doit apparaître dans un log ou une exception.
 */
sealed class AccessKey {
    /** CAN : 6 chiffres imprimés sur le document. */
    class Can(
        val value: String,
    ) : AccessKey() {
        override fun toString(): String = "AccessKey.Can(***)"
    }

    /** Clé MRZ : numéro de document (sans les `<` de remplissage), date de naissance, date d'expiration. */
    class Mrz(
        val documentNumber: String,
        val dateOfBirth: LocalDate,
        val dateOfExpiry: LocalDate,
    ) : AccessKey() {
        override fun toString(): String = "AccessKey.Mrz(***)"
    }
}

/**
 * Étapes affichées sur l'écran de lecture, dans l'ordre. `progress(step)` est appelé
 * au **début** de chaque étape : toutes les étapes précédentes sont alors terminées.
 * Le retour de [readAndVerify] marque la fin de la dernière.
 */
enum class Step {
    CONNECT,
    SECURE_CHANNEL,
    READ_DATA,
    VERIFY_SIGNATURE,
    VERIFY_CHIP,
}

/**
 * Erreurs qui interrompent la lecture. Les messages ne contiennent jamais de donnée
 * personnelle ; [code] est un identifiant technique stable, affichable à l'utilisateur.
 */
sealed class SceauException(
    val code: String,
    cause: Throwable? = null,
) : Exception(code, cause) {
    /** Pas d'application ICAO (AID A0 00 00 02 47 10 01) sur la puce. */
    class NotIcaoDocument : SceauException("NOT_ICAO")

    /** Échec de PACE ou BAC : CAN ou MRZ incorrects. */
    class AccessDenied(
        cause: Throwable? = null,
    ) : SceauException("ACCESS_DENIED", cause)

    /** Un CAN a été fourni mais EF.CardAccess n'annonce pas PACE. */
    class CanWithoutPace : SceauException("CAN_WITHOUT_PACE")

    /**
     * Le document a été retiré (TagLostException ou équivalent). [detail], facultatif, est un
     * suffixe de diagnostic sans donnée personnelle (étape, INS et longueur de l'APDU) :
     * [code] vaut alors `CONNECTION_LOST-<detail>`.
     */
    class ConnectionLost(
        cause: Throwable? = null,
        val detail: String? = null,
    ) : SceauException(withDetail("CONNECTION_LOST", detail), cause)

    /** Délai dépassé. [code] vaut `TIMEOUT-<detail>` si [detail] est donné (voir [ConnectionLost]). */
    class Timeout(
        cause: Throwable? = null,
        val detail: String? = null,
    ) : SceauException(withDetail("TIMEOUT", detail), cause)

    /**
     * Erreur inattendue. [code] = "UNEXPECTED-" + [detail], court identifiant technique (étape,
     * classe d'exception, SW, ou code d'E/S du transport avec INS et longueur de l'APDU).
     */
    class Unexpected(
        val detail: String,
        cause: Throwable? = null,
    ) : SceauException("UNEXPECTED-$detail", cause)
}

private fun withDetail(
    code: String,
    detail: String?,
): String = if (detail == null) code else "$code-$detail"

/**
 * Point d'entrée unique de `:core` (SPEC §6). Bloquant côté E/S : s'exécute sur
 * `Dispatchers.IO`. Lève [SceauException] pour les erreurs qui interrompent la lecture ;
 * tout échec de vérification est au contraire consigné dans le rapport.
 */
suspend fun readAndVerify(
    transport: CardTransport,
    key: AccessKey,
    trustStore: TrustStore,
    progress: (Step) -> Unit,
): VerificationReport = ReadingSession(transport, key, trustStore, progress).run()
