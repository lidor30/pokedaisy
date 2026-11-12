#include "pd_snapshot.h"

#include <stdio.h>
#include <string.h>

#include "pd_dex.h"
#include "pd_gb.h"
#include "pd_romart.h"
#include "pd_tables.h"

#define MON_SIZE_MAX 128
#define BATTLE_MON_MAX 0x98 // the largest layout's size
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
// The ratio table is the game's own (its [text.*] genderRatios) where the config has one, else retail's.
static enum pd_gender gender_of(const struct pd_config* cfg, const struct pd_mon* m, uint32_t personality) {
    const uint8_t* ratios = pd_gender_ratios;
    int count = pd_gender_ratios_count;
    if (cfg && cfg->text && cfg->text->genderRatios) {
        ratios = cfg->text->genderRatios;
        count = cfg->text->genderRatiosCount;
    }
    if (m->species == PD_SPECIES_EGG || m->species >= count) return PD_GENDER_NONE;
    if ((m->species == 29 && !strcmp(m->nickname, "NIDORAN♀")) ||
        (m->species == 32 && !strcmp(m->nickname, "NIDORAN♂"))) {
        return PD_GENDER_NONE;
    }
    int ratio = ratios[m->species];
    if (ratio == 255) return PD_GENDER_NONE;
    if (ratio == 0) return PD_GENDER_MALE;
    if (ratio == 254) return PD_GENDER_FEMALE;
    return (int) (personality & 0xFF) < ratio ? PD_GENDER_FEMALE : PD_GENDER_MALE;
}

bool pd_decode_party_mon(const uint8_t* raw, struct pd_mon* m) {
    return pd_decode_party_mon_l(raw, &pd_party_layout_vanilla, m);
}

// Shininess per the layout (Gen3Mon.kt: shinyBelow > 0 && (bits set, or shiny value < shinyBelow, flipped by the modifier)).
// The running game's own check where its layout has none (pd_game.shinyBelow, the app's NativeConfig.monLayout), for the
// pd_snapshot_read in progress.
static int gameShinyBelow;

static bool mon_shiny(const uint8_t* raw, const struct pd_party_layout* l) {
    int below0 = l->shinyBelow > 0 ? l->shinyBelow : gameShinyBelow;
    if (below0 <= 0) return false;
    if (l->shinyBitsMask) return (raw[l->shinyBits] & l->shinyBitsMask) != 0;
    uint32_t pid = u32(raw), ot = u32(raw + 4);
    unsigned v = ((ot >> 16) ^ (ot & 0xFFFF) ^ (pid >> 16) ^ (pid & 0xFFFF)) & 0xFFFF;
    bool below = (int) v < below0;
    bool flip = l->shinyModifier > 0 && (raw[l->shinyModifier] & 0x40) != 0;
    return below != flip;
}

// R.O.W.E.'s bit-packed struct (Gen3Mon.kt decodeRoweMon): no held item, no IVs (stats recompute with 0).
static bool decode_rowe_mon(const uint8_t* raw, const struct pd_party_layout* l, const struct pd_config* cfg, struct pd_mon* m) {
    uint32_t personality = u32(raw), w14 = u32(raw + 0x14), w18 = u32(raw + 0x18);
    int species = (int) (u32(raw + 0x1C) & 0xFFF);
    if (species == 0) return false;
    m->species = (uint16_t) species;
    m->moves[0] = w14 & 0x3FF;
    m->moves[1] = w18 & 0x3FF;
    m->moves[2] = (w18 >> 10) & 0x3FF;
    m->moves[3] = (u16(raw + 0x1E) >> 1) & 0x3FF;
    for (int i = 0; i < PD_NUM_MOVES; i++) m->pp[i] = raw[0x34 + i];
    m->experience = (w14 >> 10) & 0x1FFFFF;
    m->hasExperience = true;
    for (int i = 0; i < 6; i++) {
        m->stats.evs[i] = raw[0x20 + i];
        m->stats.values[i] = u16(raw + l->maxHp + 2 * i);
    }
    m->stats.nature = (raw[0x27] >> 2) & 0x1F;
    m->stats.valid = m->stats.hasEvs = m->stats.hasNature = m->stats.hasIvs = true;
    m->stats.hasHiddenPower = true;
    m->stats.hiddenPowerType = pd_hidden_power_type(&m->stats);
    m->stats.hiddenPowerPower = pd_hidden_power_power(&m->stats);
    m->personality = personality;
    m->level = raw[l->level];
    m->hp = u16(raw + l->hp);
    m->maxHp = u16(raw + l->maxHp);
    m->status = u32(raw + l->status);
    if (raw[l->flags] & l->eggMask) m->species = PD_SPECIES_EGG, m->egg = true;
    decode_name(raw + l->nicknameOff, l->nicknameLen, m->nickname, sizeof(m->nickname));
    m->gender = gender_of(cfg, m, personality);
    m->shiny = mon_shiny(raw, l);
    return true;
}

