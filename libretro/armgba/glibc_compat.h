/* Force-included in every C file of the aarch64 Linux build, so the core loads on any aarch64 glibc (2.17 on), whatever
 * glibc built it: glibc 2.29 gave exp and pow new versions (the Brick has 2.33): bind them to the base version every
 * aarch64 glibc still has. stat / fstat (2.33) and pthread_create / pthread_join (2.34) are glibc_compat.c's: armGBA's
 * own calls are in LTO objects, where a .symver here doesn't hold. */
#if defined(__aarch64__) && defined(__linux__) && !defined(__ANDROID__)
__asm__(".symver exp,exp@GLIBC_2.17");
__asm__(".symver pow,pow@GLIBC_2.17");
__asm__(".symver powf,powf@GLIBC_2.17");
#endif
