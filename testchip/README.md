# `:testchip` — simulated ICAO 9303 chip and fake PKI

Pure Kotlin JVM module, **never shipped in production**. It is used:

- by the `:core` tests (`testImplementation(project(":testchip"))`);
- by the demo mode of the **debug-only** APK (`debugImplementation(project(":testchip"))` in
  `app/build.gradle.kts`): home menu → “Simulate a French ID card (demo)”, wired in by
  `app/src/debug/java/…/demo/DemoMode.kt`; the `app/src/release/` version of `DemoMode` is empty.

`:core` (main) does not depend on this module, and `./gradlew :app:dependencies --configuration
releaseRuntimeClasspath` does not mention it (decision D17).

## Contents

| Element | Role |
|---|---|
| `TestPki`, `TestCredential`, `TestKeyType` | Fake CSCA hierarchy (old, new, link) → DS, RSA or EC, reproducible keys (seed). |
| `TestDocument`, `TestSod`, `SodOptions` | Fake DGs and signed EF.SOD, including the alterations for failure cases. |
| `TestCardSecurity` | EF.CardSecurity signed by the DS (PACE-CAM), same alterations as the SOD. |
| `TestTrustStore` | Test `TrustStore` containing the chosen CSCAs. |
| `SimulatedChip` | `CardTransport` that behaves like a chip: EF.CardAccess, PACE, BAC, 3DES/AES secure messaging, file reading, Chip Authentication, Active Authentication, resettable link (`reconnect`). Records under which session each file is served (`fileReads`, `sessionsServing`). Can reserve the applet or files for authorities (`restrictedApplet`, `restrictedFiles`: SW 6982, D36). |
| `PaceSettings`, `PacePassword` | PACE of the simulated chip (EF.CardAccess, CAN). |
| `SimulatedDocuments.frenchIdCard()` | Simulated French CNIe ready to read (see below). |
| `SimulatedDocuments.germanEidUb()` | German eID card for Union citizens (eID-UB): DG1 without identity data (MRZ “UBD<<<…”), CA without AA (decision D36). |

All the chip-side cryptographic exchanges (BAC, PACE, secure messaging, CA) are written
from ICAO 9303-11 and BSI TR-03110 with BouncyCastle primitives, **without** JMRTD's protocol
code: an end-to-end test therefore genuinely pits JMRTD (used by Sceau) against an
independent implementation.

## Simulated CNIe

```kotlin
val card = SimulatedDocuments.frenchIdCard()          // CAN 123456 by default
val report = readAndVerify(card.chip(), card.canKey!!, card.trustStore) { }
// or card.mrzKey for PACE with the MRZ key
```

- EF.CardAccess: `PACEInfo` id-PACE-ECDH-GM-AES-CBC-CMAC-128, standardized parameters 13
  (brainpoolP256r1). BAC remains accepted (ICAO transition).
- DG1: TD1 MRZ, code “ID”, FRA, `SPECIMEN` / `MARIANNE`, document `SPEC12345`.
- DG2: synthetic JPEG 2000 portrait (`specimen-portrait.jp2`), ISO 19794-5.
- DG7: synthetic handwritten signature (`specimen-signature.jpg`, grayscale JPEG,
  300 × 100), read and displayed since decision D37.
- DG11: fictitious full name, place of birth and address. DG12: “PREFECTURE DE TEST”,
  issued on 15/03/2024.
- DG14: ECDH brainpoolP256r1 CA key, AES-128 `ChipAuthenticationInfo`,
  ECDSA SHA-256 `ActiveAuthenticationInfo`. DG15: EC AA key.
- EF.SOD: SHA-256, signed by `DS-TEST-FRANCE`, issued by `CSCA-TEST-FRANCE` (EC brainpoolP256r1).
  These certificates are generated on the fly and appear in no real store.

Each call to `chip()` returns a fresh chip: a simulated chip has state, one per read.

### Simulated PACE (fidelity)

MSE:Set AT (`00 22 C1 A4`, objects 80/83/84), then GENERAL AUTHENTICATE in four chained
steps: encrypted nonce `z = E(Kπ, s)` with `Kπ = KDF(f(π), 3)` (f(π) = CAN in ISO 8859-1, or
SHA-1 of the MRZ information over 20 bytes), generic ECDH mapping `G' = s·G + H`, ephemeral
key agreement on `G'`, AES-CMAC tokens over `7F49{06 OID, 86 point}`, session keys `KDF(K, 1)` and
`KDF(K, 2)`, SSC = 0. A wrong reader token (wrong CAN) gives `63Cx` (x = remaining attempts,
3 at the start) at the last step. Not simulated: integrated mapping (IM), finite-field DH, 3DES,
proprietary domain parameters, password blocking.

PACE-CAM (`PaceSettings(can, protocolOid = ID_PACE_ECDH_CAM_AES_CBC_CMAC_128)`): same exchange,
but the last response also contains `8A A.IC`, `A.IC = E(KSenc, CA.IC)` (AES-CBC, IV
`E(KSenc, −1)`), `CA.IC = SK.IC⁻¹ · SK.PICC.map mod n` with the chip's CA key. EF.CardSecurity
(`TestCardSecurity.of(document, pace)`: `PACEInfo`, `ChipAuthenticationInfo`,
`ChipAuthenticationPublicKeyInfo`, signed by the DS) is passed to `SimulatedChip(cardSecurity = …)`
and served at the MF under secure messaging only. `tamperChipAuthenticationMapping` makes the chip
send `CA.IC + 1`.

## Synthetic portrait

`src/main/resources/specimen-portrait.jp2` (240 × 320, about 9 KB): gradient background and
anonymous silhouette drawn by a script, **no photo of a person**. To regenerate it:

```bash
git submodule update --init                       # OpenJPEG submodule
testchip/tools/generate-specimen-portrait.sh      # builds opj_compress outside the repository
```

The script builds `opj_compress` from `app/src/main/cpp/openjpeg` in a temporary
directory, draws the image as PPM with `python3`, then encodes it:
`opj_compress -i specimen.ppm -o specimen-portrait.jp2 -r 25 -I` (JP2, lossy 9/7
compression, rate 25). It fails if the file exceeds 20 KB.

## Synthetic signature

`src/main/resources/specimen-signature.jpg` (300 × 100, grayscale, about 3.5 KB):
a scribble made of Bézier curves drawn programmatically in black on white, **no real
signature**. To regenerate it, from the repository root (JDK 17 or later):

```bash
java -Djava.awt.headless=true testchip/tools/GenerateSpecimenSignature.java
```

The program fails if the file exceeds 8 KB. `TestDataGroups.dg7()` wraps it in a DG7
(ICAO 9303-10) built with JMRTD; the simulated CNIe carries it (decision D37).
