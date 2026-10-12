# Embedded trust store

This document lists the files in `core/src/main/resources/trust/`, their provenance and their
fingerprints. It is authoritative: the `TrustStoreFingerprintTest` test recomputes the SHA-256 of each
embedded file and compares it with the table below. It also checks that the table,
`trust/index.txt` and the contents of the directory list exactly the same files. An
undocumented substitution, addition or removal fails the build.

## Embedded files

Stable format, read by the test: one row per file, file name between backticks in the
first column, observed SHA-256 (lowercase hexadecimal) between backticks in the last
column. Do not change the order or the number of columns without adapting the test.

| File | Role | Subject | Validity (UTC) | Fingerprint published by the source | Observed SHA-256 |
|---|---|---|---|---|---|
| `ants-csca-2010.der` | Passport (CSCA) | `C=FR, O=Gouv, CN=CSCA-FRANCE` | 2010-12-09 → 2026-03-09 | SHA-1 `07dfdbe60862e9ef79b470b8e9f2be1541059c34` | `8d4d8939b9b31c0b45907bf0622f791fe90cf20e85cbbc2d368ee143b4a453ab` |
| `ants-csca-2015.der` | Passport (CSCA) | `C=FR, O=Gouv, CN=CSCA-FRANCE` | 2015-09-04 → 2030-12-04 | SHA-1 `3720e6f7c248922568bba78661cde2a51c6e22df` | `5ba9a2069f34cd93fab9dcb39c6a7a7be62141f8bcb608aff815167c813ecf92` |
| `ants-csca-2020.der` | Passport (CSCA) | `C=FR, O=Gouv, CN=CSCA-FRANCE` | 2020-05-26 → 2035-08-26 | SHA-1 `32b98cdbd85255c07503bfd96049038b241bfffe` | `28df42a7a0ed1b20f994cc96999060619e095b09764159703438ec60b88ee856` |
| `ants-csca-2025.der` | Passport (CSCA) | `C=FR, O=Gouv, CN=CSCA-FRANCE` | 2025-02-19 → 2040-05-19 | SHA-1 `59c8be053778295654ed32672a8045ef41405b44` | `d628b5100ddcbed8f3e5fa05e53b6b80bebb1e6264a12583319ef955c91d9349` |
| `ants-csca-eid-2021.der` | e-ID, CNIe (CSCA) | `C=FR, O=Gouv, CN=eID-FRANCE` | 2021-02-15 → 2036-05-15 | SHA-256 `b33ea63b9be01082d98071a29111757c72257eba80d7205d21fa35436c29fe7c` | `b33ea63b9be01082d98071a29111757c72257eba80d7205d21fa35436c29fe7c` |
| `de-bsi-master-list.ml` | CSCA Master List (BSI, Germany) | signer `C=DE, O=bund, OU=bsi, serialNumber=0039, CN=CSCA Master List Signer` | signed on 2026-10-01; signer valid 2024-10-17 → 2028-10-17 | none for the file (see “Embedded Master List”) | `2bc0f28b35d83f2fc2d1495b1c715add358dfb9ba343e9788bc27a19ced13a9f` |
| `gb-csca-2026.der` | GB passport (CSCA GBR_2026_Root) | `C=GB, O=UKKPA, CN=Country Signing Authority` | 2026-08-24 → 2042-12-24 | none | `737ce248b62d8b6dc1c9e3aaa8b937334406bac17bb37235c1c50281e6ad7edb` |
| `gb-csca-link-2021-2026.der` | GB passport (link GBR_2021-2026, signed by GBR_2021) | `C=GB, O=UKKPA, CN=Country Signing Authority` | 2026-08-24 → 2038-01-17 | none | `81d9f6bf3026a04e6e3847808cf094e34f9473a92ff66eb86a975f4c42055b5d` |
| `ge-csca-5.der` | GE travel documents (CSCA no. 5) | `C=GE, O=Ministry of Justice of Georgia, OU=Public Service Development Agency, CN=GEO Country Signing CA` | 2025-09-09 → 2040-12-04 | SHA-256 `2ef9e14c6155c315b8d7681168774a063e6fd2639f51f7d5c904ef862933c844` | `2ef9e14c6155c315b8d7681168774a063e6fd2639f51f7d5c904ef862933c844` |
| `ge-csca-6.der` | GE travel documents (CSCA no. 6, “G2”, active) | `C=GE, O=Ministry of Justice of Georgia, OU=Public Service Development Agency, CN=GEO Country Signing CA G2` | 2025-09-10 → 2040-12-05 | SHA-256 `277fcd17b53b3e2f20cfee967db499189bf23e0ef0a9e1040c5a24c8b09cde3c` | `277fcd17b53b3e2f20cfee967db499189bf23e0ef0a9e1040c5a24c8b09cde3c` |
| `lu-csca-etravel-link.der` | LU travel documents (link CSCA ePassport → CSCA eTravel Documents) | `C=LU, O=Grand-Duchy of Luxembourg Ministry of Foreign Affairs, CN=Grand-Duchy of Luxembourg CSCA eTravel Documents` | 2018-11-02 → 2029-01-12 | none | `5acb0c0264ad18912ad80139b318ab507d00dc77a86ac4eb832d3510ff6ef95c` |

