/*
 * Sceau - capped OpenJPEG allocator (replaces opj_malloc.c).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * All OpenJPEG allocations (opj_malloc, opj_calloc, opj_realloc, opj_aligned_*) go through
 * this file, which counts them and refuses those that would exceed the budget of the current
 * decoding. It is the hard cap behind the estimate made before opj_decode (jp2_decode.c): it
 * also covers what the estimate does not see (tables allocated while reading the header,
 * tile-specific parameters, JP2 palettes). The budget is per thread: outside a
 * sceau_jp2_alloc_begin / sceau_jp2_alloc_end window, every allocation is refused.
 */
#ifndef SCEAU_JP2_ALLOC_H
#define SCEAU_JP2_ALLOC_H

#include <stddef.h>

/* Opens an allocation window of at most limit bytes (block headers included). */
void sceau_jp2_alloc_begin(size_t limit);

/* Closes the window: any new allocation is refused, frees remain possible. */
void sceau_jp2_alloc_end(void);

/* Peak usage reached since the last sceau_jp2_alloc_begin, in bytes. */
size_t sceau_jp2_alloc_peak(void);

/* True if an allocation was refused for lack of budget since the last begin. */
int sceau_jp2_alloc_exhausted(void);

#endif /* SCEAU_JP2_ALLOC_H */
