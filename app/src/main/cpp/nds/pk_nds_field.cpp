// See pk_nds_field.h. Addresses from pret/pokeplatinum's .xMAP (it builds the retail ROM byte for byte), struct
// offsets from its headers, both checked on headless captures (nds_dump's fieldinfo: the field, a text box, the X
// menu, the party screen, a battle). RAM only, through the callbacks, so the Linux host builds it beside DSperate
// (linux/dsperate/); the one read of melonDS itself, pk_nds_bg3_tiles, is in pk_nds_field_gpu.cpp.

#include "pk_nds_field.h"
#include "pk_nds_ports_gen.h"

#include <string.h>

#include <unordered_map>
#include <utility>
#include <vector>

static const PkNdsFieldGame PLATINUM_US_REV1 = {
    "Platinum (USA, rev 1)",
    0x021C07DC,   // sFieldSystem
    0x020EA358,   // gBattleApplicationTemplate
    0x00, 0x10, 0x3C, 0x68,
    0x1C,
    // MapHeaderData: 4 counts + 4 pointers, tmpEventsBuf[512], initScripts[64], then WildEncounters: grass rate first,
    // surf's after 12 grass slots and the swarm / time / radar / form / dual-slot tables (checked on Route 201: 30, 0)
    0x14, 0x920, 0x920 + 0xCC,
    0x1C,
    0x021D0F69,   // FieldMap_Main (overlay 5)
    // sSoundSystem (0x02101DF8) + 0xBCD0C: found by Sound_Impl_GetCurrentBGM's answer (Twinleaf 0x409, Route 201 0x41A),
    // the only u16 there holding both
    0x021BEB04,
    0x021CAF64,   // sSeqPlayer (libnnssnd's player.c)
    0,
    1,
    0x30,         // BattleSystem.battleCtx (Gen4Reader.kt; checked on retail Platinum)
    0x08,         // BattleContext.command follows two four-byte battler-state arrays
};

// French Platinum, CPUF rev 0 (SHA-1 62f9ec7a…): the US cart's code with its globals 0x1E0 higher and the battle template
// 0x88 (each word found through the US code that uses it, scripts/port_nds.py). The structs are the US cart's.
static const PkNdsFieldGame PLATINUM_FR = {
    "Platinum (France)",
    0x021C09BC,   // sFieldSystem
    0x020EA3E0,   // gBattleApplicationTemplate
    0x00, 0x10, 0x3C, 0x68,
    0x1C,
    0x14, 0x920, 0x920 + 0xCC,
    0x1C,
    0x021D1149,   // FieldMap_Main (overlay 5)
    0x021BECE4,   // sSoundSystem (0x02101FD8) + 0xBCD0C
    0x021CB144,   // sSeqPlayer
    0,
    8,
    0x30,
    0x08,
};

// HeartGold / SoulSilver (USA): pret/pokeheartgold's .xMAP (the same in both games' builds, compared) and field_system.h, checked on
// the player's saves headless. FieldSystem: processManager 0, taskman 0x10, mapEvents 0x14 (MapEvents' wildEncounters at
// 0x920: u8 walking rate, then u8 surfing), location 0x20, playerAvatar 0x40 (PlayerAvatar.state at 0x18), runningFieldMap
// 0x6C. The field map's main (pk_nds_call's hook) is ov01_02206378's: the same word in both games.
static const PkNdsFieldGame HEARTGOLD_US = {
    "HeartGold / SoulSilver (USA)",
    0x021D4158,   // sFieldSysPtr
    0x020FA484,   // gOverlayTemplate_Battle
    0x00, 0x10, 0x40, 0x6C,
    0x18,
    0x14, 0x920, 0x921,
    0x20,
    0x021E5BE5,   // ov01_02206378's main (the field map's OverlayManagerTemplate)
    0x021D0554,   // sSoundWork (0x02111958) + 0xBEBFC: currentSeqNo, GF_GetCurrentPlayingBGM's answer
    0x021DF984,   // sSeqPlayer
    1,
    2,
    0x30,         // BattleSystem.ctx
    0x08,         // BattleContext.command (5 at the command menus, 22 running a move's script: headless dumps)
};

// German HeartGold, IPKD rev 0 (SHA-1 13b6853a…): ARM9's SaveData / FieldSystem and sound literal pools,
// gOverlayTemplate_Battle's reference in sub_02087C38, and overlay 1's field-map template. Structs and the
// callSet 2 function addresses are shared with the US carts (checked against the German ARM9).
static const PkNdsFieldGame HEARTGOLD_DE = {
    "HeartGold / SoulSilver (Germany)",
    0x021D4138,   // sFieldSysPtr
    0x020FA438,   // gOverlayTemplate_Battle
    0x00, 0x10, 0x40, 0x6C,
    0x18,
    0x14, 0x920, 0x921,
    0x20,
    0x021E5BC5,   // ov01_02206378's main in German overlay 1
    0x021D0534,   // sSoundWork (0x02111938) + 0xBEBFC
    0x021DF964,   // sSeqPlayer
    1,
    2,
    0x30,
    0x08,
};

// French HeartGold / SoulSilver, IPKF / IPGF rev 0 (SHA-1 dba64620… / 6ed98d44…): the US carts' code, the same word in both
// games, their globals and field overlays 0x20 higher, the battle template 0x1C lower (scripts/port_nds.py). The callSet 2
// functions sit low in the ARM9, where nothing moved.
static const PkNdsFieldGame HEARTGOLD_FR = {
    "HeartGold / SoulSilver (France)",
    0x021D4178,   // sFieldSysPtr
    0x020FA468,   // gOverlayTemplate_Battle
    0x00, 0x10, 0x40, 0x6C,
    0x18,
    0x14, 0x920, 0x921,
    0x20,
    0x021E5C05,   // ov01_02206378's main in French overlay 1
    0x021D0574,   // sSoundWork (0x02111978) + 0xBEBFC
    0x021DF9A4,   // sSeqPlayer
    1,
    2,
    0x30,
    0x08,
};

// Origin HeartGold (v4.0.3, English patch 1.0.0; header IPKJ rev 0): Yannanfei TB's hack, rebuilt by Alex on hg-engine over
// the Japanese HeartGold, patched to English. Every global and overlay sits elsewhere than the US cart's, so each word was
// found again through the US code that uses it (pret/pokeheartgold's .xMAP, BLs and pool words masked: SaveData_Get,
// Field_NewGame_AppInit, the battle launchers, GetSoundDataPointer, NNS_SndPlayer's) and checked headless on a new game.
// The structs are HeartGold's. A real Japanese HeartGold shares the header, so [ORIGIN_HEARTGOLD_SIG] is checked first.
static const PkNdsFieldGame ORIGIN_HEARTGOLD = {
    "Origin HeartGold",
    0x021D31F4,   // sFieldSysPtr
    0x020F70B8,   // gOverlayTemplate_Battle
    0x00, 0x10, 0x40, 0x6C,
    0x18,
    0x14, 0x920, 0x921,
    0x20,
    0x021E4C51,   // FieldMap_Main in its overlay 1
    0x021CF39C,   // sSoundWork (0x021107A0) + 0xBEBFC
    0x021DEA04,   // sSeqPlayer
    1,
    7,
    0, 0,         // no BattleSystem / BattleContext: its battle engine is its own (battleManagerPtr below)
    0,
    0, 0, 0, 0, 0,
    0, 0,
    0, 0, 0, 0,
    0,
    0,
    0, 0, 0, 0, 0,
    0,
    // Its battle manager (0x274 bytes, made by its overlay 14's battle init, which gOverlayTemplate_Battle runs): its
    // touch screen's controller at +0x238, whose +0x10 is the input handler while the battle waits for the player (headless:
    // 0x0220368D at the commands and the moves, 0x02203B61 at the party after a faint, 0 while the turn plays).
    0x02255BAC, 0x238, 0x10,
};

