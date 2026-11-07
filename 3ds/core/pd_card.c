#include "pd_card.h"

#include <stdio.h>
#include <math.h>
#include <string.h>

#include "pd_romart.h"

// --- the data (readTrainerCard) ---

// pokeemerald's sHoennToNationalOrder: the Hoenn dex's national numbers.
// Emerald Legacy's regional dex past Hoenn's (TrainerCard.kt LEGACY_HOENN_TO_NATIONAL).
static const uint16_t LEGACY_EXTRA[8] = { 77, 78, 198, 215, 220, 221, 228, 229 };

extern const uint16_t pd_card_soulgold_regional_to_national[], pd_card_soulgold_card_skipped[], pd_card_soulgold_achievement_ids[];
extern const int pd_card_soulgold_regional_to_national_count, pd_card_soulgold_card_skipped_count, pd_card_soulgold_achievement_ids_count;
extern const uint16_t pd_card_glazed_regional_to_national[];
extern const int pd_card_glazed_regional_to_national_count;
extern const uint16_t pd_card_lazarus_regional_to_national[];
extern const int pd_card_lazarus_regional_to_national_count;

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

static bool is_kanto(int style);
static void palette(const uint8_t* b, int colours, uint32_t* out);

static bool ry_style(int style) {
    return style == PD_CARD_RECHARGED_YELLOW || style == PD_CARD_RECHARGED_YELLOW_V1_9;
}

// Recharged Yellow's LoadMonIconGfx (TrainerCard.kt cardIcon): mail is the var's species (30000 + letter = an UNOWN
// form, past 412 the "?" icon); the first frame's 16 tiles and the sprite palette, tinted (1 black, 2 pink, 3 sepia).
static void card_icon(const struct pd_game* g, int mail, int tint, struct pd_card_icon* out) {
    out->ok = false;
    if (mail == 0) return;
    int species = mail - 30000 >= 0 && mail - 30000 <= 27 ? (mail == 30000 ? 201 : 412 + (mail - 30000)) : mail > 412 ? 260 : mail;
    const struct pd_config* cfg = g->cfg;
    uint32_t tp, pp;
    if (!pd_rom_read(g, cfg->cardIconTable + 4u * (uint32_t) species, &tp, 4) ||
        !pd_rom_read(g, cfg->cardPaletteTable + 8u * (uint32_t) species, &pp, 4)) return;
    const uint8_t* tiles = pd_rom_at(g, tp, 0x200);
    const uint8_t* packed = pd_rom_at(g, pp, 0x40);
    uint8_t raw[32];
    if (!tiles || !packed || pd_lz77(packed, 0x40, raw, 32) != 32) return;
    static const int tones[4][3] = { { 0, 0, 0 }, { 0, 0, 0 }, { 500, 330, 310 }, { 307, 256, 240 } };
    uint8_t tinted[32];
    for (int i = 0; i < 16; i++) {
        int v = raw[2 * i] | raw[2 * i + 1] << 8;
        if (tint >= 1 && tint <= 3) {
            int gray = ((v & 31) * 76 + (v >> 5 & 31) * 151 + (v >> 10 & 31) * 29) >> 8;
            int r = ((tones[tint][0] * gray) & 0xFFFF) >> 8, gg = ((tones[tint][1] * gray) & 0xFFFF) >> 8;
            int b = ((tones[tint][2] * gray) & 0xFFFF) >> 8;
            v = (r < 31 ? r : 31) | (tint == 3 ? gg : gg < 31 ? gg : 31) << 5 | (tint == 3 ? b : b < 31 ? b : 31) << 10;
        }
        tinted[2 * i] = (uint8_t) v;
        tinted[2 * i + 1] = (uint8_t) (v >> 8);
    }
    memcpy(out->tiles, tiles, 0x200);
    palette(tinted, 16, out->pal);
    out->ok = true;
}

static bool card_flag(const struct pd_snapshot* s, int flag) {
    return flag >= 0 && flag / 8 < PD_FLAG_BYTES && (s->cardFlags[flag / 8] >> (flag % 8) & 1);
}

static int card_var(const struct pd_snapshot* s, int var) {
    int i = var - 0x4000;
    return i >= 0 && i < PD_VAR_COUNT ? s->cardVars[i] : 0;
}

