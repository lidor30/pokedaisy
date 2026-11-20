/* POSIX odds and ends armGBA and the cores' shared C expect, for a MinGW build. Force-included (-include) into every
 * source of the Windows core builds, so no vendored file is edited. */
#ifndef PD_WIN_COMPAT_H
#define PD_WIN_COMPAT_H

#ifndef _WIN32
#error "pd_win_compat.h is the Windows core build's compat header"
#endif

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <direct.h>
#include <io.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <windows.h>

// windows.h's legacy empty macros from the segmented-memory days: armGBA's recompiler and the cores' shared C have
// their own "far" jumps and fields.
#undef near
#undef far

#ifndef O_CLOEXEC
#define O_CLOEXEC 0 // a handle belongs to this process alone anyway
#endif

/* Time broken down the POSIX way (Windows spells it _s, with the arguments the other way round). */
static inline struct tm *pd_localtime_r(const time_t *t, struct tm *out) {
  return localtime_s(out, t) ? NULL : out;
}
static inline struct tm *pd_gmtime_r(const time_t *t, struct tm *out) {
  return gmtime_s(out, t) ? NULL : out;
}
#define localtime_r pd_localtime_r
#define gmtime_r pd_gmtime_r

/* A folder: Windows' _mkdir takes no mode. */
#define mkdir(path, mode) _mkdir(path)

/* A file flushed all the way to the card. */
#define fsync(fd) _commit(fd)

/* link.c's own tell() (a link-cable message), against the CRT's file position one. */
#define tell pd_tell

/* The full path a path names (realpath's own job, glibc only). */
static inline char *pd_realpath(const char *path, char *resolved) {
  return _fullpath(resolved, path, PATH_MAX);
}
#define realpath pd_realpath

/* How many CPUs the machine has. */
#define _SC_NPROCESSORS_ONLN 84
static inline long sysconf(int name) {
  SYSTEM_INFO info;
  GetSystemInfo(&info);
  return name == _SC_NPROCESSORS_ONLN ? (long)info.dwNumberOfProcessors : -1;
}

/* open_memstream: the FILE a caller writes, whose text is handed over when it's closed (glibc has it, Windows doesn't).
 * A temporary file stands in, read back at fclose. */
#define PD_MEMSTREAMS 8
static FILE *pd_memstreamFile[PD_MEMSTREAMS];
static char **pd_memstreamPtr[PD_MEMSTREAMS];
static size_t *pd_memstreamSize[PD_MEMSTREAMS];

static inline FILE *pd_open_memstream(char **ptr, size_t *sizeloc);
static inline int pd_fclose(FILE *f);
static int (*const pd_real_fclose)(FILE *) = fclose;

#define open_memstream pd_open_memstream
#define fclose pd_fclose

static inline FILE *pd_open_memstream(char **ptr, size_t *sizeloc) {
  for (int i = 0; i < PD_MEMSTREAMS; i++) {
    if (pd_memstreamFile[i])
      continue;
    FILE *f = tmpfile();
    if (!f)
      return NULL;
    pd_memstreamFile[i] = f;
    pd_memstreamPtr[i] = ptr;
    pd_memstreamSize[i] = sizeloc;
    if (ptr)
      *ptr = NULL;
    if (sizeloc)
      *sizeloc = 0;
    return f;
  }
  return NULL;
}
static inline int pd_fclose(FILE *f) {
  for (int i = 0; i < PD_MEMSTREAMS; i++) {
    if (pd_memstreamFile[i] != f)
      continue;
    char *text = NULL;
    long size = 0;
    if (!fflush(f) && !fseek(f, 0, SEEK_END)) {
      size = ftell(f);
      if (size >= 0 && (text = malloc((size_t)size + 1)) != NULL) {
        if (fseek(f, 0, SEEK_SET) || fread(text, 1, (size_t)size, f) != (size_t)size) {
          free(text);
          text = NULL;
        } else {
          text[size] = 0;
        }
      }
    }
    if (pd_memstreamPtr[i])
      *pd_memstreamPtr[i] = text;
    if (pd_memstreamSize[i])
      *pd_memstreamSize[i] = text ? (size_t)size : 0;
    pd_memstreamFile[i] = NULL;
    break;
  }
  return pd_real_fclose(f);
}

#endif
