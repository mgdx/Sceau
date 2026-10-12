# Official sources of CSCA certificates

This document lists, country by country, the official sources of CSCA certificates (Country Signing
Certificate Authority, ICAO 9303 part 12): Master Lists published by States, and each State's
publication of its own CSCAs and link certificates. For each source, it states whether Sceau may
embed it in a free APK (GPLv3, F-Droid).

- **Date accessed**: 2026-09-25, for all pages and files cited.
- **Reference store**: Sceau's embedded store as of 2026-09-25, i.e. the 5 ANTS CSCAs and the BSI
  German Master List signed on 2026-05-28. That makes 590 distinct certificates (SHA-256) from 112
  issuers. It is called the "current store" below (see `docs/trust-store.md`).
  Since 2026-09-29, the 7 valid status-A certificates (GB 2026 and its link, GR residence permits
  003 and its link, GE no. 5 and no. 6, LU eTravel link) are embedded (decision D26); the two
  Greek certificates were removed on 2026-10-11 (D48), since the BSI ML provides them. The gain
  figures below are still computed against the 2026-09-25 store.
- **Re-check of 2026-10-06** (release 0.9): the embedded BSI Master List is replaced by the one
  signed on 2026-08-19 (608 certificates, 117 issuers; details in `docs/trust-store.md`,
  "History"). The embedded national sources (GB, GE, LU, and GR until its removal on 2026-10-11,
  D48) and the announced rollovers (DK, NO, KW) were reviewed on that date; the affected entries
  say so. The other entries and the gain figures are not recomputed.
- **Re-check of 2026-10-12** (checks before release 1.0.0): new BSI Master List, signed on
  2026-10-01 (609 certificates, 117 issuers; details in `docs/trust-store.md`, "History").
- **Method**: downloads only from government or institutional domains. Third-party aggregators were
  used only to find URLs. No captcha, web application firewall or terms of use were circumvented.
  - CMS signatures of Master Lists: `openssl cms -verify -noverify`, then a check that the signer is
    issued by the country's CSCA. That CSCA is itself compared with the fingerprint published by the
    State.
  - Standalone certificates: self-signature, or link verified with the issuer's key, `C` attribute,
    validity, fingerprints compared with the published ones.

## Reuse statuses

| Status | Meaning |
|---|---|
| **A1** | Explicit open license covering the site or the file (OGL v3, CC0, CC BY, OGDL). Verbatim redistribution is allowed, with attribution if the license requires it. |
| **A2** | Official publication, by a State, of **its own** CSCAs, presented as intended for verification. No reuse restriction was found, either on the page or in the site's legal notices. The lack of an explicit license leaves some residual uncertainty. |
| **A\*** | Explicit permission with conditions that Sceau complies with (the BSI case). |
| **B** | No license and no explicit permission: generic "all rights reserved" notice, or a file that bundles the CSCAs of **other** States (Master List) without its own conditions. Written permission must be requested (see "Contacts for permission"). |
| **C** | Reproduction or redistribution prohibited, restricted to private use, or subject to prior agreement. |

A1, A2 and A\* correspond to status "A" (usable); B to "probably usable, to be confirmed"; C to
"not usable" without written agreement.

A CSCA certificate contains only a public key and an authority name. It is published in order to be
redistributed to States and inspectors, and whether it is protectable at all remains uncertain. This
document does not settle that question: it cites the texts and qualifies the uncertainty. For a
Master List, the **file** is a compilation signed by a third State, which may have its own
conditions. That is why a Master List with no notice at all is classed B, not A2.

## 1. Summary

- **Countries and organizations documented**: 93, of which 45 have an identified official publication.
  - 36 publications downloaded and verified: 34 countries, plus the EU laissez-passer, and France
    (ANTS), already embedded.
  - 8 publications found but inaccessible: BG, LI, MC, MT (web application firewall), JP, AU, IL
    (Akamai or Cloudflare), NG (dead links).
  - Portugal's page offers no file.
- **Public Master Lists signed by a State**: 7 downloaded and verified, in order DE, IT, SE, NL,
  CH, EE and ES. One is announced but inaccessible (NG). The ICAO Master List is excluded
  (status C).
- **Source statuses** (excluding ICAO):
  - A1: GB, BE (passport), PL, TW, EU;
  - A2: EE, RO, GE, NO, DK, IS, VA, CY, LU, FI, BE (eID), NL (national
    certificates), IT (national certificates);
  - A\*: DE (Master List and certificates);
  - B: Master Lists IT, SE, NL and EE; national certificates of MY, KW, LV, LT, CZ, SK, HU, SI, RS,
    BA, MK, AT and SE;
  - C: Master Lists CH, ES and ICAO; national certificates of CH, AD, ES and GR (passport).
- **Possible gain over the current store**: certificates missing from the store and still valid
  on 2026-09-25. The figures are produced by `tools/inventory.py` (details in section 4).

| Sources retained | New certificates | of which valid | of which valid EU/EEA/CH/GB | New issuers |
|---|---|---|---|---|
| A1 only | 10 | 2 | 2 | 0 |
| A (A1 + A2 + A\*) | 40 | 7 | 5 | 0 |
| A + B | 176 | 111 | 34 | 24 |
| All (A + B + C) | 254 | 127 | 42 | 26 |

## 2. Master Lists

### 2.1 Summary table

| ML | Official page | File | Signed on | Certificates / issuers | New valid | Signer → country CSCA (fingerprint published by the State) | Published file fingerprint | Status |
|---|---|---|---|---|---|---|---|---|
| **DE** (BSI) | <https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.html> | <https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.zip?__blob=publicationFile&v=112> (ZIP → CMS `.ml`); on 2026-09-25, `v=108`, signed on 2026-05-28, 588 / 112; on 2026-10-06, `v=109`, signed on 2026-08-19, 608 / 117 | 2026-10-01 (checked on 2026-10-12) | 609 / 117 | 0 (already embedded) | `CN=CSCA Master List Signer`, serialNumber=0039 → csca-germany, SHA-256 `2084aed7…ada8` (published, matching) | none | A\* |
| **IT** (Ministry of the Interior) | <https://csca-ita.interno.gov.it/html/csca.html> | <https://csca-ita.interno.gov.it/certificatiCSCA/IT_MasterListCSCA.zip> (ZIP → `.ml`) | 2026-09-07 | 670 / 136 | **96** | `CN=MasterListSigner`, serialNumber=004 → CSCA05 "Italian Country Signer CA", SHA-1 `e8e99576…d263` (published, matching) | none | B |
| **SE** (Polismyndigheten) | <http://cert.polisen.se/CSCA/> | <http://cert.polisen.se/CSCA/SWE.ml> (**HTTP only**) | 2026-06-02 | 646 / 134 | 84 | `CN=Swedish Master List Signer` → "Swedish Country Signing CA v2" (2024), SHA-1 `f7fd7b82…7fdb` (published, matching) | SHA-1 `a2057b07…da55`, matching | B |
| **NL** (RvIG, NPKD) | <https://www.npkd.nl/masterlist.html> | <https://www.npkd.nl/files/ml/NL_MASTERLIST.mls> | 2026-07-23 | 411 / 129 (self-signed only) | 39 | `CN=Masterlist Signer NL`, serialNumber=8 → CSCA NL serialNumber=7, SHA-256 `411356d2…1a24` (published, matching) | SHA-1, SHA-256 and SHA-512 matching | B |
| **CH** (fedpol, FOITT) | <https://www.bit.admin.ch/de/sg-pki-csca-production-overview-de> | <https://www.pki.admin.ch/MasterList/Current-Swiss-MasterList.ml> | 2026-07-16 | 567 / 100 | 45 | `CN=ML, OU=MLS` → CSCA-CHE 2023, SHA-256 `a77cdb0e…8e9e` (published, matching) | none | C |
| **EE** (PPA) | <https://pki.politsei.ee/> | <https://pki.politsei.ee/CSCA_masterlist.ml> | 2026-06-08 | 374 / 111 | 2 | `CN=CSCA_MasterListSigner` → CSCA_Estonia 2023, SHA-256 `f22e447e…fafc` (published, matching) | none | B |
| **ES** (Dirección General de la Policía) | <https://www.dnie.es/PortalDNIe/PRF1_Cons02.action?pag=REF_1093> | <http://pki.policia.es/csca/MasterList/SpanishMasterList.ml> (**HTTP**) | **2022-01-25** | 277 / 86, of which 86 expired | 10 | `CN=NPKD, OU=PASSPORT` → CSCA SPAIN 3, SHA-1 `6aeb147e…f6c7` (published, matching) | none | C |
| ICAO | <https://www.icao.int/icao-pkd/icao-master-list> | via <https://download.pkd.icao.int/> (captcha) | 2026-07-15 | 579 (announced) | not downloaded | — | — | C |
| NG (NIS) | <https://immigration.gov.ng/pki-pkd/> | `files/MasterList.ml` announced, 404 | — | — | — | — | — | B/C (inaccessible) |

