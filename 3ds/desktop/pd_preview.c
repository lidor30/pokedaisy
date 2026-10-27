// pd_preview - renders the 3DS companion from a RAM fixture (the ewram.bin /
// iwram.bin dumps under app/src/test/resources/fixtures/) into PNGs laid out
// like the 3DS: the 400x240 top screen over the 320x240 bottom one. No ROM, no
// emulator: the 3DS port's ui-preview, for looking at every UI change.
//
//   pd_preview <fixture dir> <BPRE|BPGE|BPEE> <out dir> <name>
//
// writes <name>-party.png, -pressed, -summary, -battle, -bag, -bag-item,
// -bag-scrolled, -bag-pocket, -settings, -pick-screen and -confirm-save.
#include <stdio.h>
#include <assert.h>
#include <stdlib.h>
#include <string.h>

#include "../core/pd_canvas.h"
#include "../core/pd_card.h"
#include "../core/pd_romart.h"
#include "../core/pd_game.h"
#include "../core/pd_snapshot.h"
#include "../core/pd_ui.h"
#include "../core/pd_ui_internal.h"
#include "../core/pd_map.h"
#include "pd_png.h"

#define TOP_W 400
#define TOP_H 240

static uint8_t ewram[0x40000];
static uint8_t iwram[0x8000];

static bool load(const char* dir, const char* name, uint8_t* buf, size_t len) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    size_t n = fread(buf, 1, len, f);
    fclose(f);
    return n == len;
}

static bool save(const char* dir, const char* name, const uint8_t* buf, size_t len) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    FILE* f = fopen(path, "wb");
    if (!f) return false;
    size_t n = fwrite(buf, 1, len, f);
    return fclose(f) == 0 && n == len;
}

// The TRAINER CARD drawn whole (240x160, the game's screen behind it), as a
// PNG and the CRC32 of its ARGB bytes - TrainerCardTest's, which pins the CRCs
// of the cards that matched the games' own screens pixel for pixel.
static void card_png(const char* dir, const char* name, const char* what, const uint32_t* argb) {
    static uint32_t rgb[PD_CARD_W * PD_CARD_H];
    for (int i = 0; i < PD_CARD_W * PD_CARD_H; i++) rgb[i] = argb[i] & 0xFFFFFF;
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s-%s.png", dir, name, what);
    if (!pd_png_write(path, PD_CARD_W, PD_CARD_H, rgb)) {
        fprintf(stderr, "pd_preview: can't write %s\n", path);
        exit(1);
    }
    uint8_t bytes[PD_CARD_W * PD_CARD_H * 4];
    for (int i = 0; i < PD_CARD_W * PD_CARD_H; i++) {
        bytes[i * 4] = (uint8_t) (argb[i] >> 24);
        bytes[i * 4 + 1] = (uint8_t) (argb[i] >> 16);
        bytes[i * 4 + 2] = (uint8_t) (argb[i] >> 8);
        bytes[i * 4 + 3] = (uint8_t) argb[i];
    }
    printf("%s (crc %08x)\n", path, pd_crc32(bytes, sizeof(bytes)));
}

// The fixture's RAM, and the ROM when one was given (ctx: the game).
static bool fixture_read(void* ctx, uint32_t addr, void* out, size_t len) {
    if (pd_rom_read(ctx, addr, out, len)) return true;
    if (addr >= 0x02000000 && addr + len <= 0x02040000) {
        memcpy(out, ewram + (addr - 0x02000000), len);
        return true;
    }
    if (addr >= 0x03000000 && addr + len <= 0x03008000) {
        memcpy(out, iwram + (addr - 0x03000000), len);
        return true;
    }
    return false;
}

// Both screens in one picture: the top screen (here a stand-in for the game
// at 1x, centred) over the bottom one, centred like on the console.
// The UI being shot: with PD_PREVIEW_HITS set, each shot also prints where
// its tap targets are (id x y w h), for writing emulator test steps.
static const struct pd_ui* shotUi;
// PD_PREVIEW_SIZE=WxH: the companion alone at another canvas size (the Linux
// host's, which follows its screen: 426x240, 360x360 ...), no 3DS around it.
static int uiW = PD_UI_WIDTH, uiH = PD_UI_HEIGHT;

