// SPDX-License-Identifier: GPL-3.0-or-later
#include "battle_strip.h"
#include "data_gen.h"
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
  assert(argc==2);
  std::ifstream file(argv[1],std::ios::binary);
  ram.assign(std::istreambuf_iterator<char>(file),{});
  assert(ram.size()==0x400000);
  const auto* game=pk_nds_field_game("CPUE",1);
  auto view=[&]{
    return ds_read_platinum_battle(game,read32,pk_nds_field_state(game,read32,nullptr,0));
  };
  assert(view().panel==DsBattlePanel::Native);
  const auto manager=read32(read32(battle_fieldSystemPtr));
  const auto system=read32(read32(manager+battle_offChild)+battle_offAppData);
  const auto context=read32(system+battle_offBattleCtx);
  // The captured rival battle provides battlers; synthesize the transient panel.
  write32(system+0x19c,base+0x1000);
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
  std::vector<uint32_t> stage(512*384,0x123456);
  ds_draw_battle_strip(stage.data(),ds_platinum_battle_style,moves,"Turtwig",0,true,0);
  assert(stage[287*512]==0x123456 && stage[288*512]==0x29272d);
  // A move tile can be visible before its text layer finishes drawing.
  auto oneMove=moves;
  oneMove.moves={moves.moves[0],0,0,0};
  std::vector<uint32_t> lower(256*192,0xf6eaac);
  assert(!ds_platinum_has_move_art(lower.data(),oneMove));
  for(int x=26;x<34;++x)lower[41*256+x]=0x202018;
  assert(ds_platinum_has_move_art(lower.data(),oneMove));
  assert(!ds_platinum_has_move_art(lower.data(),actions));

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
}
