#include "pd_session.h"

#include "../core/pd_games.h"
#include "../core/pd_tables.h"
#include "../../linux/gb_field.h"
#include "pd_mixer.h"
#include "../emu/pd_armgba.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>

#include <mgba-util/vfs.h>
#include <mgba/core/core.h>
#include <mgba/core/serialize.h>

#include <mgba/internal/gb/gb.h>
#include <mgba/internal/gb/memory.h>

#include "../../app/src/main/cpp/pk_call.h"
#include "../../app/src/main/cpp/pk_cheats.h"
#include "../../app/src/main/cpp/pk_host.h"
#include "../../app/src/main/cpp/pk_wide.h"
#include "../../linux/field_items.h"
#include "../../linux/flash.h"
#include "../../linux/tm_teach.h"
#include "../../linux/wild.h"
#include "../core/pd_canvas.h"
#include "../core/pd_map.h"
#include "../core/pd_romart.h"
#include "../core/pd_style.h"

// What a save state keeps: the RTC, never the save data - loading one must not write an old copy over the save file
// (CLAUDE.md: save files are sacred).
#define STATE_FLAGS SAVESTATE_RTC
#define NOTICE_FRAMES 150
#define HUD_AFTER_FRAMES 20 // the HUD comes on after this much plain field (FieldHud's rule), off at once
#define BIKE_TRIES 15       // PokeDaisyActivity's SELECT_TRIES
#define DIRECTIONS (PD_KEY_UP | PD_KEY_DOWN | PD_KEY_LEFT | PD_KEY_RIGHT)
#define AUTO_RUN_BLOCKERS (PK_FIELD_LOCKED | PK_FIELD_BATTLE | PK_FIELD_BIKE)

static uint32_t wideSource[PK_WIDE_W * PK_WIDE_H];
// Two, in turn: the draw thread reads one while the next frame fills the other, with no copy between them.
static uint32_t wideFrames[2][PD_TOP_W * PD_TOP_H];
static int wideShown;
static uint32_t overlayPx[PD_TOP_W * PD_TOP_H];

// The bag's USE: the field items' own, and a TM / HM where the game's TM screen can be opened (the host callback has no
// context, so the running game's TM config is kept here).
static const struct pd_config* itemTmCfg;
static bool item_can_use(int item) {
    return pd_items_can_use(item) || (itemTmCfg && tm_teaches(itemTmCfg, item));
}

// A Game Boy game's bus; 0x1Dxxx is D000-DFFF in that WRAM bank whatever the game has switched in there (pk_gb_read_banked).
static bool gb_read(const struct pd_session* s, uint32_t addr, uint8_t* out, size_t len) {
    struct GB* gb = s->core->board;
    if (!gb->memory.wram) return false;
    for (size_t i = 0; i < len; i++) {
        uint32_t a = addr + (uint32_t) i;
        if (a >= 0x10000) {
            uint32_t bank = a >> 16, off = a & 0xFFFF;
            out[i] = bank <= 7 && off >= 0xD000 && off < 0xE000 ? gb->memory.wram[bank * GB_SIZE_WORKING_RAM_BANK0 + off - 0xD000] : 0;
        } else if (a >= 0xC000 && a < 0xD000) {
            out[i] = gb->memory.wram[a - 0xC000];
        } else if (a >= 0xD000 && a < 0xE000) {
            out[i] = gb->memory.wramBank[a - 0xD000];
        } else {
            out[i] = (uint8_t) s->core->busRead8(s->core, a);
        }
    }
    return true;
}

bool pd_session_read(void* ctx, uint32_t addr, void* out, size_t len) {
    const struct pd_session* s = ctx;
    if (s->game.kind == PD_GAME_GB) return gb_read(s, addr, out, len);
    if (addr >= 0x02000000 && addr - 0x02000000 + len <= s->ewramSize) {
        memcpy(out, s->ewram + (addr - 0x02000000), len);
        return true;
    }
    if (addr >= 0x03000000 && addr - 0x03000000 + len <= s->iwramSize) {
        memcpy(out, s->iwram + (addr - 0x03000000), len);
        return true;
    }
    // ROM: pointers in RAM often lead into it (a map's layout).
    if (addr >= 0x08000000 && addr - 0x08000000 + len <= s->romSize) {
        memcpy(out, s->rom + (addr - 0x08000000), len);
        return true;
    }
    return false;
}

bool pd_session_write(void* ctx, uint32_t addr, const void* data, size_t len) {
    struct pd_session* s = ctx;
    if (s->game.kind == PD_GAME_GB) {
        for (size_t i = 0; i < len; i++) s->core->busWrite8(s->core, addr + (uint32_t) i, ((const uint8_t*) data)[i]);
        return true;
    }
    // armGBA throws out code it compiled from RAM that changes.
    if (pd_armgba_is(s->core)) return pd_armgba_write(addr, data, len);
    if (addr >= 0x02000000 && addr - 0x02000000 + len <= s->ewramSize) {
        memcpy(s->ewram + (addr - 0x02000000), data, len);
        return true;
    }
    if (addr >= 0x03000000 && addr - 0x03000000 + len <= s->iwramSize) {
        memcpy(s->iwram + (addr - 0x03000000), data, len);
        return true;
    }
    return false;
}

void pd_session_show(struct pd_session* s, const char* text) {
    snprintf(s->notice, sizeof(s->notice), "%s", text);
    s->noticeFrames = NOTICE_FRAMES;
}

static void state_paths(const struct pd_session* s, int slot, char* vf, size_t vfLen, char* path, size_t pathLen) {
    s->files.statePath(s->files.ctx, slot, vf, vfLen, path, pathLen);
}

// Quarter-size pictures sit beside the states, 120 x 80 RGB.
static void thumb_path(const char* path, char* out, size_t len) {
    snprintf(out, len, "%s.thumb", path);
}

static void read_slot(struct pd_session* s, int slot) {
    struct pd_state_slot* out = &s->host.slots[slot];
    char vf[512], path[512], thumb[520];
    state_paths(s, slot, vf, sizeof(vf), path, sizeof(path));
    struct stat st;
    out->used = !stat(path, &st);
    out->thumb = NULL;
    if (!out->used) return;
    struct tm* t = localtime(&st.st_mtime);
    if (t) strftime(out->when, sizeof(out->when), "%m-%d %H:%M", t);
    thumb_path(path, thumb, sizeof(thumb));
    FILE* f = fopen(thumb, "rb");
    if (!f) return;
    size_t want = sizeof(s->thumbs[slot]);
    if (fread(s->thumbs[slot], 1, want, f) == want) out->thumb = s->thumbs[slot];
    fclose(f);
}

// STATES' UNDO: the state a SAVE replaced (the slot's file, set aside), and where a LOAD left the game.
static void undo_path(const struct pd_session* s, const char* name, char* vf, size_t vfLen, char* path, size_t pathLen) {
    char v[512], p[512];
    state_paths(s, 0, v, sizeof(v), p, sizeof(p));
    snprintf(vf, vfLen, "%s.%s", v, name);
    snprintf(path, pathLen, "%s.%s", p, name);
}

static void copy_file(const char* from, const char* to) {
    FILE* in = fopen(from, "rb");
    if (!in) return;
    FILE* out = fopen(to, "wb");
    if (out) {
        static uint8_t chunk[8192];
        size_t n;
        while ((n = fread(chunk, 1, sizeof(chunk), in)) > 0) fwrite(chunk, 1, n, out);
        fclose(out);
    }
    fclose(in);
}

bool pd_session_save_state(struct pd_session* s, int slot) {
    {
        char vf0[512], path0[512], undoVf[512], undo[512];
        state_paths(s, slot, vf0, sizeof(vf0), path0, sizeof(path0));
        undo_path(s, "undosave", undoVf, sizeof(undoVf), undo, sizeof(undo));
        struct stat st;
        if (!stat(path0, &st)) {
            copy_file(path0, undo);
            s->undoSaveSlot = slot;
            s->host.undoSave = true;
        }
    }
    char vfPath[512], path[512], thumb[520];
    state_paths(s, slot, vfPath, sizeof(vfPath), path, sizeof(path));
    bool ok;
    pd_mixer_release(s->core); // a state never holds the hook
    if (pd_armgba_is(s->core)) {
        ok = pd_armgba_save_state(path);
    } else {
        struct VFile* vf = VFileOpen(vfPath, O_CREAT | O_TRUNC | O_RDWR);
        if (!vf) return false;
        ok = mCoreSaveStateNamed(s->core, vf, STATE_FLAGS);
        vf->close(vf);
    }
    uint32_t* px = s->thumbs[slot];
    unsigned gw = 240, gh = 160;
    s->core->desiredVideoDimensions(s->core, &gw, &gh);
    for (int y = 0; y < PD_STATE_THUMB_H; y++) {
        for (int x = 0; x < PD_STATE_THUMB_W; x++) {
            uint32_t p = s->video[(y * (int) gh / PD_STATE_THUMB_H) * PD_GAME_STRIDE + x * (int) gw / PD_STATE_THUMB_W];
            px[y * PD_STATE_THUMB_W + x] = (p & 0xFF) << 16 | (p & 0xFF00) | (p >> 16 & 0xFF);
        }
    }
    thumb_path(path, thumb, sizeof(thumb));
    FILE* f = fopen(thumb, "wb");
    if (f) {
        fwrite(px, 1, sizeof(s->thumbs[slot]), f);
        fclose(f);
    }
    read_slot(s, slot);
    s->redraw = true;
    return ok;
}

static void write_state(struct pd_session* s, const char* vfPath, const char* path) {
    if (pd_armgba_is(s->core)) {
        pd_mixer_release(s->core);
        pd_armgba_save_state(path);
        return;
    }
    struct VFile* vf = VFileOpen(vfPath, O_CREAT | O_TRUNC | O_RDWR);
    if (!vf) return;
    pd_mixer_release(s->core);
    mCoreSaveStateNamed(s->core, vf, STATE_FLAGS);
    vf->close(vf);
}

