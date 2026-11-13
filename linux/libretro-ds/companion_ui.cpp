// GPL-3.0. Reuses the shared frame, row widgets, pick-list and Platinum views.
#include "companion_ui.h"
#include "companion_style.h"
#include "layout.h"
#include "party_view.h"
#include "bag_view.h"
#include "dex_view.h"
#include "map_view.h"
#include "guide_view.h"
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
#include "pd_tables.h"
}
namespace {
constexpr int dsTabs=(1<<PD_TAB_PARTY)|(1<<PD_TAB_BAG)|(1<<PD_TAB_MAP)|(1<<PD_TAB_GUIDE)|(1<<PD_TAB_DEX);
using Companion=DsCompanionUi;
constexpr Companion::Row settingsOrder[]={
  Companion::Layout,Companion::Hud,Companion::QuickStyle,Companion::CompanionButton,Companion::HudButton,Companion::TabBar,Companion::MoreTabs,
  Companion::HudMinimal,Companion::Battles,Companion::BattleStrip,Companion::AutoRun,Companion::ScreenButton,Companion::PenSpeed,
  Companion::DarkMode,Companion::Reset
};
// HUD opens the shared HUD page (pd_ui_settings.c's hud_page: on/off, LOOK, SIZE and each part), as on the GB/GBA cores;
// HudSize, HudBalls, HudRepel and HudMap stay rows of the enum for settings files and apply(), not of the list.
constexpr int listed=int(std::size(settingsOrder));
const char* const layoutNames[]={"ONE SCREEN","TOP","BOTTOM","SIDE BY SIDE","STACKED","LARGE + SMALL","PICTURE IN PICTURE"};
const char* const buttonNames[]={"L2","R2","X","Y","NONE"};
const unsigned buttonIds[]={RETRO_DEVICE_ID_JOYPAD_L2,RETRO_DEVICE_ID_JOYPAD_R2,RETRO_DEVICE_ID_JOYPAD_X,RETRO_DEVICE_ID_JOYPAD_Y};
const char* const hudButtonNames[]={ "X", "Y", "L2", "R2", "NONE" };
const int hudButtonIds[]={RETRO_DEVICE_ID_JOYPAD_X,RETRO_DEVICE_ID_JOYPAD_Y,RETRO_DEVICE_ID_JOYPAD_L2,RETRO_DEVICE_ID_JOYPAD_R2,-1};
const char* const speedNames[]={"SLOW","NORMAL","FAST"};
const char* const hudSizeNames[]={ "SMALL", "NORMAL", "LARGE" };
const char* const resetNames[]={"CANCEL","RESET"};
const char* const labels[]={"DS LAYOUT","AUTO BATTLES","HUD","COMPANION BUTTON","SCREEN BUTTON","STYLUS SPEED","QUICK MENU","RESET SETTINGS","BATTLE STRIP","MINIMAL HUD","POKé BALLS","REPEL STEPS","MINIMAP","DARK MODE","AUTO-RUN","TAB BAR","MORE TABS","HUD CURSOR BUTTON","HUD SIZE"};
const char* const* choices(int row){
  if(row==DsCompanionUi::HudSize)return hudSizeNames;
  return row==DsCompanionUi::Layout?layoutNames:row==DsCompanionUi::PenSpeed?speedNames:
    row==DsCompanionUi::Reset?resetNames:row==DsCompanionUi::HudButton?hudButtonNames:buttonNames;
}
int count(int row){return row==DsCompanionUi::HudSize?PD_HUD_SIZES:row==DsCompanionUi::Layout?DS_LAYOUTS:row==DsCompanionUi::CompanionButton?4:
 row==DsCompanionUi::ScreenButton||row==DsCompanionUi::HudButton?5:row==DsCompanionUi::PenSpeed?3:2;}
}
void DsCompanionUi::defaults(){
  pd_settings_defaults(&settings);settings.hud=false;
  settings.tabBar=dsTabs;
  layout=0;autoBattles=1;companionButton=0;screenButton=1;penSpeed=1;battleStrip=true;hudMinimal=false;darkMode=false;autoRun=false;
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
      if(key=="ds_darkmode" && v<2)darkMode=v;
      if(key=="ds_autorun" && v<2)autoRun=v;
      if(key=="ds_penspeed" && v<3)penSpeed=v;
    }
  }
  if(companionButton==screenButton)screenButton=(companionButton+1)%4;
  if(hud_id()<0)settings.hudBtn=PD_BTN_NONE;
  settings.tabBar &= dsTabs;
  if (!settings.tabBar) settings.tabBar=dsTabs;
  pd_ui_init(&frame,&settings);pd_ui_init(&options,&settings);
  frame.availableTabs=dsTabs;
  options.keepBackdrop=true;
  frame.shoulderTabs=true;
  frame.hudLayoutFixed=true; // hud_ds_draw has one layout
  frame.guideController=true;
  frame.contentCtx=this;frame.content=[](void* ctx,pd_ui* u,pd_canvas* c){static_cast<DsCompanionUi*>(ctx)->draw_body(u,c);};
  (void)loaded;
}
bool DsCompanionUi::save(){
  if(path.empty()){error="Settings storage unavailable";return false;}
  const std::string temp=path+".tmp";
  if(!pd_settings_save(&settings,temp.c_str())){error="Could not save settings";return false;}
  FILE* f=std::fopen(temp.c_str(),"a");bool ok=f;
  if(f){
    ok=std::fprintf(f,"ds_layout=%d\nds_battles=%d\nds_companionbtn=%d\nds_screenbtn=%d\nds_penspeed=%d\nds_battlestrip=%d\nds_hudminimal=%d\nds_darkmode=%d\nds_autorun=%d\n",layout,autoBattles,companionButton,screenButton,penSpeed,battleStrip,hudMinimal,darkMode,autoRun)>0;
    if(std::fflush(f) || fsync(fileno(f)))ok=false;
    if(std::fclose(f))ok=false;
  }
  if(ok && std::rename(temp.c_str(),path.c_str())==0){error.clear();return true;}
  std::remove(temp.c_str());error="Could not save settings";return false;
}
void DsCompanionUi::set_game(DsGame value,bool soulSilverVersion){
  game=value;
  soulSilver=soulSilverVersion;
  switch(game){
    case DsGame::Platinum:presentation.title="Pokémon Platinum";break;
    case DsGame::Johto:presentation.title=soulSilver?"Pokémon SoulSilver":"Pokémon HeartGold";break;
    case DsGame::Black:presentation.title="Pokémon Black";break;
    case DsGame::White:presentation.title="Pokémon White";break;
    case DsGame::Black2:presentation.title="Pokémon Black 2";break;
    case DsGame::White2:presentation.title="Pokémon White 2";break;
    default:presentation.title=nullptr;break;
  }
  frame.availableTabs=dsTabs;
  if(game==DsGame::Unsupported){
    frame.availableTabs=1<<PD_TAB_PARTY;
    frame.tab=PD_TAB_SETTINGS;
  }
}
unsigned DsCompanionUi::companion_id()const{return buttonIds[companionButton];}
int DsCompanionUi::screen_id()const{return screenButton<4?int(buttonIds[screenButton]):-1;}
int DsCompanionUi::hud_id()const{
  const int id=hudButtonIds[settings.hudBtn];
  return id==int(companion_id()) || id==screen_id() ? -1 : id;
}
int DsCompanionUi::pen_step()const{return penSpeed==0?1:penSpeed==1?3:6;}
int DsCompanionUi::value(int row)const{
  if(row==HudButton)return settings.hudBtn;
  if(row==HudSize)return settings.hudSize;
  if(row==DarkMode)return darkMode;
  if(row==AutoRun)return autoRun;
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
  else if(row==CompanionButton){
    if(int(buttonIds[v])==hud_id()){error="Button already controls HUD";return;}
    if(screenButton==v)screenButton=companionButton;
    companionButton=v;
  }
  else if(row==ScreenButton){
    if(companionButton==v){error="Button already opens companion";return;}
    if(v<4 && int(buttonIds[v])==hud_id()){error="Button already controls HUD";return;}
    screenButton=v;
  }
  else if(row==HudButton){
    if(v!=PD_BTN_NONE && (hudButtonIds[v]==int(companion_id()) || hudButtonIds[v]==screen_id())){
      error="Button already opens companion or switches screens";return;
    }
    settings.hudBtn=v;
  }
  else if(row==PenSpeed)penSpeed=v;
  else if(row==QuickStyle)settings.quickGrid=v;
  else if(row==BattleStrip)battleStrip=v;
  else if(row==HudMinimal)hudMinimal=v;
  else if(row==HudSize)settings.hudSize=v;
  else if(row==DarkMode)darkMode=v;
  else if(row==AutoRun){
    if(game==DsGame::Unsupported){error="Auto-Run unavailable for this ROM";return;}
    autoRun=v;
  }
  else if(row==HudMap)settings.hudHidden^=1<<PD_HUD_MAP;
  else if(row==HudBalls||row==HudRepel)settings.hudHidden^=1<<(row==HudBalls?PD_HUD_BALLS:PD_HUD_REPEL);
  else if(row==Reset && v){defaults();++displayRevision;frame.settings=&settings;options.settings=&settings;}
  save();
}
void DsCompanionUi::draw_settings(pd_canvas* c){
  const auto& s=*snapshot;
  char money[32];
  pd_ui_format_money(s.valid?s.money:-1,money,sizeof money);
  const char* values[]={layoutNames[layout],autoBattles?"ON":"OFF",settings.hud?"ON":"OFF",
    buttonNames[companionButton],buttonNames[screenButton],speedNames[penSpeed],settings.quickGrid?"GRID":"RING","...",battleStrip?"ON":"OFF",hudMinimal?"ON":"OFF",value(HudBalls)?"ON":"OFF",value(HudRepel)?"ON":"OFF",value(HudMap)?"ON":"OFF",darkMode?"ON":"OFF",game==DsGame::Unsupported?"N/A":autoRun?"ON":"OFF",game==DsGame::Unsupported?"N/A":"EDIT",
    (settings.tabBar & dsTabs)==dsTabs?"NONE HIDDEN":"OPEN",hudButtonNames[settings.hudBtn],hudSizeNames[value(HudSize)]};
  constexpr int infoCount=3;
  pd_option_row rows[Rows+infoCount]={{"PLACE",s.valid?ds_place(s.map,s.game):"-"},
    {"MONEY",money},{"HOST","PokeDaisy core"}};
  int n=infoCount;
  for(const int row:settingsOrder){
    if(row==setting)options.optionSelected=n;
    rows[n++]={labels[row],values[row]};
  }
  pd_option_source source{"SETTINGS",rows,n,infoCount};
  options.optionSource=&source;
  pd_ui_options_view(&options,c,&presentation,&battle);options.optionSource=nullptr;
}
const std::vector<int>* DsCompanionUi::dex_order() const{
  if(dexArt.regional.empty() || !snapshot)return nullptr;
  if(dexMode>=0)return dexMode?nullptr:&dexArt.regional;
  if(snapshot->nationalDex)return nullptr;
  if(ds_unova(game)){
    // Android infers the National Dex from seeing a species outside Unova.
    std::array<bool,650> member{};
    for(int species:dexArt.regional)member[species]=true;
    for(size_t i=1;i<snapshot->dexSeen.size() && i<member.size();++i)
      if(snapshot->dexSeen[i] && !member[i])return nullptr;
  }
  return &dexArt.regional;
}

