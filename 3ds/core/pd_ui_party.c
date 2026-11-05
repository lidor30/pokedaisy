// PARTY: the six slots in FireRed's party colours, each with the game's own
// icon (from the ROM, pd_icon), and a summary on tap.
#include <stdio.h>
#include <string.h>

#include "pd_icon.h"
#include "pd_tables.h"
#include "pd_experience.h"
#include "pd_ui_internal.h"

// FireRedPartyPalette (PartyScreen.kt): a state's frame (outline, light inner edge,
// upper fill, bottom shade; the HP band below the divider is the light edge's colour).
struct slot_colors {
    uint32_t outline, light, fill, shade;
};
static const struct slot_colors SLOT_NORMAL = { 0x4A4A63, 0x84C6DE, 0x3994DE, 0x297BB5 };
static const struct slot_colors SLOT_SELECTED = { 0xFF7331, 0xADEFFF, 0x7BD6EF, 0x4AADCE };
// Game Boy: a white window with an ink frame; the selected one has a grey fill, a fainted one a dark grey.
static const struct slot_colors GB_SLOT = { 0x181818, 0xFFFFFF, 0xFFFFFF, 0xE8E8E8 };
static const struct slot_colors GB_SLOT_SELECTED = { 0x181818, 0xC8C8C8, 0xC8C8C8, 0xA8A8A8 };
static const struct slot_colors GB_SLOT_FAINTED = { 0x181818, 0xB0B0B0, 0xB0B0B0, 0x909090 };
static const struct slot_colors SLOT_FAINTED = { 0x4A4A63, 0xD6A521, 0xC66B10, 0xA54A00 };
static const struct slot_colors SLOT_SELECTED_FAINTED = { 0xFF7331, 0xD6A521, 0xC66B10, 0xA54A00 };
#define SLOT_TEXT PD_GB(0xFFFFFF, PD_GB_INK)
#define SLOT_TEXT_SHADOW PD_GB(0x737373, PD_GB_PAPER)
#define MALE 0x42CEFF
#define MALE_SHADOW 0x006394
#define FEMALE 0xFF9C94
#define FEMALE_SHADOW 0x9C4239
#define HP_FRAME 0x525252
#define HP_INNER 0xFFFFFF
#define HP_LETTER 0xFFD652
#define HP_LETTER_SHADE 0xFFB542
// How faintly an empty slot shows its box (PartyPalette.emptyAlpha).
#define EMPTY_ALPHA 89 // 0.35 of 255

struct hp_band {
    uint32_t top, main;
};
static const struct hp_band HP_GREEN = { 0x5AD684, 0x73FFAD };
static const struct hp_band HP_YELLOW = { 0xCEAD08, 0xFFE739 };
static const struct hp_band HP_RED = { 0xC63900, 0xFF7331 };
static const struct hp_band HP_NONE = { 0x525252, 0x737373 };

// FireRed's closed party Poke Ball (PartyBall: partyfr/pokeball.png, 20x20).
static const char* const BALL[20] = {
    ".......KKKKKK.......", ".....KKKKKKKKKK.....", "....KKKRRRRRRKKK....", "...KKRRRRRRRRRRKK...",
    "..KKRRRRRRRRRRRRKK..", ".KKRRRRRRRRRRRRRRKK.", ".KKRRRRRRRRRRRRRRKK.", "KKRRRRRRKKKKRRRRRRKK",
    "KKHHHHHKKWWKKRRRRRKK", "KKKKKKKKWWWWKKKKKKKK", "KKKKKKKKWWWWKKKKKKKK", "KKWWWWWKKWWKKWWWWWKK",
    "KKWWWWWWKKKKWWWWWWKK", ".KKWWWWWWWWWWWWWWKK.", ".KKWWWWWWWWWWWWWWKK.", "..KKWWWWWWWWWWWWKK..",
    "...KKWWWWWWWWWWKK...", "....KKKWWWWWWKKK....", ".....KKKKKKKKKK.....", ".......KKKKKK.......",
};
// The "HP" label's letters (HpLetters): the top 3 rows in HP_LETTER, the rest in HP_LETTER_SHADE.
static const char* const HP_LETTERS[5] = { "1001.1110", "1001.1001", "1111.1110", "1001.1000", "1001.1000" };
// PixelIcons.male / female.
static const char* const MALE_MARK[8] = { "00001111", "00000011", "00000101", "01111000",
                                          "10001000", "10001000", "10001000", "01110000" };
