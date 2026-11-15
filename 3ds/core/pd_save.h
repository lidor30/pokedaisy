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
#define PD_SAVE_TMHM 58       // TM01..TM50, HM01..HM08
#define PD_SAVE_LAST_MOVE 354 // Psycho Boost

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

// A box record's fields (logical data: Growth 0, Attacks 12, EVs 24, Misc 36).
uint32_t pd_box_mon_ot_id(const struct pd_box_mon* m);
uint32_t pd_box_mon_exp(const struct pd_box_mon* m);
int pd_box_mon_friendship(const struct pd_box_mon* m);
void pd_box_mon_ivs(const struct pd_box_mon* m, int out[6]); // HP, Attack, Defense, Speed, Sp. Atk, Sp. Def
void pd_box_mon_evs(const struct pd_box_mon* m, int out[6]);
int pd_box_mon_ability_num(const struct pd_box_mon* m);
int pd_box_mon_move(const struct pd_box_mon* m, int i);
int pd_box_mon_language(const struct pd_box_mon* m);

// --- what a species / personality means (pokemon.c) ---

enum pd_save_gender { PD_SAVE_MALE, PD_SAVE_FEMALE, PD_SAVE_GENDERLESS };
enum pd_save_gender pd_save_gender_of(int species, uint32_t personality);
bool pd_save_both_genders(int species);
bool pd_save_is_shiny(uint32_t personality, uint32_t otId);
int pd_save_ability_count(int species); // 1 or 2
int pd_save_ability(int species, int abilityNum);
const char* pd_save_ability_name(int ability);
// CalculateMonStats: the six stats, in the game's order.
void pd_save_stats(int species, int level, const int ivs[6], const int evs[6], int nature, int out[6]);
uint32_t pd_save_exp_for(int species, int level);

// --- where a Pokémon is: a PC place (box * 30 + slot) or a party one (PD_AT_PARTY(i)) ---

#define PD_AT_PARTY(i) (-1 - (i))
#define PD_AT_IS_PARTY(at) ((at) < 0 && (at) > PD_AT_NONE)
#define PD_AT_PARTY_INDEX(at) (-1 - (at))
#define PD_AT_BOX(box, slot) ((box) * PD_SAVE_BOX_SLOTS + (slot))
#define PD_AT_NONE (-100)
#define PD_SAVE_PARTY 6

// The Pokémon at [at] as the companion shows one, and as stored; false = none there.
bool pd_save_mon_at(const struct pd_save* s, int at, struct pd_mon* out);
bool pd_save_record_at(const struct pd_save* s, int at, struct pd_box_mon* out);
// Its level: the party's level byte, a box record's from its EXP.
int pd_save_level_at(const struct pd_save* s, int at);
// A party Pokémon's HP and stats as stored (a box one's worked out from its record).
void pd_save_stats_at(const struct pd_save* s, int at, int out[6], int* hp);

// --- edits (the app's Gen3SaveEditor: values only as the game allows them, else nothing changes; NULL = done, else why) ---

// Moves the Pokémon at (box, slot) to (toBox, toSlot), swapping with whatever is there.
const char* pd_save_move(struct pd_save* s, int box, int slot, int toBox, int toSlot);
// The PC's MOVE between any two places: a party place past its last Pokémon adds to the party; the PC's refusals (mail,
// the last Pokémon able to battle, a full party).
const char* pd_save_move_at(struct pd_save* s, int from, int to);
bool pd_save_set_money(struct pd_save* s, long amount);
bool pd_save_set_coins(struct pd_save* s, int amount);

// A Pokémon's page. Eggs and Bad Eggs aren't edited.
const char* pd_save_editable(const struct pd_save* s, int at);
int pd_save_level_as_read(const struct pd_save* s, int at);     // the editor never takes it lower
const char* pd_save_set_level(struct pd_save* s, int at, int level); // its growth rate's EXP for it
const char* pd_save_set_ivs(struct pd_save* s, int at, const int ivs[6]);
const char* pd_save_set_evs(struct pd_save* s, int at, const int evs[6]); // 255 a stat, 510 in all
const char* pd_save_set_friendship(struct pd_save* s, int at, int value);
bool pd_save_is_own(const struct pd_save* s, int at); // the save's trainer is its OT: only they rename it
const char* pd_save_set_nickname(struct pd_save* s, int at, const char* utf8);
// Another personality with the nature (-1 = keep), shininess / gender / ability bit (-1 keep, else the value) asked for,
// keeping everything else it decides (Unown's letter, Wurmple's branch).
const char* pd_save_reroll(struct pd_save* s, int at, int nature, int shiny, int gender, int abilityNum);
const char* pd_save_set_held_item(struct pd_save* s, int at, int item);
// 1-4 different moves, each one it knows or can learn at its level; a kept move keeps its PP and PP Ups.
const char* pd_save_set_moves(struct pd_save* s, int at, const int* moves, int n);
// Every move the Pokémon at [at] could know (bit m of out, 45 bytes): level-up up to its level, TM / HM, tutors, its
// pre-evolutions' too. Its count.
int pd_save_learnable(const struct pd_save* s, int at, uint8_t out[(PD_SAVE_LAST_MOVE + 8) / 8]);
// What a Pokémon may be given to hold, by id: the bag's items but HMs and mail. Their count.
int pd_save_holdable(const struct pd_save* s, uint16_t* out, int max);
bool pd_save_is_mail(int item);
int pd_save_tmhm_move(int item); // the move a TM / HM teaches, 0 = not one

