// The companion's frame: the tab bar, the open tab, overlays, and the stylus.
// Each tab draws itself in its own file (pd_ui_party.c, _battle, _bag,
// _settings); pd_ui_internal.h has what they share.
#include <stdlib.h>
#include <string.h>

#include "pd_ui_internal.h"

static void cycle_button(struct pd_settings* s, int row);

#define CHIP_H 22
#define FF_W 28
#define GEAR_W 24
// How far the stylus moves on a list before it scrolls instead of tapping.
#define DRAG_SLOP 6

// The tab bar's chips: the ones TAB BAR keeps on it (at most PD_MAX_BAR_TABS, in this order), MORE when one of
// PARTY .. DEX is off it (CARD also opens from SETTINGS), fast-forward, then the SETTINGS gear. A battle puts BATTLE
// in the last chip's slot (the app's rule) and gives it back afterwards.
const enum pd_tab UI_BAR_CHOICE[UI_BAR_CHOICES] = { PD_TAB_PARTY, PD_TAB_BAG, PD_TAB_MAP, PD_TAB_GUIDE, PD_TAB_DEX, PD_TAB_CARD };
static const char* const TAB_LABELS[PD_TAB_COUNT] = {
    [PD_TAB_PARTY] = "PARTY", [PD_TAB_BAG] = "BAG", [PD_TAB_MAP] = "MAP", [PD_TAB_GUIDE] = "GUIDE",
    [PD_TAB_DEX] = "DEX", [PD_TAB_BATTLE] = "BATTLE", [PD_TAB_SETTINGS] = "", [PD_TAB_CARD] = "CARD",
};

const char* ui_tab_label(enum pd_tab t) { return t < PD_TAB_COUNT ? TAB_LABELS[t] : ""; }

// The bar's tabs from the settings (0, which TAB BAR never leaves, = the default bar: settings zeroed by a host).
static int bar_tabs(const struct pd_ui* ui, enum pd_tab out[PD_MAX_BAR_TABS]) {
    int n = 0, bits = ui->settings && ui->settings->tabBar ? ui->settings->tabBar : PD_TAB_BAR_DEFAULT;
    for (int i = 0; i < UI_BAR_CHOICES && n < PD_MAX_BAR_TABS; i++)
        if ((bits & (1 << UI_BAR_CHOICE[i])) && (!ui->availableTabs || (ui->availableTabs & (1u << UI_BAR_CHOICE[i])))) out[n++] = UI_BAR_CHOICE[i];
    if (!n) out[n++] = PD_TAB_PARTY;
    return n;
}

// Whether MORE is on the bar: a tab other than CARD left off it.
static bool has_more(const struct pd_ui* ui) {
    enum pd_tab bar[PD_MAX_BAR_TABS];
    int n = bar_tabs(ui, bar);
    for (int i = 0; i < UI_BAR_CHOICES - 1; i++) {
        if (ui->availableTabs && !(ui->availableTabs & (1u << UI_BAR_CHOICE[i]))) continue;
        bool on = false;
        for (int k = 0; k < n; k++) on |= bar[k] == UI_BAR_CHOICE[i];
        if (!on) return true;
    }
    return false;
}

int ui_bar_count(const struct pd_ui* ui) {
    enum pd_tab bar[PD_MAX_BAR_TABS];
    return bar_tabs(ui, bar);
}

int ui_more_tabs(const struct pd_ui* ui, enum pd_tab out[UI_BAR_CHOICES]) {
    enum pd_tab bar[PD_MAX_BAR_TABS];
    int n = bar_tabs(ui, bar), m = 0;
    for (int i = 0; i < UI_BAR_CHOICES; i++) {
        if (ui->availableTabs && !(ui->availableTabs & (1u << UI_BAR_CHOICE[i]))) continue;
        bool on = false;
        for (int k = 0; k < n; k++) on |= bar[k] == UI_BAR_CHOICE[i];
        if (!on) out[m++] = UI_BAR_CHOICE[i];
    }
    return m;
}

const char* pd_hud_part_name(int part) {
    static const char* const NAMES[PD_HUD_PARTS] = { "PARTY", "MINIMAP", "POKé BALLS", "REPEL STEPS", "PC", "BAG",
                                                     "WILD POKéMON" };
    return part >= 0 && part < PD_HUD_PARTS ? NAMES[part] : "";
}