bool pd_session_undo_save(struct pd_session* s) {
    if (s->undoSaveSlot < 0) return false;
    char vf[512], path[512], undo[512], undoVf[512];
    state_paths(s, s->undoSaveSlot, vf, sizeof(vf), path, sizeof(path));
    undo_path(s, "undosave", undoVf, sizeof(undoVf), undo, sizeof(undo));
    copy_file(undo, path);
    read_slot(s, s->undoSaveSlot);
    s->undoSaveSlot = -1;
    // The HUD: the 3DS's compact one, no buttons (the top screen has no touch; X opens the companion).
    s->hud.compact = s->hud.noButtons = true;
    s->hud.wild = s->hud.focus = -1;
    s->host.undoSave = false;
    return true;
}

// A state into the running core: armGBA's own (an mGBA state from an earlier build is refused with a notice and left
// where it is), else mGBA's.
static bool load_state_file(struct pd_session* s, const char* vfPath, const char* path) {
    if (pd_armgba_is(s->core)) {
        enum pd_armgba_state r = pd_armgba_load_state(path);
        if (r == PD_ARMGBA_STATE_FOREIGN) pd_session_show(s, "THAT STATE IS FROM AN OLDER BUILD");
        else if (r == PD_ARMGBA_STATE_OTHER_GAME) pd_session_show(s, "THAT STATE IS ANOTHER GAME'S");
        else if (r == PD_ARMGBA_STATE_BAD) pd_session_show(s, "THAT STATE DIDN'T LOAD");
        return r == PD_ARMGBA_STATE_OK;
    }
    struct VFile* vf = VFileOpen(vfPath, O_RDONLY);
    if (!vf) return false;
    bool ok = mCoreLoadStateNamed(s->core, vf, STATE_FLAGS);
    vf->close(vf);
    return ok;
}

bool pd_session_undo_load(struct pd_session* s) {
    if (!s->host.undoLoad) return false;
    char vf[512], path[512];
    undo_path(s, "undoload", vf, sizeof(vf), path, sizeof(path));
    bool ok = load_state_file(s, vf, path);
    if (ok) {
        pk_heal_rtc(s->core);
        pk_wide_reset();
    }
    s->host.undoLoad = false;
    return ok;
}

bool pd_session_load_state(struct pd_session* s, int slot) {
    char vfPath[512], path[512];
    state_paths(s, slot, vfPath, sizeof(vfPath), path, sizeof(path));
    s->files.backupSave(s->files.ctx);
    char undoVf[512], undo[512];
    undo_path(s, "undoload", undoVf, sizeof(undoVf), undo, sizeof(undo));
    write_state(s, undoVf, undo);
    s->host.undoLoad = true;
    bool ok = load_state_file(s, vfPath, path);
    if (ok) {
        pk_heal_rtc(s->core);
        pk_wide_reset(); // its RAM history is from before the jump
    }
    return ok;
}

static void cheats_update(struct pd_session* s) {
    bool on = s->settings->cheats && s->cheatText;
    if (on == s->cheatsOn) return;
    s->cheatsOn = on;
    pk_cheats_apply(s->core, on ? s->cheatText : NULL, on ? s->cheatLen : 0);
}

static void fly_to(struct pd_session* s, int mapsec);

// FREE FLIGHT's calls into the game (pk_flight.h).
static int flight_call(struct mCore* core, uint32_t fn, const uint32_t args[4], uint32_t* r0) {
    return pk_call(core, fn, args, r0) ? 1 : 0;
}

// pk_flight told this game's addresses, with FLY ON's vehicle (a change mid-flight just redraws).
static void flight_configure(struct pd_session* s) {
    uint32_t w[PK_FLIGHT_WORDS];
    flight_words(s->flightCfg, s->settings->flightVehicle, w);
    pk_flight_configure(w, PK_FLIGHT_WORDS);
    s->flightVehicle = s->settings->flightVehicle;
}

// FLIGHT AREA's file: a line per map seen ("group.num"), one per ROM and save.
static char flightAreaText[16384];

static void flight_area_write(struct pd_session* s) {
    struct flight_area* a = &s->flightArea;
    if (!a->dirty || !a->key || !s->files.flightAreaPath) return;
    a->dirty = false;
    char path[512], tmp[520];
    s->files.flightAreaPath(s->files.ctx, s->romCrc, a->key, path, sizeof(path));
    snprintf(tmp, sizeof(tmp), "%s.tmp", path);
    size_t n = flight_area_format(a, flightAreaText, sizeof(flightAreaText));
    FILE* f = fopen(tmp, "wb");
    if (!f) return;
    bool ok = fwrite(flightAreaText, 1, n, f) == n;
    if (fclose(f) != 0) ok = false;
    if (ok) {
        remove(path);
        rename(tmp, path);
    }
}

static void flight_area_load(struct pd_session* s, uint32_t key) {
    flight_area_write(s);
    flight_area_clear(&s->flightArea, key);
    if (!s->files.flightAreaPath) return;
    char path[512];
    s->files.flightAreaPath(s->files.ctx, s->romCrc, key, path, sizeof(path));
    FILE* f = fopen(path, "rb");
    if (!f) return;
    size_t n = fread(flightAreaText, 1, sizeof(flightAreaText), f);
    fclose(f);
    flight_area_parse(&s->flightArea, flightAreaText, n);
}

static void cheats_load(struct pd_session* s) {
    free(s->cheatText);
    s->cheatText = NULL;
    s->host.hasCheats = false;
    s->cheatsOn = false;
    if (!s->files.cheatPath || s->game.kind == PD_GAME_GB) return;
    char path[512];
    s->files.cheatPath(s->files.ctx, s->romCrc, path, sizeof(path));
    FILE* f = fopen(path, "rb");
    if (!f) return;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (n > 0 && n < (1 << 20) && (s->cheatText = malloc((size_t) n + 1))) {
        s->cheatLen = fread(s->cheatText, 1, (size_t) n, f);
        s->cheatText[s->cheatLen] = 0;
        s->host.hasCheats = true;
    }
    fclose(f);
    cheats_update(s);
}

// SMART: 1x while the game waits for the player (SmartFf.kt): a battle's menus, a menu screen. Never its animations or text.
static bool smart_slows(const struct pd_session* s) {
    if (s->settings->ffNormal) return false;
    if (s->shown.inBattle) {
        switch (s->input.state) {
        case PD_BATTLE_ACTION: case PD_BATTLE_MOVE: case PD_BATTLE_TARGET: case PD_BATTLE_PARTY_OPEN: case PD_BATTLE_BAG_OPEN:
            return true;
        default:
            return false;
        }
    }
    return s->wideKnown && pk_wide_scene() == PK_WIDE_SCENE_MENU;
}

void pd_session_start(struct pd_session* s, struct mCore* core, const uint32_t* video, struct pd_settings* settings,
                      const struct pd_session_host* files) {
    s->core = core;
    s->video = video;
    s->settings = settings;
    s->files = *files;
    bool gba = core->platform(core) == mPLATFORM_GBA;
    if (gba) {
        pd_mixer_attach(core);
        s->ewram = core->getMemoryBlock(core, 0x02, &s->ewramSize);
        s->iwram = core->getMemoryBlock(core, 0x03, &s->iwramSize);
    }
    // The ROM as mGBA loaded it, for the tabs that read its tables and art. Its art (the region map, the TRAINER
    // CARD) is found now - one pass over the whole ROM, under a second here - rather than on the first open, mid-game.
    size_t romSize = 0;
    s->game.rom = gba ? core->getMemoryBlock(core, 0x08, &romSize) : ((struct GB*) core->board)->memory.rom;
    if (!gba) romSize = ((struct GB*) core->board)->memory.romSize;
    s->game.romSize = s->game.rom ? romSize : 0;
    s->rom = s->game.rom;
    s->romSize = s->game.romSize;
    // Which game this is, by its SHA-1 (a hack gets its own config, gamedata's; the app's look for it, pd_games_gen.c).
    pd_game_identify(&s->game);
    if (gba) {
        pd_romart_scan(&s->game);
        pd_map_art(&s->game);
        pk_rest_reset();
    }

    pd_look_apply(&s->game, settings);
    pd_ui_init(&s->ui, settings);
    pd_binput_init(&s->input, &s->game, pd_session_read, pd_session_write, s);
    s->ui.battleInput = &s->input;
    s->ui.tab = PD_TAB_PARTY;
    s->host.hasStates = files->statePath != NULL;
    s->undoSaveSlot = -1;
    if (files->statePath)
        for (int i = 0; i < PD_STATE_SLOTS; i++) read_slot(s, i);

    core->checksum(core, &s->romCrc, mCHECKSUM_CRC32);
    cheats_load(s);
    s->wideKnown = pk_wide_configure(s->romCrc) != 0;
    pk_wide_cache(1);
    pk_wide_hole(1);
    pk_wide_later(1, files->wideJoin);
    s->fieldKnown = gba && pk_field_configure(s->romCrc) != 0;
    s->wildSwitch = s->fieldKnown ? pk_wild_switch_find(s->rom, s->romSize) : 0;
    if (s->wildSwitch) pk_field_wild(s->wildSwitch, settings->wildOff);
    s->host.hasExpand = true;
    s->host.expandGame = s->wideKnown;
    s->host.hasHud = true;
    s->host.hasPerf = true;
    s->host.fullSettings = true;
    s->gbField = !gba && s->game.cfg ? gb_field_find(s->rom, s->romSize) : NULL;
    s->host.wildSupported = s->wildSwitch != 0 || s->gbField;
    s->host.fieldSupported = s->fieldKnown;

    const struct pd_config* cfg = s->game.cfg;
    s->flyCfg = cfg && fly_supported(cfg, s->rom, s->romSize) ? cfg : NULL;
    s->flashCfg = cfg && flash_supported(cfg, s->rom, s->romSize) ? cfg : NULL;
    s->flashAsk = 0;
    // FREE FLIGHT: retail English FireRed / LeafGreen / Emerald (a retail hash, a dump or a randomized copy of one), like
    // the app's flightAllowed. The flight starts unconfigured: a state taken in the air comes back flying once it is.
    pk_flight_set_call(flight_call);
    pk_flight_configure(NULL, 0);
    pk_flight_reset();
    bool retail = gba && (s->game.retail || !strncmp(s->game.code, "BPG", 3));
    s->flightCfg = cfg && flight_supported(cfg, s->rom, s->romSize, retail) ? cfg : NULL;
    s->flightAsk = s->scenicAsk = 0;
    s->scenic = s->flightDrawn = s->flightAreaAnywhere = false;
    s->flightState = PK_FLIGHT_IDLE;
    flight_area_clear(&s->flightArea, 0);
    if (s->flightCfg) flight_configure(s);
    s->host.flightSupported = s->flightCfg != NULL;
    s->itemsOk = cfg && field_items_supported(cfg, s->rom, s->romSize);
    s->pcOk = s->itemsOk && pc_supported(cfg, s->rom, s->romSize);
    s->bikeOk = s->itemsOk && bike_supported(cfg);
    if (s->itemsOk) pd_items_setup(cfg, s->rom, s->romSize);
    if (cfg && pd_boxes_supported(&s->game)) s->ui.boxes = &s->boxes;
    s->ui.boxMoves = s->ui.boxes && s->itemsOk && pd_box_moves_ok(cfg, s->game.entry ? s->game.entry->boxSwap : NULL, s->rom, s->romSize);
    s->ui.boxSummary = s->ui.boxSlot = -1;
    s->tmCfg = cfg && tm_supported(cfg, s->rom, s->romSize) ? cfg : NULL;
    s->tmStage = 0;
    itemTmCfg = s->tmCfg;
    s->host.itemCanUse = item_can_use;
    s->host.itemCanGive = pd_items_can_give;

    // What the companion shows: read once now (RAM still blank: an empty party), then every PD_SNAPSHOT_EVERY frames.
    pd_snapshot_read(&s->shown, &s->game, pd_session_read, s);
    s->shown.frame = 0;
    s->frame = 0;
    s->redraw = true;
}

