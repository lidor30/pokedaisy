// SETTINGS: what the companion knows about the game, then the options - and
// the black-out overlays they open: a pick-list for a multi-choice setting
// (the app's OptionSelector: never a row cycling through more than two
// values) and a YES / NO confirm (OptionConfirm).
#include <stdio.h>
#include <string.h>

#include "pd_ui_internal.h"

#define TITLE_H 24
#define LIST_Y (CONTENT_Y + TITLE_H + 4)

static const char* const SCREEN_NAMES[PD_SCREEN_MODES] = { "PIXEL 1x", "SHARP 1.25x", "SHARP 1.5x", "STRETCH" };
static const char* const ASPECT_NAMES[PD_ASPECTS] = { "ORIGINAL", "EXPAND", "ZOOM OUT" };
const char* const PD_GAME_SPEEDS[PD_GAME_SPEED_COUNT] = { "1x", "1.5x", "2x", "3x", "4x", "0.5x", "0.25x" };
static const char* const VOLUMES[5] = { "20%", "40%", "60%", "80%", "100%" };
// FLY ON (the app's FlightVehicle, linux/flight.h's enum flight_vehicle): the game's bird, or a vehicle with the player in it.
static const char* const FLY_ON_NAMES[4] = { "BIRD", "AIR BALLOON", "HELICOPTER", "ZEPPELIN" };
static const char* flight_vehicle_label(int v) { return FLY_ON_NAMES[v >= 0 && v < 4 ? v : 0]; }

const char* pd_aspect_name(int aspect) {
    return aspect >= 0 && aspect < PD_ASPECTS ? ASPECT_NAMES[aspect] : "";
}

const char* pd_screen_mode_name(int mode) {
    return mode >= 0 && mode < PD_SCREEN_MODES ? SCREEN_NAMES[mode] : "";
}

// The list's visible band (rows outside it are drawn clipped and take no taps) and its rows' height last time
// (the scroll is clamped to it before drawing, so a D-pad's step past the end never draws an empty list).
static int listTop, listBottom, lastTotal = -1;
// Where the value column starts in a row: the app's labelWeight 0.58 beside the rail (a narrower list), else 42%.
static int valuePct = 42;

int pd_ui_list_step(int selected, int count, int step) {
    if (count <= 0) return -1;
    int next = selected + step;
    return next < 0 ? 0 : next >= count ? count - 1 : next;
}

static void settings_nav_row(struct pd_ui* ui, int id, int y) {
    if (!ui->shoulderTabs || ui->settingsNavCount >= PD_UI_MAX_HITS) return;
    int i = ui->settingsNavCount++;
    ui->settingsNav[i].id = id;
    ui->settingsNav[i].top = y;
    if (ui->settingsNavId < 0) ui->settingsNavId = id;
}

int ui_settings_follow(int scroll, int y, int height) {
    // At the first setting keep the game's context (place, money and host) visible.
    return y <= 0 ? 0 : ui_scroll_to(scroll, y, ROW_H, height);
}

static void settings_outline(struct pd_canvas* c, int x, int y, int w, int h) {
    pd_fill(c, x, y, w, 2, PD_VALUE);
    pd_fill(c, x, y+h-2, w, 2, PD_VALUE);
    pd_fill(c, x, y, 2, h, PD_VALUE);
    pd_fill(c, x+w-2, y, 2, h, PD_VALUE);
}

static void setting(struct pd_ui* ui, struct pd_canvas* c, int x, int* y, int w, enum setting_row row,
                    const char* label, const char* value) {
    if (ui->shoulderTabs) {
        int top = *y + ui->settingsScroll - listTop;
        settings_nav_row(ui, HIT_SETTING + row, ui->settingsNavCount == 0 ? 0 : top);
    }
    ui_row_split(c, x, *y, w, label, value, ui->pressedId == HIT_SETTING + (int) row, valuePct);
    // A scroll hit: a tap is the row, a drag on it still scrolls the list.
    if (*y >= listTop && *y + ROW_H <= listBottom) {
        ui_add_scroll_hit(ui, x - 4, *y, w + 8, ROW_H, HIT_SETTING + (int) row);
        if (ui->shoulderTabs && ui->overlay == PD_OVERLAY_NONE && ui->settingsNavId == HIT_SETTING + (int) row)
            settings_outline(c, x - 4, *y, w + 8, ROW_H);
    }
    *y += ROW_H;
}

// A row shown, not tappable (LOAD STATE with none yet, ASPECT N/A).
static void muted_row(struct pd_canvas* c, int x, int* y, int w, const char* label, const char* value) {
    pd_text(c, x, pd_text_y(*y, ROW_H), label, PD_MUTED, PD_MUTED_SHADOW);
    int vx = x + w * valuePct / 100, past = x + pd_text_width(label) + 8;
    pd_text(c, vx < past ? past : vx, pd_text_y(*y, ROW_H), value, PD_MUTED, PD_MUTED_SHADOW);
    *y += ROW_H;
}

// The rail's groups as last drawn: each title's top in the list (content pixels) and its label; the one a tab picked, kept
// while the list stays where that tap put it (a drag or the D-pad hands the choice back to the scroll, as in GroupTabs).
#define MAX_GROUPS 10
static int groupCount, groupTop[MAX_GROUPS], picked = -1, pickedScroll;

