#include "pd_romart.h"

#include <stdlib.h>
#include <string.h>

#include "pd_games.h"

// --- finding blobs (RomArt.find) ---

// Both hosts (the 3DS's ARM11, the desktop) are little-endian: one unaligned
// load, where a byte-by-byte build was the scan's main cost.
static uint64_t u64le(const uint8_t* p) {
    uint64_t v;
    memcpy(&v, p, sizeof(v));
    return v;
}

static uint64_t fmix(uint64_t k) {
    k ^= k >> 33;
    k *= 0xFF51AFD7ED558CCDull;
    k ^= k >> 33;
    k *= 0xC4CEB9FE1A85EC53ull;
    k ^= k >> 33;
    return k;
}

static uint64_t head_hash(const uint8_t* p) {
    return fmix(u64le(p) ^ fmix(u64le(p + 8)));
}

static uint16_t prefilter(uint64_t a) {
    return (uint16_t) ((a * 0x9E3779B97F4A7C15ull) >> 48);
}

uint32_t pd_crc32(const uint8_t* data, size_t len) {
    static uint32_t table[256];
    if (!table[1]) {
        for (uint32_t n = 0; n < 256; n++) {
            uint32_t c = n;
            for (int k = 0; k < 8; k++) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            table[n] = c;
        }
    }
    uint32_t c = 0xFFFFFFFFu;
    for (size_t i = 0; i < len; i++) c = table[(c ^ data[i]) & 0xFF] ^ (c >> 8);
    return c ^ 0xFFFFFFFFu;
}

size_t pd_lz77(const uint8_t* src, size_t srcLen, uint8_t* dst, size_t dstLen) {
    if (srcLen < 4 || src[0] != 0x10) return 0;
    size_t size = src[1] | (size_t) src[2] << 8 | (size_t) src[3] << 16;
    if (size != dstLen) return 0;
    size_t in = 4, out = 0;
    while (out < size) {
        if (in >= srcLen) return 0;
        uint8_t flags = src[in++];
        for (int bit = 7; bit >= 0 && out < size; bit--) {
            if (flags >> bit & 1) {
                if (in + 1 >= srcLen) return 0;
                unsigned d = (unsigned) src[in] << 8 | src[in + 1];
                in += 2;
                size_t disp = (d & 0xFFF) + 1, len = (d >> 12) + 3;
                if (disp > out) return 0;
                for (size_t k = 0; k < len && out < size; k++, out++) dst[out] = dst[out - disp];
            } else {
                if (in >= srcLen) return 0;
                dst[out++] = src[in++];
            }
        }
    }
    return size;
}

// Decodes the blob that would start at off; true if its CRC matches.
static bool decode(const uint8_t* rom, size_t romSize, size_t off, const struct pd_blob_sig* sig, uint8_t* out) {
    if (sig->smol) {
        size_t left = romSize - off < 0x10000 ? romSize - off : 0x10000;
        return pd_smol(rom + off, left, out, sig->size) && pd_crc32(out, sig->size) == sig->crc;
    }
    if (sig->lz) {
        return pd_lz77(rom + off, romSize - off, out, sig->size) == sig->size && pd_crc32(out, sig->size) == sig->crc;
    }
    if (off + sig->size > romSize) return false;
    memcpy(out, rom + off, sig->size);
    return pd_crc32(out, sig->size) == sig->crc;
}

