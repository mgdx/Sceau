# Architecture

This document describes how Sceau's code is organized, the public API of the `:core` module, the flow of data from reading to display, and the lifecycle of sensitive data. The ICAO sequence itself is detailed in `docs/protocol.md`.

## 1. Modules

```
:app   Android: NFC (IsoDep), Compose / Material 3 UI, navigation, lifecycle
  │  │
  │  │ debugImplementation (debug APK only: demo mode)
  │  ▼
  │ :testchip  Kotlin JVM: fake PKI, simulated ICAO chip, specimen French ID card
  │  │          (also testImplementation of :core)
  │  │ implementation
  ▼  ▼
:core  Pure Kotlin (JVM 17): reading, verification, trust store, models
```

| Module | Package | Role | Main dependencies |
|---|---|---|---|
| `:core` | `io.github.mgdx.sceau.core` | ICAO 9303 sequence, Passive / Chip / Active Authentication, trust store, data models, verdict | JMRTD, SCUBA (smartcards), BouncyCastle, kotlinx-coroutines |
| `:app` | `io.github.mgdx.sceau` | `IsoDep` transport, screens, navigation, reading session, storage of imported Master Lists | `:core`, scuba-sc-android, Compose, Material 3, Navigation Compose, Lifecycle; `:testchip` in debug only |
| `:testchip` | `io.github.mgdx.sceau.testchip` | Fake PKI, simulated chip (BAC, PACE, secure messaging, CA, AA), specimen French ID card (`testchip/README.md`) | `:core`, JMRTD, SCUBA, BouncyCastle |

Rules:

- `:core` imports **no** `android.*` class: it is compiled and tested on the JVM (`./gradlew :core:test`). Its tests replace the chip either with a transport that replays recorded APDUs (`ScriptedTransport`) or with the simulated chip from `:testchip`, and generate a fake PKI on the fly (CSCA, link, DS, SOD, CA and AA keys, in RSA and ECDSA).
- `:testchip` never goes into the release APK: `testImplementation` of `:core`, `debugImplementation` of `:app` (decision D17). The main code of `:core` does not depend on it.
- The dependencies of `:core` are declared as `implementation` (decision D9): its public API exposes only Kotlin, `java.security` and `java.time` types, never JMRTD or BouncyCastle.
- The resources of the built-in trust store are in `core/src/main/resources/trust/`: 5 ANTS CSCA certificates, 5 CSCA and link certificates published by their own state (GB, GE, LU; source `NATIONAL`, decision D26) and the German Master List from the BSI (decision D1), with provenance in `docs/trust-store.md`, enumerated by `trust/index.txt` (decision D16).

### Source layout

