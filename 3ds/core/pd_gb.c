#include "pd_gb.h"

#include <stdio.h>
#include <string.h>

#include "pd_games.h"

#define GEN1_MON 44
#define GEN1_BATTLE_MON 29
#define GEN2_MON 48
#define GEN2_BATTLE_MON 32
#define POLISHED_BATTLE_MON 35
#define NAME_LEN 11
#define GEN2_EGG 0xFD
#define GEN2_TMS_HMS 57
#define POLISHED_NUM_SPECIES 0x123
#define TILE_CURSOR 0xED // ▶, a menu waiting for input

#define GEN1_BATTLE_MENU_TOP 0x0E
#define GEN1_MOVE_MENU_TOP 0x0C
#define GEN1_MOVE_MENU_X 0x05
#define GEN1_SUBMENU_TOP 0x0C
#define GEN2_BATTLE_MENU_TOP 0x0E
#define GEN2_BATTLE_MENU_LEFT 0x09
#define GEN2_MOVE_MENU_TOP 0x0D
#define GEN2_MOVE_MENU_LEFT 0x05
#define GEN2_CHOOSE_POKEMON 0
#define GEN2_SWITCH 2
#define GEN2_SUBMENU_TOP 0x0C
#define POLISHED_SUBMENU_LEFT 0x0B

const struct pd_config pd_gb_blank_config = {
    .monStride = 100,
    .bagPocketCount = 5,
    .bagOrder = { PD_POCKET_ITEMS, PD_POCKET_BALLS, PD_POCKET_KEY, PD_POCKET_TMHM, PD_POCKET_BERRIES },
};

static int u8(const uint8_t* b, int o) { return b[o]; }
static int be16(const uint8_t* b, int o) { return (b[o] << 8) | b[o + 1]; }

static int read8(pd_read_fn read, void* ctx, uint32_t addr) {
    uint8_t v = 0;
    read(ctx, addr, &v, 1);
    return v;
}

// gbMonKey folded into the 32 bits a party read carries as its personality: species, OT ID and DVs, none of which change
// in a battle. Never 0 for a real Pokémon.
static uint32_t mon_key(int species, int otId, int dvs) {
    uint32_t k = ((uint32_t) otId << 16 | (uint32_t) dvs) ^ ((uint32_t) species * 0x9E3779B1u);
    return k ? k : 1;
}

static int gen1_dex(const struct pd_gb_config* c, int internal) {
    return internal < c->internalCount ? c->internalToDex[internal] : 0;
}

static bool decode_gen1(const struct pd_gb_config* c, const uint8_t* m, struct pd_mon* out) {
    int species = gen1_dex(c, m[0]);
    int level = m[33];
    if (!species || level < 1 || level > 100) return false;
    memset(out, 0, sizeof(*out));
    out->species = species;
    out->level = level;
    out->hp = be16(m, 1);
    out->maxHp = be16(m, 34);
    out->status = m[4];
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        out->moves[i] = m[8 + i];
        out->pp[i] = m[29 + i] & 0x3F;
    }
    out->exp = (uint32_t) m[14] << 16 | (uint32_t) m[15] << 8 | m[16];
    out->gender = PD_GENDER_NONE;
    out->personality = mon_key(m[0], be16(m, 12), be16(m, 27));
    return true;
}

static enum pd_gender gen2_gender(const struct pd_gb_config* c, int species, int dvs0, int dvs1) {
    if (species >= c->genderCount) return PD_GENDER_NONE;
    int r = c->genderRatios[species];
    if (r == 255) return PD_GENDER_NONE;
    if (r == 0) return PD_GENDER_MALE;
    if (r == 254) return PD_GENDER_FEMALE;
    return r < ((dvs0 & 0xF0) | (dvs1 >> 4)) ? PD_GENDER_MALE : PD_GENDER_FEMALE;
}

static bool decode_gen2(const struct pd_gb_config* c, const uint8_t* m, bool egg, struct pd_mon* out) {
    int species = m[0];
    int level = m[31];
    if (species < 1 || species > c->speciesCount || level < 1 || level > 100) return false;
    memset(out, 0, sizeof(*out));
    out->species = egg ? GEN2_EGG : species;
    out->egg = egg;
    out->level = level;
    out->hp = be16(m, 34);
    out->maxHp = be16(m, 36);
    out->status = m[32];
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        out->moves[i] = m[2 + i];
        out->pp[i] = m[23 + i] & 0x3F;
    }
    out->exp = (uint32_t) m[8] << 16 | (uint32_t) m[9] << 8 | m[10];
    out->gender = egg ? PD_GENDER_NONE : gen2_gender(c, species, m[21], m[22]);
    out->heldItem = m[1];
    out->personality = mon_key(species, be16(m, 6), be16(m, 21));
    return true;
}

// polishedSpeciesId: a species' 9-bit value, or POLISHED_NUM_SPECIES + 1 + k for a variant form.
static int polished_species(const struct pd_gb_config* c, int species, int form) {
    int s9 = species | ((form & 0x20) ? 0x100 : 0);
    uint32_t want = (uint32_t) s9 << 8 | (uint32_t) (form & 0x1F);
    for (int k = 0; k < c->formCount; k++)
        if (c->forms[k] == want) return POLISHED_NUM_SPECIES + 1 + k;
    return s9;
}

