#!/usr/bin/env bash
# Sceau - host tests of the native JPEG 2000 decoder (app/src/main/cpp/jp2_decode.c).
#
# Builds, outside the repository, OpenJPEG and opj_compress from the submodule, then the
# jp2_host_test harness under AddressSanitizer and UndefinedBehaviorSanitizer. Encodes synthetic
# images, decodes them with Sceau's core and compares the pixels; then submits truncated,
# altered and oversized streams, which must be refused without crashing, and decompression
# bombs, whose peak allocation must stay under the memory budget.
#
# Usage: app/src/main/cpp/test/run-host-tests.sh [BUILD_DIRECTORY]
# Prerequisites: cmake, a C compiler with -fsanitize=address,undefined, initialized submodule
# (git submodule update --init).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cpp_dir="$(cd "$here/.." && pwd)"
build="${1:-$(mktemp -d -t sceau-jp2-XXXXXX)}"
mkdir -p "$build"
build="$(cd "$build" && pwd)"
data="$build/data"
mkdir -p "$data"

if [[ ! -f "$cpp_dir/openjpeg/CMakeLists.txt" ]]; then
    echo "OpenJPEG submodule missing: git submodule update --init" >&2
    exit 1
fi

echo "== Building in $build"
cmake -S "$cpp_dir/openjpeg" -B "$build/opj-tools" -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF -DBUILD_CODEC=ON -DBUILD_TESTING=OFF -DBUILD_THIRDPARTY=OFF \
    -DCMAKE_C_FLAGS=-w -Wno-dev >/dev/null
# Encoding tool only: its warnings (upstream code) do not concern Sceau.
cmake --build "$build/opj-tools" --target opj_compress -j"$(nproc)" >/dev/null 2>&1
opj_compress="$build/opj-tools/bin/opj_compress"

sanitizers="-fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer"
cmake -S "$here" -B "$build/harness" -DCMAKE_BUILD_TYPE=Debug \
    -DCMAKE_C_FLAGS="-O1 -g $sanitizers" -DCMAKE_EXE_LINKER_FLAGS="$sanitizers" -Wno-dev >/dev/null
cmake --build "$build/harness" -j"$(nproc)" 2>&1 | grep -iE "warning|error" && {
    echo "Compilation warnings or errors" >&2
    exit 1
}
test_bin="$build/harness/jp2_host_test"
export ASAN_OPTIONS="detect_leaks=1:abort_on_error=1"
export UBSAN_OPTIONS="print_stacktrace=1:halt_on_error=1"

encode() { "$opj_compress" "$@" >/dev/null 2>&1; }

echo "== Reference images"
"$test_bin" gen "$data"
encode -i "$data/rgb.ppm" -o "$data/rgb-lossless.jp2"
encode -i "$data/rgb.ppm" -o "$data/rgb-lossy.jp2" -r 20 -I
encode -i "$data/rgb.ppm" -o "$data/rgb-lossless.j2k"
encode -i "$data/gray.pgm" -o "$data/gray.jp2"
encode -i "$data/gray.pgm" -o "$data/gray.j2k"
encode -i "$data/gray.pgm" -o "$data/gray-lossy.j2k" -r 10 -I
encode -i "$data/gray12.pgm" -o "$data/gray12.jp2"
encode -i "$data/yuv420.raw" -o "$data/yuv420.jp2" -F 64,48,3,8,u@1x1:2x2:2x2 -mct 0
encode -i "$data/yuv420.raw" -o "$data/yuv420.j2k" -F 64,48,3,8,u@1x1:2x2:2x2 -mct 0
encode -i "$data/rgb.ppm" -o "$data/rgb-tiled.j2k" -t 1024,1024
encode -i "$data/rgb.ppm" -o "$data/rgb-tiled.jp2" -t 1024,1024

echo "== Decoding and pixel comparison"
status=0
run() { "$test_bin" "$@" || status=1; }
run check "$data/rgb-lossless.jp2" "$data/rgb.ppm" 0 0
run check "$data/rgb-lossless.j2k" "$data/rgb.ppm" 0 0
run check "$data/rgb-tiled.jp2" "$data/rgb.ppm" 0 0
run check "$data/rgb-lossy.jp2" "$data/rgb.ppm" 40 3
run check "$data/gray.jp2" "$data/gray.pgm" 0 0
run check "$data/gray.j2k" "$data/gray.pgm" 0 0
run check "$data/gray-lossy.j2k" "$data/gray.pgm" 60 4
run check "$data/gray12.jp2" "$data/gray12.pgm" 0 0
run check "$data/yuv420.jp2" "$data/yuv420.expected.ppm" 1 0.01
run check "$data/yuv420.j2k" "$data/yuv420.expected.ppm" 1 0.01

