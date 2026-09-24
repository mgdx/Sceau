#!/usr/bin/env bash
# Sceau - génère testchip/src/main/resources/specimen-portrait.jp2, le portrait de la CNIe
# simulée (DG2).
#
# L'image est entièrement synthétique : fond en dégradé et silhouette anonyme (tête et épaules
# en aplats), dessinés pixel par pixel par ce script. Aucune photo de personne n'intervient.
# L'encodage JPEG 2000 est fait par opj_compress, construit hors du dépôt depuis le sous-module
# OpenJPEG (app/src/main/cpp/openjpeg), comme dans app/src/main/cpp/test/run-host-tests.sh.
#
# Usage : testchip/tools/generate-specimen-portrait.sh [DOSSIER_DE_BUILD]
# Prérequis : cmake, un compilateur C, python3, sous-module initialisé
# (git submodule update --init).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
openjpeg="$root/app/src/main/cpp/openjpeg"
output="$root/testchip/src/main/resources/specimen-portrait.jp2"
build="${1:-$(mktemp -d -t sceau-portrait-XXXXXX)}"
mkdir -p "$build"
build="$(cd "$build" && pwd)"

if [[ ! -f "$openjpeg/CMakeLists.txt" ]]; then
    echo "Sous-module OpenJPEG absent : git submodule update --init" >&2
    exit 1
fi

echo "== Construction d'opj_compress dans $build"
cmake -S "$openjpeg" -B "$build/opj-tools" -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF -DBUILD_CODEC=ON -DBUILD_TESTING=OFF -DBUILD_THIRDPARTY=OFF \
    -DCMAKE_C_FLAGS=-w -Wno-dev >/dev/null
cmake --build "$build/opj-tools" --target opj_compress -j"$(nproc)" >/dev/null 2>&1
opj_compress="$build/opj-tools/bin/opj_compress"

echo "== Dessin de la silhouette synthétique (240 x 320, PPM)"
python3 - "$build/specimen.ppm" <<'PY'
import sys

W, H = 240, 320
pixels = bytearray()
for y in range(H):
    for x in range(W):
        # Fond : dégradé vertical bleu-gris clair, comme un fond de photo d'identité.
        t = y / (H - 1)
        r, g, b = int(214 - 30 * t), int(222 - 26 * t), int(232 - 18 * t)
        # Épaules : demi-ellipse large en bas de l'image.
        sx, sy = (x - W / 2) / 110.0, (y - H) / 95.0
        # Cou : rectangle arrondi.
        neck = abs(x - W / 2) < 26 and 170 < y < 240
        # Tête : ellipse.
        hx, hy = (x - W / 2) / 62.0, (y - 125) / 80.0
        if sx * sx + sy * sy <= 1.0:
            r, g, b = 70, 78, 96
        elif hx * hx + hy * hy <= 1.0 or neck:
            shade = int(18 * max(0.0, hx))
            r, g, b = 150 - shade, 150 - shade, 158 - shade
        pixels += bytes((r, g, b))
with open(sys.argv[1], "wb") as f:
    f.write(b"P6\n%d %d\n255\n" % (W, H))
    f.write(pixels)
PY

echo "== Encodage JPEG 2000 (JP2, avec pertes, taux 25)"
"$opj_compress" -i "$build/specimen.ppm" -o "$output" -r 25 -I >/dev/null
size=$(wc -c <"$output")
echo "== $output : $size octets"
if (( size > 20480 )); then
    echo "Portrait trop gros (> 20 Ko)" >&2
    exit 1
fi
