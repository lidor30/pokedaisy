/* Making a folder: POSIX mkdir takes a mode, Windows' _mkdir doesn't. */
#ifndef PD_FS_H
#define PD_FS_H

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

#endif
