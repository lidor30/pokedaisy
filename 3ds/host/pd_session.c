#include "pd_session.h"

#include "../core/pd_tables.h"
#include "pd_gb_field.h"

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
static uint32_t wideFrame[PD_TOP_W * PD_TOP_H];
static uint32_t overlayPx[PD_TOP_W * PD_TOP_H];

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
    if (s->game.gb) return gb_read(s, addr, out, len);
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
    if (s->game.gb) {
        for (size_t i = 0; i < len; i++) s->core->busWrite8(s->core, addr + (uint32_t) i, ((const uint8_t*) data)[i]);
        return true;
    }
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
    struct VFile* vf = VFileOpen(vfPath, O_CREAT | O_TRUNC | O_RDWR);
    if (!vf) return false;
    bool ok = mCoreSaveStateNamed(s->core, vf, STATE_FLAGS);
    vf->close(vf);
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

static void write_state(struct pd_session* s, const char* vfPath) {
    struct VFile* vf = VFileOpen(vfPath, O_CREAT | O_TRUNC | O_RDWR);
    if (!vf) return;
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
    s->host.undoSave = false;
    return true;
}

bool pd_session_undo_load(struct pd_session* s) {
    if (!s->host.undoLoad) return false;
    char vf[512], path[512];
    undo_path(s, "undoload", vf, sizeof(vf), path, sizeof(path));
    struct VFile* f = VFileOpen(vf, O_RDONLY);
    if (!f) return false;
    bool ok = mCoreLoadStateNamed(s->core, f, STATE_FLAGS);
    f->close(f);
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
    write_state(s, undoVf);
    s->host.undoLoad = true;
    struct VFile* vf = VFileOpen(vfPath, O_RDONLY);
    if (!vf) return false;
    bool ok = mCoreLoadStateNamed(s->core, vf, STATE_FLAGS);
    vf->close(vf);
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

static void cheats_load(struct pd_session* s) {
    free(s->cheatText);
    s->cheatText = NULL;
    s->host.hasCheats = false;
    s->cheatsOn = false;
    if (!s->files.cheatPath || s->game.gb) return;
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
    s->fieldKnown = gba && pk_field_configure(s->romCrc) != 0;
    s->wildSwitch = s->fieldKnown ? pk_wild_switch_find(s->rom, s->romSize) : 0;
    if (s->wildSwitch) pk_field_wild(s->wildSwitch, settings->wildOff);
    s->host.hasExpand = true;
    s->host.expandGame = s->wideKnown;
    s->host.hasHud = true;
    s->host.fullSettings = true;
    s->gbField = s->game.gb && pd_gbf_ok(s->game.gb->field, s->rom, s->romSize) ? s->game.gb->field : NULL;
    s->host.wildSupported = s->wildSwitch != 0 || s->gbField;
    s->host.fieldSupported = s->fieldKnown;

    const struct pd_config* cfg = s->game.cfg;
    s->flyCfg = cfg && fly_supported(cfg, s->rom, s->romSize) ? cfg : NULL;
    s->itemsOk = cfg && field_items_supported(cfg, s->rom, s->romSize);
    s->pcOk = s->itemsOk && pc_supported(cfg, s->rom, s->romSize);
    s->bikeOk = s->itemsOk && bike_supported(cfg);
    if (s->itemsOk) pd_items_setup(cfg, s->rom, s->romSize);
    if (cfg && pd_boxes_supported(&s->game)) s->ui.boxes = &s->boxes;
    s->ui.boxMoves = s->ui.boxes && s->itemsOk && pd_box_moves_ok(cfg, s->rom, s->romSize);
    s->ui.boxSummary = s->ui.boxSlot = -1;
    s->host.itemCanUse = pd_items_can_use;
    s->host.itemCanGive = pd_items_can_give;

    // What the companion shows: read once now (RAM still blank: an empty party), then every PD_SNAPSHOT_EVERY frames.
    pd_snapshot_read(&s->shown, &s->game, pd_session_read, s);
    s->shown.frame = 0;
    s->frame = 0;
    s->redraw = true;
}

// EXPAND's window (Linux's wide_window): SCREEN's scale makes EXPAND's size, ZOOM OUT one whole scale less: the 3DS's
// 1.5x fills 400 x 240 with 266 x 160 of the game, 1x with all of it.
static void wide_window(const struct pd_session* s, int aspect, int* w, int* h) {
    bool whole = s->settings->screenMode == PD_SCREEN_PIXEL || aspect == PD_ASPECT_ZOOM_OUT;
    int fw = pk_wide_frame_w(), fh = pk_wide_frame_h();
    int x = whole ? PD_TOP_W : PD_TOP_W * 2 / 3, y = whole ? PD_TOP_H : PD_TOP_H * 2 / 3;
    if (x < fw) x = fw;
    if (y < fh) y = fh;
    *w = x - ((x - fw) & 1);
    *h = y - ((y - fh) & 1);
}

#define ORIGINAL_SCENE_EVERY 16

static void wide_tick(struct pd_session* s, bool skip) {
    s->wideReady = false;
    if (!s->wideKnown) return;
    pk_wide_lines(s->settings->aspect != PD_ASPECT_ORIGINAL);
    // Nothing past the picture, so only the scene is wanted (FLY's "on the field"): a look every few frames. Every frame
    // took 7 ms of a New 3DS's 16.7, and with the game's own 9 that's one refresh too many.
    bool original = s->settings->aspect == PD_ASPECT_ORIGINAL;
    if (original && s->frame % ORIGINAL_SCENE_EVERY) return;
    int fw = pk_wide_frame_w(), fh = pk_wide_frame_h();
    for (int y = 0; y < fh; y++) memcpy(wideSource + y * fw, s->video + y * PD_GAME_STRIDE, (size_t) fw * 4);
    if (skip) {
        pk_wide_skip(s->core, wideSource);
        return;
    }
    if (original) {
        // A one-pixel window.
        static uint32_t px;
        pk_wide_view(s->core, wideSource, &px, (struct PkWideView) { 0, 0, 1, 1 }, NULL);
        return;
    }
    int aspect = s->settings->aspect == PD_ASPECT_ZOOM_OUT && s->settings->autoZoom &&
        pk_wide_scene() != PK_WIDE_SCENE_FIELD ? PD_ASPECT_EXPAND : s->settings->aspect;
    wide_window(s, aspect, &s->wideW, &s->wideH);
    struct PkWideView v = { (fw - s->wideW) / 2, (fh - s->wideH) / 2, s->wideW, s->wideH };
    pk_wide_view(s->core, wideSource, wideFrame, v, NULL);
    s->wideReady = !pk_wide_fault();
}

void pd_session_run_frame(struct pd_session* s, uint32_t playerKeys, bool last) {
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
    s->core->runFrame(s->core);
    // The frame that will be shown: with mGBA's renderer on its own thread (New 3DS) the last lines may still be coming,
    // and getPixels is what waits for them.
    if (last) {
        size_t stride;
        const void* pixels;
        s->core->getPixels(s->core, &pixels, &stride);
    }
    if (!s->game.gb) pk_rest_note(s->core);
    if (s->fieldKnown)
        s->fieldFlags = pk_field_state(s->core, s->settings->runIndoors, s->wildSwitch && s->settings->wildOff ? 0 : 100);
    wide_tick(s, !last);
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
    if ((stateChanged || s->frame % PD_SNAPSHOT_EVERY == 0) && (s->game.gb || (s->ewram && s->iwram))) {
        pd_snapshot_read(&s->snap, &s->game, pd_session_read, s);
        pd_ui_update(&s->ui, &s->snap);
        // Only a change the companion shows is worth a redraw (not the frame counter).
        s->snap.frame = 0;
        if (memcmp(&s->snap, &s->shown, sizeof(s->snap))) {
            s->shown = s->snap;
            s->redraw = true;
        }
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

static void quick_run(struct pd_session* s, const struct quick_item* item) {
    s->quickOpen = false;
    switch (item->action) {
    case QUICK_PC:
        if (s->gbField) {
            if (pd_gbf_open_pc(s, s->gbField)) {
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
            s->flyMapsec = s->flyTargets.towns[s->flySel];
            s->flyStage = 1;
            s->flyWait = 0;
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
    if (item->action == QUICK_FLY) {
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

// FLY's steps and the field jobs, each between two frames the game's own code can be called in (pk_call_safe).
static void gb_jobs(struct pd_session* s) {
    const struct pd_gb_field* f = s->gbField;
    if (s->settings->wildOff) {
        pd_gbf_hold_wild(s, f);
        s->gbWildHeld = true;
    } else if (s->gbWildHeld && pd_gbf_release_wild(s, f)) {
        s->gbWildHeld = false;
    }
    if (s->flyAsk && s->quickOpen) {
        s->flyAsk = 0;
        pd_gbf_fly_targets(s, f, &s->flyTargets);
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
        enum fly_result r = pd_gbf_fly(s, f, s->flyMapsec);
        if (r == FLY_OK) {
            s->job = PD_JOB_GB_TAKEN;
            s->jobFrames = 0;
        } else {
            pd_session_show(s, fly_reason(r));
        }
    }
    if (s->job == PD_JOB_GB_TAKEN && ++s->jobFrames >= PD_GB_TAKE_FRAMES) {
        s->job = PD_JOB_NONE;
        if (!pd_gbf_taken(s, f)) pd_session_show(s, "You can't do that now.");
    }
}

static void field_jobs(struct pd_session* s) {
    if (s->gbField) {
        gb_jobs(s);
        return;
    }
    struct mCore* c = s->core;
    const struct pd_config* cfg = s->game.cfg;
    bool battle = s->shown.inBattle || (s->fieldFlags & PK_FIELD_BATTLE);
    bool busy = (s->fieldFlags & PK_FIELD_LOCKED) || (s->wideKnown && pk_wide_scene() != PK_WIDE_SCENE_FIELD);
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
    if (ask == 1) {
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
        else snprintf(boxName, sizeof(boxName), "%s", pd_species_name(boxMon->species, partyName, sizeof(partyName)));
    }
    if (partyMon) {
        if (*partyMon->nickname) snprintf(partyName, sizeof(partyName), "%s", partyMon->nickname);
        else {
            char name[32];
            snprintf(partyName, sizeof(partyName), "%s", pd_species_name(partyMon->species, name, sizeof(name)));
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
static void overlay_update(struct pd_session* s) {
    struct pd_canvas c;
    pd_canvas_init(&c, PD_TOP_W, PD_TOP_H, overlayPx);
    bool field = (s->fieldFlags & PK_FIELD_KNOWN) && !(s->fieldFlags & (PK_FIELD_LOCKED | PK_FIELD_BATTLE)) &&
        (!s->wideKnown || pk_wide_scene() == PK_WIDE_SCENE_FIELD) && s->flyStage == 0;
    // A Game Boy game has no field read: EXPAND's scene says it (pk_wide_gb.c).
    if (s->game.gb) field = s->wideKnown && pk_wide_scene() == PK_WIDE_SCENE_FIELD && !s->shown.inBattle;
    s->hudFieldFrames = field ? s->hudFieldFrames + 1 : 0;
    bool hud = !s->quickOpen && s->settings->hud && s->game.cfg && s->hudFieldFrames >= HUD_AFTER_FRAMES;
    if (hud) {
        int steps = repel_steps(s->core, s->game.cfg);
        if (steps <= 0) s->hud.repelFull = 0;
        else if (!s->hud.repelFull || steps > s->hud.repelFull) s->hud.repelFull = hud_repel_full(steps);
        s->hud.repel = steps > 0 ? steps : 0;
    }
    uint32_t key = 0x811C9DC5u;
    if (s->quickOpen) {
        key ^= (uint32_t) (s->quickSel + 1) * 2654435761u;
        key ^= (uint32_t) ((s->flyList ? s->flySel + 100 : 0) + (s->repelList ? s->repelSel + 200 : 0)) * 40503u;
        for (int i = 0; i < s->quickCount; i++) key = key * 31 + (uint32_t) (s->quick[i].why != NULL) + (uint32_t) s->quick[i].off * 2;
    } else if (hud) {
        key ^= hud_key(&s->game, &s->shown, &s->hud, s->settings->hudHidden);
    }
    for (const char* p = s->noticeFrames > 0 ? s->notice : ""; *p; p++) key = key * 33 + (uint8_t) *p;
    if (key == s->overlayKey) return;
    s->overlayKey = key;
    s->overlayDirty = true;
    s->overlayDim = s->quickOpen;
    overlay_clear();
    s->overlayShown = false;
    if (s->quickOpen) {
        if (s->flyList) quick_list_draw(&c, "FLY", s->flyNames, s->flyTargets.count, s->flySel);
        else if (s->repelList) quick_list_draw(&c, "REPEL", s->repelNames, s->repelKindCount, s->repelSel);
        else quick_draw(&c, &s->game, s->quick, s->quickCount, s->quickSel);
        s->overlayShown = true;
    } else if (hud) {
        // Without EXPAND the HUD keeps to the game's picture (FieldHud(frame)).
        unsigned gw = 240, gh = 160;
        s->core->desiredVideoDimensions(s->core, &gw, &gh);
        int picture = s->settings->screenMode == PD_SCREEN_STRETCH || s->wideReady ? PD_TOP_W :
            s->settings->screenMode == PD_SCREEN_PIXEL ? (int) gw : (int) gw * PD_TOP_H / (int) gh;
        s->hud.compact = true;
        s->hud.left = (PD_TOP_W - picture) / 2;
        s->hud.right = s->hud.left + picture;
        s->overlayShown = hud_draw(&c, &s->game, &s->shown, &s->hud, s->settings->hudHidden);
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
    uint32_t keys = s->quickOpen ? 0 : in->held;
    // A loop is one screen refresh, so a speed is frames a refresh: 1.5x runs one, two, one, two; 0.5x every other.
    static const int SPEED_X4[PD_GAME_SPEED_COUNT] = { 4, 6, 8, 12, 16, 2, 1 };
    int quarter = ff && !smart_slows(s) ? s->settings->ffSpeed * 4 : SPEED_X4[s->settings->gameSpeed];
    s->speedDebt += quarter;
    int count = s->speedDebt / 4;
    s->speedDebt %= 4;
    for (int i = 0; i < count; i++) pd_session_run_frame(s, keys, i == count - 1);
    field_jobs(s);
    item_jobs(s);
    box_jobs(s);
    if (s->noticeFrames > 0) s->noticeFrames--;
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
        s->flyMapsec = s->flyTargets.towns[s->ui.flyPick];
        s->flyStage = 1;
        s->flyWait = 0;
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
    return wideFrame;
}
