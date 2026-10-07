package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.reading.ChipAuthenticationMapping
import io.github.mgdx.sceau.core.reading.PaceCamEvidence
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.testchip.TestCrypto
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import io.github.mgdx.sceau.testchip.TestSod
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.icao.ICAOObjectIdentifiers
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Audit V40 : ICAO 9303-10 impose des attributs signés (messageDigest, contentType) dans le
 * SignerInfo du SOD ; un SOD signé directement sur son contenu, que BouncyCastle accepte, est mal
 * formé. Même règle pour EF.CardSecurity, vérifié comme le SOD.
 */
class SignedAttributesTest {
    private val pki = TestPki(TestKeyType.EC, seed = 40_404L)

    /** CMS SignedData de [content] signé par le DS, avec ([direct] faux) ou sans attributs signés. */
    private fun signed(
        contentType: ASN1ObjectIdentifier,
        content: ByteArray,
        direct: Boolean,
    ): ByteArray {
        val provider = TestCrypto.provider
        val signer = JcaContentSignerBuilder(pki.ds.keyType.signatureAlgorithm).setProvider(provider).build(pki.ds.privateKey)
        val signerInfo =
            JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().setProvider(provider).build())
                .setDirectSignature(direct)
                .build(signer, pki.ds.holder)
        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(signerInfo)
        generator.addCertificate(pki.ds.holder)
        return generator.generate(CMSProcessableByteArray(contentType, content), true).encoded
    }

    /** SOD du document factice re-signé, avec ou sans attributs signés. */
    private fun verifySod(direct: Boolean): PassiveAuthResult {
        val document = pki.document()
        val original = CMSSignedData(Tlv.value(document.sod, TestSod.SOD_TAG))
        val content = original.signedContent.content as ByteArray
        val sod = TestSod.tlv(TestSod.SOD_TAG, signed(ICAOObjectIdentifiers.id_icao_ldsSecurityObject, content, direct))
        return PassiveAuthentication.verify(
            sod = sod,
            dataGroups = document.dataGroups,
            trustStore = pki.trustStore(pki.oldCsca),
            dateOfIssue = document.dateOfIssue,
            dateOfExpiry = document.dateOfExpiry,
            documentCode = document.documentCode,
        )
    }

    @Test
    fun `SOD avec attributs signes - OK`() {
        val result = verifySod(direct = false)

        assertEquals(CheckStatus.OK, result.sodSignature.status)
        assertEquals(CheckStatus.OK, result.certificateChain.status)
    }

    @Test
    fun `SOD signe sans attributs - mal forme`() {
        val result = verifySod(direct = true)

        assertEquals(CheckStatus.FAILED, result.sodSignature.status)
        assertEquals(CheckDetail.Error("SOD_MALFORMED"), result.sodSignature.detail)
    }

    @Test
    fun `EF CardSecurity signe sans attributs - mal forme`() {
        val content = DERSet(ASN1Integer(1)).encoded
        val cardSecurity = signed(ASN1ObjectIdentifier("0.4.0.127.0.7.3.2.1"), content, direct = true)
        val evidence = PaceCamEvidence(byteArrayOf(1), pki.generateEc(TestPki.CURVE).public, cardSecurity)

        val result = ChipAuthenticationMapping.verify(evidence, pki.trustStore(pki.oldCsca), null, CheckStatus.OK, null)

        assertEquals(CheckStatus.FAILED, result.check.status)
        assertEquals(CheckDetail.Error("VERIFY_CHIP-CAM-CARD_SECURITY_MALFORMED"), result.check.detail)
    }
}
