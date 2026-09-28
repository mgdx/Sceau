#!/usr/bin/env bash
# Sceau - vérification du build reproductible des APK release (docs/reproducible-builds.md, D25).
#
# Clone deux fois le dépôt, au commit demandé, dans deux répertoires de chemins différents,
# construit les APK release non signés dans chacun (./gradlew :app:assembleRelease), puis compare
# les APK octet pour octet. En cas d'écart, liste les entrées de l'APK qui diffèrent.
#
# Usage : scripts/check-reproducible.sh [RÉVISION]      (HEAD par défaut)
# Variables : KEEP=1 conserve les deux arbres de build ; TMPDIR choisit où ils sont créés.
# Prérequis : git, JDK et SDK Android comme pour une build normale (ANDROID_HOME, ou le sdk.dir du
# local.properties du dépôt), NDK et CMake aux versions du build ;
# accès réseau pour le sous-module OpenJPEG et les dépendances Gradle absentes du cache.
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
    echo "SDK Android introuvable : définissez ANDROID_HOME." >&2
    exit 1
fi
# Le second arbre voit le SDK (donc le NDK) sous un autre chemin, par un lien symbolique, comme
# F-Droid dont le SDK n'est pas installé au même endroit que celui du développeur.
mkdir -p "$work/sdk"
ln -s "$sdk" "$work/sdk/android-sdk-bis"

# Deux chemins de longueurs et de profondeurs différentes : un chemin absolu qui fuirait dans
# un fichier de l'APK s'y verrait.
trees=("$work/a/Sceau" "$work/second-build/un/chemin/plus/long/sceau-bis")
sdks=("$sdk" "$work/sdk/android-sdk-bis")

for i in 0 1; do
    tree="${trees[$i]}"
    echo "== Construction dans $tree (SDK ${sdks[$i]})"
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
    echo "Aucun APK produit dans $out_a" >&2
    exit 1
fi
echo "== Comparaison (commit $commit)"
for apk_a in "${apks[@]}"; do
    name=$(basename "$apk_a")
    apk_b="$out_b/$name"
    if [[ ! -f "$apk_b" ]]; then
        echo "ABSENT    $name (second arbre)"
        status=1
        continue
    fi
    sum=$(sha256sum "$apk_a" | cut -d' ' -f1)
    if cmp -s "$apk_a" "$apk_b"; then
        echo "IDENTIQUE $sum  $name"
    else
        echo "DIFFÉRENT $name"
        mkdir -p "$work/diff/$name/a" "$work/diff/$name/b"
        unzip -q "$apk_a" -d "$work/diff/$name/a"
        unzip -q "$apk_b" -d "$work/diff/$name/b"
        diff -rq "$work/diff/$name/a" "$work/diff/$name/b" | sed "s|$work/diff/$name/||g" || true
        status=1
    fi
done

if [[ $status -eq 0 ]]; then
    echo "Build reproductible : APK identiques octet pour octet."
else
    echo "Build NON reproductible (arbres dans $work avec KEEP=1)." >&2
fi
exit $status
