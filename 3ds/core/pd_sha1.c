#include "pd_sha1.h"

#include <stdio.h>
#include <string.h>

#if defined(__aarch64__) && defined(__linux__)
#include <arm_neon.h>
#include <sys/auxv.h>
#ifndef HWCAP_SHA1
#define HWCAP_SHA1 (1 << 5)
#endif
// ARMv8's SHA-1 instructions (every aarch64 handheld plorpOS runs on has them, /proc/cpuinfo "sha1"): a 32 MB
// hack hashes in tens of ms on a Cortex-A53 instead of seconds.
__attribute__((target("+crypto"))) static void blocks_hw(uint32_t h[5], const uint8_t* p, size_t n) {
    uint32x4_t abcd = vld1q_u32(h);
    uint32_t e = h[4];
    for (; n; n--, p += 64) {
        uint32x4_t abcd0 = abcd;
        uint32_t e0 = e, e1;
        uint32x4_t m0 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(p)));
        uint32x4_t m1 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(p + 16)));
        uint32x4_t m2 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(p + 32)));
        uint32x4_t m3 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(p + 48)));
        static const uint32_t K[4] = {0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC, 0xCA62C1D6};
        for (int r = 0; r < 20; r++) {
            uint32x4_t wk = vaddq_u32(m0, vdupq_n_u32(K[r / 5]));
            e1 = vsha1h_u32(vgetq_lane_u32(abcd, 0));
            if (r < 5) abcd = vsha1cq_u32(abcd, e, wk);
            else if (r < 10 || r >= 15) abcd = vsha1pq_u32(abcd, e, wk);
            else abcd = vsha1mq_u32(abcd, e, wk);
            e = e1;
            uint32x4_t next = vsha1su1q_u32(vsha1su0q_u32(m0, m1, m2), m3);
            m0 = m1, m1 = m2, m2 = m3, m3 = next;
        }
        abcd = vaddq_u32(abcd, abcd0);
        e += e0;
    }
    vst1q_u32(h, abcd);
    h[4] = e;
}
static int have_hw(void) {
    static int hw = -1;
    if (hw < 0) hw = (getauxval(AT_HWCAP) & HWCAP_SHA1) != 0;
    return hw;
}
#endif

#define ROL(x, n) ((uint32_t) ((x) << (n) | (x) >> (32 - (n))))

static void block(struct pd_sha1* s, const uint8_t* p) {
    uint32_t w[80];
    for (int i = 0; i < 16; i++)
        w[i] = (uint32_t) p[i * 4] << 24 | (uint32_t) p[i * 4 + 1] << 16 | (uint32_t) p[i * 4 + 2] << 8 | p[i * 4 + 3];
    for (int i = 16; i < 80; i++) w[i] = ROL(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
    uint32_t a = s->h[0], b = s->h[1], c = s->h[2], d = s->h[3], e = s->h[4];
    for (int i = 0; i < 80; i++) {
        uint32_t f, k;
        if (i < 20) f = (b & c) | (~b & d), k = 0x5A827999;
        else if (i < 40) f = b ^ c ^ d, k = 0x6ED9EBA1;
        else if (i < 60) f = (b & c) | (b & d) | (c & d), k = 0x8F1BBCDC;
        else f = b ^ c ^ d, k = 0xCA62C1D6;
        uint32_t t = ROL(a, 5) + f + e + k + w[i];
        e = d, d = c, c = ROL(b, 30), b = a, a = t;
    }
    s->h[0] += a, s->h[1] += b, s->h[2] += c, s->h[3] += d, s->h[4] += e;
}

void pd_sha1_init(struct pd_sha1* s) {
    static const uint32_t h0[5] = {0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0};
    memcpy(s->h, h0, sizeof h0);
    s->len = 0;
    s->fill = 0;
}

void pd_sha1_update(struct pd_sha1* s, const void* data, size_t len) {
    const uint8_t* p = data;
    s->len += len;
    if (s->fill) {
        size_t n = 64 - s->fill < len ? 64 - s->fill : len;
        memcpy(s->buf + s->fill, p, n);
        s->fill += n, p += n, len -= n;
        if (s->fill < 64) return;
        block(s, s->buf);
        s->fill = 0;
    }
#if defined(__aarch64__) && defined(__linux__)
    if (len >= 64 && have_hw()) {
        blocks_hw(s->h, p, len / 64);
        p += len / 64 * 64;
        len %= 64;
    }
#endif
    for (; len >= 64; p += 64, len -= 64) block(s, p);
    memcpy(s->buf, p, len);
    s->fill = len;
}

void pd_sha1_hex(struct pd_sha1* s, char out[41]) {
    uint64_t bits = s->len * 8;
    uint8_t pad[72] = {0x80};
    size_t n = (s->fill < 56 ? 56 : 120) - s->fill;
    for (int i = 0; i < 8; i++) pad[n + i] = (uint8_t) (bits >> (56 - 8 * i));
    pd_sha1_update(s, pad, n + 8);
    for (int i = 0; i < 5; i++) snprintf(out + i * 8, 9, "%08x", s->h[i]);
}
