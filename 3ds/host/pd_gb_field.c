#include "pd_gb_field.h"

#include <stdio.h>
#include <string.h>

#include "../../app/src/main/cpp/pk_gb_field.h"
#include "pd_session.h"

#define JOY_IGNORE 0xCD6Bu // the same in every Gen 1 cart
#define GEN1_MON 44
#define NUM_CITY_MAPS 11
#define FLY_MOVE 19
#define MAX_HALTS 3

// Crystal: wramx(1, ...).
#define W1(a) (0x10000u | (a))
#define C_QUEUE W1(0xD45C)
#define C_PC_BANK 0x2F
#define C_PC_SCRIPT 0x41A9
#define C_FLY_BANK 0x03
#define C_FLY_SCRIPT 0x4AA3
#define C_SCRIPT_RUNNING W1(0xD438)
#define C_MAP_EVENT_STATUS W1(0xD433)
#define C_BATTLE_MODE W1(0xD22D)
#define C_DEFAULT_SPAWN W1(0xD001)
#define C_VISITED_SPAWNS W1(0xDCA5)
#define C_JOHTO_BADGES W1(0xD857)
#define C_ENVIRONMENT W1(0xD19A)
#define C_PARTY_COUNT W1(0xDCD7)
#define C_PARTY_MOVES W1(0xDCE1)
#define C_MON 0x30
#define C_EGG 0xFD
#define C_COOLDOWN W1(0xD452)
#define C_FLYPOINTS (0x24u * 0x4000 + (0x5C5E - 0x4000))
#define C_KANTO_LANDMARK 47
#define C_KANTO_FLYPOINT 12
#define C_SPAWN_INDIGO 13

static const struct pd_gb_sig CRYSTAL_SIGS[] = {
    { 0x25u * 0x4000 + (0x7C30 - 0x4000), "fa5cd4a7c8215ed42a666ffa5dd4cd1f" },
    { 0x2Fu * 0x4000 + (C_PC_SCRIPT - 0x4000), "470f1c00499185" },
    { 0x03u * 0x4000 + (C_FLY_SCRIPT - 0x4000), "7c0e0016300f3500" },
    { 0x25u * 0x4000 + (0x68C7 - 0x4000), "2152d47ea7c835c837c9" },
    { C_FLYPOINTS, "010e030f" },
};

static int u8(struct pd_session* s, uint32_t addr) {
    uint8_t v = 0;
    pd_session_read(s, addr, &v, 1);
    return v;
}

static void put(struct pd_session* s, uint32_t addr, int v) {
    uint8_t b = (uint8_t) v;
    pk_gb_write(s->core, addr, &b, 1);
}

static bool sig_ok(const struct pd_gb_sig* sig, const uint8_t* rom, size_t size) {
    size_t n = strlen(sig->hex) / 2;
    if (sig->off + n > size) return false;
    for (size_t i = 0; i < n; i++) {
        unsigned v = 0;
        if (sscanf(sig->hex + 2 * i, "%2x", &v) != 1 || rom[sig->off + i] != v) return false;
    }
    return true;
}

bool pd_gbf_ok(const struct pd_gb_field* f, const uint8_t* rom, size_t size) {
    if (!f || !rom) return false;
    const struct pd_gb_sig* sigs = f->crystal ? CRYSTAL_SIGS : f->sigs;
    int n = f->crystal ? (int) (sizeof(CRYSTAL_SIGS) / sizeof(*CRYSTAL_SIGS)) : f->sigCount;
    for (int i = 0; i < n; i++)
        if (!sig_ok(&sigs[i], rom, size)) return false;
    return true;
}

static bool free_gen1(struct pd_session* s, const struct pd_gb_field* f) {
    return u8(s, f->walkCounter) == 0 && (u8(s, f->walkCounter - 1) & 1) == 0 && u8(s, JOY_IGNORE) == 0 && (u8(s, f->flags5) & 0xA0) == 0 &&
        u8(s, f->inBattle) == 0 && (u8(s, f->flags6) & 0x1C) == 0;
}

