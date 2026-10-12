package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.SceauException
import org.jmrtd.PassportService
import org.jmrtd.lds.LDSFileUtil
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.COMFile

/** Objects read before Chip Authentication: EF.SOD, announced DGs and DG14. */
internal class SecurityObjects(
    /** EF.SOD, tag 0x77 included. */
    val sod: ByteArray,
    /** DGs announced in EF.COM or in the SOD: only decides what to request. */
    val announced: Set<Int>,
    /**
     * DGs for which the SOD carries a hash (empty if the SOD is unreadable). Only this list is
     * authoritative to decide that a DG is missing: EF.COM is not signed.
     */
    val signedDataGroups: Set<Int>,
    /** DG14 if announced and provided, null otherwise. */
    val dg14: ByteArray?,
)

/** Raw bytes read from the ICAO applet. */
internal class LdsContent(
    /** EF.SOD, tag 0x77 included. */
    val sod: ByteArray,
    /** Raw bytes of each DG read, key = DG number, in reading order. */
    val dataGroups: Map<Int, ByteArray>,
    /**
     * DGs for which the SOD carries a hash (empty if the SOD is unreadable). Only this list is
     * authoritative to decide that a DG is missing: EF.COM is not signed.
     */
    val signedDataGroups: Set<Int>,
)

/**
 * READ_DATA step (SPEC §6.1, steps 3 and 4, order of decision D20), in two phases separated
 * by Chip Authentication:
 * 1. [readSecurityObjects]: EF.COM, EF.SOD, then DG14 if announced in EF.COM or in the
 *    SOD;
 * 2. [readDataGroups]: DG1, DG2, then DG15, DG11, DG12 and DG7 if announced, under the
 *    current secure messaging, that is, the Chip Authentication one if it
 *    succeeded.
 *
 * EF.COM is not authenticated: it only serves to decide what to request. A DG that the chip does not
 * provide does not interrupt the reading, but if it is listed in the SOD, the verification
 * reports it ([LdsContent.signedDataGroups]): hashes failed, and CA or AA failed for
 * DG14 or DG15 (audit V1: a clone must not be able to withhold a signed DG).
 *
 * EF.SOD and DG1 are essential: a chip that denies access to them with SW 6982 reserves them
 * for government terminals ([io.github.mgdx.sceau.core.SceauException.AccessRestricted], D36).
 * EF.COM, DG2 and denied optional DGs are treated as absent.
 *
 * DG3 and DG4 (fingerprints, iris) are never requested from the chip: only the numbers in
 * [SECURITY_DATA_GROUP] and [OPTIONAL_DATA_GROUPS] can be, in addition to DG1 and DG2.
 */
internal class DocumentReader(
    private val chip: Chip,
) {
    /** First phase: EF.COM, EF.SOD, DG14. */
    fun readSecurityObjects(): SecurityObjects {
        // EF.COM is optional: if it is missing, the SOD is relied upon.
        val com = chip.readOptionalFile(PassportService.EF_COM)?.let(::parseComDataGroups).orEmpty()
        val sod = readRequired(PassportService.EF_SOD, "SOD")
        val signed = parseSodDataGroups(sod)
        val announced = com + signed
        val dg14 = if (SECURITY_DATA_GROUP in announced) chip.readOptionalFile(Chip.fidOf(SECURITY_DATA_GROUP)) else null
        return SecurityObjects(sod, announced, signed, dg14)
    }

    /**
     * Second phase: DG1, DG2, DG15, DG11, DG12, DG7. Each DG is stored in [into] as soon as it is read,
     * so that the caller can wipe it if what follows fails. [into] already contains DG14 if it has
     * been read.
     */
    fun readDataGroups(
        objects: SecurityObjects,
        into: MutableMap<Int, ByteArray>,
    ): LdsContent {
        into[1] = readRequired(Chip.fidOf(1), "DG1")
        // DG2 is mandatory under ICAO 9303: always requested. A chip that does not provide it
        // does not interrupt the reading; if it is in the SOD, the hashes will fail.
        chip.readOptionalFile(Chip.fidOf(2))?.let { into[2] = it }
        for (number in OPTIONAL_DATA_GROUPS) {
            if (number !in objects.announced) continue
            chip.readOptionalFile(Chip.fidOf(number))?.let { into[number] = it }
        }
        return LdsContent(objects.sod, into, objects.signedDataGroups)
    }

    private fun readRequired(
        fid: Short,
        tag: String,
    ): ByteArray =
        try {
            chip.readFile(fid)
        } catch (e: Exception) {
            chip.rethrowTransportFailure()
            // D36: essential file reserved for government terminals (Terminal
            // Authentication), even though the secure channel is established.
            if (e.statusWord() == SW_SECURITY_STATUS_NOT_SATISFIED) throw SceauException.AccessRestricted(tag)
            throw StepFailure(tag, e)
        }

    companion object {
        /** SW 6982 : *security status not satisfied* (ISO 7816-4). */
        const val SW_SECURITY_STATUS_NOT_SATISFIED = 0x6982

        /** DG14, read before the Chip Authentication it announces. */
        const val SECURITY_DATA_GROUP = 14

        /**
         * DGs read after Chip Authentication if announced, in this order (DG7: D37).
         * Never add 3 or 4 here.
         */
        val OPTIONAL_DATA_GROUPS = listOf(15, 11, 12, 7)

        /**
         * DGs announced by EF.COM; empty if it is unreadable. Lengths are checked before
         * JMRTD ([BerStructure]): a tag list (5C) announcing 2 GB in an EF.COM of
         * a few bytes would make it allocate that array (fuzzing, `FuzzRegressionTest`).
         */
        fun parseComDataGroups(bytes: ByteArray): Set<Int> =
            if (!BerStructure.lengthsFit(bytes)) {
                emptySet()
            } else {
                try {
                    LDSFileUtil.getDataGroupNumbers(COMFile(bytes.inputStream())).toSet()
                } catch (e: Exception) {
                    emptySet()
                }
            }

        fun parseSodDataGroups(bytes: ByteArray): Set<Int> =
            try {
                SODFile(bytes.inputStream()).dataGroupHashes.keys
            } catch (e: Exception) {
                emptySet()
            }
    }
}
