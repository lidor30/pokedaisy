#include "pd_items.h"

#include <stdio.h>
#include <string.h>

#include <mgba-util/crc32.h>
#include <mgba/core/core.h>

#include "../../app/src/main/cpp/pk_call.h"
#include "../core/pd_games.h"

#define MON_SIZE 100
#define MON_DATA_PERSONALITY 0
#define MON_DATA_SPECIES 11
#define MON_DATA_HELD_ITEM 12
#define MON_DATA_MOVE1 13
#define MON_DATA_IS_EGG 45
#define MAX_ITEMS 1024

static const struct pd_party_fns* fns;
static uint8_t usable[MAX_ITEMS], holdable[MAX_ITEMS];

static bool in_list(const uint16_t* list, int n, int item) {
    for (int i = 0; i < n; i++)
        if (list[i] == item) return true;
    return false;
}

static bool code_ok(const uint8_t* rom, size_t size, uint32_t fn, uint32_t crc) {
    uint32_t off = (fn & ~1u) - 0x08000000;
    return off + 32 <= size && doCrc32(rom + off, 32) == crc;
}

bool pd_items_setup(const struct pd_config* cfg, const uint8_t* rom, size_t romSize) {
    fns = NULL;
    memset(usable, 0, sizeof(usable));
    memset(holdable, 0, sizeof(holdable));
    const struct pd_party_fns* p = cfg ? cfg->partyFns : NULL;
    if (!p || !cfg->gItems) return false;
    uint32_t list[5] = { p->getMonData, p->setMonData, p->itemNoEffect, p->itemEffect, p->addBagItem };
    int crc = 0;
    for (int i = 0; i < 5; i++) {
        if (!list[i]) continue;
        if (!code_ok(rom, romSize, list[i], p->codeCrcs[crc++])) return false;
    }
    int nameLen = cfg->itemNameLen ? cfg->itemNameLen : 14, size = nameLen + 30;
    int importance = nameLen + 10, pocket = nameLen + 12, fieldUse = nameLen + 14;
    const uint8_t* items = rom + (cfg->gItems - 0x08000000);
    int count = p->itemCount < MAX_ITEMS ? p->itemCount : MAX_ITEMS;
    if (cfg->gItems - 0x08000000 + (size_t) count * (size_t) size > romSize) return false;
    for (int id = 1; id < count; id++) {
        const uint8_t* it = items + id * size;
        bool unholdable = p->unholdablePockets >> it[pocket] & 1;
        holdable[id] = it[importance] == 0 && !unholdable && !in_list(p->mail, p->mailCount, id);
        bool listed = in_list(p->medicine, p->medicineCount, id) || in_list(p->ppOne, p->ppOneCount, id) ||
            in_list(p->ppAll, p->ppAllCount, id);
        if (listed && p->useFuncCount) {
            uint32_t fn = (uint32_t) it[fieldUse] | (uint32_t) it[fieldUse + 1] << 8 | (uint32_t) it[fieldUse + 2] << 16 |
                (uint32_t) it[fieldUse + 3] << 24;
            bool known = false;
            for (int k = 0; k < p->useFuncCount; k++) known |= (fn & ~1u) == (p->useFuncs[k] & ~1u);
            listed = known;
        }
        usable[id] = listed;
    }
    fns = p;
    return true;
}

bool pd_items_can_use(int item) {
    return fns && item > 0 && item < MAX_ITEMS && usable[item];
}

bool pd_items_can_give(int item) {
    return fns && item > 0 && item < MAX_ITEMS && holdable[item];
}

bool pd_items_picks_move(int item) {
    return fns && in_list(fns->ppOne, fns->ppOneCount, item);
}

bool pd_items_can_take(void) {
    return fns != NULL;
}

static bool call(struct mCore* core, uint32_t fn, uint32_t a0, uint32_t a1, uint32_t a2, uint32_t a3, uint32_t* r0) {
    const uint32_t args[4] = { a0, a1, a2, a3 };
    return pk_call(core, fn, args, r0) != 0;
}

static uint32_t mon_at(int slot) {
    return fns->party + (uint32_t) (slot * MON_SIZE);
}

