/*
 * Sceau - in-memory JPEG 2000 decoding on top of OpenJPEG.
 * SPDX-License-Identifier: GPL-3.0-only
 */
#include "jp2_decode.h"

#include <stdlib.h>
#include <string.h>

#include "jp2_alloc.h"
#include "openjpeg.h"

/* Signatures: "jP  " box of a JP2 file, SOC + SIZ markers of a raw J2K stream. */
static const uint8_t JP2_SIGNATURE[] = {0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20};
static const uint8_t J2K_SIGNATURE[] = {0xFF, 0x4F, 0xFF, 0x51};

/* Maximum number of accepted components (grayscale, + alpha, RGB, + alpha). */
#define MAX_COMPONENTS 4u

/* Maximum precision of a component, in bits. */
#define MAX_PRECISION 16u

void sceau_jp2_secure_zero(void *buffer, size_t length) {
    if (buffer == NULL || length == 0) {
        return;
    }
    memset(buffer, 0, length);
    /* Barrier: the compiler must assume that the buffer is still read. */
    __asm__ __volatile__("" : : "r"(buffer) : "memory");
}

void sceau_jp2_image_free(sceau_jp2_image *image) {
    if (image == NULL) {
        return;
    }
    if (image->argb != NULL) {
        sceau_jp2_secure_zero(image->argb, (size_t) image->width * image->height * sizeof(uint32_t));
        free(image->argb);
    }
    image->argb = NULL;
    image->width = 0;
    image->height = 0;
}

/* ---- OpenJPEG stream over a byte array -------------------------------------------------- */

typedef struct {
    const uint8_t *data;
    size_t length;
    size_t position;
} memory_stream;

static OPJ_SIZE_T memory_read(void *buffer, OPJ_SIZE_T size, void *user_data) {
    memory_stream *stream = (memory_stream *) user_data;
    size_t remaining;
    if (stream->position >= stream->length) {
        return (OPJ_SIZE_T) -1;
    }
    remaining = stream->length - stream->position;
    if (size > remaining) {
        size = remaining;
    }
    memcpy(buffer, stream->data + stream->position, size);
    stream->position += size;
    return size;
}

static OPJ_OFF_T memory_skip(OPJ_OFF_T count, void *user_data) {
    memory_stream *stream = (memory_stream *) user_data;
    if (count < 0) {
        if ((uint64_t) (-count) > stream->position) {
            return (OPJ_OFF_T) -1;
        }
        stream->position -= (size_t) (-count);
        return count;
    }
    if ((uint64_t) count > stream->length - stream->position) {
        stream->position = stream->length;
        return (OPJ_OFF_T) -1;
    }
    stream->position += (size_t) count;
    return count;
}

static OPJ_BOOL memory_seek(OPJ_OFF_T offset, void *user_data) {
    memory_stream *stream = (memory_stream *) user_data;
    if (offset < 0 || (uint64_t) offset > stream->length) {
        return OPJ_FALSE;
    }
    stream->position = (size_t) offset;
    return OPJ_TRUE;
}

static void memory_free(void *user_data) {
    /* The stream does not own the data: nothing to free. */
    (void) user_data;
}

/* OpenJPEG message handler: silent, no logging (SPEC §8). */
static void silent_handler(const char *message, void *client_data) {
    (void) message;
    (void) client_data;
}

/* ---- Conversion to ARGB ---------------------------------------------------------------- */

static int starts_with(const uint8_t *data, size_t length, const uint8_t *prefix, size_t prefix_length) {
    return length >= prefix_length && memcmp(data, prefix, prefix_length) == 0;
}

/* Division by 2^shift rounded up, like opj_int_ceildivpow2 (shift <= 32). */
static uint64_t ceil_shift(uint64_t value, uint32_t shift) {
    return (value + (((uint64_t) 1 << shift) - 1u)) >> shift;
}

static uint64_t ceil_div(uint64_t value, uint64_t divisor) {
    return (value + divisor - 1u) / divisor;
}

