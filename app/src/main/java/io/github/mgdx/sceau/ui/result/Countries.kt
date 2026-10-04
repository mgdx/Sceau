package io.github.mgdx.sceau.ui.result

import java.util.IllformedLocaleException
import java.util.Locale

/** Codes ICAO 9303 qui ne désignent pas un État ISO 3166 et reçoivent un libellé propre. */
enum class SpecialCountry {
    /** UNO : Organisation des Nations unies. */
    UNITED_NATIONS,

    /** UNA : institution spécialisée des Nations unies. */
    UN_SPECIALIZED_AGENCY,

    /** UNK : résident du Kosovo, document délivré par la MINUK. */
    UN_KOSOVO,

    /** XXA : apatride (convention de 1954). */
    STATELESS,

    /** XXB : réfugié (convention de 1951). */
    REFUGEE,

    /** XXC : réfugié, autre texte que la convention de 1951. */
    REFUGEE_OTHER,

    /** XXX : nationalité non précisée. */
    UNSPECIFIED,
}

/**
 * Pays résolu depuis un code ICAO à trois lettres. [alpha2] sert au drapeau et au nom
 * localisé ; [special] prime sur le nom localisé quand il est présent ; [code] est le
 * code brut nettoyé, affiché si rien d'autre n'est connu.
 */
class CountryRef(
    val code: String,
    val alpha2: String?,
    val special: SpecialCountry?,
)

/** Correspondance entre codes pays ICAO 9303 (alpha-3 et codes spéciaux) et ISO 3166-1 alpha-2. */
object Countries {
    private val special: Map<String, Pair<String?, SpecialCountry?>> =
        mapOf(
            // Allemagne : code historique à une lettre, complété par des « < » dans la MRZ.
            "D" to ("DE" to null),
            // Royaume-Uni : citoyens des territoires d'outre-mer, ressortissants, sujets, protégés.
            "GBD" to ("GB" to null),
            "GBN" to ("GB" to null),
            "GBO" to ("GB" to null),
            "GBP" to ("GB" to null),
            "GBS" to ("GB" to null),
            "EUE" to ("EU" to null),
            "UNO" to ("UN" to SpecialCountry.UNITED_NATIONS),
            "UNA" to ("UN" to SpecialCountry.UN_SPECIALIZED_AGENCY),
            "UNK" to ("UN" to SpecialCountry.UN_KOSOVO),
            "XXA" to (null to SpecialCountry.STATELESS),
            "XXB" to (null to SpecialCountry.REFUGEE),
            "XXC" to (null to SpecialCountry.REFUGEE_OTHER),
            "XXX" to (null to SpecialCountry.UNSPECIFIED),
            // Kosovo : code utilisé par ICAO, absent d'ISO 3166-1.
            "RKS" to ("XK" to null),
        )