static const char* groupName[MAX_GROUPS];
static bool railed;

void ui_settings_group(struct pd_ui* ui, int i) {
    if (i < 0 || i >= groupCount) return;
    // The last group goes to the list's end, so what closes the list (CLOSE GAME / RESTART GAME) shows whole.
    ui->settingsScroll = ui_clamp_scroll(i == groupCount - 1 ? lastTotal : groupTop[i], lastTotal, listBottom - listTop);
    picked = i;
    pickedScroll = ui->settingsScroll;
}

// FLIGHT AREA (the app's GAMEPLAY row): where TAKE OFF may fly. Two values, so it flips in place.
static void flight_area_row(struct pd_ui* ui, struct pd_canvas* c, int x, int* y, int w, const struct pd_settings* st) {
    setting(ui, c, x, y, w, SETTING_FLIGHT_AREA, "FLIGHT AREA", st->flightAnywhere ? "ANYWHERE" : "VISITED");
}

static void group(struct pd_canvas* c, int x, int* y, const char* title) {
    if (railed && groupCount < MAX_GROUPS) {
        groupTop[groupCount] = *y - listTop + 0; // the list's scroll is added back below
        groupName[groupCount++] = title;
    }
    *y += 3;
    // Gen 1's value colour is the rows' black: its titles are a black band (groupTitleFill).
    if (pd_cur->groupTitleFill != PD_NO_SHADOW) pd_fill(c, x - 4, *y + 1, pd_text_width(title) + 8, ROW_H - 2, pd_cur->groupTitleFill);
    pd_text(c, x, pd_text_y(*y, ROW_H), title, pd_cur->groupTitleText, pd_cur->groupTitleShadow);
    *y += ROW_H;
}

