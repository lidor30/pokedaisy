// A Gen 3 save file (Ruby / Sapphire / Emerald / FireRed / LeafGreen's 128 KB flash) read and written the way the games'
// own save.c does it: the app's Gen3SaveFile / Gen3SaveData / Gen3SaveEditor / Gen3Trade (companion/data/save/), ported.
//
// - Two slots of 14 sectors (0x0000 and 0xE000), each 4 KB: 0xF80 bytes of data, then the sector's id, a checksum, the
//   signature 0x08012025 and the save counter. The ids rotate inside a slot.
// - The game loads the slot whose 14 sectors are all valid and whose counter is newer, so an edit is written like a save:
//   into the *other* slot, counter + 1, rotation + 1, the loaded slot left as the game's fallback. Everything outside that
//   slot is copied byte for byte, and the result is read back before it's handed out (pd_save_build).
// - Retail English / European layouts only (a Japanese build's structs are shorter); pd_save_mismatch refuses a save of
//   another layout (a hack's own).
#ifndef PD_SAVE_H
#define PD_SAVE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "pd_snapshot.h"

#define PD_SAVE_SECTOR 0x1000
#define PD_SAVE_DATA 0xF80
#define PD_SAVE_SLOT_SECTORS 14
#define PD_SAVE_MIN_SIZE (2 * PD_SAVE_SLOT_SECTORS * PD_SAVE_SECTOR)
#define PD_SAVE_MAX_SIZE (0x20000 + 0x100) // 128 KB of flash, then mGBA's RTC footer
#define PD_SAVE_BLOCK2_MAX 0xF2C
#define PD_SAVE_BLOCK1_MAX 0x3D88
#define PD_SAVE_STORAGE 0x83D0
#define PD_SAVE_BOXES 14
#define PD_SAVE_BOX_SLOTS 30
#define PD_SAVE_RECORD 80
#define PD_SAVE_PARTY_MON 100
#define PD_SAVE_SPECIES 412 // internal ids 0..411; 412 is the egg
#define PD_SAVE_MAX_MONEY 999999
#define PD_SAVE_MAX_COINS 9999

enum pd_save_kind { PD_SAVE_RUBY_SAPPHIRE, PD_SAVE_EMERALD, PD_SAVE_FIRERED_LEAFGREEN };

// Offsets from the decomps' global.h (the app's Gen3SaveFormat).
struct pd_save_layout {
    enum pd_save_kind kind;
    const char* label;
    int block2Size, block1Size;
    int partyCountOff, partyOff, moneyOff, coinsOff, encryptionKeyOff; // -1 = no key (Ruby / Sapphire)
    int flagsOff, varsOff, seen1Off, seen2Off;
    int nationalMagicOff, nationalMagic, nationalVar, nationalVarValue, nationalFlag;
    int linkRsFlag; // FireRed / LeafGreen's FLAG_SYS_CAN_LINK_WITH_RS, -1 elsewhere
    int badgeFlag;  // the first of the eight badge flags
    bool kanto;     // the regional dex is Kanto's 151
};

// The layout a ROM's header game code (BPRE, AXVE, ...) saves in; NULL = none known (Japanese, a hack's own code).
const struct pd_save_layout* pd_save_layout_for(const char* code);

enum pd_save_slot_status { PD_SAVE_SLOT_OK, PD_SAVE_SLOT_EMPTY, PD_SAVE_SLOT_CORRUPT };

struct pd_save {
    const struct pd_save_layout* f;
    uint8_t raw[PD_SAVE_MAX_SIZE]; // the file as read
    size_t rawLen;
    int slot, rotation; // the slot the game loads and where its id 0 sits
    uint32_t counter;
    enum pd_save_slot_status otherSlot;
    // The blocks as read, and the working copies the edits change.
    uint8_t block2[PD_SAVE_BLOCK2_MAX], block1[PD_SAVE_BLOCK1_MAX], storage[PD_SAVE_STORAGE];
    uint8_t read2[PD_SAVE_BLOCK2_MAX], read1[PD_SAVE_BLOCK1_MAX], readStorage[PD_SAVE_STORAGE];
};

// Reads [bytes] as an [f] save; NULL on success, else why not (in words for the player).
const char* pd_save_parse(struct pd_save* s, const uint8_t* bytes, size_t len, const struct pd_save_layout* f);
// Why the blocks don't look like an [f] save (a hack's own layout, another game), or NULL.
const char* pd_save_mismatch(const struct pd_save* s);
bool pd_save_changed(const struct pd_save* s);
// The whole file with the working copies saved into the other slot, read back and checked: [out] holds s->rawLen bytes.
// NULL on success, else why it wasn't (nothing to write then).
const char* pd_save_build(const struct pd_save* s, uint8_t* out);
// Back to the blocks as read.
void pd_save_revert(struct pd_save* s);

