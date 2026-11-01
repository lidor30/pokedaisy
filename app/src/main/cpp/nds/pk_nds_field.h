// The DS games' field read (pk_wide.c's pk_field_state for a Gen 4 game): what the overworld is doing this frame, from
// the game's FieldSystem (pret/pokeplatinum's field_system.h). Portable - the app (pk_nds.cpp) and the headless tool
// (native-capture/nds_dump's fieldinfo) read memory through the callback.
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Where one DS game keeps its field state. */
typedef struct PkNdsFieldGame {
    const char* name;
    uint32_t fieldSystemPtr;   // sFieldSystem: the FieldSystem, NULL outside the overworld's process
    uint32_t battleTemplate;   // gBattleApplicationTemplate: the battle's child process copies its init function
    int offProcessManager;     // FieldSystem.processManager: FieldProcessManager { parent, child, pause, kill }
    int offTask;               // FieldSystem.task: a field task (a script, a warp, a menu's own task), NULL when free
    int offPlayerAvatar;       // FieldSystem.playerAvatar
    int offRunningFieldMap;    // FieldSystem.runningFieldMap: the field map is up and drawing
    int offAvatarState;        // PlayerAvatar.playerAvatarState: PLAYER_AVATAR_WALKING / _CYCLING / _SURFING / ...
    int offMapHeaderData;      // FieldSystem.mapHeaderData: the map's events and its WildEncounters, loaded on entering it
    int offGrassRate;          // MapHeaderData.wildEncounters.grassEncounters.encounterRate (0 = no random encounter)
    int offSurfRate;           // ... .surfEncounters.encounterRate
    int offLocation;           // FieldSystem.location: Location { mapHeaderID, ... }
    uint32_t fieldMapMain;     // gFieldMapTemplate.main (Thumb): the field map's per-frame function, where pk_nds_call hooks in
    uint32_t currentBgm;       // sSoundSystem.currentBGM (u16): the song playing (STEADY FF music starts again on a new one)
    uint32_t seqPlayers;       // NitroSystem's sSeqPlayer[16] (NNSSndSeqPlayer, 0x44 bytes): a volume fade running (STEADY waits it out)
    int rateBytes;             // the encounter rates are u8s (HeartGold / SoulSilver's EncounterData), not Platinum's u32s
    int callSet;               // pk_nds_call's functions for this game: 0 none, 1 Platinum's, 2 HeartGold / SoulSilver's, 3 Black's, 4 White's,
                               // 5 Black 2's, 6 White 2's, 7 Origin HeartGold's
    int offBattleContext;      // BattleSystem.ctx (the battle process's data is the BattleSystem), 0 = unknown
    int offCommand;            // BattleContext.command: the battle controller's step (constants/battle.h's CONTROLLER_COMMAND_*)
    // Gen 5 (Black): no FieldSystem. fieldSystemPtr is then the static GameSystem pointer, and the scene comes from it:
    // its proc system's running proc (whose overlay says field / battle / an app), its field map and its event.
    int gen;                   // 5 for Gen 5; 0 = Gen 4
    int offProcSys;            // GameSystem's GFL proc system; its running proc at offCurrentProc
    int offCurrentProc;
    int offProcOverlay;        // the proc's overlay id: fieldOverlay / battleOverlay, anything else an app (the party, the bag, ...)
    int fieldOverlay;
    int battleOverlay;
    int offFieldMap;           // GameSystem's field map work: NULL while a full-screen app or a battle runs
    int offEvent;              // GameSystem's running GMEVENT: NULL when the field is free (a script, a menu, a warp hold one)
    int offProcWork;           // the battle proc's work: the battle's main module
    int offClient;             // main module -> client 0 (the player's)
    int offAdapter;            // client -> its adapter
    int offAdapterCommand;     // adapter's command byte (BTL_ACMD_*)
    int battleSelecting;       // the command while the player chooses (Gen 4: PK_NDS_BATTLE_SELECTING)
    int textBg;                // engine A's BG the field text box is drawn on (0 = Gen 4's BG3)
    int offGameData;           // GameSystem -> GameData
    int offZone;               // GameData's player record: the zone (u16) the player is on, live
    int offMoveForm;           // GameData: the player's form (0 walking, 1 cycling, 2 surfing)
    int offEventData;          // GameData -> the zone's event data, which holds the zone's encounter table inline
    int offEncounterRates;     // the event data's encounter table: 7 rate bytes (grass, dark grass, rustling grass,
                               // surf, rippling surf, fishing, rippling fishing)
    uint32_t bgmDepth;         // the BGM stack's depth (u32); currentBgm is its first entry's song, 0x1C an entry
    // A battle engine of the hack's own (Origin HeartGold): no BattleSystem / BattleContext, but a manager whose static
    // pointer is here, its touch screen's controller at offBattleUi and that controller's input handler at offUiHandler -
    // set only while the battle waits for a tap (the commands, the moves, the party). 0 = HeartGold's battle.
    uint32_t battleManagerPtr;
    int offBattleUi;
    int offUiHandler;
} PkNdsFieldGame;

