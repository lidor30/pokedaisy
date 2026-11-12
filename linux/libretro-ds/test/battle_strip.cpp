// SPDX-License-Identifier: GPL-3.0-or-later
#include "battle_strip.h"
#include "data_gen.h"
#include "companion.h"
extern "C" {
#include "pd_snapshot.h"
}
#include <algorithm>
#include <cassert>
#include <fstream>
#include <iterator>
#include <vector>

namespace {
constexpr uint32_t base=0x02000000;
std::vector<uint8_t> ram;
uint32_t read32(uint32_t address){
  if(address<base || address-base>ram.size()-4)return 0;
  const auto* p=ram.data()+address-base;
  return uint32_t(p[0])|(uint32_t(p[1])<<8)|(uint32_t(p[2])<<16)|(uint32_t(p[3])<<24);
}
void write32(uint32_t address,uint32_t value){
  assert(address>=base && address-base<=ram.size()-4);
  for(int i=0;i<4;++i)ram[address-base+i]=value>>(8*i);
}
}

int main(int argc,char** argv){
  using namespace pd_ds_data;
  assert(argc==3);
  std::ifstream file(argv[1],std::ios::binary);
  ram.assign(std::istreambuf_iterator<char>(file),{});
  assert(ram.size()==0x400000);
  const auto* game=pk_nds_field_game("CPUE",1);
  auto view=[&]{
    return ds_read_battle(game,read32,pk_nds_field_state(game,read32,nullptr,0));
  };
  assert(view().panel==DsBattlePanel::Native);
  // The info pane reads a busy battle too, with no actionable touch panel.
  pd_snapshot info{};
  const auto platinumSave=ds_read_companion(ram.data(),ram.size());
  ds_read_battle_companion(game,read32,ram.data(),ram.size(),platinumSave,info);
  assert(info.valid && info.inBattle && info.isTrainer && !info.isDouble);
  assert(info.battlers[0].species==387 && info.battlers[0].pp[0]==35);
  assert(info.battlers[1].species==390 && info.foeCount==1 && info.foeActive==0);
  ds_read_battle_companion(game,read32,ram.data(),1,platinumSave,info);
  assert(!info.inBattle && !info.valid);
  const auto manager=read32(read32(battle_fieldSystemPtr));
  const auto system=read32(read32(manager+battle_offChild)+battle_offAppData);
  const auto context=read32(system+battle_offBattleCtx);
  // The captured rival battle provides battlers; synthesize the transient panel.
  write32(system+0x19c,base+0x1000);
  write32(base+0x1000,system); // BattleInput names its BattleSystem while the battle's touch screen is up
  write32(base+0x14d0,base+0x2000);
  write32(context+8,PK_NDS_BATTLE_SELECTING);
  write32(context,1);
  const auto actions=view();
  assert(actions.panel==DsBattlePanel::Actions && actions.species==387 && !actions.enabled(3));
  DsBattleStrip strip;
  strip.select(actions,0,0,true,false);
  strip.select(actions,1,0,true,false);
  for(int i=0;i<3;++i){
    const auto tap=strip.tick();
    assert(tap.down && tap.x==128 && tap.y==90);
  }
  for(int i=0;i<3;++i)assert(!strip.tick().down);
  write32(context,4);
  const auto moves=view();
  assert(moves.panel==DsBattlePanel::Moves && moves.moves[0]==33 && moves.pp[0]==35);
  strip.select(moves,0,0,false,true);
  assert(strip.cancelingMove && strip.moveCommitFrames==18);
  for(int i=0;i<5;++i)strip.tick();
  strip.select(moves,0,0,true,false);
  assert(strip.tick().down && strip.moveCommitFrames==17 && !strip.cancelingMove);
  for(int i=0;i<17;++i)strip.tick();
  assert(strip.moveCommitFrames==0);
  std::vector<uint32_t> stage(512*384,0x123456);
  ds_draw_battle_strip(stage.data(),ds_platinum_battle_style,moves,"Turtwig",0,true,0);
  assert(stage[273*512]==0x123456 && stage[274*512]==0x000000);
  // A move tile can be visible before its text layer finishes drawing.
  auto oneMove=moves;
  oneMove.moves={moves.moves[0],0,0,0};
  std::vector<uint32_t> lower(256*192,0xf6eaac);
  assert(!ds_platinum_has_move_art(lower.data(),oneMove));
  for(int x=26;x<35;++x)lower[41*256+x]=0x202018;
  assert(ds_platinum_has_move_art(lower.data(),oneMove));
  assert(!ds_platinum_has_move_art(lower.data(),actions));
  lower[41*256+27]=0x958f83; // Native shadow directly beside an ink pixel.
  lower[56*256+60]=0x384038; // Native PP ink.
  ds_draw_battle_strip(stage.data(),ds_platinum_battle_style,oneMove,"Turtwig",0,true,0,lower.data());
  for(int y=0;y<2;++y)for(int x=0;x<2;++x){
    assert(stage[(279+y)*512+37+x]==0x202018);
    assert(stage[(279+y)*512+39+x]==0x958f83);
    assert(stage[(301+y)*512+109+x]==0x384038);
  }

  // The lower screen can show the tiles a few frames before their lettering.
  std::vector<uint32_t> commandsArt(256*192,0);
  commandsArt[54*256+65]=0xee3839;
  commandsArt[160*256+21]=0xe69929;
  commandsArt[160*256+209]=0x5ab229;
  assert(!ds_platinum_has_command_art(commandsArt.data()));
  for(int x=0;x<8;++x){
    commandsArt[79*256+111+x]=0xffffff;
    commandsArt[165*256+30+x]=0xffffff;
    commandsArt[165*256+192+x]=0xffffff;
  }
  assert(ds_platinum_has_command_art(commandsArt.data()));

  for(unsigned native:{5u,8u,10u,15u}){
    write32(context,native);
    assert(view().panel==DsBattlePanel::Native);
  }
  write32(context,1);
  write32(system+battle_offBattleType,3);
  assert(view().panel==DsBattlePanel::Native);
  ds_read_battle_companion(game,read32,ram.data(),ram.size(),platinumSave,info);
  assert(!info.inBattle);

  // Exercise the shared Gen 4 reader with Johto's exact field profile.
  const auto* johto=pk_nds_field_game("IPKE",0);
  assert(johto && johto==pk_nds_field_game("IPGE",0));
  write32(johto->fieldSystemPtr,read32(battle_fieldSystemPtr));
  write32(johto->battleTemplate,read32(battle_battleTemplate));
  write32(system+battle_offBattleType,0);
  const auto johtoView=ds_read_battle(johto,read32,pk_nds_field_state(johto,read32,nullptr,0));
  assert(johtoView.panel==DsBattlePanel::Actions && johtoView.enabled(3));
  const auto* goldStyle=ds_battle_style(heartGoldSha1);
  const auto* silverStyle=ds_battle_style(soulSilverSha1);
  assert(goldStyle&&silverStyle&&goldStyle!=silverStyle);
  ds_draw_battle_strip(stage.data(),*goldStyle,johtoView,"Turtwig",0,true,0);
  assert(stage[298*512+6]==0xfbdb69);
  ds_draw_battle_strip(stage.data(),*silverStyle,johtoView,"Turtwig",0,true,0);
  assert(stage[298*512+6]==0xdbebfb);

  // Black's capture has the battler, setup and adapter. The discarded
  // touch UI structures are reconstructed to exercise the shared scene gate.
  std::ifstream blackFile(argv[2],std::ios::binary);
  ram.assign(std::istreambuf_iterator<char>(blackFile),{});
  assert(ram.size()==0x400000);
  const auto* black=pk_nds_field_game("IRBO",0);
  const auto blackSave=ds_read_companion(ram.data(),ram.size(),DsGame::Black);
  ds_read_battle_companion(black,read32,ram.data(),ram.size(),blackSave,info);
  assert(info.valid && info.inBattle && !info.isTrainer && !info.isDouble);
  assert(info.battlers[0].species==381 && info.battlers[0].type1==16);
  assert(info.battlers[1].species==506 && info.foeCount==0);
  const auto gs=read32(black->fieldSystemPtr);
  const auto proc=read32(read32(gs+8)+8);
  const auto client=read32(read32(proc+0x14)+0x10);
  const auto ui=base+0x1000, scene=base+0x2000, input=base+0x3000;
  write32(client+0x54,ui);
  write32(ui+0x180,scene);
  write32(scene+0xb0,input);
  write32(scene+0x60,1);
  write32(input+0x58,1);
  auto unovaView=[&]{return ds_read_battle(black,read32,pk_nds_field_state(black,read32,nullptr,0));};
  const auto blackActions=unovaView();
  assert(blackActions.panel==DsBattlePanel::Actions && blackActions.species==381 && blackActions.gen5);
  DsBattleStrip unovaStrip;
  unovaStrip.select(blackActions,0,0,true,false);
  auto tap=unovaStrip.tick();
  assert(tap.down&&tap.x==128&&tap.y==80);
  write32(input+0x58,2);
  const auto blackMoves=unovaView();
  assert(blackMoves.panel==DsBattlePanel::Moves && blackMoves.moves[1]==473 && blackMoves.pp[1]>0);
  unovaStrip={};
  for(int i=0;i<3;++i){
    unovaStrip.select(blackMoves,0,0,true,false);
    assert(!unovaStrip.tick().down);
  }
  unovaStrip.select(blackMoves,1,0,true,false);
  tap=unovaStrip.tick();
  assert(tap.down&&tap.x==191&&tap.y==56);
  unovaStrip={};
  for(int i=0;i<3;++i)unovaStrip.select(blackMoves,0,0,false,false);
  unovaStrip.select({},0,0,false,false);
  for(int i=0;i<3;++i){
    unovaStrip.select(blackMoves,0,0,false,true);
    assert(!unovaStrip.tick().down);
  }
  unovaStrip.select(blackMoves,0,0,false,true);
  tap=unovaStrip.tick();
  assert(tap.down&&tap.x==230&&tap.y==175);
  write32(input+8,1);
  assert(unovaView().panel==DsBattlePanel::Native);
  write32(input+8,0);
  write32(read32(proc+0x10)+4,1);
  assert(unovaView().panel==DsBattlePanel::Native);
  const auto* bwStyle=ds_battle_style(blackSha1);
  const auto* bw2Style=ds_battle_style(black2Sha1);
  assert(bwStyle && bwStyle==ds_battle_style(whiteSha1));
  assert(bw2Style && bw2Style==ds_battle_style(white2Sha1) && bwStyle!=bw2Style);
  assert(!ds_battle_style("unknown"));
  // The games' own cursor: red brackets on the picked plate, in both Unova styles, which draw differently.
  auto cursorPixels=[&]{return std::count(stage.begin(),stage.end(),0xf0141cu);};
  ds_draw_battle_strip(stage.data(),*bwStyle,blackMoves,"Latios",0,true,0);
  assert(stage[276*512]==0x0c3c34 && stage[376*512]==0x4d201c && cursorPixels()>100);
  const auto bw=stage;
  ds_draw_battle_strip(stage.data(),*bw2Style,blackMoves,"Latios",0,true,0);
  assert(stage!=bw && stage[278*512]==0x0c3851 && stage[376*512]==0x41697d && cursorPixels()>100);
  // No cursor while a tap is in flight.
  std::fill(stage.begin(),stage.end(),0x808080u);
  ds_draw_battle_strip(stage.data(),*bwStyle,blackMoves,"Latios",0,false,0);
  assert(cursorPixels()==0);
}
