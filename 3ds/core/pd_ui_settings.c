// SETTINGS: what the companion knows about the game, then the options - and
// the black-out overlays they open: a pick-list for a multi-choice setting
// (the app's OptionSelector: never a row cycling through more than two
// values) and a YES / NO confirm (OptionConfirm).
#include <stdio.h>
#include <string.h>

#include "pd_ui_internal.h"

#define TITLE_H 24
#define LIST_Y (CONTENT_Y + TITLE_H + 4)

static const char* const SCREEN_NAMES[PD_SCREEN_MODES] = { "PIXEL 1x", "SHARP 1.5x", "STRETCH" };
static const char* const ASPECT_NAMES[PD_ASPECTS] = { "ORIGINAL", "EXPAND", "ZOOM OUT" };

const char* pd_aspect_name(int aspect) {
    return aspect >= 0 && aspect < PD_ASPECTS ? ASPECT_NAMES[aspect] : "";
}

const char* pd_screen_mode_name(int mode) {
    return mode >= 0 && mode < PD_SCREEN_MODES ? SCREEN_NAMES[mode] : "";
}

// The list's visible band (rows outside it are drawn clipped and take no taps) and its rows' height last time
// (the scroll is clamped to it before drawing, so a D-pad's step past the end never draws an empty list).
static int listTop, listBottom, lastTotal = -1;

static void setting(struct pd_ui* ui, struct pd_canvas* c, int x, int* y, int w, enum setting_row row,
                    const char* label, const char* value) {
    ui_row(c, x, *y, w, label, value, ui->pressedId == HIT_SETTING + (int) row);
    if (*y >= listTop && *y + ROW_H <= listBottom) ui_add_hit(ui, x - 4, *y, w + 8, ROW_H, HIT_SETTING + (int) row);
    *y += ROW_H;
}

// A row shown, not tappable (LOAD STATE with none yet, ASPECT N/A).
static void muted_row(struct pd_canvas* c, int x, int* y, int w, const char* label, const char* value) {
    pd_text(c, x, pd_text_y(*y, ROW_H), label, PD_MUTED, PD_MUTED_SHADOW);
    pd_text(c, x + w * 42 / 100, pd_text_y(*y, ROW_H), value, PD_MUTED, PD_MUTED_SHADOW);
    *y += ROW_H;
}

void ui_settings_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                     const struct pd_host_info* host) {
    int x = MARGIN, w = c->w - 2 * MARGIN;
    int f = pd_title_box(c, x, CONTENT_Y, w, TITLE_H, PD_TITLE_FILL);
    int ty = pd_text_y(CONTENT_Y, TITLE_H);
    pd_text(c, x + f + 6, ty, "SETTINGS", PD_TITLE_TEXT, PD_TITLE_SHADOW);
    int right = x + w - f - 6;
    // The TRAINER CARD opens from here (the list below is full): a grey chip at
    // the title window's right, white while pressed, like the tab bar's.
    if (g->cfg && g->cfg->cardStyle && g->rom) {
        const char* label = "TRAINER CARD";
        int bw = pd_text_width(label) + 12, bh = TITLE_H - 6;
        int bx = x + w - f - 2 - bw, by = CONTENT_Y + 3;
        pd_title_box(c, bx, by, bw, bh, ui->pressedId == HIT_OPEN_CARD ? PD_TITLE_FILL : PD_LIST_FILL);
        pd_text(c, bx + 6, pd_text_y(by, bh), label, PD_TITLE_TEXT, PD_TITLE_SHADOW);
        ui_add_hit(ui, bx, by, bw, bh, HIT_OPEN_CARD);
        right = bx - 8;
    }
    if (g->title) {
        int tx = x + f + 6 + pd_text_width("SETTINGS") + 12;
        int tw = pd_text_width(g->title);
        if (tw > right - tx) tw = right - tx;
        pd_text_fit(c, right - tw, ty, tw, g->title, PD_TITLE_TEXT, PD_TITLE_SHADOW);
    }

    int lh = c->h - LIST_Y - MARGIN;
    int lf = pd_list_box(c, x, LIST_Y, w, lh);
    int ix = x + lf + 4, iw = w - 2 * (lf + 4);
    // The rows scroll where they don't all fit (a drag, or the D-pad past the last one shown).
    listTop = LIST_Y + lf;
    listBottom = LIST_Y + lh - lf;
    ui_add_scroll_hit(ui, x, listTop, w, listBottom - listTop, HIT_SETTINGS_LIST);
    pd_clip(c, x + lf, listTop, w - 2 * lf, listBottom - listTop);
    if (lastTotal >= 0) ui->settingsScroll = ui_clamp_scroll(ui->settingsScroll, lastTotal, listBottom - listTop);
    int y = listTop - ui->settingsScroll;
    char buf[40];
    if (s->valid) {
        const char* place = pd_mapsec_name(g, s->mapsec);
        ui_row(c, ix, y, iw, "PLACE", *place ? place : "-", false);
        y += ROW_H;
        ui_format_money(s->money, buf, sizeof(buf));
        ui_row(c, ix, y, iw, "MONEY", buf, false);
        y += ROW_H;
    }
    for (int i = 0; host && i < host->count && i < PD_UI_HOST_LINES; i++) {
        ui_row(c, ix, y, iw, host->labels[i], host->values[i], false);
        y += ROW_H;
    }
    pd_fill(c, ix, y + 1, iw, 1, PD_DIVIDER);
    y += 4;

    if (!host || !host->hideScreenMode)
        setting(ui, c, ix, &y, iw, SETTING_SCREEN, "SCREEN", pd_screen_mode_name(ui->settings->screenMode));
    if (ui->settings->ffSpeed)
        snprintf(buf, sizeof(buf), "%dx", ui->settings->ffSpeed);
    else
        snprintf(buf, sizeof(buf), "MAX");
    setting(ui, c, ix, &y, iw, SETTING_FF, "FAST-FORWARD", buf);
    if (host && host->hasExpand) {
        if (host->expandGame) {
            setting(ui, c, ix, &y, iw, SETTING_ASPECT, "ASPECT", pd_aspect_name(ui->settings->aspect));
            // Two values: flips in place (the app's rule for pick-lists is more than two). Only
            // ZOOM OUT has anything to step back from.
            if (ui->settings->aspect == PD_ASPECT_ZOOM_OUT)
                setting(ui, c, ix, &y, iw, SETTING_AUTO_ZOOM, "AUTO ZOOM IN", ui->settings->autoZoom ? "ON" : "OFF");
        } else {
            // This game's overworld can't be rebuilt: shown, not tappable.
            muted_row(c, ix, &y, iw, "ASPECT", "N/A");
        }
    }
    if (host && host->hasHud) setting(ui, c, ix, &y, iw, SETTING_HUD, "HUD", ui->settings->hud ? "ON" : "OFF");
    if (host && host->hasDisplayFilter)
        setting(ui, c, ix, &y, iw, SETTING_FILTER, "FILTER", ui->settings->lcdFilter ? "LCD" : "OFF");
    if (host && host->hasQuickMenu)
        setting(ui, c, ix, &y, iw, SETTING_QUICK_MENU, "QUICK MENU", ui->settings->quickGrid ? "GRID" : "RING");
    snprintf(buf, sizeof(buf), "%d TABS", ui_bar_count(ui));
    setting(ui, c, ix, &y, iw, SETTING_TAB_BAR, "TAB BAR", buf);
    setting(ui, c, ix, &y, iw, SETTING_SAVE_STATE, "SAVE STATE", NULL);
    if (host && host->hasState) {
        setting(ui, c, ix, &y, iw, SETTING_LOAD_STATE, "LOAD STATE", host->stateWhen);
    } else {
        // Nothing to load: shown, not tappable.
        muted_row(c, ix, &y, iw, "LOAD STATE", "none yet");
    }
    setting(ui, c, ix, &y, iw, SETTING_LEAVE, "LEAVE GAME", NULL);
    pd_unclip(c);
    int total = y + ui->settingsScroll - listTop, view = listBottom - listTop;
    lastTotal = total;
    ui->settingsScroll = ui_clamp_scroll(ui->settingsScroll, total, view);
    if (total > view) ui_scroll_bar(c, x + w - lf - 3, listTop, view, ui->settingsScroll, total, PD_DIVIDER);
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
    for (int i = 0; i < UI_BAR_CHOICES; i++) {
        labels[i] = ui_tab_label(UI_BAR_CHOICE[i]);
        on[i] = (ui->settings->tabBar >> UI_BAR_CHOICE[i]) & 1;
    }
    toggle_page(ui, c, "TAB BAR", labels, on, NULL, UI_BAR_CHOICES, "At most 5 on the bar; the rest open from MORE.", NULL);
}

