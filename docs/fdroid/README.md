# F-Droid recipe

`io.github.mgdx.sceau.yml` is the draft of the recipe to be proposed to [fdroiddata](https://gitlab.com/fdroid/fdroiddata) (file `metadata/io.github.mgdx.sceau.yml`). Its form follows that of the accepted recipe of Roue Libre, the author's other application. Choices and rationale: D25 (reproducible builds) and D38 (universal APK only for F-Droid) in [`../decisions.md`](../decisions.md).

## Principle

F-Droid builds the **universal** APK from the tag (`app-universal-release-unsigned.apk`), downloads the author-signed APK (`Binaries:` field), copies its signature onto its own APK and verifies the result. If the contents are byte-for-byte identical, F-Droid publishes the author's APK, with its signature: an installation from F-Droid and another from the GitHub releases (same universal APK) can then update each other. Details: [`../reproducible-builds.md`](../reproducible-builds.md).

The asset expected on the GitHub release is named **`sceau-<version>-universal.apk`** (for example `sceau-1.0.0-universal.apk`, URL `https://github.com/mgdx/Sceau/releases/download/v1.0.0/sceau-1.0.0-universal.apk`). This name is fixed by [`../release.md`](../release.md): changing it requires changing the recipe.

## Manifest

The release APK requests only `android.permission.NFC` and exports a single component, `MainActivity`: the internal permission of androidx.core (D7), the exported receiver `ProfileInstallReceiver` and the profileinstaller and emoji2 initializers (D46) are removed from the merged manifest. To be checked on the universal APK before submission:

```bash
aapt2 dump xmltree --file AndroidManifest.xml app-universal-release-unsigned.apk | grep -B2 exported
```

## Build environment

The F-Droid build server (Debian trixie) provides neither the JDKs nor the CMake used by the project, and F-Droid rejects any binary JDK downloaded during the build. The recipe installs them itself (D49):

- **`sudo:`**: JDK 17 (`jvmToolchain(17)` toolchains of the three modules), taken from bookworm since trixie no longer has it, and JDK 25 (Gradle daemon, `gradle/gradle-daemon-jvm.properties`), then `update-java-alternatives -a`. Gradle finds the JDKs installed in `/usr/lib/jvm` on its own.
- **`prebuild:`** (run from `app/`, hence the `../`): removal of the `foojay-resolver-convention` plugin from `settings.gradle.kts` and `org.gradle.java.installations.auto-download=false` added to `gradle.properties`, so that a missing JDK makes the build fail instead of being downloaded (the daemon's `toolchainUrl` entries also point to foojay); `sdkmanager 'cmake;4.1.2'` (`externalNativeBuild.cmake.version`). NDK r28c comes from the `ndk:` field.

These modifications do not change the APK. Verified in a clone: `./gradlew :app:assembleRelease --offline` after the prebuild gives an `app-universal-release-unsigned.apk` byte-for-byte identical (`cmp`) to the one built without it. The local build and the CI keep foojay.

## Fill in before submission

1. **`commit:`**: full hash of the tag commit, never the tag name (a tag can be moved):

   ```bash
   git rev-parse 'v1.0.0^{commit}'
   ```

2. **`AllowedAPKSigningKeys:`**: SHA-256 fingerprint of the signing certificate, lowercase, without `:` or spaces:

   ```bash
   apksigner verify --print-certs sceau-1.0.0-universal.apk | grep 'SHA-256'
   ```

   It never changes as long as the keystore stays the same (`docs/release.md`, “Keystore” step).

3. **`versionName`, `versionCode`, `CurrentVersion`, `CurrentVersionCode`**: those of `app/build.gradle.kts` at the tag.

## Verify, then submit

In a clone of fdroiddata with `fdroidserver` installed:

```bash
cp …/Sceau/docs/fdroid/io.github.mgdx.sceau.yml metadata/
fdroid readmeta && fdroid rewritemeta io.github.mgdx.sceau   # syntax and canonical field order
fdroid lint io.github.mgdx.sceau
fdroid build -v -l io.github.mgdx.sceau                       # builds and compares with the author's APK
```

What the workstation needs for `fdroid build` to succeed (observed on 2026-10-12, D54):

- **fdroidserver from master** in a virtualenv (`pip install git+https://gitlab.com/fdroid/fdroidserver.git apksigcopier platformdirs`): this is what the fdroiddata CI runs. For `rewritemeta`, a second virtualenv with the released `fdroidserver` and `ruamel.yaml==0.18.6` (the Debian trixie version): recent ruamel versions wrap long values differently from the CI (lesson learned from Roue Libre);
- **`gradlew-fdroid`** (repository https://gitlab.com/fdroid/gradlew-fdroid, `gradlew.py`, which verifies the fingerprint of the Gradle distribution) behind a `gradle` script, designated by `gradle:` in `config.yml`;
- in `config.yml`, **`ndk_paths: {r28c: …/ndk/28.2.13676358}`**;
- a **`sdkmanager` run under JDK 21**: the one from the cmdline-tools crashes under JDK 25 (`NumberFormatException` in `Properties.store`);
- these additions to `config.yml` stay local: restore it before any commit in fdroiddata.

The scanner rejects a `scandelete` or `scanignore` path that serves no purpose (“Unused scandelete path”): the recipe has none. The expected result is “compared built binary to supplied reference binary successfully”.

Then open a merge request on fdroiddata (branch `io.github.mgdx.sceau` of the fork https://gitlab.com/mgdx/fdroiddata, title “New app: Sceau”, “App inclusion” template). Feedback received on Roue Libre, already applied here: full commit hash and never the tag, no `scanignore`, `subdir` filled in.

For a new version, F-Droid adds the `Builds` block itself (`AutoUpdateMode: Version`, tags `v1.0.0`, `v1.0.1`, `v1.1.0`…), provided that the `sceau-<version>-universal.apk` asset is published under that name.
