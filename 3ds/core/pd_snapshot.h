// One read of the running game: party, battle, place and money - a slice of
// the app's Telemetry, read the way readNativeTelemetry() does
// (NativeReader.kt) with the mon decoding of Gen3Mon.kt.
#ifndef PD_SNAPSHOT_H
#define PD_SNAPSHOT_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "pd_game.h"
#include "pd_stats.h"

// Reads len bytes of the GBA bus at addr into out; false if it can't.
typedef bool (*pd_read_fn)(void* ctx, uint32_t addr, void* out, size_t len);

#define PD_PARTY_SIZE 6
#define PD_NUM_MOVES 4

enum pd_gender {
    PD_GENDER_NONE,
    PD_GENDER_MALE,
    PD_GENDER_FEMALE,
};

// Where Monomyth's natures start in pd_mon.nature: past vanilla's 25, so they raise and lower nothing.
#define PD_MONOMYTH_NATURE_BASE 25
extern const char* const pd_monomyth_natures[250]; // pd_games_gen.c

struct pd_mon {
    int species;      // internal species id; PD_SPECIES_EGG for an egg
    char nickname[32]; // UTF-8, decoded from the game's charset
    enum pd_gender gender; // the party menu's mark (none for eggs, genderless, "NIDORAN♀")
    int level, hp, maxHp;
    uint32_t status;
    int moves[PD_NUM_MOVES];
    int pp[PD_NUM_MOVES];
    uint32_t personality;
    bool shiny;       // only where the config reads it (pd_party_layout shiny*)
    // Optional live fields used by the native companions.
    bool hasExperience, hasHeldItem; // distinguish unavailable data from zero
    uint32_t experience;
    int heldItem;
    bool hasFriendship;
    int friendship;
    struct pd_mon_stats stats; // optional: valid only for verified decoders
    bool egg;         // shown as PD_SPECIES_EGG; the box's isEgg flag
};

struct pd_battle_mon {
    int species;
    int level, hp, maxHp;
    int type1, type2;
    uint32_t status;
    int moves[PD_NUM_MOVES];
    int pp[PD_NUM_MOVES];
    // Optional live battle fields, in Attack/Defense/Speed/Sp.Atk/Sp.Def order: a Gen 2 game's, and a Gen 3 game's where its
    // layout maps them (hasFacts: the ability too, and statStages - HP, Atk, Def, Speed, Sp. Atk, Sp. Def, accuracy,
    // evasion, 6 = unchanged).
    int heldItem, stats[5];
    int ability;
    signed char stages[8];
    bool hasFacts;
    bool shiny; // by its personality and OT ID, where the game's shiny check is known
};

#define PD_POS_PLAYER_LEFT 0
#define PD_POS_OPPONENT_LEFT 1

// A pocket's slots: every pocket of FireRed's and Emerald's bag holds at most 64.
#define PD_POCKET_SLOTS 256 // Emerald Legacy's ITEMS pocket holds 120
#define PD_NATIONAL_COUNT 386
#define PD_DEX_BYTES 256 // 2048 national numbers; the largest game's count is 1222
#define PD_BANK_FLAG_BYTES 0x400 // Quetzal's 0x2000 flags from 0x1000
#define PD_BANK_VARS 0x100
#define PD_FLAG_BYTES 0x300 // the most any game has (a hack's 767); Emerald 0x12C, Emerald Legacy 0x138
#define PD_VAR_COUNT 0x100
#define PD_GAME_STATS 52 // up to GAME_STAT_BERRY_CRUSH_POINTS

struct pd_item {
    uint16_t id, quantity;
};

struct pd_pocket_items {
    int count;
    struct pd_item items[PD_POCKET_SLOTS];
};

// The app's BATTLE_INPUT_*: what the game's battle waits for.
enum pd_battle_state {
    PD_BATTLE_NONE,
    PD_BATTLE_BUSY,
    PD_BATTLE_ACTION,
    PD_BATTLE_MOVE,
    PD_BATTLE_BAG_OPEN,
    PD_BATTLE_PARTY_OPEN,
    PD_BATTLE_TARGET,
};

struct pd_snapshot {
    bool valid;
    uint32_t frame;  // gMain's vblank counter
    int partyCount;
    struct pd_mon party[PD_PARTY_SIZE];
    bool inBattle, isDouble, isTrainer;
    enum pd_battle_state battleState;
    struct pd_battle_mon battlers[4]; // by battler position; species 0 = none
    // The FOE TEAM (trainer battles): gEnemyParty in order, the one out
    // (gBattlerPartyIndexes) and the one the trainer is about to send in -
    // only once the one out has fainted and the pick is alive (the app's
    // SnapshotView.enemyNext). -1 = none.
    int foeCount;
    struct pd_mon foes[PD_PARTY_SIZE];
    int foeActive, foeNext;
    int partyActive; // Optional player party index, -1 when unknown in native Gen 2.
    int mapsec;
    long money; // -1 = unknown
    // By PD_POCKET_*; bagOk = the pockets read (not just empty).
    bool bagOk;
    struct pd_pocket_items bag[PD_POCKET_COUNT];

