#include "pd_card.h"

#include <stdio.h>
#include <string.h>

#include "pd_romart.h"

// --- the data (readTrainerCard) ---

// pokeemerald's sHoennToNationalOrder: the Hoenn dex's national numbers.
// Emerald Legacy's regional dex past Hoenn's (TrainerCard.kt LEGACY_HOENN_TO_NATIONAL).
static const uint16_t LEGACY_EXTRA[8] = { 77, 78, 198, 215, 220, 221, 228, 229 };

static const uint16_t HOENN_TO_NATIONAL[202] = {
    252, 253, 254, 255, 256, 257, 258, 259, 260, 261, 262, 263, 264, 265, 266, 267, 268, 269, 270, 271,
    272, 273, 274, 275, 276, 277, 278, 279, 280, 281, 282, 283, 284, 285, 286, 287, 288, 289, 63, 64,
    65, 290, 291, 292, 293, 294, 295, 296, 297, 118, 119, 129, 130, 298, 183, 184, 74, 75, 76, 299,
    300, 301, 41, 42, 169, 72, 73, 302, 303, 304, 305, 306, 66, 67, 68, 307, 308, 309, 310, 311,
    312, 81, 82, 100, 101, 313, 314, 43, 44, 45, 182, 84, 85, 315, 316, 317, 318, 319, 320, 321,
    322, 323, 218, 219, 324, 88, 89, 109, 110, 325, 326, 27, 28, 327, 227, 328, 329, 330, 331, 332,
    333, 334, 335, 336, 337, 338, 339, 340, 341, 342, 343, 344, 345, 346, 347, 348, 174, 39, 40, 349,
    350, 351, 120, 121, 352, 353, 354, 355, 356, 357, 358, 359, 37, 38, 172, 25, 26, 54, 55, 360,
    202, 177, 178, 203, 231, 232, 127, 214, 111, 112, 361, 362, 363, 364, 365, 366, 367, 368, 369, 222,
    170, 171, 370, 116, 117, 230, 371, 372, 373, 374, 375, 376, 377, 378, 379, 380, 381, 382, 383, 384,
    385, 386,
};

enum {
    STAT_FIRST_HOF_PLAY_TIME = 1,
    STAT_ENTERED_HOF = 10,
    STAT_POKEMON_TRADES = 21,
    STAT_LINK_BATTLE_WINS = 23,
    STAT_LINK_BATTLE_LOSSES = 24,
    STAT_POKEBLOCKS_WITH_FRIENDS = 34,
    STAT_WON_LINK_CONTEST = 35,
    STAT_UNION_ROOM_BATTLES = 50,
    STAT_BERRY_CRUSH_POINTS = 51,
};

static int capped(const struct pd_snapshot* s, int stat, uint32_t max) {
    uint32_t v = s->gameStats[stat];
    return (int) (v < max ? v : max);
}