static bool get_mon(struct mCore* core, int slot, int field, uint32_t* out) {
    return call(core, fns->getMonData, mon_at(slot), (uint32_t) field, 0, 0, out);
}

static int shift(void) {
    return fns->monFieldShift;
}

static enum pd_item_outcome gate(struct mCore* core, const struct pd_config* cfg, int item, bool allowed, bool busy) {
    if (!fns || !allowed) return PD_ITEM_FAILED;
    uint32_t locked = 1, has = 0;
    if (busy || !call(core, cfg->fieldLocked, 0, 0, 0, 0, &locked) || locked) return PD_ITEM_NOT_NOW;
    if (!call(core, cfg->fieldCheckBag, (uint32_t) item, 1, 0, 0, &has) || has != 1) return PD_ITEM_NONE_LEFT;
    return PD_ITEM_USED;
}

// The effect itself, undone where the game has no dry run: the Pokémon's struct is all it touches on the field.
static bool helps(struct mCore* core, int item, int slot, int move) {
    uint32_t species = 0, r = 1;
    if (item == fns->hpUp && get_mon(core, slot, MON_DATA_SPECIES + shift(), &species) && species == (uint32_t) fns->shedinja) return false;
    if (fns->itemNoEffect) return call(core, fns->itemNoEffect, mon_at(slot), (uint32_t) item, (uint32_t) slot, (uint32_t) move, &r) && r == 0;
    uint8_t saved[MON_SIZE];
    for (int i = 0; i < MON_SIZE; i++) saved[i] = (uint8_t) core->busRead8(core, mon_at(slot) + (uint32_t) i);
    bool ok = call(core, fns->itemEffect, mon_at(slot), (uint32_t) item, (uint32_t) slot, (uint32_t) move, &r) && r == 0;
    for (int i = 0; i < MON_SIZE; i++) core->busWrite8(core, mon_at(slot) + (uint32_t) i, saved[i]);
    return ok;
}

void pd_items_targets(struct mCore* core, const struct pd_config* cfg, int item, bool give, bool busy, struct pd_party_targets* out) {
    memset(out, 0, sizeof(*out));
    out->result = gate(core, cfg, item, give ? pd_items_can_give(item) : pd_items_can_use(item), busy);
    if (out->result != PD_ITEM_USED) return;
    int count = core->busRead8(core, fns->partyCount);
    out->count = count > PD_PARTY_SIZE ? PD_PARTY_SIZE : count;
    for (int slot = 0; slot < out->count; slot++) {
        struct pd_party_target* t = &out->slots[slot];
        uint32_t personality = 0, held = 0, egg = 0;
        if (!get_mon(core, slot, MON_DATA_PERSONALITY, &personality) || !get_mon(core, slot, MON_DATA_HELD_ITEM + shift(), &held) ||
            !get_mon(core, slot, MON_DATA_IS_EGG + shift(), &egg)) {
            out->result = PD_ITEM_FAILED;
            return;
        }
        t->personality = personality;
        t->heldItem = (int) held;
        if (egg) {
            t->verdict = PD_SLOT_EGG;
        } else if (give) {
            t->verdict = in_list(fns->mail, fns->mailCount, (int) held) ? PD_SLOT_HOLDS_MAIL : (int) held == item ? PD_SLOT_HOLDS_IT : PD_SLOT_OK;
        } else if (in_list(fns->ppOne, fns->ppOneCount, item)) {
            bool any = false;
            for (int m = 0; m < 4; m++) {
                uint32_t move = 0;
                t->moves[m] = get_mon(core, slot, MON_DATA_MOVE1 + shift() + m, &move) && move && helps(core, item, slot, m);
                any |= t->moves[m];
            }
            t->verdict = any ? PD_SLOT_OK : PD_SLOT_NO_EFFECT;
        } else {
            t->verdict = helps(core, item, slot, 0) ? PD_SLOT_OK : PD_SLOT_NO_EFFECT;
        }
    }
}

static bool still_there(struct mCore* core, int slot, uint32_t personality) {
    uint32_t p = 0, egg = 1;
    return slot >= 0 && slot < core->busRead8(core, fns->partyCount) && get_mon(core, slot, MON_DATA_PERSONALITY, &p) &&
        p == personality && get_mon(core, slot, MON_DATA_IS_EGG + shift(), &egg) && !egg;
}

