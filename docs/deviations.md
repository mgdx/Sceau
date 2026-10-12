# Known deviations and per-country limits

This document lists the published or reported non-conformities of real documents with ICAO 9303, as well as limits specific to certain countries. For each case, it states what Sceau does with it today and what it could do. It complements `docs/trust-sources.md` §2.4 (public Deviation Lists), `docs/protocol.md` (sequence and verification rules) and decisions D14 and D15 in `docs/decisions.md`.

- **Date accessed**: 2026-10-04, for all pages and files cited, unless stated otherwise.
- **Method**: reading of the standards (ICAO 9303-12, BSI TR-03129-2, BSI TR-03127), of national publications and of the code of free readers (JMRTD 0.8.8, pymrtd, NFCPassportReader). Files were downloaded outside the repository, then analyzed (`openssl cms -verify -noverify`, `openssl asn1parse`, `asn1crypto`). No captcha and no terms of use were circumvented.
- **Level of evidence**: each case is marked **verified** (read in the primary source: standard, state publication, signed file, Sceau code) or **reported** (stated by a third party: code comment in another library, issue, commercial vendor). A reported case has not been reproduced on a real document.
- **Classification**:
  - **Codable**: can go into the registry of known deviations (hard-coded rules). The registry's current safeguard tolerates only a hash mismatch on DG11 or DG12, for documents designated by signed elements, and sets that data group aside. Never DG1, DG2, DG7, DG14 or DG15.
  - **To document**: a limit to explain to the user, or to flag on screen, without any tolerance.
  - **Already handled / out of scope**: Sceau already tolerates it (code cited) or is not concerned.

## 1. What a deviation is

### 1.1 ICAO Deviation List (9303-12 §10)

A *Deviation List* (DL) is published by a state to flag non-conforming documents, keys or certificates. Source: ICAO Doc 9303 part 12, 8th edition (2021), §3.1.7 and §10, <https://www.icao.int/sites/default/files/publications/DocSeries/9303_p12_cons_en.pdf> (verified).

- **Format**: CMS `SignedData` in DER, content type `id-icao-DeviationList` (`2.23.136.1.1.7`). The signer is a *Deviation List Signer* issued by the CSCA, with the extended key usage `id-icao-DeviationListSigningKey` (`2.23.136.1.1.8`). The `signingTime` attribute is mandatory.
- **Designation of documents** (`DeviationDocuments`), criteria combined with a logical AND, never with an OR:
  - `documentType`: document code from the MRZ (“P”, “C”…);
  - `dscIdentifier`: DS certificate, by issuer and serial number, by key identifier (SKI) or by fingerprint;
  - `issuingDate`: issuance period (`firstIssued`, `lastIssued`);
  - `documentNumbers`: list of document numbers.
- **Description** (`DeviationDescription`): optional free text, deviation type (OID), parameters, national use field.

| OID | 9303-12 name | Parameter |
|---|---|---|
| `2.23.136.1.1.7.1.1` | `id-Deviation-CertOrKey-DSSignature` | — |
| `2.23.136.1.1.7.1.2` | `id-Deviation-CertOrKey-DSEncoding` | `CertField` (certificate field or extension) |
| `2.23.136.1.1.7.1.3` | `id-Deviation-CertOrKey-CSCAEncoding` | `CertField` |
| `2.23.136.1.1.7.1.4` | `id-Deviation-CertOrKey-AAKeyCompromised` | — |
| `2.23.136.1.1.7.2.1` | `id-Deviation-LDS-DGMalformed` | `Datagroup` (1 to 16, 20 = SOD, 21 = COM) |
| `2.23.136.1.1.7.2.2` | `id-Deviation-LDS-DGHashWrong` | `Datagroup` |
| `2.23.136.1.1.7.2.3` | `id-Deviation-LDS-SODSignatureWrong` | — |
| `2.23.136.1.1.7.2.4` | `id-Deviation-LDS-COMInconsistent` | — |
| `2.23.136.1.1.7.3.1` | `id-Deviation-MRZ-WrongData` | `MRZField` |
| `2.23.136.1.1.7.3.2` | `id-Deviation-MRZ-WrongCheckDigit` | `MRZField` |
| `2.23.136.1.1.7.4` | `id-Deviation-Chip` | — |
| `2.23.136.1.1.7.5` | `id-Deviation-NationalUse` | — |

The standard describes the format but does not prescribe how the reader should handle it: it is up to the receiving state to decide what it tolerates.

### 1.2 BSI Defect List (TR-03129-2 §7)

Germany defines a similar format, the *Defect List* (DFL), which it uses between inspection systems. Source: BSI TR-03129-2 v1.4.1, chapter 7, <https://www.bsi.bund.de/SharedDocs/Downloads/EN/BSI/Publications/TechGuidelines/TG03129/BSI-TR-03129-2_V1_4_1.pdf> (verified).

