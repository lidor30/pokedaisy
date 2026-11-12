#include "pd_library.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#include "pd_games.h"
#include "pd_library_internal.h"
#include "pd_save.h"
#include "pd_sha1.h"
#include "pd_ui.h"

// How much of a ROM is hashed a frame while the library names its games (a slice keeps the screen moving).
#define HASH_SLICE (192 * 1024)
#define HEADERS_A_FRAME 6

// --- names ---

// GameTitles.tidy: the file name minus its dump tags ("(USA, Europe)", "(v1.3.1)", "1636 - ").
static void tidy(const char* file, char* out, int len) {
    const char* dot = strrchr(file, '.');
    int n = dot ? (int) (dot - file) : (int) strlen(file);
    int o = 0;
    int i = 0;
    while (i < n && isdigit((unsigned char) file[i])) i++;
    if (i && i + 2 < n && file[i] == ' ' && file[i + 1] == '-') i += 3;
    else i = 0;
    int depth = 0;
    for (; i < n && o + 1 < len; i++) {
        char c = file[i];
        if (c == '(' || c == '[') depth++;
        else if (c == ')' || c == ']') depth--;
        else if (!depth) out[o++] = c;
    }
    while (o && out[o - 1] == ' ') o--;
    out[o] = 0;
}

// A cart in its No-Intro name ("Pokemon - FireRed Version (USA)", "Pocket Monsters - Ruby (Japan)") is retail; anything else
// the app knows by hash is a hack.
static bool retail_entry(const struct pd_game_entry* e) {
    return !strncmp(e->file, "Pokemon - ", 10) || !strncmp(e->file, "Pocket Monsters - ", 18);
}

static void name_from_entry(struct pd_library_game* g, const struct pd_game_entry* e) {
    snprintf(g->title, sizeof g->title, "%s", e->title);
    g->hack = !retail_entry(e);
    g->supported = true;
    g->named = true;
}

void pd_library_title(const char* file, char* out, int len, bool* hack, bool* supported) {
    char base[256];
    snprintf(base, sizeof(base), "%s", file);
    char* dot = strrchr(base, '.');
    if (dot) *dot = 0;
    *hack = false;
    *supported = false;
    for (int i = 0; i < pd_game_entry_count; i++) {
        const struct pd_game_entry* e = &pd_game_entries[i];
        if (strcmp(e->file, base)) continue;
        snprintf(out, (size_t) len, "%s", e->title);
        *hack = !retail_entry(e);
        *supported = true;
        return;
    }
    tidy(file, out, len);
}

static const struct pd_game_entry* entry_by_file(const char* file) {
    char base[256];
    snprintf(base, sizeof(base), "%s", file);
    char* dot = strrchr(base, '.');
    if (dot) *dot = 0;
    for (int i = 0; i < pd_game_entry_count; i++)
        if (!strcmp(pd_game_entries[i].file, base)) return &pd_game_entries[i];
    return NULL;
}

const struct pd_game_entry* lib_entry(const struct pd_library_game* g) {
    const struct pd_game_entry* e = g->sha1[0] ? pd_game_entry_for(g->sha1) : NULL;
    return e ? e : entry_by_file(g->file);
}

// A GBA Pokémon game's header code (BPR / BPG / BPE / AXV / AXP and the hacks' own few): one the app doesn't know by hash is
// a hack (an unknown build, or a cart edited).
static bool pokemon_code(const char* code) {
    static const char* const CODES[] = { "BPR", "BPG", "BPE", "AXV", "AXP", "MON", "PUV" };
    for (size_t i = 0; i < sizeof CODES / sizeof CODES[0]; i++)
        if (!strncmp(code, CODES[i], 3)) return true;
    return false;
}

static enum pd_console console_of(const char* file) {
    size_t len = strlen(file);
    if (len > 4 && !strcasecmp(file + len - 4, ".gbc")) return PD_CONSOLE_GBC;
    if (len > 3 && !strcasecmp(file + len - 3, ".gb")) return PD_CONSOLE_GB;
    return PD_CONSOLE_GBA;
}

// --- the list ---

