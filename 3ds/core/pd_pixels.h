#ifndef PD_PIXELS_H
#define PD_PIXELS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

static inline unsigned pd_pixels_morton(unsigned x, unsigned y) {
    return (x & 1) | ((y & 1) << 1) | ((x & 2) << 1) |
           ((y & 2) << 2) | ((x & 4) << 2) | ((y & 4) << 3);
}

static inline uint16_t pd_pixels_rgb565(uint32_t p) {
    return (uint16_t)((p >> 8 & 0xF800) | (p >> 5 & 0x7E0) | (p >> 3 & 0x1F));
}

// Visit each 8x8 tile together. Every horizontal pair occupies adjacent
// texels, avoiding the full texture-address calculation for every pixel.
static inline void pd_pixels_canvas(uint16_t* dst, unsigned textureWidth,
                                    const uint32_t* src, unsigned w, unsigned h) {
    for (unsigned y0 = 0; y0 < h; y0 += 8) {
        for (unsigned x0 = 0; x0 < w; x0 += 8) {
            uint16_t* tile = dst + ((y0 >> 3) * (textureWidth >> 3) + (x0 >> 3)) * 64;
            for (unsigned y = 0; y < 8 && y0 + y < h; ++y) {
                const uint32_t* row = src + (y0 + y) * w + x0;
                for (unsigned x = 0; x < 8 && x0 + x < w; x += 2) {
                    unsigned at = pd_pixels_morton(x, y);
                    tile[at] = pd_pixels_rgb565(row[x]);
                    if (x0 + x + 1 < w) tile[at + 1] = pd_pixels_rgb565(row[x + 1]);
                }
            }
        }
    }
}

static inline void pd_pixels_overlay(uint32_t* dst, unsigned textureWidth,
                                     const uint32_t* src, unsigned w, unsigned h) {
    for (unsigned y0 = 0; y0 < h; y0 += 8) {
        for (unsigned x0 = 0; x0 < w; x0 += 8) {
            uint32_t* tile = dst + ((y0 >> 3) * (textureWidth >> 3) + (x0 >> 3)) * 64;
            for (unsigned y = 0; y < 8 && y0 + y < h; ++y) {
                const uint32_t* row = src + (y0 + y) * w + x0;
                for (unsigned x = 0; x < 8 && x0 + x < w; x += 2) {
                    unsigned at = pd_pixels_morton(x, y);
                    uint32_t p = row[x];
                    tile[at] = (p << 8) | (p >> 24);
                    if (x0 + x + 1 < w) {
                        p = row[x + 1];
                        tile[at + 1] = (p << 8) | (p >> 24);
                    }
                }
            }
        }
    }
}

static inline void pd_pixels_native(uint16_t* dst, unsigned textureWidth,
                                    const uint16_t* src, unsigned stride, unsigned w, unsigned h) {
    for (unsigned y0 = 0; y0 < h; y0 += 8) {
        for (unsigned x0 = 0; x0 < w; x0 += 8) {
            uint16_t* tile = dst + ((y0 >> 3) * (textureWidth >> 3) + (x0 >> 3)) * 64;
            for (unsigned y = 0; y < 8 && y0 + y < h; ++y) {
                const uint16_t* row = src + (y0 + y) * stride + x0;
                for (unsigned x = 0; x < 8 && x0 + x < w; x += 2) {
                    unsigned at = pd_pixels_morton(x, y);
                    tile[at] = row[x];
                    if (x0 + x + 1 < w) tile[at + 1] = row[x + 1];
                }
            }
        }
    }
}

// Native pixels may be overwritten as soon as the core starts its next
// frame. The asynchronous display thread must consume an owned snapshot.
static inline void pd_pixels_stage_native(uint16_t* dst, const uint16_t* src,
                                          unsigned stride, unsigned w, unsigned h) {
    if (stride == w) {
        memcpy(dst, src, (size_t) w * h * sizeof(*dst));
        return;
    }
    for (unsigned y = 0; y < h; ++y)
        memcpy(dst + y * w, src + y * stride, (size_t) w * sizeof(*dst));
}

static inline bool pd_pixels_native_allowed(bool gpsp, bool wide, bool flying, bool flightDrawn) {
    return gpsp && !wide && !flying && !flightDrawn;
}

#endif
