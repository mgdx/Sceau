#!/usr/bin/env bash
# Sceau - reproducible build check for the release APKs (docs/reproducible-builds.md, D25).
#
# Clones the repository twice, at the requested commit, into two directories with different paths,
# builds the unsigned release APKs in each (./gradlew :app:assembleRelease), then compares the APKs
# byte for byte. If they differ, lists the APK entries that differ.
#
# Usage: scripts/check-reproducible.sh [REVISION]      (HEAD by default)
# Variables: KEEP=1 keeps both build trees; TMPDIR chooses where they are created.
# Requirements: git, JDK and Android SDK as for a normal build (ANDROID_HOME, or the sdk.dir of the
# repository's local.properties), NDK and CMake at the build's versions;
# network access for the OpenJPEG submodule and the Gradle dependencies missing from the cache.
set -euo pipefail

repo=$(git rev-parse --show-toplevel)
commit=$(git -C "$repo" rev-parse --verify "${1:-HEAD}^{commit}")
work=$(mktemp -d "${TMPDIR:-/tmp}/sceau-repro.XXXXXX")
if [[ "${KEEP:-0}" != 1 ]]; then
    trap 'rm -rf "$work"' EXIT
fi

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" && -f "$repo/local.properties" ]]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' "$repo/local.properties")
fi
if [[ -z "$sdk" || ! -d "$sdk" ]]; then
    echo "Android SDK not found: set ANDROID_HOME." >&2
    exit 1
fi
# The second tree sees the SDK (hence the NDK) under another path, through a symbolic link, like
# F-Droid, whose SDK is not installed in the same place as the developer's.
mkdir -p "$work/sdk"
ln -s "$sdk" "$work/sdk/android-sdk-bis"

# Two paths of different lengths and depths: an absolute path leaking into a file of the APK
# would show up.
trees=("$work/a/Sceau" "$work/second-build/a/much/longer/path/sceau-bis")
sdks=("$sdk" "$work/sdk/android-sdk-bis")

for i in 0 1; do
    tree="${trees[$i]}"
    echo "== Building in $tree (SDK ${sdks[$i]})"
    git clone --quiet --no-checkout "$repo" "$tree"
    git -C "$tree" checkout --quiet --detach "$commit"
    git -C "$tree" submodule update --init --quiet
    echo "sdk.dir=${sdks[$i]}" > "$tree/local.properties"
    (cd "$tree" && ./gradlew --no-daemon --quiet :app:assembleRelease)
done

out_a="${trees[0]}/app/build/outputs/apk/release"
out_b="${trees[1]}/app/build/outputs/apk/release"
status=0
shopt -s nullglob
apks=("$out_a"/*.apk)
if [[ ${#apks[@]} -eq 0 ]]; then
    echo "No APK produced in $out_a" >&2
    exit 1
fi
echo "== Comparison (commit $commit)"
for apk_a in "${apks[@]}"; do
    name=$(basename "$apk_a")
    apk_b="$out_b/$name"
    if [[ ! -f "$apk_b" ]]; then
        echo "MISSING   $name (second tree)"
        status=1
        continue
    fi
    sum=$(sha256sum "$apk_a" | cut -d' ' -f1)
    if cmp -s "$apk_a" "$apk_b"; then
        echo "IDENTICAL $sum  $name"
    else
        echo "DIFFERENT $name"
        mkdir -p "$work/diff/$name/a" "$work/diff/$name/b"
        unzip -q "$apk_a" -d "$work/diff/$name/a"
        unzip -q "$apk_b" -d "$work/diff/$name/b"
        diff -rq "$work/diff/$name/a" "$work/diff/$name/b" | sed "s|$work/diff/$name/||g" || true
        status=1
    fi
done

if [[ $status -eq 0 ]]; then
    echo "Reproducible build: APKs identical byte for byte."
else
    echo "Build NOT reproducible (trees in $work with KEEP=1)." >&2
fi
exit $status
