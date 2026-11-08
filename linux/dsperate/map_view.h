// GPL-3.0. Cartridge map composition with shared map presentation.
#pragma once
#include "companion.h"
#include "map_art.h"
extern "C" {
#include "pd_canvas.h"
}
void ds_map_view(pd_canvas*,const DsCompanion&,const DsMapArt&,int,int,int action=-1);

struct DsMapPosition { int x=-1,y=-1; };
struct DsMapPlace { DsMapPosition position; const char* name; };
DsMapPosition ds_map_cursor_max(DsGame);
std::vector<DsMapPlace> ds_map_places(const DsCompanion&);
DsMapPosition ds_map_position(const DsCompanion&);
void ds_draw_minimap(pd_canvas*,const DsCompanion&,const DsMapArt&,int,int,int,int);
