// GPL-3.0. Reuses the shared frame, row widgets, pick-list and Platinum views.
#include "companion_ui.h"
#include "layout.h"
#include "party_view.h"
#include "bag_view.h"
#include "dex_view.h"
#include "map_view.h"
#include "browser.h"
#include "libretro.h"
#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sys/stat.h>
#include <unistd.h>
extern "C" {
#include "pd_style.h"
}
namespace {
const char* const layoutNames[]={"ONE SCREEN","TOP","BOTTOM","SIDE BY SIDE","STACKED","LARGE + SMALL","PICTURE IN PICTURE"};
const char* const buttonNames[]={"L2","R2","X","Y","NONE"};
const unsigned buttonIds[]={RETRO_DEVICE_ID_JOYPAD_L2,RETRO_DEVICE_ID_JOYPAD_R2,RETRO_DEVICE_ID_JOYPAD_X,RETRO_DEVICE_ID_JOYPAD_Y};
const char* const speedNames[]={"SLOW","NORMAL","FAST"};
const char* const resetNames[]={"CANCEL","RESET"};
const char* const labels[]={"DS LAYOUT","AUTO BATTLES","PARTY HUD","COMPANION BUTTON","SCREEN BUTTON","STYLUS SPEED","QUICK MENU","RESET SETTINGS","BATTLE STRIP","MINIMAL HUD","POKÉ BALLS","REPEL STEPS","MINIMAP"};
const char* const* choices(int row){
  return row==DsCompanionUi::Layout?layoutNames:row==DsCompanionUi::PenSpeed?speedNames:
    row==DsCompanionUi::Reset?resetNames:buttonNames;
}
int count(int row){return row==DsCompanionUi::Layout?DS_LAYOUTS:row==DsCompanionUi::CompanionButton?4:
 row==DsCompanionUi::ScreenButton?5:row==DsCompanionUi::PenSpeed?3:2;}
}
void DsCompanionUi::defaults(){
  pd_settings_defaults(&settings);settings.hud=false;
  settings.tabBar=(1<<PD_TAB_PARTY)|(1<<PD_TAB_BAG)|(1<<PD_TAB_MAP)|(1<<PD_TAB_DEX);
  layout=0;autoBattles=1;companionButton=0;screenButton=1;penSpeed=1;battleStrip=true;hudMinimal=false;
}
void DsCompanionUi::init(const std::string& directory,int legacyLayout,bool legacyHud){
  *this=DsCompanionUi{};defaults();layout=std::clamp(legacyLayout,0,4);settings.hud=legacyHud;
  if(!directory.empty()){
    const std::string sub=directory+"/pokedaisy-ds";
    if(mkdir(sub.c_str(),0755)==0 || errno==EEXIST)path=sub+"/settings.ini";
  }
  bool loaded=false;
  if(!path.empty()){
    std::string loadPath=path;
    // Only migrate a DS-authored legacy file; never import the GB/GBA settings.
    if(access(path.c_str(),F_OK)!=0){
      const std::string legacy=directory+"/pokedaisy/settings.ini";
      std::ifstream old(legacy);std::string line;
      while(std::getline(old,line))if(line.rfind("ds_layout=",0)==0){loadPath=legacy;break;}
    }
    loaded=pd_settings_load(&settings,loadPath.c_str());
    if(!loaded){defaults();layout=std::clamp(legacyLayout,0,4);settings.hud=legacyHud;}
    std::ifstream file(loadPath);std::string line;
    while(std::getline(file,line)){
      auto at=line.find('=');if(at==std::string::npos)continue;
      auto key=line.substr(0,at),text=line.substr(at+1);char* end=nullptr;
      long v=std::strtol(text.c_str(),&end,10);
      if(end==text.c_str() || (*end && *end!='\r') || v<0 || v>8)continue;
      if(key=="ds_layout" && v<DS_LAYOUTS)layout=v;
      if(key=="ds_battles" && v<2)autoBattles=v;
      if(key=="ds_companionbtn" && v<4)companionButton=v;
      if(key=="ds_screenbtn" && v<5)screenButton=v;
      if(key=="ds_hudminimal" && v<2)hudMinimal=v;
      if(key=="ds_battlestrip" && v<2)battleStrip=v;
      if(key=="ds_penspeed" && v<3)penSpeed=v;
    }
  }
  if(companionButton==screenButton)screenButton=(companionButton+1)%4;
  settings.tabBar=(1<<PD_TAB_PARTY)|(1<<PD_TAB_BAG)|(1<<PD_TAB_MAP)|(1<<PD_TAB_DEX);
  pd_ui_init(&frame,&settings);pd_ui_init(&options,&settings);
  frame.availableTabs=settings.tabBar;
  frame.contentCtx=this;frame.content=[](void* ctx,pd_ui* u,pd_canvas* c){static_cast<DsCompanionUi*>(ctx)->draw_body(u,c);};
  (void)loaded;
}
bool DsCompanionUi::save(){
  if(path.empty()){error="Settings storage unavailable";return false;}
  const std::string temp=path+".tmp";
  if(!pd_settings_save(&settings,temp.c_str())){error="Could not save settings";return false;}
  FILE* f=std::fopen(temp.c_str(),"a");bool ok=f;
  if(f){
    ok=std::fprintf(f,"ds_layout=%d\nds_battles=%d\nds_companionbtn=%d\nds_screenbtn=%d\nds_penspeed=%d\nds_battlestrip=%d\nds_hudminimal=%d\n",layout,autoBattles,companionButton,screenButton,penSpeed,battleStrip,hudMinimal)>0;
    if(std::fflush(f) || fsync(fileno(f)))ok=false;
    if(std::fclose(f))ok=false;
  }
  if(ok && std::rename(temp.c_str(),path.c_str())==0){error.clear();return true;}
  std::remove(temp.c_str());error="Could not save settings";return false;
}
void DsCompanionUi::set_game(DsGame value){
  game=value;
  if(game!=DsGame::Platinum)frame.availableTabs=(1<<PD_TAB_PARTY)|(1<<PD_TAB_BAG)|(1<<PD_TAB_MAP)|(1<<PD_TAB_DEX);
  if(game==DsGame::Unsupported){
    frame.availableTabs=1<<PD_TAB_PARTY;
    frame.tab=PD_TAB_SETTINGS;
  }
}
unsigned DsCompanionUi::companion_id()const{return buttonIds[companionButton];}
int DsCompanionUi::screen_id()const{return screenButton<4?int(buttonIds[screenButton]):-1;}
int DsCompanionUi::pen_step()const{return penSpeed==0?1:penSpeed==1?3:6;}
int DsCompanionUi::value(int row)const{
  if(row==HudMinimal)return hudMinimal;
  if(row==HudMap)return !(settings.hudHidden&(1<<PD_HUD_MAP));
  if(row==HudBalls||row==HudRepel)return !(settings.hudHidden&(1<<(row==HudBalls?PD_HUD_BALLS:PD_HUD_REPEL)));
  return row==Layout?layout:row==Battles?autoBattles:row==Hud?settings.hud:
    row==CompanionButton?companionButton:row==ScreenButton?screenButton:row==PenSpeed?penSpeed:row==QuickStyle?settings.quickGrid:row==BattleStrip?battleStrip:0;
}
void DsCompanionUi::apply(int row,int v){
  if(row==Layout){layout=v;++displayRevision;}
  else if(row==Battles)autoBattles=v;
  else if(row==Hud)settings.hud=v;
  else if(row==CompanionButton){if(screenButton==v)screenButton=companionButton;companionButton=v;}
  else if(row==ScreenButton){if(companionButton==v){error="Button already opens companion";return;}screenButton=v;}
  else if(row==PenSpeed)penSpeed=v;
  else if(row==QuickStyle)settings.quickGrid=v;
  else if(row==BattleStrip)battleStrip=v;
  else if(row==HudMinimal)hudMinimal=v;
  else if(row==HudMap)settings.hudHidden^=1<<PD_HUD_MAP;
  else if(row==HudBalls||row==HudRepel)settings.hudHidden^=1<<(row==HudBalls?PD_HUD_BALLS:PD_HUD_REPEL);
  else if(row==Reset && v){defaults();++displayRevision;frame.settings=&settings;options.settings=&settings;}
  save();
}
void DsCompanionUi::draw_settings(pd_canvas* c){
  const auto& s=*snapshot;
  const std::string money=s.valid?std::to_string(s.money):"-";
  const char* values[]={layoutNames[layout],autoBattles?"ON":"OFF",settings.hud?"ON":"OFF",
    buttonNames[companionButton],buttonNames[screenButton],speedNames[penSpeed],settings.quickGrid?"GRID":"RING","...",battleStrip?"ON":"OFF",hudMinimal?"ON":"OFF",value(HudBalls)?"ON":"OFF",value(HudRepel)?"ON":"OFF",value(HudMap)?"ON":"OFF"};
  pd_option_row rows[Rows+5]={{"TRAINER",s.valid?s.trainer.c_str():"-"},{"PLACE",s.valid?ds_place(s.map,s.game):"-"},{"MONEY",money.c_str()}};
  for(int i=0;i<Rows;i++)rows[3+i]={labels[i],values[i]};
  rows[Rows+3]={"STATES / SPEED","NXRedux MENU"};
  rows[Rows+4]={"RESUME / SHADERS","NXRedux MENU"};
  pd_option_source source{"SETTINGS",rows,Rows+5};
  options.optionSource=&source;options.optionSelected=3+setting;
  pd_game game{};pd_snapshot state{};
  pd_ui_options_view(&options,c,&game,&state);options.optionSource=nullptr;
}
void DsCompanionUi::draw_body(pd_ui* u,pd_canvas* c){
  if(u->tab==PD_TAB_SETTINGS){draw_settings(c);return;}
  if(!supported || !snapshot->valid){
    pd_title_box(c,4,4,c->w-8,24,0xffffff);
    pd_text(c,12,9,supported?"WAITING FOR SAVE DATA":"COMPANION UNAVAILABLE FOR THIS ROM",PD_TITLE_TEXT,PD_TITLE_SHADOW);return;
  }
  if(u->tab==PD_TAB_PARTY)ds_party_view(c,*snapshot,*icons,selected,summary,page);
  if(u->tab==PD_TAB_BAG)ds_bag_view(c,*snapshot,pocket,scroll,details);
  if(u->tab==PD_TAB_DEX)ds_dex_view(c,*snapshot,filter,scroll);
  if(u->tab==PD_TAB_MAP)ds_map_view(c,*snapshot,*art,mapX,mapY);
}
void DsCompanionUi::draw(pd_canvas* c,const DsCompanion& s,bool match,const DsMapArt& map,const std::vector<std::vector<uint32_t>>& monIcons){
  snapshot=&s;art=&map;icons=&monIcons;supported=match;
  pd_game game{};pd_snapshot state{};pd_host_info host{};host.frontendOwnsPlayback=true;
  pd_ui_draw(&frame,c,&game,&state,&host);
  if(picker>=0)pd_ui_choices_view(c,labels[picker],choices(picker),count(picker),choice);
  if(!error.empty()){
    pd_fill(c,4,c->h-41,c->w-8,16,0x602020);
    pd_text_fit(c,8,c->h-43,c->w-16,error.c_str(),0xffffff,PD_NO_SHADOW);
  }
}
bool DsCompanionUi::control(unsigned pressed){
  auto hit=[&](unsigned id){return bool(pressed&(1u<<id));};
  int dy=hit(RETRO_DEVICE_ID_JOYPAD_DOWN)-hit(RETRO_DEVICE_ID_JOYPAD_UP);
  int dx=hit(RETRO_DEVICE_ID_JOYPAD_RIGHT)-hit(RETRO_DEVICE_ID_JOYPAD_LEFT);
  if(picker>=0){
    choice=std::clamp(choice+dy,0,count(picker)-1);
    if(hit(RETRO_DEVICE_ID_JOYPAD_B)){picker=-1;return false;}
    if(hit(RETRO_DEVICE_ID_JOYPAD_A)){int row=picker;picker=-1;apply(row,choice);}return false;
  }
  if(hit(RETRO_DEVICE_ID_JOYPAD_L) || hit(RETRO_DEVICE_ID_JOYPAD_R)){
    pd_ui_next_tab(&frame,hit(RETRO_DEVICE_ID_JOYPAD_R)?1:-1);
    scroll=0;summary=details=false;error.clear();return false;
  }
  if(frame.tab==PD_TAB_SETTINGS){
    setting=std::clamp(setting+dy,0,int(Rows)-1);
    if(hit(RETRO_DEVICE_ID_JOYPAD_B)){if(game==DsGame::Unsupported)return true;frame.tab=PD_TAB_PARTY;return false;}
    if(hit(RETRO_DEVICE_ID_JOYPAD_A)){
      error.clear();if(setting==Battles || setting==Hud || setting==QuickStyle || setting>=BattleStrip)apply(setting,!value(setting));
      else{picker=setting;choice=value(setting);}
    }return false;
  }
  if(frame.tab==PD_TAB_PARTY){
    int n=snapshot?int(snapshot->party.size()):0;
    selected=std::clamp(selected+dy*(summary?1:2)+(summary?0:dx),0,std::max(0,n-1));
    if(summary && dx)page=1-page;
    if(hit(RETRO_DEVICE_ID_JOYPAD_A))summary=!summary;
    if(hit(RETRO_DEVICE_ID_JOYPAD_B)){if(summary)summary=false;else return true;}
  }else if(frame.tab==PD_TAB_BAG){
    if(dx){const int tabs=ds_bag_tabs(game);pocket=(pocket+dx+tabs)%tabs;scroll=0;}
    scroll=std::clamp(scroll+dy,0,std::max(0,snapshot?ds_bag_count(*snapshot,pocket)-1:0));
    if(hit(RETRO_DEVICE_ID_JOYPAD_A))details=!details;
    if(hit(RETRO_DEVICE_ID_JOYPAD_B)){if(details)details=false;else return true;}
  }else if(frame.tab==PD_TAB_DEX){
    if(hit(RETRO_DEVICE_ID_JOYPAD_SELECT)){filter=(filter+1)%3;scroll=0;}
    scroll=std::clamp(scroll+dy+dx*8,0,std::max(0,snapshot?ds_dex_count(*snapshot,filter)-1:0));
    if(hit(RETRO_DEVICE_ID_JOYPAD_B))return true;
  }else{
    const int maxX=game==DsGame::Johto?46:ds_unova(game)?31:29;
    const int maxY=game==DsGame::Johto?19:ds_unova(game)?20:29;
    mapX=std::clamp(mapX+dx,0,maxX);mapY=std::clamp(mapY+dy,0,maxY);
    if(hit(RETRO_DEVICE_ID_JOYPAD_A) && snapshot){mapX=snapshot->mapX;mapY=snapshot->mapY;}
    if(hit(RETRO_DEVICE_ID_JOYPAD_B))return true;
  }
  return false;
}