static bool in_view(const struct pd_library_game* g, int view) {
    switch (view) {
    case PD_VIEW_RECENT: return g->lastPlayed != 0;
    case PD_VIEW_GBA: return g->console == PD_CONSOLE_GBA;
    case PD_VIEW_GB: return g->console != PD_CONSOLE_GBA;
    case PD_VIEW_HACKS: return g->hack;
    default: return true;
    }
}

static const struct pd_library* sortLibrary;
static bool sortRecent;

static int by_order(const void* a, const void* b) {
    const struct pd_library_game* x = &sortLibrary->games[*(const int*) a];
    const struct pd_library_game* y = &sortLibrary->games[*(const int*) b];
    if (sortRecent && x->lastPlayed != y->lastPlayed) return x->lastPlayed > y->lastPlayed ? -1 : 1;
    int c = strcasecmp(x->title, y->title);
    return c ? c : strcasecmp(x->file, y->file);
}

struct lib_list* lib_games_list(struct pd_library* l) {
    return &l->list;
}

bool lib_grid(const struct pd_library* l) {
    return l->settings && l->settings->libraryGrid;
}

static void relist(struct pd_library* l) {
    int keep = l->list.count ? l->list.items[l->list.selected] : -1;
    l->list.count = 0;
    for (int i = 0; i < l->count; i++)
        if (in_view(&l->games[i], l->view)) l->list.items[l->list.count++] = i;
    sortLibrary = l;
    sortRecent = l->view == PD_VIEW_RECENT || (l->settings && l->settings->libraryRecent);
    qsort(l->list.items, (size_t) l->list.count, sizeof(int), by_order);
    l->list.selected = 0;
    for (int i = 0; i < l->list.count; i++)
        if (l->list.items[i] == keep) l->list.selected = i;
    l->dirty = true;
}

void pd_library_init(struct pd_library* l, struct pd_settings* settings, const struct pd_library_host* host) {
    memset(l, 0, sizeof(*l));
    // A game played since may have changed any save: what the tools and the top screen read before is dropped.
    lib_saves_reset();
    lib_top_reset();
    lib_draw_reset();
    l->batteryPct = -1;
    l->settings = settings;
    l->host = host;
    l->picked = -1;
    l->pressedHit = HIT_NONE;
    l->columns = 3;
    l->pickRow = -1;
    l->hashing = -1;
    l->dirty = true;
}

void pd_library_refresh(struct pd_library* l) {
    for (int i = 0; i < l->count; i++) {
        struct pd_library_game* g = &l->games[i];
        g->console = console_of(g->file);
        const struct pd_game_entry* e = entry_by_file(g->file);
        if (e) name_from_entry(g, e);
        else if (!g->title[0]) tidy(g->file, g->title, sizeof g->title);
    }
    relist(l);
}

void pd_library_select(struct pd_library* l, const char* file) {
    for (int i = 0; file && i < l->list.count; i++)
        if (!strcmp(l->games[l->list.items[i]].file, file)) l->list.selected = i;
    l->dirty = true;
}

void pd_library_played(struct pd_library* l, int game, uint32_t now) {
    if (game < 0 || game >= l->count) return;
    l->games[game].lastPlayed = now ? now : 1;
    if (l->host && l->host->cache_changed) l->host->cache_changed(l->host->ctx);
}

// --- naming games by their contents ---

static void named_by_hash(struct pd_library* l, struct pd_library_game* g) {
    const struct pd_game_entry* e = g->sha1[0] ? pd_game_entry_for(g->sha1) : NULL;
    if (e) name_from_entry(g, e);
    else g->hack = g->console == PD_CONSOLE_GBA && pokemon_code(g->code);
    g->named = true;
    relist(l);
}