/*
 * Sample of the component at point (x, y) of the reference grid, scaled to 8 bits.
 * A subsampled component (dx, dy > 1, for example 4:2:0 chrominance) is upscaled with
 * nearest neighbor: sample i covers points i*dx to i*dx + dx - 1.
 * If the resolution is reduced by reduce levels, (x, y) is a point of the reduced grid
 * (divided by 2^reduce) and the origin of the component is reduced likewise.
 */
static int sample8(const opj_image_comp_t *comp, uint32_t reduce, uint32_t image_x, uint32_t image_y) {
    uint32_t cx = image_x / comp->dx;
    uint32_t cy = image_y / comp->dy;
    const uint32_t origin_x = (uint32_t) ceil_shift(comp->x0, reduce);
    const uint32_t origin_y = (uint32_t) ceil_shift(comp->y0, reduce);
    int64_t value;
    int64_t max;

    /* Coordinates relative to the origin of the component, clamped (subsampling). */
    cx = cx > origin_x ? cx - origin_x : 0u;
    cy = cy > origin_y ? cy - origin_y : 0u;
    if (cx >= comp->w) {
        cx = comp->w - 1u;
    }
    if (cy >= comp->h) {
        cy = comp->h - 1u;
    }

    value = comp->data[(size_t) cy * comp->w + cx];
    if (comp->sgnd) {
        value += (int64_t) 1 << (comp->prec - 1u);
    }
    max = ((int64_t) 1 << comp->prec) - 1;
    if (value < 0) {
        value = 0;
    } else if (value > max) {
        value = max;
    }
    if (comp->prec == 8u) {
        return (int) value;
    }
    return (int) ((value * 255 + max / 2) / max);
}

static uint32_t clamp8(float value) {
    if (value <= 0.0f) {
        return 0u;
    }
    if (value >= 255.0f) {
        return 255u;
    }
    return (uint32_t) (value + 0.5f);
}

static int is_ycc(const opj_image_t *image) {
    if (image->numcomps < 3u) {
        return 0;
    }
    if (image->color_space == OPJ_CLRSPC_SYCC) {
        return 1;
    }
    /* Same heuristic as opj_decompress: undeclared color space and subsampled chrominance. */
    if (image->color_space == OPJ_CLRSPC_UNKNOWN || image->color_space == OPJ_CLRSPC_UNSPECIFIED) {
        return image->comps[0].dx == 1u && image->comps[0].dy == 1u &&
               (image->comps[1].dx != 1u || image->comps[1].dy != 1u ||
                image->comps[2].dx != 1u || image->comps[2].dy != 1u);
    }
    return 0;
}

static int components_valid(const opj_image_t *image) {
    uint32_t i;
    if (image->numcomps == 0u || image->numcomps > MAX_COMPONENTS || image->comps == NULL) {
        return 0;
    }
    for (i = 0; i < image->numcomps; i++) {
        const opj_image_comp_t *comp = &image->comps[i];
        if (comp->dx == 0u || comp->dy == 0u || comp->prec == 0u || comp->prec > MAX_PRECISION) {
            return 0;
        }
    }
    return 1;
}

static int components_decoded(const opj_image_t *image) {
    uint32_t i;
    if (!components_valid(image)) {
        return 0;
    }
    for (i = 0; i < image->numcomps; i++) {
        const opj_image_comp_t *comp = &image->comps[i];
        if (comp->data == NULL || comp->w == 0u || comp->h == 0u) {
            return 0;
        }
    }
    return 1;
}


/* Decoded image, converted to ARGB row by row (sceau_jp2_decoded_row). */
struct sceau_jp2_decoded {
    opj_image_t *image;
    uint32_t reduce; /* resolution levels removed: the image is divided by 2^reduce */
    uint32_t x0;     /* origin of the reduced reference grid */
    uint32_t y0;
    uint32_t width;
    uint32_t height;
    int color;
    int ycc;
};