static const char* const FEMALE_MARK[8] = { "01110", "10001", "10001", "10001", "01110", "00100", "11111", "00100" };

// A character bitmap, `u` canvas pixels a pixel: one colour per character, '.' / '0' clear.
static void char_bitmap(struct pd_canvas* c, const char* const* rows, int n, int x, int y, int u,
                        const char* keys, const uint32_t* colors) {
    for (int r = 0; r < n; r++) {
        for (int col = 0; rows[r][col]; col++) {
            const char* k = strchr(keys, rows[r][col]);
            if (k && rows[r][col] != '.' && rows[r][col] != '0') pd_fill(c, x + col * u, y + r * u, u, u, colors[k - keys]);
        }
    }
}

#define SHINY_MARK 0xF8C830 // a shiny mon's gold asterisk

static const char* mon_display_name(const struct pd_game* g, const struct pd_mon* m, char* buf, size_t len) {
    if (m->species == PD_SPECIES_EGG) return "EGG";
    if (m->nickname[0]) return m->nickname;
    return pd_species_name(g, m->species, buf, len);
}

// The summary's ♂ / ♀ after the name, in their own colours.
static void gender_mark(struct pd_canvas* c, int x, int y, enum pd_gender g) {
    if (g == PD_GENDER_MALE) pd_text(c, x, y, "♂", MALE, MALE_SHADOW);
    if (g == PD_GENDER_FEMALE) pd_text(c, x, y, "♀", FEMALE, FEMALE_SHADOW);
}

// Where everything sits (PartyScreen.kt's SlotGeometry), in whole slot pixels `u`
// (canvas pixels): FireRed's MAIN slot, its frame stretched to the cell, the Poke
// Ball and icon overhanging 8 px left and 6 px up.
struct slot_geometry {
    int u, fx, fy, fw, fh, bottom, dividerY, barY, labelX, barX, barRight, textX;
    int nameMid, levelMid, hpTextMid;
};

static struct slot_geometry geometry(int w, int h) {
    struct slot_geometry g;
    float scale = (float) w / 86 < (float) h / 57 ? (float) w / 86 : (float) h / 57;
    g.u = scale < 1 ? 1 : (int) scale;
    int u = g.u;
    g.fx = 8 * u;
    g.fy = 6 * u;
    g.fw = (w - g.fx - 2 * u) / u * u;
    g.fh = (h - g.fy - 2 * u) / u * u;
    g.bottom = g.fy + g.fh;
    g.dividerY = g.bottom - 19 * u;
    g.barY = g.dividerY + u;
    g.labelX = g.fx + 20 * u;
    g.barX = g.labelX + 11 * u;
    g.barRight = g.fx + g.fw - 4 * u;
    g.textX = g.fx + 30 * u;
    g.nameMid = g.fy + 3 * u + (int) ((g.dividerY - g.fy - 3 * u) * 0.3f);
    g.levelMid = g.fy + 3 * u + (int) ((g.dividerY - g.fy - 3 * u) * 0.74f);
    g.hpTextMid = g.barY + 10 * u;
    return g;
}