The five ANTS certificates are self-signed root CSCAs (RSA 4096, `sha256WithRSAEncryption`).
The ANTS archives contain no link certificate. The 2010 passport CSCA has
expired (March 2026) but is still required: it signed DSs whose passports, valid for
ten years, are still in circulation. The validity period is checked at the date of
issue of the document, not at the date of reading.

All observed fingerprints are equal to the published fingerprints, recomputed on the
files extracted from the archives. The `.crt` files in the archives were already DER-encoded;
they are copied without conversion and only renamed.

## Provenance

| File | Source archive | File in the archive | Source page | Downloaded on |
|---|---|---|---|---|
| `ants-csca-2010.der` | `CSCA-FRANCE_2010.zip` | `CSCA-FRANCE_2010.crt`, fingerprint in `CSCSA-FRANCE_2010_fingerprint.txt` | ANTS CSCA page | 2026-09-25 |
| `ants-csca-2015.der` | `CSCA-FRANCE_2015.zip` | `CSCA-FRANCE_2015.crt`, fingerprint in `CSCA-FRANCE_2015_fingerprint.txt` | ANTS CSCA page | 2026-09-25 |
| `ants-csca-2020.der` | `CSCA-FRANCE_2020.zip` | `CSCA-FRANCE_2020.crt`, fingerprint in `CSCA-FRANCE_2020_fingerprint.txt` | ANTS CSCA page | 2026-09-25 |
| `ants-csca-2025.der` | `csca-france_2025.zip` | `CSCA-FRANCE_2025.crt`, fingerprint in `CSCA-FRANCE_2025_fingerprint.txt` | ANTS CSCA page | 2026-09-25 |
| `ants-csca-eid-2021.der` | `Certificat_CSCA_PROD_EID.zip` | `eID-FRANCE.crt` (accompanied by an identical `eID-FRANCE.pem`), fingerprint in `Fingerprint_SHA256.txt` | ANTS CSCA e-ID page | 2026-09-25 |
| `de-bsi-master-list.ml` | `GermanMasterList.zip` | `DE_ML_2026-10-01-11-50-52.ml`, redistributed unchanged | <https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.html> | 2026-10-12 |
| `gb-csca-2026.der` | none (direct DER file) | <https://hmpo.gov.uk/csca/certificate/12fd5015-cf50-47b0-a1fb-ca1c3cc0f0ee> | <https://hmpo.gov.uk/csca> | 2026-09-29 |
| `gb-csca-link-2021-2026.der` | none (direct DER file) | <https://hmpo.gov.uk/csca/certificate/3a083ccd-5e5d-4e7c-be90-bd4218e335af> | <https://hmpo.gov.uk/csca> | 2026-09-29 |
| `ge-csca-5.der` | none (direct DER file) | <https://id.ge/en/pki/GEO-CSCA-5.crt> | <https://id.ge/en/tsp/csca/> | 2026-09-29 |
| `ge-csca-6.der` | none (direct DER file) | <https://id.ge/en/pki/GEO-CSCA-6.crt> | <https://id.ge/en/tsp/csca/> | 2026-09-29 |
| `lu-csca-etravel-link.der` | none (direct DER file) | <https://repository.incert.lu/CSCAeTravel_link_from_CSCAePass.crt> | <https://repository.incert.lu/> | 2026-09-29 |

