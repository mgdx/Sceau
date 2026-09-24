/*
 * Sceau - décodage JPEG 2000 en mémoire au-dessus d'OpenJPEG.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "jp2_decode.h"

#include <stdlib.h>
#include <string.h>

#include "openjpeg.h"

/* Signatures : boîte « jP  » d'un fichier JP2, marqueurs SOC + SIZ d'un flux J2K brut. */
static const uint8_t JP2_SIGNATURE[] = {0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20};
static const uint8_t J2K_SIGNATURE[] = {0xFF, 0x4F, 0xFF, 0x51};

/* Nombre maximal de composantes acceptées (niveaux de gris, + alpha, RGB, + alpha). */
#define MAX_COMPONENTS 4u

/* Précision maximale d'une composante, en bits. */
#define MAX_PRECISION 16u

void sceau_jp2_secure_zero(void *buffer, size_t length) {
    if (buffer == NULL || length == 0) {
        return;
    }
    memset(buffer, 0, length);
    /* Barrière : le compilateur doit supposer que le tampon est encore lu. */
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

/* ---- Flux OpenJPEG sur un tableau d'octets ---------------------------------------------- */

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
    /* Le flux ne possède pas les données : rien à libérer. */
    (void) user_data;
}

/* Gestionnaire de messages OpenJPEG : silencieux, aucune trace (SPEC §8). */
static void silent_handler(const char *message, void *client_data) {
    (void) message;
    (void) client_data;
}

/* ---- Conversion en ARGB ---------------------------------------------------------------- */

static int starts_with(const uint8_t *data, size_t length, const uint8_t *prefix, size_t prefix_length) {
    return length >= prefix_length && memcmp(data, prefix, prefix_length) == 0;
}

/*
 * Échantillon de la composante au point (x, y) de la grille de référence, ramené sur 8 bits.
 * Une composante sous-échantillonnée (dx, dy > 1, par exemple la chrominance en 4:2:0) est
 * agrandie au plus proche voisin : l'échantillon i couvre les points i*dx à i*dx + dx - 1.
 */
static int sample8(const opj_image_comp_t *comp, uint32_t image_x, uint32_t image_y) {
    uint32_t cx = image_x / comp->dx;
    uint32_t cy = image_y / comp->dy;
    int64_t value;
    int64_t max;

    /* Coordonnées relatives à l'origine de la composante, bornées (sous-échantillonnage). */
    cx = cx > comp->x0 ? cx - comp->x0 : 0u;
    cy = cy > comp->y0 ? cy - comp->y0 : 0u;
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
    /* Même heuristique qu'opj_decompress : espace non déclaré et chrominance sous-échantillonnée. */
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

static sceau_jp2_status to_argb(const opj_image_t *image, sceau_jp2_image *out) {
    const uint32_t width = image->x1 - image->x0;
    const uint32_t height = image->y1 - image->y0;
    const int ycc = is_ycc(image);
    const int color = image->numcomps >= 3u;
    uint32_t *pixels;
    uint32_t x;
    uint32_t y;

    pixels = (uint32_t *) malloc((size_t) width * height * sizeof(uint32_t));
    if (pixels == NULL) {
        return SCEAU_JP2_ERR_MEMORY;
    }
    for (y = 0; y < height; y++) {
        const uint32_t image_y = image->y0 + y;
        for (x = 0; x < width; x++) {
            const uint32_t image_x = image->x0 + x;
            uint32_t r;
            uint32_t g;
            uint32_t b;
            if (!color) {
                r = g = b = (uint32_t) sample8(&image->comps[0], image_x, image_y);
            } else if (ycc) {
                const float luma = (float) sample8(&image->comps[0], image_x, image_y);
                const float cb = (float) (sample8(&image->comps[1], image_x, image_y) - 128);
                const float cr = (float) (sample8(&image->comps[2], image_x, image_y) - 128);
                r = clamp8(luma + 1.402f * cr);
                g = clamp8(luma - 0.344136f * cb - 0.714136f * cr);
                b = clamp8(luma + 1.772f * cb);
            } else {
                r = (uint32_t) sample8(&image->comps[0], image_x, image_y);
                g = (uint32_t) sample8(&image->comps[1], image_x, image_y);
                b = (uint32_t) sample8(&image->comps[2], image_x, image_y);
            }
            pixels[(size_t) y * width + x] = 0xFF000000u | (r << 16) | (g << 8) | b;
        }
    }
    out->argb = pixels;
    out->width = width;
    out->height = height;
    return SCEAU_JP2_OK;
}

/* Remet à zéro les échantillons décodés (la photo) avant de libérer l'image OpenJPEG. */
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

static int dimensions_acceptable(const opj_image_t *image) {
    return image->x1 > image->x0 && image->y1 > image->y0 &&
           image->x1 - image->x0 <= SCEAU_JP2_MAX_DIMENSION &&
           image->y1 - image->y0 <= SCEAU_JP2_MAX_DIMENSION;
}

sceau_jp2_status sceau_jp2_decode(const uint8_t *data, size_t length, sceau_jp2_image *out) {
    OPJ_CODEC_FORMAT format;
    opj_codec_t *codec = NULL;
    opj_stream_t *stream = NULL;
    opj_image_t *image = NULL;
    opj_dparameters_t parameters;
    memory_stream source;
    sceau_jp2_status status;

    if (out == NULL) {
        return SCEAU_JP2_ERR_ARGUMENT;
    }
    out->argb = NULL;
    out->width = 0;
    out->height = 0;
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

    codec = opj_create_decompress(format);
    if (codec == NULL) {
        return SCEAU_JP2_ERR_MEMORY;
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
        status = SCEAU_JP2_ERR_HEADER;
        goto cleanup;
    }
    /* Refus avant tout décodage : l'en-tête d'un flux hostile peut annoncer 60000 x 60000. */
    if (!dimensions_acceptable(image)) {
        status = SCEAU_JP2_ERR_TOO_LARGE;
        goto cleanup;
    }
    if (!components_valid(image)) {
        status = SCEAU_JP2_ERR_UNSUPPORTED;
        goto cleanup;
    }

    if (!opj_decode(codec, stream, image) || !opj_end_decompress(codec, stream)) {
        status = SCEAU_JP2_ERR_DECODE;
        goto cleanup;
    }
    /* Une palette JP2 peut changer le nombre de composantes : on revérifie tout. */
    if (!dimensions_acceptable(image)) {
        status = SCEAU_JP2_ERR_TOO_LARGE;
        goto cleanup;
    }
    if (!components_decoded(image)) {
        status = SCEAU_JP2_ERR_UNSUPPORTED;
        goto cleanup;
    }

    status = to_argb(image, out);

cleanup:
    destroy_image(image);
    if (stream != NULL) {
        opj_stream_destroy(stream);
    }
    opj_destroy_codec(codec);
    if (status != SCEAU_JP2_OK) {
        sceau_jp2_image_free(out);
    }
    return status;
}
