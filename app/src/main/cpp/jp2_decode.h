/*
 * Sceau - décodage JPEG 2000 en mémoire au-dessus d'OpenJPEG.
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Cœur de décodage indépendant de JNI, testé sur l'hôte (test/run-host-tests.sh).
 * Les données décodées viennent d'une puce potentiellement hostile : toute entrée
 * invalide donne un code d'erreur, jamais un plantage, et rien n'est journalisé.
 */
#ifndef SCEAU_JP2_DECODE_H
#define SCEAU_JP2_DECODE_H

#include <stddef.h>
#include <stdint.h>

/* Dimensions maximales acceptées (largeur et hauteur, chacune). */
#define SCEAU_JP2_MAX_DIMENSION 4096u

/* Taille maximale du flux compressé accepté, en octets. */
#define SCEAU_JP2_MAX_INPUT_SIZE (16u * 1024u * 1024u)

typedef enum {
    SCEAU_JP2_OK = 0,
    SCEAU_JP2_ERR_ARGUMENT = 1,
    SCEAU_JP2_ERR_FORMAT = 2,     /* signature ni JP2 ni J2K */
    SCEAU_JP2_ERR_HEADER = 3,     /* en-tête illisible */
    SCEAU_JP2_ERR_TOO_LARGE = 4,  /* dimensions ou taille hors limites */
    SCEAU_JP2_ERR_DECODE = 5,     /* flux corrompu ou tronqué */
    SCEAU_JP2_ERR_UNSUPPORTED = 6,/* composantes ou précision non gérées */
    SCEAU_JP2_ERR_MEMORY = 7
} sceau_jp2_status;

typedef struct {
    /* Pixels ARGB 0xAARRGGBB, ligne par ligne, width * height éléments. */
    uint32_t *argb;
    uint32_t width;
    uint32_t height;
} sceau_jp2_image;

/*
 * Décode un flux JP2 ou J2K. En cas de succès, out->argb est alloué et doit être
 * libéré par sceau_jp2_image_free. En cas d'échec, out est remis à zéro.
 */
sceau_jp2_status sceau_jp2_decode(const uint8_t *data, size_t length, sceau_jp2_image *out);

/* Remet les pixels à zéro, libère le tampon et remet la structure à zéro. */
void sceau_jp2_image_free(sceau_jp2_image *image);

/* Remise à zéro qui ne peut pas être supprimée par l'optimiseur. */
void sceau_jp2_secure_zero(void *buffer, size_t length);

#endif /* SCEAU_JP2_DECODE_H */