// --- reading ---

void pd_save_trainer_name(const struct pd_save* s, char* out, size_t len);
int pd_save_gender(const struct pd_save* s);
int pd_save_trainer_id(const struct pd_save* s);
int pd_save_play_hours(const struct pd_save* s);
int pd_save_play_minutes(const struct pd_save* s);
long pd_save_money(const struct pd_save* s);
int pd_save_coins(const struct pd_save* s);
int pd_save_badges(const struct pd_save* s);
int pd_save_dex_caught(const struct pd_save* s);
bool pd_save_flag(const struct pd_save* s, int flag);
bool pd_save_national_dex(const struct pd_save* s);
int pd_save_party_count(const struct pd_save* s);
// The party's / a box record's Pokémon decoded as the companion shows one (level from its EXP for a box record); false = none.
bool pd_save_party_mon(const struct pd_save* s, int i, struct pd_mon* out);
bool pd_save_box_mon(const struct pd_save* s, int box, int slot, struct pd_mon* out);
void pd_save_box_name(const struct pd_save* s, int box, char* out, size_t len);
int pd_save_box_count(const struct pd_save* s, int box);
int pd_save_current_box(const struct pd_save* s);

// One struct BoxPokemon (the app's Gen3BoxMon): its four substructs decrypted, in logical order.
struct pd_box_mon {
    uint8_t header[32], data[48], original[PD_SAVE_RECORD];
    bool checksumOk;
};
void pd_box_mon_read(struct pd_box_mon* m, const uint8_t* record);
void pd_box_mon_encode(const struct pd_box_mon* m, uint8_t out[PD_SAVE_RECORD]);
int pd_box_mon_species(const struct pd_box_mon* m);
int pd_box_mon_item(const struct pd_box_mon* m);
uint32_t pd_box_mon_personality(const struct pd_box_mon* m);
bool pd_box_mon_empty(const struct pd_box_mon* m);
bool pd_box_mon_bad_egg(const struct pd_box_mon* m);
bool pd_box_mon_egg(const struct pd_box_mon* m);
bool pd_box_mon_fateful(const struct pd_box_mon* m);
int pd_save_level_for(int species, uint32_t exp);

// --- edits (the app's Gen3SaveEditor: values only as the game allows them, else nothing changes) ---

// Moves the Pokémon at (box, slot) to (toBox, toSlot), swapping with whatever is there. NULL = done, else why not.
const char* pd_save_move(struct pd_save* s, int box, int slot, int toBox, int toSlot);
bool pd_save_set_money(struct pd_save* s, long amount);
bool pd_save_set_coins(struct pd_save* s, int amount);

// --- trading between two saves, PC box to PC box (the app's Gen3Trade) ---

struct pd_trade_side {
    struct pd_save* save;
    const char* label; // the game's name, for the reasons
    char code[5];      // the ROM's header game code
};

// Why the Pokémon at (box, slot) of [from] can't go to [to] (in words for the player), or NULL.
const char* pd_trade_blocker(const struct pd_trade_side* from, int box, int slot, const struct pd_trade_side* to, char* buf, size_t len);
// The species the Pokémon at (box, slot) of [from] would become arriving in [to], or 0.
int pd_trade_evolution(const struct pd_trade_side* from, int box, int slot, const struct pd_trade_side* to);
// Sends (box, slot) of [a] to the empty (toBox, toSlot) of [b], or swaps it with the Pokémon there: each side's checks
// first, then the trade evolutions, the Pokédex and Ruby / Sapphire's National Dex. NULL = done, else why not (nothing
// changed then).
const char* pd_trade(struct pd_trade_side* a, int box, int slot, struct pd_trade_side* b, int toBox, int toSlot, char* buf, size_t len);

// pd_save_tables_gen.c (tools/gen_save_tables.py).
extern const uint16_t pd_species_national[PD_SAVE_SPECIES];
extern const uint16_t pd_species_hoenn[PD_SAVE_SPECIES];
struct pd_trade_evolution { uint16_t species, item, into; };
extern const struct pd_trade_evolution pd_trade_evolutions[];
extern const int pd_trade_evolution_count;

#endif
