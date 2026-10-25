#include "pd_snapshot.h"

#include <stdio.h>
#include <string.h>

#include "pd_tables.h"

#define MON_SIZE 100
#define BATTLE_MON_SIZE 0x58
#define MAP_HEADER_MAPSEC_OFF 0x14
#define MAP_HEADER_MAP_TYPE_OFF 0x17
#define SAVEBLOCK2_GENDER_OFF 8
#define BATTLE_TYPE_DOUBLE 0x0001
#define BATTLE_TYPE_TRAINER 0x0008
#define MAX_MONEY 999999

static uint16_t u16(const uint8_t* b) {
    return (uint16_t) (b[0] | (b[1] << 8));
}

static uint32_t u32(const uint8_t* b) {
    return (uint32_t) b[0] | ((uint32_t) b[1] << 8) | ((uint32_t) b[2] << 16) | ((uint32_t) b[3] << 24);
}

static bool in_ram(uint32_t a) {
    return a >= 0x02000000 && a < 0x04000000;
}

// gen3SubstructOrder[personality % 24][slot] = logical substruct
// (0 Growth, 1 Attacks, 2 EVs, 3 Misc) - the "GAEM" permutation table.
static const uint8_t SUBSTRUCT_ORDER[24][4] = {
    {0, 1, 2, 3}, {0, 1, 3, 2}, {0, 2, 1, 3}, {0, 2, 3, 1}, {0, 3, 1, 2}, {0, 3, 2, 1},
    {1, 0, 2, 3}, {1, 0, 3, 2}, {1, 2, 0, 3}, {1, 2, 3, 0}, {1, 3, 0, 2}, {1, 3, 2, 0},
    {2, 0, 1, 3}, {2, 0, 3, 1}, {2, 1, 0, 3}, {2, 1, 3, 0}, {2, 3, 0, 1}, {2, 3, 1, 0},
    {3, 0, 1, 2}, {3, 0, 2, 1}, {3, 1, 0, 2}, {3, 1, 2, 0}, {3, 2, 0, 1}, {3, 2, 1, 0},
};

// The English games' charset (charmap.txt in the pret decomps), the part a
// nickname uses.
static const char* gen3_char(uint8_t c) {
    static char one[2];
    if (c >= 0xBB && c <= 0xD4) { one[0] = (char) ('A' + c - 0xBB); one[1] = 0; return one; }
    if (c >= 0xD5 && c <= 0xEE) { one[0] = (char) ('a' + c - 0xD5); one[1] = 0; return one; }
    if (c >= 0xA1 && c <= 0xAA) { one[0] = (char) ('0' + c - 0xA1); one[1] = 0; return one; }
    switch (c) {
    case 0x00: return " ";
    case 0x1B: return "é";
    case 0xAB: return "!";
    case 0xAC: return "?";
    case 0xAD: return ".";
    case 0xAE: return "-";
    case 0xB0: return "...";
    case 0xB1: case 0xB2: return "\"";
    case 0xB3: case 0xB4: return "'";
    case 0xB5: return "♂";
    case 0xB6: return "♀";
    case 0xB8: return ",";
    case 0xBA: return "/";
    case 0xF0: return ":";
    default: return "?";
    }
}

// Control-code argument counts after 0xFC (the app's FC_ARGS).
static const uint8_t FC_ARGS[] = { 0, 1, 1, 1, 3, 1, 1, 0, 1, 0, 0, 2, 1, 1, 1, 0, 2, 1, 1, 1, 1, 0, 0, 0, 0 };

void pd_gen3_text(const uint8_t* src, size_t maxLen, char* out, size_t outLen) {
    size_t n = 0;
    out[0] = 0;
    for (size_t i = 0; i < maxLen && src[i] != 0xFF; i++) {
        const char* s;
        uint8_t c = src[i];
        if (c == 0xFE || c == 0xFA || c == 0xFB) {
            s = " "; // line / paragraph breaks
        } else if (c == 0xFC) {
            uint8_t code = i + 1 < maxLen ? src[i + 1] : 0;
            i += 1 + (code < sizeof(FC_ARGS) ? FC_ARGS[code] : 0);
            continue;
        } else {
            s = gen3_char(c);
        }
        size_t l = strlen(s);
        if (n + l + 1 > outLen) break;
        // One space for a run of breaks.
        if (*s == ' ' && l == 1 && n && out[n - 1] == ' ') continue;
        memcpy(out + n, s, l);
        n += l;
    }
    out[n] = 0;
    while (n && out[n - 1] == ' ') out[--n] = 0;
    size_t lead = 0;
    while (out[lead] == ' ') lead++;
    if (lead) memmove(out, out + lead, n - lead + 1);
}