// Black (USA / Europe, IRBO rev 0): no decomp, so from headless captures (nds_dump dumps of the field, a text box, the X
// menu, the party screen and a Battle Subway battle, diffed; the heap's blocks carry their source file's name). The
// GameSystem is procsys.c's proc work, its pointer static; its proc system's running proc names its overlay (21 the field
// map, 91 the party list, 93 the battle); the battle's main module -> client 0 -> its adapter, whose command is 3 while
// the player chooses (FIGHT / the moves) and 8 while the turn plays.
static const PkNdsFieldGame BLACK_US = {
    "Black (USA / Europe)",
    0x02146248,   // the GameSystem
    0,
    0, 0, 0, 0,
    0,
    0, 0, 0,
    0,
    0,            // no field map hook: PC / FLY take the GameSystem's event check's place (pk_nds_call.cpp's Gen5Fns)
    0x020AA1BC,   // the BGM stack's first song (u16; 0x1C an entry, the depth at bgmDepth)
    0x0214E024,   // sSeqPlayer (NitroSDK's, 16 x 0x44: player 6 plays the field's song, 12 a battle's)
    0,
    3,            // pk_nds_call's Gen 5 jobs (BLACK there)
    0, 0,
    5,
    0x08, 0x08, 0x04, 21, 93,
    0x14, 0x18,
    0x14, 0x10, 0x50, 0x0F,
    3,
    1,            // the field's message window: engine A's BG1 (192 tiles while one is up, none on the free field)
    0x1C,         // GameData
    0x114,        // its player record's zone (then fx32 x / y / z)
    0x154,        // the move form (1 on the Bicycle, Y > Bicycle and back)
    0x158,        // EVENTDATA_SYSTEM
    0x38,         // its encounter table: the season's block of /a/1/2/6, rates first (Route 1: 08 08 01 03 01 32 01)
    0x020AA1B0,   // the BGM stack's depth: 0 on the field, 1 in a battle
};

// White (USA / Europe, IRAO rev 0): Black's code, its statics and overlays 0x20 higher (each word found through the code
// that uses it in both carts, then checked in White's headless captures of the same save); the structs are Black's.
static const PkNdsFieldGame WHITE_US = {
    "White (USA / Europe)",
    0x02146268,   // the GameSystem
    0,
    0, 0, 0, 0,
    0,
    0, 0, 0,
    0,
    0,
    0x020AA1DC,   // the BGM stack's first song
    0x0214E044,   // sSeqPlayer
    0,
    4,            // pk_nds_call's Gen 5 jobs (WHITE there)
    0, 0,
    5,
    0x08, 0x08, 0x04, 21, 93,
    0x14, 0x18,
    0x14, 0x10, 0x50, 0x0F,
    3,
    1,
    0x1C,
    0x114,
    0x154,
    0x158,
    0x38,
    0x020AA1D0,   // the BGM stack's depth
};

// French Black / White, IRBF / IRAF rev 0 (SHA-1 a559da54… / 28f07591…): the US / Europe carts' code, their statics and
// field overlays 0x80 lower (scripts/port_nds.py), the structs and overlay numbers the same.
static const PkNdsFieldGame BLACK_FR = {
    "Black (France)",
    0x021461C8,   // the GameSystem
    0,
    0, 0, 0, 0,
    0,
    0, 0, 0,
    0,
    0,
    0x020AA13C,   // the BGM stack's first song
    0x0214DFA4,   // sSeqPlayer
    0,
    9,            // pk_nds_call's Gen 5 jobs
    0, 0,
    5,
    0x08, 0x08, 0x04, 21, 93,
    0x14, 0x18,
    0x14, 0x10, 0x50, 0x0F,
    3,
    1,
    0x1C,
    0x114,
    0x154,
    0x158,
    0x38,
    0x020AA130,   // the BGM stack's depth
};

static const PkNdsFieldGame WHITE_FR = {
    "White (France)",
    0x021461E8,   // the GameSystem
    0,
    0, 0, 0, 0,
    0,
    0, 0, 0,
    0,
    0,
    0x020AA15C,   // the BGM stack's first song
    0x0214DFC4,   // sSeqPlayer
    0,
    10,            // pk_nds_call's Gen 5 jobs
    0, 0,
    5,
    0x08, 0x08, 0x04, 21, 93,
    0x14, 0x18,
    0x14, 0x10, 0x50, 0x0F,
    3,
    1,
    0x1C,
    0x114,
    0x154,
    0x158,
    0x38,
    0x020AA150,   // the BGM stack's depth
};

// Black 2 (USA / Europe, IREO rev 0): a new build of Black's engine, so every static moved, but the GameSystem, its procs,
// the GameData and the battle module keep Black's shapes (headless captures of the player's save, each word found again
// through the code that uses it in Black and Black 2). The field map is overlay 36, the battle 167.
static const PkNdsFieldGame BLACK2_US = {
    "Black 2 (USA / Europe)",
    0x021410F4,   // the GameSystem
    0,
    0, 0, 0, 0,
    0,
    0, 0, 0,
    0,
    0,
    0x0209DA80,   // the BGM stack's first song
    0x02148EE4,   // sSeqPlayer
    0,
    5,            // pk_nds_call's Gen 5 jobs (BLACK2 there)
    0, 0,
    5,
    0x08, 0x08, 0x04, 36, 167,
    0x14, 0x18,
    0x14, 0x10, 0x50, 0x0F,
    3,
    1,
    0x1C,
    0x114,
    0x154,
    0x158,
    0x38,
    0x0209DA74,   // the BGM stack's depth
};

// White 2 (USA / Europe, IRDO rev 0): Black 2's code with its statics and overlays 0x40 higher (each word found through
// the code that uses it in both carts, then checked in White 2's headless captures of the player's save).
static const PkNdsFieldGame WHITE2_US = {
    "White 2 (USA / Europe)",
    0x02141134,   // the GameSystem
    0,
    0, 0, 0, 0,
    0,
    0, 0, 0,
    0,
    0,
    0x0209DAC0,   // the BGM stack's first song
    0x02148F24,   // sSeqPlayer
    0,
    6,            // pk_nds_call's Gen 5 jobs (WHITE2 there)
    0, 0,
    5,
    0x08, 0x08, 0x04, 36, 167,
    0x14, 0x18,
    0x14, 0x10, 0x50, 0x0F,
    3,
    1,
    0x1C,
    0x114,
    0x154,
    0x158,
    0x38,
    0x0209DAB4,   // the BGM stack's depth
};

// WILD ENCOUNTERS OFF's hold: the MapHeaderData whose rates it zeroed, for which map, and their values before.
static uint32_t heldData, heldMap, heldGrass, heldSurf;
static int holding;

// The cart's rates by map header id (pk_nds_field_set_rates), and the map ON last checked against them.
static std::unordered_map<uint32_t, std::pair<uint32_t, uint32_t>> cartRates;
static uint32_t checkedData, checkedMap;
static int checked;