bool pd_card_info(const struct pd_game* g, const struct pd_snapshot* s, struct pd_card_info* c) {
    const struct pd_config* cfg = g->cfg;
    memset(c, 0, sizeof(*c));
    if (!cfg || !cfg->cardStyle || !s->valid || !s->cardOk || !s->flagsOk || !s->dexOk) return false;
    c->style = cfg->cardStyle;
    while (c->nameLen < 7 && s->playerName[c->nameLen] != 0xFF) {
        c->name[c->nameLen] = s->playerName[c->nameLen];
        c->nameLen++;
    }
    c->female = s->gender == 1;
    c->trainerId = s->trainerId;
    c->hours = s->playHours < 999 ? s->playHours : 999;
    c->minutes = s->playMinutes < 59 ? s->playMinutes : 59;
    c->money = s->money >= 0 ? s->money : 0;

    bool national = s->dexNational && pd_var(s, cfg->nationalVar) == cfg->nationalVarValue &&
        pd_flag(s, cfg->nationalFlag);
    int caught = 0;
    if (national) {
        for (int n = 1; n <= PD_NATIONAL_COUNT; n++) caught += pd_dex_caught(s, n);
    } else if (cfg->cardStyle == PD_CARD_HOENN || cfg->cardStyle == PD_CARD_LEGACY) {
        for (int i = 0; i < 202; i++) caught += pd_dex_caught(s, HOENN_TO_NATIONAL[i]);
        if (cfg->cardStyle == PD_CARD_LEGACY)
            for (int i = 0; i < 8; i++) caught += pd_dex_caught(s, LEGACY_EXTRA[i]);
    } else {
        for (int n = 1; n <= 151; n++) caught += pd_dex_caught(s, n);
    }
    c->dexCaught = pd_flag(s, cfg->pokedexFlag) ? caught : -1;
    for (int i = 0; i < 8; i++) {
        if (pd_flag(s, cfg->badgeFlag + i)) c->badges |= 1 << i;
    }

    // GAME_STAT_FIRST_HOF_PLAY_TIME counts only once ENTERED_HOF is set; hours cap at 999:59:59.
    int hof = s->gameStats[STAT_ENTERED_HOF] ? (int) s->gameStats[STAT_FIRST_HOF_PLAY_TIME] : 0;
    if (((unsigned) hof >> 16) > 999) hof = 999 << 16 | 59 << 8 | 59;
    c->hofDebut = hof;
    // HasAllHoennMons: the Hoenn dex minus Jirachi and Deoxys.
    bool allHoenn = true;
    for (int i = 0; i < 200 && allHoenn; i++) allHoenn = pd_dex_caught(s, HOENN_TO_NATIONAL[i]);
    // Legacy's regional dex runs 210 (Hoenn's 202, JIRACHI and DEOXYS still left out of "all", then eight more).
    for (int i = 0; i < 8 && allHoenn && cfg->cardStyle == PD_CARD_LEGACY; i++) allHoenn = pd_dex_caught(s, LEGACY_EXTRA[i]);
    c->stars = (hof != 0) + allHoenn;
    if (cfg->extraStarFlag && pd_flag(s, cfg->extraStarFlag)) c->stars++;
    if (cfg->museumWinnersOff) {
        bool all = true;
        for (int i = 0; i < 5; i++) all &= s->museumWinners[i] != 0;
        c->stars += all;
    }
    if (cfg->frontierSymbolFlag) {
        bool all = true;
        for (int i = 0; i < 7; i++) {
            all &= pd_flag(s, cfg->frontierSymbolFlag + 2 * i) && pd_flag(s, cfg->frontierSymbolFlag + 2 * i + 1);
        }
        c->stars += all;
    }

    bool hoenn = cfg->cardStyle == PD_CARD_HOENN || cfg->cardStyle == PD_CARD_LEGACY;
    c->linkWins = capped(s, STAT_LINK_BATTLE_WINS, 9999);
    c->linkLosses = capped(s, STAT_LINK_BATTLE_LOSSES, 9999);
    c->trades = capped(s, STAT_POKEMON_TRADES, 0xFFFF);
    c->unionRoom = hoenn ? 0 : capped(s, STAT_UNION_ROOM_BATTLES, 0xFFFF);
    c->berryCrush = hoenn ? 0 : capped(s, STAT_BERRY_CRUSH_POINTS, 0xFFFF);
    c->linkContests = hoenn ? capped(s, STAT_WON_LINK_CONTEST, 999) : 0;
    c->linkPokeblocks = hoenn ? capped(s, STAT_POKEBLOCKS_WITH_FRIENDS, 0xFFFF) : 0;
    c->battlePoints = cfg->frontierBpOff ? s->battlePoints : 0;
    for (int i = 0; cfg->stickerVar && i < 3; i++) c->stickers[i] = pd_var(s, cfg->stickerVar + i);
    return true;
}

// --- the art (TrainerCardArt.render) ---

struct blobs {
    int gfx, front, back, bg, pals[6], femalePal, badgesPal, badgesGfx; // pals: one per star count; [5] -1 = none
    int picMale, picMalePal, picFemale, picFemalePal, font, widths;
    int stickers[5]; // FireRed: the tiles, then a palette per brag level; -1 = none
};