static bool decode_polished(const struct pd_gb_config* c, const uint8_t* m, struct pd_mon* out) {
    int species = m[0];
    if (species == 0 || species == 0xFF) return false;
    int form = m[21];
    int id = polished_species(c, species, form);
    if (id < 1 || id >= c->genderCount) return false;
    int level = m[31];
    if (level < 1 || level > 100) return false;
    bool egg = form & 0x40;
    memset(out, 0, sizeof(*out));
    int ratio = c->genderRatios[id];
    enum pd_gender gender = ratio == 255 ? PD_GENDER_NONE : ratio == 0 ? PD_GENDER_MALE : ratio == 254 ? PD_GENDER_FEMALE :
        (form & 0x80) ? PD_GENDER_FEMALE : PD_GENDER_MALE;
    out->species = egg ? 0xFF : id;
    out->egg = egg;
    out->level = level;
    out->hp = be16(m, 34);
    out->maxHp = be16(m, 36);
    out->status = m[32];
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        out->moves[i] = m[2 + i];
        out->pp[i] = m[22 + i] & 0x3F;
    }
    out->exp = (uint32_t) m[8] << 16 | (uint32_t) m[9] << 8 | m[10];
    out->gender = egg ? PD_GENDER_NONE : gender;
    out->heldItem = m[1];
    out->personality = mon_key(id, be16(m, 6), be16(m, 17));
    return true;
}

static void battler_gen1(const struct pd_gb_config* c, const uint8_t* b, struct pd_battle_mon* out) {
    int species = gen1_dex(c, b[0]);
    if (!species) return;
    out->species = species;
    out->level = b[14];
    out->type1 = b[5];
    out->type2 = b[6];
    out->hp = be16(b, 1);
    out->maxHp = be16(b, 15);
    out->status = b[4];
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        out->moves[i] = b[8 + i];
        out->pp[i] = b[25 + i] & 0x3F;
    }
}

static void battler_gen2(const struct pd_gb_config* c, const uint8_t* b, struct pd_battle_mon* out) {
    if (b[0] < 1 || b[0] > c->speciesCount) return;
    out->species = b[0];
    out->level = b[13];
    out->type1 = b[30];
    out->type2 = b[31];
    out->hp = be16(b, 16);
    out->maxHp = be16(b, 18);
    out->status = b[14];
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        out->moves[i] = b[2 + i];
        out->pp[i] = b[8 + i] & 0x3F;
    }
}

static void battler_polished(const struct pd_gb_config* c, const uint8_t* b, struct pd_battle_mon* out) {
    if (b[0] == 0 || b[0] == 0xFF || b[16] < 1 || b[16] > 100) return;
    out->species = polished_species(c, b[0], b[10]);
    out->level = b[16];
    out->type1 = b[33];
    out->type2 = b[34];
    out->hp = be16(b, 19);
    out->maxHp = be16(b, 21);
    out->status = b[17];
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        out->moves[i] = b[2 + i];
        out->pp[i] = b[11 + i] & 0x3F;
    }
}

static void nickname(const struct pd_gb_config* c, const uint8_t* raw, char* out, size_t len) {
    size_t n = 0;
    for (int i = 0; i < NAME_LEN; i++) {
        int ch = raw[i];
        if (ch == 0 || ch == 0x50 || (c->polished && (ch == 0x52 || ch == 0x53))) break;
        const char* t = c->chars[ch];
        size_t l = strlen(t);
        if (n + l + 1 > len) break;
        memcpy(out + n, t, l);
        n += l;
    }
    while (n && out[n - 1] == ' ') n--;
    out[n] = 0;
}

static void add_item(struct pd_pocket_items* p, int id, int qty) {
    if (p->count >= PD_POCKET_SLOTS) return;
    p->items[p->count].id = (uint16_t) id;
    p->items[p->count].quantity = (uint16_t) qty;
    p->count++;
}

// An {item, quantity} list, 0xFF right after its count: false for anything else (WRAM before a save is loaded).
static bool read_pairs(pd_read_fn read, void* ctx, uint32_t addr, int max, struct pd_pocket_items* p) {
    uint8_t raw[2 + 2 * 60];
    if (max > 60 || !read(ctx, addr, raw, (size_t) (2 + 2 * max))) return false;
    int n = raw[0];
    if (n > max || raw[1 + 2 * n] != 0xFF) return false;
    for (int i = 0; i < n; i++)
        if (raw[1 + 2 * i] >= 1 && raw[1 + 2 * i] <= 0xFE) add_item(p, raw[1 + 2 * i], raw[2 + 2 * i]);
    return true;
}

static void read_tm_bits(const struct pd_gb_config* c, pd_read_fn read, void* ctx, struct pd_pocket_items* p) {
    uint8_t bits[32] = { 0 };
    read(ctx, c->tmsHms, bits, (size_t) (c->tmCount + 7) / 8);
    for (int i = 0; i < c->tmCount; i++)
        if (bits[i / 8] >> (i % 8) & 1) add_item(p, c->tmItems[i], 1);
}

