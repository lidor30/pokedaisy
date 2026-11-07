// SPDX-License-Identifier: GPL-3.0-or-later
#include "battle_strip.h"
#include "data_gen.h"
#include <algorithm>

bool DsBattleView::enabled(int slot) const {
  if(slot<0 || slot>=4)return false;
  if(panel==DsBattlePanel::Actions)return slot!=3 || !trainer;
  return panel==DsBattlePanel::Moves && moves[slot]>0 && pp[slot]>0;
}

namespace {
using namespace pd_ds_data;
bool in_ram(uint32_t addr,unsigned last=0){
  return !(addr&3) && addr>=0x02000000 && addr<=0x023ffffc-last;
}

DsBattleView read_gen4(const PkNdsFieldGame* game,uint32_t (*read32)(uint32_t)) {
  const auto fs=read32(game->fieldSystemPtr);
  if(!in_ram(fs,game->offProcessManager))return {};
  const auto manager=read32(fs+game->offProcessManager);
  if(!in_ram(manager,battle_offChild))return {};
  const auto child=read32(manager+battle_offChild);
  const auto init=read32(game->battleTemplate);
  if(!init || !in_ram(child,battle_offAppData) || read32(child)!=init)return {};
  const auto system=read32(child+battle_offAppData);
  if(!in_ram(system,game->offBattleContext))return {};
  const auto type=read32(system+battle_offBattleType);
  if(type&2)return {};
  const auto ctx=read32(system+game->offBattleContext);
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
  return view;
}

DsBattleView read_gen5(const PkNdsFieldGame* game,uint32_t (*read32)(uint32_t)) {
  const auto gs=read32(game->fieldSystemPtr);
  if(!in_ram(gs,game->offProcSys))return {};
  const auto ps=read32(gs+game->offProcSys);
  if(!in_ram(ps,game->offCurrentProc))return {};
  const auto proc=read32(ps+game->offCurrentProc);
  if(!in_ram(proc,game->offProcWork) || read32(proc+game->offProcOverlay)!=unsigned(game->battleOverlay))return {};
  // Gen5Reader.Gen5Battle: the setup's competitor and rule, and the
  // main module's first active player battler.
  const auto setup=read32(proc+0x10), main=read32(proc+game->offProcWork);
  if(!in_ram(setup,4) || read32(setup+4)!=0 || !in_ram(main,0xcc))return {};
  const auto mon=read32(main+0xcc), client=read32(main+game->offClient);
  if(!in_ram(mon,0x130) || !in_ram(client,0x54))return {};
  const auto view=read32(client+0x54);
  const bool sequel=game->battleOverlay==167;
  const unsigned scdOffset=sequel?0x1c4:0x180, inputOffset=sequel?0xbc:0xb0;
  if(!in_ram(view,scdOffset))return {};
  const auto scd=read32(view+scdOffset);
  if(!in_ram(scd,inputOffset))return {};
  const auto input=read32(scd+inputOffset);
  if(!in_ram(input,0x58))return {};
  // The shared field reader already checked the command, wait flag, and slide.
  const auto panel=read32(input+0x58)&0xff;
  if(panel!=1 && panel!=2)return {};
  DsBattleView result;
  result.gen5=true;
  result.trainer=read32(setup)!=0;
  result.species=read32(mon+0x0c)&0xffff;
  if(result.species<1 || result.species>=int(std::size(unovaSpecies)))return {};
  result.panel=panel==1?DsBattlePanel::Actions:DsBattlePanel::Moves;
  for(int slot=0;slot<4;++slot){
    const auto offset=0x104+slot*14;
    const auto id=(read32(mon+(offset&~3u))>>((offset&3)*8))&0xffff;
    if(id>=std::size(unovaMoves))return {};
    const auto pp=offset+2;
    result.moves[slot]=id;
    result.pp[slot]=(read32(mon+(pp&~3u))>>((pp&3)*8))&0xff;
  }
  return result;
}
}

DsBattleView ds_read_battle(const PkNdsFieldGame* game,uint32_t (*read32)(uint32_t),int scene) {
  if(!game || game->battleManagerPtr || !(scene&PK_NDS_FIELD_TOUCH_BATTLE_MENU))return {};
  const auto view=game->gen==5?read_gen5(game,read32):read_gen4(game,read32);
  if(view.panel==DsBattlePanel::Moves &&
     std::none_of(view.pp.begin(),view.pp.end(),[](int n){return n>0;}))return {};
  return view;
}

const char* ds_battle_species(const DsBattleView& view){
  return view.gen5?unovaSpecies[view.species]:species[view.species];
}

void DsBattleStrip::select(const DsBattleView& view,int dx,int dy,bool accept,bool back) {
  if(view.panel!=panel && view.panel==DsBattlePanel::Moves){
    moveFocus=0;
    moveReadyFrames=0;
    while(moveFocus<3 && !view.enabled(moveFocus))++moveFocus;
  }
  panel=view.panel;
  if(panel==DsBattlePanel::Native || tapFrames)return;
  const bool moves=panel==DsBattlePanel::Moves;
  int& focus=moves?moveFocus:actionFocus;
  if(!view.enabled(focus))for(int i=0;i<4;++i)if(view.enabled(i)){focus=i;break;}
  const int next=std::clamp(focus%2+dx,0,1)+2*std::clamp(focus/2+dy,0,1);
  if(view.enabled(next))focus=next;
  // Gen 5 briefly reports a ready move panel between two native slides.
  // Wait for it to stay ready before injecting a tap, as Android does.
  if(moves&&view.gen5&&moveReadyFrames<4 && ++moveReadyFrames<4)return;
  // Native touch targets from Android's NdsBattleTaps.Layout.
  static constexpr DsBattleTap actions[]={{true,128,90},{true,40,170},{true,215,170},{true,128,170}};
  static constexpr DsBattleTap cards[]={{true,64,42},{true,192,42},{true,64,113},{true,192,113}};
  static constexpr DsBattleTap unovaActions[]={{true,128,80},{true,30,172},{true,222,172},{true,128,178}};
  static constexpr DsBattleTap unovaCards[]={{true,63,56},{true,191,56},{true,63,104},{true,191,104}};
  if(moves && back){
    tap=view.gen5?DsBattleTap{true,230,175}:DsBattleTap{true,128,175};
    moveCommitFrames=18;
    cancelingMove=true;
  }
  else if(accept && view.enabled(focus)){
    tap=moves?(view.gen5?unovaCards:cards)[focus]:(view.gen5?unovaActions:actions)[focus];
    // A single battle briefly retains the old move panel while resolving
    // the target. Let an actual native prompt through if it persists.
    if(moves){moveCommitFrames=18;cancelingMove=false;}
  }
  else return;
  tapFrames=5;
}
DsBattleTap DsBattleStrip::tick(){
  if(moveCommitFrames>0)--moveCommitFrames;
  return tapFrames && tapFrames-->2?tap:DsBattleTap{};
}