- A defect is “a production error that affects a large number of documents”. It is **always identified by the DS certificate** (issuer and serial number, or SKI, plus the certificate fingerprint if these two means are ambiguous).
- Content type `id-DefectList` (`0.4.0.127.0.7.3.1.5`); signer with the extended key usage `id-electronicDefectListSigningKey`. A system that applies a DFL “SHALL” verify its signature and ignore it otherwise.
- The categories are finer than ICAO's. Those that concern what Sceau reads:

| BSI defect | Meaning | Counterpart in Sceau |
|---|---|---|
| `id-CertRevoked` | DS revoked (equivalent to a CRL) | “Certificate chain” or “SOD signature valid” line |
| `id-CertReplaced` | DS malformed, replacement certificate supplied | “SOD signature valid” line |
| `id-ChipAuthKeyRevoked`, `id-ActiveAuthKeyRevoked` | CA or AA keys compromised | CA, AA lines |
| `id-AuthenticationProtocolFailure` | CA, AA or PACE-CAM known to fail | CA, AA lines |
| `id-ValidityPeriodIncorrect` | DS or CSCA validity wrong | “DS certificate validity” line |
| `id-ePassportDGMalformed` | data groups badly encoded (list) | reading of the data groups |
| `id-SODInvalid` | SOD invalid (signature, encoding, hashes) | “SOD signature valid”, “Data group hashes” lines |
| `id-COMSODDiscrepancy` | data group lists of EF.COM and the SOD diverge | announced data groups |
| `id-sodWrongSignerIdentifier` | SOD `SignerIdentifier` wrong, corrected value supplied | DS lookup |
| `id-IssuingCountryDefect` | DS country missing or wrong (alpha-3 instead of alpha-2…), corrected value supplied | country consistency (audit V3) |
| `id-CardSecurityMalformed` | EF.CardSecurity badly encoded, corrected version supplied | PACE-CAM |

The DFL designates documents by their DS, whereas the ICAO DL can designate them by number and period. Both formats are model sources for Sceau's registry: a “DS certificate” criterion is available for any document whose Passive Authentication succeeds.

### 1.3 Accessible deviation sources

| Source | Access | Useful content |
|---|---|---|
| “eMRTD DL” collection of the ICAO PKD (version 10 as of 2026-10-04), <https://pkddownload.icao.int/> | captcha and terms of use that forbid redistribution (“*except for your own personal/ organizational non-commercial use*”) | **not consulted**: access requires a captcha, and the content could not be redistributed in the APK |
| Italian DL, <https://csca-ita.interno.gov.it/html/tddl.html> | open | entry [IT-1](#it-1-cie-30-incorrect-dg12-346275-cards) |
| Page of the European laissez-passer CSCA, <https://eu-csca.jrc.ec.europa.eu/> | open (text) | entry [EU-1](#eu-1-laissez-passer-deu-nationality) |
| Page of the Estonian CSCA, <https://pki.politsei.ee/> | open | “No DLs available” (`docs/trust-sources.md` §2.4) |
| BSI TR-03127 v1.40, annex D “Varianten” | open | variants of German cards, identified by the DS serial number (§3.1) |

No other public national Deviation List was found (web search, national pages already listed in `docs/trust-sources.md` §3).

## 2. Inventory

### 2.1 Table