const char* pd_hud_layout_name(int layout) {
    static const char* const NAMES[PD_HUD_LAYOUTS] = { "CORNERS", "CORNERS 2", "RAILS", "DOCK" };
    return layout >= 0 && layout < PD_HUD_LAYOUTS ? NAMES[layout] : "";
}

const char* pd_hud_look_name(int look) {
    static const char* const NAMES[PD_HUD_LOOKS] = { "GLASS", "WHITE GLASS", "WINDOW" };
    return look >= 0 && look < PD_HUD_LOOKS ? NAMES[look] : "";
}

const char* pd_hud_size_name(int size) {
    static const char* const NAMES[PD_HUD_SIZES] = { "SMALL", "NORMAL", "LARGE" };
    return size >= 0 && size < PD_HUD_SIZES ? NAMES[size] : "";
}

void ui_toggle_act(struct pd_ui* ui, int i) {
    struct pd_settings* s = ui->settings;
    if (ui->overlay == PD_OVERLAY_MORE) {
        enum pd_tab more[UI_BAR_CHOICES];
        if (i < ui_more_tabs(ui, more)) {
            ui->tab = more[i];
            ui->summarySlot = -1;
            ui->moreOpened = true;
        }
        ui->overlay = PD_OVERLAY_NONE;
        return;
    }
    if (ui->overlay == PD_OVERLAY_HUD) {
        // HUD, then LAYOUT / LOOK / SIZE (each cycles on a press, FieldHud.kt's fieldHudRows), then the parts.
        if (i == 0) s->hud = !s->hud;
        else if (!s->hud) return;
        else if (i == 1) s->hudLayout = (s->hudLayout + 1) % PD_HUD_LAYOUTS;
        else if (i == 2) s->hudLook = (s->hudLook + 1) % PD_HUD_LOOKS;
        else if (i == 3) s->hudSize = (s->hudSize + 1) % PD_HUD_SIZES;
        else if (i - 4 < PD_HUD_PARTS) s->hudHidden ^= 1 << (i - 4);
        ui->action = PD_ACTION_SETTINGS_CHANGED;
        return;
    }
    // TAB BAR: never more than PD_MAX_BAR_TABS on, never none.
    if (i >= UI_BAR_CHOICES) return;
    int bit = 1 << UI_BAR_CHOICE[i];
    int on = 0;
    for (int k = 0; k < UI_BAR_CHOICES; k++) on += (s->tabBar >> UI_BAR_CHOICE[k]) & 1;
    if (s->tabBar & bit) {
        if (on <= 1) return;
    } else if (on >= PD_MAX_BAR_TABS) {
        return;
    }
    s->tabBar ^= bit;
    // A tab taken off the bar while it's open stays open (from MORE now).
    if (!(s->tabBar & bit) && ui->tab == UI_BAR_CHOICE[i]) ui->moreOpened = true;
    ui->action = PD_ACTION_SETTINGS_CHANGED;
}

// The tab a bar slot shows now.
static enum pd_tab slot_tab(const struct pd_ui* ui, int slot) {
    enum pd_tab bar[PD_MAX_BAR_TABS];
    int n = bar_tabs(ui, bar);
    return slot == ui->battleSlot ? PD_TAB_BATTLE : bar[slot < n ? slot : n - 1];
}

// Whether a tab is one of the bar's chips (else MORE or SETTINGS opened it).
static bool on_bar(const struct pd_ui* ui, enum pd_tab t) {
    enum pd_tab bar[PD_MAX_BAR_TABS];
    int n = bar_tabs(ui, bar);
    for (int i = 0; i < n; i++)
        if (slot_tab(ui, i) == t) return true;
    return false;
}

// The SETTINGS gear, 11x11 (bit 0 = left):
//   ....###....   .##.###.##.   .#########.   ..###.###..
//   ####...####   ###.....###   ####...####   ...and back up.
static const uint16_t GEAR[11] = {
    0x070, 0x376, 0x3FE, 0x1DC, 0x78F, 0x707, 0x78F, 0x1DC, 0x3FE, 0x376, 0x070,
};