```
core/src/main/kotlin/io/github/mgdx/sceau/core/
  Api.kt                   readAndVerify, CardTransport, AccessKey, Step, SceauException
  model/                   DocumentData, Dg1Data, Dg11Data, Dg12Data, EncodedImage, ArgbImage
  reading/                 ReadingSession (orchestration), SecureChannel (PACE, BAC, fallback),
                           DocumentReader (EF.COM, SOD, DG), ChipVerifier (CA, AA),
                           Chip and TransportCardService (bridge to JMRTD), DataGroupParsers
  report/                  VerificationReport, Verdict, Check, CheckId, CheckStatus, CheckDetail, ChainInfo
  trust/                   TrustStore, TrustAnchor, TrustSource, TrustStores, MasterList, MasterListInfo,
                           TrustStoreLoader, MasterListParser
  verify/                  PassiveAuthentication, ActiveAuthentication, Verdicts, CertificateChains

app/src/main/java/io/github/mgdx/sceau/
  SceauApplication.kt      TrustStoreRepository singleton, trust store preloaded at startup
  MainActivity.kt          single activity, NFC reader mode, activity-scoped SessionViewModel
  nfc/                     IsoDepTransport (CardTransport, error classification), NfcStatus
  session/                 SessionViewModel, ReadState, AccessForm (home screen input, dates)
  trust/                   TrustStoreRepository
  demo/DemoCard            simulated document for demo mode (see DemoMode below)
  ui/                      SceauNavHost, Routes, screens home/, reading/, result/, trust/, about/, learn/ (introduction, “What the chip contains”, D35)
  ui/common/SecureWindow   FLAG_SECURE counted per window
  ui/theme/                Material 3 theme, logo palette, dark mode following the system
  jp2/Jpeg2000Decoder      JPEG 2000 decoding (JNI to libsceau_jp2.so), called only in the isolated process
  jp2/Jpeg2000Service      isolatedProcess service that decodes JPEG 2000 and JPEG (D23, audit V18)
  jp2/IsolatedJpeg2000Decoder  service client, called by the result screen
  jp2/Jpeg2000Protocol     chunked binder protocol, tested on the JVM

app/src/debug/java/…/demo/DemoMode.kt     demo mode: simulated French ID card from :testchip
app/src/release/java/…/demo/DemoMode.kt   empty version (isAvailable = false)

app/src/main/cpp/
  CMakeLists.txt           builds libsceau_jp2.so (static OpenJPEG + Sceau code)
  jp2_decode.c/.h          in-memory decoding core, without JNI, tested on the host
  jp2_jni.c                JNI bridge
  openjpeg/                git submodule, OpenJPEG master 8314119b, v2.5.4-29 (BSD-2-Clause, D3)
  test/                    host test harness (ASan/UBSan), outside the APK: run-host-tests.sh
```

Strings are split per screen into `res/values*/strings_<screen>.xml` (decision D5).

### Image decoding

Decoding lives in `:app`, never in `:core` (which stays free of JNI), and never in the app's process: no image byte coming from the chip ever reaches a native decoder there (decision D23, audit V18). The format declared by the chip is ignored; `ui/result/ImageFormats.kt` (`detectImageFormat`) recognizes, from their first bytes, only JPEG (`FF D8 FF`) and JPEG 2000 (JP2 file or raw J2K codestream). Any other image is not decoded.

`ui/result/ImageDecoding.kt` hands both formats to `IsolatedJpeg2000Decoder.decode(context, bytes): Bitmap?` (a `suspend` function), which passes the stream to the `Jpeg2000Service` service, declared `android:isolatedProcess="true"` and not exported, in the `:jp2` process. This process, running under an isolated UID, has no permission and no access to the app's files. The service redoes the format detection on the bytes it receives:

- JPEG: Android's `BitmapFactory`, dimensions read first, rejection above 40 announced megapixels, subsampling and downscaling to 2048 pixels per side (`DecodeLimits`, audit V2);
- JPEG 2000: `Jpeg2000Decoder.decode(bytes)`; the isolated process is the only one where `libsceau_jp2.so` is loaded. The library statically links OpenJPEG, compiled from the `app/src/main/cpp/openjpeg` submodule (decision D3), and decodes from memory; it returns null, without exception or trace, for any stream that is invalid, truncated or announces more than 4096 pixels per side.

  Each decoding binds the service (thus starts a fresh isolated process), sends the stream in 256 KiB binder transactions, receives width and height and then the ARGB pixels in chunks of 64 Ki pixels, and unbinds; in `onDestroy`, the service wipes its buffers if they are free and terminates its process, without waiting for a decoding in progress to finish (audit V26). Nothing goes through a file. Decodings are serialized. Isolated process dead (decompression bomb, native bug), 10 s timeout exceeded (startup and transfers included), service unavailable or out-of-protocol response (dimensions beyond 2048 × 2048, chunk out of sequence): null, the image is not displayed. `SceauApplication`, which is also created in the isolated process, preloads nothing there (`isIsolatedProcess()`).

The native code is compiled for `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`, hence one APK per architecture plus a universal APK (decision D2). The `jp2_decode.c` core is tested on the host: `app/src/main/cpp/test/run-host-tests.sh` (synthetic images encoded by `opj_compress`, truncated, corrupted and oversized streams, under ASan and UBSan).

## 2. Public API of `:core`

