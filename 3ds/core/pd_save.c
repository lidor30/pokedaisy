#include "pd_save.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "pd_experience.h"
#include "pd_tables.h"

#define ID_OFF 0xFF4
#define CHECKSUM_OFF 0xFF6
#define SIGNATURE_OFF 0xFF8
#define COUNTER_OFF 0xFFC
#define SIGNATURE 0x08012025u
#define BOX_NAMES_OFF (4 + PD_SAVE_BOXES * PD_SAVE_BOX_SLOTS * PD_SAVE_RECORD)
#define BOX_NAME 9
#define POKEDEX_OFF 0x18 // SaveBlock2.pokedex: owned[] at +0x10, seen[] at +0x44
#define NATIONAL_COUNT 386
#define HOENN_COUNT 202
#define KANTO_COUNT 151
#define SPECIES_MEW 151
#define SPECIES_UNOWN 201
#define SPECIES_SPINDA 308
#define SPECIES_DEOXYS 410
#define ITEM_ENIGMA_BERRY 175
#define ITEM_EVERSTONE 195
#define MAIL_FIRST 121
#define MAIL_LAST 132

static const struct pd_save_layout LAYOUTS[] = {
    { PD_SAVE_RUBY_SAPPHIRE, "RUBY / SAPPHIRE", 0x890, 0x3AC0, 0x234, 0x238, 0x490, 0x494, -1, 0x1220, 0x1340, 0x938, 0x3A8C,
      0x1A, 0xDA, 0x4046, 0x302, 0x836, -1, 0x807, false },
    { PD_SAVE_EMERALD, "EMERALD", 0xF2C, 0x3D88, 0x234, 0x238, 0x490, 0x494, 0xAC, 0x1270, 0x139C, 0x988, 0x3B24,
      0x1A, 0xDA, 0x4046, 0x302, 0x896, -1, 0x867, false },
    { PD_SAVE_FIRERED_LEAFGREEN, "FIRERED / LEAFGREEN", 0xF24, 0x3D68, 0x34, 0x38, 0x290, 0x294, 0xF20, 0xEE0, 0x1000, 0x5F8, 0x3A18,
      0x1B, 0xB9, 0x404E, 0x6258, 0x840, 0x844, 0x820, true },
};

// gen3SubstructOrder[personality % 24][slot] = logical substruct (0 Growth, 1 Attacks, 2 EVs, 3 Misc).
static const uint8_t ORDER[24][4] = {
    {0, 1, 2, 3}, {0, 1, 3, 2}, {0, 2, 1, 3}, {0, 2, 3, 1}, {0, 3, 1, 2}, {0, 3, 2, 1},
    {1, 0, 2, 3}, {1, 0, 3, 2}, {1, 2, 0, 3}, {1, 2, 3, 0}, {1, 3, 0, 2}, {1, 3, 2, 0},
    {2, 0, 1, 3}, {2, 0, 3, 1}, {2, 1, 0, 3}, {2, 1, 3, 0}, {2, 3, 0, 1}, {2, 3, 1, 0},
    {3, 0, 1, 2}, {3, 0, 2, 1}, {3, 1, 0, 2}, {3, 1, 2, 0}, {3, 2, 0, 1}, {3, 2, 1, 0},
};

static uint16_t u16(const uint8_t* p) { return (uint16_t) (p[0] | p[1] << 8); }
static uint32_t u32(const uint8_t* p) { return (uint32_t) p[0] | (uint32_t) p[1] << 8 | (uint32_t) p[2] << 16 | (uint32_t) p[3] << 24; }
static void put16(uint8_t* p, unsigned v) { p[0] = (uint8_t) v; p[1] = (uint8_t) (v >> 8); }
static void put32(uint8_t* p, uint32_t v) { put16(p, v & 0xFFFF); put16(p + 2, v >> 16); }

const struct pd_save_layout* pd_save_layout_for(const char* code) {
    if (!code || strlen(code) < 4 || code[3] == 'J') return NULL;
    if (!strncmp(code, "AXV", 3) || !strncmp(code, "AXP", 3)) return &LAYOUTS[0];
    if (!strncmp(code, "BPE", 3)) return &LAYOUTS[1];
    if (!strncmp(code, "BPR", 3) || !strncmp(code, "BPG", 3)) return &LAYOUTS[2];
    return NULL;
}

// sSaveSlotLayout's size for sector [id]: SaveBlock2 in 0, SaveBlock1 in 1-4, PokemonStorage in 5-13.
static int chunk_size(const struct pd_save_layout* f, int id) {
    int size = id == 0 ? f->block2Size : id <= 4 ? f->block1Size - (id - 1) * PD_SAVE_DATA : PD_SAVE_STORAGE - (id - 5) * PD_SAVE_DATA;
    if (id == 0) return size;
    return size < 0 ? 0 : size > PD_SAVE_DATA ? PD_SAVE_DATA : size;
}

static uint8_t* chunk_of(uint8_t* b2, uint8_t* b1, uint8_t* st, int id) {
    return id == 0 ? b2 : id <= 4 ? b1 + (id - 1) * PD_SAVE_DATA : st + (id - 5) * PD_SAVE_DATA;
}

// save.c's CalculateChecksum: the u32 words summed, both halves folded into a u16.
static uint16_t checksum(const uint8_t* p, int size) {
    uint32_t sum = 0;
    for (int i = 0; i < size / 4; i++) sum += u32(p + i * 4);
    return (uint16_t) ((sum >> 16) + sum);
}

struct slot { enum pd_save_slot_status status; uint32_t counter; bool sameCounter; int base[PD_SAVE_SLOT_SECTORS]; };

static void read_slot(const uint8_t* b, int slot, const struct pd_save_layout* f, struct slot* out) {
    bool anySignature = false, mixed = false;
    unsigned valid = 0;
    uint32_t counter = 0;
    for (int i = 0; i < PD_SAVE_SLOT_SECTORS; i++) out->base[i] = -1;
    for (int i = 0; i < PD_SAVE_SLOT_SECTORS; i++) {
        int off = (slot * PD_SAVE_SLOT_SECTORS + i) * PD_SAVE_SECTOR;
        if (u32(b + off + SIGNATURE_OFF) != SIGNATURE) continue;
        anySignature = true;
        int id = u16(b + off + ID_OFF);
        if (id >= PD_SAVE_SLOT_SECTORS) continue;
        if (u16(b + off + CHECKSUM_OFF) != checksum(b + off, chunk_size(f, id))) continue;
        // Each id once: a slot holding one chunk twice isn't one the game wrote.
        if (out->base[id] >= 0) {
            out->status = PD_SAVE_SLOT_CORRUPT;
            out->sameCounter = false;
            return;
        }
        out->base[id] = off;
        uint32_t c = u32(b + off + COUNTER_OFF);
        if (valid && c != counter) mixed = true;
        counter = c;
        valid |= 1u << id;
    }
    out->status = !anySignature ? PD_SAVE_SLOT_EMPTY : valid == (1u << PD_SAVE_SLOT_SECTORS) - 1 ? PD_SAVE_SLOT_OK : PD_SAVE_SLOT_CORRUPT;
    out->counter = counter;
    out->sameCounter = !mixed;
}

// GetSaveValidStatus's pick between two good slots: the larger counter, with its 0xFFFFFFFF -> 0 wrap.
static int newer(uint32_t c0, uint32_t c1) {
    if ((c0 == 0xFFFFFFFFu && c1 == 0) || (c0 == 0 && c1 == 0xFFFFFFFFu)) return c0 == 0xFFFFFFFFu ? 1 : 0;
    return c0 < c1 ? 1 : 0;
}

// Reads [bytes] into the blocks (as read and the working copies); [verifyOnly] leaves s's blocks alone and compares.
static const char* parse_into(const uint8_t* bytes, size_t len, const struct pd_save_layout* f, int* slotOut, uint32_t* counterOut,
                              int* rotationOut, enum pd_save_slot_status* otherOut, uint8_t* b2, uint8_t* b1, uint8_t* st) {
    if (!f) return "Not a save this editor knows";
    if (len < PD_SAVE_MIN_SIZE) return "Too small for a Gen 3 save";
    static struct slot slots[2];
    read_slot(bytes, 0, f, &slots[0]);
    read_slot(bytes, 1, f, &slots[1]);
    bool ok0 = slots[0].status == PD_SAVE_SLOT_OK, ok1 = slots[1].status == PD_SAVE_SLOT_OK;
    if (!ok0 && !ok1) {
        return slots[0].status == PD_SAVE_SLOT_EMPTY && slots[1].status == PD_SAVE_SLOT_EMPTY ? "The save is empty: save once in the game first"
                                                                                     : "Neither save slot is whole: the game would call it corrupted";
    }
    int pick = ok0 && ok1 ? newer(slots[0].counter, slots[1].counter) : ok0 ? 0 : 1;
    const struct slot* s = &slots[pick];
    if (!s->sameCounter) return "The save's sectors disagree on its save counter";
    memset(b2, 0, PD_SAVE_BLOCK2_MAX);
    memset(b1, 0, PD_SAVE_BLOCK1_MAX);
    memset(st, 0, PD_SAVE_STORAGE);
    for (int id = 0; id < PD_SAVE_SLOT_SECTORS; id++)
        memcpy(chunk_of(b2, b1, st, id), bytes + s->base[id], (size_t) chunk_size(f, id));
    *slotOut = pick;
    *counterOut = s->counter;
    *rotationOut = (s->base[0] / PD_SAVE_SECTOR) % PD_SAVE_SLOT_SECTORS;
    *otherOut = slots[1 - pick].status;
    return NULL;
}

const char* pd_save_parse(struct pd_save* s, const uint8_t* bytes, size_t len, const struct pd_save_layout* f) {
    s->f = f;
    s->rawLen = 0;
    if (len > PD_SAVE_MAX_SIZE) return "Too big for a Gen 3 save";
    const char* why = parse_into(bytes, len, f, &s->slot, &s->counter, &s->rotation, &s->otherSlot, s->read2, s->read1, s->readStorage);
    if (why) return why;
    memcpy(s->raw, bytes, len);
    s->rawLen = len;
    pd_save_revert(s);
    return NULL;
}

void pd_save_revert(struct pd_save* s) {
    memcpy(s->block2, s->read2, sizeof(s->block2));
    memcpy(s->block1, s->read1, sizeof(s->block1));
    memcpy(s->storage, s->readStorage, sizeof(s->storage));
}

bool pd_save_changed(const struct pd_save* s) {
    return memcmp(s->block2, s->read2, sizeof(s->block2)) || memcmp(s->block1, s->read1, sizeof(s->block1)) ||
           memcmp(s->storage, s->readStorage, sizeof(s->storage));
}