All downloaded CMS signatures verify. All signers were cryptographically verified as issued by the
publishing country's CSCA.

Searched without success (no public Master List found): FR, BE, AT, NO, FI, DK, LU, LV, LT,
PL, CZ, HU, PT, IE, GB, CA, AU, NZ, JP (site returns 403), SG, US and KR. The results point to the
ICAO PKD.

### 2.2 Master List reuse conditions (texts cited)

**DE, BSI (A\*)**
- ML page: "*Eine kommerzielle Nutzung der deutschen CSCA Masterliste darf erfolgen, sofern
  folgende Vorgaben beachtet werden: Mit der deutschen CSCA Masterliste darf nicht geworben werden.
  Die Nutzung der deutschen CSCA Masterliste darf nicht den Anschein einer Kooperation mit dem BSI
  erwecken.*" The page also publishes the English version: "Commercial use may be permitted provided
  that […] must not be used for advertising […] must not give the appearance of cooperation with the
  BSI."
- Site terms of use, <https://www.bsi.bund.de/EN/Service/Nutzungsbedingungen/nutzungsbedingungen_node.html>:
  - "*Any further use, in particular commercial or journalistic use, requires the prior consent of
    the BSI […] The other BSI contents made available for download may only be used unchanged within
    the scope of the permitted use.*"
  - "*VII. Mirroring the website — Any mirroring of BSI web content requires the prior written
    consent of the BSI.*"
- Reading: the ML's own page permits use under two conditions. The general terms require unchanged
  use, and written agreement for "Spiegelung" (mirroring). Whether or not an APK counts as
  "mirroring" has not been established.

**IT, Ministry of the Interior (B)**
- The csca-ita.interno.gov.it site carries no legal notice or license.
- The ministry's note legali, <https://www.interno.gov.it/it/note-legali>, provide CC BY 4.0
  for www.interno.gov.it: "*sono disponibili con licenza d'uso Creative Commons CC BY 4.0 […]
  liberamente scaricabili, distribuibili e riutilizzabili a condizione che sia citata la fonte*".
  They defer thematic sites to their own conditions. csca-ita does not appear in the list at
  <https://www.interno.gov.it/it/siti-tematici>.
