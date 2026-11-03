#include "pd_snapshot.h"

#include "pd_gb.h"
#include "pd_games.h"

#include <stdio.h>
#include <string.h>

#include "pd_tables.h"

#define MON_SIZE 100
#define MAX_MON_SIZE 104
#define MAX_BATTLE_MON_SIZE 0x98
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
    if (m->egg) return PD_GENDER_NONE;
    int ratio = pd_gender_ratio(m->species);
    if (ratio < 0) return PD_GENDER_NONE;
    if ((m->species == 29 && !strcmp(m->nickname, "NIDORAN♀")) ||
        (m->species == 32 && !strcmp(m->nickname, "NIDORAN♂"))) {
        return PD_GENDER_NONE;
    }
    if (ratio == 255) return PD_GENDER_NONE;
    if (ratio == 0) return PD_GENDER_MALE;
    if (ratio == 254) return PD_GENDER_FEMALE;
    return (int) (personality & 0xFF) < ratio ? PD_GENDER_FEMALE : PD_GENDER_MALE;
}

static const struct pd_party_layout VANILLA_PARTY = {
    .size = 100, .status = 0x50, .level = 0x54, .hp = 0x56, .maxHp = 0x58, .flags = 0x13, .plainBox = 0x20, .hpBits = 16,
    .statusMask = 0xFFFFFFFFu, .eggMask = 4, .nicknameOff = 8, .nicknameLen = 10, .plainSpeciesMask = 0xFFFF,
    .plainItemMask = 0xFFFF, .hasStats = true,
};

static const struct pd_party_layout* party_layout(const struct pd_config* cfg) {
    return cfg && cfg->party ? cfg->party : &VANILLA_PARTY;
}

// decryptGen3Substructs: false when the box isn't encrypted (a plaintext game), or it's an empty slot.
static inline uint32_t ror32(uint32_t x, int n) {
    return x >> n | x << (32 - n);
}

// Monomyth's own key (Gen3Mon.kt's monomythKey, its box decryption at 0x08068198).
static uint32_t monomyth_key(uint32_t personality, uint32_t otId) {
    uint32_t r = otId + ror32(personality ^ 0x690, 7);
    r = ror32(r + ror32(personality, 11), 13);
    r = personality + ror32(0x5A000000u ^ r ^ otId, 17);
    return r ^ ror32(otId, 5);
}

// Its 250 flavour natures by a hash of the personality (monomythNature, its GetNature at 0x0805B214).
static int monomyth_nature(uint32_t personality) {
    uint32_t x = personality ^ personality >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    x ^= x >> 16;
    return (int) (x % 250);
}

static bool decrypt_box(const uint8_t* raw, uint8_t* block, int off[4], bool monomyth) {
    uint32_t personality = u32(raw);
    uint32_t key = monomyth ? monomyth_key(personality, u32(raw + 4)) : personality ^ u32(raw + 4);
    uint16_t stored = u16(raw + 28);
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
    for (int slot = 0; slot < 4; slot++) off[SUBSTRUCT_ORDER[personality % 24][slot]] = slot * 12;
    return true;
}

