/*
 * Sceau - host test harness of the JPEG 2000 decoding core (jp2_decode.c).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Not compiled into the APK: driven by run-host-tests.sh, which encodes the reference images
 * with opj_compress (built from the same OpenJPEG submodule).
 *
 * Commands:
 *   gen DIR                           writes the reference images (PPM, PGM, raw YUV)
 *   check FILE REF MAX MEAN           decodes and compares to REF (tolerated max and mean diff)
 *   expect-fail FILE                  decoding must fail cleanly
 *   truncate FILE STEP                decodes each prefix (every STEP bytes)
 *   fuzz FILE N SEED                  decodes N variants with altered bytes
 *   patch-siz INPUT OUTPUT W H [TW TH]
 *                                     rewrites the declared dimensions (SIZ and ihdr) and,
 *                                     if TW and TH are given, those of the tiles (SIZ)
 *   budget FILE refuse|decode [W H]   decompression bomb: OpenJPEG's peak allocation stays
 *                                     under the budget; "refuse" requires a refusal,
 *                                     "decode" a decoding reduced to at most W x H, "any"
 *                                     accepts both (streams whose data is inconsistent)
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "jp2_alloc.h"
#include "jp2_decode.h"

typedef struct {
    uint32_t width;
    uint32_t height;
    uint8_t *rgb; /* width * height * 3 */
} rgb_image;

static int fail(const char *message) {
    fprintf(stderr, "FAILURE: %s\n", message);
    return 1;
}

static uint8_t *read_file(const char *path, size_t *length) {
    FILE *file = fopen(path, "rb");
    uint8_t *data;
    long size;
    if (file == NULL) {
        return NULL;
    }
    if (fseek(file, 0, SEEK_END) != 0 || (size = ftell(file)) < 0 || fseek(file, 0, SEEK_SET) != 0) {
        fclose(file);
        return NULL;
    }
    data = (uint8_t *) malloc(size > 0 ? (size_t) size : 1u);
    if (data == NULL || fread(data, 1, (size_t) size, file) != (size_t) size) {
        free(data);
        fclose(file);
        return NULL;
    }
    fclose(file);
    *length = (size_t) size;
    return data;
}

static int write_file(const char *path, const uint8_t *data, size_t length) {
    FILE *file = fopen(path, "wb");
    int ok;
    if (file == NULL) {
        return 0;
    }
    ok = fwrite(data, 1, length, file) == length;
    return fclose(file) == 0 && ok;
}

/* ---- Reference images ------------------------------------------------------------------ */

static uint8_t clamp_u8(float value) {
    if (value <= 0.0f) {
        return 0;
    }
    if (value >= 255.0f) {
        return 255;
    }
    return (uint8_t) (value + 0.5f);
}

static int write_ppm(const char *path, const rgb_image *image) {
    FILE *file = fopen(path, "wb");
    size_t size = (size_t) image->width * image->height * 3u;
    int ok;
    if (file == NULL) {
        return 0;
    }
    fprintf(file, "P6\n%u %u\n255\n", image->width, image->height);
    ok = fwrite(image->rgb, 1, size, file) == size;
    return fclose(file) == 0 && ok;
}

/*
 * RGB gradient of full_width x full_height, sampled one point every step: step = 2 gives the
 * reference of an image decoded at half resolution (the low-pass filter of the wavelet
 * transform preserves a linear gradient at even points).
 */
static int write_gradient(const char *path, uint32_t full_width, uint32_t full_height, uint32_t step) {
    rgb_image image;
    uint32_t x;
    uint32_t y;
    int ok;
    image.width = (full_width + step - 1u) / step;
    image.height = (full_height + step - 1u) / step;
    image.rgb = (uint8_t *) malloc((size_t) image.width * image.height * 3u);
    if (image.rgb == NULL) {
        return 0;
    }
    for (y = 0; y < image.height; y++) {
        for (x = 0; x < image.width; x++) {
            uint8_t *p = &image.rgb[((size_t) y * image.width + x) * 3u];
            const uint32_t fx = x * step;
            const uint32_t fy = y * step;
            p[0] = (uint8_t) (fx * 255u / (full_width - 1u));
            p[1] = (uint8_t) (fy * 255u / (full_height - 1u));
            p[2] = (uint8_t) ((fx + fy) * 255u / (full_width + full_height - 2u));
        }
    }
    ok = write_ppm(path, &image);
    free(image.rgb);
    return ok;
}

