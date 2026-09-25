/*
 * Sceau - décodage JPEG 2000 en mémoire au-dessus d'OpenJPEG.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "jp2_decode.h"

#include <stdlib.h>
#include <string.h>

#include "jp2_alloc.h"
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

/* Division par 2^shift arrondie au supérieur, comme opj_int_ceildivpow2 (shift <= 32). */
static uint64_t ceil_shift(uint64_t value, uint32_t shift) {
    return (value + (((uint64_t) 1 << shift) - 1u)) >> shift;
}

static uint64_t ceil_div(uint64_t value, uint64_t divisor) {
    return (value + divisor - 1u) / divisor;
}

/*
 * Échantillon de la composante au point (x, y) de la grille de référence, ramené sur 8 bits.
 * Une composante sous-échantillonnée (dx, dy > 1, par exemple la chrominance en 4:2:0) est
 * agrandie au plus proche voisin : l'échantillon i couvre les points i*dx à i*dx + dx - 1.
 * Si la résolution est réduite de reduce niveaux, (x, y) est un point de la grille réduite
 * (divisée par 2^reduce) et l'origine de la composante est réduite de même.
 */
static int sample8(const opj_image_comp_t *comp, uint32_t reduce, uint32_t image_x, uint32_t image_y) {
    uint32_t cx = image_x / comp->dx;
    uint32_t cy = image_y / comp->dy;
    const uint32_t origin_x = (uint32_t) ceil_shift(comp->x0, reduce);
    const uint32_t origin_y = (uint32_t) ceil_shift(comp->y0, reduce);
    int64_t value;
    int64_t max;

    /* Coordonnées relatives à l'origine de la composante, bornées (sous-échantillonnage). */
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


/* Image décodée, convertie en ARGB ligne par ligne (sceau_jp2_decoded_row). */
struct sceau_jp2_decoded {
    opj_image_t *image;
    uint32_t reduce; /* niveaux de résolution retirés : l'image est divisée par 2^reduce */
    uint32_t x0;     /* origine de la grille de référence réduite */
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

/* Étendue [start, end) de la grille de référence une fois la résolution réduite. */
static uint64_t reduced_extent(uint32_t start, uint32_t end, uint32_t reduce) {
    return ceil_shift(end, reduce) - ceil_shift(start, reduce);
}

/* ---- Budget mémoire ------------------------------------------------------------------ */

/*
 * Coûts unitaires, en octets, des structures qu'OpenJPEG alloue pour décoder, relevés sur la
 * version épinglée en 64 bits et majorés des en-têtes d'allocation (docs/decisions.md, D3).
 */
/* Paramètres d'une tuile (opj_tcp_t, index du flux), lus dès l'en-tête principal. */
#define COST_TILE 8192u
/* Paramètres d'une composante dans une tuile (opj_tccp_t). */
#define COST_TILE_COMPONENT 1152u
/* Bloc de code : structure, nœuds des arbres d'inclusion et de bits nuls, segments. */
#define COST_CODE_BLOCK 448u
/* Fragment de données d'un bloc de code, par couche de qualité. */
#define COST_CODE_BLOCK_LAYER 16u
/* Sous-bande et précinct d'une résolution. */
#define COST_BAND 512u
/* Codec, tampon de lecture du flux (1 Mo), transformée en ondelettes, tampons de travail. */
#define COST_FIXED (4u * 1024u * 1024u)

/*
 * Nombre de blocs de code d'une composante de tuile de width x height échantillons, toutes
 * résolutions comprises : OpenJPEG les alloue toutes, y compris celles qu'une réduction de
 * résolution ne décode pas. Les précincts déclarés (Scod) peuvent réduire encore la taille
 * effective des blocs : l'API n'en donne pas la taille de façon fiable, ce cas est laissé au
 * plafond d'allocation (jp2_alloc.c).
 */
static uint64_t code_blocks(uint64_t width, uint64_t height, const opj_tccp_info_t *tccp) {
    uint64_t total = 0;
    uint32_t resno;
    for (resno = 0; resno < tccp->numresolutions; resno++) {
        const uint32_t level = tccp->numresolutions - 1u - resno;
        /* Résolution 0 : une sous-bande LL ; au-delà : HL, LH et HH, de demi-taille. */
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
 * Mémoire qu'OpenJPEG allouera pour décoder l'image à la résolution réduite de reduce
 * niveaux. Les échantillons décodés occupent 32 bits chacun quelle que soit leur précision.
 * Les tuiles sont décodées l'une après l'autre : leurs blocs de code et leurs échantillons ne
 * sont comptés qu'une fois, leurs paramètres autant de fois qu'il y a de tuiles.
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
    /* Données compressées des parties de tuile, recopiées par OpenJPEG avant décodage. */
    uint64_t total = COST_FIXED + (uint64_t) length +
                     tiles * (COST_TILE + (uint64_t) image->numcomps * COST_TILE_COMPONENT);
    uint32_t i;

    for (i = 0; i < image->numcomps; i++) {
        const opj_image_comp_t *comp = &image->comps[i];
        const opj_tccp_info_t *tccp = &info->m_default_tile_info.tccp_info[i];
        const uint64_t tile_comp_width = ceil_div(tile_width, comp->dx);
        const uint64_t tile_comp_height = ceil_div(tile_height, comp->dy);
        /* Échantillons de l'image décodée. */
        total += ceil_shift(ceil_div(width, comp->dx), reduce) *
                 ceil_shift(ceil_div(height, comp->dy), reduce) * sizeof(OPJ_INT32);
        /* Plusieurs tuiles : échantillons de la tuile en cours, avant recopie dans l'image. */
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
 * Choisit la plus petite réduction de résolution qui ramène l'image sous
 * SCEAU_JP2_MAX_OUTPUT_DIMENSION de côté et son décodage sous SCEAU_JP2_MEMORY_BUDGET.
 * Aucune réduction possible (trop peu de niveaux de résolution) : SCEAU_JP2_ERR_TOO_LARGE.
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

/* ---- Décodage -------------------------------------------------------------------------- */

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

    /* Toute allocation d'OpenJPEG, dès la lecture de l'en-tête, est plafonnée au budget. */
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
    /* Refus avant tout décodage : l'en-tête d'un flux hostile peut annoncer 60000 x 60000. */
    if (!dimensions_acceptable(image)) {
        status = SCEAU_JP2_ERR_TOO_LARGE;
        goto cleanup;
    }
    if (!components_valid(image)) {
        status = SCEAU_JP2_ERR_UNSUPPORTED;
        goto cleanup;
    }
    /* Bombe de décompression : coût estimé avant opj_decode, résolution réduite si besoin. */
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
    /* Une palette JP2 peut changer le nombre de composantes : on revérifie tout. */
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
    /* Plus aucune allocation d'OpenJPEG ; les libérations restent possibles. */
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
