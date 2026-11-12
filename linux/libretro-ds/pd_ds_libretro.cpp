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
#include "guide_view.h"
#include "browser.h"
#include "data_gen.h"
#include "pk_nds_field.h"
#include <ctime>
#include "pk_nds_call_memory.h"
#include "core/crypto/sha1.h"
#include <fstream>
#include <stdexcept>
extern "C" {
#include "pd_style.h"
#include "pd_ui.h"
#include "../hud.h"
#include "../quick_menu.h"
#include "pd_tables.h"
#include "pd_icon.h"
}
namespace pd_ds {
retro_environment_t environment;
retro_video_refresh_t video;
retro_input_state_t input;
const PkNdsFieldGame* game;
const DsBattleStyle* battleStyle=nullptr;
DsGame companionGame=DsGame::Unsupported;
bool soulSilver=false;
DsCompanion snapshot;
DsMapArt map;
std::vector<std::vector<uint32_t>> icons,hudItemIcons;
int repelFull=0;
hud_hits hudHits{};
int hudCursor=-1;
unsigned hudConsumed=0;
void clear_hud_focus(){hudHits.n=0;hudCursor=-1;hudConsumed=0;}
bool supported=false, companion=false, quickOpen=false, swallowMenuInput=false;
int quickSelected=0,companionHold=0;
struct FlyDestination { int x,y; const char* name; int header=0; };
enum class FlyStage { Idle, Menu, Party, Member, Move, Town, Release };
struct FlyJob {
  FlyStage stage=FlyStage::Idle;
  unsigned started=0,ready=0,key=0,tapFrames=0;
  int slot=0,x=0,y=0;
  FlyDestination destination{};
  bool active() const {return stage!=FlyStage::Idle;}
} flyJob;
std::vector<FlyDestination> flyDestinations;
int flySelected=0,flySlot=-1;
uint32_t flyPersonality=0;
bool flyPicker=false,heldQuickConfirm=false;
int pcJob=0,flyCallJob=0;
bool flyChecking=false,autoRunThisFrame=false;
const char* flyWhy=nullptr;
constexpr size_t shortcutCapacity=6;
bool stageReady=false;
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
bool battleActive=false,injectBattleTap=false,wasInBattle=false;
constexpr unsigned battleKeys=(1u<<RETRO_DEVICE_ID_JOYPAD_A)|(1u<<RETRO_DEVICE_ID_JOYPAD_B)|
  (1u<<RETRO_DEVICE_ID_JOYPAD_UP)|(1u<<RETRO_DEVICE_ID_JOYPAD_DOWN)|
  (1u<<RETRO_DEVICE_ID_JOYPAD_LEFT)|(1u<<RETRO_DEVICE_ID_JOYPAD_RIGHT);
DsArrangement arrangement;
bool manualTouch=false,fieldValid=false,fieldMenuFlow=false;
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
void write32(uint32_t a,uint32_t value) {
  if(nds && a>=0x02000000 && a<=0x023ffffc)nds->bus.dma_write32(ds::Cpu::ARM9,a,value);
}
void set_wild_rates(){
  const auto now=std::time(nullptr);
  const auto* local=std::localtime(&now);
  const int savedMonth=ds_unova_month(nds->bus.main_ram.get(),nds->bus.main_ram_size(),companionGame);
  const int month=savedMonth>=1 && savedMonth<=12?savedMonth:local?local->tm_mon+1:1;
  const auto rates=ds_wild_rates(companionGame,month,soulSilver);
  pk_nds_field_set_rates(rates.triples,rates.count);
  pk_nds_field_reset_wild();
}
void clear_battle() {
  battleStrip={};battleTap={};drawnBattle={};battleName.clear();battlePanelArtValid=false;
  battleHeld=0;holdStrip=restoreGrace=0;battleActive=injectBattleTap=wasInBattle=false;
}
bool wants_strip() {
  return game && battleStyle && shell.battleStrip && shell.layout==DS_ONE && !screen_override &&
    !manualTouch && !companion && !quickOpen;
}
DsBattleView battle_view(int bits) {
  if(!wants_strip() || (buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)))return {};
  return ds_read_battle(game,read32,bits);
}
void update_battle(unsigned pressed,int field) {
  battleHeld&=buttons;
  if(!wants_strip() || (buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT))) {
    battleStrip={};battleTap={};injectBattleTap=false;return;
  }
  const auto view=battle_view(field);
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
void clear_field_actions() {
  pcJob=flyCallJob=0;
  flyChecking=autoRunThisFrame=false;
  flyWhy=nullptr;
  pk_nds_field_reset_wild();
  pk_nds_call_reset();
}