// Elite Redux's bit-packed struct (Gen3Mon.kt decodeEliteReduxMon): species +0x10, moves in 11-bit fields, held item and
// nature in +0x14; every IV is 31 but a Speed one of 0 where speedDown (+0x32 bit 5) is set.
static bool decode_er_mon(const uint8_t* raw, const struct pd_party_layout* l, const struct pd_config* cfg, struct pd_mon* m) {
    uint32_t w08 = u32(raw + 0x08), w0c = u32(raw + 0x0C), w10 = u32(raw + 0x10), w14 = u32(raw + 0x14);
    int species = (int) (w10 & 0xFFFF);
    if (species == 0) return false;
    m->species = (uint16_t) species;
    m->moves[0] = w08 & 0x7FF;
    m->moves[1] = w0c & 0x7FF;
    m->moves[2] = (w0c >> 11) & 0x7FF;
    m->moves[3] = (w10 >> 16) & 0x7FF;
    for (int i = 0; i < PD_NUM_MOVES; i++) m->pp[i] = raw[0x34 + i];
    m->experience = (w08 >> 11) & 0x1FFFFF;
    m->hasExperience = true;
    m->heldItem = (int) (w14 & 0x3FF);
    m->hasHeldItem = true;
    bool speedDown = (raw[0x32] & 0x20) != 0;
    for (int i = 0; i < 6; i++) {
        m->stats.ivs[i] = (i == 3 && speedDown) ? 0 : 31;
        m->stats.evs[i] = raw[0x18 + i];
        m->stats.values[i] = u16(raw + l->maxHp + 2 * i);
    }
    m->stats.nature = (int) ((w14 >> 10) & 0x1F);
    m->stats.valid = m->stats.hasIvs = m->stats.hasEvs = m->stats.hasNature = true;
    m->stats.hasHiddenPower = true;
    m->stats.hiddenPowerType = pd_hidden_power_type(&m->stats);
    m->stats.hiddenPowerPower = pd_hidden_power_power(&m->stats);
    m->personality = u32(raw);
    m->level = raw[l->level];
    m->hp = u16(raw + l->hp);
    m->maxHp = u16(raw + l->maxHp);
    m->status = u32(raw + l->status);
    if ((w14 >> 15) & 1) m->species = PD_SPECIES_EGG, m->egg = true;
    decode_name(raw + l->nicknameOff, l->nicknameLen, m->nickname, sizeof(m->nickname));
    m->gender = gender_of(cfg, m, m->personality);
    m->shiny = mon_shiny(raw, l);
    return true;
}

// Android Gen3Mon.kt decodePartyMon: retail's encrypted, checksummed substructs first, else a
// plaintext box (CFRU's: Unbound, Radical Red) at the layout's plainBox.
bool pd_decode_party_mon_l(const uint8_t* raw, const struct pd_party_layout* l, struct pd_mon* m) {
    return pd_decode_party_mon_c(raw, l, NULL, m);
}

// Monomyth's own key (Gen3Mon.kt's monomythKey, its box decryption at 0x08068198).
static inline uint32_t ror32(uint32_t x, int n) {
    return x >> n | x << (32 - n);
}

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