static void shot(const char* dir, const char* name, const char* what, const uint32_t* bottom) {
    static uint32_t img[TOP_W * TOP_H * 2];
    if (uiW != PD_UI_WIDTH || uiH != PD_UI_HEIGHT) {
        char path[1024];
        snprintf(path, sizeof(path), "%s/%s-%s.png", dir, name, what);
        if (!pd_png_write(path, uiW, uiH, bottom)) {
            fprintf(stderr, "pd_preview: can't write %s\n", path);
            exit(1);
        }
        printf("%s\n", path);
        return;
    }
    for (int i = 0; i < TOP_W * TOP_H * 2; i++) img[i] = 0x101010;
    struct pd_canvas top;
    pd_canvas_init(&top, TOP_W, TOP_H, img);
    pd_fill(&top, 0, 0, TOP_W, TOP_H, 0x202020);
    int gx = (TOP_W - 240) / 2, gy = (TOP_H - 160) / 2;
    pd_fill(&top, gx, gy, 240, 160, 0x404850);
    pd_text(&top, gx + 8, gy + 4, "GAME (fixture: no emulator)", 0xFFFFFF, 0x202020);
    for (int y = 0; y < PD_UI_HEIGHT; y++) {
        memcpy(img + (TOP_H + y) * TOP_W + (TOP_W - PD_UI_WIDTH) / 2, bottom + y * PD_UI_WIDTH, PD_UI_WIDTH * sizeof(uint32_t));
    }
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s-%s.png", dir, name, what);
    if (!pd_png_write(path, TOP_W, TOP_H * 2, img)) {
        fprintf(stderr, "pd_preview: can't write %s\n", path);
        exit(1);
    }
    printf("%s\n", path);
    if (shotUi && getenv("PD_PREVIEW_HITS")) {
        for (int i = 0; i < shotUi->hitCount; i++) {
            const struct pd_hit* h = &shotUi->hits[i];
            printf("  hit %d: %d %d %d %d\n", h->id, h->x, h->y, h->w, h->h);
        }
    }
}

// The stylus, driven by what the last draw recorded: each step finds its
// target by hit id, so the shots go through the same hit-testing a tap does.
struct preview {
    struct pd_ui* ui;
    struct pd_canvas* c;
    const struct pd_game* g;
    const struct pd_snapshot* s;
    const struct pd_host_info* host;
    const char* out;
    const char* name;
};

static void draw(struct preview* p) {
    pd_ui_draw(p->ui, p->c, p->g, p->s, p->host);
}

static bool has_hit(struct preview* p, int id) {
    for (int i = 0; i < p->ui->hitCount; i++)
        if (p->ui->hits[i].id == id) return true;
    return false;
}

// The first hit with an id in [lo, hi), or -1.
static int first_hit(struct preview* p, int lo, int hi) {
    for (int i = 0; i < p->ui->hitCount; i++)
        if (p->ui->hits[i].id >= lo && p->ui->hits[i].id < hi) return p->ui->hits[i].id;
    return -1;
}

static void centre_of(struct preview* p, int id, int* x, int* y) {
    for (int i = p->ui->hitCount - 1; i >= 0; i--) {
        const struct pd_hit* h = &p->ui->hits[i];
        if (h->id == id) {
            *x = h->x + h->w / 2;
            *y = h->y + h->h / 2;
            return;
        }
    }
    fprintf(stderr, "pd_preview (%s): nothing to tap with id %d\n", p->name, id);
    exit(1);
}

static void press(struct preview* p, int id, int dy) {
    int x, y;
    centre_of(p, id, &x, &y);
    pd_ui_touch(p->ui, x, y + dy, true);
    draw(p);
}

static void release(struct preview* p) {
    pd_ui_touch(p->ui, 0, 0, false);
    draw(p);
}

static void tap(struct preview* p, int id, int dy) {
    press(p, id, dy);
    release(p);
}