The archives were downloaded by the maintainer from the official ANTS pages
(URLs confirmed by the maintainer on 2026-09-25):

- CSCA page (passports): <https://ants.gouv.fr/csca>;
- CSCA e-ID page (identity cards): <https://ants.gouv.fr/home/csca-e-id>.

The ANTS website filters bots: the archives are downloaded by hand from these pages,
then `scripts/update-trust-store.sh --ants-dir` compares the fingerprints published in each
archive with the recomputed ones (see “Update procedure”).

## National publications

Five certificates published by a State for its **own** documents supplement the embedded
Master List (decision D26). They were missing from the BSI list when they were added, they
are still valid, and their source has
status A in `docs/trust-sources.md`: open license (A1) or official publication with no
restriction on reuse (A2). The loader recognizes them by their name: a `.der` file that
does not start with `ants-` gets the `NATIONAL` source, displayed as “National publication”.

| Country | Authority | Status | Terms and attribution |
|---|---|---|---|
| GB | HM Passport Office (UKKPA) | A1 | Open Government Licence v3.0. Attribution displayed in “About”: “Contains public sector information licensed under the Open Government Licence v3.0”. |
| GE | Public Service Development Agency, Ministry of Justice | A2 | No mention of reuse on the website. |
| LU | INCERT | A2 | No mention of reuse on the website. |

Checks carried out on 2026-09-29 on each file, before embedding it:

- download from the State's official page, over HTTPS;
- published fingerprint, where one exists, equal to the recomputed fingerprint (GE: SHA-256). The United Kingdom and Luxembourg do not publish any. Their integrity relies on TLS and
  on the signature of the links;
- signature verified: self-signature of the three root CSCAs; GB link signed by GBR_2021 and LU link
  by “Grand-Duchy of Luxembourg CSCA ePassport”
  (`CSCA_ePassport_card_1.crt` from the same repository). The GB key is an EC P-384 key with explicit
  curve parameters, which OpenSSL 3 refuses to verify in `verify` mode: the verification is
  done with the Python `cryptography` library, and redone by `TrustStoreTest`
  (`nationalLinksAreSignedByAnEmbeddedAnchor`), which verifies each link with the key of a CSCA
  in the store;
- extensions: `basicConstraints` cA=TRUE and `keyUsage` keyCertSign present on all five.

Re-verification on 2026-10-06 (release 0.9): the pages were downloaded again, no new
certificate or link is published there, and the embedded files are still served there
identically (equal SHA-256).

Removal on 2026-10-11 (decision D48): the two Greek residence permit certificates
(CSCAeRP-HELLAS 003 and its link 002 → 003, `gr-csca-erp-003.der` and
`gr-csca-erp-link-002-003.der`) are no longer embedded as national publications. Since
2026-10-06, the footer of their website reads “© 2026 All rights reserved” (status B), and they
are in the embedded BSI Master List, which now provides them: coverage of Greek
residence permits is unchanged.

The two Georgian CSCAs no. 5 and no. 6 share the same key (same SKI) under two names. No. 6 is
also in the Italian, Swedish and Dutch Master Lists, which are not embedded.

## Master List

The SPEC (§7.1) provides for an embedded Master List. The code supports it: a `.ml` file
listed in `trust/index.txt` is parsed at load time. Its CMS signature is verified, and its
signer must be issued by a certificate whose fingerprint is pinned in the code (see
“Signer anchoring”). Otherwise loading fails (`InvalidMasterListException`). Its
certificates get the `EMBEDDED_MASTER_LIST` source. In case of duplicates, the priority order
is ANTS, then national publications, then embedded Master List, then anchored imported
Master List, then single imported certificate, then unanchored imported Master List (D44).

### Embedded Master List: the BSI German Master List

`de-bsi-master-list.ml` is the German Master List published by the BSI (Bundesamt für Sicherheit
in der Informationstechnik). It is not the ICAO Master List cited by the SPEC (§7.1),
hence the file name.

