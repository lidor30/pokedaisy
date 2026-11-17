#include "../../emu/pd_gpsp.h"

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>
#ifdef PD_TEST_THREAD_FAILURE
#include <pthread.h>
#include <errno.h>
static bool refuseThread;
int __real_pthread_create(pthread_t*, const pthread_attr_t*, void* (*)(void*), void*);
int __wrap_pthread_create(pthread_t* t, const pthread_attr_t* attr, void* (*fn)(void*), void* arg) {
    return refuseThread ? EAGAIN : __real_pthread_create(t, attr, fn, arg);
}
#endif

#define ARENA_SIZE (32u << 20)
static unsigned requests;
static unsigned char* arena;

static void* refuse_arena(size_t size) {
    assert(size == ARENA_SIZE);
    ++requests;
    return NULL;
}

static void* provide_arena(size_t size) {
    assert(size == ARENA_SIZE);
    ++requests;
    memset(arena, 0, ARENA_SIZE);
    return arena;
}

int main(int argc, char** argv) {
    assert(argc == 3); // ROM and a temporary save path, never a player's save.
    char error[128] = "";
    struct pd_gpsp_host host = { .romBuffer = refuse_arena };
    assert(!pd_gpsp_open(argv[1], argv[2], &host, error, sizeof(error)));
    assert(requests == 1 && strstr(error, "contiguous 32 MiB"));
    assert(!pd_gpsp_loaded());
    struct pd_gpsp_memory memory;
    pd_gpsp_memory(&memory);
    assert(!memory.rom && !memory.romSize);

    arena = malloc(ARENA_SIZE);
    assert(arena);
    host.romBuffer = provide_arena;
    FILE* rom = fopen(argv[1], "rb");
    assert(rom);
    fseek(rom, 0, SEEK_END);
    long length = ftell(rom);
    assert(length > (1 << 20) && length <= ARENA_SIZE);
    rewind(rom);
    unsigned char chunk[65536];
    uLong expected = crc32(0, NULL, 0);
    size_t n;
    while ((n = fread(chunk, 1, sizeof(chunk), rom))) expected = crc32(expected, chunk, (uInt)n);
    assert(!ferror(rom));

    // Retry after allocation failure, then unload/reopen the same borrowed arena.
    static uint32_t video[240 * 160];
    int passes = 2;
#ifdef PD_TEST_THREAD_FAILURE
    passes = 3;
#endif
    for (int pass = 0; pass < passes; ++pass) {
#ifdef PD_TEST_THREAD_FAILURE
        refuseThread = pass == 2;
#endif
        host.threaded = pass != 0;
        host.renderCore = -1;
        assert(pd_gpsp_open(argv[1], argv[2], &host, error, sizeof(error)));
        assert(pd_gpsp_loaded());
        assert(pd_gpsp_threaded() == (pass == 1));
        struct pd_gpsp_stats stats;
        pd_gpsp_get_stats(&stats);
        assert(stats.snapshotBytes > 1024 * 1024);
        assert(stats.startError == (pass == 2 ? -7 : 0));
        pd_gpsp_set_video(video, 240);
        pd_gpsp_memory(&memory);
        assert(memory.rom == arena && memory.romSize == (size_t)length);
        assert(pd_gpsp_crc() == expected);
        // Companion patches may repeat the same bytes. Both identical and
        // changed writes must preserve adjacent RAM/ROM and reject overflow.
        uint8_t ramBefore[4];
        memcpy(ramBefore, memory.ewram + 0x3fffc, 4);
        uint8_t ramNew[4];
        for (unsigned i = 0; i < 4; ++i) ramNew[i] = ramBefore[i] ^ 0x5a;
        assert(pd_gpsp_write(0x0203fffc, ramNew, 4));
        assert(pd_gpsp_write(0x0203fffc, ramNew, 4));
        assert(!pd_gpsp_write(0x0203fffd, ramNew, 4));
        assert(!memcmp(memory.ewram + 0x3fffc, ramNew, 4));
        assert(pd_gpsp_write(0x0203fffc, ramBefore, 4));
        uint8_t romBefore[4], romNew[4];
        memcpy(romBefore, memory.rom + length - 4, 4);
        for (unsigned i = 0; i < 4; ++i) romNew[i] = romBefore[i] ^ 0xa5;
        uint32_t romAddress = 0x08000000u + (uint32_t)length - 4;
        assert(pd_gpsp_rom_write(romAddress, romNew, 4));
        assert(pd_gpsp_rom_write(romAddress, romNew, 4));
        assert(!pd_gpsp_rom_write(romAddress + 1, romNew, 4));
        assert(!memcmp(memory.rom + length - 4, romNew, 4));
        assert(pd_gpsp_rom_write(romAddress, romBefore, 4));
        // Probe across every 1 MiB boundary, including the far end of the ROM.
        for (long at = (1 << 20) - 16; at + 32 <= length; at += 1 << 20) {
            assert(!fseek(rom, at, SEEK_SET));
            assert(fread(chunk, 1, 32, rom) == 32);
            assert(!memcmp(memory.rom + at, chunk, 32));
        }
        for (int frame = 0; frame < 8; ++frame) pd_gpsp_run_frame();
        size_t nativeStride = 0;
        const uint16_t* native = pd_gpsp_native_pixels(&nativeStride);
        assert(native && nativeStride >= 240);
        for (unsigned y = 0; y < 160; ++y)
            for (unsigned x = 0; x < 240; ++x) {
                uint32_t p = native[y * nativeStride + x];
                uint32_t r = p >> 11 & 31, g = p >> 5 & 63, b = p & 31;
                uint32_t expectedPixel = ((b << 3 | b >> 2) << 16) |
                    ((g << 2 | g >> 4) << 8) | (r << 3 | r >> 2);
                assert(video[y * 240 + x] == expectedPixel);
            }
        pd_gpsp_close();
        assert(!pd_gpsp_loaded());
        assert(!pd_gpsp_native_pixels(&nativeStride));
    }
    fclose(rom);
    free(arena);
    puts("PASS: allocation failure, retry, ROM CRC/boundaries, threaded/unthreaded native video, worker-failure fallback, close/reopen");
    return 0;
}