enum pd_item_outcome pd_items_use(struct mCore* core, const struct pd_config* cfg, int item, int slot, uint32_t personality,
                                  int move, bool busy) {
    enum pd_item_outcome g = gate(core, cfg, item, pd_items_can_use(item), busy);
    if (g != PD_ITEM_USED) return g;
    int m = pd_items_picks_move(item) ? move : 0;
    uint32_t r = 1;
    if (!still_there(core, slot, personality)) return PD_ITEM_PARTY_CHANGED;
    if (!helps(core, item, slot, m)) return PD_ITEM_NO_EFFECT;
    if (!call(core, fns->itemEffect, mon_at(slot), (uint32_t) item, (uint32_t) slot, (uint32_t) m, &r) || r) return PD_ITEM_NO_EFFECT;
    if (!call(core, cfg->fieldRemoveBag, (uint32_t) item, 1, 0, 0, &r) || r != 1) return PD_ITEM_FAILED;
    return PD_ITEM_USED;
}

// SetMonData(mon, MON_DATA_HELD_ITEM, &item) through the scratch bytes, then read back.
static bool set_held(struct mCore* core, int slot, int item) {
    uint8_t before[2] = { (uint8_t) core->busRead8(core, fns->scratch), (uint8_t) core->busRead8(core, fns->scratch + 1) };
    core->busWrite8(core, fns->scratch, (uint8_t) item);
    core->busWrite8(core, fns->scratch + 1, (uint8_t) (item >> 8));
    uint32_t r = 0, now = 0;
    bool ok = call(core, fns->setMonData, mon_at(slot), (uint32_t) (MON_DATA_HELD_ITEM + shift()), fns->scratch, 0, &r);
    core->busWrite8(core, fns->scratch, before[0]);
    core->busWrite8(core, fns->scratch + 1, before[1]);
    return ok && get_mon(core, slot, MON_DATA_HELD_ITEM + shift(), &now) && (int) now == item;
}

enum pd_item_outcome pd_items_give(struct mCore* core, const struct pd_config* cfg, int item, int slot, uint32_t personality,
                                   bool busy, int* swapped) {
    *swapped = 0;
    enum pd_item_outcome g = gate(core, cfg, item, pd_items_can_give(item), busy);
    if (g != PD_ITEM_USED) return g;
    uint32_t old = 0, r = 0;
    if (!still_there(core, slot, personality)) return PD_ITEM_PARTY_CHANGED;
    if (!get_mon(core, slot, MON_DATA_HELD_ITEM + shift(), &old)) return PD_ITEM_FAILED;
    if (in_list(fns->mail, fns->mailCount, (int) old)) return PD_ITEM_FAILED;
    if ((int) old == item) return PD_ITEM_NO_EFFECT;
    if (!call(core, cfg->fieldRemoveBag, (uint32_t) item, 1, 0, 0, &r) || r != 1) return PD_ITEM_NONE_LEFT;
    if (old && (!call(core, fns->addBagItem, old, 1, 0, 0, &r) || r != 1)) {
        call(core, fns->addBagItem, (uint32_t) item, 1, 0, 0, &r);
        return PD_ITEM_BAG_FULL;
    }
    if (set_held(core, slot, item)) {
        *swapped = (int) old;
        return PD_ITEM_GIVEN;
    }
    // Back as it was: the old item out of the bag and onto the Pokémon, the new one back in.
    if (old && call(core, cfg->fieldRemoveBag, old, 1, 0, 0, &r) && r == 1) set_held(core, slot, (int) old);
    call(core, fns->addBagItem, (uint32_t) item, 1, 0, 0, &r);
    return PD_ITEM_FAILED;
}

