#include "pd_game.h"

#include <string.h>

#include "pd_games.h"

// The configs come from gamedata/ (gamedata/targets/3ds.toml picks the fields
// this port reads), generated with the app's: `make gamedata`.
#include "pd_configs_gen.h"

const uint8_t* pd_rom_at(const struct pd_game* g, uint32_t addr, size_t len) {
    if (!g->rom || addr < 0x08000000) return NULL;
    size_t off = addr - 0x08000000;
    if (off > g->romSize || len > g->romSize - off) return NULL;
    return g->rom + off;
}

unsigned pd_rom_id(const struct pd_game* g) {
    return g->rom ? g->loadId : 0;
}

bool pd_rom_read(const struct pd_game* g, uint32_t addr, void* out, size_t len) {
    const uint8_t* p = pd_rom_at(g, addr, len);
    if (!p) return false;
    memcpy(out, p, len);
    return true;
}

void pd_game_detect(struct pd_game* g, const uint8_t* header, size_t romSize) {
    static unsigned loads;
    memset(g, 0, sizeof(*g));
    g->loadId = ++loads ? loads : ++loads;
    memcpy(g->code, header + 0xAC, 4);
    g->code[4] = 0;
    g->revision = header[0xBC];

    if (!strcmp(g->code, "BPRE") || !strcmp(g->code, "BPGE")) {
        g->kind = PD_GAME_FIRERED;
        g->title = g->code[2] == 'R' ? "Pokémon FireRed" : "Pokémon LeafGreen";
        bool rev1 = g->revision == 1;
        if (g->code[2] == 'R') g->cfg = rev1 ? &PD_CONFIG_FIRERED_REV1 : &PD_CONFIG_FIRERED_REV0;
        else g->cfg = rev1 ? &PD_CONFIG_LEAFGREEN_REV1 : &PD_CONFIG_LEAFGREEN_REV0;
    } else if (!strcmp(g->code, "BPEE")) {
        g->kind = PD_GAME_EMERALD;
        g->title = "Pokémon Emerald";
        g->cfg = &PD_CONFIG_EMERALD;
    } else {
        g->title = "Unknown game";
        g->unsupported = "Not a game the 3DS companion knows yet";
        return;
    }
    if (g->revision > 1) {
        g->cfg = NULL;
        g->unsupported = "This revision isn't supported yet";
    } else if (romSize > 0x1000000) {
        // A >16 MB FireRed / Emerald is a ROM hack: the app tells them apart
        // by SHA1 (Poller.detect) - not ported yet.
        g->cfg = NULL;
        g->title = g->kind == PD_GAME_FIRERED ? "FireRed ROM hack" : "Emerald ROM hack";
        g->unsupported = "ROM hacks aren't supported on 3DS yet";
    }
}

const struct pd_game_entry* pd_game_entry_for(uint32_t crc32) {
    int lo = 0, hi = pd_game_entry_count - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        uint32_t c = pd_game_entries[mid].crc32;
        if (c == crc32) return &pd_game_entries[mid];
        if (c < crc32) lo = mid + 1;
        else hi = mid - 1;
    }
    return NULL;
}

bool pd_game_identify(struct pd_game* g, uint32_t crc32) {
    const struct pd_game_entry* e = pd_game_entry_for(crc32);
    if (!e) {
        pd_tables_use(NULL);
        return false;
    }
    g->kind = e->kind;
    g->title = e->title;
    g->cfg = e->cfg;
    g->tables = e->tables;
    g->entry = e;
    g->gb = e->gb;
    g->language = e->language;
    g->unsupported = NULL;
    pd_tables_use(e->tables);
    return true;
}
