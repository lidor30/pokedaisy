// NUZLOCKE: the app's NuzlockeScreen - one list in SETTINGS' look: HERE (the area the player stands in: free, or what its
// encounter was), TEAM (the run's living Pokémon), ENCOUNTERS (one row an area, newest first), GRAVEYARD and the RULES
// with NEW RUN. Every row the tracker fills can be put right by hand: an encounter's outcome (or cleared), a Pokémon
// marked dead or brought back. While it's off for this game: what it does and TURN ON.
#include <stdio.h>
#include <string.h>

#include "pd_icon.h"
#include "pd_nuzlocke.h"
#include "pd_tables.h"
#include "pd_ui_internal.h"

#define TITLE_H 24

// The rows as drawn, for the taps: what each one is.
enum row_kind { ROW_GROUP, ROW_HERE, ROW_MON, ROW_ENCOUNTER, ROW_DEAD, ROW_DUPES, ROW_WARNINGS, ROW_NEW_RUN, ROW_NONE };
struct row {
    enum row_kind kind;
    int area;
    uint32_t personality;
    char label[40], value[24], sub[64], name[32];
    int species;
};
#define ROWS (PD_NUZ_ENCOUNTERS + PD_NUZ_MONS + 16)
static struct row rows[ROWS];
static int rowCount;
static const struct pd_nuz_run* shownRun;

static struct row* add(enum row_kind kind) {
    if (rowCount == ROWS) return NULL;
    struct row* r = &rows[rowCount++];
    memset(r, 0, sizeof(*r));
    r->kind = kind;
    return r;
}

static void group_row(const char* title) {
    struct row* r = add(ROW_GROUP);
    if (r) snprintf(r->label, sizeof r->label, "%s", title);
}

static const char* outcome_label(enum pd_nuz_outcome o) {
    return o == PD_NUZ_NOW ? "NOW" : o == PD_NUZ_CAUGHT ? "CAUGHT" : "MISSED";
}

static void build(const struct pd_game* g, const struct pd_host_info* host) {
    const struct pd_nuz_run* run = host->nuzRun;
    char buf[40];
    rowCount = 0;
    group_row("HERE");
    const struct pd_nuz_encounter* here = pd_nuz_encounter_at(run, host->nuzArea);
    struct row* r = add(ROW_HERE);
    snprintf(r->label, sizeof r->label, "%s", host->nuzAreaName[0] ? host->nuzAreaName : "?");
    if (here) {
        r->area = here->area;
        r->species = here->species;
        snprintf(r->value, sizeof r->value, "%s", outcome_label(here->outcome));
        snprintf(r->sub, sizeof r->sub, "%s", pd_species_name(g, here->species, buf, sizeof buf));
    } else {
        r->area = -1;
        snprintf(r->value, sizeof r->value, "FREE");
        snprintf(r->sub, sizeof r->sub, "Your first wild POKéMON here is this area's catch");
    }
    group_row("TEAM");
    if (!pd_nuz_alive(run)) snprintf(add(ROW_NONE)->label, sizeof r->label, "Nobody yet");
    for (int i = 0; i < run->monCount; i++) {
        const struct pd_nuz_mon* m = &run->mons[i];
        if (m->dead || !(r = add(ROW_MON))) continue;
        r->personality = m->personality;
        r->species = m->species;
        snprintf(r->label, sizeof r->label, "%s", m->name);
        snprintf(r->name, sizeof r->name, "%s", m->name);
        snprintf(r->value, sizeof r->value, "Lv%d", m->level);
        if (m->areaName[0]) snprintf(r->sub, sizeof r->sub, "Joined in %s", m->areaName);
        else snprintf(r->sub, sizeof r->sub, "With you from the start");
    }
    group_row("ENCOUNTERS");
    if (!run->encCount) snprintf(add(ROW_NONE)->label, sizeof r->label, "None yet");
    for (int i = run->encCount - 1; i >= 0; i--) {
        const struct pd_nuz_encounter* e = &run->enc[i];
        if (!(r = add(ROW_ENCOUNTER))) break;
        r->area = e->area;
        r->species = e->species;
        snprintf(r->label, sizeof r->label, "%s", e->areaName);
        snprintf(r->value, sizeof r->value, "%s", outcome_label(e->outcome));
        snprintf(r->sub, sizeof r->sub, "%s", pd_species_name(g, e->species, buf, sizeof buf));
    }
    if (pd_nuz_dead(run)) {
        group_row("GRAVEYARD");
        for (int i = 0; i < run->monCount; i++) {
            const struct pd_nuz_mon* m = &run->mons[i];
            if (!m->dead || !(r = add(ROW_DEAD))) continue;
            r->personality = m->personality;
            r->species = m->species;
            snprintf(r->label, sizeof r->label, "%s", m->name);
            snprintf(r->name, sizeof r->name, "%s", m->name);
            snprintf(r->value, sizeof r->value, "Lv%d", m->diedLevel);
            if (m->diedIn[0]) snprintf(r->sub, sizeof r->sub, "Fainted in %s", m->diedIn);
        }
    }
    group_row("RULES");
    r = add(ROW_DUPES);
    snprintf(r->label, sizeof r->label, "DUPES CLAUSE");
    snprintf(r->value, sizeof r->value, "%s", run->dupes ? "ON" : "OFF");
    snprintf(r->sub, sizeof r->sub, "A POKéMON you've caught before doesn't use up an area");
    r = add(ROW_WARNINGS);
    snprintf(r->label, sizeof r->label, "WARNINGS");
    snprintf(r->value, sizeof r->value, "%s", run->warnings ? "ON" : "OFF");
    snprintf(r->sub, sizeof r->sub, "Notices on the game screen as the run goes");
    r = add(ROW_NEW_RUN);
    snprintf(r->label, sizeof r->label, "NEW RUN");
}

