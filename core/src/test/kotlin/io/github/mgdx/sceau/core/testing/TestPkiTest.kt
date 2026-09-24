package io.github.mgdx.sceau.core.testing

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.jmrtd.lds.ActiveAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG1File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Garde-fous de la fabrique : ce qu'elle produit est bien ce que les tests croient vérifier. */
class TestPkiTest {
    @Test
    fun `EC_EXPLICIT - parametres de courbe explicites dans le certificat`() {
        val pki = TestPki(TestKeyType.EC_EXPLICIT)
        for (credential in listOf(pki.oldCsca, pki.ds)) {
            val parameters = credential.holder.subjectPublicKeyInfo.algorithm.parameters
            assertTrue(parameters is ASN1Sequence)
        }
        val named =
            TestPki(TestKeyType.EC)
                .ds.holder.subjectPublicKeyInfo.algorithm.parameters
        assertTrue(named is ASN1ObjectIdentifier)
    }

    @Test
    fun `certificat de lien - sujet et cle du nouveau CSCA, emetteur l ancien`() {
        val pki = TestPki(TestKeyType.RSA)
        assertEquals(pki.newCsca.holder.subject, pki.link.holder.subject)
        assertEquals(pki.oldCsca.holder.subject, pki.link.holder.issuer)
        assertEquals(pki.newCsca.keyPair.public, pki.link.certificate.publicKey)
        pki.link.certificate.verify(pki.oldCsca.keyPair.public, TestCrypto.provider)
        assertArrayEquals(
            TestTrustStore.subjectKeyId(pki.newCsca.certificate),
            TestTrustStore.subjectKeyId(pki.link.certificate),
        )
    }

    @Test
    fun `document - DG et SOD relisibles par JMRTD`() {
        val pki = TestPki(TestKeyType.EC)
        val document = pki.document { activeAuthentication = AaKeyType.EC }

        val dg1 = DG1File(document.dataGroups.getValue(1).inputStream())
        assertEquals("L898902C3", dg1.mrzInfo.documentNumber)
        val dg14 = DG14File(document.dataGroups.getValue(14).inputStream())
        assertEquals(1, dg14.securityInfos.count { it is ChipAuthenticationPublicKeyInfo })
        assertEquals(1, dg14.securityInfos.count { it is ActiveAuthenticationInfo })
        val sod = SODFile(document.sod.inputStream())
        assertEquals(setOf(1, 2, 14, 15), sod.dataGroupHashes.keys)
        assertEquals(pki.ds.certificate, sod.docSigningCertificate)
    }

    @Test
    fun `options - DS non embarque, signingTime absent`() {
        val pki = TestPki(TestKeyType.RSA)
        val document = pki.document { sod = SodOptions(embedDs = false, signingTime = null) }
        val sod = SODFile(document.sod.inputStream())
        assertEquals(null, sod.docSigningCertificate)
        assertFalse(document.sod.isEmpty())
    }
}