static bool read_gen2_bag(const struct pd_gb_config* c, pd_read_fn read, void* ctx, struct pd_snapshot* s) {
    struct pd_pocket_items* items = &s->bag[PD_POCKET_ITEMS];
    struct pd_pocket_items* balls = &s->bag[PD_POCKET_BALLS];
    struct pd_pocket_items* keys = &s->bag[PD_POCKET_KEY];
    struct pd_pocket_items* tms = &s->bag[PD_POCKET_TMHM];
    if (!read_pairs(read, ctx, c->numItems, c->itemsMax, items)) return false;
    if (!read_pairs(read, ctx, c->numBalls, c->ballsMax, balls)) return false;
    if (c->polished) {
        if (!read_pairs(read, ctx, c->numMedicine, c->medicineMax, items)) return false;
        if (!read_pairs(read, ctx, c->numBerries, c->berriesMax, &s->bag[PD_POCKET_BERRIES])) return false;
        uint8_t raw[64];
        int max = c->keyItemsMax < 63 ? c->keyItemsMax : 63;
        read(ctx, c->numKeyItems, raw, (size_t) max + 1);
        for (int i = 0; i <= max && raw[i]; i++) add_item(keys, 0x200 + raw[i], 1);
        read_tm_bits(c, read, ctx, tms);
        return true;
    }
    uint8_t raw[64];
    int max = c->keyItemsMax < 62 ? c->keyItemsMax : 62;
    read(ctx, c->numKeyItems, raw, (size_t) max + 2);
    int n = raw[0];
    if (n > max || raw[1 + n] != 0xFF) return false;
    for (int i = 0; i < n; i++)
        if (raw[1 + i] >= 1 && raw[1 + i] <= 0xFE) add_item(keys, raw[1 + i], 1);
    if (c->tmBits) {
        read_tm_bits(c, read, ctx, tms);
        return true;
    }
    uint8_t tm[GEN2_TMS_HMS];
    read(ctx, c->tmsHms, tm, sizeof(tm));
    for (int i = 0; i < GEN2_TMS_HMS; i++)
        if (tm[i] > 99) return false;
    for (int i = 0; i < GEN2_TMS_HMS && i < c->tmCount; i++)
        if (tm[i]) add_item(tms, c->tmItems[i], tm[i]);
    return true;
}

static int landmark_of(const struct pd_gb_config* c, int key) {
    int lo = 0, hi = c->landmarkCount - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        int k = c->landmarks[mid].map;
        if (k == key) return c->landmarks[mid].landmark;
        if (k < key) lo = mid + 1;
        else hi = mid - 1;
    }
    return 0;
}

static void read_dex(const struct pd_gb_config* c, pd_read_fn read, void* ctx, struct pd_snapshot* s) {
    if (!c->dexCaught || !c->dexSeen) return;
    int bytes = c->gen == 1 ? 19 : 32;
    uint8_t seen[PD_DEX_BYTES] = { 0 };
    read(ctx, c->dexCaught, s->dexCaught, (size_t) bytes);
    read(ctx, c->dexSeen, seen, (size_t) bytes);
    for (int i = 0; i < bytes; i++) s->dexSeen[i] = seen[i] | s->dexCaught[i];
    s->dexOk = true;
    s->dexNational = true;
}

static void read_gen1(struct pd_snapshot* s, const struct pd_gb_config* c, pd_read_fn read, void* ctx) {
    static uint8_t raw[6 * GEN1_MON], names[6 * NAME_LEN];
    int count = read8(read, ctx, c->partyCount);
    if (count > PD_PARTY_SIZE) count = PD_PARTY_SIZE;
    read(ctx, c->partyMons, raw, sizeof(raw));
    read(ctx, c->partyMons + 6 * GEN1_MON + 6 * NAME_LEN, names, sizeof(names));
    for (int i = 0; i < count; i++) {
        struct pd_mon* m = &s->party[s->partyCount];
        if (!decode_gen1(c, raw + i * GEN1_MON, m)) continue;
        nickname(c, names + i * NAME_LEN, m->nickname, sizeof(m->nickname));
        s->partyCount++;
    }
    struct pd_pocket_items* bag = &s->bag[PD_POCKET_ITEMS];
    uint8_t items[1 + 2 * 60];
    int slots = c->bagSlots < 60 ? c->bagSlots : 60;
    read(ctx, c->bagCount, items, (size_t) (1 + 2 * slots));
    int n = items[0] < slots ? items[0] : slots;
    for (int i = 0; i < n && items[1 + 2 * i] != 0xFF; i++) add_item(bag, items[1 + 2 * i], items[2 + 2 * i]);
    s->bagOk = true;

    int battle = read8(read, ctx, c->battleMode);
    s->inBattle = battle == 1 || battle == 2;
    if (s->inBattle) {
        uint8_t b[GEN1_BATTLE_MON];
        read(ctx, c->battleMon, b, sizeof(b));
        battler_gen1(c, b, &s->battlers[PD_POS_PLAYER_LEFT]);
        read(ctx, c->enemyMon, b, sizeof(b));
        battler_gen1(c, b, &s->battlers[PD_POS_OPPONENT_LEFT]);
        if (battle == 2) {
            s->isTrainer = true;
            int foes = read8(read, ctx, c->otPartyCount);
            if (foes > PD_PARTY_SIZE) foes = PD_PARTY_SIZE;
            read(ctx, c->otPartyMons, raw, sizeof(raw));
            for (int i = 0; i < foes; i++)
                if (decode_gen1(c, raw + i * GEN1_MON, &s->foes[s->foeCount])) s->foeCount++;
            int active = read8(read, ctx, c->otActive);
            s->foeActive = active < s->foeCount ? active : -1;
        }
    }
    int map = read8(read, ctx, c->curMap);
    s->mapNum = map;
    s->mapsec = map;
    s->x = read8(read, ctx, c->xCoord);
    s->y = read8(read, ctx, c->yCoord);
    s->posOk = true;
    uint8_t m[3];
    read(ctx, c->money, m, 3);
    long money = 0;
    for (int i = 0; i < 3; i++) money = money * 100 + (m[i] >> 4 & 0xF) * 10 + (m[i] & 0xF);
    s->money = money;
    s->gender = 0;
    if (c->gameTime) {
        uint8_t t[5];
        read(ctx, c->gameTime, t, 5);
        s->frame = (uint32_t) (((t[0] * 60u + t[2]) * 60u + t[3]) * 60u + t[4]);
        s->playHours = t[0];
        s->playMinutes = t[2];
    }
}

