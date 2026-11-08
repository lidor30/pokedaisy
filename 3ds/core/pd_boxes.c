#include "pd_boxes.h"

#include <string.h>

#include "pd_dex.h"
#include "pd_stats.h"
#include "pd_tables.h"

#define MONS_OFF 4
#define NAME_LEN 9
#define BOX_RECORD 80
#define CFRU_BOXES_IN_PLACE 19
#define CFRU_RECORD 58
#define CFRU_NAMES_OFF 0x8344
#define CFRU_WALLPAPERS_OFF 0x83C2
#define VANILLA_BOXES 14
#define SPECIES_SHEDINJA 303

static uint16_t u16le(const uint8_t* p) {
    return (uint16_t) (p[0] | p[1] << 8);
}

static uint32_t u32le(const uint8_t* p) {
    return (uint32_t) p[0] | (uint32_t) p[1] << 8 | (uint32_t) p[2] << 16 | (uint32_t) p[3] << 24;
}

// The app's PcStorageLayouts (NativeReader.kt), by the names 3ds.toml gives them.
#define CFRU_PC(moves) { .format = PD_BOX_CFRU, .boxes = 25, .record = CFRU_RECORD, .cfruStorage = 0x02029314u, .cfruMoves = (moves), \
    .cfruExtra = { 0x0203CB44u, 0x0203CB44u + 30 * 58, 0x0203CB44u + 60 * 58, 0x02027434u, 0x02027434u + 30 * 58, 0x02024638u } }
const struct pd_pc_storage pd_pc_vanilla = { .format = PD_BOX_ENCRYPTED, .boxes = VANILLA_BOXES, .record = BOX_RECORD };
const struct pd_pc_storage pd_pc_static = { .format = PD_BOX_ENCRYPTED, .boxes = VANILLA_BOXES, .record = BOX_RECORD, .direct = true };
const struct pd_pc_storage pd_pc_plain = { .format = PD_BOX_PLAIN, .boxes = VANILLA_BOXES, .record = BOX_RECORD };
const struct pd_pc_storage pd_pc_soulgold = { .format = PD_BOX_PLAIN, .boxes = 15, .record = 0x4C };
const struct pd_pc_storage pd_pc_unbound = CFRU_PC(0x08A769AFu), pd_pc_radical_red = CFRU_PC(0x091521D0u),
    pd_pc_amethyst = CFRU_PC(0x089B6358u), pd_pc_amethyst_v141 = CFRU_PC(0x089FFCA8u), pd_pc_sword_shield = CFRU_PC(0x094BFA7Cu),
    pd_pc_gs_chronicles = CFRU_PC(0x09D10BE3u);

bool pd_boxes_supported(const struct pd_game* g) {
    const struct pd_config* c = g->cfg;
    const struct pd_party_layout* l = c ? c->partyLayout : NULL;
    return c && c->pcStorage && c->pcStorage->boxes > 0 && c->pokemonStoragePtr != 0 && !(l && (l->rowe || l->eliteRedux));
}

// CalculateMonStats, from the ROM's base stats (gBaseStats: HP, ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF).
static void work_out_stats(const struct pd_game* g, struct pd_mon* m) {
    const struct pd_config* c = g->cfg;
    struct pd_mon_stats* st = &m->stats;
    uint8_t base[6];
    st->valid = false;
    if (!c->speciesInfo || m->egg || !pd_rom_read(g, c->speciesInfo + (uint32_t) m->species * 28, base, 6)) return;
    int up = pd_nature_raises(st->nature), down = pd_nature_lowers(st->nature);
    for (int s = 0; s < 6; s++) {
        int n = (2 * base[s] + st->ivs[s] + st->evs[s] / 4) * m->level / 100;
        if (s == 0) {
            st->values[0] = m->species == SPECIES_SHEDINJA ? 1 : n + m->level + 10;
        } else {
            int v = n + 5;
            if (s == up) v = v * 110 / 100;
            if (s == down) v = v * 90 / 100;
            st->values[s] = v;
        }
    }
    m->hp = m->maxHp = st->values[0];
    st->valid = st->hasIvs = st->hasEvs = st->hasNature = st->hasHiddenPower = true;
    st->hiddenPowerType = pd_hidden_power_type(st);
    st->hiddenPowerPower = pd_hidden_power_power(st);
}

static void finish(const struct pd_game* g, struct pd_mon* m) {
    int rate = pd_growth_rate(g, m->egg ? 0 : m->species);
    int level = 1;
    if (rate >= 0)
        while (level < 100 && (long) m->experience >= pd_exp_at(rate, level + 1)) level++;
    m->level = level;
    m->status = 0;
    work_out_stats(g, m);
}