static bool free_crystal(struct pd_session* s) {
    return u8(s, C_SCRIPT_RUNNING) == 0 && u8(s, C_MAP_EVENT_STATUS) == 0 && u8(s, C_BATTLE_MODE) == 0 && u8(s, C_QUEUE) == 0;
}

static void queue(struct pd_session* s, int bank, int addr) {
    put(s, C_QUEUE + 1, bank);
    put(s, C_QUEUE + 2, addr & 0xFF);
    put(s, C_QUEUE + 3, addr >> 8);
    put(s, C_QUEUE, 1);
}

bool pd_gbf_open_pc(struct pd_session* s, const struct pd_gb_field* f) {
    if (f->crystal) {
        if (!free_crystal(s)) return false;
        queue(s, C_PC_BANK, C_PC_SCRIPT);
        return true;
    }
    if (!free_gen1(s, f)) return false;
    return pk_gb_call_at(s->core, f->loopAfterWait, f->bankswitch, 0, f->pcBank, f->pcRoutine, MAX_HALTS);
}

static int flyer(struct pd_session* s, const struct pd_gb_field* f) {
    int n = u8(s, f->crystal ? C_PARTY_COUNT : f->partyCount);
    if (n > 6) n = 6;
    for (int i = 0; i < n; i++) {
        if (f->crystal && u8(s, C_PARTY_COUNT + 1 + (uint32_t) i) == C_EGG) continue;
        uint32_t moves = f->crystal ? C_PARTY_MOVES + (uint32_t) (i * C_MON) : f->partyMoves + (uint32_t) (i * GEN1_MON);
        for (int m = 0; m < 4; m++)
            if (u8(s, moves + (uint32_t) m) == FLY_MOVE) return i;
    }
    return -1;
}

static bool visited(struct pd_session* s, int spawn) {
    return u8(s, C_VISITED_SPAWNS + (uint32_t) spawn / 8) >> (spawn % 8) & 1;
}

// The Fly map's points the game would show: (landmark, spawn), Johto's, or Kanto's from a Kanto landmark once Indigo Plateau is visited.
static int shown_points(struct pd_session* s, int (*points)[2]) {
    const uint8_t* raw = s->rom + C_FLYPOINTS;
    int all = 0;
    while (all < 32 && raw[2 * all] != 0xFF && raw[2 * all] != 0) all++;
    int first = 0, last = all < C_KANTO_FLYPOINT ? all : C_KANTO_FLYPOINT;
    if (s->shown.mapsec >= C_KANTO_LANDMARK && visited(s, C_SPAWN_INDIGO)) {
        first = C_KANTO_FLYPOINT;
        last = all;
    }
    int n = 0;
    for (int i = first; i < last; i++) {
        points[n][0] = raw[2 * i];
        points[n][1] = raw[2 * i + 1];
        n++;
    }
    return n;
}

void pd_gbf_fly_targets(struct pd_session* s, const struct pd_gb_field* f, struct fly_targets* out) {
    memset(out, 0, sizeof(*out));
    if (f->crystal) {
        if (!free_crystal(s)) out->result = FLY_NOT_NOW;
        else if (!(u8(s, C_JOHTO_BADGES) & 0x20)) out->result = FLY_NO_BADGE;
        else if (u8(s, C_ENVIRONMENT) < 1 || u8(s, C_ENVIRONMENT) > 2) out->result = FLY_CANT_FLY_HERE;
        else if ((out->flyerSlot = flyer(s, f)) < 0) out->result = FLY_NO_FLYER;
        else {
            int points[32][2], n = shown_points(s, points);
            for (int i = 0; i < n && out->count < FLY_MAX_TOWNS; i++)
                if (visited(s, points[i][1])) out->towns[out->count++] = points[i][0];
            out->result = FLY_OK;
        }
        return;
    }
    int tileset = u8(s, f->tileset);
    if (!free_gen1(s, f)) out->result = FLY_NOT_NOW;
    else if (!(u8(s, f->badges) & 0x04)) out->result = FLY_NO_BADGE;
    else if (tileset != 0 && tileset != 23) out->result = FLY_CANT_FLY_HERE;
    else if ((out->flyerSlot = flyer(s, f)) < 0) out->result = FLY_NO_FLYER;
    else {
        int v = u8(s, f->townsVisited) | u8(s, f->townsVisited + 1) << 8;
        for (int i = 0; i < NUM_CITY_MAPS; i++)
            if (v >> i & 1) out->towns[out->count++] = i;
        out->result = FLY_OK;
    }
}

