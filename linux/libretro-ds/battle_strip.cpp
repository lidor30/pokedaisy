// SPDX-License-Identifier: GPL-3.0-or-later
#include "battle_strip.h"
#include "data_gen.h"
#include <algorithm>

bool DsBattleView::enabled(int slot) const {
  if(slot<0 || slot>=4)return false;
  if(panel==DsBattlePanel::Actions)return slot!=3 || !trainer;
  return panel==DsBattlePanel::Moves && moves[slot]>0 && pp[slot]>0;
}

DsBattleView ds_read_platinum_battle(const PkNdsFieldGame* game,uint32_t (*read32)(uint32_t),int scene) {
  using namespace pd_ds_data;
  auto in_ram=[](uint32_t addr,unsigned last=0){
    return !(addr&3) && addr>=0x02000000 && addr<=0x023ffffc-last;
  };
  if(!game || !(scene&PK_NDS_FIELD_TOUCH_BATTLE_MENU))return {};
  const auto fs=read32(battle_fieldSystemPtr);
  if(!in_ram(fs))return {};
  const auto manager=read32(fs);
  if(!in_ram(manager,battle_offChild))return {};
  const auto child=read32(manager+battle_offChild);
  const auto init=read32(battle_battleTemplate);
  if(!init || !in_ram(child,battle_offAppData) || read32(child)!=init)return {};
  const auto system=read32(child+battle_offAppData);
  if(!in_ram(system,battle_offBattleCtx))return {};
  const auto type=read32(system+battle_offBattleType);
  if(type&2)return {};
  const auto ctx=read32(system+battle_offBattleCtx);
  if(!in_ram(ctx,battle_offBattleMons+0x68) ||
     pk_nds_battle_command(game,read32)!=PK_NDS_BATTLE_SELECTING)return {};
  const auto step=read32(ctx)&0xff;
  if(step<1 || step>4)return {};
  // BattleMon fields from Android's Gen4Reader.decodeGen4BattleMon.
  const auto mon=ctx+battle_offBattleMons;
  DsBattleView view;
  view.species=read32(mon)&0xffff;
  if(view.species<1 || view.species>=int(std::size(species)))return {};
  view.personality=read32(mon+0x68);
  view.trainer=type&1;
  view.panel=step<=2?DsBattlePanel::Actions:DsBattlePanel::Moves;
  const auto pp=read32(mon+0x2c);
  for(int slot=0;slot<4;++slot){
    const auto move=(read32(mon+0x0c+4*(slot/2))>>(16*(slot%2)))&0xffff;
    if(move>=std::size(moves))return {};
    view.moves[slot]=move;
    view.pp[slot]=(pp>>(8*slot))&0xff;
  }
  if(view.panel==DsBattlePanel::Moves &&
     std::none_of(view.pp.begin(),view.pp.end(),[](int n){return n>0;}))return {};
  return view;
}

void DsBattleStrip::select(const DsBattleView& view,int dx,int dy,bool accept,bool back) {
  if(view.panel!=panel && view.panel==DsBattlePanel::Moves){
    moveFocus=0;
    while(moveFocus<3 && !view.enabled(moveFocus))++moveFocus;
  }
  panel=view.panel;
  if(panel==DsBattlePanel::Native || tapFrames)return;
  const bool moves=panel==DsBattlePanel::Moves;
  int& focus=moves?moveFocus:actionFocus;
  if(!view.enabled(focus))for(int i=0;i<4;++i)if(view.enabled(i)){focus=i;break;}
  const int next=std::clamp(focus%2+dx,0,1)+2*std::clamp(focus/2+dy,0,1);
  if(view.enabled(next))focus=next;
  // The native Gen 4 touch targets from Android's NdsBattleTaps.Layout.
  static constexpr DsBattleTap actions[]={{true,128,90},{true,40,170},{true,215,170},{true,128,170}};
  static constexpr DsBattleTap cards[]={{true,64,42},{true,192,42},{true,64,113},{true,192,113}};
  if(moves && back)tap={true,128,175};
  else if(accept && view.enabled(focus))tap=moves?cards[focus]:actions[focus];
  else return;
  tapFrames=5;
}
DsBattleTap DsBattleStrip::tick(){
  return tapFrames && tapFrames-->2?tap:DsBattleTap{};
}
