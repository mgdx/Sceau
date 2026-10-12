package io.github.mgdx.sceau.core.reading

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Cryptographic provider for the reading.
 *
 * On Android, the system provider named "BC" is a stripped-down copy of BouncyCastle
 * (no brainpool curves, no AES CMAC…): JMRTD needs the full BouncyCastle,
 * in first position so that `getInstance` calls without an explicit provider (ECDH for PACE
 * and Chip Authentication, in particular) pick it.
 */
internal object Crypto {
    private val lock = Any()

    /**
     * Ensures that a full [BouncyCastleProvider] is registered under the name "BC", at
     * position 1. Idempotent and thread-safe: does nothing if that is already the case, otherwise removes
     * the existing "BC" (stripped-down or misplaced) and inserts a full instance at the head.
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
 * JMRTD and SCUBA log through `java.util.logging` (redirected to logcat on Android),
 * sometimes with the hexadecimal content of APDUs, hence personal data. These logs
 * are turned off. The references are kept: `java.util.logging` only holds
 * weak references to its loggers, which would otherwise lose their level.
 */
internal object LibraryLogging {
    private val loggers = listOf("org.jmrtd", "net.sf.scuba").map { Logger.getLogger(it) }

    fun silence() {
        loggers.forEach { it.level = Level.OFF }
    }
}