void pk_nds_field_set_rates(const int32_t* triples, int n)
{
    cartRates.clear();
    for (int i = 0; i < n; i++)
        cartRates[(uint32_t) triples[3 * i]] = { (uint32_t) triples[3 * i + 1], (uint32_t) triples[3 * i + 2] };
    checked = 0;
}

// PLAYER_AVATAR_* (constants/player_avatar.h)
enum { AVATAR_WALKING = 0, AVATAR_CYCLING = 1, AVATAR_SURFING = 2 };

// Origin HeartGold's cart: SaveData_Get's literal pool (0x0202773C in its ARM9, which the cart keeps uncompressed at 0x4000)
// holds the save's static block (its SaveData pointer 4 bytes in: `ldr r0, [r0, #4]`, where the US cart's has the pointer
// alone). Another build of the hack (or a real Japanese HeartGold) moves it, and gets no table.
static const uint32_t ORIGIN_HEARTGOLD_SIG_OFFSET = 0x4000 + 0x2773C, ORIGIN_HEARTGOLD_SIG = 0x021D11B0;

int pk_nds_origin_heartgold(const uint8_t* rom, uint32_t romLen)
{
    if (!rom || romLen < ORIGIN_HEARTGOLD_SIG_OFFSET + 4 || memcmp(rom + 0xC, "IPKJ", 4) != 0 || rom[0x1E] != 0) return 0;
    const uint8_t* w = rom + ORIGIN_HEARTGOLD_SIG_OFFSET;
    return (uint32_t) (w[0] | w[1] << 8 | w[2] << 16 | (uint32_t) w[3] << 24) == ORIGIN_HEARTGOLD_SIG;
}

const PkNdsFieldGame* pk_nds_field_game_rom(const uint8_t* rom, uint32_t romLen)
{
    if (pk_nds_origin_heartgold(rom, romLen)) return &ORIGIN_HEARTGOLD;
    if (!rom || romLen < 0x20) return 0;
    return pk_nds_field_game((const char*) rom + 0xC, rom[0x1E]);
}

static const PkNdsFieldGame* portGame(const char code[4], int revision);

const PkNdsFieldGame* pk_nds_field_game(const char code[4], int revision)
{
    if (memcmp(code, "CPUE", 4) == 0 && revision == 1) return &PLATINUM_US_REV1;
    if (memcmp(code, "CPUF", 4) == 0 && revision == 0) return &PLATINUM_FR;
    if ((memcmp(code, "IPKE", 4) == 0 || memcmp(code, "IPGE", 4) == 0) && revision == 0) return &HEARTGOLD_US;
    if ((memcmp(code, "IPKD", 4) == 0 || memcmp(code, "IPGD", 4) == 0) && revision == 0) return &HEARTGOLD_DE;
    if ((memcmp(code, "IPKF", 4) == 0 || memcmp(code, "IPGF", 4) == 0) && revision == 0) return &HEARTGOLD_FR;
    if (memcmp(code, "IRBO", 4) == 0 && revision == 0) return &BLACK_US;
    if (memcmp(code, "IRAO", 4) == 0 && revision == 0) return &WHITE_US;
    if (memcmp(code, "IRBF", 4) == 0 && revision == 0) return &BLACK_FR;
    if (memcmp(code, "IRAF", 4) == 0 && revision == 0) return &WHITE_FR;
    if (memcmp(code, "IREO", 4) == 0 && revision == 0) return &BLACK2_US;
    if (memcmp(code, "IRDO", 4) == 0 && revision == 0) return &WHITE2_US;
    return portGame(code, revision);
}

static int inMainRam(uint32_t p) { return p >= 0x02000000u && p < 0x02400000u; }

// When the game wants its touch screen (PK_NDS_FIELD_TOUCH), for the games whose touch-screen moments are mapped.
// HeartGold / SoulSilver (pret/pokeheartgold, checked headless on the player's save): the X menu is the field task
// Task_StartMenu (start_menu.c), its buttons on the touch screen (its SAVE too); every menu screen it opens (party,
// bag, Pokégear, Pokédex, trainer card, options) has its controls there. A battle's touch panels hang off
// BattleSystem.battleInput: its text objects are set only while a panel is drawn; battler 0's selection step
// (BattleContext's first byte, SSI_STATE_*) is 1 / 2 at the commands, 3 / 4 the moves, 7 / 8 the bag, 9 / 10 the party.
// A menu screen whose page is the touch screen's alone: its template's init function (the child's first word) and, where
// only some pages are, the app's states (OverlayManager +0x14) on those pages as a bit mask (0 = every page).
struct PkNdsTouchApp {
    uint32_t init;
    uint32_t states;
};

struct PkNdsTouchRules {
    const PkNdsFieldGame* game;
    uint32_t startMenuTask;   // Task_StartMenu (Thumb)
    int offBattleInput;       // BattleSystem.battleInput
    int offPanelShown;        // BattleInput.textObj[]'s first pointer: NULL while no panel is drawn
    PkNdsTouchApp touchOnly[4];
    int offMenuInputState;    // FieldSystem.menuInputState: 0 buttons, 1 touch (the last input on a menu screen)
    PkNdsTouchApp topOnly[2]; // menu screens all on the top screen (their touch screen only a picture): no touch screen
};

// HeartGold / SoulSilver's touch-screen-only pages (headless, the player's saves): the party list (its summary is an app of
// its own, two screens), the options, the Pokédex's first page (states 0 / 2 / 5 opening it, 6 on it; 11 is its list, an
// INFO page on top). Everything here is the same address in both games but the Pokédex's init: SoulSilver's overlay has it
// 4 bytes earlier (its version's dex differs), so both are listed.
static const PkNdsTouchRules TOUCH_RULES[] = {
    // Origin HeartGold: its Task_StartMenu found through StartMenu_Init's pool (checked headless: the X menu's field task),
    // PartyMenuApp_Init by its code (HeartGold's, byte for byte but the calls); its options are a page of its own, all on
    // the touch screen (init 0x023DE0F9, read off the child process while it was up headless). Its Pokédex is its own too
    // (not reached headless: side by side). Its battles are pk_nds_field.cpp's managed battle (battleManagerPtr).
    { &ORIGIN_HEARTGOLD, 0x0203B3E9, 0x19C, 0x4D0, {
        { 0x02076E9D, 0 },                                      // the party
        { 0x023DE0F9, 0 },                                      // the options
    }, 0x10C },
    { &HEARTGOLD_US, 0x0203BEF1, 0x19C, 0x4D0, {
        { 0x02078E31, 0 },                                      // the party
        { 0x021E5901, 0 },                                      // the options
        { 0x021E5AA1, (1u << 0) | (1u << 2) | (1u << 5) | (1u << 6) },   // the Pokédex (HeartGold)
        { 0x021E5A9D, (1u << 0) | (1u << 2) | (1u << 5) | (1u << 6) },   // the Pokédex (SoulSilver)
    }, 0x10C },
    // German HeartGold / SoulSilver: the US apps, their overlays 0x20 lower (scripts/port_nds_fns.py, checked headless).
    { &HEARTGOLD_DE, 0x0203BEF1, 0x19C, 0x4D0, {
        { 0x02078E31, 0 },                                      // the party
        { 0x021E58E1, 0 },                                      // the options
        { 0x021E5A81, (1u << 0) | (1u << 2) | (1u << 5) | (1u << 6) },   // the Pokédex (HeartGold)
        { 0x021E5A7D, (1u << 0) | (1u << 2) | (1u << 5) | (1u << 6) },   // the Pokédex (SoulSilver)
    }, 0x10C },
    // The French carts: the US apps at their French addresses (the ARM9's the same, the overlays 0x20 higher).
    { &HEARTGOLD_FR, 0x0203BEF1, 0x19C, 0x4D0, {
        { 0x02078E31, 0 },                                      // the party
        { 0x021E5921, 0 },                                      // the options
        { 0x021E5AC1, (1u << 0) | (1u << 2) | (1u << 5) | (1u << 6) },   // the Pokédex (HeartGold)
        { 0x021E5ABD, (1u << 0) | (1u << 2) | (1u << 5) | (1u << 6) },   // the Pokédex (SoulSilver)
    }, 0x10C },
    // Platinum (pret/pokeplatinum, headless on a new game): its X menu is on the top screen (no field task to follow), its
    // battle's touch panels are HeartGold's (BattleSystem.battleSubscreen at 0x19C, its first font OAM at 0x4D0; the
    // command selection's states the same). The party and the options are top-screen pages, their touch screen a picture.
    { &PLATINUM_US_REV1, 0, 0x19C, 0x4D0, {}, 0, {
        { 0x0207E0B9, 0 },   // the party
        { 0x021D0D81, 0 },   // the options
    } },
    { &PLATINUM_FR, 0, 0x19C, 0x4D0, {}, 0, {
        { 0x0207E159, 0 },   // the party
        { 0x021D0F61, 0 },   // the options
    } },
};