static int pikachuFlags, pikachuImage;

enum fly_result pd_gbf_fly(struct pd_session* s, const struct pd_gb_field* f, int mapsec) {
    struct fly_targets now;
    pd_gbf_fly_targets(s, f, &now);
    if (now.result != FLY_OK) return now.result;
    if (f->crystal) {
        int points[32][2], n = shown_points(s, points);
        for (int i = 0; i < n; i++) {
            if (points[i][0] != mapsec || !visited(s, points[i][1])) continue;
            put(s, C_DEFAULT_SPAWN, points[i][1]);
            queue(s, C_FLY_BANK, C_FLY_SCRIPT);
            return FLY_OK;
        }
        return FLY_FAILED;
    }
    bool known = false;
    for (int i = 0; i < now.count; i++) known |= now.towns[i] == mapsec;
    if (!known) return FLY_FAILED;
    put(s, f->destination, mapsec);
    if (f->pikachuFlags >= 0) {
        pikachuFlags = u8(s, (uint32_t) f->pikachuFlags);
        pikachuImage = u8(s, (uint32_t) f->pikachuImage);
        put(s, (uint32_t) f->pikachuFlags, pikachuFlags | 0x80);
        put(s, (uint32_t) f->pikachuImage, 0xFF);
    }
    put(s, f->flags6 + 1, u8(s, f->flags6 + 1) | 0x80);
    put(s, f->flags6, u8(s, f->flags6) | 0x08);
    return FLY_OK;
}

bool pd_gbf_taken(struct pd_session* s, const struct pd_gb_field* f) {
    if (f->crystal) {
        if (u8(s, C_QUEUE) == 0) return true;
        put(s, C_QUEUE, 0);
        return false;
    }
    int f6 = u8(s, f->flags6);
    if (!(f6 & 0x08) || (f6 & 0x04)) return true;
    put(s, f->flags6, f6 & ~0x08);
    put(s, f->flags6 + 1, u8(s, f->flags6 + 1) & ~0x80);
    if (f->pikachuFlags >= 0) {
        put(s, (uint32_t) f->pikachuFlags, pikachuFlags);
        put(s, (uint32_t) f->pikachuImage, pikachuImage);
    }
    return false;
}

void pd_gbf_hold_wild(struct pd_session* s, const struct pd_gb_field* f) {
    if (f->crystal) {
        if (u8(s, C_COOLDOWN) < 3) put(s, C_COOLDOWN, 5);
        return;
    }
    if (u8(s, f->inBattle)) return;
    if (u8(s, f->grassRate)) put(s, f->grassRate, 0);
    if (u8(s, f->waterRate)) put(s, f->waterRate, 0);
}

bool pd_gbf_release_wild(struct pd_session* s, const struct pd_gb_field* f) {
    if (f->crystal) return true;
    if (u8(s, f->inBattle) || !free_gen1(s, f)) return false;
    if (u8(s, f->grassRate) || u8(s, f->waterRate)) return true;
    int map = u8(s, f->curMap);
    uint32_t bank = (uint32_t) f->wildBank * 0x4000;
    uint32_t at = bank + (uint32_t) (f->wildDataPointers - 0x4000) + 2u * (uint32_t) map;
    if (at + 2 > s->romSize) return false;
    int ptr = s->rom[at] | s->rom[at + 1] << 8;
    if (ptr < 0x4000 || ptr > 0x7FFF) return true;
    uint32_t data = bank + (uint32_t) (ptr - 0x4000);
    if (data + 22 > s->romSize) return false;
    int grass = s->rom[data], water = s->rom[data + (grass ? 21 : 1)];
    if (grass) put(s, f->grassRate, grass);
    if (water) put(s, f->waterRate, water);
    return true;
}
