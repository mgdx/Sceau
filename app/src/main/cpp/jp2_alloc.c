/*
 * Sceau - capped OpenJPEG allocator (replaces opj_malloc.c, see jp2_alloc.h).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Same contracts as the functions of opj_malloc.c: zero size -> NULL, opj_calloc zeroes the
 * memory, opj_aligned_* aligned on 16 or 32 bytes, opj_realloc(p, 0) -> NULL without freeing p.
 * Each block is preceded by a header of BLOCK_HEADER bytes that records its size, which keeps
 * every block aligned on 32 bytes.
 */
#define _POSIX_C_SOURCE 200112L

#include "jp2_alloc.h"
#include "jp2_decode.h" /* sceau_jp2_secure_zero */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* Size of the header, and alignment of every block (opj_aligned_32_malloc requires it). */
#define BLOCK_HEADER ((size_t) 32u)

/* Per-block overhead of the system allocator, counted so that the budget tracks real memory
 * (on a hostile stream, OpenJPEG allocates millions of small blocks). */
#define BLOCK_OVERHEAD ((size_t) 16u)

/* Prototypes of the functions expected by OpenJPEG (opj_malloc.h, not included: it poisons
 * malloc and free). */
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

/* One budget per thread: two concurrent decodings do not share the counter. */
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
    size_t size;
    size_t cost;
    if (ptr == NULL) {
        return;
    }
    size = block_size(ptr);
    /* OpenJPEG blocks contain pieces of the photo (compressed stream, coefficients, samples):
     * wiped before being freed, with a write the optimizer cannot remove. This also applies to
     * the old block of a reallocation (budget_realloc). */
    sceau_jp2_secure_zero(ptr, size);
    cost = size + BLOCK_HEADER + BLOCK_OVERHEAD;
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
    /* No in-place realloc(): the old and new blocks coexist, and both are counted. */
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