static void read_gen2(struct pd_snapshot* s, const struct pd_gb_config* c, pd_read_fn read, void* ctx) {
    static uint8_t raw[6 * GEN2_MON], names[6 * NAME_LEN];
    uint8_t countRaw[1 + PD_PARTY_SIZE];
    read(ctx, c->partyCount, countRaw, sizeof(countRaw));
    // wPartySpecies ends in 0xFF after the count: anything else is WRAM before a save is loaded.
    int count = countRaw[0];
    if (count > PD_PARTY_SIZE || (!c->polished && count != PD_PARTY_SIZE && read8(read, ctx, c->partyCount + 1 + (uint32_t) count) != 0xFF))
        count = 0;
    read(ctx, c->partyMons, raw, sizeof(raw));
    read(ctx, c->partyMons + 6 * GEN2_MON + 6 * NAME_LEN, names, sizeof(names));
    for (int i = 0; i < count; i++) {
        struct pd_mon* m = &s->party[s->partyCount];
        bool ok = c->polished ? decode_polished(c, raw + i * GEN2_MON, m) :
            decode_gen2(c, raw + i * GEN2_MON, countRaw[1 + i] == GEN2_EGG, m);
        if (!ok) continue;
        nickname(c, names + i * NAME_LEN, m->nickname, sizeof(m->nickname));
        s->partyCount++;
    }
    s->bagOk = read_gen2_bag(c, read, ctx, s);
    if (!s->bagOk) memset(s->bag, 0, sizeof(s->bag));

    int battle = read8(read, ctx, c->battleMode);
    s->inBattle = battle == 1 || battle == 2;
    if (s->inBattle) {
        uint8_t b[POLISHED_BATTLE_MON];
        size_t n = c->polished ? POLISHED_BATTLE_MON : GEN2_BATTLE_MON;
        read(ctx, c->battleMon, b, n);
        if (c->polished) battler_polished(c, b, &s->battlers[PD_POS_PLAYER_LEFT]);
        else battler_gen2(c, b, &s->battlers[PD_POS_PLAYER_LEFT]);
        read(ctx, c->enemyMon, b, n);
        if (c->polished) battler_polished(c, b, &s->battlers[PD_POS_OPPONENT_LEFT]);
        else battler_gen2(c, b, &s->battlers[PD_POS_OPPONENT_LEFT]);
        if (battle == 2) {
            s->isTrainer = true;
            int foes = read8(read, ctx, c->otPartyCount);
            if (foes > PD_PARTY_SIZE) foes = PD_PARTY_SIZE;
            read(ctx, c->otPartyMons, raw, sizeof(raw));
            for (int i = 0; i < foes; i++) {
                bool ok = c->polished ? decode_polished(c, raw + i * GEN2_MON, &s->foes[s->foeCount]) :
                    decode_gen2(c, raw + i * GEN2_MON, false, &s->foes[s->foeCount]);
                if (ok) s->foeCount++;
            }
            int active = read8(read, ctx, c->otActive);
            s->foeActive = active < s->foeCount ? active : -1;
        }
    }
    uint8_t map[4], backup[2], t[5], m[3];
    read(ctx, c->mapGroup, map, 2);
    if (c->coords != 0xFFFFFFFFu) read(ctx, c->coords, map + 2, 2);
    else read(ctx, c->mapGroup + 2, map + 2, 2);
    read(ctx, c->backupMapGroup, backup, 2);
    s->mapGroup = map[0];
    s->mapNum = map[1];
    s->y = map[2];
    s->x = map[3];
    s->posOk = true;
    int lm = landmark_of(c, map[0] * 256 + map[1]);
    s->mapsec = lm ? lm : landmark_of(c, backup[0] * 256 + backup[1]);
    read(ctx, c->gameTime, t, 5);
    s->frame = (uint32_t) (((be16(t, 0) * 60u + t[2]) * 60u + t[3]) * 60u + t[4]);
    s->playHours = (uint16_t) be16(t, 0);
    s->playMinutes = t[2];
    read(ctx, c->money, m, 3);
    s->money = (long) m[0] << 16 | (long) m[1] << 8 | m[2];
    s->gender = c->playerGender == 0xFFFFFFFFu ? 0 : read8(read, ctx, c->playerGender) & 1;
}

