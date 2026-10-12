package io.github.mgdx.sceau.core.fuzz

import io.github.mgdx.sceau.core.reading.FileSizeLimits
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.verify.PassiveAuthResult
import io.github.mgdx.sceau.core.verify.PassiveAuthentication
import io.github.mgdx.sceau.core.verify.Tlv
import io.github.mgdx.sceau.testchip.TestSod
import org.bouncycastle.cms.CMSSignedData
import org.jmrtd.PassportService
import org.junit.Test
import java.security.MessageDigest

/**
 * Fuzzing of Passive Authentication. Properties: `verify` never throws (its contract: every
 * failure is a FAILED line), a mutation of the signed content never yields SOD_SIGNATURE OK,
 * and a mutated DG never yields DG_HASHES OK.
 */
class PassiveAuthenticationFuzzTest {
    private val seeds = FuzzSeeds.documents
    private val maxSod = FileSizeLimits.maxSize(PassportService.EF_SOD).toInt()

    private fun verify(
        index: Int,
        sod: ByteArray,
        dataGroups: Map<Int, ByteArray> = seeds[index].document.dataGroups,
    ): PassiveAuthResult {
        val document = seeds[index].document
        return PassiveAuthentication.verify(
            sod = sod,
            dataGroups = dataGroups,
            trustStore = seeds[index].trustStore,
            dateOfIssue = document.dateOfIssue,
            dateOfExpiry = document.dateOfExpiry,
            documentCode = document.documentCode,
        )
    }

    private fun PassiveAuthResult.allOk() =
        listOf(sodSignature, certificateChain, dsValidity, dataGroupHashes).all { it.status == CheckStatus.OK }

    /** EF.SOD mutated as is: the signature only covers the eContent and the signed attributes. */
    @Test
    fun mutatedSod() =
        FuzzCampaign(
            "PA-SOD",
            seeds.map { it.sod },
            maxSod,
            FuzzConfig.iterations(SOD_ITERATIONS),
            precondition = withinSizeLimit(PassportService.EF_SOD),
        ).run { input ->
            val result = verify(input.seedIndex, input.bytes)
            if (input.pristine) {
                property(result.allOk()) { "seed ${seeds[input.seedIndex].name}: PA not valid" }
            } else if (result.sodSignature.status == CheckStatus.OK) {
                property(signedPartsUnchanged(seeds[input.seedIndex].sod, input.bytes)) {
                    "SOD_SIGNATURE OK although the signed content or the signed attributes changed"
                }
            }
        }

    /**
     * LDSSecurityObject mutated then signed by the DS: the signed content parser facing a
     * hostile issuer (or a compromised DS key). DG_HASHES OK requires the hash of every DG to
     * appear in the signed content.
     */
    @Test
    fun resignedSecurityObject() =
        FuzzCampaign(
            "PA-LDSSecurityObject",
            seeds.map { it.securityObject },
            maxSod,
            FuzzConfig.iterations(RESIGNED_ITERATIONS),
        ).run { input ->
            val seed = seeds[input.seedIndex]
            val sod = seed.sod(input.bytes)
            val result = verify(input.seedIndex, sod)
            if (input.pristine) property(result.allOk()) { "re-signed seed ${seed.name}: PA not valid" }
            if (result.dataGroupHashes.status == CheckStatus.OK) {
                val digests = DIGESTS.map { MessageDigest.getInstance(it) }
                property(
                    seed.document.dataGroups.values
                        .all { dg -> digests.any { input.bytes.contains(it.digest(dg)) } },
                ) { "DG_HASHES OK without the hash of a DG in the signed content" }
            }
        }

    /** Intact SOD, one mutated DG: DG_HASHES never OK. */
    @Test
    fun mutatedDataGroup() {
        val entries =
            seeds.indices.flatMap { index ->
                seeds[index].document.dataGroups.map { (number, bytes) -> Triple(index, number, bytes) }
            }
        FuzzCampaign(
            "PA-DG",
            entries.map { it.third },
            FileSizeLimits.maxSize(PassportService.EF_DG2).toInt(),
            FuzzConfig.iterations(DG_ITERATIONS),
            precondition = withinSizeLimit(PassportService.EF_DG2),
        ).run { input ->
            val (index, number, original) = entries[input.seedIndex]
            val groups = seeds[index].document.dataGroups + (number to input.bytes)
            val result = verify(index, seeds[index].sod, groups)
            if (input.pristine) {
                property(result.allOk()) { "seed ${seeds[index].name}: PA not valid" }
            } else if (!input.bytes.contentEquals(original)) {
                property(result.dataGroupHashes.status != CheckStatus.OK) { "DG_HASHES OK with mutated DG$number" }
            }
        }
    }

    /** True if the eContent and signed attributes of [mutated] are those of [original]. */
    private fun signedPartsUnchanged(
        original: ByteArray,
        mutated: ByteArray,
    ): Boolean {
        fun parts(sod: ByteArray): Pair<ByteArray, ByteArray?>? =
            try {
                // Same extraction as ParsedSod: the length of tag 0x77 is authoritative, the rest is ignored.
                val signed = CMSSignedData(if (sod[0].toInt() == 0x30) sod else Tlv.value(sod, TestSod.SOD_TAG))
                val signer = signed.signerInfos.signers.single()
                (signed.signedContent.content as ByteArray) to signer.encodedSignedAttributes
            } catch (e: Exception) {
                null
            }
        val before = checkNotNull(parts(original))
        val after = parts(mutated) ?: return false
        return before.first.contentEquals(after.first) && before.second.contentEquals(after.second)
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    private companion object {
        const val SOD_ITERATIONS = 1_500
        const val RESIGNED_ITERATIONS = 600
        const val DG_ITERATIONS = 600
        val DIGESTS = listOf("SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512")
    }
}
