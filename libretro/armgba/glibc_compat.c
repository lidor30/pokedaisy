/* So a core built against a newer glibc loads on an older one (the Brick has 2.33): the link sends these calls here
 * (-Wl,--wrap, CMakeLists.txt). stat and fstat are functions only since glibc 2.33; before, sys/stat.h called __xstat /
 * __fxstat, which every aarch64 glibc still exports (a build against an older glibc never calls stat by that name, and
 * those two go unused). glibc 2.34 gave pthread_create / pthread_join new versions; the base ones are still there. */
#include <pthread.h>
#include <sys/stat.h>

extern int __xstat(int ver, const char *path, struct stat *st);
extern int __fxstat(int ver, int fd, struct stat *st);
__asm__(".symver __xstat,__xstat@GLIBC_2.17");
__asm__(".symver __fxstat,__fxstat@GLIBC_2.17");

int __wrap_stat(const char *path, struct stat *st);
int __wrap_fstat(int fd, struct stat *st);
int __wrap_stat(const char *path, struct stat *st) { return __xstat(0, path, st); } // _STAT_VER on aarch64: 0
int __wrap_fstat(int fd, struct stat *st) { return __fxstat(0, fd, st); }

extern int pd_pthread_create_217(pthread_t *t, const pthread_attr_t *a, void *(*fn)(void *), void *arg);
extern int pd_pthread_join_217(pthread_t t, void **ret);
__asm__(".symver pd_pthread_create_217,pthread_create@GLIBC_2.17");
__asm__(".symver pd_pthread_join_217,pthread_join@GLIBC_2.17");

int __wrap_pthread_create(pthread_t *t, const pthread_attr_t *a, void *(*fn)(void *), void *arg);
int __wrap_pthread_join(pthread_t t, void **ret);
int __wrap_pthread_create(pthread_t *t, const pthread_attr_t *a, void *(*fn)(void *), void *arg) {
  return pd_pthread_create_217(t, a, fn, arg);
}
int __wrap_pthread_join(pthread_t t, void **ret) { return pd_pthread_join_217(t, ret); }
