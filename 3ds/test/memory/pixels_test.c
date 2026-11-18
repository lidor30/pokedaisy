#include "../../core/pd_pixels.h"

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

// Deliberately keep the old full-coordinate address and conversion here as
// the independent oracle, including alpha and unused texture padding.
static unsigned original_offset(unsigned x, unsigned y, unsigned tw) {
    return ((((y >> 3) * (tw >> 3)) + (x >> 3)) << 6) |
           ((x & 1) | ((y & 1) << 1) | ((x & 2) << 1) |
            ((y & 2) << 2) | ((x & 4) << 2) | ((y & 4) << 3));
}

static void original_canvas(uint16_t* dst, unsigned tw, const uint32_t* src, unsigned w, unsigned h) {
    for (unsigned y = 0; y < h; ++y)
        for (unsigned x = 0; x < w; ++x) {
            uint32_t p = src[y * w + x];
            dst[original_offset(x, y, tw)] =
                (uint16_t)(((p >> 19) & 31) << 11 | ((p >> 10) & 63) << 5 | ((p >> 3) & 31));
        }
}

static uint32_t expand(uint16_t p) {
    uint32_t r = p >> 11 & 31, g = p >> 5 & 63, b = p & 31;
    r = r << 3 | r >> 2;
    g = g << 2 | g >> 4;
    b = b << 3 | b >> 2;
    return b << 16 | g << 8 | r;
}

static void check_size(unsigned w, unsigned h, unsigned tw) {
    unsigned th = (h + 7) & ~7u, stride = w + 7;
    size_t count = (size_t)tw * th;
    uint32_t* src = malloc((size_t)w * h * sizeof(*src));
    uint16_t* native = malloc((size_t)stride * h * sizeof(*native));
    uint16_t* staged = malloc((size_t)w * h * sizeof(*staged));
    uint16_t* a = malloc(count * sizeof(*a));
    uint16_t* b = malloc(count * sizeof(*b));
    uint32_t* oa = malloc(count * sizeof(*oa));
    uint32_t* ob = malloc(count * sizeof(*ob));
    assert(src && native && staged && a && b && oa && ob);
    uint32_t rng = 12345;
    for (unsigned y = 0; y < h; ++y)
        for (unsigned x = 0; x < w; ++x) {
            rng = rng * 1664525u + 1013904223u;
            src[y * w + x] = rng;
            native[y * stride + x] = (uint16_t)rng;
        }
    memset(a, 0xA5, count * sizeof(*a));
    memset(b, 0xA5, count * sizeof(*b));
    original_canvas(a, tw, src, w, h);
    pd_pixels_canvas(b, tw, src, w, h);
    assert(!memcmp(a, b, count * sizeof(*a)));
    memset(oa, 0x5A, count * sizeof(*oa));
    memset(ob, 0x5A, count * sizeof(*ob));
    for (unsigned y = 0; y < h; ++y)
        for (unsigned x = 0; x < w; ++x) {
            uint32_t p = src[y * w + x];
            oa[original_offset(x, y, tw)] = (p & 0xFF000000) >> 24 |
                (p & 0xFF) << 8 | (p & 0xFF00) << 8 | (p & 0xFF0000) << 8;
        }
    pd_pixels_overlay(ob, tw, src, w, h);
    assert(!memcmp(oa, ob, count * sizeof(*oa)));

    pd_pixels_stage_native(staged, native, stride, w, h);
    // Core starts the next frame: overwrite the borrowed source immediately.
    for (unsigned y = 0; y < h; ++y)
        for (unsigned x = 0; x < w; ++x) {
            assert(staged[y * w + x] == native[y * stride + x]);
            native[y * stride + x] = 0;
        }
    memset(a, 0xA5, count * sizeof(*a));
    memset(b, 0xA5, count * sizeof(*b));
    for (unsigned y = 0; y < h; ++y)
        for (unsigned x = 0; x < w; ++x) a[original_offset(x, y, tw)] = staged[y * w + x];
    pd_pixels_native(b, tw, staged, w, w, h);
    assert(!memcmp(a, b, count * sizeof(*a)));
    // The common contiguous staging case must preserve the same owned image.
    pd_pixels_stage_native(native, staged, w, w, h);
    assert(!memcmp(native, staged, (size_t)w * h * sizeof(*staged)));
    free(src); free(native); free(staged); free(a); free(b); free(oa); free(ob);
}

int main(void) {
    for (unsigned p = 0; p <= 65535; ++p) {
        uint32_t old = expand((uint16_t)p);
        uint16_t back = (uint16_t)((old & 0xF8) << 8 | (old >> 5 & 0x7E0) | (old >> 19 & 31));
        assert(back == p); // Native upload is exactly the former round trip.
    }
    const unsigned sizes[][3] = { {1,1,8}, {7,9,16}, {9,7,16}, {160,144,256},
                                  {240,160,256}, {320,240,512}, {400,240,512}, {128,16,128} };
    for (unsigned i = 0; i < sizeof(sizes) / sizeof(*sizes); ++i)
        check_size(sizes[i][0], sizes[i][1], sizes[i][2]);
    for (unsigned flags = 0; flags < 16; ++flags) {
        bool allowed = pd_pixels_native_allowed(flags & 1, flags & 2, flags & 4, flags & 8);
        assert(allowed == (flags == 1));
    }
    puts("PASS: all 65536 colours, canvas/overlay/native tiles, padding, partial tiles, staging lifetime, fallbacks");

    // A CPU microbenchmark only: these numbers do not predict Old3DS FPS.
    static uint32_t src[320 * 240];
    static uint16_t dst[512 * 256];
    for (unsigned i = 0; i < 320 * 240; ++i) src[i] = i * 2654435761u;
    const unsigned runs = 300;
    clock_t begin = clock();
    for (unsigned n = 0; n < runs; ++n) {
        original_canvas(dst, 512, src, 320, 240);
        src[n] ^= dst[n]; // Keep each iteration observable to the optimizer.
    }
    clock_t middle = clock();
    for (unsigned n = 0; n < runs; ++n) {
        pd_pixels_canvas(dst, 512, src, 320, 240);
        src[n] ^= dst[n];
    }
    clock_t end = clock();
    printf("Host CPU canvas benchmark: original %.3f ms, tiled %.3f ms (%u uploads)\n",
           1000.0 * (middle - begin) / CLOCKS_PER_SEC, 1000.0 * (end - middle) / CLOCKS_PER_SEC, runs);
    return 0;
}