// pk_wide.h's PK_FIELD_* bits, which the engine already reads, plus two the DS sets:
#define PK_NDS_FIELD_KNOWN 1
#define PK_NDS_FIELD_LOCKED 2
#define PK_NDS_FIELD_BATTLE 4
#define PK_NDS_FIELD_TEXT 8
#define PK_NDS_FIELD_BIKE 16
#define PK_NDS_FIELD_WILD_HELD 32
#define PK_NDS_FIELD_SURF 64
#define PK_NDS_FIELD_AVATAR 128
/** The overworld itself is on screen (the field map process runs, no child process over it). */
#define PK_NDS_FIELD_SCREEN 256
/** A menu screen (the party, the bag, the Pokédex, ...) runs over the field as its child process. */
#define PK_NDS_FIELD_MENU 512
/** A battle waits for the player's command (pk_nds_battle_command is PK_NDS_BATTLE_SELECTING): SMART FF's 1x. */
#define PK_NDS_FIELD_BATTLE_INPUT 1024
/** The game wants its touch screen now - one screen's DS LAYOUT floats it in over the game (HeartGold / SoulSilver, Black /
 * White: the X menu, a menu screen, a battle's bag / party / prompts; Platinum: a menu screen, a battle's; pk_nds_field.cpp's
 * touch rules). */
#define PK_NDS_FIELD_TOUCH 2048
/** A battle's command or move panel is on the touch screen (BATTLE BUTTONS take its place where they can). */
#define PK_NDS_FIELD_TOUCH_BATTLE_MENU 4096
/** A menu screen whose page is all on the touch screen (HG/SS's party list, options, the Pokédex's first page; Black /
 * White's party, bag, trainer card, options): it floats in alone, not beside the top screen. */
#define PK_NDS_FIELD_TOUCH_ONLY 8192
/** The touch screen PK_NDS_FIELD_TOUCH wants is a new one the game fades in first (a battle's bag / party): it floats in
 * once that's done, not over the old screen fading out. */
#define PK_NDS_FIELD_TOUCH_FADES 16384
/** The top screen / the touch screen is one colour (black between scenes, a fade's end; pk_nds.cpp's oneColour). */
#define PK_NDS_FIELD_DARK_TOP 32768
#define PK_NDS_FIELD_DARK_BOTTOM 65536
/** A menu screen all on the top screen, its touch screen only a picture (Platinum's party and options): one screen's DS
 * LAYOUT keeps the game alone, not side by side. */
#define PK_NDS_FIELD_TOP_ONLY 131072

#ifdef __cplusplus
}
namespace melonDS { class NDS; }
/** How many of the top screen's BG3 map entries show a tile (tile number not 0) in its 24 visible rows: Platinum's
 * field text box, sign and yes / no windows (field_message.c: BG_LAYER_MAIN_3). PK_NDS_FIELD_TEXT needs some: a field
 * task alone (a warp, a door) isn't a text box. */
