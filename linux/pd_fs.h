/* Making a folder: POSIX mkdir takes a mode, Windows' _mkdir doesn't. */
#ifndef PD_FS_H
#define PD_FS_H

#include <stdio.h>
#include <string.h>

#ifdef _WIN32
#include <direct.h>
#include <io.h>
#define fsync(fd) _commit(fd) // a save on its way to the card: Windows flushes a file handle with _commit
static inline int pd_mkdir(const char *path) {
  return _mkdir(path);
}
// POSIX rename replaces a file that is already there, Windows' plain rename refuses to: every write after the first one
// (a battery save, a state, host.ini) failed. MoveFileEx replaces it, and waits for the disk like fsync does.
// Declared here rather than through <windows.h>, whose min / max macros this frontend doesn't want.
__declspec(dllimport) int __stdcall MoveFileExA(const char *from, const char *to, unsigned long flags);
static inline int pd_rename(const char *from, const char *to) {
  return MoveFileExA(from, to, 0x1ul /* REPLACE_EXISTING */ | 0x8ul /* WRITE_THROUGH */) ? 0 : -1;
}
#else
#include <sys/stat.h>
static inline int pd_mkdir(const char *path) {
  return mkdir(path, 0755);
}
#define pd_rename(from, to) rename(from, to)
#endif

/* The battery save in DIR for the ROM file romName (its name, extension and all): DIR/<romName>.sav, or, when
 * that one isn't there, a save named after the ROM without its extension (DIR/<base>.sav), the way other
 * emulators leave them (pokeblack2.nds -> pokeblack2.sav). [out] may name a file that doesn't exist yet: then
 * it is <romName>.sav, the name this host writes. */
static inline void pd_save_path(const char *dir, const char *romName, char *out, size_t size) {
  snprintf(out, size, "%s/%s.sav", dir, romName);
  FILE *f = fopen(out, "rb");
  if (f) {
    fclose(f);
    return;
  }
  const char *dot = strrchr(romName, '.'), *slash = strrchr(romName, '/');
  if (!dot || dot == romName || (slash && dot < slash))
    return;
  char alt[1400];
  snprintf(alt, sizeof alt, "%s/%.*s.sav", dir, (int)(dot - romName), romName);
  f = fopen(alt, "rb");
  if (f) {
    fclose(f);
    snprintf(out, size, "%s", alt);
  }
}

#endif