void pd_ui_init(struct pd_ui* ui, struct pd_settings* settings) {
    memset(ui, 0, sizeof(*ui));
    ui->tab = PD_TAB_PARTY;
    ui->summarySlot = -1;
    ui->bagSelected = -1;
    ui->pressedId = -1;
    ui->focusId = -1;
    ui->battleSlot = -1;
    ui->dexNational = -1;
    ui->mapPage = -1;
    ui->mapSel = -1;
    ui->mapDungeon = -1;
    ui->guideOpen = -1;
    ui->foeSelected = ui->foeShown = -1;
    ui->lastFoeActive = ui->lastFoeNext = -1;
    ui->settings = settings;
}

void pd_ui_update(struct pd_ui* ui, const struct pd_snapshot* s) {
    bool on = s->valid && s->inBattle && s->battlers[PD_POS_PLAYER_LEFT].species;
    if (on && !ui->battleWasOn) {
        // BATTLE takes the bar's last tab's place (the app's rule - PARTY,
        // what a battle needs most, stays); its end brings back what was open.
        ui->tabBeforeBattle = ui->tab;
        enum pd_tab bar[PD_MAX_BAR_TABS];
        ui->battleSlot = bar_tabs(ui, bar) - 1;
        ui->tab = PD_TAB_BATTLE;
        ui->summarySlot = -1;
        ui->overlay = PD_OVERLAY_NONE;
        ui->battlePane = 0;
        ui->foeSeen = 0;
        ui->foeSelected = -1;
        ui->lastFoeActive = ui->lastFoeNext = -1;
    } else if (!on && ui->battleWasOn) {
        if (ui->tab == PD_TAB_BATTLE) ui->tab = ui->tabBeforeBattle;
        ui->battleSlot = -1;
    }
    ui->battleWasOn = on;
    // The FOE TEAM: what's been sent out or announced counts as seen; a new
    // pick by the trainer is shown, whatever was tapped (CompanionScreen).
    if (on && (s->foeActive != ui->lastFoeActive || s->foeNext != ui->lastFoeNext)) {
        if (s->foeActive >= 0) ui->foeSeen |= 1u << s->foeActive;
        if (s->foeNext >= 0) {
            ui->foeSeen |= 1u << s->foeNext;
            ui->foeSelected = -1;
        }
        ui->lastFoeActive = s->foeActive;
        ui->lastFoeNext = s->foeNext;
    }
    if (ui->foeSelected >= s->foeCount) ui->foeSelected = -1;
    if (ui->summarySlot >= s->partyCount) ui->summarySlot = -1;
}

static const struct pd_hit* hit_at(const struct pd_ui* ui, int x, int y) {
    for (int i = ui->hitCount - 1; i >= 0; i--) {
        const struct pd_hit* h = &ui->hits[i];
        if (x >= h->x && x < h->x + h->w && y >= h->y && y < h->y + h->h) return h;
    }
    return NULL;
}

// The tab bar runs along the bottom, like the app's (CompanionScreen).
#define BAR_Y(c) ((c)->h - CHIP_H - 2)

static void chip(struct pd_ui* ui, struct pd_canvas* c, int x, int w, bool open, int id) {
    pd_title_box(c, x, BAR_Y(c), w, CHIP_H, open || ui->pressedId == id ? PD_TITLE_FILL : PD_LIST_FILL);
    ui_add_hit(ui, x, BAR_Y(c), w, CHIP_H, id);
}

// MORE's icon: three dots, 11x3 (bit 0 = left).
static const uint16_t MORE_DOTS[3] = { 0x739, 0x739, 0x739 };

