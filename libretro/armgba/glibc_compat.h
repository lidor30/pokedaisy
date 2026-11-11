/* Force-included in every C file of the aarch64 Linux build. glibc 2.29 gave exp and pow new versions, and the Brick's
 * glibc is 2.27: bind them to the base version every aarch64 glibc has, so the core loads there. */
#if defined(__aarch64__) && defined(__linux__) && !defined(__ANDROID__)
__asm__(".symver exp,exp@GLIBC_2.17");
__asm__(".symver pow,pow@GLIBC_2.17");
__asm__(".symver powf,powf@GLIBC_2.17");
#endif
