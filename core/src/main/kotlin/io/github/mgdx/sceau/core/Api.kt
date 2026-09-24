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

    /** Le document a été retiré (TagLostException ou équivalent). */
    class ConnectionLost(
        cause: Throwable? = null,
    ) : SceauException("CONNECTION_LOST", cause)

    /** Délai dépassé. */
    class Timeout(
        cause: Throwable? = null,
    ) : SceauException("TIMEOUT", cause)

    /** Erreur inattendue. [code] = "UNEXPECTED-" + court identifiant technique (classe d'exception, SW). */
    class Unexpected(
        detail: String,
        cause: Throwable? = null,
    ) : SceauException("UNEXPECTED-$detail", cause)
}

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
