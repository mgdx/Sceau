package io.github.mgdx.sceau.testchip

import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.ImageInfo
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG7File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Safeguards for the CNIe profile: what the simulated chip announces is really what we think we test. */
class SimulatedDocumentsTest {
    private val card by lazy { SimulatedDocuments.frenchIdCard() }

    @Test
    fun `EF CardAccess - PACEInfo ECDH GM AES-128 on brainpoolP256r1`() {
        val infos = CardAccessFile(checkNotNull(card.pace).cardAccess.inputStream()).securityInfos
        val pace = infos.filterIsInstance<PACEInfo>().single()
        assertEquals(SecurityInfo.ID_PACE_ECDH_GM_AES_CBC_CMAC_128, pace.objectIdentifier)
        assertEquals(PACEInfo.PARAM_ID_ECP_BRAINPOOL_P256_R1, pace.parameterId.toInt())
        assertEquals(2, pace.version)
    }

    @Test
    fun `DG1 - TD1 MRZ of a fake CNIe`() {
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
    fun `portrait - synthetic JPEG 2000 of at most 20 KB`() {
        val portrait = SimulatedDocuments.specimenPortrait()
        assertTrue("${portrait.size} bytes", portrait.size <= 20 * 1024)
        val jp2Signature = byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A)
        assertTrue(portrait.copyOf(jp2Signature.size).contentEquals(jp2Signature))
    }

    @Test
    fun `signature - synthetic JPEG of at most 8 KB, in the CNIe DG7`() {
        val signature = SimulatedDocuments.specimenSignature()
        assertTrue("${signature.size} bytes", signature.size <= 8 * 1024)
        assertTrue(signature.copyOf(3).contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        val dg7 =
            DG7File(
                card.document.dataGroups
                    .getValue(7)
                    .inputStream(),
            )
        val image = dg7.images.single()
        assertEquals(ImageInfo.TYPE_SIGNATURE_OR_MARK, image.type)
        assertTrue(image.imageInputStream.use { it.readBytes() }.contentEquals(signature))
    }

    @Test
    fun `SOD - signed by DS-TEST-FRANCE under CSCA-TEST-FRANCE, hashes of all DGs`() {
        val sod = SODFile(card.document.sod.inputStream())
        assertEquals(setOf(1, 2, 7, 11, 12, 14, 15), sod.dataGroupHashes.keys)
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
    fun `PaceSettings - protocol not simulated is rejected`() {
        PaceSettings("123456", protocolOid = SecurityInfo.ID_PACE_DH_GM_AES_CBC_CMAC_128)
    }
}
