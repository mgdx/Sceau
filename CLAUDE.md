# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository status

Milestones reached (SPEC §11): Foundation, Reading, Verification and most of Finishing (DG11/DG12, Trust store screen and import, About, memory wiping, `FLAG_SECURE`, en-US translations). Real reading of a French passport was validated on a Fairphone 3 on 2026-09-25. Publication milestone in progress: first public release **1.0.0** published on 2026-10-12 on GitHub, F-Droid submission still to do (`versionName = "1.0.0"`, `versionCode = 1`, tag `v1.0.0`, D53), license GPL-3.0-only (D51), repository https://github.com/mgdx/Sceau, universal APK for F-Droid and ABI splits for GitHub releases (D38). Release procedure (checks, signing outside the repository, assets, tag, F-Droid): `docs/release.md`.

`SPEC.md` is the source of truth: read it in full before any change. Any departure is recorded and justified in `docs/decisions.md` (D1 to D54 so far). The documentation lives in `docs/`: `architecture.md`, `protocol.md` (actual ICAO sequence, timeouts, error codes), `trust-store.md`, `deviations.md` (known issuer deviations and per-country limitations), `dependencies.md`, `test-plan.md`, `decisions.md`, `release.md`, `reproducible-builds.md`, `fdroid/` (draft fdroiddata recipe). The repository documentation is in English; the interface is in English by default, with French and 43 other languages (D55); the translation glossaries are in `docs/traduction/`.

## What Sceau is

Free Android application (GPLv3, targeting F-Droid) that reads, over NFC, the chip of ICAO 9303 documents (French CNIe, EU identity cards, passports), displays DG1/DG2/DG7/DG11/DG12 (DG7, handwritten signature, if announced: D37) and verifies authenticity: Passive Authentication against a built-in CSCA store, then Chip Authentication (DG14) and Active Authentication (DG15). Fully offline, with no persistence of the data read.

## Commands

Kotlin Gradle project (AGP 9, built-in Kotlin), three modules: `:core`, `:app`, `:testchip`. JVM toolchain 17; the Gradle daemon runs on JDK 25 (`gradle/gradle-daemon-jvm.properties`). Versions in `gradle/libs.versions.toml`.

The JPEG 2000 decoder (OpenJPEG) is a git submodule: after cloning, `git submodule update --init` (or `git clone --recurse-submodules`). Native compilation requires NDK `28.2.13676358` and CMake `4.1.2`.

```bash
./gradlew :core:test                                                   # core JVM tests
./gradlew :core:test --tests "io.github.mgdx.sceau.core.SomeTest"       # a single test
./gradlew :testchip:test                                               # simulated chip tests
SCEAU_FUZZ_ITERATIONS=200000 ./gradlew :core:test --tests '*fuzz*' --rerun   # long fuzzing of the parsers (docs/architecture.md §5)
SCEAU_SCREENSHOTS=1 ./gradlew :app:testDebugUnitTest --tests '*StoreScreenshots*' --rerun   # F-Droid screenshots fr-FR/en-US in fastlane/ (D39)
./gradlew :app:testDebugUnitTest                                        # app JVM tests (including demo mode)
./gradlew :app:assembleDebug                                            # debug APK (demo mode included)
./gradlew :app:assembleRelease                                          # release APKs (R8), unsigned
./gradlew lint ktlintCheck                                              # zero warnings required
./gradlew ktlintFormat                                                  # reformats according to .editorconfig
./gradlew check                                                         # everything that blocks CI
./gradlew :app:dependencies --configuration releaseRuntimeClasspath     # dependency audit
scripts/check-reproducible.sh                                           # double release build, byte-for-byte identical APKs (D25)
scripts/update-trust-store.sh --ants-dir DIR [--apply]              # checks the trust store sources (at each release)
app/src/main/cpp/test/run-host-tests.sh                                 # host tests of the JPEG 2000 decoder (ASan, UBSan)
```

CI rule (`.github/workflows/ci.yml`: checkout with submodules, then `./gradlew check :app:assembleDebug`): Kotlin warnings treated as errors (`allWarningsAsErrors`), lint in `warningsAsErrors`, ktlint blocking. Disabled lint rules are listed and justified in the `build.gradle.kts` files, `app/lint.xml` and `docs/decisions.md` (D8, D17). R8 rules in `app/src/main/keepRules/rules.keep`.

