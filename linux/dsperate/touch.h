// GPL-3.0. Tap targets the DS companion's own views record as they draw, for a host that feeds it a pointer (the Windows
// core's mouse). The shared pd_ui writes its own (the tab bar, the GUIDE, the settings rows); these are the views that lay
// their lists and buttons out by hand. The ids are the companion's, above the shared HIT_* range.
#pragma once
#include <vector>
extern "C" {
#include "pd_ui.h"
}
struct ds_touch_hit {
    int x, y, w, h;
    int id;
};
enum {
    DS_HIT_PARTY = 63000,        // + slot: a party grid cell
    DS_HIT_BAG_TAB = 63100,      // + the bag's pocket tab (its pocket id)
    DS_HIT_BAG_SORT = 63150,
    DS_HIT_BAG_ROW = 63200,      // + item index
    DS_HIT_BAG_ROW_END = DS_HIT_BAG_ROW + 512,
    DS_HIT_DEX_FILTER = 64000,   // + filter (ALL / OWNED / SEEN / UNKNOWN)
    DS_HIT_DEX_ROW = 64100,      // + row index in the filtered list
    DS_HIT_DEX_ROW_END = DS_HIT_DEX_ROW + 2048,
    DS_HIT_DEX_SECTION = 66500,  // + INFO / EVOLVE / AREA / MOVES
    DS_HIT_MAP = 66600,
    DS_HIT_MAP_PLACES,
    DS_HIT_MAP_ME,
};
inline void ds_touch_add(std::vector<ds_touch_hit>* out, int x, int y, int w, int h, int id) {
    if (out) out->push_back(ds_touch_hit{x, y, w, h, id});
}
// Every hit a shared pd_ui recorded, as it stands.
inline void ds_touch_view(std::vector<ds_touch_hit>* out, const pd_ui* ui) {
    if (!out || !ui) return;
    for (int i = 0; i < ui->hitCount; i++)
        out->push_back(ds_touch_hit{ui->hits[i].x, ui->hits[i].y, ui->hits[i].w, ui->hits[i].h, ui->hits[i].id});
}
