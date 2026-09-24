package io.github.mgdx.sceau.core.reading

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Provider
import java.security.Security

class CryptoTest {
    @Test
    fun remplaceUnFournisseurBcTronque_EtResteIdempotent() {
        // Simule le "BC" tronqué d'Android : un fournisseur de ce nom, en fin de liste.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(object : Provider("BC", "0", "tronqué") {})

        Crypto.ensureInstalled()
        val installed = Security.getProviders()[0]
        assertTrue(installed is BouncyCastleProvider)
        assertSame(installed, Security.getProvider("BC"))

        Crypto.ensureInstalled()
        assertSame(installed, Security.getProviders()[0])
        assertEquals(1, Security.getProviders().count { it.name == "BC" })
    }
}
