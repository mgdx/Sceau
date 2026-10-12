package io.github.mgdx.sceau.core.fuzz

import io.github.mgdx.sceau.core.reading.DataGroupParsers
import io.github.mgdx.sceau.core.reading.DocumentReader
import io.github.mgdx.sceau.core.reading.FileSizeLimits
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.verify.ActiveAuthentication
import org.jmrtd.PassportService
import org.jmrtd.Util
import org.jmrtd.lds.ActiveAuthenticationInfo
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Random

/**
 * Fuzzing of the chip file parsers (DG, EF.COM, EF.SOD, EF.CardAccess) and of the size header
 * analysis. Each property follows the real caller's contract: what it catches is accepted,
 * everything else is a failure.
 */
class LdsParsersFuzzTest {
    private val validDg1 = FuzzSeeds.dataGroup(1).first()

    // --- DG1: exception allowed (DG1 is mandatory), but never an Error ------------------------

    @Test
    fun dg1() =
        campaign("DG1", FuzzSeeds.dataGroup(1), PassportService.EF_DG1, DEFAULT_ITERATIONS).run { input ->
            val dg1 =
                try {
                    DataGroupParsers.parseDg1(input.bytes, FuzzSeeds.TODAY)
                } catch (e: Exception) {
                    // Contract: JMRTD exceptions propagate as is and ReadingSession converts them
                    // to the UNEXPECTED code; only an Error (memory, stack) gets through.
                    if (input.pristine) throw e
                    return@run
                }
            property(dg1.documentNumber.length <= input.bytes.size) { "document number longer than DG1" }
        }

    // --- DG2, DG7, DG11, DG12: never an exception, unreadable DG = absent ---------------------

    @Test
    fun dg2() = optionalDataGroup(2, PassportService.EF_DG2, DG2_ITERATIONS)

    @Test
    fun dg7() = optionalDataGroup(7, PassportService.EF_DG7, DEFAULT_ITERATIONS)

    @Test
    fun dg11() = optionalDataGroup(11, PassportService.EF_DG11, DEFAULT_ITERATIONS)

    @Test
    fun dg12() = optionalDataGroup(12, PassportService.EF_DG12, DEFAULT_ITERATIONS)

    private fun optionalDataGroup(
        number: Int,
        fid: Short,
        iterations: Int,
    ) = campaign("DG$number", FuzzSeeds.dataGroup(number), fid, iterations).run { input ->
        // Direct call, without the OutOfMemoryError safety net of DataGroupParsers.orNull: an Error
        // (oversized allocation) must be avoided at the source, and fails the campaign.
        try {
            when (number) {
                2 -> DataGroupParsers.parseDg2(input.bytes)
                7 -> DataGroupParsers.parseDg7(input.bytes)
                11 -> DataGroupParsers.parseDg11(input.bytes, FuzzSeeds.TODAY)
                else -> DataGroupParsers.parseDg12(input.bytes, FuzzSeeds.TODAY)
            }
        } catch (e: Exception) {
            // Unreadable DG: absent, as in DataGroupParsers.document.
        }
        val document = DataGroupParsers.document(mapOf(1 to validDg1, number to input.bytes), FuzzSeeds.TODAY)
        val size = input.bytes.size
        document.portrait?.let { property(it.bytes.size <= size) { "portrait larger than DG2" } }
        document.signature?.let { property(it.bytes.size <= size) { "signature larger than DG7" } }
        document.dg12?.frontImage?.let { property(it.bytes.size <= size) { "front image larger than DG12" } }
        document.dg12?.rearImage?.let { property(it.bytes.size <= size) { "rear image larger than DG12" } }
        if (input.pristine && number != 2) {
            val parsed =
                when (number) {
                    7 -> document.signature != null
                    11 -> document.dg11 != null
                    else -> document.dg12 != null
                }
            property(parsed) { "DG$number seed unreadable" }
        }
    }

    // --- EF.COM and EF.SOD as seen by DocumentReader: never an exception -------------------

    @Test
    fun comDataGroups() =
        campaign("COM", FuzzSeeds.com, PassportService.EF_COM, DEFAULT_ITERATIONS).run { input ->
            val groups = DocumentReader.parseComDataGroups(input.bytes)
            if (input.pristine) property(1 in groups) { "EF.COM seed unreadable" }
        }

    @Test
    fun sodDataGroups() =
        campaign("SOD-groups", FuzzSeeds.documents.map { it.sod }, PassportService.EF_SOD, SOD_ITERATIONS).run { input ->
            val groups = DocumentReader.parseSodDataGroups(input.bytes)
            if (input.pristine) property(1 in groups) { "EF.SOD seed unreadable" }
        }

    // --- EF.CardAccess, like SecureChannel.readPaceInfos then tryPace ----------------------

    @Test
    fun cardAccess() =
        campaign("CardAccess", FuzzSeeds.cardAccess, PassportService.EF_CARD_ACCESS, DEFAULT_ITERATIONS).run { input ->
            val file =
                try {
                    CardAccessFile(input.bytes.inputStream())
                } catch (e: Exception) {
                    if (input.pristine) throw e
                    null
                }
            val infos = file?.securityInfos.orEmpty().filterIsInstance<PACEInfo>()
            if (input.pristine) property(infos.isNotEmpty()) { "EF.CardAccess seed without PACEInfo" }
            for (info in infos) {
                val parameterId = info.parameterId ?: continue
                info.objectIdentifier
                try {
                    PACEInfo.toParameterSpec(parameterId)
                } catch (e: Exception) {
                    // Proprietary parameters: ignored by tryPace.
                }
            }
        }