// While it's off for this game: what it does, and the same switch as SETTINGS' NUZLOCKE.
static void off_page(struct pd_ui* ui, struct pd_canvas* c) {
    int x = MARGIN, w = c->w - 2 * MARGIN, y = CONTENT_Y;
    int f = pd_title_box(c, x, y, w, TITLE_H, PD_TITLE_FILL);
    pd_text(c, x + f + 6, pd_text_y(y, TITLE_H), "NUZLOCKE", PD_TITLE_TEXT, PD_TITLE_SHADOW);
    pd_text_right(c, x + w - f - 6, pd_text_y(y, TITLE_H), "OFF", PD_VALUE, PD_VALUE_SHADOW);
    y += TITLE_H + 4;
    int h = c->h - y - MARGIN;
    int lf = pd_list_box(c, x, y, w, h);
    int ix = x + lf + 6, iw = w - 2 * (lf + 6), ty = y + lf + 4;
    ty += pd_text_wrap(c, ix, ty, iw, pd_lh(16), 6,
                       "NUZLOCKE tracking is off for this game. Turn it on and the companion keeps score of your run: only "
                       "the first wild POKéMON in each area counts, and a POKéMON that faints is out for good.",
                       PD_LABEL, PD_LABEL_SHADOW) * pd_lh(16) + 4;
    pd_text_wrap(c, ix, ty, iw, pd_lh(16), 2, "You can turn it off again in SETTINGS, under GAMEPLAY.", PD_MUTED, PD_MUTED_SHADOW);
    ui_button(ui, c, ix, y + h - lf - 30, iw, 24, "TURN ON", HIT_NUZ_ON);
    ui->startId = HIT_NUZ_ON;
}

