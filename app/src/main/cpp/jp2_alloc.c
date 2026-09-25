/*
 * Sceau - allocateur d'OpenJPEG plafonné (remplace opj_malloc.c, voir jp2_alloc.h).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Mêmes contrats que les fonctions d'opj_malloc.c : taille nulle -> NULL, opj_calloc remet à
 * zéro, opj_aligned_* alignés sur 16 ou 32 octets, opj_realloc(p, 0) -> NULL sans libérer p.
 * Chaque bloc est précédé d'un en-tête de BLOCK_HEADER octets qui mémorise sa taille, ce qui
 * garde l'alignement sur 32 octets pour tous les blocs.
 */
#define _POSIX_C_SOURCE 200112L

#include "jp2_alloc.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* Taille de l'en-tête, et alignement de tous les blocs (opj_aligned_32_malloc le demande). */
#define BLOCK_HEADER ((size_t) 32u)

/* Surcoût de l'allocateur système par bloc, compté pour que le budget suive la mémoire réelle
 * (sur un flux hostile, OpenJPEG alloue des millions de petits blocs). */
#define BLOCK_OVERHEAD ((size_t) 16u)

/* Prototypes des fonctions attendues par OpenJPEG (opj_malloc.h, non inclus : il empoisonne
 * malloc et free). */
void *opj_malloc(size_t size);
void *opj_calloc(size_t num, size_t size);
void *opj_realloc(void *ptr, size_t size);
void opj_free(void *ptr);
void *opj_aligned_malloc(size_t size);
void *opj_aligned_realloc(void *ptr, size_t size);
void *opj_aligned_32_malloc(size_t size);
void *opj_aligned_32_realloc(void *ptr, size_t size);
void opj_aligned_free(void *ptr);

typedef struct {
    size_t used;
    size_t peak;
    size_t limit;
    int exhausted;
} alloc_budget;

/* Un budget par fil : deux décodages concurrents ne se partagent pas le compteur. */
static _Thread_local alloc_budget budget;

void sceau_jp2_alloc_begin(size_t limit) {
    budget.used = 0;
    budget.peak = 0;
    budget.limit = limit;
    budget.exhausted = 0;
}

void sceau_jp2_alloc_end(void) {
    budget.limit = 0;
}

size_t sceau_jp2_alloc_peak(void) {
    return budget.peak;
}

int sceau_jp2_alloc_exhausted(void) {
    return budget.exhausted;
}

static size_t block_size(const void *ptr) {
    size_t size;
    memcpy(&size, (const uint8_t *) ptr - BLOCK_HEADER, sizeof(size));
    return size;
}

static void *budget_alloc(size_t size, int zero) {
    void *base = NULL;
    size_t cost;
    if (size == 0u || size > SIZE_MAX - BLOCK_HEADER - BLOCK_OVERHEAD) {
        return NULL;
    }
    cost = size + BLOCK_HEADER + BLOCK_OVERHEAD;
    if (budget.used > budget.limit || cost > budget.limit - budget.used) {
        budget.exhausted = 1;
        return NULL;
    }
    if (posix_memalign(&base, BLOCK_HEADER, size + BLOCK_HEADER) != 0 || base == NULL) {
        return NULL;
    }
    memcpy(base, &size, sizeof(size));
    if (zero) {
        memset((uint8_t *) base + BLOCK_HEADER, 0, size);
    }
    budget.used += cost;
    if (budget.used > budget.peak) {
        budget.peak = budget.used;
    }
    return (uint8_t *) base + BLOCK_HEADER;
}

static void budget_free(void *ptr) {
    size_t cost;
    if (ptr == NULL) {
        return;
    }
    cost = block_size(ptr) + BLOCK_HEADER + BLOCK_OVERHEAD;
    budget.used = cost <= budget.used ? budget.used - cost : 0u;
    free((uint8_t *) ptr - BLOCK_HEADER);
}

static void *budget_realloc(void *ptr, size_t size) {
    void *resized;
    size_t old_size;
    if (ptr == NULL) {
        return budget_alloc(size, 0);
    }
    if (size == 0u) {
        return NULL;
    }
    /* Pas de realloc() en place : l'ancien et le nouveau bloc coexistent, et sont comptés. */
    old_size = block_size(ptr);
    resized = budget_alloc(size, 0);
    if (resized == NULL) {
        return NULL;
    }
    memcpy(resized, ptr, old_size < size ? old_size : size);
    budget_free(ptr);
    return resized;
}

void *opj_malloc(size_t size) {
    return budget_alloc(size, 0);
}

void *opj_calloc(size_t num, size_t size) {
    if (num == 0u || size == 0u || num > SIZE_MAX / size) {
        return NULL;
    }
    return budget_alloc(num * size, 1);
}

void *opj_realloc(void *ptr, size_t size) {
    return budget_realloc(ptr, size);
}

void opj_free(void *ptr) {
    budget_free(ptr);
}

void *opj_aligned_malloc(size_t size) {
    return budget_alloc(size, 0);
}

void *opj_aligned_realloc(void *ptr, size_t size) {
    return budget_realloc(ptr, size);
}

void *opj_aligned_32_malloc(size_t size) {
    return budget_alloc(size, 0);
}

void *opj_aligned_32_realloc(void *ptr, size_t size) {
    return budget_realloc(ptr, size);
}

void opj_aligned_free(void *ptr) {
    budget_free(ptr);
}
