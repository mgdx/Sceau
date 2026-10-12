/*
 * Sceau - in-memory JPEG 2000 decoding on top of OpenJPEG.
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * JNI-independent decoding core, tested on the host (test/run-host-tests.sh).
 * The decoded data comes from a potentially hostile chip: any invalid input
 * yields an error code, never a crash, and nothing is logged.
 */
#ifndef SCEAU_JP2_DECODE_H
#define SCEAU_JP2_DECODE_H

#include <stddef.h>
#include <stdint.h>

/* Maximum dimensions declared by the header (width and height, each). */
#define SCEAU_JP2_MAX_DIMENSION 4096u

/*
 * Maximum dimensions of the decoded image (width and height, each): a larger portrait (DG2)
 * or DG12 image is decoded at a resolution halved as many times as needed
 * (opj_set_decoded_resolution_factor), instead of being refused.
 */
#define SCEAU_JP2_MAX_OUTPUT_DIMENSION 2048u

/*
 * OpenJPEG memory budget for one decoding, in bytes. Estimated before opj_decode from the
 * header (samples, tiles, code blocks, resolutions, layers) to choose the resolution
 * reduction or refuse the stream, then enforced on every allocation (jp2_alloc.c).
 * With the output ARGB pixels (at most 2048 x 2048 x 4 = 16 MB, copied once into the
 * Bitmap), a decoding stays under 96 MB all included. See docs/decisions.md (D3).
 */
#define SCEAU_JP2_MEMORY_BUDGET (64u * 1024u * 1024u)

/* Maximum size of the accepted compressed stream, in bytes. */
#define SCEAU_JP2_MAX_INPUT_SIZE (16u * 1024u * 1024u)

typedef enum {
    SCEAU_JP2_OK = 0,
    SCEAU_JP2_ERR_ARGUMENT = 1,
    SCEAU_JP2_ERR_FORMAT = 2,     /* signature neither JP2 nor J2K */
    SCEAU_JP2_ERR_HEADER = 3,     /* unreadable header */
    SCEAU_JP2_ERR_TOO_LARGE = 4,  /* dimensions, size or memory budget out of bounds */
    SCEAU_JP2_ERR_DECODE = 5,     /* corrupted or truncated stream */
    SCEAU_JP2_ERR_UNSUPPORTED = 6,/* unsupported components or precision */
    SCEAU_JP2_ERR_MEMORY = 7
} sceau_jp2_status;

typedef struct {
    /* ARGB pixels 0xAARRGGBB, row by row, width * height elements. */
    uint32_t *argb;
    uint32_t width;
    uint32_t height;
} sceau_jp2_image;

/*
 * Decodes a JP2 or J2K stream. On success, out->argb is allocated and must be
 * freed with sceau_jp2_image_free. On failure, out is zeroed.
 */
sceau_jp2_status sceau_jp2_decode(const uint8_t *data, size_t length, sceau_jp2_image *out);

/* Zeroes the pixels, frees the buffer and zeroes the structure. */
void sceau_jp2_image_free(sceau_jp2_image *image);

/*
 * Two-step decoding, without a full ARGB buffer on the native side: sceau_jp2_decode_image
 * decodes the stream and gives the output dimensions, sceau_jp2_decoded_row converts one row to
 * ARGB (0 <= y < height, row of width elements), sceau_jp2_decoded_free wipes and frees it all.
 * On failure, *out is NULL and the dimensions are 0.
 */
typedef struct sceau_jp2_decoded sceau_jp2_decoded;

sceau_jp2_status sceau_jp2_decode_image(const uint8_t *data, size_t length, sceau_jp2_decoded **out,
                                        uint32_t *width, uint32_t *height);

void sceau_jp2_decoded_row(const sceau_jp2_decoded *decoded, uint32_t y, uint32_t *row);

void sceau_jp2_decoded_free(sceau_jp2_decoded *decoded);

/* Zeroing that cannot be removed by the optimizer. */
void sceau_jp2_secure_zero(void *buffer, size_t length);

#endif /* SCEAU_JP2_DECODE_H */
