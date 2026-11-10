// pd_sim: runs a real ROM and save through the 3DS app's host logic with no screens. Reads commands from a script
// (a file, or stdin), one a line; # starts a comment. See README.md's "pd_sim".
//
//   pd_sim ROM SAVE OUTDIR [SCRIPT]
//
//   run N              N frames, the player holding nothing
//   press KEYS [N]     KEYS (a+b+up ...) held N frames (default 3), then 10 frames free
//   tap X Y            the stylus at (X, Y) on the bottom screen, then 4 frames
//   quick              the QUICK MENU button
//   aspect N / screen N  the ASPECT and SCREEN settings
//   hit ID             the stylus on the companion's hit with that id, then 4 frames
//   hits               prints what can be tapped
//   bench N            times N redraws of the companion as it is now
//   shot NAME          NAME.png: the two screens as the console shows them
//   wild SPECIES LEVEL a wild battle now (setwildbattle / dowildbattle through ScriptContext_SetupScript)
//   wait-state S [MAX] frames until the battle waits for S (none busy action move bag party target)
//   press-to-state S [MAX] the same, pressing A now and then through the game's messages
//   poke ADDR VALUE    a byte of RAM
//   peek ADDR [N]      N bytes of RAM
//   state              prints the battle's state, the party's HP, the place
//   expect WHAT ...    the script fails unless it holds: state S / hp SLOT N / inbattle 0|1
//
// The save is copied first, so the player's file is never written.
#include <ctype.h>
#include <fcntl.h>
#include <stdio.h>
#include <time.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <mgba-util/vfs.h>
#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include "../../host/pd_mixer.h"
#include <mgba/internal/gba/gba.h>

#include "../../../app/src/main/cpp/pk_call.h"
#include "../../../app/src/main/cpp/pk_host.h"
#include "../../../app/src/main/cpp/pk_wide.h"
#include "../../core/pd_games.h"
#include "../../core/pd_settings.h"
#include "../../core/pd_style.h"
#include "../../core/pd_gb.h"
#include "../../host/pd_session.h"
#include "../pd_png.h"

#define GBA_W 240
#define GBA_H 160
#define STRIDE PD_GAME_STRIDE

static uint32_t video[STRIDE * GBA_H];
static uint32_t bottomPx[PD_UI_WIDTH * PD_UI_HEIGHT];
static struct pd_session session;
static struct pd_settings settings;
static const char* outDir;
static int failures;
static uint32_t heldKeys;

static void noopLog(struct mLogger* log, int category, enum mLogLevel level, const char* format, va_list args) {
    (void) log; (void) category; (void) level; (void) format; (void) args;
}
static struct mLogger silentLogger = { .log = noopLog, .filter = NULL };

static void frames(int n, uint32_t keys) {
    static uint32_t last;
    while (n-- > 0) {
        struct pd_input in = { .held = keys, .down = keys & ~last, .stickAngle = -1 };
        last = keys;
        pd_session_tick(&session, &in, 16);
    }
}

static void quick_button(void) {
    struct pd_input in = { .quickMenu = true, .stickAngle = -1 };
    pd_session_tick(&session, &in, 16);
}

static void state_path(void* ctx, int slot, char* vf, size_t vfLen, char* path, size_t pathLen) {
    (void) ctx;
    snprintf(path, pathLen, "%s/slot%d.ss", outDir, slot);
    snprintf(vf, vfLen, "%s", path);
}

static void no_backup(void* ctx) {
    (void) ctx;
}

static void redraw(void) {
    struct pd_canvas bottom;
    pd_canvas_init(&bottom, PD_UI_WIDTH, PD_UI_HEIGHT, bottomPx);
    pd_session_draw(&session, &bottom);
}