// ... and a config's masks (Config.masked): an expansion game packs more into the species, move and PP words.
bool pd_decode_party_mon_c(const uint8_t* raw, const struct pd_party_layout* l, const struct pd_config* cfg,
                           struct pd_mon* m) {
    memset(m, 0, sizeof(*m));
    uint32_t personality = u32(raw);
    if (l->rowe) return decode_rowe_mon(raw, l, cfg, m);
    if (l->eliteRedux) return decode_er_mon(raw, l, cfg, m);
    uint32_t key = l->monomythKey ? monomyth_key(personality, u32(raw + 4)) : personality ^ u32(raw + 4);
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
    const uint8_t *growth, *attacks, *evs;
    uint32_t ivWord;
    if (sum == stored && stored != 0) {
        int off[4] = {0};
        for (int slot = 0; slot < 4; slot++) off[SUBSTRUCT_ORDER[personality % 24][slot]] = slot * 12;
        growth = block + off[0];
        attacks = block + off[1];
        evs = block + off[2];
        ivWord = u32(block + off[3] + 4);
        m->experience = u32(growth + 4);
        m->hasExperience = true;
    } else {
        // An empty slot is all zeros (species 0); anything else without a checksum is a plaintext box.
        const uint8_t* box = raw + l->plainBox;
        growth = box;
        attacks = box + 0x0C;
        evs = box + 0x18;
        ivWord = u32(box + 0x28);
        if (l->plainExp) {
            m->experience = u32(growth + 4);
            m->hasExperience = true;
        }
    }
    unsigned speciesMask = cfg ? (unsigned) cfg->speciesMask : 0xFFFFu;
    bool masked = cfg && (cfg->speciesMask != 0xFFFF || cfg->moveMask != 0xFFFF || cfg->ppMask != 0xFF);
    int species = u16(growth) & speciesMask & (sum == stored && stored != 0 ? 0xFFFF : l->plainSpeciesMask);
    if (species == 0 || species > 4000) return false;
    m->species = (uint16_t) species;
    m->heldItem = u16(growth + 2) & (sum == stored && stored != 0 ? 0xFFFF : l->plainItemMask);
    m->hasHeldItem = true;
    if (l->hasStats) {
        int nature = l->monomythKey ? PD_MONOMYTH_NATURE_BASE + monomyth_nature(personality) : (int) (personality % 25);
        if (l->natureModifier && !l->monomythKey) {
            int n = nature ^ (raw[0x12] >> 3);
            if (n < 25) nature = n;
        }
        for (int i = 0; i < 6; i++) {
            m->stats.ivs[i] = (ivWord >> (5 * i)) & 31;
            m->stats.evs[i] = evs[i];
            m->stats.values[i] = u16(raw + l->maxHp + 2 * i);
        }
        m->stats.nature = nature;
        m->hasFriendship = sum == stored && stored != 0;
        m->friendship = growth[9];
        m->stats.valid = true;
        m->stats.hasIvs = m->stats.hasEvs = m->stats.hasNature = true;
        m->stats.hasHiddenPower = true;
        m->stats.hiddenPowerType = pd_hidden_power_type(&m->stats);
        m->stats.hiddenPowerPower = pd_hidden_power_power(&m->stats);
    }
    bool packed = l->packedAttacks && !(sum == stored && stored != 0);
    uint32_t ppWord = u32(attacks + 8);
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        if (packed) {
            // SoulGold v1.2.1: move 4's 11 bits run across the third and fourth u16, the PP are 7-bit fields of a u32.
            unsigned w = i < 3 ? u16(attacks + i * 2) : ((unsigned) u16(attacks + 4) >> 11) | ((unsigned) u16(attacks + 6) << 5);
            m->moves[i] = (uint16_t) (w & 0x7FF);
            m->pp[i] = (uint8_t) ((ppWord >> (7 * i)) & 0x7F);
        } else {
            m->moves[i] = u16(attacks + i * 2);
            m->pp[i] = attacks[8 + i];
        }
        if (masked) {
            m->moves[i] &= (uint16_t) cfg->moveMask;
            m->pp[i] &= (uint8_t) cfg->ppMask;
        }
    }
    if (masked && m->hasExperience) m->experience &= 0x1FFFFFu; // the same packing: experience in 21 bits
    m->personality = personality;
    m->level = raw[l->level];
    m->hp = u16(raw + l->hp) & ((1u << l->hpBits) - 1);
    m->maxHp = u16(raw + l->maxHp);
    m->status = (u32(raw + l->status) >> l->statusShift) & l->statusMask;
    // The flags byte (isBadEgg, hasSpecies, isEgg): an egg shows as SPECIES_EGG, like the party menu.
    m->egg = l->eggInIvWord ? (ivWord >> 30) & 1 : (raw[l->flags] & l->eggMask) != 0;
    if (m->egg) m->species = PD_SPECIES_EGG;
    decode_name(raw + l->nicknameOff, l->nicknameLen, m->nickname, sizeof(m->nickname));
    m->gender = gender_of(cfg, m, personality);
    m->shiny = mon_shiny(raw, l);
    return true;
}

bool pd_decode_party_mon_for(const struct pd_config* cfg, const uint8_t* raw, struct pd_mon* m) {
    const struct pd_party_layout* l = cfg && cfg->partyLayout ? cfg->partyLayout : &pd_party_layout_vanilla;
    return pd_decode_party_mon_c(raw, l, cfg, m);
}

static int battleShinyBelow; // the app's pidShiny, for the pd_snapshot_read in progress

static void decode_battle_mon(const uint8_t* raw, const struct pd_battle_layout* l, struct pd_battle_mon* b) {
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
    b->status = u32(raw + l->status);
    if (l->facts) {
        b->ability = l->abilityU16 ? u16(raw + l->ability) : raw[l->ability];
        b->heldItem = u16(raw + l->item);
        for (int i = 0; i < 5; i++) b->stats[i] = u16(raw + l->stats + i * 2);
        for (int i = 0; i < 8; i++) b->stages[i] = (signed char) (raw[l->stages + i] - 6);
        b->hasFacts = true;
    }
    // A wrong guess at the game's addresses reads bytes that are not a battler: show nothing rather than garbage.
    if (b->level < 1 || b->level > 100 || b->maxHp < 1 || b->maxHp > 9999 || b->hp > b->maxHp) { memset(b, 0, sizeof(*b)); return; }
    // the app's decodeBattleMon: a game that keeps shininess in bits of its own never tells by the battler's personality
    if (l->personality && l->otId && battleShinyBelow > 0) {
        uint32_t pid = u32(raw + l->personality), ot = u32(raw + l->otId);
        b->shiny = (((ot >> 16) ^ (ot & 0xFFFF) ^ (pid >> 16) ^ (pid & 0xFFFF)) & 0xFFFF) < (unsigned) battleShinyBelow;
    }
}