void sceau_jp2_decoded_row(const sceau_jp2_decoded *decoded, uint32_t y, uint32_t *row) {
    const opj_image_t *image;
    uint32_t image_y;
    uint32_t reduce;
    uint32_t x;
    if (decoded == NULL || row == NULL || y >= decoded->height) {
        return;
    }
    image = decoded->image;
    reduce = decoded->reduce;
    image_y = decoded->y0 + y;
    for (x = 0; x < decoded->width; x++) {
        const uint32_t image_x = decoded->x0 + x;
        uint32_t r;
        uint32_t g;
        uint32_t b;
        if (!decoded->color) {
            r = g = b = (uint32_t) sample8(&image->comps[0], reduce, image_x, image_y);
        } else if (decoded->ycc) {
            const float luma = (float) sample8(&image->comps[0], reduce, image_x, image_y);
            const float cb = (float) (sample8(&image->comps[1], reduce, image_x, image_y) - 128);
            const float cr = (float) (sample8(&image->comps[2], reduce, image_x, image_y) - 128);
            r = clamp8(luma + 1.402f * cr);
            g = clamp8(luma - 0.344136f * cb - 0.714136f * cr);
            b = clamp8(luma + 1.772f * cb);
        } else {
            r = (uint32_t) sample8(&image->comps[0], reduce, image_x, image_y);
            g = (uint32_t) sample8(&image->comps[1], reduce, image_x, image_y);
            b = (uint32_t) sample8(&image->comps[2], reduce, image_x, image_y);
        }
        row[x] = 0xFF000000u | (r << 16) | (g << 8) | b;
    }
}

/* Zeroes the decoded samples (the photo) before freeing the OpenJPEG image. */
static void destroy_image(opj_image_t *image) {
    uint32_t i;
    if (image == NULL) {
        return;
    }
    if (image->comps != NULL) {
        for (i = 0; i < image->numcomps; i++) {
            opj_image_comp_t *comp = &image->comps[i];
            if (comp->data != NULL) {
                sceau_jp2_secure_zero(comp->data, (size_t) comp->w * comp->h * sizeof(OPJ_INT32));
            }
        }
    }
    opj_image_destroy(image);
}

void sceau_jp2_decoded_free(sceau_jp2_decoded *decoded) {
    if (decoded == NULL) {
        return;
    }
    destroy_image(decoded->image);
    free(decoded);
}

static int dimensions_acceptable(const opj_image_t *image) {
    return image->x1 > image->x0 && image->y1 > image->y0 &&
           image->x1 - image->x0 <= SCEAU_JP2_MAX_DIMENSION &&
           image->y1 - image->y0 <= SCEAU_JP2_MAX_DIMENSION;
}

/* Extent [start, end) of the reference grid once the resolution is reduced. */
static uint64_t reduced_extent(uint32_t start, uint32_t end, uint32_t reduce) {
    return ceil_shift(end, reduce) - ceil_shift(start, reduce);
}

/* ---- Memory budget ------------------------------------------------------------------- */

/*
 * Unit costs, in bytes, of the structures OpenJPEG allocates for decoding, measured on the
 * pinned version in 64-bit and increased by the allocation headers (docs/decisions.md, D3).
 */
/* Parameters of a tile (opj_tcp_t, stream index), read as early as the main header. */
#define COST_TILE 8192u
/* Parameters of a component within a tile (opj_tccp_t). */
#define COST_TILE_COMPONENT 1152u
/* Code block: structure, nodes of the inclusion and zero bit-plane trees, segments. */
#define COST_CODE_BLOCK 448u
/* Data chunk of a code block, per quality layer. */
#define COST_CODE_BLOCK_LAYER 16u
/* Sub-band and precinct of a resolution. */
#define COST_BAND 512u
/* Codec, stream read buffer (1 MB), wavelet transform, work buffers. */
#define COST_FIXED (4u * 1024u * 1024u)

/*
 * Number of code blocks of a tile component of width x height samples, all resolutions
 * included: OpenJPEG allocates all of them, including those a resolution reduction does not
 * decode. Declared precincts (Scod) can further reduce the effective size of the blocks: the
 * API does not report their size reliably, so this case is left to the allocation cap
 * (jp2_alloc.c).
 */