static uint32_t parse_keys(const char* text) {
    static const struct { const char* name; uint32_t bit; } KEYS[] = {
        { "a", PD_KEY_A }, { "b", PD_KEY_B }, { "select", PD_KEY_SELECT }, { "start", PD_KEY_START },
        { "right", PD_KEY_RIGHT }, { "left", PD_KEY_LEFT }, { "up", PD_KEY_UP }, { "down", PD_KEY_DOWN },
        { "r", PD_KEY_R }, { "l", PD_KEY_L },
    };
    char buf[64];
    snprintf(buf, sizeof(buf), "%s", text);
    uint32_t bits = 0;
    for (char* k = strtok(buf, "+"); k; k = strtok(NULL, "+"))
        for (size_t i = 0; i < sizeof(KEYS) / sizeof(*KEYS); i++)
            if (!strcasecmp(k, KEYS[i].name)) bits |= KEYS[i].bit;
    return bits;
}

static void tap_at(int x, int y) {
    redraw();
    pd_ui_touch(&session.ui, x, y, true);
    frames(2, heldKeys);
    pd_ui_touch(&session.ui, 0, 0, false);
    pd_session_action(&session, pd_ui_take_action(&session.ui));
    frames(2, heldKeys);
    redraw();
}

static void tap_hit(int id) {
    redraw();
    for (int i = session.ui.hitCount - 1; i >= 0; i--) {
        const struct pd_hit* h = &session.ui.hits[i];
        if (h->id == id) {
            tap_at(h->x + h->w / 2, h->y + h->h / 2);
            return;
        }
    }
    printf("FAIL: nothing to tap with id %d\n", id);
    failures++;
}

static enum pd_battle_state sim_state(void) {
    if (session.game.kind != PD_GAME_GB) return pd_battle_state_read(session.game.cfg, pd_session_read, &session, NULL);
    int cursor;
    return pd_gb_battle_menu(session.game.cfg->gb, pd_session_read, &session, &cursor);
}

static void shot(const char* name) {
    redraw();
    static uint32_t out[400 * 480];
    memset(out, 0, sizeof(out));
    // SHARP 1.5x (or EXPAND's window) in nearest steps: where the game sits, not how sharp it looks.
    bool wide = session.wideReady;
    const uint32_t* src = wide ? pd_session_wide(&session) : video;
    static uint32_t filled[400 * 240];
    const struct pd_wide_hole* hole = &session.wideHole;
    if (wide && hole->w) {
        // What the console's draw thread does.
        memcpy(filled, src, (size_t) session.wideW * session.wideH * sizeof(uint32_t));
        for (int y = 0; y < hole->h; y++)
            memcpy(filled + (hole->y + y) * session.wideW + hole->x, video + (hole->gameY + y) * STRIDE + hole->gameX, (size_t) hole->w * sizeof(uint32_t));
        src = filled;
    }
    unsigned coreW = GBA_W, coreH = GBA_H;
    session.core->desiredVideoDimensions(session.core, &coreW, &coreH);
    int sw = wide ? session.wideW : (int) coreW, sh = wide ? session.wideH : (int) coreH, stride = wide ? session.wideW : STRIDE;
    int outW = sw * 240 / sh, x0 = (400 - outW) / 2;
    for (int y = 0; y < 240; y++) {
        for (int x = 0; x < outW; x++) {
            uint32_t p = src[(y * sh / 240) * stride + x * sw / outW];
            out[y * 400 + x0 + x] = 0xFF000000 | (p & 0xFF) << 16 | (p & 0xFF00) | (p >> 16 & 0xFF);
        }
    }
    const uint32_t* overlay = pd_session_overlay(&session);
    for (int i = 0; i < 400 * 240; i++) {
        uint32_t o = overlay[i], a = o >> 24, d = out[i], r = 0;
        if (session.overlayDim) d = (d >> 1 & 0x7F7F7F) + (d >> 2 & 0x3F3F3F);
        for (int sft = 0; sft <= 16; sft += 8)
            r |= ((((o >> sft) & 0xFF) * a + ((d >> sft) & 0xFF) * (255 - a)) / 255) << sft;
        out[i] = 0xFF000000 | r;
    }
    for (int y = 0; y < 240; y++)
        for (int x = 0; x < PD_UI_WIDTH; x++)
            out[(240 + y) * 400 + 40 + x] = 0xFF000000 | bottomPx[y * PD_UI_WIDTH + x];
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s.png", outDir, name);
    pd_png_write(path, 400, 480, out);
}