- Page: <https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.html>
- Archive: <https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.zip?__blob=publicationFile&v=112>,
  observed SHA-256 of the archive `a0d2b448d8a2ef383c66991897c970ab2f0494173d33ef0d55195127dae08a6a`.
- File: `DE_ML_2026-10-01-11-50-52.ml` extracted from the archive, 934,771 bytes, signed on
  2026-10-01, redistributed **unchanged** and only renamed. The BSI does not publish a fingerprint
  for the file: integrity relies on the CMS signature and on the signer anchoring
  described below.
- Contents: 609 CSCA certificates and link certificates from 117 issuers, all readable by
  BouncyCastle, including 172 EC keys with explicit curve parameters and two negative
  serial numbers. The 3 French certificates already provided by ANTS keep the `ANTS` source.
- Downloaded on 2026-10-12.

#### History

| Date | Version | Change |
|---|---|---|
| 2026-09-25 | `DE_ML_2026-05-28-08-28-45.ml` (archive `v=108`), SHA-256 `e036f8c989193b38cf19493bb2c957bfa2385b35a680bf03300515cad7526dd0` | First embedded Master List: 588 certificates, 112 issuers. |
| 2026-10-06 | `DE_ML_2026-08-19-10-28-54.ml` (archive `v=109`), SHA-256 `b0dfa486ef25ab2d23f62af7214ace207444c384eb5b7f8edbaf9ab42f7831ea` | Re-verification for release 0.9. Same signer (serialNumber=0039), still issued by csca-germany 10-2024, whose pinned fingerprint is still the one published by the BSI. 608 certificates (+30, −10), 117 issuers (+5: AO, ID, KN, MZ, TG; none removed). Additions: AO, BY ×2, CL ×2, CO, GR ×2 (residence permits 003 and link, already embedded as a national publication), ID, IQ ×2, KN ×7, ME, MX ×2, MZ, RU ×2, SA ×2, TG, TJ, UZ ×2; all have `basicConstraints` cA=TRUE and `keyUsage` keyCertSign. Removals: ten CSCAs that expired between June and August 2026 (AT ×3, CZ ×2, GB 2010, QA, SG, TR ×2). No new certificate for DK or NO. |
| 2026-10-12 | `DE_ML_2026-10-01-11-50-52.ml` (archive `v=112`), SHA-256 `2bc0f28b35d83f2fc2d1495b1c715add358dfb9ba343e9788bc27a19ced13a9f` | Checks before release 1.0.0. Same signer (serialNumber=0039), same pinned anchor. 609 certificates (+4, −3), 117 issuers (none added or removed). Additions: GB 2026 and its link GBR_2021-2026 (byte for byte the national files `gb-csca-2026.der` and `gb-csca-link-2021-2026.der`, which keep priority), HU ×2 (cross certificates `BMHCA-HUNGARY 1` and `OIFCA-HUNGARY 2`); all have `basicConstraints` cA=TRUE and `keyUsage` keyCertSign. Removals: three certificates that expired between August and September 2026 (BE `CSCAEC_BE_2`, IT link `Italian Country Signer CA`, LI `CSCA-LIECHTENSTEIN`). |

Reuse terms set by the BSI: use, including commercial use, is permitted; no
advertising use; nothing may suggest cooperation with the BSI or
its endorsement. Sceau redistributes the file without modification, cites it as the source
and claims no connection with the BSI.

### Signer anchoring

Verifying a Master List with only the signer certificate it carries proves its
integrity, not its origin. For an **embedded** list, `:core` additionally requires the
signer to be signed by one of the pinned certificates
(`TrustStoreLoader.EMBEDDED_MASTER_LIST_ANCHORS`). This pinned certificate is looked up among the
certificates of the list and in the `certificates` block of the CMS:

- Germany CSCA (`C=DE, O=bund, OU=bsi, CN=csca-germany`), SHA-256 fingerprint published by the
  BSI: `2084aed7a991b3158e63ad750d3dc38bc6c9dcf5958f28d1162f49884e91ada8`;