static int pageIn(const PkNdsTouchApp* apps, int n, uint32_t (*read32)(uint32_t), uint32_t child)
{
    uint32_t init = read32(child);
    for (int i = 0; i < n; i++) {
        const PkNdsTouchApp& a = apps[i];
        if (!a.init || a.init != init) continue;
        if (!a.states) return 1;
        uint32_t state = read32(child + 0x14);
        return state < 32 && (a.states >> state & 1);
    }
    return 0;
}

// [child]'s app is on a page all on the touch screen.
static int touchOnlyPage(const PkNdsTouchRules* t, uint32_t (*read32)(uint32_t), uint32_t child)
{
    return pageIn(t->touchOnly, 4, read32, child);
}

// [child]'s app is all on the top screen.
static int topOnlyPage(const PkNdsTouchRules* t, uint32_t (*read32)(uint32_t), uint32_t child)
{
    return pageIn(t->topOnly, 2, read32, child);
}

// Black / White and their sequels (headless on the player's saves, the heap's blocks named by their source files): the X menu is a field
// event (GMEVENT: parent at 0, its function at 4) on the touch screen, and so is what it starts on the field (the save's
// prompt runs two events under it); the party, the bag, the trainer card and the options are apps all on the touch screen
// (their overlays; the top screen a pattern). A battle's touch screen is btlv_scd's (client -> btlv_core +0x54 -> scd
// +0x180), its panel btlv_input's (scd +0xB0): the panel's kind at +0x58 (1 the commands, 2 the moves, 4 a prompt such
// as "Use next Pokémon?"), scd +0x60 set while a panel is up and input +0x08 its slide's task (NULL once it takes touches:
// a tap before that is lost - headless, FIGHT's moves 10 frames after +0x58 says 2), scd +0x98 the screen it opened (2 the
// bag, 3 the party; stale after, so only while the adapter's command is the choice's); command 5 is a fainted Pokémon's
// party.
struct PkNdsGen5Touch {
    const PkNdsFieldGame* game;
    uint32_t startMenuEvent;  // the X menu's event function (Thumb)
    int touchOnly[4];         // apps' overlays all on the touch screen (0: none)
    int offScd, offInput, offSubscreen;   // btlv_core -> btlv_scd, btlv_scd -> btlv_input, btlv_scd's opened screen
};

// The bag (Black / White 70, their sequels 142) isn't one: the item picked shows on the top screen, its picture, count
// and description (headless, Black's and Black 2's), so it's a menu screen on both, side by side.
static const PkNdsGen5Touch GEN5_TOUCH[] = {
    { &BLACK_US, 0x0215FD0D, { 91, 111, 68, 0 }, 0x180, 0xB0, 0x98 },
    // White: Black's code, its overlays 0x20 higher (the X menu's function checked in White's capture of the same save).
    { &WHITE_US, 0x0215FD2D, { 91, 111, 68, 0 }, 0x180, 0xB0, 0x98 },
    // The French carts: the X menu's function 0x90 lower (overlay 10 lost 0x10 before it), the rest the same.
    { &BLACK_FR, 0x0215FC7D, { 91, 111, 68, 0 }, 0x180, 0xB0, 0x98 },
    { &WHITE_FR, 0x0215FC9D, { 91, 111, 68, 0 }, 0x180, 0xB0, 0x98 },
    // Black 2 (headless on the player's save, its wild battle on Route 20 / Humilau's water): the apps renumbered
    // (party 165, trainer card 186, options 140; the Pokédex, 302, and the bag, 142, both screens), btlv_scd 0x1C4 into the core,
    // btlv_input 0xBC into it, the opened screen at +0xA4; the rest Black's. White 2: Black 2's code 0x40 higher (its X
    // menu's function and apps checked on its save; its battle not reached headless: Black 2's structs).
    { &BLACK2_US, 0x0215A875, { 165, 186, 140, 0 }, 0x1C4, 0xBC, 0xA4 },
    { &WHITE2_US, 0x0215A8B5, { 165, 186, 140, 0 }, 0x1C4, 0xBC, 0xA4 },
};

// The language ports (gamedata/nds/ports.toml, pk_nds_ports_gen.h): each its base cart's tables with its own words. Built
// once; callSet 100 + n / 200 + n is the n-th port of each kind (pk_nds_call.cpp builds its functions from the same rows).
struct PortTables {
    std::vector<PkNdsFieldGame> games4, games4p, games5;
    std::vector<PkNdsTouchRules> touch4;
    std::vector<PkNdsGen5Touch> touch5;
};