static int gen(const char *dir) {
    char path[4096];
    rgb_image image;
    uint32_t x;
    uint32_t y;
    FILE *file;

    /* 8-bit RGB gradient, odd dimensions. */
    image.width = 97;
    image.height = 61;
    image.rgb = (uint8_t *) malloc((size_t) image.width * image.height * 3u);
    if (image.rgb == NULL) {
        return fail("memory");
    }
    for (y = 0; y < image.height; y++) {
        for (x = 0; x < image.width; x++) {
            uint8_t *p = &image.rgb[((size_t) y * image.width + x) * 3u];
            p[0] = (uint8_t) (x * 255u / (image.width - 1u));
            p[1] = (uint8_t) (y * 255u / (image.height - 1u));
            p[2] = (uint8_t) ((x + y) * 255u / (image.width + image.height - 2u));
        }
    }
    snprintf(path, sizeof(path), "%s/rgb.ppm", dir);
    if (!write_ppm(path, &image)) {
        return fail("write rgb.ppm");
    }
    free(image.rgb);

    /* 8-bit grayscale. */
    snprintf(path, sizeof(path), "%s/gray.pgm", dir);
    file = fopen(path, "wb");
    if (file == NULL) {
        return fail("write gray.pgm");
    }
    fprintf(file, "P5\n80 50\n255\n");
    for (y = 0; y < 50; y++) {
        for (x = 0; x < 80; x++) {
            fputc((int) ((x * 3u + y * 2u) & 0xFFu), file);
        }
    }
    fclose(file);

    /* 12-bit grayscale (16-bit big-endian samples). */
    snprintf(path, sizeof(path), "%s/gray12.pgm", dir);
    file = fopen(path, "wb");
    if (file == NULL) {
        return fail("write gray12.pgm");
    }
    fprintf(file, "P5\n64 40\n4095\n");
    for (y = 0; y < 40; y++) {
        for (x = 0; x < 64; x++) {
            uint32_t value = (x * 64u + y * 13u) % 4096u;
            fputc((int) (value >> 8), file);
            fputc((int) (value & 0xFFu), file);
        }
    }
    fclose(file);

    /* Raw YCbCr 4:2:0 (Y, Cb, Cr planes) and its expected RGB equivalent. */
    {
        const uint32_t w = 64;
        const uint32_t h = 48;
        uint8_t *yuv = (uint8_t *) malloc(w * h + 2u * (w / 2u) * (h / 2u));
        uint8_t *cb_plane;
        uint8_t *cr_plane;
        if (yuv == NULL) {
            return fail("memory");
        }
        cb_plane = yuv + w * h;
        cr_plane = cb_plane + (w / 2u) * (h / 2u);
        for (y = 0; y < h; y++) {
            for (x = 0; x < w; x++) {
                yuv[y * w + x] = (uint8_t) (40u + (x + y) * 2u);
            }
        }
        for (y = 0; y < h / 2u; y++) {
            for (x = 0; x < w / 2u; x++) {
                cb_plane[y * (w / 2u) + x] = (uint8_t) (90u + x * 3u);
                cr_plane[y * (w / 2u) + x] = (uint8_t) (170u - y * 3u);
            }
        }
        snprintf(path, sizeof(path), "%s/yuv420.raw", dir);
        if (!write_file(path, yuv, w * h + 2u * (w / 2u) * (h / 2u))) {
            return fail("write yuv420.raw");
        }
        image.width = w;
        image.height = h;
        image.rgb = (uint8_t *) malloc((size_t) w * h * 3u);
        if (image.rgb == NULL) {
            return fail("memory");
        }
        for (y = 0; y < h; y++) {
            for (x = 0; x < w; x++) {
                const float luma = (float) yuv[y * w + x];
                const float cb = (float) cb_plane[(y / 2u) * (w / 2u) + x / 2u] - 128.0f;
                const float cr = (float) cr_plane[(y / 2u) * (w / 2u) + x / 2u] - 128.0f;
                uint8_t *p = &image.rgb[((size_t) y * w + x) * 3u];
                p[0] = clamp_u8(luma + 1.402f * cr);
                p[1] = clamp_u8(luma - 0.344136f * cb - 0.714136f * cr);
                p[2] = clamp_u8(luma + 1.772f * cb);
            }
        }
        snprintf(path, sizeof(path), "%s/yuv420.expected.ppm", dir);
        if (!write_ppm(path, &image)) {
            return fail("write yuv420.expected.ppm");
        }
        free(image.rgb);
        free(yuv);
    }

    /* Large legitimate image (more than 2048 pixels wide): decoded at half resolution. */
    snprintf(path, sizeof(path), "%s/big.ppm", dir);
    if (!write_gradient(path, 2500u, 1700u, 1u)) {
        return fail("write big.ppm");
    }
    snprintf(path, sizeof(path), "%s/big-half.ppm", dir);
    if (!write_gradient(path, 2500u, 1700u, 2u)) {
        return fail("write big-half.ppm");
    }

    /* Small raw 16-bit RGBA image (big-endian planes), basis of a 4-component bomb. */
    snprintf(path, sizeof(path), "%s/rgba16.raw", dir);
    file = fopen(path, "wb");
    if (file == NULL) {
        return fail("write rgba16.raw");
    }
    for (y = 0; y < 4u * 64u * 64u; y++) {
        fputc((int) ((y >> 4) & 0xFFu), file);
        fputc((int) (y & 0xFFu), file);
    }
    fclose(file);
    return 0;
}

