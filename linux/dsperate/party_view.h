#pragma once
#include "companion.h"
struct DsDexArt;
struct pd_party_source_palette;
const pd_party_source_palette& ds_party_palette(DsGame game);
extern "C" {
#include "pd_canvas.h"
}
void ds_party_view(pd_canvas*,const DsCompanion&,const std::vector<std::vector<uint32_t>>&,int,bool,int,
                   const DsDexArt* art=nullptr,int learnRow=0,int* learnLast=nullptr);

bool ds_draw_party_icon(pd_canvas*,const std::vector<std::vector<uint32_t>>&,int id,int x,int y,int scale,bool faded,int size=32);
