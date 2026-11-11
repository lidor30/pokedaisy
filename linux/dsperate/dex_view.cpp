// GPL-3.0. National Gen 4/5 data for the established companion dex list.
#include "dex_view.h"
#include "browser.h"
#include "companion_style.h"
#include <cstdio>
#include <algorithm>
extern "C" {
#include "pd_ui.h"
#include "pd_style.h"
#include "pd_tables.h"

}
namespace {
struct DsMovesSource { const DsDexPage& page; DsGame game; };
void ds_move_row(const void* ctx, bool machine, int index, pd_dex_move_row* row) {
  const auto& source = *static_cast<const DsMovesSource*>(ctx);
  int move;
  if (machine) {
    const auto& m = source.page.machines[index];
    move = m.move;
    row->number = m.number;
    row->hm = m.hm;
  } else {
    const auto& m = source.page.moves[index];
    move = m.move;
    row->number = m.level;
  }
  std::snprintf(row->name, sizeof row->name, "%s", ds_move(move, source.game));
  std::snprintf(row->type, sizeof row->type, "%s", pd_type_name(nullptr, ds_move_type(move, source.game)));
}
std::string evolution_requirement(const DsDexEvolution& evo,DsGame game){
  int method=evo.method,param=evo.param;
  if(ds_unova(game)){
    if(method==7)return param?std::string("Trade for ")+ds_species(param,game):"Trade";
    if(method>7)--method;
  }
  const std::string level=param>0 && param<=100?"Lv"+std::to_string(param):"Level up";
  switch(method){
    case 1:return "Level up with friendship";
    case 2:return "Friendship by day";
    case 3:return "Friendship by night";
    case 4:case 13:return level;
    case 5:return "Trade";
    case 6:return std::string("Trade holding ")+ds_item(param,game);
    case 7:return std::string("Use ")+ds_item(param,game);
    case 8:return level+" / ATK > DEF";
    case 9:return level+" / ATK = DEF";
    case 10:return level+" / ATK < DEF";
    case 11:case 12:return level+" / personality";
    case 14:return level+" / spare party slot";
    case 15:return "Level up with beauty";
    case 16:case 17:return std::string(ds_item(param,game))+(method==16?" / male":" / female");
    case 18:case 19:return std::string("Hold ")+ds_item(param,game)+(method==18?" / day":" / night");
    case 20:return std::string("Know ")+ds_move(param,game);
    case 21:return std::string("Party has ")+ds_species(param,game);
    case 22:return level+" / male";
    case 23:return level+" / female";
    case 24:case 25:case 26:return "Level up at special place";
    default:return "Special condition";
  }
}
}

void ds_dex_view(pd_canvas* c,const DsCompanion& s,int filter,int selected,int filterFocus,
                 const std::vector<std::vector<uint32_t>>* icons,const std::vector<int>* regional,const char* modeLabel){
  ds_companion_backdrop(c,s.game,PD_TAB_DEX);
  const auto all=ds_dex_rows(s,0,regional);
  int seen=0,owned=0;
  for(const auto& entry:all){
    const bool caught = size_t(entry.species) < s.dexCaught.size() && s.dexCaught[entry.species];
    seen += caught || s.dexSeen[entry.species];
    owned += caught;
  }
  const int counts[]={int(all.size()),owned,seen-owned,int(all.size())-seen};
  const int listX = pd_ui_dex_filters(nullptr, c, counts, filter, filterFocus, modeLabel);
  const int listW = c->w - listX - 4;

  pd_list_box(c,listX,4,listW,c->h-8);
  constexpr int rowH=18;
  const auto entries=ds_dex_rows(s,filter,regional);
  const int rows=std::max(1,(c->h-19)/rowH),count=int(entries.size());
  if(!count){
    pd_text(c,listX+10,13,"NONE",PD_MUTED,PD_MUTED_SHADOW);
    return;
  }
  selected=std::clamp(selected,0,count-1);
  const int first=std::clamp(selected-rows/2,0,std::max(0,count-rows));
  for(int index=first;index<count && index<first+rows;++index){
    const auto [species,number]=entries[index];
    const size_t n=species;
    const int y=9+(index-first)*rowH;
    const bool caught = n < s.dexCaught.size() && s.dexCaught[n];
    const uint32_t* icon = icons && n < icons->size() && (*icons)[n].size() == 32 * 32 ? (*icons)[n].data() : nullptr;
    // PokedexScreen.kt's caughtBall / seenBall / seenBallNight body colors.
    const uint32_t mark=caught?0xE83028:pd_dark_look?0x3E4552:0xC6C5C5;
    pd_ui_dex_list_row(c, listX + 5, y, listW - 10, number, ds_species(n, s.game), caught || s.dexSeen[n],
                       mark, index == selected && filterFocus < 0, icon, 0);
  }
}