// FieldHud.kt's fieldHudRows: the switch, LAYOUT / LOOK / SIZE (a press cycles them), then which parts show.
static void hud_page(struct pd_ui* ui, struct pd_canvas* c) {
    enum { N = 4 + PD_HUD_PARTS };
    const struct pd_settings* s = ui->settings;
    const char* labels[N] = { "HUD", "LAYOUT", "LOOK", "SIZE" };
    const char* values[N] = { NULL, pd_hud_layout_name(s->hudLayout), pd_hud_look_name(s->hudLook),
                              pd_hud_size_name(s->hudSize) };
    bool on[N] = { s->hud, true, true, true }, muted[N] = { false, !s->hud, !s->hud, !s->hud };
    for (int i = 0; i < PD_HUD_PARTS; i++) {
        labels[4 + i] = pd_hud_part_name(i);
        on[4 + i] = !((s->hudHidden >> i) & 1);
        muted[4 + i] = !s->hud;
    }
    toggle_page(ui, c, "HUD", labels, on, muted, N, "Shown over the overworld, off in menus and battles.", values);
}

static void more_page(struct pd_ui* ui, struct pd_canvas* c) {
    enum pd_tab more[UI_BAR_CHOICES];
    const char* labels[UI_BAR_CHOICES];
    int n = ui_more_tabs(ui, more);
    for (int i = 0; i < n; i++) labels[i] = ui_tab_label(more[i]);
    toggle_page(ui, c, "MORE", labels, NULL, NULL, n, "Which tabs are on the bar: SETTINGS > TAB BAR.", NULL);
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
        pick_list(ui, c, "SCREEN", SCREEN_NAMES, PD_SCREEN_MODES, ui->settings->screenMode);
        break;
    case PD_OVERLAY_PICK_ASPECT:
        pick_list(ui, c, "ASPECT", ASPECT_NAMES, PD_ASPECTS, ui->settings->aspect);
        break;
    case PD_OVERLAY_PICK_FF:
        pick_list(ui, c, "FAST-FORWARD", FF, PD_FF_CHOICES, pd_ff_index(ui->settings->ffSpeed));
        break;
    case PD_OVERLAY_TAB_BAR:
        tab_bar_page(ui, c);
        break;
    case PD_OVERLAY_HUD:
        hud_page(ui, c);
        break;
    case PD_OVERLAY_MORE:
        more_page(ui, c);
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
        confirm(ui, c, "LEAVE GAME",
                "Back to the game list? What the game saved stays saved; anything since is lost.", "LEAVE");
        break;
    default:
        break;
    }
}
