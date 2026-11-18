// Forced into every BananaDS source libpkbananads.so builds (CMakeLists.txt): what bionic at the app's minSdk (26) and
// clang lack or spell differently from BananaDS's glibc / GCC build. BananaDS's sources stay as they are.
#pragma once
#include <stdlib.h>

#if defined(__ANDROID__) && __ANDROID_API__ < 28
// aligned_alloc is API 28; BananaDS's buffers go back through free(), as posix_memalign's do.
#ifdef __cplusplus
extern "C" {
#endif
__attribute__((visibility("hidden"))) static inline void* pk_bnd_aligned_alloc(size_t alignment, size_t size)
{
    void* p = 0;
    if (alignment < sizeof(void*)) alignment = sizeof(void*);
    return posix_memalign(&p, alignment, size) == 0 ? p : 0;
}
#ifdef __cplusplus
}
#endif
#define aligned_alloc pk_bnd_aligned_alloc
#endif

// GCC's AArch64 CRC32C builtins, under clang's names (needs +crc: CMakeLists.txt).
#if defined(__clang__) && defined(__aarch64__)
#define __builtin_aarch64_crc32cx __builtin_arm_crc32cd
#define __builtin_aarch64_crc32cw __builtin_arm_crc32cw
#define __builtin_aarch64_crc32ch __builtin_arm_crc32ch
#define __builtin_aarch64_crc32cb __builtin_arm_crc32cb
#endif