    /** ISO 3166-1 alpha-3 vers alpha-2, sous forme de paires « ABC=AB » séparées par des blancs. */
    private val iso: Map<String, String> =
        """
            ABW=AW AFG=AF AGO=AO AIA=AI ALA=AX ALB=AL AND=AD ARE=AE ARG=AR ARM=AM ASM=AS ATA=AQ ATF=TF ATG=AG AUS=AU AUT=AT
            AZE=AZ BDI=BI BEL=BE BEN=BJ BES=BQ BFA=BF BGD=BD BGR=BG BHR=BH BHS=BS BIH=BA BLM=BL BLR=BY BLZ=BZ BMU=BM BOL=BO
            BRA=BR BRB=BB BRN=BN BTN=BT BVT=BV BWA=BW CAF=CF CAN=CA CCK=CC CHE=CH CHL=CL CHN=CN CIV=CI CMR=CM COD=CD COG=CG
            COK=CK COL=CO COM=KM CPV=CV CRI=CR CUB=CU CUW=CW CXR=CX CYM=KY CYP=CY CZE=CZ DEU=DE DJI=DJ DMA=DM DNK=DK DOM=DO
            DZA=DZ ECU=EC EGY=EG ERI=ER ESH=EH ESP=ES EST=EE ETH=ET FIN=FI FJI=FJ FLK=FK FRA=FR FRO=FO FSM=FM GAB=GA GBR=GB
            GEO=GE GGY=GG GHA=GH GIB=GI GIN=GN GLP=GP GMB=GM GNB=GW GNQ=GQ GRC=GR GRD=GD GRL=GL GTM=GT GUF=GF GUM=GU GUY=GY
            HKG=HK HMD=HM HND=HN HRV=HR HTI=HT HUN=HU IDN=ID IMN=IM IND=IN IOT=IO IRL=IE IRN=IR IRQ=IQ ISL=IS ISR=IL ITA=IT
            JAM=JM JEY=JE JOR=JO JPN=JP KAZ=KZ KEN=KE KGZ=KG KHM=KH KIR=KI KNA=KN KOR=KR KWT=KW LAO=LA LBN=LB LBR=LR LBY=LY
            LCA=LC LIE=LI LKA=LK LSO=LS LTU=LT LUX=LU LVA=LV MAC=MO MAF=MF MAR=MA MCO=MC MDA=MD MDG=MG MDV=MV MEX=MX MHL=MH
            MKD=MK MLI=ML MLT=MT MMR=MM MNE=ME MNG=MN MNP=MP MOZ=MZ MRT=MR MSR=MS MTQ=MQ MUS=MU MWI=MW MYS=MY MYT=YT NAM=NA
            NCL=NC NER=NE NFK=NF NGA=NG NIC=NI NIU=NU NLD=NL NOR=NO NPL=NP NRU=NR NZL=NZ OMN=OM PAK=PK PAN=PA PCN=PN PER=PE
            PHL=PH PLW=PW PNG=PG POL=PL PRI=PR PRK=KP PRT=PT PRY=PY PSE=PS PYF=PF QAT=QA REU=RE ROU=RO RUS=RU RWA=RW SAU=SA
            SDN=SD SEN=SN SGP=SG SGS=GS SHN=SH SJM=SJ SLB=SB SLE=SL SLV=SV SMR=SM SOM=SO SPM=PM SRB=RS SSD=SS STP=ST SUR=SR
            SVK=SK SVN=SI SWE=SE SWZ=SZ SXM=SX SYC=SC SYR=SY TCA=TC TCD=TD TGO=TG THA=TH TJK=TJ TKL=TK TKM=TM TLS=TL TON=TO
            TTO=TT TUN=TN TUR=TR TUV=TV TWN=TW TZA=TZ UGA=UG UKR=UA UMI=UM URY=UY USA=US UZB=UZ VAT=VA VCT=VC VEN=VE VGB=VG
            VIR=VI VNM=VN VUT=VU WLF=WF WSM=WS YEM=YE ZAF=ZA ZMB=ZM ZWE=ZW
        """.trim()
            .split(Regex("\\s+"))
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    private val alpha3ByAlpha2: Map<String, String> by lazy { iso.entries.associate { (a3, a2) -> a2 to a3 } }

    /** Code ISO 3166-1 alpha-3 d'un pays désigné par son code alpha-2, ou null s'il est inconnu. */
    fun alpha3(alpha2: String): String? = alpha3ByAlpha2[alpha2.trim().uppercase(Locale.ROOT)]

    /** Résout un code ICAO (ex. "FRA", "D<<", "GBD") ; les « < » de remplissage sont ignorés. */
    fun fromIcao(icaoCode: String): CountryRef {
        val code = icaoCode.replace("<", "").trim().uppercase(Locale.ROOT)
        special[code]?.let { (alpha2, kind) -> return CountryRef(code, alpha2, kind) }
        return CountryRef(code, iso[code], null)
    }

    /** Pays désigné directement par son code ISO 3166-1 alpha-2 (attribut C d'un certificat). */
    fun fromAlpha2(alpha2: String): CountryRef {
        val code = alpha2.trim().uppercase(Locale.ROOT)
        return CountryRef(code, code.takeIf { isAlpha2(it) }, null)
    }

    /** Drapeau emoji composé de deux indicateurs régionaux, ou null si [alpha2] n'est pas deux lettres. */
    fun flagEmoji(alpha2: String?): String? {
        if (alpha2 == null || !isAlpha2(alpha2)) return null
        val builder = StringBuilder()
        alpha2.uppercase(Locale.ROOT).forEach { builder.appendCodePoint(REGIONAL_INDICATOR_A + (it - 'A')) }
        return builder.toString()
    }

    /**
     * Nom du pays dans la langue [inLocale], ou null si le système ne le connaît pas
     * (le nom renvoyé est alors le code lui-même).
     */
    fun displayName(
        alpha2: String?,
        inLocale: Locale,
    ): String? {
        if (alpha2 == null || !isAlpha2(alpha2)) return null
        val region =
            try {
                Locale.Builder().setRegion(alpha2).build()
            } catch (_: IllformedLocaleException) {
                return null
            }
        val name = region.getDisplayCountry(inLocale)
        return name.takeIf { it.isNotBlank() && !it.equals(alpha2, ignoreCase = true) }
    }

    private fun isAlpha2(code: String): Boolean = code.length == 2 && code.all { it.uppercaseChar() in 'A'..'Z' }

    private const val REGIONAL_INDICATOR_A = 0x1F1E6
}