// Boxes.
const char* pd_save_rename_box(struct pd_save* s, int box, const char* utf8);
enum pd_box_sort { PD_SORT_DEX, PD_SORT_LEVEL, PD_SORT_NAME };
void pd_save_sort_box(struct pd_save* s, int box, enum pd_box_sort by);
const char* pd_save_release(struct pd_save* s, int at);
// Whether [utf8] can be written in the games' Western charset, in at most [max] characters.
bool pd_save_name_ok(const char* utf8, int max);

// The bag.
enum pd_save_pocket { PD_BAG_ITEMS, PD_BAG_BALLS, PD_BAG_TMHM, PD_BAG_BERRIES, PD_BAG_KEY, PD_BAG_POCKETS };
struct pd_stack { int item, quantity; };
int pd_save_pocket_slots(const struct pd_save* s, enum pd_save_pocket p);
int pd_save_pocket(const struct pd_save* s, enum pd_save_pocket p, struct pd_stack* out, int max);
int pd_save_stack_max(const struct pd_save* s, enum pd_save_pocket p, int item);
bool pd_save_stack_fixed(enum pd_save_pocket p, int item); // key items and HMs: never changed or taken out
const char* pd_save_set_quantity(struct pd_save* s, enum pd_save_pocket p, int index, int quantity); // 0 takes it out
const char* pd_save_add_item(struct pd_save* s, enum pd_save_pocket p, int item, int quantity);
// The items the game files in [p] that the pocket doesn't hold yet, by id. Their count (0 for KEY).
int pd_save_addable(const struct pd_save* s, enum pd_save_pocket p, uint16_t* out, int max);

// The Pokédex.
struct pd_save_dex { int seen, caught, regionalSeen, regionalCaught, regionalSize; bool national; const char* region; };
void pd_save_dex_counts(const struct pd_save* s, struct pd_save_dex* out);
void pd_save_register_owned(struct pd_save* s); // every Pokémon the save holds, caught
void pd_save_mark_all_dex(struct pd_save* s, bool caught); // the regional dex's, all of them with the National Dex

// The event items Nintendo handed out, as their distribution scripts gave them (the app's Gen3Events).
enum pd_save_event { PD_EVENT_EON, PD_EVENT_AURORA, PD_EVENT_MYSTIC, PD_EVENT_OLD_SEA_MAP, PD_EVENTS };
enum pd_event_state { PD_EVENT_NOT_HERE, PD_EVENT_AVAILABLE, PD_EVENT_HAS, PD_EVENT_DONE };
const char* pd_save_event_name(enum pd_save_event e);
const char* pd_save_event_note(const struct pd_save* s);
enum pd_event_state pd_save_event_state(const struct pd_save* s, enum pd_save_event e);
const char* pd_save_give_event(struct pd_save* s, enum pd_save_event e);

// --- trading between two saves (the app's Gen3Trade): a PC place or a party Pokémon on either side ---

struct pd_trade_side {
    struct pd_save* save;
    const char* label; // the game's name, for the reasons
    char code[5];      // the ROM's header game code
};

// Why the Pokémon at [at] of [from] can't go to [to] (in words for the player), or NULL.
const char* pd_trade_blocker(const struct pd_trade_side* from, int at, const struct pd_trade_side* to, char* buf, size_t len);
// The species the Pokémon at [at] of [from] would become arriving in [to], or 0.
int pd_trade_evolution(const struct pd_trade_side* from, int at, const struct pd_trade_side* to);
// Sends [atA] of [a] to [b]'s empty [atB] (an empty PC place, or a party place past its last Pokémon), or swaps the two:
// each side's checks first, then the trade evolutions (unless [evolve] is false: the game's B in its evolution scene), the
// Pokédex and Ruby / Sapphire's National Dex. NULL = done, else why not (nothing changed then).
const char* pd_trade(struct pd_trade_side* a, int atA, struct pd_trade_side* b, int atB, bool evolve, char* buf, size_t len);

// pd_save_tables_gen.c (tools/gen_save_tables.py).
extern const uint16_t pd_species_national[PD_SAVE_SPECIES];
extern const uint16_t pd_species_hoenn[PD_SAVE_SPECIES];
struct pd_trade_evolution { uint16_t species, item, into; };
extern const struct pd_trade_evolution pd_trade_evolutions[];
extern const int pd_trade_evolution_count;
extern const uint8_t pd_save_base_stats[PD_SAVE_SPECIES][6];
extern const uint8_t pd_save_abilities[PD_SAVE_SPECIES][2];
extern const uint8_t pd_save_gender_ratios[PD_SAVE_SPECIES];
extern const char* const pd_save_ability_names[];
extern const int pd_save_ability_names_count;
extern const uint16_t pd_save_pre_evolution[PD_SAVE_SPECIES];
extern const uint16_t pd_save_tmhm_moves[PD_SAVE_TMHM];
extern const uint8_t pd_save_move_pp[PD_SAVE_LAST_MOVE + 1];
// Per pd_save_kind: what that game family may hold (the app's Gen3Legal).
struct pd_save_legal {
    const uint16_t (*bag)[2]; // (item, pocket 1 items / 2 balls / 3 TMs & HMs / 4 berries); key items never listed
    int bagCount;
    const uint16_t* levelUp;      // level << 9 | move
    const uint16_t* levelUpStart; // species' run: levelUp[levelUpStart[s] .. levelUpStart[s + 1])
    const uint64_t* tmhm;         // bit n = pd_save_tmhm_moves[n]
    const uint32_t* tutors;       // bit n = tutorMoves[n]
    const uint16_t* tutorMoves;
    int tutorCount;
};
extern const struct pd_save_legal pd_save_legal[3];

#endif
