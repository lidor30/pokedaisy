// What the 3DS gives the app besides the screens: the memory split, a log, its model - and what armGBA's own 3DS host
// code (third_party/armgba/platform/3ds/source/jitmem.c: executable memory, the render and sound threads, the warm
// list) asks its frontend for. From ratherDashing's pokedaisy-3ds fork (ctr/host.c there), with mGBA's heap file kept
// out (see below).
#include "host.h"

#include <3ds.h>

#include <malloc.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// armGBA's libretro layer keeps several long paths on the stack.
u32 __stacksize__ = 512 * 1024;

// libmgba's 3DS heap file (ctru-heap.c) fixes the heap at 36 MiB and mallocs a 32 MiB ROM buffer at start: armGBA's
// games, its recompiler's code and its threads' copies need that memory. Defining what that file defines keeps it out
// of the link: mGBA's ROM buffer is made per Game Boy game (host_rom_buffer), and the heap is split here.
uint32_t* romBuffer = NULL;
size_t romBufferSize;
FS_Archive sdmcArchive;

void userAppInit(void) {
    FSUSER_OpenArchive(&sdmcArchive, ARCHIVE_SDMC, fsMakePath(PATH_EMPTY, ""));
}

#define ROM_ARENA (32u << 20) // the GBA's whole cartridge space
static uint8_t* romArena;

bool host_rom_arena_init(void) {
    romArena = malloc(ROM_ARENA);
    romBuffer = (uint32_t*) romArena;
    romBufferSize = romArena ? ROM_ARENA : 0;
    return romArena != NULL;
}

void* host_rom_arena(size_t size) {
    if (!romArena || size > ROM_ARENA) return NULL;
    memset(romArena, 0, size);
    return romArena;
}

// Linked with --wrap=free (ctr/CMakeLists.txt): the arena is never given back.
void __real_free(void* p);
void __wrap_free(void* p) {
    if (p && p == romArena) return;
    __real_free(p);
}

// libctru splits the app's memory evenly between the heap and linear memory (what the GPU and the DSP read). Here linear
// gets what the screens' textures, the frames in flight, the sound and a Game Boy game's mGBA machine need, and the heap
// the rest, as far as its address range goes (asked for more, the allocation fails and the app stops before main:
// armGBA-3DS's finding on a New 3DS).
#define LINEAR_WANTED (12u << 20)
extern char* fake_heap_start;
extern char* fake_heap_end;
extern u32 __ctru_heap, __ctru_linear_heap; // libctru's; the sizes are weak there
u32 __ctru_heap_size;
u32 __ctru_linear_heap_size;
void mappableInit(u32 addrMin, u32 addrMax);

void __system_allocateHeaps(void) {
    Handle limit = 0;
    s64 most = 0, now = 0;
    ResourceLimitType commit = RESLIMIT_COMMIT;
    if (R_FAILED(svcGetResourceLimit(&limit, CUR_PROCESS_HANDLE))) svcBreak(USERBREAK_PANIC);
    svcGetResourceLimitLimitValues(&most, limit, &commit, 1);
    svcGetResourceLimitCurrentValues(&now, limit, &commit, 1);
    svcCloseHandle(limit);
    u32 remaining = (u32) (most - now) & ~0xFFFu;
    u32 area = OS_HEAP_AREA_END - OS_HEAP_AREA_BEGIN;
    u32 heap = remaining > LINEAR_WANTED ? remaining - LINEAR_WANTED : remaining / 2;
    if (heap > area) heap = area;
    __ctru_heap_size = heap;
    __ctru_linear_heap_size = remaining - heap;
    if (R_FAILED(svcControlMemory(&__ctru_heap, OS_HEAP_AREA_BEGIN, 0, __ctru_heap_size, MEMOP_ALLOC, MEMPERM_READ | MEMPERM_WRITE)))
        svcBreak(USERBREAK_PANIC);
    if (R_FAILED(svcControlMemory(&__ctru_linear_heap, 0, 0, __ctru_linear_heap_size, MEMOP_ALLOC_LINEAR, MEMPERM_READ | MEMPERM_WRITE)))
        svcBreak(USERBREAK_PANIC);
    mappableInit(OS_MAP_AREA_BEGIN, OS_MAP_AREA_END);
    fake_heap_start = (char*) __ctru_heap;
    fake_heap_end = fake_heap_start + __ctru_heap_size;
}

static FILE* logFile;
static u64 logStart;

void host_log_open(const char* path) {
    char prev[300];
    snprintf(prev, sizeof(prev), "%.*s-prev.txt", (int) (strlen(path) > 4 ? strlen(path) - 4 : strlen(path)), path);
    remove(prev);
    rename(path, prev);
    logFile = fopen(path, "w");
    logStart = osGetTime();
}

static void log_va(const char* format, va_list args) {
    if (!logFile) return;
    fprintf(logFile, "[%7.2f] ", (double) (osGetTime() - logStart) / 1000.0);
    vfprintf(logFile, format, args);
    fputc('\n', logFile);
    fflush(logFile); // a crash leaves the lines so far
}

void host_log(const char* format, ...) {
    va_list args;
    va_start(args, format);
    log_va(format, args);
    va_end(args);
}

void host_log_close(void) {
    if (logFile) fclose(logFile);
    logFile = NULL;
}

void host_emu_log(void* ctx, const char* line) {
    (void) ctx;
    host_log("%s", line);
}

bool host_is_emulator(void) {
    static int known = -1;
    if (known < 0) {
        s64 value = 0;
        known = R_SUCCEEDED(svcGetSystemInfo(&value, 0x20000, 0)) && value != 0;
    }
    return known;
}

bool host_is_new_3ds(void) {
    bool isNew = false;
    APT_CheckNew3DS(&isNew);
    return isNew;
}

size_t host_heap_free(void) {
    struct mallinfo m = mallinfo();
    return __ctru_heap_size > (u32) m.uordblks ? __ctru_heap_size - (u32) m.uordblks : 0;
}

// --- what jitmem.c calls in its own frontend, under the names it calls them by ---

void logLine(const char* format, ...) {
    va_list args;
    va_start(args, format);
    log_va(format, args);
    va_end(args);
}

bool isEmulator(void) {
    return host_is_emulator();
}

bool isNew3ds(void) {
    return host_is_new_3ds();
}

bool writeFile(const char* path, const void* data, size_t size) {
    char temp[600];
    snprintf(temp, sizeof(temp), "%s.tmp", path);
    FILE* f = fopen(temp, "wb");
    if (!f) return false;
    bool ok = fwrite(data, 1, size, f) == size;
    ok = fclose(f) == 0 && ok;
    if (!ok) {
        remove(temp);
        return false;
    }
    remove(path);
    return rename(temp, path) == 0;
}

void* readFile(const char* path, size_t* size) {
    FILE* f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long length = ftell(f);
    fseek(f, 0, SEEK_SET);
    void* data = length >= 0 ? malloc(length ? (size_t) length : 1) : NULL;
    if (data && fread(data, 1, (size_t) length, f) != (size_t) length) {
        free(data);
        data = NULL;
    }
    fclose(f);
    if (data) *size = (size_t) length;
    return data;
}

// armGBA's second render worker (jitmem.c's is renamed away, ctr/CMakeLists.txt): none. It would share the fourth core
// with the companion's draw thread and EXPAND's, and beside them one of the two starved (43-48 FPS on FireRed's title).
bool renderThreadHostCreateSecond(pthread_t* thread, void* (*function)(void*), void* argument) {
    (void) thread;
    (void) function;
    (void) argument;
    return false;
}