- The Codice dell'amministrazione digitale, art. 52 para. 2, makes published data open by default:
  "*I dati e i documenti che i soggetti […] pubblicano, con qualsiasi modalità, senza l'espressa
  adozione di una licenza […] si intendono rilasciati come dati di tipo aperto*"
  (<https://docs.italia.it/italia/piano-triennale-ict/codice-amministrazione-digitale-docs/it/stabile/_rst/capo_V-sezione_I-articolo_52.html>).
  This is a favorable basis, but one the authority must confirm.

**SE, Polismyndigheten (B)**
- ML policy,
  <https://polisen.se/contentassets/359ae9679d4949cc869baaf6733fb29c/se-ml-policy.pdf>: the list is
  "*signed by the Swedish eMRTD Master List Signer (MLS) and made publicly available*". It asks that
  this excerpt be clearly presented with any distribution: "*a receiving State making use of
  Master Lists MUST still determine its own policies for establishing trust in the certificates
  contained on that list*". No license.
- Site, <https://polisen.se/om-polisen/om-webbplatsen/bild-och-text-upphovsratt/>: "*I regel går
  det bra att använda delar av informationsmaterialet […] Innan eventuellt textmaterial ska användas
  vill vi ändå att du kontaktar Polismyndigheten*". In other words, use is generally allowed, but the
  police must be contacted before using texts. Nothing addresses certificates.

**NL, RvIG (B)**
- npkd.nl carries no notice.
- The operator's CC0 covers only rvig.nl (<https://www.rvig.nl/copyright>): "*Tenzij anders
  vermeld, is op de inhoud van deze website de Creative Commons zero verklaring (CC0) van
  toepassing.*"

**EE, PPA (B)**: no notice on pki.politsei.ee, and no terms page found on politsei.ee.

**CH, fedpol (C)**: <https://www.admin.ch/gov/en/start/terms-and-conditions.html> states
"*Downloading or copying of texts, illustrations, photos or any other data does not entail any
transfer of rights on the content […] Any reproduction requires the prior written consent of the
copyright holder.*"

**ES, Policía (C)**: the aviso legal,
<https://www.dnie.es/PortalDNIe/PRF1_Cons02.action?pag=REF_750&id_menu=0>, provides that "*Su uso,
reproducción, distribución, comunicación pública […] queda totalmente prohibida salvo que medie
expresa autorización de la Dirección General de la Policía. La licencia de uso […] se limita a la
descarga por parte del usuario de dicho contenido y el uso privado del mismo*".

**ICAO (C)**: "*You may not copy, reproduce, republish, download, post, broadcast, transmit, make
available to the public, or otherwise use ICAO PKD content in any way except for your own
personal/organizational non-commercial use*". Downloading goes through a captcha.

### 2.3 Technical particulars

- **Anchoring**: each ML contains the CSCA that signed its own signer. The fingerprint of the
  publishing country's CSCA must therefore be pinned outside the file, taken from its official page:
  - DE: `2084aed7a991b3158e63ad750d3dc38bc6c9dcf5958f28d1162f49884e91ada8`;
  - IT (CSCA05): `615bd40cd7bc63aeb572cf168ccb78162003b31cd98130dd71f7d2d69c83fa3a`. The State only
    publishes the SHA-1 `e8e99576f6e770250b47c663d86e0a0285c9d263`.
  - SE (v2 2024): `353bd3863bb526a3f8a0a36eade08365eccba03ff13a5f7ac8fac539a7db17ff`; published SHA-1
    `f7fd7b82d07b369f9732a636d727152ceb427fdb`;
  - NL: `411356d206b99da15efadf4b84149f8600ee6260c6727cd6bc230d6b6e151a24`;
  - EE: `f22e447ebb4b363d0fc863447dac867b2d4f4f896492292fb6e6720cdbfffafc`;
  - CH (2023): `a77cdb0e304a0733777f521eb4f4ae36db7e8732626da7307933bc32ba0f8e9e`.
- **Algorithms**: IT and EE sign with RSASSA-PSS and SHA-512, DE and SE with ECDSA-SHA256, NL with
  RSA-SHA256.
- **Contents**: NL and EE contain only self-signed certificates, no links. IT is the only ML that
  covers almost all of the current store: it lacks 16 certificates from the BSI ML, versus 28 for
  SE, 71 for CH and 218 for NL and EE.
- **Transport**: SE and ES are served over HTTP only. EE presents an incomplete TLS chain (the
  Sectigo intermediate is missing). In all three cases, integrity rests on the CMS signature and on
  the pinned anchor.

### 2.4 Public Deviation Lists

| Issuer | Page | File | Contents |
|---|---|---|---|
| IT | <https://csca-ita.interno.gov.it/> (TDDL section) | <https://csca-ita.interno.gov.it/certificatiCSCA/IT_CIE_DeviationList.zip> → `IT/IT_CIE_DeviationList/TDDL-CIE-Signed-20180530.der` | CMS `id-icao-DeviationList` (2.23.136.1.1.7) signed on 2018-05-30. The signer `ITDeviationListSigner` is issued by CSCA03 (published SHA-1 `33dfc79d…`, matching). **346,275 Italian identity cards (CIE 3.0) carry a faulty DG12**: their DG12 hash will not match the SOD. Handled by a hard-coded rule (decision D34, below). Notes: <https://csca-ita.interno.gov.it/certificatiCSCA/CIE3.0-NotaAnomaliaDG12ITA.pdf>. |
| EU | <https://eu-csca.jrc.ec.europa.eu/> | page text | Laissez-passers issued to German officials before 2022-05-05: nationality field "DEU" in the MRZ. |
| EE | <https://pki.politsei.ee/> | — | "No DLs available". |

#### Italian list: contents and chosen criterion (decision D34)

File examined on 2026-10-04, **not embedded**: `TDDL-CIE-Signed-20180530.der`, 3,811,896 bytes,
SHA-256 `1219d8c74a7acf13c55e265fa8125230167160f4f999b64b28644275a084d31d` (archive
`IT_CIE_DeviationList.zip`: `22c6c32a1023333d5226dac8d844878e6aa8e4539a89470594d23c7ef605ffdb`).

- **Signature**: valid CMS `SignedData` (SHA-256 with RSA, signingTime 2018-05-30 11:02:37 UTC).
  Signer `CN=ITDeviationListSigner, serialNumber=001` (SHA-256
  `9af02c83205cc3506a06b9b1b2f804f2147aa8f578852e0d63850953266d4a41`, valid from 2018-05-30 to
  2021-05-29), whose signature is verified by the Italian CSCA03 (AKI `436ce392…7f1b`, SHA-1
  `33dfc79d…`) present in the embedded BSI Master List.
- **Contents** (`DeviationList` version 0): two deviations, each of type
  `2.23.136.1.1.7.2.2` (data group hash) with parameter DG 12:
  1. 299,400 documents: "the hash of the DG12 inside the EF.SOD does not correspond to the one
     computed reading the DG12 from the chip. Issuance date inside the DG12 read from the card is set
     to 05th December 2015 for all the documents";
  2. 46,875 documents: same anomaly, and the wording "non valida per l'espatrio" is missing from the
     observations field (documents not valid for travel abroad).
- **Documents covered**: document type `C`, issuance period from 2017-10-01 to 2018-02-05, and
  the explicit list of document numbers: 346,275 distinct numbers, all of the form `CA` +
  5 digits + 2 letters (suffixes `AA` to `AS`). The list does not designate any DS certificate.
- **Non-conformance**: the period bounds are `GeneralizedTime` values followed by the text "UTC"
  (`20171001000000.000Z UTC`), which ASN.1 syntax does not allow. A future generic Deviation List
  reader will have to tolerate it.
- **Numbers not embedded**: they do not form ranges (215,394 contiguous runs, whatever the
  ordering); as text they take about 3.1 MB, as a bitmap 237 KB (about 125 KB
  compressed). User's choice: hard-coded rule, with no embedded file.
- **Coded criterion** (`core/…/verify/KnownDeviations.kt`, `ItalianCie3Dg12`), all cumulative, on
  signed elements: valid SOD signature and DS → CSCA chain, CSCA with country `IT`; DG1 hash
  verified, issuing State `ITA`, document code starting with `C`, number of the form
  `CA[0-9]{5}[A-Z]{2}`; SOD signingTime present and between 2017-09-01 and 2018-03-05
  (the list's period widened by one month on each side). Restriction based on unverified data:
  a readable DG12 whose issuance date is not 2015-12-05 is not covered.
- **Scope**: broader than the list (any CIE from the period meeting the criterion), without risk:
  only the DG12 hash mismatch is tolerated, and DG12 is then discarded (not displayed, bytes
  zeroed, issuance date ignored). No data that underpins the verdict (DG1, DG2, DG14, DG15, SOD,
  chain) is affected.
- **Expiry**: the rule only applies to a document whose expiry date (DG1, signed) is no later than
  **2029-03-05**. This ceiling is 2018-03-05 (last admitted issuance) + 10 years (decree-law
  112/2008, art. 31), plus at most one year until the holder's birthday (decree-law 5/2012,
  art. 7, para. 2). After that date, `KnownDeviationsTest` fails: remove the rule
  `IT-CIE3-DG12` and decision D34.

## 3. National publications, country by country

Each entry gives: the authority, the official page, the files, the published fingerprints and
whether they match, the certificates missing from the current store ("new"), the reuse conditions,
the status and the contact. "Current" means the most recent self-signed CSCA. The files are stored
in `trust-catalog/<country code>/`.

### 3.1 European Union, EEA, Switzerland, United Kingdom

**AT — Austria, BMI**
- Page: <https://www.bmi.gv.at/downloads/csca.html>
- Files: `https://www.bmi.gv.at/csca/crt/cscaaustriacacert00{3,4,5}.cer`, the links
  `cscaaustriacacertlink00{3,4,5}.cer`, `c_csca_001.cer`, and
  `https://www.bmi.gv.at/csca/der/cscaaustria.cacert.gen2(.link).cer`; DER or PEM.
- Fingerprints: SHA-1 published, 9 of 9 matching.
- Certificates: current CSCA-AUSTRIA 005 (2024-08-19 → 2039-11-23),
  `1763ae21ba75dc3e830f4c5cde7cfcdfae560981762fb0acd67936a10910aff3`. Only one new, expired.
- Conditions, <https://www.bmi.gv.at/impressum/start.html>: "*Die Übernahme von Beiträgen ist –
  unter Quellenangabe – gestattet (außer für kommerzielle Zwecke).*" Reuse is allowed with the
  source cited, except for commercial purposes. The clause covers "Beiträge" (contributions);
  does it apply to certificates? Uncertain.
- Status: **B**. The non-commercial clause is incompatible with the GPL's freedom of use if it
  applies. No practical impact: all valid Austrian CSCAs are already in the store.
- Contact: csca@bmi.gv.at

**BE — Belgium, passport (FPS Foreign Affairs)**
- Page: <https://diplomatie.belgium.be/en/belgian-passport-country-signing-certificate-authority>,
  with the fingerprint PDF <https://diplomatie.belgium.be/sites/default/files/2026-08/CSCA-FA-BE-2024.pdf>.
- Files (HTTP): `http://www.diplomatie.be/csca/CSCA_FA_BE_2024.crt` and `CSCA_FA_BE_2024_link.crt`,
  `CSCA.2021.crt`, `CSCA.2021.link.crt`, `CSCA_FA_BE.crt`, `CSCAEC_BE_3.crt`, `CSCAPKI_BE_02.crt`.
- Fingerprints: the SHA-1 values all match. Two SHA-256 values in the PDF do not match the DER: the
  2024 one is the SHA-256 of the PEM file, the 2024 link one matches no file. This is an
  anomaly on the page.
- Certificates: current CSCA_FA_BE 2024 (2024-11-29 → 2035-11-27),
  `ac4a33500784302cfba2d4203cae38748a56dcab63dbefdad275a55a320a7877`, already in the store. Only one
  new, expired.
- Conditions, <https://diplomatie.belgium.be/en/legal-notice>: "*Conditions for re-use (Creative
  Commons-0 licence) Unless otherwise stated, the information on this website is free of rights and
  may be used for private, association, scientific and commercial purposes.*" Caveat: the files
  are served by www.diplomatie.be, a different host name.
- Status: **A1**. Contact: csca-pass@diplobel.fed.be

**BE — Belgium, eID card and residence permits**
- Page: <http://certs.eid.belgium.be/>
- Files: `csca-0{1..4}.crt` and `csca-0{2,3,4}-link.crt`.
- No fingerprint published.
- Certificates: Belgium Country Signing CA 02, 03 and 04 and their links, already in the store. CA 01
  (expired in March 2026) and one expired link are new.
- Conditions: no notice on the site. Status: **A2**. Contact: not found.

**BG — Bulgaria, MVR**
- Page:
  <https://www.mvr.bg/en/ministry/about-ministry-of-internals/usefull-for-you/certificates-for-verification-of-e-documents>
- Cloudflare challenge, nothing was downloaded. According to the search engine snippet, the page
  publishes a CSCA from 02/2024 and a 2019-2024 link.
- Status: undetermined (**B** by default). To be checked by hand. The current store contains 8
  valid Bulgarian certificates, one of them from 2026.

**CY — Cyprus, CRMD**
- Pages: <https://csca.crmd.moi.gov.cy/PKI/CSCA/Root_Certificate> and `/Previous_Certificates`.
- Files: `https://csca.crmd.moi.gov.cy/csca/csca-cy_{03_2024,07_2020,11_2018,11_2014}_{self_signed,link}.zip`.
- Fingerprints: SHA-256 published, 8 of 8 matching.
- Certificates: all already in the store. Current: CSCA-CYPRUS 03/2024.
- Note: the site's TLS certificate expired on 2026-02-09. Integrity is ensured by the published
  SHA-256 values.
