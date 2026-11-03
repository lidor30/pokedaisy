// make test-roms ROMS=<dir>: every ROM under <dir> (one level of folders) is recognised: a config, or a named game the
// companion says it can't read. "Unknown game" and "ROM hack" titles fail. Folders starting with _ or . are skipped.
#include <dirent.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "../core/pd_game.h"
#include "../core/pd_map.h"
#include "../core/pd_snapshot.h"
#include "../core/pd_tables.h"

static uint8_t rom[33 << 20];

static int check(const char* path, const char* name, int* ok, int* unsupported) {
    FILE* f = fopen(path, "rb");
    if (!f) return 0;
    size_t n = fread(rom, 1, sizeof rom, f);
    fclose(f);
    if (n < 0x150) return 0;
    struct pd_game g;
    pd_game_detect(&g, rom, n);
    g.rom = rom;
    g.romSize = n;
    pd_game_identify(&g);
    bool generic = !g.title || strstr(g.title, "Unknown") || strstr(g.title, "ROM hack") || strstr(g.title, "Game Boy ROM");
    if (generic) { printf("FAIL %s: not recognised (%s: %s)\n", name, g.title ? g.title : "?", g.unsupported ? g.unsupported : ""); return 1; }
    // A GBA game the companion reads names its map sections (WHERE IS, HERE, MAP): its own table, its ROM's
    // sMapNames or its engine's retail names - the app's activeMapSecData.
    if (g.cfg && g.kind != PD_GAME_GB) {
        int named = 0;
        const char* first = NULL;
        for (int m = 0; m < 0x200; m++) {
            const char* n = pd_mapsec_name(&g, m);
            if (n[0]) { named++; if (!first) first = n; }
        }
        const struct pd_text* t = g.cfg->text;
        // its items' descriptions come from its ROM (the app's RomItemText): item 4's (a POKé BALL in every game so far)
        const char* desc = pd_item_description(&g, 4);
        if (getenv("PD_ROMS_NAMES")) printf("  %s: item 4 '%s'\n", name, desc);
        // (a build guessed onto a neighbour's config may keep its table elsewhere: the app has none for it either)
        if (!desc[0] && !strstr(g.title, "unverified")) { printf("FAIL %s: no item description\n", name); return 1; }
        if (getenv("PD_ROMS_NAMES"))
            printf("  %s: %d place names (%s), first %s\n", name, named,
                   t && t->mapsecsRom ? "ROM" : t && t->mapsecs ? "own table" : "retail", first ? first : "-");
        if (named < 20) { printf("FAIL %s: %d place names\n", name, named); return 1; }
        // The FireRed 1.0 hacks the app reads its own region map for (RomRegionMap): the map is drawn from the ROM.
        static const char* const OWN_MAP[] = { "Unbound", "Gaia", "Odyssey", "Radical Red", "Amethyst", "GS Chronicles",
                                               "Sword and Shield", "Orange Islands", "Ash Gray", "Ghost Grey" };
        for (size_t k = 0; k < sizeof OWN_MAP / sizeof *OWN_MAP; k++) {
            if (!strstr(g.title, OWN_MAP[k]) || strstr(g.title, "unverified")) continue;
            const struct pd_map_art* art = pd_map_art(&g);
            int page, onMap = 0;
            struct pd_tile_rect r[4];
            for (int m = 0; m < 0x100; m++) onMap += pd_map_tiles(&g, m, &page, r, 4) > 0;
            if (getenv("PD_ROMS_NAMES")) printf("  %s: own map %d page(s), %d sections on it\n", name, art ? art->pages : 0, onMap);
            if (!art || onMap < 10) { printf("FAIL %s: its own region map isn't read\n", name); return 1; }
            // RomRegionMapTest's pins: pages, and sections' size / page / placement
            struct { const char* t; int pages, sec, page, w, h; } PIN[] = {
                { "Unbound", 1, 89, 0, 1, 1 }, { "Unbound", 1, 98, 0, 1, 3 }, { "Unbound", 1, 134, 0, 0, 0 },
                { "Odyssey", 1, 89, 0, 1, 1 }, { "Sword and Shield", 1, 0x58, 0, 1, 1 }, { "Sword and Shield", 1, 0x8F, 0, 0, 0 },
                { "Orange Islands", 4, 0x65, 1, 1, 1 }, { "Orange Islands", 4, 147, 2, -1, -1 },
                { "Ash Gray", 2, 88, 0, 1, 1 }, { "Ash Gray", 2, 171, 1, 1, 3 }, { "Ghost Grey", 4, -1, 0, 0, 0 },
            };
            for (size_t q = 0; q < sizeof PIN / sizeof *PIN; q++) {
                if (!strstr(g.title, PIN[q].t)) continue;
                int pg = -1, n = PIN[q].sec >= 0 ? pd_map_tiles(&g, PIN[q].sec, &pg, r, 4) : 0;
                bool okPin = art->pages == PIN[q].pages;
                if (PIN[q].sec >= 0 && PIN[q].w > 0) okPin = okPin && n == 1 && pg == PIN[q].page && r[0].w == PIN[q].w && r[0].h == PIN[q].h;
                if (PIN[q].sec >= 0 && PIN[q].w == 0) okPin = okPin && (n == 0 || r[0].w == 1);  // not placed: grid cells only
                if (PIN[q].sec >= 0 && PIN[q].w < 0) okPin = okPin && n > 0 && pg == PIN[q].page;
                if (!okPin) { printf("FAIL %s: map pin %d (pages %d, page %d, n %d)\n", name, PIN[q].sec, art->pages, pg, n); return 1; }
            }
        }
    }
    if (g.cfg) (*ok)++;
    else { (*unsupported)++; printf("  %s: %s (%s)\n", name, g.title, g.unsupported ? g.unsupported : "?"); }
    return 0;
}

int main(int argc, char** argv) {
    if (argc < 2) return 2;
    int bad = 0, ok = 0, unsupported = 0;
    DIR* d = opendir(argv[1]);
    if (!d) return 2;
    struct dirent* e;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.' || e->d_name[0] == '_') continue;
        char sub[1024];
        snprintf(sub, sizeof sub, "%s/%s", argv[1], e->d_name);
        DIR* s = opendir(sub);
        if (!s) continue;
        struct dirent* r;
        while ((r = readdir(s))) {
            const char* x = strrchr(r->d_name, '.');
            if (r->d_name[0] == '.' || !x || (strcmp(x, ".gba") && strcmp(x, ".gb") && strcmp(x, ".gbc"))) continue;
            char path[2048];
            snprintf(path, sizeof path, "%s/%s", sub, r->d_name);
            bad += check(path, r->d_name, &ok, &unsupported);
        }
        closedir(s);
    }
    closedir(d);
    printf("%s (%d with a config, %d recognised but unsupported)\n", bad ? "FAILED" : "PASS roms", ok, unsupported);
    return bad != 0;
}
