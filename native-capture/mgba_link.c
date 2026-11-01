// mgba_link - two to four GBA games joined by a link cable, headless, scripted from stdin like mgba_dump. The games
// run on the app's own LINK PLAY code (app/src/main/cpp/pk_link.c: mGBA's lockstep link with Qt's
// MultiplayerController callbacks on one thread), so what passes here is what the app runs.
//
// stdin commands, one per line (SIDE = 0..3, or all / both for every game):
//   wait N                 - run N frames of game 0 (the others keep pace)
//   keys SIDE KEYS         - hold KEYS on SIDE from now on (NONE lets go); KEYS as mgba_dump's: A,B,START,UP,...
//   press SIDE KEYS        - hold KEYS on SIDE for one frame, then one frame without
//   hold SIDE KEYS N       - hold KEYS on SIDE for N frames, then one frame without
//   shot SIDE FILE         - the game's frame as a PPM (every game: FILE gets _0, _1, ... before its extension)
//   dump SIDE DIR          - DIR/ewram.bin + DIR/iwram.bin, as mgba_dump's `dump`
//   peek SIDE ADDR N       - print N bytes (hex) from the game's bus
//   poke8/poke16/poke32 SIDE ADDR VAL
//   link                   - print the link's state: SIO modes, phase, budgets, frames
//
// Usage: mgba_link [--verbose] ROM0 SAVE0 ROM1 SAVE1 [ROM2 SAVE2 [ROM3 SAVE3]] < commands.txt
// SAVE files are written by the games (pass copies, never a player's own save).
//
// Build from the repo root (it compiles the app's pk_link.c and pk_savevf.c):
//   docker run --rm -v "$(pwd):/repo" -w /repo/native-capture pokedaisy-capture
//       -c "gcc -O2 -Wall -o mgba_link mgba_link.c -I/usr/include -L/usr/lib/aarch64-linux-gnu -lmgba -lm"

#include <fcntl.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(__has_include)
#if __has_include(<mgba/flags.h>)
#include <mgba/flags.h>
#endif
#endif
#include <mgba/core/core.h>
#include <mgba/core/log.h>

#include "../app/src/main/cpp/pk_savevf.c"
#include "../app/src/main/cpp/pk_link.c"

enum {
    KEY_A = 1 << 0, KEY_B = 1 << 1, KEY_SELECT = 1 << 2, KEY_START = 1 << 3,
    KEY_RIGHT = 1 << 4, KEY_LEFT = 1 << 5, KEY_UP = 1 << 6, KEY_DOWN = 1 << 7,
    KEY_R = 1 << 8, KEY_L = 1 << 9,
};

static uint32_t keys[PK_LINK_MAX];

static bool runFrame(void) {
    for (int i = 0; i < pk_link_count(); i++) pk_link_set_keys(i, keys[i]);
    bool ok = pk_link_run_frame();
    if (!ok) fprintf(stderr, "mgba_link: lockstep fault at frame %u\n", pk_link_core(0)->frameCounter(pk_link_core(0)));
    return ok;
}

static int keyBitFor(const char* name) {
    static const struct { const char* name; int bit; } table[] = {
        { "A", KEY_A }, { "B", KEY_B }, { "SELECT", KEY_SELECT }, { "START", KEY_START }, { "RIGHT", KEY_RIGHT },
        { "LEFT", KEY_LEFT }, { "UP", KEY_UP }, { "DOWN", KEY_DOWN }, { "R", KEY_R }, { "L", KEY_L },
    };
    for (size_t i = 0; i < sizeof(table) / sizeof(table[0]); i++) if (!strcmp(name, table[i].name)) return table[i].bit;
    if (strcmp(name, "NONE")) fprintf(stderr, "mgba_link: unknown key %s\n", name);
    return 0;
}

static uint32_t parseKeys(char* s) {
    uint32_t mask = 0;
    for (char* tok = strtok(s, ","); tok; tok = strtok(NULL, ",")) mask |= keyBitFor(tok);
    return mask;
}

// "0".."3", or "all" / "both" -> a bit per game
static unsigned sides(const char* s) {
    if (!strcmp(s, "all") || !strcmp(s, "both")) return (1u << pk_link_count()) - 1;
    if (s[0] >= '0' && s[0] < '0' + pk_link_count() && !s[1]) return 1u << (s[0] - '0');
    fprintf(stderr, "mgba_link: unknown side %s (0..%d, all or both)\n", s, pk_link_count() - 1);
    return 0;
}

static int firstSide(unsigned which) {
    for (int i = 0; i < PK_LINK_MAX; i++) if (which & (1u << i)) return i;
    return 0;
}

static void shot(int i, const char* path) {
    FILE* f = fopen(path, "wb");
    if (!f) { fprintf(stderr, "mgba_link: cannot write %s\n", path); return; }
    unsigned w = pk_link_video_w(i), h = pk_link_video_h(i);
    const color_t* video = pk_link_video(i);
    fprintf(f, "P6\n%u %u\n255\n", w, h);
    for (unsigned k = 0; k < w * h; k++) {
        uint32_t c = (uint32_t) video[k];
        uint8_t rgb[3] = { (uint8_t) c, (uint8_t) (c >> 8), (uint8_t) (c >> 16) };
        fwrite(rgb, 1, 3, f);
    }
    fclose(f);
}

static void dumpRegion(struct mCore* core, uint32_t addr, int len, const char* path) {
    FILE* f = fopen(path, "wb");
    if (!f) { fprintf(stderr, "mgba_link: cannot write %s\n", path); return; }
    for (int k = 0; k < len; k++) fputc(core->busRead8(core, addr + k), f);
    fclose(f);
}

