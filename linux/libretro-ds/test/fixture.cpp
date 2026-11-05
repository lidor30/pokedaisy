#include "companion.h"
#include "pk_nds_field.h"
#include <cstring>
#include "party_view.h"
#include "bag_view.h"
#include "dex_view.h"
#include "map_view.h"
#include <cassert>
#include <fstream>
#include <iterator>
#include <cstdio>
static std::vector<uint8_t> test_ram;
static uint32_t read32(uint32_t a){uint32_t v=0;if(a>=0x02000000 && a<=0x023ffffc)std::memcpy(&v,test_ram.data()+a-0x02000000,4);return v;}
static void write32(uint32_t a,uint32_t v){assert(a>=0x02000000 && a<=0x023ffffc);std::memcpy(test_ram.data()+a-0x02000000,&v,4);}
int main(int argc,char** argv){
  assert(argc==2);std::ifstream file(argv[1],std::ios::binary);
  std::vector<uint8_t> ram{std::istreambuf_iterator<char>(file),{}};
  assert(ram.size()==4*1024*1024);
  auto s=ds_read_companion(ram.data(),ram.size());
  assert(s.valid && s.party.size()==1 && s.party[0].species==387 && s.party[0].level==6);
  assert(s.dexValid && s.seenCount>0 && !s.bag.empty());
  assert(!ds_read_companion(ram.data(),4).valid);
  std::vector<uint32_t> px(341*171);pd_canvas c;pd_canvas_init(&c,341,171,px.data());
  ds_party_view(&c,s,{},0,false,0);ds_party_view(&c,s,{},0,true,1);
  ds_bag_view(&c,s,0,0,true);ds_dex_view(&c,s,0,0);ds_map_view(&c,s,{},s.mapX,s.mapY);
  test_ram=ram;
  const auto* g=pk_nds_field_game("CPUE",1);assert(g);
  uint32_t fs=0x023e0000,pm=0x023e1000;
  write32(g->fieldSystemPtr,fs);write32(fs+g->offProcessManager,pm);
  // Synthetic menu states on a copy of a real RAM capture, never the ROM.
  uint32_t child=0x023f0000;write32(pm+4,child);
  write32(child,0x0207E0B9); // upstream's Platinum party app
  auto bits=pk_nds_field_state(g,read32,nullptr,0);
  assert((bits&PK_NDS_FIELD_TOP_ONLY) && !(bits&PK_NDS_FIELD_TOUCH));
  write32(child,0x021D0D81); // upstream's Platinum options app
  assert(pk_nds_field_state(g,read32,nullptr,0)&PK_NDS_FIELD_TOP_ONLY);
  write32(child,0x02012345); // another menu app requires both screens
  assert(pk_nds_field_state(g,read32,nullptr,0)&PK_NDS_FIELD_TOUCH);
  assert(pk_nds_field_game("DSPT",0)==nullptr);
  puts("PASS Platinum top-only and touch menu classification");
  puts("PASS captured Platinum RAM and existing companion views");
}