const char* pd_save_build(const struct pd_save* s, uint8_t* out) {
    const struct pd_save_layout* f = s->f;
    if (!f || !s->rawLen) return "No save";
    memcpy(out, s->raw, s->rawLen);
    int target = 1 - s->slot;
    uint32_t counter = s->counter + 1;
    int rotation = (s->rotation + 1) % PD_SAVE_SLOT_SECTORS;
    // The working copies, const in spirit: chunk_of only points into them.
    uint8_t* b2 = (uint8_t*) s->block2;
    uint8_t* b1 = (uint8_t*) s->block1;
    uint8_t* st = (uint8_t*) s->storage;
    for (int id = 0; id < PD_SAVE_SLOT_SECTORS; id++) {
        uint8_t* sector = out + (target * PD_SAVE_SLOT_SECTORS + (id + rotation) % PD_SAVE_SLOT_SECTORS) * PD_SAVE_SECTOR;
        memset(sector, 0, PD_SAVE_SECTOR);
        int size = chunk_size(f, id);
        memcpy(sector, chunk_of(b2, b1, st, id), (size_t) size);
        put16(sector + ID_OFF, (unsigned) id);
        put16(sector + CHECKSUM_OFF, checksum(sector, size));
        put32(sector + SIGNATURE_OFF, SIGNATURE);
        put32(sector + COUNTER_OFF, counter);
    }
    // Read back: the game must load the new slot, find exactly these blocks there and still have the old one to fall back on.
    static uint8_t a2[PD_SAVE_BLOCK2_MAX], a1[PD_SAVE_BLOCK1_MAX], ast[PD_SAVE_STORAGE];
    int slot, rot;
    uint32_t c;
    enum pd_save_slot_status other;
    if (parse_into(out, s->rawLen, f, &slot, &c, &rot, &other, a2, a1, ast)) return "The new save doesn't read back";
    if (slot == s->slot) return "The game would still load the old save";
    if (c != counter) return "Wrong save counter";
    if (other != PD_SAVE_SLOT_OK) return "The previous save didn't survive";
    if (memcmp(a2, s->block2, sizeof(a2)) || memcmp(a1, s->block1, sizeof(a1)) || memcmp(ast, s->storage, sizeof(ast)))
        return "The blocks differ after reading back";
    size_t start = (size_t) target * PD_SAVE_SLOT_SECTORS * PD_SAVE_SECTOR, end = start + PD_SAVE_SLOT_SECTORS * PD_SAVE_SECTOR;
    for (size_t i = 0; i < s->rawLen; i++)
        if ((i < start || i >= end) && out[i] != s->raw[i]) return "A byte outside the new slot changed";
    return NULL;
}

// --- box records ---

void pd_box_mon_read(struct pd_box_mon* m, const uint8_t* r) {
    memcpy(m->header, r, 32);
    memcpy(m->original, r, PD_SAVE_RECORD);
    uint32_t personality = u32(r), key = personality ^ u32(r + 4);
    const uint8_t* order = ORDER[personality % 24];
    uint16_t sum = 0;
    for (int slot = 0; slot < 4; slot++) {
        for (int w = 0; w < 3; w++) {
            uint32_t word = u32(r + 0x20 + slot * 12 + w * 4) ^ key;
            put32(m->data + order[slot] * 12 + w * 4, word);
            sum = (uint16_t) (sum + (word & 0xFFFF) + (word >> 16));
        }
    }
    m->checksumOk = sum == u16(r + 0x1C);
}

void pd_box_mon_encode(const struct pd_box_mon* m, uint8_t out[PD_SAVE_RECORD]) {
    if (!m->checksumOk) {
        memcpy(out, m->original, PD_SAVE_RECORD); // never re-encode a record that couldn't be read
        return;
    }
    memcpy(out, m->header, 32);
    uint16_t sum = 0;
    for (int i = 0; i < 24; i++) sum = (uint16_t) (sum + u16(m->data + i * 2));
    put16(out + 0x1C, sum);
    uint32_t personality = u32(m->header), key = personality ^ u32(m->header + 4);
    const uint8_t* order = ORDER[personality % 24];
    for (int slot = 0; slot < 4; slot++)
        for (int w = 0; w < 3; w++) put32(out + 0x20 + slot * 12 + w * 4, u32(m->data + order[slot] * 12 + w * 4) ^ key);
}

int pd_box_mon_species(const struct pd_box_mon* m) { return u16(m->data); }
int pd_box_mon_item(const struct pd_box_mon* m) { return u16(m->data + 2); }
uint32_t pd_box_mon_personality(const struct pd_box_mon* m) { return u32(m->header); }
bool pd_box_mon_empty(const struct pd_box_mon* m) { return m->checksumOk && pd_box_mon_species(m) == 0; }
bool pd_box_mon_bad_egg(const struct pd_box_mon* m) { return (m->header[0x13] & 1) || !m->checksumOk; }
bool pd_box_mon_egg(const struct pd_box_mon* m) { return u32(m->data + 40) >> 30 & 1; }
bool pd_box_mon_fateful(const struct pd_box_mon* m) { return u32(m->data + 44) >> 31 & 1; }

int pd_save_level_for(int species, uint32_t exp) {
    if (species <= 0 || species >= pd_growth_rates_count) return 0;
    int rate = pd_growth_rates[species];
    if (rate < 0 || rate > 5) return 0;
    int level = 1;
    while (level < 100 && exp >= pd_experience_tables[rate][level + 1]) level++;
    return level;
}

static int record_off(int box, int slot) {
    return 4 + (box * PD_SAVE_BOX_SLOTS + slot) * PD_SAVE_RECORD;
}

static bool box_ok(int box, int slot) {
    return box >= 0 && box < PD_SAVE_BOXES && slot >= 0 && slot < PD_SAVE_BOX_SLOTS;
}

static void read_box_mon(const struct pd_save* s, int box, int slot, struct pd_box_mon* m) {
    pd_box_mon_read(m, s->storage + record_off(box, slot));
}

// --- reading ---

static int key16(const struct pd_save* s) {
    return s->f->encryptionKeyOff >= 0 ? u16(s->block2 + s->f->encryptionKeyOff) : 0;
}

void pd_save_trainer_name(const struct pd_save* s, char* out, size_t len) {
    pd_gen3_text(s->block2, 7, out, len);
}

int pd_save_gender(const struct pd_save* s) { return s->block2[8] ? 1 : 0; }
int pd_save_trainer_id(const struct pd_save* s) { return u16(s->block2 + 0x0A); }
int pd_save_play_hours(const struct pd_save* s) { return u16(s->block2 + 0x0E); }
int pd_save_play_minutes(const struct pd_save* s) { return s->block2[0x10]; }

long pd_save_money(const struct pd_save* s) {
    uint32_t key = s->f->encryptionKeyOff >= 0 ? u32(s->block2 + s->f->encryptionKeyOff) : 0;
    return (long) (u32(s->block1 + s->f->moneyOff) ^ key);
}

int pd_save_coins(const struct pd_save* s) {
    return u16(s->block1 + s->f->coinsOff) ^ key16(s);
}

bool pd_save_flag(const struct pd_save* s, int flag) {
    return s->block1[s->f->flagsOff + flag / 8] >> (flag % 8) & 1;
}

static int var(const struct pd_save* s, int id) {
    return u16(s->block1 + s->f->varsOff + (id - 0x4000) * 2);
}

int pd_save_badges(const struct pd_save* s) {
    int n = 0;
    for (int i = 0; i < 8; i++) n += pd_save_flag(s, s->f->badgeFlag + i);
    return n;
}

int pd_save_dex_caught(const struct pd_save* s) {
    int n = 0;
    for (int i = 0; i < NATIONAL_COUNT; i++) n += s->block2[POKEDEX_OFF + 0x10 + i / 8] >> (i % 8) & 1;
    return n;
}

// IsNationalPokedexEnabled, as each game checks it.
bool pd_save_national_dex(const struct pd_save* s) {
    const struct pd_save_layout* f = s->f;
    return s->block2[f->nationalMagicOff] == f->nationalMagic && var(s, f->nationalVar) == f->nationalVarValue && pd_save_flag(s, f->nationalFlag);
}

int pd_save_party_count(const struct pd_save* s) {
    return s->block1[s->f->partyCountOff];
}

bool pd_save_party_mon(const struct pd_save* s, int i, struct pd_mon* out) {
    if (i < 0 || i >= pd_save_party_count(s) || i >= 6) return false;
    return pd_decode_party_mon(s->block1 + s->f->partyOff + i * PD_SAVE_PARTY_MON, out);
}

bool pd_save_box_mon(const struct pd_save* s, int box, int slot, struct pd_mon* out) {
    if (!box_ok(box, slot)) return false;
    const uint8_t* r = s->storage + record_off(box, slot);
    if (!u32(r) && !u32(r + 4)) return false;
    // The record is a struct Pokemon's first part: the party fields read as nothing.
    uint8_t mon[PD_SAVE_PARTY_MON] = { 0 };
    memcpy(mon, r, PD_SAVE_RECORD);
    if (!pd_decode_party_mon(mon, out)) return false;
    out->level = out->egg ? 0 : pd_save_level_for(out->species, out->experience);
    out->hp = out->maxHp = 0;
    out->status = 0;
    return true;
}

void pd_save_box_name(const struct pd_save* s, int box, char* out, size_t len) {
    pd_gen3_text(s->storage + BOX_NAMES_OFF + box * BOX_NAME, BOX_NAME, out, len);
}

int pd_save_box_count(const struct pd_save* s, int box) {
    int n = 0;
    for (int i = 0; i < PD_SAVE_BOX_SLOTS; i++) {
        const uint8_t* r = s->storage + record_off(box, i);
        n += u32(r) || u32(r + 4);
    }
    return n;
}

int pd_save_current_box(const struct pd_save* s) {
    return s->storage[0] < PD_SAVE_BOXES ? s->storage[0] : 0;
}

const char* pd_save_mismatch(const struct pd_save* s) {
    const struct pd_save_layout* f = s->f;
    int count = pd_save_party_count(s);
    // 0 before the first Pokémon (a save made before the starter).
    if (count > 6) return "Its party doesn't read: another game's save?";
    for (int i = 0; i < count; i++) {
        struct pd_box_mon m;
        const uint8_t* raw = s->block1 + f->partyOff + i * PD_SAVE_PARTY_MON;
        pd_box_mon_read(&m, raw);
        int species = pd_box_mon_species(&m), level = raw[0x54];
        if (!m.checksumOk || species < 1 || species > PD_SAVE_SPECIES || (!pd_box_mon_egg(&m) && (level < 1 || level > 100)))
            return "Its party doesn't read: another game's save?";
    }
    // The game empties the slots past the count (ZeroMonData): zeroes, but the mail byte (+0x55) MAIL_NONE in every slot a
    // party ever shrank from. A whole Pokémon left there (a party some tool shrank) is still this layout's; anything else
    // is another layout's data.
    for (int i = count; i < 6; i++) {
        const uint8_t* r = s->block1 + f->partyOff + i * PD_SAVE_PARTY_MON;
        bool empty = true;
        for (int b = 0; b < PD_SAVE_PARTY_MON; b++) empty &= r[b] == 0 || (b == 0x55 && r[b] == 0xFF);
        if (empty) continue;
        struct pd_box_mon m;
        pd_box_mon_read(&m, r);
        int sp = pd_box_mon_species(&m);
        if (!m.checksumOk || sp < 1 || sp >= PD_SAVE_SPECIES) return "Its party doesn't read: another game's save?";
    }
    if (s->storage[0] >= PD_SAVE_BOXES) return "Its PC doesn't read: another game's save?";
    int used = 0, unreadable = 0;
    for (int b = 0; b < PD_SAVE_BOXES; b++) {
        for (int i = 0; i < PD_SAVE_BOX_SLOTS; i++) {
            struct pd_box_mon m;
            read_box_mon(s, b, i, &m);
            if (pd_box_mon_empty(&m)) continue;
            used++;
            int sp = pd_box_mon_species(&m);
            if (pd_box_mon_bad_egg(&m) || sp < 1 || sp > PD_SAVE_SPECIES) unreadable++;
        }
    }
    // A save of another layout decodes as noise in nearly every slot; a real one has a Bad Egg at most.
    if (used >= 4 && unreadable * 2 > used) return "Its PC doesn't read: another game's save?";
    return NULL;
}

// --- box record fields ---

uint32_t pd_box_mon_ot_id(const struct pd_box_mon* m) { return u32(m->header + 4); }
uint32_t pd_box_mon_exp(const struct pd_box_mon* m) { return u32(m->data + 4); }
int pd_box_mon_friendship(const struct pd_box_mon* m) { return m->data[9]; }
int pd_box_mon_move(const struct pd_box_mon* m, int i) { return u16(m->data + 12 + i * 2); }
int pd_box_mon_language(const struct pd_box_mon* m) { return m->header[0x12]; }
int pd_box_mon_ability_num(const struct pd_box_mon* m) { return (int) (u32(m->data + 40) >> 31 & 1); }