static void noopLog(struct mLogger* log, int category, enum mLogLevel level, const char* format, va_list args) {
    (void) log; (void) category; (void) level; (void) format; (void) args;
}
static struct mLogger silentLogger = { .log = noopLog, .filter = NULL };

int main(int argc, char** argv) {
    const char* paths[PK_LINK_MAX * 2];
    int nargs = 0;
    bool verbose = false;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--verbose")) verbose = true;
        else if (nargs < PK_LINK_MAX * 2) paths[nargs++] = argv[i];
    }
    if (nargs < 4 || nargs % 2) {
        fprintf(stderr, "usage: %s [--verbose] ROM0 SAVE0 ROM1 SAVE1 [ROM2 SAVE2 [ROM3 SAVE3]] < commands.txt\n", argv[0]);
        return 2;
    }
    if (!verbose) mLogSetDefaultLogger(&silentLogger);
    int n = nargs / 2;
    const char* roms[PK_LINK_MAX];
    const char* saves[PK_LINK_MAX];
    for (int i = 0; i < n; i++) {
        roms[i] = paths[i * 2];
        saves[i] = paths[i * 2 + 1];
    }
    if (!pk_link_open(n, roms, saves, 48000)) {
        fprintf(stderr, "mgba_link: cannot open the games\n");
        return 1;
    }
    fprintf(stderr, "mgba_link: %d games linked\n", n);

    char line[512];
    char status[512];
    while (fgets(line, sizeof(line), stdin)) {
        char* nl = strchr(line, '\n');
        if (nl) *nl = 0;
        if (line[0] == '#' || !line[0]) continue;
        char cmd[32] = {0}, side[16] = {0}, arg[256] = {0};
        int count = 0;
        if (sscanf(line, "%31s", cmd) != 1) continue;
        bool ok = true;

        if (!strcmp(cmd, "wait")) {
            sscanf(line, "%*s %d", &count);
            for (int k = 0; k < count && ok; k++) ok = runFrame();
        } else if (!strcmp(cmd, "keys")) {
            sscanf(line, "%*s %15s %255s", side, arg);
            unsigned which = sides(side);
            uint32_t mask = parseKeys(arg);
            for (int i = 0; i < n; i++) if (which & (1u << i)) keys[i] = mask;
        } else if (!strcmp(cmd, "press") || !strcmp(cmd, "hold")) {
            count = 1;
            sscanf(line, "%*s %15s %255s %d", side, arg, &count);
            unsigned which = sides(side);
            uint32_t mask = parseKeys(arg);
            for (int i = 0; i < n; i++) if (which & (1u << i)) keys[i] = mask;
            for (int k = 0; k < count && ok; k++) ok = runFrame();
            for (int i = 0; i < n; i++) if (which & (1u << i)) keys[i] = 0;
            if (ok) ok = runFrame();
        } else if (!strcmp(cmd, "shot")) {
            sscanf(line, "%*s %15s %255s", side, arg);
            unsigned which = sides(side);
            bool many = which & (which - 1);
            for (int i = 0; i < n; i++) {
                if (!(which & (1u << i))) continue;
                char path[300];
                if (many) {
                    const char* dot = strrchr(arg, '.');
                    int stem = dot ? (int) (dot - arg) : (int) strlen(arg);
                    snprintf(path, sizeof(path), "%.*s_%d%s", stem, arg, i, dot ? dot : "");
                } else {
                    snprintf(path, sizeof(path), "%s", arg);
                }
                shot(i, path);
            }
        } else if (!strcmp(cmd, "dump")) {
            sscanf(line, "%*s %15s %255s", side, arg);
            struct mCore* c = pk_link_core(firstSide(sides(side)));
            char path[300];
            snprintf(path, sizeof(path), "%s/ewram.bin", arg);
            dumpRegion(c, 0x02000000, 0x40000, path);
            snprintf(path, sizeof(path), "%s/iwram.bin", arg);
            dumpRegion(c, 0x03000000, 0x8000, path);
        } else if (!strcmp(cmd, "peek")) {
            unsigned addr = 0;
            sscanf(line, "%*s %15s %x %d", side, &addr, &count);
            unsigned which = sides(side);
            for (int i = 0; i < n; i++) {
                if (!(which & (1u << i))) continue;
                struct mCore* c = pk_link_core(i);
                printf("peek %d %08X:", i, addr);
                for (int k = 0; k < count; k++) printf(" %02X", c->busRead8(c, addr + k));
                printf("\n");
            }
        } else if (!strncmp(cmd, "poke", 4)) {
            unsigned addr = 0;
            int val = 0;
            sscanf(line, "%*s %15s %x %i", side, &addr, &val);
            unsigned which = sides(side);
            for (int i = 0; i < n; i++) {
                if (!(which & (1u << i))) continue;
                struct mCore* c = pk_link_core(i);
                if (!strcmp(cmd, "poke8")) c->busWrite8(c, addr, val);
                else if (!strcmp(cmd, "poke16")) c->busWrite16(c, addr, val);
                else c->busWrite32(c, addr, val);
            }
        } else if (!strcmp(cmd, "link")) {
            pk_link_status(status, sizeof(status));
            printf("link: %s\n", status);
        } else {
            fprintf(stderr, "mgba_link: unknown command '%s'\n", cmd);
        }
        fflush(stdout);
        fflush(stderr);
        if (!ok) {
            pk_link_status(status, sizeof(status));
            printf("link: %s\n", status);
            break;
        }
    }

    pk_link_close(600);
    return 0;
}