static const struct blobs KANTO = {
    PD_FR_CARD_GFX, PD_FR_CARD_FRONT, PD_FR_CARD_BACK, PD_FR_CARD_BG,
    { PD_FR_CARD_PAL0, PD_FR_CARD_PAL1, PD_FR_CARD_PAL2, PD_FR_CARD_PAL3, PD_FR_CARD_PAL4, -1 },
    PD_FR_CARD_FEMALE_PAL, PD_FR_CARD_BADGES_PAL, PD_FR_CARD_BADGES_GFX,
    PD_FR_PIC_RED, PD_FR_PIC_RED_PAL, PD_FR_PIC_LEAF, PD_FR_PIC_LEAF_PAL,
    PD_FR_FONT_NORMAL, PD_FR_FONT_NORMAL_WIDTHS,
    { PD_FR_CARD_STICKERS_GFX, PD_FR_CARD_STICKER_PAL1, PD_FR_CARD_STICKER_PAL2, PD_FR_CARD_STICKER_PAL3,
      PD_FR_CARD_STICKER_PAL4 },
};

static const struct blobs HOENN = {
    PD_EM_CARD_GFX, PD_EM_CARD_FRONT, PD_EM_CARD_BACK, PD_EM_CARD_BG,
    { PD_EM_CARD_PAL0, PD_EM_CARD_PAL1, PD_EM_CARD_PAL2, PD_EM_CARD_PAL3, PD_EM_CARD_PAL4, -1 },
    PD_EM_CARD_FEMALE_PAL, PD_EM_CARD_BADGES_PAL, PD_EM_CARD_BADGES_GFX,
    PD_EM_PIC_BRENDAN, PD_EM_PIC_BRENDAN_PAL, PD_EM_PIC_MAY, PD_EM_PIC_MAY_PAL,
    PD_EM_FONT_NORMAL, PD_EM_FONT_NORMAL_WIDTHS,
    { -1, -1, -1, -1, -1 },
};

// Emerald Legacy (TrainerCardArt.kt LEGACY): HOENN's, the fifth star on LeafGreen's black palette.
static const struct blobs LEGACY = {
    PD_EM_CARD_GFX, PD_EM_CARD_FRONT, PD_EM_CARD_BACK, PD_EM_CARD_BG,
    { PD_EM_CARD_PAL0, PD_EM_CARD_PAL1, PD_EM_CARD_PAL2, PD_EM_CARD_PAL3, PD_EM_CARD_PAL4, PD_LG_CARD_PAL5 },
    PD_EM_CARD_FEMALE_PAL, PD_EM_CARD_BADGES_PAL, PD_EM_CARD_BADGES_GFX,
    PD_EM_PIC_BRENDAN, PD_EM_PIC_BRENDAN_PAL, PD_EM_PIC_MAY, PD_EM_PIC_MAY_PAL,
    PD_EM_FONT_NORMAL, PD_EM_FONT_NORMAL_WIDTHS,
    { -1, -1, -1, -1, -1 },
};

// Per style: the text window's width, FONT_NORMAL's glyph height (FireRed 14
// rows, Emerald 16; RenderText adds letter spacing only to Japanese text, so
// none here), the digits' pad (FireRed a space, Emerald CHAR_SPACER), the
// stars' / badges' tile rows and the trainer pic's offset in its window.
struct layout {
    int textW, glyphH, pad, starY, badgeY, picX, picY;
};
static const struct layout KANTO_LAYOUT = { 27 * 8, 14, 0x00, 7, 16, 13, 4 };
static const struct layout HOENN_LAYOUT = { 28 * 8, 16, 0x77, 7, 15, 1, 0 };

#define TEXT 0xFF636363u
#define TEXT_SHADOW 0xFFD6D6CEu
#define STAT 0xFFE70808u
#define STAT_SHADOW 0xFFFFBD73u

struct art {
    const uint8_t* d[PD_BLOB_COUNT];
    uint32_t* img;
    const struct layout* lay;
};

static uint32_t expand(uint32_t v) {
    return v << 3 | v >> 2;
}

// BGR555 -> opaque ARGB (RomArt.palette).
static void palette(const uint8_t* b, int colours, uint32_t* out) {
    for (int i = 0; i < colours; i++) {
        uint32_t c = (uint32_t) (b[i * 2] | b[i * 2 + 1] << 8);
        out[i] = 0xFF000000u | expand(c & 31) << 16 | expand(c >> 5 & 31) << 8 | expand(c >> 10 & 31);
    }
}