static uint64_t code_blocks(uint64_t width, uint64_t height, const opj_tccp_info_t *tccp) {
    uint64_t total = 0;
    uint32_t resno;
    for (resno = 0; resno < tccp->numresolutions; resno++) {
        const uint32_t level = tccp->numresolutions - 1u - resno;
        /* Resolution 0: one LL sub-band; beyond: HL, LH and HH, at half size. */
        const uint32_t band_level = resno == 0u ? level : level + 1u;
        const uint64_t bands = resno == 0u ? 1u : 3u;
        const uint64_t band_width = ceil_shift(width, band_level);
        const uint64_t band_height = ceil_shift(height, band_level);
        total += bands * (ceil_shift(band_width, tccp->cblkw) + 1u) *
                 (ceil_shift(band_height, tccp->cblkh) + 1u);
    }
    return total;
}

/*
 * Memory OpenJPEG will allocate to decode the image at the resolution reduced by reduce
 * levels. Decoded samples take 32 bits each whatever their precision.
 * Tiles are decoded one after the other: their code blocks and samples are counted only
 * once, their parameters as many times as there are tiles.
 */
static uint64_t estimate_memory(const opj_image_t *image, const opj_codestream_info_v2_t *info,
                                size_t length, uint32_t reduce) {
    const uint64_t width = image->x1 - image->x0;
    const uint64_t height = image->y1 - image->y0;
    const uint64_t tiles = (uint64_t) info->tw * info->th;
    const uint64_t tile_width = info->tdx < width ? info->tdx : width;
    const uint64_t tile_height = info->tdy < height ? info->tdy : height;
    const uint64_t block_cost =
        COST_CODE_BLOCK + (uint64_t) info->m_default_tile_info.numlayers * COST_CODE_BLOCK_LAYER;
    /* Compressed data of the tile parts, copied by OpenJPEG before decoding. */
    uint64_t total = COST_FIXED + (uint64_t) length +
                     tiles * (COST_TILE + (uint64_t) image->numcomps * COST_TILE_COMPONENT);
    uint32_t i;

    for (i = 0; i < image->numcomps; i++) {
        const opj_image_comp_t *comp = &image->comps[i];
        const opj_tccp_info_t *tccp = &info->m_default_tile_info.tccp_info[i];
        const uint64_t tile_comp_width = ceil_div(tile_width, comp->dx);
        const uint64_t tile_comp_height = ceil_div(tile_height, comp->dy);
        /* Samples of the decoded image. */
        total += ceil_shift(ceil_div(width, comp->dx), reduce) *
                 ceil_shift(ceil_div(height, comp->dy), reduce) * sizeof(OPJ_INT32);
        /* Several tiles: samples of the current tile, before being copied into the image. */
        if (tiles > 1u) {
            total += ceil_shift(tile_comp_width, reduce) * ceil_shift(tile_comp_height, reduce) *
                     sizeof(OPJ_INT32);
        }
        total += code_blocks(tile_comp_width, tile_comp_height, tccp) * block_cost;
        total += (uint64_t) tccp->numresolutions * 3u * COST_BAND;
    }
    return total;
}

/*
 * Chooses the smallest resolution reduction that brings the image under
 * SCEAU_JP2_MAX_OUTPUT_DIMENSION per side and its decoding under SCEAU_JP2_MEMORY_BUDGET.
 * No possible reduction (too few resolution levels): SCEAU_JP2_ERR_TOO_LARGE.
 */
static sceau_jp2_status choose_reduction(opj_codec_t *codec, const opj_image_t *image, size_t length,
                                         uint32_t *reduce) {
    opj_codestream_info_v2_t *info = opj_get_cstr_info(codec);
    sceau_jp2_status status = SCEAU_JP2_ERR_TOO_LARGE;
    uint32_t max_reduce = OPJ_J2K_MAXRLVLS;
    uint32_t candidate;
    uint32_t i;

    if (info == NULL) {
        return sceau_jp2_alloc_exhausted() ? SCEAU_JP2_ERR_TOO_LARGE : SCEAU_JP2_ERR_MEMORY;
    }
    if (info->nbcomps != image->numcomps || info->m_default_tile_info.tccp_info == NULL ||
        info->tw == 0u || info->th == 0u || info->tdx == 0u || info->tdy == 0u) {
        opj_destroy_cstr_info(&info);
        return SCEAU_JP2_ERR_HEADER;
    }
    for (i = 0; i < info->nbcomps; i++) {
        const uint32_t resolutions = info->m_default_tile_info.tccp_info[i].numresolutions;
        if (resolutions == 0u || resolutions > OPJ_J2K_MAXRLVLS) {
            opj_destroy_cstr_info(&info);
            return SCEAU_JP2_ERR_HEADER;
        }
        if (resolutions - 1u < max_reduce) {
            max_reduce = resolutions - 1u;
        }
    }
    for (candidate = 0; candidate <= max_reduce; candidate++) {
        if (reduced_extent(image->x0, image->x1, candidate) <= SCEAU_JP2_MAX_OUTPUT_DIMENSION &&
            reduced_extent(image->y0, image->y1, candidate) <= SCEAU_JP2_MAX_OUTPUT_DIMENSION &&
            estimate_memory(image, info, length, candidate) <= SCEAU_JP2_MEMORY_BUDGET) {

            *reduce = candidate;
            status = SCEAU_JP2_OK;
            break;
        }
    }
    opj_destroy_cstr_info(&info);
    return status;
}