void pd_gb_snapshot_read(struct pd_snapshot* s, const struct pd_game* g, pd_read_fn read, void* ctx) {
    const struct pd_gb_config* c = g->gb;
    memset(s, 0, sizeof(*s));
    s->money = -1;
    s->gender = -1;
    s->foeActive = -1;
    s->foeNext = -1;
    if (c->gen == 1) read_gen1(s, c, read, ctx);
    else read_gen2(s, c, read, ctx);
    read_dex(c, read, ctx, s);
    int cursor;
    s->battleState = s->inBattle ? pd_gb_battle_menu(c, read, ctx, &cursor) : PD_BATTLE_NONE;
    s->valid = true;
}

static bool cursor_shown(const struct pd_gb_config* c, pd_read_fn read, void* ctx, int row, int x, int tile) {
    return row >= 0 && row < 18 && x >= 0 && x < 20 && read8(read, ctx, c->tileMap + (uint32_t) (row * 20 + x)) == tile;
}

static enum pd_battle_state gen1_battle_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx, int* cursor) {
    int battle = read8(read, ctx, c->battleMode);
    if (battle != 1 && battle != 2) return PD_BATTLE_NONE;
    struct pd_gb_party_menu p = pd_gb_party_menu(c, read, ctx);
    if (p.open) {
        *cursor = p.cursor;
        return PD_BATTLE_PARTY_OPEN;
    }
    uint8_t m[5];
    read(ctx, c->topMenuItemY, m, 5);
    int top = m[0], x = m[1], item = m[2], max = m[4];
    if (top == GEN1_BATTLE_MENU_TOP && max == 1 && item <= 1 && (x == c->battleMenuLeftX || x == c->battleMenuRightX) &&
        cursor_shown(c, read, ctx, top + 2 * item, x, TILE_CURSOR)) {
        *cursor = (x == c->battleMenuRightX ? 1 : 0) + 2 * item;
        return PD_BATTLE_ACTION;
    }
    if (top == GEN1_MOVE_MENU_TOP && x == GEN1_MOVE_MENU_X && item >= 1 && item <= 4 && read8(read, ctx, c->moveMenuType) == 0 &&
        cursor_shown(c, read, ctx, top + item, x, TILE_CURSOR)) {
        *cursor = item - 1;
        return PD_BATTLE_MOVE;
    }
    return PD_BATTLE_BUSY;
}

static enum pd_battle_state gen2_battle_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx, int* cursor) {
    int battle = read8(read, ctx, c->battleMode);
    if (battle != 1 && battle != 2) return PD_BATTLE_NONE;
    struct pd_gb_party_menu p = pd_gb_party_menu(c, read, ctx);
    if (p.open) {
        *cursor = p.cursor;
        return PD_BATTLE_PARTY_OPEN;
    }
    uint8_t m[7], cur[2];
    read(ctx, c->menu2D, m, 7);
    read(ctx, c->menuCursor, cur, 2);
    int top = m[0], left = m[1], rows = m[2], cols = m[3], spacing = m[6], y = cur[0], x = cur[1];
    if (y < 1 || y > rows || x < 1 || x > cols) return PD_BATTLE_BUSY;
    int tx = left + (x - 1) * (spacing & 0xF), ty = top + (y - 1) * (spacing >> 4);
    if (!cursor_shown(c, read, ctx, ty, tx, c->cursorTile)) return PD_BATTLE_BUSY;
    if (top == GEN2_BATTLE_MENU_TOP && left == GEN2_BATTLE_MENU_LEFT && rows == 2 && cols == 2) {
        *cursor = (x - 1) + 2 * (y - 1);
        return PD_BATTLE_ACTION;
    }
    if (top == GEN2_MOVE_MENU_TOP && left == GEN2_MOVE_MENU_LEFT && cols == 1 && rows >= 1 && rows <= 4 &&
        read8(read, ctx, c->moveMenuType) == 0) {
        *cursor = y - 1;
        return PD_BATTLE_MOVE;
    }
    return PD_BATTLE_BUSY;
}

enum pd_battle_state pd_gb_battle_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx, int* cursor) {
    *cursor = -1;
    return c->gen == 1 ? gen1_battle_menu(c, read, ctx, cursor) : gen2_battle_menu(c, read, ctx, cursor);
}

static struct pd_gb_party_menu gen1_party_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx) {
    struct pd_gb_party_menu r = { 0 };
    if (!c->topMenuItemY || !c->partyMenuType) return r;
    int battle = read8(read, ctx, c->battleMode);
    if (battle != 1 && battle != 2) return r;
    uint8_t m[5];
    read(ctx, c->topMenuItemY, m, 5);
    int top = m[0], x = m[1], item = m[2], max = m[4];
    if (top == GEN1_SUBMENU_TOP && x == GEN1_SUBMENU_TOP && max == 2 && item <= 2 && cursor_shown(c, read, ctx, top + 2 * item, x, TILE_CURSOR)) {
        r.open = r.submenu = true;
        r.cursor = item;
        return r;
    }
    if (top != 1 || x != 0 || item > max) return r;
    int count = read8(read, ctx, c->partyCount);
    int type = read8(read, ctx, c->partyMenuType);
    if (count < 1 || count > PD_PARTY_SIZE || max != count - 1) return r;
    if (type != 0 && type != c->battlePartyMenu) return r; // a Potion's "use on which?"
    if (!cursor_shown(c, read, ctx, top + 2 * item, x, TILE_CURSOR)) return r;
    r.open = true;
    r.cursor = item;
    r.forced = type == c->battlePartyMenu;
    return r;
}