void ui_nuzlocke_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_host_info* host) {
    if (!host || !host->nuzRun) {
        ui_notice(c, "NUZLOCKE", "Not for this game:", "the GBA games the companion reads.");
        return;
    }
    if (!host->nuzlockeOn) {
        off_page(ui, c);
        return;
    }
    shownRun = host->nuzRun;
    build(g, host);
    int x = MARGIN, w = c->w - 2 * MARGIN, y = CONTENT_Y;
    int f = pd_title_box(c, x, y, w, TITLE_H, PD_TITLE_FILL);
    pd_text(c, x + f + 6, pd_text_y(y, TITLE_H), "NUZLOCKE", PD_TITLE_TEXT, PD_TITLE_SHADOW);
    char counts[40];
    snprintf(counts, sizeof counts, "%d ALIVE · %d FAINTED", pd_nuz_alive(host->nuzRun), pd_nuz_dead(host->nuzRun));
    pd_text_right(c, x + w - f - 6, pd_text_y(y, TITLE_H), counts, PD_VALUE, PD_VALUE_SHADOW);
    y += TITLE_H + 4;
    int lf = pd_list_box(c, x, y, w, c->h - y - MARGIN);
    int ix = x + lf, iy = y + lf + 1, iw = w - 2 * lf, ih = c->h - y - MARGIN - 2 * lf - 2;
    ui_add_scroll_hit(ui, ix, iy, iw, ih, HIT_NUZ_LIST);
    // A row: its line, and its subtitle under it in the small smoothed text where it has one.
    const int line = pd_list_row(18), subH = 9, groupH = pd_list_row(18);
    int total = 0;
    for (int i = 0; i < rowCount; i++) total += rows[i].kind == ROW_GROUP ? groupH : line + (rows[i].sub[0] ? subH : 0);
    // The row a host's D-pad cursor moves onto comes wholly into view.
    static int seenFocus = -1;
    if (ui->focusId != seenFocus && ui->focusId >= HIT_NUZ_ROW && ui->focusId < HIT_NUZ_ROW + rowCount) {
        int at = 0, k = ui->focusId - HIT_NUZ_ROW;
        for (int i = 0; i < k; i++) at += rows[i].kind == ROW_GROUP ? groupH : line + (rows[i].sub[0] ? subH : 0);
        ui->nuzScroll = ui_scroll_to(ui->nuzScroll, at, line + (rows[k].sub[0] ? subH : 0), ih);
    }
    seenFocus = ui->focusId;
    ui->nuzScroll = ui_clamp_scroll(ui->nuzScroll, total, ih);
    pd_clip(c, ix, iy, iw, ih);
    int ry = iy - ui->nuzScroll;
    for (int i = 0; i < rowCount; i++) {
        const struct row* r = &rows[i];
        int h = r->kind == ROW_GROUP ? groupH : line + (r->sub[0] ? subH : 0);
        if (ry + h > iy && ry < iy + ih) {
            if (r->kind == ROW_GROUP) {
                pd_text(c, ix + 6, pd_text_y(ry, groupH), r->label, PD_VALUE, PD_VALUE_SHADOW);
            } else {
                bool sel = ui->pressedId == HIT_NUZ_ROW + i;
                if (sel) pd_fill(c, ix, ry, iw, h, PD_ROW_SELECTED);
                int tx = ix + 6;
                if (r->species) {
                    const uint32_t* icon = pd_mon_icon_pixels(ui->drawnGame, r->species, false);
                    for (int py = 0; icon && py < 16; py++)
                        for (int px = 0; px < 16; px++) {
                            uint32_t p = icon[py * 64 + px * 2];
                            if (p != 0xff000000u) pd_fill(c, tx + px, ry + (line - 16) / 2 + py, 1, 1, p & 0xffffff);
                        }
                    tx += 18;
                }
                const uint32_t ink = r->kind == ROW_NONE ? PD_MUTED : PD_LABEL;
                const uint32_t shadow = r->kind == ROW_NONE ? PD_MUTED_SHADOW : PD_LABEL_SHADOW;
                int vw = r->value[0] ? pd_text_width(r->value) : 0;
                pd_text_fit(c, tx, pd_text_y(ry, line), ix + iw - 10 - vw - 6 - tx, r->label, ink, shadow);
                if (r->value[0]) pd_text_right(c, ix + iw - 10, pd_text_y(ry, line), r->value, PD_VALUE, PD_VALUE_SHADOW);
                if (r->sub[0]) pd_text_mini(c, tx, ry + line - 3, r->sub, PD_MUTED, PD_MUTED_SHADOW, 3, 4);
                if (r->kind != ROW_NONE) {
                    int top = ry < iy ? iy : ry, bottom = ry + h > iy + ih ? iy + ih : ry + h;
                    ui_add_scroll_hit(ui, ix, top, iw, bottom - top, HIT_NUZ_ROW + i);
                }
                if (i + 1 < rowCount && rows[i + 1].kind != ROW_GROUP) ui_row_divider(c, ix + 2, ry + h - 1, iw - 4);
            }
        }
        ry += h;
    }
    pd_unclip(c);
    ui_list_rail(ui, c, ix + iw, iy, ih, ui->nuzScroll, total);
    if (ui->startId < 0) ui->startId = HIT_NUZ_ROW + 1;
}