int pk_nds_bg3_tiles(melonDS::NDS* nds, int bg = 3);
extern "C" {
#endif

/** The table entry for this cart (its header's game code and revision), or NULL. */
const PkNdsFieldGame* pk_nds_field_game(const char code[4], int revision);

/** The table entry for this cart from its bytes (header, and for a hack sharing a retail header its code: Origin
 * HeartGold's IPKJ), or NULL. */
const PkNdsFieldGame* pk_nds_field_game_rom(const uint8_t* rom, uint32_t romLen);

/** The cart is Origin HeartGold's build these tables were taken from (an IPKJ header with its code where it was mapped). */
int pk_nds_origin_heartgold(const uint8_t* rom, uint32_t romLen);

/** The battle controller's step (BattleContext.command: CONTROLLER_COMMAND_*) while a battle runs, else -1. */
int pk_nds_battle_command(const PkNdsFieldGame* game, uint32_t (*read32)(uint32_t addr));

/** CONTROLLER_COMMAND_SELECTION_SCREEN_INPUT: the battle waits for the player's command (FIGHT / BAG / POKéMON / RUN, a
 * move, a target - and the bag or party opened from there). */
#define PK_NDS_BATTLE_SELECTING 5

/** EXPAND carries [game]'s battles into the margins (its background, 3D, HP boxes and text box: tuned on HG/SS). Not
 * Black's: its battle is all 3D (pk_nds_battle_3d) and the carried HP boxes streaked across the margins. */
int pk_nds_battle_margins(const PkNdsFieldGame* game);

/** [game]'s battle is all 3D - sky, ground, platforms and the Pokemon (Black) - so EXPAND draws its margins as it does
 * the overworld's: the 3D past the screen, the 2D over it carried where it crosses the screen's edge. */
int pk_nds_battle_3d(const PkNdsFieldGame* game);

/** [game]'s battle command while the player chooses (Gen 4's PK_NDS_BATTLE_SELECTING; Gen 5's adapter has its own). */
int pk_nds_battle_selecting(const PkNdsFieldGame* game);

/** STEADY FF music: whether the song the game plays can be copied now (pk_nds_music copies what's playing, frozen):
 * -1 while it can't - a volume fade runs, or (Gen 5) the song's own sequence player is missing (not started yet), paused
 * or not yet back to its volume (a song resumed after a battle: paused at 0 for a few frames, then a short fade in) - else
 * the player's index << 16 | the volume it's at (0 faded out; Gen 4: 0x7F00, no player read), which a copy already
 * playing compares: another one means the game paused, faded or resumed the song since (the copy is taken again, or
 * faded out with it). */
int pk_nds_bgm_settle(const PkNdsFieldGame* game, uint32_t (*read32)(uint32_t addr));

/** The cart's own grass / surf rates by map header id: [map, grass, surf] triples (n triples; the app's generated tables,
 * Gen4World.wildRates). WILD ENCOUNTERS back on puts a map's rates back from here when they're still 0 - a hold this
 * process no longer remembers, from a resume state taken while it was off. Emu thread. */
void pk_nds_field_set_rates(const int32_t* triples, int n);

/** This frame's bits for [game], reading main RAM through [read32] (0 = unreadable). With [wildOff], WILD ENCOUNTERS
 * OFF: while the field is free, the current map's grass and surf encounter rates are held at 0 through [write32] (the
 * game never rolls a random encounter; fishing and scripted battles are untouched) - PK_NDS_FIELD_WILD_HELD while
 * they are; off again, the rates go back (a new map loads its own anyway). */
int pk_nds_field_state(const PkNdsFieldGame* game, uint32_t (*read32)(uint32_t addr),
                       void (*write32)(uint32_t addr, uint32_t value), int wildOff);

/** DS LAYOUT on one screen: the X menu comes back after a menu screen left by touch too (pk_nds_field.cpp). Call on the
 * screen's closing fade. */
void pk_nds_field_keep_start_menu(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t), void (*write32)(uint32_t, uint32_t));

#ifdef __cplusplus
}
#endif
