/* dlfcn.h for a Windows build: the host loads a core's .dll the way it dlopens a .so, and finds its own folder
 * without /proc/self/exe. */
#ifndef PD_WIN32_DL_H
#define PD_WIN32_DL_H

#ifndef _WIN32
#error "win32_dl.h is the Windows stand-in for dlfcn.h"
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <stdint.h>
#include <stdio.h>

#define RTLD_NOW 0
#define RTLD_LOCAL 0

static inline void *dlopen(const char *path, int mode) {
  (void)mode;
  return (void *)LoadLibraryA(path);
}
static inline void *dlsym(void *handle, const char *name) {
  return (void *)(uintptr_t)GetProcAddress((HMODULE)handle, name);
}
static inline int dlclose(void *handle) {
  return FreeLibrary((HMODULE)handle) ? 0 : -1;
}
static inline char *dlerror(void) {
  static char text[256];
  DWORD code = GetLastError();
  if (!code) {
    *text = 0;
    return text;
  }
  if (!FormatMessageA(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS, NULL, code, 0, text, sizeof text,
                      NULL))
    snprintf(text, sizeof text, "Windows error %lu", (unsigned long)code);
  return text;
}

/* This executable's full path (readlink("/proc/self/exe") on Linux); -1 when it can't be read. */
static inline long pd_exe_path(char *buf, size_t size) {
  DWORD n = GetModuleFileNameA(NULL, buf, (DWORD)size);
  return n && n < size ? (long)n : -1;
}

#endif