void pd_box_mon_ivs(const struct pd_box_mon* m, int out[6]) {
    uint32_t word = u32(m->data + 40);
    for (int i = 0; i < 6; i++) out[i] = (int) (word >> (i * 5) & 0x1F);
}

void pd_box_mon_evs(const struct pd_box_mon* m, int out[6]) {
    for (int i = 0; i < 6; i++) out[i] = m->data[24 + i];
}

// --- species and personalities (pokemon.c) ---

#define SPECIES_SHEDINJA 303
#define SPECIES_SMEARGLE 235
#define MOVE_STRUGGLE 165
#define FIRST_TM 289
#define FIRST_HM 339
#define ITEM_LAST_TMHM 346
#define EV_MAX 255
#define EV_TOTAL 510
#define PARTY_STATUS 0x50
#define PARTY_LEVEL 0x54
#define PARTY_MAIL 0x55
#define PARTY_HP 0x56
#define PARTY_STATS 0x58
#define MAIL_NONE 0xFF

static bool species_ok(int species) { return species > 0 && species < PD_SAVE_SPECIES; }

enum pd_save_gender pd_save_gender_of(int species, uint32_t personality) {
    int ratio = species_ok(species) ? pd_save_gender_ratios[species] : 255;
    if (ratio == 0) return PD_SAVE_MALE;
    if (ratio == 254) return PD_SAVE_FEMALE;
    if (ratio == 255) return PD_SAVE_GENDERLESS;
    return (int) (personality & 0xFF) < ratio ? PD_SAVE_FEMALE : PD_SAVE_MALE;
}

bool pd_save_both_genders(int species) {
    int ratio = species_ok(species) ? pd_save_gender_ratios[species] : 255;
    return ratio != 0 && ratio != 254 && ratio != 255;
}

bool pd_save_is_shiny(uint32_t personality, uint32_t otId) {
    return ((otId >> 16) ^ (otId & 0xFFFF) ^ (personality >> 16) ^ (personality & 0xFFFF)) < 8;
}

int pd_save_ability_count(int species) {
    if (!species_ok(species)) return 1;
    int a = pd_save_abilities[species][0], b = pd_save_abilities[species][1];
    return b == 0 || b == a ? 1 : 2;
}

int pd_save_ability(int species, int abilityNum) {
    if (!species_ok(species)) return 0;
    return abilityNum == 1 && pd_save_ability_count(species) == 2 ? pd_save_abilities[species][1] : pd_save_abilities[species][0];
}

const char* pd_save_ability_name(int ability) {
    return ability >= 0 && ability < pd_save_ability_names_count ? pd_save_ability_names[ability] : "?";
}

void pd_save_stats(int species, int level, const int ivs[6], const int evs[6], int nature, int out[6]) {
    const uint8_t* base = species_ok(species) ? pd_save_base_stats[species] : pd_save_base_stats[0];
    out[0] = species == SPECIES_SHEDINJA ? 1 : (2 * base[0] + ivs[0] + evs[0] / 4) * level / 100 + level + 10;
    for (int i = 1; i < 6; i++) {
        int n = (2 * base[i] + ivs[i] + evs[i] / 4) * level / 100 + 5;
        // gNatureStatTable: nature / 5 raises, nature % 5 lowers, over Attack, Defense, Speed, Sp. Atk, Sp. Def.
        if (nature / 5 != nature % 5) {
            if (i - 1 == nature / 5) n = n * 110 / 100;
            else if (i - 1 == nature % 5) n = n * 90 / 100;
        }
        out[i] = n;
    }
}

uint32_t pd_save_exp_for(int species, int level) {
    if (!species_ok(species) || species >= pd_growth_rates_count || level < 1 || level > 100) return 0;
    int rate = pd_growth_rates[species];
    return rate >= 0 && rate <= 5 ? pd_experience_tables[rate][level] : 0;
}

// GetUnownLetterByPersonality and Wurmple's branch: what a new personality has to keep.
static int unown_letter(uint32_t p) {
    return (int) (((p & 0x3000000) >> 18 | (p & 0x30000) >> 12 | (p & 0x300) >> 6 | (p & 0x3)) % 28);
}

static bool wurmple_silcoon(uint32_t p) { return (p >> 16 & 0xFFFF) % 10 < 5; }

// --- places ---

static bool at_valid(const struct pd_save* s, int at) {
    if (PD_AT_IS_PARTY(at)) return PD_AT_PARTY_INDEX(at) < PD_SAVE_PARTY;
    (void) s;
    return at >= 0 && at < PD_SAVE_BOXES * PD_SAVE_BOX_SLOTS;
}

static int party_off(const struct pd_save* s, int i) { return s->f->partyOff + i * PD_SAVE_PARTY_MON; }

static uint8_t* rec_ptr(struct pd_save* s, int at) {
    return PD_AT_IS_PARTY(at) ? s->block1 + party_off(s, PD_AT_PARTY_INDEX(at)) : s->storage + 4 + at * PD_SAVE_RECORD;
}

static const uint8_t* rec_cptr(const struct pd_save* s, int at) { return rec_ptr((struct pd_save*) s, at); }

bool pd_save_record_at(const struct pd_save* s, int at, struct pd_box_mon* out) {
    if (!at_valid(s, at)) return false;
    if (PD_AT_IS_PARTY(at) && PD_AT_PARTY_INDEX(at) >= pd_save_party_count(s)) return false;
    pd_box_mon_read(out, rec_cptr(s, at));
    return !pd_box_mon_empty(out);
}

bool pd_save_mon_at(const struct pd_save* s, int at, struct pd_mon* out) {
    if (!at_valid(s, at)) return false;
    if (PD_AT_IS_PARTY(at)) return pd_save_party_mon(s, PD_AT_PARTY_INDEX(at), out);
    return pd_save_box_mon(s, at / PD_SAVE_BOX_SLOTS, at % PD_SAVE_BOX_SLOTS, out);
}

int pd_save_level_at(const struct pd_save* s, int at) {
    struct pd_box_mon m;
    if (!pd_save_record_at(s, at, &m)) return 0;
    if (PD_AT_IS_PARTY(at)) return rec_cptr(s, at)[PARTY_LEVEL];
    return pd_save_level_for(pd_box_mon_species(&m), pd_box_mon_exp(&m));
}

void pd_save_stats_at(const struct pd_save* s, int at, int out[6], int* hp) {
    memset(out, 0, 6 * sizeof out[0]);
    *hp = 0;
    struct pd_box_mon m;
    if (!pd_save_record_at(s, at, &m) || pd_box_mon_egg(&m) || pd_box_mon_bad_egg(&m)) return;
    if (PD_AT_IS_PARTY(at)) {
        const uint8_t* r = rec_cptr(s, at);
        for (int i = 0; i < 6; i++) out[i] = u16(r + PARTY_STATS + i * 2);
        *hp = u16(r + PARTY_HP);
        return;
    }
    int ivs[6], evs[6];
    pd_box_mon_ivs(&m, ivs);
    pd_box_mon_evs(&m, evs);
    pd_save_stats(pd_box_mon_species(&m), pd_save_level_at(s, at), ivs, evs, (int) (pd_box_mon_personality(&m) % 25), out);
    *hp = out[0];
}

// --- edits ---

static bool alive(const uint8_t* party) {
    struct pd_box_mon m;
    pd_box_mon_read(&m, party);
    return !pd_box_mon_empty(&m) && !pd_box_mon_egg(&m) && !pd_box_mon_bad_egg(&m) && u16(party + PARTY_HP) > 0;
}

// CountPartyAliveNonEggMonsExcept.
static int alive_except(const struct pd_save* s, int except) {
    int n = 0;
    for (int i = 0; i < pd_save_party_count(s) && i < PD_SAVE_PARTY; i++)
        if (i != except && alive(s->block1 + party_off(s, i))) n++;
    return n;
}

// CalculateMonStats for the party Pokémon at [off]: its level from its EXP and its stats, the HP moved by as much as its
// max did (a fainted one stays at 0; Shedinja keeps 1).
static void refresh_party_stats(struct pd_save* s, int off, bool fullHp) {
    uint8_t* r = s->block1 + off;
    struct pd_box_mon m;
    pd_box_mon_read(&m, r);
    if (!m.checksumOk || (pd_box_mon_egg(&m) && !fullHp)) return;
    int species = pd_box_mon_species(&m), level = pd_save_level_for(species, pd_box_mon_exp(&m));
    if (level < 1) level = 1;
    int ivs[6], evs[6], stats[6];
    pd_box_mon_ivs(&m, ivs);
    pd_box_mon_evs(&m, evs);
    pd_save_stats(species, level, ivs, evs, (int) (pd_box_mon_personality(&m) % 25), stats);
    int oldMax = u16(r + PARTY_STATS), hp = u16(r + PARTY_HP), newHp;
    if (species == SPECIES_SHEDINJA) newHp = hp != 0 || oldMax == 0 || fullHp ? 1 : 0;
    else if (fullHp || (hp == 0 && oldMax == 0)) newHp = stats[0];
    else if (hp == 0) newHp = 0;
    else {
        newHp = hp + stats[0] - oldMax;
        if (newHp < 1) newHp = 1;
        if (newHp > stats[0]) newHp = stats[0];
    }
    r[PARTY_LEVEL] = (uint8_t) level;
    put16(r + PARTY_HP, (unsigned) newHp);
    for (int i = 0; i < 6; i++) put16(r + PARTY_STATS + i * 2, (unsigned) stats[i]);
}

// A changed record back where it was; a party Pokémon's level byte and stats follow it.
static void put_mon(struct pd_save* s, int at, const struct pd_box_mon* m) {
    pd_box_mon_encode(m, rec_ptr(s, at));
    if (PD_AT_IS_PARTY(at)) refresh_party_stats(s, party_off(s, PD_AT_PARTY_INDEX(at)), false);
}

// A box record as party Pokémon [index] (BoxMonToMon): no status, no mail, its level and stats, full HP; [keep] = a party
// Pokémon's own 100 bytes, whose status and HP stay (a trade from a party).
static void write_party_mon(struct pd_save* s, int index, const uint8_t* record, const uint8_t* keep) {
    uint8_t* r = s->block1 + party_off(s, index);
    memcpy(r, record, PD_SAVE_RECORD);
    if (keep) memcpy(r + PD_SAVE_RECORD, keep + PD_SAVE_RECORD, PD_SAVE_PARTY_MON - PD_SAVE_RECORD);
    else memset(r + PD_SAVE_RECORD, 0, PD_SAVE_PARTY_MON - PD_SAVE_RECORD);
    r[PARTY_MAIL] = MAIL_NONE;
    refresh_party_stats(s, party_off(s, index), keep == NULL);
}

// CompactPartySlots after party Pokémon [index] leaves; ZeroMonData on the last.
static void remove_party_mon(struct pd_save* s, int index) {
    int count = pd_save_party_count(s);
    for (int i = index; i < count - 1; i++) memmove(s->block1 + party_off(s, i), s->block1 + party_off(s, i + 1), PD_SAVE_PARTY_MON);
    uint8_t* last = s->block1 + party_off(s, count - 1);
    memset(last, 0, PD_SAVE_PARTY_MON);
    last[PARTY_MAIL] = MAIL_NONE;
    s->block1[s->f->partyCountOff] = (uint8_t) (count - 1);
}

bool pd_save_is_mail(int item) { return item >= MAIL_FIRST && item <= MAIL_LAST; }

const char* pd_save_move(struct pd_save* s, int box, int slot, int toBox, int toSlot) {
    if (!box_ok(box, slot) || !box_ok(toBox, toSlot)) return "No such box";
    return pd_save_move_at(s, PD_AT_BOX(box, slot), PD_AT_BOX(toBox, toSlot));
}

