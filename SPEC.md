# Sceau: reader and verifier for ICAO 9303 identity documents

Version of 2026-09-25, incorporating decisions D1 to D19 and the security audit of 2026-09-25 (findings V1 to V16); updated as later decisions are made, referenced by their number, most recently on 2026-10-06 (D36, D38).

This document is the project's source of truth. Any future implementation decision that departs from it must be justified in `docs/decisions.md` and approved. Decisions D1 to D19, now incorporated here, remain there for the record.

## 1. Purpose

Sceau is a free Android application that reads, over NFC, the chip of identity documents compliant with ICAO 9303 (French electronic national identity card, European identity cards, biometric passports from all countries), displays the data and photo stored on the chip, and cryptographically verifies that the document is genuine and that the chip is not a clone, using a built-in store of CSCA certificates.

The application keeps nothing, sends nothing and works entirely offline.

### Out of scope for v1

- Reading fingerprints (DG3) and iris (DG4), which require Terminal Authentication and certificates issued by the State.
- Optical reading of the MRZ or the CAN with the camera (no OCR).
- Automatic face recognition.
- Any storage, history, export or transmission of the data read.
- Updating the trust store over the network.

## 2. General constraints

- License: GNU GPL version 3 only (GPL-3.0-only), as stated on the “About” screen and in the headers of the C sources; logo and icon under the same license (D38, D51). Target distribution is F-Droid: no proprietary dependency, no Google service, no non-rebuildable binary blob. Sceau's own native code (JPEG 2000 decoder, OpenJPEG) is compiled from source on every build; the only exception is the free native libraries shipped prebuilt in the AndroidX artifacts on Google Maven (`libandroidx.graphics.path.so` from `androidx.graphics:graphics-path`, Apache-2.0, pulled in by Compose), as in any Compose application accepted by F-Droid (see `docs/dependencies.md`).
- No network permission in the manifest. Permissions requested: `android.permission.NFC` only, plus the internal `signature`-level permission `<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` added by androidx.core (D7). The `android.hardware.nfc` feature is declared not required so that the app installs everywhere and shows a clear message if NFC is missing. Android backup disabled.
- Android 8.0+ (minSdk 26), current compileSdk, Kotlin, Jetpack Compose, Material 3, dark mode following the system. Application-specific palette (the logo's), dynamic colors disabled to keep verdicts consistently readable (D18).
- Application ID and package `io.github.mgdx.sceau`, `io.github.mgdx.sceau.core` for `:core` (D4).
- Interface in English by default (fallback language when the phone's language is not translated), in French and in 43 other languages (D27, D55). No hardcoded string; English strings in `res/values/`, split into one file per screen (`strings_<screen>.xml`) plus `strings.xml` for shared strings (D5), translations in `values-fr/` and the other `values-<language>/`, with per-app language selection (D27).
- Module architecture:
  - `:core`: reading, verification, trust store, models. Pure Kotlin, no `android.*` import, no native code, testable on the JVM. Its dependencies are `implementation`: its public API exposes only Kotlin, `java.security` and `java.time` types (D9).
  - `:app`: NFC, Compose UI, navigation, lifecycle, image decoding (including the native JPEG 2000 decoder).
  - `:testchip`: fake PKI, simulated ICAO chip and specimen CNIe, Kotlin JVM. Reserved for tests (`testImplementation` of `:core`) and the debug APK (`debugImplementation` of `:app`); it never goes into the release APK (D17).
- Quality: zero warnings on `test`, `lint` and `ktlintCheck`. A warning blocks CI. Disabled lint rules are justified in `docs/decisions.md` (D8).
- APK: ABI splits enabled, one APK per architecture (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`), each embedding only the native library for its architecture, plus a universal APK. Target under 8 MB per APK, Master List included (D2).
- `FLAG_SECURE` on the whole activity, set as soon as it is created: no screen, home included (CAN and MRZ entry), appears in a screenshot or in the recent apps preview (section 8, audit V7).

## 3. Selected dependencies

| Need | Library | License | Note |
|---|---|---|---|
| PACE, BAC, DG reading, PA, CA, AA | JMRTD | LGPL 2.1 or later | Latest version published on Maven Central |
| APDU abstraction, JMRTD bridge to `IsoDep` | SCUBA (scuba-smartcards, scuba-sc-android) | LGPL 2.1 or later | |
| Cryptography, X.509, CMS | BouncyCastle (bcprov, bcpkix, `jdk18on` variants only) | MIT | Do not use SpongyCastle |
| JPEG 2000 decoding (DG2 photo, DG7 signature, DG12 images) | OpenJPEG (`openjp2` library only) | BSD-2-Clause | Native JNI code in `:app`, compiled from source (git submodule `app/src/main/cpp/openjpeg`) by CMake and the NDK, statically linked; no prebuilt binary |
| Asynchronous execution | kotlinx-coroutines | Apache 2 | |
| UI | Compose, Material 3, Navigation Compose, Lifecycle | Apache 2 | |

The “or later” clause allows JMRTD and SCUBA to be used under LGPL 3, which is compatible with Sceau's license (GPL-3.0-only).

jj2000 (JMRTD fork) is ruled out: its original license restricts the field of use (non-free, removed from Debian for that reason), which makes it incompatible with GPLv3 and with F-Droid's inclusion policy; this fork is not published on Maven Central either (D3). OpenJPEG, the reference implementation under a free license, replaces it. The submodule follows upstream security advisories: it is advanced as soon as a security fix is released, and the native test harness is rerun on every version upgrade (audit V5).

Any new dependency must be justified in `docs/dependencies.md`, which lists each dependency with its resolved version, its license as verified on the artifact and its F-Droid compatibility.

## 4. Supported documents

| Document | Access key | Protocol | Note |
|---|---|---|---|
| French CNIe (credit-card format, since 2021) | CAN, 6 digits on the front | PACE | Main case |
| EU identity cards (Regulation 2019/1157) | CAN if printed, otherwise MRZ | PACE | Some older cards have no ICAO application |
| Biometric passports, all countries | MRZ (number, birth, expiry) | PACE if announced in EF.CardAccess, otherwise BAC; fallback to BAC per §6.1 | |

A document without an ICAO application (AID `A0 00 00 02 47 10 01`) is reported as “document not compliant with ICAO 9303” with no further attempt.

## 5. User flow

### 5.1 Home screen

Two tabs at the top, Passport first and selected on opening (D29):

- **Passport**: three fields, document number (alphanumeric, forced uppercase), date of birth and expiry date. The document number is padded with `<` to 9 characters as in the MRZ. As soon as the eighth digit of the expiry date is typed, the keyboard is dismissed (D29).
- **ID card**: a CAN / MRZ selector, on CAN by default (D31). CAN: single field, numeric keyboard, 6 digits, validation when the field is complete. MRZ: the three fields of the Passport tab, for cards whose chip does not accept the CAN or whose CAN does not have 6 digits.

Both dates are typed on the numeric keyboard (numeric password keyboard, so that the system keyboard neither learns nor suggests these dates), as eight digits, without a date picker. The `/` separators are displayed as the user types without being stored. The field order follows the locale's short date format: DD/MM/YYYY in French, MM/DD/YYYY in American English, day, month, year by default. A date must exist, the date of birth must not be later than today, and the expiry date must be after 1990; the error is shown under the field and “Read” stays disabled as long as a date is invalid (D10).

The CAN, document number and date fields are excluded from autofill: no password manager must ever be offered them (audit V14).

Below the fields, a sentence reminds the user that the document must be presented by its holder, followed by a “Read” button.

If NFC is turned off, a banner says so, with a button to the system NFC settings. If the phone has no NFC, the screen explains it and disables the button.

A menu gives access to the “Trust store” and “About” screens. In the debug APK only, it also offers “Simulate a French ID card (demo)”, which reads the specimen CNIe from `:testchip` through the same path as a real document (section 9.3). This entry does not exist in the release APK.

The values entered are never persisted: they live in the `ViewModel`, never in a `Bundle` or a `SavedStateHandle`, and are cleared at the end of a successful read and when the result screen is closed.

### 5.2 Reading screen

Prompt “Hold the document against the back of the phone and don’t move it away”, with a subtle animation and a list of steps that are checked off as they progress:

1. Connecting to the chip
2. Opening secure channel (PACE or BAC)
3. Reading data
4. Verifying signature
5. Verifying chip

If the “Opening secure channel” step lasts more than 5 s, a waiting message explains that the chip may make the reader wait up to a minute after failed attempts (anti-brute-force countermeasure, §6.1) and asks the user not to remove the document (D12).

Errors handled, each with a message in the interface language and a “Try again” button, except restricted access:

- Wrong CAN or MRZ (PACE or BAC failure); the message warns that a failed attempt may lengthen the chip's response delay
- CAN provided for a document that does not announce PACE; the message suggests using the Passport tab and the MRZ
- Document removed too early (connection lost)
- Document without an ICAO application
- Chip reserved for authorized authorities (`ACCESS_RESTRICTED`, German identity cards issued before August 2021): the message says that trying again will not change anything, and only “Cancel” is offered, without “Try again” (D36)
- Timeout
- Unexpected error

For **all** errors, the full technical code is shown in small print under the message: since no logging is allowed (section 8), it is the only means of diagnosis (D11). The message is chosen from the base code (text before the first `-`). Stable base codes: `NOT_ICAO`, `ACCESS_DENIED`, `ACCESS_RESTRICTED`, `CAN_WITHOUT_PACE`, `CONNECTION_LOST`, `TIMEOUT`, `UNEXPECTED`. `ACCESS_RESTRICTED` carries as a suffix the step and the refused element (`ACCESS_RESTRICTED-SECURE_CHANNEL-SELECT_APPLET`, `ACCESS_RESTRICTED-READ_DATA-SOD`, `ACCESS_RESTRICTED-READ_DATA-DG1`, D36). `CONNECTION_LOST`, `TIMEOUT` and `UNEXPECTED` carry a diagnostic suffix made only of elements without personal data: step (`SECURE_CHANNEL`…), INS byte of the current command (`INS86`), APDU length rounded to the nearest ten (`L10`), operation label (`SELECT_APPLET`, `SOD`, `DG1`, `RECONNECT`), I/O error subtype taken from a closed list (`IO-TRANSCEIVE_FAILED`, `IO-TOO_LONG`, `IO-SERVICE_DIED`, `IO-OTHER`, `IO-NO_MESSAGE`), simple name of the exception class and SW status word. Example: `TIMEOUT-SECURE_CHANNEL-INS86-L10`. A code never contains data read, a key, an APDU byte or a raw exception message. The full grammar is in `docs/protocol.md` §5.

### 5.3 Result screen

From top to bottom:

**Overall verdict**, large, on a colored background:

- **Authentic** (green): PA passed and at least one of the two challenges (CA or AA) passed.
- **Valid signature, chip not verified** (orange): PA passed but neither DG14 nor DG15 announced in the SOD. The document is genuine but the chip could be a clone.
- **Unknown issuer** (gray): the SOD chain leads to no CSCA in the store, or the DS certificate cannot be found. The data is displayed but nothing is guaranteed.
- **Failed** (red): invalid signature, non-matching DG hash, signed DG not provided by the chip, invalid chain or inconsistent countries, failed challenge, or unsupported algorithm.

The verdict is computed using the decision table in `docs/protocol.md` §6: a row that failed or has an unsupported algorithm gives “Failed”; otherwise an unavailable chain gives “Unknown issuer”; otherwise PA passed and CA or AA passed gives “Authentic”; CA and AA not available give “Valid signature, chip not verified”; any other case gives “Failed” (D6).

When the chain anchor is a CSCA from a Master List **imported** by the user, the verdict card itself says so with a visible notice, without expanding anything (audit V6).

After a read in demo mode (debug APK), a “Simulated document — demo” banner is displayed (D17).

**Photo** from DG2, large, with a button to show it full screen.

**Identity** (DG1): surname, given names, sex, date of birth, nationality, document type and number, issuing state (flag and country name from the three-letter code), expiry date. If the document has expired, a “Document expired” banner is shown without changing the authenticity verdict.

**German eID card for Union citizens (eID-UB)**: its chip contains no identity data (DG1 with document code “UB”, state “D”, “<” everywhere else; DG2 holds the eID logo, identical for all cards). If the SOD signature is valid and the DG1 hash matches, the Identity section is replaced by a banner explaining this, followed only by the document type and the issuing state, and the photo is not displayed; the DG2 hash is still checked. The verdict is computed normally (D36).

**Holder's signature** (DG7), section shown only if the chip announces and provides it: image of the handwritten signature, decoded like the photo, on a white background in both themes to remain legible (D37).

**Additional data**, section shown only if DG11 or DG12 is present:

- DG11: full name, other names, personal number, place of birth, address, telephone, profession, title, personal summary, other nationalities, custody information.
- DG12: issuing authority, date of issue, endorsements and observations, front and back images if present.

Only the fields that are filled in are displayed. Any text coming from the chip (DG1, DG11, DG12, certificate subjects) is sanitized for display: control characters, bidirectional formatting characters and line or paragraph separators removed, length bounded, number of lines limited (audit V12).

**Checks**, a checklist with, for each row, a state (ok, failed, not available, unsupported algorithm) and an expandable detail:

1. Secure channel established (PACE or BAC, and which)
2. SOD signature valid
3. Certificate chain: DS linked to a CSCA in the store, with the CSCA name, the country, the source (ANTS, national publication, built-in Master List, imported Master List) and the signature algorithm. The CSCA's country, the DS's country and the DG1 issuing state must match; otherwise the row fails with the detail of the three countries (audit V3)
4. DS certificate within its validity period at the document's date of issue
5. Data group hashes matching the SOD, with the list of DGs checked. A DG that the SOD announces, among those Sceau reads (1, 2, 7, 11, 12, 14, 15), and that the chip does not provide is a failure, listed as missing: a chip cannot withhold a signed DG (audit V1)
6. Chip Authentication (DG14) passed
7. Active Authentication (DG15) passed

**“Clear” button** at the bottom and in the action bar: immediately wipes all data in memory, decoded images included, and returns to the home screen. The system back action has the same effect.

### 5.4 “Trust store” screen

Accessible from the home screen menu:

- List of known CSCAs, grouped by country, with subject name, validity period, SHA-256 fingerprint and source.
- “Import a Master List or a certificate” button: opens the system file picker (`ACTION_OPEN_DOCUMENT`, without persistent permission), accepts a CMS file (`.ml`, `.der`, `.p7b`) of at most 20 MB. The CMS signature is verified with the signer certificate embedded in the file, whose fingerprint is shown to the user for confirmation before import. The confirmation dialog warns that an imported certificate can make any document from its country authentic, and that only a list from a trusted source should be imported (audit V6). Imported Master Lists are stored as is in the app's internal storage (`filesDir/trust/`, file name = SHA-256 of the content, atomic write) and their CSCAs are marked “Imported”.
- The same button accepts a self-signed CSCA certificate or a standalone link certificate (DER or PEM, only one per file), recognized automatically, with a preview (subject, country, validity, SHA-256 fingerprint) and the warning that no State signature vouches for it; stored as DER in `filesDir/trust/<sha256>.der`, marked “Imported certificate” (D33).
- “Imported items” section: each imported Master List or certificate, deletable one by one after confirmation (D33).
- “Delete imported certificates” button.

No personal data passes through this screen.

### 5.5 “About” screen

Version, license, link to the repository, donation link to Liberapay opened in the browser (D52), reminder of the terms of use (see section 8), and description of the built-in trust store: French CSCAs from ANTS and the German Master List from the BSI, with the signing date of that Master List, then the countries whose certificates published by the State itself are built in. A notice states that the BSI Master List is redistributed without modification, without cooperation with or endorsement by the BSI (reuse conditions, D1). The Open Government Licence v3.0 attribution accompanies the British certificates (D26). The limitations of section 7.4 are restated there. An “Open-source libraries” section names the embedded libraries and expands, on request, their copyright and license notices, in English and untranslated, including the full text of OpenJPEG's BSD-2-Clause license (D50).

### 5.6 Introduction and “What the chip contains” screen

On first launch, a 4-page swipeable introduction explains the ICAO 9303 chip, the access key (CAN or MRZ) and the encrypted channel, what Sceau checks and the meaning of the four verdicts, then privacy. “Skip introduction” (all pages except the last), “Get started” (last page) and the back action lead to the home screen and mark the introduction as seen; it can be replayed from “About”. The “What the chip contains” screen, opened from “About” and from the last page of the introduction, lists the files read and displayed, those read for verification only, those never read (DG3, DG4…) and what Sceau does not do (D35).

## 6. Reading and verification protocol (`:core` module)

The public API of `:core` is a suspending function `readAndVerify(transport: CardTransport, key: AccessKey, trustStore: TrustStore, progress: (Step) -> Unit): VerificationReport`, run on `Dispatchers.IO`. `CardTransport` is an abstract interface (`transceive`, `maxTransceiveLength`, `timeoutMillis`, `reconnect`, `close`) implemented in `:app` on top of `IsoDep`, which makes it possible to test `:core` with a simulated transport. `reconnect()` resets the link (empty by default; `IsoDep.close()` then `connect()` in the app, timeout reapplied) for the PACE → BAC fallback (D13).

An error that interrupts the read throws a `SceauException` with a stable code (§5.2); any verification failure, on the contrary, is recorded in the report, which is always produced once the data has been read. If the read fails, the data already read is zeroed.

The detailed sequence, the timeouts and the error code grammar are in `docs/protocol.md`.

### 6.1 Sequence

The order follows ICAO 9303 part 11: EF.CardAccess is a file of the MF and PACE runs at MF level, before the application is selected.

1. **EF.CardAccess**: read in plain at MF level, before any application selection. Present and containing `PACEInfo`: PACE is announced. Absent or unreadable: no PACE.
2. **Secure channel and selection of the ICAO application**:
   - PACE announced: PACE with the key provided (CAN, or key derived from the MRZ), each `PACEInfo` tried in order, mapping and domain parameters taken from what the chip announces; then selection of the ICAO application under secure messaging.
   - PACE not announced: selection of the ICAO application in plain, then BAC with the MRZ key. A CAN without PACE announced is an explicit error (`CAN_WITHOUT_PACE`).
   - `6A82` response to the application selection: `NotIcaoDocument`, with no further attempt.
   - Key refused (SW `63xx` during PACE, BAC failure): `ACCESS_DENIED`, with no further attempt.
   - **PACE → BAC fallback** (MRZ key only): if PACE fails other than by a refusal of the key or a timeout (unexpected SW, library error, link lost), the link is reset by `CardTransport.reconnect()`, the ICAO application is selected in plain, then BAC is performed; ICAO 9303 requires documents that announce PACE to accept BAC during the transition period. With a CAN, no fallback. A reconnection that cannot be made gives `CONNECTION_LOST-SECURE_CHANNEL-RECONNECT` (D13).
   - **Authentication timeout**: during PACE and BAC, the transport's response timeout is raised to 60 s, then restored to its previous value (10 s) for reading. A chip may impose an increasing delay after failed attempts; cutting off earlier never lets it respond and worsens the penalty. A timeout during PACE stops the read (`TIMEOUT-…`), with no fallback to BAC (D12).
   - All subsequent commands go through secure messaging; the MAC of responses is always verified. Short APDUs only (256 bytes at most, no extended APDU), reading by `SELECT` then `READ BINARY` in blocks of 223 bytes, without SFI (D14).
3. **EF.COM then EF.SOD**. EF.COM is optional: if absent or unreadable, the list of DGs present comes from the SOD. EF.SOD is mandatory.
4. **Data groups**: DG1, DG2, then DG14, DG15, DG11, DG12 and DG7 (handwritten signature, D37) if they are announced in EF.COM or in the SOD. DG3 and DG4 are never read, nor are DG5, DG6, DG8 to DG10, DG13 and DG16.
   - DG1 is mandatory: its absence interrupts the read.
   - DG2 is always requested. A DG, DG2 included, that the chip does not provide although the SOD announces it makes the “Data group hashes” row fail (§5.3, audit V1); a DG that is not announced in the SOD is simply absent (no photo without DG2).
   - Bounded size: the length announced by the TLV header of each file is checked before reading, against a ceiling specific to the file; a length beyond it is refused without being read. Running out of memory is treated as a read error, without preventing the wiping of the data already read. A portrait whose image header is corrupted gives “photo missing” rather than failing the whole read (audit V11).
   - A transport error (document removed, timeout) always interrupts the read.
5. **Passive Authentication**:
   - extraction of the DS certificate embedded in the SOD. If it is absent, lookup in the store by (issuer, serial number) or by key identifier; not found: SOD signature and chain “not available” (`DsCertificateMissing`), hence “Unknown issuer” (D15),
   - construction and validation of the chain from the DS to a self-signed CSCA in the store, through 8 link certificates at most; candidate issuers searched by Authority Key Identifier then by name. Only the signatures of the CSCAs and links count, not their validity period: a document remains valid up to ten years after its CSCA expires (D15). Certificates contained in the SOD are never used as intermediates. No CSCA in the store matches: chain “not available”, verdict “Unknown issuer” (D6),
   - country consistency check: CSCA country = DS country = DG1 issuing state (ICAO alpha-3 code mapped to alpha-2, special codes included: `D` = DE, `GBD`…`GBS` = GB); otherwise the chain fails (audit V3). Any exception (international organization) is documented in `docs/decisions.md`,
   - verification of the SOD signature with the DS key, according to the algorithm declared in the SOD,
   - recomputation of the hash of each DG read with the algorithm declared in the SOD and comparison; a DG read but absent from the SOD does not match; a DG announced in the SOD and not provided is a failure (step 4),
   - verification that the DS validity period covers the document's date of issue: DG12 date if available, otherwise SOD signing date, otherwise expiry minus 10 years, marked “estimated”. Outside the period: failure for a DG12 or SOD date; “not available”, never failure, for an estimated date, since the usual validity period may be wrong (minor's passport) (D6, D15).
6. **Chip Authentication** if DG14 is present and announces it: key agreement with the static key from DG14, then renewal of secure messaging; all subsequent reads go through this channel. CA only passes if the chip responds under the new keys: a short exchange (reading DG1) confirms it, with the MAC of the response verified. CA is “not available” only if DG14 is absent from the SOD.
7. **Active Authentication** if DG15 is present: 8-byte nonce drawn from a `SecureRandom`, sent by `INTERNAL AUTHENTICATE`, verification of the signature with the DG15 key (RSA: ISO/IEC 9796-2 scheme 1, hash designated by the trailer; EC: ECDSA with the hash announced in DG14, or, if it announces none, SHA-1 to SHA-512 tried in order, D15). AA is “not available” only if DG15 is absent from the SOD. Losing the link during CA or AA interrupts the read; any other chip error is recorded in the corresponding row.
8. Construction of an immutable `VerificationReport` containing the data read, the result of each step (exactly one row per check), the overall verdict and the certificate chain metadata.

### 6.2 Rules

- The signature algorithm and the curve are never hardcoded. Verification accepts what the certificates and the SOD declare (RSA PKCS#1 v1.5, RSA-PSS, ECDSA on any curve supported by BouncyCastle, and any future algorithm added to BouncyCastle). An unknown algorithm produces `UnsupportedAlgorithm`, not a silent failure.
- Refused algorithms, which can never give an “ok” row: MD5 (for DG hashes as for signatures), RIPEMD-128, and RSA keys shorter than 1024 bits for Active Authentication. SHA-1 remains accepted, since documents that use it are still in circulation (audit V9).
- Chain extensions: every issuer in the chain (CSCA, link certificate) must carry `basicConstraints` with CA=true and the `keyCertSign` key usage; the DS must carry `digitalSignature`. A tolerance is only allowed for real CSCAs that lack these extensions, after verification on the built-in anchors, and must be documented in `docs/decisions.md` (audit V8).
- SOD content type: the signed content must be of type `id-icao-ldsSecurityObject` (`2.23.136.1.1.1`); otherwise SOD verification fails (audit V10).
- The chain search is bounded: each certificate is visited only once per search, and the number of signature verifications is capped, so that a hostile store cannot make the search exponential (audit V15).
- A step that is not available (DG14 or DG15 absent from the SOD, step not reached) is distinguished from a failed step.
- Restricted access: an SW `6982` received **after** the secure channel is established, on selection of the ICAO application or on reading an essential file (EF.SOD, DG1), throws `ACCESS_RESTRICTED`: the chip reserves these files for State terminals (Terminal Authentication), which free software cannot perform. A `6982` before authentication remains `UNEXPECTED`, a key refusal remains `ACCESS_DENIED`, and a `6982` on an optional file (EF.COM, DG2, DG7, DG11, DG12, DG14, DG15) makes it absent (D36).
- No personal data appears in exceptions, logs or error identifiers. No JMRTD exception is propagated or attached as a cause: their messages contain APDUs in hexadecimal (D14). The models, the key and the report have a masked `toString()`.
- The report contains no Android reference and is neither serialized nor persisted: tests work on the in-memory objects produced with `:testchip` (D19). `:core` produces no user-facing text: the app formats the structured details with its own strings.

## 7. Trust store

### 7.1 Built-in content

In `core/src/main/resources/trust/`:

- `ants-csca-2010.der`, `ants-csca-2015.der`, `ants-csca-2020.der`, `ants-csca-2025.der`: ANTS passport CSCA certificates (`CN=CSCA-FRANCE`), from the CSCA page of ants.gouv.fr. The 2010 CSCA, expired, is kept: it signed DSs of passports that are still valid (D16).
- `ants-csca-eid-2021.der`: ANTS e-ID CSCA certificate (`CN=eID-FRANCE`, identity cards), from the e-ID CSCA page of ants.gouv.fr. It appears in no national Master List: without it, no CNIe could be verified.
- `de-bsi-master-list.ml`: German Master List published by the BSI (signed CMS file), redistributed byte for byte, only renamed. It covers the 27 EU countries, the EEA, Switzerland and the United Kingdom, among 117 issuers. The ICAO Master List is not built in: its terms of use forbid redistribution and downloading it requires a captcha. The BSI one is published with explicit reuse conditions (no advertising use, no appearance of cooperation with or endorsement by the BSI, file unchanged), which Sceau complies with (D1).
- `<country>-csca-*.der` (for example `gb-csca-2026.der`): CSCAs and link certificates published by a State for its own documents, absent from the BSI list, still valid and taken from a status A source in `docs/trust-sources.md` (open license, or official publication without reuse restrictions). United Kingdom, Georgia and Luxembourg (D26; Greek certificates removed on 2026-10-11, D48).
- `index.txt`: list of the files in the directory, one name per line, because listing a classpath directory is not reliable in an APK (D16).

`docs/trust-store.md` documents for each file: the original URL, the download date, the fingerprint published by the source and the SHA-256 fingerprint observed. A unit test verifies that the fingerprints of the built-in files match those listed in `docs/trust-store.md`, and that this document, `index.txt` and the directory list exactly the same files, to detect any substitution, addition or removal.

### 7.2 Loading

`:core` loads the DER certificates (source `ANTS` for the `ants-*.der` files, `NATIONAL` for the others, D26), then the built-in Master List. Verifying its CMS signature with only the signer certificate it contains would be circular: the signature is verified **and** the signer must be issued by the German CSCA whose SHA-256 fingerprint is pinned in the code (or by its link certificate, also pinned; fingerprints in `docs/trust-store.md`). Otherwise the built-in list is rejected and loading fails (D1). The content type of the Master List is verified.

Master Lists imported by the user are verified as per §5.4 (CMS signature with the signer contained in the file, fingerprint confirmed by the user); an invalid import is ignored.

The sources are merged. In case of duplicates, the priority is ANTS, then national publications, then the built-in Master List, then imported Master Lists. Each CSCA is indexed by country, subject, key identifier and fingerprint, with its source (`ANTS`, `NATIONAL`, `EMBEDDED_MASTER_LIST`, `IMPORTED_MASTER_LIST`). Whether an anchor is self-signed is deduced from its key identifiers, the signature being verified only as a fallback; chain construction always verifies every signature (D16).

The store is preloaded in the background when the process starts, so that neither the first read nor the “Trust store” screen has to wait; a preloading error is retried and reported on the next access (D16).

### 7.3 Updates

CSCAs rotate every three to five years. The built-in store is updated at each release following the procedure in `docs/trust-store.md`. Between two releases, the user can import a more recent Master List (section 5.4). The app never downloads anything.

### 7.4 Documented limitations

- A country absent from the store (ANTS, national publications, BSI list and imports) gives the verdict “Unknown issuer”, not “Failed”.
- The BSI list does not contain the CSCA for Slovak identity cards nor 4 Hungarian CSCAs from 2024; they appear in the Italian and Swedish Master Lists, which the user can import (D1).
- Revocation lists are not checked in v1 (they require the network).

These limitations are displayed on the “About” screen.

## 8. Privacy and security

- No data read is written to disk, cache, database or log, in debug or in release. No `Bundle`, `SavedStateHandle`, `rememberSaveable` or preference contains data read or a key (the only preference is the “introduction seen” flag, D35); after process death, the application restarts on the home screen, empty, or on the introduction as long as it has not been seen.
- No logging, not even temporarily for debugging: no `Log`, no `println`, no `printStackTrace`. Diagnosis goes through the displayed error code (§5.2). The `java.util.logging` loggers of JMRTD and SCUBA (`org.jmrtd`, `net.sf.scuba`) are turned off at the start of each read, because they write APDUs in plain (D14).
- `FLAG_SECURE` on the whole activity from its creation: no screenshot, no recent apps preview, on the home screen (CAN and MRZ entry) as on the reading screen, the result screen and the full-screen photo (audit V7).
- Wiping: the byte arrays of DG1, DG2, DG7, DG11 and DG12 (raw DGs, portrait, signature, DG12 images) and the decoded images (bitmaps zeroed then recycled) are wiped on leaving the result screen (“Clear”, back action), when the app goes to the background during a read or on the result screen, when a read is cancelled and when the `ViewModel` ends. The bitmaps are owned by the session, so that wiping reaches them directly, including when a decode finishes after the screen has been left (audit V13). A rotation wipes nothing. A report produced by a read abandoned in the meantime is wiped as soon as it is received, without being published.
- Limitations of wiping, accepted and documented: `String`s and dates (name, number, CAN, DG11 and DG12 fields) are immutable and cannot be zeroed, only the garbage collector frees them; the internal buffers of JMRTD (read streams, LDS objects, secure messaging), of OpenJPEG and of the Android image decoder are freed without being zeroed. These copies remain in process memory and are neither written nor logged. Sceau zeroes all its own arrays, including the intermediate native buffers of JPEG 2000 decoding (D3, D14).
- The access key (CAN or MRZ) is never remembered between two reads: it is forgotten after a successful read (kept after an error for “Try again”) and on wiping.
- Constrained image decoding: an image coming from the chip is potentially hostile data.
  - JPEG: dimensions read before decoding, pixel ceiling, subsampling to a reasonable display size (audit V2).
  - JPEG 2000 (native): dimensions beyond 4096 × 4096 refused from the header, stream of 16 MB at most, 1 to 4 components of 16 bits at most, strict mode (truncated stream refused), OpenJPEG messages silenced. Memory budget estimated before decoding (components, precision, pixels, tiles, code blocks) with refusal beyond it, resolution reduction for large images (audit V4). Hardened compilation (`-fstack-protector-strong`, `_FORTIFY_SOURCE=2`, hidden visibility except for the JNI function, full RELRO) (D3).
  - Decodes are serialized, never run in parallel (audit V4). A refused decode gives a missing image, never a crash.
- No telemetry, no crash reporting, no analytics library.
- The Active Authentication nonce is drawn from a `SecureRandom`.
- The README and the “About” screen restate the terms of use: reading assumes that the holder presents their own document. The photo is biometric data within the meaning of the GDPR if it is used for automated comparison, which Sceau does not do and must not do without a dedicated legal basis. They also state that Sceau is provided without warranty, that its verdict has no evidentiary value and does not say whether the document has been reported lost or stolen, and that Sceau is neither an official tool nor a certified identity verification service (D30).
- The code security review (`android-securite` skill) is carried out before each release; its report is filed in `docs/audit-securite-<date>.md`.

## 9. Tests

### 9.1 Unit and end-to-end tests (JVM)

The `:testchip` module provides:

- a fake PKI factory generated on the fly: old and new CSCA, link certificate, DS, SOD signed over fake DGs, DG14 with a CA key pair and DG15 with an AA key pair, in RSA and in ECDSA;
- a stateful simulated ICAO chip, written without JMRTD's protocol code: EF.CardAccess, BAC, PACE (generic ECDH mapping), 3DES and AES secure messaging, Chip Authentication, Active Authentication;
- a specimen CNIe (SPECIMEN identity, CAN 123456, synthetic JPEG 2000 portrait) and its test store.

The `:core` tests use this simulated chip for end-to-end reads with `readAndVerify` (BAC and PACE), and a transport that replays recorded APDU exchanges for sequence and error cases. They fail if DG3 or DG4 is requested. Cases covered at a minimum:

- nominal chain: Authentic verdict
- altered SOD signature: Failed
- DG2 modified after signing: Failed on the hash
- DG announced in the SOD but not provided (DG2, DG7, DG14 or DG15): Failed
- DS expired before the date of issue: Failed on validity; estimated date outside the period: not available
- DS not linked to a known CSCA: Unknown issuer
- inconsistent CSCA, DS and DG1 countries: Failed
- DG14 and DG15 absent from the SOD: Valid signature, chip not verified
- AA response signed with the wrong key: Failed
- unknown signature algorithm: `UnsupportedAlgorithm`; refused algorithm (§6.2): never “ok”
- PACE → BAC fallback, and no fallback on key refusal or timeout
- Master List with an invalid CMS signature, or built-in list whose signer is not anchored: rejected at loading
- fingerprints of the built-in files matching `docs/trust-store.md`
- error codes without personal data

The `:app` tests (JVM) cover home screen input, formatting of the checks, error mapping and demo mode end to end.

### 9.2 Native code

The JPEG 2000 decoding core (`jp2_decode.c`), separate from the JNI bridge, is tested on the host under AddressSanitizer and UndefinedBehaviorSanitizer (`app/src/main/cpp/test/run-host-tests.sh`): synthetic images, truncated, altered and oversized streams, decompression bombs. The harness is rerun on every change to the native code or OpenJPEG version upgrade.

### 9.3 Demo mode

The debug APK offers “Simulate a French ID card (demo)” (§5.1): the specimen CNIe from `:testchip` is read through the same path as a real document, with the same wiping rules, and verified with its own test store, which never replaces or supplements the real store. The key entered is forgotten when the demo starts. In the release APK, the entry does not exist and no `:testchip` class is present: `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` must not contain `project :testchip` (D17).

### 9.4 On-device tests

Manual test plan in `docs/test-plan.md`, run via adb on a real phone (`android-test` skill) with a real CNIe and a real passport:

- nominal ID card case, nominal passport case
- wrong CAN, wrong MRZ, then waiting for the chip under penalty
- removing the document during the read
- expired document
- screen rotation and going to the background during the read and on the result screen (verification of wiping)
- recent apps preview and screenshot refused on all screens
- NFC turned off, then back on
- import of a valid then an invalid Master List
- date entry on the keyboard, demo mode (debug APK)

## 10. Deliverables and repository layout

```
README.md                    presentation, screenshots, terms of use, installation
SPEC.md                      this document
LICENSE                      GNU GPL version 3 (the project is under GPL-3.0-only)
CONTRIBUTING.md              contribution rules (sections 2 and 3)
docs/architecture.md         modules, data flows, lifecycle of sensitive data
docs/protocol.md             detailed ICAO sequence, timeouts, error codes, verdict table
docs/trust-store.md          provenance and fingerprints of the certificates, update procedure
docs/dependencies.md         dependencies, licenses, F-Droid compatibility
docs/test-plan.md            manual test plan
docs/decisions.md            decisions and departures from SPEC.md, justified
docs/release.md              release procedure: checks, signing, assets, tag, F-Droid
docs/reproducible-builds.md  reproducible builds (D25)
docs/fdroid/                 draft fdroiddata recipe (universal APK)
docs/audit-securite-*.md     security review reports
fastlane/metadata/           fr-FR and en-US: description, screenshots, changelog
core/                        pure Kotlin module
app/                         Android module
app/src/main/cpp/            native JPEG 2000 decoder (JNI bridge, decoding core, CMake)
app/src/main/cpp/openjpeg/   OpenJPEG git submodule
app/src/main/cpp/test/       host test harness under sanitizers (not in the APK)
testchip/                    fake PKI, simulated chip, specimen CNIe (tests and debug)
```

Releases: public repository https://github.com/mgdx/Sceau; per-architecture APKs and universal APK, signed by the author, published on GitHub releases (`sceau-<version>-<abi>.apk`, `sceau-<version>-universal.apk`, `SHA256SUMS`), annotated tag `vX.Y.Z` (D53), Fastlane changelog up to date. F-Droid rebuilds and publishes only the universal APK, signed by the author thanks to reproducible builds (D25, D38). Procedure: `docs/release.md`. Preparation of the F-Droid submission with the `android-fdroid` skill.

## 11. Milestones

Status as of 2026-10-12: Foundation, Reading, Verification and Finishing reached; Publication in progress, first public release, version 1.0.0, published on 2026-10-12 (tag `v1.0.0`, D53), F-Droid submission to follow.

1. **Foundation** (reached): modules, CI, ktlint, trust store loaded and tested, crypto test factory.
2. **Reading** (reached): `IsoDep` transport, PACE and BAC, DG reading, raw display of DG1 and the photo.
3. **Verification** (reached): PA, CA, AA, `VerificationReport`, complete result screen with verdict and checklist.
4. **Finishing** (reached): additional DG11 and DG12 data, trust store screen and import, About screen, verified memory wiping, `FLAG_SECURE`, en-US translations, fixes from the security audit.
5. **Publication** (in progress): security review, on-device test plan (signed release APK included), Fastlane metadata, first release 1.0.0, F-Droid submission.
