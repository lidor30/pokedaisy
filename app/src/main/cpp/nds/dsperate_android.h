// Forced into every DSperate source the app builds (dsperate.cmake): what bionic lacks or spells differently from glibc.
#pragma once

// bionic's <sys/user.h> / <bits/page_size.h> define PAGE_SIZE / PAGE_MASK as macros (32-bit ARM), which turn DSperate's
// own constants of those names (mem/page_table.h: its 2 KB guest page) into numbers. Included first, then undefined:
// their include guards keep them from coming back.
#include <limits.h>
#include <sys/user.h>
#undef PAGE_SIZE
#undef PAGE_MASK

#include <stddef.h>
// aligned_alloc is API 28; the app runs from 26. pk_nds_dsp.cpp defines it (posix_memalign: free() releases it).
#if defined(__ANDROID__) && __ANDROID_API__ < 28
#ifdef __cplusplus
extern "C"
#endif
void* aligned_alloc(size_t alignment, size_t size);
#endif

// Byte buffers read / written a word at a time (dsperate.cmake's kernels_neon.cpp fix). Through a u16* / u32*, clang
// makes them 32-bit ARM's aligned forms (vst1.32 {d[0]}, [rN:32]), which fault on an odd address: the depth pre-pass's
// pass plane killed the 3D band workers (SIGBUS BUS_ADRALN) on a Galaxy Tab A. memcpy is the same instruction, unaligned.
#ifdef __cplusplus
#include <stdint.h>
static inline uint16_t pk_dsp_load16(const void* p) { uint16_t v; __builtin_memcpy(&v, p, 2); return v; }
static inline uint32_t pk_dsp_load32(const void* p) { uint32_t v; __builtin_memcpy(&v, p, 4); return v; }
static inline void pk_dsp_store32(void* p, uint32_t v) { __builtin_memcpy(p, &v, 4); }
#endif