static const char* const STATES[] = { "none", "busy", "action", "move", "bag", "party", "target" };

static int state_id(const char* name) {
    for (int i = 0; i < 7; i++)
        if (!strcmp(name, STATES[i])) return i;
    return -1;
}

static void wild(int species, int level) {
    const struct pd_config* cfg = session.game.cfg;
    if (!cfg->pcSetupScript || !cfg->pcScript) {
        printf("FAIL: no ScriptContext_SetupScript for this game\n");
        failures++;
        return;
    }
    // setwildbattle species level item; dowildbattle; end
    uint8_t script[] = { 0xB6, (uint8_t) species, (uint8_t) (species >> 8), (uint8_t) level, 0, 0, 0xB7, 0x02 };
    pd_session_write(&session, cfg->pcScript, script, sizeof(script));
    for (int i = 0; i < 300 && !pk_call_safe(session.core); i++) frames(1, 0);
    uint32_t args[4] = { cfg->pcScript, 0, 0, 0 };
    if (!pk_call(session.core, cfg->pcSetupScript, args, NULL)) {
        printf("FAIL: the script didn't start\n");
        failures++;
    }
}

// The game's ScriptContext on [script] (written over the PC's script RAM, as `wild` does).
static void run_script(const uint8_t* script, int len) {
    const struct pd_config* cfg = session.game.cfg;
    if (!cfg || !cfg->pcSetupScript || !cfg->pcScript) {
        printf("FAIL: no ScriptContext_SetupScript for this game\n");
        failures++;
        return;
    }
    pd_session_write(&session, cfg->pcScript, script, (size_t) len);
    for (int i = 0; i < 300 && !pk_call_safe(session.core); i++) frames(1, 0);
    uint32_t args[4] = { cfg->pcScript, 0, 0, 0 };
    if (!pk_call(session.core, cfg->pcSetupScript, args, NULL)) {
        printf("FAIL: the script didn't start\n");
        failures++;
    }
}

// teach SLOT INDEX MOVE: the party Pokémon's move INDEX set by the game's own SetMonData (MON_DATA_MOVE1 + INDEX), its
// value from the field items' scratch RAM.
static void teach(int slot, int index, int move) {
    const struct pd_config* cfg = session.game.cfg;
    const struct pd_party_fns* p = cfg ? cfg->partyFns : NULL;
    if (!p || !p->setMonData || !p->scratch) {
        printf("FAIL: no SetMonData for this game\n");
        failures++;
        return;
    }
    uint8_t v[2] = { (uint8_t) move, (uint8_t) (move >> 8) };
    pd_session_write(&session, p->scratch, v, 2);
    for (int i = 0; i < 300 && !pk_call_safe(session.core); i++) frames(1, 0);
    uint32_t args[4] = { p->party + (uint32_t) slot * 100, 13u + (uint32_t) (index + p->monFieldShift), p->scratch, 0 };
    if (!pk_call(session.core, p->setMonData, args, NULL)) {
        printf("FAIL: SetMonData didn't return\n");
        failures++;
    }
}

static void print_state(void) {
    pd_snapshot_read(&session.snap, &session.game, pd_session_read, &session);
    const struct pd_snapshot* s = &session.snap;
    printf("state=%s inBattle=%d mapsec=%d money=%ld party:", STATES[s->battleState], s->inBattle, s->mapsec, s->money);
    for (int i = 0; i < s->partyCount; i++) printf(" %s %d/%d", s->party[i].nickname, s->party[i].hp, s->party[i].maxHp);
    printf("\n");
}

static void expect(char* what) {
    pd_snapshot_read(&session.snap, &session.game, pd_session_read, &session);
    const struct pd_snapshot* s = &session.snap;
    char kind[32], a[32] = "", b[32] = "";
    sscanf(what, "%31s %31s %31s", kind, a, b);
    bool ok = false;
    if (!strcmp(kind, "state")) ok = (int) s->battleState == state_id(a);
    else if (!strcmp(kind, "inbattle")) ok = s->inBattle == (atoi(a) != 0);
    else if (!strcmp(kind, "hp")) ok = s->party[atoi(a)].hp == atoi(b);
    if (!ok) {
        failures++;
        printf("FAIL: expect %s\n  ", what);
        print_state();
    }
}