### Reading

```kotlin
suspend fun readAndVerify(transport: CardTransport, key: AccessKey, trustStore: TrustStore, progress: (Step) -> Unit): VerificationReport
```

| Type | Role |
|---|---|
| `CardTransport` | Raw APDU channel: `transceive(apdu)`, `maxTransceiveLength`, `timeoutMillis` (changed by `:core` during authentication), `reconnect()` (resets the link for the PACE → BAC fallback, empty by default), `close()`. Throws `SceauException.ConnectionLost` if the document is removed, `SceauException.Timeout` if the chip does not respond in time. |
| `AccessKey.Can` / `AccessKey.Mrz` | Access key. `toString()` masked (`***`). |
| `Step` | `CONNECT`, `SECURE_CHANNEL`, `READ_DATA`, `VERIFY_SIGNATURE`, `VERIFY_CHIP`. |
| `SceauException` | Errors that interrupt reading, each with a stable `code` containing no personal data, possibly followed by a diagnostic (`TIMEOUT-SECURE_CHANNEL-INS86-L10`; list in `docs/protocol.md` §5). |

### Report

| Type | Role |
|---|---|
| `VerificationReport` | Verdict, seven `Check`s (one per `CheckId`), `ChainInfo?`, `DocumentData`. `wipe()` zeroes the bytes read. |
| `Verdict` | `AUTHENTIC`, `SIGNATURE_VALID_CHIP_UNVERIFIED`, `UNKNOWN_ISSUER`, `FAILED`. |
| `Check`, `CheckId`, `CheckStatus` | Line of the checklist; status `OK`, `FAILED`, `NOT_AVAILABLE`, `UNSUPPORTED_ALGORITHM`. |
| `CheckDetail` | Structured detail (channel protocol, algorithm, chain, DS validity, data groups checked, technical error). The app formats it with its own strings: `:core` produces no user-facing text. |
| `ChainInfo` | Subject and serial number of the DS, period, algorithm, CSCA found (subject, country, fingerprint, source), link certificates. No personal data. |
| `DocumentData` | DG1, portrait, DG11, DG12, DG7 signature (D37) and raw bytes of the data groups read. Not a `data class`, `toString()` masked. |

### Verification (used by `readAndVerify`, testable separately)

| Object | Role |
|---|---|
| `PassiveAuthentication.verify(…)` | DS → CSCA chain, SOD signature, hashes, DS validity. Does not access the chip. |
| `ActiveAuthentication.verifyResponse(…)` | Verifies the response to the AA challenge with the DG15 key. |
| `Verdicts.compute(checks)` | Overall verdict (decision table in `docs/protocol.md` §6). |

`:core` decodes no image: it passes the bytes of the portrait, of the DG7 signature and of the DG12 images (`EncodedImage`, with their format) to `:app`, which decodes them (section 1, “Image decoding”).

### Trust store

| Element | Role |
|---|---|
| `TrustStores.load(importedMasterLists)` | Loads the built-in store (files enumerated by `trust/index.txt`) and merges it with the imported Master Lists (raw bytes supplied by the app), in order of priority: ANTS, national publications (D26), built-in list, imported Master Lists, individually imported certificates (D33). A built-in list that is invalid or whose signer is not anchored throws `InvalidMasterListException`; an invalid import is ignored. |
| `TrustStores.parseMasterList(bytes)` | Parses a CMS Master List (`.ml`, `.der`, `.p7b`) and verifies its signature with the signer certificate it contains. |
| `TrustStore` | `anchors`, `embeddedMasterList`, `findBySubject`, `findBySubjectKeyId`. Read-only. |
| `TrustAnchor` | Certificate and source (`ANTS`, `NATIONAL`, `EMBEDDED_MASTER_LIST`, `IMPORTED_MASTER_LIST`, `IMPORTED_CERTIFICATE`), country, SHA-256 fingerprint, validity, self-signed or link (`isSelfSigned`, inferred from the AKI and SKI key identifiers, signature verified only as a fallback: decision D16). |