const char* pd_save_move_at(struct pd_save* s, int from, int to) {
    if (!at_valid(s, from) || !at_valid(s, to)) return "No such place";
    if (from == to) return NULL;
    int count = pd_save_party_count(s);
    bool fp = PD_AT_IS_PARTY(from), tp = PD_AT_IS_PARTY(to);
    if (!fp && !tp) {
        struct pd_box_mon a, b;
        read_box_mon(s, from / PD_SAVE_BOX_SLOTS, from % PD_SAVE_BOX_SLOTS, &a);
        read_box_mon(s, to / PD_SAVE_BOX_SLOTS, to % PD_SAVE_BOX_SLOTS, &b);
        if (pd_box_mon_empty(&a) || pd_box_mon_bad_egg(&a)) return "Nothing to move there";
        if (!pd_box_mon_empty(&b) && pd_box_mon_bad_egg(&b)) return "A Bad Egg sits there";
        uint8_t tmp[PD_SAVE_RECORD];
        memcpy(tmp, rec_ptr(s, from), PD_SAVE_RECORD);
        memcpy(rec_ptr(s, from), rec_ptr(s, to), PD_SAVE_RECORD);
        memcpy(rec_ptr(s, to), tmp, PD_SAVE_RECORD);
        return NULL;
    }
    if (fp && tp) {
        int a = PD_AT_PARTY_INDEX(from), b = PD_AT_PARTY_INDEX(to);
        if (a >= count) return "Nothing to move there";
        if (b >= count) b = count - 1;
        if (a == b) return NULL;
        uint8_t tmp[PD_SAVE_PARTY_MON];
        memcpy(tmp, s->block1 + party_off(s, a), PD_SAVE_PARTY_MON);
        memcpy(s->block1 + party_off(s, a), s->block1 + party_off(s, b), PD_SAVE_PARTY_MON);
        memcpy(s->block1 + party_off(s, b), tmp, PD_SAVE_PARTY_MON);
        return NULL;
    }
    if (!fp) { // PC -> party
        struct pd_box_mon mon;
        pd_box_mon_read(&mon, rec_ptr(s, from));
        if (pd_box_mon_empty(&mon) || pd_box_mon_bad_egg(&mon)) return "Nothing to move there";
        int i = PD_AT_PARTY_INDEX(to);
        uint8_t record[PD_SAVE_RECORD];
        memcpy(record, rec_ptr(s, from), PD_SAVE_RECORD);
        if (i >= count) {
            if (count >= PD_SAVE_PARTY) return "Your party is full";
            write_party_mon(s, count, record, NULL);
            s->block1[s->f->partyCountOff] = (uint8_t) (count + 1);
            memset(rec_ptr(s, from), 0, PD_SAVE_RECORD);
            return NULL;
        }
        struct pd_box_mon leaving;
        pd_box_mon_read(&leaving, s->block1 + party_off(s, i));
        if (pd_save_is_mail(pd_box_mon_item(&leaving))) return "A Pokémon holding mail can't go in the PC";
        if (pd_box_mon_egg(&mon) && alive_except(s, i) == 0) return "That's your last Pokémon able to battle";
        uint8_t out[PD_SAVE_RECORD];
        memcpy(out, s->block1 + party_off(s, i), PD_SAVE_RECORD);
        write_party_mon(s, i, record, NULL);
        memcpy(rec_ptr(s, from), out, PD_SAVE_RECORD);
        return NULL;
    }
    // party -> PC
    int i = PD_AT_PARTY_INDEX(from);
    if (i >= count) return "Nothing to move there";
    struct pd_box_mon leaving, target;
    pd_box_mon_read(&leaving, s->block1 + party_off(s, i));
    if (pd_save_is_mail(pd_box_mon_item(&leaving))) return "A Pokémon holding mail can't go in the PC";
    pd_box_mon_read(&target, rec_ptr(s, to));
    if (!pd_box_mon_empty(&target) && pd_box_mon_bad_egg(&target)) return "A Bad Egg sits there";
    bool incomingAlive = !pd_box_mon_empty(&target) && !pd_box_mon_egg(&target);
    if (!incomingAlive && alive_except(s, i) == 0) return "That's your last Pokémon able to battle";
    uint8_t record[PD_SAVE_RECORD], incoming[PD_SAVE_RECORD];
    memcpy(record, s->block1 + party_off(s, i), PD_SAVE_RECORD);
    memcpy(incoming, rec_ptr(s, to), PD_SAVE_RECORD);
    if (pd_box_mon_empty(&target)) remove_party_mon(s, i);
    else write_party_mon(s, i, incoming, NULL);
    memcpy(rec_ptr(s, to), record, PD_SAVE_RECORD);
    return NULL;
}

bool pd_save_set_money(struct pd_save* s, long amount) {
    if (amount < 0 || amount > PD_SAVE_MAX_MONEY) return false;
    uint32_t key = s->f->encryptionKeyOff >= 0 ? u32(s->block2 + s->f->encryptionKeyOff) : 0;
    put32(s->block1 + s->f->moneyOff, (uint32_t) amount ^ key);
    return true;
}

bool pd_save_set_coins(struct pd_save* s, int amount) {
    if (amount < 0 || amount > PD_SAVE_MAX_COINS) return false;
    put16(s->block1 + s->f->coinsOff, (unsigned) (amount ^ key16(s)));
    return true;
}

// --- a Pokémon's page ---

const char* pd_save_editable(const struct pd_save* s, int at) {
    struct pd_box_mon m;
    if (!pd_save_record_at(s, at, &m)) return "There's no Pokémon there";
    if (pd_box_mon_bad_egg(&m)) return "A Bad Egg isn't edited";
    if (pd_box_mon_egg(&m)) return "An egg isn't edited";
    return NULL;
}

int pd_save_level_as_read(const struct pd_save* s, int at) {
    struct pd_box_mon m;
    if (!pd_save_record_at(s, at, &m)) return 1;
    uint32_t p = pd_box_mon_personality(&m), ot = pd_box_mon_ot_id(&m);
    // The same Pokémon in the blocks as read: in the party or anywhere in the PC.
    for (int i = 0; i < PD_SAVE_PARTY && i < s->read1[s->f->partyCountOff]; i++) {
        struct pd_box_mon was;
        const uint8_t* r = s->read1 + s->f->partyOff + i * PD_SAVE_PARTY_MON;
        pd_box_mon_read(&was, r);
        if (was.checksumOk && pd_box_mon_personality(&was) == p && pd_box_mon_ot_id(&was) == ot)
            return pd_save_level_for(pd_box_mon_species(&was), pd_box_mon_exp(&was));
    }
    for (int k = 0; k < PD_SAVE_BOXES * PD_SAVE_BOX_SLOTS; k++) {
        struct pd_box_mon was;
        pd_box_mon_read(&was, s->readStorage + 4 + k * PD_SAVE_RECORD);
        if (!pd_box_mon_empty(&was) && was.checksumOk && pd_box_mon_personality(&was) == p && pd_box_mon_ot_id(&was) == ot)
            return pd_save_level_for(pd_box_mon_species(&was), pd_box_mon_exp(&was));
    }
    return pd_save_level_for(pd_box_mon_species(&m), pd_box_mon_exp(&m));
}

static const char* editable_mon(const struct pd_save* s, int at, struct pd_box_mon* m) {
    const char* why = pd_save_editable(s, at);
    if (why) return why;
    pd_save_record_at(s, at, m);
    return NULL;
}

const char* pd_save_set_level(struct pd_save* s, int at, int level) {
    struct pd_box_mon m;
    const char* why = editable_mon(s, at, &m);
    if (why) return why;
    if (level < pd_save_level_as_read(s, at) || level > 100) return "Not a level it can have";
    put32(m.data + 4, pd_save_exp_for(pd_box_mon_species(&m), level));
    put_mon(s, at, &m);
    return NULL;
}

const char* pd_save_set_ivs(struct pd_save* s, int at, const int ivs[6]) {
    struct pd_box_mon m;
    const char* why = editable_mon(s, at, &m);
    if (why) return why;
    uint32_t word = u32(m.data + 40) & ~0x3FFFFFFFu;
    for (int i = 0; i < 6; i++) {
        if (ivs[i] < 0 || ivs[i] > 31) return "IVs go from 0 to 31";
        word |= (uint32_t) ivs[i] << (i * 5);
    }
    put32(m.data + 40, word);
    put_mon(s, at, &m);
    return NULL;
}

const char* pd_save_set_evs(struct pd_save* s, int at, const int evs[6]) {
    struct pd_box_mon m;
    const char* why = editable_mon(s, at, &m);
    if (why) return why;
    int total = 0;
    for (int i = 0; i < 6; i++) {
        if (evs[i] < 0 || evs[i] > EV_MAX) return "EVs go up to 255 a stat";
        total += evs[i];
    }
    if (total > EV_TOTAL) return "EVs go up to 510 in all";
    for (int i = 0; i < 6; i++) m.data[24 + i] = (uint8_t) evs[i];
    put_mon(s, at, &m);
    return NULL;
}

const char* pd_save_set_friendship(struct pd_save* s, int at, int value) {
    struct pd_box_mon m;
    const char* why = editable_mon(s, at, &m);
    if (why) return why;
    if (value < 0 || value > 255) return "Friendship goes from 0 to 255";
    m.data[9] = (uint8_t) value;
    put_mon(s, at, &m);
    return NULL;
}

static int name_bytes_len(const uint8_t* b, int max) {
    int n = 0;
    while (n < max && b[n] != 0xFF) n++;
    return n;
}

bool pd_save_is_own(const struct pd_save* s, int at) {
    struct pd_box_mon m;
    if (!pd_save_record_at(s, at, &m)) return false;
    uint32_t ot = pd_box_mon_ot_id(&m);
    if ((ot & 0xFFFF) != u16(s->block2 + 0x0A) || ot >> 16 != u16(s->block2 + 0x0C)) return false;
    int a = name_bytes_len(m.header + 0x14, 7), b = name_bytes_len(s->block2, 7);
    return a == b && !memcmp(m.header + 0x14, s->block2, (size_t) a);
}

// The naming screen's characters (charmap.txt): one byte each in the Western games. -1 = not one of them.
static int name_char(const unsigned char** p) {
    unsigned c = **p;
    if (c >= 'A' && c <= 'Z') return (*p)++, 0xBB + (int) (c - 'A');
    if (c >= 'a' && c <= 'z') return (*p)++, 0xD5 + (int) (c - 'a');
    if (c >= '0' && c <= '9') return (*p)++, 0xA1 + (int) (c - '0');
    static const char ASCII[] = " !?.-,/'\"";
    static const int CODES[] = { 0x00, 0xAB, 0xAC, 0xAD, 0xAE, 0xB8, 0xBA, 0xB4, 0xB2 };
    for (int i = 0; ASCII[i]; i++)
        if ((char) c == ASCII[i]) return (*p)++, CODES[i];
    // UTF-8: … “ ” ‘ ’ ♂ ♀ é
    static const struct { const char* utf8; int code; } WIDE[] = {
        { "\xE2\x80\xA6", 0xB0 }, { "\xE2\x80\x9C", 0xB1 }, { "\xE2\x80\x9D", 0xB2 }, { "\xE2\x80\x98", 0xB3 },
        { "\xE2\x80\x99", 0xB4 }, { "\xE2\x99\x82", 0xB5 }, { "\xE2\x99\x80", 0xB6 }, { "\xC3\xA9", 0x1B },
    };
    for (size_t i = 0; i < sizeof WIDE / sizeof WIDE[0]; i++) {
        size_t n = strlen(WIDE[i].utf8);
        if (!strncmp((const char*) *p, WIDE[i].utf8, n)) {
            *p += n;
            return WIDE[i].code;
        }
    }
    return -1;
}