static void command(char* line) {
    char* hash = strchr(line, '#');
    if (hash) *hash = 0;
    char cmd[32] = "";
    int n = 0;
    if (sscanf(line, "%31s %n", cmd, &n) < 1) return;
    char* args = line + n;
    args[strcspn(args, "\r\n")] = 0;
    int a = 0, b = 0, a3 = 0;
    if (!strcmp(cmd, "run")) {
        frames(atoi(args), heldKeys);
    } else if (!strcmp(cmd, "press")) {
        char keys[64] = "";
        int held = 3;
        sscanf(args, "%63s %d", keys, &held);
        frames(held, heldKeys | parse_keys(keys));
        frames(10, heldKeys);
    } else if (!strcmp(cmd, "cache")) {
        pk_wide_cache(atoi(args));
    } else if (!strcmp(cmd, "cache-hits")) {
        printf("cache hits %d\n", pk_wide_cache_hits());
    } else if (!strcmp(cmd, "aspect")) {
        settings.aspect = atoi(args);
    } else if (!strcmp(cmd, "dark")) {
        settings.darkMode = atoi(args) != 0;
        pd_look_apply(&session.game, &settings);
    } else if (!strcmp(cmd, "gen5")) {
        settings.battleGen5 = atoi(args) != 0;
    } else if (!strcmp(cmd, "noinfo")) {
        settings.noBattleInfo = atoi(args) != 0;
    } else if (!strcmp(cmd, "infofirst")) {
        settings.battleInfoFirst = atoi(args) != 0;
    } else if (!strcmp(cmd, "onetap")) {
        settings.oneTapMoves = atoi(args) != 0;
    } else if (!strcmp(cmd, "theme")) {
        settings.theme = atoi(args);
        pd_look_apply(&session.game, &settings);
    } else if (!strcmp(cmd, "screen")) {
        settings.screenMode = atoi(args);
    } else if (!strcmp(cmd, "quick")) {
        quick_button();
    } else if (!strcmp(cmd, "tap") && sscanf(args, "%d %d", &a, &b) == 2) {
        tap_at(a, b);
    } else if (!strcmp(cmd, "hit")) {
        tap_hit(atoi(args));
    } else if (!strcmp(cmd, "hits")) {
        redraw();
        for (int i = 0; i < session.ui.hitCount; i++) {
            const struct pd_hit* h = &session.ui.hits[i];
            printf("hit %d at %d,%d %dx%d\n", h->id, h->x, h->y, h->w, h->h);
        }
    } else if (!strcmp(cmd, "idle") && session.core->platform(session.core) == mPLATFORM_GBA) {
        // idle detect | remove | ignore: mGBA's busy-loop skipping, as the console sets it (detect) or not.
        struct GBA* gba = session.core->board;
        gba->idleOptimization = !strcmp(args, "detect") ? IDLE_LOOP_DETECT : !strcmp(args, "ignore") ? IDLE_LOOP_IGNORE : IDLE_LOOP_REMOVE;
        printf("idle %s: loop at %08X\n", args, gba->idleLoop);
    } else if (!strcmp(cmd, "timeframes")) {
        // N frames of the session (the game, its hooks and the host's per-frame work), timed: a host's cost.
        int n = atoi(args) > 0 ? atoi(args) : 300;
        struct timespec t0, t1;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        frames(n, 0);
        clock_gettime(CLOCK_MONOTONIC, &t1);
        double us = ((t1.tv_sec - t0.tv_sec) * 1e9 + (t1.tv_nsec - t0.tv_nsec)) / 1e3 / n;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        for (int i = 0; i < n; i++) session.core->runFrame(session.core);
        clock_gettime(CLOCK_MONOTONIC, &t1);
        double core = ((t1.tv_sec - t0.tv_sec) * 1e9 + (t1.tv_nsec - t0.tv_nsec)) / 1e3 / n;
        printf("timeframes: %.1f us a session frame, %.1f us a bare runFrame\n", us, core);
    } else if (!strcmp(cmd, "bench")) {
        // The companion's draw, timed: N redraws of what's up now (a host's cost, not a 3DS's: compare screens with it).
        int n = atoi(args) > 0 ? atoi(args) : 200;
        struct timespec t0, t1;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        for (int i = 0; i < n; i++) {
            session.redraw = true;
            redraw();
        }
        clock_gettime(CLOCK_MONOTONIC, &t1);
        double us = ((t1.tv_sec - t0.tv_sec) * 1e9 + (t1.tv_nsec - t0.tv_nsec)) / 1e3 / n;
        printf("bench tab %d: %.1f us a draw\n", (int) session.ui.tab, us);
    } else if (!strcmp(cmd, "shot")) {
        shot(args);
    } else if (!strcmp(cmd, "wild") && sscanf(args, "%d %d", &a, &b) == 2) {
        wild(a, b);
    } else if (!strcmp(cmd, "wait-state") || !strcmp(cmd, "press-to-state")) {
        char name[16] = "";
        int max = 3000;
        sscanf(args, "%15s %d", name, &max);
        int want = state_id(name), i = 0;
        // A message that waits for a button gets one, as a player would.
        for (;;) {
            for (; i < max && (int) sim_state() != want; i++)
                frames(1, heldKeys | (!strcmp(cmd, "press-to-state") && i % 40 == 39 ? PD_KEY_A : 0));
            // That A can land on the menu's first frame and be taken: only a state that stays counts.
            frames(8, heldKeys);
            if (i >= max || (int) sim_state() == want) break;
        }
        if (i == max) {
            failures++;
            printf("FAIL: wait-state %s\n  ", name);
            print_state();
        } else {
            printf("state %s after %d frames\n", name, i);
            frames(2, heldKeys); // the session reads it on its next frame
        }
    } else if (!strcmp(cmd, "teach") && sscanf(args, "%d %d %d", &a, &b, &a3) == 3) {
        teach(a, b, a3);
    } else if (!strcmp(cmd, "script")) {
        // script HEX: the game runs these script bytes (a warp, say) through its own ScriptContext, like `wild`.
        uint8_t bytes[64];
        int len = 0;
        for (char* p = args; *p && len < (int) sizeof(bytes);) {
            while (*p == ' ') p++;
            unsigned v;
            if (sscanf(p, "%2x", &v) != 1) break;
            bytes[len++] = (uint8_t) v;
            p += 2;
        }
        run_script(bytes, len);
    } else if (!strcmp(cmd, "flash")) {
        // FLASH picked from the quick menu: the session runs it between two frames.
        session.flashAsk = 1;
    } else if (!strcmp(cmd, "poke")) {
        unsigned addr, value;
        if (sscanf(args, "%x %x", &addr, &value) == 2) {
            uint8_t v = (uint8_t) value;
            pd_session_write(&session, addr, &v, 1);
        }
    } else if (!strcmp(cmd, "peek")) {
        unsigned addr;
        int count = 1;
        if (sscanf(args, "%x %d", &addr, &count) >= 1) {
            for (int i = 0; i < count; i++) {
                uint8_t v = 0;
                pd_session_read(&session, addr + (unsigned) i, &v, 1);
                printf("%02X ", v);
            }
            printf("\n");
        }
    } else if (!strcmp(cmd, "mixer")) {
        // The native mixer against the game's, run side by side on every mix so far (pd_mixer.c).
        printf("mixer mixes=%u differed=%u reverb=%u fixed=%u resampled=%u compressed=%u reversed=%u ended=%u\n", pd_mixer_stats.mixes,
               pd_mixer_stats.differed, pd_mixer_stats.reverb, pd_mixer_stats.fixed, pd_mixer_stats.resampled, pd_mixer_stats.compressed,
               pd_mixer_stats.reversed, pd_mixer_stats.ended);
        if (pd_mixer_stats.differed) failures++;
    } else if (!strcmp(cmd, "state")) {
        print_state();
    } else if (!strcmp(cmd, "expect")) {
        expect(args);
    } else {
        printf("FAIL: unknown command: %s\n", line);
        failures++;
    }
}

