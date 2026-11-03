// The SHINY marks and shiny icons (the app's ShinyOddsTest / ShinyIconsScreenshotTest): retail Emerald by its hash gets
// vanilla's shiny check, the emerald_shiny_battle fixture's wild PIKACHU (made shiny by SHINY ODDS, headless) reads shiny
// and the lead doesn't, and the shiny icon is drawn in other colours (the shiny battle palette's) than the normal one.
// Usage: pd_shiny_test <fixtures dir> <dir with the ROMs>; skipped without the ROM.
#include "../core/pd_canvas.h"
#include "../core/pd_game.h"
#include "../core/pd_icon.h"
#include "../core/pd_romart.h"
#include "../core/pd_snapshot.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static uint8_t ram[0x40000], iram[0x8000];
static bool rd(void* c, uint32_t a, void* o, size_t n) {
    (void) c;
    if (a >= 0x02000000 && a < 0x02040000 && n <= 0x40000 - (a - 0x02000000)) { memcpy(o, ram + a - 0x02000000, n); return true; }
    if (a >= 0x03000000 && a < 0x03008000 && n <= 0x8000 - (a - 0x03000000)) { memcpy(o, iram + a - 0x03000000, n); return true; }
    return false;
}
static bool load(const char* path, uint8_t* buf, size_t n) {
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    size_t got = fread(buf, 1, n, f);
    fclose(f);
    return got > 0;
}
static uint32_t icon_crc(const struct pd_game* g, int species, bool shiny) {
    static uint32_t px[PD_ICON * PD_ICON];
    struct pd_canvas c = { .px = px, .w = PD_ICON, .h = PD_ICON, .cx0 = 0, .cy0 = 0, .cx1 = PD_ICON, .cy1 = PD_ICON };
    memset(px, 0, sizeof px);
    return pd_draw_mon_icon_shiny(&c, g, species, shiny, 0, 0, 1, false) ? pd_crc32((const uint8_t*) px, sizeof px) : 0;
}

int main(int argc, char** argv) {
    if (argc < 3) return 2;
    char p[1024];
    snprintf(p, sizeof p, "%s/Pokemon - Emerald Version.gba", argv[2]);
    FILE* f = fopen(p, "rb");
    if (!f) { printf("skip shiny (no Emerald ROM)\n"); return 0; }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t* rom = malloc((size_t) n);
    if (!rom || fread(rom, 1, (size_t) n, f) != (size_t) n) return 1;
    fclose(f);
    static struct pd_game g;
    pd_game_detect(&g, rom, (size_t) n);
    g.rom = rom; g.romSize = (size_t) n; g.loadId = 1;
    pd_game_identify(&g);
    int bad = 0;
    if (g.shinyBelow != 8) { printf("FAIL retail Emerald's shiny check %d (want 8)\n", g.shinyBelow); bad++; }
    snprintf(p, sizeof p, "%s/emerald_shiny_battle/ewram.bin", argv[1]);
    bool okE = load(p, ram, sizeof ram);
    snprintf(p, sizeof p, "%s/emerald_shiny_battle/iwram.bin", argv[1]);
    if (!okE || !load(p, iram, sizeof iram)) { printf("FAIL no emerald_shiny_battle fixture\n"); return 1; }
    static struct pd_snapshot s;
    pd_snapshot_read(&s, &g, rd, NULL);
    const struct pd_battle_mon* foe = &s.battlers[PD_POS_OPPONENT_LEFT];
    if (foe->species != 25 || !foe->shiny) { printf("FAIL the foe PIKACHU (sp %d) isn't shiny\n", foe->species); bad++; }
    if (s.battlers[PD_POS_PLAYER_LEFT].shiny || (s.partyCount && s.party[0].shiny)) { printf("FAIL the lead reads shiny\n"); bad++; }
    uint32_t a = icon_crc(&g, 25, false), b = icon_crc(&g, 25, true);
    if (!a || !b || a == b) { printf("FAIL PIKACHU's shiny icon (%08x vs %08x)\n", a, b); bad++; }
    // a game with no known shiny check never marks one
    g.shinyBelow = 0;
    pd_snapshot_read(&s, &g, rd, NULL);
    if (s.battlers[PD_POS_OPPONENT_LEFT].shiny) { printf("FAIL shiny without a check\n"); bad++; }
    printf("%s\n", bad ? "FAILED shiny" : "PASS shiny (retail Emerald: foe shiny, lead not, icon recoloured)");
    return bad != 0;
}