// The outcome pick (CAUGHT / MISSED / NOT USED), or a confirm (FAINTED? / BRING BACK? / NEW RUN?).
void ui_nuzlocke_overlay(struct pd_ui* ui, struct pd_canvas* c) {
    pd_dim(c, 0xE6);
    ui_add_hit(ui, 0, 0, c->w, c->h, HIT_SCRIM);
    int x = MARGIN, w = c->w - 2 * MARGIN, y = CONTENT_Y + 8;
    int f = pd_title_box(c, x, y, w, TITLE_H, PD_TITLE_FILL);
    pd_text_fit(c, x + f + 6, pd_text_y(y, TITLE_H), w - 2 * f - 12, ui->nuzTitle, PD_TITLE_TEXT, PD_TITLE_SHADOW);
    y += TITLE_H + 4;
    if (ui->overlay == PD_OVERLAY_NUZ_PICK) {
        static const char* const OPTIONS[3] = { "CAUGHT", "MISSED", "NOT USED" };
        const int row = pd_list_row(18);
        int lf = pd_list_box(c, x, y, w, 3 * row + 18);
        int ix = x + lf + 4, iw = w - 2 * (lf + 4), ry = y + lf + 2;
        ui->startId = HIT_NUZ_PICK + (ui->nuzValue >= 0 && ui->nuzValue < 3 ? ui->nuzValue : 0);
        for (int i = 0; i < 3; i++) {
            bool on = i == ui->nuzValue || ui->pressedId == HIT_NUZ_PICK + i;
            if (on) pd_fill(c, ix - 4, ry, iw + 8, row, PD_ROW_SELECTED);
            pd_text(c, ix + 10, pd_text_y(ry, row), OPTIONS[i], on ? PD_VALUE : PD_LABEL, on ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
            if (i == ui->nuzValue) pd_triangle(c, ix, ry + 4, 5, PD_RIGHT, PD_VALUE);
            ui_add_hit(ui, ix - 4, ry, iw + 8, row, HIT_NUZ_PICK + i);
            if (i < 2) ui_row_divider(c, ix - 2, ry + row - 1, iw + 4);
            ry += row;
        }
        return;
    }
    int lf = pd_list_box(c, x, y, w, 96);
    int ix = x + lf + 6, iw = w - 2 * (lf + 6);
    pd_text_wrap(c, ix, y + lf + 4, iw, pd_lh(16), 3, ui->nuzMessage, PD_LABEL, PD_LABEL_SHADOW);
    int by = y + 96 - lf - 26, bw = (iw - 8) / 2;
    ui_button(ui, c, ix, by, bw, 22, "YES", HIT_YES);
    ui_button(ui, c, ix + bw + 8, by, bw, 22, "NO", HIT_NO);
    ui->startId = HIT_NO;
}

static void confirm(struct pd_ui* ui, enum pd_nuz_edit edit, const char* name, uint32_t personality) {
    ui->nuzEdit = edit;
    ui->nuzPersonality = personality;
    if (edit == PD_NUZ_EDIT_DEAD) {
        snprintf(ui->nuzTitle, sizeof ui->nuzTitle, "%s FAINTED?", name);
        snprintf(ui->nuzMessage, sizeof ui->nuzMessage, "Marks %s as out of the run, as if it had fainted.", name);
    } else if (edit == PD_NUZ_EDIT_ALIVE) {
        snprintf(ui->nuzTitle, sizeof ui->nuzTitle, "BRING BACK %s?", name);
        snprintf(ui->nuzMessage, sizeof ui->nuzMessage, "Puts %s back in the run, for a faint that shouldn't count.", name);
    } else {
        snprintf(ui->nuzTitle, sizeof ui->nuzTitle, "NEW RUN?");
        snprintf(ui->nuzMessage, sizeof ui->nuzMessage,
                 "Forgets every encounter and fainted POKéMON of this run. Your party right now starts the new one.");
    }
    ui->overlay = PD_OVERLAY_NUZ_CONFIRM;
}

bool ui_nuzlocke_act(struct pd_ui* ui, int id) {
    if (ui->overlay == PD_OVERLAY_NUZ_PICK) {
        if (id >= HIT_NUZ_PICK && id < HIT_NUZ_PICK + 3) {
            ui->nuzEdit = PD_NUZ_EDIT_OUTCOME;
            ui->nuzValue = id == HIT_NUZ_PICK ? PD_NUZ_CAUGHT : id == HIT_NUZ_PICK + 1 ? PD_NUZ_MISSED : -1;
            ui->overlay = PD_OVERLAY_NONE;
            ui->action = PD_ACTION_NUZLOCKE_EDIT;
            return true;
        }
        if (id == HIT_SCRIM) ui->overlay = PD_OVERLAY_NONE;
        return id == HIT_SCRIM;
    }
    if (ui->overlay == PD_OVERLAY_NUZ_CONFIRM) {
        if (id == HIT_YES) {
            ui->overlay = PD_OVERLAY_NONE;
            ui->action = PD_ACTION_NUZLOCKE_EDIT;
            return true;
        }
        if (id == HIT_NO || id == HIT_SCRIM) {
            ui->overlay = PD_OVERLAY_NONE;
            return true;
        }
        return false;
    }
    if (ui->tab != PD_TAB_NUZLOCKE || ui->overlay != PD_OVERLAY_NONE) return false;
    if (id == HIT_NUZ_ON) {
        ui->action = PD_ACTION_NUZLOCKE_TOGGLE;
        return true;
    }
    if (id < HIT_NUZ_ROW || id >= HIT_NUZ_ROW + rowCount) return false;
    const struct row* r = &rows[id - HIT_NUZ_ROW];
    switch (r->kind) {
    case ROW_HERE:
    case ROW_ENCOUNTER: {
        if (r->area < 0 || !shownRun) return true;
        const struct pd_nuz_encounter* e = pd_nuz_encounter_at(shownRun, r->area);
        if (!e) return true;
        ui->nuzArea = r->area;
        ui->nuzValue = e->outcome == PD_NUZ_CAUGHT ? 0 : e->outcome == PD_NUZ_MISSED ? 1 : -1;
        snprintf(ui->nuzTitle, sizeof ui->nuzTitle, "%s", e->areaName);
        ui->overlay = PD_OVERLAY_NUZ_PICK;
        return true;
    }
    case ROW_MON: confirm(ui, PD_NUZ_EDIT_DEAD, r->name, r->personality); return true;
    case ROW_DEAD: confirm(ui, PD_NUZ_EDIT_ALIVE, r->name, r->personality); return true;
    case ROW_NEW_RUN: confirm(ui, PD_NUZ_EDIT_NEW_RUN, "", 0); return true;
    case ROW_DUPES:
    case ROW_WARNINGS:
        ui->nuzEdit = r->kind == ROW_DUPES ? PD_NUZ_EDIT_DUPES : PD_NUZ_EDIT_WARNINGS;
        ui->action = PD_ACTION_NUZLOCKE_EDIT;
        return true;
    default:
        return true;
    }
}
