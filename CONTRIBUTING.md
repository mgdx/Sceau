# Contributing to Sceau

Thank you for your interest. Sceau handles identity data: the rules below are not style preferences, they determine whether a contribution is accepted. They restate SPEC §2 (general constraints), §3 (dependencies) and §8 (privacy).

Before you start, read [`SPEC.md`](SPEC.md) (source of truth), [`docs/architecture.md`](docs/architecture.md) and [`docs/protocol.md`](docs/protocol.md). Any change that departs from the SPEC must be recorded and justified in [`docs/decisions.md`](docs/decisions.md).

## Rules

### Quality

- **Zero warnings** on `test`, `lint` and `ktlintCheck`. Kotlin warnings are treated as errors (`allWarningsAsErrors`), lint in `warningsAsErrors`. A single warning makes CI fail.
- Do not disable a lint or ktlint rule to get a build through. An exception, if unavoidable, is targeted as narrowly as possible (one file, one jar), commented in the configuration file and recorded in `docs/decisions.md`.
- Any change in the behavior of `:core` comes with a JVM test. The cases in SPEC §9.1 must remain covered.

### Architecture

- **`:core` is pure Kotlin**: no `android.*` import, no Android dependency. It must compile and be tested on the JVM.
- The public API of `:core` exposes no JMRTD or BouncyCastle type.
- **No hardcoded algorithm**: whatever the certificates and the SOD declare is accepted; an unknown algorithm gives `UNSUPPORTED_ALGORITHM`, never a silent failure (SPEC §6.2).
- A step that is not available (`NOT_AVAILABLE`) remains distinct from a failed step (`FAILED`).
- **`:testchip` never goes into the release APK**: it stays a `testImplementation` of `:core` and a `debugImplementation` of `:app`. Anything in `:app` that uses it goes through the `debug` source set, with an empty version in `release` (decision D17).

### Interface

- **No hardcoded string.** All strings are in `app/src/main/res/values/strings_<screen>.xml` (English, the default language, D55) with their translation in `values-fr/` and the other `values-<language>/` (decisions D5, D27). A string added without a French translation is incomplete.
- Compose and Material 3; the theme follows the system dark mode.

### Privacy and security

- **No data read** (DG1, photo, DG11, DG12) and no access key (CAN, MRZ) is written to disk, cache, database, preferences or logs, in debug or in release. No `rememberSaveable` or `SavedStateHandle` for this data.
- **No logging, not even temporarily for debugging**: no `Log`, no `println`, no `printStackTrace`. To diagnose on a device, enrich the displayed error code instead, with elements free of personal data (step, INS, rounded length, subtype from a closed list: decision D11).
- No personal data in exception messages or error codes. Never attach a JMRTD exception as a cause: its messages contain APDUs in hexadecimal (decision D14). Model classes are not `data class`es and mask their `toString()`.
- Screens that display data read set `FLAG_SECURE` (`SecureWindow()`).
- Byte arrays read are zeroed (`wipe()`) on leaving the result screen and in the background.
- All cryptographic randomness comes from `SecureRandom`.
- **No network permission.** The manifest requests only `android.permission.NFC`.
- No telemetry, no crash reporting, no analytics or advertising library.

### Dependencies

- Required dependencies: JMRTD, scuba-sc-android, BouncyCastle `jdk18on` (never SpongyCastle), OpenJPEG for JPEG 2000 (native code compiled from source, decision D3), Compose, Material 3, Navigation Compose.
- **Any new dependency**, including a notable transitive one, is justified in [`docs/dependencies.md`](docs/dependencies.md): version, license, role, F-Droid compatibility, in the same commit that adds it.
- **No Google service** (Play Services, Firebase, ML Kit…), **no non-rebuildable binary blob**, no native library without justification. The only native code, OpenJPEG, is compiled from the sources of a git submodule (decisions D2 and D3).
- Allowed repositories: Maven Central and Google Maven.
- Versions in `gradle/libs.versions.toml`. A version upgrade is a separate commit.

### Trust store