static const PortTables& ports()
{
    static const PortTables t = [] {
        PortTables p;
        p.games4.reserve(PK_NDS_PORTS4_COUNT);
        p.games4p.reserve(PK_NDS_PORTS4P_COUNT);
        p.games5.reserve(PK_NDS_PORTS5_COUNT);
        for (int i = 0; i < PK_NDS_PORTS4_COUNT; i++) {
            const PkNdsPort4& r = PK_NDS_PORTS4[i];
            PkNdsFieldGame g = HEARTGOLD_US;
            g.name = r.name;
            g.fieldSystemPtr = r.fieldSystemPtr;
            g.battleTemplate = r.battleTemplate;
            g.fieldMapMain = r.fieldMapMain;
            g.currentBgm = r.soundWork + 0xBEBFC;
            g.seqPlayers = r.seqPlayer;
            g.callSet = 100 + i;
            p.games4.push_back(g);
        }
        // Platinum builds: the US rev 1 table, callSet 300 + n (pk_nds_call.cpp's portPlatinumFns).
        for (int i = 0; i < PK_NDS_PORTS4P_COUNT; i++) {
            const PkNdsPort4P& r = PK_NDS_PORTS4P[i];
            PkNdsFieldGame g = PLATINUM_US_REV1;
            g.name = r.name;
            g.fieldSystemPtr = r.fieldSystemPtr;
            g.battleTemplate = r.battleTemplate;
            g.fieldMapMain = r.fieldMapMain;
            g.currentBgm = r.soundSystem + 0xBCD0C;
            g.seqPlayers = r.seqPlayer;
            g.callSet = 300 + i;
            p.games4p.push_back(g);
        }
        for (int i = 0; i < PK_NDS_PORTS5_COUNT; i++) {
            const PkNdsPort5& r = PK_NDS_PORTS5[i];
            PkNdsFieldGame g = r.sequel ? BLACK2_US : BLACK_US;
            g.name = r.name;
            g.fieldSystemPtr = r.gameSystem;
            g.currentBgm = r.bgmStack;
            g.seqPlayers = r.seqPlayer;
            g.callSet = 200 + i;
            g.bgmDepth = r.bgmStack - 0xC;
            p.games5.push_back(g);
        }
        // After both reserves, so these pointers stay put.
        for (int i = 0; i < PK_NDS_PORTS4_COUNT; i++) {
            const PkNdsPort4& r = PK_NDS_PORTS4[i];
            if (!r.startMenu || !r.partyInit || !r.optionsInit || !r.dexInit) continue;
            PkNdsTouchRules t = {};
            t.game = &p.games4[i];
            t.startMenuTask = r.startMenu;
            t.offBattleInput = 0x19C;
            t.offPanelShown = 0x4D0;
            t.touchOnly[0] = { r.partyInit, 0 };
            t.touchOnly[1] = { r.optionsInit, 0 };
            t.touchOnly[2] = { r.dexInit, (1u << 0) | (1u << 2) | (1u << 5) | (1u << 6) };
            t.offMenuInputState = 0x10C;
            p.touch4.push_back(t);
        }
        // Platinum's: no X menu task to follow, HeartGold's battle panels, the party and options as top-screen pages.
        for (int i = 0; i < PK_NDS_PORTS4P_COUNT; i++) {
            const PkNdsPort4P& r = PK_NDS_PORTS4P[i];
            if (!r.partyInit || !r.optionsInit) continue;
            PkNdsTouchRules t = {};
            t.game = &p.games4p[i];
            t.offBattleInput = 0x19C;
            t.offPanelShown = 0x4D0;
            t.topOnly[0] = { r.partyInit, 0 };
            t.topOnly[1] = { r.optionsInit, 0 };
            p.touch4.push_back(t);
        }
        for (int i = 0; i < PK_NDS_PORTS5_COUNT; i++) {
            const PkNdsPort5& r = PK_NDS_PORTS5[i];
            if (!r.startMenuEvent) continue;
            PkNdsGen5Touch t = r.sequel ? PkNdsGen5Touch{ nullptr, 0, { 165, 186, 140, 0 }, 0x1C4, 0xBC, 0xA4 }
                                        : PkNdsGen5Touch{ nullptr, 0, { 91, 111, 68, 0 }, 0x180, 0xB0, 0x98 };
            t.game = &p.games5[i];
            t.startMenuEvent = r.startMenuEvent;
            p.touch5.push_back(t);
        }
        return p;
    }();
    return t;
}

static const PkNdsFieldGame* portGame(const char code[4], int revision)
{
    const PortTables& p = ports();
    for (int i = 0; i < PK_NDS_PORTS4_COUNT; i++)
        if (memcmp(code, PK_NDS_PORTS4[i].code, 4) == 0 && revision == PK_NDS_PORTS4[i].revision) return &p.games4[i];
    for (int i = 0; i < PK_NDS_PORTS4P_COUNT; i++)
        if (memcmp(code, PK_NDS_PORTS4P[i].code, 4) == 0 && revision == PK_NDS_PORTS4P[i].revision) return &p.games4p[i];
    for (int i = 0; i < PK_NDS_PORTS5_COUNT; i++)
        if (memcmp(code, PK_NDS_PORTS5[i].code, 4) == 0 && revision == PK_NDS_PORTS5[i].revision) return &p.games5[i];
    return 0;
}

static const int GEN5_OFF_VIEW = 0x54;
static const int GEN5_OFF_SLIDE = 0x08, GEN5_OFF_PANEL = 0x58, GEN5_OFF_WAITING = 0x60;
static const int GEN5_ACMD_PARTY = 5;
// "Will you switch your Pokémon?" after a foe faints (SHIFT style): the prompt (panel 4), then on SWITCH POKéMON the party,
// both under this one command with the panel at 0 for the party and nothing in btlv_scd's opened screen; KEEP BATTLING
// leaves the command ~12 frames on (headless, Black 2 on the player's state at that prompt, 2026-11-09).
static const int GEN5_ACMD_SWITCH_ASK = 4;
static int gen5SwitchAsked;   // the prompt was up during this GEN5_ACMD_SWITCH_ASK

static const PkNdsGen5Touch* gen5Touch(const PkNdsFieldGame* g)
{
    for (const PkNdsGen5Touch& t : GEN5_TOUCH)
        if (t.game == g) return &t;
    for (const PkNdsGen5Touch& t : ports().touch5)
        if (t.game == g) return &t;
    return nullptr;
}

static int byteAt(uint32_t (*read32)(uint32_t), uint32_t a) { return (int) ((read32(a & ~3u) >> ((a & 3) * 8)) & 0xFF); }

static int gen5BattlePanel(const PkNdsFieldGame* g, const PkNdsGen5Touch* t, uint32_t (*read32)(uint32_t), uint32_t proc,
                           int* panel, int* waiting, int* subscreen)
{
    uint32_t main = read32(proc + g->offProcWork);
    uint32_t client = inMainRam(main) ? read32(main + g->offClient) : 0;
    uint32_t view = inMainRam(client) ? read32(client + GEN5_OFF_VIEW) : 0;
    uint32_t scd = inMainRam(view) ? read32(view + t->offScd) : 0;
    uint32_t input = inMainRam(scd) ? read32(scd + t->offInput) : 0;
    if (!inMainRam(input)) return 0;
    *panel = byteAt(read32, input + GEN5_OFF_PANEL);
    *waiting = byteAt(read32, scd + GEN5_OFF_WAITING) && !read32(input + GEN5_OFF_SLIDE);
    *subscreen = byteAt(read32, scd + t->offSubscreen);
    return 1;
}

static const PkNdsTouchRules* touchRules(const PkNdsFieldGame* g)
{
    for (const PkNdsTouchRules& r : TOUCH_RULES)
        if (r.game == g) return &r;
    for (const PkNdsTouchRules& r : ports().touch4)
        if (r.game == g) return &r;
    return nullptr;
}

// A hack's own battle engine (PkNdsFieldGame.battleManagerPtr): its touch screen's input handler, set while the battle waits
// for a tap; 0 while it plays (or no manager yet).
static uint32_t managedBattleInput(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t))
{
    uint32_t mgr = read32(g->battleManagerPtr);
    uint32_t ui = inMainRam(mgr) ? read32(mgr + g->offBattleUi) : 0;
    return inMainRam(ui) ? read32(ui + g->offUiHandler) : 0;
}