// Scans the ROM once for every wanted blob; each found one is decoded into
// out[i] (malloc'd, sig.size bytes), the rest left NULL.
static void find_blobs(const uint8_t* rom, size_t size, const struct pd_blob_sig* const* sigs, int n, uint8_t** out) {
    static uint64_t bits[1024];
    memset(bits, 0, sizeof(bits));
    for (int i = 0; i < n; i++) {
        uint16_t p = sigs[i]->pre;
        bits[p >> 6] |= 1ull << (p & 63);
        out[i] = NULL;
    }
    // A run of zero bytes hashes to 0 and passes the prefilter of a blob whose
    // window starts with eight zeros (Emerald's girl's card palette): skip
    // those runs unless a blob's whole window is zeros.
    bool zeroHead = false;
    for (int i = 0; i < n; i++) zeroHead |= sigs[i]->head == 0;
    int left = n;
    for (size_t i = 0; i + 16 <= size && left > 0; i++) {
        uint64_t a = u64le(rom + i);
        uint16_t p = prefilter(a);
        if (!(bits[p >> 6] >> (p & 63) & 1)) continue;
        if (!a && !zeroHead && !u64le(rom + i + 8)) continue;
        uint64_t h = head_hash(rom + i);
        for (int k = 0; k < n; k++) {
            const struct pd_blob_sig* sig = sigs[k];
            if (out[k] || sig->head != h || i < sig->headOff) continue;
            uint8_t* buf = malloc(sig->size);
            if (buf && decode(rom, size, i - sig->headOff, sig, buf)) {
                out[k] = buf;
                left--;
            } else {
                free(buf);
            }
        }
    }
}

void pd_romart_find(const struct pd_game* g, const struct pd_blob_sig* const* sigs, int n, uint8_t** out) {
    find_blobs(g->rom, g->romSize, sigs, n, out);
}

// --- the cache: every blob of the game's family, found in one pass ---

static uint8_t* found[PD_BLOB_COUNT];
static unsigned foundRom;

void pd_romart_scan(const struct pd_game* g) {
    unsigned rom = pd_rom_id(g);
    if (foundRom == rom) return;
    for (int i = 0; i < PD_BLOB_COUNT; i++) {
        free(found[i]);
        found[i] = NULL;
    }
    foundRom = rom;
    int family = g->kind == PD_GAME_FIRERED ? PD_BLOB_FIRERED : g->kind == PD_GAME_EMERALD ? PD_BLOB_EMERALD : 0;
    if (!rom || !family) return;
    // The party's art: only what this game's slots and backdrop are made of, so a ROM that has it all stops the pass early.
    bool party[PD_BLOB_COUNT] = { [PD_FR_HOLD_GFX] = true, [PD_FR_HOLD_PAL] = true };
    const struct pd_game_entry* e = g->entry;
    const struct pd_party_style* st = e ? e->partyStyle : NULL;
    if (st) {
        const short blobs[] = { st->gfx, st->pal, st->slotMain, st->slotNoHp, st->slotEmpty, st->ball, st->ballPal, st->status, st->statusPal, st->font };
        for (size_t i = 0; i < sizeof(blobs) / sizeof(*blobs); i++)
            if (blobs[i] >= 0) party[blobs[i]] = true;
    }
    for (int i = 0; e && i < e->partyBackdropCount; i++) {
        party[e->partyBackdrops[i].gfx] = true;
        party[e->partyBackdrops[i].pal] = true;
        party[e->partyBackdrops[i].map] = true;
    }
    int wanted[PD_BLOB_COUNT], n = 0;
    const struct pd_blob_sig* sigs[PD_BLOB_COUNT];
    static uint8_t* decoded[PD_BLOB_COUNT];
    for (int i = 0; i < PD_BLOB_COUNT; i++) {
        if ((pd_blob_sigs[i].family & family) || party[i]) {
            sigs[n] = &pd_blob_sigs[i];
            wanted[n++] = i;
        }
    }
    find_blobs(g->rom, g->romSize, sigs, n, decoded);
    for (int i = 0; i < n; i++) found[wanted[i]] = decoded[i];
}

const uint8_t* pd_romart_blob(const struct pd_game* g, enum pd_blob b) {
    pd_romart_scan(g);
    return b >= 0 && b < PD_BLOB_COUNT ? found[b] : NULL;
}

// --- smol (Smol.kt, pokeemerald-expansion's SmolDecompressData) ---

#define TANS_SIZE 64

struct tans_slot {
    uint8_t sym, k;
    int16_t y, mask;
};

