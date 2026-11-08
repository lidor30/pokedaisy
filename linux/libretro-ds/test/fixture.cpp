#include "companion.h"
#include "data_gen.h"
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
static void write8(uint32_t a,uint8_t v){assert(a>=0x02000000 && a<0x02400000);test_ram[a-0x02000000]=v;}
static void write32(uint32_t a,uint32_t v){assert(a>=0x02000000 && a<=0x023ffffc);std::memcpy(test_ram.data()+a-0x02000000,&v,4);}
int main(int argc,char** argv){
  assert(argc==2);std::ifstream file(argv[1],std::ios::binary);
  std::vector<uint8_t> ram{std::istreambuf_iterator<char>(file),{}};
  assert(ram.size()==4*1024*1024);
  auto s=ds_read_companion(ram.data(),ram.size());
  assert(s.valid && s.party.size()==1 && s.party[0].species==387 && s.party[0].level==6);
  const auto& turtwig=s.party.front();
  assert(turtwig.nature==6 && turtwig.friendship==75);
  assert((turtwig.stats==std::array<int,6>{24,14,13,9,11,13}));
  assert((turtwig.ivs==std::array<int,6>{30,14,10,10,19,26}));
  assert((turtwig.evs==std::array<int,6>{0,0,0,1,0,0}));
  assert(s.dexValid && s.seenCount>0 && !s.bag.empty());
  assert(!ds_read_companion(ram.data(),4).valid);
  std::vector<uint32_t> px(341*256);pd_canvas c;pd_canvas_init(&c,341,256,px.data());
  ds_party_view(&c,s,{},0,false,0);ds_party_view(&c,s,{},0,true,1);
  ds_bag_view(&c,s,0,0,true);ds_dex_view(&c,s,0,0);ds_map_view(&c,s,{},s.mapX,s.mapY);
  test_ram=ram;
  using namespace pd_ds_data;
  const uint32_t body=read32(saveDataPtr)+bodyOff;
  const uint32_t pocket=body+bagOff;
  write8(body+repelStepsOff,0);
  write32(pocket,79|(1u<<16));
  write32(pocket+4,77|(2u<<16));
  using R=DsRepelResult;
  auto use=[&](int item){return ds_use_repel(test_ram.data(),test_ram.size(),DsGame::Platinum,item,write8);};
  assert(use(79)==R::Used);
  assert(read32(pocket)==(77|(2u<<16)) && (read32(body+repelStepsOff)&255)==100);
  assert(use(77)==R::StillActive && read32(pocket)==(77|(2u<<16)));
  write8(body+repelStepsOff,0);
  assert(use(77)==R::Used && read32(pocket)==(77|(1u<<16)));
  assert((read32(body+repelStepsOff)&255)==250);
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
  const auto* b2=pk_nds_field_game("IREO",0);assert(b2);
  const uint32_t system=0x02300000,procs=0x02301000,proc=0x02302000,
    map=0x02303000,data=0x02304000,eventData=0x02305000;
  write32(b2->fieldSystemPtr,system);
  write32(system+b2->offProcSys,procs);
  write32(procs+b2->offCurrentProc,proc);
  write32(proc+b2->offProcOverlay,b2->fieldOverlay);
  write32(system+b2->offFieldMap,map);
  write32(system+b2->offEvent,0);
  write32(system+b2->offGameData,data);
  write32(data+b2->offEventData,eventData);
  write32(data+b2->offZone,64);
  const auto table=eventData+b2->offEncounterRates;
  const int32_t wildRates[]={64,0x1e140a05,0x00020408};
  pk_nds_field_set_rates(wildRates,1);
  write32(table,wildRates[1]);
  write32(table+4,0xab000000|wildRates[2]);
  auto wild=[&](bool off){return pk_nds_field_state(b2,read32,write32,off);};
  assert(wild(true)&PK_NDS_FIELD_WILD_HELD);
  assert(read32(table)==0 && read32(table+4)==0xab000000);
  assert(!(wild(false)&PK_NDS_FIELD_WILD_HELD));
  assert(read32(table)==uint32_t(wildRates[1]) && read32(table+4)==(0xab000000|wildRates[2]));
  wild(true);
  pk_nds_field_reset_wild(); // The emulator just restored a state whose rates are zero.
  wild(false);
  assert(read32(table)==uint32_t(wildRates[1]) && read32(table+4)==(0xab000000|wildRates[2]));
  pk_nds_field_set_rates(nullptr,0);
  puts("PASS Platinum top-only and touch menu classification");
  puts("PASS captured Platinum RAM and existing companion views");
}