static int u16at(const uint8_t* b, int i) {
    return b[i * 2] | b[i * 2 + 1] << 8;
}

// One 8x8 4bpp BG tile through tilemap entry e at tile (tx, ty); colour 0 left alone.
static void tile(uint32_t* img, const uint8_t* tiles, size_t tilesLen, const uint32_t* pal, int e, int tx, int ty) {
    if (tx < 0 || tx >= 30 || ty < 0 || ty >= 20) return;
    int t = e & 0x3FF, pn = e >> 12;
    for (int y = 0; y < 8; y++) {
        for (int x = 0; x < 8; x++) {
            int sx = e & 0x400 ? 7 - x : x;
            int sy = e & 0x800 ? 7 - y : y;
            size_t at = (size_t) t * 32 + (size_t) (sy * 4 + sx / 2);
            int byte = at < tilesLen ? tiles[at] : 0;
            int v = sx & 1 ? byte >> 4 & 15 : byte & 15;
            if (v) img[(ty * 8 + y) * PD_CARD_W + tx * 8 + x] = pal[pn * 16 + v];
        }
    }
}

// The 64x64 front pic, blitted into its 72x80 window at tile (19, 5), clipped to it.
static void pic(uint32_t* img, const uint8_t* tiles, const uint32_t* pal, const struct layout* lay) {
    int wx = 19 * 8, wy = 5 * 8;
    for (int y = 0; y < 64; y++) {
        for (int x = 0; x < 64; x++) {
            int px = lay->picX + x, py = lay->picY + y;
            if (px >= 72 || py >= 80) continue;
            int byte = tiles[((y / 8) * 8 + x / 8) * 32 + (y % 8) * 4 + (x % 8) / 2];
            int v = x & 1 ? byte >> 4 & 15 : byte & 15;
            if (v) img[(wy + py) * PD_CARD_W + wx + px] = pal[v];
        }
    }
}

// --- text: FONT_NORMAL into the text window (tile 1,1), 16x16 2bpp glyphs ---

// A string in the games' encoding.
struct str {
    int n;
    uint8_t c[48];
};

static void add(struct str* s, int ch) {
    if (s->n < (int) sizeof(s->c)) s->c[s->n++] = (uint8_t) ch;
}

static void cat(struct str* s, const struct str* t) {
    for (int i = 0; i < t->n; i++) add(s, t->c[i]);
}

// ASCII (and é / ¥) in the games' character encoding (enc).
static struct str enc(const char* text) {
    struct str s = { 0 };
    for (const unsigned char* p = (const unsigned char*) text; *p; p++) {
        int ch = *p;
        if (ch == 0xC3 && p[1] == 0xA9) { // é
            add(&s, 0x1B);
            p++;
            continue;
        }
        if (ch == 0xC2 && p[1] == 0xA5) { // ¥
            add(&s, 0xB7);
            p++;
            continue;
        }
        if (ch >= 'A' && ch <= 'Z') add(&s, 0xBB + ch - 'A');
        else if (ch >= 'a' && ch <= 'z') add(&s, 0xD5 + ch - 'a');
        else if (ch >= '0' && ch <= '9') add(&s, 0xA1 + ch - '0');
        else if (ch == ' ') add(&s, 0x00);
        else if (ch == ':') add(&s, 0xF0);
        else if (ch == '.') add(&s, 0xAD);
        else if (ch == '\'') add(&s, 0xB4);
        else if (ch == '&') add(&s, 0x2D);
        else if (ch == '/') add(&s, 0xBA);
        else if (ch == '-') add(&s, 0xAE);
        else add(&s, 0xAC); // '?'
    }
    return s;
}

enum { LEFT, RIGHT, LEADING_ZEROS };

// ConvertIntToDecimalStringN: n digits at most; RIGHT pads with pad, LEFT doesn't.
static struct str digits(int value, int n, int mode, int pad) {
    char buf[16];
    snprintf(buf, sizeof(buf), "%d", value < 0 ? 0 : value);
    const char* d = buf;
    int len = (int) strlen(buf);
    if (len > n) {
        d += len - n;
        len = n;
    }
    struct str s = { 0 };
    if (mode != LEFT) {
        for (int i = len; i < n; i++) add(&s, mode == LEADING_ZEROS ? 0xA1 : pad);
    }
    for (int i = 0; i < len; i++) add(&s, 0xA1 + d[i] - '0');
    return s;
}