bool pd_session_game_boy(const struct pd_session* s) {
    unsigned w = 240, h = 160;
    s->core->desiredVideoDimensions(s->core, &w, &h);
    return w == 160;
}

static bool in_battle(const struct pd_session* s) {
    return s->shown.inBattle || (s->wideKnown && pk_wide_scene() == PK_WIDE_SCENE_BATTLE_START);
}

// EXPAND and ZOOM OUT give way to the whole screen, stretched, on the game's own menu screens (the title, CONTINUE, the
// party, the bag ...): there's no map past them to show, and drawing their margins every frame cost the frame.
static bool menu_screen(const struct pd_session* s) {
    if (!s->wideKnown || s->settings->aspect == PD_ASPECT_ORIGINAL || s->flightState != PK_FLIGHT_IDLE || in_battle(s)) return false;
    int scene = pk_wide_scene();
    // OFF is a battle too: only with the battle flag read this very frame (the snapshot's comes later) saying it isn't. A
    // fade from a menu screen (the intro's, the title's to CONTINUE) stays one.
    return scene == PK_WIDE_SCENE_MENU || (scene == PK_WIDE_SCENE_OFF && s->fieldKnown && !(s->fieldFlags & PK_FIELD_BATTLE)) ||
           (scene == PK_WIDE_SCENE_FADE && s->menuLatched);
}

int pd_session_screen(const struct pd_session* s) {
    // A Game Boy game keeps its shape on them (the screen's height, white bars beside: stretched, its letters went uneven).
    if (menu_screen(s)) return pd_session_game_boy(s) ? PD_SCREEN_SHARP : PD_SCREEN_STRETCH;
    int mode = s->settings->screenMode;
    if (mode == PD_SCREEN_PIXEL && s->wideKnown && s->settings->aspect != PD_ASPECT_ORIGINAL) mode = PD_SCREEN_SHARP_125;
    if (mode != PD_SCREEN_STRETCH && in_battle(s)) mode = PD_SCREEN_SHARP;
    return mode;
}

const uint32_t* pd_session_perf(unsigned fpsTenths, unsigned cpuPercent, int* w) {
    static uint32_t px[PD_PERF_W * PD_PERF_H];
    struct pd_canvas c;
    pd_canvas_init(&c, PD_PERF_W, PD_PERF_H, px);
    memset(px, 0, sizeof(px));
    char text[32];
    snprintf(text, sizeof(text), "%u.%u FPS  CPU %u%%", fpsTenths / 10, fpsTenths % 10, cpuPercent);
    *w = pd_text_width(text) + 8;
    // The capitals (font rows 4-12) and their shadow centred in the box: 2 rows above, 2 under.
    const int boxH = PD_PERF_H - 2;
    pd_round_rect(&c, 0, 0, *w, boxH, 2, 0xB8121620u);
    pd_text(&c, 4, (boxH - 10) / 2 - 4, text, 0xF8F8F8, 0x303840);
    for (int i = 0; i < PD_PERF_W * PD_PERF_H; i++)
        if (px[i] && !(px[i] >> 24)) px[i] |= 0xFF000000u;
    return px;
}

// EXPAND's window (Linux's wide_window): SCREEN's scale makes EXPAND's size, ZOOM OUT one whole scale less: the 3DS's
// 1.5x fills 400 x 240 with 266 x 160 of the game, 1x with all of it.
static void wide_window(const struct pd_session* s, int aspect, int* w, int* h) {
    int mode = pd_session_screen(s);
    bool whole = mode == PD_SCREEN_PIXEL || aspect == PD_ASPECT_ZOOM_OUT;
    int fw = pk_wide_frame_w(), fh = pk_wide_frame_h();
    bool mid = mode == PD_SCREEN_SHARP_125;
    int x = whole ? PD_TOP_W : mid ? PD_TOP_W * 4 / 5 : PD_TOP_W * 2 / 3, y = whole ? PD_TOP_H : mid ? PD_TOP_H * 4 / 5 : PD_TOP_H * 2 / 3;
    if (x < fw) x = fw;
    if (y < fh) y = fh;
    *w = x - ((x - fw) & 1);
    *h = y - ((y - fh) & 1);
}

#define ORIGINAL_SCENE_EVERY 16
#define MENU_SCENE_EVERY 4 // on a menu screen (menu_screen): the way back to the field goes through a fade longer than this

// The frame that will be shown: with mGBA's renderer on its own thread (New 3DS) the last lines may still be coming,
// and getPixels is what waits for them. EXPAND's margins are worked out meanwhile.
static void frame_whole(void* ctx) {
    struct pd_session* s = ctx;
    if (!s->framePending) return;
    s->framePending = false;
    size_t stride;
    const void* pixels;
    uint64_t start = s->files.clock ? s->files.clock() : 0;
    s->core->getPixels(s->core, &pixels, &stride);
    if (s->files.clock) s->prof.wait += s->files.clock() - start;
}

// FREE FLIGHT's clouds over EXPAND's margins (the frame has its own: pk_flight_draw), planned with the view and drawn
// once the picture is whole - after pk_wide_finish, on whichever thread that runs.
static struct PkFlightClouds wideClouds;
static uint32_t* wideCloudsOut;
static int wideCloudsW, wideCloudsH;

void pd_session_wide_finish(void) {
    pk_wide_finish();
    if (!wideCloudsOut) return;
    pk_flight_clouds_margins(&wideClouds, wideCloudsOut, wideCloudsW, wideCloudsH);
    wideCloudsOut = NULL;
}

static void wide_tick(struct pd_session* s, bool skip) {
    s->wideReady = false;
    if (!s->wideKnown) return;
    pk_wide_lines(s->settings->aspect != PD_ASPECT_ORIGINAL);
    // Nothing past the picture, so only the scene is wanted (FLY's "on the field"): a look every few frames. Every frame
    // took 7 ms of a New 3DS's 16.7, and with the game's own 9 that's one refresh too many.
    bool original = s->settings->aspect == PD_ASPECT_ORIGINAL;
    if (original && s->frame % ORIGINAL_SCENE_EVERY) return;
    bool menu = !original && menu_screen(s);
    s->menuLatched = menu;
    if (menu && s->frame % MENU_SCENE_EVERY) return;
    int fw = pk_wide_frame_w(), fh = pk_wide_frame_h();
    // A GBA frame is already the rows EXPAND reads; a Game Boy's is narrower than the buffer's.
    const uint32_t* source = s->video;
    if (fw != PD_GAME_STRIDE) {
        frame_whole(s);
        for (int y = 0; y < fh; y++) memcpy(wideSource + y * fw, s->video + y * PD_GAME_STRIDE, (size_t) fw * 4);
        source = wideSource;
    }
    if (skip) {
        pk_wide_skip(s->core, source);
        return;
    }
    if (original || menu) {
        // A one-pixel window.
        static uint32_t px;
        pk_wide_view(s->core, source, &px, (struct PkWideView) { 0, 0, 1, 1 }, NULL);
        pk_wide_finish();
        return;
    }
    // A flight's controls are held (the map's sign as it crosses): no pull-in for that. The scenic FLY is shown zoomed out.
    bool flying = s->flightState != PK_FLIGHT_IDLE;
    int aspect = s->settings->aspect == PD_ASPECT_ZOOM_OUT && (in_battle(s) || (s->settings->autoZoom && !flying &&
        pk_wide_scene() != PK_WIDE_SCENE_FIELD)) ? PD_ASPECT_EXPAND : s->settings->aspect;
    if (s->scenic && flying && aspect == PD_ASPECT_EXPAND) aspect = PD_ASPECT_ZOOM_OUT;
    wide_window(s, aspect, &s->wideW, &s->wideH);
    struct PkWideView v = { (fw - s->wideW) / 2, (fh - s->wideH) / 2, s->wideW, s->wideH };
    wideShown ^= 1;
    pk_wide_frame_wait(frame_whole, s);
    pk_wide_view(s->core, source, wideFrames[wideShown], v, NULL);
    pk_wide_frame_wait(NULL, NULL);
    frame_whole(s);
    // (pk_wide_view joined any finish still running: the plan is free to change.)
    wideCloudsOut = NULL;
    if (s->flightCfg) {
        pk_flight_clouds_plan(&wideClouds, v.x, v.y, fw, fh);
        if (wideClouds.strength > 0) {
            wideCloudsOut = wideFrames[wideShown];
            wideCloudsW = v.w;
            wideCloudsH = v.h;
        }
    }
    if (pk_wide_due() && s->files.wideGo) s->files.wideGo();
    else pd_session_wide_finish();
    s->wideReady = !pk_wide_fault();
    s->wideHole = (struct pd_wide_hole) { 0 };
    if (pk_wide_holed()) {
        // FREE FLIGHT's smooth camera moved the view (pk_wide_view adds its offset): the frame's place moved with it.
        int fdx = 0, fdy = 0;
        pk_wide_flight_offset(&fdx, &fdy);
        v.x += fdx;
        v.y += fdy;
        struct pd_wide_hole* h = &s->wideHole;
        h->x = v.x < 0 ? -v.x : 0;
        h->y = v.y < 0 ? -v.y : 0;
        h->gameX = v.x > 0 ? v.x : 0;
        h->gameY = v.y > 0 ? v.y : 0;
        h->w = fw - h->gameX < v.w - h->x ? fw - h->gameX : v.w - h->x;
        h->h = fh - h->gameY < v.h - h->y ? fh - h->gameY : v.h - h->y;
    }
}