static void tab_bar(struct pd_ui* ui, struct pd_canvas* c, const struct pd_host_info* host) {
    int by = BAR_Y(c);
    enum pd_tab bar[PD_MAX_BAR_TABS];
    int n = bar_tabs(ui, bar);
    bool more = has_more(ui);
    int moreW = more ? GEAR_W + MARGIN : 0;
    int ffW = ui->content && host && host->frontendOwnsPlayback ? 0 : FF_W;
    // The chips share what's left, but never wider than five would be (a short bar keeps its chips' size).
    int w = (c->w - MARGIN * (n + 3) - ffW - GEAR_W - moreW) / n;
    int five = (c->w - MARGIN * (PD_MAX_BAR_TABS + 3) - FF_W - GEAR_W - GEAR_W - MARGIN) / PD_MAX_BAR_TABS;
    if (ffW && n < PD_MAX_BAR_TABS && w > five * 3 / 2) w = five * 3 / 2;
    int x = MARGIN;
    for (int i = 0; i < n; i++, x += w + MARGIN) {
        enum pd_tab t = slot_tab(ui, i);
        bool open = ui->tab == t;
        chip(ui, c, x, w, open, HIT_TAB + (int) t);
        int tw = pd_text_width(TAB_LABELS[t]);
        pd_text(c, x + (w - tw) / 2, pd_text_y(by, CHIP_H), TAB_LABELS[t], open ? PD_VALUE : PD_TITLE_TEXT,
                open ? PD_VALUE_SHADOW : PD_TITLE_SHADOW);
    }
    // MORE: an icon chip, lit while a tab it opens is up.
    if (more) {
        bool lit = ui->overlay == PD_OVERLAY_MORE ||
                   (!on_bar(ui, ui->tab) && ui->tab != PD_TAB_SETTINGS && ui->tab != PD_TAB_CARD && ui->tab != PD_TAB_BATTLE) ||
                   (ui->tab == PD_TAB_CARD && ui->moreOpened);
        chip(ui, c, x, GEAR_W, lit, HIT_MORE);
        pd_bitmap(c, x + (GEAR_W - 11) / 2, by + (CHIP_H - 3) / 2, MORE_DOTS, 3, lit ? PD_VALUE : PD_TITLE_TEXT);
        x += GEAR_W + MARGIN;
    }
    // Frontends own fast-forward in libretro hosts.
    if (ffW) {
        bool ff = host && host->ffOn;
        chip(ui, c, x, FF_W, ff, HIT_FF);
        uint32_t col = ff ? PD_VALUE : PD_TITLE_TEXT;
        pd_triangle(c, x + 7, by + CHIP_H / 2 - 4, 5, PD_RIGHT, col);
        pd_triangle(c, x + 13, by + CHIP_H / 2 - 4, 5, PD_RIGHT, col);
        x += FF_W + MARGIN;
    }
    // The TRAINER CARD opened from SETTINGS keeps the gear lit.
    bool open = ui->tab == PD_TAB_SETTINGS || (ui->tab == PD_TAB_CARD && !on_bar(ui, PD_TAB_CARD) && !ui->moreOpened);
    chip(ui, c, x, GEAR_W, open, HIT_TAB + PD_TAB_SETTINGS);
    pd_bitmap(c, x + (GEAR_W - 11) / 2, by + (CHIP_H - 11) / 2, GEAR, 11, open ? PD_VALUE : PD_TITLE_TEXT);
}

void pd_ui_draw(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g,
                const struct pd_snapshot* s, const struct pd_host_info* host) {
    ui->hitCount = 0;
    ui->startId = -1;
    pd_backdrop(c);
    tab_bar(ui, c, host);
    // The tabs draw above the bar: the same pixels, a shorter canvas.
    struct pd_canvas full = *c, view = *c;
    view.h = BAR_Y(c) - 2;
    if (view.cy1 > view.h) view.cy1 = view.h;
    c = &view;
    if (ui->content) {
        ui->content(ui->contentCtx, ui, c);
    } else if (ui->tab == PD_TAB_SETTINGS) {
        ui_settings_tab(ui, c, g, s, host);
    } else if (!g->cfg) {
        ui_notice(c, "NOT SUPPORTED", g->unsupported ? g->unsupported : "", "SETTINGS has what was detected.");
    } else if (ui->tab == PD_TAB_PARTY) {
        ui_party_tab(ui, c, g, s);
    } else if (ui->tab == PD_TAB_BAG) {
        ui_bag_tab(ui, c, g, s);
    } else if (ui->tab == PD_TAB_MAP) {
        ui_map_tab(ui, c, g, s);
    } else if (ui->tab == PD_TAB_GUIDE) {
        ui_guide_tab(ui, c, g, s);
    } else if (ui->tab == PD_TAB_DEX) {
        ui_dex_tab(ui, c, g, s);
    } else if (ui->tab == PD_TAB_CARD) {
        ui_card_tab(ui, c, g, s);
    } else {
        ui_battle_tab(ui, c, g, s);
    }
    // Overlays black out the whole companion, the bar too.
    c = &full;
    if (ui->overlay == PD_OVERLAY_PLACES) {
        ui_places_overlay(ui, c, g);
    } else if (ui->overlay == PD_OVERLAY_GUIDE_NOTICE) {
        ui_guide_notice(ui, c, g);
    } else {
        ui_overlay(ui, c);
    }
}

