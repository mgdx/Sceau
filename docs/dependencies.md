# Dependencies

Every dependency shipped in the APK is listed here with its version, its license, its role and its F-Droid compatibility (SPEC §3). A new dependency is accepted only after a row has been added to this file.

F-Droid criteria applied to each row: free source code, published on a standard Maven repository (Maven Central or Google Maven), rebuildable, no Google service, no telemetry or advertising. No dependency chosen by Sceau brings a prebuilt native binary; a single AndroidX transitive dependency contains one, `libandroidx.graphics.path.so` (section 2), as in any Compose application. All the licenses retained are compatible with Sceau's license, GPL version 3 only (GPL-3.0-only, D51).

List established on 2026-09-25 from:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

The versions are those **resolved** by Gradle (after conflict resolution). The declared versions are in `gradle/libs.versions.toml`. Rerun the command at every version upgrade and update this file in the same commit.

The licenses were verified on 2026-09-25 **on the artifacts themselves**, never from memory: `<licenses>` section of the POM (Gradle cache `~/.gradle/caches/modules-2/files-2.1/` or original Maven repository), license file contained in the jar or the AAR, or, when the POM does not specify the license version, headers of the source files in the `-sources` jar. The “License” column gives the source when it is not the POM.

## 1. Direct dependencies

### Module `:core`

| Artifact | Version | License | Role | F-Droid |
|---|---|---|---|---|
| `org.jmrtd:jmrtd` | 0.8.8 | LGPL 2.1 or later (POM: “LGPL” without version; source headers: “version 2.1 of the License, or (at your option) any later version”) | PACE, BAC, secure messaging, LDS parsing (EF.COM, SOD, DG), CA, AA | yes, Maven Central, pure Java |
| `net.sf.scuba:scuba-smartcards` | 0.0.21 | LGPL 2.1 or later (same) | APDU abstraction (`CardService`) used by JMRTD | yes, pure Java |
| `org.bouncycastle:bcprov-jdk18on` | 1.86 | Bouncy Castle License (POM), MIT text (`META-INF/LICENSE.md` in the jar) | JCA provider: RSA, RSA-PSS, ECDSA, Brainpool curves, DH/ECDH, hashes | yes, pure Java |
| `org.bouncycastle:bcpkix-jdk18on` | 1.86 | Bouncy Castle License (MIT) | X.509, CMS (SOD, Master Lists), chain building | yes, pure Java |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | Apache 2.0 | `suspend fun readAndVerify`, execution on `Dispatchers.IO` | yes |

SPEC §3 and `CLAUDE.md` gave JMRTD and SCUBA as “LGPL 3”: the artifact states LGPL 2.1 or any later version. The conclusion does not change: the “or later” clause allows using them under LGPL 3, compatible with Sceau's license (GPL-3.0-only).

Only the BouncyCastle `jdk18on` artifacts are allowed: the `jdk15on` and `jdk15to18` variants, which would duplicate the classes, are excluded in the `build.gradle.kts` files. SpongyCastle is never used (SPEC §3).

### Module `:app`

| Artifact | Version | License | Role | F-Droid |
|---|---|---|---|---|
| `project(":core")` | — | GPL 3 only (GPL-3.0-only) | Sceau core | yes |
| `net.sf.scuba:scuba-sc-android` | 0.0.27 | LGPL 2.1 or later (POM “LGPL”, source headers) | Bridge between Android's `IsoDep` and SCUBA | yes, pure Java |
| `androidx.compose:compose-bom` | 2026.02.01 | Apache 2.0 | Alignment of Compose versions | yes (Google Maven, free AndroidX) |
| `androidx.compose.ui:ui`, `ui-graphics`, `ui-tooling-preview` | 1.11.2 | Apache 2.0 | Jetpack Compose | yes |
| `androidx.compose.material3:material3` | 1.4.0 | Apache 2.0 | Material 3 components | yes |
| `androidx.activity:activity-compose` | 1.13.0 | Apache 2.0 | `setContent`, `LocalActivity`, back navigation | yes |
| `androidx.navigation:navigation-compose` | 2.10.2 | Apache 2.0 | Navigation between screens | yes |
| `androidx.lifecycle:lifecycle-runtime-ktx`, `lifecycle-viewmodel-compose` | 2.11.0 | Apache 2.0 | `ViewModel`, lifecycle (wiping in the background) | yes |
| `androidx.core:core-ktx` | 1.19.0 | Apache 2.0 | AndroidX utilities | yes |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Apache 2.0 | `Dispatchers.Main` | yes |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.20 | Apache 2.0 | Kotlin standard library | yes |

Apache 2.0 licenses of the AndroidX libraries verified on the `LICENSE.txt` shipped in the AAR (`META-INF/androidx/…/LICENSE.txt`); those of Kotlin and kotlinx on the POM published on Maven Central.

### Debug APK only

These dependencies are declared as `debugImplementation`: they do not appear in `releaseRuntimeClasspath` and do not go into the release APK.