/* Reads a PPM (P6) or a PGM (P5, 8 or 16 bits) and converts it to 8-bit RGB. */
static int read_pnm(const char *path, rgb_image *image) {
    FILE *file = fopen(path, "rb");
    char magic[3] = {0};
    unsigned width;
    unsigned height;
    unsigned maxval;
    size_t i;
    size_t count;
    int channels;
    if (file == NULL) {
        return 0;
    }
    if (fscanf(file, "%2s %u %u %u", magic, &width, &height, &maxval) != 4 || fgetc(file) == EOF ||
        maxval == 0 || maxval > 65535) {
        fclose(file);
        return 0;
    }
    channels = strcmp(magic, "P6") == 0 ? 3 : (strcmp(magic, "P5") == 0 ? 1 : 0);
    if (channels == 0) {
        fclose(file);
        return 0;
    }
    image->width = width;
    image->height = height;
    count = (size_t) width * height;
    image->rgb = (uint8_t *) malloc(count * 3u);
    if (image->rgb == NULL) {
        fclose(file);
        return 0;
    }
    for (i = 0; i < count * (size_t) channels; i++) {
        int high = fgetc(file);
        unsigned value = (unsigned) high;
        if (high == EOF) {
            fclose(file);
            return 0;
        }
        if (maxval > 255) {
            int low = fgetc(file);
            if (low == EOF) {
                fclose(file);
                return 0;
            }
            value = (value << 8) | (unsigned) low;
        }
        value = (value * 255u + maxval / 2u) / maxval;
        if (channels == 3) {
            image->rgb[i] = (uint8_t) value;
        } else {
            image->rgb[i * 3u] = image->rgb[i * 3u + 1u] = image->rgb[i * 3u + 2u] = (uint8_t) value;
        }
    }
    fclose(file);
    return 1;
}

static int check(const char *path, const char *reference_path, int max_tolerance, double mean_tolerance) {
    size_t length;
    uint8_t *data = read_file(path, &length);
    rgb_image reference;
    sceau_jp2_image decoded;
    sceau_jp2_status status;
    size_t i;
    int max_diff = 0;
    double total = 0.0;
    double mean;
    if (data == NULL) {
        return fail("reading the encoded file");
    }
    if (!read_pnm(reference_path, &reference)) {
        free(data);
        return fail("reading the reference");
    }
    status = sceau_jp2_decode(data, length, &decoded);
    free(data);
    if (status != SCEAU_JP2_OK) {
        fprintf(stderr, "FAILURE: %s not decoded (status %d)\n", path, (int) status);
        free(reference.rgb);
        return 1;
    }
    if (decoded.width != reference.width || decoded.height != reference.height) {
        fprintf(stderr, "FAILURE: %s dimensions %ux%u, expected %ux%u\n", path, decoded.width,
                decoded.height, reference.width, reference.height);
        sceau_jp2_image_free(&decoded);
        free(reference.rgb);
        return 1;
    }
    for (i = 0; i < (size_t) decoded.width * decoded.height; i++) {
        const uint32_t pixel = decoded.argb[i];
        const int channels[3] = {(int) ((pixel >> 16) & 0xFFu), (int) ((pixel >> 8) & 0xFFu),
                                 (int) (pixel & 0xFFu)};
        int c;
        if ((pixel >> 24) != 0xFFu) {
            fprintf(stderr, "FAILURE: %s alpha not opaque\n", path);
            sceau_jp2_image_free(&decoded);
            free(reference.rgb);
            return 1;
        }
        for (c = 0; c < 3; c++) {
            int diff = abs(channels[c] - (int) reference.rgb[i * 3u + (size_t) c]);
            if (diff > max_diff) {
                max_diff = diff;
            }
            total += diff;
        }
    }
    mean = total / ((double) decoded.width * decoded.height * 3.0);
    printf("%-28s %ux%u  max diff %3d  mean diff %.3f\n", path + (strrchr(path, '/') ? strrchr(path, '/') - path + 1 : 0),
           decoded.width, decoded.height, max_diff, mean);
    sceau_jp2_image_free(&decoded);
    free(reference.rgb);
    if (max_diff > max_tolerance || mean > mean_tolerance) {
        fprintf(stderr, "FAILURE: %s out of tolerance (max %d, mean %.3f)\n", path, max_tolerance,
                mean_tolerance);
        return 1;
    }
    return 0;
}

