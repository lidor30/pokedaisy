// Prints what the C port reads from a Game Boy cart, for gb_check.py to hold against the app's own reading (the game
// export's gbcheck/ folder): every dex page and picture, and with `wild` every map's wild lists at each clock.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "pd_dex.h"
#include "pd_games.h"
#include "pd_gb.h"
#include "pd_guide.h"
#include "pd_snapshot.h"
#include "pd_tables.h"

static void clean(const char* s) {
    for (; *s; s++) putchar(*s == '|' ? '/' : *s == '\n' ? '~' : *s);
}

int main(int argc, char** argv) {
    FILE* f = fopen(argv[1], "rb");
    if (!f) return 2;
    fseek(f, 0, SEEK_END);
    size_t n = (size_t) ftell(f);
    rewind(f);
    uint8_t* rom = malloc(n);
    if (fread(rom, 1, n, f) != n) return 2;
    fclose(f);
    static struct pd_game g;
    pd_game_detect(&g, rom, n);
    g.rom = rom;
    g.romSize = n;
    pd_game_identify(&g);
    if (g.kind != PD_GAME_GB || !g.cfg || !g.cfg->gb) return 3;
    if (argc > 2) {
        // The wild lists the GUIDE's HERE reads (pd_wild_here): every map at each time of day and badge count (Polished
        // Crystal's world scales levels by badges; the clock is its wTimeOfDay there, the snapshot's hour elsewhere).
        if (g.guide == PD_GUIDE_NONE) return 4;
        int gen = g.cfg->gb->gen;
        for (int tod = 0; tod < 4; tod++) {
            for (int b = 0; b < 3; b++) {
                int badges = b * 8;
                static struct pd_snapshot s;
                memset(&s, 0, sizeof(s));
                s.posOk = s.flagsOk = true;
                s.hour = tod;
                s.gbTimeOfDay = (uint8_t) tod;
                s.partyCount = 1;
                s.gbBanks[0][0] = (uint8_t) ((1 << (badges > 8 ? 8 : badges)) - 1);
                s.gbBanks[0][1] = badges == 16 ? 0xFF : 0;
                for (int key = 0; key < 0x10000; key++) {
                    if (gen == 1 && key > 255) break;
                    s.mapGroup = key >> 8;
                    s.mapNum = key & 0xFF;
                    static struct pd_wild_set set;
                    pd_wild_here(&g, &s, &set);
                    bool any = false;
                    for (int m = 0; m < PD_WILD_METHODS; m++) any |= set.count[m] > 0;
                    if (!any) continue;
                    printf("%d %d %d %d", tod, badges, s.mapGroup, s.mapNum);
                    for (int m = 0; m < PD_WILD_METHODS; m++) {
                        printf(" |");
                        for (int i = 0; i < set.count[m]; i++) {
                            const struct pd_wild* x = &set.mons[m][i];
                            printf(" %d:%d:%d:%d", x->species, x->minLv, x->maxLv, x->pct);
                        }
                    }
                    putchar('\n');
                }
            }
        }
        return 0;
    }
    int count = pd_dex_gb_count(&g);
    for (int dex = 1; dex <= count; dex++) {
        static struct pd_dex_entry e;
        if (!pd_dex_entry(&g, dex, &e)) continue;
        printf("%d|%d|", dex, e.species);
        clean(e.category);
        printf("|%d|%d|", e.heightDm, e.weightHg);
        clean(e.description);
        printf("|%d|%d|", e.type1, e.type2);
        for (int i = 0; i < e.statCount; i++) printf("%s%d", i ? "," : "", e.stats[i]);
        char ht[24], wt[24];
        pd_dex_measures(&g, &e, ht, sizeof(ht), wt, sizeof(wt));
        printf("|%d|%d|%s|%s|", e.heightIn, e.weightLbs10, ht, wt);
        static uint32_t px[PD_SPRITE * PD_SPRITE];
        bool ok = pd_dex_sprite(&g, e.species, px);
        uint32_t hash = 0;
        if (ok) {
            // The app's 56 x 56 box, clear as 0, opaque with its alpha.
            for (int y = 0; y < 56; y++) {
                for (int x = 0; x < 56; x++) {
                    uint32_t v = px[(y + 4) * PD_SPRITE + x + 4];
                    hash = hash * 31 + (v == 0xFF000000u ? 0 : 0xFF000000u | v);
                }
            }
        }
        printf("%d|%u\n", ok, hash);
    }
    return 0;
}
