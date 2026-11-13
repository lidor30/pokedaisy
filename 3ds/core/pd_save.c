#include "pd_save.h"

#include <ctype.h>
#include <stdio.h>
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
    // The game zeroes the slots past the count (ZeroMonData): anything there is another layout's data.
    for (int i = count; i < 6; i++)
        for (int b = 0; b < PD_SAVE_PARTY_MON; b++)
            if (s->block1[f->partyOff + i * PD_SAVE_PARTY_MON + b]) return "Its party doesn't read: another game's save?";
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

// --- edits ---

const char* pd_save_move(struct pd_save* s, int box, int slot, int toBox, int toSlot) {
    if (!box_ok(box, slot) || !box_ok(toBox, toSlot)) return "No such box";
    if (box == toBox && slot == toSlot) return NULL;
    struct pd_box_mon a, b;
    read_box_mon(s, box, slot, &a);
    read_box_mon(s, toBox, toSlot, &b);
    if (pd_box_mon_empty(&a) || pd_box_mon_bad_egg(&a)) return "Nothing to move there";
    if (!pd_box_mon_empty(&b) && pd_box_mon_bad_egg(&b)) return "A Bad Egg sits there";
    uint8_t tmp[PD_SAVE_RECORD];
    uint8_t* ra = s->storage + record_off(box, slot);
    uint8_t* rb = s->storage + record_off(toBox, toSlot);
    memcpy(tmp, ra, PD_SAVE_RECORD);
    memcpy(ra, rb, PD_SAVE_RECORD);
    memcpy(rb, tmp, PD_SAVE_RECORD);
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

// --- trading ---

static bool dex_bit(const uint8_t* base, int off, int national) {
    return base[off + (national - 1) / 8] >> ((national - 1) % 8) & 1;
}

static void set_dex_bit(uint8_t* base, int off, int national) {
    base[off + (national - 1) / 8] |= (uint8_t) (1 << ((national - 1) % 8));
}

static int national_of(int species) {
    return species > 0 && species < PD_SAVE_SPECIES ? pd_species_national[species] : 0;
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

// Ruby / Sapphire's EnableNationalPokedex: what a trade with FireRed / LeafGreen / Emerald does to them.
static void enable_national_dex(struct pd_save* s) {
    const struct pd_save_layout* f = s->f;
    s->block2[f->nationalMagicOff] = (uint8_t) f->nationalMagic;
    put16(s->block1 + f->varsOff + (f->nationalVar - 0x4000) * 2, (unsigned) f->nationalVarValue);
    s->block1[f->flagsOff + f->nationalFlag / 8] |= (uint8_t) (1 << (f->nationalFlag % 8));
    s->block2[POKEDEX_OFF + 1] = 1; // mode: national
    s->block2[POKEDEX_OFF] = 0;     // order: numerical
}

static bool in_regional_dex(int species, const struct pd_save_layout* f) {
    if (f->kanto) return species >= 1 && species <= KANTO_COUNT;
    return species > 0 && species < PD_SAVE_SPECIES && pd_species_hoenn[species] >= 1 && pd_species_hoenn[species] <= HOENN_COUNT;
}

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
    if ((item >= MAIL_FIRST && item <= MAIL_LAST) || item == ITEM_ENIGMA_BERRY)
        return "Mail and Enigma Berries stay with their save: take it off in the game first";
    return NULL;
}

const char* pd_trade_blocker(const struct pd_trade_side* from, int box, int slot, const struct pd_trade_side* to, char* buf, size_t len) {
    if (!box_ok(box, slot)) return "No such box";
    struct pd_box_mon m;
    read_box_mon(from->save, box, slot, &m);
    return blocker(&m, from, to, buf, len);
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

int pd_trade_evolution(const struct pd_trade_side* from, int box, int slot, const struct pd_trade_side* to) {
    if (!box_ok(box, slot)) return 0;
    struct pd_box_mon m;
    read_box_mon(from->save, box, slot, &m);
    const struct pd_trade_evolution* e = evolution_of(&m, to);
    return e ? e->into : 0;
}

// The species' English name in the games' charset as gSpeciesNames stores it (caps, EOS, zero padding); false if it can't be.
static bool english_name(int species, uint8_t out[10]) {
    if (species <= 0 || species >= pd_species_names_count || !pd_species_names[species]) return false;
    const char* name = pd_species_names[species];
    memset(out, 0, 10);
    int n = 0;
    for (const unsigned char* p = (const unsigned char*) name; *p; n++) {
        if (n >= 10) return false;
        unsigned c = *p;
        int code;
        if (c >= 'a' && c <= 'z') code = 0xBB + (int) (c - 'a');
        else if (c >= 'A' && c <= 'Z') code = 0xBB + (int) (c - 'A');
        else if (c >= '0' && c <= '9') code = 0xA1 + (int) (c - '0');
        else if (c == ' ') code = 0x00;
        else if (c == '.') code = 0xAD;
        else if (c == '-') code = 0xAE;
        else if (c == '\'') code = 0xB4;
        else if (c == 0xE2 && p[1] == 0x99 && (p[2] == 0x82 || p[2] == 0x80)) { // ♂ / ♀
            code = p[2] == 0x82 ? 0xB5 : 0xB6;
            p += 2;
        } else if (c == 0xC3 && (p[1] == 0xA9 || p[1] == 0x89)) { // é / É
            code = 0x1B;
            p += 1;
        } else return false;
        out[n] = (uint8_t) code;
        p++;
    }
    if (n < 10) out[n] = 0xFF;
    return true;
}

static int name_len(const uint8_t* b) {
    int n = 0;
    while (n < 10 && b[n] != 0xFF) n++;
    return n;
}

// [m]'s record as it lands in [to]: evolved if it trade-evolves (the item it needed used up, its name changed when it was the
// species' own - EvolutionRenameMon), and [to]'s Pokédex told about it.
static void arrive(const struct pd_box_mon* m, struct pd_trade_side* to, uint8_t out[PD_SAVE_RECORD]) {
    uint32_t personality = pd_box_mon_personality(m);
    if (!pd_box_mon_egg(m)) mark_caught(to->save, pd_box_mon_species(m), personality);
    const struct pd_trade_evolution* e = evolution_of(m, to);
    if (!e) {
        pd_box_mon_encode(m, out);
        return;
    }
    struct pd_box_mon evolved = *m;
    uint8_t oldName[10], newName[10];
    bool renamed = english_name(e->species, oldName) && name_len(oldName) == name_len(m->header + 8) &&
                   !memcmp(oldName, m->header + 8, (size_t) name_len(oldName)) && english_name(e->into, newName);
    put16(evolved.data, e->into);
    if (e->item) put16(evolved.data + 2, 0);
    if (renamed) memcpy(evolved.header + 8, newName, 10);
    pd_box_mon_encode(&evolved, out);
    mark_caught(to->save, e->into, personality);
}

const char* pd_trade(struct pd_trade_side* a, int box, int slot, struct pd_trade_side* b, int toBox, int toSlot, char* buf, size_t len) {
    if (!box_ok(box, slot) || !box_ok(toBox, toSlot)) return "No such box";
    struct pd_box_mon ma, mb;
    read_box_mon(a->save, box, slot, &ma);
    read_box_mon(b->save, toBox, toSlot, &mb);
    if (pd_box_mon_empty(&ma)) return "There's no Pokémon there";
    if (!pd_box_mon_empty(&mb) && pd_box_mon_bad_egg(&mb)) return "A Bad Egg sits there";
    const char* why = blocker(&ma, a, b, buf, len);
    if (!why && !pd_box_mon_empty(&mb)) why = blocker(&mb, b, a, buf, len);
    if (why) return why;
    uint8_t toB[PD_SAVE_RECORD], toA[PD_SAVE_RECORD] = { 0 };
    arrive(&ma, b, toB);
    if (!pd_box_mon_empty(&mb)) arrive(&mb, a, toA);
    memcpy(b->save->storage + record_off(toBox, toSlot), toB, PD_SAVE_RECORD);
    memcpy(a->save->storage + record_off(box, slot), toA, PD_SAVE_RECORD);
    // Ruby / Sapphire get their National Dex from any trade with FireRed / LeafGreen / Emerald (pokeruby trade.c).
    struct pd_trade_side* sides[2] = { a, b };
    for (int i = 0; i < 2; i++) {
        struct pd_trade_side *side = sides[i], *partner = sides[1 - i];
        if (side->save->f->kind == PD_SAVE_RUBY_SAPPHIRE && partner->save->f->kind != PD_SAVE_RUBY_SAPPHIRE && !pd_save_national_dex(side->save))
            enable_national_dex(side->save);
    }
    return NULL;
}