bool pd_decode_party_mon_for(const struct pd_config* cfg, const uint8_t* raw, struct pd_mon* m) {
    const struct pd_party_layout* l = party_layout(cfg);
    memset(m, 0, sizeof(*m));
    uint32_t personality = u32(raw);
    if (l->rowe || l->eliteRedux) {
        // Bit-packed plaintext structs (decodeRoweMon, decodeEliteReduxMon): no IVs to speak of, the nature stored.
        uint32_t w08 = u32(raw + 0x08), w0c = u32(raw + 0x0C), w10 = u32(raw + 0x10), w14 = u32(raw + 0x14), w18 = u32(raw + 0x18);
        m->species = l->rowe ? (int) (u32(raw + 0x1C) & 0xFFF) : (int) (w10 & 0xFFFF);
        if (!m->species) return false;
        m->personality = personality;
        m->level = raw[0x3C];
        m->hp = u16(raw + 0x3E);
        m->maxHp = u16(raw + 0x40);
        m->status = u32(raw + 0x38);
        for (int i = 0; i < PD_NUM_MOVES; i++) m->pp[i] = raw[0x34 + i];
        for (int i = 0; i < 6; i++) m->stats[i] = u16(raw + 0x40 + 2 * i);
        if (l->rowe) {
            m->moves[0] = (int) (w14 & 0x3FF);
            m->moves[1] = (int) (w18 & 0x3FF);
            m->moves[2] = (int) (w18 >> 10 & 0x3FF);
            m->moves[3] = u16(raw + 0x1E) >> 1 & 0x3FF;
            m->exp = w14 >> 10 & 0x1FFFFF;
            for (int i = 0; i < 6; i++) m->evs[i] = raw[0x20 + i];
            m->nature = raw[0x27] >> 2 & 0x1F;
        } else {
            m->moves[0] = (int) (w08 & 0x7FF);
            m->moves[1] = (int) (w0c & 0x7FF);
            m->moves[2] = (int) (w0c >> 11 & 0x7FF);
            m->moves[3] = (int) (w10 >> 16 & 0x7FF);
            m->exp = w08 >> 11 & 0x1FFFFF;
            for (int i = 0; i < 6; i++) {
                m->evs[i] = raw[0x18 + i];
                m->ivs[i] = 31;
            }
            if (raw[0x32] & 0x20) m->ivs[3] = 0; // speedDown
            m->nature = (int) (w14 >> 10 & 0x1F);
            m->heldItem = (int) (w14 & 0x3FF);
        }
        m->hasStats = true;
        decode_name(raw + l->nicknameOff, l->nicknameLen, m->nickname, sizeof(m->nickname));
        m->gender = gender_of(m, personality);
        return true;
    }
    uint8_t block[48];
    int off[4] = { 0 };
    uint32_t iv;
    if (decrypt_box(raw, block, off, l->monomythKey)) {
        m->species = u16(block + off[0]);
        m->heldItem = u16(block + off[0] + 2);
        m->exp = u32(block + off[0] + 4);
        for (int i = 0; i < PD_NUM_MOVES; i++) {
            m->moves[i] = u16(block + off[1] + i * 2);
            m->pp[i] = block[off[1] + 8 + i];
        }
        for (int i = 0; i < 6; i++) m->evs[i] = block[off[2] + i];
        iv = u32(block + off[3] + 4);
    } else {
        // Plaintext boxes keep the substructs in G-A-E-M order: EVs at +0x18, IVs at Misc +4.
        const uint8_t* box = raw + l->plainBox;
        m->species = u16(box) & l->plainSpeciesMask;
        if (m->species == 0 || m->species > 4000) return false;
        m->heldItem = u16(box + 2) & l->plainItemMask;
        for (int i = 0; i < PD_NUM_MOVES; i++) {
            m->moves[i] = u16(box + 0x0C + i * 2);
            m->pp[i] = box[0x0C + 8 + i];
        }
        for (int i = 0; i < 6; i++) m->evs[i] = box[0x18 + i];
        iv = u32(box + 0x28);
    }
    if (m->species == 0) return false;
    if (cfg) {
        if (cfg->speciesMask) m->species &= cfg->speciesMask;
        for (int i = 0; i < PD_NUM_MOVES; i++) {
            if (cfg->moveMask) m->moves[i] &= cfg->moveMask;
            if (cfg->ppMask) m->pp[i] &= cfg->ppMask;
        }
    }
    m->personality = personality;
    m->level = raw[l->level];
    m->hp = u16(raw + l->hp) & (int) ((1u << l->hpBits) - 1);
    m->maxHp = u16(raw + l->maxHp);
    m->status = (u32(raw + l->status) >> l->statusShift) & l->statusMask;
    // Struct Pokemon's stats after maxHP: attack, defense, speed, sp. attack, sp. defense.
    m->stats[0] = m->maxHp;
    for (int i = 1; i < 6; i++) m->stats[i] = u16(raw + l->maxHp + i * 2);
    for (int i = 0; i < 6; i++) m->ivs[i] = (int) (iv >> (i * 5) & 31);
    m->nature = l->monomythKey ? PD_MONOMYTH_NATURE_BASE + monomyth_nature(personality) : (int) (personality % 25);
    uint32_t otId = u32(raw + 4);
    m->shiny = ((otId >> 16) ^ (otId & 0xFFFF) ^ (personality >> 16) ^ (personality & 0xFFFF)) < 8;
    m->hasStats = l->hasStats;
    // BoxPokemon +0x13 flags (isBadEgg, hasSpecies, isEgg): an egg shows as the game's egg, like the party menu.
    m->egg = l->eggInIvWord ? (raw[l->plainBox + 0x28 + 3] & 0x40) != 0 : (raw[l->flags] & l->eggMask) != 0;
    if (m->egg) m->species = cfg && cfg->eggSpecies ? cfg->eggSpecies : PD_SPECIES_EGG;
    decode_name(raw + l->nicknameOff, l->nicknameLen, m->nickname, sizeof(m->nickname));
    m->gender = gender_of(m, personality);
    return true;
}