void clear() {
  clear_field_actions();
  clear_hud_focus();
  stageReady=false;
  pk_nds_field_set_rates(nullptr,0);
  shell.dexArt={};shell.dexPage={};shell.dexCachedSpecies=0;
  clear_battle();
  flyJob={};flyPicker=heldQuickConfirm=false;flyDestinations.clear();
  supported=companion=quickOpen=select_used=select_pulse=false;quickSelected=0; game=nullptr; snapshot={};map={};icons.clear();hudItemIcons.clear();repelFull=0;
  companionHold=0;
  battleStyle=nullptr;
  companionGame=DsGame::Unsupported;
  soulSilver=false;
  old_buttons=buttons=tick=0;std::fill(held_frames,held_frames+16,0);
  screen_override=0;cursor_x=128;cursor_y=96;layout=0;
  scene.reset();arrangement={};manualTouch=fieldValid=fieldMenuFlow=false;
}
bool env(unsigned cmd,void* data) {
  if(cmd==RETRO_ENVIRONMENT_SET_VARIABLES) {
    auto vars=static_cast<const retro_variable*>(data);
    std::vector<retro_variable> all;
    for(;vars && vars->key;++vars)all.push_back(*vars);
    all.push_back({"pokedaisy_ds_layout","Initial DS layout (saved companion settings take priority); auto|top|bottom|stacked"});
    all.push_back({"pokedaisy_ds_hud","Initial party HUD (saved companion settings take priority); enabled|disabled"});
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
void refresh() {
  auto next=ds_read_companion(nds->bus.main_ram.get(),nds->bus.main_ram_size(),companionGame);
  // On the newer games an invalid transient read can occur as the game
  // decrypts a record in place. Keep the last coherent snapshot.
  if(next.valid||companionGame==DsGame::Platinum){
    snapshot=std::move(next);
    shell.guideLoadedPage=-1;
    pd_ui_guide_reset(&shell.frame);
  }
  ds_map_update(map,snapshot);
}
void refresh_battle_companion(){
  ds_read_battle_companion(game,read32,nds->bus.main_ram.get(),nds->bus.main_ram_size(),snapshot,shell.battle);
  shell.update_battle();
}
void choose_layout() {
  int bits=game?pk_nds_field_state(game,read32,nullptr,0):0;
  const bool inBattle=bits&PK_NDS_FIELD_BATTLE;
  // A screen peek in the overworld shouldn't carry into the next fight.
  // Screen changes made during a fight still give access to the native panel.
  if(inBattle&&!wasInBattle&&shell.layout==DS_ONE&&shell.battleStrip){
    screen_override=0;
    manualTouch=false;
  }
  wasInBattle=inBattle;
  auto one_colour=[](const uint32_t* px){
    uint32_t first=px[0]&0xffffff;
    for(int gy=0;gy<12;++gy)for(int gx=0;gx<16;++gx)
      if((px[(gy*16+8)*256+gx*16+8]&0xffffff)!=first)return false;
    return true;
  };
  bool topBlank=one_colour(nds->gpu.framebuffer(0)),bottomBlank=one_colour(nds->gpu.framebuffer(1));
  const bool fieldApp=!inBattle&&(bits&PK_NDS_FIELD_MENU);
  const bool fieldScreen=bits&PK_NDS_FIELD_SCREEN;
  const bool nativeX=(buttons&~hudConsumed&(1u<<RETRO_DEVICE_ID_JOYPAD_X)) &&
    shell.companion_id()!=RETRO_DEVICE_ID_JOYPAD_X && shell.screen_id()!=RETRO_DEVICE_ID_JOYPAD_X;
  if(fieldApp || (fieldScreen&&nativeX))fieldMenuFlow=true;
  else if(inBattle || (fieldScreen&&!(bits&PK_NDS_FIELD_LOCKED)&&!topBlank))fieldMenuFlow=false;
  if(topBlank&&!bottomBlank)bits|=PK_NDS_FIELD_TOUCH;
  const auto view=battle_view(bits);
  battleActive=view.panel!=DsBattlePanel::Native;
  if(battleActive){
    if(drawnBattle.panel!=view.panel || drawnBattle.personality!=view.personality ||
       drawnBattle.species!=view.species ||
       (view.panel==DsBattlePanel::Moves &&
        (drawnBattle.moves!=view.moves || drawnBattle.pp!=view.pp)))battlePanelArtValid=false;
    if(supported && !battlePanelArtValid){
      const auto* lower=nds->gpu.framebuffer(1);
      const bool ready=view.panel==DsBattlePanel::Actions?ds_platinum_has_command_art(lower):
        ds_platinum_has_move_art(lower,view);
      if(ready){
        std::copy_n(lower,battlePanelArt.size(),battlePanelArt.begin());
        battlePanelArtValid=true;
      }
    }
    if(drawnBattle.personality!=view.personality || drawnBattle.species!=view.species || battleName.empty()){
      battleName=ds_battle_species(view);
      if(companionGame!=DsGame::Unsupported && !view.gen5){
        const auto party=ds_read_companion(nds->bus.main_ram.get(),nds->bus.main_ram_size(),companionGame).party;
        const auto mon=std::find_if(party.begin(),party.end(),[&](const DsMon& candidate){
          return candidate.personality==view.personality && candidate.species==view.species;
        });
        if(mon!=party.end() && !mon->nickname.empty())battleName=mon->nickname;
      }
    }
    drawnBattle=view;
    holdStrip=18;
  }else if(!wants_strip() || !inBattle || ((bits&PK_NDS_FIELD_TOUCH_BATTLE_MENU) &&
            !(battleStrip.moveCommitFrames>0&&battleStrip.cancelingMove))){
    holdStrip=0;drawnBattle={};battlePanelArtValid=false;
  }else if(holdStrip>0 && !(bits&PK_NDS_FIELD_TOUCH_FADES)){
    --holdStrip;
  }
  // Keep the stale move panel off the screen during a single-battle commit.
  // Explicit native transitions (including Bag and party) retain their fade.
  int displayBits=bits;
  if(inBattle&&battleStrip.moveCommitFrames>0&&!(bits&PK_NDS_FIELD_TOUCH_FADES))
    displayBits&=~(PK_NDS_FIELD_TOUCH|PK_NDS_FIELD_TOUCH_BATTLE_MENU);
  // PIP already exposes the native battle panel; it must not float twice.
  scene.tick(displayBits,topBlank,bottomBlank,!battleActive&&(shell.autoBattles||wants_strip())&&shell.layout!=DS_PIP);
  int mode=screen_override==1?DS_BOTTOM:screen_override==2?DS_TOP:shell.layout;
  arrangement=ds_arrange(mode,scene,game!=nullptr,manualTouch&&screen_override==0);
  if(mode==DS_ONE&&!manualTouch&&game&&!supported){
    arrangement=ds_arrange_one_screen(arrangement,scene,bits,topBlank,fieldValid,fieldMenuFlow);
  }else if(mode==DS_ONE&&!manualTouch&&fieldValid&&fieldMenuFlow&&!inBattle){
    // Present the last field image through the game's loading fades. The
    // native menu is shown only once the screen(s) it needs are drawn.
    if(fieldApp&&(bits&PK_NDS_FIELD_TOP_ONLY)&&!topBlank)
      arrangement={{0,0,512,384},{},false,true,true};
    else if(fieldApp&&!(bits&(PK_NDS_FIELD_TOP_ONLY|PK_NDS_FIELD_TOUCH_ONLY))&&!topBlank&&!bottomBlank)
      arrangement={{0,96,256,192},{256,96,256,192},false,true,true};
    else if(topBlank || (fieldApp&&!(bits&PK_NDS_FIELD_TOUCH_ONLY)))
      arrangement={{},{},false,false,true};
  }
  if(battleActive && !scene.dark)arrangement={{0,0,512,384},{}};
  layout=mode==DS_BOTTOM?1:mode==DS_SIDE?2:mode==DS_STACKED?3:0;
  if(scene.hud&&!scene.dark&&!(bits&PK_NDS_FIELD_LOCKED)){std::memcpy(fieldFrame,nds->gpu.framebuffer(0),sizeof fieldFrame);fieldValid=true;}
}
void draw_companion() {
  pd_canvas c;pd_canvas_init(&c,341,256,ui_pixels);
  shell.draw(&c,snapshot,companionGame!=DsGame::Unsupported,map,icons);
  geometry(341,256);
  for(unsigned i=0;i<341*256;++i)pixels[i]=ds_rgb565(ui_pixels[i]);
  video(pixels,width,height,width*2);
}
constexpr int repelItems[]={79,76,77};
int repelChoices[3],repelCount=0,repelSelected=0;
std::string repelLabels[3];
bool repelPicker=false;
bool field_free(int bits){
  return (bits&PK_NDS_FIELD_SCREEN)&&!(bits&(PK_NDS_FIELD_LOCKED|PK_NDS_FIELD_BATTLE|PK_NDS_FIELD_MENU|PK_NDS_FIELD_TEXT));
}
bool field_free(){return field_free(pk_nds_field_state(game,read32,nullptr,0));}
void quick_notice(const char* label){
  retro_message message{label,120};
  environment(RETRO_ENVIRONMENT_SET_MESSAGE,&message);
}
// DSperate's CPU-visible bus handles TCM mirrors and invalidates recompiled
// code on writes. The field job and its ROM-specific recipes are shared with Android.
PkNdsCallMemory field_call_memory(){
  constexpr auto cpu=ds::Cpu::ARM9;
  return {nds.get(),
    [](void* ctx,uint32_t addr){return static_cast<ds::NDS*>(ctx)->bus.dma_read32(cpu,addr);},
    [](void* ctx,uint32_t addr,uint32_t value){static_cast<ds::NDS*>(ctx)->bus.dma_write32(cpu,addr,value);},
    [](void* ctx,uint32_t addr,void* dst,uint32_t len){
      auto& bus=static_cast<ds::NDS*>(ctx)->bus;
      auto* bytes=static_cast<uint8_t*>(dst);
      for(uint32_t i=0;i<len;++i)bytes[i]=bus.dma_read8(cpu,addr+i);
    },
    [](void* ctx,uint32_t addr,const void* src,uint32_t len){
      auto& bus=static_cast<ds::NDS*>(ctx)->bus;
      const auto* bytes=static_cast<const uint8_t*>(src);
      for(uint32_t i=0;i<len;++i)bus.dma_write8(cpu,addr+i,bytes[i]);
    }};
}
void open_pc(){
  if(!field_free() || !pk_nds_call_supported(game) || nds->cpu(ds::Cpu::ARM9).itcm_size<0x02000000){
    quick_notice("PC is unavailable here.");
    return;
  }
  pcJob=pk_nds_call_action(field_call_memory(),game,PK_NDS_ACTION_PC,nullptr);
  if(!pcJob){quick_notice("PC is unavailable here.");return;}
  heldQuickConfirm=true;
  quickOpen=false;
}
void update_pc(){
  if(!pcJob)return;
  pk_nds_call_frame(field_call_memory(),game);
  const int status=pk_nds_call_status(pcJob);
  if(!status)return;
  uint32_t results[PK_NDS_MAX_RESULTS];
  if(status<0 || (game->gen==5 && (!pk_nds_call_results(pcJob,results) || !results[1])))
    quick_notice("PC could not open here.");
  pcJob=0;
}

// Verified native overlays for Black 2 USA/Europe revision 0. Use input so the
// game checks field eligibility and owns confirmation, animation and travel.
constexpr int BLACK2_PARTY_OVERLAY=165,BLACK2_FLY_OVERLAY=144;
bool fly_supported(){return companionGame==DsGame::Black2 || (pk_nds_call_supported(game)&&!ds_fly_spots(companionGame).empty());}
int field_overlay(){
  const uint32_t system=read32(game->fieldSystemPtr);
  const uint32_t procs=read32(system+game->offProcSys);
  const uint32_t proc=read32(procs+game->offCurrentProc);
  return proc>=0x02000000&&proc<0x02400000?int(read32(proc+game->offProcOverlay)):-1;
}

void open_fly(){
  if(!field_free()){quick_notice("Fly is available in the overworld.");return;}
  const auto current=ds_read_companion(nds->bus.main_ram.get(),nds->bus.main_ram_size(),companionGame);
  if(!current.valid){quick_notice("Party data is unavailable.");return;}
  const auto flyer=std::find_if(current.party.begin(),current.party.end(),[](const DsMon& mon){
    return !mon.egg&&std::find(mon.moves.begin(),mon.moves.end(),19)!=mon.moves.end();
  });
  if(flyer==current.party.end()){quick_notice("No one in your party knows Fly.");return;}
  flySlot=int(flyer-current.party.begin());
  flyPersonality=flyer->personality;
  flyDestinations.clear();
  if(companionGame!=DsGame::Black2){
    const auto& spots=ds_fly_spots(companionGame);
    const uint32_t args[]={game->gen!=5?uint32_t(spots.size()):0,0,0,0};
    if(nds->cpu(ds::Cpu::ARM9).itcm_size>=0x02000000)
      flyCallJob=pk_nds_call_action(field_call_memory(),game,PK_NDS_ACTION_FLY_CHECK,args);
    if(!flyCallJob){quick_notice("Fly is unavailable here.");return;}
    flyChecking=true;
    heldQuickConfirm=true;
    quickOpen=false;
    return;
  }
  for(const auto& point:pd_ds_data::sequelMapPoints){
    const int kind=point[4],bit=point[5]-2469;
    if((kind!=0&&kind!=3&&kind!=5)||bit<0||bit>=62||!(current.arrivals>>bit&1))continue;
    flyDestinations.push_back({point[2],point[3],ds_place(point[0],companionGame)});
  }
  if(flyDestinations.empty()){quick_notice("No Fly destinations unlocked.");return;}
  flySelected=0;
  flyPicker=true;
}

void update_fly_call(){
  if(!flyCallJob)return;
  pk_nds_call_frame(field_call_memory(),game);
  const int status=pk_nds_call_status(flyCallJob);
  if(!status)return;
  uint32_t results[PK_NDS_MAX_RESULTS]{};
  const bool ok=status>0 && pk_nds_call_results(flyCallJob,results);
  flyCallJob=0;
  if(!flyChecking){
    if(!ok || (game->gen==5 && !results[1]))quick_notice("Fly could not start here.");
    return;
  }
  flyChecking=false;
  if(!ok || results[1]){
    const char* message="Fly is unavailable here.";
    if(ok && game->gen!=5 && results[1]==2)message="You need the badge to use Fly.";
    if(ok && game->gen!=5 && results[1]==3)message="You cannot Fly with a partner.";
    flyWhy=message;
    quickOpen=true;
    return;
  }
  const auto current=ds_read_companion(nds->bus.main_ram.get(),nds->bus.main_ram_size(),companionGame);
  const auto& spots=ds_fly_spots(companionGame);
  for(size_t i=0;i<spots.size();++i){
    const auto& spot=spots[i];
    const bool open=companionGame==DsGame::Platinum
      ? i+2<std::size(results) && results[i+2] : ds_fly_unlocked(current,spot);
    if(open)flyDestinations.push_back({spot.x,spot.z,ds_place(spot.nameMap,companionGame),spot.header});
  }
  if(flyDestinations.empty()){
    flyWhy="No Fly destinations unlocked";
    quickOpen=true;
    return;
  }
  flySelected=0;
  flyPicker=quickOpen=true;
}

void confirm_fly(){
  if(!field_free()){quick_notice("Fly is unavailable here.");return;}
  const auto current=ds_read_companion(nds->bus.main_ram.get(),nds->bus.main_ram_size(),companionGame);
  if(flySlot<0 || size_t(flySlot)>=current.party.size()){
    quick_notice("Party data is unavailable.");return;
  }
  const auto& flyer=current.party[flySlot];
  if(flyer.personality!=flyPersonality || flyer.egg ||
     std::find(flyer.moves.begin(),flyer.moves.end(),19)==flyer.moves.end()){
    quick_notice("Your Fly user has changed.");return;
  }
  const auto& spot=flyDestinations[flySelected];
  if(companionGame==DsGame::Black2){
    flyJob={};flyJob.stage=FlyStage::Menu;flyJob.started=tick;
    flyJob.slot=flySlot;flyJob.destination=spot;
  }else{
    const uint32_t args[]={uint32_t(flySlot),uint32_t(spot.header),
      game->gen!=5?uint32_t(spot.x):0,game->gen!=5?uint32_t(spot.y):0};
    flyCallJob=pk_nds_call_action(field_call_memory(),game,PK_NDS_ACTION_FLY,args);
    if(!flyCallJob){quick_notice("Fly is unavailable here.");return;}
    heldQuickConfirm=true;
  }
  flyPicker=quickOpen=false;
}

// Match the whole native cyan Fly label so the shortcut works even when
// other field moves change its row in the party member's submenu.
int fly_row(const uint32_t* bottom){
  constexpr uint16_t glyph[]={0x1f,0xc1,0x81,0x81,0x4481,0x448f,0x4481,0x4481,0x7881,0x4081,0x4000,0x3800};
  const auto mask=[&](int y){
    uint16_t bits=0;
    for(int x=0;x<15;++x){
      const auto p=bottom[y*256+162+x];
      const int red=(p>>16)&255,green=(p>>8)&255,blue=p&255;
      if(green>125&&blue>125&&green>red+30)bits|=1<<x;
    }
    return bits;
  };
  for(int y=45;y<177;++y){
    if(mask(y)!=glyph[0])continue;
    unsigned row=1;
    while(row<std::size(glyph)&&mask(y+row)==glyph[row])++row;
    if(row==std::size(glyph))return y+5;
  }
  return -1;
}

void update_fly(){
  auto& job=flyJob;
  if(!job.active())return;
  job.key=0;
  if(job.tapFrames){--job.tapFrames;return;}
  const unsigned elapsed=tick-job.started;
  const int bits=pk_nds_field_state(game,read32,nullptr,0);
  const int overlay=field_overlay();
  if(elapsed>240||(bits&PK_NDS_FIELD_BATTLE)){
    job={};quick_notice("Fly could not open here.");return;
  }
  const auto advance=[&](FlyStage stage){job.stage=stage;job.started=tick;job.ready=0;};
  const auto tap=[&](int x,int y,FlyStage stage){job.x=x;job.y=y;job.tapFrames=5;advance(stage);};
  switch(job.stage){
  case FlyStage::Menu:
    if(!field_free()){job={};return;}
    job.key=1u<<RETRO_DEVICE_ID_JOYPAD_X;
    advance(FlyStage::Party);
    break;
  case FlyStage::Party:
    if((bits&(PK_NDS_FIELD_SCREEN|PK_NDS_FIELD_TOUCH))==(PK_NDS_FIELD_SCREEN|PK_NDS_FIELD_TOUCH)
       && elapsed>=55&&!scene.bottomBlank)tap(60,47,FlyStage::Member);
    break;
  case FlyStage::Member:
    if(overlay!=BLACK2_PARTY_OVERLAY||scene.bottomBlank){job.ready=0;break;}
    if(++job.ready>=24)tap(job.slot%2?192:64,32+(job.slot/2)*48+(job.slot%2)*8,FlyStage::Move);
    break;
  case FlyStage::Move:
    if(overlay!=BLACK2_PARTY_OVERLAY||elapsed<12)break;
    if(const int y=fly_row(nds->gpu.framebuffer(1));y>=0)tap(198,y,FlyStage::Town);
    break;
  case FlyStage::Town:
    if(overlay!=BLACK2_FLY_OVERLAY||scene.bottomBlank){job.ready=0;break;}
    if(++job.ready>=24)tap(job.destination.x,job.destination.y,FlyStage::Release);
    break;
  case FlyStage::Release:
    // A held since the picker must not confirm the native map.
    heldQuickConfirm=buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_A);
    job={};
    break;
  case FlyStage::Idle: break;
  }
}

void open_repel(){
  if(!field_free()){quick_notice("Repel is available in the overworld.");return;}
  refresh();
  if(snapshot.repelSteps){quick_notice("Repel is still active.");return;}
  repelCount=0;
  for(int id:repelItems){
    const auto item=std::find_if(snapshot.bag.begin(),snapshot.bag.end(),[&](const DsItem& i){return i.id==id&&i.pocket==0;});
    if(item==snapshot.bag.end())continue;
    repelChoices[repelCount]=id;
    repelLabels[repelCount++]=std::string(ds_item(id,companionGame))+" x"+std::to_string(item->count);
  }
  if(!repelCount){quick_notice("No Repel in the Bag.");return;}
  repelPicker=true;
  repelSelected=0;
}
void use_repel(){
  using R=DsRepelResult;
  const R result=field_free()?ds_use_repel(nds->bus.main_ram.get(),nds->bus.main_ram_size(),
    companionGame,repelChoices[repelSelected],[](uint32_t addr,uint8_t value){
      nds->bus.dma_write8(ds::Cpu::ARM9,addr,value);
    }):R::NotAvailable;
  if(result==R::Used){refresh();repelFull=0;}
  quick_notice(result==R::Used?"Repel used.":result==R::StillActive?"Repel is still active.":
               result==R::NoneLeft?"No Repel in the Bag.":"Repel is unavailable here.");
}
int ds_shortcuts(quick_item (&items)[shortcutCapacity]){
  int count=0;
  const bool field=field_free();
  const char* fieldWhy=field?nullptr:"Overworld only";
  const bool canCall=nds->cpu(ds::Cpu::ARM9).itcm_size>=0x02000000;
  const bool hasRepel=std::any_of(snapshot.bag.begin(),snapshot.bag.end(),[](const DsItem& item){
    return item.pocket==0 && item.count>0 &&
      std::find(std::begin(repelItems),std::end(repelItems),item.id)!=std::end(repelItems);
  });
  const bool hasFlyer=std::any_of(snapshot.party.begin(),snapshot.party.end(),[](const DsMon& mon){
    return !mon.egg && std::find(mon.moves.begin(),mon.moves.end(),19)!=mon.moves.end();
  });
  if(pk_nds_call_supported(game))
    items[count++]={QUICK_PC,false,false,fieldWhy?fieldWhy:!canCall?"PC unavailable":nullptr};
  if(fly_supported())
    items[count++]={QUICK_FLY,false,false,fieldWhy?fieldWhy:!hasFlyer?"No party member knows Fly":
      (!canCall && companionGame!=DsGame::Black2)?"Fly unavailable":flyWhy};
  if(companionGame!=DsGame::Unsupported){
    items[count++]={QUICK_REPEL,false,false,fieldWhy?fieldWhy:snapshot.repelSteps?"Repel still active":
      !hasRepel?"No Repel in the Bag":nullptr};
    if(game)items[count++]={QUICK_WILD,shell.settings.wildOff,false,fieldWhy};
    items[count++]={QUICK_HUD,false,false,hudHits.n?nullptr:"No HUD on screen"};
  }
  items[count++]={QUICK_COMPANION,false,false,nullptr};
  return count;
}
void draw_quick() {
  pd_canvas c;pd_canvas_init(&c,341,256,quick_pixels);
  pd_game presentation{};
  quick_item items[shortcutCapacity]{};
  const int count=ds_shortcuts(items);
  quick_set_grid(shell.settings.quickGrid);
  if(repelPicker){
    const char* labels[3]={repelLabels[0].c_str(),repelLabels[1].c_str(),repelLabels[2].c_str()};
    quick_list_draw(&c,"REPEL",labels,repelCount,repelSelected,shell.settings.hudLook);
  }else if(flyPicker){
    std::vector<const char*> labels;
    for(const auto& destination:flyDestinations)labels.push_back(destination.name);
    quick_list_draw(&c,"FLY TO",labels.data(),int(labels.size()),flySelected,shell.settings.hudLook);
  }else quick_draw(&c,&presentation,items,count,std::min(quickSelected,count-1),shell.settings.hudLook);
  quick_finish(&c);
  // Overlay the menu on the paused composition at the same geometry as gameplay.
  if(!stageReady){
    choose_layout();
    ds_compose(stage,arrangement,nds->gpu.framebuffer(0),nds->gpu.framebuffer(1));
    stageReady=true;
  }
  geometry(512,384);
  for(unsigned y=0;y<384;++y)for(unsigned x=0;x<512;++x){
    uint32_t base=stage[y*512+x]&0xffffff;
    base=(base&0xfefefe)>>1;
    uint32_t p=quick_pixels[(y*256/384)*341+x*341/512];
    if(p!=QUICK_CLEAR){unsigned a=p>>24;
      unsigned r=(((p>>16)&255)*a+((base>>16)&255)*(255-a))/255;
      unsigned g=(((p>>8)&255)*a+((base>>8)&255)*(255-a))/255;
      unsigned b=((p&255)*a+(base&255)*(255-a))/255;
      base=(r<<16)|(g<<8)|b;
    }
    pixels[y*512+x]=ds_rgb565(base);
  }
  video(pixels,512,384,512*2);
}
void draw_strip(uint32_t* out){
  const int focus=drawnBattle.panel==DsBattlePanel::Moves?battleStrip.moveFocus:battleStrip.actionFocus;
  ds_draw_battle_strip(out,*battleStyle,drawnBattle,battleName,focus,
                       battleActive&&!battleStrip.tapFrames,tick,
                       battlePanelArtValid?battlePanelArt.data():nullptr);
}
void present(const void* data,unsigned,unsigned,size_t) {
  hudHits.n=0;
  if(companion){draw_companion();return;}
  // Refresh composition even when DSperate skipped rasterization: switching
  // screens must still display the latest retained framebuffer.
  (void)data;
  if(hudCursor<0)choose_layout();
  const unsigned w=512,h=384;
  const bool showStrip=holdStrip>0 && !scene.dark && arrangement.top.w==512 && wants_strip();
  ds_compose(stage,arrangement,nds->gpu.framebuffer(0),nds->gpu.framebuffer(1),
             fieldValid?fieldFrame:nullptr,showStrip?draw_strip:nullptr);
  stageReady=true;
  geometry(w,h);
  for(unsigned i=0;i<w*h;++i)pixels[i]=ds_rgb565(stage[i]);
  if(hudCursor<0&&(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT))&&arrangement.touch.w>0){
    int cx=arrangement.touch.x+cursor_x*arrangement.touch.w/256;
    int cy=arrangement.touch.y+cursor_y*arrangement.touch.h/192;
    for(int d=-4;d<=4;++d){
      if(arrangement.touch.contains(cx+d,cy))pixels[cy*w+cx+d]=0xffff;
      if(arrangement.touch.contains(cx,cy+d))pixels[(cy+d)*w+cx]=0xffff;
    }
  }
  const auto& top=arrangement.top;
  if(companionGame!=DsGame::Unsupported && shell.settings.hud && scene.hud && !scene.dark &&
     (!game || field_free()) && !fieldMenuFlow &&
     top.w>0 && top.h>0 && !arrangement.floating && !arrangement.modal) {
    if(tick%12==0 || !snapshot.valid)refresh();
    pd_canvas c;pd_canvas_init(&c,256,192,ui_pixels);
    pd_snapshot party{};party.valid=snapshot.valid;party.partyCount=std::min(6,int(snapshot.party.size()));
    for(int i=0;i<party.partyCount;++i){
      const auto& m=snapshot.party[i];auto& d=party.party[i];
      d.species=m.egg?PD_SPECIES_EGG:1;d.level=m.level;d.hp=m.hp;d.maxHp=m.maxHp;d.status=m.status;
    }
    party.bagOk=snapshot.valid;
    auto& balls=party.bag[PD_POCKET_BALLS];
    for(const auto& item:snapshot.bag){
      // Unova stores Poké Balls in Items, alongside held items and field tools.
      const bool unovaBall=(item.id>=1&&item.id<=16)||(item.id>=492&&item.id<=500)||item.id==576;
      const bool ball=ds_unova(companionGame)?unovaBall:item.pocket==6;
      if(ball&&balls.count<PD_POCKET_SLOTS)balls.items[balls.count++]={uint16_t(item.id),uint16_t(item.count)};
    }
    const int steps=snapshot.repelSteps;
    if(!steps)repelFull=0;
    else if(steps>repelFull)repelFull=hud_repel_full(steps);
    const auto encounters=ds_guide_here(snapshot,soulSilver);
    int wild=encounters.count ? 0 : -1;
    if(wild>=0){
      bool counted[650]{};
      for(int i=0;i<encounters.count;++i){
        const unsigned id=encounters.entries[i].species;
        if(!id || id>=std::size(counted) || counted[id])continue;
        counted[id]=true;
        if(id>=snapshot.dexCaught.size() || !snapshot.dexCaught[id])++wild;
      }
    }
    const bool pc=pk_nds_call_supported(game)&&nds->cpu(ds::Cpu::ARM9).itcm_size>=0x02000000;
    hud_state state{steps,repelFull,pc,wild,-1};
    state.mini=shell.settings.hudSize!=PD_HUD_LARGE;
    const bool pip=arrangement.touch.w>0 && arrangement.touch.x>top.x && arrangement.touch.x<top.x+top.w;
    const int hudRight=pip?(arrangement.touch.x-top.x)*256/top.w:256;
    struct HudIcons {const pd_snapshot& party;bool small;};
    HudIcons hudIcons{party,pip&&!state.mini};
    hud_ds_draw(&c,&party,&state,&shell.settings,&hudIcons,[](pd_canvas* c,void* ctx,const pd_mon* m,int x,int y,bool){
      const auto& hud=*static_cast<HudIcons*>(ctx);
      const int slot=m-hud.party.party;
      const auto& mon=snapshot.party[slot];
      const int size=hud.small?20:32;
      if(hud.small){x+=6;y+=5;}
      const int id=mon.egg?ds_egg_icon(companionGame):mon.species;
      if(id<0||size_t(id)>=icons.size()||icons[id].size()!=1024){
        if(companionGame!=DsGame::Platinum){
          const char number[]={char('1'+slot),0};
          pd_text(c,x+(size-8)/2,y+8,number,0xffffff,0x282828);
        }
        return;
      }
      const auto& art=icons[id];
      for(int j=0;j<size;++j)for(int i=0;i<size;++i){
        const auto p=art[(j*32/size)*32+i*32/size];
        if(p>>24)pd_fill(c,x+i,y+j,1,1,p&0xffffff);
      }
    }, [](pd_canvas* c,int id,int x,int y){
      if(id<0||size_t(id)>=hudItemIcons.size()||hudItemIcons[id].empty())return false;
      const auto& art=hudItemIcons[id];
      for(int row=0;row<PD_ITEM_ICON;++row)for(int col=0;col<PD_ITEM_ICON;++col){
        const auto p=art[(row*32/PD_ITEM_ICON)*32+col*32/PD_ITEM_ICON];
        if(p>>24)pd_fill(c,x+col,y+row,1,1,p&0xffffff);
      }
      return true;
    },79,shell.hudMinimal,hudRight,&hudHits,&ds_party_palette(companionGame));
    const auto player=ds_map_position(snapshot);
    if(!shell.hudMinimal&&!(shell.settings.hudHidden&(1<<PD_HUD_MAP))&&!map.background.empty()&&player.x>=0&&player.y>=0)
      hud_ds_minimap(&c,&shell.settings,ds_place(snapshot.map,companionGame),[](pd_canvas* c,int x,int y,int w,int h){
        ds_draw_minimap(c,snapshot,map,x,y,w,h);
      },&hudHits);
    hud_focus_draw(&c,&hudHits,hudCursor);
    // Scale with the top screen and keep the picture-in-picture touch screen clear.
    const bool doubled=top.w==512 && top.h==384;
    for(int y=0;y<top.h;++y){
      const auto* row=ui_pixels+(doubled?y/2:y*192/top.h)*256;
      for(int x=0;x<top.w;++x){
        const uint32_t p=row[doubled?x/2:x*256/top.w];
        if(p==QUICK_CLEAR || arrangement.touch.contains(top.x+x,top.y+y))continue;
        const unsigned a=p>>24?p>>24:255;
        const unsigned at=(top.y+y)*w+top.x+x;
        const uint16_t old=pixels[at];
        unsigned r=(old>>11&31)*255/31,g=(old>>5&63)*255/63,b=(old&31)*255/31;
        r=(((p>>16)&255)*a+r*(255-a))/255;
        g=(((p>>8)&255)*a+g*(255-a))/255;
        b=((p&255)*a+b*(255-a))/255;
        pixels[at]=ds_rgb565((r<<16)|(g<<8)|b);
      }
    }
  }
  video(pixels,w,h,w*2);
}
int16_t filtered_input(unsigned p,unsigned d,unsigned i,unsigned id) {
  if(flyCallJob)return 0;
  if(flyJob.active()){
    if(d==RETRO_DEVICE_JOYPAD)return id<16&&!!(flyJob.key&(1u<<id));
    if(d==RETRO_DEVICE_POINTER){
      if(id==RETRO_DEVICE_ID_POINTER_PRESSED)return flyJob.tapFrames>2;
      if(id==RETRO_DEVICE_ID_POINTER_X)return (flyJob.x*65535+255)/256-32767;
      if(id==RETRO_DEVICE_ID_POINTER_Y)return (flyJob.y*32768+191)/192;
    }
    return 0;
  }
  if(swallowMenuInput)return 0;
  if(d==RETRO_DEVICE_JOYPAD) {
    if(id<16 && (hudConsumed&(1u<<id)))return 0;
    if(id==RETRO_DEVICE_ID_JOYPAD_A&&heldQuickConfirm)return 0;
    if(id==RETRO_DEVICE_ID_JOYPAD_SELECT && select_pulse)return 1;
    if(companion || quickOpen || (buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)))return 0;
    if(id<16 && (battleHeld&(1u<<id)))return 0;
    if(id==shell.companion_id() || int(id)==shell.screen_id())return 0;
    if(p==0 && id==RETRO_DEVICE_ID_JOYPAD_B && autoRunThisFrame)return !input(p,d,i,id);
  }
  // DSperate expects a pointer over stacked screens. Reproject the current
  // layout into that convention before its input::apply receives it.
  if(d==RETRO_DEVICE_POINTER) {
    bool stylus=buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT);
    if(stylus) {
      if(id==RETRO_DEVICE_ID_POINTER_PRESSED)return !!(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_A));
      if(id==RETRO_DEVICE_ID_POINTER_X)return (cursor_x*65535+255)/256-32767;
      if(id==RETRO_DEVICE_ID_POINTER_Y)return (cursor_y*32768+191)/192;
    }
    if(injectBattleTap) {
      if(id==RETRO_DEVICE_ID_POINTER_PRESSED)return battleTap.down;
      if(id==RETRO_DEVICE_ID_POINTER_X)return (battleTap.x*65535+255)/256-32767;
      if(id==RETRO_DEVICE_ID_POINTER_Y)return (battleTap.y*32768+191)/192;
    }
    int x=input(p,d,i,RETRO_DEVICE_ID_POINTER_X),y=input(p,d,i,RETRO_DEVICE_ID_POINTER_Y);
    auto pen=ds_pen(arrangement,(x+32767)*512/65535,(y+32767)*384/65535);
    if(id==RETRO_DEVICE_ID_POINTER_PRESSED)return pen.valid?input(p,d,i,id):0;
    if(id==RETRO_DEVICE_ID_POINTER_X)return pen.valid?(pen.x*65535+255)/256-32767:0;
    if(id==RETRO_DEVICE_ID_POINTER_Y)return pen.valid?(pen.y*32768+191)/192:0;
  }
  return input(p,d,i,id);
}
void hud_open_target(int id) {
  if(id==HUD_PC){open_pc();return;}
  refresh();
  if(!snapshot.valid)return;
  shell.frame.overlay=PD_OVERLAY_NONE;
  shell.frame.moreOpened=false;
  shell.summary=shell.details=false;
  shell.placeRow=shell.mapAction=-1;
  if(id>=HUD_SLOT && id<HUD_SLOT+int(snapshot.party.size())){
    shell.frame.tab=PD_TAB_PARTY;
    shell.selected=id-HUD_SLOT;
    shell.summary=true;
    shell.page=shell.learnRow=0;
  }else if(id==HUD_MAP || id==HUD_MAP_BUTTON){
    shell.frame.tab=PD_TAB_MAP;
    shell.mapX=snapshot.mapX;
    shell.mapY=snapshot.mapY;
  }else if(id==HUD_WILD){
    shell.frame.tab=PD_TAB_GUIDE;
    shell.frame.guidePage=int(GuidePage::Here);
    shell.guideLoadedPage=-1;
    pd_ui_guide_reset(&shell.frame);
  }else if(id==HUD_COMPANION){
    refresh_battle_companion();
  }else if(id==HUD_BALLS || id==HUD_REPEL || id==HUD_BAG){
    shell.frame.tab=PD_TAB_BAG;
    shell.pocket=shell.scroll=0;
  }else return;
  companion=true;
}

