package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.SceauException
import org.jmrtd.PassportService
import org.jmrtd.lds.LDSFileUtil
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.COMFile

/** Objets lus avant la Chip Authentication : EF.SOD, DG annoncés et DG14. */
internal class SecurityObjects(
    /** EF.SOD, tag 0x77 compris. */
    val sod: ByteArray,
    /** DG annoncés dans EF.COM ou dans le SOD : décide seulement quoi demander. */
    val announced: Set<Int>,
    /**
     * DG dont le SOD porte une empreinte (vide si le SOD est illisible). Seule cette liste fait
     * foi pour décider qu'un DG manque : EF.COM n'est pas signé.
     */
    val signedDataGroups: Set<Int>,
    /** DG14 s'il est annoncé et fourni, null sinon. */
    val dg14: ByteArray?,
)

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
 * Étape READ_DATA (SPEC §6.1, étapes 3 et 4, ordre de la décision D20), en deux temps séparés
 * par la Chip Authentication :
 * 1. [readSecurityObjects] : EF.COM, EF.SOD, puis DG14 s'il est annoncé dans EF.COM ou dans le
 *    SOD ;
 * 2. [readDataGroups] : DG1, DG2, puis DG15, DG11, DG12 et DG7 s'ils sont annoncés, sous la
 *    messagerie sécurisée courante, c'est-à-dire celle de la Chip Authentication si elle a
 *    réussi.
 *
 * EF.COM n'est pas authentifié : il ne sert qu'à décider quoi demander. Un DG que la puce ne
 * fournit pas n'interrompt pas la lecture, mais s'il figure dans le SOD, la vérification le
 * signale ([LdsContent.signedDataGroups]) : empreintes en échec, et CA ou AA en échec pour
 * DG14 ou DG15 (audit V1 : un clone ne doit pas pouvoir retenir un DG signé).
 *
 * EF.SOD et DG1 sont indispensables : la puce qui en refuse l'accès par le SW 6982 les réserve
 * aux terminaux étatiques ([io.github.mgdx.sceau.core.SceauException.AccessRestricted], D36).
 * EF.COM, DG2 et les DG facultatifs refusés sont traités comme absents.
 *
 * DG3 et DG4 (empreintes, iris) ne sont jamais demandés à la puce : seuls les numéros de
 * [SECURITY_DATA_GROUP] et [OPTIONAL_DATA_GROUPS] peuvent l'être, en plus de DG1 et DG2.
 */
internal class DocumentReader(
    private val chip: Chip,
) {
    /** Premier temps : EF.COM, EF.SOD, DG14. */
    fun readSecurityObjects(): SecurityObjects {
        // EF.COM est facultatif : s'il manque, on se fie au SOD.
        val com = chip.readOptionalFile(PassportService.EF_COM)?.let(::parseComDataGroups).orEmpty()
        val sod = readRequired(PassportService.EF_SOD, "SOD")
        val signed = parseSodDataGroups(sod)
        val announced = com + signed
        val dg14 = if (SECURITY_DATA_GROUP in announced) chip.readOptionalFile(Chip.fidOf(SECURITY_DATA_GROUP)) else null
        return SecurityObjects(sod, announced, signed, dg14)
    }

    /**
     * Second temps : DG1, DG2, DG15, DG11, DG12, DG7. Chaque DG est rangé dans [into] dès sa lecture,
     * pour que l'appelant puisse l'effacer si la suite échoue. [into] contient déjà DG14 s'il a
     * été lu.
     */
    fun readDataGroups(
        objects: SecurityObjects,
        into: MutableMap<Int, ByteArray>,
    ): LdsContent {
        into[1] = readRequired(Chip.fidOf(1), "DG1")
        // DG2 est obligatoire selon ICAO 9303 : toujours demandé. Une puce qui ne le fournit pas
        // n'interrompt pas la lecture ; s'il est dans le SOD, les empreintes seront en échec.
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
            // D36 : fichier indispensable réservé aux terminaux étatiques (Terminal
            // Authentication), alors que le canal sécurisé est établi.
            if (e.statusWord() == SW_SECURITY_STATUS_NOT_SATISFIED) throw SceauException.AccessRestricted(tag)
            throw StepFailure(tag, e)
        }

    companion object {
        /** SW 6982 : *security status not satisfied* (ISO 7816-4). */
        const val SW_SECURITY_STATUS_NOT_SATISFIED = 0x6982

        /** DG14, lu avant la Chip Authentication qu'il annonce. */
        const val SECURITY_DATA_GROUP = 14

        /**
         * DG lus après la Chip Authentication s'ils sont annoncés, dans cet ordre (DG7 : D37).
         * Ne jamais y ajouter 3 ni 4.
         */
        val OPTIONAL_DATA_GROUPS = listOf(15, 11, 12, 7)

        /**
         * DG annoncés par EF.COM ; vide s'il est illisible. Les longueurs sont contrôlées avant
         * JMRTD ([BerStructure]) : une liste de tags (5C) annonçant 2 Go dans un EF.COM de
         * quelques octets lui ferait allouer ce tableau (fuzzing, `FuzzRegressionTest`).
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