static int width(const struct art* a, const struct blobs* b, const struct str* s) {
    int w = 0;
    for (int i = 0; i < s->n; i++) w += a->d[b->widths][s->c[i]];
    return w;
}

static void print(const struct art* a, const struct blobs* b, int x0, int y, const struct str* s, uint32_t fg,
                  uint32_t shadow) {
    const uint8_t* font = a->d[b->font];
    int x = x0;
    for (int i = 0; i < s->n; i++) {
        int g = s->c[i];
        for (int py = 0; py < a->lay->glyphH; py++) {
            for (int px = 0; px < 16; px++) {
                int wx = x + px, wy = y + py;
                if (wx < 0 || wx >= a->lay->textW || wy < 0 || wy >= 144) continue;
                int base = g * 64 + ((py / 8) * 2 + px / 8) * 16 + (py % 8) * 2;
                int row = font[base] | font[base + 1] << 8;
                int v = row >> (14 - 2 * (px % 8)) & 3;
                if (v == 1) a->img[(8 + wy) * PD_CARD_W + 8 + wx] = fg;
                else if (v == 2) a->img[(8 + wy) * PD_CARD_W + 8 + wx] = shadow;
            }
        }
        x += a->d[b->widths][g];
    }
}

static void say(const struct art* a, const struct blobs* b, int x, int y, const struct str* s) {
    print(a, b, x, y, s, TEXT, TEXT_SHADOW);
}

static void say_red(const struct art* a, const struct blobs* b, int x, int y, const struct str* s) {
    print(a, b, x, y, s, STAT, STAT_SHADOW);
}

static struct str name_of(const struct pd_card_info* c) {
    struct str s = { 0 };
    for (int i = 0; i < c->nameLen; i++) add(&s, c->name[i]);
    return s;
}

static struct str hof_time(int hof, int pad) {
    struct str s = digits((unsigned) hof >> 16, 3, RIGHT, pad);
    struct str colon = enc(":"), mm = digits(hof >> 8 & 0xFF, 2, LEADING_ZEROS, pad);
    struct str ss = digits(hof & 0xFF, 2, LEADING_ZEROS, pad);
    cat(&s, &colon);
    cat(&s, &mm);
    cat(&s, &colon);
    cat(&s, &ss);
    return s;
}

static void kanto_text(const struct art* a, const struct blobs* b, const struct pd_card_info* c, bool back,
                       bool colon) {
    struct str s, t;
    if (!back) {
        s = enc("NAME: ");
        t = name_of(c);
        cat(&s, &t);
        say(a, b, 20, 29, &s);
        s = enc("IDNo.");
        t = digits(c->trainerId, 5, LEADING_ZEROS, 0);
        cat(&s, &t);
        say(a, b, 142, 10, &s);
        struct str money = enc("¥");
        t = digits((int) c->money, 6, LEFT, 0);
        cat(&money, &t);
        s = enc("MONEY");
        say(a, b, 20, 56, &s);
        say(a, b, (134 - 6 * money.n) & 0xFF, 56, &money);
        if (c->dexCaught >= 0) {
            t = digits(c->dexCaught, 3, LEFT, 0);
            s = enc("POKéDEX");
            say(a, b, 20, 72, &s);
            say(a, b, (136 - 6 * t.n) & 0xFF, 72, &t);
        }
        s = enc("TIME");
        say(a, b, 20, 88, &s);
        t = digits(c->hours, 3, RIGHT, 0x00);
        say(a, b, 101, 88, &t);
        if (colon) {
            s = enc(":");
            say(a, b, 119, 88, &s);
        }
        t = digits(c->minutes, 2, LEADING_ZEROS, 0);
        say(a, b, 124, 88, &t);
        return;
    }
    t = name_of(c);
    say(a, b, 138, 11, &t);
    if (c->hofDebut) {
        s = enc("HALL OF FAME DEBUT  ");
        say(a, b, 10, 35, &s);
        t = hof_time(c->hofDebut, 0x00);
        say_red(a, b, 164, 35, &t);
    }
    if (c->linkWins || c->linkLosses) {
        s = enc("LINK BATTLES");
        say(a, b, 10, 51, &s);
        s = enc("W:");
        say(a, b, 130, 51, &s);
        s = enc("L:");
        say(a, b, 130 + 0x30, 51, &s);
        t = digits(c->linkWins, 4, RIGHT, 0x00);
        say_red(a, b, 144, 51, &t);
        t = digits(c->linkLosses, 4, RIGHT, 0x00);
        say_red(a, b, 192, 51, &t);
    }
    if (c->trades) {
        s = enc("POKéMON TRADES");
        say(a, b, 10, 67, &s);
        t = digits(c->trades, 5, RIGHT, 0x00);
        say_red(a, b, 186, 67, &t);
    }
    if (c->unionRoom) {
        s = enc("UNION TRADES & BATTLES");
        say(a, b, 10, 83, &s);
        t = digits(c->unionRoom, 5, RIGHT, 0x00);
        say_red(a, b, 186, 83, &t);
    }
    if (c->berryCrush) {
        s = enc("BERRY CRUSH");
        say(a, b, 10, 99, &s);
        t = digits(c->berryCrush, 5, RIGHT, 0x00);
        say_red(a, b, 186, 99, &t);
    }
}