static struct pd_gb_party_menu gen2_party_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx) {
    struct pd_gb_party_menu r = { 0 };
    int battle = read8(read, ctx, c->battleMode);
    if (battle != 1 && battle != 2) return r;
    uint8_t m[7];
    read(ctx, c->menu2D, m, 7);
    int top = m[0], left = m[1], rows = m[2], cols = m[3], spacing = m[6];
    int y = read8(read, ctx, c->menuCursor);
    if (cols != 1 || spacing != 0x20 || y < 1 || y > rows) return r;
    int ty = top + 2 * (y - 1);
    if (ty >= 18 || left >= 20 || !cursor_shown(c, read, ctx, ty, left, c->cursorTile)) return r;
    if (top == GEN2_SUBMENU_TOP && left == (c->polished ? POLISHED_SUBMENU_LEFT : GEN2_SUBMENU_TOP) && rows == 3) {
        r.open = r.submenu = true;
        r.cursor = y - 1;
        return r;
    }
    if (top != 1 || left != 0) return r;
    int count = read8(read, ctx, c->partyCount);
    int action = read8(read, ctx, c->partyMenuAction);
    if (count < 1 || count > PD_PARTY_SIZE || rows != count + 1) return r;
    if (action != GEN2_CHOOSE_POKEMON && action != GEN2_SWITCH) return r;
    r.open = true;
    r.cursor = y == rows ? PD_GB_PARTY_CANCEL : y - 1;
    r.forced = action == GEN2_SWITCH;
    return r;
}

struct pd_gb_party_menu pd_gb_party_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx) {
    return c->gen == 1 ? gen1_party_menu(c, read, ctx) : gen2_party_menu(c, read, ctx);
}

int pd_gb_party(const struct pd_gb_config* c, pd_read_fn read, void* ctx, struct pd_gb_party_mon* out) {
    static uint8_t raw[6 * GEN2_MON];
    int n = read8(read, ctx, c->partyCount);
    if (n > PD_PARTY_SIZE) n = PD_PARTY_SIZE;
    int stride = c->gen == 1 ? GEN1_MON : GEN2_MON;
    read(ctx, c->partyMons, raw, (size_t) (6 * stride));
    for (int i = 0; i < n; i++) {
        const uint8_t* m = raw + i * stride;
        struct pd_mon mon;
        if (c->gen == 1) {
            out[i].key = mon_key(m[0], be16(m, 12), be16(m, 27));
            out[i].hp = be16(m, 1);
        } else {
            if (c->polished) out[i].key = decode_polished(c, m, &mon) ? mon.personality : 0;
            else out[i].key = mon_key(m[0], be16(m, 6), be16(m, 21));
            out[i].hp = be16(m, 34);
        }
    }
    return n;
}

int pd_gb_out_slot(const struct pd_gb_config* c, pd_read_fn read, void* ctx) {
    return read8(read, ctx, c->outSlot);
}

// --- party icons, from the cart ---

static uint32_t rom_off(int bank, int addr) {
    return bank == 0 ? (uint32_t) addr : (uint32_t) bank * 0x4000 + (uint32_t) addr - 0x4000;
}

static int tile_px(const uint8_t* tile, int x, int y) {
    return (tile[2 * y] >> (7 - x) & 1) | (tile[2 * y + 1] >> (7 - x) & 1) << 1;
}

static void put2x(uint32_t* out, int x, int y, uint32_t colour) {
    uint32_t* p = out + 2 * y * 32 + 2 * x;
    p[0] = p[1] = p[32] = p[33] = colour;
}

static bool icon_gen1(const struct pd_game* g, const struct pd_gb_art* a, int dex, uint32_t* out) {
    const uint8_t* rom = g->rom;
    if (dex < 1 || dex > a->dexCount) return false;
    if (a->iconSheet) {
        // PureRGB: an icon's 8 tiles as they are, OAM drawing tiles 0, 1 over 4, 5.
        uint32_t icon = a->iconSheet + 0x80u * rom[a->monPartyData + (uint32_t) dex];
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                int v = tile_px(rom + icon + 16 * ((y / 8) * 4 + x / 8), x % 8, y % 8);
                if (v) put2x(out, x, y, a->iconColors[v - 1]);
            }
        return true;
    }
    // MonPartySpritePointers loads the shapes' tiles into VRAM; a 16 x 16 is a tile column and its mirror image.
    int b = rom[a->monPartyData + (uint32_t) (dex - 1) / 2];
    int type = (dex - 1) % 2 == 0 ? b >> 4 : b & 0xF;
    int base = type << 2;
    bool asymmetric = type == 2; // the helix: 4 tiles, no mirroring
    static const uint8_t* vram[0x100];
    memset(vram, 0, sizeof(vram));
    for (int e = 0; e < a->iconPointerCount; e++) {
        const uint8_t* o = rom + a->iconPointers + 6 * e;
        int ptr = o[0] | o[1] << 8, count = o[2], bank = o[3];
        int dest = ((o[4] | o[5] << 8) - 0x8000) / 16;
        for (int k = 0; k < count && dest + k >= 0 && dest + k < 0x100; k++) vram[dest + k] = rom + rom_off(bank, ptr) + 16 * k;
    }
    for (int y = 0; y < 16; y++)
        for (int x = 0; x < 16; x++) {
            int slot = asymmetric ? base + (y / 8) * 2 + x / 8 : base + (y / 8) * 2;
            int tx = asymmetric ? x % 8 : (x < 8 ? x : 15 - x);
            const uint8_t* tile = vram[slot];
            int v = tile ? tile_px(tile, tx, y % 8) : 0;
            if (v) put2x(out, x, y, a->iconColors[v - 1]);
        }
    return true;
}