void pd_session_set_video(struct pd_session* s, const uint32_t* video) {
    if (video != s->video) s->nextVideo = video;
}

void pd_session_run_frame(struct pd_session* s, uint32_t playerKeys, bool last) {
    if (s->nextVideo) {
        s->core->setVideoBuffer(s->core, (color_t*) s->nextVideo, PD_GAME_STRIDE);
        s->video = s->nextVideo;
        s->nextVideo = NULL;
    }
    bool exclusive = false;
    uint32_t script = pd_binput_tick(&s->input, &exclusive);
    uint32_t keys = exclusive ? script : (playerKeys | script);
    // AUTO-RUN: B flipped while walking on the player's own field (holding it walks) - never in a script, a battle, a
    // menu or on a bike (the Acro Bike's wheelie is B).
    if (s->settings->autoRun && !exclusive && (keys & DIRECTIONS) && !(keys & PD_KEY_A) && (s->fieldFlags & PK_FIELD_KNOWN) &&
        !(s->fieldFlags & AUTO_RUN_BLOCKERS) && (!s->wideKnown || pk_wide_scene() == PK_WIDE_SCENE_FIELD))
        keys ^= PD_KEY_B;
    if (s->gameKeyFrames > 0) {
        keys |= s->gameKeys;
        s->gameKeyFrames--;
    }
    s->core->setKeys(s->core, keys);
    // Fast-forward's frames that aren't shown: armGBA needn't draw them.
    if (pd_armgba_is(s->core)) pd_armgba_skip_video(!last);
    uint64_t start = s->files.clock ? s->files.clock() : 0;
    s->core->runFrame(s->core);
    uint64_t ran = s->files.clock ? s->files.clock() : 0;
    s->prof.run += ran - start;
    if (s->game.kind != PD_GAME_GB) pk_wide_after_frame(s->core);
    if (s->game.kind != PD_GAME_GB) {
        pk_rest_note(s->core);
        pd_mixer_frame(s->core);
    }
    if (s->fieldKnown)
        s->fieldFlags = pk_field_state(s->core, s->settings->runIndoors, s->wildSwitch && s->settings->wildOff ? 0 : 100);
    s->framePending = last;
    if (s->flightCfg) {
        // FREE FLIGHT's next step, then its shadow and bird into the finished frame (before EXPAND reads it).
        uint64_t t0 = s->files.clock ? s->files.clock() : 0;
        pk_flight_tick(s->core);
        s->flightState = pk_flight_state();
        uint64_t t1 = s->files.clock ? s->files.clock() : 0;
        s->prof.flightTick += t1 - t0;
        if (last && (s->flightState != PK_FLIGHT_IDLE || s->flightDrawn)) {
            frame_whole(s);
            uint64_t t2 = s->files.clock ? s->files.clock() : 0;
            // The clouds go into the frame itself: the 3DS's EXPAND may show the frame in its middle (its hole), so the
            // margins get theirs apart (pd_session_wide_finish), the frame's rectangle left alone.
            pk_flight_draw((uint32_t*) s->video, PD_GAME_STRIDE, 160, PD_GAME_STRIDE, 1);
            s->prof.flightDraw += (s->files.clock ? s->files.clock() : 0) - t2;
            s->flightDrawn = s->flightState != PK_FLIGHT_IDLE;
        }
    }
    uint64_t wideStart = s->files.clock ? s->files.clock() : 0;
    wide_tick(s, !last);
    frame_whole(s);
    uint64_t wideEnd = s->files.clock ? s->files.clock() : 0;
    s->prof.wide += wideEnd - wideStart;
    s->frame++;
    // What the battle waits for is read every frame: the touch controls follow it at once.
    bool stateChanged = s->input.state != s->shown.battleState;
    // BOXES reads only while it's open, or a DEPOSIT wants the boxes' names.
    if (s->ui.boxes && (s->ui.tab == PD_TAB_BOXES || s->ui.overlay == PD_OVERLAY_PICK_BOX) &&
        (s->frame % 30 == 0 || s->ui.boxShown != s->boxes.shown)) {
        static struct pd_boxes before;
        before = s->boxes;
        pd_boxes_read(&s->boxes, &s->game, pd_session_read, s, s->ui.boxShown);
        if (memcmp(&before, &s->boxes, sizeof(before))) s->redraw = true;
    }
    if ((stateChanged || s->frame % PD_SNAPSHOT_EVERY == 0) && (s->game.kind == PD_GAME_GB || (s->ewram && s->iwram))) {
        pd_snapshot_read(&s->snap, &s->game, pd_session_read, s);
        pd_ui_update(&s->ui, &s->snap);
        // Only a change the companion shows is worth a redraw (not the frame counter).
        s->snap.frame = 0;
        if (memcmp(&s->snap, &s->shown, sizeof(s->snap))) {
            // A step moves the player and the game's step counters, which only MAP and GUIDE show: repainting the tab for
            // each cost walking 8% of a second on the emulator's own core.
            static struct pd_snapshot seen;
            seen = s->snap;
            if (s->ui.tab != PD_TAB_MAP) {
                seen.x = s->shown.x;
                seen.y = s->shown.y;
            }
            if (s->ui.tab != PD_TAB_GUIDE) {
                memcpy(seen.flags, s->shown.flags, sizeof(seen.flags));
                memcpy(seen.vars, s->shown.vars, sizeof(seen.vars));
                memcpy(seen.bankFlags, s->shown.bankFlags, sizeof(seen.bankFlags));
                memcpy(seen.bankVars, s->shown.bankVars, sizeof(seen.bankVars));
            }
            if (s->ui.tab != PD_TAB_CARD) memcpy(seen.gameStats, s->shown.gameStats, sizeof(seen.gameStats));
            if (memcmp(&seen, &s->shown, sizeof(seen))) s->redraw = true;
            s->shown = s->snap;
        }
        if (s->files.clock) s->prof.snap += s->files.clock() - wideEnd;
    }
}

// In the app's order (QuickAction): PC, FLY, REPEL, BIKE, WILD, SAVE / LOAD STATE, FF, COMPANION; each only where the
// game can, dimmed with why when not now.
static void quick_items(struct pd_session* s) {
    const struct pd_snapshot* snap = &s->shown;
    s->host.flyReady = s->flyCfg && fly_flyer(snap) >= 0;
    s->host.canTake = pd_items_can_take();
    const struct pd_config* cfg = s->game.cfg;
    struct quick_item* q = s->quick;
    int n = 0;
    if (s->pcOk || s->gbField) q[n++] = (struct quick_item) { QUICK_PC, false, false, NULL, 0, 0, NULL };
    if (s->gbField) q[n++] = (struct quick_item) { QUICK_FLY, false, false, NULL, 0, 0, NULL };
    if (s->flyCfg) {
        int flyer = fly_flyer(snap);
        q[n++] = (struct quick_item) { QUICK_FLY, false, false, flyer < 0 ? "No one in your party knows FLY" : NULL,
                                       flyer < 0 ? 0 : snap->party[flyer].species, 0, NULL };
    }
    s->host.flightReady = s->flightCfg && fly_flyer(snap) >= 0;
    s->host.flying = s->flightState != PK_FLIGHT_IDLE;
    if (s->flightCfg) {
        // TAKE OFF (QuickMenu.kt's quickBlocked): up there LAND always can, the bird finds the nearest ground.
        bool flying = s->flightState != PK_FLIGHT_IDLE;
        const char* why = flying ? NULL : snap->inBattle ? "Not during a battle"
                        : fly_flyer(snap) < 0 ? "No one in your party knows FLY" : NULL;
        q[n++] = (struct quick_item) { flying ? QUICK_LAND : QUICK_FLIGHT, false, false, why, 0, 0, NULL };
    }
    if (s->flashCfg) {
        // FLASH (QuickMenu.kt's quickBlocked): who knows it, then whether it's dark here at all.
        bool flasher = flash_flasher(snap) >= 0;
        int dark = flash_dark(s->core, s->flashCfg, snap);
        q[n++] = (struct quick_item) { QUICK_FLASH, false, false,
                                       !flasher ? flash_reason(FLASH_NO_FLASHER) : dark == 0 ? flash_reason(FLASH_CANT_USE_HERE) : NULL,
                                       0, 0, NULL };
    }
    if (s->itemsOk) {
        s->repelKindCount = 0;
        for (int k = 0; k < REPEL_KINDS; k++)
            if (bag_count(snap, repel_kinds[k][0]) > 0) s->repelKinds[s->repelKindCount++] = repel_kinds[k][0];
        int left = repel_steps(s->core, cfg);
        if (left) snprintf(s->repelWhy, sizeof(s->repelWhy), "%d steps left", left);
        q[n++] = (struct quick_item) { QUICK_REPEL, false, false,
                                       left ? s->repelWhy : s->repelKindCount ? NULL : "No REPEL in your bag", 0,
                                       s->repelKindCount ? s->repelKinds[s->repelKindCount - 1] : repel_kinds[0][0], NULL };
    }
    if (s->bikeOk) {
        int bike = bag_bike(snap);
        bool riding = (s->fieldFlags & PK_FIELD_AVATAR) && (s->fieldFlags & PK_FIELD_BIKE);
        q[n++] = (struct quick_item) { QUICK_BIKE, false, false, bike ? NULL : "No bike in your bag", 0,
                                       bike ? bike : 360, riding ? "GET OFF" : NULL };
    }
    q[n++] = (struct quick_item) { QUICK_WILD, s->settings->wildOff, false, s->wildSwitch || s->gbField ? NULL : "Not in this game", 0, 0, NULL };
    q[n++] = (struct quick_item) { QUICK_SAVE_STATE, false, false, NULL, 0, 0, NULL };
    q[n++] = (struct quick_item) { QUICK_LOAD_STATE, false, false,
                                   s->host.slots[s->host.slotSelected].used ? NULL : "No state saved yet", 0, 0, NULL };
    q[n++] = (struct quick_item) { QUICK_FF, false, s->ff, NULL, 0, 0, NULL };
    s->quickCount = n;
    if (s->quickSel >= n) s->quickSel = 0;
}