static void decode_name(const uint8_t* src, int maxLen, char* out, size_t outLen) {
    pd_gen3_text(src, (size_t) maxLen, out, outLen);
}

// GetMonGender: the personality's low byte against the species' ratio; and
// DisplayPartyPokemonGender's rule that a Nidoran still named "NIDORAN♀" /
// "NIDORAN♂" shows no mark (its name has one) - the app's withNativeGender.
static enum pd_gender gender_of(const struct pd_mon* m, uint32_t personality) {
    if (m->species == PD_SPECIES_EGG || m->species >= pd_gender_ratios_count) return PD_GENDER_NONE;
    if ((m->species == 29 && !strcmp(m->nickname, "NIDORAN♀")) ||
        (m->species == 32 && !strcmp(m->nickname, "NIDORAN♂"))) {
        return PD_GENDER_NONE;
    }
    int ratio = pd_gender_ratios[m->species];
    if (ratio == 255) return PD_GENDER_NONE;
    if (ratio == 0) return PD_GENDER_MALE;
    if (ratio == 254) return PD_GENDER_FEMALE;
    return (int) (personality & 0xFF) < ratio ? PD_GENDER_FEMALE : PD_GENDER_MALE;
}

bool pd_decode_party_mon(const uint8_t* raw, struct pd_mon* m) {
    memset(m, 0, sizeof(*m));
    uint32_t personality = u32(raw);
    uint32_t key = personality ^ u32(raw + 4);
    uint16_t stored = u16(raw + 28);

    uint8_t block[48];
    uint16_t sum = 0;
    for (int i = 0; i < 12; i++) {
        uint32_t w = u32(raw + 0x20 + i * 4) ^ key;
        block[i * 4] = (uint8_t) w;
        block[i * 4 + 1] = (uint8_t) (w >> 8);
        block[i * 4 + 2] = (uint8_t) (w >> 16);
        block[i * 4 + 3] = (uint8_t) (w >> 24);
        sum = (uint16_t) (sum + (w & 0xFFFF) + (w >> 16));
    }
    // Retail boxes are encrypted and checksummed; an empty slot fails here.
    if (sum != stored || stored == 0) return false;

    int off[4] = {0};
    for (int slot = 0; slot < 4; slot++) off[SUBSTRUCT_ORDER[personality % 24][slot]] = slot * 12;
    m->species = u16(block + off[0]);
    if (m->species == 0) return false;
    // Android Gen3Mon.kt decryptGen3Substructs / monStats, retail layout.
    m->heldItem = u16(block + off[0] + 2);
    m->experience = u32(block + off[0] + 4);
    uint32_t ivWord = u32(block + off[3] + 4);
    for (int i = 0; i < 6; i++) {
        m->stats.ivs[i] = (ivWord >> (5 * i)) & 31;
        m->stats.evs[i] = block[off[2] + i];
        m->stats.values[i] = u16(raw + 0x58 + 2 * i);
    }
    m->stats.nature = personality % 25;
    m->stats.valid = true;
    m->stats.hasIvs = m->stats.hasEvs = m->stats.hasNature = true;
    m->stats.hasHiddenPower = true;
    m->stats.hiddenPowerType = pd_hidden_power_type(&m->stats);
    m->stats.hiddenPowerPower = pd_hidden_power_power(&m->stats);
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        m->moves[i] = u16(block + off[1] + i * 2);
        m->pp[i] = block[off[1] + 8 + i];
    }
    m->personality = personality;
    m->level = raw[0x54];
    m->hp = u16(raw + 0x56);
    m->maxHp = u16(raw + 0x58);
    m->status = u32(raw + 0x50);
    // BoxPokemon +0x13 flags (isBadEgg, hasSpecies, isEgg): an egg shows as
    // SPECIES_EGG, like the party menu.
    if (raw[0x13] & 0x04) m->species = PD_SPECIES_EGG;
    decode_name(raw + 0x08, 10, m->nickname, sizeof(m->nickname));
    m->gender = gender_of(m, personality);
    return true;
}

