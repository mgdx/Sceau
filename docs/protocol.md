# Reading and verification protocol

This document details the ICAO 9303 sequence followed by `:core` (SPEC §6), the rules that govern it, how it maps to the displayed steps and to errors, and how the verdict is computed. It describes the behavior of the code in `core/src/main/kotlin/` (packages `reading/` and `verify/`); departures from the SPEC are justified in `docs/decisions.md`.

References:

- **ICAO Doc 9303, part 10**: *Logical Data Structure (LDS) for Storage of Biometrics and Other Data in the Contactless Integrated Circuit*: file structure (EF.COM, EF.SOD, EF.CardAccess, data groups).
- **ICAO Doc 9303, part 11**: *Security Mechanisms for MRTDs*: BAC, PACE, Passive Authentication, Chip Authentication, Active Authentication, secure messaging.
- **ICAO Doc 9303, part 12**: *Public Key Infrastructure for MRTDs*: CSCA, DS, link certificates, Master Lists.

## 1. Entry point

```kotlin
suspend fun readAndVerify(
    transport: CardTransport,
    key: AccessKey,
    trustStore: TrustStore,
    progress: (Step) -> Unit,
): VerificationReport
```

- `transport`: raw APDU channel (`transceive`, `maxTransceiveLength`, `timeoutMillis`, `reconnect`, `close`). `:app` implements it on top of `IsoDep` (`IsoDepTransport`); the tests use a transport that replays recorded APDU exchanges (`ScriptedTransport`) or the simulated chip from `:testchip` (`SimulatedChip`).
- `key`: `AccessKey.Can` (6 digits) or `AccessKey.Mrz` (document number without the `<` filler characters, date of birth, expiry date).
- `trustStore`: merged trust store (ANTS CSCAs, built-in German Master List from the BSI, imported Master Lists).
- `progress`: called **at the start** of each step (see §4).

The function blocks on I/O and runs on `Dispatchers.IO`. It throws a `SceauException` for any error that **interrupts** reading; any **verification** failure, on the contrary, is recorded in the report, which is always produced once the data has been read. If reading fails, the data already read is zeroed.

Before each reading, the `java.util.logging` loggers of JMRTD and SCUBA are turned off (`LibraryLogging`): they write APDUs in plain text (decision D14).

## 2. Sequence (SPEC §6.1)

The actual order of exchanges follows ICAO 9303 part 11: EF.CardAccess is a file of the MF, read **before** any application selection, and PACE runs at the MF level, before the eMRTD application is selected. The SPEC lists the steps in logical order (recognize an ICAO document, then open the channel); the observable result is the same (`NotIcaoDocument` if the ICAO application is missing). Implementation: `SecureChannel` (`core/src/main/kotlin/…/reading/`).

Full sequence (decision D20):

1. EF.CardAccess, then PACE or BAC (§2.1, §2.2); after any PACE (GM, IM or CAM), EF.CardSecurity at the MF, never under BAC (§2.2, §2.6.1);
2. EF.COM, EF.SOD (§2.3), then DG14 if it is announced in EF.COM or in the SOD;
3. Chip Authentication if DG14 announces a key (§2.6), except after PACE-CAM (the chip has already authenticated itself);
4. DG1, DG2, then DG15, DG11, DG12 and DG7 if they are announced (§2.4), under the secure messaging of Chip Authentication if it succeeded;
5. Passive Authentication (§2.5), which also verifies the hash of DG14;
6. Active Authentication if DG15 is present (§2.7);
7. downgrade check: protocol performed compared with the signed `PACEInfo`s of DG14 (§2.2, audit V20).

Chip Authentication comes before reading the data, and not before Passive Authentication as SPEC §6.1 lists it: this way DG1, DG2, DG7, DG11, DG12 and DG15 are read under session keys that only the chip holding the DG14 private key can derive, which binds them to that chip (ICAO 9303-11 §6.2, inspection procedure).

### 2.1 EF.CardAccess, then selection of the ICAO application

1. Reading of **EF.CardAccess** (FID `01 1C`, file of the MF, part 10), in plain text. If it is present and contains `PACEInfo`s, PACE is announced. Missing or unreadable: no PACE.
2. **Without PACE**, the ICAO application is selected in plain text right away: `SELECT` command by application name, AID `A0 00 00 02 47 10 01` (LDS1 eMRTD application, part 10).
3. **With PACE**, the selection takes place after PACE, under secure messaging (§2.2).

Response `6A82` (file not found) to the selection: `SceauException.NotIcaoDocument` (code `NOT_ICAO`). No further attempt (SPEC §4). Any other selection failure gives a technical error (`UNEXPECTED-<step>-SELECT_APPLET-…`).

### 2.2 Secure channel: PACE or BAC