enum pd_item_outcome pd_items_take(struct mCore* core, const struct pd_config* cfg, int slot, uint32_t personality, bool busy,
                                   int* held) {
    *held = 0;
    if (!fns) return PD_ITEM_FAILED;
    uint32_t locked = 1, item = 0, r = 0;
    if (busy || !call(core, cfg->fieldLocked, 0, 0, 0, 0, &locked) || locked) return PD_ITEM_NOT_NOW;
    if (!still_there(core, slot, personality)) return PD_ITEM_PARTY_CHANGED;
    if (!get_mon(core, slot, MON_DATA_HELD_ITEM + shift(), &item)) return PD_ITEM_FAILED;
    *held = (int) item;
    if (!item) return PD_ITEM_NO_EFFECT;
    if (in_list(fns->mail, fns->mailCount, (int) item)) return PD_ITEM_FAILED;
    if (!call(core, fns->addBagItem, item, 1, 0, 0, &r) || r != 1) return PD_ITEM_BAG_FULL;
    if (set_held(core, slot, 0)) return PD_ITEM_TAKEN;
    call(core, cfg->fieldRemoveBag, item, 1, 0, 0, &r);
    return PD_ITEM_FAILED;
}

// --- the PC's moves ---

#define BOX_MON 80
#define HP_OFF 0x56
#define BOXES 14
#define SLOTS 30
#define PARTY_MAX 6

// The game's BoxSwapFns (its pd_games_gen.c entry's), for the moves below once pd_box_moves_ok has checked them.
static const struct pd_box_swap* swap;

bool pd_box_moves_ok(const struct pd_config* cfg, const struct pd_box_swap* fnsSwap, const uint8_t* rom, size_t romSize) {
    swap = NULL;
    const struct pd_pc_storage* pc = cfg->pcStorage;
    if (!fns || !fnsSwap || !pc || pc->format != 0 || pc->boxes != BOXES || cfg->pokemonStoragePtr != fnsSwap->boxStoragePtr) return false;
    if (!code_ok(rom, romSize, fnsSwap->boxToMon, fnsSwap->boxCrcs[0]) || !code_ok(rom, romSize, fnsSwap->boxRestorePP, fnsSwap->boxCrcs[1]) ||
        !code_ok(rom, romSize, fnsSwap->boxGetData, fnsSwap->boxCrcs[2])) return false;
    swap = fnsSwap;
    return true;
}

static uint32_t bus32(struct mCore* core, uint32_t a) {
    return (uint32_t) core->busRead8(core, a) | (uint32_t) core->busRead8(core, a + 1) << 8 | (uint32_t) core->busRead8(core, a + 2) << 16 |
        (uint32_t) core->busRead8(core, a + 3) << 24;
}

static int party_count(struct mCore* core) {
    int n = core->busRead8(core, fns->partyCount);
    return n > PARTY_MAX ? PARTY_MAX : n;
}

static bool box_at(struct mCore* core, const struct pd_config* cfg, int box, int slot, uint32_t* out) {
    (void) cfg;
    uint32_t storage = bus32(core, swap->boxStoragePtr);
    if (storage < 0x02000000 || storage >= 0x02040000) return false;
    *out = storage + 4 + (uint32_t) (box * SLOTS + slot) * BOX_MON;
    return true;
}

static bool boxed(struct mCore* core, const struct pd_config* cfg, uint32_t addr, int field, uint32_t* out) {
    (void) cfg;
    return call(core, swap->boxGetData, addr, (uint32_t) field, 0, 0, out);
}

static void copy_out(struct mCore* core, uint32_t addr, uint8_t* to, int n) {
    for (int i = 0; i < n; i++) to[i] = (uint8_t) core->busRead8(core, addr + (uint32_t) i);
}

static void copy_in(struct mCore* core, uint32_t addr, const uint8_t* from, int n) {
    for (int i = 0; i < n; i++) core->busWrite8(core, addr + (uint32_t) i, from[i]);
}

static bool holds_mail(struct mCore* core, int slot) {
    uint32_t item = 0;
    return get_mon(core, slot, MON_DATA_HELD_ITEM + shift(), &item) && in_list(fns->mail, fns->mailCount, (int) item);
}

// The party keeps a Pokémon that can fight without slot [without] ("That's your last POKéMON!").
static bool fighter_left(struct mCore* core, int without) {
    int n = party_count(core);
    for (int i = 0; i < n; i++) {
        uint32_t species = 0, egg = 0;
        if (i == without || !get_mon(core, i, MON_DATA_SPECIES + shift(), &species) || !species) continue;
        if (!get_mon(core, i, MON_DATA_IS_EGG + shift(), &egg) || egg) continue;
        uint32_t a = mon_at(i) + HP_OFF;
        if (core->busRead8(core, a) | core->busRead8(core, a + 1)) return true;
    }
    return false;
}