- Conditions: no notice. Status: **A2**. Contact: cyp-csca@crmd.moi.gov.cy

**CZ — Czechia, MV ČR**
- Page: <https://mv.gov.cz/clanek/certifikaty-csca-cvca.aspx>, with the subpages 2026, 2021, 2016,
  2011 and eID. The site is labeled "archivní verze webu".
- Files: `https://mv.gov.cz/soubor/<name>-cer.aspx`, for cze-csca-{20260319, 20210323, 20160324,
  20110325}, the corresponding links, cze-eid-csca-20180618, cze-eid-csca-20230523 and
  cze-eid-csca-link-20230523.
- Fingerprints: SHA-1 published, 10 of 11 matching. For the 2023 eID link, the page mistakenly
  copies the fingerprint of the 2018 root. The link was verified cryptographically.
- Certificates: current CSCA_CZ 2026 (2026-03-19 → 2041-06-19), already in the store. **New and
  valid: the 3 eID_CSCA of the identity cards**:
  - `996fdda02cfb4b585fe710c7e08eae377cfd7c0e13b60bb23858893c3e6f5a0d` (2018 → 2058);
  - `b52e3ad60e35703e5824861c9c3ea4dea5a270246c7b8050086df74c7945e9dd` (2023 → 2063);
  - the link `f4729d706be1528a61a7963a0205bfa267fbdf4e684adbaef7bd43fe37c49cbd`.

  They do not appear in **any** Master List. Without them, Czech identity cards would
  probably yield "Unknown issuer" (not verified on a real card).
- Conditions: "*© 2026 Ministerstvo vnitra České republiky, všechna práva vyhrazena*" (all rights
  reserved).
- Status: **B**. Contact: webmaster@mv.gov.cz (the only address on the page).

**DE — Germany, BSI**
- Page:
  <https://www.bsi.bund.de/EN/Themen/Oeffentliche-Verwaltung/Elektronische-Identitaeten/Public-Key-Infrastrukturen/CSCA/Root_Cert_Germany/Zertifikate_der_CSCA-PKI.html>
- Files: `csca-germany_10-2024_self_signed.zip` and `csca-germany_10-2024_link.zip`.
- Fingerprints: SHA-256 published, matching (`2084aed7…ada8` and `5f28cb69…20bd`). Both
  certificates are already in the store. Unchanged as of 2026-10-06 (the current CSCA is still
  csca-germany 10-2024).
- Conditions: those of the BSI (section 2.2). Status: **A\***.
- Contact: csca-germany@bsi.bund.de, poststelle@bsi.bund.de

**DK — Denmark, Rigspolitiet**
- Page: <https://politi.dk/en/law-and-information/the-danish-country-signing-ca-csca>. The
  certificates are published as PEM in the HTML.
- Fingerprints: SHA-256 published, 9 of 9 matching.
- Certificates: current CSCA 2023, `84fbb2a1…8892`, already in the store. Two new, expired
  (2006 and 2009).
- A rollover is announced for September 2026; nothing was published as of 2026-09-25. As of
  2026-10-06, the page still announces the rollover for September 2026 but publishes nothing new (9
  certificates, current CSCA 2023), and the BSI ML of 2026-08-19 adds no Danish certificate.
  To be re-checked before the next release.
- Conditions: no notice found. Status: **A2**. Contact: POL-forretningsejerskaber@politi.dk
  (address taken from the certificate).

**EE — Estonia, PPA**
- Page: <https://pki.politsei.ee/>
- Files: `https://pki.politsei.ee/csca_Estonia_<year>.{cer,crt}` and the links
  `csca_Estonia_<a>-<b>-link.*`, 15 files, plus the Master List (section 2).
- Fingerprints: SHA-256 published, 15 of 15 matching. For three files, the published fingerprint is
  that of the raw PEM.
- Certificates: current CSCA_Estonia 2023, `f22e447e…fafc`, already in the store. The 8 new ones are
  expired.
- Conditions: none. Status: **A2** for the national certificates, **B** for the ML.
- Contact: pki@politsei.ee

**ES — Spain, Dirección General de la Policía**
- Page: <https://www.dnielectronico.es/PortalDNIe/PRF1_Cons02.action?pag=REF_1093&id_menu=55>
- Files: `https://www.dnielectronico.es/descargas/mrtd/CSCA_4_RAIZ.zip`, `CSCA_3_RAIZ.zip` and
  `CSCA_3_LINK.zip`. The CSCA 2 links return 404.
- Fingerprints: SHA-1 published, matching.
- Certificates: current CSCA SPAIN 4 (2022-05-18 → 2037-08-18),
  `1c2c6ff0…7209`, already in the store.
- Conditions: aviso legal, private use only (section 2.2). Status: **C**. Contact: no CSCA
  address published.

**FI — Finland, Poliisihallitus**
- Page: <https://poliisi.fi/en/csca>
- Files (HTTP): `http://proxy.fineid.fi/ca/cscafin{3..6}.crt` and `cslifin{3..6}.crt`.
- Fingerprints: SHA-256 (fin3 to fin6) and SHA-1 published, matching.
- Certificates: current cscafin6 (2025-05-14 → 2035-08-12), `b321524d…ab52`, already in the store.
  Three new, expired.
- Conditions, <https://poliisi.fi/en/information-about-the-website>: "*The use of the text materials
  found on this site, and providing links to it, is allowed in the context corresponding to generally
  accepted practice. […] The use of the material or images is prohibited for commercial, political
  […] or religious purposes in an inappropriate or immoral manner.*" The text covers the
  poliisi.fi site, not explicitly the files on proxy.fineid.fi.
- Status: **A2**. Contact: CSCA.Finland@govsec.fi

**FR — France, ANTS**
- Pages: <https://ants.gouv.fr/csca> (passport) and <https://ants.gouv.fr/home/csca-e-id> (CNIe).
- Files: ZIP archives containing a DER `.crt` and a fingerprint file.
- Fingerprints: SHA-1 published (passport), SHA-256 published (e-ID), matching. The 5 certificates
  are already embedded (see `docs/trust-store.md`).
- No ANTS Master List was found. The site filters bots; updates are done by
  hand.
- eID-FRANCE (`b33ea63b…fe7c`) does not appear in any Master List.
- Status: source already retained by decision D1 (A2).

**GB — United Kingdom, HM Passport Office (UKKPA)**
- Page: <https://hmpo.gov.uk/csca>
- Files: `https://hmpo.gov.uk/csca/certificate/<uuid>`, 14 DER certificates (GBR and Bermuda);
  CRL `https://hmpo.gov.uk/csca/GBR.crl`.
- Fingerprints: none published.
- Certificates: **new and valid**:
  - GBR_2026_Root (2026-08-24 → 2042-12-24),
    `737ce248b62d8b6dc1c9e3aaa8b937334406bac17bb37235c1c50281e6ad7edb`;
  - the GBR_2021-2026 link (2026-08-24 → 2038-01-17),
    `81d9f6bf3026a04e6e3847808cf094e34f9473a92ff66eb86a975f4c42055b5d`, whose signature by
    GBR_2021 was verified.

  They are in no Master List. **The British CSCA rollover is announced for
  September 26, 2026.** Two other new certificates, from 2005, are expired.
- As of 2026-10-06: page unchanged (same 14 certificates), GBR_2026 and its link are embedded
  (D26) and still missing from the BSI ML of 2026-08-19, which removed GBR_2010 (expired).
- As of 2026-10-12: the BSI ML of 2026-10-01 contains GBR_2026 and its link, byte for byte the
  embedded files, which keep the `NATIONAL` source (priority). Whether to remove them, as with the
  Greek certificates (D48), remains to be decided.
- Conditions (footer): "*All content is available under the Open Government Licence v3.0,
  except where otherwise stated © Crown copyright*".
- Status: **A1**. Attribution to provide: "Contains public sector information licensed under the
  Open Government Licence v3.0".
- Contact: document.technology@homeoffice.gov.uk
- Note: with no published fingerprint, integrity rests on TLS and on the link signed by the 2021 CSCA,
  already in the store.

