#include "pd_romart.h"

#include <stdlib.h>
#include <string.h>

#include "pd_smol.h"
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
    if (sig->lz) {
        return pd_lz77(rom + off, romSize - off, out, sig->size) == sig->size && pd_crc32(out, sig->size) == sig->crc;
    }
    if (sig->smol) {
        size_t got = (rom[off] & 0xF) == 8 ? pd_smol_tilemap(rom + off, romSize - off, out, sig->size)
                                           : pd_smol(rom + off, romSize - off, out, sig->size);
        return got == sig->size && pd_crc32(out, sig->size) == sig->crc;
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