static void decode_battle_mon(const uint8_t* raw, struct pd_battle_mon* b) {
    memset(b, 0, sizeof(*b));
    b->species = u16(raw);
    if (!b->species) return;
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        b->moves[i] = u16(raw + 0x0C + i * 2);
        b->pp[i] = raw[0x24 + i];
    }
    b->type1 = raw[0x21];
    b->type2 = raw[0x22];
    b->hp = u16(raw + 0x28);
    b->level = raw[0x2A];
    b->maxHp = u16(raw + 0x2C);
    b->status = u32(raw + 0x4C);
}

static void read_save_fields(struct pd_snapshot* s, const struct pd_config* cfg, pd_read_fn read, void* ctx,
                             uint32_t sb1, uint32_t sb2);
static void read_foes(struct pd_snapshot* s, const struct pd_config* cfg, pd_read_fn read, void* ctx, int foeBattler);

static bool read_u32(pd_read_fn read, void* ctx, uint32_t addr, uint32_t* out) {
    uint8_t b[4];
    if (!read(ctx, addr, b, 4)) return false;
    *out = u32(b);
    return true;
}

// A save block: where the pointer says, or (Ruby / Sapphire) the address itself.
static bool save_block(const struct pd_config* cfg, pd_read_fn read, void* ctx, uint32_t at, uint32_t* out) {
    if (cfg->staticSaveBlocks) *out = at;
    else if (!read_u32(read, ctx, at, out)) return false;
    return in_ram(*out);
}

// SaveBlock2's encryption key, or 0 where the game has none (encryptionKeyOff -1).
static bool save_key(const struct pd_config* cfg, pd_read_fn read, void* ctx, uint32_t sb2, uint32_t* key) {
    *key = 0;
    return cfg->encryptionKeyOff < 0 || read_u32(read, ctx, sb2 + (uint32_t) cfg->encryptionKeyOff, key);
}