static int expect_fail(const char *path) {
    size_t length;
    uint8_t *data = read_file(path, &length);
    sceau_jp2_image decoded;
    sceau_jp2_status status;
    if (data == NULL) {
        return fail("read");
    }
    status = sceau_jp2_decode(data, length, &decoded);
    free(data);
    if (status == SCEAU_JP2_OK) {
        sceau_jp2_image_free(&decoded);
        fprintf(stderr, "FAILURE: %s decoded although it should have been refused\n", path);
        return 1;
    }
    if (decoded.argb != NULL || decoded.width != 0 || decoded.height != 0) {
        return fail("output not zeroed after failure");
    }
    printf("%-28s refused (status %d)\n", strrchr(path, '/') ? strrchr(path, '/') + 1 : path, (int) status);
    return 0;
}

static int truncate_all(const char *path, size_t step) {
    size_t length;
    uint8_t *data = read_file(path, &length);
    size_t prefix;
    unsigned ok = 0;
    unsigned refused = 0;
    if (data == NULL || step == 0) {
        free(data);
        return fail("read");
    }
    for (prefix = 0; prefix < length; prefix += step) {
        /* Copy of exactly the prefix size: ASan detects any read beyond it. */
        uint8_t *copy = (uint8_t *) malloc(prefix > 0 ? prefix : 1u);
        sceau_jp2_image decoded;
        if (copy == NULL) {
            free(data);
            return fail("memory");
        }
        memcpy(copy, data, prefix);
        if (sceau_jp2_decode(copy, prefix, &decoded) == SCEAU_JP2_OK) {
            ok++;
            sceau_jp2_image_free(&decoded);
        } else {
            refused++;
        }
        free(copy);
    }
    free(data);
    printf("%-28s %u truncated prefixes: %u refused, %u decoded\n",
           strrchr(path, '/') ? strrchr(path, '/') + 1 : path, ok + refused, refused, ok);
    return 0;
}

static uint32_t next_random(uint32_t *state) {
    /* xorshift32: reproducible from one run to the next. */
    uint32_t x = *state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    *state = x;
    return x;
}

static int fuzz(const char *path, unsigned iterations, uint32_t seed) {
    size_t length;
    uint8_t *data = read_file(path, &length);
    unsigned i;
    unsigned ok = 0;
    unsigned refused = 0;
    uint32_t state = seed != 0 ? seed : 1u;
    if (data == NULL || length == 0) {
        free(data);
        return fail("read");
    }
    for (i = 0; i < iterations; i++) {
        uint8_t *copy = (uint8_t *) malloc(length);
        sceau_jp2_image decoded;
        unsigned flips = 1u + next_random(&state) % 8u;
        unsigned f;
        if (copy == NULL) {
            free(data);
            return fail("memory");
        }
        memcpy(copy, data, length);
        for (f = 0; f < flips; f++) {
            copy[next_random(&state) % length] = (uint8_t) next_random(&state);
        }
        if (sceau_jp2_decode(copy, length, &decoded) == SCEAU_JP2_OK) {
            ok++;
            sceau_jp2_image_free(&decoded);
        } else {
            refused++;
        }
        free(copy);
    }
    free(data);
    printf("%-28s %u altered variants: %u refused, %u decoded\n",
           strrchr(path, '/') ? strrchr(path, '/') + 1 : path, iterations, refused, ok);
    return 0;
}

static void put_be32(uint8_t *p, uint32_t value) {
    p[0] = (uint8_t) (value >> 24);
    p[1] = (uint8_t) (value >> 16);
    p[2] = (uint8_t) (value >> 8);
    p[3] = (uint8_t) value;
}