| Ref. | Issuer | Non-conformity | Evidence | Classification |
|---|---|---|---|---|
| IT-1 | Italy, CIE 3.0 | wrong DG12 hash (date of issue frozen, “not valid for travel abroad” endorsement missing) | verified (signed DL, IPZS note) | **codable**, within the DG11/DG12 safeguard, with a condition on the “DS certificate validity” line |
| CN-1 | China, passports | SOD content type `id-data` instead of `id-icao-ldsSecurityObject` | reported (pymrtd) | **codable outside the safeguard**; to be considered only on a real case |
| FRBE-1 | France, Belgium, old passports | SOD content type `1.3.27.1.1.1` | reported (JMRTD) | out of scope in practice (expired documents) |
| DE-1 | Germany, identity cards issued before 2021-08-02 | ICAO application reserved for authenticated inspection systems (TA2) | verified (BSI TR-03127) | **handled, D36**: code `ACCESS_RESTRICTED` and dedicated message (§3.1) |
| DE-2 | Germany, eID card for EU citizens (eID-UB) | placeholder DG1 (“UB”, empty fields), DG2 = logo | verified (BSI TR-03127) | **handled, D36**: “no identity data” banner, photo hidden (§3.1) |
| EU-1 | EU, laissez-passer | nationality “DEU” instead of “D<<” in the MRZ | verified (EU CSCA page) | already handled (no effect) |
| GEN-1 | various, unnamed | wrongly computed hashes, truncated photos, chip MRZ different from the printed one | reported (E. Poll, Radboud) | to document |
| GEN-2 | no public case | EF.COM and SOD diverge (`COMInconsistent`) | type defined by 9303-12 and TR-03129 | to document; improvement to study (§4.4) |
| UK-1, HU-1 | United Kingdom, Hungary, residence permits | verification impossible for a commercial vendor | reported (Inverid) | to document |
| BM-1 | Bermuda (UKKPA) | “BMU” CSCA cross-certified with the British CSCA | verified (BSI Master List) | to verify on a real document |
| H-1 | Romania, Albania, United Kingdom, Slovenia | certificate `C` attribute in lowercase | verified (BSI Master List) | already handled |
| H-2 | Hong Kong, Macao | CSCA `C=CN`, issuing state “CHN” | verified (BSI Master List) | already handled |
| H-3 | Portugal, Cameroon, Italy, Turkey, Luxembourg… | CSCAs and links with a non-conforming profile (pathLen, basicConstraints, keyUsage) | verified (Sceau code), reported (pymrtd, ZeroPass) | already handled |
| H-4 | Moldova, China, Tanzania, Philippines, France | DS “document type list” extension badly encoded | reported (pymrtd) | out of scope |
| H-5 | France, 2013-2014 passports | DG14 without `ChipAuthenticationInfo` | reported (JMRTD) | already handled |
| H-6 | unnamed | AA with an EC key without `ActiveAuthenticationInfo` | verified (D15) | already handled |
| H-7 | unnamed | DG2 missing | verified (D14) | already handled |
| H-8 | France or Belgium; Bosnia | DG11/DG12 dates in BCD (4 bytes); date-time in 7 bytes | reported (JMRTD) | already handled |
| H-9 | United Kingdom (?) | EF.CardAccess without short file identifier (SFI) | reported (JMRTD) | already handled |
| H-10 | various | X.500 names encoded as PrintableString or UTF8String | reported (E. Poll) | probably already handled, to be confirmed by a test |
| H-11 | China (issue) | DG14 missing, CA impossible | reported (passport-reader) | already handled |
| H-12 | Germany (Master List of 2019-09-25), Hungary | Master List signed directly by the CSCA; Master List signature unverifiable | reported (pymrtd, ZeroPass) | out of scope |

Total: 1 case codable within the safeguard, 1 case codable outside the safeguard, 2 cases handled outside the registry (DE-1, DE-2, D36), 5 cases to document (GEN-1, GEN-2, UK-1, HU-1, BM-1), 13 cases already handled or out of scope (EU-1, FRBE-1, H-1 to H-12, H-10 still to be confirmed).

### 2.2 Entries

#### IT-1 CIE 3.0: incorrect DG12 (346,275 cards)

- **Issuer**: Italy, Ministry of the Interior; personalization by the IPZS (*Istituto Poligrafico e Zecca dello Stato*).
- **Sources** (verified):
  - TDDL page: <https://csca-ita.interno.gov.it/html/tddl.html>;
  - signed DL: <https://csca-ita.interno.gov.it/certificatiCSCA/IT_CIE_DeviationList.zip> → `TDDL-CIE-Signed-20180530.der`; valid CMS signature, `signingTime` 2018-05-30 11:02:37 UTC, signer `CN=ITDeviationListSigner, serialNumber=001` issued by `CN=Italian Country Signer CA` (CSCA03, see `docs/trust-sources.md` §2.4);
  - IPZS note (Italian): <https://csca-ita.interno.gov.it/certificatiCSCA/CIE3.0-NotaAnomaliaDG12ITA.pdf>; English version announced on the TDDL page (`CIE3.0-NotaAnomaliaDG12ENG.pdf`, not read); signed PDF list of the numbers (`ItalianIdentityCardListof346.275cardswithwrongDG12-signed.pdf`, not read).
- **Content of the DL** (decoded): two `Deviation`s, each with `documentType` = “C”, `issuingDate` from 2017-10-01 00:00:00 to 2018-02-05 23:59:59.999 UTC, a `documentNumbers` list and a type `id-Deviation-LDS-DGHashWrong` (`2.23.136.1.1.7.2.2`) with parameter 12 (DG12). **No `dscIdentifier`.**
  - 299,400 numbers: “*THE HASH OF THE DG12 INSIDE THE EFSOD DOES NOT CORRESPOND TO THE ONE COMPUTED READING THE DG12 FROM THE CHIP. ISSUANCE DATE INSIDE THE DG12 READ FROM THE CARD IS SET TO 05th DECEMBER 2015 FOR ALL THE DOCUMENTS.*”
  - 46,875 numbers: same text, plus “*ENDORSMENTS/OBSERVATIONS DOES NOT CONTAIN THE STRING ‘NON VALIDA PER L’ESPATRIO’. THE DOCUMENTS CANNOT BE USED TO GO ABROAD*”.
  - Numbers in the format `CA` + 5 digits + 2 letters, from `CA00000AL` to `CA99999AQ`, all distinct (346,275).