static enum pd_box_outcome box_gate(struct mCore* core, const struct pd_config* cfg, bool busy) {
    uint32_t locked = 1;
    if (!fns) return PD_BOX_FAILED;
    if (busy || !call(core, cfg->fieldLocked, 0, 0, 0, 0, &locked) || locked) return PD_BOX_NOT_NOW;
    return PD_BOX_DONE;
}

enum pd_box_outcome pd_box_withdraw(struct mCore* core, const struct pd_config* cfg, int box, int slot, uint32_t personality, bool busy) {
    enum pd_box_outcome gate_result = box_gate(core, cfg, busy);
    if (gate_result != PD_BOX_DONE) return gate_result;
    uint32_t mon, species = 0, pid = 0;
    if (!box_at(core, cfg, box, slot, &mon)) return PD_BOX_FAILED;
    int count = party_count(core);
    if (!boxed(core, cfg, mon, MON_DATA_SPECIES, &species) || !species || !boxed(core, cfg, mon, MON_DATA_PERSONALITY, &pid) ||
        pid != personality) return PD_BOX_CHANGED;
    if (count >= PARTY_MAX) return PD_BOX_PARTY_FULL;
    uint8_t party[MON_SIZE], record[BOX_MON];
    copy_out(core, mon_at(count), party, MON_SIZE);
    copy_out(core, mon, record, BOX_MON);
    uint32_t r = 0;
    bool ok = call(core, swap->boxToMon, mon, mon_at(count), 0, 0, &r) && get_mon(core, count, MON_DATA_PERSONALITY, &pid) && pid == personality;
    if (ok) {
        static const uint8_t ZERO[BOX_MON];
        copy_in(core, mon, ZERO, BOX_MON);
        core->busWrite8(core, fns->partyCount, (uint8_t) (count + 1));
        ok = party_count(core) == count + 1 && boxed(core, cfg, mon, MON_DATA_SPECIES, &species) && !species;
    }
    if (ok) return PD_BOX_DONE;
    copy_in(core, mon_at(count), party, MON_SIZE);
    copy_in(core, mon, record, BOX_MON);
    core->busWrite8(core, fns->partyCount, (uint8_t) count);
    return PD_BOX_FAILED;
}

enum pd_box_outcome pd_box_deposit(struct mCore* core, const struct pd_config* cfg, int partySlot, uint32_t personality, int box, bool busy) {
    enum pd_box_outcome gate_result = box_gate(core, cfg, busy);
    if (gate_result != PD_BOX_DONE) return gate_result;
    int count = party_count(core);
    uint32_t pid = 0;
    if (partySlot >= count || !get_mon(core, partySlot, MON_DATA_PERSONALITY, &pid) || pid != personality) return PD_BOX_CHANGED;
    if (count <= 1 || !fighter_left(core, partySlot)) return PD_BOX_LAST_MON;
    if (holds_mail(core, partySlot)) return PD_BOX_HOLDS_MAIL;
    uint32_t mon = 0, species = 1;
    int slot = -1;
    for (int s = 0; s < SLOTS && slot < 0; s++) {
        if (!box_at(core, cfg, box, s, &mon)) return PD_BOX_FAILED;
        if (boxed(core, cfg, mon, MON_DATA_SPECIES, &species) && !species) slot = s;
    }
    if (slot < 0) return PD_BOX_FULL;
    static uint8_t party[PARTY_MAX * MON_SIZE], compacted[PARTY_MAX * MON_SIZE];
    uint8_t record[BOX_MON];
    copy_out(core, fns->party, party, count * MON_SIZE);
    copy_out(core, mon, record, BOX_MON);
    memset(compacted, 0, sizeof(compacted));
    memcpy(compacted, party, (size_t) (partySlot * MON_SIZE));
    memcpy(compacted + partySlot * MON_SIZE, party + (partySlot + 1) * MON_SIZE, (size_t) ((count - partySlot - 1) * MON_SIZE));
    copy_in(core, mon, party + partySlot * MON_SIZE, BOX_MON);
    uint32_t r = 0;
    bool ok = call(core, swap->boxRestorePP, mon, 0, 0, 0, &r) && boxed(core, cfg, mon, MON_DATA_PERSONALITY, &pid) && pid == personality;
    if (ok) {
        copy_in(core, fns->party, compacted, count * MON_SIZE);
        core->busWrite8(core, fns->partyCount, (uint8_t) (count - 1));
        ok = party_count(core) == count - 1 && get_mon(core, count - 1, MON_DATA_SPECIES + shift(), &species) && !species;
    }
    if (ok) return PD_BOX_DONE;
    copy_in(core, fns->party, party, count * MON_SIZE);
    copy_in(core, mon, record, BOX_MON);
    core->busWrite8(core, fns->partyCount, (uint8_t) count);
    return PD_BOX_FAILED;
}