void pd_library_tick(struct pd_library* l) {
    l->frame++;
    lib_saves_tick(l);
    const struct pd_library_host* h = l->host;
    if (!h || !h->read_rom) return;
    // Headers first, a few a frame: the game code tells what the save tools can open, and what a hack is before its hash.
    int headers = 0;
    for (int i = 0; i < l->count && headers < HEADERS_A_FRAME; i++) {
        struct pd_library_game* g = &l->games[i];
        if (g->code[0] || g->console != PD_CONSOLE_GBA) continue;
        uint8_t header[0xC0];
        headers++;
        if (h->read_rom(h->ctx, g->file, 0, header, sizeof header) != (long) sizeof header) {
            snprintf(g->code, sizeof g->code, "----");
            continue;
        }
        for (int k = 0; k < 4; k++) g->code[k] = isalnum(header[0xAC + k]) ? (char) header[0xAC + k] : '-';
        g->code[4] = 0;
        if (!g->named && !g->sha1[0]) g->hack = pokemon_code(g->code);
        l->dirty = true;
    }
    if (headers) return;
    // Then one game's hash at a time, a slice a frame.
    static struct pd_sha1 sha;
    static uint8_t chunk[HASH_SLICE];
    if (l->hashing < 0) {
        for (int i = 0; i < l->count; i++) {
            struct pd_library_game* g = &l->games[i];
            if (g->named) continue;
            if (g->sha1[0]) {
                named_by_hash(l, g);
                continue;
            }
            l->hashing = i;
            l->hashedBytes = 0;
            pd_sha1_init(&sha);
            break;
        }
        if (l->hashing < 0) return;
    }
    struct pd_library_game* g = &l->games[l->hashing];
    long n = h->read_rom(h->ctx, g->file, l->hashedBytes, chunk, HASH_SLICE);
    if (n > 0) {
        pd_sha1_update(&sha, chunk, (size_t) n);
        l->hashedBytes += n;
    }
    if (n < HASH_SLICE) {
        if (n >= 0) {
            pd_sha1_hex(&sha, g->sha1);
            g->size = l->hashedBytes;
        }
        l->hashing = -1;
        named_by_hash(l, g);
        if (h->cache_changed) h->cache_changed(h->ctx);
    }
    l->dirty = true;
}

// --- the cache: "sha1 \t code \t size \t last played \t file" a line ---

void pd_library_cache_read(struct pd_library* l, const char* text) {
    while (text && *text) {
        const char* end = strchr(text, '\n');
        size_t len = end ? (size_t) (end - text) : strlen(text);
        char line[400];
        if (len < sizeof line) {
            memcpy(line, text, len);
            line[len] = 0;
            char* f[5];
            int n = 0;
            char* p = line;
            while (n < 5) {
                f[n++] = p;
                char* t = n < 5 ? strchr(p, '\t') : NULL;
                if (!t) break;
                *t = 0;
                p = t + 1;
            }
            if (n == 5) {
                for (int i = 0; i < l->count; i++) {
                    struct pd_library_game* g = &l->games[i];
                    if (strcmp(g->file, f[4])) continue;
                    long size = atol(f[2]);
                    // A file of another size under the same name is another game: its hash and code are read again.
                    if (strlen(f[0]) == 40 && (!g->size || g->size == size)) {
                        snprintf(g->sha1, sizeof g->sha1, "%s", f[0]);
                        g->size = size;
                    }
                    if (strlen(f[1]) == 4) snprintf(g->code, sizeof g->code, "%s", f[1]);
                    g->lastPlayed = (uint32_t) strtoul(f[3], NULL, 10);
                }
            }
        }
        text = end ? end + 1 : NULL;
    }
    for (int i = 0; i < l->count; i++)
        if (l->games[i].sha1[0] && !l->games[i].named) named_by_hash(l, &l->games[i]);
    relist(l);
}

size_t pd_library_cache_write(const struct pd_library* l, char* out, size_t len) {
    size_t at = 0;
    for (int i = 0; i < l->count && at < len; i++) {
        const struct pd_library_game* g = &l->games[i];
        if (!g->sha1[0] && !g->code[0] && !g->lastPlayed) continue;
        int n = snprintf(out + at, len - at, "%s\t%s\t%ld\t%lu\t%s\n", g->sha1, g->code, g->size, (unsigned long) g->lastPlayed, g->file);
        if (n < 0 || (size_t) n >= len - at) break;
        at += (size_t) n;
    }
    if (at < len) out[at] = 0;
    return at;
}

// --- SETTINGS ---

// The rows: a group title, or a setting with its value and (for a pick-list) how many choices.
enum row {
    R_LIBRARY, R_VIEW, R_SORT,
    R_SCREEN, R_THEME, R_DARK, R_SCREEN_MODE, R_ASPECT,
    R_SOUND, R_VOLUME, R_MUTE,
    R_BATTLE, R_STYLE, R_HINTS, R_INFO,
    R_FIELD, R_HUD, R_FLY,
    R_APP, R_VERSION, R_DEV,
    R_ROWS,
};

