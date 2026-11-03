#include "pd_smol.h"

#include <string.h>

#define TABLE 64
#define LO_MAX 8192   // loSize is 13 bits
#define SYM_MAX 16384 // symSize is 14 bits

struct tans { uint8_t sym, k, y, mask; };

static uint32_t rd32(const uint8_t* b, size_t avail, size_t off, bool* ok) {
    if (off + 4 > avail) { *ok = false; return 0; }
    return (uint32_t) b[off] | (uint32_t) b[off + 1] << 8 | (uint32_t) b[off + 2] << 16 | (uint32_t) b[off + 3] << 24;
}

// Each of 16 symbols gets freq[s] consecutive slots j = f..2f-1; slot j reads k = 6 - floor(log2 j) bits, y = (j << k) - 64.
static bool build_table(const uint8_t* src, size_t avail, size_t off, struct tans* table) {
    bool ok = true;
    int freqs[16] = { 0 };
    for (int i = 0; i < 3; i++) {
        uint32_t w = rd32(src, avail, off + 4 * (size_t) i, &ok);
        for (int j = 0; j < 5; j++) freqs[i * 5 + j] = (int) (w >> (6 * j) & 0x3F);
        freqs[15] += (int) ((w & 0xC0000000u) >> (30 - 2 * i));
    }
    if (!ok) return false;
    int n = 0;
    for (int s = 0; s < 16; s++) {
        for (int j = freqs[s]; j < 2 * freqs[s]; j++) {
            if (n >= TABLE) return false;
            int k = 0;
            while ((j << k) < TABLE) k++;
            table[n++] = (struct tans) { (uint8_t) s, (uint8_t) k, (uint8_t) ((j << k) - TABLE), (uint8_t) ((1 << k) - 1) };
        }
    }
    return n == TABLE;
}

// One continuous little-endian u32 bitstream shared by the lo and symbol decodes; the tANS state carries over.
struct reader { const uint8_t* src; size_t avail, base; size_t word; uint32_t bits; int bitIndex, state; bool ok; };

static int next(struct reader* r, const struct tans* table) {
    struct tans t = table[r->state];
    r->state = t.y + (int) ((r->bits >> r->bitIndex) & t.mask);
    r->bitIndex += t.k;
    if (r->bitIndex >= 32) {
        r->word++;
        r->bits = rd32(r->src, r->avail, r->base + 4 * r->word, &r->ok);
        r->bitIndex -= 32;
        if (r->bitIndex) r->state += (int) ((r->bits & ((1u << r->bitIndex) - 1)) << (t.k - r->bitIndex));
    }
    return t.sym;
}

// Runs the instruction bytes `lo` over the symbols into words[] (static, shared); the u16 count, or -1 if malformed.
static uint16_t words[(8192 + 2) / 2 + 2];

static size_t instructions(const uint8_t* lo, size_t loSize, const uint16_t* sym, size_t symSize, size_t outBytes) {
    // lo holds (length, offset) pairs, 7 bits per byte + continue bit. A nonzero length emits one symbol, then copies
    // `length` values from `offset` back (1 = a run); length 0 emits `offset` literal symbols.
    size_t cap = outBytes / 2 + 1, n = 0, i = 0, si = 0;
#define B(at) ((at) < loSize ? lo[at] : 0)
    while (i < loSize && n < cap) {
        size_t length, offset;
        if (B(i) & 0x80) {
            length = (size_t) (B(i) & 0x7F) | (size_t) B(i + 1) << 7;
            offset = B(i + 2) & 0x7F;
            if (B(i + 2) & 0x80) { offset |= (size_t) B(i + 3) << 7; i += 4; } else i += 3;
        } else {
            length = B(i) & 0x7F;
            offset = B(i + 1) & 0x7F;
            if (B(i + 1) & 0x80) { offset |= (size_t) B(i + 2) << 7; i += 3; } else i += 2;
        }
        if (length) {
            if (si >= symSize || n >= cap) return (size_t) -1;
            words[n++] = sym[si++];
            if (offset == 0 || offset > n) return (size_t) -1;
            for (size_t k = 0; k < length && n < cap; k++) { words[n] = words[n - offset]; n++; }
        } else {
            for (size_t k = 0; k < offset && n < cap; k++) {
                if (si >= symSize) return (size_t) -1;
                words[n++] = sym[si++];
            }
        }
    }
#undef B
    return n;
}

bool pd_is_smol(const uint8_t* src, size_t avail) {
    return avail >= 8 && (src[0] & 0x0F) >= 1 && (src[0] & 0x0F) <= 6;
}