static void start_job(struct pd_session* s, enum pd_job job, int item) {
    s->job = job;
    s->jobItem = item;
    s->jobFrames = 0;
    s->jobTries = BIKE_TRIES;
}

// A Game Boy game off its plain field (a menu, a battle, a text box): the host's own "busy" for gb_field.
static bool gb_busy(const struct pd_session* s) {
    return s->shown.inBattle || (s->wideKnown && pk_wide_scene() != PK_WIDE_SCENE_FIELD);
}

static void quick_run(struct pd_session* s, const struct quick_item* item) {
    s->quickOpen = false;
    switch (item->action) {
    case QUICK_PC:
        if (s->gbField) {
            if (gb_field_pc(s->core, s->gbField, gb_busy(s)) == ITEM_USED) {
                s->job = PD_JOB_GB_TAKEN;
                s->jobFrames = 0;
            } else {
                pd_session_show(s, "You can't do that now.");
            }
            break;
        }
        start_job(s, PD_JOB_PC, 0);
        break;
    case QUICK_REPEL:
        start_job(s, PD_JOB_REPEL, s->repelKinds[0]);
        break;
    case QUICK_BIKE:
        start_job(s, PD_JOB_BIKE, bag_bike(&s->shown));
        break;
    case QUICK_WILD:
        s->settings->wildOff = !s->settings->wildOff;
        if (s->files.settingsChanged) s->files.settingsChanged(s->files.ctx);
        pd_session_show(s, s->settings->wildOff ? "Wild encounters off" : "Wild encounters on");
        break;
    case QUICK_SAVE_STATE:
        pd_session_show(s, pd_session_save_state(s, s->host.slotSelected) ? "State saved" : "State save failed");
        break;
    case QUICK_LOAD_STATE:
        pd_session_show(s, pd_session_load_state(s, s->host.slotSelected) ? "State loaded" : "State load failed");
        break;
    case QUICK_FF:
        s->ffToggled = !s->ffToggled;
        break;
    default:
        break;
    }
}

static void quick_input(struct pd_session* s, const struct pd_input* in) {
    uint32_t down = in->down;
    if (s->repelList) {
        // REPEL's kinds in the bag: UP / DOWN, A uses one, B back to the ring.
        int n = s->repelKindCount;
        if (down & PD_KEY_UP) s->repelSel = (s->repelSel + n - 1) % n;
        if (down & PD_KEY_DOWN) s->repelSel = (s->repelSel + 1) % n;
        if (down & PD_KEY_B) {
            s->repelList = false;
        } else if (down & PD_KEY_A) {
            s->quickOpen = s->repelList = false;
            start_job(s, PD_JOB_REPEL, s->repelKinds[s->repelSel]);
        }
        return;
    }
    if (s->flyList) {
        // FLY's places: UP / DOWN, A flies, B back to the ring.
        int n = s->flyTargets.count;
        if ((down & PD_KEY_UP) && n) s->flySel = (s->flySel + n - 1) % n;
        if ((down & PD_KEY_DOWN) && n) s->flySel = (s->flySel + 1) % n;
        if (down & PD_KEY_B) {
            s->flyList = false;
        } else if ((down & PD_KEY_A) && n) {
            s->quickOpen = s->flyList = false;
            fly_to(s, s->flyTargets.towns[s->flySel]);
        }
        return;
    }
    if (down & PD_KEY_B) {
        s->quickOpen = false;
        return;
    }
    int dx = !!(down & PD_KEY_RIGHT) - !!(down & PD_KEY_LEFT), dy = !!(down & PD_KEY_DOWN) - !!(down & PD_KEY_UP);
    if (dx || dy) s->quickSel = quick_step(s->quickCount, s->quickSel, dx, dy, PD_TOP_W, PD_TOP_H);
    if (in->stickAngle >= 0 && s->quickCount > 0)
        s->quickSel = (in->stickAngle * s->quickCount + 180) / 360 % s->quickCount;
    const struct quick_item* item = &s->quick[s->quickSel];
    if (!(down & PD_KEY_A) || item->why) return;
    if (item->action == QUICK_FLASH) {
        s->quickOpen = false;
        s->flashAsk = 1; // run between two frames
    } else if (item->action == QUICK_FLIGHT || item->action == QUICK_LAND) {
        s->quickOpen = false;
        s->flightAsk = 1;
    } else if (item->action == QUICK_FLY) {
        s->flyAsk = 1; // answered between two frames
    } else if (item->action == QUICK_REPEL && s->repelKindCount > 1) {
        // More than one kind: their list (QuickMenu.kt's RepelKinds), each with how many and its steps.
        for (int k = 0; k < s->repelKindCount; k++) {
            int steps = 0;
            for (int r = 0; r < REPEL_KINDS; r++)
                if (repel_kinds[r][0] == s->repelKinds[k]) steps = repel_kinds[r][1];
            snprintf(s->repelRows[k], sizeof(s->repelRows[k]), "%s x%d  %d steps", pd_item_name(&s->game, s->repelKinds[k]),
                     bag_count(&s->shown, s->repelKinds[k]), steps);
            s->repelNames[k] = s->repelRows[k];
        }
        s->repelList = true;
        s->repelSel = s->repelKindCount - 1;
    } else {
        quick_run(s, item);
    }
}

// FLY to [mapsec]: the game's own (FLY STYLE CLASSIC, or a game without FREE FLIGHT), else up on the bird and over the
// field to it (the scenic FLY), routed between two frames.
static void fly_to(struct pd_session* s, int mapsec) {
    s->flyMapsec = mapsec;
    if (s->flightCfg && !s->settings->flyClassic && s->game.kind != PD_GAME_GB) {
        s->scenicMapsec = mapsec;
        s->scenicAsk = 1;
    } else {
        s->flyStage = 1;
        s->flyWait = 0;
    }
}

// FLIGHT AREA (FlightArea.Keeper.tick): the save checked, seeded once a run, the player's map added while they stand on the
// field, pk_flight given the maps when they changed. [now]: before a take-off, not just every half second.
static void flight_area_job(struct pd_session* s, bool busy, bool battle, bool now) {
    struct mCore* c = s->core;
    struct flight_area* a = &s->flightArea;
    if (s->settings->flightAnywhere) {
        if (!s->flightAreaAnywhere) pk_flight_set_area(NULL, 0);
        s->flightAreaAnywhere = true;
        return;
    }
    if (s->flightAreaAnywhere) a->sent = -1;
    s->flightAreaAnywhere = false;
    if (!now && s->frame % 30) return;
    uint32_t key = flight_save_key(c, s->flightCfg);
    if (!key) return;
    if (key != a->key) flight_area_load(s, key);
    if (!a->seeded && pk_call_safe(c)) flight_area_seed(a, c, s->flightCfg, s->rom, s->romSize);
    struct flight_spot here;
    if (!busy && !battle && s->flightState == PK_FLIGHT_IDLE && flight_here(c, s->flightCfg, &here)) flight_area_add(a, here.map);
    if (a->count != a->sent) {
        pk_flight_set_area(a->bits, PK_FLIGHT_AREA_BYTES);
        a->sent = a->count;
    }
    flight_area_write(s);
}

// TAKE OFF / LAND and the scenic FLY, between two frames (PokeDaisyActivity's toggleFlight / scenicFly).
static void flight_jobs(struct pd_session* s, bool busy, bool battle) {
    struct mCore* c = s->core;
    const struct pd_config* cfg = s->flightCfg;
    if (s->settings->flightVehicle != s->flightVehicle) flight_configure(s);
    flight_area_job(s, busy, battle, false);
    // The bird turned at a map not seen yet: say why, now and then (it can be met many times a second).
    if (pk_flight_area_turned() && (unsigned) (s->frame - s->flightAreaTold) > 240) {
        s->flightAreaTold = s->frame;
        pd_session_show(s, "You haven't been there yet.");
    }
    if (s->scenic && s->flightState == PK_FLIGHT_IDLE) s->scenic = false;
    if (s->flightAsk && s->flightState != PK_FLIGHT_IDLE) {
        s->flightAsk = 0;
        pk_flight_land();
    } else if (s->flightAsk || s->scenicAsk) {
        if (!pk_call_safe(c)) {
            if ((s->flightAsk && ++s->flightAsk > 120) || (s->scenicAsk && ++s->scenicAsk > 120)) {
                s->flightAsk = s->scenicAsk = 0;
                pd_session_show(s, fly_reason(FLY_FAILED));
            }
            return;
        }
        bool scenic = s->scenicAsk != 0;
        s->flightAsk = s->scenicAsk = 0;
        pd_snapshot_read(&s->snap, &s->game, pd_session_read, s);
        enum fly_result gate = flight_check(c, cfg, s->rom, s->romSize, &s->snap, busy, battle);
        if (gate != FLY_OK) {
            pd_session_show(s, fly_reason(gate));
            return;
        }
        flight_area_job(s, busy, battle, true);
        static uint8_t route[FLIGHT_MAX_STEPS];
        int steps = -1;
        if (scenic) {
            // FLIGHT AREA's maps only (none known yet: anywhere); a town past them, or not joined to here, flies the classic way.
            const struct flight_area* area = !s->settings->flightAnywhere && s->flightArea.key ? &s->flightArea : NULL;
            struct flight_spot here, dest;
            if (flight_here(c, cfg, &here) && flight_destination(c, cfg, s->rom, s->romSize, s->scenicMapsec, &dest))
                steps = flight_route(c, cfg, s->rom, s->romSize, &here, &dest, area, route);
            if (steps < 0) {
                s->flyStage = 1;
                s->flyWait = 0;
                return;
            }
        }
        flight_configure(s);
        int code = pk_flight_take_off(c);
        if (code == PK_FLIGHT_OK && scenic) {
            pk_flight_route(route, steps);
            s->scenic = true;
        } else if (code == PK_FLIGHT_NOT_ON_FOOT && scenic) {
            // Surfing: the bird can't take off from the water - the game's own FLY can.
            s->flyStage = 1;
            s->flyWait = 0;
        } else if (code != PK_FLIGHT_OK) {
            pd_session_show(s, flight_reason(code));
        }
    }
}