static int themes(int* ids) {
    int n = 0;
    for (int i = 0; i < pd_theme_count; i++)
        if (!pd_themes[i].seasonal && (!ids || (ids[n] = pd_themes[i].id, 1))) n++;
    return n;
}

int lib_settings_rows(const struct pd_library* l) {
    return l->host && l->host->devLine ? R_ROWS : R_ROWS - 1;
}

static const char* on_off(bool on) { return on ? "ON" : "OFF"; }

static const char* VOLUMES[5] = { "20%", "40%", "60%", "80%", "100%" };

const char* lib_settings_choice(const struct pd_library* l, int row, int choice) {
    (void) l;
    switch (row) {
    case R_THEME: {
        int ids[64];
        int n = themes(ids);
        for (int i = 0; i < pd_theme_count; i++)
            if (choice < n && pd_themes[i].id == ids[choice]) return pd_themes[i].label;
        return "";
    }
    case R_SCREEN_MODE: return pd_screen_mode_name(choice);
    case R_ASPECT: return pd_aspect_name(choice);
    case R_VOLUME: return choice >= 0 && choice < 5 ? VOLUMES[choice] : "";
    default: return "";
    }
}

const char* lib_settings_label(const struct pd_library* l, int row, const char** value, bool* group, int* choices) {
    const struct pd_settings* s = l->settings;
    static char text[48];
    *value = NULL;
    *group = false;
    *choices = 0;
    switch (row) {
    case R_LIBRARY: *group = true; return "LIBRARY";
    case R_VIEW: *value = s->libraryGrid ? "GRID" : "LIST"; return "VIEW";
    case R_SORT: *value = s->libraryRecent ? "LAST PLAYED" : "NAME"; return "SORT";
    case R_SCREEN: *group = true; return "SCREEN";
    case R_THEME: {
        *value = "";
        for (int i = 0; i < pd_theme_count; i++)
            if (pd_themes[i].id == s->theme) *value = pd_themes[i].label;
        *choices = themes(NULL);
        return "THEME";
    }
    case R_DARK: *value = on_off(s->darkMode); return "DARK MODE";
    case R_SCREEN_MODE: *value = pd_screen_mode_name(s->screenMode); *choices = PD_SCREEN_MODES; return "SCREEN";
    case R_ASPECT: *value = pd_aspect_name(s->aspect); *choices = PD_ASPECTS; return "ASPECT";
    case R_SOUND: *group = true; return "SOUND";
    case R_VOLUME: {
        int v = (s->gameVolume + 10) / 20 - 1;
        *value = VOLUMES[v < 0 ? 0 : v > 4 ? 4 : v];
        *choices = 5;
        return "GAME VOLUME";
    }
    case R_MUTE: *value = on_off(s->gameMuted); return "MUTE GAME";
    case R_BATTLE: *group = true; return "BATTLE";
    case R_STYLE: *value = s->battleGen5 ? "GEN 5" : "GEN 4"; return "BATTLE STYLE";
    case R_HINTS: *value = on_off(!s->hideHints); return "BATTLE HINTS";
    case R_INFO: *value = on_off(!s->noBattleInfo); return "BATTLE INFO";
    case R_FIELD: *group = true; return "FIELD";
    case R_HUD: *value = on_off(s->hud); return "HUD";
    case R_FLY: *value = s->flyClassic ? "CLASSIC" : "SCENIC"; return "FLY STYLE";
    case R_APP: *group = true; return "APP";
    case R_VERSION: *value = l->host && l->host->version ? l->host->version : ""; return "VERSION";
    case R_DEV:
        snprintf(text, sizeof text, "%s", l->host && l->host->devLine ? l->host->devLine : "");
        *value = text;
        return "DEV LINK";
    default: return "";
    }
}

static void changed(struct pd_library* l) {
    pd_look_apply(NULL, l->settings);
    if (l->host && l->host->settings_changed) l->host->settings_changed(l->host->ctx);
    l->dirty = true;
}