static void slot_frame(struct pd_canvas* c, int x, int y, const struct slot_geometry* g, const struct slot_colors* col) {
    int u = g->u;
    pd_round_rect(c, x + g->fx, y + g->fy, g->fw, g->fh, 3 * u, col->outline);
    pd_round_rect(c, x + g->fx + 2 * u, y + g->fy + 2 * u, g->fw - 4 * u, g->fh - 4 * u, u, col->light);
    pd_fill(c, x + g->fx + 3 * u, y + g->fy + 3 * u, g->fw - 6 * u, g->dividerY - g->fy - 3 * u, col->fill);
    pd_fill(c, x + g->fx + 2 * u, y + g->bottom - 3 * u, g->fw - 4 * u, u, col->shade);
}

static void hp_bar(struct pd_canvas* c, int x, int y, const struct slot_geometry* g, int hp, int maxHp) {
    int u = g->u, by = y + g->barY;
    // The "HP" label box, sharing its right edge with the bar's outline.
    pd_round_rect(c, x + g->labelX, by, g->barX - g->labelX + u, 7 * u, u, HP_FRAME);
    for (int r = 0; r < 5; r++) {
        for (int col = 0; HP_LETTERS[r][col]; col++) {
            if (HP_LETTERS[r][col] == '1')
                pd_fill(c, x + g->labelX + (1 + col) * u, by + (1 + r) * u, u, u, r < 3 ? HP_LETTER : HP_LETTER_SHADE);
        }
    }
    int w = g->barRight - g->barX;
    pd_round_rect(c, x + g->barX, by, w, 7 * u, 2 * u, HP_FRAME);
    pd_round_rect(c, x + g->barX + u, by + u, w - 2 * u, 5 * u, u, HP_INNER);
    int inner = w - 4 * u;
    if (hp > maxHp) hp = maxHp;
    if (hp < 0) hp = 0;
    int filled = maxHp > 0 ? inner / u * hp / maxHp * u : 0;
    if (filled < u && hp > 0) filled = u;
    const struct hp_band* col = maxHp <= 0 || hp * 2 > maxHp ? &HP_GREEN : hp * 5 > maxHp ? &HP_YELLOW : &HP_RED;
    int bx = x + g->barX + 2 * u;
    pd_fill(c, bx, by + 2 * u, inner, u, HP_NONE.top);
    pd_fill(c, bx, by + 3 * u, inner, 2 * u, HP_NONE.main);
    pd_fill(c, bx, by + 2 * u, filled, u, col->top);
    pd_fill(c, bx, by + 3 * u, filled, 2 * u, col->main);
}

// The status chip (StatusChip): a darker rim around the status colour, FNT grey.
static void status_chip(struct pd_canvas* c, int x, int y, const char* label) {
    uint32_t color = !strcmp(label, "FNT") ? 0x6B6B6B : !strcmp(label, "SLP") ? 0x8A8A8A
        : !strcmp(label, "PSN") ? 0xA259D9 : !strcmp(label, "BRN") ? 0xE0693E : !strcmp(label, "PAR") ? 0xD9C341
        : !strcmp(label, "FRZ") ? 0x56C2E6 : !strcmp(label, "TOX") ? 0x7A3EA1 : 0x8890A0;
    uint32_t rim = ((((color >> 16) & 0xFF) * 65 / 100) << 16) | ((((color >> 8) & 0xFF) * 65 / 100) << 8)
        | ((color & 0xFF) * 65 / 100);
    uint32_t shadow = ((((color >> 16) & 0xFF) / 2) << 16) | ((((color >> 8) & 0xFF) / 2) << 8) | ((color & 0xFF) / 2);
    int w = pd_text_width(label) + 8;
    pd_round_rect(c, x, y, w, 14, 2, rim);
    pd_round_rect(c, x + 1, y + 1, w - 2, 12, 1, color);
    pd_text(c, x + 4, y - 1, label, 0xFFFFFF, shadow);
}