- **IPZS note**: DG12 of part of the CIEs issued from **June 2017 to February 2018**; the date of issue is always “20151205”; for cards not valid for travel abroad, field 04 *Endorsement(s)/Observation(s)* is missing. The note states that the printed data and the other structures of the chip are compliant. The period in the note (June 2017) is wider than that of the DL (October 2017): the DL, which is signed, is authoritative.
- **Current effect in Sceau**:
  - “Data group hashes” line `FAILED` (DG12 read and different from the SOD), hence the “Failed” verdict;
  - and, independently, the “DS certificate validity” line: the date of issue is taken from DG12 first (`ReadingSession` passes `data.dg12?.dateOfIssue` to `PassiveAuthenticator.checkValidity`). The wrong date of 2015-12-05 very probably falls before the start of validity of a DS used in late 2017: the line would then be `FAILED` as well (inference, not verified on a real card).
- **Classification: codable, within the DG11/DG12 safeguard.**
  - Criterion on signed elements: DG1 verified by Passive Authentication, with issuing state “ITA”, document code starting with “C”, document number in the DL list; optionally, SOD `signingTime` within the DL period. The DL does not designate any DS: the “DS certificate” criterion is not available without a real card to record the DSs concerned.
  - Effect: DG12 hash mismatch tolerated, DG12 set aside (neither displayed nor used). **The DG12 date of issue must not be used for the “DS certificate validity” line either**: fall back on the SOD `signingTime`, as if DG12 were missing. Otherwise the tolerance leaves the verdict at “Failed”.
  - Cost: the raw list is 3.1 MB (346,275 × 9 characters), 834 KB gzipped, 246 KB with xz; a bit field (19 two-letter suffixes × 100,000 numbers) is 238 KB before compression. The signed DL itself weighs 620 KB zipped: embedding it as is and verifying its signature at load time is another option.
  - Variant without a list, to be decided: criterion “ITA + code C + `signingTime` within the DL period”. It also tolerates CIEs from the period that are not in the list. The effect is limited to setting DG12 aside, so nothing false is displayed, but a forged DG12 on a genuine card from the period would no longer be flagged.
  - Limit to display: for 46,875 of these cards, the chip does not say that the card is not valid for travel abroad. Sceau cannot tell these cards apart without the list. The message that accompanies the tolerance should point to the endorsement printed on the back.

#### CN-1 Chinese passports: SOD content type `id-data`

- **Source** (reported): pymrtd (ZeroPass), `src/pymrtd/ef/sod.py`, comment “*Some Chinese passports has this OID instead of id_mrtd_ldsSecurityObject*” (`1.2.840.113549.1.7.1`), <https://github.com/ZeroPass/pymrtd>. No serial number or period is given.
- **Current effect in Sceau**: `ParsedSod.parse` requires `id-icao-ldsSecurityObject` (audit V10, `PassiveAuthenticator.kt`); otherwise `SOD_MALFORMED` and Passive Authentication fails.
- **Classification: codable outside the safeguard.** Possible criterion: DS certificate (country CN, issuer and serial numbers recorded on real documents). Effect: accept `id-data` as content type for those DSs only. The risk is low, since a DS signs only SODs. But the rule affects the verification of the whole SOD, hence DG1, DG2, DG14 and DG15, and not an optional data group. To be coded only on a real document and an identified DS.

#### FRBE-1 Old French and Belgian passports: content type `1.3.27.1.1.1`

- **Source** (reported): JMRTD 0.8.8, `org/jmrtd/lds/SODFile.java`, constant `ICAO_LDS_SOD_ALT_OID`: “*Seen in live French and Belgian MRTDs*”; JMRTD also accepts a test OID from the Dutch printer SDU (`2.16.528.1.1006.1.20.1`).
- **Current effect in Sceau**: `SOD_MALFORMED`, like CN-1.
- **Classification: out of scope in practice.** These documents date from the first generations: French electronic passports from 2006 onward, valid for 10 years, and Belgian passports valid for 5 to 7 years. They have all expired. An expired document is not rejected by Sceau, but this case does not justify broadening SOD verification.

#### DE-1, DE-2 German cards

See §3.1.

#### EU-1 Laissez-passer: DEU nationality

