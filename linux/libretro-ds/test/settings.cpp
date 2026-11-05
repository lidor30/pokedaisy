// Settings changes go through real controller navigation and shared rendering.
#include "companion_ui.h"
#include "libretro.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <filesystem>
#include <unistd.h>
static void press(DsCompanionUi& ui,unsigned id){assert(!ui.control(1u<<id));}
static void ppm(const char* path,const std::vector<uint32_t>& px){
  FILE* f=fopen(path,"wb");assert(f);fprintf(f,"P6\n341 256\n255\n");
  for(auto p:px){fputc(p>>16&255,f);fputc(p>>8&255,f);fputc(p&255,f);}fclose(f);
}
int main(int argc,char** argv){
  assert(argc==3);char path[]="/tmp/pdds-settings-XXXXXX";assert(mkdtemp(path));
  DsCompanionUi ui;ui.init(path);assert(!ui.settings.hud && ui.companion_id()==RETRO_DEVICE_ID_JOYPAD_L2);
  std::ifstream f(argv[1],std::ios::binary);std::vector<uint8_t> ram{std::istreambuf_iterator<char>(f),{}};
  auto s=ds_read_companion(ram.data(),ram.size());assert(s.valid);
  std::vector<uint32_t> px(341*256);pd_canvas c;pd_canvas_init(&c,341,256,px.data());
  auto draw=[&](){ui.draw(&c,s,true,{},{});};draw();
  // The actual shared bar contains only the four implemented tabs and gear.
  int tabs=0;for(int i=0;i<ui.frame.hitCount;i++)if(ui.frame.hits[i].id>=100 && ui.frame.hits[i].id<108)++tabs;
  assert(tabs==5);
  s.party.resize(6,s.party[0]);draw();
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);assert(ui.selected==2);
  press(ui,RETRO_DEVICE_ID_JOYPAD_RIGHT);assert(ui.selected==3);
  ui.selected=0;
  press(ui,RETRO_DEVICE_ID_JOYPAD_L);assert(ui.frame.tab==PD_TAB_SETTINGS);
  draw();ppm((std::string(argv[2])+"/settings.ppm").c_str(),px);
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);assert(ui.picker==DsCompanionUi::Layout);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);draw();ppm((std::string(argv[2])+"/layout-picker.ppm").c_str(),px);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);assert(ui.layout==3 && ui.picker==-1);
  // Cancel does not apply a highlighted choice.
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);press(ui,RETRO_DEVICE_ID_JOYPAD_UP);press(ui,RETRO_DEVICE_ID_JOYPAD_B);assert(ui.layout==3);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);assert(!ui.autoBattles);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);assert(ui.settings.hud);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.companion_id()==RETRO_DEVICE_ID_JOYPAD_X);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.screen_id()==RETRO_DEVICE_ID_JOYPAD_R2 && !ui.error.empty());
  // Choose NONE for screen shortcut, then FAST pen speed.
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);for(int i=0;i<3;i++)press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.screen_id()==-1 && ui.pen_step()==6);draw();ppm((std::string(argv[2])+"/settings-controls.ppm").c_str(),px);
  DsCompanionUi reloaded;reloaded.init(path);
  assert(reloaded.layout==3 && !reloaded.autoBattles && reloaded.settings.hud && reloaded.companion_id()==RETRO_DEVICE_ID_JOYPAD_X && reloaded.screen_id()==-1 && reloaded.pen_step()==6);
  // A cancelled reset leaves the file and current values alone.
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);assert(ui.settings.quickGrid);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);assert(ui.choice==0);press(ui,RETRO_DEVICE_ID_JOYPAD_A);assert(ui.layout==3);
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.layout==0 && ui.autoBattles && !ui.settings.hud && ui.companion_id()==RETRO_DEVICE_ID_JOYPAD_L2);
  reloaded.init(path);assert(reloaded.layout==0 && !reloaded.settings.hud);
  // Keep the two engines' preferences independent in a common frontend save directory.
  std::filesystem::create_directory(std::string(path)+"/pokedaisy");
  {std::ofstream gb(std::string(path)+"/pokedaisy/settings.ini");gb<<"hud=1\naspect=2\n";}
  DsCompanionUi separate;separate.init(path);assert(!separate.settings.hud);
  std::filesystem::remove(ui.path);
  separate.init(path);assert(!separate.settings.hud); // GBA file is not a DS migration source
  {std::ofstream legacy(std::string(path)+"/pokedaisy/settings.ini");legacy<<"hud=1\nds_layout=4\n";}
  separate.init(path);assert(separate.settings.hud && separate.layout==4);
  assert(separate.save()); // DS-authored legacy settings migrate without modifying the source
  // Every appended layout is selectable and survives a real settings reload.
  ui.init(path);ui.layout=0;ui.frame.tab=PD_TAB_SETTINGS;ui.setting=0;
  for(int mode=0;mode<7;++mode){
    press(ui,RETRO_DEVICE_ID_JOYPAD_A);
    for(int i=0;i<mode;++i)press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);
    press(ui,RETRO_DEVICE_ID_JOYPAD_A);assert(ui.layout==mode);
    reloaded.init(path);assert(reloaded.layout==mode);
    // Return to zero so the next selection starts at the same row.
    press(ui,RETRO_DEVICE_ID_JOYPAD_A);
    for(int i=0;i<mode;++i)press(ui,RETRO_DEVICE_ID_JOYPAD_UP);
    press(ui,RETRO_DEVICE_ID_JOYPAD_A);assert(ui.layout==0);
  }
  // Hostile/old preference values never index a label/button array out of bounds.
  ui.save();
  {std::ofstream file(ui.path,std::ios::app);file<<"ds_layout=999\nds_companionbtn=-1\nds_penspeed=oops\n";}
  reloaded.init(path);assert(reloaded.layout==0 && reloaded.companion_id()==RETRO_DEVICE_ID_JOYPAD_L2);
  reloaded.init("/missing/pdds-parent");assert(!reloaded.save() && !reloaded.error.empty());
  std::filesystem::remove_all(path);
  puts("PASS shared frame, choice/cancel, preferences, remapping conflicts, atomic reload, reset and failure feedback");
}
