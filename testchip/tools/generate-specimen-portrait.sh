#!/usr/bin/env bash
# Sceau - generates testchip/src/main/resources/specimen-portrait.jp2, the portrait of the
# simulated CNIe (DG2).
#
# The image is entirely synthetic: gradient background and anonymous silhouette (head and
# shoulders in flat colors), drawn pixel by pixel by this script. No photo of a person is involved.
# JPEG 2000 encoding is done by opj_compress, built outside the repository from the OpenJPEG
# submodule (app/src/main/cpp/openjpeg), as in app/src/main/cpp/test/run-host-tests.sh.
#
# Usage: testchip/tools/generate-specimen-portrait.sh [BUILD_DIR]
# Requirements: cmake, a C compiler, python3, initialized submodule
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
    echo "OpenJPEG submodule missing: git submodule update --init" >&2
    exit 1
fi

echo "== Building opj_compress in $build"
cmake -S "$openjpeg" -B "$build/opj-tools" -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF -DBUILD_CODEC=ON -DBUILD_TESTING=OFF -DBUILD_THIRDPARTY=OFF \
    -DCMAKE_C_FLAGS=-w -Wno-dev >/dev/null
cmake --build "$build/opj-tools" --target opj_compress -j"$(nproc)" >/dev/null 2>&1
opj_compress="$build/opj-tools/bin/opj_compress"

echo "== Drawing the synthetic silhouette (240 x 320, PPM)"
python3 - "$build/specimen.ppm" <<'PY'
import sys

W, H = 240, 320
pixels = bytearray()
for y in range(H):
    for x in range(W):
        # Background: light blue-gray vertical gradient, like an ID photo background.
        t = y / (H - 1)
        r, g, b = int(214 - 30 * t), int(222 - 26 * t), int(232 - 18 * t)
        # Shoulders: wide half-ellipse at the bottom of the image.
        sx, sy = (x - W / 2) / 110.0, (y - H) / 95.0
        # Neck: rounded rectangle.
        neck = abs(x - W / 2) < 26 and 170 < y < 240
        # Head: ellipse.
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

echo "== JPEG 2000 encoding (JP2, lossy, rate 25)"
"$opj_compress" -i "$build/specimen.ppm" -o "$output" -r 25 -I >/dev/null
size=$(wc -c <"$output")
echo "== $output: $size bytes"
if (( size > 20480 )); then
    echo "Portrait too large (> 20 KB)" >&2
    exit 1
fi
