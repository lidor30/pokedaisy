// GPL-3.0. Cartridge map composition with shared map presentation.
#pragma once
#include "companion.h"
#include "map_art.h"
#include "touch.h"
extern "C" {
#include "pd_canvas.h"
}

struct DsMapPosition { int x=-1,y=-1; };
struct DsMapPlace { DsMapPosition position; const char* name; };

pd_map_viewport ds_map_view(pd_canvas*,const DsCompanion&,const DsMapArt&,int,int,int action=-1,
                            std::vector<ds_touch_hit>* hits=nullptr);
// The map cell under a canvas point of [view] (the viewport ds_map_view returned), clamped to the map's own bounds:
// what a click on the map selects.
DsMapPosition ds_map_cell_at(DsGame,const DsMapArt&,pd_map_viewport view,int x,int y);

DsMapPosition ds_map_cursor_max(DsGame);
std::vector<DsMapPlace> ds_map_places(const DsCompanion&);
DsMapPosition ds_map_position(const DsCompanion&);
void ds_draw_minimap(pd_canvas*,const DsCompanion&,const DsMapArt&,int,int,int,int);