- its link certificate, SHA-256 fingerprint published by the BSI:
  `5f28cb692fb346ba2d23674c8778f2810c24a35869e5b517de3abd67d5db20bd`.

Anchoring failure: `InvalidMasterListException("UNTRUSTED_SIGNER")`.

### User import

Between two releases, the user can import a more recent Master List from the
“Trust store” screen (“Import a Master List” button, SPEC §5.4). Accepted are
CMS files `.ml`, `.der` and `.p7b`, for example the German Master List published by the BSI or
a Master List obtained from a national authority. The CMS signature is verified with the
signer certificate contained in the file, whose SHA-256 fingerprint is shown to the
user before the import. An invalid list is rejected.

This signature proves nothing about the origin of the list: anyone can create a
signer named “CSCA XY”. The signer is therefore linked, where possible, to an already known CSCA
(audit V34, D44): it must be issued by an embedded certificate (ANTS, national publication,
embedded Master List) or by a certificate imported on its own by the user, with the same issuer name and a signature
verified with its key (`TrustStores.isMasterListAnchored`, same rule at load time). The CSCAs
of another imported Master List, and those of the list itself, do not count.

- **Anchored signer**: the certificates get the `IMPORTED_MASTER_LIST` source and are displayed as
  “Imported”. This is the case for national lists signed under a CSCA already present in the BSI
  list.
- **Unanchored signer**: the import preview shows a warning (“This list’s signer isn’t
  linked to any known CSCA […]”) and the “Import” button stays disabled
  until the user has ticked “I got this file directly from the authority that published
  it”. The certificates get the `IMPORTED_MASTER_LIST_UNANCHORED` source, last in
  priority, and are displayed as “Imported list, unverified origin”, in the store as in the
  result details; the verdict carries the note “Verified using a certificate you
  imported”. Importing the signer's issuing CSCA on its own first, after comparing its
  fingerprint, makes the list anchored.

The signer's extended key usage (EKU) is checked only if present: it must then contain
`id-icao-cscaMasterListSigningKey` (2.23.136.1.1.3) or `anyExtendedKeyUsage`. It is not
required, since some real Master Lists do not carry it; the signer of the BSI list does
carry it (critical extension).

Rejection codes (`InvalidMasterListException.code`, without personal data):

| Code | Cause |
|---|---|
| `NOT_CMS` | The file is not a readable CMS SignedData. |
| `BAD_CONTENT_TYPE` | The signed content is not of type `id-icao-cscaMasterList` (2.23.136.1.1.2). |
| `BAD_CONTENT` | Missing content (detached signature) or invalid `CscaMasterList` structure. |
| `NO_SIGNER` | No signer, or signer certificate missing from the CMS. |
| `MULTIPLE_SIGNERS` | More than one SignerInfo: a Master List has only one signer (audit V22). |
| `TOO_MANY_CERTIFICATES` | More than 2,000 certificates in the list or in the `certificates` block of the CMS (audit V22, D22). |
| `BAD_SIGNATURE` | The CMS signature does not verify. |
| `SIGNER_NOT_VALID` | The signer certificate was not valid at the declared signing date. |
| `UNSUPPORTED_ALGORITHM` | Signature algorithm unknown to BouncyCastle. |
| `UNTRUSTED_SIGNER` | Embedded list only: signer not issued by a pinned certificate. |
| `BAD_SIGNER_USAGE` | The signer carries an extended key usage without `id-icao-cscaMasterListSigningKey` or `anyExtendedKeyUsage`, or an unreadable one (audit V34). |

The parsing cost is bounded whatever the file (D22, audit V22): a single signer, and
at most 2,000 certificates (`TrustStores.MAX_MASTER_LIST_CERTIFICATES`), counted before being
read. The largest real Master List found (Sweden) has 646, the embedded BSI one 609;
the embedded list goes through the same check. Parsing for the preview stops if
the user leaves the screen.

An unreadable certificate inside a valid list is ignored and does not cause the list
to be rejected. The certificates are read by BouncyCastle, which accepts EC keys with explicit
curve parameters and negative serial numbers, which occur in real lists.

### Importing a single certificate (D33)

