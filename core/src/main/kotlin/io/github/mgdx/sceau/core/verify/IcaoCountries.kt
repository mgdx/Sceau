package io.github.mgdx.sceau.core.verify

import java.util.Locale

/**
 * Cohérence des pays d'un document (audit V3) : pays du CSCA (attribut C, ISO 3166-1 alpha-2),
 * pays du certificat DS, et État émetteur lu dans DG1 (code ICAO 9303-3 à trois lettres).
 *
 * Règles retenues :
 * - le pays du CSCA et celui du DS doivent être présents et égaux ;
 * - l'État émetteur est converti en alpha-2 ([alpha2]) : codes ISO 3166-1 alpha-3, plus les
 *   codes propres à ICAO 9303-3 (`D` → DE ; `GBD`, `GBN`, `GBO`, `GBP`, `GBS` → GB ;
 *   `RKS` → KS, code employé par le CSCA du Kosovo) ; il doit alors égaler le pays du CSCA ;
 * - les codes d'organisations et de statuts ([ORGANIZATIONS] : `UNO`, `UNA`, `UNK`, `EUE`,
 *   `XXA`, `XXB`, `XXC`, `XXX`…) n'ont pas d'équivalent alpha-2 : pour eux, seuls le CSCA et
 *   le DS sont comparés (le CSCA des Nations unies porte C=UN, celui du laissez-passer
 *   européen C=EU) ;
 * - tout autre code, inconnu d'ICAO (y compris le pays fictif `UTO` des spécimens), est une
 *   incohérence : un document signé ne peut pas annoncer un État qui n'existe pas.
 *
 * Les pays des certificats sont comparés en majuscules ; `XK` (code utilisateur ISO souvent
 * attribué au Kosovo) est rapproché de `KS`.
 */
internal object IcaoCountries {
    /** Codes ICAO 9303-3 d'organisations internationales et de statuts, sans pays alpha-2. */
    val ORGANIZATIONS: Set<String> =
        setOf(
            // Nations unies et institutions spécialisées.
            "UNO",
            "UNA",
            "UNK",
            // Union européenne (laissez-passer).
            "EUE",
            // Apatrides, réfugiés, nationalité non précisée.
            "XXA",
            "XXB",
            "XXC",
            "XXX",
            // Autres organisations émettrices reconnues par ICAO.
            "XBA",
            "XIM",
            "XCC",
            "XCE",
            "XCO",
            "XDC",
            "XEC",
            "XES",
            "XMP",
            "XOM",
            "XPO",
        )

    /** Codes propres à ICAO 9303-3, en plus des codes ISO 3166-1 alpha-3. */
    private val ICAO_SPECIFIC: Map<String, String> =
        mapOf(
            "D" to "DE",
            "GBD" to "GB",
            "GBN" to "GB",
            "GBO" to "GB",
            "GBP" to "GB",
            "GBS" to "GB",
            "RKS" to "KS",
        )

