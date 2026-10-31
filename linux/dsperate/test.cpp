// GPL-3.0. Real core and captured-RAM tests, never fake application screens.
#include "core/nds.h"
#include "core/state/state.h"
#include "companion.h"
#include "battle_view.h"
#include "pk_nds_field.h"
#include <filesystem>
#include "map_art.h"
#include <cassert>
#include <fstream>
#include <cstdio>
#include <cstring>
extern "C" {
#include "../hud.h"
#include "../quick_menu.h"
}
static std::vector<uint8_t> battleRam;
static uint32_t battle_read32(uint32_t addr){
  if(addr<0x02000000 || addr>0x023ffffc)return 0;
  uint32_t value;std::memcpy(&value,battleRam.data()+addr-0x02000000,4);return value;
}
int main(int argc,char** argv){
  assert(!ds_battle_view(0,true,true,false).bottom);
  assert(ds_battle_view(1,true,true,false).bottom);
  assert(!ds_battle_view(1,true,false,true).bottom);
  assert(ds_battle_view(1,true,false,false,1).bottom);
  assert(!ds_battle_view(1,true,true,false,0).bottom);
  assert(ds_battle_view(1,false,false,true).bottom);
  std::puts("PASS automatic screen transitions and temporary manual override");
  assert(argc==3 || argc==4);std::ifstream file(argv[1],std::ios::binary);
  std::vector<uint8_t> ram{std::istreambuf_iterator<char>(file),{}};
  assert(ram.size()==4*1024*1024);
  auto battlePath=std::filesystem::path(argv[1]).parent_path().parent_path()/"platinum_battle"/"mainram.bin";
  std::ifstream capturedBattle(battlePath,std::ios::binary);
  battleRam={std::istreambuf_iterator<char>(capturedBattle),{}};
  assert(battleRam.size()==4*1024*1024);
  const auto* game=pk_nds_field_game("CPUE",1);assert(game && game->offBattleContext==0x30 && game->offCommand==8);
  assert(pk_nds_field_state(game,battle_read32,nullptr,0)&PK_NDS_FIELD_BATTLE);
  auto fs=battle_read32(game->fieldSystemPtr),pm=battle_read32(fs+game->offProcessManager),child=battle_read32(pm+4);
  auto system=battle_read32(child+0x1c),context=battle_read32(system+game->offBattleContext);
  assert(context>=0x02000000 && context<=0x023ffff0);
  // The captured fixture omits the controller head; seed just that test field.
  uint32_t command=PK_NDS_BATTLE_SELECTING;
  std::memcpy(battleRam.data()+context-0x02000000+game->offCommand,&command,4);
  assert(pk_nds_battle_command(game,battle_read32)==pk_nds_battle_selecting(game));
  std::puts("PASS captured battle pointer chain and Platinum controller metadata");
  auto s=ds_read_companion(ram.data(),ram.size());
  assert(s.valid && s.party.size()==1);
  assert(s.party[0].species==387 && s.party[0].level==6);
  pd_snapshot hs{};hs.valid=true;hs.partyCount=1;
  hs.party[0].species=s.party[0].species;hs.party[0].level=s.party[0].level;
  hs.party[0].hp=s.party[0].hp;hs.party[0].maxHp=s.party[0].maxHp;
  std::vector<uint32_t> hpixels(341*256,QUICK_CLEAR);pd_canvas hc;pd_canvas_init(&hc,341,256,hpixels.data());
  int icons=0;
  hud_party_draw(&hc,&hs,&icons,[](pd_canvas*,void* ctx,const pd_mon* mon,int,int,bool){
    assert(mon->species==387 && mon->level==6);++*static_cast<int*>(ctx);
  });
  assert(icons==1);quick_finish(&hc);
  bool drawn=false;for(auto p:hpixels)drawn|=p!=0;assert(drawn);
  std::puts("PASS shared party HUD with captured Gen 4 data and backend asset callback");
  assert(s.dexValid && s.seenCount==2 && s.caughtCount==1);
  assert(s.dexSeen[387] && s.dexSeen[390] && s.dexCaught[387] && !s.dexCaught[390]);
  assert(s.dexSeen.size()==494);

  assert(s.mapX==3 && s.mapY==27); // Player's house: use its outside exit block.
  assert(ds_map_section(s.mapX,s.mapY)==1);
  assert(std::strcmp(ds_map_name(1),"Twinleaf Town")==0);
  assert(ds_map_section(0,0)==-1 && ds_map_section(-1,0)==-1 && ds_map_section(30,0)==-1);
  assert(ds_map_art({}).background.empty());
  assert(ds_party_icons({}).empty());
  if(argc==4){
    std::ifstream cart(argv[3],std::ios::binary);std::vector<uint8_t> bytes{std::istreambuf_iterator<char>(cart),{}};
    auto icons=ds_party_icons(bytes);assert(icons.size()==495);
    for(int species: {1,387,412,493,494}){
      assert(icons[species].size()==1024);bool clear=false,opaque=false;
      for(auto p:icons[species]){clear|=p==0;opaque|=(p>>24)==255;}assert(clear && opaque);
    }
    std::puts("PASS real ROM party icons including Gen 4 species, Burmy and egg");
    auto art=ds_map_art(bytes);assert(art.width==216 && art.height==168);
    assert(art.background.size()==216*168 && art.blocks.size()==21 && art.heads.size()==2);
    bool varied=false;for(auto px:art.background)varied|=px!=art.background[0];assert(varied);
    std::puts("PASS real ROM Town Map tiles/palettes/cells and player heads decoded");
  }
  std::puts("PASS map grid blank/bounds and captured indoor exit-location fallback");
  bool potion=false,balls=false;
  for(auto i:s.bag){potion|=i.id==17 && i.count==3;balls|=i.id==4 && i.count==5;}
  assert(potion && balls);
  assert(std::strcmp(ds_species(387),"TURTWIG")==0);
  std::puts("PASS captured upstream Platinum RAM fixture: party, checksum/decryption and bag");
  // Truncated/corrupt input must not become a valid companion snapshot.
  assert(!ds_read_companion(ram.data(),16).valid);
  auto invalid=ram;std::memset(invalid.data(),0,invalid.size());
  assert(!ds_read_companion(invalid.data(),invalid.size()).valid);
  auto nds=std::make_unique<ds::NDS>();
  nds->bus.dma_write32(ds::Cpu::ARM9,0x02000000,0x12345678);
  assert(nds->bus.dma_read32(ds::Cpu::ARM9,0x02000000)==0x12345678);
  nds->io.set_buttons(1);assert(!(nds->io.keyinput&1));nds->io.set_buttons(0);assert(nds->io.keyinput&1);
  auto boot=std::make_unique<ds::NDS>();std::string error;
  assert(boot->load_bios("","","",{},&error));assert(boot->load_rom(argv[2]));
  boot->setup_direct_boot();boot->run_frame();boot->run_frame();boot->gpu.quiesce();
  assert(boot->gpu.framebuffer(0)[96*256+128]==0xff00fb00u);
  boot->bus.dma_write32(ds::Cpu::ARM9,0x02010000,0x12345678);
  ds::state::Writer writer;assert(boot->save_state(writer,error));
  boot->bus.dma_write32(ds::Cpu::ARM9,0x02010000,0);
  ds::state::Reader reader(writer.data().data(),writer.data().size());
  assert(boot->load_state(reader,error));
  assert(boot->bus.dma_read32(ds::Cpu::ARM9,0x02010000)==0x12345678);
  std::puts("PASS original DS diagnostic boot, video and state roundtrip");
}