void pd_snapshot_read(struct pd_snapshot* s, const struct pd_game* g, pd_read_fn read, void* ctx) {
    memset(s, 0, sizeof(*s));
    s->partyActive = -1;
    s->money = -1;
    const struct pd_config* cfg = g->cfg;
    if (!cfg) return;
    s->valid = true;
    read_u32(read, ctx, cfg->gMain + 0x24, &s->frame);

    // --- party ---
    uint8_t count = 0;
    read(ctx, cfg->playerPartyCount, &count, 1);
    if (count > PD_PARTY_SIZE) count = PD_PARTY_SIZE;
    uint8_t raw[PD_PARTY_SIZE * MON_SIZE];
    if (count && read(ctx, cfg->playerParty, raw, count * MON_SIZE)) {
        for (int i = 0; i < count; i++) {
            if (pd_decode_party_mon(raw + i * MON_SIZE, &s->party[s->partyCount])) s->partyCount++;
        }
    }

    // --- battle --- (gMain.inBattle is bit 1 of the byte at +0x439)
    s->foeActive = s->foeNext = -1;
    uint8_t flags = 0;
    read(ctx, cfg->gMain + 0x439, &flags, 1);
    s->inBattle = (flags & 0x02) != 0;
    if (s->inBattle) {
        uint32_t type = 0;
        read_u32(read, ctx, cfg->battleTypeFlags, &type);
        s->isDouble = (type & BATTLE_TYPE_DOUBLE) != 0;
        s->isTrainer = (type & BATTLE_TYPE_TRAINER) != 0;
        uint8_t n = 4, pos[4] = {0, 1, 2, 3};
        read(ctx, cfg->battlersCount, &n, 1);
        if (n < 1 || n > 4) n = 4;
        read(ctx, cfg->battlerPositions, pos, 4);
        uint8_t mons[4 * BATTLE_MON_SIZE];
        int foeBattler = -1;
        if (read(ctx, cfg->battleMons, mons, sizeof(mons))) {
            for (int b = 0; b < n; b++) {
                int p = pos[b] < 4 ? pos[b] : b;
                decode_battle_mon(mons + b * BATTLE_MON_SIZE, &s->battlers[p]);
                if (p == PD_POS_OPPONENT_LEFT) foeBattler = b;
            }
        }
        if (s->isTrainer && cfg->enemyParty) read_foes(s, cfg, read, ctx, foeBattler);
    }

    // --- location ---
    uint8_t sec = 0;
    read(ctx, cfg->mapHeader + MAP_HEADER_MAPSEC_OFF, &sec, 1);
    s->mapsec = sec;

    // --- money: SaveBlock1.money XOR SaveBlock2.encryptionKey ---
    uint32_t sb1 = 0, sb2 = 0, money = 0, key = 0;
    bool haveKey = save_block(cfg, read, ctx, cfg->saveBlock2Ptr, &sb2) && save_key(cfg, read, ctx, sb2, &key);
    if (haveKey && save_block(cfg, read, ctx, cfg->saveBlock1Ptr, &sb1) &&
        read_u32(read, ctx, sb1 + cfg->moneyOff, &money)) {
        uint32_t v = money ^ key;
        if (v <= MAX_MONEY) s->money = (long) v;
    }

    // --- bag: quantities XOR the key's low half (readNativeBag) ---
    uint8_t pockets[PD_POCKET_COUNT * 8];
    if (haveKey && read(ctx, cfg->bagPockets, pockets, sizeof(pockets))) {
        s->bagOk = true;
        for (int p = 0; p < PD_POCKET_COUNT; p++) {
            uint32_t slots = u32(pockets + p * 8);
            int capacity = pockets[p * 8 + 4];
            if (!in_ram(slots) || capacity == 0) continue;
            if (capacity > PD_POCKET_SLOTS) capacity = PD_POCKET_SLOTS;
            uint8_t items[PD_POCKET_SLOTS * 4];
            if (!read(ctx, slots, items, (size_t) capacity * 4)) continue;
            struct pd_pocket_items* out = &s->bag[cfg->bagOrder[p]];
            for (int i = 0; i < capacity; i++) {
                uint16_t id = u16(items + i * 4);
                if (!id) continue;
                out->items[out->count].id = id;
                out->items[out->count].quantity = (uint16_t) (u16(items + i * 4 + 2) ^ (key & 0xFFFF));
                out->count++;
            }
        }
    }

    bool saveOk = haveKey && in_ram(sb1);
    s->gender = -1;
    if (saveOk) read_save_fields(s, cfg, read, ctx, sb1, sb2);

    // --- the map: gMapHeader's layout size and type ---
    uint32_t layout = 0, w = 0, h = 0;
    uint8_t type = 0;
    // (The layout lives in the ROM: the host's read serves ROM addresses too.)
    if (read_u32(read, ctx, cfg->mapHeader, &layout) && (in_ram(layout) || (layout >= 0x08000000 && layout < 0x0A000000)) &&
        read_u32(read, ctx, layout, &w) &&
        read_u32(read, ctx, layout + 4, &h) && w >= 1 && w <= 1024 && h >= 1 && h <= 1024 &&
        read(ctx, cfg->mapHeader + MAP_HEADER_MAP_TYPE_OFF, &type, 1)) {
        s->mapW = (int) w;
        s->mapH = (int) h;
        s->mapType = type;
    }
}

