package io.github.mgdx.sceau.core.fuzz

import io.github.mgdx.sceau.core.trust.InvalidMasterListException
import io.github.mgdx.sceau.core.trust.MasterList
import io.github.mgdx.sceau.core.trust.MasterListParser
import io.github.mgdx.sceau.core.trust.TrustStoreLoader
import io.github.mgdx.sceau.core.trust.TrustStores
import org.junit.Test

/**
 * Fuzzing du parseur de Master List. Contrat : [TrustStores.parseMasterList] renvoie une liste
 * ou lève [InvalidMasterListException], seule exception que `TrustStoreLoader.load` rattrape
 * pour un import ; toute autre ferait échouer le chargement du magasin entier. Une liste
 * acceptée doit pouvoir être fusionnée et affichée (pays, empreinte, auto-signature, index).
 */
class MasterListFuzzTest {
    private val fixture = FuzzSeeds.testMasterList

    private fun parseOrNull(bytes: ByteArray): MasterList? =
        try {
            TrustStores.parseMasterList(bytes)
        } catch (e: InvalidMasterListException) {
            null
        }

    /** Fusion dans un magasin et accès à tout ce qu'affiche l'écran Magasin de confiance. */
    private fun exercise(list: MasterList) {
        val store = TrustStoreLoader.merge(emptyList(), emptyList(), null, listOf(list))
        for (anchor in store.anchors) {
            anchor.country
            anchor.subject
            anchor.sha256
            anchor.notBefore
            anchor.notAfter
            anchor.isSelfSigned
            store.findBySubject(anchor.certificate.subjectX500Principal)
        }
        list.info.signerSubject
        list.info.signingTime
    }

    private fun certificateSet(list: MasterList) = list.certificates.map { it.encoded.toList() }.toSet()

    /** Master List de test mutée telle quelle : une liste acceptée a le contenu signé d'origine. */
    @Test
    fun mutatedTestMasterList() {
        val original = certificateSet(TrustStores.parseMasterList(fixture.bytes))
        val iterations = FuzzConfig.iterations(TEST_ML_ITERATIONS)
        FuzzCampaign("ML-test", listOf(fixture.bytes), fixture.bytes.size + GROWTH, iterations).run { input ->
            val list = parseOrNull(input.bytes)
            if (input.pristine) property(list != null) { "graine Master List refusée" }
            list ?: return@run
            property(certificateSet(list) == original) { "Master List mutée acceptée avec d'autres certificats" }
            exercise(list)
        }
    }

    /**
     * Contenu `CscaMasterList` muté puis signé : un import n'est pas ancré, n'importe qui peut
     * donc signer une liste hostile. Les certificats illisibles sont ignorés, pas fatals.
     */
    @Test
    fun resignedMasterListContent() {
        val content = fixture.content
        FuzzCampaign("ML-content", listOf(content), content.size + GROWTH, FuzzConfig.iterations(RESIGNED_ITERATIONS)).run { input ->
            val bytes = fixture.sign(input.bytes)
            val list = parseOrNull(bytes)
            if (input.pristine) property(list?.certificates?.size == 2) { "graine Master List resignée refusée" }
            list?.let(::exercise)
        }
    }

    /** Master List embarquée (BSI) mutée : lente (880 Ko), peu d'itérations par défaut. */
    @Test
    fun mutatedEmbeddedMasterList() {
        val bytes = FuzzSeeds.embeddedMasterList
        // Relevé sur la graine intacte, que la campagne analyse en premier (une analyse coûte ~2 s).
        var original: Set<List<Byte>>? = null
        FuzzCampaign(
            "ML-embedded",
            listOf(bytes),
            bytes.size + GROWTH,
            FuzzConfig.iterations(EMBEDDED_ITERATIONS, scale = EMBEDDED_SCALE),
            timeoutMillis = EMBEDDED_TIMEOUT_MILLIS,
        ).run { input ->
            val parsed =
                try {
                    MasterListParser.parse(input.bytes)
                } catch (e: InvalidMasterListException) {
                    null
                }
            if (input.pristine) {
                property(parsed != null) { "Master List embarquée refusée" }
                original = certificateSet(checkNotNull(parsed).masterList)
            }
            parsed ?: return@run
            val reference = original ?: certificateSet(TrustStores.parseMasterList(bytes)).also { original = it }
            property(certificateSet(parsed.masterList) == reference) { "Master List embarquée mutée acceptée avec d'autres certificats" }
            try {
                MasterListParser.requireAnchoredSigner(parsed, TrustStoreLoader.EMBEDDED_MASTER_LIST_ANCHORS)
            } catch (e: InvalidMasterListException) {
                property(!input.pristine) { "Master List embarquée non ancrée" }
            }
        }
    }

    private companion object {
        const val GROWTH = 64 * 1024
        const val TEST_ML_ITERATIONS = 1_500
        const val RESIGNED_ITERATIONS = 1_000
        const val EMBEDDED_ITERATIONS = 20

        /** Une itération sur la liste embarquée coûte environ cent fois une itération ordinaire. */
        const val EMBEDDED_SCALE = 100
        const val EMBEDDED_TIMEOUT_MILLIS = 20_000L
    }
}