// [utf8] in [size] bytes as the naming screen leaves it (the characters, then 0xFF); its length, -1 if it can't be.
static int encode_name(const char* utf8, uint8_t* out, int size, int max) {
    memset(out, 0xFF, (size_t) size);
    int n = 0;
    const unsigned char* p = (const unsigned char*) utf8;
    while (*p) {
        int code = name_char(&p);
        if (code < 0 || n >= max || n >= size) return -1;
        out[n++] = (uint8_t) code;
    }
    return n;
}

bool pd_save_name_ok(const char* utf8, int max) {
    uint8_t buf[16];
    bool blank = true;
    for (const char* c = utf8; *c; c++) blank &= *c == ' ';
    return !blank && encode_name(utf8, buf, sizeof buf, max) > 0;
}

const char* pd_save_set_nickname(struct pd_save* s, int at, const char* utf8) {
    struct pd_box_mon m;
    const char* why = editable_mon(s, at, &m);
    if (why) return why;
    if (!pd_save_is_own(s, at)) return "Only the trainer who caught it can rename it";
    if (pd_box_mon_language(&m) == 1) return "A Japanese Pokémon's name isn't edited";
    if (!pd_save_name_ok(utf8, 10)) return "The games can't write that name (letters, digits, . - ! ? ' and ♂ ♀)";
    encode_name(utf8, m.header + 8, 10, 10);
    put_mon(s, at, &m);
    return NULL;
}

// xorshift32: the personality search's random numbers (any spread will do: it only has to find one that fits).
static uint32_t rng_state = 0x9E3779B9u;
static uint32_t rng(void) {
    uint32_t x = rng_state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    return rng_state = x;
}

const char* pd_save_reroll(struct pd_save* s, int at, int nature, int shiny, int gender, int abilityNum) {
    struct pd_box_mon m;
    const char* why = editable_mon(s, at, &m);
    if (why) return why;
    int species = pd_box_mon_species(&m);
    uint32_t p0 = pd_box_mon_personality(&m), ot = pd_box_mon_ot_id(&m);
    if (nature > 24 || (abilityNum >= 0 && abilityNum >= pd_save_ability_count(species))) return "Not one it can have";
    int wantNature = nature >= 0 ? nature : (int) (p0 % 25);
    bool wantShiny = shiny >= 0 ? shiny != 0 : pd_save_is_shiny(p0, ot);
    enum pd_save_gender wantGender = gender >= 0 ? (enum pd_save_gender) gender : pd_save_gender_of(species, p0);
    if (wantGender != pd_save_gender_of(species, p0) && !pd_save_both_genders(species)) return "No Pokémon like that can exist";
    bool two = pd_save_ability_count(species) == 2;
    int wantBit = abilityNum >= 0 ? abilityNum : pd_box_mon_ability_num(&m);
    int letter = unown_letter(p0);
    bool silcoon = wurmple_silcoon(p0);
    uint32_t ids = (ot >> 16 ^ (ot & 0xFFFF)) & 0xFFFF;
    rng_state ^= p0 ^ (uint32_t) at * 2654435761u;
    if (!rng_state) rng_state = 1;
    for (long tries = 0; tries < 4000000; tries++) {
        uint32_t low = rng() & 0xFFFF;
        uint32_t high = wantShiny ? (ids ^ low ^ (rng() & 7)) & 0xFFFF : rng() & 0xFFFF;
        uint32_t p = high << 16 | low;
        if ((int) (p % 25) != wantNature || pd_save_is_shiny(p, ot) != wantShiny || pd_save_gender_of(species, p) != wantGender) continue;
        if (two && (int) (p & 1) != wantBit) continue;
        if (species == SPECIES_UNOWN && unown_letter(p) != letter) continue;
        if (species >= 265 && species <= 269 && wurmple_silcoon(p) != silcoon) continue;
        // The ability bit follows the personality, as when the game made it.
        uint32_t word = u32(m.data + 40);
        if (two) word = (word & 0x7FFFFFFFu) | (p & 1) << 31;
        put32(m.data + 40, word);
        put32(m.header, p);
        put_mon(s, at, &m);
        return NULL;
    }
    return "No Pokémon like that can exist";
}

int pd_save_tmhm_move(int item) {
    return item >= FIRST_TM && item <= ITEM_LAST_TMHM ? pd_save_tmhm_moves[item - FIRST_TM] : 0;
}

static const struct pd_save_legal* legal(const struct pd_save* s) { return &pd_save_legal[s->f->kind]; }

static int pocket_of(const struct pd_save* s, int item) {
    const struct pd_save_legal* t = legal(s);
    for (int i = 0; i < t->bagCount; i++)
        if (t->bag[i][0] == item) return t->bag[i][1];
    return 0;
}

static int cmp_u16(const void* a, const void* b) { return (int) *(const uint16_t*) a - (int) *(const uint16_t*) b; }

int pd_save_holdable(const struct pd_save* s, uint16_t* out, int max) {
    const struct pd_save_legal* t = legal(s);
    int n = 0;
    for (int i = 0; i < t->bagCount && n < max; i++) {
        int item = t->bag[i][0];
        if (item >= FIRST_HM && item <= ITEM_LAST_TMHM) continue;
        if (pd_save_is_mail(item)) continue;
        out[n++] = (uint16_t) item;
    }
    qsort(out, (size_t) n, sizeof out[0], cmp_u16);
    return n;
}

const char* pd_save_set_held_item(struct pd_save* s, int at, int item) {
    struct pd_box_mon m;
    const char* why = editable_mon(s, at, &m);
    if (why) return why;
    int now = pd_box_mon_item(&m);
    if (pd_save_is_mail(now)) return "Mail stays with its letter: take it off in the game";
    if (item != 0 && item != now && (pocket_of(s, item) == 0 || (item >= FIRST_HM && item <= ITEM_LAST_TMHM) || pd_save_is_mail(item)))
        return "That can't be held";
    put16(m.data + 2, (unsigned) item);
    put_mon(s, at, &m);
    return NULL;
}

static void learnable_of(const struct pd_save* s, int species, int level, uint8_t* bits) {
    memset(bits, 0, (PD_SAVE_LAST_MOVE + 8) / 8);
#define SET(mv) do { int m_ = (mv); if (m_ > 0 && m_ <= PD_SAVE_LAST_MOVE) bits[m_ / 8] |= (uint8_t) (1 << (m_ % 8)); } while (0)
    if (species == SPECIES_SMEARGLE) { // Sketch: any move but Struggle
        for (int mv = 1; mv <= PD_SAVE_LAST_MOVE; mv++)
            if (mv != MOVE_STRUGGLE) SET(mv);
        return;
    }
    const struct pd_save_legal* t = legal(s);
    for (int guard = 0; species_ok(species) && guard < 4; guard++) {
        for (int k = t->levelUpStart[species]; k < t->levelUpStart[species + 1]; k++)
            if (t->levelUp[k] >> 9 <= level) SET(t->levelUp[k] & 0x1FF);
        for (int b = 0; b < PD_SAVE_TMHM; b++)
            if (t->tmhm[species] >> b & 1) SET(pd_save_tmhm_moves[b]);
        for (int b = 0; b < t->tutorCount; b++)
            if (t->tutors[species] >> b & 1) SET(t->tutorMoves[b]);
        // FireRed / LeafGreen's Cape Brink tutor for the three starters' last stages.
        if (s->f->kind == PD_SAVE_FIRERED_LEAFGREEN) {
            if (species == 3) SET(338);
            if (species == 6) SET(307);
            if (species == 9) SET(308);
        }
        species = pd_save_pre_evolution[species];
    }
#undef SET
}

int pd_save_learnable(const struct pd_save* s, int at, uint8_t out[(PD_SAVE_LAST_MOVE + 8) / 8]) {
    struct pd_box_mon m;
    memset(out, 0, (PD_SAVE_LAST_MOVE + 8) / 8);
    if (!pd_save_record_at(s, at, &m)) return 0;
    learnable_of(s, pd_box_mon_species(&m), pd_save_level_at(s, at), out);
    int n = 0;
    for (int mv = 1; mv <= PD_SAVE_LAST_MOVE; mv++) n += out[mv / 8] >> (mv % 8) & 1;
    return n;
}

const char* pd_save_set_moves(struct pd_save* s, int at, const int* moves, int n) {
    struct pd_box_mon m;
    const char* why = editable_mon(s, at, &m);
    if (why) return why;
    if (n < 1 || n > 4) return "A Pokémon knows one to four moves";
    uint8_t learn[(PD_SAVE_LAST_MOVE + 8) / 8];
    learnable_of(s, pd_box_mon_species(&m), pd_save_level_at(s, at), learn);
    int known[4], pp[4], ups = m.data[8], ids[4] = { 0 }, newPp[4] = { 0 }, newUps = 0;
    for (int i = 0; i < 4; i++) known[i] = pd_box_mon_move(&m, i), pp[i] = m.data[20 + i];
    for (int i = 0; i < n; i++) {
        int mv = moves[i];
        if (mv < 1 || mv > PD_SAVE_LAST_MOVE) return "Not a move";
        for (int k = 0; k < i; k++)
            if (moves[k] == mv) return "A move only once";
        int was = -1;
        for (int k = 0; k < 4; k++)
            if (known[k] == mv) was = k;
        if (was < 0 && !(learn[mv / 8] >> (mv % 8) & 1)) return "It can't learn that move";
        ids[i] = mv;
        if (was >= 0) {
            newPp[i] = pp[was];
            newUps |= (ups >> (was * 2) & 3) << (i * 2);
        } else {
            newPp[i] = pd_save_move_pp[mv];
        }
    }
    for (int i = 0; i < 4; i++) put16(m.data + 12 + i * 2, (unsigned) ids[i]), m.data[20 + i] = (uint8_t) newPp[i];
    m.data[8] = (uint8_t) newUps;
    put_mon(s, at, &m);
    return NULL;
}

// --- boxes ---

const char* pd_save_rename_box(struct pd_save* s, int box, const char* utf8) {
    if (box < 0 || box >= PD_SAVE_BOXES) return "No such box";
    if (!pd_save_name_ok(utf8, 8)) return "The games can't write that name (8 letters, digits, . - ! ? ')";
    encode_name(utf8, s->storage + BOX_NAMES_OFF + box * BOX_NAME, BOX_NAME, 8);
    return NULL;
}

struct sort_entry { uint8_t record[PD_SAVE_RECORD]; bool egg, bad; int dex, level; char name[24]; };
static enum pd_box_sort sortBy;

static int cmp_entry(const void* pa, const void* pb) {
    const struct sort_entry *a = pa, *b = pb;
    if (a->bad != b->bad) return a->bad - b->bad; // Bad Eggs last, untouched
    if (a->bad) return 0;
    if (a->egg != b->egg) return a->egg - b->egg;
    if (sortBy == PD_SORT_NAME) return strcmp(a->name, b->name);
    if (sortBy == PD_SORT_LEVEL && a->level != b->level) return b->level - a->level;
    if (a->dex != b->dex) return a->dex - b->dex;
    return b->level - a->level;
}