- **PACE** (part 11, *Password Authenticated Connection Establishment*) is performed with the key supplied: the CAN for `AccessKey.Can`, a key derived from the MRZ for `AccessKey.Mrz`. Each announced `PACEInfo` is tried in order, PACE-CAM ones first (audit V20); the mapping and domain parameters are those announced by the chip, never hard-coded. Proprietary domain parameters (`PACEDomainParameterInfo`) are not supported: the corresponding `PACEInfo` is skipped. PACE succeeded: the ICAO application is selected under secure messaging. If PACE completed with the **PACE-CAM** mapping, EF.CardSecurity is read at the MF under the new secure messaging, before that selection (§2.6.1).
- **BAC** (part 11, *Basic Access Control*) is used if PACE is not announced, with the MRZ key (document number, date of birth, expiry date and their check digits).
- **CAN without PACE**: if the key is a CAN and the chip does not announce PACE, reading stops with `SceauException.CanWithoutPace` (code `CAN_WITHOUT_PACE`): BAC cannot use a CAN.
- **Key rejected**: an SW `63xx` during PACE (ICAO 9303-11, BSI TR-03110) or a BAC failure gives `SceauException.AccessDenied` (code `ACCESS_DENIED`), with no further attempt.
- **PACE → BAC fallback** (MRZ key only, decision D13): if PACE fails without an explicit key rejection or a timeout, that is on an unexpected SW, a JMRTD error or a lost link, the link is reset (`CardTransport.reconnect()`: `IsoDep.close()` then `connect()`), the ICAO application is selected in plain text, then BAC is performed. Documents that announce PACE are still required to accept BAC during the ICAO transition period. With a CAN, no fallback: a transport error is passed through as is, and a PACE failure gives `ACCESS_DENIED`. A failed reconnection gives `CONNECTION_LOST-SECURE_CHANNEL-RECONNECT`.
- **Authentication timeout** (decision D12): during PACE and BAC, the transport response timeout is raised to 60 s (`SecureChannel.AUTHENTICATION_TIMEOUT_MILLIS`), then restored to its previous value (10 s by default, `IsoDepTransport.DEFAULT_TIMEOUT_MILLIS`) for reading. After failed attempts, a chip may impose an increasing delay before responding to the first authentication command (an anti-brute-force countermeasure): a real French passport, silent when the timeout was 5 s (PACE GENERAL AUTHENTICATE) and then 10 s (BAC EXTERNAL AUTHENTICATE) on a Fairphone 3 and a Fairphone 5, took 23.3 s to answer the first PACE GENERAL AUTHENTICATE after several failed attempts. Cutting off earlier never lets the chip answer, and each interrupted attempt may increase the penalty. A timeout during PACE therefore stops reading (`TIMEOUT-SECURE_CHANNEL-INS86-…`), **without** falling back to BAC. On the app side, `IsoDepTransport` reads `IsoDep.getTimeout()` back after each assignment and classifies failures according to the timeout actually applied by the system; the reading screen shows a waiting message if the “Opening secure channel” step lasts more than 5 s (`SlowChipHint`).
- All subsequent commands go through the secure messaging (*Secure Messaging*, part 11) established by PACE or BAC; the MAC of responses is always verified.
- **Downgrade** (audit V20, decision D40; audit V32, decision D43): EF.CardAccess is not signed, so a clone can remove PACE-CAM or PACE from it. After any successful PACE, whatever the mapping, EF.CardSecurity is read at the MF under PACE secure messaging, before the applet is selected (`SecureChannel.cardSecurity`; missing: nothing is compared; never read under BAC). At the `VERIFY_CHIP` step, if neither CA via DG14 nor AA succeeded, the channel performed is compared with the signed elements (`ProtocolDowngrade`): the `PACEInfo`s of DG14 if its hash is verified, and, without PACE-CAM, the content of EF.CardSecurity verified like the SOD (`PassiveAuthenticator.verifyCardSecurity`; content whose DS or CSCA cannot be found is taken as is, since it can only make CA fail). If the CA line is still `NOT_AVAILABLE`: PACE-CAM announced by DG14 or EF.CardSecurity but channel without CAM → `CHIP_AUTHENTICATION` `FAILED` (`VERIFY_CHIP-DOWNGRADE-CAM`); EF.CardSecurity present but invalid (signature, chain, country, structure) → `FAILED` (`VERIFY_CHIP-DOWNGRADE-CARD_SECURITY`); EF.CardSecurity announcing a Chip Authentication key (`id-PK-*`) or protocol (`id-CA-*`) → `FAILED` (`VERIFY_CHIP-DOWNGRADE-CA`). PACE announced by DG14 but BAC channel → `SECURE_CHANNEL` `FAILED` (`VERIFY_CHIP-DOWNGRADE-BAC`).

#### Transport parameters

- **Short APDUs only**: the maximum length passed to JMRTD is that of the transport (`IsoDep.getMaxTransceiveLength()`), capped at 256 (`Chip`); no extended APDU is sent (decision D14).
- Files are read with `SELECT` then `READ BINARY`, in blocks of 223 bytes; short file identifiers (SFI) are not used.
- **Reading that no longer progresses** (audit V29, decision D41): SCUBA retries a `READ BINARY` as long as the response is empty, and JMRTD accepts a `90 00` response without data. Exchanges are therefore counted per file, reset at each `SELECT` (`BoundedReadBinarySender`): beyond 3 consecutive empty responses, or `file ceiling / 223 + 16` `READ BINARY` commands (20 for DG1, 1,191 for DG2), reading stops with `READ_STALLED-<STEP>-<file>` (e.g. `READ_STALLED-READ_DATA-DG2`), including for an optional file, which is not treated as missing. A real 40 KB DG2 takes about 180 `READ BINARY` commands.
- **Responses checked before JMRTD** (audit V36, decision D41): `TransportCardService.transmit` rejects (`CardServiceException`, handled like any chip error, never an `Error`) a response without an SW or with more than 64 KiB of data; for a protected command (CLA `x C`), a response in which a DO'85 or DO'87 does not fit within the data, walked byte by byte as JMRTD does (JMRTD allocates the announced length before verifying the MAC); for GENERAL AUTHENTICATE in plain text (PACE), a response whose BER lengths do not fit (`BerStructure`). Plain-text responses to other commands (SELECT, EF.CardAccess, GET CHALLENGE, BAC) are not interpreted.
- **Global timeout** (audit V29, decision D41): outside authentication, a reading has 120 s in total (`ReadingSession.GLOBAL_TIMEOUT_MILLIS`), counted during the `CONNECT`, `READ_DATA` (including re-establishing the channel after a failed CA), `VERIFY_SIGNATURE` and `VERIFY_CHIP` steps, and suspended during `SECURE_CHANNEL`, where PACE and BAC keep their own 60 s timeout per response. When it expires, the transport is closed (which unblocks the current APDU) and reading ends with `TIMEOUT-<STEP>-GLOBAL`, even if a report was produced in the meantime (it is wiped). A full real reading takes from a few seconds to about twenty seconds.
- On the app side, `IsoDepTransport` reads in NFC reader mode (`NfcAdapter.enableReaderMode`, NFC-A and NFC-B, without NDEF check), with presence checks spaced 2 s apart so as not to disturb a slow chip during PACE.