void lib_settings_act(struct pd_library* l, int row) {
    struct pd_settings* s = l->settings;
    const char* value;
    bool group;
    int choices;
    lib_settings_label(l, row, &value, &group, &choices);
    l->settingsSelected = row;
    if (group || row == R_VERSION || row == R_DEV) return;
    if (choices) {
        l->pickRow = row;
        l->pickSelected = 0;
        for (int i = 0; i < choices; i++)
            if (!strcmp(lib_settings_choice(l, row, i), value)) l->pickSelected = i;
        l->dirty = true;
        return;
    }
    switch (row) {
    case R_VIEW: s->libraryGrid = !s->libraryGrid; l->list.scroll = 0; break;
    case R_SORT: s->libraryRecent = !s->libraryRecent; break;
    case R_DARK: s->darkMode = !s->darkMode; break;
    case R_MUTE: s->gameMuted = !s->gameMuted; break;
    case R_STYLE: s->battleGen5 = !s->battleGen5; break;
    case R_HINTS: s->hideHints = !s->hideHints; break;
    case R_INFO: s->noBattleInfo = !s->noBattleInfo; break;
    case R_HUD: s->hud = !s->hud; break;
    case R_FLY: s->flyClassic = !s->flyClassic; break;
    default: return;
    }
    if (row == R_SORT || row == R_VIEW) relist(l);
    changed(l);
}

void lib_settings_pick(struct pd_library* l, int choice) {
    struct pd_settings* s = l->settings;
    int row = l->pickRow;
    l->pickRow = -1;
    l->dirty = true;
    if (choice < 0) return;
    switch (row) {
    case R_THEME: {
        int ids[64];
        if (choice < themes(ids)) s->theme = ids[choice];
        break;
    }
    case R_SCREEN_MODE: s->screenMode = choice; break;
    case R_ASPECT: s->aspect = choice; break;
    case R_VOLUME: s->gameVolume = (choice + 1) * 20; break;
    default: return;
    }
    changed(l);
}

static void settings_keys(struct pd_library* l, unsigned down) {
    int rows = lib_settings_rows(l);
    if (l->pickRow >= 0) {
        const char* value;
        bool group;
        int choices;
        lib_settings_label(l, l->pickRow, &value, &group, &choices);
        if (down & PD_LIB_UP && l->pickSelected > 0) l->pickSelected--;
        if (down & PD_LIB_DOWN && l->pickSelected < choices - 1) l->pickSelected++;
        if (down & PD_LIB_A) lib_settings_pick(l, l->pickSelected);
        if (down & PD_LIB_B) lib_settings_pick(l, -1);
        l->dirty = true;
        return;
    }
    int step = down & PD_LIB_UP ? -1 : down & PD_LIB_DOWN ? 1 : 0;
    if (step) {
        int r = l->settingsSelected;
        do r += step;
        while (r >= 0 && r < rows && (r == R_LIBRARY || r == R_SCREEN || r == R_SOUND || r == R_BATTLE || r == R_FIELD || r == R_APP));
        if (r >= 0 && r < rows) l->settingsSelected = r;
        l->settingsFollow = true;
        l->dirty = true;
    }
    if (down & PD_LIB_A) lib_settings_act(l, l->settingsSelected);
}

// --- input ---

static void open_page(struct pd_library* l, int page) {
    if (page == l->page) return;
    l->page = page;
    l->pickRow = -1;
    if (page == PD_PAGE_SETTINGS && l->settingsSelected < 1) l->settingsSelected = 1;
    l->settingsFollow = true;
    if (page == PD_PAGE_EDITOR || page == PD_PAGE_TRADE) lib_saves_open(l, page);
    l->dirty = true;
}

static void games_keys(struct pd_library* l, unsigned down) {
    bool grid = lib_grid(l);
    int dx = !!(down & PD_LIB_RIGHT) - !!(down & PD_LIB_LEFT), dy = !!(down & PD_LIB_DOWN) - !!(down & PD_LIB_UP);
    if (!grid) dx = 0;
    if (dx || dy) {
        lib_list_move(&l->list, dx, dy, l->columns, 0, grid);
        l->dirty = true;
    }
    if (down & (PD_LIB_L | PD_LIB_R)) {
        l->view = (l->view + (down & PD_LIB_R ? 1 : PD_VIEW_COUNT - 1)) % PD_VIEW_COUNT;
        l->list.selected = 0;
        l->list.scroll = 0;
        relist(l);
    }
    if (down & PD_LIB_A && l->list.count) l->picked = l->list.items[l->list.selected];
}