size_t pd_smol(const uint8_t* src, size_t avail, uint8_t* out, size_t outCap) {
    static uint8_t lo[LO_MAX];
    static uint16_t sym[SYM_MAX];
    bool ok = true;
    uint32_t h0 = rd32(src, avail, 0, &ok), h1 = rd32(src, avail, 4, &ok);
    if (!ok) return 0;
    int mode = (int) (h0 & 0xF);
    size_t imageSize = h0 >> 4 & 0x3FFF, symSize = h0 >> 18 & 0x3FFF;
    size_t bitstreamSize = h1 >> 6 & 0x1FFF, loSize = h1 >> 19 & 0x1FFF;
    size_t outBytes = imageSize * 4;
    if (mode < 1 || mode > 6 || !loSize || !symSize || loSize > LO_MAX || outBytes > 8192 || outBytes > outCap) return 0;

    const size_t data = 8;
    bool loEncoded = mode >= 4, symEncoded = mode == 2 || mode == 3 || mode >= 5, symDelta = mode == 3 || mode == 6;
    size_t loFreqs = 0, symFreqs = 0, streamWord = 0;
    if (mode == 4) { loFreqs = data; streamWord = 3; }
    else if (mode == 2 || mode == 3) { symFreqs = data; streamWord = 3; }
    else if (mode >= 5) { loFreqs = data; symFreqs = data + 12; streamWord = 6; }

    struct reader r = { src, avail, data, streamWord, 0, 0, (int) (h1 & 0x3F), true };
    r.bits = rd32(src, avail, data + 4 * streamWord, &r.ok);
    size_t leftover = data;
    struct tans table[TABLE];
    if (loEncoded) {
        if (!build_table(src, avail, loFreqs, table)) return 0;
        for (size_t i = 0; i < loSize; i++) {
            int a = next(&r, table), b = next(&r, table);
            lo[i] = (uint8_t) (a | b << 4);
        }
        leftover += 12;
    }
    if (symEncoded) {
        if (!build_table(src, avail, symFreqs, table)) return 0;
        int cur = 0;
        for (size_t i = 0; i < symSize; i++) {
            int v = 0;
            for (int n = 0; n < 4; n++) {
                int x = next(&r, table);
                if (symDelta) { cur = (cur + x) & 0xF; x = cur; }
                v |= x << (4 * n);
            }
            sym[i] = (uint16_t) v;
        }
        leftover += 12;
    }
    if (!r.ok) return 0;
    if (loEncoded || symEncoded) leftover += 4 * bitstreamSize;
    if (!symEncoded) {
        if (leftover + 2 * symSize > avail) return 0;
        for (size_t i = 0; i < symSize; i++) sym[i] = (uint16_t) (src[leftover + 2 * i] | src[leftover + 2 * i + 1] << 8);
        leftover += symSize * 2;
    }
    if (!loEncoded) {
        if (leftover + loSize > avail) return 0;
        memcpy(lo, src + leftover, loSize);
    }

    size_t n = instructions(lo, loSize, sym, symSize, outBytes);
    if (n == (size_t) -1) return 0;
    memset(out, 0, outBytes);
    for (size_t w = 0; w < n && w < outBytes / 2; w++) {
        out[2 * w] = (uint8_t) words[w];
        out[2 * w + 1] = (uint8_t) (words[w] >> 8);
    }
    return outBytes;
}

// Mode 8, SmolDecompressTilemap: u32 mode:4 | tilemapSize:14 | symSize:14 and a u32 instruction-byte count, then the raw
// u16 symbols (padded to a word) and the instruction bytes. The decoded u16s are deltas: each tile entry is the
// running sum of them.
size_t pd_smol_tilemap(const uint8_t* src, size_t avail, uint8_t* out, size_t outCap) {
    static uint8_t lo[LO_MAX];
    static uint16_t sym[SYM_MAX];
    bool ok = true;
    uint32_t h0 = rd32(src, avail, 0, &ok), h1 = rd32(src, avail, 4, &ok);
    if (!ok || (h0 & 0xF) != 8) return 0;
    size_t size = h0 >> 4 & 0x3FFF, symSize = h0 >> 18 & 0x3FFF, loSize = h1;
    if (!symSize || !loSize || loSize > LO_MAX || symSize > SYM_MAX || size > 8192 || size > outCap) return 0;
    size_t loAt = 8 + symSize * 2 + 2 * (symSize % 2);
    if (loAt + loSize > avail) return 0;
    for (size_t i = 0; i < symSize; i++) sym[i] = (uint16_t) (src[8 + 2 * i] | src[9 + 2 * i] << 8);
    memcpy(lo, src + loAt, loSize);
    size_t n = instructions(lo, loSize, sym, symSize, size);
    if (n == (size_t) -1) return 0;
    memset(out, 0, size);
    unsigned acc = 0;
    for (size_t w = 0; w < n && w < size / 2; w++) {
        acc = (acc + words[w]) & 0xFFFF;
        out[2 * w] = (uint8_t) acc;
        out[2 * w + 1] = (uint8_t) (acc >> 8);
    }
    return size;
}