// The save's own fields: position, gender, the POKéDEX's flags, event flags
// and vars.
// readTrainerCard's reads (TrainerCard.kt); the rest is worked out by pd_card.
static void read_card_fields(struct pd_snapshot* s, const struct pd_config* cfg, pd_read_fn read, void* ctx,
                             uint32_t sb1, uint32_t sb2) {
    uint8_t head[0x12];
    uint32_t key;
    uint8_t stats[PD_GAME_STATS * 4];
    if (!read(ctx, sb2, head, sizeof(head)) || !save_key(cfg, read, ctx, sb2, &key) ||
        !read(ctx, sb1 + cfg->gameStatsOff, stats, sizeof(stats))) {
        return;
    }
    memcpy(s->playerName, head, 7);
    s->playerName[7] = 0xFF;
    s->trainerId = u16(head + 0x0A);
    s->playHours = u16(head + 0x0E);
    s->playMinutes = head[0x10];
    for (int i = 0; i < PD_GAME_STATS; i++) s->gameStats[i] = u32(stats + i * 4) ^ key;
    if (cfg->museumWinnersOff) {
        uint8_t winners[5 * 32];
        if (!read(ctx, sb1 + cfg->museumWinnersOff, winners, sizeof(winners))) return;
        for (int i = 0; i < 5; i++) s->museumWinners[i] = u16(winners + i * 32 + 8);
    }
    uint8_t bp[2];
    if (cfg->frontierBpOff) {
        if (!read(ctx, sb2 + cfg->frontierBpOff, bp, 2)) return;
        s->battlePoints = u16(bp);
    }
    s->cardOk = true;
}

static void read_save_fields(struct pd_snapshot* s, const struct pd_config* cfg, pd_read_fn read, void* ctx,
                             uint32_t sb1, uint32_t sb2) {
    uint8_t pos[6];
    if (read(ctx, sb1, pos, sizeof(pos))) {
        s->posOk = true;
        s->x = u16(pos);
        s->y = u16(pos + 2);
        s->mapGroup = pos[4];
        s->mapNum = pos[5];
    }
    uint8_t gender = 0xFF;
    if (read(ctx, sb2 + SAVEBLOCK2_GENDER_OFF, &gender, 1)) s->gender = gender <= 1 ? gender : -1;

    // struct Pokedex at SaveBlock2+0x18: owned at +0x10, seen at +0x44.
    uint8_t owned[PD_DEX_BYTES], seen[PD_DEX_BYTES], copy1[PD_DEX_BYTES], copy2[PD_DEX_BYTES], magic = 0;
    if (cfg->dexSeenCopy1 && read(ctx, sb2 + 0x28, owned, PD_DEX_BYTES) && read(ctx, sb2 + 0x5C, seen, PD_DEX_BYTES) &&
        read(ctx, sb1 + cfg->dexSeenCopy1, copy1, PD_DEX_BYTES) &&
        read(ctx, sb1 + cfg->dexSeenCopy2, copy2, PD_DEX_BYTES) &&
        read(ctx, sb2 + 0x18 + cfg->nationalMagicOff, &magic, 1)) {
        s->dexOk = true;
        s->dexNational = magic == cfg->nationalMagic;
        for (int i = 0; i < PD_DEX_BYTES; i++) {
            s->dexSeen[i] = seen[i] & copy1[i] & copy2[i];
            s->dexCaught[i] = owned[i] & s->dexSeen[i];
        }
    }

    if (cfg->cardStyle) read_card_fields(s, cfg, read, ctx, sb1, sb2);

    if (cfg->flagBytes && cfg->flagBytes <= PD_FLAG_BYTES && read(ctx, sb1 + cfg->flagsOff, s->flags, cfg->flagBytes)) {
        uint8_t vars[PD_VAR_COUNT * 2];
        if (read(ctx, sb1 + cfg->varsOff, vars, sizeof(vars))) {
            s->flagsOk = true;
            for (int i = 0; i < PD_VAR_COUNT; i++) s->vars[i] = u16(vars + i * 2);
        }
    }
}

bool pd_flag(const struct pd_snapshot* s, int flag) {
    return s->flagsOk && flag >= 0 && flag / 8 < PD_FLAG_BYTES && (s->flags[flag / 8] >> (flag % 8) & 1);
}

int pd_var(const struct pd_snapshot* s, int var) {
    int i = var - 0x4000;
    return s->flagsOk && i >= 0 && i < PD_VAR_COUNT ? s->vars[i] : 0;
}

static bool dex_bit(const uint8_t* bits, int national) {
    int i = national - 1;
    return i >= 0 && i < PD_NATIONAL_COUNT && (bits[i / 8] >> (i % 8) & 1);
}

bool pd_dex_seen(const struct pd_snapshot* s, int national) {
    return s->dexOk && dex_bit(s->dexSeen, national);
}

