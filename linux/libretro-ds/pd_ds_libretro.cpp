// SPDX-License-Identifier: GPL-3.0-or-later
// Keep DSperate's libretro lifecycle, SRAM and state format. Only presentation
// and input are adapted; there is no second launcher or save writer.
#define retro_set_environment ds_set_environment
#define retro_set_video_refresh ds_set_video_refresh
#define retro_set_input_state ds_set_input_state
#define retro_get_system_info ds_get_system_info
#define retro_get_system_av_info ds_get_system_av_info
#define retro_load_game ds_load_game
#define retro_unload_game ds_unload_game
#define retro_run ds_run
#define retro_reset ds_reset
#define retro_unserialize ds_unserialize
#include "src/libretro.cpp"
#undef retro_set_environment
#undef retro_set_video_refresh
#undef retro_set_input_state
#undef retro_get_system_info
#undef retro_get_system_av_info
#undef retro_load_game
#undef retro_unload_game
#undef retro_run
#undef retro_reset
#undef retro_unserialize
#include "companion.h"
#include "companion_ui.h"
#include "battle_strip.h"
#include "layout.h"
#include "party_view.h"
#include "bag_view.h"
#include "dex_view.h"
#include "map_view.h"
#include "browser.h"
#include "data_gen.h"
#include "pk_nds_field.h"
#include "core/crypto/sha1.h"
#include <fstream>
extern "C" {
#include "pd_style.h"
#include "pd_ui.h"
#include "../hud.h"
#include "../quick_menu.h"
#include "pd_tables.h"
}
namespace pd_ds {
retro_environment_t environment;
retro_video_refresh_t video;
retro_input_state_t input;
const PkNdsFieldGame* game;
DsCompanion snapshot;
DsMapArt map;
std::vector<std::vector<uint32_t>> icons;
bool supported=false, companion=false, quickOpen=false;
int quickSelected=0;
uint32_t quick_pixels[341*256];
DsCompanionUi shell;
DsScenePolicy scene;
DsBattleStrip battleStrip;
DsBattleTap battleTap;
DsBattleView drawnBattle;
std::string battleName;
std::array<uint32_t,256*192> battlePanelArt;
bool battlePanelArtValid=false;
unsigned battleHeld=0;
int holdStrip=0,restoreGrace=0;
bool battleActive=false,injectBattleTap=false;
constexpr unsigned battleKeys=(1u<<RETRO_DEVICE_ID_JOYPAD_A)|(1u<<RETRO_DEVICE_ID_JOYPAD_B)|
  (1u<<RETRO_DEVICE_ID_JOYPAD_UP)|(1u<<RETRO_DEVICE_ID_JOYPAD_DOWN)|
  (1u<<RETRO_DEVICE_ID_JOYPAD_LEFT)|(1u<<RETRO_DEVICE_ID_JOYPAD_RIGHT);
DsArrangement arrangement;
bool manualTouch=false,fieldValid=false;
uint32_t stage[512*384],fieldFrame[256*192];
unsigned held_frames[16]={};
bool select_used=false, select_pulse=false;
unsigned old_buttons=0, buttons=0, tick=0, width=256, height=192;
int screen_override=0, cursor_x=128, cursor_y=96;
int layout=0; // 0 top, 1 bottom, 2 side by side, 3 stacked
uint16_t pixels[512*384];
uint32_t ui_pixels[341*256];

uint32_t read32(uint32_t a) {
  if (!nds || a<0x02000000 || a>0x023ffffc) return 0;
  return nds->bus.dma_read32(ds::Cpu::ARM9,a);
}
void clear_battle() {
  battleStrip={};battleTap={};drawnBattle={};battleName.clear();battlePanelArtValid=false;
  battleHeld=0;holdStrip=restoreGrace=0;battleActive=injectBattleTap=false;
}
bool wants_strip() {
  return supported && shell.battleStrip && shell.layout==DS_ONE && !screen_override &&
    !manualTouch && !companion && !quickOpen;
}
DsBattleView battle_view(int bits) {
  if(!wants_strip() || (buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)))return {};
  return ds_read_platinum_battle(game,read32,bits);
}
void update_battle(unsigned pressed) {
  battleHeld&=buttons;
  if(!wants_strip() || (buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT))) {
    battleStrip={};battleTap={};injectBattleTap=false;return;
  }
  const auto view=battle_view(pk_nds_field_state(game,read32,nullptr,0));
  if(view.panel!=DsBattlePanel::Native)battleHeld|=buttons&battleKeys;
  if(restoreGrace>0){--restoreGrace;pressed=0;}
  auto hit=[&](unsigned id){return bool(pressed&(1u<<id));};
  battleStrip.select(view,
    hit(RETRO_DEVICE_ID_JOYPAD_RIGHT)-hit(RETRO_DEVICE_ID_JOYPAD_LEFT),
    hit(RETRO_DEVICE_ID_JOYPAD_DOWN)-hit(RETRO_DEVICE_ID_JOYPAD_UP),
    hit(RETRO_DEVICE_ID_JOYPAD_A),hit(RETRO_DEVICE_ID_JOYPAD_B));
  injectBattleTap=battleStrip.tapFrames>0;
  battleTap=battleStrip.tick();
}
void clear() {
  clear_battle();
  supported=companion=quickOpen=select_used=select_pulse=false;quickSelected=0; game=nullptr; snapshot={};map={};icons.clear();
  old_buttons=buttons=tick=0;std::fill(held_frames,held_frames+16,0);
  screen_override=0;cursor_x=128;cursor_y=96;layout=0;
  scene.reset();arrangement={};manualTouch=fieldValid=false;
}
bool env(unsigned cmd,void* data) {
  if(cmd==RETRO_ENVIRONMENT_SET_VARIABLES) {
    auto vars=static_cast<const retro_variable*>(data);
    std::vector<retro_variable> all;
    for(;vars && vars->key;++vars)all.push_back(*vars);
    all.push_back({"pokedaisy_ds_layout","Initial DS layout (saved companion settings take priority); auto|top|bottom|stacked"});
    all.push_back({"pokedaisy_ds_hud","Initial party HUD (saved companion settings take priority); disabled|enabled"});
    all.push_back({nullptr,nullptr});
    return environment(cmd,all.data());
  }
  if(cmd==RETRO_ENVIRONMENT_SET_PIXEL_FORMAT) {
    retro_pixel_format f=RETRO_PIXEL_FORMAT_RGB565;
    return environment(cmd,&f);
  }
  return environment(cmd,data);
}
void geometry(unsigned w,unsigned h) {
  if(width==w && height==h)return;
  width=w;height=h;
  retro_game_geometry g{w,h,512,384,float(w)/h};
  environment(RETRO_ENVIRONMENT_SET_GEOMETRY,&g);
}
uint16_t rgb565(uint32_t p) {return ((p>>8)&0xf800)|((p>>5)&0x7e0)|((p>>3)&31);}
void refresh() {
  snapshot=supported?ds_read_companion(nds->bus.main_ram.get(),nds->bus.main_ram_size()):DsCompanion{};
}
void choose_layout() {
  int bits=game?pk_nds_field_state(game,read32,nullptr,0):0;
  auto one_colour=[](const uint32_t* px){
    uint32_t first=px[0]&0xffffff;
    for(int gy=0;gy<12;++gy)for(int gx=0;gx<16;++gx)
      if((px[(gy*16+8)*256+gx*16+8]&0xffffff)!=first)return false;
    return true;
  };
  bool topBlank=one_colour(nds->gpu.framebuffer(0)),bottomBlank=one_colour(nds->gpu.framebuffer(1));
  if(topBlank&&!bottomBlank)bits|=PK_NDS_FIELD_TOUCH;
  const auto view=battle_view(bits);
  battleActive=view.panel!=DsBattlePanel::Native;
  if(battleActive){
    if(drawnBattle.panel!=view.panel || drawnBattle.personality!=view.personality ||
       drawnBattle.species!=view.species ||
       (view.panel==DsBattlePanel::Moves &&
        (drawnBattle.moves!=view.moves || drawnBattle.pp!=view.pp)))battlePanelArtValid=false;
    if(!battlePanelArtValid){
      const auto* lower=nds->gpu.framebuffer(1);
      const bool ready=view.panel==DsBattlePanel::Actions?ds_platinum_has_command_art(lower):
        ds_platinum_has_move_art(lower,view);
      if(ready){
        std::copy_n(lower,battlePanelArt.size(),battlePanelArt.begin());
        battlePanelArtValid=true;
      }
    }
    if(drawnBattle.personality!=view.personality || drawnBattle.species!=view.species || battleName.empty()){
      const auto party=ds_read_companion(nds->bus.main_ram.get(),nds->bus.main_ram_size()).party;
      auto mon=std::find_if(party.begin(),party.end(),[&](const DsMon& candidate){
        return candidate.personality==view.personality && candidate.species==view.species;
      });
      battleName=mon!=party.end() && !mon->nickname.empty()?mon->nickname:ds_species(view.species);
    }
    drawnBattle=view;
    holdStrip=18;
  }else if(!wants_strip() || !(bits&PK_NDS_FIELD_BATTLE) || (bits&PK_NDS_FIELD_TOUCH_BATTLE_MENU)){
    holdStrip=0;drawnBattle={};battlePanelArtValid=false;
  }else if(holdStrip>0 && !(bits&PK_NDS_FIELD_TOUCH_FADES)){
    --holdStrip;
  }
  // PIP already exposes the native battle panel; it must not float twice.
  scene.tick(bits,topBlank,bottomBlank,!battleActive&&(shell.autoBattles||wants_strip())&&shell.layout!=DS_PIP);
  int mode=screen_override==1?DS_BOTTOM:screen_override==2?DS_TOP:shell.layout;
  arrangement=ds_arrange(mode,scene,game!=nullptr,manualTouch&&screen_override==0);
  if(battleActive && !scene.dark)arrangement={{0,0,512,384},{}};
  layout=mode==DS_BOTTOM?1:mode==DS_SIDE?2:mode==DS_STACKED?3:0;
  if(scene.hud&&!scene.dark){std::memcpy(fieldFrame,nds->gpu.framebuffer(0),sizeof fieldFrame);fieldValid=true;}
}
void draw_companion() {
  pd_canvas c;pd_canvas_init(&c,341,256,ui_pixels);
  shell.draw(&c,snapshot,supported,map,icons);
  geometry(341,256);
  for(unsigned i=0;i<341*256;++i)pixels[i]=rgb565(ui_pixels[i]);
  video(pixels,width,height,width*2);
}
void draw_quick() {
  pd_canvas c;pd_canvas_init(&c,341,256,quick_pixels);
  pd_game presentation{};
  const quick_item items[]={
    {QUICK_COMPANION,false,false,nullptr,0,0,"PARTY"},
    {QUICK_COMPANION,false,false,nullptr,0,0,"BAG"},
    {QUICK_COMPANION,false,false,nullptr,0,0,"MAP"},
    {QUICK_COMPANION,false,false,nullptr,0,0,"DEX"},
    {QUICK_COMPANION,false,false,nullptr,0,0,"SETTINGS"},
    {QUICK_HUD,false,shell.settings.hud,nullptr,0,0,"HUD"}};
  quick_set_grid(shell.settings.quickGrid);
  quick_draw(&c,&presentation,items,6,quickSelected);
  quick_finish(&c);
  const uint32_t* src=nds->gpu.framebuffer(layout==1?1:0);
  geometry(341,256);
  for(unsigned y=0;y<256;++y)for(unsigned x=0;x<341;++x){
    uint32_t base=x>=42 && x<298 && y>=32 && y<224?src[(y-32)*256+x-42]&0xffffff:0;
    base=(base&0xfefefe)>>1;
    uint32_t p=quick_pixels[y*341+x];
    if(p!=QUICK_CLEAR){unsigned a=p>>24;
      unsigned r=(((p>>16)&255)*a+((base>>16)&255)*(255-a))/255;
      unsigned g=(((p>>8)&255)*a+((base>>8)&255)*(255-a))/255;
      unsigned b=((p&255)*a+(base&255)*(255-a))/255;
      base=(r<<16)|(g<<8)|b;
    }
    pixels[y*341+x]=rgb565(base);
  }
  video(pixels,341,256,341*2);
}
void present(const void* data,unsigned,unsigned,size_t) {
  if(companion){draw_companion();return;}
  // Refresh composition even when DSperate skipped rasterization: switching
  // screens must still display the latest retained framebuffer.
  (void)data;
  choose_layout();
  const unsigned w=512,h=384;
  geometry(w,h);
  const auto overlay=holdStrip>0 && !scene.dark && arrangement.top.w==512 && wants_strip()
    ? +[](uint32_t* out){
        const int focus=drawnBattle.panel==DsBattlePanel::Moves?battleStrip.moveFocus:battleStrip.actionFocus;
        ds_draw_battle_strip(out,ds_platinum_battle_style,drawnBattle,battleName,focus,
                             battleActive&&!battleStrip.tapFrames,tick,
                             battlePanelArtValid?battlePanelArt.data():nullptr);
      } : nullptr;
  ds_compose(stage,arrangement,nds->gpu.framebuffer(0),nds->gpu.framebuffer(1),fieldValid?fieldFrame:nullptr,overlay);
  for(unsigned i=0;i<w*h;++i)pixels[i]=rgb565(stage[i]);
  if((buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT))&&arrangement.touch.w>0){
    int cx=arrangement.touch.x+cursor_x*arrangement.touch.w/256;
    int cy=arrangement.touch.y+cursor_y*arrangement.touch.h/192;
    for(int d=-4;d<=4;++d){
      if(arrangement.touch.contains(cx+d,cy))pixels[cy*w+cx+d]=0xffff;
      if(arrangement.touch.contains(cx,cy+d))pixels[(cy+d)*w+cx]=0xffff;
    }
  }
  if(supported && shell.settings.hud && scene.hud && !scene.dark && arrangement.top.w==512 && arrangement.touch.w==0) {
    if(tick%12==0 || !snapshot.valid)refresh();
    pd_canvas c;pd_canvas_init(&c,256,192,ui_pixels);
    std::fill(ui_pixels,ui_pixels+256*192,QUICK_CLEAR);
    pd_snapshot party{};party.valid=snapshot.valid;party.partyCount=std::min(6,int(snapshot.party.size()));
    for(int i=0;i<party.partyCount;++i){
      const auto& m=snapshot.party[i];auto& d=party.party[i];
      d.species=m.egg?PD_SPECIES_EGG:1;d.level=m.level;d.hp=m.hp;d.maxHp=m.maxHp;d.status=m.status;
    }
    hud_party_draw(&c,&party,&party,[](pd_canvas* c,void* ctx,const pd_mon* m,int x,int y,bool){
      auto* p=static_cast<pd_snapshot*>(ctx);
      const auto& mon=snapshot.party[m-p->party];
      int id=mon.egg?pd_ds_data::iconEgg:mon.species;
      if(id<0 || size_t(id)>=icons.size())return;
      const auto& art=icons[id];if(art.size()!=1024)return;
      for(int j=0;j<32;++j)for(int i=0;i<32;++i){auto p=art[j*32+i];if(p>>24)pd_fill(c,x+i,y+j,1,1,p&0xffffff);}
    }, shell.settings.hudLook);
    // Composite the shared HUD glass over the native game frame.
    for(unsigned i=0;i<256*192;++i){
      uint32_t p=ui_pixels[i];if(p==QUICK_CLEAR)continue;
      unsigned a=p>>24;if(!a)a=255;
      for(unsigned dy=0;dy<2;++dy)for(unsigned dx=0;dx<2;++dx){
        unsigned at=((i/256)*2+dy)*512+(i%256)*2+dx;
        uint16_t old=pixels[at];unsigned r=(old>>11&31)*255/31,g=(old>>5&63)*255/63,b=(old&31)*255/31;
        r=(((p>>16)&255)*a+r*(255-a))/255;
        g=(((p>>8)&255)*a+g*(255-a))/255;b=((p&255)*a+b*(255-a))/255;
        pixels[at]=rgb565((r<<16)|(g<<8)|b);
      }
    }
  }
  video(pixels,w,h,w*2);
}
int16_t filtered_input(unsigned p,unsigned d,unsigned i,unsigned id) {
  if(d==RETRO_DEVICE_JOYPAD) {
    if(id==RETRO_DEVICE_ID_JOYPAD_SELECT && select_pulse)return 1;
    if(companion || quickOpen || (buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)))return 0;
    if(id<16 && (battleHeld&(1u<<id)))return 0;
    if(id==shell.companion_id() || int(id)==shell.screen_id())return 0;
  }
  // DSperate expects a pointer over stacked screens. Reproject the current
  // layout into that convention before its input::apply receives it.
  if(d==RETRO_DEVICE_POINTER) {
    bool stylus=buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT);
    if(stylus) {
      if(id==RETRO_DEVICE_ID_POINTER_PRESSED)return !!(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_A));
      if(id==RETRO_DEVICE_ID_POINTER_X)return cursor_x*65535/256-32767;
      if(id==RETRO_DEVICE_ID_POINTER_Y)return cursor_y*32768/192;
    }
    if(injectBattleTap) {
      if(id==RETRO_DEVICE_ID_POINTER_PRESSED)return battleTap.down;
      if(id==RETRO_DEVICE_ID_POINTER_X)return (battleTap.x*65535+255)/256-32767;
      if(id==RETRO_DEVICE_ID_POINTER_Y)return (battleTap.y*32768+191)/192;
    }
    int x=input(p,d,i,RETRO_DEVICE_ID_POINTER_X),y=input(p,d,i,RETRO_DEVICE_ID_POINTER_Y);
    auto pen=ds_pen(arrangement,(x+32767)*512/65535,(y+32767)*384/65535);
    if(id==RETRO_DEVICE_ID_POINTER_PRESSED)return pen.valid?input(p,d,i,id):0;
    if(id==RETRO_DEVICE_ID_POINTER_X)return pen.valid?pen.x*65535/256-32767:0;
    if(id==RETRO_DEVICE_ID_POINTER_Y)return pen.valid?pen.y*32768/192:0;
  }
  return input(p,d,i,id);
}
void controls() {
  buttons=0;for(unsigned id=0;id<16;++id)if(input(0,RETRO_DEVICE_JOYPAD,0,id))buttons|=1u<<id;
  select_pulse=!(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)) &&
    (old_buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)) && !select_used;
  if(!(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)))select_used=false;
  unsigned edge=buttons&~old_buttons;old_buttons=buttons;
  for(unsigned id=RETRO_DEVICE_ID_JOYPAD_UP;id<=RETRO_DEVICE_ID_JOYPAD_RIGHT;++id){
    held_frames[id]=(buttons&(1u<<id))?held_frames[id]+1:0;
    if((companion || quickOpen) && held_frames[id]>15 && (held_frames[id]-15)%5==0)edge|=1u<<id;
  }
  auto hit=[&](unsigned id){return !!(edge&(1u<<id));};
  if(hit(shell.companion_id()) && (buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)) && !companion){
    quickOpen=!quickOpen;select_used=true;quickSelected=0;return;
  }
  if(quickOpen){
    if(hit(RETRO_DEVICE_ID_JOYPAD_B) || hit(shell.companion_id())){quickOpen=false;return;}
    int dx=hit(RETRO_DEVICE_ID_JOYPAD_RIGHT)-hit(RETRO_DEVICE_ID_JOYPAD_LEFT);
    int dy=hit(RETRO_DEVICE_ID_JOYPAD_DOWN)-hit(RETRO_DEVICE_ID_JOYPAD_UP);
    if(dx || dy){
      quick_set_grid(shell.settings.quickGrid);
      quickSelected=quick_step(6,quickSelected,dx,dy,341,256);
    }
    if(hit(RETRO_DEVICE_ID_JOYPAD_A)){
      if(quickSelected==5){shell.settings.hud=!shell.settings.hud;shell.save();}
      else{
        static const pd_tab tabs[]={PD_TAB_PARTY,PD_TAB_BAG,PD_TAB_MAP,PD_TAB_DEX,PD_TAB_SETTINGS};
        refresh();shell.frame.tab=tabs[quickSelected];shell.picker=-1;companion=true;
      }
      quickOpen=false;
    }
    return;
  }
  if(hit(shell.companion_id())) {
    companion=!companion;
    if(companion){refresh();shell.mapX=snapshot.mapX;shell.mapY=snapshot.mapY;}
  }
  if(!companion) {
    if(shell.screen_id()>=0 && hit(shell.screen_id())){
      if(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)){
        manualTouch=!manualTouch;screen_override=0;select_used=true;
      }else{screen_override=(screen_override+1)%3;manualTouch=false;}
    }
    if(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)) {
      if(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_UP))cursor_y-=shell.pen_step();
      if(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_DOWN))cursor_y+=shell.pen_step();
      if(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_LEFT))cursor_x-=shell.pen_step();
      if(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_RIGHT))cursor_x+=shell.pen_step();
      cursor_x=std::clamp(cursor_x,0,255);cursor_y=std::clamp(cursor_y,0,191);
      // A SELECT tap is forwarded on release; SELECT plus pen controls is consumed.
      unsigned pen_keys=(1u<<RETRO_DEVICE_ID_JOYPAD_UP)|(1u<<RETRO_DEVICE_ID_JOYPAD_DOWN)|
        (1u<<RETRO_DEVICE_ID_JOYPAD_LEFT)|(1u<<RETRO_DEVICE_ID_JOYPAD_RIGHT)|(1u<<RETRO_DEVICE_ID_JOYPAD_A);
      if(buttons&pen_keys){
        select_used=true;
        if(arrangement.touch.w==0){
          if(shell.layout==DS_ONE||shell.layout==DS_PIP){manualTouch=true;screen_override=0;}
          else screen_override=1;
        }
      }
    }
    return;
  }
  unsigned previous_layout=shell.displayRevision;
  if(shell.control(edge))companion=false;
  if(shell.displayRevision!=previous_layout){screen_override=0;manualTouch=false;}

}
}
extern "C" {
RETRO_API void retro_set_environment(retro_environment_t cb){pd_ds::environment=cb;ds_set_environment(pd_ds::env);
  static const retro_input_descriptor d[]={
    {0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_UP,"Up"},
    {0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_DOWN,"Down"},
    {0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_LEFT,"Left"},
    {0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_RIGHT,"Right"},
    {0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_A,"DS A"},
    {0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_B,"DS B"},
    {0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_X,"DS X"},
    {0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_Y,"DS Y"},
    {0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_START,"DS Start"},
    {0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_SELECT,"DS Select / Stylus"},
    {0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_L,"DS L"},
    {0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_R,"DS R"},
    {0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_L2,"Companion"},
    {0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_R2,"Screen Auto Bottom Top"},
    {0,0,0,0,nullptr}};
  cb(RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS,const_cast<retro_input_descriptor*>(d));}
RETRO_API void retro_set_video_refresh(retro_video_refresh_t cb){pd_ds::video=cb;ds_set_video_refresh(pd_ds::present);}
RETRO_API void retro_set_input_state(retro_input_state_t cb){pd_ds::input=cb;ds_set_input_state(pd_ds::filtered_input);}
RETRO_API void retro_get_system_info(retro_system_info* info){ds_get_system_info(info);info->library_name="PokeDaisy DS";info->library_version="0.4-one-screen";}
RETRO_API void retro_get_system_av_info(retro_system_av_info* info){
  ds_get_system_av_info(info);info->geometry={512,384,512,384,4.f/3.f};
}
RETRO_API bool retro_load_game(const retro_game_info* info){
  pd_ds::clear();pd_ds::width=512;pd_ds::height=384;
  if(!ds_load_game(info))return false;
  std::ifstream f(info->path,std::ios::binary);
  std::vector<uint8_t> rom{std::istreambuf_iterator<char>(f),{}};
  if(rom.size()>=512) {
    // Preserve the existing exact-ROM guard: offsets and art are rev-specific.
    uint8_t hash[20];ds::crypto::sha1(rom.data(),rom.size(),hash);
    char hex[41];for(int i=0;i<20;++i)std::sprintf(hex+2*i,"%02x",hash[i]);
    pd_ds::supported=!std::strcmp(hex,pd_ds_data::sha1);
    if(pd_ds::supported){
      pd_ds::game=pk_nds_field_game(reinterpret_cast<const char*>(rom.data()+12),rom[30]);
      pd_ds::map=ds_map_art(rom);pd_ds::icons=ds_party_icons(rom);
    }
  }
  const auto old_layout=opt("pokedaisy_ds_layout");
  pd_ds::shell.init(dir(RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY),
    old_layout=="top"?1:old_layout=="bottom"?2:old_layout=="stacked"?4:0,opt("pokedaisy_ds_hud")=="enabled");
  log(RETRO_LOG_INFO,"PokeDaisy DS: Platinum companion %s\n",pd_ds::supported?"enabled":"unavailable for this ROM");
  return true;
}
RETRO_API void retro_reset(){
  pd_ds::clear_battle();
  ds_reset();pd_ds::companion=pd_ds::quickOpen=false;pd_ds::shell.picker=-1;pd_ds::shell.summary=false;pd_ds::shell.details=false;pd_ds::screen_override=0;pd_ds::snapshot={};pd_ds::tick=0;pd_ds::scene.reset();pd_ds::arrangement={};pd_ds::manualTouch=pd_ds::fieldValid=false;
}
RETRO_API bool retro_unserialize(const void* data,size_t n){
  pd_ds::clear_battle();
  bool ok=ds_unserialize(data,n);
  if(ok)pd_ds::restoreGrace=4;
  if(ok){pd_ds::companion=pd_ds::quickOpen=false;pd_ds::shell.picker=-1;pd_ds::shell.summary=false;pd_ds::shell.details=false;pd_ds::screen_override=0;pd_ds::snapshot={};pd_ds::tick=0;pd_ds::scene.reset();pd_ds::arrangement={};pd_ds::manualTouch=pd_ds::fieldValid=false;}
  return ok;
}
RETRO_API void retro_unload_game(){ds_unload_game();pd_ds::clear();}
RETRO_API void retro_run(){
  if(!nds)return;
  input_poll_cb();
  const unsigned previous=pd_ds::old_buttons;
  const bool menuWasOpen=pd_ds::companion || pd_ds::quickOpen;
  pd_ds::controls();
  pd_ds::update_battle(menuWasOpen?0:pd_ds::buttons&~previous);
  if(pd_ds::companion || pd_ds::quickOpen){
    if(pd_ds::quickOpen)pd_ds::draw_quick();else pd_ds::draw_companion();
    static const int16_t silence[548*2]={};audio_batch_cb(silence,547);
  }else {ds_run();++pd_ds::tick;}
}
}
