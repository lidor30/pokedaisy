// The GUIDE per hack (the app's HackGuidesTest): the game's tables are where its config says (the probe trainer's name),
// and every boss the NEXT BOSS page lists has a team of 1-6 real Pokemon with levels 1-100 and moves, read from the
// ROM. Usage: pd_guide_test <dir with the ROMs>; a game without its ROM is skipped, a ROM whose tables are not where
// the config says (another build than the config's) is reported and skipped, not failed.
#include "../core/pd_game.c" // for the static PD_CONFIG_* tables
#include "../core/pd_guide.h"
#include <stdio.h>
#include <stdlib.h>

// Every ROM in the folder (and one level of folders under it) that pd_game_identify gives a guide is checked.
#include <dirent.h>

static int roms(const char* dir, char paths[][1024], int max, int n) {
    DIR* d = opendir(dir);
    if (!d) return n;
    struct dirent* e;
    while ((e = readdir(d)) && n < max) {
        if (e->d_name[0] == '.' || e->d_name[0] == '_') continue;
        char p[1024];
        snprintf(p, sizeof p, "%s/%s", dir, e->d_name);
        const char* x = strrchr(e->d_name, '.');
        if (x && !strcmp(x, ".gba")) snprintf(paths[n++], 1024, "%s", p);
    }
    closedir(d);
    return n;
}

static int by_name(const void* a, const void* b) { return strcmp(a, b); }

// NEXT BOSS on a save (the app's HackGuidesTest asserts): the first boss not beaten, in pd_boss_order's order.
static uint8_t ram[0x40000], iram[0x8000];
static bool rd(void* c, uint32_t a, void* o, size_t n) {
    (void) c;
    if (a >= 0x02000000 && a < 0x02040000 && n <= 0x40000 - (a - 0x02000000)) { memcpy(o, ram + a - 0x02000000, n); return true; }
    if (a >= 0x03000000 && a < 0x03008000 && n <= 0x8000 - (a - 0x03000000)) { memcpy(o, iram + a - 0x03000000, n); return true; }
    return false;
}
static bool load(const char* dir, const char* fx, const char* name, uint8_t* buf, size_t n) {
    char p[1024];
    snprintf(p, sizeof p, "%s/%s/%s", dir, fx, name);
    FILE* f = fopen(p, "rb");
    if (!f) return false;
    size_t got = fread(buf, 1, n, f);
    fclose(f);
    return got > 0;
}
struct next { const char* fixture; const struct pd_config* cfg; enum pd_guide_id guide; const char* title; int mapGroup; };
#define N(fx, C, G, t) { fx, &PD_CONFIG_##C, PD_GUIDE_##G, t, -1 }
static const struct next NEXT[] = {
    N("glazed", GLAZED, GLAZED, "LEADER SPARKY"),
    N("mariomon_v1_5_2", MARIOMON_V1_5_2, MARIOMON, "STAR 1 PEACH"), N("mariomon_v1_5_1", MARIOMON_V1_5_1, MARIOMON, "STAR 1 PEACH"),
    N("mariomon_v1_3", MARIOMON_V1_3, MARIOMON, "STAR 1 PEACH"), N("mariomon_v1_2_2", MARIOMON_V1_2_2, MARIOMON, "STAR 1 PEACH"),
    N("mariomon_v1_2_1", MARIOMON_V1_2_1, MARIOMON, "STAR 1 PEACH"),
    N("quetzal_084", QUETZAL_V0_8_4, QUETZAL_V084, "LEADERS TATE AND LIZA"),
    N("quetzal_es_084", QUETZAL_V0_8_4_ES, QUETZAL_V084, "LEADERS TATE AND LIZA"),
    N("quetzal_moemon_084", MOEMON_QUETZAL_V0_8_4, QUETZAL_V084, "LEADERS TATE AND LIZA"),
    N("quetzal_moemon_es_084", MOEMON_QUETZAL_V0_8_4_ES, QUETZAL_V084, "LEADERS TATE AND LIZA"),
    { "quetzal", &PD_CONFIG_QUETZAL_V9_1, PD_GUIDE_QUETZAL, "LEADER BROCK", 0x25 },
    N("imperium", IMPERIUM, IMPERIUM, "LEADER ROXANNE"),
    N("ultra_violet", ULTRA_VIOLET, ULTRA_VIOLET, "LEADER BROCK"),
    N("orange_islands", ORANGE_ISLANDS, ORANGE_ISLANDS, "LEADER CISSY"),
    N("ghost_grey", GHOST_GREY, GHOST_GREY, "LEADER ERIKA"),
    N("sword_shield_up", SWORD_SHIELD, SWORD_SHIELD, "LEADER MILO"),
    N("gs_chronicles", GS_CHRONICLES, GS_CHRONICLES, "LEADER FALKNER"),
    N("recharged_yellow", RECHARGED_YELLOW_V1_9_7, RECHARGED_YELLOW, "LEADER ERIKA"),
    N("recharged_yellow_v180", RECHARGED_YELLOW_V1_8_0, RECHARGED_YELLOW_V180, "LEADER ERIKA"),
    N("recharged_emerald", RECHARGED_EMERALD_V2_2_8, RECHARGED_EMERALD, "LEADERS TATE AND LIZA"),
    // the app only asserts these have a boss left (expansion hacks tables and teams): printed, not pinned
    N("lazarus", LAZARUS, LAZARUS, NULL), N("emerald_seaglass", SEAGLASS, SEAGLASS, NULL), N("soulgold", SOULGOLD, SOULGOLD, NULL),
    N("soulgold_v12", SOULGOLD_V1_2, SOULGOLD, NULL), N("heart_and_soul", HEART_AND_SOUL, HEART_AND_SOUL, NULL),
    N("dreamstone", DREAMSTONE, DREAMSTONE, NULL), N("amethyst", AMETHYST, AMETHYST, NULL), N("emerald_kaizo", EMERALD_KAIZO, EMERALD_KAIZO, NULL),
};

