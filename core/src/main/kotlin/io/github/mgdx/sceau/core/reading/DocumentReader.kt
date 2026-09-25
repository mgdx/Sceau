package io.github.mgdx.sceau.core.reading

import org.jmrtd.PassportService
import org.jmrtd.lds.LDSFileUtil
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.COMFile

/** Octets bruts lus dans l'applet ICAO. */
internal class LdsContent(
    /** EF.SOD, tag 0x77 compris. */
    val sod: ByteArray,
    /** Octets bruts de chaque DG lu, clé = numéro de DG, dans l'ordre de lecture. */
    val dataGroups: Map<Int, ByteArray>,
    /**
     * DG dont le SOD porte une empreinte (vide si le SOD est illisible). Seule cette liste fait
     * foi pour décider qu'un DG manque : EF.COM n'est pas signé.
     */
    val signedDataGroups: Set<Int>,
)

/**
 * Étape READ_DATA (SPEC §6.1, étapes 3 et 4) : EF.COM, EF.SOD, DG1, DG2, puis DG14, DG15,
 * DG11 et DG12 s'ils sont annoncés dans EF.COM ou dans le SOD.
 *
 * EF.COM n'est pas authentifié : il ne sert qu'à décider quoi demander. Un DG que la puce ne
 * fournit pas n'interrompt pas la lecture, mais s'il figure dans le SOD, la vérification le
 * signale ([LdsContent.signedDataGroups]) : empreintes en échec, et CA ou AA en échec pour
 * DG14 ou DG15 (audit V1 : un clone ne doit pas pouvoir retenir un DG signé).
 *
 * DG3 et DG4 (empreintes, iris) ne sont jamais demandés à la puce : seuls les numéros de
 * [OPTIONAL_DATA_GROUPS] peuvent l'être, en plus de DG1 et DG2.
 */
internal class DocumentReader(
    private val chip: Chip,
) {
    fun read(): LdsContent {
        // EF.COM est facultatif : s'il manque, on se fie au SOD.
        val com = chip.readOptionalFile(PassportService.EF_COM)?.let(::parseComDataGroups).orEmpty()
        val sod = readRequired(PassportService.EF_SOD, "SOD")
        val signed = parseSodDataGroups(sod)
        val announced = com + signed

        val dataGroups = LinkedHashMap<Int, ByteArray>()
        dataGroups[1] = readRequired(Chip.fidOf(1), "DG1")
        // DG2 est obligatoire selon ICAO 9303 : toujours demandé. Une puce qui ne le fournit pas
        // n'interrompt pas la lecture ; s'il est dans le SOD, les empreintes seront en échec.
        chip.readOptionalFile(Chip.fidOf(2))?.let { dataGroups[2] = it }
        for (number in OPTIONAL_DATA_GROUPS) {
            if (number !in announced) continue
            chip.readOptionalFile(Chip.fidOf(number))?.let { dataGroups[number] = it }
        }
        return LdsContent(sod, dataGroups, signed)
    }

    private fun readRequired(
        fid: Short,
        tag: String,
    ): ByteArray =
        try {
            chip.readFile(fid)
        } catch (e: Exception) {
            chip.rethrowTransportFailure()
            throw StepFailure(tag, e)
        }

    companion object {
        /** DG lus s'ils sont annoncés, dans cet ordre. Ne jamais y ajouter 3 ni 4. */
        val OPTIONAL_DATA_GROUPS = listOf(14, 15, 11, 12)

        fun parseComDataGroups(bytes: ByteArray): Set<Int> =
            try {
                LDSFileUtil.getDataGroupNumbers(COMFile(bytes.inputStream())).toSet()
            } catch (e: Exception) {
                emptySet()
            }

        fun parseSodDataGroups(bytes: ByteArray): Set<Int> =
            try {
                SODFile(bytes.inputStream()).dataGroupHashes.keys
            } catch (e: Exception) {
                emptySet()
            }
    }
}