void DsCompanionUi::draw_body(pd_ui* u,pd_canvas* c){
  if(u->tab==PD_TAB_SETTINGS){draw_settings(c);return;}
  if(!supported || !snapshot->valid){
    pd_title_box(c,4,4,c->w-8,24,PD_TITLE_FILL);
    pd_text(c,12,9,supported?"WAITING FOR SAVE DATA":"COMPANION UNAVAILABLE FOR THIS ROM",PD_TITLE_TEXT,PD_TITLE_SHADOW);return;
  }
  if(u->tab==PD_TAB_PARTY)
    ds_party_view(c,*snapshot,*icons,selected,summary,page,&dexArt,learnRow,&learnLast);
  if(u->tab==PD_TAB_BAG){
    const auto visible=ds_bag_view(c,*snapshot,pocket,scroll,details,frame.bagSort,bagTabKey);
    bagPockets=visible.occupied;
    if(pocket!=visible.active){pocket=visible.active;scroll=0;details=false;}
  }
  if(u->tab==PD_TAB_DEX){
    const auto* regional=dex_order();
    if(dexEntry){
      const auto entries=ds_dex_rows(*snapshot,filter,regional);
      if(dexCachedSpecies!=dexEntry){
        dexPage=ds_dex_page(dexArt,game,dexEntry);
        dexEvolutions=ds_dex_evolutions(dexArt,dexEntry);
        dexSpots.clear();
        dexSpotsReady=false;
        dexCachedSpecies=dexEntry;
      }
      if(dexSection==DsDexSection::Area && !dexSpotsReady){
        dexSpots=ds_guide_spots(game,dexEntry,soulSilver);
        dexSpotsReady=true;
      }
      const auto* details=size_t(dexEntry)<dexArt.details.size()?&dexArt.details[dexEntry]:nullptr;
      const int number=dexRow<int(entries.size())?entries[dexRow].number:dexEntry;
      ds_dex_entry(c,*snapshot,dexEntry,number,*icons,details,dexPage,dexEvolutions,dexSpots,
                   dexRow,int(entries.size()),dexSection,dexDetailRow);
    }else{
      const char* label=dexArt.regional.empty()?nullptr:!regional?"NATIONAL DEX":
        game==DsGame::Platinum?"SINNOH DEX":game==DsGame::Johto?"JOHTO DEX":"UNOVA DEX";
      ds_dex_view(c,*snapshot,filter,dexRow,dexFilterFocus,icons,regional,label);
    }
  }
  if(u->tab==PD_TAB_GUIDE){
    if(guideMap!=snapshot->map){
      guideMap=snapshot->map;
      guideLoadedPage=-1;
      if(u->guidePage==int(GuidePage::Here))pd_ui_guide_reset(u);
    }
    if(guideLoadedPage!=u->guidePage){
      guideLoadedPage=u->guidePage;
      guideRows=ds_guide_rows(*snapshot,static_cast<GuidePage>(u->guidePage),soulSilver);
    }
    ds_guide_view(u,c,*snapshot,guideRows,soulSilver);
  }
  if(u->tab==PD_TAB_MAP){
    ds_map_view(c,*snapshot,*art,mapX,mapY,mapAction);
  }
}
void DsCompanionUi::update_battle(){
  const bool was=frame.battleWasOn;
  pd_ui_update(&frame,&battle);
  if(was!=frame.battleWasOn){
    frame.focusId=-1;
    picker=-1;
  }
}
void DsCompanionUi::draw(pd_canvas* c,const DsCompanion& s,bool match,const DsMapArt& map,const std::vector<std::vector<uint32_t>>& monIcons){
  snapshot=&s;art=&map;icons=&monIcons;supported=match;
  settings.darkMode=darkMode;
  pd_look_apply(nullptr,&settings);
  battleSource={
    this,
    [](void* ctx,int id){return ds_species(id,static_cast<DsCompanionUi*>(ctx)->game);},
    [](void* ctx,int id){return ds_move(id,static_cast<DsCompanionUi*>(ctx)->game);},
    [](void* ctx,int id){return ds_move_type(id,static_cast<DsCompanionUi*>(ctx)->game);},
    [](void* ctx,int id){return ds_move_power(id,static_cast<DsCompanionUi*>(ctx)->game);},
    [](void* ctx,int id,int* a,int* b){
      const auto game=static_cast<DsCompanionUi*>(ctx)->game;
      *a=ds_species_type(id,0,game);if(*a<0)*a=PD_TYPE_NONE;
      *b=ds_species_type(id,1,game);if(*b<0)*b=PD_TYPE_NONE;
    },
    [](void* ctx,pd_canvas* c,int id,int x,int y,bool faded){
      return ds_draw_party_icon(c,*static_cast<DsCompanionUi*>(ctx)->icons,id,x,y,1,faded);
    },
  };
  frame.battleSource=&battleSource;
  frame.keyFocus=frame.tab==PD_TAB_BATTLE;
  pd_host_info host{};host.frontendOwnsPlayback=true;
  frame.keepBackdrop=true;
  ds_companion_backdrop(c,this->game,frame.tab,pocket);
  pd_ui_tab_menu_focus(&frame,choice);
  pd_ui_draw(&frame,c,&presentation,&battle,&host);
  pd_ui_tab_menu_focus(&frame,-1);
  if(frame.tab==PD_TAB_MAP && placeRow>=0){
    const int count=std::min(8,int(mapPlaces.size()));
    const int first=std::clamp(placeRow-count/2,0,int(mapPlaces.size())-count);
    const char* names[8];
    for(int i=0;i<count;++i)names[i]=mapPlaces[first+i].name;
    pd_ui_choices_view(c,"PLACES",names,count,placeRow-first);
  }
  if(picker>=0)pd_ui_choices_view(c,labels[picker],choices(picker),count(picker),choice);
  if(!error.empty()){
    pd_fill(c,4,c->h-41,c->w-8,16,0x602020);
    pd_text_fit(c,8,c->h-43,c->w-16,error.c_str(),0xffffff,PD_NO_SHADOW);
  }
  pd_look_apply(nullptr,nullptr);
}
// The party grid (two columns, row by row) as Android's focus moves over it, the GB core's focus search in cell units:
// a press goes to the nearest slot that lies that way, one that lines up first; with none that way it stays put.
static int party_grid_step(int selected,int n,int dx,int dy){
  if(n<=0)return 0;
  if(!dx && !dy)return selected;
  int best=selected,score=1<<30;
  for(int to=0;to<n;++to){
    if(to==selected)continue;
    const int cx=to%2-selected%2,cy=to/2-selected/2;
    const int along=dx?cx*dx:cy*dy;
    const int across=std::abs(dx?cy:cx);
    const int cost=along+across*4+(across?100000:0);
    if(along>0 && cost<score){best=to;score=cost;}
  }
  return best;
}
bool DsCompanionUi::control(unsigned pressed){
  auto hit=[&](unsigned id){return bool(pressed&(1u<<id));};
  int dy=hit(RETRO_DEVICE_ID_JOYPAD_DOWN)-hit(RETRO_DEVICE_ID_JOYPAD_UP);
  int dx=hit(RETRO_DEVICE_ID_JOYPAD_RIGHT)-hit(RETRO_DEVICE_ID_JOYPAD_LEFT);
  if(picker<0 && placeRow<0 && (frame.overlay==PD_OVERLAY_NONE || frame.overlay==PD_OVERLAY_MORE) &&
     (hit(RETRO_DEVICE_ID_JOYPAD_L) || hit(RETRO_DEVICE_ID_JOYPAD_R))){
    pd_ui_next_tab(&frame,hit(RETRO_DEVICE_ID_JOYPAD_R)?1:-1);
    pd_ui_guide_reset(&frame);
    choice=0;
    scroll=dexEntry=0;placeRow=mapAction=dexFilterFocus=-1;bagTabKey=-1;summary=details=false;error.clear();return false;
  }
  if(frame.overlay==PD_OVERLAY_TAB_BAR || frame.overlay==PD_OVERLAY_MORE || frame.overlay==PD_OVERLAY_HUD){
    const int step=frame.overlay==PD_OVERLAY_MORE && dx ? dx : dy;
    choice=std::clamp(choice+step,0,std::max(0,pd_ui_tab_menu_count(&frame)-1));
    if(hit(RETRO_DEVICE_ID_JOYPAD_B))pd_ui_back(&frame);
    else if(hit(RETRO_DEVICE_ID_JOYPAD_A)){
      pd_ui_tab_menu_choose(&frame,choice);
      if(pd_ui_take_action(&frame)==PD_ACTION_SETTINGS_CHANGED)save();
      if(frame.overlay==PD_OVERLAY_NONE){
        summary=details=false;dexEntry=0;placeRow=mapAction=-1;
      }
    }
    return false;
  }
  if(frame.overlay==PD_OVERLAY_GUIDE_NOTICE){
    if(hit(RETRO_DEVICE_ID_JOYPAD_B))pd_ui_back(&frame);
    else pd_ui_guide_control(&frame,dx,dy,hit(RETRO_DEVICE_ID_JOYPAD_A));
    if(pd_ui_take_action(&frame)==PD_ACTION_SETTINGS_CHANGED)save();
    return false;
  }
  if(picker>=0){
    choice=std::clamp(choice+dy,0,count(picker)-1);
    if(hit(RETRO_DEVICE_ID_JOYPAD_B)){picker=-1;return false;}
    if(hit(RETRO_DEVICE_ID_JOYPAD_A)){int row=picker;picker=-1;apply(row,choice);}return false;
  }
  if(frame.tab==PD_TAB_SETTINGS){
    const int index=int(std::find(std::begin(settingsOrder),std::end(settingsOrder),setting)-std::begin(settingsOrder));
    setting=settingsOrder[pd_ui_list_step(index,listed,dy)];
    if(hit(RETRO_DEVICE_ID_JOYPAD_B))return true;
    if(hit(RETRO_DEVICE_ID_JOYPAD_A)){
      error.clear();
      if(setting==Hud){
        frame.overlay=PD_OVERLAY_HUD;
        choice=0;
      }else if(setting==TabBar || setting==MoreTabs){
        if(game!=DsGame::Unsupported){
          frame.overlay=setting==TabBar?PD_OVERLAY_TAB_BAR:PD_OVERLAY_MORE;
          choice=0;
          if(!pd_ui_tab_menu_count(&frame))frame.overlay=PD_OVERLAY_NONE;
        }
      }else if(setting==Battles || setting==QuickStyle || (setting>=BattleStrip && setting<HudButton))apply(setting,!value(setting));
      else{picker=setting;choice=value(setting);}
    }return false;
  }
  if(frame.tab==PD_TAB_BATTLE){
    if(hit(RETRO_DEVICE_ID_JOYPAD_B))return !pd_ui_back(&frame);
    if(dx)pd_ui_focus_move(&frame,dx<0?2:3);
    else if(dy)pd_ui_focus_move(&frame,dy<0?0:1);
    if(hit(RETRO_DEVICE_ID_JOYPAD_A))pd_ui_focus_press(&frame);
    return false;
  }
  if(frame.tab==PD_TAB_PARTY){
    int n=snapshot?int(snapshot->party.size()):0;
    const int oldSelected=selected;
    if(summary){
      const int step=dy?dy:page==PD_SUMMARY_LEARN?0:dx;
      selected=std::clamp(selected+step,0,std::max(0,n-1));
    }else selected=party_grid_step(selected,n,dx,dy);
    const bool confirm=hit(RETRO_DEVICE_ID_JOYPAD_A);
    if(summary){
      if(oldSelected!=selected)learnRow=0;
      if(n>0 && !snapshot->party[selected].egg){
        if(hit(RETRO_DEVICE_ID_JOYPAD_SELECT)){page=page==2?0:2;learnRow=0;}
        else if(confirm)page=page==0?1:0;
        else if(dx && page==PD_SUMMARY_LEARN)learnRow=std::clamp(learnRow+dx,0,learnLast);
      }
    }else if(confirm && n>0){
      summary=true;
      page=0;
    }
    if(hit(RETRO_DEVICE_ID_JOYPAD_B)){if(summary)summary=false;else return true;}
  }else if(frame.tab==PD_TAB_BAG){
    const int tabs=ds_bag_tabs(game);
    if(bagTabKey>=0){
      // On the pocket tab bar: up / down walk it, A opens the pocket under the cursor, RIGHT / B go back to the list.
      std::vector<int> keys;
      for(int k=0;k<tabs;++k)if(bagPockets&(1u<<k))keys.push_back(k);
      if(dy)bagTabKey=pd_ui_bag_tab_step(keys.data(),int(keys.size()),bagTabKey,dy);
      if(hit(RETRO_DEVICE_ID_JOYPAD_A)){pocket=bagTabKey;scroll=0;details=false;bagTabKey=-1;}
      else if(dx>0 || hit(RETRO_DEVICE_ID_JOYPAD_B))bagTabKey=-1;
      return false;
    }
    if(!details && hit(RETRO_DEVICE_ID_JOYPAD_SELECT)){
      pd_ui_bag_cycle_sort(&frame);
      scroll=0;
    }
    if(dx<0 && __builtin_popcount(bagPockets)>1){
      // LEFT closes an open item and goes to the tab bar in the same press, on the pocket that is open.
      details=false;
      bagTabKey=pocket;
      return false;
    }
    const int count=snapshot?ds_bag_count(*snapshot,pocket):0;
    scroll=std::clamp(scroll+dy,0,std::max(0,count-1));
    if(count>0 && hit(RETRO_DEVICE_ID_JOYPAD_A))details=!details;
    if(hit(RETRO_DEVICE_ID_JOYPAD_B)){if(details)details=false;else return true;}
  }else if(frame.tab==PD_TAB_DEX){
    const auto* regional=dex_order();
    if(dexEntry){
      if(hit(RETRO_DEVICE_ID_JOYPAD_B)){dexEntry=0;return false;}
      if(hit(RETRO_DEVICE_ID_JOYPAD_SELECT)){
        dexSection=static_cast<DsDexSection>((int(dexSection)+1)%int(DsDexSection::Count));dexDetailRow=0;
      }
      const int machineRow=pd_ui_dex_machine_row(int(dexPage.moves.size()));
      int rows=0;
      if(dexSection==DsDexSection::Evolve)rows=int(dexEvolutions.size());
      if(dexSection==DsDexSection::Area)rows=int(dexSpots.size());
      if(dexSection==DsDexSection::Moves){
        rows=machineRow+1+int(dexPage.machines.size());
        if(hit(RETRO_DEVICE_ID_JOYPAD_A))dexDetailRow=dexDetailRow<machineRow?machineRow:0;
      }
      dexDetailRow=std::clamp(dexDetailRow+dy,0,std::max(0,rows-1));
      if(dx && snapshot){
        const auto entries=ds_dex_rows(*snapshot,filter,regional);
        // Android's Previous / Next entry wraps round the filtered list; an unseen species stays unopenable here.
        const int n=int(entries.size());
        for(int i=1;i<n;++i){
          const int row=((dexRow+dx*i)%n+n)%n;
          const int species=entries[row].species;
          if(snapshot->dexSeen[species]){
            dexEntry=species;dexRow=row;dexDetailRow=0;
            break;
          }
        }
      }
      return false;
    }
    if(hit(RETRO_DEVICE_ID_JOYPAD_SELECT)){filter=(filter+1)%4;dexRow=0;dexFilterFocus=-1;}
    if(dexFilterFocus>=0){
      const int last=dexArt.regional.empty()?3:4;
      dexFilterFocus=std::clamp(dexFilterFocus+dy,0,last);
      if(hit(RETRO_DEVICE_ID_JOYPAD_A)){
        if(dexFilterFocus==4)dexMode=regional?1:0;
        else filter=dexFilterFocus;
        dexRow=0;dexFilterFocus=-1;
      }else if(dx>0 || hit(RETRO_DEVICE_ID_JOYPAD_B))dexFilterFocus=-1;
      return false;
    }
    if(dx<0){dexFilterFocus=filter;return false;}
    if(snapshot && (dy || hit(RETRO_DEVICE_ID_JOYPAD_A))){
      const auto entries=ds_dex_rows(*snapshot,filter,regional);
      dexRow=std::clamp(dexRow+dy,0,std::max(0,int(entries.size())-1));
      if(hit(RETRO_DEVICE_ID_JOYPAD_A) && !entries.empty()){
        const int species=entries[dexRow].species;
        if(snapshot->dexSeen[species]){dexEntry=species;dexDetailRow=0;}
      }
    }
    if(hit(RETRO_DEVICE_ID_JOYPAD_B))return true;
  }else if(frame.tab==PD_TAB_GUIDE){
    if(hit(RETRO_DEVICE_ID_JOYPAD_B))return !pd_ui_back(&frame);
    pd_ui_guide_control(&frame,dx,dy,hit(RETRO_DEVICE_ID_JOYPAD_A));
  }else if(frame.tab==PD_TAB_MAP){
    auto openPlaces=[&](){
      if(!snapshot)return;
      mapPlaces=ds_map_places(*snapshot);
      if(!mapPlaces.empty())placeRow=0;
    };
    auto recenter=[&](){
      if(snapshot){mapX=snapshot->mapX;mapY=snapshot->mapY;}
    };
    if(placeRow>=0){
      placeRow=pd_ui_list_step(placeRow,int(mapPlaces.size()),dy);
      if(hit(RETRO_DEVICE_ID_JOYPAD_B)){placeRow=-1;mapAction=0;}
      else if(hit(RETRO_DEVICE_ID_JOYPAD_A)){
        const auto pos=mapPlaces[placeRow].position;
        mapX=pos.x;mapY=pos.y;placeRow=mapAction=-1;
      }
      return false;
    }
    switch(pd_ui_map_control(&mapAction,2,dx,dy,hit(RETRO_DEVICE_ID_JOYPAD_A),
                             hit(RETRO_DEVICE_ID_JOYPAD_B),hit(RETRO_DEVICE_ID_JOYPAD_SELECT))){
      case PD_MAP_CLOSE:
        mapAction=-1;
        if(frame.moreOpened){
          frame.overlay=PD_OVERLAY_MORE;
          choice=0;
          return false;
        }
        return true;
      case PD_MAP_OPEN_PLACES: openPlaces(); break;
      case PD_MAP_CHOOSE:
        if(mapAction==0)openPlaces();
        else{recenter();mapAction=-1;}
        break;
      case PD_MAP_CENTER: recenter(); break;
      case PD_MAP_MOVE:{
        const auto bounds=ds_map_cursor_max(game);
        mapX=std::clamp(mapX+dx,0,bounds.x);
        mapY=std::clamp(mapY+dy,0,bounds.y);
        break;
      }
      case PD_MAP_IDLE: break;
    }
  }
  return false;
}