static uint32_t rd32(const uint8_t* p) {
    return (uint32_t) p[0] | (uint32_t) p[1] << 8 | (uint32_t) p[2] << 16 | (uint32_t) p[3] << 24;
}

static bool build_table(const uint8_t* src, struct tans_slot* table) {
    int freqs[16] = { 0 };
    for (int i = 0; i < 3; i++) {
        uint32_t w = rd32(src + 4 * i);
        for (int j = 0; j < 5; j++) freqs[i * 5 + j] = (int) (w >> (6 * j) & 0x3F);
        freqs[15] += (int) ((w & 0xC0000000u) >> (30 - 2 * i));
    }
    int n = 0;
    for (int s = 0; s < 16; s++) {
        for (int j = freqs[s]; j < 2 * freqs[s]; j++) {
            int k = 0;
            while ((j << k) < TANS_SIZE) k++;
            if (n >= TANS_SIZE) return false;
            table[n++] = (struct tans_slot) { (uint8_t) s, (uint8_t) k, (int16_t) ((j << k) - TANS_SIZE), (int16_t) ((1 << k) - 1) };
        }
    }
    return n == TANS_SIZE;
}

struct bit_reader {
    const uint8_t* base;
    int word, state, bitIndex;
    uint32_t bits;
};

static int next_sym(struct bit_reader* r, const struct tans_slot* table) {
    const struct tans_slot* e = &table[r->state];
    int k = e->k;
    r->state = e->y + (int) (r->bits >> r->bitIndex & (uint32_t) e->mask);
    r->bitIndex += k;
    if (r->bitIndex >= 32) {
        r->word++;
        r->bits = rd32(r->base + 4 * r->word);
        r->bitIndex -= 32;
        if (r->bitIndex) r->state += (int) ((r->bits & ((1u << r->bitIndex) - 1)) << (k - r->bitIndex));
    }
    return e->sym;
}

// (length, offset) pairs: a nonzero length emits a symbol then copies length values from offset back; 0 emits offset literals.
static bool instructions(const uint8_t* lo, int loSize, const uint16_t* sym, int symSize, uint8_t* dst, size_t outBytes) {
    int outCount = (int) (outBytes / 2) + 1;
    uint16_t* out = malloc((size_t) outCount * 2);
    if (!out) return false;
    int n = 0, i = 0, si = 0;
    while (i < loSize && n < outCount) {
        int length, offset;
        if (lo[i] & 0x80) {
            length = (lo[i] & 0x7F) | lo[i + 1] << 7;
            offset = lo[i + 2] & 0x7F;
            if (lo[i + 2] & 0x80) {
                offset |= lo[i + 3] << 7;
                i += 4;
            } else {
                i += 3;
            }
        } else {
            length = lo[i] & 0x7F;
            offset = lo[i + 1] & 0x7F;
            if (lo[i + 1] & 0x80) {
                offset |= lo[i + 2] << 7;
                i += 3;
            } else {
                i += 2;
            }
        }
        if (length) {
            if (si >= symSize || n >= outCount) break;
            out[n++] = sym[si++];
            for (int k = 0; k < length && n < outCount; k++, n++) {
                if (n - offset < 0) {
                    free(out);
                    return false;
                }
                out[n] = out[n - offset];
            }
        } else {
            for (int k = 0; k < offset && n < outCount && si < symSize; k++) out[n++] = sym[si++];
        }
    }
    memset(dst, 0, outBytes);
    for (int w = 0; w < n && w < (int) (outBytes / 2); w++) {
        dst[2 * w] = (uint8_t) out[w];
        dst[2 * w + 1] = (uint8_t) (out[w] >> 8);
    }
    free(out);
    return true;
}