void pd_save_sort_box(struct pd_save* s, int box, enum pd_box_sort by) {
    if (box < 0 || box >= PD_SAVE_BOXES) return;
    static struct sort_entry e[PD_SAVE_BOX_SLOTS];
    int n = 0;
    for (int i = 0; i < PD_SAVE_BOX_SLOTS; i++) {
        uint8_t* r = s->storage + record_off(box, i);
        struct pd_box_mon m;
        pd_box_mon_read(&m, r);
        if (pd_box_mon_empty(&m)) continue;
        struct sort_entry* x = &e[n++];
        memcpy(x->record, r, PD_SAVE_RECORD);
        x->bad = pd_box_mon_bad_egg(&m);
        x->egg = !x->bad && pd_box_mon_egg(&m);
        int sp = pd_box_mon_species(&m);
        x->dex = species_ok(sp) && pd_species_national[sp] ? pd_species_national[sp] : 999;
        x->level = pd_save_level_for(sp, pd_box_mon_exp(&m));
        pd_gen3_text(m.header + 8, 10, x->name, sizeof x->name);
    }
    // A stable sort: insertion keeps equal Pokémon in their order (qsort needn't).
    sortBy = by;
    for (int i = 1; i < n; i++) {
        struct sort_entry t = e[i];
        int k = i - 1;
        while (k >= 0 && cmp_entry(&e[k], &t) > 0) e[k + 1] = e[k], k--;
        e[k + 1] = t;
    }
    for (int i = 0; i < PD_SAVE_BOX_SLOTS; i++) {
        uint8_t* r = s->storage + record_off(box, i);
        if (i < n) memcpy(r, e[i].record, PD_SAVE_RECORD);
        else memset(r, 0, PD_SAVE_RECORD);
    }
}

const char* pd_save_release(struct pd_save* s, int at) {
    if (PD_AT_IS_PARTY(at)) return "Put it in a box first: the PC releases from its boxes";
    struct pd_box_mon m;
    if (!pd_save_record_at(s, at, &m) || pd_box_mon_bad_egg(&m)) return "Nothing to release there";
    if (pd_box_mon_egg(&m)) return "An egg can't be released";
    memset(rec_ptr(s, at), 0, PD_SAVE_RECORD);
    return NULL;
}

// --- the bag ---

// global.h's bagPocket_Items, _KeyItems, _PokeBalls, _TMHM, _Berries: back to back, 4 bytes a slot.
struct pocket_layout { int start, slots[PD_BAG_POCKETS], stackMax; };
static const struct pocket_layout POCKET_LAYOUTS[3] = {
    { 0x560, { 20, 16, 64, 46, 20 }, 99 },  // Ruby / Sapphire
    { 0x560, { 30, 16, 64, 46, 30 }, 99 },  // Emerald
    { 0x310, { 42, 13, 58, 43, 30 }, 999 }, // FireRed / LeafGreen
};

static int pocket_off(const struct pd_save* s, enum pd_save_pocket p) {
    const struct pocket_layout* l = &POCKET_LAYOUTS[s->f->kind];
    // The struct's order: items, key items, balls, TMs & HMs, berries.
    static const enum pd_save_pocket ORDER[PD_BAG_POCKETS] = { PD_BAG_ITEMS, PD_BAG_KEY, PD_BAG_BALLS, PD_BAG_TMHM, PD_BAG_BERRIES };
    int off = l->start;
    for (int i = 0; i < PD_BAG_POCKETS; i++) {
        if (ORDER[i] == p) return off;
        off += l->slots[ORDER[i]] * 4;
    }
    return off;
}

int pd_save_pocket_slots(const struct pd_save* s, enum pd_save_pocket p) { return POCKET_LAYOUTS[s->f->kind].slots[p]; }

int pd_save_pocket(const struct pd_save* s, enum pd_save_pocket p, struct pd_stack* out, int max) {
    int off = pocket_off(s, p), n = 0;
    for (int i = 0; i < pd_save_pocket_slots(s, p) && n < max; i++) {
        int item = u16(s->block1 + off + i * 4);
        if (!item) continue;
        out[n].item = item;
        out[n].quantity = u16(s->block1 + off + i * 4 + 2) ^ key16(s);
        n++;
    }
    return n;
}

int pd_save_stack_max(const struct pd_save* s, enum pd_save_pocket p, int item) {
    if (p == PD_BAG_TMHM && item >= FIRST_HM) return 1;
    if (p == PD_BAG_BERRIES) return 999;
    return POCKET_LAYOUTS[s->f->kind].stackMax;
}

bool pd_save_stack_fixed(enum pd_save_pocket p, int item) {
    return p == PD_BAG_KEY || (p == PD_BAG_TMHM && item >= FIRST_HM);
}

// The pocket packed from its first slot, the rest zeroed (CompactItemsInBagPocket); TMs and berries by id, as the game sorts them.
static void write_pocket(struct pd_save* s, enum pd_save_pocket p, struct pd_stack* stacks, int n) {
    if (p == PD_BAG_TMHM || p == PD_BAG_BERRIES) {
        for (int i = 1; i < n; i++) {
            struct pd_stack t = stacks[i];
            int k = i - 1;
            while (k >= 0 && stacks[k].item > t.item) stacks[k + 1] = stacks[k], k--;
            stacks[k + 1] = t;
        }
    }
    int off = pocket_off(s, p);
    for (int i = 0; i < pd_save_pocket_slots(s, p); i++) {
        put16(s->block1 + off + i * 4, i < n ? (unsigned) stacks[i].item : 0);
        put16(s->block1 + off + i * 4 + 2, i < n ? (unsigned) (stacks[i].quantity ^ key16(s)) : 0);
    }
}

static const char* legal_stack(const struct pd_save* s, enum pd_save_pocket p, int item, int quantity) {
    if (p == PD_BAG_KEY) return "Key items aren't edited";
    if (pocket_of(s, item) != (int) p + 1) return "That doesn't go in this pocket in this game";
    if (quantity < 1 || quantity > pd_save_stack_max(s, p, item)) return "Not that many in one stack";
    return NULL;
}

#define POCKET_MAX 64

const char* pd_save_set_quantity(struct pd_save* s, enum pd_save_pocket p, int index, int quantity) {
    struct pd_stack stacks[POCKET_MAX];
    int n = pd_save_pocket(s, p, stacks, POCKET_MAX);
    if (index < 0 || index >= n) return "No such item";
    int item = stacks[index].item;
    if (quantity == 0) {
        // Key items and HMs are handed out once: without them the game can't go on.
        if (pd_save_stack_fixed(p, item)) return "That one stays: the game needs it";
        memmove(stacks + index, stacks + index + 1, (size_t) (n - index - 1) * sizeof stacks[0]);
        n--;
    } else {
        const char* why = legal_stack(s, p, item, quantity);
        if (why) return why;
        stacks[index].quantity = quantity;
    }
    write_pocket(s, p, stacks, n);
    return NULL;
}

const char* pd_save_add_item(struct pd_save* s, enum pd_save_pocket p, int item, int quantity) {
    const char* why = legal_stack(s, p, item, quantity);
    if (why) return why;
    struct pd_stack stacks[POCKET_MAX];
    int n = pd_save_pocket(s, p, stacks, POCKET_MAX);
    for (int i = 0; i < n; i++)
        if (stacks[i].item == item) return "That's in the bag already";
    if (n >= pd_save_pocket_slots(s, p)) return "That pocket is full";
    stacks[n].item = item;
    stacks[n].quantity = quantity;
    write_pocket(s, p, stacks, n + 1);
    return NULL;
}

int pd_save_addable(const struct pd_save* s, enum pd_save_pocket p, uint16_t* out, int max) {
    if (p == PD_BAG_KEY) return 0;
    struct pd_stack stacks[POCKET_MAX];
    int have = pd_save_pocket(s, p, stacks, POCKET_MAX), n = 0;
    const struct pd_save_legal* t = legal(s);
    for (int i = 0; i < t->bagCount && n < max; i++) {
        if (t->bag[i][1] != (int) p + 1) continue;
        bool in = false;
        for (int k = 0; k < have; k++) in |= stacks[k].item == t->bag[i][0];
        if (!in) out[n++] = t->bag[i][0];
    }
    qsort(out, (size_t) n, sizeof out[0], cmp_u16);
    return n;
}

// --- the Pokédex ---

static bool dex_bit(const uint8_t* base, int off, int national) {
    return base[off + (national - 1) / 8] >> ((national - 1) % 8) & 1;
}

static void set_dex_bit(uint8_t* base, int off, int national) {
    base[off + (national - 1) / 8] |= (uint8_t) (1 << ((national - 1) % 8));
}

static int national_of(int species) {
    return species_ok(species) ? pd_species_national[species] : 0;
}

static bool in_regional_dex(int species, const struct pd_save_layout* f) {
    if (f->kanto) return species >= 1 && species <= KANTO_COUNT;
    return species_ok(species) && pd_species_hoenn[species] >= 1 && pd_species_hoenn[species] <= HOENN_COUNT;
}

void pd_save_dex_counts(const struct pd_save* s, struct pd_save_dex* out) {
    memset(out, 0, sizeof *out);
    static bool regional[NATIONAL_COUNT + 1];
    memset(regional, 0, sizeof regional);
    for (int sp = 1; sp < PD_SAVE_SPECIES; sp++) {
        int n = national_of(sp);
        if (n >= 1 && n <= NATIONAL_COUNT && in_regional_dex(sp, s->f)) regional[n] = true;
    }
    for (int n = 1; n <= NATIONAL_COUNT; n++) {
        bool seen = dex_bit(s->block2, POKEDEX_OFF + 0x44, n), got = dex_bit(s->block2, POKEDEX_OFF + 0x10, n);
        out->seen += seen;
        out->caught += got;
        out->regionalSize += regional[n];
        if (regional[n]) out->regionalSeen += seen, out->regionalCaught += got;
    }
    out->national = pd_save_national_dex(s);
    out->region = s->f->kanto ? "KANTO" : "HOENN";
}

// GetSetPokedexFlag(FLAG_GET_CAUGHT): owned, and seen in all three copies.
static bool caught(const struct pd_save* s, int species) {
    int n = national_of(species);
    if (n < 1 || n > NATIONAL_COUNT) return false;
    return dex_bit(s->block2, POKEDEX_OFF + 0x10, n) && dex_bit(s->block2, POKEDEX_OFF + 0x44, n) && dex_bit(s->block1, s->f->seen1Off, n) &&
           dex_bit(s->block1, s->f->seen2Off, n);
}

// UpdatePokedexForReceivedMon: seen in all three copies, then caught, Unown's / Spinda's personality kept for the first one.
static void mark_caught(struct pd_save* s, int species, uint32_t personality) {
    int n = national_of(species);
    if (n < 1 || n > NATIONAL_COUNT) return;
    bool first = !caught(s, species);
    set_dex_bit(s->block2, POKEDEX_OFF + 0x44, n);
    set_dex_bit(s->block1, s->f->seen1Off, n);
    set_dex_bit(s->block1, s->f->seen2Off, n);
    if (!first) return;
    set_dex_bit(s->block2, POKEDEX_OFF + 0x10, n);
    if (species == SPECIES_UNOWN) put32(s->block2 + POKEDEX_OFF + 0x04, personality);
    if (species == SPECIES_SPINDA) put32(s->block2 + POKEDEX_OFF + 0x08, personality);
}

void pd_save_register_owned(struct pd_save* s) {
    for (int at = PD_AT_PARTY(PD_SAVE_PARTY - 1); at < PD_SAVE_BOXES * PD_SAVE_BOX_SLOTS; at++) {
        struct pd_box_mon m;
        if (!pd_save_record_at(s, at, &m) || pd_box_mon_egg(&m) || pd_box_mon_bad_egg(&m)) continue;
        mark_caught(s, pd_box_mon_species(&m), pd_box_mon_personality(&m));
    }
}

void pd_save_mark_all_dex(struct pd_save* s, bool caughtToo) {
    bool national = pd_save_national_dex(s);
    for (int sp = 1; sp < PD_SAVE_SPECIES; sp++) {
        int n = national_of(sp);
        if (n < 1 || n > NATIONAL_COUNT || (!national && !in_regional_dex(sp, s->f))) continue;
        set_dex_bit(s->block2, POKEDEX_OFF + 0x44, n);
        set_dex_bit(s->block1, s->f->seen1Off, n);
        set_dex_bit(s->block1, s->f->seen2Off, n);
        if (caughtToo) set_dex_bit(s->block2, POKEDEX_OFF + 0x10, n);
    }
}

