/* What DSperate's core and the app's DS companion expect from a POSIX runtime, for a MinGW build. Force-included
 * (-include) into every source of the Windows DS build, C and C++ alike, so no vendored file is edited. */
#ifndef PD_WIN_COMPAT_CXX_H
#define PD_WIN_COMPAT_CXX_H

#ifndef _WIN32
#error "pd_win_compat_cxx.h is the Windows DS build's compat header"
#endif

#include <direct.h>
#include <io.h>
#include <limits.h>
#include <malloc.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>

// windows.h's legacy empty macros from the segmented-memory days: DSperate has fields of these names.
#undef near
#undef far

#ifndef O_CLOEXEC
#define O_CLOEXEC 0 // a handle belongs to this process alone anyway
#endif

/* Time broken down the POSIX way (Windows spells it _s, with the arguments the other way round). */
static __inline struct tm *pd_localtime_r(const time_t *t, struct tm *out) {
  return localtime_s(out, t) ? NULL : out;
}
static __inline struct tm *pd_gmtime_r(const time_t *t, struct tm *out) {
  return gmtime_s(out, t) ? NULL : out;
}
#define localtime_r pd_localtime_r
#define gmtime_r pd_gmtime_r

/* A file flushed all the way to the card. */
#define fsync(fd) _commit(fd)

/* The full path a path names. */
static __inline char *pd_realpath(const char *path, char *resolved) {
  return _fullpath(resolved, path, PATH_MAX);
}
#define realpath pd_realpath

/* How many CPUs the machine has, and the page size. */
#define _SC_NPROCESSORS_ONLN 84
#define _SC_PAGESIZE 30
#define _SC_PAGE_SIZE _SC_PAGESIZE
static __inline long pd_sysconf(int name) {
  SYSTEM_INFO info;
  if (name == _SC_NPROCESSORS_ONLN) {
    GetSystemInfo(&info);
    return (long)info.dwNumberOfProcessors;
  }
  if (name == _SC_PAGESIZE) {
    GetSystemInfo(&info);
    return (long)info.dwPageSize;
  }
  return -1;
}
#define sysconf pd_sysconf

#ifdef __cplusplus
// C++: a real overload, so a member that happens to be named mkdir (DSperate's FatVolume) still compiles.
static inline int mkdir(const char *path, unsigned mode) {
  (void)mode;
  return _mkdir(path);
}
static inline int mkdir(const char *path, int mode) {
  (void)mode;
  return _mkdir(path);
}
#else
#define mkdir(path, mode) _mkdir(path)
#endif

/* opendir/readdir's entry kinds (MinGW's dirent.h leaves them to the platform). */
#ifndef DT_UNKNOWN
#define DT_UNKNOWN 0
#define DT_DIR 4
#define DT_REG 8
#endif

#endif
