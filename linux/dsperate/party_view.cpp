// GPL-3.0. DS data adapter for the established portable companion renderer.
#include "party_view.h"
#include "companion_style.h"
#include "map_art.h"
#include <algorithm>
#include <cstdio>
extern "C" {
#include "pd_ui.h"
#include "pd_tables.h"
#include "pd_stats.h"
#include "pd_experience.h"
}
bool ds_draw_party_icon(pd_canvas* c,const std::vector<std::vector<uint32_t>>& icons,int id,int x,int y,int scale,bool faded,int size){
  if(id<1 || size_t(id)>=icons.size() || icons[id].size()!=1024)return false;
  for(int yy=0;yy<size;++yy)for(int xx=0;xx<size;++xx){
    const auto color=icons[id][(yy*32/size)*32+xx*32/size];
    if(color>>24)pd_fill(c,x+xx*scale,y+yy*scale,scale,scale,faded?(color&0xfefefe)>>1:color&0xffffff);
  }
  return true;
}

namespace {
// Android's PlatinumPartyPalette, HeartGoldPartyPalette and BlackPartyPalette.
constexpr pd_hp_band dsGreen{0x61FB61,0x18C320}, dsYellow{0xFBDB00,0xEBAA00}, dsRed{0xFB9A9A,0xFB4938};
constexpr pd_party_source_palette platinum{
  {0x494961,0x9AC3F3,0x82B2F3,0x41699A,0x5982D3,0},
  {0xEB5900,0xB2DBEB,0x92DBF3,0x69BAEB,0x69BAEB,0},
  {0x494961,0xDBA251,0xCB8228,0x923800,0xBA6108,0},
  {0xEB5900,0xEBBAAA,0xEBBAAA,0xE39A79,0xE39A79,0},
  {0x494961,0x383849,0x383849,0x383849,0,0},
  0xFBFBFB,0x515151,0x000000,0xA2AA92,0xFB9A9A,0xFB4938,
  {0x828282,0xFBBABA,0xCBAAAA,0xF3F3F3},false,false,
  0,0x798292,dsGreen,dsYellow,dsRed,{0x697161,0xA2AA92},
  0x0071FB,0x79BAEB,0xFF9C94,0x9C4239
};
constexpr pd_party_source_palette johto{
  {0x004900,0x69DB71,0x009238,0x006920,0x69DB71,0},
  {0xFB2020,0x79F382,0x30DB51,0x10AA41,0x79F382,0},
  {0x490000,0xF37900,0xD33810,0x821000,0xF37900,0},
  {0xFB2020,0xFBAA41,0xFB7900,0xFB4100,0xFBAA41,0},
  {},
  0xFBFBFB,0x282828,0x414141,0xFBFBFB,0xFFD652,0xFFB542,
  {0x4A4A63,0xB54200,0xC65200,0xB5BDCE},false,false,
  89,0,dsGreen,{0xCEAD08,0xFFE739},{0xC63900,0xFF7331},{0x525252,0x737373},
  0x42CEFF,0x006394,0xFF9C94,0x9C4239
};
constexpr pd_party_source_palette unova{
  {0x0C719E,0x96FFFF,0x0C5175,0x0C719E,0x0C5175,0x0C304D},
  {0x0C719E,0x96FFFF,0x4579A6,0x0C719E,0x4579A6,0x0C304D},
  {0xC73886,0xFF9AAE,0x8E2024,0xC73886,0x8E2024,0x6D2824},
  {0x0C699E,0xC7CBCF,0xEF106D,0x0C699E,0xEF106D,0xB61055},
  {},
  0xFBFBFB,0x828282,0x303030,0x303030,0x00FB49,0x00BA20,
  {},true,true,
  0,0x0C415D,dsGreen,dsYellow,dsRed,{0x202020,0x202020},
  0x00EBFB,0x006961,0xE33838,0x712030
};
void party_summary(pd_canvas* c,const DsCompanion& s,const std::vector<std::vector<uint32_t>>& icons,int selected,
                   int page,const DsDexArt* art,int learnRow,int* learnLast){
  const auto& mon=s.party[selected];
  struct Context {const DsCompanion& s; const std::vector<std::vector<uint32_t>>& icons;} ctx{s, icons};
  const auto icon = [](void* data, pd_canvas* canvas, int slot, int x, int y, bool small) {
    const auto& context = *static_cast<Context*>(data);
    const auto& member = context.s.party[slot];
    return ds_draw_party_icon(canvas, context.icons, member.egg ? ds_egg_icon(context.s.game) : member.species,
                     x, y, 1, false, small ? 16 : 32);
  };
  pd_summary_view shared{};
  shared.ctx = &ctx;
  shared.icon = icon;
  shared.name = mon.egg ? "EGG" : mon.nickname.c_str();
  shared.selected = selected;
  shared.count = int(s.party.size());
  shared.primary = mon.egg ? nullptr : page ? "A MOVES" : "A STATS";
  shared.secondary = mon.egg ? nullptr : page == 2 ? "SEL MOVES" : "SEL LEARN";
  shared.handheldControls = true;
  shared.learnPage = page == PD_SUMMARY_LEARN;
  shared.level = mon.level; shared.hp = mon.hp; shared.maxHp = mon.maxHp;
  shared.type1 = ds_species_type(mon.species, 0, s.game);
  shared.type2 = ds_species_type(mon.species, 1, s.game);
  shared.status = mon.status; shared.egg = mon.egg;
  shared.experience = mon.experience; shared.hasExperience = true; shared.expPercent = -1;
  shared.speciesName = ds_species(mon.species, s.game);
  shared.heldItem = mon.heldItem ? ds_item(mon.heldItem, s.game) : "NONE";
  shared.friendship = mon.friendship;
  const int growth = art && size_t(mon.species) < art->details.size() ? art->details[mon.species].growth : -1;
  pd_exp xp{};
  if (pd_exp_progress(growth, mon.level, mon.experience, 100, &xp)) {
    shared.expPercent = xp.filled; shared.toNext = xp.toNext;
  }
  pd_mon_stats stats{};
  stats.valid = stats.hasIvs = stats.hasEvs = stats.hasNature = stats.hasHiddenPower = true;
  stats.nature = mon.nature;
  for (int i = 0; i < 6; i++) {
    stats.values[i] = mon.stats[i]; stats.ivs[i] = mon.ivs[i]; stats.evs[i] = mon.evs[i];
  }
  stats.hiddenPowerType = pd_hidden_power_type(&stats);
  stats.hiddenPowerPower = pd_hidden_power_power(&stats);
  shared.stats = &stats;
  for (int i = 0; i < 4; i++) if (int move = mon.moves[i])
    shared.moves[i] = {ds_move(move, s.game), ds_move_type(move, s.game), ds_move_power(move, s.game), mon.pp[i]};
  const auto moves = page == PD_SUMMARY_LEARN && art ? ds_level_moves(*art, s.game, mon.species) : std::vector<DsDexMove>{};
  if (learnLast) *learnLast = pd_ui_summary_learn_last(c->h, int(moves.size()));
  struct LearnContext {const std::vector<DsDexMove>& moves; DsGame game;};
  const LearnContext learnContext{moves, s.game};
  const pd_summary_learn learn{&learnContext, [](const void* data, int i, char*, size_t) {
    const auto& context = *static_cast<const LearnContext*>(data);
    const auto& move = context.moves[i];
    return pd_summary_learn_row{move.level, ds_move(move.move, context.game)};
  }, int(moves.size()), learnRow};
  shared.learn = &learn;
  pd_ui_summary_page(c, &shared, static_cast<pd_summary_page>(page));
  pd_ui_summary_footer(nullptr, c, &shared, false);
}

}
const pd_party_source_palette& ds_party_palette(DsGame game) {
  return ds_unova(game)?unova:game==DsGame::Johto?johto:platinum;
}