- **Source** (verified): <https://eu-csca.jrc.ec.europa.eu/>. Laissez-passer issued to staff of German nationality before 2022-05-05 carry “DEU” in the nationality field of the MRZ; those issued since then carry “D<<”, in accordance with 9303-3.
- **Effect in Sceau**: none. Nationality is not part of any check: country consistency (audit V3, `IcaoCountries`) concerns the **issuing state** of DG1 (“EUE”, an organization code: only the CSCA and the DS are compared, `C=EU`). “DEU” and “D” both designate Germany.
- **Classification: already handled.**

#### GEN-1 Generic defects without a named country

- **Source** (reported): E. Poll, *e-passports*, Radboud Universiteit, slide “*Technical things that go wrong: defects*”, <https://www.cs.ru.nl/E.Poll/ufrj/C_ePassport.pdf>: “*Wrongly calculated hashes ; Encoding of certificate fields: printable string ↔ UTF8 ; Wrong identifiers in certificates ; Printed MRZ data not matching the one stored in the chip ; Truncated photos*”.
- **Classification: to document.** Without a designated document or DS, nothing can be coded. A wrong DG1 or DG2 hash must remain a failure: that is precisely what Passive Authentication must flag.

#### GEN-2 EF.COM and SOD diverge

- **Sources** (verified): types `id-Deviation-LDS-COMInconsistent` (9303-12) and `id-COMSODDiscrepancy` (TR-03129-2 §7.3.2.3). No public case found.
- **Current effect in Sceau**: `DocumentReader` reads DG11, DG12, DG14 and DG15 if they are announced in EF.COM **or** in the SOD. A data group announced by EF.COM alone and supplied by the chip counts as non-matching (`checkHashes`: “A data group that was read but is missing from the SOD”), hence “Failed”.
- **Classification: to document.** See §4.4 for the possible improvement.

#### UK-1, HU-1 British residence permits and some Hungarian ones

- **Source** (reported): Inverid, *ReadID Supported Identity Documents Summary*, version 2, 2023-07-14, §4, via <https://www.notaris.nl/files/Documenten/overzicht-readid-documenten-samenvatting.pdf>: “*ReadID can also verify all of them, except residence permits from UK, and part of Hungarian residence permits*”. The cause is not given.
- **Expected effect in Sceau**: probably “Unknown issuer” (CSCA missing from the trust store), without certainty.
- **Classification: to document**; to be confirmed on a real document.

#### BM-1 Bermuda: cross-certified “BMU” CSCA

- **Source** (verified in the built-in trust store): the BSI Master List contains the self-signed CSCA `CN=BMU CSCA, O=UKKPA, C=BM` (2024-01-17 → 2040-05-17) and two certificates cross-certified with `CN=Country Signing Authority, O=UKKPA, C=GB`.
- **Effect in Sceau**: `CertificateChains` prefers a self-signed CSCA at each level: a DS `C=BM` is anchored on the CSCA `C=BM`. Country consistency then requires an issuing state “BMU” in DG1. Yet Inverid reports that since 2015 the United Kingdom has issued the passports of its overseas territories with issuing state “GBR” (same source as UK-1, note 11). If a passport signed by a DS `C=BM` carried “GBR”, Sceau would conclude there is a country inconsistency (“Failed”).
- **Classification: to verify on a real document.** Nothing to code as long as no case is observed.

#### H-1 to H-12 Cases already handled or out of scope