// The touch bits for a battle child process [child] (its data the BattleSystem). While the controller waits for the
// player (SELECTION_SCREEN_INPUT), battler 0's step says which: 1 - 4 the commands and the moves (what BATTLE BUTTONS stand
// in for - whatever BattleMenuID draws them: HG redraws the commands under another one after a move's CANCEL), 7 - 10 the
// bag and the party (screens of their own); anything else with a panel drawn (a target, YES / NO, "Use next Pokémon?") is
// the touch screen's.
static int battleTouch(const PkNdsFieldGame* g, const PkNdsTouchRules* t, uint32_t (*read32)(uint32_t), uint32_t child)
{
    // A battle of its own (Origin HeartGold): BATTLE BUTTONS can't steer its panels, so whatever waits for a tap is the
    // touch screen's.
    if (g->battleManagerPtr) return managedBattleInput(g, read32) ? PK_NDS_FIELD_TOUCH : 0;
    uint32_t bs = read32(child + 0x1C);
    if (!inMainRam(bs)) return 0;
    uint32_t ctx = read32(bs + g->offBattleContext);
    uint32_t input = read32(bs + t->offBattleInput);
    // BattleInput's first word is the BattleSystem while the battle's own touch screen is up. A screen of its own takes
    // that block meanwhile - the party (also the one a fainted Pokémon forces, outside the command selection), the bag,
    // the move to forget for a new one, the naming screen after a catch - and the word is then something else (HeartGold
    // and Platinum headless, GitHub #490).
    if (inMainRam(input) && read32(input) != bs) return PK_NDS_FIELD_TOUCH | PK_NDS_FIELD_TOUCH_FADES;
    bool panel = inMainRam(input) && inMainRam(read32(input + t->offPanelShown));
    if (inMainRam(ctx) && read32(ctx + g->offCommand) == PK_NDS_BATTLE_SELECTING) {
        int step = (int) (read32(ctx) & 0xFF);
        // The bag / the party: their own screens, faded in and out (the engine waits the fades out).
        if (step >= 7 && step <= 10) return PK_NDS_FIELD_TOUCH | PK_NDS_FIELD_TOUCH_FADES;
        // The commands and the moves (1 - 4), the steps between them (0 - 2: back from the moves goes through the
        // command init, its panel still drawn) and a target (5 / 6), a message's wait (15): the battle buttons' own.
        if (step <= 6 || step == 15) return panel ? PK_NDS_FIELD_TOUCH_BATTLE_MENU : 0;
    }
    return panel ? PK_NDS_FIELD_TOUCH : 0;
}

// An encounter rate: a u32 (Platinum) or a u8 (HeartGold / SoulSilver), read and written through the aligned word.
static uint32_t rateRead(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t), uint32_t a)
{
    if (!g->rateBytes) return read32(a);
    return (read32(a & ~3u) >> ((a & 3) * 8)) & 0xFF;
}

static void rateWrite(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t), void (*write32)(uint32_t, uint32_t),
                      uint32_t a, uint32_t v)
{
    if (!g->rateBytes) { write32(a, v); return; }
    int shift = (a & 3) * 8;
    uint32_t w = read32(a & ~3u);
    write32(a & ~3u, (w & ~(0xFFu << shift)) | ((v & 0xFF) << shift));
}

static void releaseWild(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t), void (*write32)(uint32_t, uint32_t),
                        uint32_t data, uint32_t map)
{
    // Only the struct it changed, still holding the same map's data: a map change reloaded both rates already.
    if (holding && data == heldData && map == heldMap) {
        if (rateRead(g, read32, heldData + g->offGrassRate) == 0) rateWrite(g, read32, write32, heldData + g->offGrassRate, heldGrass);
        if (rateRead(g, read32, heldData + g->offSurfRate) == 0) rateWrite(g, read32, write32, heldData + g->offSurfRate, heldSurf);
    }
    holding = 0;
}

int pk_nds_battle_margins(const PkNdsFieldGame* g) { return g && g->gen != 5; }

int pk_nds_battle_3d(const PkNdsFieldGame* g) { return g && g->gen == 5; }

int pk_nds_battle_selecting(const PkNdsFieldGame* g) { return g && g->battleSelecting ? g->battleSelecting : PK_NDS_BATTLE_SELECTING; }

// The naming screen's engine A DISPCNT (BG0-2, OBJ, window 0, no 3D): the same in HeartGold and Platinum, in a battle and in
// the new game's intro (headless).
static const uint32_t NAMING_SCREEN_DISPCNT = 0x00013710;

int pk_nds_field_screen_touch(const PkNdsFieldGame* g, int bits, uint32_t dispCntA)
{
    if (!g || g->gen == 5 || !touchRules(g)) return 0;
    return bits == PK_NDS_FIELD_KNOWN && dispCntA == NAMING_SCREEN_DISPCNT ? PK_NDS_FIELD_TOUCH : 0;
}

// Gen 5: the GameSystem's running proc, or 0.
static uint32_t gen5Proc(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t), uint32_t gs)
{
    uint32_t ps = read32(gs + g->offProcSys);
    if (!inMainRam(ps)) return 0;
    uint32_t proc = read32(ps + g->offCurrentProc);
    return inMainRam(proc) ? proc : 0;
}

static int gen5BattleCommand(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t))
{
    uint32_t gs = read32(g->fieldSystemPtr);
    if (!inMainRam(gs)) return -1;
    uint32_t proc = gen5Proc(g, read32, gs);
    if (!proc || (int) read32(proc + g->offProcOverlay) != g->battleOverlay) return -1;
    uint32_t main = read32(proc + g->offProcWork);
    uint32_t client = inMainRam(main) ? read32(main + g->offClient) : 0;
    uint32_t adapter = inMainRam(client) ? read32(client + g->offAdapter) : 0;
    if (!inMainRam(adapter)) return -1;
    uint32_t a = adapter + g->offAdapterCommand;
    return (int) ((read32(a & ~3u) >> ((a & 3) * 8)) & 0xFF);
}

int pk_nds_gen5_battle_panel(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t), int out[3])
{
    const PkNdsGen5Touch* t = g && g->gen == 5 ? gen5Touch(g) : nullptr;
    uint32_t gs = t ? read32(g->fieldSystemPtr) : 0;
    uint32_t proc = inMainRam(gs) ? gen5Proc(g, read32, gs) : 0;
    if (!proc || (int) read32(proc + g->offProcOverlay) != g->battleOverlay) return 0;
    return gen5BattlePanel(g, t, read32, proc, &out[0], &out[1], &out[2]);
}

// Gen 5's touch bits in a battle (proc: the battle's): the commands and the moves are the battle buttons' own while their
// panel takes touches; the bag and the party (and a fainted Pokémon's party) are screens of their own, faded in; any
// other panel (a prompt) is the touch screen's.
static int gen5BattleTouch(const PkNdsFieldGame* g, const PkNdsGen5Touch* t, uint32_t (*read32)(uint32_t), uint32_t proc)
{
    int panel = 0, waiting = 0, sub = 0;
    if (!gen5BattlePanel(g, t, read32, proc, &panel, &waiting, &sub)) return 0;
    int command = gen5BattleCommand(g, read32);
    if (command == g->battleSelecting) {
        if (panel == 1 || panel == 2) return waiting ? PK_NDS_FIELD_TOUCH_BATTLE_MENU : 0;
        if (sub == 2 || sub == 3) return PK_NDS_FIELD_TOUCH | PK_NDS_FIELD_TOUCH_FADES;
        return 0;
    }
    if (command == GEN5_ACMD_PARTY) return PK_NDS_FIELD_TOUCH | PK_NDS_FIELD_TOUCH_FADES;
    // The switch prompt's party: after the prompt, no panel (the party's own screen, faded in - the engine waits the fade
    // out, so KEEP BATTLING's few frames never float anything in).
    if (command != GEN5_ACMD_SWITCH_ASK) gen5SwitchAsked = 0;
    else if (panel == 4) gen5SwitchAsked = 1;
    else if (gen5SwitchAsked && panel == 0) return PK_NDS_FIELD_TOUCH | PK_NDS_FIELD_TOUCH_FADES;
    return panel >= 3 ? PK_NDS_FIELD_TOUCH : 0;
}

