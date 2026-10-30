// See pk_nds_field.h. Addresses from pret/pokeplatinum's .xMAP (it builds the retail ROM byte for byte), struct
// offsets from its headers, both checked on headless captures (nds_dump's fieldinfo: the field, a text box, the X
// menu, the party screen, a battle).

#include "pk_nds_field.h"

#include <string.h>

#include <unordered_map>
#include <utility>

#include <NDS.h>

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

const PkNdsFieldGame* pk_nds_field_game(const char code[4], int revision)
{
    if (memcmp(code, "CPUE", 4) == 0 && revision == 1) return &PLATINUM_US_REV1;
    if ((memcmp(code, "IPKE", 4) == 0 || memcmp(code, "IPGE", 4) == 0) && revision == 0) return &HEARTGOLD_US;
    if ((memcmp(code, "IPKD", 4) == 0 || memcmp(code, "IPGD", 4) == 0) && revision == 0) return &HEARTGOLD_DE;
    if (memcmp(code, "IRBO", 4) == 0 && revision == 0) return &BLACK_US;
    return 0;
}

static int inMainRam(uint32_t p) { return p >= 0x02000000u && p < 0x02400000u; }

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

// Gen 5's WILD ENCOUNTERS hold: the zone's seven rates (the event data's encounter table, reloaded on every zone
// change), zeroed while it's off and put back after. Two words: rates 0-3, then 4-6 under the table's flag byte.
static uint32_t heldTable, heldZone5, heldRates[2];
static int holding5;

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
    if (overlay == g->battleOverlay) return bits | PK_NDS_FIELD_BATTLE | PK_NDS_FIELD_LOCKED;
    if (overlay != g->fieldOverlay || !inMainRam(fieldMap)) return bits | PK_NDS_FIELD_MENU | PK_NDS_FIELD_LOCKED;
    bits |= PK_NDS_FIELD_SCREEN;
    // A script, a text box, the X menu (drawn on the touch screen over the field), a warp: each runs as an event.
    if (event) bits |= PK_NDS_FIELD_LOCKED | PK_NDS_FIELD_TEXT;
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

int pk_nds_battle_command(const PkNdsFieldGame* g, uint32_t (*read32)(uint32_t))
{
    if (g && g->gen == 5) return gen5BattleCommand(g, read32);
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
    if (inMainRam(child)) {
        // The battle's child process starts from gBattleApplicationTemplate (its template is copied in whole).
        bits |= read32(child) == read32(g->battleTemplate) ? PK_NDS_FIELD_BATTLE : PK_NDS_FIELD_MENU;
        return bits | PK_NDS_FIELD_LOCKED;
    }
    if (inMainRam(parent) && running) bits |= PK_NDS_FIELD_SCREEN;
    // The field's own input runs only with no task and no pause (field_system.c's HandleFieldInput): a script, a text
    // box, a warp, the X menu all hold a task.
    if (task || pause || !running) {
        bits |= PK_NDS_FIELD_LOCKED;
        if (task && running) bits |= PK_NDS_FIELD_TEXT;
    }
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

int pk_nds_bg3_tiles(melonDS::NDS* nds, int bg)
{
    uint32_t disp = nds->GPU.GPU2D_A.DispCnt;
    if (!(disp & (1 << (8 + bg)))) return 0;   // the BG is off
    if (bg == 3 && (disp & 7) > 1) return 0;   // not a text BG in this mode
    if (bg == 2 && (disp & 7) > 3) return 0;
    uint16_t cnt = nds->GPU.GPU2D_A.BGCnt[bg];
    uint32_t base = 0x06000000u + ((disp >> 27) & 7) * 0x10000 + ((cnt >> 8) & 0x1F) * 0x800;
    int n = 0;
    for (uint32_t i = 0; i < 32 * 24; i++)
        if (nds->ARM9Read16(base + i * 2) & 0x3FF) n++;
    return n;
}