// PrintStatOnBackOfCard: the label at x 16, the value right-aligned to 216, a row every 16 px.
static void hoenn_stat(const struct art* a, const struct blobs* b, int row, const struct str* label,
                       const struct str* value) {
    say(a, b, 16, row * 16 + 33, label);
    say_red(a, b, 216 - width(a, b, value), row * 16 + 33, value);
}

static void hoenn_text(const struct art* a, const struct blobs* b, const struct pd_card_info* c, bool back,
                       bool colon) {
    const int pad = 0x77;
    struct str s, t;
    if (!back) {
        s = enc("NAME: ");
        t = name_of(c);
        cat(&s, &t);
        say(a, b, 16, 33, &s);
        s = enc("IDNo.");
        t = digits(c->trainerId, 5, LEADING_ZEROS, pad);
        cat(&s, &t);
        say(a, b, (96 - width(a, b, &s)) / 2 + 120, 9, &s);
        struct str money = enc("¥");
        t = digits((int) c->money, 6, LEFT, pad);
        cat(&money, &t);
        s = enc("MONEY");
        say(a, b, 16, 57, &s);
        say(a, b, 128 - width(a, b, &money), 57, &money);
        if (c->dexCaught >= 0) {
            t = digits(c->dexCaught, 3, LEFT, pad);
            s = enc("POKéDEX");
            say(a, b, 16, 73, &s);
            say(a, b, 128 - width(a, b, &t), 73, &t);
        }
        s = enc("TIME");
        say(a, b, 16, 89, &s);
        struct str colonStr = enc(":");
        int colonW = width(a, b, &colonStr);
        int x = 128 - (colonW + 30);
        t = digits(c->hours, 3, RIGHT, pad);
        say(a, b, x, 89, &t);
        if (colon) say(a, b, x + 18, 89, &colonStr);
        t = digits(c->minutes, 2, LEADING_ZEROS, pad);
        say(a, b, x + 18 + colonW, 89, &t);
        return;
    }
    s = name_of(c);
    t = enc("'s TRAINER CARD");
    cat(&s, &t);
    say(a, b, 216 - width(a, b, &s), 9, &s);
    if (c->hofDebut) {
        s = enc("HALL OF FAME DEBUT  ");
        t = hof_time(c->hofDebut, pad);
        hoenn_stat(a, b, 0, &s, &t);
    }
    if (c->linkWins || c->linkLosses) {
        // "W:{red}wins{grey}  L:{red}losses": one string, colours switching inside it.
        struct str w = enc("W:"), wins = digits(c->linkWins, 4, LEFT, pad), l = enc("  L:");
        struct str losses = digits(c->linkLosses, 4, LEFT, pad);
        s = enc("LINK BATTLES");
        say(a, b, 16, 49, &s);
        int x = 216 - (width(a, b, &w) + width(a, b, &wins) + width(a, b, &l) + width(a, b, &losses));
        say(a, b, x, 49, &w);
        x += width(a, b, &w);
        say_red(a, b, x, 49, &wins);
        x += width(a, b, &wins);
        say(a, b, x, 49, &l);
        x += width(a, b, &l);
        say_red(a, b, x, 49, &losses);
    }
    if (c->trades) {
        s = enc("POKéMON TRADES");
        t = digits(c->trades, 5, RIGHT, pad);
        hoenn_stat(a, b, 2, &s, &t);
    }
    if (c->linkPokeblocks) {
        // POKéBLOCKS: the game's own "POKéBLOCK" characters.
        s = (struct str) { 0 };
        for (int ch = 0x55; ch <= 0x59; ch++) add(&s, ch);
        t = enc("S W/FRIENDS");
        cat(&s, &t);
        t = digits(c->linkPokeblocks, 5, RIGHT, pad);
        hoenn_stat(a, b, 3, &s, &t);
    }
    if (c->linkContests) {
        s = enc("WON CONTESTS W/FRIENDS");
        t = digits(c->linkContests, 5, RIGHT, pad);
        hoenn_stat(a, b, 4, &s, &t);
    }
    if (c->battlePoints) {
        struct str n = digits(c->battlePoints, 5, RIGHT, pad), bp = enc("BP");
        int x = 216 - (width(a, b, &n) + width(a, b, &bp));
        s = enc("BATTLE POINTS WON");
        say(a, b, 16, 5 * 16 + 33, &s);
        say_red(a, b, x, 5 * 16 + 33, &n);
        say(a, b, x + width(a, b, &n), 5 * 16 + 33, &bp);
    }
}