### 2.3 EF.COM then EF.SOD

- **EF.COM** (FID `01 1E`, tag `60`): LDS version and list of data groups present. Optional: if it is missing or unreadable, the list of data groups comes from the SOD. Its internal TLV lengths are checked against the actual file size before JMRTD (`BerStructure`): an EF.COM in which an element overflows its container is unreadable, and nothing is allocated (otherwise JMRTD allocates the announced length, up to 2 GB).
- **EF.SOD** (FID `01 1D`, tag `77`): *Document Security Object*, a CMS `SignedData` structure signed by the Document Signer (DS). It contains the hash algorithm, the hash of each data group present (`LDSSecurityObject`) and, usually, the DS certificate. Mandatory: a reading failure gives `UNEXPECTED-READ_DATA-SOD-…`.

### 2.4 Data groups

| DG | FID | Tag | Content | Reading |
|---|---|---|---|---|
| DG1 | `01 01` | `61` | MRZ | always (mandatory) |
| DG2 | `01 02` | `75` | Portrait (JPEG or JPEG 2000, ISO/IEC 19794-5 or 39794-5) | always requested; missing tolerated |
| DG3 | `01 03` | `63` | Fingerprints | **never** |
| DG4 | `01 04` | `76` | Iris | **never** |
| DG7 | `01 07` | `67` | Holder's handwritten signature (JPEG or JPEG 2000 image, decision D37) | if announced |
| DG11 | `01 0B` | `6B` | Additional personal details | if announced |
| DG12 | `01 0C` | `6C` | Additional document details (authority, date of issue, images) | if announced |
| DG14 | `01 0E` | `6E` | Security information (Chip Authentication, AA algorithm) | if announced |
| DG15 | `01 0F` | `6F` | Active Authentication public key | if announced |

Reading order (decision D20): DG14 first, if it is announced in EF.COM **or** in the SOD, then Chip Authentication (§2.6), then DG1, DG2, and DG15, DG11, DG12, DG7 if they are announced. DG3 and DG4 are protected by Terminal Authentication (Extended Access Control), which requires certificates issued by states: they are out of scope (SPEC §1) and are never requested (`DocumentReader` requests only DG14, `SECURITY_DATA_GROUP`, then DG1, DG2 and `OPTIONAL_DATA_GROUPS` = 15, 11, 12, 7; the end-to-end tests fail if the simulated chip receives a request for DG3 or DG4). The other data groups (DG5, DG6, DG8 to DG10, DG13, DG16) are not requested either.

A DG1 reading failure gives `UNEXPECTED-READ_DATA-DG1-…`. An optional data group (DG2 included) that is missing or unreadable is simply omitted: a chip without DG2 is read without a photo (decision D14). Before JMRTD, the TLV lengths of DG7, DG11, DG12 and DG2 are checked against the actual size of the data group (`BerStructure`, which reads headers the way SCUBA does), as is, in DG2, the length of each ISO 19794-5 image block: a data group in which an internal length overflows is unreadable, without any oversized allocation (catching `OutOfMemoryError` in `DataGroupParsers` remains only a safety net). DG1 is not concerned (JMRTD already rejects an abnormal MRZ length). A transport error (document removed, timeout) always interrupts reading.

The raw bytes of each data group read are kept in `DocumentData.rawDataGroups` to recompute the hashes, then zeroed by `wipe()` (see `docs/architecture.md`).

### 2.5 Passive Authentication (part 11 and part 12)

Performed by `PassiveAuthentication.verify(sod, dataGroups, trustStore, dateOfIssue, dateOfExpiry, documentCode)`, without access to the chip. It produces four lines of the checklist and the chain metadata (`ChainInfo`).