enum pd_box_outcome pd_box_swap(struct mCore* core, const struct pd_config* cfg, int box, int slot, uint32_t boxPersonality, int partySlot,
                                uint32_t partyPersonality, bool busy) {
    enum pd_box_outcome gate_result = box_gate(core, cfg, busy);
    if (gate_result != PD_BOX_DONE) return gate_result;
    uint32_t mon, species = 0, pid = 0, egg = 0;
    if (!box_at(core, cfg, box, slot, &mon)) return PD_BOX_FAILED;
    int count = party_count(core);
    if (partySlot >= count || !get_mon(core, partySlot, MON_DATA_PERSONALITY, &pid) || pid != partyPersonality ||
        !boxed(core, cfg, mon, MON_DATA_SPECIES, &species) || !species || !boxed(core, cfg, mon, MON_DATA_PERSONALITY, &pid) ||
        pid != boxPersonality) return PD_BOX_CHANGED;
    if (holds_mail(core, partySlot)) return PD_BOX_HOLDS_MAIL;
    // Only an egg coming out can leave the party with nothing to fight: a Pokémon out of a box has full HP.
    if (boxed(core, cfg, mon, MON_DATA_IS_EGG, &egg) && egg && !fighter_left(core, partySlot)) return PD_BOX_LAST_MON;
    uint8_t party[MON_SIZE], record[BOX_MON];
    copy_out(core, mon_at(partySlot), party, MON_SIZE);
    copy_out(core, mon, record, BOX_MON);
    uint32_t r = 0;
    bool ok = call(core, swap->boxToMon, mon, mon_at(partySlot), 0, 0, &r);
    if (ok) {
        copy_in(core, mon, party, BOX_MON);
        ok = call(core, swap->boxRestorePP, mon, 0, 0, 0, &r) && get_mon(core, partySlot, MON_DATA_PERSONALITY, &pid) && pid == boxPersonality &&
            boxed(core, cfg, mon, MON_DATA_PERSONALITY, &pid) && pid == partyPersonality;
    }
    if (ok) return PD_BOX_DONE;
    copy_in(core, mon_at(partySlot), party, MON_SIZE);
    copy_in(core, mon, record, BOX_MON);
    return PD_BOX_FAILED;
}

const char* pd_box_outcome_text(enum pd_box_outcome o) {
    switch (o) {
    case PD_BOX_DONE: return "Done.";
    case PD_BOX_NOT_NOW: return "Not now: only on the field.";
    case PD_BOX_CHANGED: return "The party or box changed.";
    case PD_BOX_HOLDS_MAIL: return "Please remove the MAIL.";
    case PD_BOX_LAST_MON: return "That's your last POKéMON!";
    case PD_BOX_PARTY_FULL: return "Your party is full.";
    case PD_BOX_FULL: return "That box is full.";
    default: return "That didn't work.";
    }
}

const char* pd_item_outcome_text(enum pd_item_outcome o) {
    switch (o) {
    case PD_ITEM_USED: return "Used!";
    case PD_ITEM_NOT_NOW: return "You can't use that now.";
    case PD_ITEM_NONE_LEFT: return "None left in the bag.";
    case PD_ITEM_NO_EFFECT: return "It won't have any effect.";
    case PD_ITEM_GIVEN: return "Given.";
    case PD_ITEM_BAG_FULL: return "The bag is full.";
    case PD_ITEM_PARTY_CHANGED: return "The party changed.";
    case PD_ITEM_TAKEN: return "Taken.";
    default: return "That didn't work.";
    }
}
