// GPL-3.0. DS data adapter for the established portable companion renderer.
#include "party_view.h"
#include "data_gen.h"
#include <algorithm>
#include <cstdio>
extern "C" {
#include "pd_ui.h"
#include "pd_tables.h"
}
void ds_party_view(pd_canvas* c,const DsCompanion& s,const std::vector<std::vector<uint32_t>>& icons,int selected,bool summary,int page){
  pd_snapshot view{};view.valid=s.valid;view.partyCount=std::min(6,int(s.party.size()));
  for(int i=0;i<view.partyCount;i++){
    const auto& m=s.party[i];auto& out=view.party[i];out.species=m.species;
    out.level=m.level;out.hp=m.hp;out.maxHp=m.maxHp;out.status=m.status;
    out.gender=static_cast<pd_gender>(m.gender);
    std::snprintf(out.nickname,sizeof out.nickname,"%s",m.nickname.c_str());
    for(int j=0;j<4;j++){out.moves[j]=m.moves[j];out.pp[j]=m.pp[j];}
  }
  struct Context {const DsCompanion& s;const pd_snapshot& view;const std::vector<std::vector<uint32_t>>& icons;std::string text; } ctx{s,view,icons,{}};
  pd_party_source source{};source.ctx=&ctx;
  source.egg=[](void* data,const pd_mon* m){auto& x=*static_cast<Context*>(data);return x.s.party[m-x.view.party].egg;};
  source.species=[](void* data,int id){return ds_species(id,static_cast<Context*>(data)->s.game);};
  source.move=[](void* data,int id){return ds_move(id,static_cast<Context*>(data)->s.game);};
  source.moveType=[](void* data,int id){return ds_move_type(id,static_cast<Context*>(data)->s.game);};
  source.icon=[](void* data,pd_canvas* c,const pd_mon* m,int x,int y,int scale,bool faded){
    auto& ctx=*static_cast<Context*>(data);const auto& mon=ctx.s.party[m-ctx.view.party];
    int id=mon.egg?ds_egg_icon(ctx.s.game):mon.species;
    if(id<1 || size_t(id)>=ctx.icons.size() || ctx.icons[id].size()!=1024)return false;
    for(int yy=0;yy<32;yy++)for(int xx=0;xx<32;xx++){
      uint32_t px=ctx.icons[id][yy*32+xx];if(!(px>>24))continue;
      px&=0xffffff;if(faded)px=(px&0xfefefe)>>1;
      pd_fill(c,x+xx*scale,y+yy*scale,scale,scale,px);
    }return true;
  };
  source.info=[](void* data,const pd_mon* m,int row)->const char*{
    auto& ctx=*static_cast<Context*>(data);const auto& mon=ctx.s.party[m-ctx.view.party];
    const auto game=ctx.s.game;
    int a=ds_species_type(mon.species,0,game),b=ds_species_type(mon.species,1,game);
    if(row==0)ctx.text=std::string(ds_species(mon.species,game))+"  "+pd_type_name(nullptr,a)+(a!=b?std::string("/")+pd_type_name(nullptr,b):"");
    else if(row==1)ctx.text=std::string("ITEM: ")+(mon.heldItem?ds_item(mon.heldItem,game):"NONE");
    else if(ds_unova(game))ctx.text="EXP "+std::to_string(mon.experience);
    else ctx.text=std::string(ds_nature(mon.nature))+"  EXP "+std::to_string(mon.experience);
    return ctx.text.c_str();
  };
  pd_ui ui{};ui.partySource=&source;ui.summarySlot=summary && view.partyCount?std::clamp(selected,0,view.partyCount-1):-1;
  ui.summaryPage=page;ui.pressedId=-1;pd_game game{};
  pd_ui_party_view(&ui,c,&game,&view,selected);
}