The same button, “Import a Master List or a certificate”, also accepts a self-signed CSCA
certificate or a standalone link certificate, as some States publish them on their website
(`.der`, `.cer`, `.crt` or `.pem`, a single certificate per file, 64 KiB at most). The file
type is detected automatically: a file of 64 KiB at most is first read as a
certificate; if it is not one, it is treated as a Master List.

Unlike a Master List, **no State's signature vouches for a standalone certificate**:
the user is the one who decides to trust it. The confirmation dialog says so, and shows the
subject, the country, the validity and the SHA-256 fingerprint of the certificate, to be compared with the one published
by the State. `TrustStores.parseCertificate` (`:core`) checks:

| Code (`InvalidCertificateException.code`) | Cause |
|---|---|
| `UNREADABLE` | Not exactly one X.509 certificate (DER without trailing bytes, or PEM with a single `CERTIFICATE` block and no other block), or unreadable public key. |
| `NOT_CA` | `basicConstraints` missing or without cA=TRUE, or `keyUsage` present without keyCertSign. |
| `BAD_SIGNATURE` | Certificate that presents itself as self-signed (subject equal to the issuer, Authority Key Identifier missing or equal to the Subject Key Identifier) whose self-signature does not verify. |

The signature of a link certificate cannot be verified at import time (it is made by the
old key of the CSCA): it is verified when the chain is built, as for any certificate in the
store, which only anchors a chain on a self-signed CSCA. Importing a link on its own therefore adds
nothing as long as the CSCA that signed it is not known.

Storage: `filesDir/trust/<SHA-256 of the DER>.der`, in DER (a PEM is converted before writing),
next to the `<SHA-256>.ml` files of the Master Lists; atomic write, certificate re-verified just before
writing. At load time, an imported certificate that has become invalid is ignored. Imported certificates
get the `IMPORTED_CERTIFICATE` source, distinct from `IMPORTED_MASTER_LIST`, and
come after all other sources: a certificate already present (ANTS, national
publication, embedded or imported Master List) keeps its original source. They are displayed as
“Imported certificate”.

Limits (`ImportLimits`, D22 and D33): 100 imported certificates at most, 64 KiB per file; they
do not count toward the Master List limits (10 lists, 40 MB in total).

### Duplicates on import (D33)

A file that would add nothing is rejected as soon as it is previewed, without a confirmation dialog and without
writing anything (`DuplicateImportException`, logic in `trust/ImportDuplicates`):

| File chosen | Detection | Message |
|---|---|---|
| Certificate already imported on its own, in DER or in PEM | SHA-256 of the DER = name of an imported `<fingerprint>.der` | “This certificate has already been imported.” |
| Certificate already in the store | SHA-256 of the DER among `TrustStore.anchors` | “This certificate is already in the trust store (source: …). There is nothing to import.”, with the source ANTS, national publication, built-in Master List or imported Master List |
| Master List already imported | SHA-256 of the file = name of an imported `<fingerprint>.ml` | “This Master List has already been imported.” |
| Master List identical to the embedded Master List | SHA-256 of the file = that of the `.ml` resource of `:core` | “This Master List is identical to the one built into the app. There is nothing to import.” |

A different Master List can still be imported even if all its certificates are already known (it
may be more recent); the confirmation dialog shows how many of its certificates are
missing from the current store, for example “12 new certificates out of 590”.

### Imported items and deletion

The “Trust store” screen lists each imported item: Master List (signer, signing
date, number of certificates) or certificate (subject, country, validity, fingerprint). Each one can be
deleted individually, after confirmation; the “Delete imported certificates” button still deletes
everything, Master Lists and certificates. An imported file that has become unreadable stays listed so that it
can be deleted. The identifier of an item is its file name; deletion rejects
any identifier that is not `<64 lowercase hexadecimal digits>.ml` or `.der`.

The list of imported items is read independently of the merged store (audit V22): it
is displayed, and each item can be deleted, while the store is loading as well as after a failure.
X.500 subjects coming from imported files (signer of a Master List, subject of a
certificate), in the confirmation dialogs as in the lists, are sanitized before
display (audit V23): a single line, without control or bidirectional formatting characters,
bounded length with an ellipsis.

## Update procedure (at each release)

### Script