    /** ISO 3166-1 alpha-3 → alpha-2 (249 codes, figés ici pour ne pas dépendre de la plateforme). */
    private val ISO_ALPHA3: Map<String, String> =
        """
            ABW=AW AFG=AF AGO=AO AIA=AI ALA=AX ALB=AL AND=AD ARE=AE ARG=AR ARM=AM ASM=AS ATA=AQ
            ATF=TF ATG=AG AUS=AU AUT=AT AZE=AZ BDI=BI BEL=BE BEN=BJ BES=BQ BFA=BF BGD=BD BGR=BG
            BHR=BH BHS=BS BIH=BA BLM=BL BLR=BY BLZ=BZ BMU=BM BOL=BO BRA=BR BRB=BB BRN=BN BTN=BT
            BVT=BV BWA=BW CAF=CF CAN=CA CCK=CC CHE=CH CHL=CL CHN=CN CIV=CI CMR=CM COD=CD COG=CG
            COK=CK COL=CO COM=KM CPV=CV CRI=CR CUB=CU CUW=CW CXR=CX CYM=KY CYP=CY CZE=CZ DEU=DE
            DJI=DJ DMA=DM DNK=DK DOM=DO DZA=DZ ECU=EC EGY=EG ERI=ER ESH=EH ESP=ES EST=EE ETH=ET
            FIN=FI FJI=FJ FLK=FK FRA=FR FRO=FO FSM=FM GAB=GA GBR=GB GEO=GE GGY=GG GHA=GH GIB=GI
            GIN=GN GLP=GP GMB=GM GNB=GW GNQ=GQ GRC=GR GRD=GD GRL=GL GTM=GT GUF=GF GUM=GU GUY=GY
            HKG=HK HMD=HM HND=HN HRV=HR HTI=HT HUN=HU IDN=ID IMN=IM IND=IN IOT=IO IRL=IE IRN=IR
            IRQ=IQ ISL=IS ISR=IL ITA=IT JAM=JM JEY=JE JOR=JO JPN=JP KAZ=KZ KEN=KE KGZ=KG KHM=KH
            KIR=KI KNA=KN KOR=KR KWT=KW LAO=LA LBN=LB LBR=LR LBY=LY LCA=LC LIE=LI LKA=LK LSO=LS
            LTU=LT LUX=LU LVA=LV MAC=MO MAF=MF MAR=MA MCO=MC MDA=MD MDG=MG MDV=MV MEX=MX MHL=MH
            MKD=MK MLI=ML MLT=MT MMR=MM MNE=ME MNG=MN MNP=MP MOZ=MZ MRT=MR MSR=MS MTQ=MQ MUS=MU
            MWI=MW MYS=MY MYT=YT NAM=NA NCL=NC NER=NE NFK=NF NGA=NG NIC=NI NIU=NU NLD=NL NOR=NO
            NPL=NP NRU=NR NZL=NZ OMN=OM PAK=PK PAN=PA PCN=PN PER=PE PHL=PH PLW=PW PNG=PG POL=PL
            PRI=PR PRK=KP PRT=PT PRY=PY PSE=PS PYF=PF QAT=QA REU=RE ROU=RO RUS=RU RWA=RW SAU=SA
            SDN=SD SEN=SN SGP=SG SGS=GS SHN=SH SJM=SJ SLB=SB SLE=SL SLV=SV SMR=SM SOM=SO SPM=PM
            SRB=RS SSD=SS STP=ST SUR=SR SVK=SK SVN=SI SWE=SE SWZ=SZ SXM=SX SYC=SC SYR=SY TCA=TC
            TCD=TD TGO=TG THA=TH TJK=TJ TKL=TK TKM=TM TLS=TL TON=TO TTO=TT TUN=TN TUR=TR TUV=TV
            TWN=TW TZA=TZ UGA=UG UKR=UA UMI=UM URY=UY USA=US UZB=UZ VAT=VA VCT=VC VEN=VE VGB=VG
            VIR=VI VNM=VN VUT=VU WLF=WF WSM=WS YEM=YE ZAF=ZA ZMB=ZM ZWE=ZW
        """.trim()
            .split(Regex("\\s+"))
            .associate { pair -> pair.substringBefore('=') to pair.substringAfter('=') }

    /** Codes de certificat rapprochés : XK (code utilisateur ISO) et KS désignent le Kosovo. */
    private val CERTIFICATE_ALIASES: Map<String, String> = mapOf("XK" to "KS")

    /** Pays alpha-2 qu'un code ICAO d'État peut désigner (contrôle du magasin embarqué). */
    val knownCountries: Set<String> by lazy { (ISO_ALPHA3.values + ICAO_SPECIFIC.values).toSet() }

    /** Code ICAO lu dans DG1 débarrassé des caractères de remplissage `<` et des espaces. */
    fun normalize(icaoCode: String): String = icaoCode.replace("<", "").trim().uppercase(Locale.ROOT)

    fun isOrganization(icaoCode: String): Boolean = normalize(icaoCode) in ORGANIZATIONS

    /** Pays alpha-2 d'un code ICAO d'État, ou null (organisation ou code inconnu). */
    fun alpha2(icaoCode: String): String? = normalize(icaoCode).let { ICAO_SPECIFIC[it] ?: ISO_ALPHA3[it] }

    /**
     * Vrai si les pays sont cohérents. [issuingState] null : l'appelant ne l'a pas fourni, seuls
     * le CSCA et le DS sont comparés.
     */
    fun consistent(
        cscaCountry: String?,
        dsCountry: String?,
        issuingState: String?,
    ): Boolean {
        val csca = cscaCountry?.let(::canonicalCertificateCountry) ?: return false
        val ds = dsCountry?.let(::canonicalCertificateCountry) ?: return false
        if (csca != ds) return false
        if (issuingState == null || isOrganization(issuingState)) return true
        return alpha2(issuingState)?.let(::canonicalCertificateCountry) == csca
    }

    private fun canonicalCertificateCountry(country: String): String =
        country.trim().uppercase(Locale.ROOT).let { CERTIFICATE_ALIASES[it] ?: it }
}