// Gen 5's WILD ENCOUNTERS hold: the zone's seven rates (the event data's encounter table, reloaded on every zone
// change), zeroed while it's off and put back after. Two words: rates 0-3, then 4-6 under the table's flag byte.
static uint32_t heldTable, heldZone5, heldRates[2];
static int holding5;

void pk_nds_field_reset_wild(void)
{
    holding = holding5 = checked = 0;
    gen5SwitchAsked = 0;
}

static void gen5Wild(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t), void (*write32)(uint32_t, uint32_t),
                     uint32_t gd, int wildOff, int* bits);

// Gen 5's field bits: the scene from the running proc, the field map and the event; the form from the GameData.
static int gen5FieldState(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t), void (*write32)(uint32_t, uint32_t),
                          int wildOff)
{
    int bits = PK_NDS_FIELD_KNOWN;
    uint32_t gs = read32(g->fieldSystemPtr);
    if (!inMainRam(gs)) return bits;   // the title screen, the intro
    uint32_t proc = gen5Proc(g, read32, gs);
    if (!proc) return bits;
    int overlay = (int) read32(proc + g->offProcOverlay);
    uint32_t fieldMap = read32(gs + g->offFieldMap);
    uint32_t event = read32(gs + g->offEvent);
    const PkNdsGen5Touch* touch = gen5Touch(g);
    if (overlay == g->battleOverlay)
        return bits | PK_NDS_FIELD_BATTLE | PK_NDS_FIELD_LOCKED | (touch ? gen5BattleTouch(g, touch, read32, proc) : 0);
    gen5SwitchAsked = 0;
    if (overlay != g->fieldOverlay || !inMainRam(fieldMap)) {
        bits |= PK_NDS_FIELD_MENU | PK_NDS_FIELD_LOCKED;
        if (touch) {
            bits |= PK_NDS_FIELD_TOUCH;
            for (int o : touch->touchOnly) if (o && o == overlay) bits |= PK_NDS_FIELD_TOUCH_ONLY;
        }
        return bits;
    }
    bits |= PK_NDS_FIELD_SCREEN;
    // A script, a text box, the X menu (drawn on the touch screen over the field), a warp: each runs as an event.
    if (event) bits |= PK_NDS_FIELD_LOCKED | PK_NDS_FIELD_TEXT;
    // The X menu, or what it runs on the field (the save's prompt: two events under it).
    if (touch && inMainRam(event)) {
        uint32_t e = event;
        for (int depth = 0; depth < 3 && inMainRam(e); depth++, e = read32(e)) {
            if (read32(e + 4) == touch->startMenuEvent) { bits |= PK_NDS_FIELD_TOUCH; break; }
        }
    }
    uint32_t gd = read32(gs + g->offGameData);
    if (!inMainRam(gd)) return bits;
    uint32_t form = read32(gd + g->offMoveForm);
    bits |= PK_NDS_FIELD_AVATAR;
    if (form == AVATAR_CYCLING) bits |= PK_NDS_FIELD_BIKE;
    if (form == AVATAR_SURFING) bits |= PK_NDS_FIELD_SURF;
    if (write32) gen5Wild(g, read32, write32, gd, wildOff, &bits);
    return bits;
}

static void gen5Wild(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t), void (*write32)(uint32_t, uint32_t),
                     uint32_t gd, int wildOff, int* bits)
{
    uint32_t ev = read32(gd + g->offEventData);
    if (!inMainRam(ev)) return;
    uint32_t table = ev + g->offEncounterRates;
    uint32_t zone = read32(gd + g->offZone) & 0xFFFF;
    uint32_t w0 = read32(table), w1 = read32(table + 4);
    if (holding5 && (table != heldTable || zone != heldZone5)) holding5 = 0;   // a new zone loaded its own table
    if (wildOff) {
        if (!holding5) {
            heldTable = table;
            heldZone5 = zone;
            heldRates[0] = w0;
            heldRates[1] = w1 & 0x00FFFFFFu;
            holding5 = 1;
        }
        checked = 0;
        if (w0) write32(table, 0);
        if (w1 & 0x00FFFFFFu) write32(table + 4, w1 & 0xFF000000u);
    } else {
        if (holding5 && table == heldTable && zone == heldZone5 && !w0 && !(w1 & 0x00FFFFFFu)) {
            write32(table, heldRates[0]);
            write32(table + 4, (w1 & 0xFF000000u) | heldRates[1]);
        }
        holding5 = 0;
        // A zone still at 0 where the cart has rates: a hold this process doesn't remember (a resume state taken while
        // OFF) - the cart's go back ([zone, rates 0-3, rates 4-6], pk_nds_field_set_rates).
        if (!checked || table != checkedData || zone != checkedMap) {
            auto it = cartRates.find(zone);
            if (it != cartRates.end() && !w0 && !(w1 & 0x00FFFFFFu) && (it->second.first || it->second.second)) {
                write32(table, it->second.first);
                write32(table + 4, (w1 & 0xFF000000u) | (it->second.second & 0x00FFFFFFu));
            }
            checked = 1;
            checkedData = table;
            checkedMap = zone;
        }
    }
    if (holding5) *bits |= PK_NDS_FIELD_WILD_HELD;
}

int pk_nds_bgm_settle(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t))
{
    if (!g || !g->seqPlayers) return 0x7F00;
    // NNSSndSeqPlayer (0x44 bytes): its handle's address first, the volume fader { origin, target, counter, frame } at
    // 0x1C, status at 0x2C (byte 0 playing, byte 2 paused). A fade is run by the ARM9 a frame at a time
    // (NNS_SndPlayerMoveVolume), so a copy taken mid-fade would stay at that volume.
    for (uint32_t i = 0; i < 16; i++) {
        uint32_t p = g->seqPlayers + i * 0x44;
        if ((read32(p + 0x2C) & 0xFF) && (int32_t) read32(p + 0x24) < (int32_t) read32(p + 0x28)) return -1;
    }
    if (!g->bgmDepth) return 0x7F00;   // Gen 4: no song's player read, so always full volume
    // Gen 5: the song's player is the one holding the BGM stack entry's handle (the entry + 4). Headless in Black, a
    // song coming back after a battle (or the battle's after its interlude) was paused at volume 0 when its id changed,
    // un-paused and faded in 6 - 24 frames later: a copy taken in between stayed silent until the next song.
    uint32_t depth = read32(g->bgmDepth);
    if (depth > 7) return -1;
    uint32_t handle = g->currentBgm + 0x1C * depth + 4;
    for (uint32_t i = 0; i < 16; i++) {
        uint32_t p = g->seqPlayers + i * 0x44;
        if (read32(p) != handle) continue;
        uint32_t status = read32(p + 0x2C);
        if (!(status & 0xFF) || (status & 0xFF0000)) return -1;
        uint32_t target = read32(p + 0x20) & 0xFFFF;
        return (int) ((i << 16) | target);
    }
    return -1;
}