bool pd_decode_party_mon(const uint8_t* raw, struct pd_mon* m) {
    return pd_decode_party_mon_for(NULL, raw, m);
}

static const struct pd_battle_layout VANILLA_BATTLE = {
    .size = 0x58, .species = 0, .speciesMask = 0xFFFF, .moves = 0x0C, .pp = 0x24, .type1 = 0x21, .type2 = 0x22, .hp = 0x28,
    .level = 0x2A, .maxHp = 0x2C, .status1 = 0x4C,
};

static void decode_battle_mon(const struct pd_battle_layout* l, const uint8_t* raw, struct pd_battle_mon* b) {
    memset(b, 0, sizeof(*b));
    b->species = u16(raw + l->species) & l->speciesMask;
    if (!b->species) return;
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        b->moves[i] = u16(raw + l->moves + i * 2);
        b->pp[i] = raw[l->pp + i];
    }
    b->type1 = raw[l->type1];
    b->type2 = raw[l->type2];
    b->hp = u16(raw + l->hp);
    b->level = raw[l->level];
    b->maxHp = u16(raw + l->maxHp);
    b->status = u32(raw + l->status1);
}

static void read_save_fields(struct pd_snapshot* s, const struct pd_config* cfg, pd_read_fn read, void* ctx,
                             uint32_t sb1, uint32_t sb2);
static void read_foes(struct pd_snapshot* s, const struct pd_config* cfg, pd_read_fn read, void* ctx, int foeBattler);
static void read_item_flags(struct pd_snapshot* s, const struct pd_config* cfg, pd_read_fn read, void* ctx, uint32_t sb1);
static void read_bag3(struct pd_snapshot* s, const struct pd_config* cfg, pd_read_fn read, void* ctx, uint32_t sb1, uint32_t sb2);

static bool read_u32(pd_read_fn read, void* ctx, uint32_t addr, uint32_t* out) {
    uint8_t b[4];
    if (!read(ctx, addr, b, 4)) return false;
    *out = u32(b);
    return true;
}

enum pd_battle_state pd_battle_state_read(const struct pd_config* cfg, pd_read_fn read, void* ctx, int* battler) {
    if (battler) *battler = 0;
    if (!cfg->battlerControllerFuncs) return PD_BATTLE_NONE;
    uint8_t flags = 0;
    if (!read(ctx, cfg->gMain + cfg->inBattleOff, &flags, 1) || !(flags & 2)) return PD_BATTLE_NONE;
    uint32_t fns[4];
    if (!read(ctx, cfg->battlerControllerFuncs, fns, sizeof(fns))) return PD_BATTLE_NONE;
    // The addresses are Thumb, so the low bit is masked on both sides.
    uint32_t want[5] = { cfg->handleInputChooseAction, cfg->handleInputChooseMove, cfg->completeWhenChoseItem,
                         cfg->waitForMonSelection, cfg->handleInputChooseTarget };
    static const enum pd_battle_state STATES[5] = { PD_BATTLE_ACTION, PD_BATTLE_MOVE, PD_BATTLE_BAG_OPEN,
                                                    PD_BATTLE_PARTY_OPEN, PD_BATTLE_TARGET };
    for (int i = 0; i < 4; i++) {
        for (int k = 0; k < 5; k++) {
            if (want[k] && (fns[i] & ~1u) == (want[k] & ~1u)) {
                if (battler) *battler = i;
                return STATES[k];
            }
        }
    }
    return PD_BATTLE_BUSY;
}