`scripts/update-trust-store.sh` automates the checks of the procedure below. It is
a developer tool: the app never downloads anything. It requires `bash`, `curl`,
`openssl` 3, `sha256sum`, `sha1sum`, `unzip` and the GNU coreutils. The source URLs are
grouped at the top of the script.

```bash
# 1. Download by hand the .zip archives from the two ANTS pages into a folder
#    (the site blocks curl), for example ~/ants/.
# 2. Report, without writing anything (default mode):
scripts/update-trust-store.sh --ants-dir ~/ants
# 3. If it reports differences and all checks pass:
scripts/update-trust-store.sh --ants-dir ~/ants --apply
```

- **ANTS**: for each certificate in each archive, fingerprint recomputed and compared with
  the one in the archive's fingerprint file (SHA-1 for passports, SHA-256 for e-ID),
  PEM to DER conversion if needed, display of the subject, the issuer and the dates. The subject
  must be `CN=CSCA-FRANCE` or `CN=eID-FRANCE`, which sets the name `ants-csca-<year>.der` or
  `ants-csca-eid-<year>.der`. Without `--ants-dir`, this part is skipped and the script
  says so.
- **BSI**: download of the German Master List (or `--bsi-zip` for a local archive),
  CMS signature verified with the included signer, content of type `id-icao-cscaMasterList`,
  signer valid at the signing date and signed by a certificate whose SHA-256 appears
  in `EMBEDDED_MASTER_LIST_ANCHORS` (fingerprints read from `TrustStoreLoader.kt`; the script
  fails if the format of this constant changes). Shows the signing date and the number
  of certificates.
- **Comparison**: nothing is written if the files are identical to the embedded files.
  Only with `--apply` are new or modified files copied into
  `core/src/main/resources/trust/` and the new names added to `trust/index.txt`. No
  file is ever removed, in particular no expired CSCA.
- The script prints the rows to copy into the “Embedded files” and
  “Provenance” tables; this document still has to be updated by hand (steps 6 and 7), then
  `./gradlew :core:test` (step 8) confirms consistency.
- Any failed check gives a non-zero exit status, and no file is written.

### Steps

The manual procedure remains the reference for what the script does.

1. Download the CSCA and CSCA e-ID archives from the ANTS pages cited above.
2. For each archive, extract the certificate and the fingerprint file, then check that
   the recomputed fingerprint matches the published fingerprint:
   `sha1sum file.crt` (passports, SHA-1 published) or `sha256sum eID-FRANCE.crt` (e-ID).
3. Check the format and the contents:
   `openssl x509 -inform DER -in file.crt -noout -subject -issuer -dates`. A PEM file
   is converted with `openssl x509 -in file.pem -outform DER -out file.der`.
4. Copy the certificate into `core/src/main/resources/trust/` under the name
   `ants-csca-<year>.der` (passport) or `ants-csca-eid-<year>.der` (e-ID), the year being
   that of the start of validity. Never remove an expired CSCA while documents it
   signed are in circulation.
5. Add the file name to `trust/index.txt`.
6. Add the file's row to both tables above: subject, validity, published fingerprint
   with its algorithm, observed SHA-256 (`sha256sum`), download date.
7. For an embedded Master List: download the file again, check the CMS signature and
   the signer anchoring (the loading test fails otherwise), check the published fingerprints
   of the pinned certificates, then update the fingerprints in this document. If the
   issuing country changes CSCA, update `EMBEDDED_MASTER_LIST_ANCHORS` from the
   fingerprints published by that authority, never from the file itself.
8. National publications (section of the same name): the script does not cover them. For each
   source, follow the procedure in `docs/trust-sources.md` §5: download the page again, note
   the new certificates, check country, self-signature or link signed by a CSCA already
   present, validity and published fingerprint. Name the file `<country>-csca-<designation>.der`,
   never with the `ants-` prefix. Add it only if the source keeps status A, and update
   the expected list in `TrustStoreTest.nationalSha256`. Watch for announced
   rollovers (DK planned for September 2026 but not published as of 2026-10-06, NO in January 2027).
9. Run `./gradlew :core:test`: `TrustStoreFingerprintTest` and `TrustStoreTest` must
   pass.