void controls() {
  buttons=0;for(unsigned id=0;id<16;++id)if(input(0,RETRO_DEVICE_JOYPAD,0,id))buttons|=1u<<id;
  hudConsumed&=buttons;
  select_pulse=!(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)) &&
    (old_buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)) && !select_used;
  if(!(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)))select_used=false;
  unsigned edge=buttons&~old_buttons;old_buttons=buttons;
  for(unsigned id=RETRO_DEVICE_ID_JOYPAD_UP;id<=RETRO_DEVICE_ID_JOYPAD_RIGHT;++id){
    held_frames[id]=(buttons&(1u<<id))?held_frames[id]+1:0;
    if((companion || quickOpen || hudCursor>=0) && held_frames[id]>15 && (held_frames[id]-15)%5==0)edge|=1u<<id;
  }
  auto hit=[&](unsigned id){return !!(edge&(1u<<id));};
  heldQuickConfirm&=!!(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_A));
  if(flyCallJob)return;
  if(flyJob.active()){
    if(hit(RETRO_DEVICE_ID_JOYPAD_B))flyJob={};
    return;
  }
  const int hudButton=shell.hud_id();
  if(hudCursor>=0){
    if(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT))select_used=true;
    if(hit(RETRO_DEVICE_ID_JOYPAD_B) || hit(RETRO_DEVICE_ID_JOYPAD_A) ||
       (hudButton>=0 && hit(hudButton))){
      const int target=hudCursor;
      hudCursor=-1;
      hudConsumed|=buttons;
      if(hit(RETRO_DEVICE_ID_JOYPAD_A) && !hit(RETRO_DEVICE_ID_JOYPAD_B))hud_open_target(target);
    }else{
      const int dx=hit(RETRO_DEVICE_ID_JOYPAD_RIGHT)-hit(RETRO_DEVICE_ID_JOYPAD_LEFT);
      const int dy=hit(RETRO_DEVICE_ID_JOYPAD_DOWN)-hit(RETRO_DEVICE_ID_JOYPAD_UP);
      if(dx || dy)hudCursor=hud_step(&hudHits,hudCursor,dy?0:dx,dy);
    }
    return;
  }
  if(!companion && !quickOpen && hudButton>=0 && hit(hudButton) && hudHits.n &&
     !(buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)) && field_free()){
    hudCursor=hud_first(&hudHits);
    hudConsumed|=1u<<hudButton;
    companionHold=0;
    return;
  }
  const bool selectDown=buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT);
  const bool stylusChord=selectDown && (buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_Y))
                         && opt("pokedaisy_ds_select_modifier")=="enabled";
  const bool companionDown=!stylusChord && (buttons&(1u<<shell.companion_id()));
  const bool companionTap=!companionDown&&companionHold>0;
  if(!companionDown)companionHold=0;
  else if(companionHold>=0)++companionHold;
  if(!companion && companionHold>0 && (selectDown || (!quickOpen&&companionHold>=24))){
    quickOpen=!quickOpen;repelPicker=flyPicker=false;select_used=selectDown;quickSelected=0;companionHold=-1;
    if(quickOpen){flyWhy=nullptr;refresh();}
    return;
  }
  if(quickOpen){
    if(hit(RETRO_DEVICE_ID_JOYPAD_B) || hit(shell.companion_id())){
      if(companionDown)companionHold=-1;
      if(repelPicker||flyPicker){repelPicker=flyPicker=false;return;}
      quickOpen=false;return;
    }
    quick_item shortcuts[shortcutCapacity]{};
    const int count=ds_shortcuts(shortcuts);
    int dx=hit(RETRO_DEVICE_ID_JOYPAD_RIGHT)-hit(RETRO_DEVICE_ID_JOYPAD_LEFT);
    int dy=hit(RETRO_DEVICE_ID_JOYPAD_DOWN)-hit(RETRO_DEVICE_ID_JOYPAD_UP);
    if(repelPicker){
      repelSelected=std::clamp(repelSelected+dy,0,repelCount-1);
      if(hit(RETRO_DEVICE_ID_JOYPAD_A)){use_repel();repelPicker=quickOpen=false;}
      return;
    }
    if(flyPicker){
      flySelected=std::clamp(flySelected+dy,0,int(flyDestinations.size())-1);
      if(hit(RETRO_DEVICE_ID_JOYPAD_A))confirm_fly();
      return;
    }
    quickSelected=std::min(quickSelected,count-1);
    if(dx || dy){
      quick_set_grid(shell.settings.quickGrid);
      quickSelected=quick_step(count,quickSelected,dx,dy,341,256);
    }
    if(hit(RETRO_DEVICE_ID_JOYPAD_A)){
      const auto& shortcut=shortcuts[quickSelected];
      if(shortcut.why)return;
      if(shortcut.action==QUICK_PC){open_pc();return;}
      if(shortcut.action==QUICK_FLY){open_fly();return;}
      if(shortcut.action==QUICK_REPEL){open_repel();return;}
      if(shortcut.action==QUICK_WILD){
        shell.settings.wildOff=!shell.settings.wildOff;
        const bool saved=shell.save();
        quick_notice(saved?(shell.settings.wildOff?"Wild encounters off.":"Wild encounters on."):shell.error.c_str());
        quickOpen=false;
        return;
      }
      if(shortcut.action==QUICK_HUD){
        hudCursor=hud_first(&hudHits);
        hudConsumed|=buttons;
      }
      else{
        refresh();refresh_battle_companion();companion=true;
        shell.mapX=snapshot.mapX;shell.mapY=snapshot.mapY;
      }
      quickOpen=false;
    }
    return;
  }
  if(companionTap) {
    companion=!companion;
    if(companion){refresh();refresh_battle_companion();shell.mapX=snapshot.mapX;shell.mapY=snapshot.mapY;}
  }
  if(!companion) {
    if(stylusChord && hit(RETRO_DEVICE_ID_JOYPAD_Y)){
      manualTouch=!manualTouch;screen_override=0;select_used=true;
    }else if(shell.screen_id()>=0 && hit(shell.screen_id())){
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
  if(shell.control(edge) && (!shell.frame.moreOpened || !pd_ui_back(&shell.frame)))companion=false;
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
  ds_get_system_av_info(info);
  info->geometry={512,384,512,384,4.f/3.f};
}
RETRO_API bool retro_load_game(const retro_game_info* info){
  pd_ds::clear();pd_ds::width=512;pd_ds::height=384;
  if(!ds_load_game(info))return false;
  // Read for the hash and the companion's art: a game too big for memory (or not a file) leaves the companion off
  // rather than throwing out of a C entry point.
  std::vector<uint8_t> rom;
  try{
    std::ifstream f(info->path?info->path:"",std::ios::binary|std::ios::ate);
    if(f){auto size=f.tellg();if(size>0){rom.resize(size_t(size));f.seekg(0);
      if(!f.read(reinterpret_cast<char*>(rom.data()),size))rom.clear();}}
  }catch(const std::exception&){rom.clear();rom.shrink_to_fit();
    log(RETRO_LOG_WARN,"PokeDaisy DS: couldn't read the ROM for the companion\n");}
  DsDexArt dexArt;
  if(rom.size()>=512) {
    // Preserve the existing exact-ROM guard: offsets and art are rev-specific.
    uint8_t hash[20];ds::crypto::sha1(rom.data(),rom.size(),hash);
    char dump[41];for(int i=0;i<20;++i)std::sprintf(dump+2*i,"%02x",hash[i]);
    const char* hex=ds_canonical_sha1(dump);
    pd_ds::supported=!std::strcmp(hex,pd_ds_data::sha1);
    pd_ds::companionGame=ds_companion_game(hex);
    pd_ds::soulSilver=!std::strcmp(hex,pd_ds_data::soulSilverSha1);
    pd_ds::battleStyle=ds_battle_style(hex);
    ds_battle_set_base_pp({});
    if(pd_ds::battleStyle)
      pd_ds::game=pk_nds_field_game(reinterpret_cast<const char*>(rom.data()+12),rom[30]);
    if(pd_ds::companionGame!=DsGame::Unsupported)pd_ds::map=ds_map_art(rom,pd_ds::companionGame);
    if(pd_ds::companionGame!=DsGame::Unsupported){
      dexArt=ds_dex_art(rom,pd_ds::companionGame);
      pd_ds::icons=ds_party_icons(rom,pd_ds::companionGame);
      pd_ds::hudItemIcons=ds_hud_item_icons(rom,pd_ds::companionGame);
      ds_battle_set_base_pp(ds_move_base_pp(rom,pd_ds::companionGame));
    }
  }
  const auto old_layout=opt("pokedaisy_ds_layout");
  pd_ds::shell.init(dir(RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY),
    old_layout=="top"?1:old_layout=="bottom"?2:old_layout=="stacked"?4:0,opt("pokedaisy_ds_hud")=="enabled");
  pd_ds::shell.set_game(pd_ds::companionGame,pd_ds::soulSilver);
  pd_ds::set_wild_rates();
  pd_ds::shell.dexArt=std::move(dexArt);
  log(RETRO_LOG_INFO,"PokeDaisy DS: companion %s\n",pd_ds::companionGame!=DsGame::Unsupported?"enabled":"unavailable for this ROM");
  return true;
}
RETRO_API void retro_reset(){
  pd_ds::clear_battle();
  ds_reset();pd_ds::clear_hud_focus();pd_ds::stageReady=false;pd_ds::clear_field_actions();pd_ds::flyJob={};pd_ds::flyPicker=pd_ds::heldQuickConfirm=false;pd_ds::companion=pd_ds::quickOpen=false;pd_ds::shell.picker=-1;pd_ds::shell.summary=false;pd_ds::shell.details=false;pd_ds::shell.dexEntry=0;pd_ds::shell.guideLoadedPage=-1;pd_ui_guide_reset(&pd_ds::shell.frame);pd_ds::shell.placeRow=pd_ds::shell.mapAction=-1;pd_ds::screen_override=0;pd_ds::snapshot={};pd_ds::repelFull=0;pd_ds::tick=0;pd_ds::scene.reset();pd_ds::arrangement={};pd_ds::manualTouch=pd_ds::fieldValid=pd_ds::fieldMenuFlow=false;pd_ds::companionHold=0;
}
RETRO_API bool retro_unserialize(const void* data,size_t n){
  pd_ds::clear_battle();
  // A DSperate state carries the cart's save memory: loading one would put the state's (maybe older) save over the
  // battery save the frontend then writes to its .srm. States never carry the save here, as the Linux host's
  // (load_state_keeping_save) and the GB / GBA core's don't: the save the console held is put back, in the same buffer
  // the frontend was given (retro_get_memory_data), still dirty if it was.
  std::vector<uint8_t> battery;bool dirty=false;uint8_t* frontendSave=nullptr;
  if(nds&&nds->cart){battery=nds->cart->sram();dirty=nds->cart->sram_dirty();frontendSave=nds->cart->sram().data();}
  bool ok=ds_unserialize(data,n);
  if(ok&&nds&&nds->cart&&!battery.empty()){
    auto& sram=nds->cart->sram();
    if(sram.size()==battery.size())std::memcpy(sram.data(),battery.data(),battery.size());else sram=battery;
    if(!dirty)nds->cart->clear_sram_dirty();
    if(sram.data()!=frontendSave)log(RETRO_LOG_WARN,"PokeDaisy DS: the save buffer moved on a state load\n");
  }
  if(ok){pd_ds::shell.battle={};pd_ds::shell.update_battle();pd_ds::stageReady=false;pd_ds::restoreGrace=4;pd_ds::set_wild_rates();}
  if(ok){pd_ds::clear_hud_focus();pd_ds::clear_field_actions();pd_ds::flyJob={};pd_ds::flyPicker=pd_ds::heldQuickConfirm=false;pd_ds::companion=pd_ds::quickOpen=false;pd_ds::shell.picker=-1;pd_ds::shell.summary=false;pd_ds::shell.details=false;pd_ds::shell.dexEntry=0;pd_ds::shell.guideLoadedPage=-1;pd_ui_guide_reset(&pd_ds::shell.frame);pd_ds::shell.placeRow=pd_ds::shell.mapAction=-1;pd_ds::screen_override=0;pd_ds::snapshot={};pd_ds::repelFull=0;pd_ds::tick=0;pd_ds::scene.reset();pd_ds::arrangement={};pd_ds::manualTouch=pd_ds::fieldValid=pd_ds::fieldMenuFlow=false;pd_ds::companionHold=0;}
  return ok;
}
RETRO_API void retro_unload_game(){ds_unload_game();pd_ds::clear();}
RETRO_API void retro_run(){
  if(!nds)return;
  input_poll_cb();
  const unsigned previous=pd_ds::old_buttons;
  const bool menuWasOpen=pd_ds::companion || pd_ds::quickOpen || pd_ds::hudCursor>=0;
  pd_ds::controls();
  const int field=pk_nds_field_state(pd_ds::game,pd_ds::read32,nullptr,0);
  if(pd_ds::shell.frame.battleWasOn && !(field&PK_NDS_FIELD_BATTLE)){
    pd_ds::shell.battle={};
    pd_ds::shell.update_battle();
  }
  pd_ds::update_battle(menuWasOpen?0:pd_ds::buttons&~previous,field);
  if(pd_ds::companion || pd_ds::quickOpen || pd_ds::hudCursor>=0){
    if(pd_ds::quickOpen)pd_ds::draw_quick();
    else if(pd_ds::companion)pd_ds::draw_companion();
    else pd_ds::present(nullptr,0,0,0);
    static const int16_t silence[548*2]={};audio_batch_cb(silence,547);
  }else {
    pd_ds::update_fly();
    pd_ds::swallowMenuInput=menuWasOpen;
    if(pd_ds::field_free(field))
      pk_nds_field_state(pd_ds::game,pd_ds::read32,pd_ds::write32,pd_ds::shell.settings.wildOff);
    constexpr unsigned directions=(1u<<RETRO_DEVICE_ID_JOYPAD_UP)|(1u<<RETRO_DEVICE_ID_JOYPAD_DOWN)|
      (1u<<RETRO_DEVICE_ID_JOYPAD_LEFT)|(1u<<RETRO_DEVICE_ID_JOYPAD_RIGHT);
    pd_ds::autoRunThisFrame=pd_ds::shell.autoRun && !pd_ds::manualTouch &&
      !pd_ds::pcJob && ds_auto_run(field,pd_ds::buttons&directions,pd_ds::buttons&(1u<<RETRO_DEVICE_ID_JOYPAD_A));
    ds_run();
    pd_ds::update_pc();
    pd_ds::update_fly_call();
    pd_ds::swallowMenuInput=false;
    ++pd_ds::tick;
  }
}
}