// pret's LZ (Gen2Lz); [polished]: its variant's counts. Stops once [max] bytes are out.
static int gen2_lz(const uint8_t* rom, size_t romSize, uint32_t start, uint8_t* out, int max, bool polished) {
    int n = 0;
    uint32_t p = start;
    while (n < max && p < romSize) {
        int b = rom[p++];
        if (b == 0xFF) break;
        int cmd = b >> 5, length;
        if (cmd == 7) {
            cmd = b >> 2 & 7;
            length = (((b & (polished ? 1 : 3)) << 8) | rom[p++]) + 1;
        } else {
            length = (b & 0x1F) + 1;
        }
        switch (cmd) {
        case 0:
            for (int i = 0; i < length && n < max; i++) out[n++] = rom[p + (uint32_t) i];
            p += (uint32_t) length;
            break;
        case 1: {
            int v = rom[p++], times = polished ? length + 1 : length;
            for (int i = 0; i < times && n < max; i++) out[n++] = (uint8_t) v;
            break;
        }
        case 2: {
            int v0 = rom[p++], v1 = rom[p++], times = polished ? length + 2 : length;
            for (int i = 0; i < times && n < max; i++) out[n++] = (uint8_t) (i & 1 ? v1 : v0);
            break;
        }
        case 3:
            for (int i = 0; i < length && n < max; i++) out[n++] = 0;
            break;
        default: {
            int o = rom[p++];
            int from = o & 0x80 ? n - (o & 0x7F) - 1 : (o << 8 | rom[p++]);
            if (from < 0 || from >= n) return -1;
            for (int i = 0; i < length && n < max; i++) {
                if (cmd == 4) {
                    out[n++] = out[from + i];
                } else if (cmd == 5) {
                    int v = out[from + i], f = 0;
                    for (int k = 0; k < 8; k++) f |= (v >> k & 1) << (7 - k);
                    out[n++] = (uint8_t) f;
                } else {
                    if (from - i < 0) return -1;
                    out[n++] = out[from - i];
                }
            }
            break;
        }
        }
    }
    return n;
}

static uint32_t gbc_rgb(const uint8_t* rom, uint32_t o) {
    int c = rom[o] | rom[o + 1] << 8;
    uint32_t r = c & 31, g = c >> 5 & 31, b = c >> 10 & 31;
    return (r << 3 | r >> 2) << 16 | (g << 3 | g >> 2) << 8 | (b << 3 | b >> 2);
}

// Frame 1's four tiles (TL, TR, BL, BR) in [pal].
static void draw_gen2(const uint8_t* tiles, const uint32_t* pal, uint32_t* out) {
    for (int t = 0; t < 4; t++)
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int v = tile_px(tiles + 16 * t, x, y);
                if (v) put2x(out, (t % 2) * 8 + x, (t / 2) * 8 + y, pal[v]);
            }
}

static bool icon_gen2(const struct pd_game* g, const struct pd_gb_art* a, int species, uint32_t* out) {
    const uint8_t* rom = g->rom;
    bool polished = g->gb->polished;
    uint8_t tiles[128];
    uint32_t pal[4] = { 0, 0, 0, 0 };
    if (a->minis) {
        int mini = species > POLISHED_NUM_SPECIES ? POLISHED_NUM_SPECIES + a->cosmeticForms + (species - POLISHED_NUM_SPECIES - 1) : species;
        const uint8_t* e = rom + a->minis + 7 * mini;
        if (gen2_lz(rom, g->romSize, rom_off(e[0], e[1] | e[2] << 8), tiles, 128, polished) < 64) return false;
        uint32_t p = a->monPalettes + 8u * (uint32_t) species;
        pal[1] = gbc_rgb(rom, p);
        pal[2] = gbc_rgb(rom, p + 2);
        draw_gen2(tiles, pal, out);
        return true;
    }
    if (a->iconFarPointers) {
        const uint8_t* e = rom + a->iconFarPointers + 3 * (species - 1);
        if (gen2_lz(rom, g->romSize, rom_off(e[0], e[1] | e[2] << 8), tiles, 128, false) < 64) return false;
        uint32_t p = a->iconObjPals + 8u * (rom[a->iconPalIndex + (uint32_t) species - 1] >> 4 & 7);
        for (int i = 1; i < 4; i++) pal[i] = gbc_rgb(rom, p + 2u * (uint32_t) i);
        draw_gen2(tiles, pal, out);
        return true;
    }
    if (a->bankLow) {
        // Crystal Clear: by Dex number, LZ, each in its own colours (UNOWN's by letter).
        int bank = species < 0x80 ? a->bankLow : a->bankHigh;
        uint32_t table = a->icons + 2u * (uint32_t) species;
        if (species == 201 && a->unownIcons) {
            table = a->unownIcons;
            bank = a->unownBank;
        }
        if (gen2_lz(rom, g->romSize, rom_off(bank, rom[table] | rom[table + 1] << 8), tiles, 128, false) < 64) return false;
        uint32_t p = a->iconPalettes + 8u * (uint32_t) species;
        pal[1] = gbc_rgb(rom, p);
        pal[2] = gbc_rgb(rom, p + 2);
        draw_gen2(tiles, pal, out);
        return true;
    }
    int id = species == GEN2_EGG ? a->eggIcon : rom[a->monMenuIcons + (uint32_t) species - 1];
    uint32_t t = a->icons + 2u * (uint32_t) id;
    uint32_t at = rom_off(a->iconsBank, rom[t] | rom[t + 1] << 8);
    for (int i = 0; i < 4; i++) pal[i] = gbc_rgb(rom, a->partyMenuObPals + 2u * (uint32_t) i);
    draw_gen2(rom + at, pal, out);
    return true;
}

