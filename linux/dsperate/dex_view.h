#pragma once
#include "map_art.h"
#include "guide_view.h"
extern "C" {
#include "pd_canvas.h"
}
void ds_dex_view(pd_canvas*,const DsCompanion&,int filter,int selected,int filterFocus=-1,
                 const std::vector<std::vector<uint32_t>>* icons=nullptr,
                 const std::vector<int>* regional=nullptr,const char* modeLabel=nullptr);
enum class DsDexSection { Info, Evolve, Area, Moves, Count };
void ds_dex_entry(pd_canvas*,const DsCompanion&,int species,int number,const std::vector<std::vector<uint32_t>>&,
                  const DsDexDetails*,const DsDexPage&,const std::vector<DsDexEvolution>&,
                  const std::vector<DsGuideSpot>&,int row,int count,DsDexSection,int detailRow);
