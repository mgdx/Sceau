package io.github.mgdx.sceau.core.verify

import java.util.Locale

/**
 * Consistency of a document's countries (audit V3): CSCA country (C attribute, ISO 3166-1
 * alpha-2), DS certificate country, and issuing state read from DG1 (three-letter ICAO 9303-3
 * code).
 *
 * Rules applied:
 * - the CSCA country and the DS country must be present and equal;
 * - the issuing state is converted to alpha-2 ([alpha2]): ISO 3166-1 alpha-3 codes, plus the
 *   codes specific to ICAO 9303-3 (`D` → DE; `GBD`, `GBN`, `GBO`, `GBP`, `GBS` → GB;
 *   `RKS` → KS, the code used by the Kosovo CSCA); it must then equal the CSCA country;
 * - organization and status codes ([ORGANIZATIONS]: `UNO`, `UNA`, `UNK`, `EUE`,
 *   `XXA`, `XXB`, `XXC`, `XXX`…) have no alpha-2 equivalent: for them, only the CSCA and
 *   the DS are compared (the United Nations CSCA carries C=UN, the European laissez-passer
 *   CSCA C=EU);
 * - any other code, unknown to ICAO (including the fictitious `UTO` country of specimens), is
 *   an inconsistency: a signed document cannot declare a state that does not exist.
 *
 * Certificate countries are compared in uppercase; `XK` (ISO user-assigned code often
 * given to Kosovo) is matched with `KS`.
 */
internal object IcaoCountries {
    /** ICAO 9303-3 codes of international organizations and statuses, without an alpha-2 country. */
    val ORGANIZATIONS: Set<String> =
        setOf(
            // United Nations and specialized agencies.
            "UNO",
            "UNA",
            "UNK",
            // European Union (laissez-passer).
            "EUE",
            // Stateless persons, refugees, unspecified nationality.
            "XXA",
            "XXB",
            "XXC",
            "XXX",
            // Other issuing organizations recognized by ICAO.
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

    /** Codes specific to ICAO 9303-3, in addition to the ISO 3166-1 alpha-3 codes. */
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

    /** ISO 3166-1 alpha-3 → alpha-2 (249 codes, frozen here so as not to depend on the platform). */
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

    /** Matched certificate codes: XK (ISO user-assigned code) and KS both designate Kosovo. */
    private val CERTIFICATE_ALIASES: Map<String, String> = mapOf("XK" to "KS")

    /** Alpha-2 countries that an ICAO state code can designate (check of the embedded store). */
    val knownCountries: Set<String> by lazy { (ISO_ALPHA3.values + ICAO_SPECIFIC.values).toSet() }

    /** ICAO code read from DG1, stripped of `<` filler characters and spaces. */
    fun normalize(icaoCode: String): String = icaoCode.replace("<", "").trim().uppercase(Locale.ROOT)

    fun isOrganization(icaoCode: String): Boolean = normalize(icaoCode) in ORGANIZATIONS

    /** Alpha-2 country of an ICAO state code, or null (organization or unknown code). */
    fun alpha2(icaoCode: String): String? = normalize(icaoCode).let { ICAO_SPECIFIC[it] ?: ISO_ALPHA3[it] }

    /**
     * True if the countries are consistent. [issuingState] null: the caller did not provide it,
     * only the CSCA and the DS are compared.
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