// An empty slot: its box, faint over the backdrop (drawn whole, then blended in).
static void empty_slot(struct pd_canvas* c, int x, int y, int w, int h, const struct slot_geometry* g) {
    static uint32_t scratch[512 * 256];
    if (w <= 0 || h <= 0 || w * h > (int) (sizeof(scratch) / sizeof(*scratch))) return;
    struct pd_canvas t;
    pd_canvas_init(&t, w, h, scratch);
    memset(scratch, 0xFF, (size_t) w * h * sizeof(*scratch)); // 0xFFFFFFFF: nothing drawn
    slot_frame(&t, 0, 0, g, pd_gb_look ? &GB_SLOT : &SLOT_NORMAL);
    for (int yy = 0; yy < h; yy++) {
        int sy = y + yy;
        if (sy < c->cy0 || sy >= c->cy1) continue;
        for (int xx = 0; xx < w; xx++) {
            int sx = x + xx;
            uint32_t v = scratch[yy * w + xx];
            if (v == 0xFFFFFFFF || sx < c->cx0 || sx >= c->cx1) continue;
            uint32_t* d = &c->px[sy * c->w + sx];
            uint32_t out = 0;
            for (int sh = 0; sh <= 16; sh += 8) {
                uint32_t a = (v >> sh) & 0xFF, b = (*d >> sh) & 0xFF;
                out |= ((a * EMPTY_ALPHA + b * (255 - EMPTY_ALPHA)) / 255) << sh;
            }
            *d = out;
        }
    }
}

static void party_slot(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, int x, int y, int w,
                       int h, const struct pd_mon* m, int slot, bool selected) {
    struct slot_geometry geo = geometry(w, h);
    int u = geo.u;
    if (!m) {
        empty_slot(c, x, y, w, h, &geo);
        return;
    }
    char buf[40];
    bool egg = m->species == PD_SPECIES_EGG;
    bool fainted = !egg && m->hp == 0;
    const struct slot_colors* col = pd_gb_look ? (fainted ? &GB_SLOT_FAINTED : selected ? &GB_SLOT_SELECTED : &GB_SLOT)
        : fainted ? (selected ? &SLOT_SELECTED_FAINTED : &SLOT_FAINTED) : selected ? &SLOT_SELECTED : &SLOT_NORMAL;
    slot_frame(c, x, y, &geo, col);
    if (!egg) hp_bar(c, x, y, &geo, m->hp, m->maxHp);

    // The Poke Ball, then the icon over it, resting 4 px up.
    static const uint32_t BALL_COLORS[4] = { 0x4A4A63, 0xB54200, 0xC65200, 0xB5BDCE };
    char_bitmap(c, BALL, 20, x + geo.fx - 6 * u, y + geo.fy - 4 * u, u, "KRHW", BALL_COLORS);
    pd_draw_mon_icon_shiny(c, g, m->species, m->shiny, x + geo.fx - 8 * u, y + geo.fy - 4 * u, u, false);

    int textW = geo.fx + geo.fw - 4 * u - geo.textX;
    pd_text_fit(c, x + geo.textX, y + geo.nameMid - 8, textW, mon_display_name(g, m, buf, sizeof(buf)), SLOT_TEXT,
                SLOT_TEXT_SHADOW);
    if (!egg) {
        const char* status = fainted ? "FNT" : pd_status_label(m->status);
        if (*status) {
            status_chip(c, x + geo.textX + 6 * u, y + geo.levelMid - 7, status);
        } else {
            char lv[12];
            snprintf(lv, sizeof(lv), "Lv%d", m->level);
            int lw = pd_text(c, x + geo.textX + 6 * u, y + geo.levelMid - 8, lv, SLOT_TEXT, SLOT_TEXT_SHADOW);
            if (m->shiny) pd_text(c, x + geo.textX + 6 * u + lw + 3, y + geo.levelMid - 8, "*", SHINY_MARK, SLOT_TEXT_SHADOW);
        }
        int gx = x + geo.fx + geo.fw - 14 * u, gy = y + geo.levelMid - 4 * u;
        if (m->gender == PD_GENDER_MALE || m->gender == PD_GENDER_FEMALE) {
            bool male = m->gender == PD_GENDER_MALE;
            const uint32_t shadow[1] = { male ? MALE_SHADOW : FEMALE_SHADOW };
            const uint32_t color[1] = { male ? MALE : FEMALE };
            char_bitmap(c, male ? MALE_MARK : FEMALE_MARK, 8, gx + u, gy + u, u, "1", shadow);
            char_bitmap(c, male ? MALE_MARK : FEMALE_MARK, 8, gx, gy, u, "1", color);
        }
        char hp[16];
        snprintf(hp, sizeof(hp), "%d/%d", m->hp, m->maxHp);
        pd_text_right(c, x + geo.barRight, y + geo.hpTextMid - 8, hp, SLOT_TEXT, SLOT_TEXT_SHADOW);
    }
    ui_add_hit(ui, x + geo.fx, y + geo.fy, geo.fw, geo.fh, HIT_SLOT + slot);
}

