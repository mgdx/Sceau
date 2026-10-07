package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.respond
import io.github.mgdx.sceau.core.reading.TestFixtures.AID_SELECT
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckStatus
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.BERTags
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Audit V30 : une clé de Chip Authentication géante dans DG14 est refusée avant l'accord de clé
 * (`UNSUPPORTED_ALGORITHM`), sans calcul ni commande envoyée à la puce.
 */
class ChipAuthenticationKeyBoundsTest {
    private val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(30_305L) }

    @Test(timeout = 10_000)
    fun dg14AvecUneCleDhDe9000Bits_AlgorithmeNonPrisEnCharge() {
        val p = BigInteger(9000, random).setBit(8999).setBit(0)
        val y = BigInteger(8000, random)
        val key =
            SubjectPublicKeyInfo(
                AlgorithmIdentifier(PKCSObjectIdentifiers.dhKeyAgreement, DERSequence(arrayOf(ASN1Integer(p), ASN1Integer(2)))),
                ASN1Integer(y),
            )
        val keyInfo = DERSequence(arrayOf<ASN1Encodable>(ASN1ObjectIdentifier(ID_PK_DH), key, ASN1Integer(1)))
        val dg14 = DERTaggedObject(true, BERTags.APPLICATION, DG14_TAG_NUMBER, DERSet(keyInfo)).encoded
        val transport = ScriptedTransport(respond(AID_SELECT, "9000"))
        Crypto.ensureInstalled()
        val chip = Chip(transport)
        chip.service.open()
        chip.service.sendSelectApplet(false)

        val check = ChipVerifier(chip, random).chipAuthentication(dg14, signedInSod = true).check

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, check.status)
        assertEquals(CheckDetail.UnsupportedAlgorithm("DH-9000"), check.detail)
        assertEquals("aucune commande de CA envoyée", 1, transport.sent.size)
    }

    private companion object {
        const val ID_PK_DH = "0.4.0.127.0.7.2.2.1.1"
        const val DG14_TAG_NUMBER = 14
    }
}
