# Publishing a release

Step-by-step procedure for publishing a version of Sceau on GitHub releases (https://github.com/mgdx/Sceau/releases) and then on F-Droid. Decisions: D2 (ABI splits), D25 (reproducible builds), D38 (license, hosting, universal APK for F-Droid), D53 (first release 1.0.0, `X.Y.Z` numbering). Worked example: version **1.0.0**.

Rules that allow no exception:

- **no secret in the repository**: no keystore, no password, no `keystore.properties`. `*.jks` and `*.keystore` are in `.gitignore`, but the keystore must live **outside** the clone anyway;
- **no `signingConfig` in Gradle**: `assembleRelease` produces unsigned APKs, which are then signed by hand with `apksigner`. This is what lets F-Droid rebuild the same unsigned APKs and apply the author's signature to them;
- **never modify an APK after `assembleRelease`** other than with `apksigner sign` (no `zipalign`, which would rewrite the archive, no recompression): F-Droid compares its unsigned APK with the author's APK stripped of its signature.

## 1. Prepare the version

1. `app/build.gradle.kts`: `versionName` (`X.Y.Z` format) and `versionCode` (strictly increasing integer; `1` for 1.0.0). A single `versionCode` for all APKs: no per-ABI code (D38).
2. Fastlane changelog: `fastlane/metadata/android/fr-FR/changelogs/<versionCode>.txt` and `en-US/…` (500 characters at most).
3. `docs/` up to date (SPEC §11, `CLAUDE.md`, decisions), commit `chore(release): version 1.0.0` on `master`.

## 2. Pre-release checks

On a clean clone of `master` (empty `git status`, submodule up to date: `git submodule update --init`):

| Check | Command or document | Expected |
|---|---|---|
| Tests, lint, ktlint | `./gradlew check` | success, zero warnings |
| Trust store | `scripts/update-trust-store.sh --ants-dir DIR` (procedure in `docs/trust-store.md`) | no certificate or Master List to update; otherwise, update in a separate commit, before the release |
| OpenJPEG | D3 watch: upstream `git log` since the pinned commit, security advisories; `app/src/main/cpp/test/run-host-tests.sh` | no pending decoding fix, harness green |
| Security review | `android-securite` skill | no open vulnerability of high or critical severity |
| Reproducibility | `scripts/check-reproducible.sh` | five byte-for-byte identical APKs (universal included) |
| Test plan | `docs/test-plan.md`, **on the signed release APK** (step 4) installed on a real phone, cases TP-38 to TP-41 at minimum, plus the cases affected by the version | all OK, dated report |
| F-Droid review | `android-fdroid` skill (first submission, then at every dependency change) | no blocker |

The test plan is run on the release APK, not on the debug one: R8 (D24) may remove a class that only a real read reaches.

## 3. Keystore (once and for all)

To be done **only once** for the whole life of the application: a different key would prevent already-installed users from updating, and would change `AllowedAPKSigningKeys` on F-Droid.

```bash
keytool -genkeypair -v \
  -keystore ~/.android-keys/sceau-release.jks -storetype PKCS12 \
  -alias sceau -keyalg RSA -keysize 4096 -validity 11000 \
  -dname "CN=mgdx, O=Sceau"
```

- RSA 4096, validity of 11,000 days (30 years and more): Google Play and F-Droid both recommend a validity extending beyond 2033;
- the file lives **outside the repository** (here `~/.android-keys/`, next to the keys of other applications, each under its own name and its own alias), with a strong password kept in a password manager;
- encrypted **backups** on at least two separate media. A lost key forces a new application (different signature); a stolen key makes it possible to publish a fake update.

Certificate fingerprint, to be entered in the F-Droid recipe (`AllowedAPKSigningKeys`, lowercase, without `:`):

```bash
keytool -J-Duser.language=en -list -v -keystore ~/.android-keys/sceau-release.jks -alias sceau | grep 'SHA256:'
```

`-J-Duser.language=en` works around a keytool 25 bug in French (`MissingFormatArgumentException` when displaying the certificate).

Key created on 2026-10-12, password and file kept in Bitwarden (“Sceau — keystore release Android”). Fingerprint:

```
DE:E8:10:5B:0B:0F:27:B4:44:B3:30:3E:0B:34:02:28:35:18:8D:49:D9:52:FF:25:42:FB:BC:05:79:27:43:4B
```

## 4. Build and sign

```bash
./gradlew clean :app:assembleRelease
ls app/build/outputs/apk/release/
# app-arm64-v8a-release-unsigned.apk   app-armeabi-v7a-release-unsigned.apk
# app-x86-release-unsigned.apk         app-x86_64-release-unsigned.apk
# app-universal-release-unsigned.apk
```

The APKs come out of Gradle already aligned (entries on 4 bytes, uncompressed native libraries on 16 KB pages): **no `zipalign`**. To make sure:

```bash
BT=$ANDROID_HOME/build-tools/37.0.0
$BT/zipalign -c -P 16 4 app/build/outputs/apk/release/app-universal-release-unsigned.apk
```

Signing with the **v2 and v3 schemes only**, preserving the existing alignment, with the asset naming. **Never v1** (D54): minSdk 26 does not need it, and `apksigcopier`, the tool F-Droid uses to copy the author's signature onto its own build, cannot reproduce the `META-INF/` entries of a v1 signature written by apksigner 37 (“CHUNKED_SHA512 digest mismatch” verification error). The password is read once, without echo:

```bash
VERSION=1.0.0
OUT=~/releases/sceau-$VERSION && mkdir -p "$OUT"
read -rsp "Keystore password: " SCEAU_KS_PASS; echo; export SCEAU_KS_PASS
for abi in arm64-v8a armeabi-v7a x86 x86_64 universal; do
  $BT/apksigner sign \
    --ks ~/.android-keys/sceau-release.jks --ks-key-alias sceau --ks-pass env:SCEAU_KS_PASS \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --alignment-preserved true \
    --out "$OUT/sceau-$VERSION-$abi.apk" \
    "app/build/outputs/apk/release/app-$abi-release-unsigned.apk"
done
unset SCEAU_KS_PASS
rm -f "$OUT"/*.idsig
```

Checks:

```bash
for f in "$OUT"/*.apk; do $BT/apksigner verify --print-certs "$f" | grep 'SHA-256 digest'; done   # same certificate everywhere
$BT/zipalign -c -P 16 4 "$OUT/sceau-$VERSION-universal.apk"
# No added entry (no v1 signature):
diff <(unzip -Z1 app/build/outputs/apk/release/app-universal-release-unsigned.apk) \
     <(unzip -Z1 "$OUT/sceau-$VERSION-universal.apk")
# F-Droid's own check, mandatory (pip install apksigcopier):
for abi in arm64-v8a armeabi-v7a x86 x86_64 universal; do apksigcopier compare "$OUT/sceau-$VERSION-$abi.apk" --unsigned "app/build/outputs/apk/release/app-$abi-release-unsigned.apk" && echo "$abi OK"; done
```

`dependenciesInfo` is already disabled in `app/build.gradle.kts` (D25): no dependency metadata block is added to the signing block.

Asset naming, not to be changed (the F-Droid recipe depends on it for the universal APK):

| Asset | Contents |
|---|---|
| `sceau-<version>-arm64-v8a.apk` | 64-bit ARM phones (nearly all recent devices) |
| `sceau-<version>-armeabi-v7a.apk` | 32-bit ARM phones |
| `sceau-<version>-x86.apk`, `sceau-<version>-x86_64.apk` | emulators, rare Intel tablets |
| `sceau-<version>-universal.apk` | all architectures; this is the APK published by F-Droid |
| `SHA256SUMS` | SHA-256 fingerprints of the five APKs |

```bash
(cd "$OUT" && sha256sum sceau-*.apk > SHA256SUMS && cat SHA256SUMS)
```

## 5. Tag and GitHub release

**Annotated** tag, in `vX.Y.Z` format, on the exact commit built in step 4:

```bash
git tag -a v1.0.0 -m "Sceau 1.0.0"
git push origin master
git push --tags
gh release create v1.0.0 \
  --title "Sceau 1.0.0" \
  --notes-file fastlane/metadata/android/fr-FR/changelogs/1.txt \
  "$OUT"/sceau-1.0.0-*.apk "$OUT"/SHA256SUMS
```

Never move a published tag: F-Droid and Obtainium rely on it. A mistake is fixed with a new version (`v1.0.1`).

Then install the published APK (downloaded from the release page, not the local file) on a phone and perform a real read again: this is the APK users will receive.

## 6. F-Droid

First submission: fill in `docs/fdroid/io.github.mgdx.sceau.yml` (tag commit hash, certificate fingerprint) and follow [`docs/fdroid/README.md`](fdroid/README.md): `fdroid build` in a clone of fdroiddata, then a merge request on https://gitlab.com/fdroid/fdroiddata.

Subsequent versions: nothing to do if the `vX.Y.Z` tag and the `sceau-X.Y.Z-universal.apk` asset are published and the `versionCode` has increased: F-Droid detects the tag (`UpdateCheckMode: Tags`), adds the build block and compares its APK with the one in the release. If the comparison fails, F-Droid publishes nothing: rerun `scripts/check-reproducible.sh vX.Y.Z` and read `docs/reproducible-builds.md`.