| Artifact | Version | License | Role |
|---|---|---|---|
| `androidx.compose.ui:ui-tooling` | 1.11.2 | Apache 2.0 | Compose preview tools |
| `project(":testchip")` | — | GPL 3 only (GPL-3.0-only, Sceau code) | Demo mode: simulated CNIe, test PKI (decision D17). Pulls in JMRTD, SCUBA and BouncyCastle, already present. |
| `androidx.compose.ui:ui-test-manifest` | 1.11.2 (Compose BOM) | Apache 2.0 (POM) | Declares the empty activity `androidx.activity.ComponentActivity` (exported, without content) that hosts the Compose tests under Robolectric (`TextOverflowTest`, D28). Nothing else: no code, no permission. Present in the debug APK only. |

Verification: `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` does not mention `project :testchip`, `debugRuntimeClasspath` does (observed on 2026-09-25).

### Native code compiled from source: JPEG 2000 decoder

| Component | Version | License | Role | F-Droid |
|---|---|---|---|---|
| OpenJPEG (`openjp2` library only) | 2.5.4+ (`master`, commit `8314119b`, `v2.5.4-29`: security fixes made after the tag, see D3) | BSD-2-Clause | Decoding of JPEG 2000 portraits (DG2, DG12), via `Jpeg2000Decoder` and `libsceau_jp2.so` | yes: git submodule `app/src/main/cpp/openjpeg` pointing to the official repository `github.com/uclouvain/openjpeg`, compiled from source by CMake and the NDK at every build, statically linked; no prebuilt binary in the repository |

This component is not a Maven dependency: it does not appear in the output of `./gradlew :app:dependencies`. It replaces jj2000, rejected because its original license restricts the field of use (non-free, incompatible with GPLv3 and F-Droid): see decision D3. Corresponding build tools: NDK `28.2.13676358` and CMake `4.1.2` (section 4).

Version upgrade: go into the submodule, `git fetch --tags && git checkout vX.Y.Z`, commit the new pointer, rerun `app/src/main/cpp/test/run-host-tests.sh` and update this table and decision D3.

## 2. Notable transitive dependencies

| Artifact | Version | License | Pulled in by | Note |
|---|---|---|---|---|
| `org.bouncycastle:bcutil-jdk18on` | 1.86 | Bouncy Castle License (MIT) | bcpkix, JMRTD | Common ASN.1 structures |
| `org.ejbca.cvc:cert-cvc` | 1.4.13 | LGPL 2.1 (POM: “LGPL license, Version 2.1”) | JMRTD | Card-verifiable (CV) certificates, used by JMRTD for Terminal Authentication, which Sceau does not perform. Removed from the APK by R8: Sceau reaches none of its code paths (D24). |
| `com.google.guava:listenablefuture` | 1.0 | Apache 2.0 (source header; the POM inherits from `guava-parent`) | AndroidX (`concurrent-futures`) | Empty Guava artifact containing only the `ListenableFuture` interface. Free, not a Google service. |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.7.3 | Apache 2.0 | Navigation, Lifecycle | Serialization of navigation routes and states. No data read from the chip is serialized. |
| `androidx.profileinstaller:profileinstaller` | 1.4.0 | Apache 2.0 | Compose | ART compilation profiles, local to the device, no network |
| `androidx.startup:startup-runtime` | 1.1.1 | Apache 2.0 | Lifecycle, emoji2 | Initialization at startup |
| `androidx.emoji2:emoji2` | 1.4.0 | Apache 2.0 | Compose | Emoji compatibility. May query a font provider installed on the device (system component, outside Sceau); Sceau itself opens no connection (no network permission). Widely present in F-Droid applications. |
| `androidx.window:window` | 1.5.0 | Apache 2.0 | Activity, Compose | Information about the window and foldable screens |
| `org.jspecify:jspecify` | 1.0.0 | Apache 2.0 | AndroidX | Nullness annotations |
| `org.jetbrains:annotations` | 23.0.0 | Apache 2.0 | kotlin-stdlib, coroutines | Annotations, no executed code |

| `androidx.graphics:graphics-path` | 1.0.1 | Apache 2.0 | Compose (`ui-graphics`) | **Contains a prebuilt native library**, `lib/<abi>/libandroidx.graphics.path.so` (7 to 11 KB per architecture, present in all release APKs), which reads `android.graphics.Path` paths. AndroidX code published under Apache 2.0 with its sources (AOSP), distributed through Google Maven; F-Droid accepts it like the rest of AndroidX, present in all Compose applications. It is not a Google service and it opens no connection. The only native binary in the APK that Sceau does not compile itself (observed on the 0.9 release APK, `unzip -l`). |