// Ruby / Sapphire's EnableNationalPokedex: what a trade with FireRed / LeafGreen / Emerald does to them.
static void enable_national_dex(struct pd_save* s) {
    const struct pd_save_layout* f = s->f;
    s->block2[f->nationalMagicOff] = (uint8_t) f->nationalMagic;
    put16(s->block1 + f->varsOff + (f->nationalVar - 0x4000) * 2, (unsigned) f->nationalVarValue);
    s->block1[f->flagsOff + f->nationalFlag / 8] |= (uint8_t) (1 << (f->nationalFlag % 8));
    s->block2[POKEDEX_OFF + 1] = 1; // mode: national
    s->block2[POKEDEX_OFF] = 0;     // order: numerical
}

// --- events (pret's LilycoveCity_Harbor / VermilionCity scripts, gift_*_ticket.inc) ---

struct event_flags { int set[2], setCount, done[2], doneCount; };
struct event { const char* name; int item; struct event_flags per[3]; }; // per pd_save_kind; setCount 0 = not in that game

static const struct event EVENTS[PD_EVENTS] = {
    // pokeruby: FLAG_SYS_HAS_EON_TICKET. pokeemerald: FLAG_ENABLE_SHIP_SOUTHERN_ISLAND; done: FLAG_CAUGHT_LATIAS_OR_LATIOS.
    { "EON TICKET", 275, { { { 0x853 }, 1, { 0 }, 0 }, { { 0x8B3 }, 1, { 0x1C9 }, 1 }, { { 0 }, 0, { 0 }, 0 } } },
    // FLAG_ENABLE_SHIP_BIRTH_ISLAND, FLAG_RECEIVED_AURORA_TICKET; done: FLAG_BATTLED_DEOXYS / FLAG_FOUGHT_DEOXYS.
    { "AURORA TICKET", 371, { { { 0 }, 0, { 0 }, 0 }, { { 0x8D5, 0x13A }, 2, { 0x1AD }, 1 }, { { 0x84B, 0x2A7 }, 2, { 0x2E4 }, 1 } } },
    // FLAG_ENABLE_SHIP_NAVEL_ROCK, FLAG_RECEIVED_MYSTIC_TICKET; done: Lugia / Ho-Oh caught (Emerald) or fought (FR / LG).
    { "MYSTIC TICKET", 370, { { { 0 }, 0, { 0 }, 0 }, { { 0x8E0, 0x13B }, 2, { 0x91, 0x92 }, 2 }, { { 0x84A, 0x2A8 }, 2, { 0x2F2, 0x2F3 }, 2 } } },
    // FLAG_ENABLE_SHIP_FARAWAY_ISLAND, FLAG_RECEIVED_OLD_SEA_MAP; done: FLAG_CAUGHT_MEW.
    { "OLD SEA MAP", 376, { { { 0 }, 0, { 0 }, 0 }, { { 0x8D6, 0x13C }, 2, { 0x1CA }, 1 }, { { 0 }, 0, { 0 }, 0 } } },
};

const char* pd_save_event_name(enum pd_save_event e) { return e < PD_EVENTS ? EVENTS[e].name : "?"; }

const char* pd_save_event_note(const struct pd_save* s) {
    return s->f->kind == PD_SAVE_FIRERED_LEAFGREEN ? "Show it at the port once you have the Rainbow Pass"
                                                     : "Show it at the port after the Hall of Fame";
}

static bool has_key_item(const struct pd_save* s, int item) {
    struct pd_stack stacks[POCKET_MAX];
    int n = pd_save_pocket(s, PD_BAG_KEY, stacks, POCKET_MAX);
    for (int i = 0; i < n; i++)
        if (stacks[i].item == item) return true;
    return false;
}

enum pd_event_state pd_save_event_state(const struct pd_save* s, enum pd_save_event e) {
    if (e >= PD_EVENTS) return PD_EVENT_NOT_HERE;
    const struct event_flags* fl = &EVENTS[e].per[s->f->kind];
    if (!fl->setCount) return PD_EVENT_NOT_HERE;
    if (has_key_item(s, EVENTS[e].item) || pd_save_flag(s, fl->set[0])) return PD_EVENT_HAS;
    for (int i = 1; i < fl->setCount; i++)
        if (pd_save_flag(s, fl->set[i])) return PD_EVENT_DONE;
    for (int i = 0; i < fl->doneCount; i++)
        if (pd_save_flag(s, fl->done[i])) return PD_EVENT_DONE;
    return PD_EVENT_AVAILABLE;
}

const char* pd_save_give_event(struct pd_save* s, enum pd_save_event e) {
    enum pd_event_state st = pd_save_event_state(s, e);
    if (st == PD_EVENT_NOT_HERE) return "That isn't in this game";
    if (st != PD_EVENT_AVAILABLE) return "This save has that already";
    struct pd_stack stacks[POCKET_MAX];
    int n = pd_save_pocket(s, PD_BAG_KEY, stacks, POCKET_MAX);
    if (n >= pd_save_pocket_slots(s, PD_BAG_KEY)) return "The key items pocket is full";
    stacks[n].item = EVENTS[e].item;
    stacks[n].quantity = 1;
    write_pocket(s, PD_BAG_KEY, stacks, n + 1);
    const struct event_flags* fl = &EVENTS[e].per[s->f->kind];
    for (int i = 0; i < fl->setCount; i++) s->block1[s->f->flagsOff + fl->set[i] / 8] |= (uint8_t) (1 << (fl->set[i] % 8));
    return NULL;
}

// --- trading ---

static const char* species_label(int species, char* buf, size_t len) {
    if (species > 0 && species < pd_species_names_count && pd_species_names[species]) {
        snprintf(buf, len, "%s", pd_species_names[species]);
        for (char* p = buf; *p; p++) *p = (char) toupper((unsigned char) *p);
    } else {
        snprintf(buf, len, "#%d", species);
    }
    return buf;
}

static const char* link_blocker(const struct pd_trade_side* a, const struct pd_trade_side* b, char* buf, size_t len) {
    bool af = a->save->f->kind == PD_SAVE_FIRERED_LEAFGREEN, bf = b->save->f->kind == PD_SAVE_FIRERED_LEAFGREEN;
    if (af == bf) return NULL;
    const struct pd_trade_side* side = af ? a : b;
    if (pd_save_flag(side->save, side->save->f->linkRsFlag)) return NULL;
    snprintf(buf, len, "%s links with Ruby, Sapphire and Emerald only after the Sevii Islands", side->label);
    return buf;
}

static const char* blocker(const struct pd_box_mon* m, const struct pd_trade_side* from, const struct pd_trade_side* to, char* buf, size_t len) {
    if (pd_box_mon_empty(m)) return NULL;
    if (pd_box_mon_bad_egg(m)) return "A Bad Egg can't be traded";
    const char* link = link_blocker(from, to, buf, len);
    if (link) return link;
    int species = pd_box_mon_species(m);
    char name[24];
    const struct pd_trade_side* sides[2] = { from, to };
    for (int i = 0; i < 2; i++) {
        const struct pd_trade_side* side = sides[i];
        if (side->save->f->kind == PD_SAVE_RUBY_SAPPHIRE || pd_save_national_dex(side->save)) continue;
        if (pd_box_mon_egg(m)) {
            snprintf(buf, len, "%s has no National Dex yet, so no eggs", side->label);
            return buf;
        }
        if (!in_regional_dex(species, side->save->f)) {
            snprintf(buf, len, "%s has no National Dex yet, so no %s", side->label, species_label(species, name, sizeof name));
            return buf;
        }
    }
    if ((species == SPECIES_MEW || species == SPECIES_DEOXYS) && !pd_box_mon_fateful(m)) {
        snprintf(buf, len, "This %s isn't from an event, so the games won't trade it", species_label(species, name, sizeof name));
        return buf;
    }
    int item = pd_box_mon_item(m);
    if (pd_save_is_mail(item) || item == ITEM_ENIGMA_BERRY) return "Mail and Enigma Berries stay with their save: take it off in the game first";
    return NULL;
}

// The game's "last Pokémon" rule for a party Pokémon about to leave: another one that isn't an egg must stay.
static const char* party_blocker(const struct pd_trade_side* side, int at, char* buf, size_t len) {
    if (!PD_AT_IS_PARTY(at)) return NULL;
    const struct pd_save* s = side->save;
    int others = 0;
    for (int i = 0; i < pd_save_party_count(s) && i < PD_SAVE_PARTY; i++) {
        if (i == PD_AT_PARTY_INDEX(at)) continue;
        struct pd_box_mon m;
        pd_box_mon_read(&m, s->block1 + party_off(s, i));
        if (!pd_box_mon_empty(&m) && !pd_box_mon_egg(&m) && !pd_box_mon_bad_egg(&m)) others++;
    }
    if (others) return NULL;
    snprintf(buf, len, "That's %s's last Pokémon that isn't an egg: it stays in the party", side->label);
    return buf;
}

const char* pd_trade_blocker(const struct pd_trade_side* from, int at, const struct pd_trade_side* to, char* buf, size_t len) {
    struct pd_box_mon m;
    if (!at_valid(from->save, at)) return "No such place";
    if (!pd_save_record_at(from->save, at, &m)) return NULL;
    const char* why = blocker(&m, from, to, buf, len);
    return why ? why : party_blocker(from, at, buf, len);
}

static const struct pd_trade_evolution* evolution_of(const struct pd_box_mon* m, const struct pd_trade_side* to) {
    // A trade evolution renames by the English species name, so only English games evolve on arrival.
    if (pd_box_mon_empty(m) || pd_box_mon_bad_egg(m) || pd_box_mon_egg(m) || to->code[3] != 'E') return NULL;
    int species = pd_box_mon_species(m), item = pd_box_mon_item(m);
    if (item == ITEM_EVERSTONE) return NULL;
    for (int i = 0; i < pd_trade_evolution_count; i++) {
        const struct pd_trade_evolution* e = &pd_trade_evolutions[i];
        if (e->species == species && (e->item == 0 || e->item == item)) return e;
    }
    return NULL;
}

int pd_trade_evolution(const struct pd_trade_side* from, int at, const struct pd_trade_side* to) {
    struct pd_box_mon m;
    if (!pd_save_record_at(from->save, at, &m)) return 0;
    const struct pd_trade_evolution* e = evolution_of(&m, to);
    return e ? e->into : 0;
}

// The species' English name in the games' charset as gSpeciesNames stores it (caps, EOS, zero padding); false if it can't be.
static bool english_name(int species, uint8_t out[10]) {
    if (species <= 0 || species >= pd_species_names_count || !pd_species_names[species]) return false;
    char upper[32];
    species_label(species, upper, sizeof upper);
    memset(out, 0, 10);
    int n = 0;
    for (const unsigned char* p = (const unsigned char*) upper; *p;) {
        if (n >= 10) return false;
        int code;
        if (*p == 0xC3 && (p[1] == 0xA9 || p[1] == 0x89)) code = 0x1B, p += 2; // é / É
        else if ((code = name_char(&p)) < 0) return false;
        out[n++] = (uint8_t) code;
    }
    if (n < 10) out[n] = 0xFF;
    return true;
}