**GR — Greece, passport and ID card (Hellenic Police)**
- Pages: <https://www.passport.gov.gr/en/e-passport/csca-epassport.html> and `…/csca-epassport-past.html`.
- Files: ZIPs served by `dl.php` (primary and backup keys, current and former).
- Fingerprints: SHA-1 published, matching.
- Certificates: current CSCA-HELLAS SN=11 (2026), `038dd011…b190`, already in the store. 16 new,
  8 of them valid: these are the **backup keys** of the 2021, 2022 and 2026 generations, which only
  sign DSs after a disaster. Three of them are also in the IT and SE MLs.
- Conditions, <https://www.passport.gov.gr/en/oroi-xrisis.html>: "*Όλες οι πληροφορίες […]
  διατίθενται στους χρήστες μόνον για πληροφοριακούς και σε καμία περίπτωση για εμπορικούς ή άλλους
  σκοπούς. Τροποποίηση του υλικού του διαδικτυακού τόπου ή χρήση για άλλους σκοπούς διώκεται
  ποινικά.*" The information is provided for informational purposes only, for no commercial
  or other use.
- Status: **C**. The wording is broad, for certificates that are nonetheless published for
  verification. Contact: csca@passport.gov.gr

**GR — Greece, residence permits (Ministry of Migration)**
- Page: <http://spoc.immigration.gov.gr/csca/> and `/previous/`.
- Files: `CSCAeRP-HELLAS003.cer` and `CSCAeRP-HELLAS003_link.cer`, as well as 002, 001 and HELLAS1.
- Fingerprints: SHA-1 published, matching.
- Certificates: **new and valid**: CSCAeRP-HELLAS 003 (2026-05-27 → 2041-05-26),
  `8f01ced4c95d9ee5cc6915e1151432e18fa1615ad6d8e61bb6ea1af025368e3e`, and its link
  `5bbdb09b12bc25f9373142409d9b52da806b764294baf4f028a2c13da15bdfc5`, in service since
  2026-06-19. Among the MLs, they are found only in the CH ML; as of 2026-10-06, they also
  appear in the BSI ML of 2026-08-19. Page unchanged on that date, next rollover announced
  for 2031.
- Conditions: no notice as of 2026-09-29. **As of 2026-10-06, the footer shows "© 2026
  All rights reserved"**: this is the generic notice which, under the grid above, classes a
  source as B. Status: **B** since 2026-10-06. The two national files were removed from the
  store on 2026-10-11 (D48); the certificates remain covered by the BSI ML (A\*). Contact:
  csca@migration.gov.gr

**HR — Croatia**: no official page found (searches on mup.gov.hr and akd.hr). The current
store contains 7 Croatian certificates. Status: not applicable.

**HU — Hungary**
- Pages: <https://www.nyilvantarto.hu/hu/csca_tanusitvany_listak_hu/> (download) and
  <https://kormany.hu/nyilvantartasok/biometrikus-utlevel/tajekoztato-a-csca-tanusitvanyrol>
  (fingerprints).
- Files: `https://www.nyilvantarto.hu/letoltes/CSCA/<name>`, 33 certificates: passport CSCA,
  ID-CSCA (eID), BAHCA, BMHCA and OIFCA (residence permits).
- Fingerprints: SHA-1 published, matching, except the "No. 4" entry, which describes a different certificate.
- Certificates: current CSCA HUNGARY 2025 (OU=MK), `1d250ea2…2e31`, already in the store. New
  valid ones: BMHCA-HUNGARY 1 (`c7a6d46b…7557`) and two links (`9d1fe758…`, `086e0d70…`).
- **The two Hungarian CSCAs from 2024 are not published by Hungary**:
  - `bb528b03f4eaa9d6db983b44882dfae4cc1d9ace91a8137cdeb410e8652f6317`, OU=Ministry of Interior;
  - `93381cc2d3d52dc5c57f6d361c050467124a53566a628d98f879c3e782f7cd38`, OU=Cabinet Office.

  They are found only in the IT, SE, NL, EE and CH MLs.
- Conditions, <https://kormany.hu/impresszum-kiado>: "*Minden jog fenntartva ©*" (all rights
  reserved).
- Status: **B**. Contact: csca-hungary@em.gov.hu

**IE — Ireland**: no page found (dfa.ie, ireland.ie). Status: not applicable. Contact: form
<https://www.ireland.ie/en/dfa/passports/contact-us/>.

**IS — Iceland, Þjóðskrá**
- Page:
  <https://www.skra.is/english/people/passport-and-id-card/passport/various-information-on-passports/security-of-icelandic-passports/>
- File: `https://www.skra.is/library/Samnyttar-skrar-/Vegabref/Iceland%20CSCA%20G1-G3%20all%20certificates%202023.zip`,
  7 certificates.
- Fingerprints: none published.
- Certificates: the valid certificates are already in the store; two new, expired.
- Conditions: no notice. Status: **A2**. Contact: csca@skra.is

**IT — Italy, Ministry of the Interior**
- Page: <https://csca-ita.interno.gov.it/html/csca.html>
- Files: `https://csca-ita.interno.gov.it/certificatiCSCA/CSCA05.cer` (and the previous CSCAs).
- Fingerprints: SHA-1 published `e8e99576…d263`, matching.
- Certificates: current CSCA05, `615bd40c…fa3a`, already in the store.
- Conditions: none (section 2.2). Status: **A2** for the national certificates, **B** for the ML.
- Contacts: epass.certauthority@interno.it (passport), epermits.certauthority@interno.it (residence
  permits).

**LI — Liechtenstein, Ausländer- und Passamt**
- Page:
  <https://www.llv.li/de/landesverwaltung/auslaender-und-passamt/reisepass/datenschutz-und-sicherheitsmassnahmen>
- Web application firewall (403), nothing was downloaded.
- The CSCA-LIECHTENSTEIN 2024 (`3458c10e…`) is missing from the store. It is found only in the CH ML.
- Status: undetermined. Contact: info@apa.llv.li (not verified).

**LT — Lithuania, ADIC**
- Pages: <https://www.csca.lt/> and <https://www.csca.lt/PreviousCSCA/>
- Files: `https://www.csca.lt/certif/CSCA_Lithuania_00{1..7}.cer` and the links
  `_00x_link_00y.cer`.
- Fingerprints: SHA-1 published, 12 of 12 matching. For 007, the value given as SHA-256 is
  actually the SHA-1.
- Certificates: current CSCA 007 (2025-01-31 → 2038-07-27), `06c6b8ac…a7f`, already in the store.
  New valid ones: three links (`65747b23…`, `9a89d2e8…`, `38b3a638…`). A fourth valid link (003, `1978e151…`) appears only in the IT ML.
- Conditions: "*© 2023 Identity Documents Personalisation Centre under the Ministry of the Interior.
  All rights reserved.*"
- Status: **B**. Contact: no email address displayed; postal mail: Žirmūnų g. 1D, LT-09239 Vilnius.

**LU — Luxembourg, INCERT**
- Page: <https://repository.incert.lu/>
- Files: `CSCA_LU_2023.crt`, `CSCA2023_link_from_CSCA2019.crt`, `CSCA_eID_card_2018.crt`,
  `CSCA_eTravelDocuments.crt`, `CSCAeTravel_link_from_CSCAePass.crt`, `CSCA_eResidencePermit_card.crt`,
  etc. (13 files).
- Fingerprints: none published.
- Certificates: current "Grand Duchy of Luxembourg CSCA" 2023, `2985657f…9fa2`, already in the
  store. New and valid: the eTravel link `5acb0c02…f95c`. As of 2026-10-06: same 13
  certificates, nothing new (the page also serves `ppkirca.cer` and `ppkiqca.cer`, INCERT's "PPKI1"
  CAs, which are not CSCAs).
- Conditions: none. Status: **A2**. Contact: csca@incert.lu (address taken from the certificate).

**LV — Latvia, PMLP**
- Page: <https://www.pmlp.gov.lv/en/latvian-csca>
- Files: `https://www.pmlp.gov.lv/en/media/<id>/download?attachment`, 14 ZIPs.
- Fingerprints: SHA-256 (010) and SHA-1 published, matching.
- Certificates: current CSCA Latvia 010 (2026-05-06 → 2039-08-06), `d68079b1…5a90`, already in the
  store.