    // Where the player stands: SaveBlock1.pos and the map's group / number,
    // and gMapHeader's layout size and map type (for the head on the MAP).
    bool posOk;
    int x, y, mapGroup, mapNum;
    int mapW, mapH, mapType;
    int gender; // SaveBlock2.playerGender: 0 boy, 1 girl, -1 unknown

    // POKéDEX: by national number - 1, a bit each (the app's
    // readPokedexState: seen only when SaveBlock2's flags and both of
    // SaveBlock1's copies agree; caught only when also seen).
    bool dexOk, dexNational;
    bool dexStray; // Lazarus: national 0's caught bit, out at the caught array + 0x1FFF (bit 7)
    int dexCount; // national numbers this game's dex holds (bits 0..dexCount-1 of the arrays below)
    uint8_t dexSeen[PD_DEX_BYTES], dexCaught[PD_DEX_BYTES];

    // The GUIDE's save data: SaveBlock1's event flags and vars.
    bool flagsOk;
    const struct pd_config* cfg; // the config it was read with (its flag / var banks)
    uint8_t flags[PD_FLAG_BYTES];
    uint16_t vars[PD_VAR_COUNT];
    // cfg->flagBank / varBank's flags and vars (Quetzal's Johto and Kanto), and the game's clock hour (cfg->nightWild,
    // -1 = not read).
    uint8_t bankFlags[PD_BANK_FLAG_BYTES];
    uint16_t bankVars[PD_BANK_VARS];
    int hour;
    // Polished Crystal's GUIDE world (pd_gb_world.c): its badge and trade banks, the champion's var, wTimeOfDay.
    uint8_t gbBanks[4][32];
    uint8_t gbTeam, gbTimeOfDay;

    // The TRAINER CARD's (games with a cfg->cardStyle): SaveBlock2's name (the
    // game's encoding, 0xFF-ended), ID and play time; the game stats decrypted;
    // Emerald's museum paintings (each winner's species) and battle points.
    bool cardOk;
    uint8_t achievements[16]; // SaveBlock1 + achievementBitsOff; achievementsOn = its magic (if it has one) reads 0xACE1
    bool achievementsOn;
    bool frontierRecordHigh; // SoulGold: any of its Battle Frontier records (SaveBlock2 +0x8B4 x16, +0x9B6 x8) past 49
    uint8_t cardFlags[PD_FLAG_BYTES]; // the card's own reads (TrainerCard.kt flagsOff / varsOff)
    uint16_t cardVars[PD_VAR_COUNT];
    uint8_t cardMagic; // SaveBlock2 + cardMagicOff
    uint8_t playerName[8];
    uint16_t trainerId, playHours;
    uint8_t playMinutes;
    uint32_t gameStats[PD_GAME_STATS];
    uint16_t museumWinners[5];
    uint16_t battlePoints;

    // Polished Crystal guide: read-only progress banks from Gen2Reader.
    bool pcProgressOk;
    uint16_t pcBadges, pcTrades;
    uint8_t pcTimeOfDay;
};

bool pd_flag(const struct pd_snapshot* s, int flag);
int pd_var(const struct pd_snapshot* s, int var);
bool pd_dex_seen(const struct pd_snapshot* s, int national);
bool pd_dex_caught(const struct pd_snapshot* s, int national);
bool pd_bag_has(const struct pd_snapshot* s, int item);

void pd_snapshot_read(struct pd_snapshot* s, const struct pd_game* g, pd_read_fn read, void* ctx);
// readNativeBattleInputFast: what the battle waits for, and from which battler. PD_BATTLE_NONE outside a battle or
// where the handlers aren't known.
enum pd_battle_state pd_battle_state_read(const struct pd_config* cfg, pd_read_fn read, void* ctx, int* battler);

// The English games' text (up to 0xFF or maxLen bytes) as UTF-8; line breaks
// become spaces and control codes are skipped.
void pd_gen3_text(const uint8_t* src, size_t maxLen, char* out, size_t outLen);

// Decodes one struct Pokemon (100 bytes); false for an empty slot.
bool pd_decode_party_mon(const uint8_t* raw, struct pd_mon* out);
// ... by a config's layout (struct pd_party_layout; a plaintext box where the checksum fails).
bool pd_decode_party_mon_l(const uint8_t* raw, const struct pd_party_layout* l, struct pd_mon* out);
bool pd_decode_party_mon_c(const uint8_t* raw, const struct pd_party_layout* l, const struct pd_config* cfg,
                           struct pd_mon* out);
// ... by the config's own layout (NULL = retail's).
bool pd_decode_party_mon_for(const struct pd_config* cfg, const uint8_t* raw, struct pd_mon* out);

// The item's name / bag description in the game's own words ("" if unknown).
const char* pd_item_name(const struct pd_game* g, int item);
const char* pd_item_description(const struct pd_game* g, int item);
// The game's title for a pocket ("POKé BALLS").
const char* pd_pocket_name(int pocket);

// The map section's name ("" if unknown).
const char* pd_mapsec_name(const struct pd_game* g, int mapsec);
// "PSN", "SLP", ... or "" (the app's statusLabel).
const char* pd_status_label(uint32_t status);

#endif