1. **DS certificate**: extracted from the SOD (the certificate that matches the `SignerIdentifier`). If it is missing there, it is looked up in the trust store: by (issuer, serial number) among the anchors, or by key identifier (SKI) if the `SignerIdentifier` has that form (decision D15). Not found: `SOD_SIGNATURE` and `CERTIFICATE_CHAIN` lines set to `NOT_AVAILABLE` with the detail `CheckDetail.DsCertificateMissing`, `DS_VALIDITY` set to `NOT_AVAILABLE`, hence the “Unknown issuer” verdict (this is not an exception: the report is produced); but if the trust store contains certificates of the issuing country of DG1, `CERTIFICATE_CHAIN` is `FAILED` (`NoChainForKnownCountry`, see step 2).
2. **Certificate chain** (`CERTIFICATE_CHAIN`): building and validation of the chain DS → any link certificates → self-signed CSCA from the trust store (`CertificateChains`). Candidate issuers are looked up by Authority Key Identifier (`findBySubjectKeyId`), then by name (`findBySubject`), discarding a candidate with the same name whose SKI differs from the AKI; 8 links at most. Only signatures count: the validity period of CSCAs and links is not checked, since a document remains valid up to ten years after its CSCA expires (decision D15). The link certificates traversed are listed in `ChainInfo.linkCertificates`. Result: `OK` (complete chain), `FAILED` (an issuer in the trust store matches but the signature is wrong), `UNSUPPORTED_ALGORITHM`, or `NOT_AVAILABLE` if no certificate in the trust store matches (decision D6): this is the “Unknown issuer” case. **Known country** (audit V25, decision D40): if the trust store contains at least one certificate (CSCA or link) of the issuing country of DG1, the lack of a chain (DS not found, or DS under an unknown CSCA) gives `FAILED` with the detail `CheckDetail.NoChainForKnownCountry(country, attempted chain)`, hence “Failed”: for a country whose CSCAs are known, a missing chain more likely points to a forgery than to a missing CSCA. **Unknown state code, DS from another country** (audit V35, decision D42): still without a chain, an issuing state in DG1 unknown to ICAO 9303-3 (neither a country nor an organization: `UTO`, `ZZZ`, `FR<`…) gives `FAILED` (`CheckDetail.Error("UNKNOWN_STATE_CODE")`), DS not found included, and a DS whose country (attribute C, missing included) differs from DG1 gives `FAILED` (`CountryMismatch`, CSCA country null). “Unknown issuer” is therefore reserved for real countries missing from the trust store and for organization codes (`UNO`, `EUE`…). **Search order** (audit V31, decision D42): candidate issuers are examined source by source (ANTS, national publications, built-in Master List, then imports), and the search stops at the first self-signed CSCA that verifies the certificate: imported decoys carrying the SKI of a built-in CSCA do not consume the verification budget (audit V15) of a chain that the built-in store resolves.
3. **SOD signature** (`SOD_SIGNATURE`): verification of the CMS signature with the DS public key, according to the algorithm declared in the SOD. The verifier is built on the key alone, so that a DS expired at signing time falls under the `DS_VALIDITY` line and not this one. Without a chain (no CSCA found, whatever the status of `CERTIFICATE_CHAIN`), a signature that the DS verifies proves nothing: the line is `NOT_AVAILABLE`, not `OK` (audit V25). The `SignerInfo` must carry signed attributes, including `messageDigest` and a `contentType` equal to `id-icao-ldsSecurityObject` (ICAO 9303-10): a SOD signed directly over its content is malformed, and all four lines are `FAILED` (`SOD_MALFORMED`; audit V40, decision D42).

**Key size** (audits V9 and V30, decision D42): before any computation, any public key (DS, issuers in the chain, then DG14, EF.CardSecurity, DG15 in §2.6, 2.6.1 and 2.7) is rejected if it falls outside the bounds of `Crypto.unsupportedKey`: RSA under 1,024 or over 8,192 bits, or with a public exponent over 64 bits; DH prime over 8,192 bits; EC base field over 1,024 bits. The keys of certificates and of EF.CardSecurity are checked in their encoded form, before the provider rebuilds them (rebuilding validates the key, at a cost that grows with its size). Result: `UNSUPPORTED_ALGORITHM` with the detail `UnsupportedAlgorithm("RSA-16384")`, `"RSA-2048/e-65"`, `"DH-9000"`, `"EC-2048"`, hence “Failed”.
4. **Data group hashes** (`DG_HASHES`): the hash of each data group read is recomputed with the algorithm declared in the SOD and compared. A data group that was read but is missing from the SOD counts as non-matching. The `DataGroupHashes` detail lists the data groups checked and those whose hash differs. A data group that the signed SOD announces among those Sceau reads (1, 2, 7, 11, 12, 14, 15: `PassiveAuthenticator.READ_DATA_GROUPS`) but that the chip does not supply is listed in `missing` and makes the line fail (audit V1: a clone must not be able to withhold a signed data group; DG7 since decision D37).

   **Known issuer deviations** (decision D34, `KnownDeviations` registry): a hash mismatch on DG11 or DG12, and only on them, is tolerated if it matches a deviation published by the issuer (Deviation List). The lookup only takes place if `SOD_SIGNATURE` and `CERTIFICATE_CHAIN` are `OK`, the hash of DG1 is verified and no signed data group is missing; the criterion relies on signed elements (CSCA country, DG1, SOD signingTime). The tolerated data group moves from `mismatched` to `deviations` (`KnownDeviation`, e.g. `IT-CIE3-DG12`), the line is `OK` if no other mismatch remains, and the data group is set aside (`PassiveAuthResult.discardedDataGroups`): removed from `DocumentData`, bytes and images zeroed, DG12 date of issue ignored in step 5. Only case to date: Italian CIE 3.0 cards with a wrong DG12 (`docs/trust-sources.md` §2.4).
