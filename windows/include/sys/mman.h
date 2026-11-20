/* sys/mman.h for a Windows build: armGBA's recompiler asks for one anonymous, executable mapping and Windows' answer
 * is VirtualAlloc. Kept here so none of armGBA's own sources is edited. */
#ifndef PD_WIN32_MMAN_H
#define PD_WIN32_MMAN_H

#ifndef _WIN32
#error "windows/include/sys/mman.h is the Windows stand-in for sys/mman.h"
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <io.h>
#include <stddef.h>

#define PROT_NONE 0
#define PROT_READ 1
#define PROT_WRITE 2
#define PROT_EXEC 4

#define MAP_PRIVATE 0x02
#define MAP_ANONYMOUS 0x20
#define MAP_FIXED 0x10
#define MAP_SHARED 0x01
#define MAP_NORESERVE 0 // VirtualAlloc commits what's asked for, nothing else is reserved
#define MAP_ANON MAP_ANONYMOUS
#define MAP_FAILED ((void *)-1)

static inline void *mmap(void *addr, size_t len, int prot, int flags, int fd, long offset) {
  DWORD access = PAGE_READWRITE;
  if ((flags & MAP_FIXED) == MAP_FIXED) {
    // A ROM's bytes at a fixed address: armGBA opens the file, reserves a block and maps the file into it (offset 0
    // every time), so the file's contents are copied in there.
    HANDLE h = (HANDLE)_get_osfhandle(fd);
    DWORD got = 0;
    if (!addr || h == INVALID_HANDLE_VALUE || SetFilePointer(h, offset, NULL, FILE_BEGIN) == INVALID_SET_FILE_POINTER)
      return MAP_FAILED;
    if (!ReadFile(h, addr, (DWORD)len, &got, NULL) || got != len)
      return MAP_FAILED;
    return addr;
  }
  if (!addr && len > 0 && (flags & MAP_ANONYMOUS) != MAP_ANONYMOUS && fd >= 0) {
    // A file's bytes (glibc's MAP_PRIVATE/MAP_SHARED over an fd; a DS ROM): a view of it, copy-on-write when writable.
    HANDLE hFile = (HANDLE)_get_osfhandle(fd);
    DWORD protect = (prot & PROT_WRITE) ? PAGE_WRITECOPY : PAGE_READONLY;
    HANDLE hMap = hFile == INVALID_HANDLE_VALUE ? NULL : CreateFileMappingA(hFile, NULL, protect, 0, 0, NULL);
    if (!hMap)
      return MAP_FAILED;
    void *view = MapViewOfFile(hMap, (prot & PROT_WRITE) ? FILE_MAP_COPY : FILE_MAP_READ, 0, (DWORD)offset, len);
    CloseHandle(hMap); // the view holds the object open
    return view ? view : MAP_FAILED;
  }
  if (addr || len == 0 || (flags & MAP_ANONYMOUS) != MAP_ANONYMOUS)
    return MAP_FAILED; // the recompiler's only other use: a fresh anonymous block
  if (prot & PROT_EXEC)
    access = (prot & PROT_WRITE) ? PAGE_EXECUTE_READWRITE : PAGE_EXECUTE_READ;
  else if (!(prot & PROT_WRITE))
    access = PAGE_READONLY;
  void *p = VirtualAlloc(NULL, len, MEM_COMMIT | MEM_RESERVE, access);
  return p ? p : MAP_FAILED;
}
static inline int munmap(void *addr, size_t len) {
  (void)len;
  if (!addr)
    return -1;
  return UnmapViewOfFile(addr) || VirtualFree(addr, 0, MEM_RELEASE) ? 0 : -1; // a view, else an anonymous block
}
static inline int mprotect(void *addr, size_t len, int prot) {
  DWORD access = PAGE_READWRITE, old;
  (void)len;
  if (prot & PROT_EXEC)
    access = (prot & PROT_WRITE) ? PAGE_EXECUTE_READWRITE : PAGE_EXECUTE_READ;
  else if (!(prot & PROT_WRITE))
    access = PAGE_READONLY;
  return VirtualProtect(addr, len, access, &old) ? 0 : -1;
}

#endif