// FLY's steps and the field jobs, each between two frames the game's own code can be called in (pk_call_safe).
static void gb_jobs(struct pd_session* s) {
    const struct gb_field* f = s->gbField;
    if (s->settings->wildOff) {
        gb_field_hold_wild(s->core, f);
        s->gbWildHeld = true;
    } else if (s->gbWildHeld && gb_field_release_wild(s->core, f, s->rom, s->romSize)) {
        s->gbWildHeld = false;
    }
    if (s->flyAsk && s->quickOpen) {
        s->flyAsk = 0;
        gb_field_fly_targets(s->core, f, s->rom, s->romSize, gb_busy(s), &s->flyTargets);
        if (s->flyTargets.result == FLY_OK && s->flyTargets.count) {
            for (int i = 0; i < s->flyTargets.count; i++) {
                const char* name = pd_mapsec_name(&s->game, s->flyTargets.towns[i]);
                s->flyNames[i] = name && *name ? name : "?";
            }
            s->flySel = 0;
            s->flyList = true;
        } else {
            s->quickOpen = false;
            pd_session_show(s, s->flyTargets.result == FLY_OK ? "You haven't been anywhere to FLY to yet." : fly_reason(s->flyTargets.result));
        }
    }
    if (s->flyStage == 1) {
        s->flyStage = 0;
        enum fly_result r = gb_field_fly(s->core, f, s->rom, s->romSize, s->flyMapsec, gb_busy(s));
        if (r == FLY_OK) {
            s->job = PD_JOB_GB_TAKEN;
            s->jobFrames = 0;
        } else {
            pd_session_show(s, fly_reason(r));
        }
    }
    if (s->job == PD_JOB_GB_TAKEN && ++s->jobFrames >= GB_FIELD_TAKE_FRAMES) {
        s->job = PD_JOB_NONE;
        if (!gb_field_taken(s->core, f)) pd_session_show(s, "You can't do that now.");
    }
}

// The TM screen's second step, once the fade has reached black (TmTeach.kt's tmFinish).
static void tm_jobs(struct pd_session* s) {
    if (!s->tmCfg || s->tmStage != 1 || ++s->tmWait < TM_FADE_FRAMES || !pk_call_safe(s->core)) return;
    s->tmStage = 0;
    bool left = tm_main_callback(s->core, s->tmCfg) != s->tmCallback;
    if (!tm_finish(s->core, s->tmCfg, s->tmItem, left) && !left) pd_session_show(s, tm_reason(TM_FAILED));
}

static void field_jobs(struct pd_session* s) {
    tm_jobs(s);
    if (s->gbField) {
        gb_jobs(s);
        return;
    }
    struct mCore* c = s->core;
    const struct pd_config* cfg = s->game.cfg;
    bool battle = s->shown.inBattle || (s->fieldFlags & PK_FIELD_BATTLE);
    bool busy = (s->fieldFlags & PK_FIELD_LOCKED) || (s->wideKnown && pk_wide_scene() != PK_WIDE_SCENE_FIELD);
    if (s->flightCfg) flight_jobs(s, busy, battle);
    if (s->flashCfg && s->flashAsk) {
        if (pk_call_safe(c)) {
            pd_snapshot_read(&s->snap, &s->game, pd_session_read, s);
            enum flash_result r = flash_use(c, s->flashCfg, s->rom, s->romSize, &s->snap, busy, battle);
            s->flashAsk = 0;
            if (r != FLASH_OK) pd_session_show(s, flash_reason(r));
        } else if (++s->flashAsk > 120) {
            s->flashAsk = 0;
            pd_session_show(s, flash_reason(FLASH_FAILED));
        }
    }
    if (s->flyCfg && s->flyAsk && (s->quickOpen || s->flyFromMap)) {
        if (pk_call_safe(c)) {
            pd_snapshot_read(&s->snap, &s->game, pd_session_read, s);
            fly_targets(c, s->flyCfg, s->rom, s->romSize, &s->snap, busy, battle, &s->flyTargets);
            s->flyAsk = 0;
            if (s->flyTargets.result == FLY_OK && s->flyTargets.count) {
                for (int i = 0; i < s->flyTargets.count; i++) {
                    const char* name = pd_mapsec_name(&s->game, s->flyTargets.towns[i]);
                    s->flyNames[i] = name && *name ? name : "?";
                }
                s->flySel = 0;
                if (s->flyFromMap) {
                    s->ui.flyNames = s->flyNames;
                    s->ui.flyCount = s->flyTargets.count;
                    s->ui.overlay = PD_OVERLAY_FLY;
                    s->ui.placesScroll = 0;
                    s->redraw = true;
                } else {
                    s->flyList = true;
                }
            } else {
                s->quickOpen = false;
                pd_session_show(s, s->flyTargets.result == FLY_OK ? "You haven't been anywhere to FLY to yet."
                                                                  : fly_reason(s->flyTargets.result));
            }
        } else if (++s->flyAsk > 120) {
            s->flyAsk = 0;
            s->quickOpen = false;
            pd_session_show(s, fly_reason(FLY_FAILED));
        }
    }
    if (s->flyCfg && s->flyStage == 1 && pk_call_safe(c)) {
        pd_snapshot_read(&s->snap, &s->game, pd_session_read, s);
        enum fly_result r = fly_start(c, s->flyCfg, s->rom, s->romSize, &s->snap, s->flyMapsec, &s->flyTargets, busy, battle);
        if (r == FLY_OK) {
            s->flyStage = 2;
            s->flyWait = 0;
            s->flyCallback = fly_main_callback(c, s->flyCfg);
        } else {
            s->flyStage = 0;
            pd_session_show(s, fly_reason(r));
        }
    } else if (s->flyCfg && s->flyStage == 2 && ++s->flyWait >= FLY_FADE_FRAMES && pk_call_safe(c)) {
        s->flyStage = 0;
        if (!fly_finish(c, s->flyCfg, s->rom, s->romSize, s->flyMapsec, &s->flyTargets,
                        fly_main_callback(c, s->flyCfg) != s->flyCallback))
            pd_session_show(s, fly_reason(FLY_FAILED));
    }
    bool safe = s->job != PD_JOB_NONE && pk_call_safe(c);
    if (s->job == PD_JOB_PC && safe) {
        s->job = PD_JOB_NONE;
        enum item_result r = pc_open(c, cfg, s->rom, s->romSize, busy, battle);
        if (r != ITEM_USED) pd_session_show(s, item_reason(r));
    } else if (s->job == PD_JOB_REPEL && safe) {
        s->job = PD_JOB_NONE;
        int steps = 0;
        enum item_result r = repel_use(c, cfg, s->rom, s->romSize, s->jobItem, busy, battle, &steps);
        if (r == ITEM_USED) {
            char msg[48];
            snprintf(msg, sizeof(msg), "Used! It lasts %d steps.", steps);
            pd_session_show(s, msg);
        } else {
            pd_session_show(s, item_reason(r));
        }
    } else if (s->job == PD_JOB_BIKE && safe) {
        enum item_result r = bike_register(c, cfg, s->rom, s->romSize, s->jobItem, busy, battle, &s->bikeOld);
        if (r == ITEM_USED) {
            s->gameKeys = PD_KEY_SELECT;
            s->gameKeyFrames = 2;
            s->bikeWasRiding = s->fieldFlags & PK_FIELD_BIKE;
            s->bikeTook = false;
            s->job = PD_JOB_BIKE_WAIT;
            s->jobFrames = 0;
        } else {
            s->job = PD_JOB_NONE;
            pd_session_show(s, item_reason(r));
        }
    } else if (s->job == PD_JOB_BIKE_WAIT) {
        // SELECT again if the game dropped it (a bike rolling at full speed ignores it), but only where taking it
        // shows for sure: the riding state flips, or the field's controls stay held (PokeDaisyActivity.pressSelect).
        if (((s->fieldFlags & PK_FIELD_BIKE) != 0) != s->bikeWasRiding || (s->fieldFlags & PK_FIELD_LOCKED)) s->bikeTook = true;
        if (++s->jobFrames >= 10) {
            if (s->bikeTook || !(s->fieldFlags & PK_FIELD_AVATAR) || --s->jobTries <= 0) {
                s->job = PD_JOB_BIKE_RESTORE;
            } else {
                s->gameKeys = PD_KEY_SELECT;
                s->gameKeyFrames = 2;
            }
            s->jobFrames = 0;
        }
    } else if (s->job == PD_JOB_BIKE_RESTORE && ++s->jobFrames >= 20) {
        s->job = PD_JOB_NONE;
        bike_restore(c, cfg, s->jobItem, s->bikeOld);
    } else if ((s->job == PD_JOB_PC || s->job == PD_JOB_REPEL || s->job == PD_JOB_BIKE) && ++s->jobFrames > 120) {
        s->job = PD_JOB_NONE;
        pd_session_show(s, item_reason(ITEM_FAILED));
    }
}

