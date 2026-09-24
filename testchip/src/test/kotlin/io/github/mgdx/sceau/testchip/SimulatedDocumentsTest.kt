package io.github.mgdx.sceau.testchip

import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.icao.DG1File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Garde-fous du profil CNIe : ce que la puce simulée annonce est bien ce qu'on croit tester. */
class SimulatedDocumentsTest {
    private val card by lazy { SimulatedDocuments.frenchIdCard() }

    @Test
    fun `EF CardAccess - PACEInfo ECDH GM AES-128 sur brainpoolP256r1`() {
        val infos = CardAccessFile(checkNotNull(card.pace).cardAccess.inputStream()).securityInfos
        val pace = infos.filterIsInstance<PACEInfo>().single()
        assertEquals(SecurityInfo.ID_PACE_ECDH_GM_AES_CBC_CMAC_128, pace.objectIdentifier)
        assertEquals(PACEInfo.PARAM_ID_ECP_BRAINPOOL_P256_R1, pace.parameterId.toInt())
        assertEquals(2, pace.version)
    }

    @Test
    fun `DG1 - MRZ TD1 d une CNIe factice`() {
        val mrz =
            DG1File(
                card.document.dataGroups
                    .getValue(1)
                    .inputStream(),
            ).mrzInfo
        assertEquals("ID", mrz.documentCode)
        assertEquals("FRA", mrz.issuingState)
        assertEquals("SPECIMEN", mrz.primaryIdentifier)
        assertEquals(SimulatedDocuments.SPECIMEN_DOCUMENT_NUMBER, mrz.documentNumber)
    }

    @Test
    fun `portrait - JPEG 2000 synthetique de 20 Ko au plus`() {
        val portrait = SimulatedDocuments.specimenPortrait()
        assertTrue("${portrait.size} octets", portrait.size <= 20 * 1024)
        val jp2Signature = byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A)
        assertTrue(portrait.copyOf(jp2Signature.size).contentEquals(jp2Signature))
    }

    @Test
    fun `SOD - signe par DS-TEST-FRANCE sous CSCA-TEST-FRANCE, empreintes de tous les DG`() {
        val sod = SODFile(card.document.sod.inputStream())
        assertEquals(setOf(1, 2, 11, 12, 14, 15), sod.dataGroupHashes.keys)
        assertTrue(
            sod.docSigningCertificate.subjectX500Principal.name
                .contains("CN=DS-TEST-FRANCE"),
        )
        assertTrue(
            card.pki.oldCsca.certificate.subjectX500Principal.name
                .contains("CN=CSCA-TEST-FRANCE"),
        )
        sod.docSigningCertificate.verify(card.pki.oldCsca.keyPair.public, TestCrypto.provider)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `PaceSettings - protocole non simule refuse`() {
        PaceSettings("123456", protocolOid = SecurityInfo.ID_PACE_DH_GM_AES_CBC_CMAC_128)
    }
}