int pk_nds_battle_command(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t))
{
    if (g && g->gen == 5) return gen5BattleCommand(g, read32);
    if (g && g->battleManagerPtr) {
        uint32_t fs = read32(g->fieldSystemPtr);
        uint32_t pm = inMainRam(fs) ? read32(fs + g->offProcessManager) : 0;
        uint32_t child = inMainRam(pm) ? read32(pm + 4) : 0;
        if (!inMainRam(child) || read32(child) != read32(g->battleTemplate)) return -1;
        return managedBattleInput(g, read32) ? PK_NDS_BATTLE_SELECTING : 0;
    }
    if (!g || !g->offBattleContext) return -1;
    uint32_t fs = read32(g->fieldSystemPtr);
    if (!inMainRam(fs)) return -1;
    uint32_t pm = read32(fs + g->offProcessManager);
    if (!inMainRam(pm)) return -1;
    uint32_t child = read32(pm + 4);
    if (!inMainRam(child) || read32(child) != read32(g->battleTemplate)) return -1;
    // The battle's OverlayManager: its data (+0x1C) is the BattleSystem.
    uint32_t bs = read32(child + 0x1C);
    if (!inMainRam(bs)) return -1;
    uint32_t ctx = read32(bs + g->offBattleContext);
    if (!inMainRam(ctx)) return -1;
    uint32_t c = read32(ctx + g->offCommand);
    return c < 64 ? (int) c : -1;
}

int pk_nds_field_state(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t), void (*write32)(uint32_t, uint32_t),
                       int wildOff)
{
    if (!g) return 0;
    if (g->gen == 5) return gen5FieldState(g, read32, write32, wildOff);
    int bits = PK_NDS_FIELD_KNOWN;
    uint32_t fs = read32(g->fieldSystemPtr);
    if (!inMainRam(fs)) return bits;   // title screen, the intro: no overworld at all
    uint32_t pm = read32(fs + g->offProcessManager);
    if (!inMainRam(pm)) return bits;
    uint32_t parent = read32(pm), child = read32(pm + 4), pause = read32(pm + 8);
    uint32_t task = read32(fs + g->offTask);
    int running = read32(fs + g->offRunningFieldMap) != 0;
    const PkNdsTouchRules* touch = touchRules(g);
    if (inMainRam(child)) {
        // The battle's child process starts from gBattleApplicationTemplate (its template is copied in whole).
        int battle = read32(child) == read32(g->battleTemplate);
        bits |= battle ? PK_NDS_FIELD_BATTLE : PK_NDS_FIELD_MENU;
        // (A top-screen page wants no touch screen: the game alone, not side by side.)
        if (touch) bits |= battle ? battleTouch(g, touch, read32, child)
                         : topOnlyPage(touch, read32, child) ? PK_NDS_FIELD_TOP_ONLY
                         : PK_NDS_FIELD_TOUCH | (touchOnlyPage(touch, read32, child) ? PK_NDS_FIELD_TOUCH_ONLY : 0);
        return bits | PK_NDS_FIELD_LOCKED;
    }
    if (inMainRam(parent) && running) bits |= PK_NDS_FIELD_SCREEN;
    // The field's own input runs only with no task and no pause (field_system.c's HandleFieldInput): a script, a text
    // box, a warp, the X menu all hold a task.
    if (task || pause || !running) {
        bits |= PK_NDS_FIELD_LOCKED;
        if (task && running) bits |= PK_NDS_FIELD_TEXT;
    }
    // The X menu (HeartGold / SoulSilver: on the touch screen): its field task.
    if (touch && touch->startMenuTask && inMainRam(task) && read32(task + 4) == touch->startMenuTask) bits |= PK_NDS_FIELD_TOUCH;
    // WILD ENCOUNTERS: the current map's rates, held at 0 while it's off.
    uint32_t data = read32(fs + g->offMapHeaderData);
    uint32_t loc = read32(fs + g->offLocation);
    uint32_t map = inMainRam(loc) ? read32(loc) : 0xFFFFFFFFu;
    if (write32 && inMainRam(data) && inMainRam(loc)) {
        if (holding && (data != heldData || map != heldMap)) holding = 0;   // a new map: its own rates were loaded
        if (wildOff) {
            uint32_t grass = rateRead(g, read32, data + g->offGrassRate), surf = rateRead(g, read32, data + g->offSurfRate);
            if (!holding) {
                heldData = data;
                heldMap = map;
                heldGrass = grass;
                heldSurf = surf;
                holding = 1;
            }
            checked = 0;
            if (grass) rateWrite(g, read32, write32, data + g->offGrassRate, 0);
            if (surf) rateWrite(g, read32, write32, data + g->offSurfRate, 0);
        } else {
            if (holding) releaseWild(g, read32, write32, data, map);
            // Once a map, back on: rates still at 0 where the cart has some are a hold this process doesn't remember (a
            // resume state taken while OFF, which the app restarted from): the cart's own go back. Its rates came from the
            // ROM, and nothing in the game zeroes them.
            if (!checked || data != checkedData || map != checkedMap) {
                auto it = cartRates.find(map);
                if (it != cartRates.end()) {
                    if (it->second.first && rateRead(g, read32, data + g->offGrassRate) == 0)
                        rateWrite(g, read32, write32, data + g->offGrassRate, it->second.first);
                    if (it->second.second && rateRead(g, read32, data + g->offSurfRate) == 0)
                        rateWrite(g, read32, write32, data + g->offSurfRate, it->second.second);
                }
                checked = 1;
                checkedData = data;
                checkedMap = map;
            }
        }
        if (holding) bits |= PK_NDS_FIELD_WILD_HELD;
    }
    uint32_t avatar = read32(fs + g->offPlayerAvatar);
    if (inMainRam(avatar)) {
        uint32_t state = read32(avatar + g->offAvatarState);
        bits |= PK_NDS_FIELD_AVATAR;
        if (state == AVATAR_CYCLING) bits |= PK_NDS_FIELD_BIKE;
        if (state == AVATAR_SURFING) bits |= PK_NDS_FIELD_SURF;
    }
    return bits;
}

// A menu screen opened from the X menu ends: the X menu comes back for buttons, but closes itself when the screen was
// left by touch (start_menu.c's Task_StartMenu_WaitApp: FieldSystem.menuInputState TOUCH -> START_MENU_STATE_10).
// DS LAYOUT's floating X menu should come back either way, so the screen's closing fade sets it to buttons. (HeartGold /
// SoulSilver; headless: the trainer card's Cancel, then this during its fade - the menu stays, its cursor shown.)
void pk_nds_field_keep_start_menu(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t), void (*write32)(uint32_t, uint32_t))
{
    const PkNdsTouchRules* t = touchRules(g);
    if (!t || !t->offMenuInputState || !write32) return;
    uint32_t fs = read32(g->fieldSystemPtr);
    if (!inMainRam(fs)) return;
    uint32_t task = read32(fs + g->offTask);
    if (!inMainRam(task) || read32(task + 4) != t->startMenuTask) return;
    if (read32(fs + t->offMenuInputState) != 0) write32(fs + t->offMenuInputState, 0);
}
