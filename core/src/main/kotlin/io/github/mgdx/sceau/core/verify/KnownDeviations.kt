package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.KnownDeviation
import java.time.LocalDate

/**
 * Éléments sur lesquels se reconnaît une anomalie connue (décision D34). [PassiveAuthenticator]
 * ne les construit qu'une fois la signature du SOD et la chaîne DS → CSCA validées, et
 * l'empreinte de DG1 vérifiée : tous les champs sont alors signés, sauf [dg12DateOfIssue].
 */
internal class DeviationEvidence(
    /** Pays (ISO 3166-1 alpha-2) du CSCA auquel aboutit la chaîne validée. */
    val cscaCountry: String?,
    /** État émetteur de DG1 (code ICAO à trois lettres). */
    val issuingState: String?,
    /** Code de document de DG1 ("P", "C", "ID"…). */
    val documentCode: String,
    /** Numéro du document de DG1, sans caractère de remplissage. */
    val documentNumber: String?,
    /** Date d'expiration de DG1, null si illisible. */
    val dateOfExpiry: LocalDate?,
    /** Attribut signé signingTime du SOD (date UTC), null s'il est absent. */
    val sodSigningTime: LocalDate?,
    /**
     * Date de délivrance lue dans DG12. **Non vérifiée** : DG12 est justement le groupe dont
     * l'empreinte diffère. Une règle ne peut s'en servir que pour **restreindre** sa portée.
     */
    val dg12DateOfIssue: LocalDate?,
)

/**
 * Anomalie connue codée en dur, avec sa source.
 *
 * @property deviation identifiant stable et DG concerné (11 ou 12 seulement)
 * @property source page ou fichier officiel de l'émetteur qui publie l'anomalie
 * @property listDate date de la liste de l'émetteur (signingTime de la Deviation List)
 * @property listSha256 empreinte SHA-256 du fichier publié, consignée dans docs/trust-sources.md §2.4
 * @property obsoleteAfter date après laquelle aucun document couvert ne peut plus être valide : la
 *   règle ne s'applique qu'à un document dont l'expiration (DG1) ne la dépasse pas, et un test
 *   échoue une fois cette date passée, pour penser à supprimer la règle
 * @property matches critère, évalué sur des éléments signés (voir [DeviationEvidence])
 */
internal class KnownDeviationRule(
    val deviation: KnownDeviation,
    val source: String,
    val listDate: LocalDate,
    val listSha256: String,
    val obsoleteAfter: LocalDate,
    val matches: (DeviationEvidence) -> Boolean,
) {
    /** Vrai si [today] est postérieur à [obsoleteAfter] : la règle n'a plus lieu d'être. */
    fun isObsolete(today: LocalDate): Boolean = today.isAfter(obsoleteAfter)

    init {
        // Garde-fou : DG1 (MRZ), DG2 (portrait), DG14 et DG15 (clés de la puce) ne sont jamais tolérés.
        require(deviation.dataGroup in KnownDeviations.TOLERABLE_DATA_GROUPS) { "DEVIATION_DATA_GROUP" }
    }
}

/**
 * Registre des anomalies connues publiées par les émetteurs (décision D34). Une anomalie ne
 * tolère qu'un écart d'empreinte sur DG11 ou DG12, et seulement si la signature du SOD et la
 * chaîne sont valides et l'empreinte de DG1 vérifiée ([PassiveAuthenticator]) ; le DG toléré
 * est alors écarté du rapport.
 */
internal class KnownDeviations(
    val rules: List<KnownDeviationRule>,
) {
    /** Anomalies qui s'appliquent à [evidence], parmi les DG de [mismatched]. */
    fun applicable(
        evidence: DeviationEvidence,
        mismatched: Collection<Int>,
    ): List<KnownDeviation> =
        rules
            .filter { rule ->
                val dataGroup = rule.deviation.dataGroup
                val expiry = evidence.dateOfExpiry
                dataGroup in TOLERABLE_DATA_GROUPS &&
                    dataGroup in mismatched &&
                    expiry != null &&
                    !expiry.isAfter(rule.obsoleteAfter) &&
                    rule.matches(evidence)
            }.map { it.deviation }
            .distinct()

    /** Règles périmées à la date [today] (voir [KnownDeviationRule.obsoleteAfter]). */
    fun obsoleteRules(today: LocalDate): List<KnownDeviationRule> = rules.filter { it.isObsolete(today) }

    companion object {
        /** Seuls DG11 et DG12 (données complémentaires, sans rôle dans la vérification) peuvent être tolérés. */
        val TOLERABLE_DATA_GROUPS: Set<Int> = setOf(11, 12)

        /** Aucune anomalie : pour les tests. */
        val NONE = KnownDeviations(emptyList())

        /** Registre réel (paresseux : les règles lisent [TOLERABLE_DATA_GROUPS] à leur création). */
        val REGISTRY: KnownDeviations by lazy { KnownDeviations(listOf(ItalianCie3Dg12.RULE)) }
    }
}

