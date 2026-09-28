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
 * Fuzzing des parseurs de fichiers de la puce (DG, EF.COM, EF.SOD, EF.CardAccess) et de
 * l'analyse des en-têtes de taille. Chaque propriété reprend le contrat de l'appelant réel :
 * ce qu'il rattrape est accepté, tout le reste est un échec.
 */
class LdsParsersFuzzTest {
    private val validDg1 = FuzzSeeds.dataGroup(1).first()

    // --- DG1 : exception admise (DG1 est indispensable), mais jamais une Error ------------------

    @Test
    fun dg1() =
        campaign("DG1", FuzzSeeds.dataGroup(1), PassportService.EF_DG1, DEFAULT_ITERATIONS).run { input ->
            val dg1 =
                try {
                    DataGroupParsers.parseDg1(input.bytes, FuzzSeeds.TODAY)
                } catch (e: Exception) {
                    // Contrat : les exceptions de JMRTD remontent telles quelles et ReadingSession
                    // les convertit en code UNEXPECTED ; seule une Error (mémoire, pile) échappe.
                    if (input.pristine) throw e
                    return@run
                }
            property(dg1.documentNumber.length <= input.bytes.size) { "numéro de document plus long que DG1" }
        }

    // --- DG2, DG11, DG12 : jamais d'exception, DG illisible = absent ---------------------------

    @Test
    fun dg2() = optionalDataGroup(2, PassportService.EF_DG2, DG2_ITERATIONS)

    @Test
    fun dg11() = optionalDataGroup(11, PassportService.EF_DG11, DEFAULT_ITERATIONS)

    @Test
    fun dg12() = optionalDataGroup(12, PassportService.EF_DG12, DEFAULT_ITERATIONS)

    private fun optionalDataGroup(
        number: Int,
        fid: Short,
        iterations: Int,
    ) = campaign("DG$number", FuzzSeeds.dataGroup(number), fid, iterations).run { input ->
        val document = DataGroupParsers.document(mapOf(1 to validDg1, number to input.bytes), FuzzSeeds.TODAY)
        val size = input.bytes.size
        document.portrait?.let { property(it.bytes.size <= size) { "portrait plus grand que DG2" } }
        document.dg12?.frontImage?.let { property(it.bytes.size <= size) { "image recto plus grande que DG12" } }
        document.dg12?.rearImage?.let { property(it.bytes.size <= size) { "image verso plus grande que DG12" } }
        if (input.pristine && number != 2) {
            property(if (number == 11) document.dg11 != null else document.dg12 != null) { "graine DG$number illisible" }
        }
    }

    // --- EF.COM et EF.SOD vus par DocumentReader : jamais d'exception ---------------------

    @Test
    fun comDataGroups() =
        campaign("COM", FuzzSeeds.com, PassportService.EF_COM, DEFAULT_ITERATIONS).run { input ->
            val groups = DocumentReader.parseComDataGroups(input.bytes)
            if (input.pristine) property(1 in groups) { "graine EF.COM illisible" }
        }

    @Test
    fun sodDataGroups() =
        campaign("SOD-groups", FuzzSeeds.documents.map { it.sod }, PassportService.EF_SOD, SOD_ITERATIONS).run { input ->
            val groups = DocumentReader.parseSodDataGroups(input.bytes)
            if (input.pristine) property(1 in groups) { "graine EF.SOD illisible" }
        }

    // --- EF.CardAccess, comme SecureChannel.readPaceInfos puis tryPace ---------------------

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
            if (input.pristine) property(infos.isNotEmpty()) { "graine EF.CardAccess sans PACEInfo" }
            for (info in infos) {
                val parameterId = info.parameterId ?: continue
                info.objectIdentifier
                try {
                    PACEInfo.toParameterSpec(parameterId)
                } catch (e: Exception) {
                    // Paramètres propriétaires : ignorés par tryPace.
                }
            }
        }

    // --- DG14 et DG15, comme ChipVerifier, puis la vérification AA avec une clé hostile -----

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
            // Accès que ChipVerifier.chipAuthentication fait hors de tout try.
            val infos = dg14.securityInfos.orEmpty()
            val publicKeyInfo = infos.filterIsInstance<ChipAuthenticationPublicKeyInfo>().firstOrNull()
            if (publicKeyInfo != null) {
                val keyId = publicKeyInfo.keyId
                val caInfos = infos.filterIsInstance<ChipAuthenticationInfo>()
                val caInfo = caInfos.firstOrNull { keyId != null && it.keyId == keyId } ?: caInfos.firstOrNull()
                caInfo?.objectIdentifier
            }
            // ChipVerifier.aaSignatureAlgorithm : seul lookupMnemonicByOID est protégé, par GeneralSecurityException.
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
                    property(check.status == CheckStatus.OK) { "graine DG15 : AA ${check.status}" }
                } else if (check.status == CheckStatus.OK) {
                    property(sameKey(key, keyPair.public)) { "AA valide avec une autre clé que celle de DG15" }
                }
            }
    }

    /** Même clé mathématique (un encodage différent de la même clé reste la même clé). */
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

    // --- Taille annoncée par l'en-tête d'un fichier -----------------------------------------

    @Test
    fun announcedSize() {
        val headers =
            (FuzzSeeds.documents.flatMap { it.document.dataGroups.values + it.sod } + FuzzSeeds.com + FuzzSeeds.cardAccess)
                .map { it.copyOf(minOf(it.size, HEADER_BYTES)) }
        campaign("FileSizeLimits", headers, HEADER_BYTES * 2, HEADER_ITERATIONS).run { input ->
            val size = FileSizeLimits.announcedSize(input.bytes)
            if (input.pristine) property(size != null) { "en-tête de graine non analysé" }
            size?.let { property(it > 0) { "taille annoncée négative ou nulle" } }
            val fid =
                (input.bytes.firstOrNull()?.toInt() ?: 0) shl 8 or (
                    input.bytes
                        .getOrNull(1)
                        ?.toInt()
                        ?.and(0xFF) ?: 0
                )
            property(FileSizeLimits.maxSize(fid.toShort()) > 0) { "plafond nul" }
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