bool pd_dex_caught(const struct pd_snapshot* s, int national) {
    return s->dexOk && dex_bit(s->dexCaught, national);
}

bool pd_bag_has(const struct pd_snapshot* s, int item) {
    for (int p = 0; p < PD_POCKET_COUNT; p++) {
        for (int i = 0; i < s->bag[p].count; i++) {
            if (s->bag[p].items[i].id == item) return true;
        }
    }
    return false;
}

static const char* item_entry(const char* const* t, int n, int item) {
    if (item > 0 && item < n && t[item]) return t[item];
    return "";
}

const char* pd_item_name(const struct pd_game* g, int item) {
    if (g->kind == PD_GAME_EMERALD) return item_entry(pd_item_names_emerald, pd_item_names_emerald_count, item);
    return item_entry(pd_item_names_firered, pd_item_names_firered_count, item);
}

const char* pd_item_description(const struct pd_game* g, int item) {
    if (g->kind == PD_GAME_EMERALD) return item_entry(pd_item_desc_emerald, pd_item_desc_emerald_count, item);
    return item_entry(pd_item_desc_firered, pd_item_desc_firered_count, item);
}

const char* pd_pocket_name(int pocket) {
    switch (pocket) {
    case PD_POCKET_ITEMS: return "ITEMS";
    case PD_POCKET_BALLS: return "POKé BALLS";
    case PD_POCKET_TMHM: return "TMs & HMs";
    case PD_POCKET_BERRIES: return "BERRIES";
    case PD_POCKET_KEY: return "KEY ITEMS";
    default: return "";
    }
}

const char* pd_mapsec_name(const struct pd_game* g, int mapsec) {
    // Emerald's map sections start at 0 (Littleroot); FireRed's Kanto ones at 88.
    const char* const* t = g->kind == PD_GAME_EMERALD ? pd_mapsec_emerald : pd_mapsec_firered;
    int n = g->kind == PD_GAME_EMERALD ? pd_mapsec_emerald_count : pd_mapsec_firered_count;
    if (mapsec >= 0 && mapsec < n && t[mapsec]) return t[mapsec];
    return "";
}

const char* pd_status_label(uint32_t status) {
    if (status & 0x7) return "SLP";
    if (status & (1 << 5)) return "FRZ";
    if (status & (1 << 4)) return "BRN";
    if (status & (1 << 6)) return "PAR";
    if (status & (1 << 7)) return "TOX";
    if (status & (1 << 3)) return "PSN";
    return "";
}

// The trainer's party and which of it is out / next (readNativeTelemetry's
// enemyParty / enemyActive / enemyNext, then SnapshotView's rule for next).
static void read_foes(struct pd_snapshot* s, const struct pd_config* cfg, pd_read_fn read, void* ctx, int foeBattler) {
    uint8_t raw[PD_PARTY_SIZE * MON_SIZE];
    if (!read(ctx, cfg->enemyParty, raw, sizeof(raw))) return;
    for (int i = 0; i < PD_PARTY_SIZE; i++) {
        if (pd_decode_party_mon(raw + i * MON_SIZE, &s->foes[s->foeCount])) s->foeCount++;
    }
    if (!s->foeCount || foeBattler < 0 || !cfg->battlerPartyIndexes) return;
    uint8_t idx[2];
    if (read(ctx, cfg->battlerPartyIndexes + (uint32_t) foeBattler * 2, idx, 2)) {
        int active = idx[0] | idx[1] << 8;
        if (active < s->foeCount) s->foeActive = active;
    }
    uint32_t bs = 0;
    uint8_t next = 0xFF;
    if (s->foeActive >= 0 && (cfg->battleStructStatic ? (bs = cfg->battleStructPtr, true)
                                                     : read_u32(read, ctx, cfg->battleStructPtr, &bs)) && in_ram(bs) &&
        read(ctx, bs + cfg->monToSwitchIntoOff + (uint32_t) foeBattler, &next, 1) && next < s->foeCount &&
        next != s->foeActive && s->foes[next].hp > 0 && s->foes[s->foeActive].hp == 0) {
        s->foeNext = next;
    }
}