/* ---- Decoding -------------------------------------------------------------------------- */

sceau_jp2_status sceau_jp2_decode_image(const uint8_t *data, size_t length, sceau_jp2_decoded **out,
                                        uint32_t *width, uint32_t *height) {
    OPJ_CODEC_FORMAT format;
    opj_codec_t *codec = NULL;
    opj_stream_t *stream = NULL;
    opj_image_t *image = NULL;
    opj_dparameters_t parameters;
    memory_stream source;
    sceau_jp2_decoded *decoded;
    sceau_jp2_status status;
    uint32_t reduce = 0;
    uint64_t out_width;
    uint64_t out_height;

    if (out == NULL || width == NULL || height == NULL) {
        return SCEAU_JP2_ERR_ARGUMENT;
    }
    *out = NULL;
    *width = 0;
    *height = 0;
    if (data == NULL || length == 0) {
        return SCEAU_JP2_ERR_ARGUMENT;
    }
    if (length > SCEAU_JP2_MAX_INPUT_SIZE) {
        return SCEAU_JP2_ERR_TOO_LARGE;
    }

    if (starts_with(data, length, JP2_SIGNATURE, sizeof(JP2_SIGNATURE))) {
        format = OPJ_CODEC_JP2;
    } else if (starts_with(data, length, J2K_SIGNATURE, sizeof(J2K_SIGNATURE))) {
        format = OPJ_CODEC_J2K;
    } else {
        return SCEAU_JP2_ERR_FORMAT;
    }

    /* Every OpenJPEG allocation, starting with the header read, is capped by the budget. */
    sceau_jp2_alloc_begin(SCEAU_JP2_MEMORY_BUDGET);
    codec = opj_create_decompress(format);
    if (codec == NULL) {
        status = SCEAU_JP2_ERR_MEMORY;
        goto cleanup;
    }
    opj_set_info_handler(codec, silent_handler, NULL);
    opj_set_warning_handler(codec, silent_handler, NULL);
    opj_set_error_handler(codec, silent_handler, NULL);

    opj_set_default_decoder_parameters(&parameters);
    if (!opj_setup_decoder(codec, &parameters) || !opj_decoder_set_strict_mode(codec, OPJ_TRUE)) {
        status = SCEAU_JP2_ERR_DECODE;
        goto cleanup;
    }

    source.data = data;
    source.length = length;
    source.position = 0;
    stream = opj_stream_create(OPJ_J2K_STREAM_CHUNK_SIZE, OPJ_TRUE);
    if (stream == NULL) {
        status = SCEAU_JP2_ERR_MEMORY;
        goto cleanup;
    }
    opj_stream_set_user_data(stream, &source, memory_free);
    opj_stream_set_user_data_length(stream, (OPJ_UINT64) length);
    opj_stream_set_read_function(stream, memory_read);
    opj_stream_set_skip_function(stream, memory_skip);
    opj_stream_set_seek_function(stream, memory_seek);

    if (!opj_read_header(stream, codec, &image) || image == NULL) {
        status = sceau_jp2_alloc_exhausted() ? SCEAU_JP2_ERR_TOO_LARGE : SCEAU_JP2_ERR_HEADER;
        goto cleanup;
    }
    /* Refused before any decoding: the header of a hostile stream can declare 60000 x 60000. */
    if (!dimensions_acceptable(image)) {
        status = SCEAU_JP2_ERR_TOO_LARGE;
        goto cleanup;
    }
    if (!components_valid(image)) {
        status = SCEAU_JP2_ERR_UNSUPPORTED;
        goto cleanup;
    }
    /* Decompression bomb: cost estimated before opj_decode, resolution reduced if needed. */
    status = choose_reduction(codec, image, length, &reduce);
    if (status != SCEAU_JP2_OK) {
        goto cleanup;
    }
    if (reduce > 0u && !opj_set_decoded_resolution_factor(codec, reduce)) {
        status = SCEAU_JP2_ERR_DECODE;
        goto cleanup;
    }

    if (!opj_decode(codec, stream, image) || !opj_end_decompress(codec, stream)) {
        status = sceau_jp2_alloc_exhausted() ? SCEAU_JP2_ERR_TOO_LARGE : SCEAU_JP2_ERR_DECODE;
        goto cleanup;
    }
    /* A JP2 palette can change the number of components: everything is checked again. */
    if (!dimensions_acceptable(image)) {
        status = SCEAU_JP2_ERR_TOO_LARGE;
        goto cleanup;
    }
    if (!components_decoded(image)) {
        status = SCEAU_JP2_ERR_UNSUPPORTED;
        goto cleanup;
    }
    out_width = reduced_extent(image->x0, image->x1, reduce);
    out_height = reduced_extent(image->y0, image->y1, reduce);
    if (out_width == 0u || out_height == 0u || out_width > SCEAU_JP2_MAX_OUTPUT_DIMENSION ||
        out_height > SCEAU_JP2_MAX_OUTPUT_DIMENSION) {
        status = SCEAU_JP2_ERR_TOO_LARGE;
        goto cleanup;
    }

    decoded = (sceau_jp2_decoded *) malloc(sizeof(*decoded));
    if (decoded == NULL) {
        status = SCEAU_JP2_ERR_MEMORY;
        goto cleanup;
    }
    decoded->image = image;
    decoded->reduce = reduce;
    decoded->x0 = (uint32_t) ceil_shift(image->x0, reduce);
    decoded->y0 = (uint32_t) ceil_shift(image->y0, reduce);
    decoded->width = (uint32_t) out_width;
    decoded->height = (uint32_t) out_height;
    decoded->color = image->numcomps >= 3u;
    decoded->ycc = is_ycc(image);
    image = NULL;
    *out = decoded;
    *width = decoded->width;
    *height = decoded->height;
    status = SCEAU_JP2_OK;

cleanup:
    /* No more OpenJPEG allocations; frees remain possible. */
    sceau_jp2_alloc_end();
    destroy_image(image);
    if (stream != NULL) {
        opj_stream_destroy(stream);
    }
    if (codec != NULL) {
        opj_destroy_codec(codec);
    }
    return status;
}

sceau_jp2_status sceau_jp2_decode(const uint8_t *data, size_t length, sceau_jp2_image *out) {
    sceau_jp2_decoded *decoded = NULL;
    sceau_jp2_status status;
    uint32_t width;
    uint32_t height;
    uint32_t y;
    uint32_t *pixels;

    if (out == NULL) {
        return SCEAU_JP2_ERR_ARGUMENT;
    }
    out->argb = NULL;
    out->width = 0;
    out->height = 0;
    status = sceau_jp2_decode_image(data, length, &decoded, &width, &height);
    if (status != SCEAU_JP2_OK) {
        return status;
    }
    pixels = (uint32_t *) malloc((size_t) width * height * sizeof(uint32_t));
    if (pixels == NULL) {
        sceau_jp2_decoded_free(decoded);
        return SCEAU_JP2_ERR_MEMORY;
    }
    for (y = 0; y < height; y++) {
        sceau_jp2_decoded_row(decoded, y, pixels + (size_t) y * width);
    }
    sceau_jp2_decoded_free(decoded);
    out->argb = pixels;
    out->width = width;
    out->height = height;
    return SCEAU_JP2_OK;
}