bool pd_gb_icon(const struct pd_game* g, int species, uint32_t* out, uint32_t clear) {
    const struct pd_gb_config* c = g->gb;
    if (!c || !c->art || !g->rom || species <= 0) return false;
    for (int i = 0; i < 32 * 32; i++) out[i] = clear;
    return c->gen == 1 ? icon_gen1(g, c->art, species, out) : icon_gen2(g, c->art, species, out);
}

// --- the town map ---

static bool gen1_map(const struct pd_game* g, const struct pd_gb_art* a, uint32_t* out) {
    const uint8_t* rom = g->rom;
    if (!a->townMap) return false;
    const uint8_t* tiles = rom + a->townMapTiles;
    uint32_t o = a->townMap;
    int cell = 0;
    while (rom[o] && cell < 20 * 18) {
        int b = rom[o++];
        for (int k = 0; k < (b & 0xF) && cell < 20 * 18; k++, cell++) {
            const uint8_t* tile = tiles + 16 * (b >> 4);
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++)
                    out[((cell / 20) * 8 + y) * 160 + (cell % 20) * 8 + x] = a->townMapColors[tile_px(tile, x, y)];
        }
    }
    return true;
}

static bool gen2_map(const struct pd_game* g, const struct pd_gb_art* a, int region, uint32_t* out) {
    const uint8_t* rom = g->rom;
    if (!a->townMapGfx || region < 0 || region >= a->townMapCount) return false;
    static uint8_t tiles[0x1000], lz[20 * 18];
    int size = gen2_lz(rom, g->romSize, a->townMapGfx, tiles, sizeof(tiles), g->gb->polished);
    if (size <= 0) return false;
    int first = 0;
    const uint8_t* map = rom + a->townMaps[region];
    if (a->townMapsLz) {
        if (gen2_lz(rom, g->romSize, a->townMaps[region], lz, 20 * 17, false) != 20 * 17) return false;
        map = lz;
        first = 20;
        for (int i = 0; i < 160 * 8; i++) out[i] = gbc_rgb(rom, a->pokegearPals);
    }
    for (int cell = first; cell < 20 * 18; cell++) {
        int byte = map[cell - first];
        if (byte == 0xFF) return false;
        int tile = a->townMapFlips ? byte & 0x3F : byte;
        bool xf = a->townMapFlips && (byte & 0x40), yf = a->townMapFlips && (byte & 0x80);
        if (tile * 16 + 16 > size) return false;
        int pal = tile >= a->townMapPal0From ? 0 : (rom[a->townMapPalMap + (uint32_t) tile / 2] >> (tile % 2 == 0 ? 0 : 4)) & 7;
        if (pal >= a->townMapPalCount) return false;
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int v = tile_px(tiles + tile * 16, xf ? 7 - x : x, yf ? 7 - y : y);
                out[((cell / 20) * 8 + y) * 160 + (cell % 20) * 8 + x] = gbc_rgb(rom, a->pokegearPals + 8u * (uint32_t) pal + 2u * (uint32_t) v);
            }
    }
    return true;
}

bool pd_gb_town_map(const struct pd_game* g, int region, uint32_t* out) {
    const struct pd_gb_config* c = g->gb;
    if (!c || !c->art || !g->rom) return false;
    return c->gen == 1 ? gen1_map(g, c->art, out) : gen2_map(g, c->art, region, out);
}

bool pd_gb_player(const struct pd_game* g, int gender, uint32_t* out, uint32_t clear) {
    const struct pd_gb_config* c = g->gb;
    if (!c || !c->art || !g->rom) return false;
    const struct pd_gb_art* a = c->art;
    const uint8_t* tiles;
    static uint8_t buffer[128];
    uint32_t pal[4];
    if (c->gen == 1) {
        if (!a->playerSprite) return false;
        tiles = g->rom + a->playerSprite;
        for (int i = 0; i < 4; i++) pal[i] = a->playerColors[i];
    } else {
        if (gender < 0 || !a->playerSprites[gender]) return false;
        tiles = g->rom + a->playerSprites[gender];
        if (a->playerLz) {
            if (gen2_lz(g->rom, g->romSize, a->playerSprites[gender], buffer, sizeof(buffer), true) < 64) return false;
            tiles = buffer;
        }
        for (int i = 0; i < 4; i++) pal[i] = gbc_rgb(g->rom, a->objectPals + 8u * (uint32_t) (a->playerPalFrom + gender) + 2u * (uint32_t) i);
    }
    for (int y = 0; y < 16; y++)
        for (int x = 0; x < 16; x++) {
            int v = tile_px(tiles + 16 * ((y / 8) * 2 + x / 8), x % 8, y % 8);
            out[y * 16 + x] = v ? pal[v] : clear;
        }
    return true;
}