// Down on id, then moved by dy in a few steps, then up.
static void drag(struct preview* p, int id, int dy) {
    int x, y;
    centre_of(p, id, &x, &y);
    pd_ui_touch(p->ui, x, y, true);
    for (int i = 1; i <= 4; i++) pd_ui_touch(p->ui, x, y + dy * i / 4, true);
    release(p);
}

int main(int argc, char** argv) {
    if (argc != 5 && argc != 6) {
        fprintf(stderr, "usage: pd_preview <fixture dir> <BPRE|BPGE|BPEE> <out dir> <name> [rom]\n");
        return 2;
    }
    const char *dir = argv[1], *code = argv[2], *out = argv[3], *name = argv[4];
    if (!load(dir, "ewram.bin", ewram, sizeof(ewram)) || !load(dir, "iwram.bin", iwram, sizeof(iwram))) {
        fprintf(stderr, "pd_preview: %s needs ewram.bin (256 KB) and iwram.bin (32 KB)\n", dir);
        return 1;
    }
    uint8_t header[0xC0] = { 0 };
    memcpy(header + 0xAC, code, 4);
    header[0xBC] = strcmp(code, "BPEE") ? 1 : 0;
    struct pd_game game;
    pd_game_detect(&game, header, 0x1000000);
    // A ROM (pret's byte-identical builds stand in for retail) feeds the tabs
    // that read it: MAP, POKéDEX, GUIDE.
    if (argc == 6) {
        static uint8_t rom[32 << 20];
        FILE* f = fopen(argv[5], "rb");
        size_t n = f ? fread(rom, 1, sizeof(rom), f) : 0;
        if (f) fclose(f);
        if (n < 0xC0) {
            fprintf(stderr, "pd_preview: can't read the ROM %s\n", argv[5]);
            return 1;
        }
        pd_game_detect(&game, rom, n);
        game.rom = rom;
        game.romSize = n;
    }

    struct pd_snapshot snap;
    pd_snapshot_read(&snap, &game, fixture_read, &game);

    static uint32_t px[1024 * 768];
    const char* size = getenv("PD_PREVIEW_SIZE");
    if (size && (sscanf(size, "%dx%d", &uiW, &uiH) != 2 || uiW < 320 || uiH < 240 || uiW > 1024 || uiH > 768)) {
        fprintf(stderr, "pd_preview: PD_PREVIEW_SIZE is WxH, 320x240 to 1024x768\n");
        return 1;
    }
    struct pd_canvas c;
    pd_canvas_init(&c, uiW, uiH, px);
    struct pd_settings settings = { .screenMode = PD_SCREEN_SHARP, .ffSpeed = 2 };
    struct pd_ui ui;
    pd_ui_init(&ui, &settings);
    shotUi = &ui;
    pd_ui_update(&ui, &snap);

    struct pd_host_info host = { .count = 2, .hasState = true };
    host.labels[0] = "SOURCE";
    snprintf(host.values[0], sizeof(host.values[0]), "%s", name);
    host.labels[1] = "SPEED";
    snprintf(host.values[1], sizeof(host.values[1]), "100%% (60 FPS)");
    snprintf(host.stateWhen, sizeof(host.stateWhen), "10-09 12:30");

    struct preview p = { &ui, &c, &game, &snap, &host, out, name };
    draw(&p);
    // A battle fixture opens on BATTLE by itself.
    if (ui.tab != PD_TAB_PARTY) tap(&p, HIT_TAB + PD_TAB_PARTY, 0);
    shot(out, name, "party", px);

    // A stylus held on the second slot (the cursor's colours), then let go:
    // its summary opens.
    press(&p, HIT_SLOT + 1, 0);
    shot(out, name, "pressed", px);
    release(&p);
    shot(out, name, "summary", px);
    tap(&p, HIT_BACK, 0);

    // BATTLE has a chip only while a battle is on.
    ui.tab = PD_TAB_BATTLE;
    draw(&p);
    shot(out, name, "battle", px);
    if (has_hit(&p, HIT_BATTLE_SUGGEST)) {
        tap(&p, HIT_BATTLE_SUGGEST, 0);
        shot(out, name, "battle-suggest", px);
        tap(&p, HIT_BATTLE_INFO, 0);
    }

    // BAG: a tapped item shows its description; a drag scrolls the list; the
    // arrow opens the next pocket.
    tap(&p, HIT_TAB + PD_TAB_BAG, 0);
    shot(out, name, "bag", px);
    tap(&p, HIT_BAG_ROW + 2, 0);
    shot(out, name, "bag-item", px);
    drag(&p, HIT_BAG_LIST, -60);
    shot(out, name, "bag-scrolled", px);
    tap(&p, HIT_BAG_TAB + 1, 0);
    shot(out, name, "bag-pocket", px);

    // SETTINGS, its pick-list and a confirm. FF lit, as while it runs.
    host.ffOn = true;
    tap(&p, HIT_TAB + PD_TAB_SETTINGS, 0);
    shot(out, name, "settings", px);
    tap(&p, HIT_SETTING + SETTING_SCREEN, 0);
    shot(out, name, "pick-screen", px);
    tap(&p, HIT_OPTION + PD_SCREEN_STRETCH, 0);
    printf("action %d, screen mode %d\n", pd_ui_take_action(&ui), settings.screenMode);
    tap(&p, HIT_SETTING + SETTING_SAVE_STATE, 0);
    shot(out, name, "confirm-save", px);
    tap(&p, HIT_YES, 0);
    printf("action %d\n", pd_ui_take_action(&ui));
    host.ffOn = false;

    // MAP: where the player is, a tapped place, the cursor's other size, PLACES.
    tap(&p, HIT_TAB + PD_TAB_MAP, 0);
    shot(out, name, "map", px);
    if (game.rom) {
        tap(&p, HIT_MAP, 0);
        shot(out, name, "map-tapped", px);
        ui.mapBlink = true;
        draw(&p);
        shot(out, name, "map-blink", px);
        // Empty tiles keep a blank label and offer ME, rather than naming the player.
        for (int i = 0; i < ui.hitCount; i++) if (ui.hits[i].id == HIT_MAP) {
            struct pd_hit h = ui.hits[i];
            ui.touchX0 = h.x + 4 * h.w / PD_MAP_W;
            ui.touchY0 = h.y + 4 * h.h / PD_MAP_H;
            assert(ui_map_act(&ui, HIT_MAP));
            break;
        }
        assert(ui.mapPage >= 0 && ui.mapSel == -1 && ui.mapDungeon == -1);
        draw(&p);
        assert(has_hit(&p, HIT_MAP_ME));
        if (game.kind == PD_GAME_EMERALD) {
            for (int i = 0; i < ui.hitCount; i++) if (ui.hits[i].id == HIT_MAP) {
                struct pd_hit h = ui.hits[i];
                // Inside Emerald's bottom-right label, the text area must be white.
                for (int y = h.y + h.h - 26; y < h.y + h.h - 8; y++)
                    for (int x = h.x + h.w - 125; x < h.x + h.w - 9; x++)
                        assert(px[y * uiW + x] == 0xFFFFFF);
            }
        }
        shot(out, name, "map-empty", px);
        tap(&p, HIT_MAP_ME, 0);
        assert(ui.mapPage == -1 && ui.mapSel == -1 && ui.mapDungeon == -1);
        tap(&p, HIT_MAP_PLACES, 0);
        drag(&p, HIT_SCRIM, -200);
        shot(out, name, "map-places", px);
        pd_ui_back(&ui);
        draw(&p);
    }

    // POKéDEX: the list, scrolled, an entry, the next one, the other dex.
    // (In a battle DEX's chip is BATTLE's.)
    ui.tab = PD_TAB_DEX;
    draw(&p);
    shot(out, name, "dex", px);
    if (game.rom && ui.tab == PD_TAB_DEX && ui.hitCount > 0) {
        drag(&p, HIT_DEX_LIST, -120);
        shot(out, name, "dex-scrolled", px);
        ui.dexOpen = 6;
        draw(&p);
        shot(out, name, "dex-entry", px);
        tap(&p, HIT_DEX_NEXT, 0);
        shot(out, name, "dex-next", px);
        tap(&p, HIT_BACK, 0);
        tap(&p, HIT_DEX_TOGGLE, 0);
        shot(out, name, "dex-other", px);
    }

    // GUIDE: the first-open notice, then each page (an entry's hint and
    // answer on the first static one).
    ui.tab = PD_TAB_GUIDE;
    draw(&p);
    shot(out, name, "guide", px);
    if (has_hit(&p, HIT_NOTICE_OK)) {
        tap(&p, HIT_NOTICE_OK, 0);
        static const char* const PAGE_SHOTS[] = { "guide-p0", "guide-p1", "guide-p2", "guide-p3", "guide-p4" };
        for (int page = 0; page < 5 && has_hit(&p, HIT_GUIDE_PAGE + page); page++) {
            tap(&p, HIT_GUIDE_PAGE + page, 0);
            shot(out, name, PAGE_SHOTS[page], px);
            if (has_hit(&p, HIT_GUIDE_LIST)) {
                drag(&p, HIT_GUIDE_LIST, -100);
                if (ui.guideScroll > 0) {
                    char what[32];
                    snprintf(what, sizeof(what), "%s-scrolled", PAGE_SHOTS[page]);
                    shot(out, name, what, px);
                    drag(&p, HIT_GUIDE_LIST, 400);
                }
            }
        }
        // The end of HERE (PEOPLE, ITEMS).
        tap(&p, HIT_GUIDE_PAGE, 0);
        drag(&p, HIT_GUIDE_LIST, -4000);
        shot(out, name, "guide-p0-end", px);
        // A hint, then its answer, on the last page (STUCK? has hints).
        int last = 0;
        while (has_hit(&p, HIT_GUIDE_PAGE + last + 1)) last++;
        tap(&p, HIT_GUIDE_PAGE + last, 0);
        int row = first_hit(&p, HIT_GUIDE_ROW, HIT_GUIDE_ROW + 800);
        if (row >= 0) {
            tap(&p, row, 0);
            shot(out, name, "guide-hint", px);
            tap(&p, row, 0);
            shot(out, name, "guide-answer", px);
        }
    }

    // A trainer battle, made from a wild one: the wild foe is already
    // gEnemyParty[0]; three of the player's Pokémon (whole encrypted structs)
    // join it, the battle type gets BATTLE_TYPE_TRAINER, and the foe battler's
    // party index is 0. Then the first one faints and the trainer picks the
    // third (gBattleStruct->monToSwitchIntoId), and the last slot is tapped.
    if (snap.inBattle && game.cfg->enemyParty && snap.partyCount >= 5) {
        const struct pd_config* cfg = game.cfg;
        uint8_t* enemy = ewram + (cfg->enemyParty - 0x02000000);
        const uint8_t* party = ewram + (cfg->playerParty - 0x02000000);
        static const int FROM[3] = { 1, 2, 4 };
        for (int i = 0; i < 3; i++) memcpy(enemy + (i + 1) * 100, party + FROM[i] * 100, 100);
        ewram[cfg->battleTypeFlags - 0x02000000] |= 0x08;
        int foeBattler = 0;
        while (foeBattler < 4 && ewram[cfg->battlerPositions - 0x02000000 + foeBattler] != PD_POS_OPPONENT_LEFT) {
            foeBattler++;
        }
        uint8_t* idx = ewram + (cfg->battlerPartyIndexes - 0x02000000) + foeBattler * 2;
        idx[0] = idx[1] = 0;
        // PD_PREVIEW_TRAINER_DIR: keep this RAM as a fixture of its own, for
        // a fixture ROM the 3DS app can be run on (make fixture-roms).
        const char* keep = getenv("PD_PREVIEW_TRAINER_DIR");
        if (keep && !(save(keep, "ewram.bin", ewram, sizeof(ewram)) && save(keep, "iwram.bin", iwram, sizeof(iwram)))) {
            fprintf(stderr, "pd_preview: can't write the trainer battle to %s\n", keep);
            return 1;
        }
        pd_snapshot_read(&snap, &game, fixture_read, &game);
        pd_ui_update(&ui, &snap);
        ui.tab = PD_TAB_BATTLE;
        draw(&p);
        shot(out, name, "trainer", px);
        tap(&p, HIT_BATTLE_SUGGEST, 0);
        shot(out, name, "trainer-suggest", px);
        tap(&p, HIT_BATTLE_INFO, 0);

        // The first one faints (its party HP and the battler's), the trainer picks the third.
        enemy[0x56] = enemy[0x57] = 0;
        uint8_t* foeMon = ewram + (cfg->battleMons - 0x02000000) + foeBattler * 0x58;
        foeMon[0x28] = foeMon[0x29] = 0;
        uint32_t bs = 0;
        memcpy(&bs, ewram + (cfg->battleStructPtr - 0x02000000), 4);
        if (bs >= 0x02000000 && bs < 0x02040000) ewram[bs - 0x02000000 + cfg->monToSwitchIntoOff + foeBattler] = 2;
        pd_snapshot_read(&snap, &game, fixture_read, &game);
        pd_ui_update(&ui, &snap);
        draw(&p);
        shot(out, name, "trainer-next", px);
        tap(&p, HIT_FOE_SLOT + snap.foeCount - 1, 0);
        shot(out, name, "trainer-unseen", px);
        tap(&p, HIT_BATTLE_SUGGEST, 0);
        shot(out, name, "trainer-unseen-suggest", px);
        printf("foes %d, active %d, next %d\n", snap.foeCount, snap.foeActive, snap.foeNext);
    }

    // The TRAINER CARD, front and back, as the game draws it (TrainerCardTest's
    // renders: Emerald's front a minute later, 0:27, like its screenshot).
    struct pd_card_info card;
    if (game.rom && pd_card_info(&game, &snap, &card)) {
        static uint32_t img[PD_CARD_W * PD_CARD_H];
        printf("card: id %d, money %ld, badges %02x, stars %d, hof %06x, dex %d, %d:%02d\n", card.trainerId,
               card.money, card.badges, card.stars, card.hofDebut, card.dexCaught, card.hours, card.minutes);
        struct pd_card_info front = card;
        if (card.style == PD_CARD_HOENN) front.minutes = 27;
        if (pd_card_render(&game, &front, false, true, true, img)) card_png(out, name, "card-front-full", img);
        if (pd_card_render(&game, &card, true, true, true, img)) card_png(out, name, "card-back-full", img);

        // The CARD tab, from SETTINGS: the card, its colon blinked off, a flip
        // (squashing, then opening on the back) and the back.
        ui.overlay = PD_OVERLAY_NONE;
        tap(&p, HIT_TAB + PD_TAB_SETTINGS, 0);
        shot(out, name, "card-settings", px);
        tap(&p, HIT_OPEN_CARD, 0);
        shot(out, name, "card", px);
        pd_ui_tick(&ui, 1000);
        draw(&p);
        shot(out, name, "card-colon", px);
        tap(&p, HIT_CARD, 0);
        pd_ui_tick(&ui, 90);
        draw(&p);
        shot(out, name, "card-flip-1", px);
        pd_ui_tick(&ui, 110);
        draw(&p);
        shot(out, name, "card-flip-2", px);
        pd_ui_tick(&ui, 200);
        draw(&p);
        shot(out, name, "card-back", px);
    }

    // The decoded party, for checking against the app's tests.
    for (int i = 0; i < snap.partyCount; i++) {
        printf("party[%d] species=%d \"%s\" Lv%d %d/%d\n", i, snap.party[i].species, snap.party[i].nickname,
               snap.party[i].level, snap.party[i].hp, snap.party[i].maxHp);
    }
    printf("inBattle=%d mapsec=%d money=%ld\n", snap.inBattle, snap.mapsec, snap.money);
    printf("pos (%d,%d) map %d.%d %dx%d type %d gender %d; dex %d national %d; flags %d\n", snap.x, snap.y,
           snap.mapGroup, snap.mapNum, snap.mapW, snap.mapH, snap.mapType, snap.gender, snap.dexOk, snap.dexNational,
           snap.flagsOk);
    return 0;
}
