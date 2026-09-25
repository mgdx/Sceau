package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.trust.TrustSource
import io.github.mgdx.sceau.testchip.TestCredential
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import org.bouncycastle.asn1.x509.KeyUsage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Recherche de chaîne face à un magasin hostile (audit V15) : certificats croisés partageant
 * leurs AKI, sans CSCA auto-signé. Sans ensemble global ni budget, la recherche coûtait
 * jusqu'à k^8 vérifications de signature.
 */
class ChainSearchBudgetTest {
    private val pki = TestPki(TestKeyType.RSA, seed = 70_707L)

    private fun ca(
        issuer: TestCredential,
        name: String,
        subject: TestCredential? = null,
    ) = pki.issueCustom(issuer, name, basicConstraintsCa = true, keyUsage = KeyUsage.keyCertSign, keyPair = subject?.keyPair)

    private fun verify(
        ds: TestCredential,
        store: List<TestCredential>,
    ): PassiveAuthResult {
        val document = pki.document { this.ds = ds }
        return PassiveAuthentication.verify(
            sod = document.sod,
            dataGroups = document.dataGroups,
            trustStore = pki.trustStore(*store.toTypedArray(), source = TrustSource.IMPORTED_MASTER_LIST),
            dateOfIssue = document.dateOfIssue,
            dateOfExpiry = document.dateOfExpiry,
            documentCode = document.documentCode,
        )
    }

    /**
     * [count] certificats « A » (clé KA) signés par la clé KB et [count] certificats « B » (clé
     * KB) signés par KA : chaque certificat A a pour émetteurs possibles tous les B, et
     * inversement. Le DS est signé par KA.
     */
    private fun crossSigned(count: Int): Pair<TestCredential, List<TestCredential>> {
        val a = ca(pki.oldCsca, "A")
        val b = ca(pki.oldCsca, "B")
        val aCerts = List(count) { ca(b, "A", subject = a) }
        val bCerts = List(count) { ca(a, "B", subject = b) }
        val ds = pki.issueCustom(a, "DS", basicConstraintsCa = null, keyUsage = KeyUsage.digitalSignature)
        return ds to aCerts + bCerts
    }

    @Test(timeout = 20_000)
    fun `certificats croises - budget epuise, chaine en echec`() {
        val (ds, store) = crossSigned(count = 12)

        val result = verify(ds, store)

        assertEquals(CheckStatus.FAILED, result.certificateChain.status)
        assertEquals(CheckDetail.Error("CHAIN_BUDGET"), result.certificateChain.detail)
    }

    @Test(timeout = 20_000)
    fun `quelques certificats croises - emetteur inconnu, sans exploration repetee`() {
        val (ds, store) = crossSigned(count = 3)

        assertEquals(CheckStatus.NOT_AVAILABLE, verify(ds, store).certificateChain.status)
    }

    @Test
    fun `chaine par lien avec le CSCA - toujours trouvee`() {
        val link = ca(pki.oldCsca, "NOUVEAU CSCA")
        val ds = pki.issueCustom(link, "DS", basicConstraintsCa = null, keyUsage = KeyUsage.digitalSignature)

        assertEquals(CheckStatus.OK, verify(ds, listOf(pki.oldCsca, link)).certificateChain.status)
    }
}