// HERE on maps the app's tests walked headless (expansion hacks HERE, Lazarus night wild sets).
static int grass(const struct pd_game* g, int group, int num, int hour, int species, bool want) {
    static struct pd_snapshot s;
    memset(&s, 0, sizeof s);
    s.cfg = g->cfg; s.posOk = true; s.mapGroup = group; s.mapNum = num; s.hour = hour; s.flagsOk = true;
    struct pd_wild_set w;
    if (!pd_wild_here(g, &s, &w)) return -1;
    int pct = 0;
    bool has = false;
    for (int i = 0; i < w.count[0]; i++) { pct += w.mons[0][i].pct; has |= w.mons[0][i].species == species; }
    return species && has != want ? -2 : pct;
}

static int here_checks(const struct pd_game* g, const char* name) {
    int bad = 0;
    struct { const struct pd_config* cfg; int group, num, hour, species; bool want; int pct; const char* what; } C[] = {
        { &PD_CONFIG_LAZARUS, 0, 79, -1, 0, false, 100, "Lazarus 0.79" },
        { &PD_CONFIG_LAZARUS, 0, 57, 12, 731, true, 100, "Lazarus 0.57 noon PIKIPEK" },
        { &PD_CONFIG_LAZARUS, 0, 57, 22, 731, false, 100, "Lazarus 0.57 22:00 no PIKIPEK" },
        { &PD_CONFIG_LAZARUS, 0, 57, 22, 163, true, 100, "Lazarus 0.57 22:00 HOOTHOOT" },
        { &PD_CONFIG_LAZARUS, 0, 57, 6, 163, true, 100, "Lazarus 0.57 6:00 HOOTHOOT" },
        { &PD_CONFIG_SEAGLASS, 0, 16, -1, 0, false, 100, "Seaglass 0.16" },
        { &PD_CONFIG_TMT2, 0, 32, -1, 0, false, 99, "TMT2 0.32 (11 land slots)" },
        { &PD_CONFIG_SOULGOLD, 0, 16, -1, 0, false, 100, "SoulGold 0.16" },
        { &PD_CONFIG_RECHARGED_YELLOW_V1_9_7, 3, 19, -1, 16, true, 100, "Recharged Yellow Route 1" },
        { &PD_CONFIG_RECHARGED_EMERALD_V2_2_5, 0, 29, -1, 0, false, 100, "Recharged Emerald Route 114" },
    };
    for (size_t i = 0; i < sizeof C / sizeof C[0]; i++) {
        if (C[i].cfg != g->cfg) continue;
        int got = grass(g, C[i].group, C[i].num, C[i].hour, C[i].species, C[i].want);
        if (got != C[i].pct) { printf("FAIL %s: HERE %s (%d)\n", name, C[i].what, got); bad++; }
        else printf("  HERE %s ok\n", C[i].what);
    }
    return bad;
}

static int next_bosses(const char* dir) {
    int bad = 0, n = 0;
    for (size_t i = 0; i < sizeof NEXT / sizeof NEXT[0]; i++) {
        const struct next* t = &NEXT[i];
        memset(ram, 0, sizeof ram);
        memset(iram, 0, sizeof iram);
        if (!load(dir, t->fixture, "ewram.bin", ram, sizeof ram) || !load(dir, t->fixture, "iwram.bin", iram, sizeof iram)) {
            printf("skip next %s (no fixture)\n", t->fixture);
            continue;
        }
        struct pd_game g;
        memset(&g, 0, sizeof g);
        g.cfg = t->cfg; g.guide = t->guide; g.kind = PD_GAME_EMERALD;
        static struct pd_snapshot s;
        pd_snapshot_read(&s, &g, rd, NULL);
        if (t->mapGroup >= 0) { s.posOk = true; s.mapGroup = t->mapGroup; }
        const struct pd_guide* gd = pd_guide_for(&g);
        int order[256], k = pd_boss_order(gd, &s, order, 256);
        const char* got = NULL;
        for (int j = 0; j < k && !got; j++) if (!pd_boss_done(&gd->bosses[order[j]], &s)) got = gd->bosses[order[j]].title;
        n++;
        if (!s.flagsOk || !got || (t->title && strcmp(got, t->title))) {
            printf("FAIL next %s: %s (want %s)\n", t->fixture, got ? got : "none", t->title ? t->title : "any");
            bad++;
        } else {
            printf("  next %s: %s\n", t->fixture, got);
        }
    }
    printf("next boss: %d saves\n", n);
    return bad;
}