// USE / GIVE / TAKE's steps (the companion asked between loops).
static void item_jobs(struct pd_session* s) {
    if (!s->itemAsk || !pk_call_safe(s->core)) return;
    int ask = s->itemAsk;
    s->itemAsk = 0;
    struct pd_ui* ui = &s->ui;
    const struct pd_config* cfg = s->game.cfg;
    bool battle = s->shown.inBattle || (s->fieldFlags & PK_FIELD_BATTLE);
    bool busy = battle || (s->wideKnown && pk_wide_scene() != PK_WIDE_SCENE_FIELD);
    if (ask == 1 && !ui->itemGive && s->tmCfg && tm_teaches(s->tmCfg, ui->itemId)) {
        // A TM / HM: the game's own TM screen, the way its bag opens it (two steps: the fade, then the screen).
        enum tm_result r = tm_start(s->core, s->tmCfg, ui->itemId, busy || (s->fieldFlags & PK_FIELD_LOCKED));
        if (r == TM_STARTED) {
            s->tmStage = 1;
            s->tmWait = 0;
            s->tmItem = ui->itemId;
            s->tmCallback = tm_main_callback(s->core, s->tmCfg);
        } else {
            pd_session_show(s, tm_reason(r));
        }
    } else if (ask == 1) {
        pd_items_targets(s->core, cfg, ui->itemId, ui->itemGive, busy, &s->itemTargets);
        if (s->itemTargets.result != PD_ITEM_USED) {
            pd_session_show(s, pd_item_outcome_text(s->itemTargets.result));
            return;
        }
        for (int i = 0; i < s->itemTargets.count; i++) {
            const struct pd_party_target* t = &s->itemTargets.slots[i];
            ui->itemSlots[i].ok = t->verdict == PD_SLOT_OK;
            ui->itemSlots[i].heldItem = t->heldItem;
            memcpy(ui->itemSlots[i].moves, t->moves, sizeof(t->moves));
        }
        ui->itemPicking = true;
        ui->itemMoveSlot = -1;
    } else if (ask == 2) {
        const struct pd_party_target* t = &s->itemTargets.slots[ui->itemSlot];
        int swapped = 0;
        enum pd_item_outcome r = ui->itemGive
            ? pd_items_give(s->core, cfg, ui->itemId, ui->itemSlot, t->personality, busy, &swapped)
            : pd_items_use(s->core, cfg, ui->itemId, ui->itemSlot, t->personality, ui->itemMove, busy);
        pd_session_show(s, pd_item_outcome_text(r));
    } else {
        int held = 0;
        const struct pd_mon* m = &s->shown.party[ui->summarySlot >= 0 ? ui->summarySlot : 0];
        pd_session_show(s, pd_item_outcome_text(pd_items_take(s->core, cfg, ui->summarySlot, m->personality, busy, &held)));
    }
    s->redraw = true;
}

// The PC's moves, between frames. The app's messages (BoxesScreen.kt).
static void box_jobs(struct pd_session* s) {
    if (!s->boxAsk || !pk_call_safe(s->core)) return;
    int ask = s->boxAsk;
    s->boxAsk = 0;
    struct pd_ui* ui = &s->ui;
    const struct pd_config* cfg = s->game.cfg;
    bool battle = s->shown.inBattle || (s->fieldFlags & PK_FIELD_BATTLE);
    bool busy = battle || (s->wideKnown && pk_wide_scene() != PK_WIDE_SCENE_FIELD);
    const struct pd_mon* boxMon = ui->boxSlot >= 0 ? &s->boxes.mons[ui->boxSlot] : NULL;
    const struct pd_mon* partyMon = ui->boxPartySlot >= 0 && ui->boxPartySlot < s->shown.partyCount ? &s->shown.party[ui->boxPartySlot] : NULL;
    char boxName[32], partyName[32];
    *boxName = *partyName = 0;
    if (boxMon) {
        if (*boxMon->nickname) snprintf(boxName, sizeof(boxName), "%s", boxMon->nickname);
        else snprintf(boxName, sizeof(boxName), "%s", pd_species_name(&s->game, boxMon->species, partyName, sizeof(partyName)));
    }
    if (partyMon) {
        if (*partyMon->nickname) snprintf(partyName, sizeof(partyName), "%s", partyMon->nickname);
        else {
            char name[32];
            snprintf(partyName, sizeof(partyName), "%s", pd_species_name(&s->game, partyMon->species, name, sizeof(name)));
        }
    }
    enum pd_box_outcome r = PD_BOX_FAILED;
    if (ask == PD_ACTION_BOX_WITHDRAW && boxMon)
        r = pd_box_withdraw(s->core, cfg, s->boxes.shown, ui->boxSlot, boxMon->personality, busy);
    else if (ask == PD_ACTION_BOX_SWAP && boxMon && partyMon)
        r = pd_box_swap(s->core, cfg, s->boxes.shown, ui->boxSlot, boxMon->personality, ui->boxPartySlot, partyMon->personality, busy);
    else if (ask == PD_ACTION_BOX_DEPOSIT && partyMon)
        r = pd_box_deposit(s->core, cfg, ui->boxPartySlot, partyMon->personality, ui->boxTarget, busy);
    const char* where = s->boxes.names[ask == PD_ACTION_BOX_DEPOSIT ? ui->boxTarget : s->boxes.shown];
    if (r != PD_BOX_DONE) snprintf(ui->boxMessage, sizeof(ui->boxMessage), "%s", pd_box_outcome_text(r));
    else if (ask == PD_ACTION_BOX_WITHDRAW)
        snprintf(ui->boxMessage, sizeof(ui->boxMessage), "%s joined the party. Save in the game to keep it.", boxName);
    else if (ask == PD_ACTION_BOX_SWAP)
        snprintf(ui->boxMessage, sizeof(ui->boxMessage), "%s joined the party, %s went to %s. Save to keep it.", boxName, partyName, where);
    else
        snprintf(ui->boxMessage, sizeof(ui->boxMessage), "%s was stored in %s. Save in the game to keep it.", partyName, where);
    if (r == PD_BOX_DONE) {
        ui->boxSummary = -1;
        ui->boxSlot = -1;
        ui->summarySlot = -1;
    }
    pd_boxes_read(&s->boxes, &s->game, pd_session_read, s, ui->boxShown);
    s->redraw = true;
}

static void overlay_clear(void) {
    for (int i = 0; i < PD_TOP_W * PD_TOP_H; i++) overlayPx[i] = QUICK_CLEAR;
}

// What the top screen shows over the game, redrawn only when it changes: the QUICK MENU, else the HUD, and a notice.
// On the field with its controls free: the HUD shows, and the buttons can go to the companion.
static bool on_field(const struct pd_session* s) {
    // A Game Boy game has no field read: EXPAND's scene says it (pk_wide_gb.c).
    if (s->game.kind == PD_GAME_GB) return s->wideKnown && pk_wide_scene() == PK_WIDE_SCENE_FIELD && !s->shown.inBattle;
    return (s->fieldFlags & PK_FIELD_KNOWN) && !(s->fieldFlags & (PK_FIELD_LOCKED | PK_FIELD_BATTLE)) &&
        (!s->wideKnown || pk_wide_scene() == PK_WIDE_SCENE_FIELD) && s->flyStage == 0;
}

bool pd_session_can_focus(const struct pd_session* s) {
    return on_field(s) && !s->quickOpen && !s->shown.inBattle;
}

void pd_session_set_focus(struct pd_session* s, bool on) {
    if (s->ui.keyFocus == on) return;
    s->ui.keyFocus = on;
    s->ui.focusId = -1;
    s->stripeIn = 0;
    s->redraw = true;
}

// CompanionFocusStripe: down the bottom of the game's picture, in the game's own pixels. A dark edge, a shine, a silver
// fall-off, a foot line, and an arrow bobbing down at the companion.
#define STRIPE_ROWS 22
#define STRIPE_SLIDE 11 // frames: its 180 ms
static void focus_stripe(struct pd_canvas* c, int left, int right, int bottom, int height, int gameRows, int bob, int slide) {
    static const int ARROW[9] = { 17, 15, 13, 11, 9, 7, 5, 3, 1 };
#define ROW(r) (bottom - (STRIPE_ROWS - (r)) * height / gameRows + drop)
    int total = STRIPE_ROWS * height / gameRows, drop = total - total * slide / STRIPE_SLIDE, w = right - left, mid = (left + right) / 2;
    for (int r = 0; r < STRIPE_ROWS; r++) {
        uint32_t col = r == 0 ? 0x6C7080 : r == 1 ? 0xFAFAFE : r == STRIPE_ROWS - 1 ? 0x878B98 : 0;
        if (!col) {
            int t = r - 2, n = STRIPE_ROWS - 4;
            uint32_t a = 0xE4E6EC, b = 0xA6AAB6;
            for (int sh = 0; sh <= 16; sh += 8) {
                int ca = (a >> sh) & 0xFF, cb = (b >> sh) & 0xFF;
                col |= (uint32_t) (ca + (cb - ca) * t / n) << sh;
            }
        }
        pd_fill(c, left, ROW(r), w, ROW(r + 1) - ROW(r), col);
    }
    for (int pass = 0; pass < 2; pass++) {
        for (int i = 0; i < 9; i++) {
            int r = 5 + bob + i + (pass ? 0 : 1), half = ARROW[i] * height / gameRows / 2;
            pd_fill(c, mid - half, ROW(r), 2 * half + 1, ROW(r + 1) - ROW(r), pass ? 0x454957 : 0xFFFFFF);
        }
    }
    int below = bottom < PD_TOP_H ? bottom : PD_TOP_H;
    if (drop > 0 && below < PD_TOP_H) pd_fill(c, left, below, w, PD_TOP_H - below, QUICK_CLEAR);
#undef ROW
}