static int patch_siz(const char *in, const char *out, uint32_t width, uint32_t height, uint32_t tile_width,
                     uint32_t tile_height) {
    size_t length;
    uint8_t *data = read_file(in, &length);
    size_t i;
    int patched = 0;
    int ok;
    if (data == NULL) {
        return fail("read");
    }
    for (i = 0; i + 16u <= length; i++) {
        /* ihdr box of a JP2: HEIGHT then WIDTH. */
        if (memcmp(&data[i], "ihdr", 4) == 0) {
            put_be32(&data[i + 4u], height);
            put_be32(&data[i + 8u], width);
        }
        /* SOC + SIZ: Lsiz(2) Rsiz(2) Xsiz(4) Ysiz(4) XOsiz(4) YOsiz(4). */
        if (data[i] == 0xFF && data[i + 1u] == 0x4F && data[i + 2u] == 0xFF && data[i + 3u] == 0x51) {
            put_be32(&data[i + 8u], width);
            put_be32(&data[i + 12u], height);
            /* Then XTsiz(4) YTsiz(4). */
            if (tile_width != 0u && tile_height != 0u && i + 32u <= length) {
                put_be32(&data[i + 24u], tile_width);
                put_be32(&data[i + 28u], tile_height);
            }
            patched = 1;
            break;
        }
    }
    ok = patched && write_file(out, data, length);
    free(data);
    return ok ? 0 : fail("SIZ marker not found");
}

static int budget(const char *path, const char *mode, uint32_t max_width, uint32_t max_height) {
    size_t length;
    uint8_t *data = read_file(path, &length);
    sceau_jp2_image decoded;
    sceau_jp2_status status;
    size_t peak;
    const char *name = strrchr(path, '/') ? strrchr(path, '/') + 1 : path;
    if (data == NULL) {
        return fail("read");
    }
    status = sceau_jp2_decode(data, length, &decoded);
    peak = sceau_jp2_alloc_peak();
    free(data);
    printf("%-28s status %d  %ux%u  OpenJPEG peak %.1f MB (budget %u MB)\n", name, (int) status,
           decoded.width, decoded.height, (double) peak / (1024.0 * 1024.0),
           SCEAU_JP2_MEMORY_BUDGET / (1024u * 1024u));
    if (peak > SCEAU_JP2_MEMORY_BUDGET) {
        sceau_jp2_image_free(&decoded);
        return fail("memory budget exceeded");
    }
    if (strcmp(mode, "refuse") == 0 && status == SCEAU_JP2_OK) {
        sceau_jp2_image_free(&decoded);
        fprintf(stderr, "FAILURE: %s decoded although it should have been refused\n", name);
        return 1;
    }
    if (strcmp(mode, "decode") == 0 &&
        (status != SCEAU_JP2_OK || decoded.width > max_width || decoded.height > max_height)) {
        sceau_jp2_image_free(&decoded);
        fprintf(stderr, "FAILURE: %s not decoded or not reduced to %ux%u\n", name, max_width, max_height);
        return 1;
    }
    if (status != SCEAU_JP2_OK && (decoded.argb != NULL || decoded.width != 0 || decoded.height != 0)) {
        return fail("output not zeroed after failure");
    }
    sceau_jp2_image_free(&decoded);
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 3 && strcmp(argv[1], "gen") == 0) {
        return gen(argv[2]);
    }
    if (argc == 6 && strcmp(argv[1], "check") == 0) {
        return check(argv[2], argv[3], atoi(argv[4]), atof(argv[5]));
    }
    if (argc == 3 && strcmp(argv[1], "expect-fail") == 0) {
        return expect_fail(argv[2]);
    }
    if (argc == 4 && strcmp(argv[1], "truncate") == 0) {
        return truncate_all(argv[2], (size_t) strtoul(argv[3], NULL, 10));
    }
    if (argc == 5 && strcmp(argv[1], "fuzz") == 0) {
        return fuzz(argv[2], (unsigned) strtoul(argv[3], NULL, 10), (uint32_t) strtoul(argv[4], NULL, 10));
    }
    if ((argc == 6 || argc == 8) && strcmp(argv[1], "patch-siz") == 0) {
        return patch_siz(argv[2], argv[3], (uint32_t) strtoul(argv[4], NULL, 10),
                         (uint32_t) strtoul(argv[5], NULL, 10),
                         argc == 8 ? (uint32_t) strtoul(argv[6], NULL, 10) : 0u,
                         argc == 8 ? (uint32_t) strtoul(argv[7], NULL, 10) : 0u);
    }
    if ((argc == 4 || argc == 6) && strcmp(argv[1], "budget") == 0) {
        return budget(argv[2], argv[3], argc == 6 ? (uint32_t) strtoul(argv[4], NULL, 10) : 0u,
                      argc == 6 ? (uint32_t) strtoul(argv[5], NULL, 10) : 0u);
    }
    fprintf(stderr, "usage: see the header of jp2_host_test.c\n");
    return 2;
}