void ds_party_view(pd_canvas* c,const DsCompanion& s,const std::vector<std::vector<uint32_t>>& icons,int selected,bool summary,int page,const DsDexArt* art,int learnRow,int* learnLast){
  ds_companion_backdrop(c,s.game,PD_TAB_PARTY);
  if(summary && !s.party.empty()){
    party_summary(c,s,icons,std::clamp(selected,0,int(s.party.size())-1),page,art,learnRow,learnLast);
    return;
  }
  pd_snapshot view{};view.valid=s.valid;view.partyCount=std::min(6,int(s.party.size()));
  for(int i=0;i<view.partyCount;i++){
    const auto& m=s.party[i];auto& out=view.party[i];out.species=m.species;
    out.level=m.level;out.hp=m.hp;out.maxHp=m.maxHp;out.status=m.status;
    out.gender=static_cast<pd_gender>(m.gender);
    std::snprintf(out.nickname,sizeof out.nickname,"%s",m.nickname.c_str());
  }
  struct Context {const DsCompanion& s;const pd_snapshot& view;const std::vector<std::vector<uint32_t>>& icons; } ctx{s,view,icons};
  pd_party_source source{};source.ctx=&ctx;
  source.palette=&ds_party_palette(s.game);
  source.egg=[](void* data,const pd_mon* m){auto& x=*static_cast<Context*>(data);return x.s.party[m-x.view.party].egg;};
  source.species=[](void* data,int id){return ds_species(id,static_cast<Context*>(data)->s.game);};
  source.icon=[](void* data,pd_canvas* c,const pd_mon* m,int x,int y,int scale,bool faded){
    auto& ctx=*static_cast<Context*>(data);const auto& mon=ctx.s.party[m-ctx.view.party];
    int id=mon.egg?ds_egg_icon(ctx.s.game):mon.species;
    return ds_draw_party_icon(c,ctx.icons,id,x,y,scale,faded);
  };
  pd_ui ui{};ui.partySource=&source;ui.summarySlot=-1;
  ui.pressedId=-1;pd_game game{};
  ui.keepBackdrop=true;
  pd_ui_party_view(&ui,c,&game,&view,selected);
}
