// STATES (StatesScreen.kt): the host's slots with their pictures, SAVE and LOAD on the one picked.
#include <stdio.h>

#include "pd_ui_internal.h"

#define TITLE_ROW 24
#define UNDO_ROW 22
#define CARDS 3

// The slot's picture: every [step]th pixel of the quarter-size frame, into w x h.
static void thumb(struct pd_canvas* c, const uint32_t* px, int x, int y, int w, int h) {
    for (int yy = 0; yy < h; yy++)
        for (int xx = 0; xx < w; xx++)
            pd_fill(c, x + xx, y + yy, 1, 1, px[(yy * PD_STATE_THUMB_H / h) * PD_STATE_THUMB_W + xx * PD_STATE_THUMB_W / w]);
}

void ui_states_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_host_info* host) {
    int x = MARGIN, w = c->w - 2 * MARGIN;
    int f = pd_title_box(c, x, CONTENT_Y, w, TITLE_ROW, PD_TITLE_FILL);
    pd_text(c, x + f + 6, pd_text_y(CONTENT_Y, TITLE_ROW), "SAVE STATES", PD_TITLE_TEXT, PD_TITLE_SHADOW);
    char name[16];
    snprintf(name, sizeof(name), "SLOT %d", host->slotSelected);
    pd_text_right(c, x + w - f - 6, pd_text_y(CONTENT_Y, TITLE_ROW), name, PD_TITLE_TEXT, PD_TITLE_SHADOW);

    int y = CONTENT_Y + TITLE_ROW + 3, half = (w - 4) / 2;
    if (host->undoSave) ui_button(ui, c, x, y, half, UNDO_ROW, "UNDO SAVE", HIT_STATE_UNDO_SAVE);
    else {
        pd_title_box(c, x, y, half, UNDO_ROW, PD_LIST_FILL);
        pd_text(c, x + (half - pd_text_width("UNDO SAVE")) / 2, pd_text_y(y, UNDO_ROW), "UNDO SAVE", PD_MUTED, PD_MUTED_SHADOW);
    }
    if (host->undoLoad) ui_button(ui, c, x + half + 4, y, half, UNDO_ROW, "UNDO LOAD", HIT_STATE_UNDO_LOAD);
    else {
        pd_title_box(c, x + half + 4, y, half, UNDO_ROW, PD_LIST_FILL);
        pd_text(c, x + half + 4 + (half - pd_text_width("UNDO LOAD")) / 2, pd_text_y(y, UNDO_ROW), "UNDO LOAD", PD_MUTED, PD_MUTED_SHADOW);
    }
    y += UNDO_ROW + 3;

    // Three cards, the picked one among them; a tap on a neighbour moves the row on.
    int first = host->slotSelected - 1;
    if (first > PD_STATE_SLOTS - CARDS) first = PD_STATE_SLOTS - CARDS;
    if (first < 0) first = 0;
    int cw = (w - 2 * 3) / CARDS, ch = c->h - y - 2;
    for (int k = 0; k < CARDS; k++) {
        int i = first + k, cx = x + k * (cw + 3);
        const struct pd_state_slot* slot = &host->slots[i];
        bool sel = i == host->slotSelected;
        int lf = pd_list_box(c, cx, y, cw, ch);
        int ix = cx + lf + 2, iw = cw - 2 * (lf + 2), iy = y + lf + 1;
        if (sel) pd_fill(c, ix - 2, iy - 1, iw + 4, ch - 2 * lf + 2, PD_ROW_SELECTED);
        snprintf(name, sizeof(name), "SLOT %d", i);
        pd_text(c, ix + 2, iy - 1, name, PD_LABEL, PD_LABEL_SHADOW);
        if (sel) pd_triangle(c, ix + iw - 6, iy + 3, 4, PD_LEFT, PD_VALUE);
        int th = iw * 2 / 3, ty = iy + 16;
        pd_fill(c, ix, ty, iw, th, pd_cur->imageWell);
        if (slot->thumb) thumb(c, slot->thumb, ix + 1, ty + 1, iw - 2, th - 2);
        ty += th + 2;
        pd_text_fit(c, ix, ty, iw, slot->used ? slot->when : "EMPTY", PD_MUTED, PD_MUTED_SHADOW);
        ui_add_hit(ui, cx, y, cw, ch, HIT_STATE_SLOT + i);
        if (sel) {
            int by = y + ch - lf - 21, bw = (iw - 2) / 2;
            ui_button(ui, c, ix, by, bw, 19, "SAVE", HIT_STATE_SAVE);
            if (slot->used) ui_button(ui, c, ix + bw + 2, by, bw, 19, "LOAD", HIT_STATE_LOAD);
        }
    }
}

bool ui_states_act(struct pd_ui* ui, int id) {
    if (id >= HIT_STATE_SLOT && id < HIT_STATE_SLOT + PD_STATE_SLOTS) {
        ui->action = PD_ACTION_STATE_SLOT;
        ui->actionSlot = id - HIT_STATE_SLOT;
        return true;
    }
    if (id == HIT_STATE_UNDO_SAVE || id == HIT_STATE_UNDO_LOAD) {
        ui->action = id == HIT_STATE_UNDO_SAVE ? PD_ACTION_UNDO_SAVE : PD_ACTION_UNDO_LOAD;
        return true;
    }
    if (id == HIT_STATE_SAVE) {
        ui->overlay = PD_OVERLAY_CONFIRM_SAVE;
        return true;
    }
    if (id == HIT_STATE_LOAD) {
        ui->overlay = PD_OVERLAY_CONFIRM_LOAD;
        return true;
    }
    return false;
}