static bool decode_cfru(const struct pd_game* g, const uint8_t* r, struct pd_mon* m) {
    memset(m, 0, sizeof(*m));
    int species = u16le(r + 0x1C);
    if (!species || species > 4000) return false;
    m->species = species;
    m->personality = u32le(r);
    m->heldItem = u16le(r + 0x1E);
    m->experience = u32le(r + 0x20);
    m->hasExperience = m->hasHeldItem = true;
    uint64_t packed = 0;
    for (int i = 0; i < 5; i++) packed |= (uint64_t) r[0x27 + i] << (8 * i);
    int bonuses = r[0x24];
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        m->moves[i] = (int) (packed >> (10 * i) & 0x3FF);
        uint8_t base = 0;
        if (m->moves[i]) pd_rom_read(g, g->cfg->pcStorage->cfruMoves + 12u * (uint32_t) m->moves[i] + 4, &base, 1);
        m->pp[i] = base + base * 20 * (bonuses >> (2 * i) & 3) / 100;
    }
    for (int i = 0; i < 6; i++) m->stats.evs[i] = r[0x2C + i];
    uint32_t iv = u32le(r + 0x36);
    for (int i = 0; i < 6; i++) m->stats.ivs[i] = (uint8_t) (iv >> (i * 5) & 31);
    m->egg = iv >> 30 & 1;
    m->stats.nature = (int) (m->personality % 25);
    pd_gen3_text(r + 8, 10, m->nickname, sizeof(m->nickname));
    if (m->egg) m->species = PD_SPECIES_EGG;
    return true;
}

void pd_boxes_read(struct pd_boxes* b, const struct pd_game* g, pd_read_fn read, void* ctx, int box) {
    const struct pd_config* c = g->cfg;
    b->ok = false;
    if (!pd_boxes_supported(g)) return;
    const struct pd_pc_storage* pc = c->pcStorage;
    uint32_t at = c->pokemonStoragePtr;
    if (!pc->direct) {
        uint8_t p[4];
        if (!read(ctx, c->pokemonStoragePtr, p, 4)) return;
        at = u32le(p);
    }
    if (at < 0x02000000 || at >= 0x04000000) return;
    if (pc->cfruStorage && at != pc->cfruStorage) return;
    bool cfru = pc->format == PD_BOX_CFRU;
    int rec = pc->record;
    b->boxCount = pc->boxes < PD_MAX_BOXES ? pc->boxes : PD_MAX_BOXES;
    if (box < 0 || box >= b->boxCount) box = 0;
    b->shown = box;
    uint32_t namesOff = MONS_OFF + (uint32_t) (pc->boxes * PD_BOX_SLOTS * rec);
    static uint8_t raw[PD_BOX_SLOTS * BOX_RECORD];
    for (int i = 0; i < b->boxCount; i++) {
        uint32_t name, wall, first;
        if (!cfru) {
            name = namesOff + (uint32_t) (i * NAME_LEN);
            wall = namesOff + (uint32_t) (pc->boxes * NAME_LEN + i);
            first = at + MONS_OFF + (uint32_t) (i * PD_BOX_SLOTS * rec);
        } else {
            name = i < VANILLA_BOXES ? CFRU_NAMES_OFF + (uint32_t) (i * NAME_LEN) : CFRU_NAMES_OFF - (uint32_t) ((i - VANILLA_BOXES + 1) * NAME_LEN);
            wall = i < VANILLA_BOXES ? CFRU_WALLPAPERS_OFF + (uint32_t) i :
                MONS_OFF + (uint32_t) (CFRU_BOXES_IN_PLACE * PD_BOX_SLOTS * rec + i - VANILLA_BOXES);
            first = i < CFRU_BOXES_IN_PLACE ? at + MONS_OFF + (uint32_t) (i * PD_BOX_SLOTS * rec) : pc->cfruExtra[i - CFRU_BOXES_IN_PLACE];
        }
        uint8_t text[NAME_LEN];
        if (!read(ctx, at + name, text, NAME_LEN) || !read(ctx, at + wall, &b->wallpaper[i], 1)) return;
        pd_gen3_text(text, NAME_LEN, b->names[i], sizeof(b->names[i]));
        if (!read(ctx, first, raw, (size_t) (PD_BOX_SLOTS * rec))) return;
        int count = 0;
        memset(i == box ? b->has : (bool[PD_BOX_SLOTS]) { 0 }, 0, sizeof(b->has));
        for (int s = 0; s < PD_BOX_SLOTS; s++) {
            const uint8_t* r = raw + s * rec;
            bool taken = cfru ? u16le(r + 0x1C) != 0 : u32le(r) != 0 || u32le(r + 4) != 0;
            if (!taken) continue;
            count++;
            if (i != box) continue;
            struct pd_mon* m = &b->mons[s];
            if (cfru) {
                if (!decode_cfru(g, r, m)) continue;
            } else {
                // The record is a struct Pokemon's first part: the party fields read as nothing.
                static uint8_t mon[128];
                memset(mon, 0, sizeof(mon));
                memcpy(mon, r, (size_t) rec);
                if (!pd_decode_party_mon_for(c, mon, m)) continue;
            }
            finish(g, m);
            b->has[s] = true;
        }
        b->count[i] = (uint8_t) count;
    }
    b->ok = true;
}