void ds_dex_entry(pd_canvas* c,const DsCompanion& s,int species,int number,const std::vector<std::vector<uint32_t>>& icons,
                  const DsDexDetails* details,const DsDexPage& page,const std::vector<DsDexEvolution>& evolutions,
                  const std::vector<DsGuideSpot>& spots,int row,int count,DsDexSection section,int detailRow){
  ds_companion_backdrop(c,s.game,PD_TAB_DEX);
  const auto n = size_t(species);
  const bool caught = n < s.dexCaught.size() && s.dexCaught[n];
  const bool front = page.side > 0 && page.sprite.size() == size_t(page.side * page.side);
  const uint32_t* picture = front ? page.sprite.data() :
    n < icons.size() && icons[n].size() == 32 * 32 ? icons[n].data() : nullptr;
  const int a = ds_species_type(species, 0, s.game), b = ds_species_type(species, 1, s.game);
  char types[48], size[48] = "", label[64];
  std::snprintf(types, sizeof types, "%s%s%s", pd_type_name(nullptr, a), a != b ? " / " : "", a != b ? pd_type_name(nullptr, b) : "");
  if (details && (details->heightDm || details->weightHg)) {
    const auto ht=details->heightDm, wt=details->weightHg;
    std::snprintf(size, sizeof size, "%u.%u m  %u.%u kg", ht/10, ht%10, wt/10, wt%10);
  }
  const pd_dex_card card{ds_species(species, s.game), types, size, details ? details->category.c_str() : nullptr, nullptr,
    picture, front ? page.side : 32, front ? 1 : 2, 0, number, row, count, caught,
    (1u << PD_DEX_SECTIONS) - 1, static_cast<pd_dex_section>(section)};
  const auto pane = pd_ui_dex_card(nullptr, c, &card);
  const int tx = pane.x - 1, tw = pane.w + 2, h = pane.h + 54;
  if(section==DsDexSection::Evolve){
    struct Context {const std::vector<DsDexEvolution>& links; int species; DsGame game;};
    const Context context{evolutions, species, s.game};
    const pd_dex_links links{&context, [](const void* data, int i, pd_dex_link_row* row){
      const auto& x = *static_cast<const Context*>(data);
      const auto& evo = x.links[i];
      const bool incoming = evo.to == x.species;
      std::snprintf(row->name, sizeof row->name, "%s  %s", incoming ? "FROM" : "TO",
                    ds_species(incoming ? evo.from : evo.to, x.game));
      const auto req = evolution_requirement(evo, x.game);
      std::snprintf(row->requirement, sizeof row->requirement, "%s", req.c_str());
    }, int(evolutions.size())};
    pd_ui_dex_links(c, pane, &links, detailRow);
  }else if(section==DsDexSection::Area){
    if(spots.empty())
      pd_text_wrap(c,tx+4,40,tw-8,15,3,"No ordinary wild encounters listed.",PD_MUTED,PD_MUTED_SHADOW);
    constexpr int rowH=46;
    const int visible=std::max(1,(h-54)/rowH);
    const int first=std::clamp(detailRow-visible/2,0,std::max(0,int(spots.size())-visible));
    const bool seasons=ds_unova(s.game);
    const unsigned all=seasons?15:7;
    const char* const times[]={"MORN","DAY","NIGHT"};
    const char* const months[]={"SPR","SUM","AUT","WIN"};
    for(int i=first;i<int(spots.size()) && i<first+visible;++i){
      const auto& spot=spots[i];
      const auto& e=spot.encounter;
      const int y=39+(i-first)*rowH;
      if(i==detailRow)pd_fill(c,tx+1,y,tw-2,rowH-1,pd_dark_look?PD_ROW_SELECTED:PD_LIST_FILL);
      const auto* place=ds_place(spot.map,s.game);
      if(*place=='?'){
        std::snprintf(label,sizeof label,"MAP %d",spot.map);
        place=label;
      }
      pd_text_fit(c,tx+4,y,tw-8,place,PD_LABEL,PD_LABEL_SHADOW);
      if(e.low==e.high)std::snprintf(label,sizeof label,"Lv%d  %d%%",e.low,e.chance);
      else std::snprintf(label,sizeof label,"Lv%d-%d  %d%%",e.low,e.high,e.chance);
      const int oddsW=pd_text_width(label);
      pd_text_fit(c,tx+4,y+15,tw-13-oddsW,ds_guide_method(e.method),PD_MUTED,PD_MUTED_SHADOW);
      pd_text_right(c,tx+tw-4,y+15,label,PD_VALUE,PD_VALUE_SHADOW);
      if(spot.variants!=all){
        std::string variants;
        for(int v=0;v<(seasons?4:3);++v)if(spot.variants&(1u<<v)){
          if(!variants.empty())variants+=" / ";
          variants+=seasons?months[v]:times[v];
        }
        pd_text_fit(c,tx+4,y+30,tw-8,variants.c_str(),PD_MUTED,PD_MUTED_SHADOW);
      }
    }
  }else if(section==DsDexSection::Moves){
    const DsMovesSource context{page, s.game};
    const pd_dex_moves_source source{&context, int(page.moves.size()), int(page.machines.size()),
      details != nullptr, details && details->hasMachines ? "TM / HM" : nullptr, ds_move_row};
    pd_ui_dex_moves(c, tx + 1, 39, tw - 2, h - 54, &source, detailRow);
  }else{
    int stats[6];
    if (details) {
      constexpr int order[] = {0, 1, 2, 4, 5, 3};
      for (int i = 0; i < 6; i++) stats[i] = details->stats[order[i]];
    }
    pd_ui_dex_info(c, pane, details ? details->description.c_str() : nullptr, details ? stats : nullptr, false);
  }
}