echo "== Invalid streams: clean refusal expected"
: >"$data/empty.bin"
printf '\xff\xd8\xff\xe0\x00\x10JFIF\x00' >"$data/jpeg-header.bin"
head -c 4096 /dev/urandom >"$data/random.bin"
head -c 8 "$data/rgb-lossless.jp2" >"$data/jp2-signature-only.bin"
head -c 4 "$data/rgb-lossless.j2k" >"$data/j2k-signature-only.bin"
run patch-siz "$data/rgb-tiled.j2k" "$data/huge-60000.j2k" 60000 60000
run patch-siz "$data/rgb-tiled.jp2" "$data/huge-60000.jp2" 60000 60000
run patch-siz "$data/rgb-tiled.j2k" "$data/wide-4097.j2k" 4097 61
run patch-siz "$data/rgb-lossless.j2k" "$data/huge-untiled.j2k" 60000 60000
for f in empty.bin jpeg-header.bin random.bin jp2-signature-only.bin j2k-signature-only.bin \
    huge-60000.j2k huge-60000.jp2 wide-4097.j2k huge-untiled.j2k; do
    run expect-fail "$data/$f"
done

echo "== Large legitimate image: decoded at reduced resolution"
encode -i "$data/big.ppm" -o "$data/big.j2k"
encode -i "$data/big.ppm" -o "$data/big.jp2"
run check "$data/big.j2k" "$data/big-half.ppm" 3 1
run check "$data/big.jp2" "$data/big-half.ppm" 3 1
run budget "$data/big.j2k" decode 2048 2048

echo "== Decompression bombs: OpenJPEG memory budget respected"
# Small streams with hostile parameters, whose header is then patched to declare 4096 x 4096:
# 4 x 4 code blocks, 4 x 4 precincts (effective 2 x 2 blocks), 16384 tiles of 32 x 32,
# 4 components of 16 bits. All are generated here, no binary is versioned.
encode -i "$data/rgb.ppm" -o "$data/cb4-small.j2k" -b 4,4
encode -i "$data/rgb.ppm" -o "$data/cb4-small.jp2" -b 4,4
encode -i "$data/rgb.ppm" -o "$data/precincts-small.j2k" -c "[4,4],[4,4],[4,4],[4,4],[4,4],[4,4]"
encode -i "$data/rgb.ppm" -o "$data/tiles-small.j2k" -t 32,32
encode -i "$data/rgba16.raw" -o "$data/rgba16-small.j2k" -F 64,64,4,16,u
run patch-siz "$data/cb4-small.j2k" "$data/bomb-cb4.j2k" 4096 4096 4096 4096
run patch-siz "$data/cb4-small.jp2" "$data/bomb-cb4.jp2" 4096 4096 4096 4096
run patch-siz "$data/precincts-small.j2k" "$data/bomb-precincts.j2k" 4096 4096 4096 4096
run patch-siz "$data/tiles-small.j2k" "$data/bomb-tiles.j2k" 4096 4096
run patch-siz "$data/rgba16-small.j2k" "$data/bomb-rgba16.j2k" 4096 4096 4096 4096
run budget "$data/bomb-cb4.j2k" refuse
run budget "$data/bomb-cb4.jp2" refuse
run budget "$data/bomb-precincts.j2k" refuse
run budget "$data/bomb-tiles.j2k" refuse
# Reduced to 1024 x 1024 before decoding; its data, meant for 64 x 64, may fail to decode.
run budget "$data/bomb-rgba16.j2k" any

echo "== Truncated and altered streams: no crash expected"
run truncate "$data/rgb-lossless.jp2" 7
run truncate "$data/yuv420.j2k" 5
run truncate "$data/gray12.jp2" 3
run fuzz "$data/rgb-lossless.jp2" 3000 1
run fuzz "$data/rgb-lossy.jp2" 3000 2
run fuzz "$data/yuv420.j2k" 3000 3
run fuzz "$data/gray12.jp2" 3000 4

if [[ $status -eq 0 ]]; then
    echo "== All host tests passed (ASan + UBSan)"
else
    echo "== FAILURE of at least one host test" >&2
fi
exit $status