int main(int argc, char** argv) {
    int bad = 0, games = 0, teams = 0;
    if (argc < 2) return 2;
    static char paths[1024][1024];
    int np = roms(argv[1], paths, 1024, 0);
    qsort(paths, (size_t) np, sizeof paths[0], by_name);
    for (int w = 0; w < np; w++) {
        FILE* f = fopen(paths[w], "rb");
        if (!f) continue;
        fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
        uint8_t* rom = malloc((size_t) n);
        if (!rom || fread(rom, 1, (size_t) n, f) != (size_t) n) { fclose(f); free(rom); bad++; continue; }
        fclose(f);
        struct pd_game g; memset(&g, 0, sizeof g);
        pd_game_detect(&g, rom, (size_t) n);
        g.rom = rom; g.romSize = (size_t) n; g.loadId = (unsigned) (w + 1);
        pd_game_identify(&g);
        const char* name = strrchr(paths[w], '/') + 1;
        struct { const char* name; } tt = { name }, *t = &tt;
        if (!g.cfg || g.kind == PD_GAME_GB || g.guide == PD_GUIDE_NONE) { free(rom); continue; }
        if (!pd_guide_rom_ok(&g)) { printf("skip %s: its tables are not where the config says (another build)\n", t->name); free(rom); continue; }
        const struct pd_guide* gd = pd_guide_for(&g);
        if (!gd) { printf("FAIL %s: no guide\n", t->name); bad++; free(rom); continue; }
        games++;
        int before = teams;
        static struct pd_snapshot s;
        memset(&s, 0, sizeof s);
        for (int b = 0; b < gd->bossCount; b++) {
            const struct pd_boss* boss = &gd->bosses[b];
            int ids[16], k = 0;
            ids[k++] = pd_boss_trainer(gd, boss, &s);
            for (const struct pd_variant* v = boss->variants; v && v->label && k < 15; v++) ids[k++] = v->trainer;
            if (boss->kind == PD_BOSS_BY_FLAGS) for (const uint16_t* a = boss->alts; a && a[0] && k < 15; a += 2) ids[k++] = a[1];
            if (boss->kind == PD_BOSS_BY_COUNT) {
                int nf = 0;
                for (const uint16_t* a = boss->alts; a && a[0]; a++) nf++;
                for (int c = 0; c <= nf && k < 15; c++) ids[k++] = (int) boss->countTeams[c];
            }
            if (boss->kind == PD_BOSS_VAR_ADD) for (int c = 1; c <= boss->addMax && k < 15; c++) ids[k++] = (int) boss->a + c;
            if (boss->kind == PD_BOSS_VAR_IS) ids[k++] = (int) boss->b;
            // every table the boss's picked trainers show in (NORMAL / HARD, Amethyst's four)
            for (int base = k, i = 0; boss->pickLabels && i < base; i++)
                for (int l = 1; boss->pickLabels[l] && k < 15; l++) ids[k++] = (ids[i] & 0xFFFF) | l << 16;
            for (int i = 0; i < k; i++) {
                struct pd_team_mon team[PD_TEAM_MAX];
                int cnt = pd_trainer_team(&g, ids[i], team);
                bool ok = cnt >= 1 && cnt <= PD_TEAM_MAX;
                for (int m = 0; ok && m < cnt; m++) {
                    bool moves = false;
                    for (int q = 0; q < 4; q++) moves |= team[m].moves[q] != 0;
                    ok = team[m].species > 0 && team[m].level >= 1 && team[m].level <= 100 && (moves || !g.cfg->learnsets);
                }
                teams++;
                if (!ok) { printf("FAIL %s %s (trainer %d: %d mons, first sp %d lv %d)\n", t->name, boss->title, ids[i], cnt, cnt ? team[0].species : 0, cnt ? team[0].level : 0); bad++; }
            }
        }
        printf("  %s: %d bosses, %d teams\n", t->name, gd->bossCount, teams - before);
        bad += here_checks(&g, t->name);
        free(rom);
    }
    if (argc > 2) bad += next_bosses(argv[2]);
    printf("%s (%d games, %d teams)\n", bad ? "FAILED" : "PASS guide", games, teams);
    return bad != 0;
}
