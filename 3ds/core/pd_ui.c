// The companion's frame: the tab bar, the open tab, overlays, and the stylus.
// Each tab draws itself in its own file (pd_ui_party.c, _battle, _bag,
// _settings); pd_ui_internal.h has what they share.
#include "pd_font.h"
#include <stdlib.h>
#include <string.h>

#include "pd_dex.h"
#include "pd_guide.h"
#include "pd_party_art.h"
#include "pd_ui_internal.h"

static void cycle_button(struct pd_settings* s, int row);

#define CHIP_H 28
#define FF_W 28
#define ICON_CHIP_W 30
#define CHIP_GAP 2
#define SHOULDER_W 16
// How far the stylus moves on a list before it scrolls instead of tapping.
#define DRAG_SLOP 6

// The tab bar (CompanionScreen): BATTLE first while a battle's on, the chosen tabs this game has, MORE when any are
// left off it, and the gear at the end. Which tabs may sit on it, in the bar's order.
const enum pd_tab UI_BAR_CHOICE[UI_BAR_CHOICES] = {
    PD_TAB_PARTY, PD_TAB_DEX, PD_TAB_MAP, PD_TAB_BAG, PD_TAB_BOXES, PD_TAB_GUIDE, PD_TAB_NUZLOCKE, PD_TAB_CARD,
    PD_TAB_STATES, PD_TAB_ACHIEVEMENTS,
};
static const char* const TAB_LABELS[PD_TAB_COUNT] = {
    [PD_TAB_PARTY] = "PARTY", [PD_TAB_BAG] = "BAG", [PD_TAB_MAP] = "MAP", [PD_TAB_GUIDE] = "GUIDE",
    [PD_TAB_DEX] = "DEX", [PD_TAB_BATTLE] = "BATTLE", [PD_TAB_SETTINGS] = "", [PD_TAB_CARD] = "CARD",
    [PD_TAB_STATES] = "STATES", [PD_TAB_BOXES] = "BOXES", [PD_TAB_NUZLOCKE] = "NUZLOCKE",
    [PD_TAB_ACHIEVEMENTS] = "CHEEVOS",
};

const char* ui_tab_label(enum pd_tab t) { return t < PD_TAB_COUNT ? TAB_LABELS[t] : ""; }

const struct pd_art* ui_tab_art(enum pd_tab t) {
    for (int i = 0; i < UI_BAR_CHOICES; i++)
        if (UI_BAR_CHOICE[i] == t) return &PD_TAB_ART[i];
    return NULL;
}

bool ui_tab_available(const struct pd_ui* ui, const struct pd_game* g, enum pd_tab t) {
    // A backend that draws its own tabs (ui->content) says which it has; 0 = every tab this game has.
    if (ui->availableTabs && !(ui->availableTabs & (1u << t))) return false;
    if (ui->content) return true;
    const struct pd_config* cfg = g ? g->cfg : NULL;
    switch (t) {
    case PD_TAB_DEX: return cfg && (cfg->dexEntries || pd_dex_gb_count(g));
    case PD_TAB_GUIDE: return !g || pd_guide_for(g) != NULL;
    case PD_TAB_CARD: return cfg && cfg->cardStyle;
    case PD_TAB_STATES: return ui->hostHasStates;
    case PD_TAB_BOXES: return ui->boxes != NULL;
    case PD_TAB_NUZLOCKE: case PD_TAB_ACHIEVEMENTS: return false; // not ported yet
    default: return true;
    }
}

// The chips of the chosen tabs this game has, then - while there's room - its others, in the tabs' own order. Without
// a game known yet (g NULL) the choice as it stands.

static int bar_tabs(const struct pd_ui* ui, const struct pd_game* g, enum pd_tab out[PD_MAX_BAR_TABS]) {
    int bits = ui->settings && ui->settings->tabBar ? ui->settings->tabBar : PD_TAB_BAR_DEFAULT;
    int room = ui->battleOnBar && !ui->shoulderTabs ? PD_MAX_BAR_TABS - 1 : PD_MAX_BAR_TABS;
    bool picked[UI_BAR_CHOICES] = { false };
    int n = 0;
    for (int i = 0; i < UI_BAR_CHOICES && n < room; i++) {
        if ((bits >> UI_BAR_CHOICE[i] & 1) && ui_tab_available(ui, g, UI_BAR_CHOICE[i])) {
            picked[i] = true;
            n++;
        }
    }
    for (int i = 0; i < UI_BAR_CHOICES && n < room; i++) {
        if (!ui->content && (!ui->shoulderTabs || !n) && !picked[i] && ui_tab_available(ui, g, UI_BAR_CHOICE[i]) && UI_BAR_CHOICE[i] != PD_TAB_NUZLOCKE) {
            picked[i] = true;
            n++;
        }
    }
    int m = 0;
    for (int i = 0; i < UI_BAR_CHOICES; i++)
        if (picked[i]) out[m++] = UI_BAR_CHOICE[i];
    return m;
}