// MonStatsSection.kt: struct order differs from the summary's display order.
static void summary_stats(struct pd_canvas* c, const struct pd_mon_stats* s,
                          int x, int y, int w, int h) {
    static const int order[] = {0, 1, 2, 4, 5, 3};
    static const char* labels[] = {"HP", "ATTACK", "DEFENSE", "SPEED", "SP. ATK", "SP. DEF"};
    // Ten lines (the heading, six stats, TOTAL, NATURE, HIDDEN POWER): the last one's text ends 14 rows below its top.
    int row = (h - 14) / 9;
    if (row > 25) row = 25;
    int statX = x + w * 46 / 100, ivX = x + w * 83 / 100;
    char buf[48];
    pd_text_right(c,statX,y,"STAT",PD_MUTED,PD_MUTED_SHADOW);
    if(s->hasIvs)pd_text_right(c,ivX,y,"IV",PD_MUTED,PD_MUTED_SHADOW);
    if(s->hasEvs)pd_text_right(c,x+w,y,"EV",PD_MUTED,PD_MUTED_SHADOW);
    int up=s->hasNature?pd_nature_raises(s->nature):-1;
    int down=s->hasNature?pd_nature_lowers(s->nature):-1;
    int ivTotal=0,evTotal=0;
    for(int r=0;r<6;r++) {
        int i=order[r], ry=y+(r+1)*row;
        uint32_t color=i==up?0xE04040:i==down?0x3984F7:PD_LABEL;
        snprintf(buf,sizeof buf,"%s%s",labels[i],i==up?" +":i==down?" -":"");
        pd_text_fit(c,x,ry,w*32/100,buf,color,PD_LABEL_SHADOW);
        snprintf(buf,sizeof buf,"%d",s->values[i]);
        pd_text_right(c,statX,ry,buf,PD_LABEL,PD_LABEL_SHADOW);
        if(s->hasIvs && w>=220) {
            int bx=statX+8,bw=ivX-bx-23;
            pd_fill(c,bx,ry+4,bw,6,0x506858);
            uint32_t fill=s->ivs[i]==31?0xE8B830:s->ivs[i]*2>31?0x60D048:s->ivs[i]*5>31?0xF8C838:0xF04838;
            pd_fill(c,bx+1,ry+5,(bw-2)*s->ivs[i]/31,4,fill);
        }
        snprintf(buf,sizeof buf,"%d",s->ivs[i]);
        if(s->hasIvs)pd_text_right(c,ivX,ry,buf,PD_VALUE,PD_VALUE_SHADOW);
        snprintf(buf,sizeof buf,"%d",s->evs[i]);
        if(s->hasEvs)pd_text_right(c,x+w,ry,buf,PD_VALUE,PD_VALUE_SHADOW);
        ivTotal+=s->ivs[i];evTotal+=s->evs[i];
    }
    int ry=y+7*row;
    pd_text(c,x,ry,"TOTAL",PD_LABEL,PD_LABEL_SHADOW);
    snprintf(buf,sizeof buf,"%d",ivTotal);
    if(s->hasIvs)pd_text_right(c,ivX,ry,buf,PD_VALUE,PD_VALUE_SHADOW);
    snprintf(buf,sizeof buf,"%d",evTotal);
    if(s->hasEvs)pd_text_right(c,x+w,ry,buf,PD_VALUE,PD_VALUE_SHADOW);
    pd_fill(c,x,ry+(row+18)/2,w,1,PD_DIVIDER); // between TOTAL's shadow (row 13) and NATURE's caps (row+4)
    if(s->hasNature)pd_text(c,x,ry+row,"NATURE",PD_MUTED,PD_MUTED_SHADOW);
    const char* nature=s->nature>=0&&s->nature<pd_nature_names_count?pd_nature_names[s->nature]:"?";
    if(s->hasNature)pd_text_right(c,x+w,ry+row,nature,PD_VALUE,PD_VALUE_SHADOW);
    if(s->hasHiddenPower) {
        int lw=pd_text(c,x,ry+2*row,"HIDDEN POWER",PD_MUTED,PD_MUTED_SHADOW);
        if(s->hiddenPowerPower>0)
            snprintf(buf,sizeof buf,"%s  PWR %d",pd_type_name(NULL, s->hiddenPowerType),s->hiddenPowerPower);
        else snprintf(buf,sizeof buf,"%s",pd_type_name(NULL, s->hiddenPowerType));
        int vw=pd_text_width(buf); if(vw>w-lw-8)vw=w-lw-8;
        pd_text_fit(c,x+w-vw,ry+2*row,vw,buf,PD_LABEL,PD_LABEL_SHADOW);
    }
}