The files in `core/src/main/resources/trust/` may only be changed by following the procedure in [`docs/trust-store.md`](docs/trust-store.md): official source, published fingerprint, update of the fingerprints expected by the test. A built-in file is always redistributed without modification.

## Commands

The OpenJPEG sources are a git submodule: clone with `git clone --recurse-submodules`, or, in an existing clone, run `git submodule update --init` once. Native compilation requires NDK `28.2.13676358` and CMake `4.1.2` (installable through the SDK Manager or `sdkmanager "ndk;28.2.13676358" "cmake;4.1.2"`).

```bash
./gradlew :core:test                                                   # core JVM tests
./gradlew :core:test --tests "io.github.mgdx.sceau.core.SomeTest"       # a single test
./gradlew :testchip:test                                               # simulated chip tests
./gradlew :app:testDebugUnitTest                                        # app JVM tests (including demo mode)
./gradlew :app:assembleDebug                                            # debug APK (demo mode included)
./gradlew :app:assembleRelease                                          # release APK (R8), unsigned
./gradlew lint ktlintCheck                                              # lint and style
./gradlew ktlintFormat                                                  # reformats according to .editorconfig
./gradlew check                                                         # everything that blocks CI
./gradlew :app:dependencies --configuration releaseRuntimeClasspath     # dependency audit
app/src/main/cpp/test/run-host-tests.sh                                 # host tests of the JPEG 2000 decoder (ASan, UBSan)
scripts/update-trust-store.sh --ants-dir DIR [--apply]              # checks the trust store sources (at each release)
scripts/check-reproducible.sh                                           # double release build, byte-for-byte identical APKs
```

`update-trust-store.sh` (bash, curl, openssl, unzip) writes nothing without `--apply`; see the procedure in [`docs/trust-store.md`](docs/trust-store.md).

Release APKs must remain reproducible (decision D25, [`docs/reproducible-builds.md`](docs/reproducible-builds.md)): any change to the build (Gradle, CMake, tool or dependency versions) goes through `scripts/check-reproducible.sh` before a release.

Any change to `app/src/main/cpp/` (C code or OpenJPEG version) goes through `run-host-tests.sh`, which requires `cmake` and a host C compiler with AddressSanitizer. Sceau's C code is compiled with `-Wall -Wextra -Wconversion -Werror`.

CI (`.github/workflows/ci.yml`) fetches the OpenJPEG submodule then runs `./gradlew check :app:assembleDebug`. Run `./gradlew check` before proposing a contribution.

Toolchain: JDK 17 for compilation, JDK 25 for the Gradle daemon.

## Commits

Format: `type(scope): description`, in English, in the imperative mood, with no final period. One commit = one coherent change.

| Type | Use |
|---|---|
| `feat` | new feature |
| `fix` | bug fix |
| `docs` | documentation only |
| `test` | adding or fixing tests |
| `refactor` | restructuring without behavior change |
| `chore` | build, dependencies, CI, tooling |

Usual scopes: `core`, `app`, `trust`, `reading`, `result`, `home`, `about`, `nfc`, `jp2`, `sim` (`:testchip`), `debug` (demo mode), `socle`, `ci`. Example: `fix(core): distinguish a missing DG15 from an Active Authentication failure`.

The message body explains the why, and cites where relevant the SPEC section or the decision in `docs/decisions.md` concerned.

## Reporting a vulnerability

Security problems are not reported as issues or pull requests. Use GitHub's private form, https://github.com/mgdx/Sceau/security/advisories/new, as described in [`SECURITY.md`](SECURITY.md). A pull request that fixes a vulnerability must not be opened before the report has been acknowledged, so that the fix and the advisory can be published together.

## Proposing a contribution

1. Create a branch from `master`.
2. Make your changes following the rules above, with the corresponding tests and documentation (`docs/`, and `fastlane/metadata/` if a visible feature changes).
3. Check `./gradlew check`.
4. Open a pull request describing the change, its justification and how it was verified (tests, and cases from [`docs/test-plan.md`](docs/test-plan.md) run on a device where relevant).

By contributing, you agree that your contribution is distributed under the project's license, GNU GPL version 3 only (GPL-3.0-only).