Other transitive dependencies: AndroidX libraries (`annotation`, `collection`, `arch.core`, `savedstate`, `navigationevent`, `customview-poolingcontainer`, `autofill`, `tracing`, `transition`, `dynamicanimation`, `interpolator`, `versionedparcelable`, `legacy-support-core-utils`, `loader`, `localbroadcastmanager`, `print`, `documentfile`, `concurrent-futures`, `core-viewtree`) and Compose modules (`runtime`, `runtime-saveable`, `runtime-retain`, `runtime-annotation`, `animation`, `animation-core`, `foundation`, `foundation-layout`, `material-ripple`, `ui-geometry`, `ui-text`, `ui-unit`, `ui-util`): Apache 2.0, published on Google Maven, free. These transitive dependencies were not checked one by one; the Apache 2.0 license was observed on the AAR or the POM of each direct AndroidX and Compose dependency and of `emoji2`, `profileinstaller`, `startup-runtime` and `window`.

Notices in the application (D50): “About” → “Open-source libraries” reproduces, from `app/src/main/res/raw/third_party_notices.txt`, the license and copyright of each shipped library (JMRTD, SCUBA, Bouncy Castle, OpenJPEG with the full text of its BSD-2-Clause, AndroidX/Compose, Kotlin, kotlinx). Any addition or removal of a shipped dependency also updates this file. cert-cvc is not listed there as long as R8 removes it from the release APK (verified on 2026-10-12: no `org.ejbca` class in `mapping.txt` nor in the `dexdump` of the universal APK).

No dependency belongs to Google Play Services, Firebase, an analytics, advertising or crash-reporting library.

## 3. Shipped content that is not code

The trust store in `core/src/main/resources/trust/` is made up of public data, not libraries (decision D1):

- 5 ANTS CSCA certificates, published on ants.gouv.fr;
- 5 CSCA and link certificates published by their own State for its documents (decision D26): United Kingdom (`gb-csca-2026.der`, `gb-csca-link-2021-2026.der`, under the Open Government Licence v3.0, attribution displayed in “About”), Georgia (`ge-csca-5.der`, `ge-csca-6.der`) and Luxembourg (`lu-csca-etravel-link.der`);
- German Master List published by the BSI, redistributed unmodified in accordance with its reuse conditions (decision D1 in `docs/decisions.md`).

Provenance and fingerprints: `docs/trust-store.md`.

## 4. Build tools (outside the APK)

| Tool | Version | License |
|---|---|---|
| Android Gradle Plugin | 9.3.3 | Apache 2.0 |
| Kotlin (Compose plugin, JVM plugin) | 2.2.10 | Apache 2.0 |
| ktlint (`com.pinterest.ktlint`) | 1.8.0 | MIT (POM of `ktlint-cli`) |
| Gradle plugin `org.jlleitschuh.gradle.ktlint` | 14.2.0 | not verified on the artifact: the published POM declares no license (MIT according to the upstream repository, to be confirmed) |
| JUnit (tests only) | 4.13.2 | EPL 1.0 (`LICENSE-junit.txt` in the jar) |
| Hamcrest Core (tests only, pulled in by JUnit) | 1.3 | BSD (`LICENSE.txt` in the jar) |
| kotlinx-coroutines-test (tests only) | 1.11.0 | Apache 2.0 |
| Robolectric `org.robolectric:robolectric` (tests only, D28) | 4.17 | MIT (POM, like `nativeruntime` 4.17) |
| Robolectric Android jars `org.robolectric:android-all-instrumented` (downloaded by Robolectric the first time the tests run, from Maven Central) | 17-robolectric-15733970-i7 (Android 37) | Apache 2.0 (AOSP code) |
| Compose UI test `androidx.compose.ui:ui-test-junit4` (tests only, D28) | 1.11.2 (Compose BOM) | Apache 2.0 (POM) |
| Espresso `androidx.test.espresso:espresso-core` (tests only, D28) | 3.7.0 | Apache 2.0 (Google Maven POM, headers of the `-sources` jar) |
| Plugin `org.gradle.toolchains.foojay-resolver-convention` | 1.0.0 | not verified on the artifact: the published POM declares no license (Apache 2.0 according to the upstream repository, to be confirmed) |
| Android NDK (compilation of `libsceau_jp2.so`) | 28.2.13676358 | Apache 2.0 (Clang/LLVM: Apache 2.0 with LLVM exception) |
| CMake (native compilation) | 4.1.2 | BSD-3-Clause |

Robolectric, Compose UI test and Espresso are used only for the text overflow check (`app/src/test/.../l10n/TextOverflowTest.kt`, D28) and go into no APK: `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` mentions none of them, nor `ui-test-manifest` (observed on 2026-09-29). Robolectric's native rendering (`nativeruntime`) contains prebuilt libraries, executed only by the JVM tests: F-Droid builds the APK without running the tests. Espresso 3.7.0 is required because version 3.5.0, pulled in by `ui-test-junit4`, calls `InputManager.getInstance()`, which is absent from Android 37. The `:app` tests run on JDK 25 (the one used by the Gradle daemon and the CI): Robolectric requires Java 21 or later for Android 37; the code is still compiled for Java 17.