// INFO: the EXP bar, the held item and its description, the type matchups (MonDetailScreen's information column,
// after Keegan Whitacre's Summary INFO in pokedaisy-brick), packed to fit rows of a 341x256 canvas.
static void summary_info(struct pd_canvas* c, const struct pd_game* g, const struct pd_mon* m, int x, int y, int w,
                         int bottom) {
    char buf[96];
    int row = y;
    struct pd_exp xp;
    if (m->hasExperience && pd_retail_exp_progress(m->species, m->level, m->experience, w - 2, &xp)) {
        pd_text(c, x, row, "EXP", PD_MUTED, PD_MUTED_SHADOW);
        snprintf(buf, sizeof(buf), "%lu", (unsigned long) m->experience);
        pd_text_right(c, x + w, row, buf, PD_VALUE, PD_VALUE_SHADOW);
        row += 17;
        pd_fill(c, x, row, w, 9, PD_DIVIDER);
        pd_fill(c, x + 1, row + 1, w - 2, 7, 0x506858);
        pd_fill(c, x + 1, row + 1, xp.filled, 7, 0x40B8F8);
        row += 10;
        if (m->level == 100) snprintf(buf, sizeof(buf), "MAX");
        else snprintf(buf, sizeof(buf), "NEXT %lu", (unsigned long) xp.toNext);
        pd_text_right(c, x + w, row, buf, PD_VALUE, PD_VALUE_SHADOW);
        row += 17;
    }
    if (m->hasHeldItem && row + 17 <= bottom) {
        pd_text(c, x, row, "HELD ITEM", PD_MUTED, PD_MUTED_SHADOW);
        const char* item = m->heldItem ? pd_item_name(g, m->heldItem) : "NONE";
        if (!item[0]) item = "UNKNOWN ITEM";
        pd_text_fit(c, x + 84, row, w - 84, item, PD_LABEL, PD_LABEL_SHADOW);
        row += 17;
        const char* desc = m->heldItem ? pd_item_description(g, m->heldItem) : "";
        if (desc[0] && row + 14 <= bottom) {
            int lines = (bottom - row - 40) / 14;
            if (lines > 2) lines = 2;
            if (lines > 0) row += pd_text_wrap(c, x, row, w, 14, lines, desc, PD_LABEL, PD_LABEL_SHADOW) * 14 + 4;
        }
    }
    int t1, t2;
    pd_species_type_pair(g, m->species, &t1, &t2);
    static const char* const heads[3] = {"WEAK TO", "RESISTS", "IMMUNE"};
    for (int group = 0; group < 3; group++) {
        buf[0] = 0;
        for (int t = 0; t < 32; t++) {
            if (!pd_type_name(g, t)[0] || !strcmp(pd_type_name(g, t), "???")) continue;
            int pct = pd_type_multiplier_pct(g, t, t1, t2);
            if (!((group == 0 && pct > 100) || (group == 1 && pct > 0 && pct < 100) || (group == 2 && pct == 0)))
                continue;
            size_t n = strlen(buf);
            snprintf(buf + n, sizeof(buf) - n, "%s%s", n ? ", " : "", pd_type_name(g, t));
        }
        if (!buf[0] || row + 13 > bottom) continue;
        pd_text(c, x, row, heads[group], PD_MUTED, PD_MUTED_SHADOW);
        pd_text_fit(c, x + 66, row, w - 66, buf, PD_LABEL, PD_LABEL_SHADOW);
        row += 14;
    }
}