bool pd_card_render(const struct pd_game* g, const struct pd_card_info* c, bool back, bool colon, bool backdrop,
                    uint32_t* img) {
    bool kanto = c->style == PD_CARD_KANTO;
    const struct blobs* b = kanto ? &KANTO : c->style == PD_CARD_LEGACY ? &LEGACY : &HOENN;
    struct art a = { .img = img, .lay = kanto ? &KANTO_LAYOUT : &HOENN_LAYOUT };
    if (c->style != PD_CARD_KANTO && c->style != PD_CARD_HOENN && c->style != PD_CARD_LEGACY) return false;
    // Every blob the card needs, or no card.
    const int need[] = {
        b->gfx, b->front, b->back, b->bg, b->pals[0], b->pals[1], b->pals[2], b->pals[3], b->pals[4], b->pals[5],
        b->femalePal, b->badgesPal, b->badgesGfx, b->picMale, b->picMalePal, b->picFemale, b->picFemalePal,
        b->font, b->widths, PD_CARD_STAR_PAL, b->stickers[0], b->stickers[1], b->stickers[2], b->stickers[3],
        b->stickers[4],
    };
    for (size_t i = 0; i < sizeof(need) / sizeof(need[0]); i++) {
        if (need[i] < 0) continue;
        a.d[need[i]] = pd_romart_blob(g, (enum pd_blob) need[i]);
        if (!a.d[need[i]]) return false;
    }
    a.d[PD_CARD_STAR_PAL] = pd_romart_blob(g, PD_CARD_STAR_PAL);

    uint32_t pal[256] = { 0 };
    int most = b->pals[5] >= 0 ? 5 : 4;
    int stars = c->stars < 0 ? 0 : c->stars > most ? most : c->stars;
    palette(a.d[b->pals[stars]], 48, pal);
    if (c->female) palette(a.d[b->femalePal], 16, pal + 16);
    palette(a.d[b->badgesPal], 16, pal + 48);
    palette(a.d[PD_CARD_STAR_PAL], 16, pal + 64);
    const uint8_t* tiles = a.d[b->gfx];
    size_t tilesLen = pd_blob_sigs[b->gfx].size;

    // BG2 then BG0: colour 0 is see-through, down to the backdrop (palette 0's first colour).
    for (int i = 0; i < PD_CARD_W * PD_CARD_H; i++) img[i] = backdrop ? pal[0] : 0;
    const uint8_t* maps[2] = { backdrop ? a.d[b->bg] : NULL, a.d[back ? b->back : b->front] };
    for (int m = 0; m < 2; m++) {
        if (!maps[m]) continue;
        for (int ty = 0; ty < 20; ty++) {
            for (int tx = 0; tx < 30; tx++) tile(img, tiles, tilesLen, pal, u16at(maps[m], ty * 30 + tx), tx, ty);
        }
    }
    // BG3: the card's tiles with the badges' at tile 192.
    static uint8_t bg3[0x1800 + 0x400];
    memset(bg3, 0, sizeof(bg3));
    memcpy(bg3, tiles, tilesLen < 0x1800 ? tilesLen : 0x1800);
    memcpy(bg3 + 192 * 32, a.d[b->badgesGfx], 0x400);
    if (!back) {
        for (int i = 0; i < stars; i++) tile(img, bg3, sizeof(bg3), pal, 4 << 12 | 143, 15 + i, a.lay->starY);
        for (int i = 0; i < 8; i++) {
            if (!(c->badges >> i & 1)) continue;
            int t = 192 + 2 * i, x = 4 + 3 * i, y = a.lay->badgeY;
            tile(img, bg3, sizeof(bg3), pal, 3 << 12 | t, x, y);
            tile(img, bg3, sizeof(bg3), pal, 3 << 12 | (t + 1), x + 1, y);
            tile(img, bg3, sizeof(bg3), pal, 3 << 12 | (t + 16), x, y + 1);
            tile(img, bg3, sizeof(bg3), pal, 3 << 12 | (t + 17), x + 1, y + 1);
        }
        uint32_t picPal[16];
        palette(a.d[c->female ? b->picFemalePal : b->picMalePal], 16, picPal);
        pic(img, a.d[c->female ? b->picFemale : b->picMale], picPal, a.lay);
    } else if (kanto) {
        // DrawCardBackStats: a little marker beside each FireRed stat line that has a count.
        const int marks[3][3] = { { c->trades, 26, 9 }, { c->berryCrush, 21, 13 }, { c->unionRoom, 27, 11 } };
        for (int i = 0; i < 3; i++) {
            if (!marks[i][0]) continue;
            tile(img, bg3, sizeof(bg3), pal, 1 << 12 | 141, marks[i][1], marks[i][2]);
            tile(img, bg3, sizeof(bg3), pal, 1 << 12 | 157, marks[i][1], marks[i][2] + 1);
        }
        // PrintStickersOnCard: sticker i (HoF, eggs, link wins) at tile (2 + 3i, 2), coloured by its brag level.
        for (int i = 0; i < 3; i++) {
            int level = c->stickers[i];
            if (level < 1 || level > 4) continue;
            uint32_t sp[256] = { 0 };
            palette(a.d[b->stickers[level]], 16, sp);
            for (int k = 0; k < 4; k++) {
                tile(img, a.d[b->stickers[0]], pd_blob_sigs[b->stickers[0]].size, sp, i * 4 + k, 2 + 3 * i + k % 2,
                     2 + k / 2);
            }
        }
    } else {
        // Emerald's DrawCardBackStats (palette 0).
        const int marks[2][3] = { { c->trades, 27, 9 }, { c->linkContests, 27, 13 } };
        for (int i = 0; i < 2; i++) {
            if (!marks[i][0]) continue;
            tile(img, bg3, sizeof(bg3), pal, 141, marks[i][1], marks[i][2]);
            tile(img, bg3, sizeof(bg3), pal, 157, marks[i][1], marks[i][2] + 1);
        }
    }
    // BG1: the text.
    if (kanto) kanto_text(&a, b, c, back, colon);
    else hoenn_text(&a, b, c, back, colon);
    return true;
}

void pd_card_bounds(const uint32_t* img, int* l, int* t, int* r, int* b) {
    *l = PD_CARD_W;
    *t = PD_CARD_H;
    *r = *b = 0;
    for (int y = 0; y < PD_CARD_H; y++) {
        for (int x = 0; x < PD_CARD_W; x++) {
            if (!img[y * PD_CARD_W + x]) continue;
            if (x < *l) *l = x;
            if (y < *t) *t = y;
            if (x + 1 > *r) *r = x + 1;
            if (y + 1 > *b) *b = y + 1;
        }
    }
    if (*r <= *l) {
        *l = *t = 0;
        *r = PD_CARD_W;
        *b = PD_CARD_H;
    }
}