- Conditions: "*© 2026 Pilsonības un migrācijas lietu pārvalde, all rights of published content
  reserved.*"
- Status: **B**. Contact: npkd@pmlp.gov.lv

**MC — Monaco**
- Page:
  <https://monservicepublic.gouv.mc/en/themes/nationality-and-residency/monegasque-nationality/identity-documents/country-signer-certificate-authority-csca>
- List MC1 to MC8 visible; the files are blocked by a web application firewall (403).
- Status: undetermined. Contact: Service des passeports, +377 98 98 82 18.

**MT — Malta, Identità / MECS**
- Page: <https://repository.csca.gov.mt/>; conditions: `/pages/terms-and-conditions`.
- Cloudflare (403), nothing was read. Five valid Maltese certificates are missing from the store; they
  are in the IT, SE and CH MLs.
- Status: undetermined.

**NL — Netherlands, RvIG**
- Page: <https://www.npkd.nl/>
- Files: CSCA NL serialNumber=7, self-signed and link.
- Fingerprints: SHA-256 published, matching (`411356d2…1a24`, `987268ed…05b1`).
- Certificates: already in the store.
- Conditions: none. Status: **A2** for the national certificates, **B** for the ML.
- Contact: info@rvig.nl

**NO — Norway, Politidirektoratet**
- Page: <https://www.politiet.no/en/english/csca/>
- File: `https://edit.politiet.no/globalassets/03-rad-og-forebygging/csca/csca_no.zip`, 7 DER.
- Fingerprints: SHA-1 published for CSCA 06 only, matching.
- Certificates: current CSCA_NO 06 (2022 → 2037), `4f8176db…27ba`, already in the store. Three
  new, expired. Next rollover announced for January 2027 (still announced as such as of
  2026-10-06, archive unchanged).
- Conditions: none found. Status: **A2**. Contact: pki.eDocuments@politiet.no

**PL — Poland, Ministry of Digital Affairs**
- Pages: <https://www.gov.pl/web/cyfryzacja/certificates-of-the-csca> and
  <https://www.gov.pl/web/cyfryzacja/urzad-certyfikacji-systemow-paszportowych-csca->
- Files: current `https://www.gov.pl/attachment/f4ab7e41-198d-4389-87ff-d08f025916b5`, link
  `https://www.gov.pl/attachment/c293c761-be4c-469c-a0fd-1fba70eab40f`, and history.
- **The page also publishes TEST certificates** (stored separately in `PL/TEST_ne_pas_embarquer/`):
  never embed them.
- Fingerprints: SHA-1 and SHA-256 published. Everything matches, except the SHA-256 of the current
  CSCA on the page (`55045cf7…`, whereas the file gives `adb554e6…c4f8`); its SHA-1 matches.
- Certificates: current CSCA Poland (2025-04-03 → 2039-04-03), already in the store.
- Conditions:
  - <https://www.gov.pl/web/cyfryzacja/ponowne-wykorzystywanie>: "*Informacje sektora publicznego
    udostępniane na stronie Ministerstwa Cyfryzacji są udostępniane w celu ponownego wykorzystywania
    na licencji Creative Commons Uznanie Autorstwa 3.0 Polska, o ile nie jest to stwierdzone
    inaczej.*" (CC BY 3.0 PL);
  - <https://www.gov.pl/web/gov/prawa-autorskie>: "*Korzystanie przez użytkowników z materiałów
    urzędowych opublikowanych w serwisie Gov.pl nie wymaga zgody Ministra Cyfryzacji.*"
- Status: **A1**. Contact: kancelaria@cyfra.gov.pl

**PT — Portugal, IRN**
- Page: <https://irn.justica.gov.pt/Documentos-de-Identificacao/Certificados-CSCA>, updated on
  2026-08-04. No file online.
- The current store contains 6 Portuguese certificates. Status: not applicable.

**RO — Romania, DGP**
- Page: <https://pasapoarte.mai.gov.ro/csca.html>
- Files: `https://pasapoarte.mai.gov.ro/certificate/<name>.der`, 11 certificates.
- Fingerprints: SHA-1 published, 11 of 11 matching.
- Certificates: current CSCA Romania 2023 (→ 2039), `859d155c…e1b3`, already in the store.
- Conditions: no notice. The page states: "*The information required to verify the
  authenticity of the following certificates is also available in authentic printed form upon
  request.*"
- Status: **A2**. Contact: npkd.dgp@mai.gov.ro

**SE — Sweden, Polismyndigheten**
- Pages:
  <https://polisen.se/en/services-and-permits/passport-and-national-id-card/swedish-certificates-and-crls/country-signing-certificate-authority-csca-sweden/>
  and <http://cert.polisen.se/CSCA/>
- File: <https://polisen.se/contentassets/359ae9679d4949cc869baaf6733fb29c/se_csca.zip>,
  11 certificates.
- Fingerprints: SHA-1 published, matching.
- Certificates: current "Swedish Country Signing CA v2" (2024), already in the store.
- Conditions: section 2.2 (contact required before reusing texts).
- Status: **B**. Contacts: csca.sweden@polisen.se, emrtd@polisen.se

**SI — Slovenia, SI-TRUST**
- Page:
  <https://www.si-trust.gov.si/sl/storitve-centra/digitalna-potrdila-za-biometricne-potne-listine/digitalna-potrdila-za-biometricne-potne-listine>
- Files: `cacert*.der` and their links (9 files).
- Fingerprints: SHA-256 and SHA-1 published for the self-signed certificates, matching.
- Certificates: current CSCA-Slovenia 5 (2024 → 2040), already in the store.
- Conditions: "© 2026 SI-TRUST" only. Status: **B**. Contact: csca-slovenia@gov.si

**SK — Slovakia, MV SR**
- Page: <https://www.minv.sk/?ePassports-certifikaty>
- Files: `https://www.minv.sk/swift_data/source/images/standardy_certifikaty_letectvo/*.rar`
  (6 RAR archives).
- Fingerprints: SHA-1 published, matching.
- Certificates: CSCA Slovakia 5 (2023 → 2038) and its link, already in the store.
- **The identity card CSCA, "The Slovak eTP eID CSCA"
  (`31c15a3aeb6f07e670b9c0e92be422684be992c5086e28f0be9685bf4dd10cca`, 2020 → 2040), is not published
  by Slovakia.** It is found only in the IT, SE and NL MLs.
- Conditions: "© 2026 Ministerstvo vnútra SR." Status: **B**.
- Contact: public@minv.slovensko.sk

**CH — Switzerland, fedpol / FOITT**
- Pages: <https://www.bit.admin.ch/en/sg-pki-csca-production-en> and
  <https://www.bit.admin.ch/en/sg-pki-csca-production-previous-en>
- Files: `https://www.pki.admin.ch/certificates/csca/prod/CSCA_Self-Signed_Certificate_<year>.der`
  and `CSCA_Link_Certificate_<a>-<b>.der`, 13 files.
- Fingerprints: SHA-256 published, 13 of 13 matching.
- Certificates: **new and valid**: CSCA-CHE 2026 (2026-09-08 → 2040-02-08),
  `843990bd13ba69083ab0afee994c4f7116d764a5be1d47d70c9da039f99587bc`, and its 2026-2029 link,
  `ee4b88a6…ae7f`. They are in no Master List, not even the Swiss ML.
- Conditions: admin.ch (section 2.2). Status: **C**.
- Contacts: eac-spoc.che@fedpol.admin.ch (address on the page, spelled out in words),
  info@bit.admin.ch

**AD — Andorra** (non-EU country included here for proximity)
- Page:
  <https://www.govern.ad/ca/ministeris-i-secretaries-d-estat/ministeri-de-justicia-i-interior/passaports>
- Files: `https://www.govern.ad/documents/d/guest/csca-1002?download=true` and
  `…/cscalink-1003?download=true` (PEM).
- Fingerprints: none published.
- Certificates: CSCA-AND 2025 and its link, new and valid (`be77d05f…475c`, `e076cb6d…ac69`).
- Conditions, <https://www.govern.ad/ca/avis-legal>: "*No es podran fer actes totals o parcials de
  reproducció, publicació, […] distribució […] dels webs sense el consentiment previ i per escrit del
  Govern.*"