static void summary(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_mon* m) {
    char buf[40], buf2[40];
    int x = MARGIN, y = CONTENT_Y, w = c->w - 2 * MARGIN, h = c->h - CONTENT_Y - MARGIN;
    int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
    int ix = x + f + 6, iw = w - 2 * (f + 6);
    int ty = y + f + 2;
    // A short card (a 240-row frame: the 3DS, 720x480 panels) packs the name and HP lines closer, so the INFO and
    // STATS panes get the room they have on a 256-row frame.
    bool shortCard = h < 210;
    int lineH = shortCard ? 16 : 20;
    // The icon left of the first two lines (name, HP).
    if (pd_draw_mon_icon_shiny(c, g, m->species, m->shiny, ix - 2, ty + 2, 1, m->species != PD_SPECIES_EGG && m->hp == 0)) {
        ix += PD_ICON + 2;
        iw -= PD_ICON + 2;
    }
    int ix0 = x + f + 6, iw0 = w - 2 * (f + 6);

    bool egg = m->species == PD_SPECIES_EGG;
    const char* name = mon_display_name(g, m, buf, sizeof(buf));
    int nameW = pd_text(c, ix, ty, name, PD_LABEL, PD_LABEL_SHADOW);
    if (!egg) {
        gender_mark(c, ix + nameW + 3, ty, m->gender);
        if (m->shiny) pd_text(c, ix + nameW + 14, ty, "*", SHINY_MARK, PD_LABEL_SHADOW);
        char lv[12];
        snprintf(lv, sizeof(lv), "Lv%d", m->level);
        pd_text_right(c, ix + iw, ty, lv, PD_VALUE, PD_VALUE_SHADOW);
        const char* species = pd_species_name(g, m->species, buf2, sizeof(buf2));
        if (strcmp(species, name)) pd_text(c, ix + nameW + 14, ty, species, PD_MUTED, PD_MUTED_SHADOW);
    }
    ty += lineH;
    if (!egg) {
        ui_hp_bar(c, ix, ty + 4, 96, m->hp, m->maxHp);
        char hp[16];
        snprintf(hp, sizeof(hp), "%d/%d", m->hp, m->maxHp);
        pd_text(c, ix + 120, ty, hp, PD_LABEL, PD_LABEL_SHADOW);
        ui_status_badge(c, ix + iw - 30, ty + 2, m->status);
        ty += lineH;
        ix = ix0;
        iw = iw0;
        pd_fill(c, ix, ty, iw, 1, PD_DIVIDER);
        ty += shortCard ? 3 : 4;
        int bottom = y + h - f - 30;
        if (ui->summaryInfo) {
            summary_info(c, g, m, ix, ty, iw, bottom);
        } else if (ui->summaryStats && m->stats.valid) {
            summary_stats(c, &m->stats, ix, ty, iw, bottom - ty);
        } else for (int i = 0; i < PD_NUM_MOVES; i++) {
            if (!m->moves[i]) continue;
            pd_text_fit(c, ix, ty, iw - 120, pd_move_name(g, m->moves[i], buf, sizeof(buf)), PD_LABEL, PD_LABEL_SHADOW);
            pd_text(c, ix + iw - 116, ty, pd_type_name(g, pd_move_type(g, m->moves[i])), PD_MUTED, PD_MUTED_SHADOW);
            char pp[12];
            snprintf(pp, sizeof(pp), "PP %d", m->pp[i]);
            pd_text_right(c, ix + iw, ty, pp, PD_VALUE, PD_VALUE_SHADOW);
            ty += 20;
        }
    } else {
        pd_text(c, ix, ty, "It's an egg. It will hatch in time.", PD_MUTED, PD_MUTED_SHADOW);
    }
    if (!egg) {
        // The two panes this isn't, as buttons right to left from BACK: the pane's own button goes back to MOVES.
        struct pane_btn { const char* label; int hit; } pane[2];
        int n = 0;
        if (ui->summaryInfo) {
            pane[n++] = (struct pane_btn){"MOVES", HIT_SUMMARY_INFO};
            if (m->stats.valid) pane[n++] = (struct pane_btn){"STATS", HIT_SUMMARY_STATS};
        } else {
            if (m->stats.valid) pane[n++] = (struct pane_btn){ui->summaryStats ? "MOVES" : "STATS", HIT_SUMMARY_STATS};
            pane[n++] = (struct pane_btn){"INFO", HIT_SUMMARY_INFO};
        }
        for (int i = 0; i < n; i++)
            ui_button(ui, c, x + w - f - 70 - 76 * (i + 1), y + h - f - 26, 72, 22, pane[i].label, pane[i].hit);
    }
    ui_button(ui, c, x + w - f - 70, y + h - f - 26, 66, 22, "BACK", HIT_BACK);
}

