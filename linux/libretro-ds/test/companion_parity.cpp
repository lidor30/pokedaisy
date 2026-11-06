#include "companion_ui.h"
#include "libretro.h"
#include <cassert>
#include <array>
#include <fstream>
#include <cstdio>
#include <vector>
int main(int argc,char**argv) {
  assert(argc==4 || argc==7);
  using G=DsGame;
  struct Case { G game; int money,items; std::vector<int> species; };
  const Case cases[]={
    {G::Johto,593506,0,{154,181,197,164,195,20}},
    {G::Johto,575648,0,{157,196,18,62,162,20}},
    {G::Black,684260,185,{381,245,639,521,612}},
    {G::White,684260,185,{381,245,639,521,612}},
    {G::Black2,656841,50,{500,571,547,520,55,505}},
    {G::White2,544628,49,{497,227,579,505,127,184}},
  };
  for(int i=0;i<argc-1;i++) {
    std::ifstream f(argv[i+1],std::ios::binary);
    std::vector<uint8_t> ram(4*1024*1024);
    assert(f.read(reinterpret_cast<char*>(ram.data()),ram.size()));
    const auto& expect=cases[i];
    const auto s=ds_read_companion(ram.data(),ram.size(),expect.game);
    printf("%s: valid=%d team=%zu money=%d bag=%zu first=%s %d/%d\n",argv[i+1],s.valid,s.party.size(),s.money,s.bag.size(),s.party.empty()?"":s.party[0].nickname.c_str(),s.party.empty()?0:s.party[0].hp,s.party.empty()?0:s.party[0].maxHp);
    fflush(stdout);
    assert(s.valid && s.party.size()==expect.species.size() && s.money==expect.money);
    for(size_t n=0;n<s.party.size();n++) assert(s.party[n].species==expect.species[n] && s.party[n].maxHp>0);
    assert(expect.items ? s.bag.size()==size_t(expect.items) : !s.bag.empty());
    if(i==0) {
      assert(s.party[0].nickname=="HDChipCard" && s.party[0].hp==321 && s.party[1].personality==0x83DE92E4);
    }
    if(i==1) assert(s.party[1].hp==276);
    if(i==2 || i==3) {
      assert((s.party[0].moves==std::array<int,4>{406,473,58,85}));
      assert(s.bag[0].id==575 && s.bag[0].count==155);
    }
    if(i==4) assert((s.party[0].moves==std::array<int,4>{394,372,457,535}));

    assert(s.dexValid && s.caughtCount>0 && s.seenCount>=s.caughtCount);
    if(i==3)assert(s.caughtCount==638);
    if(i==5)assert(s.seenCount==230);
    DsCompanionUi ui;
    ui.init("");
    ui.set_game(expect.game);
    std::vector<uint32_t> pixels(341*256);
    pd_canvas canvas;
    pd_canvas_init(&canvas,341,256,pixels.data());
    const DsMapArt map;
    const std::vector<std::vector<uint32_t>> icons;
    auto draw=[&]{ui.draw(&canvas,s,true,map,icons);};
    auto press=[&](unsigned button){assert(!ui.control(1u<<button));};
    draw();
    assert(ui.frame.tab==PD_TAB_PARTY);
    press(RETRO_DEVICE_ID_JOYPAD_DOWN);
    assert(ui.selected==2);
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.summary);
    draw();
    press(RETRO_DEVICE_ID_JOYPAD_B);
    press(RETRO_DEVICE_ID_JOYPAD_R);
    assert(ui.frame.tab==PD_TAB_BAG);
    draw();
    press(RETRO_DEVICE_ID_JOYPAD_LEFT);
    assert(ui.pocket==(expect.game==G::Johto?8:5));
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.details);
    draw();
    press(RETRO_DEVICE_ID_JOYPAD_B);
    press(RETRO_DEVICE_ID_JOYPAD_R);
    assert(ui.frame.tab==PD_TAB_MAP && s.mapX>=0 && s.mapY>=0);
    draw();
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.mapX==s.mapX && ui.mapY==s.mapY);
    press(RETRO_DEVICE_ID_JOYPAD_R);
    assert(ui.frame.tab==PD_TAB_DEX);
    draw();
    press(RETRO_DEVICE_ID_JOYPAD_SELECT);
    assert(ui.filter==1);
    press(RETRO_DEVICE_ID_JOYPAD_R);
    assert(ui.frame.tab==PD_TAB_SETTINGS);
    draw();
    press(RETRO_DEVICE_ID_JOYPAD_R);
    assert(ui.frame.tab==PD_TAB_PARTY);
  }
}
