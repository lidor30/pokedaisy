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

static bool playingOld; // host_old_3ds_stand_in
static FILE* logFile;
static u64 logStart;
// While a game runs the lines wait here (host_log_hold): gpSP 3D found that writing its log to the SD card mid-game froze
// an Old 3DS now and then. Written out when the game closes, the app goes to the HOME Menu or sleeps, or the buffer fills.
#define LOG_HELD_BYTES (16 * 1024)
static char logHeld[LOG_HELD_BYTES];
static size_t logHeldUsed;
static bool logHolding;
static LightLock logLock;
static bool logLockReady;

void host_log_open(const char* path) {
    if (!logLockReady) {
        LightLock_Init(&logLock);
        logLockReady = true;
    }
    char prev[300];
    snprintf(prev, sizeof(prev), "%.*s-prev.txt", (int) (strlen(path) > 4 ? strlen(path) - 4 : strlen(path)), path);
    remove(prev);
    rename(path, prev);
    logFile = fopen(path, "w");
    logStart = osGetTime();
}

// The held lines into the file; the lock is held.
static void log_write_held(void) {
    if (logFile && logHeldUsed) {
        fwrite(logHeld, 1, logHeldUsed, logFile);
        fflush(logFile);
    }
    logHeldUsed = 0;
}

static void log_va(const char* format, va_list args) {
    if (!logFile) return;
    char line[600];
    int n = snprintf(line, sizeof(line), "[%7.2f] ", (double) (osGetTime() - logStart) / 1000.0);
    if (n < 0) n = 0;
    int m = vsnprintf(line + n, sizeof(line) - (size_t) n - 1, format, args); // leaves room for the newline
    size_t len = (size_t) n + (m > 0 ? (size_t) m : 0);
    if (len > sizeof(line) - 2) len = sizeof(line) - 2;
    line[len++] = '\n';
    LightLock_Lock(&logLock);
    if (logHolding) {
        if (logHeldUsed + len > sizeof(logHeld)) log_write_held();
        memcpy(logHeld + logHeldUsed, line, len);
        logHeldUsed += len;
    } else {
        fwrite(line, 1, len, logFile);
        fflush(logFile); // a crash leaves the lines so far
    }
    LightLock_Unlock(&logLock);
}

void host_log(const char* format, ...) {
    va_list args;
    va_start(args, format);
    log_va(format, args);
    va_end(args);
}

void host_log_hold(bool hold) {
    if (!logLockReady) return;
    LightLock_Lock(&logLock);
    if (!hold) log_write_held();
    logHolding = hold;
    LightLock_Unlock(&logLock);
}

void host_log_flush(void) {
    if (!logLockReady) return;
    LightLock_Lock(&logLock);
    log_write_held();
    LightLock_Unlock(&logLock);
}

void host_log_close(void) {
    host_log_hold(false);
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
    static int known = -1;
    if (known < 0) {
        bool isNew = false;
        APT_CheckNew3DS(&isNew);
#ifdef PD_DEV
        // The dev build's stand-in for an Old 3DS on a New one (sdmc:/pokedaisy-dev/old3ds): no 804 MHz mode, no
        // third or fourth core, the system core lent as an Old 3DS lends it.
        FILE* f = isNew ? fopen("sdmc:/pokedaisy-dev/old3ds", "rb") : NULL;
        if (f) {
            fclose(f);
            isNew = false;
            playingOld = true;
        }
#endif
        known = isNew;
    }
    return known;
}

bool host_old_3ds_stand_in(void) {
    host_is_new_3ds();
    return playingOld;
}

// --- the Old 3DS's second core ---
// The Old 3DS has two cores: the app's (core 0) and the system's (core 1), where an app may run threads for as much of
// the core's time as it asks (APT_SetAppCpuTimeLimit, 5-89%). gpSP 3D draws there at 80% on an Old 3DS; it gives the
// core back (30%) while the app is in the background, else the HOME Menu, which runs there, crawls.
#define CORE1_BACKGROUND 30
static u32 core1Limit; // what the app asked for, 0 = never
static aptHookCookie core1Hook;

static void core1_apt_hook(APT_HookType hook, void* param) {
    (void) param;
    // Not for sleep: nothing runs then, and it'd be a call to APT in the middle of its sleep handshake (gpSP 3D's note).
    if (hook == APTHOOK_ONSUSPEND) {
        host_log_flush();
        if (core1Limit > CORE1_BACKGROUND) APT_SetAppCpuTimeLimit(CORE1_BACKGROUND);
    } else if (hook == APTHOOK_ONRESTORE) {
        if (core1Limit > CORE1_BACKGROUND) APT_SetAppCpuTimeLimit(core1Limit);
    } else if (hook == APTHOOK_ONSLEEP || hook == APTHOOK_ONEXIT) {
        host_log_flush();
    }
}

bool host_core1_take(unsigned percent) {
    if (!core1Hook.callback) aptHook(&core1Hook, core1_apt_hook, NULL);
    if (percent <= core1Limit) return true;
    Result rc = APT_SetAppCpuTimeLimit(percent);
    host_log("core 1: %u%% of its time for the app (%08lX)", percent, (unsigned long) rc);
    if (R_FAILED(rc)) return core1Limit > 0;
    core1Limit = percent;
    return true;
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

// armGBA's render worker (jitmem.c's is renamed away, ctr/CMakeLists.txt): the New 3DS's third core, else the system core
// at the app's 80% - jitmem.c kept it off an Old 3DS. There it runs One Frame Late (main.c's lateVideo): drawing each
// frame in step, the game waited on it a third of every second. At the game thread's priority, below the draw thread.
#define RENDER_STACK (256 * 1024)
bool renderThreadHostCreate(pthread_t* thread, void* (*function)(void*), void* argument) {
    s32 priority = 0x30;
    svcGetThreadPriority(&priority, CUR_THREAD_HANDLE);
    int core = host_is_new_3ds() ? 2 : 1;
    Thread t = NULL;
    if (core == 2) t = threadCreate((ThreadFunc) (void*) function, argument, RENDER_STACK, priority, 2, false);
    if (!t && host_core1_take(HOST_CORE1_PERCENT)) {
        core = 1;
        t = threadCreate((ThreadFunc) (void*) function, argument, RENDER_STACK, priority, 1, false);
    }
    host_log("armGBA's renderer on core %d: %s", core, t ? "started" : "couldn't start, drawn with the game");
    if (!t) return false;
    *thread = (pthread_t) t; // libctru's pthread_t is its Thread, so pthread_join waits for this one
    return true;
}

// armGBA's second render worker (jitmem.c's is renamed away, ctr/CMakeLists.txt): none. It would share the fourth core
// with the companion's draw thread and EXPAND's, and beside them one of the two starved (43-48 FPS on FireRed's title).
bool renderThreadHostCreateSecond(pthread_t* thread, void* (*function)(void*), void* argument) {
    (void) thread;
    (void) function;
    (void) argument;
    return false;
}