// The list a drag scrolls right now.
static int* scroll_target(struct pd_ui* ui) {
    if (ui->overlay == PD_OVERLAY_PLACES) return &ui->placesScroll;
    switch (ui->tab) {
    case PD_TAB_BAG: return &ui->bagScroll;
    case PD_TAB_DEX: return &ui->dexScroll;
    case PD_TAB_GUIDE: return &ui->guideScroll;
    case PD_TAB_SETTINGS: return &ui->settingsScroll;
    default: return NULL;
    }
}

bool pd_ui_tick(struct pd_ui* ui, unsigned ms) {
    // The MAP cursor swaps sizes every 20 GBA frames, like the game's.
    bool redraw = ui_card_tick(ui, ms);
    ui->blinkMs += ms;
    if (ui->blinkMs < 333) return redraw;
    ui->blinkMs %= 333;
    ui->mapBlink = !ui->mapBlink;
    return redraw || (ui->tab == PD_TAB_MAP && ui->overlay == PD_OVERLAY_NONE);
}

static void close_overlay(struct pd_ui* ui, enum pd_action action) {
    ui->overlay = PD_OVERLAY_NONE;
    if (action != PD_ACTION_NONE) ui->action = action;
}

static void act(struct pd_ui* ui, int id) {
    if (ui_map_act(ui, id) || ui_dex_act(ui, id) || ui_guide_act(ui, id) || ui_battle_act(ui, id) || ui_card_act(ui, id)) {
        return;
    }
    if (ui->overlay == PD_OVERLAY_TAB_BAR || ui->overlay == PD_OVERLAY_HUD || ui->overlay == PD_OVERLAY_MORE) {
        if (id >= HIT_OPTION && id < HIT_OPTION + 16) {
            ui_toggle_act(ui, id - HIT_OPTION);
        } else if (id == HIT_NO || id == HIT_SCRIM) {
            close_overlay(ui, PD_ACTION_NONE);
        }
        return;
    }
    if (ui->overlay != PD_OVERLAY_NONE) {
        if (id >= HIT_OPTION && id < HIT_OPTION + 16) {
            int i = id - HIT_OPTION;
            if (ui->overlay == PD_OVERLAY_PICK_SCREEN) ui->settings->screenMode = i;
            if (ui->overlay == PD_OVERLAY_PICK_FF && i < PD_FF_CHOICES) ui->settings->ffSpeed = PD_FF_SPEEDS[i];
            if (ui->overlay == PD_OVERLAY_PICK_ASPECT && i < PD_ASPECTS) ui->settings->aspect = i;
            close_overlay(ui, PD_ACTION_SETTINGS_CHANGED);
        } else if (id == HIT_YES) {
            enum pd_action a = ui->overlay == PD_OVERLAY_CONFIRM_SAVE ? PD_ACTION_SAVE_STATE
                : ui->overlay == PD_OVERLAY_CONFIRM_LOAD ? PD_ACTION_LOAD_STATE
                : ui->overlay == PD_OVERLAY_CONFIRM_LEAVE ? PD_ACTION_LEAVE_GAME : PD_ACTION_NONE;
            close_overlay(ui, a);
        } else if (id == HIT_NO || id == HIT_SCRIM) {
            close_overlay(ui, PD_ACTION_NONE);
        }
        return;
    }
    if (id >= HIT_TAB && id < HIT_TAB + PD_TAB_COUNT) {
        ui->tab = (enum pd_tab) (id - HIT_TAB);
        ui->summarySlot = -1;
        ui->moreOpened = false;
    } else if (id == HIT_MORE) {
        ui->overlay = PD_OVERLAY_MORE;
    } else if (id == HIT_SETTING + SETTING_TAB_BAR) {
        ui->overlay = PD_OVERLAY_TAB_BAR;
    } else if (id == HIT_SETTING + SETTING_HUD) {
        ui->overlay = PD_OVERLAY_HUD;
    } else if (id == HIT_FF) {
        ui->action = PD_ACTION_TOGGLE_FF;
    } else if (id >= HIT_SLOT && id < HIT_SLOT + PD_PARTY_SIZE) {
        ui->summarySlot = id - HIT_SLOT;
        ui->summaryStats = ui->summaryInfo = false;
    } else if (id == HIT_SUMMARY_STATS) {
        ui->summaryStats = !ui->summaryStats;
        ui->summaryInfo = false;
    } else if (id == HIT_SUMMARY_INFO) {
        ui->summaryInfo = !ui->summaryInfo;
        ui->summaryStats = false;
    } else if (id == HIT_BACK) {
        pd_ui_back(ui);
    } else if (id >= HIT_BAG_TAB && id < HIT_BAG_TAB + 8) {
        ui->bagPocket = id - HIT_BAG_TAB;
        ui->bagSelected = -1;
        ui->bagScroll = 0;
    } else if (ui->tab == PD_TAB_BAG && id >= HIT_BAG_ROW && id < HIT_BAG_ROW + PD_POCKET_SLOTS) {
        ui->bagSelected = id - HIT_BAG_ROW;
    } else if (id == HIT_SETTING + SETTING_FILTER) {
        ui->settings->lcdFilter = !ui->settings->lcdFilter;
        ui->action = PD_ACTION_SETTINGS_CHANGED;
    } else if (id == HIT_SETTING + SETTING_QUICK_MENU) {
        ui->settings->quickGrid = !ui->settings->quickGrid;
        ui->action = PD_ACTION_SETTINGS_CHANGED;
    } else if (id >= HIT_SETTING + SETTING_QUICK_BTN && id <= HIT_SETTING + SETTING_HUD_BTN) {
        cycle_button(ui->settings, id - HIT_SETTING);
        ui->action = PD_ACTION_SETTINGS_CHANGED;
    } else if (id == HIT_SETTING + SETTING_AUTO_ZOOM) {
        ui->settings->autoZoom = !ui->settings->autoZoom;
        ui->action = PD_ACTION_SETTINGS_CHANGED;
    } else if (id >= HIT_SETTING && id <= HIT_SETTING + SETTING_ASPECT) {
        static const enum pd_overlay OPENS[] = {
            [SETTING_SCREEN] = PD_OVERLAY_PICK_SCREEN,
            [SETTING_FF] = PD_OVERLAY_PICK_FF,
            [SETTING_SAVE_STATE] = PD_OVERLAY_CONFIRM_SAVE,
            [SETTING_LOAD_STATE] = PD_OVERLAY_CONFIRM_LOAD,
            [SETTING_LEAVE] = PD_OVERLAY_CONFIRM_LEAVE,
            [SETTING_ASPECT] = PD_OVERLAY_PICK_ASPECT,
        };
        ui->overlay = OPENS[id - HIT_SETTING];
    }
}