bool pd_smol(const uint8_t* src, size_t srcLen, uint8_t* dst, size_t dstLen) {
    if (srcLen < 16) return false;
    uint32_t h0 = rd32(src), h1 = rd32(src + 4);
    int mode = (int) (h0 & 0xF);
    bool ok = false;
    if (mode == 8) {
        int size = (int) (h0 >> 4 & 0x3FFF), symSize = (int) (h0 >> 18 & 0x3FFF), loSize = (int) h1;
        size_t loAt = 8 + (size_t) symSize * 2 + 2 * (size_t) (symSize % 2);
        if ((size_t) size != dstLen || loAt + (size_t) loSize > srcLen) return false;
        uint16_t* sym = malloc((size_t) (symSize + 1) * 2);
        if (!sym) return false;
        for (int i = 0; i < symSize; i++) sym[i] = (uint16_t) (src[8 + 2 * i] | src[9 + 2 * i] << 8);
        ok = instructions(src + loAt, loSize, sym, symSize, dst, dstLen);
        free(sym);
        int acc = 0;
        for (int w = 0; ok && w < size / 2; w++) {
            acc = (acc + (dst[2 * w] | dst[2 * w + 1] << 8)) & 0xFFFF;
            dst[2 * w] = (uint8_t) acc;
            dst[2 * w + 1] = (uint8_t) (acc >> 8);
        }
        return ok;
    }
    if (mode < 1 || mode > 6) return false;
    int imageSize = (int) (h0 >> 4 & 0x3FFF), symSize = (int) (h0 >> 18 & 0x3FFF);
    int bitstreamSize = (int) (h1 >> 6 & 0x1FFF), loSize = (int) (h1 >> 19 & 0x1FFF);
    if ((size_t) imageSize * 4 != dstLen || !loSize || !symSize) return false;
    const uint8_t* data = src + 8;
    bool loEncoded = mode >= 4, symEncoded = mode == 2 || mode == 3 || mode == 5 || mode == 6, symDelta = mode == 3 || mode == 6;
    int loFreqs = 0, symFreqs = 0, streamWord = 0;
    if (mode == 4) {
        streamWord = 3;
    } else if (mode == 2 || mode == 3) {
        streamWord = 3;
    } else if (mode == 5 || mode == 6) {
        symFreqs = 12;
        streamWord = 6;
    }
    struct bit_reader r = { data, streamWord, (int) (h1 & 0x3F), 0, rd32(data + 4 * streamWord) };
    uint8_t* lo = malloc((size_t) loSize);
    uint16_t* sym = malloc((size_t) symSize * 2);
    struct tans_slot table[TANS_SIZE];
    size_t leftover = 0;
    if (lo && sym) {
        ok = true;
        if (loEncoded) {
            ok = build_table(data + loFreqs, table);
            for (int i = 0; ok && i < loSize; i++) {
                int a = next_sym(&r, table), b = next_sym(&r, table);
                lo[i] = (uint8_t) (a | b << 4);
            }
            leftover += 12;
        }
        if (ok && symEncoded) {
            ok = build_table(data + symFreqs, table);
            int cur = 0;
            for (int i = 0; ok && i < symSize; i++) {
                int v = 0;
                for (int n = 0; n < 4; n++) {
                    int x = next_sym(&r, table);
                    if (symDelta) {
                        cur = (cur + x) & 0xF;
                        x = cur;
                    }
                    v |= x << (4 * n);
                }
                sym[i] = (uint16_t) v;
            }
            leftover += 12;
        }
        if (loEncoded || symEncoded) leftover += 4 * (size_t) bitstreamSize;
        if (ok && !symEncoded) {
            for (int i = 0; i < symSize; i++) sym[i] = (uint16_t) (data[leftover + 2 * (size_t) i] | data[leftover + 2 * (size_t) i + 1] << 8);
            leftover += (size_t) symSize * 2;
        }
        if (ok && !loEncoded) memcpy(lo, data + leftover, (size_t) loSize);
        if (ok) ok = instructions(lo, loSize, sym, symSize, dst, dstLen);
    }
    free(lo);
    free(sym);
    return ok;
}
