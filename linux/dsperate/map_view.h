// GPL-3.0. Cartridge map composition with shared map presentation.
#pragma once
#include "companion.h"
#include "map_art.h"
extern "C" {
#include "pd_canvas.h"
}
void ds_map_view(pd_canvas*,const DsCompanion&,const DsMapArt&,int,int);
