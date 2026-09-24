package io.github.mgdx.sceau.core.reading

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Fournisseur cryptographique de la lecture.
 *
 * Sur Android, le fournisseur système nommé "BC" est une copie tronquée de BouncyCastle
 * (pas de courbes brainpool, pas de CMAC AES…) : JMRTD a besoin du BouncyCastle complet,
 * en première position pour que les `getInstance` sans fournisseur explicite (ECDH de PACE
 * et de la Chip Authentication, notamment) le choisissent.
 */
internal object Crypto {
    private val lock = Any()

    /**
     * Garantit qu'un [BouncyCastleProvider] complet est enregistré sous le nom "BC", en
     * position 1. Idempotent et thread-safe : ne fait rien si c'est déjà le cas, sinon retire
     * le "BC" existant (tronqué ou mal placé) et insère une instance complète en tête.
     */
    fun ensureInstalled() {
        synchronized(lock) {
            val providers = Security.getProviders()
            if (providers.isNotEmpty() && providers[0] is BouncyCastleProvider) return
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
}

/**
 * JMRTD et SCUBA journalisent via `java.util.logging` (redirigé vers logcat sur Android),
 * parfois avec le contenu hexadécimal des APDU, donc des données personnelles. Ces journaux
 * sont coupés. Les références sont conservées : `java.util.logging` ne garde que des
 * références faibles vers ses loggers, qui perdraient sinon leur niveau.
 */
internal object LibraryLogging {
    private val loggers = listOf("org.jmrtd", "net.sf.scuba").map { Logger.getLogger(it) }

    fun silence() {
        loggers.forEach { it.level = Level.OFF }
    }
}