// SETTINGS' group tabs (TabRail, outside the card - the user's call): a tab chip's frame each, the open group's white with a
// red label like the bar's, sharing the rail's height out; a tap scrolls the list to that group.
static void group_rail(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, int h, int scroll) {
    int selected = picked >= 0 && picked < groupCount && scroll == pickedScroll ? picked : -1;
    if (selected < 0) {
        picked = -1;
        int view = listBottom - listTop;
        selected = 0;
        if (lastTotal > view && scroll >= lastTotal - view - 1) selected = groupCount - 1;
        else for (int i = 0; i < groupCount; i++) if (groupTop[i] <= scroll + 4) selected = i;
    }
    // The tabs share the window's height out, the rounding spread a pixel at a time so the last ends flush with it.
    const int gap = 3, n = groupCount > 0 ? groupCount : 1;
    int room = h - gap * (n - 1), th0 = room / n;
    if (th0 > 30) th0 = 30, room = th0 * n;
    for (int i = 0, ty = y; i < groupCount; i++) {
        int th = th0 + (i < room - th0 * n ? 1 : 0);
        bool on = i == selected || ui->pressedId == HIT_SETTINGS_GROUP + i;
        pd_layered_two_tone(c, x, ty, w, th, 3, pd_cur->chip, pd_cur->chipCount, on ? pd_cur->tabSelectedFill : pd_cur->tabIdleFill, 0);
        pd_text_fit(c, x + 4 + (w - 8 - pd_text_width(groupName[i]) > 0 ? (w - 8 - pd_text_width(groupName[i])) / 2 : 0),
                    pd_text_y(ty, th), w - 8, groupName[i], on ? PD_VALUE : PD_LABEL, on ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
        ui_add_hit(ui, x, ty, w, th, HIT_SETTINGS_GROUP + i);
        ty += th + gap;
    }
}

void ui_settings_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                     const struct pd_host_info* host) {
    ui->settingsNavCount = 0;
    int x = MARGIN, w = c->w - 2 * MARGIN;
    // The full companion's long list gets its group tabs down the left, on the backdrop beside the list's window.
    railed = host && host->fullSettings && !ui->optionSource;
    valuePct = railed ? 58 : 42;
    groupCount = 0;
    int railX = x, railW = 0;
    if (railed) {
        static const char* const LABELS[] = { "GAME", "FF / SPEED", "SOUND", "GAMEPLAY", "SCREEN", "COMPANION" };
        for (unsigned i = 0; i < sizeof(LABELS) / sizeof(*LABELS); i++)
            if (pd_text_width(LABELS[i]) + 12 > railW) railW = pd_text_width(LABELS[i]) + 12;
    }
    int f = pd_title_box(c, x, CONTENT_Y, w, TITLE_H, PD_TITLE_FILL);
    int ty = pd_text_y(CONTENT_Y, TITLE_H);
    pd_text(c, x + f + 6, ty, ui->optionSource ? ui->optionSource->title : "SETTINGS", PD_TITLE_TEXT, PD_TITLE_SHADOW);
    int right = x + w - f - 6;
    // The TRAINER CARD opens from here (the list below is full): a grey chip at
    // the title window's right, white while pressed, like the tab bar's.
    if (host && host->batteryPct >= 0 && host->fullSettings) {
        char pct[16];
        snprintf(pct, sizeof(pct), "%d%%", host->batteryPct);
        right -= pd_text_width(pct);
        pd_text(c, right, ty, pct, PD_TITLE_TEXT, PD_TITLE_SHADOW);
        right -= 26;
        pd_fill(c, right + 2, ty + 5, 18, 8, PD_TITLE_TEXT);
        pd_fill(c, right + 3, ty + 6, 16, 6, PD_TITLE_FILL);
        pd_fill(c, right + 4, ty + 7, 14 * host->batteryPct / 100, 4, PD_TITLE_TEXT);
        pd_fill(c, right + 20, ty + 7, 2, 4, PD_TITLE_TEXT);
        right -= 2;
    } else if (g->cfg && g->cfg->cardStyle && g->rom) {
        const char* label = "TRAINER CARD";
        int bw = pd_text_width(label) + 12, bh = TITLE_H - 6;
        int bx = x + w - f - 2 - bw, by = CONTENT_Y + 3;
        pd_title_box(c, bx, by, bw, bh, ui->pressedId == HIT_OPEN_CARD ? PD_TITLE_FILL : PD_LIST_FILL);
        pd_text(c, bx + 6, pd_text_y(by, bh), label, PD_TITLE_TEXT, PD_TITLE_SHADOW);
        ui_add_hit(ui, bx, by, bw, bh, HIT_OPEN_CARD);
        settings_nav_row(ui, HIT_OPEN_CARD, 0);
        if (ui->shoulderTabs && ui->overlay == PD_OVERLAY_NONE && ui->settingsNavId == HIT_OPEN_CARD)
            settings_outline(c, bx, by, bw, bh);
        right = bx - 8;
    }
    if (g->title) {
        int tx = x + f + 6 + pd_text_width("SETTINGS") + 12;
        int tw = pd_text_width(g->title);
        if (tw > right - tx) tw = right - tx;
        pd_text_fit(c, right - tw, ty, tw, g->title, PD_TITLE_TEXT, PD_TITLE_SHADOW);
    }

    int lh = c->h - LIST_Y - MARGIN;
    if (railed) {
        x += railW + 4;
        w -= railW + 4;
    }
    int lf = pd_list_box(c, x, LIST_Y, w, lh);
    // The bar takes its column off the rows while there's more than fits.
    const int infoCount = ui->optionSource ? ui->optionSource->infoCount : 0;
    const int separator = infoCount ? 4 : 0;
    const int totalOptions = ui->optionSource ? ui->optionSource->count * ROW_H + separator : lastTotal;
    bool hasBar = totalOptions > lh - 2 * lf;
    int ix = x + lf + 4, iw = w - 2 * (lf + 4) - (hasBar ? SCROLL_BAR_W + 2 : 0);
    // The rows scroll where they don't all fit (a drag, or the D-pad past the last one shown).
    listTop = LIST_Y + lf;
    listBottom = LIST_Y + lh - lf;
    ui->settingsNavHeight = listBottom - listTop;
    ui_add_scroll_hit(ui, x, listTop, w, listBottom - listTop, HIT_SETTINGS_LIST);
    if (ui->optionSource) {
        const int n = ui->optionSource->count, view = listBottom - listTop;
        const int selected = ui->optionSelected;
        if (selected >= infoCount && selected < n)
            ui->settingsScroll = ui_settings_follow(ui->settingsScroll,
                selected == infoCount ? 0 : selected * ROW_H + separator, view);
        ui->settingsScroll = ui_clamp_scroll(ui->settingsScroll, totalOptions, view);
        pd_clip(c, x + lf, listTop, w - 2 * lf, view);
        if (separator) pd_fill(c, ix, listTop + infoCount * ROW_H - ui->settingsScroll + 1, iw, 1, PD_DIVIDER);
        for (int i = 0; i < n; i++) {
            const int ry = listTop + i * ROW_H + (i >= infoCount ? separator : 0) - ui->settingsScroll;
            if (ry + ROW_H <= listTop || ry >= listBottom) continue;
            const struct pd_option_row* row = &ui->optionSource->rows[i];
            ui_row(c, ix, ry, iw, row->label, row->value, false);
            if (i == selected) settings_outline(c, ix - 4, ry, iw + 8, ROW_H);
        }
        pd_unclip(c);
        if (hasBar)
            ui_scroll_rail(ui,c,x+w-lf-SCROLL_BAR_W-2,listTop+2,SCROLL_BAR_W,view-4,ui->settingsScroll,totalOptions,view);
        return;
    }
    pd_clip(c, x + lf, listTop, w - 2 * lf, listBottom - listTop);
    if (lastTotal >= 0) ui->settingsScroll = ui_clamp_scroll(ui->settingsScroll, lastTotal, listBottom - listTop);
    int y = listTop - ui->settingsScroll;
    char buf[40];
    struct pd_settings* st = ui->settings;
    bool full = host && host->fullSettings;
    if (full) group(c, ix, &y, "GAME");
    if (s->valid) {
        const char* place = pd_mapsec_name(g, s->mapsec);
        ui_row(c, ix, y, iw, "PLACE", *place ? place : "-", false);
        y += ROW_H;
        pd_ui_format_money(s->money, buf, sizeof(buf));
        ui_row(c, ix, y, iw, "MONEY", buf, false);
        y += ROW_H;
    }
    for (int i = 0; host && i < host->count && i < PD_UI_HOST_LINES; i++) {
        ui_row(c, ix, y, iw, host->labels[i], host->values[i], false);
        y += ROW_H;
    }
    if (!full) {
        pd_fill(c, ix, y + 1, iw, 1, PD_DIVIDER);
        y += 4;
    }
    if (full) {
        group(c, ix, &y, "FF / SPEED");
        setting(ui, c, ix, &y, iw, SETTING_FF_TOGGLE, "FF", host->ffOn ? "ON" : "OFF");
    }
    if (st->ffSpeed) snprintf(buf, sizeof(buf), "%dx", st->ffSpeed);
    else snprintf(buf, sizeof(buf), "MAX");
    if (full || !host || !host->frontendOwnsPlayback) setting(ui, c, ix, &y, iw, SETTING_FF, full ? "FF SPEED" : "FAST-FORWARD", buf);
    if (full) {
        setting(ui, c, ix, &y, iw, SETTING_FF_MODE, "FF MODE", st->ffNormal ? "NORMAL" : "SMART");
        setting(ui, c, ix, &y, iw, SETTING_GAME_SPEED, "GAME SPEED", PD_GAME_SPEEDS[st->gameSpeed]);
        group(c, ix, &y, "SOUND");
        snprintf(buf, sizeof(buf), "%d%%", st->gameVolume);
        setting(ui, c, ix, &y, iw, SETTING_VOLUME, "GAME VOLUME", buf);
        setting(ui, c, ix, &y, iw, SETTING_MUTE, "MUTE GAME", st->gameMuted ? "ON" : "OFF");
        group(c, ix, &y, "GAMEPLAY");
        if (host->wildSupported) setting(ui, c, ix, &y, iw, SETTING_WILD, "WILD ENCOUNTERS", st->wildOff ? "OFF" : "ON");
        else muted_row(c, ix, &y, iw, "WILD ENCOUNTERS", "N/A");
        // FREE FLIGHT's (the app's FLY STYLE / FLIGHT AREA, and MAKEOVER's FLY ON): two values flip in place.
        if (host->flightSupported) {
            setting(ui, c, ix, &y, iw, SETTING_FLY_STYLE, "FLY STYLE", st->flyClassic ? "CLASSIC" : "SCENIC");
            setting(ui, c, ix, &y, iw, SETTING_FLIGHT_AREA, "FLIGHT AREA", st->flightAnywhere ? "ANYWHERE" : "VISITED");
            setting(ui, c, ix, &y, iw, SETTING_FLY_ON, "FLY ON", flight_vehicle_label(st->flightVehicle));
        } else {
            muted_row(c, ix, &y, iw, "FLY STYLE", "N/A");
            muted_row(c, ix, &y, iw, "FLIGHT AREA", "N/A");
        }
        if (host->fieldSupported) {
            setting(ui, c, ix, &y, iw, SETTING_AUTO_RUN, "AUTO-RUN", st->autoRun ? "ON" : "OFF");
            setting(ui, c, ix, &y, iw, SETTING_RUN_INDOORS, "RUN INDOORS", st->runIndoors ? "ON" : "OFF");
        } else {
            muted_row(c, ix, &y, iw, "AUTO-RUN", "N/A");
            muted_row(c, ix, &y, iw, "RUN INDOORS", "N/A");
        }
        if (host->hasCheats) setting(ui, c, ix, &y, iw, SETTING_CHEATS, "CHEATS", st->cheats ? "ON" : "OFF");
        else muted_row(c, ix, &y, iw, "CHEATS", "N/A");
        // A host with FREE FLIGHT of its own has FLIGHT AREA above with FLY STYLE; one with TAKE OFF alone, here.
        if (host->hasFlightArea && !host->flightSupported) flight_area_row(ui, c, ix, &y, iw, st);
        group(c, ix, &y, "SCREEN");
    }
    // PIXEL 1x is ORIGINAL's: EXPAND that wide can't keep up, so there it reads as the next size (pd_session_screen).
    ui->pixelOff = host && host->expandGame && st->aspect != PD_ASPECT_ORIGINAL;
    if (!host || !host->hideScreenMode)
        setting(ui, c, ix, &y, iw, SETTING_SCREEN, "SCREEN",
                pd_screen_mode_name(ui->pixelOff && st->screenMode == PD_SCREEN_PIXEL ? PD_SCREEN_SHARP_125 : st->screenMode));
    if (host && host->hasExpand) {
        if (host->expandGame) {
            setting(ui, c, ix, &y, iw, SETTING_ASPECT, "ASPECT", pd_aspect_name(st->aspect));
            // Two values: flips in place (the app's rule for pick-lists is more than two). Only
            // ZOOM OUT has anything to step back from.
            if (st->aspect == PD_ASPECT_ZOOM_OUT)
                setting(ui, c, ix, &y, iw, SETTING_AUTO_ZOOM, "AUTO ZOOM IN", st->autoZoom ? "ON" : "OFF");
        } else {
            // This game's overworld can't be rebuilt: shown, not tappable.
            muted_row(c, ix, &y, iw, "ASPECT", "N/A");
        }
    }
    if (host && host->hasHud) setting(ui, c, ix, &y, iw, SETTING_HUD, "HUD", st->hud ? "ON" : "OFF");
    if (host && host->hasPerf) setting(ui, c, ix, &y, iw, SETTING_PERF, "FPS / CPU", st->showPerf ? "ON" : "OFF");
    if (host && host->hasDisplayFilter)
        setting(ui, c, ix, &y, iw, SETTING_FILTER, "FILTER", st->lcdFilter ? "LCD" : "OFF");
    if (host && host->hasQuickMenu)
        setting(ui, c, ix, &y, iw, SETTING_QUICK_MENU, "QUICK MENU", st->quickGrid ? "GRID" : "RING");
    // The app keeps them in GAMEPLAY; a host without the groups has them by the QUICK MENU, where TAKE OFF is: FLIGHT
    // AREA alone, or with FLY STYLE and FLY ON where the host flies FREE FLIGHT's scenic FLY and vehicles too.
    if (host && host->hasFlightArea && !full) {
        if (host->flightSupported)
            setting(ui, c, ix, &y, iw, SETTING_FLY_STYLE, "FLY STYLE", st->flyClassic ? "CLASSIC" : "SCENIC");
        flight_area_row(ui, c, ix, &y, iw, st);
        if (host->flightSupported)
            setting(ui, c, ix, &y, iw, SETTING_FLY_ON, "FLY ON", flight_vehicle_label(st->flightVehicle));
    }
    if (host && host->hasButtonRows) {
        static const char* const NAMES[PD_BUTTONS] = {"X", "Y", "L2", "R2", "NONE"};
        setting(ui, c, ix, &y, iw, SETTING_QUICK_BTN, "QUICK MENU BUTTON", NAMES[st->quickBtn]);
        setting(ui, c, ix, &y, iw, SETTING_COMPANION_BTN, "COMPANION BUTTON", NAMES[st->companionBtn]);
        if (host->hasHud) setting(ui, c, ix, &y, iw, SETTING_HUD_BTN, "HUD CURSOR BUTTON", NAMES[st->hudBtn]);
    }
    if (full) {
        setting(ui, c, ix, &y, iw, SETTING_DARK, "DARK MODE", st->darkMode ? "ON" : "OFF");
        const char* themeName = pd_themes[0].label;
        for (int i = 0; i < pd_theme_count; i++)
            if (pd_themes[i].id == st->theme) themeName = pd_themes[i].label;
        setting(ui, c, ix, &y, iw, SETTING_THEME, "THEME", themeName);
        group(c, ix, &y, "COMPANION");
        setting(ui, c, ix, &y, iw, SETTING_BATTLE_INFO, "BATTLE INFO", st->noBattleInfo ? "OFF" : "ON");
        if (!st->noBattleInfo) {
            setting(ui, c, ix, &y, iw, SETTING_HINTS, "BATTLE HINTS", st->hideHints ? "OFF" : "ON");
            setting(ui, c, ix, &y, iw, SETTING_BATTLE_VIEW, "BATTLE VIEW", st->battleInfoFirst ? "INFO" : "BUTTONS");
        }
        setting(ui, c, ix, &y, iw, SETTING_ONE_TAP, "ONE-TAP MOVES", st->oneTapMoves ? "ON" : "OFF");
        setting(ui, c, ix, &y, iw, SETTING_BATTLE_STYLE, "BATTLE STYLE", st->battleGen5 ? "GEN 5" : "GEN 4");
    }
    snprintf(buf, sizeof(buf), "%d TABS", ui_bar_count(ui));
    setting(ui, c, ix, &y, iw, SETTING_TAB_BAR, "TAB BAR", buf);
    if (full) {
        y += 3;
        pd_fill(c, ix, y, iw, 1, PD_DIVIDER);
        y += 5;
        // CLOSE GAME / RESTART GAME, under a line.
        int bw = (iw - 6) / 2;
        if (y + 22 > listTop && y < listBottom) {
            // Drawn whenever any of them shows (the list's clip cuts them), tappable on the part that shows.
            int first = ui->hitCount;
            ui_button(ui, c, ix, y, bw, 22, "CLOSE GAME", HIT_SETTING + SETTING_LEAVE);
            ui_button(ui, c, ix + bw + 6, y, bw, 22, "RESTART GAME", HIT_SETTING + SETTING_RESTART);
            for (int i = first; i < ui->hitCount; i++) {
                struct pd_hit* h = &ui->hits[i];
                int top = h->y < listTop ? listTop : h->y, bottom = h->y + h->h > listBottom ? listBottom : h->y + h->h;
                h->y = top;
                h->h = bottom - top;
            }
        }
        y += 26;
    } else if (!host || !host->frontendOwnsPlayback) {
        setting(ui, c, ix, &y, iw, SETTING_SAVE_STATE, "SAVE STATE", NULL);
        if (host && host->hasState) {
            setting(ui, c, ix, &y, iw, SETTING_LOAD_STATE, "LOAD STATE", host->stateWhen);
        } else {
            // Nothing to load: shown, not tappable.
            muted_row(c, ix, &y, iw, "LOAD STATE", "none yet");
        }
        setting(ui, c, ix, &y, iw, SETTING_LEAVE, "LEAVE GAME", NULL);
    }
    pd_unclip(c);
    int total = y + ui->settingsScroll - listTop, view = listBottom - listTop;
    lastTotal = total;
    if (railed) {
        for (int i = 0; i < groupCount; i++) groupTop[i] += ui->settingsScroll;
        group_rail(ui, c, railX, LIST_Y, railW, lh, ui->settingsScroll);
    }
    ui->settingsScroll = ui_clamp_scroll(ui->settingsScroll, total, view);
    if (total > view) {
        // Its own column: a thumb to drag, or a tap on the rail to jump there.
        int bx = x + w - lf - SCROLL_BAR_W - 2;
        ui_scroll_rail(ui, c, bx, listTop + 2, SCROLL_BAR_W, view - 4, ui->settingsScroll, total, view);
    }
}