/* A BUTTON row: the next button (X, Y, L2, R2; the HUD's also NONE). A button another action already uses goes to
 * this one's old button, so no two actions ever share one; the HUD's NONE can't be handed to the other two. */
static void cycle_button(struct pd_settings* s, int row) {
    int* mine = row == SETTING_QUICK_BTN ? &s->quickBtn : row == SETTING_COMPANION_BTN ? &s->companionBtn : &s->hudBtn;
    int* all[3] = {&s->quickBtn, &s->companionBtn, &s->hudBtn};
    int old = *mine;
    for (int step = 1; step <= PD_BUTTONS; step++) {
        int next = (old + step) % PD_BUTTONS;
        if (next == PD_BTN_NONE && mine != &s->hudBtn) continue;
        int* other = NULL;
        for (int i = 0; i < 3; i++)
            if (all[i] != mine && *all[i] == next && next != PD_BTN_NONE) other = all[i];
        if (other && old == PD_BTN_NONE) continue;   // nothing to hand over
        if (other) *other = old;
        *mine = next;
        return;
    }
}

bool pd_ui_touch(struct pd_ui* ui, int x, int y, bool down) {
    if (down) {
        const struct pd_hit* h = hit_at(ui, x, y);
        int id = h ? h->id : -1;
        if (!ui->touching) {
            // A press shows on the element it starts on ...
            ui->touching = true;
            ui->dragging = false;
            ui->touchScroll = h && h->scroll;
            ui->touchX0 = x;
            ui->touchY0 = y;
            ui->touchLastY = y;
            ui->pressedId = id;
            return id != -1;
        }
        // ... a list scrolls under a drag ...
        if (ui->touchScroll && !ui->dragging && abs(y - ui->touchY0) > DRAG_SLOP) {
            ui->dragging = true;
            ui->pressedId = -1;
        }
        if (ui->dragging) {
            int* scroll = scroll_target(ui);
            if (scroll) *scroll -= y - ui->touchLastY;
            ui->touchLastY = y;
            return true;
        }
        ui->touchLastY = y;
        // ... and sliding off an element cancels it; another is never picked up.
        if (ui->pressedId != -1 && id != ui->pressedId) {
            ui->pressedId = -1;
            return true;
        }
        return false;
    }
    if (!ui->touching) return false;
    ui->touching = false;
    if (ui->dragging) {
        ui->dragging = false;
        return true;
    }
    int id = ui->pressedId;
    ui->pressedId = -1;
    if (id == -1) return false;
    act(ui, id);
    return true;
}

