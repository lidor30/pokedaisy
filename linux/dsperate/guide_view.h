// GPL-3.0. Android encounter and item data in the DS companion.
#pragma once
#include "companion_style.h"

struct DsGuideEncounter {
  uint8_t method;
  uint16_t species;
  uint8_t low,high,chance;
};
struct DsGuideHere {
  const DsGuideEncounter* entries=nullptr;
  int count=0;
};
struct DsGuideSpot {
  int map;
  DsGuideEncounter encounter;
  unsigned variants;
};
struct DsWildRates {const int32_t* triples=nullptr; int count=0;};
struct DsFlySpot {int header,x,z,flag,nameMap,region;};
DsWildRates ds_wild_rates(DsGame,int month,bool soulSilver);
const std::vector<DsFlySpot>& ds_fly_spots(DsGame);
bool ds_fly_unlocked(const DsCompanion&,const DsFlySpot&);
enum class GuidePage { Here, NextBoss, Tips, WhereIs, Stuck, Count };
int ds_guide_page_count(DsGame);
// Keep the guide UI independent of cartridge IDs and save flag layouts.
struct GuideRow {
  std::string title, detail, hint, answer;
  bool done=false,heading=false;
};
DsGuideHere ds_guide_here(DsGame,int map,int hour,int month,bool soulSilver=false);
DsGuideHere ds_guide_here(const DsCompanion&,bool soulSilver=false);
std::vector<DsGuideSpot> ds_guide_spots(DsGame,int species,bool soulSilver=false);
const char* ds_guide_method(int method);
std::vector<GuideRow> ds_guide_rows(const DsCompanion&,GuidePage,bool soulSilver=false);
void ds_guide_view(pd_ui*,pd_canvas*,const DsCompanion&,const std::vector<GuideRow>&,bool soulSilver);