- Status: **C**. Contact: interior@govern.ad

**SM — San Marino**: no page found. Three valid SM certificates, including 2026, are in the IT
ML.

**VA — Holy See**
- Page: <https://epass.vatican.va/crl/crl.html>
- Files: `https://epass.vatican.va/crl/linked/{VACSCA002.crt, Link_VACSCA001-VACSCA002.cer, VACSCA001.crt}`.
- Fingerprints: SHA-1 published, matching.
- Certificates: all already in the store.
- Conditions: none. Status: **A2**. Contact: uffprot@sds.va

**EU — European Union laissez-passer (Commission, JRC)**
- Page: <https://eu-csca.jrc.ec.europa.eu/>
- Files: `EU-LP-CSCA02_self_signed.pem`, `EU-LP-CSCA_link.pem` and `eu-lp-csca-self_signed_crt.zip`.
  The 2025 `.crt` files are blocked by the web application firewall (403).
- Fingerprints: SHA-256 published, matching. The 2025 ones are already in the store.
- Conditions, <https://commission.europa.eu/legal-notice_en>: "*Unless otherwise indicated […]
  content owned by the EU on this website is licensed under the Creative Commons Attribution 4.0
  International (CC BY 4.0) licence.*"
- Status: **A1**. Contact: eu-csca@ec.europa.eu

### 3.2 Europe outside the EU, and the Caucasus

| Country | Official page | Files | Fingerprints | New valid | Conditions | Status | Contact |
|---|---|---|---|---|---|---|---|
| RS Serbia | <https://crl.mup.gov.rs/CSCA.html> | `https://crl.mup.gov.rs/RS_CSCA_{1,4,25,53,78,101}.crt` | SHA-1, matching | 0 (current no. 101, 2024, already present) | "© Министарство унутрашњих послова Републике Србије" | B | ca@mup.gov.rs |
| BA Bosnia and Herzegovina | <https://www.iddeea.gov.ba/en/pki-system-for-digital-signing-of-travel-documents-and-e-ids/> | `…/wp-content/uploads/WEB/Dokumenti/CSCA_SertifikatBA.der`, `Link_SertifikatBA.der` | none | 0 | "Copyright 2026 © IDDEEA all rights reserved." | B | iddeea@iddeea.gov.ba |
| MK North Macedonia | <https://mvr.gov.mk/mk-MK/uslugi/csca> | `https://mvr.gov.mk/csca/ZIPs/*.zip` | SHA-256 and SHA-1, matching | 0 | "Сите права задржани" (all rights reserved) | B | csca-mk@moi.gov.mk |
| GE Georgia | <https://id.ge/en/tsp/csca/> | `https://id.ge/en/pki/GEO-CSCA-{1..6}.crt` | SHA-256, matching | **2**: no. 6 "G2", active (`277fcd17…de3c`, also in the IT, SE and NL MLs), and no. 5 (`2ef9e14c…c844`, in no ML); unchanged as of 2026-10-06 | no notice | **A2** | online@sda.gov.ge |
| ME, AL, MD, UA, AM, AZ, TR, XK, BY, RU, KZ, UZ | not found | — | — | via the MLs only | — | — | — |

### 3.3 Outside Europe

| Country | Official page | Files | Fingerprints | New valid | Conditions | Status | Contact |
|---|---|---|---|---|---|---|---|
| TW Taiwan | <https://www.boca.gov.tw/cp-243-4449-23912-2.html> | `https://www.boca.gov.tw/dl-4026-3f56bf17866c4ad8a9abee7dff57d84f.html` (CSCA 2024), etc.: 4 self-signed and 2 links | SHA-1 and SHA-256, 6 of 6 matching | 0 | <https://www.boca.gov.tw/np-359-2.html>: "*provided under "Open Government Data License, version 1.0 (OGDL-Taiwan-1.0)" […] The users are granted a perpetual, worldwide, license […] the attribution shall be provided*" | **A1** | CSCA-TWN@boca.gov.tw |
| MY Malaysia | <https://www.imi.gov.my/index.php/en/main-services/passport/the-malaysian-country-signing-ca-csca-en/> | `https://www.imi.gov.my/wp-content/uploads/2026/01/CurrentPublicKeyCert.der`, etc. (4) | SHA-256, 4 of 4 matching | 0 | "Copyright © 2021 \| Immigration Department of Malaysia"; no license | B | pbh@imi.gov.my |
| KW Kuwait | <https://epp.moi.gov.kw/csca/> | `CSCAKuwait.cer`, `1May2021Link.cer`, `1April2016.cer` | SHA-1 of the current CSCA, matching | 1 link (`e2ff8c3b…1d36`); rollover announced for March 2026, still not published as of 2026-10-06 (page last modified on 2023-04-13) | "جميع الحقوق محفوظة" (all rights reserved) | B | csca-kuwait@moi.gov.kw |
| JP Japan | <https://www.mofa.go.jp/ca/pss/page23e_000598.html> | 403 (Akamai), nothing was downloaded | published according to the snippets | ? | not read (the Government of Japan Standard Terms of Use v2.0, compatible with CC BY 4.0, is likely but not verified) | to be determined | — |
| AU Australia | <https://www.passports.gov.au/help/australian-country-signing-certificate-authority-csca> | connection blocked | SHA-1 of the link according to the snippets | ? | CC BY 4.0 on dfat.gov.au, application to passports.gov.au not verified | to be determined | csca-australia@dfat.gov.au |
| IL Israel | <https://www.gov.il/en/departments/guides/israeli_passport_and_id_certificates> | 403 (Cloudflare) | ? | ? | not read | to be determined | — |
| NG Nigeria | <https://immigration.gov.ng/pki-pkd/> | CSCA and ML announced, 404 | none | ? | "All Rights Reserved" | B/C | kn.nandap@immigration.gov.ng |

No official publication was found for US, CA, NZ, SG, KR, HK, MO, CN, TH, PH, ID, VN,
IN, AE, QA, SA, OM, BH, BR, AR, CL, MX, CO, PE, UY, ZA, KE, MA, DZ, TN, EG and the UN (laissez-passer).
These issuers are reachable only through the Master Lists.

## 4. Coverage by country

The table covers European countries and those with a national publication. "Base" counts the
valid certificates in the current store. "New valid" counts the valid certificates missing from
the store, each with the best available status.