    // --- DG14 and DG15, like ChipVerifier, then the AA check with a hostile key -------------

    @Test
    fun dg14() =
        campaign("DG14", FuzzSeeds.dataGroup(14), PassportService.EF_DG14, DEFAULT_ITERATIONS).run { input ->
            val dg14 =
                try {
                    DG14File(input.bytes.inputStream())
                } catch (e: Exception) {
                    if (input.pristine) throw e
                    return@run
                }
            // Accesses that ChipVerifier.chipAuthentication makes outside any try.
            val infos = dg14.securityInfos.orEmpty()
            val publicKeyInfo = infos.filterIsInstance<ChipAuthenticationPublicKeyInfo>().firstOrNull()
            if (publicKeyInfo != null) {
                val keyId = publicKeyInfo.keyId
                val caInfos = infos.filterIsInstance<ChipAuthenticationInfo>()
                val caInfo = caInfos.firstOrNull { keyId != null && it.keyId == keyId } ?: caInfos.firstOrNull()
                caInfo?.objectIdentifier
            }
            // ChipVerifier.aaSignatureAlgorithm: only lookupMnemonicByOID is guarded, by GeneralSecurityException.
            val oid = infos.filterIsInstance<ActiveAuthenticationInfo>().firstOrNull()?.signatureAlgorithmOID ?: return@run
            val mnemonic =
                try {
                    ActiveAuthenticationInfo.lookupMnemonicByOID(oid)
                } catch (e: GeneralSecurityException) {
                    null
                }
            mnemonic?.let(Util::inferDigestAlgorithmFromSignatureAlgorithm)
        }

    @Test
    fun dg15() {
        val originals = FuzzSeeds.documents.mapNotNull { seed -> seed.document.aaKeyPair?.let { seed to it } }
        val challenge = ByteArray(AA_CHALLENGE).also { Random(1).nextBytes(it) }
        val responses = originals.map { (seed, _) -> checkNotNull(seed.aaResponse(challenge)) }
        val seeds = originals.map { (seed, _) -> checkNotNull(seed.document.dataGroups[15]) }
        campaign("DG15", seeds, PassportService.EF_DG15, DEFAULT_ITERATIONS)
            .run { input ->
                val key =
                    try {
                        DG15File(input.bytes.inputStream()).publicKey
                    } catch (e: Exception) {
                        if (input.pristine) throw e
                        null
                    } ?: return@run
                val (seed, keyPair) = originals[input.seedIndex]
                val check = ActiveAuthentication.verifyResponse(key, seed.document.aaDigestAlgorithm, challenge, responses[input.seedIndex])
                if (input.pristine) {
                    property(check.status == CheckStatus.OK) { "DG15 seed: AA ${check.status}" }
                } else if (check.status == CheckStatus.OK) {
                    property(sameKey(key, keyPair.public)) { "AA valid with a key other than the DG15 key" }
                }
            }
    }

    /** Same mathematical key (a different encoding of the same key is still the same key). */
    private fun sameKey(
        a: PublicKey,
        b: PublicKey,
    ): Boolean =
        when {
            a is RSAPublicKey && b is RSAPublicKey -> {
                a.modulus == b.modulus && a.publicExponent == b.publicExponent
            }

            a is ECPublicKey && b is ECPublicKey -> {
                a.w == b.w &&
                    a.params.curve == b.params.curve &&
                    a.params.generator == b.params.generator &&
                    a.params.order == b.params.order
            }

            else -> {
                a.encoded.contentEquals(b.encoded)
            }
        }

    // --- Size announced by a file header -----------------------------------------------------

    @Test
    fun announcedSize() {
        val headers =
            (FuzzSeeds.documents.flatMap { it.document.dataGroups.values + it.sod } + FuzzSeeds.com + FuzzSeeds.cardAccess)
                .map { it.copyOf(minOf(it.size, HEADER_BYTES)) }
        campaign("FileSizeLimits", headers, HEADER_BYTES * 2, HEADER_ITERATIONS).run { input ->
            val size = FileSizeLimits.announcedSize(input.bytes)
            if (input.pristine) property(size != null) { "seed header not parsed" }
            size?.let { property(it > 0) { "announced size negative or zero" } }
            val fid =
                (input.bytes.firstOrNull()?.toInt() ?: 0) shl 8 or (
                    input.bytes
                        .getOrNull(1)
                        ?.toInt()
                        ?.and(0xFF) ?: 0
                )
            property(FileSizeLimits.maxSize(fid.toShort()) > 0) { "zero cap" }
            FileSizeLimits.nameOf(fid.toShort())
        }
    }

    private fun campaign(
        target: String,
        seeds: List<ByteArray>,
        fid: Short,
        iterations: Int,
    ) = FuzzCampaign(
        target,
        seeds,
        FileSizeLimits.maxSize(fid).toInt(),
        FuzzConfig.iterations(iterations),
        precondition = withinSizeLimit(fid),
    )

    private fun campaign(
        target: String,
        seeds: List<ByteArray>,
        maxSize: Int,
        iterations: Int,
    ) = FuzzCampaign(target, seeds, maxSize, FuzzConfig.iterations(iterations))

    private companion object {
        const val DEFAULT_ITERATIONS = 3_000
        const val DG2_ITERATIONS = 1_500
        const val SOD_ITERATIONS = 1_500
        const val HEADER_ITERATIONS = 20_000
        const val HEADER_BYTES = 8
        const val AA_CHALLENGE = 8
    }
}