| Ref. | Finding | Why Sceau is not affected |
|---|---|---|
| H-1 | 13 certificates of the BSI Master List have a lowercase `C` attribute (`ro`, `al`, `gb`, `si`), two of them with only the issuer in lowercase (observed in the built-in file) | `IcaoCountries.consistent` compares in uppercase |
| H-2 | Hong Kong and Macao CSCAs in `C=CN`; their passports carry issuing state “CHN” (observed in the built-in file) | CHN → CN: consistent |
| H-3 | CSCA without `pathLenConstraint`, Portuguese link with `pathLen` 2 (pymrtd, `pki/x509.py`); 7 links with `cA=FALSE` (Cameroon, Italy, Portugal, Turkey ×2, Luxembourg ×2) | `pathLen` not checked; profile tolerated if the extension is missing, non-conforming links discarded with no effect (`CertificateChains`, audit V8) |
| H-4 | DS “document type list” extension badly encoded (pymrtd: Moldovan DS `02B27F8C79935F02`, Chinese, Tanzanian, Philippine passports; OID `2.23.136.1.1.4` instead of `2.23.136.1.1.6.2` on a French DS) | Sceau does not read this extension |
| H-5 | DG14 without `ChipAuthenticationInfo` (JMRTD, `EACCAProtocol.inferChipAuthenticationOIDfromPublicKeyOID`: “*This seems to work for French passports (generation 2013, 2014)*”) | `ChipVerifier` passes a null OID; JMRTD infers it from the key |
| H-6 | AA with an EC key without an announced hash algorithm | D15: SHA-1 to SHA-512 tried |
| H-7 | DG2 missing | D14: reading without a photo |
| H-8 | DG11/DG12 dates in BCD on 4 bytes (JMRTD, `AdditionalDetailDataGroup.readFullDate`: “*Either France or Belgium uses this encoding for dates*”); personalization date-time on 7 bytes (“*Bosnia apparently uses this encoding*”) | JMRTD returns the hexadecimal string, i.e. 8 digits YYYYMMDD, which `Dates.parsePast` reads; the date-time is not used |
| H-9 | EF.CardAccess without SFI (JMRTD, `PassportService`: “*Some passports (UK?) don't support SFI for EF.CardAccess*”) | Sceau never uses the SFI (`docs/protocol.md` §2.2) |
| H-10 | X.500 names in PrintableString or UTF8String depending on the certificate | BouncyCastle (`X500Name`, BC style) and `X500Principal` compare canonical forms; no test verifies it yet |
| H-11 | “Chip authentication failed” on a Chinese passport: `6A82` on EF.CardAccess and DG14 (tananaev/passport-reader, issue 54, 2023-08-30, <https://github.com/tananaev/passport-reader/issues/54>) | no DG14: CA line `NOT_AVAILABLE`, not `FAILED` |
| H-12 | German Master List of 2019-09-25 signed directly by the CSCA (pymrtd); signature of the Hungarian Master List unverifiable (ZeroPass, <https://github.com/ZeroPass/Port-documntation-and-tools/blob/master/eMRTD%20Non-Conformancy%20Report.md>) | does not concern the reading of a document; an imported Master List whose signature is invalid is rejected (SPEC §7.2) |

## 3. Per-country limits

### 3.1 Germany

Main source (verified): BSI TR-03127 *eID-Dokumente basierend auf Extended Access Control*, version 1.40 of 2021-10-06, <https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/Publikationen/TechnischeRichtlinien/TR03127/BSI-TR-03127_1-40.pdf>. For comparison, version 1.21 of 2018-05-02, <https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/Publikationen/TechnischeRichtlinien/TR03127/BSI-TR-03127.pdf>.

German cards (identity card, *Personalausweis*, PA; electronic residence permit, eAT; eID card for EU citizens, eID-UB) carry three applications (§3.4.1):

- **biometric application** (AID `A0 00 00 02 47 10 01`, the ICAO application);
- **eID application** (AID `E8 07 04 00 7F 00 07 03 02`);
- **signature application** (eSign).

#### What the ICAO application exposes (table 1 of v1.40)

| File | Content | Access |
|---|---|---|
| EF.COM | list of data groups (“its use is discouraged, because it is not signed”) | BIS/EIS |
| EF.SOD | hashes of DG1, DG2, DG3, DS certificate | IS; BIS/EIS |
| EF.CVCA | Terminal Authentication trust points | BIS/EIS |
| DG1 | MRZ (eID-UB: placeholder MRZ, see below) | IS; BIS/EIS |
| DG2 | photo, identical to the printed photo (eID-UB: static eID logo) | IS; BIS/EIS |
| DG3 | two fingerprints (mandatory in the PA since 2021-08-02, optional before) | IS + DG3 right; EIS + DG3 right |
| DG14 | `ChipAuthenticationInfo`, `ChipAuthenticationPublicKeyInfo`, `TerminalAuthenticationInfo`, `PACEInfo` | BIS/EIS |

- BIS: basic inspection system, *Standard Inspection Procedure* (BAC or PACE), with CA1 if it supports it. This is Sceau's case.
- EIS: *Advanced Inspection Procedure* (BAC or PACE, CA1, TA1). IS: *General Authentication Procedure* (PACE, TA2, CA2/CA3). TA1 and TA2 require a terminal certificate issued under the BSI CVCA.
- **DG4 to DG13, DG15 and DG16 are not present** (§3.4.2). So no DG11, no DG12 and no DG15.
- **No Active Authentication**: “*Die Aktive Authentisierung nach [ICAO 9303], Part 11, wird aus Datenschutzgründen nicht implementiert*” (§3.3).
- EF.CardSecurity (MF): readable after PACE in v1.40 (table 3). v1.21 required PACE + TA2. It contains `ChipAuthenticationPublicKeyInfo` and a DS signature. TR-03127 does not say whether PACE-CAM is offered: not verified.
- The EF.SOD row lists only the hashes of DG1, DG2 and DG3, whereas DG14 is present. If DG14 were announced by EF.COM without appearing in the SOD, Sceau would read it and conclude “Failed” (GEN-2). This is probably a simplification in the table, since 9303 requires the hash of every data group present; to be verified on a real card.

#### The decisive point: the identity card's date of issue

- Note 7 of §3.4.2, and note 16 of §4.2: access through the *Standard/Advanced Inspection Procedure* exists “*nicht für vor dem 02.08.2021 ausgegebene Personalausweise*”.
- Annex D: “*Zugriff auf die Biometrieanwendung des Personalausweises nur mit General Authentication Procedure*” for PAs whose **DS serial number is ≤ 198**, issued up to 2021-08-01. v1.21 (2018) already reserved all PA data for the *General Authentication Procedure* (§3.1.2 and §3.2).
- Reported, and consistent: Signicat (Inverid), 2025-11-17, <https://www.signicat.com/blog/which-european-countries-have-identity-cards-with-nfc>: “*Germany started to issue ICAO compliant identity cards since August 2021 […] Before that, the identity cards already had an NFC chip, but not an ICAO 9303 compliant one.*”
- This timeline follows Regulation (EU) 2019/1157, applicable from 2021-08-02 (art. 16), which requires a contactless chip compliant with 9303 with photo and fingerprints (art. 3), <https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32019R1157>.

#### What Sceau can read and verify

| Document | Possible access | Data that can be displayed | Verification |
|---|---|---|---|
| **PA issued since 2021-08-02** | PACE with the CAN (or the three-line MRZ), then EF.COM, EF.SOD, DG1, DG2, DG14 | MRZ from DG1 (surname, given names, number, nationality, date of birth, sex, expiry) and photo | PA against the German CSCA (present in the BSI Master List), then CA1 via DG14: expected verdict “Authentic”. No DG12, so the date of issue is taken from the SOD `signingTime` or estimated (PA valid for 6 years before age 24, 10 years afterwards: an estimate outside the period gives `NOT_AVAILABLE`, never `FAILED`, D15) |
| **PA issued before 2021-08-02** | PACE with the CAN succeeds, but the ICAO application and EF.SOD require TA2 | **no data** | none. Expected response `6982` (*security status not satisfied*) on selection or on reading EF.SOD (not verified on a real card). **Handled, D36**: code `ACCESS_RESTRICTED-<step>-<element>` and message “chip reserved for authorized officials”, without “Try again”; previously “Unexpected error” (`UNEXPECTED-…-6982`) |
| **eAT** (residence permit) | BAC (only for permits issued before 2019-11-01, note 6 of §3.3) or PACE; EF.COM, EF.SOD, DG1, DG2, DG14 | MRZ and photo | PA then CA1: “Authentic” expected. The permit's annotations (*Nebenbestimmungen*) are in the eID application (DG19, DG20), out of reach |
| **eID-UB** (since 2020-11-01) | like the eAT | DG1: document code “UB”, state “D”, “<” in all other fields; DG2: eID logo | the signature may be valid, but the document carries **no identity data** in the ICAO application. **Handled, D36**: empty MRZ read without error (verified on the simulated chip), “DS certificate validity” cannot be evaluated for lack of a date; the Result screen shows a “no identity data” banner instead of the empty fields, and hides the DG2 logo |
| **Passport** | BAC or PACE; DG1, DG2, DG14; DG3 under EAC | MRZ and photo | PA then CA via DG14. Reported and not verified in a primary source for the passport: no AA, no DG11/DG12 |

#### What remains out of reach

- **DG3** (fingerprints): TA1 or TA2 with a government inspection certificate. Sceau never reads DG3 (SPEC §1).
- **eID application** (table 2 of v1.40): place of birth (DG9), address (DG17), birth name (DG13), doctoral degree (DG7), artistic or religious name (DG6), residence permit annotations (DG19, DG20)… Reading it requires PACE with the eID PIN (or the CAN with the *CAN allowed* right for on-site reading), then TA2 with an **authorization certificate** (*Berechtigungszertifikat*) issued under the BSI CVCA, then CA2/CA3. Its data groups are not signed by the DS: only the hashes of DG4, DG5, DG8 and DG9 appear in EF.ChipSecurity, which is itself reserved for privileged terminals. Free, offline software cannot obtain this certificate: this data is out of scope.
- **Annex D variants** (PrivilegedTerminalInfo missing, eIDSecurityInfo missing, DG10 or DG13 missing, *power down* required between two authentications): they concern the eID application or TA2, with no effect on what Sceau reads.

### 3.2 Other notable cases

- **EU identity cards issued before 2021-08-02**: they may have no chip, or a chip without an ICAO application. In June 2023, Inverid noted chipless cards in Bulgaria, Greece, Portugal, Liechtenstein and Switzerland (reported, same source as UK-1, table 2). Regulation 2019/1157 (art. 5) ends the validity of these cards at their expiry, at the latest on 2031-08-03, or on 2026-08-03 for those without a functional MRZ (verified). In Sceau: no NFC detection, or `NOT_ICAO`.
- **DG3 and DG4**: protected by EAC in all documents that carry them; never read (SPEC §1, `docs/protocol.md` §2.4).
- **British residence permits and some Hungarian ones**: verification impossible according to Inverid (UK-1, HU-1).
- **European laissez-passer**: the “DEU” nationality on permits issued before 2022-05-05 has no effect (EU-1).

## 4. Recommendations for Sceau

### 4.1 Codable cases, by priority

| Priority | Case | Criterion (signed elements) | Proposed effect | Safeguard |
|---|---|---|---|---|
| 1 | IT-1, CIE 3.0 | Passive Authentication succeeded (chain, signature, hashes of DG1/DG2/DG14/DG15); DG1: state “ITA”, code “C…”, number in the DL list; optionally SOD `signingTime` within [2017-10-01, 2018-02-05] | DG12 hash mismatch tolerated, DG12 set aside; **DG12 date of issue ignored for the “DS certificate validity” line**; message pointing to the printed “not valid for travel abroad” endorsement | within the DG11/DG12 safeguard |
| 2 | DE-1, German identity card from before 2021-08-02 | after a successful PACE, `6982` on selecting the ICAO application or on reading EF.SOD | dedicated message (“document that reserves its chip for officials”, see §4.3) instead of “Unexpected error”; **handled, D36**, with a new code `ACCESS_RESTRICTED` | outside the registry: no tolerance, only a message |
| 3 | DE-2, eID-UB | DG1 verified: code “UB”, state “D” | “card without identity data in the chip” banner; no empty field displayed as data; **handled, D36** (photo hidden) | outside the registry: display only |
| 4 | CN-1, SOD in `id-data` | specific DS certificate recorded on a real document | accept this content type for this DS only | **outside the safeguard**: the rule concerns the whole SOD, hence DG1 and DG2. To be coded only with evidence |

For the registry structure: identify each rule by the 9303-12 OID of the deviation (`2.23.136.1.1.7.2.2` and parameter 12 for IT-1). The rule can then be linked to a signed DL if Sceau ever imports one. The “DS certificate” criterion (TR-03129) is preferable as soon as it is known: it is signed by the CSCA, short, and does not require a list of numbers.

### 4.2 Risks to keep in mind

- Any rule that touched DG1, DG2, DG14, DG15 or the SOD signature would let a forger who meets the criterion pass off unsigned data. The criterion would then be the only protection.
- A rule based on document numbers is safe only if DG1 is itself verified (correct DG1 hash in a valid SOD): the number is read from DG1, never from the key entered.
- A tolerance that sets a data group aside must leave no trace of it anywhere else: display, date of issue (IT-1), verdict computation.

### 4.3 Proposed text to flag the German limit

To be reused in the README (supported documents section) and in the About screen (string to add in `strings_about.xml` and its translations). These files are not modified by this document.

French version:

> **Cartes d'identité allemandes.** Sceau lit les cartes d'identité allemandes délivrées depuis le 2 août 2021 : il affiche les données de la bande MRZ et la photo, puis vérifie la signature et la puce. Les cartes délivrées avant cette date réservent leur puce aux autorités habilitées : Sceau ne peut pas les lire. L'adresse et le lieu de naissance ne sont jamais lisibles : ils sont réservés à la fonction d'identification en ligne, qui exige le code PIN de la carte et un certificat délivré par l'État allemand.

English version:

> **German identity cards.** Sceau reads German identity cards issued since 2 August 2021: it shows the MRZ data and the photo, then verifies the signature and the chip. Cards issued before that date reserve their chip to authorised authorities, so Sceau cannot read them. The address and place of birth can never be read: they are reserved to the online identification function, which requires the card's PIN and a certificate issued by the German state.

### 4.4 Other avenues, outside the registry

- **Announced data groups** (GEN-2): request DG11, DG12, DG14 and DG15 only if they appear in the SOD (signed), and not in EF.COM (unsigned, and discouraged by the BSI). A data group present only in EF.COM could not be verified anyway. To study: this choice changes `DocumentReader` and the V1 check for missing data groups.
- **H-10 regression test**: a SOD whose `SignerIdentifier` encodes the issuer as UTF8String while the DS certificate encodes it as PrintableString, to be built with `:testchip`.
- **To observe on real documents** before any code: recent German identity card (DG14 in the SOD, PACE-CAM or not), eID-UB (behavior with an empty MRZ), Bermuda passport (issuing state and DS country).