static void read_save_fields(struct pd_snapshot* s, const struct pd_game* g, pd_read_fn read, void* ctx,
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

enum pd_battle_state pd_battle_state_read(const struct pd_config* cfg, pd_read_fn read, void* ctx, int* battler) {
    if (battler) *battler = 0;
    if (!cfg->battlerControllerFuncs) return PD_BATTLE_NONE;
    uint8_t flags = 0;
    if (!read(ctx, cfg->gMain + (cfg->inBattleOff ? cfg->inBattleOff : 0x439), &flags, 1) || !(flags & 2)) return PD_BATTLE_NONE;
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
    memset(s, 0, sizeof(*s));
    s->cfg = g->cfg;
    s->hour = -1;
    gameShinyBelow = g->shinyBelow;
    {
        const struct pd_party_layout* pl = g->cfg && g->cfg->partyLayout ? g->cfg->partyLayout : &pd_party_layout_vanilla;
        battleShinyBelow = pl->shinyBitsMask ? 0 : g->shinyBelow > 0 ? g->shinyBelow : pl->shinyBelow;
    }
    s->partyActive = -1;
    s->money = -1;
    const struct pd_config* cfg = g->cfg;
    if (!cfg) return;
    if (cfg->gb) {
        pd_gb_snapshot_read(s, cfg, read, ctx);
        return;
    }
    s->valid = true;
    read_u32(read, ctx, cfg->gMain + (cfg->frameCounterOff ? cfg->frameCounterOff : 0x24), &s->frame);

    // --- party ---
    uint8_t count = 0;
    read(ctx, cfg->playerPartyCount, &count, 1);
    if (count > PD_PARTY_SIZE) count = PD_PARTY_SIZE;
    const struct pd_party_layout* lay = cfg->partyLayout ? cfg->partyLayout : &pd_party_layout_vanilla;
    uint8_t raw[PD_PARTY_SIZE * MON_SIZE_MAX];
    if (count && read(ctx, cfg->playerParty, raw, count * lay->size)) {
        for (int i = 0; i < count; i++) {
            if (pd_decode_party_mon_c(raw + i * lay->size, lay, cfg, &s->party[s->partyCount])) s->partyCount++;
        }
    }

    // --- battle --- (gMain.inBattle is bit 1 of the byte at +0x439)
    s->foeActive = s->foeNext = -1;
    uint8_t flags = 0;
    read(ctx, cfg->gMain + (cfg->inBattleOff ? cfg->inBattleOff : 0x439), &flags, 1);
    s->inBattle = (flags & 0x02) != 0;
    s->battleState = pd_battle_state_read(cfg, read, ctx, NULL);
    if (s->inBattle) {
        uint32_t type = 0;
        read_u32(read, ctx, cfg->battleTypeFlags, &type);
        s->isDouble = (type & BATTLE_TYPE_DOUBLE) != 0;
        s->isTrainer = (type & BATTLE_TYPE_TRAINER) != 0;
        uint8_t n = 4, pos[4] = {0, 1, 2, 3};
        read(ctx, cfg->battlersCount, &n, 1);
        if (n < 1 || n > 4) n = 4;
        read(ctx, cfg->battlerPositions, pos, 4);
        const struct pd_battle_layout* bl = cfg->battleLayout ? cfg->battleLayout : &pd_battle_layout_vanilla;
        uint8_t mons[4 * BATTLE_MON_MAX];
        int foeBattler = -1;
        if (read(ctx, cfg->battleMons, mons, (size_t) 4 * bl->size)) {
            for (int b = 0; b < n; b++) {
                int p = pos[b] < 4 ? pos[b] : b;
                decode_battle_mon(mons + b * bl->size, bl, &s->battlers[p]);
                if (p == PD_POS_OPPONENT_LEFT) foeBattler = b;
            }
        }
        if (s->isTrainer && cfg->enemyParty) read_foes(s, cfg, read, ctx, foeBattler);
    }

    // --- location ---
    uint8_t sec[2] = { 0, 0 };
    read(ctx, cfg->mapHeader + MAP_HEADER_MAPSEC_OFF, sec, cfg->mapSecWide ? 2 : 1);
    s->mapsec = cfg->mapSecWide ? u16(sec) : sec[0];

    // --- money: SaveBlock1.money XOR SaveBlock2.encryptionKey ---
    uint32_t sb1 = 0, sb2 = 0, money = 0, key = 0;
    bool haveKey = save_block(cfg, read, ctx, cfg->saveBlock2Ptr, &sb2) && save_key(cfg, read, ctx, sb2, &key);
    if (haveKey && save_block(cfg, read, ctx, cfg->saveBlock1Ptr, &sb1) &&
        read_u32(read, ctx, sb1 + cfg->moneyOff, &money)) {
        uint32_t v = money ^ key;
        if (v <= (cfg->maxMoney ? cfg->maxMoney : MAX_MONEY)) s->money = (long) v;
    }

    // --- bag: quantities XOR the key's low half (readNativeBag); Quetzal's / Elite Redux's bags of their own ---
    if (in_ram(sb1) && cfg->itemFlagsOff) read_item_flags(s, cfg, read, ctx, sb1);
    if (in_ram(sb1) && in_ram(sb2) && cfg->bag3Off) read_bag3(s, cfg, read, ctx, sb1, sb2);
    uint8_t pockets[PD_BAG_POCKETS_MAX * 8];
    int nPockets = cfg->bagCount > PD_BAG_POCKETS_MAX ? PD_BAG_POCKETS_MAX : cfg->bagCount;
    if (haveKey && nPockets > 0 && read(ctx, cfg->bagPockets, pockets, (size_t) nPockets * 8)) {
        s->bagOk = true;
        for (int p = 0; p < nPockets; p++) {
            uint32_t slots = u32(pockets + p * 8);
            int capacity = pockets[p * 8 + 4];
            if (!in_ram(slots) || capacity == 0) continue;
            if (capacity > PD_POCKET_SLOTS) capacity = PD_POCKET_SLOTS;
            uint8_t items[PD_POCKET_SLOTS * 4];
            if (!read(ctx, slots, items, (size_t) capacity * 4)) continue;
            struct pd_pocket_items* out = &s->bag[cfg->bagOrder[p]];
            for (int i = 0; i < capacity && out->count < PD_POCKET_SLOTS; i++) {
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
    if (saveOk) read_save_fields(s, g, read, ctx, sb1, sb2);

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
    if (cfg->cardFlagBytes && cfg->cardFlagBytes <= PD_FLAG_BYTES) {
        uint8_t vars[PD_VAR_COUNT * 2];
        if (!read(ctx, sb1 + cfg->cardFlagsOff, s->cardFlags, cfg->cardFlagBytes) ||
            !read(ctx, sb1 + cfg->cardVarsOff, vars, sizeof vars)) return;
        for (int i = 0; i < PD_VAR_COUNT; i++) s->cardVars[i] = u16(vars + i * 2);
    }
    if (cfg->cardStyle == PD_CARD_SOULGOLD) {
        uint8_t rec[2][32];
        if (!read(ctx, sb2 + 0x8B4, rec[0], 32) || !read(ctx, sb2 + 0x9B6, rec[1], 16)) return;
        for (int i = 0; i < 16; i++) s->frontierRecordHigh |= u16(rec[0] + 2 * i) > 49;
        for (int i = 0; i < 8; i++) s->frontierRecordHigh |= u16(rec[1] + 2 * i) > 49;
    }
    if (cfg->achievementBitsOff) {
        uint8_t magic[2] = { 0xE1, 0xAC };
        if (!read(ctx, sb1 + cfg->achievementBitsOff, s->achievements, sizeof s->achievements)) return;
        if (cfg->achievementMagicOff && !read(ctx, sb1 + cfg->achievementMagicOff, magic, 2)) return;
        s->achievementsOn = u16(magic) == 0xACE1;
    }
    if (cfg->cardMagicOff && !read(ctx, sb2 + cfg->cardMagicOff, &s->cardMagic, 1)) return;
    s->cardOk = true;
}

static void read_save_fields(struct pd_snapshot* s, const struct pd_game* g, pd_read_fn read, void* ctx,
                             uint32_t sb1, uint32_t sb2) {
    const struct pd_config* cfg = g->cfg;
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

    // The seen / caught bit arrays (the app's readPokedexState): where there are anti-cheat copies of
    // seen, a bit only counts if every copy agrees, and caught needs seen too. Kept by national number.
    // Quetzal keeps a 3-bit level per species (8 to a u32): any level is seen, caught is a bit in any of three
    // regions' arrays or (Alpha 0.8.4) a level of 2 or more.
    uint8_t magic = 0;
    int count = cfg->nationalCount;
    if (cfg->dexBlock && cfg->dexBytes && cfg->dexBytes <= PD_DEX_BYTES && count >= 1 && count <= PD_DEX_BYTES * 8 &&
        (!cfg->dexBySpecies || (g->rom && pd_dex_available(g)))) {
        uint8_t seen[PD_DEX_BYTES * 4], owned[PD_DEX_BYTES], copy[2][PD_DEX_BYTES], region[PD_DEX_BYTES];
        uint32_t base = cfg->dexBlock == PD_DEX_SB1 ? sb1 : cfg->dexBlock == PD_DEX_SB2 ? sb2 : 0;
        const uint32_t copyOff[2] = { cfg->dexSeenCopy1, cfg->dexSeenCopy2 };
        const uint32_t regionOff[3] = { cfg->dexCaughtRegion1, cfg->dexCaughtRegion2, cfg->dexCaughtRegion3 };
        bool levels = cfg->dexSeenLevels;
        bool ok = read(ctx, base + cfg->dexSeenOff, seen, levels ? cfg->dexBytes * 4u : cfg->dexBytes) &&
            (cfg->nationalMagicOff < 0 || read(ctx, sb2 + 0x18 + (uint32_t) cfg->nationalMagicOff, &magic, 1));
        memset(owned, 0, sizeof owned);
        if (ok && levels) {
            for (int k = 0; ok && k < 3; k++) {
                if (!regionOff[k]) continue;
                ok = read(ctx, base + regionOff[k], region, cfg->dexBytes);
                for (int j = 0; ok && j < cfg->dexBytes; j++) owned[j] |= region[j];
            }
        } else if (ok) {
            ok = read(ctx, base + cfg->dexCaughtOff, owned, cfg->dexBytes);
        }
        for (int k = 0; ok && k < 2; k++) ok = !copyOff[k] || read(ctx, sb1 + copyOff[k], copy[k], cfg->dexBytes);
        if (ok) {
            s->dexOk = true;
            s->dexCount = count;
            if (cfg->cardStyle == PD_CARD_LAZARUS) {
                uint8_t stray = 0; // TrainerCard.kt readCaught(stray): (0 - 1) & 0xFFFF lands past the array
                s->dexStray = read(ctx, base + cfg->dexCaughtOff + 0x1FFF, &stray, 1) && (stray >> 7 & 1);
            }
            s->dexNational = cfg->nationalMagicOff < 0 || magic == cfg->nationalMagic;
            memset(s->dexSeen, 0, sizeof s->dexSeen);
            memset(s->dexCaught, 0, sizeof s->dexCaught);
            for (int n = 1; n <= count; n++) {
                // Emerald Rogue keeps its bits by species id; the ROM's table says which species No. n is.
                int i = (cfg->dexBySpecies ? pd_dex_species(g, n) : n) - cfg->dexFirstBit;
                if (i < 0 || (cfg->dexBySpecies && i + cfg->dexFirstBit == 0)) continue;
                int byte = i / 8, bit = i % 8;
                bool sn, cn;
                if (levels) {
                    if (byte >= cfg->dexBytes) continue;
                    unsigned lvl = (u32(seen + i / 8 * 4) >> (3 * (i % 8))) & 7;
                    sn = lvl >= 1;
                    cn = cfg->dexCaughtFromLevel ? lvl >= 2 : (owned[byte] >> bit & 1);
                } else {
                    if (byte >= cfg->dexBytes) continue;
                    sn = seen[byte] >> bit & 1;
                    cn = owned[byte] >> bit & 1;
                }
                if (cfg->dexSeenOrCaught) sn = sn || cn; // Rogue's 2-bit state: a caught species is seen
                for (int k = 0; k < 2; k++) if (copyOff[k]) sn = sn && (copy[k][byte] >> bit & 1);
                if (copyOff[0]) cn = cn && sn; // with copies, caught needs seen too
                if (sn) s->dexSeen[(n - 1) / 8] |= (uint8_t) (1 << ((n - 1) % 8));
                if (cn) s->dexCaught[(n - 1) / 8] |= (uint8_t) (1 << ((n - 1) % 8));
            }
        }
    }

    if (cfg->cardStyle) read_card_fields(s, cfg, read, ctx, sb1, sb2);

    if (cfg->flagBytes && cfg->flagBytes <= PD_FLAG_BYTES && read(ctx, sb1 + cfg->flagsOff, s->flags, cfg->flagBytes)) {
        uint8_t vars[PD_VAR_COUNT * 2];
        if (read(ctx, sb1 + cfg->varsOff, vars, sizeof(vars))) {
            s->flagsOk = true;
            for (int i = 0; i < PD_VAR_COUNT; i++) s->vars[i] = u16(vars + i * 2);
        }
        uint32_t fb = (cfg->flagBank.count + 7) / 8;
        if (fb && fb <= PD_BANK_FLAG_BYTES && !read(ctx, sb1 + cfg->flagBank.sb1Off, s->bankFlags, fb)) memset(s->bankFlags, 0, fb);
        if (cfg->varBank.count && cfg->varBank.count <= PD_BANK_VARS) {
            uint8_t bv[PD_BANK_VARS * 2];
            if (read(ctx, sb1 + cfg->varBank.sb1Off, bv, cfg->varBank.count * 2))
                for (uint32_t i = 0; i < cfg->varBank.count; i++) s->bankVars[i] = u16(bv + i * 2);
        }
    }
    s->hour = -1;
    uint8_t hour;
    if (cfg->nightWild && read(ctx, cfg->nightWild->hourAddr, &hour, 1)) s->hour = (int8_t) hour;
}

bool pd_flag(const struct pd_snapshot* s, int flag) {
    if (!s->flagsOk || flag < 0) return false;
    const struct pd_config* cfg = s->cfg;
    if (cfg && cfg->flagBank.count && (uint32_t) flag >= cfg->flagBank.firstId &&
        (uint32_t) flag < cfg->flagBank.firstId + cfg->flagBank.count) {
        uint32_t i = (uint32_t) flag - cfg->flagBank.firstId;
        return i / 8 < PD_BANK_FLAG_BYTES && (s->bankFlags[i / 8] >> (i % 8) & 1);
    }
    return flag / 8 < PD_FLAG_BYTES && (s->flags[flag / 8] >> (flag % 8) & 1);
}

int pd_var(const struct pd_snapshot* s, int var) {
    if (!s->flagsOk) return 0;
    const struct pd_config* cfg = s->cfg;
    if (cfg && cfg->varBank.count && (uint32_t) var >= cfg->varBank.firstId && (uint32_t) var < cfg->varBank.firstId + cfg->varBank.count)
        return var - (int) cfg->varBank.firstId < PD_BANK_VARS ? s->bankVars[var - cfg->varBank.firstId] : 0;
    int i = var - 0x4000;
    return i >= 0 && i < PD_VAR_COUNT ? s->vars[i] : 0;
}

static bool dex_bit(const uint8_t* bits, int national, int count) {
    int i = national - 1;
    return i >= 0 && i < count && (bits[i / 8] >> (i % 8) & 1);
}

bool pd_dex_seen(const struct pd_snapshot* s, int national) {
    return s->dexOk && dex_bit(s->dexSeen, national, s->dexCount);
}

bool pd_dex_caught(const struct pd_snapshot* s, int national) {
    return s->dexOk && dex_bit(s->dexCaught, national, s->dexCount);
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
    if (g->cfg && g->cfg->text && g->cfg->text->items) return item_entry(g->cfg->text->items, g->cfg->text->itemsCount, item);
    if (g->kind == PD_GAME_EMERALD) return item_entry(pd_item_names_emerald, pd_item_names_emerald_count, item);
    return item_entry(pd_item_names_firered, pd_item_names_firered_count, item);
}

// RomItemText.read: the description pointer in the game's item table, the text one line (breaks and runs of spaces
// collapsed). NULL = not readable here (no ROM, no table, an id outside it, a pointer off the ROM).
static const char* rom_item_description(const struct pd_game* g, int item) {
    const struct pd_item_descs* t = g->cfg ? g->cfg->itemDescs : NULL;
    if (!t || !g->rom || item <= 0) return NULL;
    static struct { unsigned rom; int item; char text[200]; } cache[8];
    static int next;
    for (int i = 0; i < 8; i++)
        if (cache[i].rom == pd_rom_id(g) && cache[i].item == item) return cache[i].text;
    // The table must be where the config says: item 1's record holds id 1 (where records carry their id).
    if (t->idOff >= 0) {
        uint8_t id[2];
        if (!pd_rom_read(g, t->base + (uint32_t) t->stride + (uint32_t) t->idOff, id, 2) || (id[0] | id[1] << 8) != 1) return NULL;
    }
    while (t && (item < t->first || (t->count && item - t->first >= t->count))) t = t->next;
    if (!t) return NULL;
    uint8_t p[4];
    if (!pd_rom_read(g, t->base + (uint32_t) ((item - t->first) * t->stride + t->descOff), p, 4)) return NULL;
    uint32_t ptr = u32(p);
    const uint8_t* src = pd_rom_at(g, ptr, 1);
    if (!src) return NULL;
    size_t avail = g->romSize - (ptr - 0x08000000u);
    char raw[256];
    pd_gen3_text(src, avail < 200 ? avail : 200, raw, sizeof raw);
    char* out = cache[next].text;
    size_t n = 0;
    for (const char* c = raw; *c && n + 1 < sizeof cache[0].text; c++) {
        if (*c == ' ' && (n == 0 || out[n - 1] == ' ')) continue;
        out[n++] = *c;
    }
    while (n && out[n - 1] == ' ') n--;
    out[n] = 0;
    cache[next].rom = pd_rom_id(g);
    cache[next].item = item;
    next = (next + 1) % 8;
    return out;
}

const char* pd_item_description(const struct pd_game* g, int item) {
    // The game's own text from its ROM, as the app's RomItemText; no ROM, no text (the games' prose is never bundled).
    const char* rom = rom_item_description(g, item);
    return rom ? rom : "";
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

// A FireRed 1.0 hack's own map-section names, read where its region_map.c loads them (the app's RomRegionMap.read):
// the literal pools of rev 0's code, each pointer landing on data of the right shape, or none at all.
#define RRM_FIRST 0x58    // MAPSEC_PALLET_TOWN, where sMapNames starts
#define RRM_SECTIONS 109  // sMapNames' length
#define RRM_NAME_LEN 24

static bool rrm_ptr(const struct pd_game* g, uint32_t lit, uint32_t* out) {
    uint8_t b[4];
    if (!pd_rom_read(g, 0x08000000u + lit, b, 4)) return false;
    uint32_t p = b[0] | b[1] << 8 | b[2] << 16 | (uint32_t) b[3] << 24;
    if (p < 0x08000000u || p - 0x08000000u >= g->romSize) return false;
    *out = p;
    return true;
}

// An LZ77 blob at the pointer the literal holds, whose decoded size passes ok(size).
static bool rrm_lz(const struct pd_game* g, uint32_t lit, size_t minSize, size_t maxSize, size_t align) {
    static uint8_t buf[0x12000];
    uint32_t p;
    if (!rrm_ptr(g, lit, &p)) return false;
    const uint8_t* src = pd_rom_at(g, p, 4);
    if (!src || src[0] != 0x10) return false;
    size_t size = src[1] | (size_t) src[2] << 8 | (size_t) src[3] << 16;
    if (size < minSize || size > maxSize || size > sizeof buf || size % align) return false;
    size_t avail = g->romSize - (p - 0x08000000u);
    return pd_lz77(src, avail < 0x12000 ? avail : 0x12000, buf, size) == size;
}

static const char* rom_mapsec_name(const struct pd_game* g, int mapsec) {
    static unsigned cachedFor;
    static bool ok;
    static char names[RRM_SECTIONS][RRM_NAME_LEN];
    if (cachedFor != pd_rom_id(g)) {
        cachedFor = pd_rom_id(g);
        ok = false;
        memset(names, 0, sizeof names);
        uint8_t rev = 0;
        uint32_t namesPtr, p;
        static const uint32_t TILEMAPS[] = {0xC035C, 0xC0370, 0xC0388, 0xC03A4};
        bool shaped = g->rom && !strcmp(g->code, "BPRE") && pd_rom_read(g, 0x080000BC, &rev, 1) && rev != 1 &&
                      rrm_lz(g, 0xC0330, 32, 0x12000, 32) && rrm_ptr(g, 0xC02EC, &p) && rrm_ptr(g, 0xC3D3C, &p) &&
                      rrm_ptr(g, 0xC3D38, &p) && rrm_ptr(g, 0xC00BC, &p) && rrm_ptr(g, 0xC0C94, &namesPtr);
        for (int i = 0; shaped && i < 4; i++) shaped = rrm_lz(g, TILEMAPS[i], 30 * 20, 0x800, 2);
        for (int i = 0; shaped && i < RRM_SECTIONS; i++) {
            uint32_t at;
            if (!rrm_ptr(g, namesPtr - 0x08000000u + (uint32_t) i * 4, &at)) break;
            const uint8_t* src = pd_rom_at(g, at, 1);
            if (!src) break;
            size_t avail = g->romSize - (at - 0x08000000u);
            pd_gen3_text(src, avail < 32 ? avail : 32, names[i], RRM_NAME_LEN);
            // titleCase: an all-capitals name in the app's casing ("PALLET TOWN" -> "Pallet Town")
            bool lower = false;
            for (char* c = names[i]; *c; c++) lower |= *c >= 'a' && *c <= 'z';
            if (!lower) {
                bool start = true;
                for (char* c = names[i]; *c; c++) {
                    if (*c >= 'A' && *c <= 'Z' && !start) *c = (char) (*c - 'A' + 'a');
                    bool letter = (*c >= 'A' && *c <= 'Z') || (*c >= 'a' && *c <= 'z') || (unsigned char) *c >= 0x80;
                    start = !letter && *c != '\'';
                }
            }
            if (names[i][0]) ok = true;
        }
    }
    int i = mapsec - RRM_FIRST;
    return ok && i >= 0 && i < RRM_SECTIONS && names[i][0] ? names[i] : NULL;
}

const char* pd_mapsec_name(const struct pd_game* g, int mapsec) {
    const struct pd_text* t = g->cfg ? g->cfg->text : NULL;
    if (g->hackMaps && !(t && t->mapsecs)) return "";
    if (t && t->mapsecsRom) {
        const char* n = rom_mapsec_name(g, mapsec);
        if (n) return n;
    }
    if (t && t->mapsecs) return mapsec >= 0 && mapsec < t->mapsecsCount && t->mapsecs[mapsec] ? t->mapsecs[mapsec] : "";
    // Emerald's map sections start at 0 (Littleroot); FireRed's Kanto ones at 88.
    const char* const* tb = g->kind == PD_GAME_EMERALD ? pd_mapsec_emerald : pd_mapsec_firered;
    int n = g->kind == PD_GAME_EMERALD ? pd_mapsec_emerald_count : pd_mapsec_firered_count;
    if (mapsec >= 0 && mapsec < n && tb[mapsec]) return tb[mapsec];
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
    const struct pd_party_layout* lay = cfg->partyLayout ? cfg->partyLayout : &pd_party_layout_vanilla;
    uint8_t raw[PD_PARTY_SIZE * MON_SIZE_MAX];
    if (!read(ctx, cfg->enemyParty, raw, PD_PARTY_SIZE * lay->size)) return;
    for (int i = 0; i < PD_PARTY_SIZE; i++) {
        if (pd_decode_party_mon_c(raw + i * lay->size, lay, cfg, &s->foes[s->foeCount])) s->foeCount++;
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