// --- overlays ---

static void title_window(struct pd_canvas* c, int y, const char* title) {
    int x = MARGIN, w = c->w - 2 * MARGIN;
    int f = pd_title_box(c, x, y, w, TITLE_H, PD_TITLE_FILL);
    pd_text(c, x + f + 6, pd_text_y(y, TITLE_H), title, PD_TITLE_TEXT, PD_TITLE_SHADOW);
}

static void pick_list(struct pd_ui* ui, struct pd_canvas* c, const char* title, const char* const* options, int n,
                      int current) {
    int x = MARGIN, w = c->w - 2 * MARGIN;
    int y = CONTENT_Y + 8;
    title_window(c, y, title);
    y += TITLE_H + 4;
    int lf = pd_list_box(c, x, y, w, n * ROW_H + 2 * 7 + 4);
    int ix = x + lf + 4, iw = w - 2 * (lf + 4);
    int ry = y + lf + 2;
    ui->startId = HIT_OPTION + current; // the D-pad starts on the current choice
    for (int i = 0; i < n; i++) {
        bool on = i == current || ui->pressedId == HIT_OPTION + i;
        if (on) pd_fill(c, ix - 4, ry, iw + 8, ROW_H, PD_ROW_SELECTED);
        pd_text(c, ix + 10, pd_text_y(ry, ROW_H), options[i], on ? PD_VALUE : PD_LABEL,
                on ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
        if (i == current) pd_triangle(c, ix, ry + 4, 5, PD_RIGHT, PD_VALUE);
        ui_add_hit(ui, ix - 4, ry, iw + 8, ROW_H, HIT_OPTION + i);
        ry += ROW_H;
    }
}

// A page of ON / OFF rows (TAB BAR's, HUD's), each flipped by A / a tap, or a plain list (MORE's: values NULL);
// a grey note under them. B / a tap beside it closes it.
static void toggle_page(struct pd_ui* ui, struct pd_canvas* c, const char* title, const char* const* labels,
                        const bool* on, const bool* muted, int n, const char* note, const char* const* values) {
    int x = MARGIN, w = c->w - 2 * MARGIN;
    int y = CONTENT_Y + 4;
    title_window(c, y, title);
    y += TITLE_H + 4;
    int lf = pd_list_box(c, x, y, w, n * ROW_H + 2 * 7 + 4 + (note ? 16 : 0));
    int ix = x + lf + 4, iw = w - 2 * (lf + 4);
    int ry = y + lf + 2;
    if (ui->startId < 0) ui->startId = HIT_OPTION;
    for (int i = 0; i < n; i++) {
        bool sel = ui->pressedId == HIT_OPTION + i;
        if (on) {
            // A value of its own (HUD's LAYOUT / LOOK / SIZE), else ON / OFF.
            const char* v = values && values[i] ? values[i] : on[i] ? "ON" : "OFF";
            ui_row(c, ix, ry, iw, labels[i], v, sel);
            if (muted && muted[i]) {
                // Its value while the whole thing is off: shown, greyed.
                pd_fill(c, ix + iw * 42 / 100, ry + 1, iw - iw * 42 / 100, ROW_H - 2, sel ? PD_ROW_SELECTED : PD_LIST_FILL);
                pd_text(c, ix + iw * 42 / 100, pd_text_y(ry, ROW_H), v, PD_MUTED, PD_MUTED_SHADOW);
            }
        } else {
            if (sel) pd_fill(c, ix - 4, ry, iw + 8, ROW_H, PD_ROW_SELECTED);
            pd_text(c, ix + 10, pd_text_y(ry, ROW_H), labels[i], sel ? PD_VALUE : PD_LABEL,
                    sel ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
        }
        ui_add_hit(ui, ix - 4, ry, iw + 8, ROW_H, HIT_OPTION + i);
        ry += ROW_H;
    }
    if (note) pd_text_fit(c, ix, ry + 2, iw, note, PD_MUTED, PD_MUTED_SHADOW);
}

static void tab_bar_page(struct pd_ui* ui, struct pd_canvas* c) {
    const char* labels[UI_BAR_CHOICES];
    bool on[UI_BAR_CHOICES];
    enum pd_tab tabs[UI_BAR_CHOICES];
    const int n = ui_configurable_tabs(ui, tabs);
    for (int i = 0; i < n; i++) {
        labels[i] = ui_tab_label(tabs[i]);
        on[i] = (ui->settings->tabBar >> tabs[i]) & 1;
    }
    toggle_page(ui, c, "TAB BAR", labels, on, NULL, n, "At most 5 on the bar; the rest open from MORE.", NULL);
}

// FieldHud.kt's fieldHudRows: the switch, LAYOUT / LOOK / SIZE (a press cycles them), then which parts show.
static void hud_page(struct pd_ui* ui, struct pd_canvas* c) {
    enum { N = 4 + PD_HUD_PARTS };
    const struct pd_settings* s = ui->settings;
    const char* labels[N] = { "HUD", "LAYOUT", "LOOK", "SIZE" };
    const char* values[N] = { NULL, pd_hud_layout_name(s->hudLayout), pd_hud_look_name(s->hudLook),
                              pd_hud_size_name(s->hudSize) };
    bool on[N] = { s->hud, true, true, true }, muted[N] = { false, !s->hud, !s->hud, !s->hud };
    if (ui->hudLayoutFixed) {
        values[1] = "N/A";
        muted[1] = true;
    }
    for (int i = 0; i < PD_HUD_PARTS; i++) {
        labels[4 + i] = pd_hud_part_name(i);
        on[4 + i] = !((s->hudHidden >> i) & 1);
        muted[4 + i] = !s->hud;
    }
    toggle_page(ui, c, "HUD", labels, on, muted, N, "Shown over the overworld, off in menus and battles.", values);
}

// MoreScreen: every tab this game has off the bar as an app tile, a line, and the way to SETTINGS' page for it.
void ui_more_page(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g) {
    // MoreScreen.kt: square tiles across the whole width with one gap everywhere (between tiles and round the grid), a rule,
    // then the line left of TAB BAR. Tiles are a tab chip's frame, lighter (TILE_LIGHTEN / TILE_LOW), still two-tone.
    enum pd_tab more[UI_BAR_CHOICES];
    int n = ui_more_tabs(ui, g, more);
    int x = MARGIN, w = c->w - 2 * MARGIN;
    title_window(c, CONTENT_Y, "MORE");
    int y = CONTENT_Y + TITLE_H + 3, h = c->h - y - 2;
    int f = pd_list_box(c, x, y, w, h);
    const int gap = 6, bottomH = 2 + 2 * gap + 40;
    int ix = x + f + gap, iy = y + f + gap, iw = w - 2 * (f + gap), gridH = h - 2 * f - gap - bottomH;
    // As many columns as fit tiles 64 wide, four at most - five when the rows wouldn't fit the 3DS's short screen.
    int cols = (iw + gap) / (64 + gap);
    if (cols > 4) cols = 4;
    if (cols < 1) cols = 1;
    int tile = (iw - gap * (cols - 1)) / cols;
    while ((n + cols - 1) / cols * (tile + gap) - gap > gridH && cols < 6) {
        cols++;
        tile = (iw - gap * (cols - 1)) / cols;
    }
    uint32_t idle = pd_mix(pd_cur->tabIdleFill, PD_TITLE_FILL, 230); // 0.9
    for (int i = 0; i < n; i++) {
        int tx = ix + (i % cols) * (tile + gap), ty = iy + (i / cols) * (tile + gap);
        bool pressed = ui->pressedId == HIT_OPTION + i;
        uint32_t fill = pressed ? pd_cur->tabSelectedFill : idle;
        pd_layered_two_tone(c, tx, ty, tile, tile, 3, pd_cur->chip, pd_cur->chipCount, fill,
                            pd_mix(fill, pd_two_tone_low(fill), 128));
        const struct pd_art* art = ui_tab_art(more[i]);
        int iconScale = tile >= 52 ? 2 : 1;
        if (art) ui_art(c, art, tx + (tile - 16 * iconScale) / 2, ty + (tile - 16 - 16 * iconScale) / 2, iconScale);
        const char* label = ui_tab_label(more[i]);
        pd_text_fit(c, tx + 2 + (tile - 4 - pd_text_width(label) > 0 ? (tile - 4 - pd_text_width(label)) / 2 : 0), ty + tile - 17,
                    tile - 4, label, pressed ? PD_VALUE : PD_TITLE_TEXT, pressed ? PD_VALUE_SHADOW : PD_TITLE_SHADOW);
        ui_add_hit(ui, tx, ty, tile, tile, HIT_OPTION + i);
    }
    // The rule: the window's own inner frame, dark over light (Separator).
    int ry = y + h - f - bottomH;
    pd_separator(c, ix, ry, iw, false);
    int bw = 86, bh = 22, by = ry + 2 + gap + (40 - bh) / 2;
    pd_text_wrap(c, ix, ry + 2 + gap - 3, iw - bw - gap, 13, 3,
                 "Tabs that aren't on the tab bar are here. Pick the bar's tabs in TAB BAR.", PD_LABEL, PD_LABEL_SHADOW);
    int bx = ix + iw - bw;
    ui_button(ui, c, bx, by, bw, bh, "", HIT_MORE_TAB_BAR);
    int lw = PD_ICON_GEAR.w + 4 + pd_text_width("TAB BAR"), lx = bx + (bw - lw) / 2;
    ui_mono(c, &PD_ICON_GEAR, lx, by + (bh - PD_ICON_GEAR.h) / 2, 1, PD_TITLE_TEXT);
    pd_text(c, lx + PD_ICON_GEAR.w + 4, pd_text_y(by, bh), "TAB BAR", PD_TITLE_TEXT, PD_TITLE_SHADOW);
}

static void confirm(struct pd_ui* ui, struct pd_canvas* c, const char* title, const char* text, const char* yes) {
    int x = MARGIN, w = c->w - 2 * MARGIN;
    int y = CONTENT_Y + 8;
    title_window(c, y, title);
    y += TITLE_H + 4;
    int h = 3 * 16 + 2 * 7 + 6;
    int lf = pd_list_box(c, x, y, w, h);
    pd_text_wrap(c, x + lf + 6, y + lf + 2, w - 2 * (lf + 6), 16, 3, text, PD_LABEL, PD_LABEL_SHADOW);
    y += h + 6;
    int bw = 96;
    ui->startId = HIT_NO; // cancel first
    ui_button(ui, c, c->w / 2 - bw - 6, y, bw, 24, yes, HIT_YES);
    ui_button(ui, c, c->w / 2 + 6, y, bw, 24, "CANCEL", HIT_NO);
}

void ui_overlay(struct pd_ui* ui, struct pd_canvas* c) {
    if (ui->overlay == PD_OVERLAY_NONE) return;
    pd_dim(c, 0xE6);
    // Swallows taps beside the windows (they close the overlay).
    ui_add_hit(ui, 0, 0, c->w, c->h, HIT_SCRIM);
    static const char* const FF[PD_FF_CHOICES] = { "2x", "3x", "4x", "5x", "6x", "8x", "10x", "MAX" };
    switch (ui->overlay) {
    case PD_OVERLAY_PICK_SCREEN:
    {
        int skip = ui->pixelOff ? 1 : 0, mode = ui->settings->screenMode - skip;
        pick_list(ui, c, "SCREEN", SCREEN_NAMES + skip, PD_SCREEN_MODES - skip, mode < 0 ? 0 : mode);
        break;
    }
    case PD_OVERLAY_PICK_ASPECT:
        pick_list(ui, c, "ASPECT", ASPECT_NAMES, PD_ASPECTS, ui->settings->aspect);
        break;
    case PD_OVERLAY_PICK_FF:
        pick_list(ui, c, "FAST-FORWARD", FF, PD_FF_CHOICES, pd_ff_index(ui->settings->ffSpeed));
        break;
    case PD_OVERLAY_PICK_SPEED:
        pick_list(ui, c, "GAME SPEED", PD_GAME_SPEEDS, PD_GAME_SPEED_COUNT, ui->settings->gameSpeed);
        break;
    case PD_OVERLAY_PICK_BOX:
        ui_boxes_overlay(ui, c);
        break;
    case PD_OVERLAY_PICK_THEME: {
        // Limited-time themes are offered only around their holidays on the app: not here yet.
        static const char* names[16];
        int n = 0, current = 0;
        for (int i = 0; i < pd_theme_count && n < 16; i++) {
            if (pd_themes[i].seasonal) continue;
            if (pd_themes[i].id == ui->settings->theme) current = n;
            names[n++] = pd_themes[i].label;
        }
        pick_list(ui, c, "THEME", names, n, current);
        break;
    }
    case PD_OVERLAY_PICK_VOLUME:
        pick_list(ui, c, "GAME VOLUME", VOLUMES, 5, ui->settings->gameVolume / 20 - 1);
        break;
    case PD_OVERLAY_PICK_FLY_ON:
        pick_list(ui, c, "FLY ON", FLY_ON_NAMES, 4, ui->settings->flightVehicle);
        break;
    case PD_OVERLAY_TAB_BAR:
        tab_bar_page(ui, c);
        break;
    case PD_OVERLAY_HUD:
        hud_page(ui, c);
        break;
    case PD_OVERLAY_CONFIRM_SAVE:
        confirm(ui, c, "SAVE STATE",
                "Save a state of the game as it is now? It replaces the last one. The save file isn't touched.",
                "SAVE");
        break;
    case PD_OVERLAY_CONFIRM_LOAD:
        confirm(ui, c, "LOAD STATE",
                "Go back to the saved state? Play since then is lost; the save file isn't touched.", "LOAD");
        break;
    case PD_OVERLAY_CONFIRM_LEAVE:
        confirm(ui, c, "CLOSE GAME",
                "Back to the game list? What the game saved stays saved; anything since is lost.", "CLOSE");
        break;
    case PD_OVERLAY_CONFIRM_RESTART:
        confirm(ui, c, "RESTART GAME", "Start the game again from its save? Anything since the last save is lost.", "RESTART");
        break;
    default:
        break;
    }
}

void pd_ui_options_view(struct pd_ui* ui,struct pd_canvas* c,const struct pd_game* g,const struct pd_snapshot* s){
    ui->hitCount=0;
    if (!ui->keepBackdrop) pd_backdrop(c);
    ui_settings_tab(ui,c,g,s,NULL);
}

void pd_ui_choices_view(struct pd_canvas* c,const char* title,const char* const* choices,int count,int selected){
    if(count<1 || count>8)return;
    struct pd_ui ui={0};ui.pressedId=-1;
    pd_dim(c,230);
    pick_list(&ui,c,title,choices,count,selected);
}