void ui_party_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    if (ui->summarySlot >= 0) {
        summary(ui, c, g, &s->party[ui->summarySlot]);
        return;
    }
    // PartyGrid: edge to edge (each slot carries its own Poke Ball overhang), two
    // columns, three on a screen wider than 2:1. The selected look follows the
    // pressed or D-pad slot, else the first, like the game's cursor.
    int x0 = MARGIN, y0 = CONTENT_Y - 2, w = c->w - 2 * MARGIN, h = c->h - y0 - MARGIN;
    int cols = w > 2 * h ? 3 : 2, rows = (PD_PARTY_SIZE + cols - 1) / cols;
    int selected = 0;
    for (int i = 0; i < PD_PARTY_SIZE; i++) {
        if (ui->focusId == HIT_SLOT + i || ui->pressedId == HIT_SLOT + i) selected = i;
    }
    for (int i = 0; i < PD_PARTY_SIZE; i++) {
        int col = i % cols, row = i / cols;
        int x = x0 + w * col / cols, y = y0 + h * row / rows;
        int cw = x0 + w * (col + 1) / cols - x, ch = y0 + h * (row + 1) / rows - y;
        const struct pd_mon* m = i < s->partyCount ? &s->party[i] : NULL;
        party_slot(ui, c, g, x, y, cw, ch, m, i, m && i == selected);
    }
}

// The party's Poke Ball, 20x20 at 1x: what the HUD draws where a game has no item icons (Ruby / Sapphire; the
// app's PixelIcons.pokeBall in FieldHud).
void pd_draw_party_ball(struct pd_canvas* c, int x, int y) {
    static const uint32_t RED_WHITE[4] = { 0x383840, 0xE04838, 0xA83028, 0xF8F8F8 };
    char_bitmap(c, BALL, 20, x, y, 1, "KRHW", RED_WHITE);
}