## 3. Data flow

```
Home     ──CAN or MRZ input──► SessionViewModel.prepare(key)              state WaitingForCard
                                   │
Reading  IsoDep tag detected ─────► SessionViewModel.onCardDetected(transport)
         (MainActivity)            │  coroutine on Dispatchers.IO
                                   ▼
                     readAndVerify(transport, key, trustStore, progress)
                       │  progress(step) ─────────────────────────► state Reading(step)
                       │  SceauException ─────────────────────────► state Error(code)  → “Try again”: retry()
                       ▼
                     VerificationReport ──────────────────────────► state Done(report)
                                   │
Result   shows verdict, photo, DG1, DG7 signature, DG11/DG12, checklist
         “Clear” or back ─────────► SessionViewModel.clear()       report.wipe(), key forgotten, state Idle
```

1. **Home** (`HomeScreen`): the user enters the MRZ (Passport tab, shown first: number, then dates typed on the numeric keyboard in the locale's order, decision D10; the keyboard is dismissed at the last digit of the expiry date, D29) or the CAN (ID card tab). The input lives in `SessionViewModel.form` (`AccessForm`), which validates it. The key is built as an `AccessKey` and handed to `SessionViewModel.prepare(key)`, which moves to `WaitingForCard`. The “Read” button navigates to the reading screen.
2. **NFC detection**: `MainActivity` (`singleTop` launch mode) enables NFC reader mode (`enableReaderMode`, NFC-A and NFC-B, presence checks every 2 s) while it is in the foreground (`repeatOnLifecycle(RESUMED)`) and disables it in `onPause`. The NFC state is polled every second (`nfcAvailabilityFlow`, `nfc/NfcStatus.kt`), without a broadcast receiver: the return to ON of `ACTION_ADAPTER_STATE_CHANGED` did not always reach a non-exported receiver (observed on a device). Each time the state switches to enabled, reader mode is re-armed: an `enableReaderMode` call made while NFC is off is lost, and turning NFC back on from Quick Settings does not take the activity through `onResume` again. The same flow drives the “NFC is turned off” banner (`nfc/NfcBanner`), shown on the home screen and on the reading screen (commit `f6cd14b`). The activity receives the `IsoDep` tag while the session is in `WaitingForCard` or `Error`, wraps it in an `IsoDepTransport` and calls `onCardDetected(transport)`.
3. **Reading** (`ReadingScreen`): `readAndVerify` runs in a coroutine of the `ViewModel`, on `Dispatchers.IO`. Each call to `progress` moves the state to `Reading(step)`; the screen checks off the completed steps and shows a waiting message if opening the secure channel takes more than 5 s (decision D12). A `SceauException` gives `Error(code)`, displayed with a message in the interface language, the technical code in small print (decision D11) and “Try again” (`retry()`, same key). The transport is always closed off the main thread (`IsoDep.close()` waits for the current APDU to finish).
4. **Trust store**: `TrustStoreRepository.get()` provides the merged `TrustStore`, loaded once and then kept in memory until the next import or clearing of imports. `SceauApplication` preloads it in the background when the process starts (decision D16).
5. **Result** (`ResultScreen`): on `Done(report)`, navigation replaces the reading screen with the result screen (`popUpTo(READING) inclusive`). The screen formats the report; the portrait and the DG7 signature (D37) are decoded in the isolated process of `Jpeg2000Service` (decision D23, audit V18): JPEG 2000 by OpenJPEG, JPEG by the Android decoder. No other image is decoded. No data is copied out of the report beyond what display requires.
6. **Trust store, dedicated screen** (`TrustStoreScreen`): list of CSCAs, import of a Master List via `ACTION_OPEN_DOCUMENT` → `preview(bytes)` (signature verified, signer fingerprint shown) → confirmation → `import(bytes)`. Imported Master Lists are stored as is in `filesDir/trust/`: they are public certificates, not personal data. “Delete imported certificates” calls `clearImported()`.
7. **Demo mode** (debug APK only, decision D17): the “Simulate a French ID card (demo)” entry of the home menu builds, off the main thread, a simulated French ID card from `:testchip` (`DemoMode.newSimulatedCnie()`), then `SessionViewModel.startDemo(card)` reads it through the same path as a real document (`launchRead`), with its CAN key and its test trust store, which serves only for this reading. The key entered by the user is forgotten. The result carries the “Simulated document — demo” banner (`ReadState.Done.isDemo`). In the release APK, `DemoMode.isAvailable` is `false` and the entry does not exist.

## 4. Sensitive data lifecycle

Sensitive data: the access key (CAN or MRZ) and the content of `VerificationReport.document` (DG1, DG2 portrait, DG7 signature, DG11, DG12, raw bytes of the data groups). Rules from SPEC §8.

### Where it lives

| Data | Location | Lifetime |
|---|---|---|
| CAN / MRZ input | `SessionViewModel.form` (`AccessForm`, in memory, never in a `Bundle` or a `SavedStateHandle`; `toString()` masked) | from typing to the end of a successful reading (form cleared, tab kept) or to `clear()` |
| `AccessKey` | `SessionViewModel` (activity scope) | from `prepare(key)` to the end of a successful reading or to `clear()`; kept after an error for “Try again” |
| `VerificationReport` | `SessionViewModel`, state `ReadState.Done` | from the end of reading to `clear()` |
| Decoded images (bitmap) | result screen | as long as the screen is composed; wiped by `wipeAndRecycle()`. The intermediate buffers of JPEG 2000 decoding (native copy of the stream, samples, native and Java ARGB arrays, stream and pixels received or sent over binder, on both sides) are zeroed as soon as the bitmap is built; the isolated process is terminated after each image (D23) |
| Imported Master Lists | `filesDir/trust/` | until “Delete imported certificates” (public data) |

Nothing else: no database, no file, no cache, no log and no preference contains data read from the chip or a key. Neither does the `SavedStateHandle` or the activity's state `Bundle`: after process death, the application restarts on the home screen, empty, or on the introduction as long as it has not been seen. The only preference written is the `intro_seen` flag (`SharedPreferences` `sceau_preferences`, D35), a boolean with no personal data. Android backup is disabled (`allowBackup="false"`, plus backup and data transfer rules that exclude all domains).

### When it is wiped

`SessionViewModel.clear()` interrupts the reading in progress (closing the transport off the main thread), calls `report.wipe()` (zeroing the byte arrays of `DocumentData`: raw data groups, portrait, DG7 signature, DG12 images), forgets the key, the input and the report, and goes back to `Idle`. It is called on:

| Event | Trigger |
|---|---|
| “Clear” button (bottom of the screen and action bar) | `ResultScreen` → `clear()` then back to the home screen |
| System back on the result | same effect as “Clear” (SPEC §5.3) |
| Moving to the background during a reading or on the result | `MainActivity.onStop()` outside a configuration change → `onBackground()` → `clear()` if the state is `Reading` or `Done`; the application returns to the home screen when brought back |
| Reading cancelled | “Cancel” button or back on the reading screen → `clear()` |
| End of the `ViewModel` | `onCleared()` (activity finished) |

Moving to the background from the home screen (in `Idle`, `WaitingForCard` or `Error`) clears neither the input nor the key, which live only in memory: the user can go through the NFC settings without typing everything again (SPEC §5.1). A rotation wipes nothing (`isChangingConfigurations`). A report produced by a reading that was abandoned in the meantime is zeroed as soon as it is received. If reading fails, `:core` zeroes the data already read.

Accepted limits:

- Kotlin/JVM does not allow `String`s (name, number, CAN) to be wiped, nor guarantee that no copy was made by the garbage collector. Zeroing applies to the byte arrays, which contain the photo and the raw data groups; the strings become unreachable as soon as the session is cleared.
- JMRTD and OpenJPEG keep intermediate copies in internal buffers that they release without zeroing (decisions D3 and D14). They remain in memory and are neither written nor logged; those of OpenJPEG disappear with the isolated process, terminated after each image (D23). The buffers of the binder `Parcel`s that carry the stream and the pixels are released without zeroing (the public API does not allow it).

### Logs

No call to `android.util.Log`, `println` or `printStackTrace` in Sceau's code, not even temporarily. The `java.util.logging` loggers of JMRTD and SCUBA are turned off before each reading, and no JMRTD exception is attached as a cause (their messages contain APDUs): decision D14. Diagnostics go through the displayed error code (decision D11).

### Screen protection

- `FLAG_SECURE` is set on the activity's window as soon as it is created, in `MainActivity.onCreate` before the first frame is drawn (`secureForLifetime(window)`), for its whole lifetime: screenshots refused, preview hidden in the recent apps, display blocked on a non-secure external screen, on every screen (home and CAN and MRZ input, reading, result, trust store, about) (SPEC §2 and §8, audit V7).
- The reading and result screens additionally request the protection while they are composed (`SecureWindow()`), as does the window of the full-screen photo dialog. Requests are counted per window; the one from `secureForLifetime` is never released, so leaving a screen never lifts the protection.

### Surface exposed to other applications

- `MainActivity`, the only exported component (launcher), replaces its intent with the launcher intent, without extras, before `super.onCreate` and in `onNewIntent`: another application cannot choose the opening screen through Navigation's deep link extras (`deepLinkIds`…), independently of the caller check that Navigation 2.10 performs (audit V27, `MainActivityIntentTest`).
- `MainActivity` declares `android:taskAffinity=""`: no application can slip into Sceau's task by declaring the same affinity to present a fake CAN or MRZ input there (task hijacking, especially on Android 8 to 10; audit V28). No functional effect, since the application has only one activity.

### Network

The manifest declares no network permission (`android.permission.NFC` only, decision D7 for the internal permission added by androidx.core). No data can leave the device; the trust store is never updated over the network.

## 5. Tests

### Parser fuzzing

`core/src/test/kotlin/…/core/fuzz/` contains a deterministic mutational fuzzer, without dependencies (no Jazzer), run by `./gradlew :core:test`. The seeds are valid inputs produced by `:testchip` (EF.COM, EF.SOD, DG1 TD1 and TD3, DG2, DG7, DG11, DG12, DG14, DG15, EF.CardAccess, test Master List) and the built-in Master List. Mutations act on the bytes (bit, byte, truncation, insertion, duplication or deletion of a block) and on the TLV/DER tree (inflated length, reduced length, long form on 4 bytes or more, indefinite length, deep nesting, unexpected tag, duplicated or swapped children, replaced value); the signed content is also mutated then re-signed (LDSSecurityObject, `CscaMasterList`). Each target checks the contract of its real caller: a result or an expected exception, never an `Error` (memory, stack), a timeout (2 s per input), or an “OK” SOD_SIGNATURE, DG_HASHES or AA for an input mutated in its signed part.

The default number of iterations keeps the whole run under 30 s. Long mode, reproduction and timeout (`SCEAU_FUZZ_TIMEOUT_MILLIS`) are set through environment variables (the `-D` flags of `./gradlew` do not reach the test JVM; the `sceau.fuzz.*` system properties are still read, for runs from the IDE):

```bash
SCEAU_FUZZ_ITERATIONS=200000 ./gradlew :core:test --tests "io.github.mgdx.sceau.core.fuzz.*" --rerun   # long mode
SCEAU_FUZZ_SEED=0x5cea2026 SCEAU_FUZZ_INDEX=736 ./gradlew :core:test --tests "…FuzzTest.comDataGroups" --rerun  # a single iteration
```

A failure reports the target, the seed, the index and the list of mutations, never the bytes; signed seeds are re-signed deterministically (ECDSA RFC 6979, fixed signing time) so that seed and index are enough to replay it. Failures are grouped by signature (exception and project line). A minimal input becomes a regression test; a bug not yet fixed stays in `FuzzRegressionTest`, marked `@Ignore`.