void pd_library_keys(struct pd_library* l, unsigned down) {
    if (!down) return;
    l->dirty = true;
    if (down & PD_LIB_START) {
        l->quit = true;
        return;
    }
    // SELECT steps through the pages (the rail's order), Y too; the save tools keep B while a save is open.
    bool busy = (l->page == PD_PAGE_EDITOR || l->page == PD_PAGE_TRADE) && lib_saves_busy(l);
    if ((down & PD_LIB_SELECT) && !busy) {
        open_page(l, (l->page + 1) % PD_PAGE_COUNT);
        return;
    }
    switch (l->page) {
    case PD_PAGE_GAMES: games_keys(l, down); break;
    case PD_PAGE_SETTINGS:
        if (down & PD_LIB_B && l->pickRow < 0) open_page(l, PD_PAGE_GAMES);
        else settings_keys(l, down);
        break;
    default:
        if (down & PD_LIB_B && !busy) open_page(l, PD_PAGE_GAMES);
        else lib_saves_keys(l, down);
        break;
    }
}

static void tap(struct pd_library* l, int hit) {
    if (hit >= HIT_RAIL && hit < HIT_RAIL + PD_PAGE_COUNT) {
        open_page(l, hit - HIT_RAIL);
        return;
    }
    if (l->page == PD_PAGE_GAMES) {
        if (hit >= HIT_VIEW && hit < HIT_VIEW + PD_VIEW_COUNT) {
            l->view = hit - HIT_VIEW;
            l->list.selected = 0;
            l->list.scroll = 0;
            relist(l);
            return;
        }
        int i = lib_list_hit(&l->list, hit);
        if (i >= 0) {
            // A tap on the game the cursor is on plays it; on another, moves the cursor there.
            if (i == l->list.selected) l->picked = l->list.items[i];
            l->list.selected = i;
        }
        return;
    }
    if (l->page == PD_PAGE_SETTINGS) {
        if (l->pickRow >= 0) {
            if (hit >= HIT_PICK && hit < HIT_PICK_CLOSE) lib_settings_pick(l, hit - HIT_PICK);
            else if (hit == HIT_PICK_CLOSE || hit == HIT_NONE) lib_settings_pick(l, -1);
            return;
        }
        if (hit >= HIT_SETTING && hit < HIT_PICK) lib_settings_act(l, hit - HIT_SETTING);
        return;
    }
    lib_saves_tap(l, hit);
}

void pd_library_touch(struct pd_library* l, int x, int y, bool down) {
    struct lib_list* list = l->page == PD_PAGE_GAMES ? &l->list : l->page == PD_PAGE_SETTINGS ? NULL : lib_saves_list(l);
    if (down) {
        if (!l->touching) {
            l->touching = true;
            l->dragged = false;
            l->touchX = x;
            l->touchY = l->lastY = y;
            l->pressedHit = lib_hit_at(x, y);
            l->dirty = true;
            if (l->pressedHit == HIT_SCROLL) lib_scroll_drag(y);
        } else if (l->pressedHit == HIT_SCROLL) {
            lib_scroll_drag(y);
            l->dirty = true;
        } else {
            if (abs(y - l->touchY) > 6 && (x > RAIL_W || l->touchX > RAIL_W)) l->dragged = true;
            if (l->dragged) {
                if (l->page == PD_PAGE_SETTINGS && l->pickRow < 0) l->settingsScroll += l->lastY - y;
                else if (list) list->scroll += l->lastY - y;
                l->lastY = y;
                l->pressedHit = HIT_NONE;
                l->dirty = true;
            }
        }
        return;
    }
    if (!l->touching) return;
    l->touching = false;
    l->dirty = true;
    int hit = l->pressedHit;
    l->pressedHit = HIT_NONE;
    if (l->dragged || hit == HIT_SCROLL) return;
    // The tap counts where it went down, if it's still on the same thing.
    if (lib_hit_at(l->touchX, l->touchY) != hit) return;
    tap(l, hit);
}
