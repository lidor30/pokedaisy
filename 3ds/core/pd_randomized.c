#include "pd_randomized.h"

#include <string.h>

#include "pd_game.h"
#include "pd_guide.h"
#include "pd_sha1.h"

#define SPECIES 412     // gBaseStats' rows (internal ids 0..411)
#define MOVES 355       // gBattleMoves' (0..354)
#define BASE_STRIDE 28
#define MOVE_STRIDE 12
#define TRADE_STRIDE 60 // struct InGameTrade
#define TRADE_GIVEN 12
#define TRADE_WANTED 56

const struct pd_randomized* pd_randomized_match(const struct pd_game* g) {
    if (!g->rom || g->romSize > 0x1000000) return NULL;
    for (int i = 0; i < pd_randomized_rom_count; i++) {
        const struct pd_randomized* r = &pd_randomized_roms[i];
        if (strcmp(r->code, g->code) || r->rev != g->revision || g->romSize < r->end) continue;
        // RetailDumps.maskedSha1: in chunks, the names folded (0xD5..0xEE lower case -> upper), then the sites zeroed.
        static uint8_t chunk[1 << 16];
        struct pd_sha1 s;
        char hex[41];
        pd_sha1_init(&s);
        for (uint32_t off = 0; off < r->end; off += sizeof chunk) {
            uint32_t len = r->end - off < sizeof chunk ? r->end - off : (uint32_t) sizeof chunk;
            memcpy(chunk, g->rom + off, len);
            for (int k = 0; k < r->foldCount; k++) {
                uint32_t a = r->fold[k][0] > off ? r->fold[k][0] : off, z = r->fold[k][1] < off + len ? r->fold[k][1] : off + len;
                for (uint32_t p = a; p < z; p++)
                    if (chunk[p - off] >= 0xD5 && chunk[p - off] <= 0xEE) chunk[p - off] -= 0x1A;
            }
            for (int k = 0; k < r->maskCount; k++) {
                uint32_t a = r->mask[k][0] > off ? r->mask[k][0] : off, z = r->mask[k][1] < off + len ? r->mask[k][1] : off + len;
                if (a < z) memset(chunk + (a - off), 0, z - a);
            }
            pd_sha1_update(&s, chunk, len);
        }
        pd_sha1_hex(&s, hex);
        if (!strcmp(hex, r->sha1)) return r;
    }
    return NULL;
}

static uint16_t u16(const uint8_t* p) { return (uint16_t) (p[0] | p[1] << 8); }

bool pd_randomized_types(const struct pd_game* g, int species, int* t1, int* t2) {
    const struct pd_randomized* r = g->randomized;
    if (!r) return false;
    *t1 = *t2 = PD_TYPE_NONE;
    if (species <= 0 || species >= SPECIES) return true;
    const uint8_t* b = pd_rom_at(g, r->baseStats + (uint32_t) species * BASE_STRIDE, BASE_STRIDE);
    if (b) {
        *t1 = b[6];
        *t2 = b[7];
    }
    return true;
}

bool pd_randomized_move(const struct pd_game* g, int move, int* type, int* power) {
    const struct pd_randomized* r = g->randomized;
    if (!r || move <= 0 || move >= MOVES) return false;
    const uint8_t* m = pd_rom_at(g, r->battleMoves + (uint32_t) move * MOVE_STRIDE, MOVE_STRIDE);
    if (!m) return false;
    *power = m[1];
    *type = m[2];
    return true;
}

bool pd_randomized_pct(const struct pd_game* g, int atk, int def, int* pct) {
    const struct pd_randomized* r = g ? g->randomized : NULL;
    if (!r) return false;
    *pct = 100;
    // gTypeEffectiveness' (attacker, defender, x10) triples up to 0xFF, the 0xFE marker row stepped over (the app's
    // RandomizedRetail.typeChart: the rows after it count too).
    for (uint32_t i = 0; i + 2 < 0x200; i += 3) {
        const uint8_t* e = pd_rom_at(g, r->typeChart + i, 3);
        if (!e || e[0] == 0xFF) break;
        if (e[0] != 0xFE && e[0] == atk && e[1] == def) *pct = e[2] * 10;
    }
    return true;
}

const struct pd_area_thing* pd_randomized_area(const struct pd_game* g, const struct pd_area_thing* t, struct pd_area_thing* out) {
    const struct pd_randomized* r = g->randomized;
    if (!r) return t;
    *out = *t;
    switch (t->kind) {
    case PD_AREA_ITEM:
    case PD_AREA_HIDDEN:
        for (int i = 0; i < r->itemCount; i++) {
            if (r->items[i][0] != t->flag) continue;
            const uint8_t* p = pd_rom_at(g, 0x08000000u + r->items[i][1], 2);
            if (!p) return NULL;
            out->id = u16(p);
            return out;
        }
        return NULL;
    case PD_AREA_TRADE:
        for (int i = 0; i < r->tradeCount; i++) {
            if (r->trades_[i][0] != t->id || r->trades_[i][1] != t->wants) continue;
            const uint8_t* p = pd_rom_at(g, r->trades + (uint32_t) r->trades_[i][2] * TRADE_STRIDE, TRADE_STRIDE);
            if (!p) return NULL;
            out->id = u16(p + TRADE_GIVEN);
            out->wants = u16(p + TRADE_WANTED);
            return out;
        }
        return NULL;
    case PD_AREA_MON:
    case PD_AREA_EGG:
        for (int i = 0; i < r->staticCount; i++) {
            if (r->statics[i][0] != t->id) continue;
            const uint8_t* p = pd_rom_at(g, 0x08000000u + r->statics[i][1], 2);
            if (!p) return NULL;
            out->id = u16(p);
            return out;
        }
        return NULL;
    default:
        return t;
    }
}
