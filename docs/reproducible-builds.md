# Reproducible builds

Goal: have F-Droid rebuild from source release APKs that are **byte-for-byte identical** to those built by the developer, so that it publishes the developer-signed APKs ([Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/)). Decision D25.

## What is guaranteed

For the same commit and with the tool versions below, `./gradlew :app:assembleRelease` produces the same five unsigned APKs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, universal), regardless of:

- the path of the clone and the depth of that path;
- the installation path of the Android SDK and of the NDK.

Verified on 2026-09-29 on Linux x86_64 (see D25 for the fingerprints). The signature is not covered: F-Droid compares its unsigned APK with the developer's signed APK stripped of its signature (see below).

Two causes of discrepancy were fixed:

| Discrepancy | Cause | Fix |
|---|---|---|
| `lib/<abi>/libsceau_jp2.so`: 20 bytes (`.note.gnu.build-id` note) | the build-id is the hash of the `.so` before stripping, whose debug information contains the absolute paths of the clone, of the `.cxx` directory and of the NDK headers | `-ffile-prefix-map` for these three roots in `app/src/main/cpp/CMakeLists.txt`, applied to OpenJPEG as well as to Sceau's code |
| “dependency metadata” block in the signing block (signed APKs only) | added by AGP, encrypted with a Google key, rejected by F-Droid | `dependenciesInfo { includeInApk = false; includeInBundle = false }` in `app/build.gradle.kts` |

The shipped library remains stripped (neither `.symtab` nor `.debug_*`); the build-id is kept, and is now stable.

Contents verified and already stable: `classes.dex` (R8), `resources.arsc`, `assets/dexopt/baseline.prof{,m}`, order and timestamps of the ZIP entries (AGP fixes them), `META-INF/version-control-info.textproto`. The latter contains the hash of the commit being built: two different commits therefore give different APKs, which is intended.

## Versions to pin

Upgrading any of these versions changes the APKs: the developer and F-Droid must build with the same ones.

| Tool | Version | Where it is pinned |
|---|---|---|
| Gradle | 9.5.0 | `gradle/wrapper/gradle-wrapper.properties` |
| AGP (internal R8, D8, aapt2, zipalign) | 9.3.3 | `gradle/libs.versions.toml` |
| Kotlin plugins (compiler built into AGP 9 for `:app`) | `kotlin` | `gradle/libs.versions.toml` |
| Compilation JDK (toolchain) | 17 | `kotlin { jvmToolchain(17) }` |
| Gradle daemon JDK (runs R8) | 25 | `gradle/gradle-daemon-jvm.properties` |
| NDK | 28.2.13676358 | `ndkVersion` in `app/build.gradle.kts` |
| CMake | 4.1.2 | `externalNativeBuild.cmake.version` |
| OpenJPEG | commit `8314119b` | submodule `app/src/main/cpp/openjpeg` |
| Maven dependencies | exact versions | `gradle/libs.versions.toml` |

The SDK build-tools are not used to produce the APK (aapt2 comes from Maven with AGP): their version has no effect.

## Verify

```bash
scripts/check-reproducible.sh            # HEAD commit
scripts/check-reproducible.sh v1.0.0     # a given revision
KEEP=1 scripts/check-reproducible.sh     # keeps both trees for investigation
```

The script clones the repository twice at the requested commit (submodule included), into two directories with different paths, the second one seeing the SDK through a symbolic link (different NDK path). It builds `:app:assembleRelease` in each, compares the APKs with `cmp` and prints their SHA-256; in case of a discrepancy, it lists the APK entries that differ. It depends only on `git`, `bash`, `cmp`, `sha256sum`, `unzip` and `diff`. For a `.so` that differs, `readelf -n` (build-id) and `strings -a` on the unstripped version in `app/build/intermediates/cxx/` show the paths involved; `diffoscope` does the full analysis if it is installed.

The script only checks what is committed: uncommitted local changes are ignored.

## On the F-Droid side

F-Droid publishes only one APK, the **universal** one (D38): a single recipe, a single `Builds` block per version, without `VercodeOperation`, with the same `versionCode` as `app/build.gradle.kts`. The per-architecture APKs remain published on GitHub releases only. Draft recipe and instructions: [`docs/fdroid/`](fdroid/).

- `output:` designates `build/outputs/apk/release/app-universal-release-unsigned.apk` (path relative to `subdir: app`), produced by `assembleRelease` together with the four per-ABI APKs, which are ignored;
- `binary:` points to the author-signed universal APK on the GitHub release, `https://github.com/mgdx/Sceau/releases/download/v%v/sceau-%v-universal.apk` (naming fixed by [`docs/release.md`](release.md));
- `AllowedAPKSigningKeys:` gives the SHA-256 fingerprint of the author's signing certificate.

F-Droid builds the universal APK from the tag commit, copies the signature of the author's APK onto its own APK, verifies the resulting signature, and publishes the author's APK only if it is valid, that is, if the contents are identical. The signing must therefore be done by `apksigner` (v2 and v3 schemes only, never v1: D54) on the unsigned APK exactly as Gradle produced it, without modifying its contents (procedure in `docs/release.md`). Before submitting, `scripts/check-reproducible.sh v<version>` must give identical APKs, the universal one included.
