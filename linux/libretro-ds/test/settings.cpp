// Settings changes go through real controller navigation and shared rendering.
#include "companion_ui.h"
#include "libretro.h"
extern "C" {
#include "../../hud.h"
#include "pd_style.h"
}
#include <algorithm>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <filesystem>
#include <unistd.h>
static void press(DsCompanionUi& ui,unsigned id){assert(!ui.control(1u<<id));}
static void ppm(const char* path,const std::vector<uint32_t>& px,int w=341,int h=256){
  FILE* f=fopen(path,"wb");assert(f);fprintf(f,"P6\n%d %d\n255\n",w,h);
  for(auto p:px){fputc(p>>16&255,f);fputc(p>>8&255,f);fputc(p&255,f);}fclose(f);
}
int main(int argc,char** argv){
  assert(argc==3);char path[]="/tmp/pdds-settings-XXXXXX";assert(mkdtemp(path));
  DsCompanionUi ui;ui.init(path);assert(!ui.settings.hud && ui.companion_id()==RETRO_DEVICE_ID_JOYPAD_L2);
  std::ifstream f(argv[1],std::ios::binary);std::vector<uint8_t> ram{std::istreambuf_iterator<char>(f),{}};
  auto s=ds_read_companion(ram.data(),ram.size());assert(s.valid);
  std::vector<uint32_t> px(341*256);pd_canvas c;pd_canvas_init(&c,341,256,px.data());
  auto draw=[&](){ui.draw(&c,s,true,{},{});};draw();
  // The actual shared bar contains only the five implemented tabs and gear.
  int tabs=0;for(int i=0;i<ui.frame.hitCount;i++)if(ui.frame.hits[i].id>=100 && ui.frame.hits[i].id<108)++tabs;
  assert(tabs==6);
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
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);assert(ui.setting==DsCompanionUi::Hud);
  // HUD opens the shared HUD page (the GB / GBA cores' own): its first row switches the HUD, B comes back.
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.frame.overlay==PD_OVERLAY_HUD && pd_ui_tab_menu_count(&ui.frame)==4+PD_HUD_PARTS && !ui.settings.hud);
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);assert(ui.settings.hud);
  draw();ppm((std::string(argv[2])+"/hud-page.ppm").c_str(),px);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.settings.hudLayout==0); // LAYOUT is N/A on DS: one layout
  press(ui,RETRO_DEVICE_ID_JOYPAD_B);assert(ui.frame.overlay==PD_OVERLAY_NONE && ui.setting==DsCompanionUi::Hud);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);assert(ui.setting==DsCompanionUi::QuickStyle);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);assert(ui.setting==DsCompanionUi::CompanionButton);
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.companion_id()==RETRO_DEVICE_ID_JOYPAD_X);
  ui.setting=DsCompanionUi::ScreenButton;
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.screen_id()==RETRO_DEVICE_ID_JOYPAD_R2 && !ui.error.empty());
  // Choose NONE for screen shortcut, then FAST pen speed.
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);for(int i=0;i<3;i++)press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);assert(ui.setting==DsCompanionUi::PenSpeed);
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.screen_id()==-1 && ui.pen_step()==6);draw();ppm((std::string(argv[2])+"/settings-controls.ppm").c_str(),px);
  ui.setting=DsCompanionUi::Battles;
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);assert(!ui.autoBattles);
  DsCompanionUi reloaded;reloaded.init(path);
  assert(reloaded.layout==3 && !reloaded.autoBattles && reloaded.settings.hud && reloaded.companion_id()==RETRO_DEVICE_ID_JOYPAD_X && reloaded.screen_id()==-1 && reloaded.pen_step()==6);
  // A cancelled reset leaves the file and current values alone.
  ui.setting=DsCompanionUi::QuickStyle;press(ui,RETRO_DEVICE_ID_JOYPAD_A);assert(ui.settings.quickGrid);
  ui.setting=DsCompanionUi::Reset;press(ui,RETRO_DEVICE_ID_JOYPAD_A);assert(ui.choice==0);press(ui,RETRO_DEVICE_ID_JOYPAD_A);assert(ui.layout==3);
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
  assert(ui.hud_id()==-1);
  ui.setting=DsCompanionUi::HudButton;
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  press(ui,RETRO_DEVICE_ID_JOYPAD_UP); // R2 is already the screen switch.
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.hud_id()==-1 && !ui.error.empty());
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  for(int i=0;i<3;++i)press(ui,RETRO_DEVICE_ID_JOYPAD_UP);
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.hud_id()==RETRO_DEVICE_ID_JOYPAD_Y);
  reloaded.init(path);
  assert(reloaded.hud_id()==RETRO_DEVICE_ID_JOYPAD_Y);
  // SIZE and the parts are rows of the HUD page: SIZE cycles NORMAL to LARGE, POKé BALLS and REPEL STEPS hide.
  ui.setting=DsCompanionUi::Hud;
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);assert(ui.frame.overlay==PD_OVERLAY_HUD && ui.settings.hudSize==PD_HUD_NORMAL);
  for(int i=0;i<3;++i)press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  for(int i=0;i<PD_HUD_BALLS+1;++i)press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  press(ui,RETRO_DEVICE_ID_JOYPAD_B);
  reloaded.init(path);
  assert(reloaded.settings.hudSize==PD_HUD_LARGE);
  ui.setting=DsCompanionUi::HudMinimal;
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  reloaded.init(path);
  assert(reloaded.hudMinimal && (reloaded.settings.hudHidden&(1<<PD_HUD_BALLS))
         && (reloaded.settings.hudHidden&(1<<PD_HUD_REPEL)));
  ui.setting=DsCompanionUi::DarkMode;
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.darkMode);
  reloaded.init(path);
  assert(reloaded.darkMode);
  ui.frame.tab=PD_TAB_DEX;
  draw();
  assert(!pd_dark_look && px[0]==0x1F202C);
  assert(std::count(px.begin(),px.end(),0x1D2128)>100);
  ui.frame.tab=PD_TAB_SETTINGS;
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  reloaded.init(path);
  assert(!reloaded.darkMode);
  ui.set_game(DsGame::Platinum);
  ui.setting=DsCompanionUi::AutoRun;
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  reloaded.init(path);
  assert(ui.autoRun && reloaded.autoRun);
  ui.setting=DsCompanionUi::TabBar;
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.frame.overlay==PD_OVERLAY_TAB_BAR && pd_ui_tab_menu_count(&ui.frame)==5);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(!(ui.settings.tabBar & (1<<PD_TAB_MAP)));
  draw();ppm((std::string(argv[2])+"/shared-tab-bar.ppm").c_str(),px);
  press(ui,RETRO_DEVICE_ID_JOYPAD_B);
  press(ui,RETRO_DEVICE_ID_JOYPAD_DOWN);
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.frame.overlay==PD_OVERLAY_MORE && pd_ui_tab_menu_count(&ui.frame)==1);
  draw();ppm((std::string(argv[2])+"/shared-more.ppm").c_str(),px);
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.frame.tab==PD_TAB_MAP);
  // Hidden cards occupy the More position when using the shoulder buttons.
  press(ui,RETRO_DEVICE_ID_JOYPAD_R);
  assert(ui.frame.tab==PD_TAB_SETTINGS && ui.frame.overlay==PD_OVERLAY_NONE);
  press(ui,RETRO_DEVICE_ID_JOYPAD_L);
  assert(ui.frame.overlay==PD_OVERLAY_MORE);
  press(ui,RETRO_DEVICE_ID_JOYPAD_L);
  assert(ui.frame.tab==PD_TAB_GUIDE && ui.frame.overlay==PD_OVERLAY_NONE);
  press(ui,RETRO_DEVICE_ID_JOYPAD_R);
  assert(ui.frame.overlay==PD_OVERLAY_MORE);
  press(ui,RETRO_DEVICE_ID_JOYPAD_A);
  assert(ui.frame.tab==PD_TAB_MAP && ui.frame.moreOpened);
  reloaded.init(path);
  assert(!(reloaded.settings.tabBar & (1<<PD_TAB_MAP)) && (reloaded.frame.availableTabs & (1<<PD_TAB_MAP)));
  pd_snapshot field{};
  field.valid=true;
  const hud_state active{250,250,false,-1,-1};
  pd_settings hudOptions{};
  pd_settings_defaults(&hudOptions);
  hud_hits hits{};
  hud_ds_draw(&c,&field,&active,&hudOptions,nullptr,nullptr,
              [](pd_canvas*,int id,int,int){assert(id==79);return true;},79,true,c.w,&hits,nullptr);
  assert(hud_first(&hits)==HUD_REPEL);
  hud_ds_minimap(&c,&hudOptions,"Route 201",[](pd_canvas*,int,int,int,int){},&hits);
  assert(hud_step(&hits,HUD_REPEL,0,-1)==HUD_MAP);
  field.partyCount=1;
  field.party[0].species=25;
  field.party[0].level=15;
  field.party[0].hp=field.party[0].maxHp=20;
  std::vector<uint32_t> hudPixels(256*192);
  pd_canvas hudCanvas;pd_canvas_init(&hudCanvas,256,192,hudPixels.data());
  field.bagOk=true;
  field.bag[PD_POCKET_BALLS].count=1;
  field.bag[PD_POCKET_BALLS].items[0]={4,12};
  int partyWidth[2],partyHeight[2];
  for(int i=0;i<2;++i){
    hud_state scale=active;
    scale.mini=i==0;
    scale.pc=true;scale.wild=3;
    hudOptions.hudSize=scale.mini?PD_HUD_NORMAL:PD_HUD_LARGE;
    hud_ds_draw(&hudCanvas,&field,&scale,&hudOptions,nullptr,
                [](pd_canvas* dest,void*,const pd_mon*,int x,int y,bool){pd_fill(dest,x+6,y+6,20,20,0xF4CF54);},
                [](pd_canvas* dest,int,int x,int y){pd_fill(dest,x+8,y+3,8,16,0x80CF72);return true;},
                79,false,hudCanvas.w,&hits,nullptr);
    assert(hud_first(&hits)==HUD_SLOT);
    for(int target : {HUD_BALLS,HUD_PC,HUD_BAG,HUD_WILD,HUD_COMPANION})
      assert(std::any_of(hits.at,hits.at+hits.n,[&](const auto& hit){return hit.id==target;}));
    const auto findTarget = [&](int id) {
      return std::find_if(hits.at,hits.at+hits.n,[=](const auto& hit){return hit.id==id;});
    };
    assert(findTarget(HUD_PC)->x<findTarget(HUD_BAG)->x);
    assert(findTarget(HUD_BAG)->x<findTarget(HUD_WILD)->x);
    partyWidth[i]=hits.at[0].w;
    partyHeight[i]=hits.at[0].h;
    hud_ds_minimap(&hudCanvas,&hudOptions,"Route 201",[](pd_canvas* dest,int x,int y,int w,int h){
      assert(w==72 || w==104);
      assert(h==(w==72?40:64));
      pd_fill(dest,x,y,w,h,0x5D9375);
    },&hits);
    assert(hits.at[hits.n-1].id==HUD_MAP && hits.at[hits.n-1].w==(scale.mini?76:112));
    ppm((std::string(argv[2])+(scale.mini?"/hud-mini.ppm":"/hud-large.ppm")).c_str(),hudPixels,hudCanvas.w,hudCanvas.h);
  }
  assert(partyWidth[0]<partyWidth[1] && partyHeight[0]<partyHeight[1]);
  field.partyCount=0;field.bagOk=false;
  const hud_state noRepel{0,0,false,-1,-1};
  hud_ds_draw(&c,&field,&noRepel,&hudOptions,nullptr,nullptr,nullptr,79,true,c.w,&hits,nullptr);
  assert(hud_first(&hits)==HUD_BAG);
  ui.frame.tab=PD_TAB_BAG;
  s.bag.clear();
  draw();
  ppm((std::string(argv[2])+"/bag-empty.ppm").c_str(),px);
  // Hostile/old preference values never index a label/button array out of bounds.
  ui.save();
  {std::ofstream file(ui.path,std::ios::app);file<<"ds_layout=999\nds_companionbtn=-1\nds_penspeed=oops\n";}
  reloaded.init(path);assert(reloaded.layout==0 && reloaded.companion_id()==RETRO_DEVICE_ID_JOYPAD_L2);
  reloaded.init("/missing/pdds-parent");assert(!reloaded.save() && !reloaded.error.empty());
  std::filesystem::remove_all(path);
  puts("PASS shared frame, choice/cancel, preferences, remapping conflicts, atomic reload, reset and failure feedback");
}
