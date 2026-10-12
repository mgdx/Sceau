package io.github.mgdx.sceau.core.fuzz

import io.github.mgdx.sceau.core.trust.InvalidMasterListException
import io.github.mgdx.sceau.core.trust.MasterList
import io.github.mgdx.sceau.core.trust.MasterListParser
import io.github.mgdx.sceau.core.trust.TrustStoreLoader
import io.github.mgdx.sceau.core.trust.TrustStores
import org.junit.Test

/**
 * Fuzzing of the Master List parser. Contract: [TrustStores.parseMasterList] returns a list or
 * throws [InvalidMasterListException], the only exception that `TrustStoreLoader.load` catches
 * for an import; any other would make loading the whole trust store fail. An accepted list
 * must be mergeable and displayable (country, fingerprint, self-signature, index).
 */
class MasterListFuzzTest {
    private val fixture = FuzzSeeds.testMasterList

    private fun parseOrNull(bytes: ByteArray): MasterList? =
        try {
            TrustStores.parseMasterList(bytes)
        } catch (e: InvalidMasterListException) {
            null
        }

    /** Merge into a trust store and access everything the Trust store screen displays. */
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

    /** Test Master List mutated as is: an accepted list has the original signed content. */
    @Test
    fun mutatedTestMasterList() {
        val original = certificateSet(TrustStores.parseMasterList(fixture.bytes))
        val iterations = FuzzConfig.iterations(TEST_ML_ITERATIONS)
        FuzzCampaign("ML-test", listOf(fixture.bytes), fixture.bytes.size + GROWTH, iterations).run { input ->
            val list = parseOrNull(input.bytes)
            if (input.pristine) property(list != null) { "Master List seed rejected" }
            list ?: return@run
            property(certificateSet(list) == original) { "mutated Master List accepted with other certificates" }
            exercise(list)
        }
    }

    /**
     * `CscaMasterList` content mutated then signed: an import is not anchored, so anyone can sign
     * a hostile list. Unreadable certificates are skipped, not fatal.
     */
    @Test
    fun resignedMasterListContent() {
        val content = fixture.content
        FuzzCampaign("ML-content", listOf(content), content.size + GROWTH, FuzzConfig.iterations(RESIGNED_ITERATIONS)).run { input ->
            val bytes = fixture.sign(input.bytes)
            val list = parseOrNull(bytes)
            if (input.pristine) property(list?.certificates?.size == 2) { "re-signed Master List seed rejected" }
            list?.let(::exercise)
        }
    }

    /** Embedded (BSI) Master List mutated: slow (880 KB), few iterations by default. */
    @Test
    fun mutatedEmbeddedMasterList() {
        val bytes = FuzzSeeds.embeddedMasterList
        // Taken from the intact seed, which the campaign parses first (one parse takes ~2 s).
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
                property(parsed != null) { "embedded Master List rejected" }
                original = certificateSet(checkNotNull(parsed).masterList)
            }
            parsed ?: return@run
            val reference = original ?: certificateSet(TrustStores.parseMasterList(bytes)).also { original = it }
            property(certificateSet(parsed.masterList) == reference) { "mutated embedded Master List accepted with other certificates" }
            try {
                MasterListParser.requireAnchoredSigner(parsed, TrustStoreLoader.EMBEDDED_MASTER_LIST_ANCHORS)
            } catch (e: InvalidMasterListException) {
                property(!input.pristine) { "embedded Master List not anchored" }
            }
        }
    }

    private companion object {
        const val GROWTH = 64 * 1024
        const val TEST_ML_ITERATIONS = 1_500
        const val RESIGNED_ITERATIONS = 1_000
        const val EMBEDDED_ITERATIONS = 20

        /** One iteration on the embedded list costs about a hundred ordinary iterations. */
        const val EMBEDDED_SCALE = 100
        const val EMBEDDED_TIMEOUT_MILLIS = 20_000L
    }
}