/**
 * Italie, cartes d'identité électroniques CIE 3.0 : DG12 erroné.
 *
 * Source : Deviation List du ministère de l'Intérieur (https://csca-ita.interno.gov.it/, rubrique
 * TDDL), fichier `TDDL-CIE-Signed-20180530.der` de `IT_CIE_DeviationList.zip`, CMS
 * id-icao-DeviationList (2.23.136.1.1.7) signé le 2018-05-30 par ITDeviationListSigner, émis par
 * le CSCA03 italien. Deux déviations (type 2.23.136.1.1.7.2.2, paramètre DG 12) : l'empreinte de
 * DG12 dans le SOD ne correspond pas au DG12 de la puce, dont la date de délivrance vaut le
 * 2015-12-05 pour tous les documents. Elles visent le type de document `C`, délivré du
 * 2017-10-01 au 2018-02-05, et 346 275 numéros de document (299 400 + 46 875), tous de la forme
 * `CA` + 5 chiffres + 2 lettres.
 *
 * Ces numéros ne forment pas de plages (215 394 séries contiguës) et ne sont pas embarqués
 * (volume, décision D34). Le critère codé ici est donc plus large que la liste, sur des éléments
 * signés : CSCA italien, DG1 vérifié (État ITA, code commençant par `C`, numéro de la forme de la
 * liste), signingTime du SOD dans la période de délivrance élargie d'un mois de chaque côté.
 * Restriction sur une donnée non vérifiée : un DG12 lisible dont la date de délivrance n'est pas
 * le 2015-12-05 n'est pas couvert.
 *
 * Péremption ([OBSOLETE_AFTER], 2029-03-05) : dernière délivrance admise par le critère
 * (2018-03-05, signingTime avec la marge) + 10 ans, durée maximale d'une carte d'identité pour un
 * majeur (décret-loi 112/2008, art. 31, converti par la loi 133/2008), échéance reportée au jour
 * anniversaire du titulaire qui suit (décret-loi 5/2012, art. 7, al. 2, converti par la loi
 * 35/2012) : au plus un an de plus, soit le 2029-03-05 au plus tard (au plus tard le 2029-02-05
 * pour les cartes de la liste, délivrées jusqu'au 2018-02-05). La validité de 50 ans au-delà de
 * 70 ans ne vaut que pour les cartes délivrées depuis le 2026-07-30.
 */
internal object ItalianCie3Dg12 {
    val DEVIATION = KnownDeviation(id = "IT-CIE3-DG12", dataGroup = 12)

    const val SOURCE = "https://csca-ita.interno.gov.it/certificatiCSCA/IT_CIE_DeviationList.zip"
    const val LIST_SHA256 = "1219d8c74a7acf13c55e265fa8125230167160f4f999b64b28644275a084d31d"
    val LIST_DATE: LocalDate = LocalDate.of(2018, 5, 30)

    /** Période de délivrance de la liste (2017-10-01 au 2018-02-05), élargie d'un mois (D34). */
    val SIGNING_TIME_FROM: LocalDate = LocalDate.of(2017, 9, 1)
    val SIGNING_TIME_TO: LocalDate = LocalDate.of(2018, 3, 5)

    /** Aucune carte couverte n'est valide au-delà : 2018-03-05 + 10 ans + 1 an au plus (D34). */
    val OBSOLETE_AFTER: LocalDate = LocalDate.of(2029, 3, 5)

    /** Date de délivrance erronée que portent tous les DG12 concernés. */
    val DG12_DATE_OF_ISSUE: LocalDate = LocalDate.of(2015, 12, 5)

    private val DOCUMENT_NUMBER = Regex("CA[0-9]{5}[A-Z]{2}")

    fun matches(evidence: DeviationEvidence): Boolean {
        val signingTime = evidence.sodSigningTime ?: return false
        return evidence.cscaCountry == "IT" &&
            evidence.issuingState == "ITA" &&
            evidence.documentCode.startsWith("C") &&
            DOCUMENT_NUMBER.matches(evidence.documentNumber.orEmpty()) &&
            !signingTime.isBefore(SIGNING_TIME_FROM) &&
            !signingTime.isAfter(SIGNING_TIME_TO) &&
            (evidence.dg12DateOfIssue == null || evidence.dg12DateOfIssue == DG12_DATE_OF_ISSUE)
    }

    val RULE = KnownDeviationRule(DEVIATION, SOURCE, LIST_DATE, LIST_SHA256, OBSOLETE_AFTER, ::matches)
}