static bool copy_file(const char* from, const char* to) {
    FILE* in = fopen(from, "rb");
    if (!in) return false;
    FILE* out = fopen(to, "wb");
    if (!out) {
        fclose(in);
        return false;
    }
    char buf[8192];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), in)) > 0) fwrite(buf, 1, n, out);
    fclose(in);
    fclose(out);
    return true;
}

int main(int argc, char** argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: pd_sim ROM SAVE OUTDIR [SCRIPT]\n");
        return 2;
    }
    mLogSetDefaultLogger(&silentLogger);
    outDir = argv[3];
    pd_settings_defaults(&settings);
    char savePath[1024];
    snprintf(savePath, sizeof(savePath), "%s/sim.sav", outDir);
    unlink(savePath);
    if (strcmp(argv[2], "-")) copy_file(argv[2], savePath);

    // From a copy in memory: a GPIO write swaps mGBA's own mapping of a mapped file (FireRed), which would leave the
    // session's pointer dangling. mGBA's 3DS build keeps every ROM in one fixed buffer, so it can't happen there.
    FILE* romFile = fopen(argv[1], "rb");
    if (!romFile) {
        fprintf(stderr, "can't open %s\n", argv[1]);
        return 1;
    }
    fseek(romFile, 0, SEEK_END);
    size_t romLen = (size_t) ftell(romFile);
    fseek(romFile, 0, SEEK_SET);
    void* romBytes = malloc(romLen);
    if (fread(romBytes, 1, romLen, romFile) != romLen) return 1;
    fclose(romFile);
    struct VFile* rom = VFileFromMemory(romBytes, romLen);
    uint8_t header[0xC0];
    rom->seek(rom, 0, SEEK_SET);
    rom->read(rom, header, sizeof(header));
    rom->seek(rom, 0, SEEK_SET);
    pd_game_detect(&session.game, header, (size_t) rom->size(rom));
    struct mCore* core = mCoreFindVF(rom);
    if (!core || !core->init(core)) return 1;
    mCoreInitConfig(core, NULL);
    // PD_SIM_THREADED_VIDEO=1: mGBA's renderer on a thread of its own, as on a New 3DS (ctr/main.c).
    if (getenv("PD_SIM_THREADED_VIDEO")) mCoreConfigSetIntValue(&core->config, "threadedVideo", 1);
    core->setVideoBuffer(core, video, STRIDE);
    if (core->platform(core) == mPLATFORM_GB) {
        mCoreConfigSetIntValue(&core->config, "sgb.borders", 0);
        core->reloadConfigOption(core, "sgb.borders", &core->config);
    }
    core->setAudioBufferSize(core, 1024);
    if (!core->loadROM(core, rom)) return 1;
    struct VFile* save = VFileOpen(savePath, O_CREAT | O_RDWR);
    if (save) core->loadSave(core, save);
    core->reset(core);
    pk_heal_rtc(core);
    struct pd_session_host files = { .statePath = state_path, .backupSave = no_backup };
    session.host.batteryPct = 76;
    pd_session_start(&session, core, video, &settings, &files);
    printf("%s (%s rev %d): %s\n", session.game.title, session.game.code, session.game.revision,
           session.game.cfg ? "supported" : session.game.unsupported);

    FILE* script = argc > 4 ? fopen(argv[4], "r") : stdin;
    if (!script) return 1;
    char line[256];
    while (fgets(line, sizeof(line), script)) {
        command(line);
        // Audio isn't played: keep the core's buffers from filling.
        fflush(stdout);
    }
    pk_finish_save(core, PK_FINISH_SAVE_FRAMES, NULL);
    core->deinit(core);
    printf(failures ? "%d FAILED\n" : "ok\n", failures);
    return failures ? 1 : 0;
}
