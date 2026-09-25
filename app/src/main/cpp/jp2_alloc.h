/*
 * Sceau - allocateur d'OpenJPEG plafonné (remplace opj_malloc.c).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Toutes les allocations d'OpenJPEG (opj_malloc, opj_calloc, opj_realloc, opj_aligned_*)
 * passent par ce fichier, qui les compte et refuse celles qui dépasseraient le budget du
 * décodage en cours. C'est le plafond dur derrière l'estimation faite avant opj_decode
 * (jp2_decode.c) : il couvre aussi ce que l'estimation ne voit pas (tables allouées pendant
 * la lecture de l'en-tête, paramètres propres à une tuile, palettes JP2). Budget propre au
 * fil d'exécution : hors d'une fenêtre sceau_jp2_alloc_begin / sceau_jp2_alloc_end, toute
 * allocation est refusée.
 */
#ifndef SCEAU_JP2_ALLOC_H
#define SCEAU_JP2_ALLOC_H

#include <stddef.h>

/* Ouvre une fenêtre d'allocation de limit octets au plus (en-têtes de blocs compris). */
void sceau_jp2_alloc_begin(size_t limit);

/* Ferme la fenêtre : toute nouvelle allocation est refusée, les libérations restent possibles. */
void sceau_jp2_alloc_end(void);

/* Pic d'occupation atteint depuis le dernier sceau_jp2_alloc_begin, en octets. */
size_t sceau_jp2_alloc_peak(void);

/* Vrai si une allocation a été refusée faute de budget depuis le dernier begin. */
int sceau_jp2_alloc_exhausted(void);

#endif /* SCEAU_JP2_ALLOC_H */