bool pd_card_info(const struct pd_game* g, const struct pd_snapshot* s, struct pd_card_info* c) {
    const struct pd_config* cfg = g->cfg;
    memset(c, 0, sizeof(*c));
    if (!cfg || !cfg->cardStyle || !s->valid || !s->cardOk || !s->dexOk) return false;
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

    // The magic is read where the card's own record says (Lazarus keeps it at +0x1E, the dex's table says +0x1A).
    bool national = s->dexOk && (cfg->nationalFlagOnly ? card_flag(s, cfg->nationalFlag) : cfg->nationalMagicOff < 0 ||
        (s->cardMagic == cfg->nationalMagic && card_var(s, cfg->nationalVar) == cfg->nationalVarValue &&
         card_flag(s, cfg->nationalFlag)));
    int caught = 0;
    if (national) {
        for (int n = 1; n <= s->dexCount; n++) caught += pd_dex_caught(s, n);
    } else if (cfg->cardStyle == PD_CARD_GLAZED) {
        for (int i = 0; i < pd_card_glazed_regional_to_national_count; i++)
            caught += pd_dex_caught(s, pd_card_glazed_regional_to_national[i]);
    } else if (cfg->cardStyle == PD_CARD_SOULGOLD) {
        for (int i = 0; i < pd_card_soulgold_regional_to_national_count; i++)
            caught += pd_dex_caught(s, pd_card_soulgold_regional_to_national[i]);
    } else if (cfg->cardStyle == PD_CARD_LAZARUS) {
        for (int i = 0; i < pd_card_lazarus_regional_to_national_count; i++)
            caught += pd_dex_caught(s, pd_card_lazarus_regional_to_national[i]);
        caught += s->dexStray;
    } else if (cfg->cardStyle == PD_CARD_HOENN || cfg->cardStyle == PD_CARD_LEGACY) {
        for (int i = 0; i < 202; i++) caught += pd_dex_caught(s, HOENN_TO_NATIONAL[i]);
        if (cfg->cardStyle == PD_CARD_LEGACY)
            for (int i = 0; i < 8; i++) caught += pd_dex_caught(s, LEGACY_EXTRA[i]);
    } else {
        for (int n = 1; n <= 151; n++) caught += pd_dex_caught(s, n);
    }
    c->dexCaught = card_flag(s, cfg->pokedexFlag) ? caught : -1;
    for (int i = 0; i < 8; i++) {
        if (card_flag(s, cfg->badgeFlag + i)) c->badges |= 1 << i;
    }

    // GAME_STAT_FIRST_HOF_PLAY_TIME counts only once ENTERED_HOF is set; hours cap at 999:59:59.
    int hof = s->gameStats[STAT_ENTERED_HOF] ? (int) s->gameStats[STAT_FIRST_HOF_PLAY_TIME] : 0;
    if (((unsigned) hof >> 16) > 999) hof = 999 << 16 | 59 << 8 | 59;
    c->hofDebut = hof;
    // HasAllHoennMons: the Hoenn dex minus Jirachi and Deoxys.
    bool allHoenn = true;
    if (cfg->cardStyle == PD_CARD_SOULGOLD) { // every regional entry but the legendaries (the v1.2 builds mark six more)
        for (int i = 0; i < pd_card_soulgold_regional_to_national_count && allHoenn; i++) {
            int n = pd_card_soulgold_regional_to_national[i];
            bool skip = false;
            for (int k = 0; k < pd_card_soulgold_card_skipped_count && !skip; k++) skip = pd_card_soulgold_card_skipped[k] == n;
            if (cfg->monIconStride == 0x120) skip |= n == 1009 || n == 1010 || (n >= 1020 && n <= 1023); // the v1.2 builds
            allHoenn = skip || pd_dex_caught(s, n);
        }
    } else if (cfg->cardStyle == PD_CARD_GLAZED) { // its regional dex less the last allRegionalDrop
        for (int i = 0; i < pd_card_glazed_regional_to_national_count - cfg->allRegionalDrop && allHoenn; i++)
            allHoenn = pd_dex_caught(s, pd_card_glazed_regional_to_national[i]);
    } else if (cfg->cardStyle == PD_CARD_LAZARUS) { // its whole ILIOS dex, and the stray entry 0 (nothing left out)
        for (int i = 0; i < pd_card_lazarus_regional_to_national_count && allHoenn; i++)
            allHoenn = pd_dex_caught(s, pd_card_lazarus_regional_to_national[i]);
        allHoenn = allHoenn && s->dexStray;
    } else if (is_kanto(cfg->cardStyle) || cfg->cardStyle == PD_CARD_RECHARGED_YELLOW ||
               cfg->cardStyle == PD_CARD_RECHARGED_YELLOW_V1_9) { // Kanto's 151, less the last allRegionalDrop
        for (int n = 1; n <= 151 - cfg->allRegionalDrop && allHoenn; n++) allHoenn = pd_dex_caught(s, n);
    } else {
        for (int i = 0; i < 202 - cfg->allRegionalDrop && allHoenn; i++) allHoenn = pd_dex_caught(s, HOENN_TO_NATIONAL[i]);
    }
    // Legacy's regional dex runs 210 (Hoenn's 202, JIRACHI and DEOXYS still left out of "all", then eight more).
    for (int i = 0; i < 8 && allHoenn && cfg->cardStyle == PD_CARD_LEGACY; i++) allHoenn = pd_dex_caught(s, LEGACY_EXTRA[i]);
    c->stars = (hof != 0) + allHoenn;
    if (cfg->extraStarFlag && card_flag(s, cfg->extraStarFlag)) c->stars++;
    if (cfg->museumWinnersOff) {
        bool all = true;
        for (int i = 0; i < 5; i++) all &= s->museumWinners[i] != 0;
        c->stars += all;
    }
    bool symbols = false;
    if (cfg->frontierSymbolFlag) {
        symbols = true;
        for (int i = 0; i < 7; i++) {
            symbols &= card_flag(s, cfg->frontierSymbolFlag + 2 * i) && card_flag(s, cfg->frontierSymbolFlag + 2 * i + 1);
        }
        c->stars += symbols;
    }
    if (ry_style(cfg->cardStyle)) {
        // Recharged Yellow: every one of its 37 achievements, and 1-248 + 252-384 caught.
        bool all = s->achievementsOn;
        for (int i = 0; i < cfg->achievementsNeeded && all; i++) all = s->achievements[i / 8] >> (i % 8) & 1;
        c->stars += all;
        all = true;
        for (int n = 1; n <= 384 && all; n++) all = (n >= 249 && n <= 251) || pd_dex_caught(s, n);
        c->stars += all;
    }
    if (cfg->cardStyle == PD_CARD_SOULGOLD) {
        // A Battle Frontier record over 49, and 80 of its 120 achievements (counted while the magic reads 0xACE1).
        c->stars += s->frontierRecordHigh;
        int have = 0;
        for (int i = 0; s->achievementsOn && i < pd_card_soulgold_achievement_ids_count; i++) {
            int id = pd_card_soulgold_achievement_ids[i];
            have += s->achievements[id / 8] >> (id % 8) & 1;
        }
        c->stars += have >= cfg->achievementsNeeded;
    }
    c->drawnStars = c->stars;
    // Lazarus draws stars by a rule of its own (0x082288B0): the HALL OF FAME, flags 0x29 and 0x4A6, the symbols.
    if (cfg->cardStyle == PD_CARD_LAZARUS) {
        c->drawnStars = (s->gameStats[STAT_ENTERED_HOF] != 0) + card_flag(s, 0x29) + card_flag(s, 0x4A6) + symbols;
    }

    bool hoenn = !is_kanto(cfg->cardStyle);
    c->linkWins = capped(s, STAT_LINK_BATTLE_WINS, 9999);
    c->linkLosses = capped(s, STAT_LINK_BATTLE_LOSSES, 9999);
    c->trades = capped(s, STAT_POKEMON_TRADES, 0xFFFF);
    c->unionRoom = hoenn || !cfg->linkLines ? 0 : capped(s, STAT_UNION_ROOM_BATTLES, 0xFFFF);
    c->berryCrush = hoenn || !cfg->linkLines ? 0 : capped(s, STAT_BERRY_CRUSH_POINTS, 0xFFFF);
    c->linkContests = hoenn ? capped(s, STAT_WON_LINK_CONTEST, 999) : 0;
    c->linkPokeblocks = hoenn ? capped(s, STAT_POKEBLOCKS_WITH_FRIENDS, 0xFFFF) : 0;
    c->battlePoints = cfg->frontierBpOff ? s->battlePoints : 0;
    for (int i = 0; cfg->stickerVar && i < 3; i++) c->stickers[i] = card_var(s, cfg->stickerVar + i);
    for (int i = 0; cfg->iconVar > 0 && cfg->cardIconTable && i < 6; i++) card_icon(g, card_var(s, cfg->iconVar + i), card_var(s, cfg->tintVar), &c->icons[i]);
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

// Unbound (TrainerCardArt.kt UNBOUND): FireRed's frame in its purple; its ROM dropped the 0-star palette (every star
// count starts from the 1-star one) and its badges aren't FireRed's (none drawn, no girl's palette).
static const struct blobs UNBOUND = {
    PD_FR_CARD_GFX, PD_FR_CARD_FRONT, PD_FR_CARD_BACK, PD_FR_CARD_BG,
    { PD_FR_CARD_PAL1, PD_FR_CARD_PAL1, PD_FR_CARD_PAL1, PD_FR_CARD_PAL1, PD_FR_CARD_PAL1, -1 },
    -1, -1, -1,
    PD_UB_PIC_MALE, PD_UB_PIC_MALE_PAL, PD_UB_PIC_FEMALE, PD_UB_PIC_FEMALE_PAL,
    PD_FR_FONT_NORMAL, PD_FR_FONT_NORMAL_WIDTHS,
    { -1, -1, -1, -1, -1 },
};

// Glazed: retail Emerald's card, with its own Tunod badges (repointed tiles, the palette edited in place), its own
// player pics and FONT_NORMAL edited in place; the widths are retail's.
static const struct blobs GLAZED = {
    PD_EM_CARD_GFX, PD_EM_CARD_FRONT, PD_EM_CARD_BACK, PD_EM_CARD_BG,
    { PD_EM_CARD_PAL0, PD_EM_CARD_PAL1, PD_EM_CARD_PAL2, PD_EM_CARD_PAL3, PD_EM_CARD_PAL4, -1 },
    PD_EM_CARD_FEMALE_PAL, PD_GZ_CARD_BADGES_PAL, PD_GZ_CARD_BADGES_GFX,
    PD_GZ_PIC_MALE, PD_GZ_PIC_MALE_PAL, PD_GZ_PIC_FEMALE, PD_GZ_PIC_FEMALE_PAL,
    PD_GZ_FONT_NORMAL, PD_EM_FONT_NORMAL_WIDTHS,
    { -1, -1, -1, -1, -1 },
};

// SoulGold: Emerald's card code with its Johto art, smol-compressed: its own everything but the star's palette.
static const struct blobs SOULGOLD = {
    PD_SG_CARD_GFX, PD_SG_CARD_FRONT, PD_SG_CARD_BACK, PD_SG_CARD_BG,
    { PD_SG_CARD_PAL0, PD_SG_CARD_PAL1, PD_SG_CARD_PAL2, PD_SG_CARD_PAL3, PD_SG_CARD_PAL4, -1 },
    PD_SG_CARD_FEMALE_PAL, PD_SG_CARD_BADGES_PAL, PD_SG_CARD_BADGES_GFX,
    PD_SG_PIC_MALE, PD_SG_PIC_MALE_PAL, PD_SG_PIC_FEMALE, PD_SG_PIC_FEMALE_PAL,
    PD_SG_FONT_NORMAL, PD_SG_FONT_NORMAL_WIDTHS,
    { -1, -1, -1, -1, -1 },
};

// Recharged Yellow: FireRed's card byte for byte with its own pics and FONT_NORMAL; its stickers' palettes are
// FireRed's first three, the fourth brag level reusing the third.
static const struct blobs RECHARGED_YELLOW = {
    PD_FR_CARD_GFX, PD_FR_CARD_FRONT, PD_FR_CARD_BACK, PD_FR_CARD_BG,
    { PD_FR_CARD_PAL0, PD_FR_CARD_PAL1, PD_FR_CARD_PAL2, PD_FR_CARD_PAL3, PD_FR_CARD_PAL4, -1 },
    PD_FR_CARD_FEMALE_PAL, PD_FR_CARD_BADGES_PAL, PD_FR_CARD_BADGES_GFX,
    PD_RY_PIC_MALE, PD_RY_PIC_MALE_PAL, PD_RY_PIC_FEMALE, PD_RY_PIC_FEMALE_PAL,
    PD_RY_FONT_NORMAL, PD_RY_FONT_NORMAL_WIDTHS,
    { PD_FR_CARD_STICKERS_GFX, PD_FR_CARD_STICKER_PAL1, PD_FR_CARD_STICKER_PAL2, PD_FR_CARD_STICKER_PAL3,
      PD_FR_CARD_STICKER_PAL3 },
};

// Lazarus (TrainerCardArt.kt LAZARUS): its own tiles, tilemaps, 0- and 1-star palettes, badges, pics and font; the
// backdrop, the other palettes and the girl's backdrop palette are retail's bytes.
static const struct blobs LAZARUS = {
    PD_LZ_CARD_GFX, PD_LZ_CARD_FRONT, PD_LZ_CARD_BACK, PD_EM_CARD_BG,
    { PD_LZ_CARD_PAL0, PD_LZ_CARD_PAL1, PD_EM_CARD_PAL2, PD_EM_CARD_PAL3, PD_EM_CARD_PAL4, -1 },
    PD_EM_CARD_FEMALE_PAL, PD_LZ_CARD_BADGES_PAL, PD_LZ_CARD_BADGES_GFX,
    PD_LZ_PIC_MALE, PD_LZ_PIC_MALE_PAL, PD_LZ_PIC_FEMALE, PD_LZ_PIC_FEMALE_PAL,
    PD_LZ_FONT_NORMAL, PD_LZ_FONT_NORMAL_WIDTHS,
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
// FireRed's colour turned to Unbound's card (TrainerCardArt.unboundPurple, float for float): hue 266, the pale greens
// and teals onto its lavender ramp; greys and the gold accents (hue 25-65) stay.
static uint32_t unbound_purple(uint32_t argb) {
    float r = (float) (argb >> 16 & 0xFF) / 255.0f, g = (float) (argb >> 8 & 0xFF) / 255.0f;
    float bl = (float) (argb & 0xFF) / 255.0f;
    float max = r > g ? (r > bl ? r : bl) : (g > bl ? g : bl);
    float min = r < g ? (r < bl ? r : bl) : (g < bl ? g : bl);
    float d = max - min;
    float s = max == 0.0f ? 0.0f : d / max;
    if (s < 0.08f) return argb;
    float hue;
    if (max == r) hue = 60.0f * fmodf((g - bl) / d, 6.0f);
    else if (max == g) hue = 60.0f * ((bl - r) / d + 2.0f);
    else hue = 60.0f * ((r - g) / d + 4.0f);
    if (hue < 0.0f) hue += 360.0f;
    if (hue >= 25.0f && hue <= 65.0f) return argb;
    float sat = s * 1.8f < 0.6f ? s * 1.8f : 0.6f;
    float val = max * 1.15f + 0.05f < 1.0f ? max * 1.15f + 0.05f : 1.0f;
    float h = 266.0f / 60.0f, c = val * sat;
    float x = c * (1.0f - fabsf(fmodf(h, 2.0f) - 1.0f));
    float r1, g1, b1;
    switch ((int) h) {
    case 0: r1 = c; g1 = x; b1 = 0; break;
    case 1: r1 = x; g1 = c; b1 = 0; break;
    case 2: r1 = 0; g1 = c; b1 = x; break;
    case 3: r1 = 0; g1 = x; b1 = c; break;
    case 4: r1 = x; g1 = 0; b1 = c; break;
    default: r1 = c; g1 = 0; b1 = x; break;
    }
    float m = val - c;
#define CH(f) ((uint32_t) (((f) + m) * 255.0f + 0.5f) > 255 ? 255u : (uint32_t) (((f) + m) * 255.0f + 0.5f))
    return (argb & 0xFF000000u) | CH(r1) << 16 | CH(g1) << 8 | CH(b1);
#undef CH
}

static bool is_kanto(int style) {
    return style == PD_CARD_KANTO || style == PD_CARD_UNBOUND || style == PD_CARD_RECHARGED_YELLOW ||
        style == PD_CARD_RECHARGED_YELLOW_V1_9;
}

enum { COLS_ALL, COLS_NARROW, COLS_WIDTH };

struct layout {
    int textW, glyphH, pad, starY, badgeY, picX, picY;
    int cols;        // which of a glyph's 16 columns are drawn: COLS_ALL, COLS_NARROW (the left 8 of a glyph no wider than 8,
                     // expansion's DecompressGlyph) or COLS_WIDTH (its width)
    int starPal;     // the stars' BG palette
    uint32_t text;   // the window palette's text colour (0 = TEXT)
    bool noMarks;    // the back's little stat markers are left out
};
static const struct layout KANTO_LAYOUT = { 27 * 8, 14, 0x00, 7, 16, 13, 4, COLS_ALL, 4, 0, false };
static const struct layout HOENN_LAYOUT = { 28 * 8, 16, 0x77, 7, 15, 1, 0, COLS_ALL, 4, 0, false };
static const struct layout LAZARUS_LAYOUT = { 28 * 8, 15, 0x77, 7, 15, 1, 0, COLS_NARROW, 1, 0, false };
static const struct layout SOULGOLD_LAYOUT = { 28 * 8, 15, 0x77, 7, 15, 1, 0, COLS_WIDTH, 4, 0xFF181818u, true };
static const struct layout RY_LAYOUT = { 27 * 8, 14, 0x00, 7, 16, 13, 4, COLS_WIDTH, 4, 0, false };

#define TEXT 0xFF636363u
#define WHITE 0xFFFFFFFFu
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
        int w = a->d[b->widths][g];
        int cols = a->lay->cols == COLS_NARROW ? (w <= 8 ? 8 : 16) : a->lay->cols == COLS_WIDTH ? (w < 16 ? w : 16) : 16;
        for (int py = 0; py < a->lay->glyphH; py++) {
            for (int px = 0; px < cols; px++) {
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
    print(a, b, x, y, s, a->lay->text ? a->lay->text : TEXT, TEXT_SHADOW);
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

#define RY19_STAT 0xFFE75252u

// Recharged Yellow's text (TrainerCardArt.rechargedYellowText): its own labels and edges; red is the stat colour.
static void ry_text(const struct art* a, const struct blobs* b, const struct pd_card_info* c, bool back, bool colon,
                    uint32_t red) {
    struct str s, t;
#define RIGHT_EDGE(edge, str) ((((edge) - width(a, b, &(str))) > 0 ? (edge) - width(a, b, &(str)) : 0) & 0xFF)
    if (!back) {
        s = enc("Name: ");
        t = name_of(c);
        cat(&s, &t);
        say(a, b, 20, 28, &s);
        s = enc("IDNo.");
        t = digits(c->trainerId, 5, LEADING_ZEROS, 0);
        cat(&s, &t);
        int idx = (80 - width(a, b, &s)) / 2;
        say(a, b, ((idx > 0 ? idx : 0) + 132) & 0xFF, 9, &s);
        s = enc("Money");
        say(a, b, 20, 56, &s);
        struct str money = enc("¥");
        t = digits((int) c->money, 7, LEFT, 0);
        cat(&money, &t);
        say(a, b, RIGHT_EDGE(144, money), 56, &money);
        if (c->dexCaught >= 0) {
            s = enc("Pokédex");
            say(a, b, 20, 72, &s);
            t = digits(c->dexCaught, 3, LEFT, 0);
            say(a, b, RIGHT_EDGE(144, t), 72, &t);
        }
        s = enc("Time");
        say(a, b, 20, 88, &s);
        struct str colonStr = enc(":");
        int colonW = width(a, b, &colonStr);
        int x = 144 - (colonW + 30);
        t = digits(c->hours, 3, RIGHT, 0x00);
        say(a, b, x & 0xFF, 88, &t);
        if (colon) say(a, b, (x + 18) & 0xFF, 88, &colonStr);
        t = digits(c->minutes, 2, LEADING_ZEROS, 0);
        say(a, b, (x + 18 + colonW) & 0xFF, 88, &t);
        return;
    }
    t = name_of(c);
    say(a, b, 136, 9, &t);
    if (c->hofDebut) {
        s = enc("Hall of Fame debut  ");
        say(a, b, 8, 33, &s);
        t = hof_time(c->hofDebut, 0x00);
        print(a, b, RIGHT_EDGE(216, t), 33, &t, red, STAT_SHADOW);
    }
    if (c->linkWins || c->linkLosses) {
        struct str parts[4] = { enc("W:"), digits(c->linkWins, 4, LEFT, 0), enc("  L:"), digits(c->linkLosses, 4, LEFT, 0) };
        struct str all = { 0 };
        for (int i = 0; i < 4; i++) cat(&all, &parts[i]);
        s = enc("Lik battles");
        say(a, b, 8, 49, &s);
        int x = RIGHT_EDGE(216, all);
        for (int i = 0; i < 4; i++) {
            if (i & 1) print(a, b, x, 49, &parts[i], red, STAT_SHADOW);
            else say(a, b, x, 49, &parts[i]);
            x += width(a, b, &parts[i]);
        }
    }
    if (c->trades) {
        s = enc("Pokémon Trades");
        say(a, b, 8, 2 * 16 + 33, &s);
        t = digits(c->trades, 5, RIGHT, 0x00);
        print(a, b, RIGHT_EDGE(216, t), 2 * 16 + 33, &t, red, STAT_SHADOW);
    }
#undef RIGHT_EDGE
}

// PrintStatOnBackOfCard: the label at x 16, the value right-aligned to 216, a row every 16 px.
static void hoenn_stat(const struct art* a, const struct blobs* b, int row, const struct str* label,
                       const struct str* value) {
    say(a, b, 16, row * 16 + 33, label);
    say_red(a, b, 216 - width(a, b, value), row * 16 + 33, value);
}

// What the Emerald-style card prints, per style (TrainerCardArt.words): labels and digit counts.
struct words {
    const char *name, *money, *dex, *time, *cardOf, *hof, *link, *trades, *blocks, *contests, *points;
    int moneyDigits, dexDigits;
    bool white; // SoulGold's white headings: its ID once the card has a star, the back's title
};
static const struct words HOENN_WORDS = { "NAME: ", "MONEY", "POKéDEX", "TIME", "'s TRAINER CARD", "HALL OF FAME DEBUT  ",
    "LINK BATTLES", "POKéMON TRADES", "S W/FRIENDS", "WON CONTESTS W/FRIENDS", "BATTLE POINTS WON", 6, 3, false };
static const struct words LAZARUS_WORDS = { "NAME: ", "MONEY", "Pokédex", "TIME", "'s TRAINER CARD", "Hall of Fame DEBUT  ",
    "LINK BATTLES", "Pokémon TRADES", "S W/FRIENDS", "WON CONTESTS W/FRIENDS", "BATTLE POINTS WON", 6, 4, false };
static const struct words GLAZED_WORDS = { "NAME: ", "MONEY", "POKéDEX", "TIME", "'s TRAINER CARD", "HALL OF FAME DEBUT  ",
    "LINK BATTLES", "POKéMON TRADES", "S W/FRIENDS", "WON CONTESTS W/FRIENDS", "BATTLE POINTS WON", 7, 3, false };
static const struct words SOULGOLD_WORDS = { "Name: ", "Money", "Pokédex", "Time", "'s Trainer Card", "Hall of Fame Debut  ",
    "Link Battles", "Pokémon Trades", "S W/Friends", "Won Contests W/Friends", "Battle Points Won", 7, 4, true };

static void hoenn_text(const struct art* a, const struct blobs* b, const struct pd_card_info* c, bool back,
                       bool colon) {
    const int pad = 0x77;
    const struct words* w = c->style == PD_CARD_LAZARUS ? &LAZARUS_WORDS : c->style == PD_CARD_SOULGOLD ? &SOULGOLD_WORDS :
        c->style == PD_CARD_GLAZED ? &GLAZED_WORDS : &HOENN_WORDS;
    struct str s, t;
    if (!back) {
        s = enc(w->name);
        t = name_of(c);
        cat(&s, &t);
        say(a, b, 16, 33, &s);
        s = enc("IDNo.");
        t = digits(c->trainerId, 5, LEADING_ZEROS, pad);
        cat(&s, &t);
        if (w->white && c->stars > 0) print(a, b, (96 - width(a, b, &s)) / 2 + 120, 9, &s, WHITE, a->lay->text ? a->lay->text : TEXT);
        else say(a, b, (96 - width(a, b, &s)) / 2 + 120, 9, &s);
        struct str money = enc("¥");
        t = digits((int) c->money, w->moneyDigits, LEFT, pad);
        cat(&money, &t);
        s = enc(w->money);
        say(a, b, 16, 57, &s);
        say(a, b, 128 - width(a, b, &money), 57, &money);
        if (c->dexCaught >= 0) {
            t = digits(c->dexCaught, w->dexDigits, LEFT, pad);
            s = enc(w->dex);
            say(a, b, 16, 73, &s);
            say(a, b, 128 - width(a, b, &t), 73, &t);
        }
        s = enc(w->time);
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
    t = enc(w->cardOf);
    cat(&s, &t);
    if (w->white) print(a, b, 216 - width(a, b, &s), 9, &s, WHITE, a->lay->text ? a->lay->text : TEXT);
    else say(a, b, 216 - width(a, b, &s), 9, &s);
    if (c->hofDebut) {
        s = enc(w->hof);
        t = hof_time(c->hofDebut, pad);
        hoenn_stat(a, b, 0, &s, &t);
    }
    if (c->linkWins || c->linkLosses) {
        // "W:{red}wins{grey}  L:{red}losses": one string, colours switching inside it.
        struct str wl = enc("W:"), wins = digits(c->linkWins, 4, LEFT, pad), l = enc("  L:");
        struct str losses = digits(c->linkLosses, 4, LEFT, pad);
        s = enc(w->link);
        say(a, b, 16, 49, &s);
        int x = 216 - (width(a, b, &wl) + width(a, b, &wins) + width(a, b, &l) + width(a, b, &losses));
        say(a, b, x, 49, &wl);
        x += width(a, b, &wl);
        say_red(a, b, x, 49, &wins);
        x += width(a, b, &wins);
        say(a, b, x, 49, &l);
        x += width(a, b, &l);
        say_red(a, b, x, 49, &losses);
    }
    if (c->trades) {
        s = enc(w->trades);
        t = digits(c->trades, 5, RIGHT, pad);
        hoenn_stat(a, b, 2, &s, &t);
    }
    if (c->linkPokeblocks) {
        // POKéBLOCKS: the game's own "POKéBLOCK" characters.
        s = (struct str) { 0 };
        for (int ch = 0x55; ch <= 0x59; ch++) add(&s, ch);
        t = enc(w->blocks);
        cat(&s, &t);
        t = digits(c->linkPokeblocks, 5, RIGHT, pad);
        hoenn_stat(a, b, 3, &s, &t);
    }
    if (c->linkContests) {
        s = enc(w->contests);
        t = digits(c->linkContests, 5, RIGHT, pad);
        hoenn_stat(a, b, 4, &s, &t);
    }
    if (c->battlePoints) {
        struct str n = digits(c->battlePoints, 5, RIGHT, pad), bp = enc("BP");
        int x = 216 - (width(a, b, &n) + width(a, b, &bp));
        s = enc(w->points);
        say(a, b, 16, 5 * 16 + 33, &s);
        say_red(a, b, x, 5 * 16 + 33, &n);
        say(a, b, x + width(a, b, &n), 5 * 16 + 33, &bp);
    }
}

bool pd_card_render(const struct pd_game* g, const struct pd_card_info* c, bool back, bool colon, bool backdrop,
                    uint32_t* img) {
    bool kanto = is_kanto(c->style);
    bool ry = ry_style(c->style);
    const struct blobs* b = c->style == PD_CARD_UNBOUND ? &UNBOUND : ry ? &RECHARGED_YELLOW : kanto ? &KANTO :
        c->style == PD_CARD_LEGACY ? &LEGACY : c->style == PD_CARD_LAZARUS ? &LAZARUS : c->style == PD_CARD_SOULGOLD ? &SOULGOLD :
        c->style == PD_CARD_GLAZED ? &GLAZED : &HOENN;
    struct art a = { .img = img, .lay = ry ? &RY_LAYOUT : kanto ? &KANTO_LAYOUT : c->style == PD_CARD_LAZARUS ? &LAZARUS_LAYOUT :
        c->style == PD_CARD_SOULGOLD ? &SOULGOLD_LAYOUT : &HOENN_LAYOUT };
    if (c->style != PD_CARD_KANTO && c->style != PD_CARD_HOENN && c->style != PD_CARD_LEGACY && c->style != PD_CARD_UNBOUND &&
        c->style != PD_CARD_LAZARUS && c->style != PD_CARD_SOULGOLD && c->style != PD_CARD_GLAZED && !ry) return false;
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
    if (c->female && b->femalePal >= 0) palette(a.d[b->femalePal], 16, pal + 16);
    if (c->style == PD_CARD_UNBOUND) {
        for (int i = 0; i < 48; i++) pal[i] = unbound_purple(pal[i]);
    }
    if (b->badgesPal >= 0) palette(a.d[b->badgesPal], 16, pal + 48);
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
    if (b->badgesGfx >= 0) memcpy(bg3 + 192 * 32, a.d[b->badgesGfx], 0x400);
    if (!back) {
        int drawn = c->drawnStars < 0 ? 0 : c->drawnStars > most ? most : c->drawnStars;
        for (int i = 0; i < drawn; i++) tile(img, bg3, sizeof(bg3), pal, a.lay->starPal << 12 | 143, 15 + i, a.lay->starY);
        for (int i = 0; i < 8; i++) {
            if (b->badgesGfx < 0 || !(c->badges >> i & 1)) continue;
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
        const int marks[3][3] = { { c->trades, ry ? 27 : 26, 9 }, { c->berryCrush, 21, 13 }, { c->unionRoom, 27, 11 } };
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
        // Recharged Yellow's PrintPokemonIconsOnCard: icon i, 4x4 tiles at (3 + 4i, 15), in its own colours.
        for (int i = 0; i < 6; i++) {
            if (!c->icons[i].ok) continue;
            for (int k = 0; k < 16; k++) tile(img, c->icons[i].tiles, 0x200, c->icons[i].pal, k, 3 + 4 * i + k % 4, 15 + k / 4);
        }
    } else {
        // Emerald's DrawCardBackStats (palette 0).
        const int marks[2][3] = { { c->trades, 27, 9 }, { c->linkContests, 27, 13 } };
        for (int i = 0; i < 2; i++) {
            if (!marks[i][0] || a.lay->noMarks) continue;
            tile(img, bg3, sizeof(bg3), pal, 141, marks[i][1], marks[i][2]);
            tile(img, bg3, sizeof(bg3), pal, 157, marks[i][1], marks[i][2] + 1);
        }
    }
    // BG1: the text.
    if (ry) ry_text(&a, b, c, back, colon, c->style == PD_CARD_RECHARGED_YELLOW_V1_9 ? RY19_STAT : STAT);
    else if (kanto) kanto_text(&a, b, c, back, colon);
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