void pd_ui_next_tab(struct pd_ui* ui, int dir) {
    if (ui->overlay != PD_OVERLAY_NONE) return;
    // The bar's order, then the gear (a tab MORE opened steps on from the gear).
    enum pd_tab bar[PD_MAX_BAR_TABS], order[PD_MAX_BAR_TABS + 1];
    int n = bar_tabs(ui, bar), at = n;
    for (int i = 0; i < n; i++) {
        order[i] = slot_tab(ui, i);
        if (order[i] == ui->tab) at = i;
    }
    order[n] = PD_TAB_SETTINGS;
    ui->tab = order[(at + n + 1 + dir) % (n + 1)];
    ui->summarySlot = -1;
    ui->moreOpened = false;
}

bool pd_ui_back(struct pd_ui* ui) {
    // The GUIDE's notice: BACK is its GO BACK.
    if (ui->overlay == PD_OVERLAY_GUIDE_NOTICE) return ui_guide_act(ui, HIT_NOTICE_BACK);
    if (ui->overlay != PD_OVERLAY_NONE) {
        ui->overlay = PD_OVERLAY_NONE;
        return true;
    }
    if (ui->summarySlot >= 0) {
        ui->summarySlot = -1;
        return true;
    }
    if (ui->tab == PD_TAB_BAG && ui->bagSelected >= 0) {
        ui->bagSelected = -1;
        return true;
    }
    if (ui->tab == PD_TAB_DEX && ui->dexOpen) {
        ui->dexOpen = 0;
        return true;
    }
    if (ui->tab == PD_TAB_MAP && (ui->mapSel >= 0 || ui->mapDungeon >= 0 || ui->mapPage >= 0)) {
        ui->mapSel = ui->mapDungeon = ui->mapPage = -1;
        return true;
    }
    if (ui->tab == PD_TAB_CARD && !on_bar(ui, PD_TAB_CARD) && !ui->moreOpened) {
        ui->tab = PD_TAB_SETTINGS;
        return true;
    }
    if (ui->tab == PD_TAB_BATTLE && ui->battlePane) {
        ui->battlePane = 0;
        return true;
    }
    if (ui->tab == PD_TAB_BATTLE && ui->foeSelected >= 0) {
        ui->foeSelected = -1;
        return true;
    }
    if (ui->tab == PD_TAB_GUIDE && ui->guideOpen >= 0) {
        ui->guideOpen = -1;
        ui->guideLevel = 0;
        return true;
    }
    return false;
}

enum pd_action pd_ui_take_action(struct pd_ui* ui) {
    enum pd_action a = ui->action;
    ui->action = PD_ACTION_NONE;
    return a;
}