// What the top screen shows over the game, redrawn only when it changes: the QUICK MENU, else the HUD, and a notice.
static void overlay_update(struct pd_session* s) {
    struct pd_canvas c;
    pd_canvas_init(&c, PD_TOP_W, PD_TOP_H, overlayPx);
    bool field = on_field(s);
    s->hudFieldFrames = field ? s->hudFieldFrames + 1 : 0;
    bool hud = !s->quickOpen && s->settings->hud && s->game.cfg && s->hudFieldFrames >= HUD_AFTER_FRAMES;
    if (hud) {
        int steps = repel_steps(s->core, s->game.cfg);
        if (steps <= 0) s->hud.repelFull = 0;
        else if (!s->hud.repelFull || steps > s->hud.repelFull) s->hud.repelFull = hud_repel_full(steps);
        s->hud.repel = steps > 0 ? steps : 0;
    }
    // The game's picture: the HUD keeps to it (FieldHud(frame)), so a change of ASPECT or SCREEN moves the HUD.
    unsigned gw = 240, gh = 160;
    s->core->desiredVideoDimensions(s->core, &gw, &gh);
    int mode = pd_session_screen(s);
    bool wide = s->wideKnown && s->settings->aspect != PD_ASPECT_ORIGINAL, pixel = mode == PD_SCREEN_PIXEL;
    bool mid = mode == PD_SCREEN_SHARP_125;
    int picture = mode == PD_SCREEN_STRETCH || wide ? PD_TOP_W : pixel ? (int) gw : mid ? (int) gw * 5 / 4 : (int) gw * PD_TOP_H / (int) gh;
    int pictureH = wide ? PD_TOP_H : pixel ? (int) gh : mid ? (int) gh * 5 / 4 : PD_TOP_H;
    int gameRows = wide ? (pixel ? PD_TOP_H : mid ? PD_TOP_H * 4 / 5 : 160) : (int) gh;
    s->perfRight = (PD_TOP_W + picture) / 2 - 3;
    if (hud && !(s->settings->hudHidden & 1 << PD_HUD_MAP)) s->perfRight -= hud_compact_map_width() + 3;
    // The arrow's bob, frame by frame (BOB_MS' keyframes).
    static const uint8_t BOB[58] = { 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 2, 2, 2, 2, 2,
                                     1, 1, 1, 1, 1, 0 };
    int bob = BOB[s->frame % 58];
    if (s->ui.keyFocus && s->stripeIn < STRIPE_SLIDE) s->stripeIn++;
    uint32_t key = 0x811C9DC5u;
    if (s->quickOpen) {
        key ^= (uint32_t) (s->quickSel + 1) * 2654435761u;
        key ^= (uint32_t) ((s->flyList ? s->flySel + 100 : 0) + (s->repelList ? s->repelSel + 200 : 0)) * 40503u;
        for (int i = 0; i < s->quickCount; i++) key = key * 31 + (uint32_t) (s->quick[i].why != NULL) + (uint32_t) s->quick[i].off * 2;
    } else if (hud) {
        key ^= hud_key(&s->game, &s->shown, &s->hud, s->settings);
    }
    key = key * 31 + (uint32_t) picture;
    if (s->ui.keyFocus) key = key * 31 + 0x9E37u + (uint32_t) (bob * 16 + s->stripeIn);
    for (const char* p = s->noticeFrames > 0 ? s->notice : ""; *p; p++) key = key * 33 + (uint8_t) *p;
    if (key == s->overlayKey) return;
    s->overlayKey = key;
    s->overlayDirty = true;
    s->overlayDim = s->quickOpen;
    overlay_clear();
    s->overlayShown = false;
    // The HUD and the QUICK MENU are drawn in the app's own font in every game, as the app's are (outside the companion, a
    // Game Boy game's letters on the see-through glass came out heavy).
    const struct pd_glyph* gameFont;
    int gameFontCount;
    pd_font_get(&gameFont, &gameFontCount);
    pd_font_set(NULL, 0);
    if (s->quickOpen) {
        if (s->flyList) quick_list_draw(&c, "FLY", s->flyNames, s->flyTargets.count, s->flySel, s->settings->hudLook);
        else if (s->repelList) quick_list_draw(&c, "REPEL", s->repelNames, s->repelKindCount, s->repelSel, s->settings->hudLook);
        else quick_draw(&c, &s->game, s->quick, s->quickCount, s->quickSel, s->settings->hudLook);
        s->overlayShown = true;
    } else if (hud) {
        s->hud.compact = s->hud.noButtons = true;
        s->hud.wild = s->hud.focus = -1;
        s->hud.left = (PD_TOP_W - picture) / 2;
        s->hud.right = s->hud.left + picture;
        s->overlayShown = hud_draw(&c, &s->game, &s->shown, &s->hud, s->settings, NULL);
        // The buttons on the companion: the HUD fades back (hudAlpha 0.25).
        for (int i = 0; s->ui.keyFocus && i < PD_TOP_W * PD_TOP_H; i++) {
            uint32_t p = overlayPx[i], a = p >> 24 ? p >> 24 : 0xFF;
            if (p != QUICK_CLEAR) overlayPx[i] = (a / 4 ? a / 4 : 1) << 24 | (p & 0xFFFFFF);
        }
    }
    pd_font_set(gameFont, gameFontCount);
    if (s->ui.keyFocus) {
        int left = (PD_TOP_W - picture) / 2, bottom = (PD_TOP_H + pictureH) / 2;
        focus_stripe(&c, left, left + picture, bottom, pictureH, gameRows, bob, s->stripeIn);
        s->overlayShown = true;
    }
    if (s->noticeFrames > 0) {
        int w = pd_text_width(s->notice) + 12;
        int x = PD_TOP_W - w - 4;
        pd_round_rect(&c, x, 4, w, 18, 2, 0xB8121620u);
        pd_text(&c, x + 6, 4, s->notice, 0xF8F8F8, 0x303840);
        s->overlayShown = true;
    }
    quick_finish(&c);
}

void pd_session_tick(struct pd_session* s, const struct pd_input* in, unsigned ms) {
    quick_items(s);
    bool menuWasOpen = s->quickOpen;
    if (in->quickMenu) {
        s->quickOpen = !s->quickOpen;
        s->flyList = s->repelList = s->flyFromMap = false;
        s->flyAsk = 0;
    } else if (s->quickOpen) {
        quick_input(s, in);
    }

    bool ff = s->ffToggled || in->ffHeld;
    if (ff != s->ff) {
        s->ff = ff;
        s->host.ffOn = ff;
        s->redraw = true;
    }
    // A button held while the menu was up (the A that picked a tile closes it this very loop) stays the menu's until
    // it's let go: else the game gets it too, and talks to the water or the person in front.
    if (menuWasOpen || s->quickOpen) s->quickHeld |= in->held;
    s->quickHeld &= in->held;
    uint32_t keys = s->quickOpen ? 0 : in->held & ~s->quickHeld;
    // A loop is one screen refresh, so a speed is frames a refresh: 1.5x runs one, two, one, two; 0.5x every other.
    static const int SPEED_X4[PD_GAME_SPEED_COUNT] = { 4, 6, 8, 12, 16, 2, 1 };
    int quarter = ff && !smart_slows(s) ? s->settings->ffSpeed * 4 : SPEED_X4[s->settings->gameSpeed];
    // The scenic FLY's trip runs at 2x at least (EmulatorEngine.SCENIC_SPEED): a show, nothing to steer.
    if (s->scenic && s->flightState != PK_FLIGHT_IDLE && s->flightState != PK_FLIGHT_DISMOUNT && quarter < 8) quarter = 8;
    s->speedDebt += quarter;
    int count = s->speedDebt / 4;
    s->speedDebt %= 4;
    for (int i = 0; i < count; i++) pd_session_run_frame(s, keys, i == count - 1);
    field_jobs(s);
    item_jobs(s);
    box_jobs(s);
    if (s->noticeFrames > 0) s->noticeFrames--;
    // Off the field (a battle, the PC opened from the companion) the buttons are the game's again.
    if (s->ui.keyFocus && !pd_session_can_focus(s)) pd_session_set_focus(s, false);
    overlay_update(s);

    s->redraw |= pd_ui_tick(&s->ui, ms);
    // The busy ball comes and goes between reads.
    bool busy = pd_binput_busy(&s->input);
    if (busy != s->wasBusy) s->redraw = true;
    s->wasBusy = busy;
}

void pd_session_draw(struct pd_session* s, struct pd_canvas* bottom) {
    pd_ui_draw(&s->ui, bottom, &s->game, &s->shown, &s->host);
    s->redraw = false;
}

bool pd_session_action(struct pd_session* s, enum pd_action action) {
    switch (action) {
    case PD_ACTION_SETTINGS_CHANGED:
        pd_look_apply(&s->game, s->settings);
        cheats_update(s);
        s->redraw = true;
        return false; // the host saves them
    case PD_ACTION_ITEM_PICK: s->itemAsk = 1; return true;
    case PD_ACTION_ITEM_APPLY: s->itemAsk = 2; return true;
    case PD_ACTION_ITEM_TAKE: s->itemAsk = 3; return true;
    case PD_ACTION_UNDO_SAVE:
        pd_session_show(s, pd_session_undo_save(s) ? "Save undone" : "Nothing to undo");
        s->redraw = true;
        return true;
    case PD_ACTION_UNDO_LOAD:
        pd_session_show(s, pd_session_undo_load(s) ? "Load undone" : "Nothing to undo");
        s->redraw = true;
        return true;
    case PD_ACTION_BOX_WITHDRAW: case PD_ACTION_BOX_SWAP: case PD_ACTION_BOX_DEPOSIT:
        s->boxAsk = (int) action;
        return true;
    case PD_ACTION_FLY_ASK:
        s->flyFromMap = true;
        s->flyAsk = 1;
        return true;
    case PD_ACTION_FLY_TO:
        fly_to(s, s->flyTargets.towns[s->ui.flyPick]);
        return true;
    case PD_ACTION_FLIGHT:
        s->flightAsk = 1;
        return true;
    case PD_ACTION_STATE_SLOT:
        s->host.slotSelected = s->ui.actionSlot;
        s->redraw = true;
        return true;
    case PD_ACTION_TOGGLE_FF:
        s->ffToggled = !s->ffToggled;
        return true;
    case PD_ACTION_SAVE_STATE:
        pd_session_show(s, pd_session_save_state(s, s->host.slotSelected) ? "State saved" : "State save failed");
        return true;
    case PD_ACTION_LOAD_STATE:
        pd_session_show(s, pd_session_load_state(s, s->host.slotSelected) ? "State loaded" : "State load failed");
        return true;
    default:
        return false;
    }
}

bool pd_session_holds_frame(const struct pd_session* s) {
    return s->input.holdFrame;
}

const uint32_t* pd_session_overlay(struct pd_session* s) {
    s->overlayDirty = false;
    return overlayPx;
}

const uint32_t* pd_session_wide(const struct pd_session* s) {
    (void) s;
    return wideFrames[wideShown];
}