ABI splits (decision D2): one APK per architecture (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`) plus a universal APK, each under the 8 MB target, Master List included. GitHub releases publish all five; F-Droid publishes only the universal one (D38).

## Architecture

**`:core` — pure Kotlin, no `android.*` import, tested on the JVM** (package `io.github.mgdx.sceau.core`). Single entry point:

```kotlin
suspend fun readAndVerify(transport: CardTransport, key: AccessKey, trustStore: TrustStore, progress: (Step) -> Unit): VerificationReport
```

- `CardTransport` is an abstract interface (`transceive`, `maxTransceiveLength`, `timeoutMillis`, `reconnect`, `close`); `:app` implements it on top of `IsoDep` (`IsoDepTransport`), the tests use a transport that replays recorded APDUs or the simulated chip from `:testchip`.
- Actual sequence (`docs/protocol.md`): EF.CardAccess read at MF level → without PACE, selection of the ICAO AID in plain then BAC (MRZ); with PACE, PACE (CAN or MRZ) at the MF then selection under secure messaging → EF.COM, EF.SOD, DG14 if announced → CA if DG14 (D20: before reading the data, which is then read under the CA keys; if the chip stops responding after a failed CA, channel re-established with the same key) → DG1, DG2, then DG15/DG11/DG12 if announced → PA (DS→CSCA chain with link certificates, SOD signature, DG hashes, DS validity at the date of issue) → AA if DG15 → immutable `VerificationReport`. With PACE-CAM (D21), EF.CardSecurity is verified like the SOD and stands in for CA; CA via DG14 is not repeated.
- During PACE and BAC, response timeout raised to 60 s (chip that makes the reader wait after failed attempts), with no fallback to BAC after a timeout. With an MRZ key, if PACE fails other than by a key refusal or a timeout, reconnection then BAC (D12, D13). Short APDUs only (≤ 256), missing DG2 tolerated (D14).
- DG3 and DG4 are never read. A CAN without PACE announced is an explicit error (`CAN_WITHOUT_PACE`). A document without an ICAO AID gives `NotIcaoDocument`.
- Algorithms are never hardcoded: whatever the certificates and the SOD declare is accepted via BouncyCastle; an unknown algorithm gives `UNSUPPORTED_ALGORITHM`. A missing step (no DG14/DG15) is distinct from a failed step.
- Known issuer anomalies (D34): `verify/KnownDeviations` registry, hardcoded rules, tolerance limited to a hash mismatch on DG11/DG12 for a valid SOD and chain, criterion based on signed elements; the tolerated DG is dropped from the report. Only rule: `IT-CIE3-DG12` (Italian CIE 3.0), expiring on 2029-03-05: a test fails after that date to force its removal.
- Verdicts (`Verdicts.compute`, table in `docs/protocol.md` §6): Authentic (PA + CA or AA ok), Valid signature chip not verified (PA ok, neither DG14 nor DG15), Unknown issuer (chain without a known CSCA), Failed.
- Errors: `SceauException` with a stable code without personal data, followed by a diagnostic (step, INS, rounded length, I/O subtype); the reading screen displays this code for all errors (D11). `ACCESS_RESTRICTED` (D36): `6982` after a secure channel is established, on application selection or on EF.SOD or DG1 (German cards issued before August 2021, reserved for authorities); dedicated message, without “Try again”. German eID-UB card (DG1 “UB”, state “D”): identity replaced by a banner and photo hidden on the result screen (D36).
- Trust store in `core/src/main/resources/trust/`, enumerated by `trust/index.txt`: 5 ANTS CSCA certificates (`ants-csca-2010/2015/2020/2025.der`, `ants-csca-eid-2021.der`), 5 certificates published by their own State (`<country>-csca-*.der`: GB, GE, LU; source `NATIONAL`, D26) and the German Master List from the BSI `de-bsi-master-list.ml` (signed CMS, rejected if the signature is invalid or if its signer is not issued by the German CSCA whose fingerprint is pinned in `TrustStoreLoader`, decision D1). `TrustStoreFingerprintTest` compares the SHA-256 fingerprints of the built-in files with those in `docs/trust-store.md`. Master Lists imported by the user are merged and marked “Imported”. The store is preloaded when the app starts (D16).

**`:testchip` — Kotlin JVM, never in the release APK** (package `io.github.mgdx.sceau.testchip`): fake PKI (CSCA, link, DS, SOD, CA/AA keys in RSA and EC), stateful simulated chip (BAC, PACE, secure messaging, CA, AA) written without JMRTD's protocol code, specimen CNIe. `testImplementation` of `:core`, `debugImplementation` of `:app`. See `testchip/README.md` and D17.

**`:app` — NFC, Compose/Material 3, Navigation Compose** (package `io.github.mgdx.sceau`). Screens: Home (Passport / ID card tabs, Passport by default, dates typed on the keyboard in the locale's order, D10; keyboard dismissed after the expiry date, D29), Reading (checked-off steps, waiting message after 5 s of authentication, errors with code and Try again), Result (verdict, photo, identity, DG11/DG12, checklist, Clear button), Trust store (list, import of a Master List or of a standalone CSCA/link certificate via `ACTION_OPEN_DOCUMENT`, deletion of each imported item one by one, D33), About (with “What the chip contains” and “Replay the introduction”). NFC detection by polling (`nfc/NfcStatus.kt`, `nfcAvailabilityFlow`): state reread every second while the app is in the foreground, with no broadcast receiver at all; `MainActivity` re-enables reader mode each time the state switches to enabled (NFC turned back on from Quick Settings), and the “NFC is turned off” banner (`nfc/NfcBanner`) is shown on the home screen and on the reading screen. 4-page introduction on first launch, `intro_seen` preference (`ui/learn` package, D35). JPEG 2000 decoded by OpenJPEG over JNI (`jp2/Jpeg2000Decoder`, `app/src/main/cpp/`, D3), only in the `jp2/Jpeg2000Service` service running as an `isolatedProcess` (D23): the native library is never loaded in the main process. Imported Master Lists limited to 10 and 40 MB in total (D22), standalone imported certificates to 100 of 64 KiB at most (D33). Logo palette, dynamic colors disabled (D18).

**Demo mode (debug APK only)**: home menu → “Simulate a French ID card (demo)” reads the simulated CNIe from `:testchip` in the real interface, with its own test store (never mixed with the real store) and the “Simulated document” banner. `DemoMode` exists in a `src/debug` version and an empty `src/release` version.

## Dependencies

JMRTD and SCUBA (`scuba-smartcards`, `scuba-sc-android`; LGPL 2.1 or later), BouncyCastle `bcprov`/`bcpkix` `jdk18on` (never SpongyCastle), OpenJPEG (BSD-2-Clause) compiled from the `app/src/main/cpp/openjpeg` submodule for JPEG 2000 (jj2000 ruled out: non-free license, D3), Compose/Material 3/Navigation. Any other dependency must be justified in `docs/dependencies.md` (license verified on the artifact, F-Droid compatibility). No Google service, no binary blob.

## Non-negotiable constraints

- Manifest: `android.permission.NFC` permission only (the internal permission added by androidx.core is removed with `tools:node="remove"`, D7), no network permission, `android.hardware.nfc` with `required="false"`. minSdk 26.
- No data read is written to disk, cache, database or log, in debug or in release. **No logging, not even temporarily for debugging**: no `Log`, no `println`, no `printStackTrace`; diagnosis goes through the displayed error code. The JMRTD and SCUBA loggers are turned off, and no JMRTD exception is attached as a cause (their messages contain APDUs). No personal data in exceptions or error identifiers.
- `FLAG_SECURE` set on the whole activity from its creation (`secureForLifetime`), hence on every screen, home included; Reading, Result and the full-screen photo also request it (`SecureWindow()`). DG1/DG2/DG7/DG11/DG12 byte arrays zeroed and released on leaving the result screen and in the background. CAN/MRZ never remembered between two reads.
- AA nonce drawn from a `SecureRandom`.
- Interface in English by default (fallback for untranslated languages, D55), no hardcoded string: English strings split per screen in `res/values/strings_<screen>.xml` (D5), translations in `values-fr` and 43 other languages (D27), offered in `res/xml/locales_config.xml`, which must be kept in sync with the `values-*` folders (lint fails otherwise). Glossaries in `docs/traduction/`.
- No telemetry or crash reporting.

## Project skills

`android-securite` before each release, `android-test` for the `docs/test-plan.md` plan on a real device, `android-fdroid` before the first submission, `android-supervision` to parallelize a milestone across several agents.