int ui_bar_count(const struct pd_ui* ui) {
    int bits = ui->settings && ui->settings->tabBar ? ui->settings->tabBar : PD_TAB_BAR_DEFAULT, n = 0;
    for (int i = 0; i < UI_BAR_CHOICES; i++) n += bits >> UI_BAR_CHOICE[i] & 1;
    return n;
}

int ui_configurable_tabs(const struct pd_ui* ui, enum pd_tab out[UI_BAR_CHOICES]) {
    int n = 0;
    for (int i = 0; i < UI_BAR_CHOICES; i++) {
        const enum pd_tab tab = UI_BAR_CHOICE[i];
        if (!ui->availableTabs || (ui->availableTabs & (1u << tab))) out[n++] = tab;
    }
    return n;
}

int ui_more_tabs(const struct pd_ui* ui, const struct pd_game* g, enum pd_tab out[UI_BAR_CHOICES]) {
    enum pd_tab bar[PD_MAX_BAR_TABS];
    int n = bar_tabs(ui, g, bar), m = 0;
    for (int i = 0; i < UI_BAR_CHOICES; i++) {
        bool on = false;
        for (int k = 0; k < n; k++) on |= bar[k] == UI_BAR_CHOICE[i];
        if (!on && ui_tab_available(ui, g, UI_BAR_CHOICE[i])) out[m++] = UI_BAR_CHOICE[i];
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
        if (i < ui_more_tabs(ui, ui->drawnGame, more)) {
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
        else if (i == 1) {
            if (ui->hudLayoutFixed) return;
            s->hudLayout = (s->hudLayout + 1) % PD_HUD_LAYOUTS;
        }
        else if (i == 2) s->hudLook = (s->hudLook + 1) % PD_HUD_LOOKS;
        else if (i == 3) s->hudSize = (s->hudSize + 1) % PD_HUD_SIZES;
        else if (i - 4 < PD_HUD_PARTS) s->hudHidden ^= 1 << (i - 4);
        ui->action = PD_ACTION_SETTINGS_CHANGED;
        return;
    }
    // TAB BAR: never more than PD_MAX_BAR_TABS on, never none.
    enum pd_tab tabs[UI_BAR_CHOICES];
    const int n = ui_configurable_tabs(ui, tabs);
    if (i < 0 || i >= n) return;
    int bit = 1 << tabs[i];
    int on = 0;
    for (int k = 0; k < n; k++) on += (s->tabBar >> tabs[k]) & 1;
    if (s->tabBar & bit) {
        if (on <= 1) return;
    } else if (on >= PD_MAX_BAR_TABS) {
        return;
    }
    s->tabBar ^= bit;
    // A tab taken off the bar while it's open stays open (from MORE now).
    if (!(s->tabBar & bit) && ui->tab == tabs[i]) ui->moreOpened = true;
    ui->action = PD_ACTION_SETTINGS_CHANGED;
}

void pd_ui_init(struct pd_ui* ui, struct pd_settings* settings) {
    memset(ui, 0, sizeof(*ui));
    ui->settingsNavId = -1;
    ui->tab = PD_TAB_PARTY;
    ui->summarySlot = -1;
    ui->bagSelected = -1;
    ui->pressedId = -1;
    ui->focusId = -1;
    ui->dexNational = -1;
    ui->mapPage = -1;
    ui->mapSel = -1;
    ui->mapDungeon = -1;
    pd_ui_guide_reset(ui);
    ui->foeSelected = ui->foeShown = -1;
    ui->itemMoveSlot = -1;
    ui->lastFoeActive = ui->lastFoeNext = -1;
    ui->settings = settings;
}

void pd_ui_update(struct pd_ui* ui, const struct pd_snapshot* s) {
    bool on = s->valid && s->inBattle && s->battlers[PD_POS_PLAYER_LEFT].species;
    if (on && !ui->battleWasOn) {
        // BATTLE leads the bar and the last chosen tab makes room; its end brings back what was open.
        ui->tabBeforeBattle = ui->tab;
        ui->battleOnBar = true;
        ui->tab = PD_TAB_BATTLE;
        ui->summarySlot = -1;
        ui->overlay = PD_OVERLAY_NONE;
        ui->battlePane = 0;
        // BATTLE VIEW: INFO first, or the buttons (always the buttons with BATTLE INFO off).
        ui->battleInfoOpen = ui->settings && ui->settings->battleInfoFirst && ui_battle_info_on(ui);
        ui->ranAway = ui->ranWaited = false;
        ui->foeSeen = 0;
        ui->foeSelected = -1;
        ui->lastFoeActive = ui->lastFoeNext = -1;
    } else if (!on && ui->battleWasOn) {
        if (ui->tab == PD_TAB_BATTLE) ui->tab = ui->tabBeforeBattle;
        ui->battleOnBar = false;
    }
    ui->battleWasOn = on;
    // RUN's hold ends with the battle, or when the menu is back after the turn (the escape failed).
    bool myTurn = s->battleState >= PD_BATTLE_ACTION;
    if (!on || !ui->ranAway) ui->ranAway = ui->ranWaited = false;
    else if (!myTurn) ui->ranWaited = true;
    else if (ui->ranWaited) ui->ranAway = ui->ranWaited = false;
    // Our move grid and party pane sit on the action menu.
    if (s->battleState != PD_BATTLE_ACTION) ui->battleChoosingMon = ui->battleChoosingMove = false;
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

// The bar's text chips, BATTLE first.
static int chips(const struct pd_ui* ui, const struct pd_game* g, enum pd_tab out[PD_MAX_BAR_TABS + 1]) {
    enum pd_tab bar[PD_MAX_BAR_TABS];
    int n = 0, m = bar_tabs(ui, g, bar);
    bool replaceParty = false;
    if (ui->battleOnBar && ui->shoulderTabs)
        for (int i = 0; i < m; i++) replaceParty |= bar[i] == PD_TAB_PARTY;
    if (ui->battleOnBar && !replaceParty) out[n++] = PD_TAB_BATTLE;
    for (int i = 0; i < m; i++)
        out[n++] = replaceParty && bar[i] == PD_TAB_PARTY ? PD_TAB_BATTLE : bar[i];
    return n;
}

static bool on_bar(const struct pd_ui* ui, enum pd_tab t) {
    enum pd_tab tabs[PD_MAX_BAR_TABS + 1];
    int n = chips(ui, ui->drawnGame, tabs);
    for (int i = 0; i < n; i++)
        if (tabs[i] == t) return true;
    return false;
}

static void chip(struct pd_ui* ui, struct pd_canvas* c, int x, int w, bool open, int id) {
    // HeartGold's two-tone face, like every companion button (the app's tab chips).
    pd_layered_two_tone(c, x, BAR_Y(c), w, CHIP_H, 3, pd_cur->chip, pd_cur->chipCount,
                        open || ui->pressedId == id ? pd_cur->tabSelectedFill : pd_cur->tabIdleFill, 0);
    ui_add_hit(ui, x, BAR_Y(c), w, CHIP_H, id);
}

static void icon_chip(struct pd_ui* ui, struct pd_canvas* c, int x, const struct pd_mono* icon, bool open, int id) {
    chip(ui, c, x, ICON_CHIP_W, open, id);
    ui_mono(c, icon, x + (ICON_CHIP_W - icon->w) / 2, BAR_Y(c) + (CHIP_H - icon->h) / 2, 1, open ? PD_VALUE : PD_TITLE_TEXT);
}

static void tab_bar(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_host_info* host) {
    int by = BAR_Y(c);
    enum pd_tab tabs[PD_MAX_BAR_TABS + 1];
    enum pd_tab more[UI_BAR_CHOICES];
    int n = chips(ui, g, tabs);
    bool hasMore = ui_more_tabs(ui, g, more) > 0;
    int icons = hasMore ? 2 : 1;
    // L / R at the ends while the buttons are the companion's (ShoulderHint).
    const bool shoulderHints = ui->keyFocus && !ui->shoulderTabs;
    int keys = shoulderHints ? 2 * (SHOULDER_W + CHIP_GAP) : 0;
    // Fast-forward's chip, on the hosts that show one (libretro frontends own fast-forward).
    int ffW = host && host->hasFfChip && !host->frontendOwnsPlayback ? FF_W + CHIP_GAP : 0;
    int room = c->w - 2 * MARGIN - icons * ICON_CHIP_W - (n + icons - 1) * CHIP_GAP - keys - ffW;
    int x = MARGIN;
    if (shoulderHints) {
        chip(ui, c, x, SHOULDER_W, false, HIT_SHOULDER_L);
        pd_text(c, x + (SHOULDER_W - pd_text_width("L")) / 2, pd_text_y(by, CHIP_H), "L", PD_TITLE_TEXT, PD_TITLE_SHADOW);
        x += SHOULDER_W + CHIP_GAP;
    }
    for (int i = 0; i < n; i++) {
        int w = room / n + (i < room % n ? 1 : 0);
        enum pd_tab t = tabs[i];
        bool open = ui->tab == t && ui->overlay != PD_OVERLAY_MORE;
        chip(ui, c, x, w, open, HIT_TAB + (int) t);
        int tw = pd_text_width(TAB_LABELS[t]);
        pd_text(c, x + (w - tw) / 2, pd_text_y(by, CHIP_H), TAB_LABELS[t], open ? PD_VALUE : PD_TITLE_TEXT,
                open ? PD_VALUE_SHADOW : PD_TITLE_SHADOW);
        x += w + CHIP_GAP;
    }
    // MORE: lit while a tab it opens is up.
    bool onBar = ui->tab == PD_TAB_SETTINGS;
    for (int i = 0; i < n; i++) onBar |= tabs[i] == ui->tab;
    if (hasMore) {
        icon_chip(ui, c, x, &PD_ICON_MORE, ui->overlay == PD_OVERLAY_MORE || !onBar, HIT_MORE);
        x += ICON_CHIP_W + CHIP_GAP;
    }
    if (ffW) {
        bool ff = host->ffOn;
        chip(ui, c, x, FF_W, ff, HIT_FF);
        uint32_t col = ff ? PD_VALUE : PD_TITLE_TEXT;
        pd_triangle(c, x + 7, by + CHIP_H / 2 - 4, 5, PD_RIGHT, col);
        pd_triangle(c, x + 13, by + CHIP_H / 2 - 4, 5, PD_RIGHT, col);
        x += FF_W + CHIP_GAP;
    }
    icon_chip(ui, c, x, &PD_ICON_GEAR, ui->tab == PD_TAB_SETTINGS && ui->overlay != PD_OVERLAY_MORE, HIT_TAB + PD_TAB_SETTINGS);
    if (shoulderHints) {
        x += ICON_CHIP_W + CHIP_GAP;
        chip(ui, c, x, SHOULDER_W, false, HIT_SHOULDER_R);
        pd_text(c, x + (SHOULDER_W - pd_text_width("R")) / 2, pd_text_y(by, CHIP_H), "R", PD_TITLE_TEXT, PD_TITLE_SHADOW);
    }
}

// What the cursor can land on (linux/main.c's focus): not the tab bar, nor a scrim, a list's drag area or its bar.
static bool focusable(int id) {
    return !(id >= HIT_TAB && id < HIT_TAB + PD_TAB_COUNT) && id != HIT_MORE && id != HIT_FF && id != HIT_SCRIM && id != HIT_BAG_LIST &&
        id != HIT_DEX_LIST && id != HIT_GUIDE_LIST && id != HIT_SETTINGS_LIST && id != HIT_SCROLL_BAR &&
        id != HIT_SHOULDER_L && id != HIT_SHOULDER_R && !(id >= HIT_SETTINGS_GROUP && id < HIT_SETTINGS_GROUP + 10);
}

static int focus_index(const struct pd_ui* ui, int id) {
    for (int i = 0; i < ui->hitCount; i++)
        if (ui->hits[i].id == id) return i;
    return -1;
}

// After a draw: the cursor's hit, moved to the screen's start on a new screen, or to the nearest item if its own went.
static int focus_resolve(struct pd_ui* ui) {
    static int lastTab = -1, lastOverlay = -1, lastSummary = -2, lastDex = -1;
    static struct pd_hit at;
    bool changed = lastTab != (int) ui->tab || lastOverlay != (int) ui->overlay || lastSummary != ui->summarySlot || lastDex != ui->dexOpen;
    lastTab = (int) ui->tab;
    lastOverlay = (int) ui->overlay;
    lastSummary = ui->summarySlot;
    lastDex = ui->dexOpen;
    int i = changed ? -1 : focus_index(ui, ui->focusId);
    if (i >= 0 && !focusable(ui->hits[i].id)) i = -1;
    if (i < 0 && !changed && ui->focusId >= 0) {
        int best = 1 << 30;
        for (int j = 0; j < ui->hitCount; j++) {
            const struct pd_hit* h = &ui->hits[j];
            int dx = h->x + h->w / 2 - (at.x + at.w / 2), dy = h->y + h->h / 2 - (at.y + at.h / 2);
            if (focusable(h->id) && dx * dx + dy * dy < best) {
                best = dx * dx + dy * dy;
                i = j;
            }
        }
    }
    if (i < 0 && ui->startId >= 0) i = focus_index(ui, ui->startId);
    for (int j = 0; i < 0 && j < ui->hitCount; j++)
        if (focusable(ui->hits[j].id)) i = j;
    ui->focusId = i >= 0 ? ui->hits[i].id : -1;
    if (i >= 0) at = ui->hits[i];
    return i;
}

// AppColors.focusRing, flush inside the element. A party slot shows its own selected look instead (pd_ui.focusId).
static void focus_ring(struct pd_ui* ui, struct pd_canvas* c) {
    int i = focus_resolve(ui);
    if (i < 0) return;
    const struct pd_hit* h = &ui->hits[i];
    if (h->id >= HIT_SLOT && h->id < HIT_SLOT + PD_PARTY_SIZE) return;
    pd_fill(c, h->x, h->y, h->w, 2, 0xFF7331);
    pd_fill(c, h->x, h->y + h->h - 2, h->w, 2, 0xFF7331);
    pd_fill(c, h->x, h->y, 2, h->h, 0xFF7331);
    pd_fill(c, h->x + h->w - 2, h->y, 2, h->h, 0xFF7331);
}

const struct pd_game* ui_game;

void pd_ui_draw(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g,
                const struct pd_snapshot* s, const struct pd_host_info* host) {
    const bool dexFont = ui->handheldDex && ui->tab == PD_TAB_DEX;
    const struct pd_glyph* glyphs;
    int glyphCount;
    if (dexFont) {
        pd_font_get(&glyphs, &glyphCount);
        pd_font_set(NULL, 0);
    }
    ui_game = g;
    ui->hitCount = 0;
    ui->startId = -1;
    ui->drawn = s;
    ui->drawnGame = g;
    ui->hostHasStates = host && host->hasStates;
    // The BATTLE tab: the DS battle screens' mint and Poké Ball in place of the game's backdrop (a Game Boy game keeps its
    // own plain look).
    if (!ui->keepBackdrop) {
        if (ui->tab == PD_TAB_BATTLE && ui->overlay != PD_OVERLAY_MORE && !pd_gb_look) {
            // BATTLE STYLE > GEN 5: Black's dark closed-ball screen instead, behind INFO / SUGGESTIONS too.
            if (ui_gen5_style(ui, g)) ui_gen5_backdrop(ui, c, s);
            else pd_battle_backdrop(c);
        }
        else if (!pd_party_backdrop(c, g)) pd_backdrop(c);
    }
    tab_bar(ui, c, g, host);
    // The tabs draw above the bar: the same pixels, a shorter canvas.
    struct pd_canvas full = *c, view = *c;
    view.h = BAR_Y(c) - 2;
    if (view.cy1 > view.h) view.cy1 = view.h;
    c = &view;
    if (ui->overlay == PD_OVERLAY_MORE) {
        ui_more_page(ui, c, g);
    } else if (ui->tab == PD_TAB_BATTLE && ui->battleSource) {
        ui_battle_tab(ui, c, g, s);
    } else if (ui->content) {
        // A backend's own tab content (libretro's DS companion) inside the shared frame and bar.
        ui->content(ui->contentCtx, ui, c);
    } else if (ui->tab == PD_TAB_SETTINGS) {
        ui_settings_tab(ui, c, g, s, host);
    } else if (!g->cfg) {
        ui_notice(c, "NOT SUPPORTED", g->unsupported ? g->unsupported : "", "SETTINGS has what was detected.");
    } else if (ui->tab == PD_TAB_PARTY) {
        ui_party_tab(ui, c, g, s, host);
    } else if (ui->tab == PD_TAB_BAG) {
        ui_bag_tab(ui, c, g, s, host);
    } else if (ui->tab == PD_TAB_MAP) {
        ui_map_tab(ui, c, g, s, host);
    } else if (ui->tab == PD_TAB_GUIDE) {
        ui_guide_tab(ui, c, g, s);
    } else if (ui->tab == PD_TAB_DEX) {
        ui_dex_tab(ui, c, g, s);
    } else if (ui->tab == PD_TAB_CARD) {
        ui_card_tab(ui, c, g, s);
    } else if (ui->tab == PD_TAB_STATES) {
        ui_states_tab(ui, c, host);
    } else if (ui->tab == PD_TAB_BOXES) {
        ui_boxes_tab(ui, c, g, s);
    } else {
        ui_battle_tab(ui, c, g, s);
    }
    // Overlays black out the whole companion, the bar too.
    c = &full;
    if (ui->overlay == PD_OVERLAY_PLACES) {
        ui_places_overlay(ui, c, g);
    } else if (ui->overlay == PD_OVERLAY_FLY) {
        ui_fly_overlay(ui, c);
    } else if (ui->overlay == PD_OVERLAY_GUIDE_NOTICE) {
        ui_guide_notice(ui, c);
    } else if (ui->overlay != PD_OVERLAY_MORE) {
        ui_overlay(ui, c);
    }
    if (ui->keyFocus) focus_ring(ui, c);
    if (dexFont) pd_font_set(glyphs, glyphCount);
}

// The list a drag scrolls right now.
static int* scroll_target(struct pd_ui* ui) {
    if (ui->overlay == PD_OVERLAY_PLACES || ui->overlay == PD_OVERLAY_FLY) return &ui->placesScroll;
    switch (ui->tab) {
    case PD_TAB_BAG: return &ui->bagScroll;
    case PD_TAB_DEX: return ui->dexOpen && ui->dexSection ? &ui->dexPageScroll : &ui->dexScroll;
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

bool ui_summary_learn(struct pd_ui* ui) {
    if (!ui->handheldParty || ui->summarySlot < 0 || !ui->summaryCanLearn) return false;
    ui->summaryLearn = !ui->summaryLearn;
    ui->summaryStats = ui->summaryInfo = false;
    ui->summaryLearnRow = 0;
    return true;
}

static void act(struct pd_ui* ui, int id) {
    if (ui_map_act(ui, id) || ui_dex_act(ui, id) || ui_guide_act(ui, id) || ui_battle_act(ui, id) || ui_card_act(ui, id) ||
        ui_battle_controls_act(ui, id, ui->drawn) || ui_states_act(ui, id) || ui_boxes_act(ui, id, ui->drawn) ||
        ui_bag_act(ui, id, ui->drawn)) {
        return;
    }
    // MORE is a page under the tab bar: a chip leaves it.
    if (ui->overlay == PD_OVERLAY_MORE && (id == HIT_MORE_TAB_BAR || id == HIT_MORE || (id >= HIT_TAB && id < HIT_TAB + PD_TAB_COUNT)))
        ui->overlay = id == HIT_MORE ? PD_OVERLAY_MORE : PD_OVERLAY_NONE;
    if (ui->overlay == PD_OVERLAY_TAB_BAR || ui->overlay == PD_OVERLAY_HUD || ui->overlay == PD_OVERLAY_MORE) {
        if (id >= HIT_OPTION && id < HIT_OPTION + UI_BAR_CHOICES) {
            ui_toggle_act(ui, id - HIT_OPTION);
        } else if (id == HIT_NO || id == HIT_SCRIM || id == HIT_MORE) {
            close_overlay(ui, PD_ACTION_NONE);
        }
        return;
    }
    if (ui->overlay != PD_OVERLAY_NONE) {
        if (id >= HIT_OPTION && id < HIT_OPTION + 16) {
            int i = id - HIT_OPTION;
            if (ui->overlay == PD_OVERLAY_PICK_THEME) {
                int n = 0;
                for (int k = 0; k < pd_theme_count; k++) {
                    if (pd_themes[k].seasonal) continue;
                    if (n++ == i) ui->settings->theme = pd_themes[k].id;
                }
            }
            if (ui->overlay == PD_OVERLAY_PICK_SCREEN) ui->settings->screenMode = i + (ui->pixelOff ? 1 : 0);
            if (ui->overlay == PD_OVERLAY_PICK_FF && i < PD_FF_CHOICES) ui->settings->ffSpeed = PD_FF_SPEEDS[i];
            if (ui->overlay == PD_OVERLAY_PICK_ASPECT && i < PD_ASPECTS) ui->settings->aspect = i;
            if (ui->overlay == PD_OVERLAY_PICK_SPEED) ui->settings->gameSpeed = i;
            if (ui->overlay == PD_OVERLAY_PICK_VOLUME) ui->settings->gameVolume = (i + 1) * 20;
            if (ui->overlay == PD_OVERLAY_PICK_FLY_ON && i < 4) ui->settings->flightVehicle = i;
            close_overlay(ui, PD_ACTION_SETTINGS_CHANGED);
        } else if (id == HIT_YES) {
            enum pd_action a = ui->overlay == PD_OVERLAY_CONFIRM_SAVE ? PD_ACTION_SAVE_STATE
                : ui->overlay == PD_OVERLAY_CONFIRM_LOAD ? PD_ACTION_LOAD_STATE
                : ui->overlay == PD_OVERLAY_CONFIRM_LEAVE ? PD_ACTION_LEAVE_GAME
                : ui->overlay == PD_OVERLAY_CONFIRM_RESTART ? PD_ACTION_RESTART_GAME : PD_ACTION_NONE;
            close_overlay(ui, a);
        } else if (id == HIT_NO || id == HIT_SCRIM) {
            close_overlay(ui, PD_ACTION_NONE);
        }
        return;
    }
    if (id == HIT_SHOULDER_L || id == HIT_SHOULDER_R) {
        pd_ui_next_tab(ui, id == HIT_SHOULDER_L ? -1 : 1);
    } else if (id >= HIT_TAB && id < HIT_TAB + PD_TAB_COUNT) {
        ui->tab = (enum pd_tab) (id - HIT_TAB);
        ui->summarySlot = -1;
        ui->moreOpened = false;
        // BATTLE VIEW: the tab opens on what it says.
        if (ui->tab == PD_TAB_BATTLE) ui->battleInfoOpen = ui->settings && ui->settings->battleInfoFirst && ui_battle_info_on(ui);
    } else if (id == HIT_MORE) {
        ui->overlay = ui->overlay == PD_OVERLAY_MORE ? PD_OVERLAY_NONE : PD_OVERLAY_MORE;
    } else if (id == HIT_MORE_TAB_BAR) {
        ui->tab = PD_TAB_SETTINGS;
        ui->overlay = PD_OVERLAY_TAB_BAR;
    } else if (id >= HIT_SETTINGS_GROUP && id < HIT_SETTINGS_GROUP + 10) {
        ui_settings_group(ui, id - HIT_SETTINGS_GROUP);
    } else if (id == HIT_SETTING + SETTING_TAB_BAR) {
        ui->overlay = PD_OVERLAY_TAB_BAR;
    } else if (id == HIT_SETTING + SETTING_HUD) {
        ui->overlay = PD_OVERLAY_HUD;
    } else if (id == HIT_SETTING + SETTING_FF_TOGGLE) {
        ui->action = PD_ACTION_TOGGLE_FF;
    } else if (id == HIT_SETTING + SETTING_GAME_SPEED) {
        ui->overlay = PD_OVERLAY_PICK_SPEED;
    } else if (id == HIT_SETTING + SETTING_VOLUME) {
        ui->overlay = PD_OVERLAY_PICK_VOLUME;
    } else if (id == HIT_SETTING + SETTING_THEME) {
        ui->overlay = PD_OVERLAY_PICK_THEME;
    } else if (id == HIT_SETTING + SETTING_FLY_ON) {
        ui->overlay = PD_OVERLAY_PICK_FLY_ON;
    } else if (id == HIT_SETTING + SETTING_DARK || id == HIT_SETTING + SETTING_FF_MODE || id == HIT_SETTING + SETTING_CHEATS) {
        bool* flag = id == HIT_SETTING + SETTING_DARK ? &ui->settings->darkMode :
            id == HIT_SETTING + SETTING_FF_MODE ? &ui->settings->ffNormal : &ui->settings->cheats;
        *flag = !*flag;
        ui->action = PD_ACTION_SETTINGS_CHANGED;
    } else if (id == HIT_SETTING + SETTING_RESTART) {
        ui->overlay = PD_OVERLAY_CONFIRM_RESTART;
    } else if (id == HIT_SETTING + SETTING_MUTE || id == HIT_SETTING + SETTING_WILD || id == HIT_SETTING + SETTING_AUTO_RUN ||
               id == HIT_SETTING + SETTING_RUN_INDOORS || id == HIT_SETTING + SETTING_HINTS || id == HIT_SETTING + SETTING_ONE_TAP ||
               id == HIT_SETTING + SETTING_BATTLE_STYLE || id == HIT_SETTING + SETTING_BATTLE_INFO ||
               id == HIT_SETTING + SETTING_BATTLE_VIEW || id == HIT_SETTING + SETTING_FLY_STYLE ||
               id == HIT_SETTING + SETTING_FLIGHT_AREA) {
        struct pd_settings* st = ui->settings;
        bool* flag = id == HIT_SETTING + SETTING_MUTE ? &st->gameMuted : id == HIT_SETTING + SETTING_WILD ? &st->wildOff
            : id == HIT_SETTING + SETTING_AUTO_RUN ? &st->autoRun : id == HIT_SETTING + SETTING_RUN_INDOORS ? &st->runIndoors
            : id == HIT_SETTING + SETTING_HINTS ? &st->hideHints : id == HIT_SETTING + SETTING_ONE_TAP ? &st->oneTapMoves
            : id == HIT_SETTING + SETTING_BATTLE_INFO ? &st->noBattleInfo : id == HIT_SETTING + SETTING_BATTLE_VIEW ? &st->battleInfoFirst
            : id == HIT_SETTING + SETTING_FLY_STYLE ? &st->flyClassic : id == HIT_SETTING + SETTING_FLIGHT_AREA ? &st->flightAnywhere
            : &st->battleGen5;
        *flag = !*flag;
        ui->action = PD_ACTION_SETTINGS_CHANGED;
    } else if (id == HIT_FF) {
        ui->action = PD_ACTION_TOGGLE_FF;
    } else if (id >= HIT_SLOT && id < HIT_SLOT + PD_PARTY_SIZE) {
        ui->summarySlot = id - HIT_SLOT;
        ui->summaryStats = ui->summaryInfo = ui->summaryLearn = false;
        ui->summaryLearnRow = 0;
    } else if (id >= HIT_SUMMARY_MON && id < HIT_SUMMARY_MON + PD_PARTY_SIZE) {
        ui->summarySlot = id - HIT_SUMMARY_MON;
        ui->summaryLearnRow = 0;
    } else if (id == HIT_SUMMARY_LEARN) {
        ui_summary_learn(ui);
    } else if (id == HIT_SUMMARY_STATS) {
        ui->summaryStats = !ui->summaryStats;
        ui->summaryInfo = ui->summaryLearn = false;
    } else if (id == HIT_SUMMARY_INFO) {
        ui->summaryInfo = !ui->summaryInfo;
        ui->summaryStats = ui->summaryLearn = false;
    } else if (id == HIT_SUMMARY_TAKE) {
        ui->action = PD_ACTION_ITEM_TAKE;
    } else if (id == HIT_BACK) {
        pd_ui_back(ui);
    } else if (id >= HIT_BAG_TAB && id < HIT_BAG_LIST) {
        ui->bagPocket = id - HIT_BAG_TAB;
        ui->bagSelected = -1;
        ui->bagScroll = 0;
    } else if (id >= HIT_BAG_ROW && id < HIT_BAG_ROW_END) {
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
    } else if (id == HIT_SETTING + SETTING_PERF) {
        ui->settings->showPerf = !ui->settings->showPerf;
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

bool pd_ui_settings_control(struct pd_ui* ui, int dy, bool accept) {
    int count = ui->settingsNavCount, index = 0;
    while (index < count && ui->settingsNav[index].id != ui->settingsNavId) ++index;
    if (index == count) return false;
    index = pd_ui_list_step(index, count, dy);
    ui->settingsNavId = ui->settingsNav[index].id;
    ui->settingsScroll = ui_settings_follow(ui->settingsScroll, ui->settingsNav[index].top, ui->settingsNavHeight);
    if (accept) act(ui, ui->settingsNavId);
    return true;
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
            ui->barDragging = id == HIT_SCROLL_BAR;
            if (!ui->barDragging) return id != -1;
        }
        // ... a list's bar follows the stylus: the thumb's middle under it ...
        if (ui->barDragging) {
            int* scroll = scroll_target(ui), span = ui->barH - ui->barThumb;
            if (scroll && span > 0) *scroll = ui_clamp_scroll((y - ui->barY - ui->barThumb / 2) * ui->barMax / span, ui->barMax, 0);
            ui->pressedId = -1;
            return true;
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
    if (ui->barDragging) {
        ui->barDragging = false;
        return true;
    }
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

int pd_ui_tab_menu_count(const struct pd_ui* ui) {
    enum pd_tab tabs[UI_BAR_CHOICES];
    if (ui->overlay == PD_OVERLAY_MORE) return ui_more_tabs(ui, ui->drawnGame, tabs);
    if (ui->overlay == PD_OVERLAY_TAB_BAR) return ui_configurable_tabs(ui, tabs);
    if (ui->overlay == PD_OVERLAY_HUD) return 4 + PD_HUD_PARTS; // HUD, LAYOUT, LOOK, SIZE, then the parts
    return 0;
}

void pd_ui_tab_menu_focus(struct pd_ui* ui, int row) {
    ui->pressedId = row >= 0 && row < pd_ui_tab_menu_count(ui) ? HIT_OPTION + row : -1;
}

void pd_ui_tab_menu_choose(struct pd_ui* ui, int row) {
    if (row >= 0 && row < pd_ui_tab_menu_count(ui)) ui_toggle_act(ui, row);
}

void pd_ui_next_tab(struct pd_ui* ui, int dir) {
    if (ui->overlay != PD_OVERLAY_NONE &&
        !(ui->shoulderTabs && ui->overlay == PD_OVERLAY_MORE)) return;
    enum pd_tab order[PD_MAX_BAR_TABS + 3];
    int n = chips(ui, ui->drawnGame, order);
    enum pd_tab more[UI_BAR_CHOICES];
    const bool hasMore = ui->shoulderTabs && ui_more_tabs(ui, ui->drawnGame, more) > 0;
    const enum pd_tab current = hasMore && (ui->overlay == PD_OVERLAY_MORE || ui->moreOpened)
        ? PD_TAB_COUNT : ui->tab;
    if (hasMore) order[n++] = PD_TAB_COUNT;
    order[n++] = PD_TAB_SETTINGS;
    int at = n - 1;
    for (int i = 0; i < n; i++)
        if (order[i] == current) at = i;
    const enum pd_tab next = order[(at + n + dir) % n];
    ui->overlay = next == PD_TAB_COUNT ? PD_OVERLAY_MORE : PD_OVERLAY_NONE;
    ui->tab = next == PD_TAB_COUNT ? PD_TAB_SETTINGS : next;
    ui->summarySlot = -1;
    ui->moreOpened = false;
}

void pd_ui_focus_move(struct pd_ui* ui, int direction) {
    int cur = focus_index(ui, ui->focusId);
    if (cur < 0) {
        focus_resolve(ui);
        return;
    }
    const struct pd_hit* h = &ui->hits[cur];
    int best = -1, score = 1000000;
    for (int i = 0; i < ui->hitCount; i++) {
        const struct pd_hit* t = &ui->hits[i];
        if (i == cur || !focusable(t->id)) continue;
        int dx = (t->x + t->w / 2) - (h->x + h->w / 2), dy = (t->y + t->h / 2) - (h->y + h->h / 2);
        int along = direction == 2 ? -dx : direction == 3 ? dx : direction == 0 ? -dy : dy;
        int across = direction >= 2 ? abs(dy) : abs(dx);
        if (along > 0 && along + across * 4 < score) {
            best = i;
            score = along + across * 4;
        }
    }
    if (best >= 0) {
        ui->focusId = ui->hits[best].id;
    } else if (direction < 2) {
        // Nothing further that way on this screen: the list moves on.
        int* scroll = scroll_target(ui);
        if (scroll) *scroll += direction == 1 ? 36 : -36;
    }
}

void pd_ui_focus_press(struct pd_ui* ui) {
    if (focus_index(ui, ui->focusId) >= 0) act(ui, ui->focusId);
}

bool pd_ui_back(struct pd_ui* ui) {
    if (ui->itemPicking) {
        if (ui->itemMoveSlot >= 0) ui->itemMoveSlot = -1;
        else ui->itemPicking = false;
        return true;
    }
    // The GUIDE's notice: BACK is its GO BACK.
    if (ui->overlay == PD_OVERLAY_GUIDE_NOTICE) return ui_guide_act(ui, HIT_NOTICE_BACK);
    if (ui->overlay != PD_OVERLAY_NONE) {
        ui->overlay = PD_OVERLAY_NONE;
        return true;
    }
    if (*ui->boxMessage) {
        *ui->boxMessage = 0;
        return true;
    }
    if (ui->boxSwapPick) {
        ui->boxSwapPick = false;
        return true;
    }
    if (ui->tab == PD_TAB_BOXES && ui->boxSummary >= 0) {
        ui->boxSummary = -1;
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
        if (ui->handheldDex) ui->dexReturn = ui->dexOpen;
        ui->dexOpen = 0;
        return true;
    }
    if (ui->tab == PD_TAB_MAP && (ui->mapSel >= 0 || ui->mapDungeon >= 0 || ui->mapPage >= 0)) {
        ui->mapSel = ui->mapDungeon = ui->mapPage = -1;
        return true;
    }
    // BACK from a tab opened from MORE returns there; the CARD opened from SETTINGS, to SETTINGS.
    if (ui->tab == PD_TAB_CARD && !on_bar(ui, PD_TAB_CARD) && !ui->moreOpened) {
        ui->tab = PD_TAB_SETTINGS;
        return true;
    }
    if (!on_bar(ui, ui->tab) && ui->tab != PD_TAB_SETTINGS) {
        ui->overlay = PD_OVERLAY_MORE;
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