// [m]'s record as it lands in [to]: evolved if it trade-evolves (the item it needed used up, its name changed when it was the
// species' own - EvolutionRenameMon), and [to]'s Pokédex told about it.
static void arrive(const struct pd_box_mon* m, struct pd_trade_side* to, bool evolve, uint8_t out[PD_SAVE_RECORD]) {
    uint32_t personality = pd_box_mon_personality(m);
    if (!pd_box_mon_egg(m)) mark_caught(to->save, pd_box_mon_species(m), personality);
    const struct pd_trade_evolution* e = evolve ? evolution_of(m, to) : NULL;
    if (!e) {
        pd_box_mon_encode(m, out);
        return;
    }
    struct pd_box_mon evolved = *m;
    uint8_t oldName[10], newName[10];
    int len = name_bytes_len(m->header + 8, 10);
    bool renamed = english_name(e->species, oldName) && name_bytes_len(oldName, 10) == len && !memcmp(oldName, m->header + 8, (size_t) len) &&
                   english_name(e->into, newName);
    put16(evolved.data, e->into);
    if (e->item) put16(evolved.data + 2, 0);
    if (renamed) memcpy(evolved.header + 8, newName, 10);
    pd_box_mon_encode(&evolved, out);
    mark_caught(to->save, e->into, personality);
}

// A traded-in record at [at]: a PC place, or a party place (past the last = the end of the party); [keep] = its party bytes.
static void put_incoming(struct pd_save* s, int at, const uint8_t* record, const uint8_t* keep) {
    if (!PD_AT_IS_PARTY(at)) {
        memcpy(rec_ptr(s, at), record, PD_SAVE_RECORD);
        return;
    }
    int count = pd_save_party_count(s), i = PD_AT_PARTY_INDEX(at);
    if (i >= count) {
        write_party_mon(s, count, record, keep);
        s->block1[s->f->partyCountOff] = (uint8_t) (count + 1);
    } else {
        write_party_mon(s, i, record, keep);
    }
}

static void take_out(struct pd_save* s, int at) {
    if (PD_AT_IS_PARTY(at)) remove_party_mon(s, PD_AT_PARTY_INDEX(at));
    else memset(rec_ptr(s, at), 0, PD_SAVE_RECORD);
}

const char* pd_trade(struct pd_trade_side* a, int atA, struct pd_trade_side* b, int atB, bool evolve, char* buf, size_t len) {
    if (!at_valid(a->save, atA) || !at_valid(b->save, atB)) return "No such place";
    struct pd_box_mon ma, mb;
    bool hasA = pd_save_record_at(a->save, atA, &ma), hasB = pd_save_record_at(b->save, atB, &mb);
    if (!hasA) return "There's no Pokémon there";
    if (hasB && pd_box_mon_bad_egg(&mb)) return "A Bad Egg sits there";
    if (!hasB && PD_AT_IS_PARTY(atB) && pd_save_party_count(b->save) >= PD_SAVE_PARTY) {
        snprintf(buf, len, "%s's party is full", b->label);
        return buf;
    }
    const char* why = blocker(&ma, a, b, buf, len);
    if (!why && hasB) why = blocker(&mb, b, a, buf, len);
    if (!why) why = party_blocker(a, atA, buf, len);
    if (!why && hasB) why = party_blocker(b, atB, buf, len);
    if (why) return why;
    uint8_t keepA[PD_SAVE_PARTY_MON], keepB[PD_SAVE_PARTY_MON];
    if (PD_AT_IS_PARTY(atA)) memcpy(keepA, rec_ptr(a->save, atA), PD_SAVE_PARTY_MON);
    if (hasB && PD_AT_IS_PARTY(atB)) memcpy(keepB, rec_ptr(b->save, atB), PD_SAVE_PARTY_MON);
    uint8_t toB[PD_SAVE_RECORD], toA[PD_SAVE_RECORD];
    arrive(&ma, b, evolve, toB);
    if (hasB) arrive(&mb, a, evolve, toA);
    put_incoming(b->save, atB, toB, PD_AT_IS_PARTY(atA) ? keepA : NULL);
    if (hasB) put_incoming(a->save, atA, toA, PD_AT_IS_PARTY(atB) ? keepB : NULL);
    else take_out(a->save, atA);
    // Ruby / Sapphire get their National Dex from any trade with FireRed / LeafGreen / Emerald (pokeruby trade.c).
    struct pd_trade_side* sides[2] = { a, b };
    for (int i = 0; i < 2; i++) {
        struct pd_trade_side *side = sides[i], *partner = sides[1 - i];
        if (side->save->f->kind == PD_SAVE_RUBY_SAPPHIRE && partner->save->f->kind != PD_SAVE_RUBY_SAPPHIRE && !pd_save_national_dex(side->save))
            enable_national_dex(side->save);
    }
    return NULL;
}

// --- TRADE AND BACK (the app's TradeBack) ---

// A save's working copies, to put back when the way home is refused.
struct save_copy { uint8_t b2[PD_SAVE_BLOCK2_MAX], b1[PD_SAVE_BLOCK1_MAX], st[PD_SAVE_STORAGE]; };
static struct save_copy keepHome, keepPartner;

static void keep_copy(const struct pd_save* s, struct save_copy* c) {
    memcpy(c->b2, s->block2, sizeof c->b2);
    memcpy(c->b1, s->block1, sizeof c->b1);
    memcpy(c->st, s->storage, sizeof c->st);
}

static void put_back(struct pd_save* s, const struct save_copy* c) {
    memcpy(s->block2, c->b2, sizeof c->b2);
    memcpy(s->block1, c->b1, sizeof c->b1);
    memcpy(s->storage, c->st, sizeof c->st);
}

// The first empty PC place from the box the PC is on, or PD_AT_NONE.
static int free_box_place(const struct pd_save* s) {
    for (int k = 0; k < PD_SAVE_BOXES; k++) {
        int box = (pd_save_current_box(s) + k) % PD_SAVE_BOXES;
        for (int slot = 0; slot < PD_SAVE_BOX_SLOTS; slot++) {
            struct pd_box_mon m;
            read_box_mon(s, box, slot, &m);
            if (pd_box_mon_empty(&m)) return PD_AT_BOX(box, slot);
        }
    }
    return PD_AT_NONE;
}

const char* pd_trade_back_blocker(const struct pd_trade_side* home, int at, const struct pd_trade_side* partner, char* buf, size_t len) {
    struct pd_box_mon m;
    if (!at_valid(home->save, at) || !pd_save_record_at(home->save, at, &m)) return "There's no Pokémon there";
    const char* why = blocker(&m, home, partner, buf, len);
    if (!why) why = party_blocker(home, at, buf, len);
    if (why) return why;
    if (!evolution_of(&m, partner)) {
        snprintf(buf, len, "It wouldn't evolve in %s", partner->label);
        return buf;
    }
    if (free_box_place(partner->save) == PD_AT_NONE) {
        snprintf(buf, len, "%s's PC is full", partner->label);
        return buf;
    }
    return NULL;
}

const char* pd_trade_back(struct pd_trade_side* home, int at, struct pd_trade_side* partner, char* buf, size_t len) {
    const char* why = pd_trade_back_blocker(home, at, partner, buf, len);
    if (why) return why;
    int waiting = free_box_place(partner->save);
    keep_copy(home->save, &keepHome);
    keep_copy(partner->save, &keepPartner);
    why = pd_trade(home, at, partner, waiting, true, buf, len);
    if (!why && PD_AT_IS_PARTY(at)) {
        // It left a gap the party closed up: it comes back at the end, then steps back to its old place.
        int end = pd_save_party_count(home->save);
        why = pd_trade(partner, waiting, home, PD_AT_PARTY(end), false, buf, len);
        for (int i = end; !why && i > PD_AT_PARTY_INDEX(at); i--) why = pd_save_move_at(home->save, PD_AT_PARTY(i), PD_AT_PARTY(i - 1));
    } else if (!why) {
        why = pd_trade(partner, waiting, home, at, false, buf, len);
    }
    if (why) {
        put_back(home->save, &keepHome);
        put_back(partner->save, &keepPartner);
    }
    return why;
}

// --- MISSING (the app's MissingDex) ---

int pd_missing_list(const struct pd_save* s, struct pd_missing* out, int max) {
    bool national = pd_save_national_dex(s);
    int n = 0;
    for (int number = 1; number <= NATIONAL_COUNT && n < max; number++) {
        // The dex's own number: the National one, Kanto's (the same), or Hoenn's.
        int species = 0;
        for (int sp = 1; sp < PD_SAVE_SPECIES && !species; sp++) {
            if (national || s->f->kanto) { if (national_of(sp) == number && (national || sp <= KANTO_COUNT)) species = sp; }
            else if (pd_species_hoenn[sp] == number && number <= HOENN_COUNT) species = sp;
        }
        if (!species || caught(s, species)) continue;
        memset(&out[n], 0, sizeof out[n]);
        out[n].number = (uint16_t) number;
        out[n].national = (uint16_t) national_of(species);
        out[n].species = (uint16_t) species;
        out[n].source = -1;
        n++;
    }
    return n;
}

// [cand] is a better way than what [e] holds: one that can come first, then a plain trade, then the highest level.
static bool better(const struct pd_missing* e, bool reachable, int way, int level) {
    if (e->way == PD_MISSING_NONE) return true;
    if (reachable != e->reachable) return reachable;
    bool plain = way == PD_MISSING_TRADE, wasPlain = e->way == PD_MISSING_TRADE;
    if (plain != wasPlain) return plain;
    return level > e->level;
}

static void offer(struct pd_missing* list, int n, int national, int source, int at, int species, int level, int way, const char* why) {
    for (int i = 0; i < n; i++) {
        struct pd_missing* e = &list[i];
        if (e->national != national || !better(e, why == NULL, way, level)) continue;
        e->source = (int8_t) source;
        e->at = (int16_t) at;
        e->owned = (uint16_t) species;
        e->level = (uint8_t) level;
        e->way = (uint8_t) way;
        e->reachable = why == NULL;
        snprintf(e->why, sizeof e->why, "%s", why ? why : "");
        return;
    }
}

void pd_missing_add(struct pd_missing* list, int n, const struct pd_trade_side* target, const struct pd_trade_side* other, int source) {
    char buf[160];
    int count = pd_save_party_count(other->save);
    for (int i = 0; i < PD_SAVE_PARTY + PD_SAVE_BOXES * PD_SAVE_BOX_SLOTS; i++) {
        int at = i < PD_SAVE_PARTY ? PD_AT_PARTY(i) : (i - PD_SAVE_PARTY);
        if (i < PD_SAVE_PARTY && i >= count) continue;
        struct pd_box_mon m;
        if (!pd_save_record_at(other->save, at, &m) || pd_box_mon_egg(&m) || pd_box_mon_bad_egg(&m)) continue;
        int species = pd_box_mon_species(&m), level = pd_save_level_at(other->save, at);
        offer(list, n, national_of(species), source, at, species, level, PD_MISSING_TRADE, pd_trade_blocker(other, at, target, buf, sizeof buf));
        // It evolves into a missing one as it arrives.
        int into = pd_trade_evolution(other, at, target);
        if (into) offer(list, n, national_of(into), source, at, species, level, PD_MISSING_EVOLVES, pd_trade_blocker(other, at, target, buf, sizeof buf));
    }
    // The target's own Pokémon that would come back evolved from a trip to [other].
    count = pd_save_party_count(target->save);
    for (int i = 0; i < PD_SAVE_PARTY + PD_SAVE_BOXES * PD_SAVE_BOX_SLOTS; i++) {
        int at = i < PD_SAVE_PARTY ? PD_AT_PARTY(i) : (i - PD_SAVE_PARTY);
        if (i < PD_SAVE_PARTY && i >= count) continue;
        int into = pd_trade_evolution(target, at, other);
        if (!into) continue;
        struct pd_box_mon m;
        if (!pd_save_record_at(target->save, at, &m)) continue;
        offer(list, n, national_of(into), source, at, pd_box_mon_species(&m), pd_save_level_at(target->save, at), PD_MISSING_TRADE_BACK,
              pd_trade_back_blocker(target, at, other, buf, sizeof buf));
    }
}