void pd_snapshot_read(struct pd_snapshot* s, const struct pd_game* g, pd_read_fn read, void* ctx) {
    if (g->gb) {
        pd_gb_snapshot_read(s, g, read, ctx);
        return;
    }
    memset(s, 0, sizeof(*s));
    s->money = -1;
    const struct pd_config* cfg = g->cfg;
    if (!cfg) return;
    s->valid = true;
    read_u32(read, ctx, cfg->gMain + (cfg->frameCounterOff ? cfg->frameCounterOff : 0x24), &s->frame);

    // --- party ---
    uint8_t count = 0;
    read(ctx, cfg->playerPartyCount, &count, 1);
    if (count > PD_PARTY_SIZE) count = PD_PARTY_SIZE;
    int stride = cfg->monStride ? cfg->monStride : MON_SIZE;
    uint8_t raw[PD_PARTY_SIZE * MAX_MON_SIZE];
    if (count && read(ctx, cfg->playerParty, raw, (size_t) (count * stride))) {
        for (int i = 0; i < count; i++) {
            if (pd_decode_party_mon_for(cfg, raw + i * stride, &s->party[s->partyCount])) s->partyCount++;
        }
    }

    // --- battle --- (gMain.inBattle is bit 1 of the byte at +0x439)
    s->foeActive = s->foeNext = -1;
    uint8_t flags = 0;
    read(ctx, cfg->gMain + cfg->inBattleOff, &flags, 1);
    s->inBattle = (flags & 0x02) != 0;
    s->battleState = pd_battle_state_read(g->cfg, read, ctx, NULL);
    if (s->inBattle) {
        uint32_t type = 0;
        read_u32(read, ctx, cfg->battleTypeFlags, &type);
        s->isDouble = (type & BATTLE_TYPE_DOUBLE) != 0;
        s->isTrainer = (type & BATTLE_TYPE_TRAINER) != 0;
        uint8_t n = 4, pos[4] = {0, 1, 2, 3};
        read(ctx, cfg->battlersCount, &n, 1);
        if (n < 1 || n > 4) n = 4;
        read(ctx, cfg->battlerPositions, pos, 4);
        const struct pd_battle_layout* bl = cfg->battle ? cfg->battle : &VANILLA_BATTLE;
        uint8_t mons[4 * MAX_BATTLE_MON_SIZE];
        int foeBattler = -1;
        if (cfg->battleMons && read(ctx, cfg->battleMons, mons, (size_t) (4 * bl->size))) {
            for (int b = 0; b < n; b++) {
                int p = pos[b] < 4 ? pos[b] : b;
                decode_battle_mon(bl, mons + b * bl->size, &s->battlers[p]);
                if (p == PD_POS_OPPONENT_LEFT) foeBattler = b;
            }
        }
        if (s->isTrainer && cfg->enemyParty) read_foes(s, cfg, read, ctx, foeBattler);
    }

    // --- location ---
    uint8_t sec[2] = { 0, 0 };
    read(ctx, cfg->mapHeader + MAP_HEADER_MAPSEC_OFF, sec, 2);
    s->mapsec = cfg->mapSecWide ? u16(sec) : sec[0];

    // --- money: SaveBlock1.money XOR SaveBlock2.encryptionKey ---
    uint32_t sb1 = 0, sb2 = 0, money = 0, key = 0;
    // Ruby / Sapphire's save blocks never move: the config's addresses are the blocks themselves.
    bool haveSb2 = cfg->staticSaveBlocks ? (sb2 = cfg->saveBlock2Ptr, true) : read_u32(read, ctx, cfg->saveBlock2Ptr, &sb2);
    // A config with no key (-1 / 0xFFFFFFFF on the wire): the money and bag are plain.
    bool haveKey = haveSb2 && in_ram(sb2) && (cfg->encryptionKeyOff == 0xFFFFFFFFu || read_u32(read, ctx, sb2 + cfg->encryptionKeyOff, &key));
    bool haveSb1 = cfg->staticSaveBlocks ? (sb1 = cfg->saveBlock1Ptr, true) : read_u32(read, ctx, cfg->saveBlock1Ptr, &sb1);
    if (haveKey && haveSb1 && in_ram(sb1) && cfg->moneyOff != 0xFFFFFFFFu && read_u32(read, ctx, sb1 + cfg->moneyOff, &money)) {
        uint32_t v = money ^ key;
        if ((long) v <= (cfg->maxMoney ? cfg->maxMoney : MAX_MONEY)) s->money = (long) v;
    }

    // --- bag: quantities XOR the key's low half (readNativeBag) ---
    if (in_ram(sb1) && cfg->itemFlagsOff) read_item_flags(s, cfg, read, ctx, sb1);
    if (in_ram(sb1) && in_ram(sb2) && cfg->bag3Off) read_bag3(s, cfg, read, ctx, sb1, sb2);
    int pocketCount = cfg->bagPocketCount ? cfg->bagPocketCount : 5;
    uint8_t pockets[12 * 8];
    if (haveKey && cfg->bagPockets && read(ctx, cfg->bagPockets, pockets, (size_t) (pocketCount * 8))) {
        s->bagOk = true;
        for (int p = 0; p < pocketCount; p++) {
            uint32_t slots = u32(pockets + p * 8);
            int capacity = pockets[p * 8 + 4];
            if (!in_ram(slots) || capacity == 0) continue;
            if (capacity > PD_POCKET_SLOTS) capacity = PD_POCKET_SLOTS;
            uint8_t items[PD_POCKET_SLOTS * 4];
            if (!read(ctx, slots, items, (size_t) capacity * 4)) continue;
            struct pd_pocket_items* out = &s->bag[cfg->bagOrder[p]];
            for (int i = 0; i < capacity; i++) {
                uint16_t id = u16(items + i * 4);
                if (!id || out->count >= PD_POCKET_SLOTS) continue;
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
    if (!read(ctx, sb2, head, sizeof(head)) || !read_u32(read, ctx, sb2 + cfg->encryptionKeyOff, &key) ||
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

static void bag_add(struct pd_snapshot* s, int pocket, int item, int quantity) {
    struct pd_pocket_items* p = &s->bag[pocket];
    if (p->count >= PD_POCKET_SLOTS) return;
    p->items[p->count].id = (uint16_t) item;
    p->items[p->count].quantity = (uint16_t) quantity;
    p->count++;
}

static int pocket_of(const struct pd_config* cfg, int item) {
    return item < cfg->itemPocketCount ? cfg->itemPockets[item] : PD_POCKET_ITEMS;
}

// Elite Redux: an owned bit per item, no counts (readItemFlagsBag).
static void read_item_flags(struct pd_snapshot* s, const struct pd_config* cfg, pd_read_fn read, void* ctx, uint32_t sb1) {
    static uint8_t raw[1024];
    int bytes = (cfg->itemFlagsCount + 7) / 8;
    if (bytes > (int) sizeof(raw) || !read(ctx, sb1 + cfg->itemFlagsOff, raw, (size_t) bytes)) return;
    for (int id = 1; id < cfg->itemFlagsCount; id++)
        if (raw[id / 8] >> (id % 8) & 1) bag_add(s, pocket_of(cfg, id), id, 1);
    s->bagOk = true;
}

// Quetzal's "BAG3" bit-stream (readBag3): 25-bit items from bit 0x80, key items 11 bits each.
static void read_bag3(struct pd_snapshot* s, const struct pd_config* cfg, pd_read_fn read, void* ctx, uint32_t sb1, uint32_t sb2) {
    static uint8_t raw[0x1000];
    uint8_t keyBytes[4] = { 0 };
    uint32_t split = cfg->bag3SplitAt, total = (uint32_t) (cfg->bag3KeyBit + 11 * 256) / 8 + 8;
    if (total > sizeof(raw) || split > total) return;
    if (!read(ctx, sb1 + cfg->bag3Off, raw, split) || !read(ctx, sb1 + cfg->bag3TailOff + split, raw + split, total - split)) return;
    if (memcmp(raw, "BAG3", 4)) return;
    read(ctx, sb2 + (uint32_t) cfg->encryptionKeyOff, keyBytes, 4);
    uint32_t key = u32(keyBytes);
    int slots = raw[0xC] | raw[0xD] << 8, keys = raw[0xE] | raw[0xF] << 8;
    if (slots > 400) slots = 400;
    if (keys > 256) keys = 256;
    #define BAG3_BITS(pos, n) ((uint32_t) ((((uint64_t) raw[(pos) >> 3] | (uint64_t) raw[((pos) >> 3) + 1] << 8 | (uint64_t) raw[((pos) >> 3) + 2] << 16 | \
        (uint64_t) raw[((pos) >> 3) + 3] << 24 | (uint64_t) raw[((pos) >> 3) + 4] << 32) >> ((pos) & 7)) & ((1u << (n)) - 1)))
    for (int i = 0; i < slots; i++) {
        uint32_t v = BAG3_BITS(0x80 + 25 * i, 25);
        int id = (int) (v & 0x7FF);
        if (id) bag_add(s, pocket_of(cfg, id), id, (int) (((v >> 11) ^ key) & 0x3FFF));
    }
    for (int i = 0; i < keys; i++) {
        int id = (int) BAG3_BITS(cfg->bag3KeyBit + 11 * i, 11);
        if (id) bag_add(s, PD_POCKET_KEY, id, 1);
    }
    s->bagOk = true;
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
    const struct pd_table_set* t = g->tables;
    if (t) return item > 0 && item < t->itemCount && t->items[item] ? t->items[item] : "";
    if (g->kind == PD_GAME_EMERALD) return item_entry(pd_item_names_emerald, pd_item_names_emerald_count, item);
    return item_entry(pd_item_names_firered, pd_item_names_firered_count, item);
}

const char* pd_item_description(const struct pd_game* g, int item) {
    // Read from the ROM as the game's bag shows it (RomItemText): the app bundles no descriptions either.
    const struct pd_config* c = g->cfg;
    if (g->tables && c && c->itemDescBase) {
        if (item < c->itemDescFirst || item - c->itemDescFirst >= c->itemDescCount) return "";
        uint32_t at = c->itemDescBase + (uint32_t) (item - c->itemDescFirst) * (uint32_t) c->itemDescStride + (uint32_t) c->itemDescOff;
        uint8_t ptr[4];
        static char text[256];
        if (!pd_rom_read(g, at, ptr, 4)) return "";
        const uint8_t* raw = pd_rom_at(g, u32(ptr), 200);
        if (!raw) return "";
        pd_gen3_text(raw, 200, text, sizeof(text));
        return text;
    }
    if (g->tables) return "";
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
    case PD_POCKET_MEDICINE: return "MEDICINE";
    case PD_POCKET_MAIL: return "MAIL";
    case PD_POCKET_BATTLE_ITEMS: return "BATTLE ITEMS";
    case PD_POCKET_MEGA_STONES: return "MEGA STONES";
    default: return "";
    }
}

const char* pd_mapsec_name(const struct pd_game* g, int mapsec) {
    if (g->tables) {
        const struct pd_table_set* t = g->tables;
        return mapsec >= 0 && mapsec < t->mapsecCount && t->mapsecs[mapsec] ? t->mapsecs[mapsec] : "";
    }
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
    if (s->foeActive >= 0 && read_u32(read, ctx, cfg->battleStructPtr, &bs) && in_ram(bs) &&
        read(ctx, bs + cfg->monToSwitchIntoOff + (uint32_t) foeBattler, &next, 1) && next < s->foeCount &&
        next != s->foeActive && s->foes[next].hp > 0 && s->foes[s->foeActive].hp == 0) {
        s->foeNext = next;
    }
}