| Country | Base | New valid (status) | Official sources | Best source for Sceau | Status |
|---|---|---|---|---|---|
| AT | 5 | 0 | national, ML DE/IT/SE/NL/CH/EE | BSI ML (already embedded) | A\* |
| BE | 16 | 0 | national passport and eID, ML | BSI ML + national | A\*/A1 |
| BG | 8 | 0 | national (blocked), ML | BSI ML | A\* |
| CH | 6 | **2 (C)**: CSCA 2026 and link | national, ML CH | national (fedpol) | C |
| CY | 10 | 0 | national, ML | BSI ML | A\* |
| CZ | 5 | **3 (B)**: identity card eID_CSCA | national only | national (MV ČR) | B |
| DE | 9 | 2 (B), serial 102 from 2016 | national, ML | BSI ML | A\* |
| DK | 7 | 0 (rollover expected) | national, ML | BSI ML + national | A\*/A2 |
| EE | 7 | 1 (B), a link | national, ML EE | BSI ML + national | A\*/A2 |
| ES | 5 | 1 (B) | national, ML ES | BSI ML | A\* |
| FI | 5 | 0 | national, ML | BSI ML | A\* |
| FR | 6 | 0 | ANTS, ML | ANTS (eID-FRANCE in no ML) | A2 |
| GB | 8 | **2 (A1)**: CSCA 2026 and link | national, ML DE | **national HMPO** | **A1** |
| GR | 12 | 2 (residence permits 2026, B since 2026-10-06, covered by the BSI ML), 3 (B), 5 (C, backup keys) | national passport, national residence permits, ML | BSI ML + IT ML | B |
| HR | 7 | 0 | ML only | BSI ML | A\* |
| HU | 21 | **8 (B)**, including the 2 CSCAs from 2024 | national, ML IT/SE/NL/EE/CH | IT ML (all but 2 links) + national | B |
| IE | 6 | 0 | ML only | BSI ML | A\* |
| IS | 5 | 0 | national, ML | BSI ML | A\* |
| IT | 8 | 1 (B), CA from 2011 | national, ML IT | BSI ML | A\* |
| LI | 5 | 1 (C), CSCA 2024 | national (blocked), ML CH | CH ML | C |
| LT | 6 | 4 (B), links | national, ML IT/CH | national (3) + IT ML (1) | B |
| LU | 12 | 1 (A2), eTravel link | national, ML | national INCERT | A2 |
| LV | 11 | 0 | national, ML | BSI ML | A\* |
| MT | 10 | 5 (B) | national (blocked), ML IT/SE/NL/CH | IT ML | B |
| NL | 10 | 0 | national, ML NL | BSI ML | A\* |
| NO | 4 | 0 | national, ML | BSI ML | A\* |
| PL | 9 | 0 | national (A1), ML | BSI ML + national | A\*/A1 |
| PT | 6 | 0 | ML only | BSI ML | A\* |
| RO | 9 | 0 | national, ML | BSI ML | A\* |
| SE | 5 | 0 | national, ML SE | BSI ML | A\* |
| SI | 5 | 0 | national, ML | BSI ML | A\* |
| SK | 5 | **1 (B)**: identity card eID CSCA | national (passport), ML IT/SE/NL | IT ML | B |
| AD | 5 | 2 (C), CSCA 2025 | national, ML CH | national | C |
| ME | 6 | 1 (C) | ML CH | CH ML | C |
| SM | 1 | 3 (B), including 2026 | ML IT/NL/CH | IT ML | B |
| GE | 4 | **2 (A2)** | national, ML IT/SE/NL | national (id.ge) | A2 |
| KW | 3 | 1 (B), link | national | national | B |
| TW, MY, EU, VA, RS, BA, MK | — | 0 | national, ML | BSI ML | A\* |
| TR | 10 | 2 (B) | ML IT/SE | IT ML | B |
| JP | 8 | 1 (B) | national (blocked), ML IT/SE/ES | IT ML | B |
| NZ | 5 | 3 (B) | ML IT/SE/NL/CH | IT ML | B |
| CN | 25 | 6 (B, including Macao) | ML IT/SE/NL | IT ML | B |
| MX | 1 | 2 (C), CSCA 2026 | ML CH | CH ML | C |
| CL | 7 | 1 (B) + 1 (C), 2026 | ML NL/CH | NL ML | B |

**24 issuers missing from the current store, present only in the IT, SE and NL MLs (B)**:
Angola, Dominican Republic, Egypt, Ethiopia, Ghana, Gambia, Indonesia, Iran, Jordan,
Kyrgyzstan, Saint Kitts and Nevis, North Korea, Kazakhstan, Mozambique, Nigeria, Pakistan,
Palestine, Sudan, Sierra Leone, Senegal, Syria, Togo, Order of Malta (XO) and Yemen. Two others,
the Maldives and "United Nations CSCA" 2012, are only in the ES ML and expired. Since the BSI ML
of 2026-08-19 (embedded on 2026-10-06), five of them are covered: Angola,
Indonesia, Saint Kitts and Nevis, Mozambique and Togo.

**Valid certificates that appear in no Master List** (national source only):
- GB 2026 and its link (A1);
- GE no. 5 (A2);
- CZ eID_CSCA, 3 certificates (B);
- CH 2026 and its link (C);
- KW, one link (B);
- LT, link 005→006 (B);
- FR eID-FRANCE (already embedded).

The full inventory (one certificate per line, with SHA-256, country, validity, sources and best
status) is in the catalog's `_notes/inventory.tsv`. It is regenerated with `python3 tools/inventory.py`.

## 5. Update procedure

To be done before each release, and as soon as a rollover is announced (GB on 2026-09-26, already
embedded; DK in September 2026, not published as of 2026-10-06; NO in January 2027; KW announced
for March 2026, not published as of 2026-10-06).

1. **Embedded Master Lists**:
   - download the file again from the official URL in section 2;
   - compare its SHA-256 with the embedded version, and with the file's published fingerprint if
     there is one (NL: SHA-256; SE: SHA-1);
   - verify the signature: `openssl cms -verify -noverify -inform DER -in f.ml -out /dev/null`;
   - verify the anchor: `python3 tools/verify_issued.py signer.der csca.der`, where `csca.der` is the
     country's CSCA downloaded from its official page, whose fingerprint is compared with the one
     published by the State. Never take the fingerprint to pin from the file itself.
2. **National certificates**: for each retained source, download the page again, note the
   new files, then run `python3 tools/certinfo.py files…`. Check:
   - the country `C`;
   - the type: self-signed, or link verified by the previous CSCA already present;
   - the validity;
   - the fingerprint published by the State, if there is one;
   - that the file is **not** a test certificate (the case of Poland).
3. Re-read the reuse conditions of each source (footer, legal notices). Any
   change alters the A/B/C status in this document.
4. Re-run `python3 tools/inventory.py` to measure the coverage gain. Update
   `docs/trust-store.md`: file, role, subject, validity, published fingerprint, observed SHA-256, date.
5. In the repository: copy the files into `core/src/main/resources/trust/`, list them in
   `trust/index.txt`, then run `./gradlew :core:test` (`TrustStoreFingerprintTest`).
6. Sites that filter bots (ANTS, BG, LI, MC, MT, JP, AU, IL, the JRC's `.crt` files): update
   by hand from a browser, with the same checks.

## 6. Contacts for permission

**B sources**: permission must be requested in writing (see `RECOMMENDATION.md` for the request
template).

| Source | Subject of the request | Contact |
|---|---|---|
| IT ML (Ministry of the Interior) | Verbatim redistribution of `IT_MasterListCSCA.ml` in a free APK | epass.certauthority@interno.it (passport); epermits.certauthority@interno.it (residence permits) |
| SE ML (Polismyndigheten) | Same for `SWE.ml` | emrtd@polisen.se; csca.sweden@polisen.se |
| NL ML (RvIG) | Same for `NL_MASTERLIST.mls` | info@rvig.nl |
| EE ML (PPA) | Same for `CSCA_masterlist.ml` | pki@politsei.ee |
| CZ eID_CSCA (MV ČR) | Redistribution of the 3 eID_CSCA certificates; correction of the 2023 link fingerprint | webmaster@mv.gov.cz (for lack of a CSCA address) |
| HU (Ministry of Energy, CSCA-HU) | Redistribution of the CSCAs; publication of the 2024 CSCAs | csca-hungary@em.gov.hu |
| SK (MV SR) | Publication of the "Slovak eTP eID CSCA", or agreement to take it from an ML | public@minv.slovensko.sk |
| LT (ADIC) | Redistribution of the link certificates | postal mail to ADIC, Žirmūnų g. 1D, Vilnius |
| LV (PMLP) | Redistribution of the CSCAs | npkd@pmlp.gov.lv |
| SI, RS, BA, MK | Redistribution of the CSCAs (all already covered by the BSI ML) | csca-slovenia@gov.si; ca@mup.gov.rs; iddeea@iddeea.gov.ba; csca-mk@moi.gov.mk |
| AT (BMI) | Scope of the non-commercial clause | csca@bmi.gv.at |
| MY, KW | Redistribution of the CSCAs | pbh@imi.gov.my; csca-kuwait@moi.gov.kw |
| DE (BSI) | Confirmation that verbatim redistribution in an APK is not a "Spiegelung" | csca-germany@bsi.bund.de; poststelle@bsi.bund.de |

**C sources**: prior written agreement is required.

| Source | Contact |
|---|---|
| CH (CSCA 2026, Swiss ML) | eac-spoc.che@fedpol.admin.ch |
| AD (CSCA 2025) | interior@govern.ad |
| GR passport (backup keys) | csca@passport.gov.gr |
| ES (ML 2022, CSCA) | Dirección General de la Policía (no CSCA address published) |
| ICAO PKD (ICAO Master List) | icao-pkd@icao.int |

**A sources** (attribution only):
- GB: OGL v3 notice;
- PL: CC BY 3.0 PL, "Źródło: Ministerstwo Cyfryzacji";
- TW: OGDL-Taiwan-1.0;
- EU: CC BY 4.0;
- BE passport: CC0, no attribution required.

The attributions can appear in `docs/trust-store.md` and on the "About" screen.