5. **DS validity** (`DS_VALIDITY`): the validity period of the DS must cover the date of issue of the document, taken in this order:
   1. date of issue from DG12 (`IssuanceDateSource.DG12`);
   2. failing that, the `signingTime` attribute of the SOD (`SOD_SIGNING_TIME`);
   3. failing that, the expiry date from DG1 minus the usual validity period, set to 10 years for all documents (`ESTIMATED`), displayed as “estimated”.

   Date within the DS period (bounds included, in UTC): `OK`, whatever its source. Outside the period: `FAILED` for a date from DG12 or the SOD; `NOT_AVAILABLE` for an **estimated** date, never `FAILED` (decisions D6 and D15), because the usual period may be wrong (a minor's passport valid for 5 years instead of 10). No date available: `NOT_AVAILABLE`.

Certificate revocation lists (CRLs) are not consulted in v1: they require the network (SPEC §7.4).

### 2.6 Chip Authentication (part 11)

- Performed if DG14 is present and announces Chip Authentication (`ChipAuthenticationInfo`, `ChipAuthenticationPublicKeyInfo`), **after DG14 is read and before the other data groups are** (decision D20), during the `READ_DATA` step.
- Key agreement (DH or ECDH) between an ephemeral terminal key and the static chip key published in DG14; secure messaging is then renewed with the derived keys, and all subsequent commands (reading of DG1, DG2, DG15, DG11, DG12, DG7, then AA) go through this new channel.
- A cloned chip does not hold the private key: the renewed channel cannot be established. The exchange alone proves nothing until the chip has answered under the new channel: an explicit confirmation (SELECT of DG1 then READ BINARY of one byte, MAC of the responses verified) checks it before any data is read. It is kept, even though the DG1 reading that follows also goes through the new channel, so that the CA line is accurate: a confirmation failure is a CA failure (and the signal that the channel must be re-established), not a DG1 reading error that would interrupt the whole reading.
- Since DG14 is covered by the SOD, its public key is itself authenticated by Passive Authentication: a forged DG14 makes the hash line fail.
- **CA failed**: the `CHIP_AUTHENTICATION` line is `FAILED` (hence the “Failed” verdict), and reading continues so that the data can be displayed:
  - if the chip rejects the exchange (error SW on MSE or GENERAL AUTHENTICATE), JMRTD keeps the previous secure messaging; the same confirmation checks that the chip still answers under it, and the data groups are read under the previous channel;
  - if the chip no longer answers under the current secure messaging (confirmation failed after the key change, or chip that closed the session), the link is reset (`CardTransport.reconnect()`), a fresh JMRTD state is created, then the channel is re-established with the same key, within the same reading (EF.CardAccess, PACE or BAC, PACE → BAC fallback included: `SecureChannel.reestablish`), and the data groups are read under this channel. The key is not kept beyond the reading. Any error during this re-establishment, whatever it is (reconnection impossible, key rejected, timeout, document removed, `6982` from the restricted access of D36), does not interrupt reading with an error: since CA has already failed, a report is produced with `CHIP_AUTHENTICATION` `FAILED`, hence the “Failed” verdict, without any identity data (DG1 empty, `VerificationReport.identityRead` false: the Result screen shows neither identity nor photo); the other lines, not performed, are `NOT_AVAILABLE` with the code `READ_DATA-REESTABLISH-<error code>` (e.g. `READ_DATA-REESTABLISH-ACCESS_RESTRICTED-SELECT_APPLET`). A hostile chip thus cannot replace the CA failure with the restricted-access message (audit V24, decision D40).
- Result: `CHIP_AUTHENTICATION` line (`OK` with the detail `CheckDetail.ChipAuthentication(DG14)`, `FAILED`, `UNSUPPORTED_ALGORITHM`, or `NOT_AVAILABLE` without DG14 or with a DG14 that announces neither a CA key nor a CA protocol). The presence of a key is read from the raw bytes of DG14 (OID `id-PK-*`), not from what JMRTD made of it: a key that is announced but unusable (structure that JMRTD discards, unknown curve) gives `UNSUPPORTED_ALGORITHM` (audit V19, decision D40), as does a key outside the size bounds of §2.5, rejected before the key agreement, with no command sent (audit V30, decision D42). A DG14 that announces the CA protocol (`ChipAuthenticationInfo`, OID `id-CA-*`) without any `id-PK-*` key also gives `UNSUPPORTED_ALGORITHM` (detail: the `id-CA-*` OID), not `NOT_AVAILABLE`: CA is announced, but the application cannot perform it (audit V33, decision D43). CA error codes start with `READ_DATA-CA` (`READ_DATA-CA-DG14Missing`, `READ_DATA-CA_CONFIRM-…`).
- If PACE completed with the CAM mapping, the chip has already authenticated itself during PACE: CA via DG14 is not performed (§2.6.1). DG14, if announced, is still read and its hash verified.

#### 2.6.1 PACE-CAM, *Chip Authentication Mapping* (part 11 §4.4; decision D21)

With the CAM mapping (`id-PACE-ECDH-CAM-AES-CBC-CMAC-128/192/256`), the chip proves during PACE that it holds the static Chip Authentication private key: its last response contains, in addition to the authentication token, `A_IC = E(KSenc, CA_IC)` (tag `8A`), with `CA_IC = SK_IC⁻¹ · SK_Map,IC`. All subsequent commands go through the PACE channel: the data read is bound to the authenticated chip, without any other exchange.

1. **During the `SECURE_CHANNEL` step**: JMRTD performs PACE, verifies the chip's token and decrypts `CA_IC` (IV = `E(KSenc, −1)`, `PACECAMResult`); Sceau keeps `CA_IC` and the chip's mapping public key `PK_Map,IC`, then reads **EF.CardSecurity** (FID `01 1D` at the MF, under PACE secure messaging, before the application is selected; a cache separate from that of EF.SOD, which has the same FID within the application). According to the code of JMRTD 0.8.8 (a case not covered by a test), a chip that announces CAM but does not send `A_IC` makes PACE fail in JMRTD: fallback to BAC with an MRZ key (CA via DG14 is then performed), `ACCESS_DENIED` with a CAN.
2. **At the `VERIFY_CHIP` step**, offline, once the issuing state of DG1 is known (`ChipAuthenticationMapping`):
   - EF.CardSecurity (CMS `SignedData`, content `id-SecurityObject` = `0.4.0.127.0.7.3.2.1`, a single signer) is verified with **the same rules as the SOD** (`PassiveAuthenticator.verifyCardSecurity`): DS certificate embedded or looked up in the trust store, weak algorithms rejected (audit V9), CMS signature, DS → CSCA chain with issuer profile and DS keyUsage (V8), countries consistent with DG1 (V3). The calendar validity of the DS is not checked there: it is checked for the SOD's DS (§2.5);
   - the `PK_IC` key is taken from the `ChipAuthenticationPublicKeyInfo`s of the signed content; its size is checked in encoded form, before JMRTD rebuilds it, and that of `PK_Map,IC` before any computation (bounds of §2.5, audit V30);
   - check `PK_Map,IC = KA(CA_IC, PK_IC, D_IC)`: `CA_IC · PK_IC` in ECDH, `PK_IC^CA_IC mod p` in DH, over the domain parameters of `PK_IC`, which must be those of `PK_Map,IC`, with `CA_IC` in `[1, n − 1]`. The algorithm comes from the keys; a key that is neither EC nor DH, or out of bounds, gives `UNSUPPORTED_ALGORITHM`.
3. **Result**, `CHIP_AUTHENTICATION` line (no line of its own):

| Situation | Status | Detail |
|---|---|---|
| everything verified | `OK` | `ChipAuthentication(PACE_CAM)` |
| EF.CardSecurity missing or unreadable | `FAILED` | `VERIFY_CHIP-CAM-CARD_SECURITY_MISSING` |
| EF.CardSecurity malformed (content type, signers, signed attributes missing: audit V40) | `FAILED` | `VERIFY_CHIP-CAM-CARD_SECURITY_MALFORMED` |
| EF.CardSecurity signature wrong | `FAILED` | `VERIFY_CHIP-CAM-CARD_SECURITY_SIGNATURE` |
| chain wrong, DS without digitalSignature, budget exceeded | `FAILED` | `VERIFY_CHIP-CAM-CARD_SECURITY_CHAIN` |
| inconsistent countries | `FAILED` | `VERIFY_CHIP-CAM-CARD_SECURITY_COUNTRY` |
| valid chain, but to a different CSCA than the SOD's (neither the same certificate nor the same key; audit V17, decision D40) | `FAILED` | `VERIFY_CHIP-CAM-CARD_SECURITY_ANCHOR` |
| no known CSCA for EF.CardSecurity, whereas the SOD chain succeeds | `FAILED` | `VERIFY_CHIP-CAM-CARD_SECURITY_UNKNOWN_ISSUER` (or `…_DS_MISSING`) |
| no known CSCA, neither for EF.CardSecurity nor for the SOD | `NOT_AVAILABLE` | `ChipAuthentication(PACE_CAM)`; “Unknown issuer” verdict |
| no `ChipAuthenticationPublicKeyInfo` | `FAILED` | `VERIFY_CHIP-CAM-NO_CHIP_KEY` |
| `CA_IC` or `PK_Map,IC` missing (decryption failed) | `FAILED` | `VERIFY_CHIP-CAM-NO_CHIP_AUTHENTICATION_DATA`, `…-NO_MAPPING_KEY` |
| KA check wrong | `FAILED` | `VERIFY_CHIP-CAM-KEY_MISMATCH` |
| unknown algorithm (signature, chain, key type), key out of bounds (audit V30) | `UNSUPPORTED_ALGORITHM` | `UnsupportedAlgorithm` |

The verdict is computed as for CA via DG14 (§6). CA via DG14 is not performed, even if CAM fails: the line has then already failed, and the verdict is “Failed”.

### 2.7 Active Authentication (part 11)

- Performed if DG15 is present.
- **8-byte nonce drawn from a `SecureRandom`**, sent with `INTERNAL AUTHENTICATE`.
- The response is verified by `ActiveAuthentication.verifyResponse(publicKey, digestAlgorithm, challenge, response)` with the DG15 public key, rejected outright (`UNSUPPORTED_ALGORITHM`) if its size falls outside the bounds of §2.5 (audits V9 and V30):
  - RSA key: ISO/IEC 9796-2 scheme 1 signature, hash function designated by the trailer (implicit `BC`: SHA-1; explicit: SHA-1 to SHA-512, RIPEMD, Whirlpool);
  - EC key: ECDSA (raw `r‖s` response), with the hash algorithm announced in DG14 (`ActiveAuthenticationInfo`). If DG14 does not announce one (a non-standard case encountered in practice), SHA-1, SHA-224, SHA-256, SHA-384 and SHA-512 are tried in that order (decision D15).
- A lost link during CA or AA interrupts reading; any other chip error is recorded in the corresponding line.
- Result: `ACTIVE_AUTHENTICATION` line (`OK`, `FAILED`, `UNSUPPORTED_ALGORITHM`, or `NOT_AVAILABLE` without DG15).

### 2.8 Report

Immutable `VerificationReport`: verdict, exactly one `Check` per `CheckId` in enumeration order, the SOD's `ChainInfo` and, with PACE-CAM, that of EF.CardSecurity (`cardSecurityChain`, without personal data; the screen flags an imported CSCA on either chain, audit V17), and `DocumentData` (data read). Its `toString()` reveals only the verdict. It is never persisted. SPEC §6.2 allows it to be serializable for tests; no test has needed it, and it implements no serialization.

## 3. Rules (SPEC §6.2)

- **No hard-coded algorithm.** Signature and curve are those declared by the certificates and the SOD: RSA PKCS#1 v1.5, RSA-PSS, ECDSA on any curve known to BouncyCastle, and any algorithm BouncyCastle adds in the future. An unknown algorithm gives the status `UNSUPPORTED_ALGORITHM` with the detail `CheckDetail.UnsupportedAlgorithm(algorithm)` (name or OID), never a silent failure.
- **Not available is not failed.** A step that does not take place (DG14 or DG15 missing, step not reached) is `NOT_AVAILABLE`, distinct from `FAILED`.
- **No personal data** in exceptions, logs or error identifiers. The classes in `model/` are not `data class`es and their `toString()` is masked; `AccessKey` shows `***`.
- **No Android reference** in the report or in `:core`.

## 4. Displayed steps: mapping to `Step`

`progress(step)` is called at the start of each step; the previous steps are then complete, and the return of `readAndVerify` marks the end of the last one.

| `Step` | Label (SPEC §5.2) | Operations (§2 above) |
|---|---|---|
| `CONNECT` | Connecting to the chip | 2.1 reading of EF.CardAccess; selection of the ICAO application in plain text if PACE is not announced |
| `SECURE_CHANNEL` | Opening secure channel (PACE or BAC) | 2.2 PACE then selection of the application under secure messaging, or BAC (fallback included) |
| `READ_DATA` | Reading data | 2.3 EF.COM, EF.SOD; DG14; 2.6 Chip Authentication; 2.4 other data groups |
| `VERIFY_SIGNATURE` | Verifying signature | 2.5 Passive Authentication |
| `VERIFY_CHIP` | Verifying chip | 2.6.1 offline verification of PACE-CAM; 2.7 Active Authentication (CA via DG14 is already done, D20) |

## 5. Errors: mapping to `SceauException`

Only these errors interrupt reading; the reading screen shows a message in the interface language, the **full code in small print, for all errors** (decision D11), and a “Try again” button (SPEC §5.2), except for `ACCESS_RESTRICTED`, where a new attempt would change nothing (decision D36: only “Cancel” is offered, `ReadingErrors.canRetry`). The message is chosen from the base code, before the first `-` (`ReadingErrors.messageFor`).

| Exception | `code` | Cause | Reading screen message |
|---|---|---|---|
| `NotIcaoDocument` | `NOT_ICAO` | no ICAO application (`6A82` on selection) | Document not compliant with ICAO 9303 |
| `AccessDenied` | `ACCESS_DENIED` | key rejected by PACE (SW `63xx`) or BAC failure; with a CAN, any PACE failure other than a transport error | Incorrect CAN or MRZ, with a reminder that a failed attempt can lengthen the chip's delay |
| `CanWithoutPace` | `CAN_WITHOUT_PACE` | CAN supplied, PACE not announced | Use the Passport tab and the MRZ |
| `AccessRestricted` | `ACCESS_RESTRICTED-<STEP>-<element>` | secure channel established (PACE or BAC succeeded), then SW `6982` (*security status not satisfied*) on selecting the ICAO application under secure messaging (`SELECT_APPLET`) or on reading EF.SOD (`SOD`) or DG1 (`DG1`): chip reserved for government terminals (Terminal Authentication), like German identity cards issued before 2021-08-02 (decision D36). A `6982` before authentication (plain-text selection before BAC) keeps the code `UNEXPECTED-…-6982`; on EF.COM, DG2, DG7, DG11, DG12, DG14 or DG15, the file is treated as missing. When re-establishing the channel after a failed CA, it is no longer an error: “Failed” report (§2.6, audit V24) | Chip reserved for authorized officials, trying again won't change anything (case of German cards from before August 2021), without a “Try again” button |
| `ConnectionLost` | `CONNECTION_LOST[-<diag>]` | document removed (`TagLostException`, link cut, “Tag is out of date”), reading cancelled, reconnection impossible | Document moved away too soon |
| `Timeout` | `TIMEOUT[-<diag>]` | no response within the timeout (failure occurring after 90% of the effective timeout, or `IsoDep` timeout message); global timeout of 120 s outside authentication exceeded (`TIMEOUT-<STEP>-GLOBAL`, D41) | Timed out |
| `ReadStalled` | `READ_STALLED-<STEP>-<file>` | the chip no longer delivers the current file: 3 consecutive empty `READ BINARY` responses, or more `READ BINARY` commands than the file ceiling justifies (§2.2, audit V29, D41) | Timed out, with “Try again” |
| `Unexpected` | `UNEXPECTED-<diag>` | any other error | Unexpected error |

The codes are stable and contain no personal data: no data read, no key, no APDU byte, no raw exception message. The `<diag>` diagnostic is built as follows:

| Origin | Form | Example |
|---|---|---|
| Timeout or lost link during an APDU (`IsoDepTransport`) | `<STEP>-INS<xx>-L<n>`: step (`Step`), INS byte of the command in hexadecimal, APDU length rounded to the nearest ten | `TIMEOUT-SECURE_CHANNEL-INS86-L10`, `CONNECTION_LOST-READ_DATA-INSB0-L20` |
| PACE → BAC fallback reconnection impossible | `<STEP>-RECONNECT` | `CONNECTION_LOST-SECURE_CHANNEL-RECONNECT` |
| NFC I/O error that is neither a timeout nor a lost link | `<STEP>-IO-<code>-INS<xx>-L<n>`, `<code>` taken from a closed list of `android.nfc` messages: `TRANSCEIVE_FAILED`, `TOO_LONG`, `SERVICE_DIED`, otherwise `OTHER` or `NO_MESSAGE` | `UNEXPECTED-SECURE_CHANNEL-IO-TRANSCEIVE_FAILED-INS86-L10` |
| Transport closed without request | `<STEP>-STATE-INS<xx>-L<n>` | `UNEXPECTED-READ_DATA-STATE-INSB0-L20` |
| Failure in `:core` (`technicalCode`) | `<STEP>[-<label>]-<class>[-<SW>]`: label `SELECT_APPLET`, `SOD` or `DG1`, simple name of the exception class, status word in hexadecimal | `UNEXPECTED-CONNECT-SELECT_APPLET-CardServiceException-6D00` |
| Error outside `readAndVerify` in the app (`SessionViewModel`) | `<class>` | `UNEXPECTED-OutOfMemoryError` |
| Restricted access (D36) | `<STEP>-<element>`: rejected element `SELECT_APPLET`, `SOD` or `DG1` | `ACCESS_RESTRICTED-SECURE_CHANNEL-SELECT_APPLET`, `ACCESS_RESTRICTED-READ_DATA-SOD` |
| Reading that no longer progresses (D41) | `<STEP>-<file>`: `CARD_ACCESS`, `COM`, `SOD`, `DG<n>` | `READ_STALLED-CONNECT-CARD_ACCESS`, `READ_STALLED-READ_DATA-DG2` |
| Global timeout exceeded (D41) | `<STEP>-GLOBAL`: step in progress when it expires | `TIMEOUT-READ_DATA-GLOBAL` |

A `CONNECTION_LOST` or `TIMEOUT` without a diagnostic remains possible (test transport, closing before any APDU). No JMRTD exception is attached as a cause to a `SceauException`: their messages contain APDUs in hexadecimal (decision D14).

The following situations **are not** exceptions and appear in the report: DS certificate not found (`CheckDetail.DsCertificateMissing`), unsupported algorithm (`UNSUPPORTED_ALGORITHM`), invalid signature or hash, failed CA or AA challenge (`FAILED`), unknown issuer (`NOT_AVAILABLE`).

## 6. Verdict

Computed by `Verdicts.compute(checks)` from the seven lines of the checklist (SPEC §5.3, decision D6). The rules apply **in order**, and the first one that applies wins:

| # | Condition | Verdict |
|---|---|---|
| 1 | at least one line `FAILED` or `UNSUPPORTED_ALGORITHM` | **Failed** (`FAILED`) |
| 2 | otherwise, `CERTIFICATE_CHAIN` is `NOT_AVAILABLE` (no certificate of the issuing country in the trust store, real country or organization) | **Unknown issuer** (`UNKNOWN_ISSUER`) |
| 3 | otherwise, PA passed and (`CHIP_AUTHENTICATION` or `ACTIVE_AUTHENTICATION` is `OK`) | **Authentic** (`AUTHENTIC`) |
| 4 | otherwise, PA passed, `CHIP_AUTHENTICATION` and `ACTIVE_AUTHENTICATION` are `NOT_AVAILABLE` | **Valid signature, chip not verified** (`SIGNATURE_VALID_CHIP_UNVERIFIED`) |
| 5 | any other case | **Failed** (`FAILED`) |

“PA passed” means: `SECURE_CHANNEL`, `SOD_SIGNATURE`, `CERTIFICATE_CHAIN` and `DG_HASHES` are `OK`, and `DS_VALIDITY` is `OK` or `NOT_AVAILABLE` (date of issue unknown, or estimated outside the DS period: decision D6). A missing line, or `SOD_SIGNATURE` at `NOT_AVAILABLE` outside the “Unknown issuer” case, falls under rule 5.

Consequences:

- a failed challenge prevails over everything else: a successful CA and a failed AA give “Failed”;
- an issuer missing from the trust store gives “Unknown issuer” even if the hashes are consistent: nothing is guaranteed, the data is displayed; the SOD signature is “not available” in that case;
- a document from a country for which the trust store holds certificates, but for which no chain succeeds, gives “Failed” (`CERTIFICATE_CHAIN` `FAILED`, `NoChainForKnownCountry`; audit V25, decision D40), not “Unknown issuer”; likewise, without a chain, a state code in DG1 unknown to ICAO (`UNKNOWN_STATE_CODE`) or a DS from a country other than that of DG1 (`CountryMismatch`) gives “Failed” (audit V35, decision D42);
- the `CHIP_AUTHENTICATION` and `SECURE_CHANNEL` lines can also be made to fail by a protocol downgrade (§2.2), a CA key or protocol that is announced but unusable (§2.6), an EF.CardSecurity chain that does not lead to the SOD's CSCA (§2.6.1) or a channel that cannot be re-established after a failed CA (§2.6): audits V17, V19, V20, V24, V32, V33, decisions D40 and D43;
- an expired document does not affect the verdict: the result screen shows a “Document expired” banner (SPEC §5.3).
- a DG11 or DG12 hash mismatch covered by a known issuer deviation (§2.5, decision D34) is not a failure: `DG_HASHES` is `OK` and the verdict is computed normally, “Authentic” included; the data of the data group set aside is not displayed.
