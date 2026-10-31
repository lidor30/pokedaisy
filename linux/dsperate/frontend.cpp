// GPL-3.0. Optional DSperate frontend; optional, never used by mGBA.
#include "core/nds.h"
#include "core/state/state.h"
#include "core/crypto/sha1.h"
#if DSPERATE_JIT
#include "core/cpu/jit/jit.h"
#endif
#include "companion.h"
#include "data_gen.h"
#include "battle_view.h"
#include "display.h"
#include "map_art.h"
#include "pk_nds_field.h"
#include <SDL.h>
#include <filesystem>
#include <fstream>
#include <chrono>
#include <ctime>
#include <cmath>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
extern "C" {
#include "pd_canvas.h"
#include "pd_style.h"
#include "pd_png.h"
#include "pd_tables.h"
#include "../quick_menu.h"
#include "../hud.h"
bool pocket_save_backup(const char*,time_t);
}
namespace {
ds::NDS* active=nullptr;
uint32_t read32(uint32_t a){return a>=0x02000000 && a<=0x023ffffc?active->bus.dma_read32(ds::Cpu::ARM9,a):0;}
void write32(uint32_t a,uint32_t v){if(a>=0x02000000 && a<=0x023ffffc)active->bus.dma_write32(ds::Cpu::ARM9,a,v);}
std::vector<uint8_t> read_file(const std::string& path){std::ifstream f(path,std::ios::binary);return {std::istreambuf_iterator<char>(f),{}};}
bool atomic_file(const std::string& path,const uint8_t* data,size_t size){
  std::string temp=path+".tmp";FILE* f=std::fopen(temp.c_str(),"wb");if(!f)return false;
  bool ok=std::fwrite(data,1,size,f)==size;
  if(std::fflush(f) || fsync(fileno(f)))ok=false;
  if(std::fclose(f))ok=false;
  if(ok && !std::rename(temp.c_str(),path.c_str()))return true;
  std::remove(temp.c_str());return false;
}
struct DsPrefs {
  bool bottom=false, resume=false, hud=true;
  int battle=0;
  bool operator!=(const DsPrefs& other)const{return bottom!=other.bottom || resume!=other.resume || battle!=other.battle || hud!=other.hud;}
};
DsPrefs load_prefs(const std::string& path){
  DsPrefs p;std::ifstream f(path);std::string line;
  while(std::getline(f,line)){
    if(!line.empty() && line.back()=='\r')line.pop_back();
    if(line=="battle=0")p.battle=0;else if(line=="battle=1")p.battle=1;else if(line=="battle=2")p.battle=1;
    else if(line=="hud=1")p.hud=true;else if(line=="hud=0")p.hud=false;
    else if(line=="bottom=1")p.bottom=true;else if(line=="bottom=0")p.bottom=false;
    else if(line=="resume=1")p.resume=true;else if(line=="resume=0")p.resume=false;
  }
  return p;
}
bool save_prefs(const std::string& path,const DsPrefs& p){
  std::string text="# PokeDaisy DS preferences\nbottom="+std::to_string(p.bottom)+"\nresume="+std::to_string(p.resume)+"\nbattle="+std::to_string(p.battle)+"\nhud="+std::to_string(p.hud)+"\n";
  return atomic_file(path,reinterpret_cast<const uint8_t*>(text.data()),text.size());
}
int keyboard(SDL_Keycode k){switch(k){
  case SDLK_z:return 0;case SDLK_x:return 1;case SDLK_BACKSPACE:return 2;case SDLK_RETURN:return 3;
  case SDLK_RIGHT:return 4;case SDLK_LEFT:return 5;case SDLK_UP:return 6;case SDLK_DOWN:return 7;
  case SDLK_e:return 8;case SDLK_q:return 9;case SDLK_s:return 10;case SDLK_a:return 11;
  case SDLK_TAB:return 12;case SDLK_SPACE:return 13;case SDLK_ESCAPE:return 15;default:return -1;}}
int pad(int b){switch(b){
  case SDL_CONTROLLER_BUTTON_B:return 0;case SDL_CONTROLLER_BUTTON_A:return 1;
  case SDL_CONTROLLER_BUTTON_BACK:return 2;case SDL_CONTROLLER_BUTTON_START:return 3;
  case SDL_CONTROLLER_BUTTON_DPAD_RIGHT:return 4;case SDL_CONTROLLER_BUTTON_DPAD_LEFT:return 5;
  case SDL_CONTROLLER_BUTTON_DPAD_UP:return 6;case SDL_CONTROLLER_BUTTON_DPAD_DOWN:return 7;
  case SDL_CONTROLLER_BUTTON_RIGHTSHOULDER:return 8;case SDL_CONTROLLER_BUTTON_LEFTSHOULDER:return 9;
  case SDL_CONTROLLER_BUTTON_Y:return 10;case SDL_CONTROLLER_BUTTON_X:return 11;
  case SDL_CONTROLLER_BUTTON_GUIDE:return 15;default:return -1;}}
struct Texture {SDL_Texture* p;~Texture(){SDL_DestroyTexture(p);}};
struct Audio {SDL_AudioDeviceID id=0;SDL_AudioStream* stream=nullptr;~Audio(){SDL_FreeAudioStream(stream);if(id)SDL_CloseAudioDevice(id);}};
void blit(SDL_Renderer* r,SDL_Texture* t,const uint32_t* px,int w,int h,int ow,int oh){
  SDL_UpdateTexture(t,nullptr,px,w*4);int scale=std::max(1,std::min(ow/w,oh/h));
  SDL_Rect dst{(ow-w*scale)/2,(oh-h*scale)/2,w*scale,h*scale};SDL_RenderCopy(r,t,nullptr,&dst);
}
void companion(pd_canvas& c,const DsCompanion& s,bool supported,int tab,int selected,bool summary,int scroll,int page,const DsPrefs& prefs,int setting,const DsMapArt& art,int mapX,int mapY){
  pd_fill(&c,0,0,c.w,c.h,0x50a8a8);pd_title_box(&c,5,5,c.w-10,25,0xffffff);
  pd_text(&c,12,10,"PokeDaisy DS",0x303030,PD_NO_SHADOW);
  const char* tabs[]={"PARTY","BAG","DEX","STATUS","SET","MAP"};
  for(int i=0;i<6;i++){int x=5+i*(c.w-10)/6;pd_title_box(&c,x,34,(c.w-10)/6-2,23,i==tab?0xffefbc:0xffffff);pd_text(&c,x+8,38,tabs[i],0x303030,PD_NO_SHADOW);}
  pd_list_box(&c,5,61,c.w-10,c.h-86);int y=68;
  auto line=[&](const std::string& t){pd_text_fit(&c,13,y,c.w-26,t.c_str(),0x303030,PD_NO_SHADOW);y+=20;};
  if(tab==4){
    const std::string rows[]={std::string("SCREEN: ")+(prefs.bottom?"BOTTOM":"TOP"),std::string("AUTO-RESUME: ")+(prefs.resume?"ON":"OFF"),std::string("BATTLES: ")+(prefs.battle?"AUTO":"OFF"),std::string("HUD: ")+(prefs.hud?"ON":"OFF")};
    for(int i=0;i<4;i++){if(i==setting)pd_fill(&c,10,y-2,c.w-20,20,0xffe4a0);line(rows[i]);}
    line("Resume saves on clean exit");line("Separate from manual states");
  }
  else if(!supported)line("Companion: Platinum USA Rev 1 only");
  else if(!s.valid){line("Waiting for the game's save data");line("Return to the game to finish booting");}
  else if(tab==5){
    if(art.background.empty())line("Map art unavailable");
    else {
      int ox=(c.w-art.width)/2,oy=61;
      for(int y=0;y<art.height;y++)for(int x=0;x<art.width;x++)c.px[(oy+y)*c.w+ox+x]=art.background[y*art.width+x]&0xffffff;
      auto sprite=[&](const std::vector<uint32_t>& img,int cx,int cy){for(int y=0;y<16;y++)for(int x=0;x<16;x++){
        int dx=cx-8+x,dy=cy-8+y;if(dx>=0 && dx<art.width && dy>=0 && dy<art.height && (img[y*16+x]>>24))c.px[(oy+dy)*c.w+ox+dx]=img[y*16+x]&0xffffff;
      }};
      for(auto mark:pd_ds_data::mapMarks){
        bool visited=mark.arrival<56 && ((s.arrivals>>mark.arrival)&1);
        if(mark.hidden && !visited)continue;
        int variant=visited?1+mark.palette:0,index=mark.shape*3+variant;
        if(index<int(art.blocks.size()))sprite(art.blocks[index],mark.x-pd_ds_data::mapLeft,mark.y-pd_ds_data::mapTop);
      }
      if(s.mapX>=0 && s.mapY>=0 && !art.heads.empty())sprite(art.heads[s.playerGender==1?1:0],pd_ds_data::mapOriginX+s.mapX*pd_ds_data::mapTile+3,pd_ds_data::mapOriginY+s.mapY*pd_ds_data::mapTile+3);
      int cx=ox+pd_ds_data::mapOriginX+mapX*pd_ds_data::mapTile,cy=oy+pd_ds_data::mapOriginY+mapY*pd_ds_data::mapTile;
      pd_clip(&c,ox,oy,art.width,art.height);
      pd_fill(&c,cx,cy,7,1,0xe84840);pd_fill(&c,cx,cy+6,7,1,0xe84840);pd_fill(&c,cx,cy,1,7,0xe84840);pd_fill(&c,cx+6,cy,1,7,0xe84840);pd_unclip(&c);
      pd_fill(&c,10,9,c.w-20,17,0xffffff);
      const char* name=ds_map_name(ds_map_section(mapX,mapY));pd_text_fit(&c,12,10,c.w-24,*name?name:"No location",0x303030,PD_NO_SHADOW);
    }
  }
  else if(tab==0 && summary && selected<int(s.party.size())){
    auto& m=s.party[selected];line(m.nickname+"  Lv"+std::to_string(m.level));
    line(std::string(ds_species(m.species))+"  HP "+std::to_string(m.hp)+"/"+std::to_string(m.maxHp));
    line(std::string("ITEM: ")+ds_item(m.heldItem));
    if(page){
      line(std::string("NATURE: ")+ds_nature(m.nature));
      line("EXP: "+std::to_string(m.experience)+(m.level==100?"  MAX LEVEL":""));
      line(std::string("STATUS: ")+ds_status(m));
      line(std::string(m.egg?"EGG  ":"")+"GENDER: "+(m.gender==1?"MALE":m.gender==2?"FEMALE":"NONE"));
    }else for(int i=0;i<4;i++)if(m.moves[i])line(std::string(ds_move(m.moves[i]))+"  PP "+std::to_string(m.pp[i]));
  }else if(tab==0){for(size_t i=0;i<s.party.size();i++){
    auto& m=s.party[i];if(int(i)==selected)pd_fill(&c,10,y-2,c.w-20,20,0xffe4a0);
    line(m.nickname+" Lv"+std::to_string(m.level)+"  "+std::to_string(m.hp)+"/"+std::to_string(m.maxHp));
  }}else if(tab==1){int rows=(c.h-100)/20;for(int i=scroll;i<int(s.bag.size()) && i<scroll+rows;i++)line(std::string(ds_item(s.bag[i].id))+" x"+std::to_string(s.bag[i].count));}
  else if(tab==2){
    if(!s.dexValid)line("Pokedex data unavailable");
    else {
      line("SEEN "+std::to_string(s.seenCount)+"  OWN "+std::to_string(s.caughtCount));
      int rows=(c.h-120)/20;
      for(int n=scroll+1;n<int(s.dexSeen.size()) && n<scroll+1+rows;n++){
        char number[16];std::snprintf(number,sizeof number,"%03d ",n);
        line(std::string(number)+(s.dexSeen[n]?ds_species(n):"???")+(s.dexCaught[n]?"  OWN":s.dexSeen[n]?"  SEEN":""));
      }
    }
  }
  else {line("TRAINER: "+s.trainer);line("MONEY: "+std::to_string(s.money));line(std::string("PLACE: ")+ds_place(s.map));line("MAP: "+std::to_string(s.map));line(s.dexValid?(s.nationalDex?"NATIONAL DEX: UNLOCKED":"NATIONAL DEX: LOCKED"):"DEX: UNAVAILABLE");}
  pd_text_fit(&c,9,c.h-20,c.w-18,tab==5?"D-pad move  A player  L/R tabs":tab==4?"Up/Down select  A change  L/R tabs":tab==0 && summary?"Left/Right info/moves  Up/Down Pokemon":tab==1 || tab==2?"Up/Down scroll  Left/Right page  L/R tabs":"L/R tabs  A summary  B back  L2 game",0xffffff,PD_NO_SHADOW);
}
}
extern "C" int pd_ds_run(const char* rom,const char* root,int limit,const char* shot,SDL_Renderer* renderer,bool (*keepRunning)()) {
  auto bytes=read_file(rom);if(bytes.size()<512 || bytes.size()>512u*1024*1024){std::fprintf(stderr,"DS: invalid ROM size\n");return 1;}
  uint8_t digest[20];ds::crypto::sha1(bytes.data(),bytes.size(),digest);char hex[41];for(int i=0;i<20;i++)std::sprintf(hex+2*i,"%02x",digest[i]);
  bool supported=!std::strcmp(hex,pd_ds_data::sha1);char code[5]={};std::memcpy(code,bytes.data()+12,4);int revision=bytes[30];
  DsMapArt mapArt=supported?ds_map_art(bytes):DsMapArt{};
  auto partyArt=supported?ds_party_icons(bytes):std::vector<std::vector<uint32_t>>{};
  bytes.clear();bytes.shrink_to_fit();
  std::fprintf(stderr,"DSperate %s rev %d SHA1 %s, companion %s\n",code,revision,hex,supported?"Platinum":"unsupported");
  auto nds=std::make_unique<ds::NDS>();active=nds.get();
  std::string err;if(!nds->load_bios("","","",{},&err) || !nds->load_rom(rom)){std::fprintf(stderr,"DS boot failed: %s %s\n",err.c_str(),nds->rom_error.c_str());return 1;}
  nds->setup_direct_boot();nds->io.start_rtc_clock();
  std::filesystem::path dir=root;auto filename=std::filesystem::path(rom).filename().string();
  std::filesystem::create_directories(dir/"saves");std::filesystem::create_directories(dir/"states");
  std::string save=(dir/"saves"/(filename+".sav")).string(),state=(dir/"states"/(filename+".dsperate.ss")).string();
  if(!pocket_save_backup(save.c_str(),std::time(nullptr)))return 1;
  if(std::filesystem::exists(save)) {auto data=read_file(save);if(data.empty() || !nds->cart->load_save(data.data(),data.size()).fitted){std::fprintf(stderr,"DS: save size mismatch; refusing to overwrite it\n");return 1;}}
  std::string config=(dir/"ds-settings.ini").string(),resume=(dir/"states"/(filename+".dsperate.resume.ss")).string();
  DsPrefs prefs=load_prefs(config);
  if(auto value=std::getenv("POCKET_DS_RESUME"))prefs.resume=std::strcmp(value,"0")!=0;
  if(auto value=std::getenv("POCKET_DS_BATTLE"))prefs.battle=std::clamp(std::atoi(value),0,1);
  bool autoResumed=false;
  if(prefs.resume && std::filesystem::exists(resume)){
    if(std::filesystem::exists(save) && std::filesystem::last_write_time(save)>std::filesystem::last_write_time(resume)){
      std::fprintf(stderr,"DS resume skipped: battery save is newer\n");
    }else{
      // Deserialize into a separate console: a failed load can mutate its target.
      // Keep the fresh console and the supplied battery save intact on failure.
      auto candidate=std::make_unique<ds::NDS>();std::string problem;
      auto data=read_file(resume);
      if(data.size()<8)std::fprintf(stderr,"DS resume ignored: truncated state; booting normally\n");
      else {ds::state::Reader reader(data.data(),data.size());
      bool ready=candidate->load_bios("","","",{},&problem) && candidate->load_rom(rom);
      if(ready)candidate->setup_direct_boot();
      if(ready && candidate->load_state(reader,problem)){
        nds=std::move(candidate);active=nds.get();autoResumed=true;std::fprintf(stderr,"DS auto-resumed at core frame %llu\n",(unsigned long long)nds->frame_count);
      }else std::fprintf(stderr,"DS resume ignored: %s; booting normally\n",problem.c_str());
      }
    }
  }
#if DSPERATE_JIT
  if(!ds::jit::attach(*nds,true,true)){std::fprintf(stderr,"DS JIT attach failed\n");return 1;}
#endif
  Audio audio;SDL_AudioSpec want{},got{};want.freq=32768;want.format=AUDIO_S16SYS;want.channels=2;want.samples=512;
  audio.id=SDL_OpenAudioDevice(nullptr,0,&want,&got,SDL_AUDIO_ALLOW_FREQUENCY_CHANGE);
  if(audio.id){audio.stream=SDL_NewAudioStream(AUDIO_S16SYS,2,int(std::lround(nds->spu.output_rate_hz())),got.format,got.channels,got.freq);SDL_PauseAudioDevice(audio.id,0);}
  Texture top{SDL_CreateTexture(renderer,SDL_PIXELFORMAT_ARGB8888,SDL_TEXTUREACCESS_STREAMING,256,192)};
  Texture overlay{SDL_CreateTexture(renderer,SDL_PIXELFORMAT_ARGB8888,SDL_TEXTUREACCESS_STREAMING,341,256)};
  Texture ui{SDL_CreateTexture(renderer,SDL_PIXELFORMAT_RGB888,SDL_TEXTUREACCESS_STREAMING,341,256)};
  if(!top.p || !ui.p || !overlay.p)return 1;
  SDL_SetTextureBlendMode(overlay.p,SDL_BLENDMODE_BLEND);
  std::vector<uint32_t> uiPixels(341*256);pd_canvas canvas;pd_canvas_init(&canvas,341,256,uiPixels.data());
  const auto* field=supported?pk_nds_field_game(code,revision):nullptr;
  bool& bottom=prefs.bottom;
  bool done=false,showCompanion=false,showQuick=false,paused=false,summary=false,failed=false;
  int quickSelected=0;uint32_t blocked=0;
  int mapX=-1,mapY=-1,setting=0,page=0,tab=0,selected=0,scroll=0,touchX=128,touchY=96;uint32_t keys=0;unsigned repeat[8]={};int frame=0,lastPhase=-1,overrideScreen=-1;
  const int raw[]={1,0,11,10,9,8,2,3,15,-1,12,13};
  struct Press{int at,n;uint32_t mask;};std::vector<Press> script,hostScript;
  if(auto s=std::getenv("POCKET_DS_KEYS")){std::string text=s;size_t pos=0;while(pos<text.size()){Press p{};int used=0;if(std::sscanf(text.c_str()+pos,"%d:%u:%d%n",&p.at,&p.mask,&p.n,&used)!=3)break;script.push_back(p);pos+=used+1;}}
  if(auto s=std::getenv("POCKET_DS_HOST_KEYS")){std::string text=s;size_t pos=0;while(pos<text.size()){Press p{};int used=0;if(std::sscanf(text.c_str()+pos,"%d:%u:%d%n",&p.at,&p.mask,&p.n,&used)!=3)break;hostScript.push_back(p);pos+=used+1;}}
  int companionAt=std::getenv("POCKET_DS_COMPANION_AT")?std::atoi(std::getenv("POCKET_DS_COMPANION_AT")):-1;
  uint64_t started=SDL_GetPerformanceCounter();uint32_t lastWrites=nds->cart->sram_writes();int quiet=autoResumed?60:0;
  while(!done && keepRunning() && (!limit || frame<limit)) {
    DsPrefs previous=prefs;
    uint64_t tick=SDL_GetPerformanceCounter();uint32_t pressed=0;SDL_Event e;
    auto key=[&](int k,bool down){if(k<0)return;uint32_t bit=1u<<k;if(down){if(!(keys&bit))pressed|=bit;keys|=bit;}else keys&=~bit;};
    while(SDL_PollEvent(&e)){
      if(e.type==SDL_QUIT)done=true;
      int k=-1;bool down=false;
      if(e.type==SDL_KEYDOWN || e.type==SDL_KEYUP){if(e.key.repeat)continue;k=keyboard(e.key.keysym.sym);down=e.type==SDL_KEYDOWN;}
      if(e.type==SDL_CONTROLLERBUTTONDOWN || e.type==SDL_CONTROLLERBUTTONUP){k=pad(e.cbutton.button);down=e.type==SDL_CONTROLLERBUTTONDOWN;}
      if(e.type==SDL_CONTROLLERAXISMOTION && (e.caxis.axis==SDL_CONTROLLER_AXIS_TRIGGERLEFT || e.caxis.axis==SDL_CONTROLLER_AXIS_TRIGGERRIGHT)){k=e.caxis.axis==SDL_CONTROLLER_AXIS_TRIGGERLEFT?12:13;down=e.caxis.value>16000;}
      if(e.type==SDL_CONTROLLERAXISMOTION){
        if(e.caxis.axis==SDL_CONTROLLER_AXIS_LEFTX){key(5,e.caxis.value<-16000);key(4,e.caxis.value>16000);}
        if(e.caxis.axis==SDL_CONTROLLER_AXIS_LEFTY){key(6,e.caxis.value<-16000);key(7,e.caxis.value>16000);}
      }
      if((e.type==SDL_JOYBUTTONDOWN || e.type==SDL_JOYBUTTONUP) && std::getenv("POCKET_RAW_TRIMUI") && !SDL_IsGameController(0)){k=e.jbutton.button<12?raw[e.jbutton.button]:-1;down=e.type==SDL_JOYBUTTONDOWN;}
      if(std::getenv("POCKET_RAW_TRIMUI") && !SDL_IsGameController(0)){
        if(e.type==SDL_JOYHATMOTION){key(4,e.jhat.value&SDL_HAT_RIGHT);key(5,e.jhat.value&SDL_HAT_LEFT);key(6,e.jhat.value&SDL_HAT_UP);key(7,e.jhat.value&SDL_HAT_DOWN);}
        if(e.type==SDL_JOYAXISMOTION && (e.jaxis.axis==2 || e.jaxis.axis==5)){k=e.jaxis.axis==2?12:13;down=e.jaxis.value>16000;}
      }
      key(k,down);
      if(e.type==SDL_WINDOWEVENT && e.window.event==SDL_WINDOWEVENT_FOCUS_LOST){keys=0;nds->io.set_buttons(0);nds->io.set_touch(0,0,false);paused=true;}
    }
    // Test the same host input path as real controls, rather than only guest buttons.
    for(auto p:hostScript)for(int k=0;k<16;k++)if(p.mask&(1u<<k)){
      if(frame==p.at)key(k,true);else if(frame==p.at+p.n)key(k,false);
    }
    unsigned now=SDL_GetTicks();for(int k=4;k<8;k++){if(pressed&(1u<<k))repeat[k]=now+400;else if((keys&(1u<<k)) && int(now-repeat[k])>=0){pressed|=1u<<k;repeat[k]=now+90;}}
    if(companionAt>=0 && frame==companionAt)showCompanion=true;
    blocked &= keys;
    if(pressed&(1u<<12)){
      if(showCompanion)showCompanion=false;else showQuick=!showQuick;
      paused=false;blocked|=keys&4095u;nds->io.set_buttons(0);nds->io.set_touch(0,0,false);
    }
    if(auto value=std::getenv("POCKET_DS_QUICK_AT"))if(frame==std::atoi(value))showQuick=true;
    int fieldBits=field?pk_nds_field_state(field,read32,write32,0):0;
    bool inBattle=(fieldBits&PK_NDS_FIELD_BATTLE)!=0;
    bool choosing=inBattle && pk_nds_battle_command(field,read32)==pk_nds_battle_selecting(field);
    int phase=inBattle?(choosing?2:1):0;
    if(phase!=lastPhase){overrideScreen=-1;lastPhase=phase;std::fprintf(stderr,"DS battle phase %d at core frame %llu\n",phase,(unsigned long long)nds->frame_count);}
    auto view=ds_battle_view(prefs.battle,inBattle,choosing,bottom,overrideScreen);
    if(pressed&(1u<<13)){
      if(prefs.battle && inBattle){
        overrideScreen=overrideScreen<0?(view.bottom?0:1):-1;
      }else bottom=!bottom;
    }
    view=ds_battle_view(prefs.battle,inBattle,choosing,bottom,overrideScreen);
    if(choosing && std::getenv("POCKET_DS_STOP_ON_BATTLE_INPUT"))limit=frame+1;
    if(pressed&(1u<<15)){paused=!paused;showQuick=false;blocked|=keys&4095u;}
    DsCompanion snap=supported?ds_read_companion(nds->bus.main_ram.get(),nds->bus.main_ram_size()):DsCompanion{};
    quick_item quickItems[4]{};
    quickItems[0].action=QUICK_COMPANION;
    quickItems[1].action=QUICK_SAVE_STATE;
    quickItems[2].action=QUICK_LOAD_STATE;
    quickItems[2].why=std::filesystem::exists(state)?nullptr:"No saved state";
    quickItems[3].action=QUICK_COMPANION;quickItems[3].label="LEAVE GAME";
    if(showQuick){
      blocked|=keys&4095u;
      int dx=(pressed&(1u<<4)?1:0)-(pressed&(1u<<5)?1:0);
      int dy=(pressed&(1u<<7)?1:0)-(pressed&(1u<<6)?1:0);
      if(dx || dy)quickSelected=quick_step(4,quickSelected,dx,dy,341,256);
      if(pressed&(1u<<1))showQuick=false;
      else if((pressed&1) && !quickItems[quickSelected].why){
        showQuick=false;
        if(quickSelected==0)showCompanion=true;
        else if(quickSelected==3)done=true;
        else {paused=true;pressed=quickSelected==1?1u:1u<<10;}
      }
      // Never forward the key which opened, navigated or closed the overlay.
      if(showQuick || showCompanion || done)pressed=0;
    }
    if(paused){if(pressed&(1u<<1))paused=false;if(pressed&(1u<<3))done=true;
      if(pressed&1){ds::state::Writer w;if(!nds->save_state(w,err) || !atomic_file(state,w.data().data(),w.data().size())){std::fprintf(stderr,"DS state save failed: %s\n",err.c_str());failed=true;done=true;}paused=false;}
      if(pressed&(1u<<10)){auto data=read_file(state);if(!data.empty()){ds::state::Reader r(data.data(),data.size());if(!nds->load_state(r,err)){failed=true;done=true;std::fprintf(stderr,"DS state load failed: %s\n",err.c_str());}else std::fprintf(stderr,"DS manual state loaded at core frame %llu\n",(unsigned long long)nds->frame_count);}paused=false;}
    }else if(showQuick){
      nds->io.set_buttons(0);nds->io.set_touch(0,0,false);
    }else if(showCompanion){
      blocked|=keys&4095u;
      if(tab==0 && summary && (pressed&((1u<<4)|(1u<<5))))page=1-page;
      if(pressed&(1u<<8)){tab=(tab+1)%6;summary=false;scroll=0;}
      if(pressed&(1u<<9)){tab=(tab+5)%6;summary=false;scroll=0;}
      if(tab==5){
        int minY=std::max(0,int(std::ceil(double(-pd_ds_data::mapOriginY-3)/pd_ds_data::mapTile)));
        int maxY=std::min(29,int((pd_ds_data::mapHeight-1-pd_ds_data::mapOriginY-3)/pd_ds_data::mapTile));
        if(mapX<0 || (pressed&((1u<<8)|(1u<<9)|1))){mapX=snap.mapX>=0?snap.mapX:0;mapY=snap.mapY>=0?snap.mapY:minY;}
        if(pressed&(1u<<4))mapX++;
        if(pressed&(1u<<5))mapX--;
        if(pressed&(1u<<6))mapY--;
        if(pressed&(1u<<7))mapY++;
        mapX=std::clamp(mapX,0,29);mapY=std::clamp(mapY,minY,maxY);
      }
      if(tab==4){
        if(pressed&(1u<<6))setting=(setting+3)%4;
        if(pressed&(1u<<7))setting=(setting+1)%4;
        if(pressed&1){if(setting==0)bottom=!bottom;else if(setting==1)prefs.resume=!prefs.resume;else if(setting==2)prefs.battle=1-prefs.battle;else prefs.hud=!prefs.hud;}
      }
      if(tab!=4 && tab!=5 && (pressed&(1u<<6))){if(tab==1 || tab==2)scroll=std::max(0,scroll-1);else selected=std::max(0,selected-1);}
      if(tab!=4 && tab!=5 && (pressed&(1u<<7))){if(tab==1 || tab==2)scroll=std::min(std::max(0,(tab==1?int(snap.bag.size()):int(snap.dexSeen.size())-1)-1),scroll+1);else selected=std::min(std::max(0,int(snap.party.size())-1),selected+1);}
      if(tab==1 || tab==2){
        int last=std::max(0,(tab==1?int(snap.bag.size()):int(snap.dexSeen.size())-1)-1);
        if(pressed&(1u<<4))scroll=std::min(last,scroll+7);
        if(pressed&(1u<<5))scroll=std::max(0,scroll-7);
      }
      if(tab==0 && (pressed&1))summary=true;
      if(pressed&(1u<<1)){if(summary)summary=false;else showCompanion=false;}
    }else if(!done){
      uint32_t gameKeys=keys&~blocked;
      bool touchMode=view.bottom && (gameKeys&(1u<<2));
      if(touchMode){if(gameKeys&(1u<<4))touchX=std::min(255,touchX+3);if(gameKeys&(1u<<5))touchX=std::max(0,touchX-3);if(gameKeys&(1u<<6))touchY=std::max(0,touchY-3);if(gameKeys&(1u<<7))touchY=std::min(191,touchY+3);}
      nds->io.set_touch(touchX,touchY,touchMode && (gameKeys&1));
      uint32_t held=touchMode?0:gameKeys&4095u;for(auto p:script)if(frame>=p.at && frame<p.at+p.n)held|=p.mask;
      nds->io.set_buttons(held);
      nds->run_frame();nds->gpu.quiesce();
      if(nds->cart->sram_writes()!=lastWrites){lastWrites=nds->cart->sram_writes();quiet=0;}else quiet++;
      if(quiet>=60 && nds->cart->sram_dirty()){auto& s=nds->cart->sram();if(!s.empty() && !atomic_file(save,s.data(),s.size())){failed=true;done=true;}else nds->cart->clear_sram_dirty();}
      int16_t samples[2048];size_t n;while((n=nds->spu.take(samples,1024)))if(audio.stream)SDL_AudioStreamPut(audio.stream,samples,int(n*4));
      if(audio.stream){int available=SDL_AudioStreamAvailable(audio.stream);std::vector<uint8_t> out(std::max(0,available));if(available>0){SDL_AudioStreamGet(audio.stream,out.data(),available);if(SDL_GetQueuedAudioSize(audio.id)>unsigned(got.freq*2))SDL_ClearQueuedAudio(audio.id);SDL_QueueAudio(audio.id,out.data(),available);}}
    }
    if(prefs!=previous && !save_prefs(config,prefs))std::fprintf(stderr,"DS settings write failed\n");
    if((paused || showCompanion || showQuick) && audio.id)SDL_ClearQueuedAudio(audio.id);
    int ow=1024,oh=768;
    if(!ds_display_pixels(renderer,ow,oh)){std::fprintf(stderr,"DS display setup failed: %s\n",SDL_GetError());failed=true;break;}
    SDL_SetRenderDrawColor(renderer,0,0,0,255);SDL_RenderClear(renderer);
    if(showCompanion){companion(canvas,snap,supported,tab,selected,summary,scroll,page,prefs,setting,mapArt,mapX,mapY);blit(renderer,ui.p,uiPixels.data(),341,256,ow,oh);}
    else blit(renderer,top.p,nds->gpu.framebuffer(view.bottom?1:0),256,192,ow,oh);
    if(!showCompanion && !paused && (showQuick || (prefs.hud && snap.valid && !view.bottom && !inBattle && (fieldBits&PK_NDS_FIELD_SCREEN) && !(fieldBits&(PK_NDS_FIELD_LOCKED|PK_NDS_FIELD_MENU))))){
      for(auto& p:uiPixels)p=QUICK_CLEAR;
      if(showQuick)quick_draw(&canvas,nullptr,quickItems,4,quickSelected);
      else {
        pd_snapshot hs{};hs.valid=true;hs.partyCount=std::min(6,int(snap.party.size()));
        for(int i=0;i<hs.partyCount;i++){
          auto& m=snap.party[i];auto& h=hs.party[i];
          // This is a draw-only GBA HUD adapter. Do not put national DS IDs in
          // its internal GBA species field (Burmy 412 collides with its egg).
          h.species=m.egg?PD_SPECIES_EGG:0;h.hp=m.hp;h.maxHp=m.maxHp;h.level=m.level;h.status=m.status;
          std::snprintf(h.nickname,sizeof h.nickname,"%s",m.egg?"EGG":ds_species(m.species));
        }
        struct HudArt {const DsCompanion* snap;const pd_snapshot* view;const std::vector<std::vector<uint32_t>>* art;} context{&snap,&hs,&partyArt};
        hud_party_draw(&canvas,&hs,&context,[](pd_canvas* c,void* data,const pd_mon* m,int x,int y,bool faded){
          auto& ctx=*static_cast<HudArt*>(data);auto& mon=ctx.snap->party[m-ctx.view->party];
          int species=mon.egg?pd_ds_data::iconEgg:mon.species;
          if(species>0 && size_t(species)<ctx.art->size() && (*ctx.art)[species].size()==1024){
            auto& icon=(*ctx.art)[species];
            for(int yy=0;yy<32;yy++)for(int xx=0;xx<32;xx++){
              uint32_t p=icon[yy*32+xx];if(!(p>>24))continue;
              if(faded)p=((p&0xfefefe)>>1)&0xffffff;
              pd_fill(c,x+xx,y+yy,1,1,p&0xffffff);
            }
          }else pd_text_fit(c,x,y+10,32,m->nickname,faded?0xa8b0c0:0xf8f8f8,0x303840);
        });
      }
      quick_finish(&canvas);blit(renderer,overlay.p,uiPixels.data(),341,256,ow,oh);
    }
    if(view.bottom && !showCompanion && !paused && !showQuick && (keys&(1u<<2))){
      int w=256,h=192;
      int scale=std::max(1,std::min(ow/w,oh/h)),x=(ow-w*scale)/2+touchX*scale,y=(oh-h*scale)/2+touchY*scale;
      SDL_SetRenderDrawColor(renderer,0,0,0,255);SDL_Rect outline{x-5,y-5,11,11};SDL_RenderDrawRect(renderer,&outline);
      SDL_SetRenderDrawColor(renderer,255,255,255,255);SDL_RenderDrawLine(renderer,x-4,y,x+4,y);SDL_RenderDrawLine(renderer,x,y-4,x,y+4);
    }
    if(paused){pd_fill(&canvas,0,0,341,256,0x303838);pd_title_box(&canvas,15,40,311,170,0xffffff);pd_text(&canvas,28,55,"PokeDaisy DS / PAUSED",0x303030,PD_NO_SHADOW);pd_text(&canvas,28,90,"B resume   A save state",0x303030,PD_NO_SHADOW);pd_text(&canvas,28,120,"X load state   START quit",0x303030,PD_NO_SHADOW);blit(renderer,ui.p,uiPixels.data(),341,256,ow,oh);}
    if(shot && limit && frame==limit-1){std::vector<uint32_t> pixels(size_t(ow)*oh);if(!SDL_RenderReadPixels(renderer,nullptr,SDL_PIXELFORMAT_ARGB8888,pixels.data(),ow*4))pd_png_write(shot,ow,oh,pixels.data());}
    SDL_RenderPresent(renderer);frame++;
    if(!limit){double ms=1000.0*(SDL_GetPerformanceCounter()-tick)/SDL_GetPerformanceFrequency();if(ms<1000.0/59.8261)SDL_Delay(unsigned(1000.0/59.8261-ms));}
  }
  nds->io.set_buttons(0);nds->io.set_touch(0,0,false);
  // Let an in-progress battery write finish before replacing the disk copy.
  // A bounded failure preserves the last completed save and its startup backup.
  if(!failed && nds->cart->sram_dirty()){
    for(int i=0;i<300 && quiet<60;i++){
      nds->run_frame();
      if(nds->cart->sram_writes()!=lastWrites){lastWrites=nds->cart->sram_writes();quiet=0;}else quiet++;
    }
    if(quiet<60){std::fprintf(stderr,"DS: save never settled; preserving disk save\n");failed=true;}
  }
  if(!failed && nds->cart->sram_dirty()){auto& s=nds->cart->sram();if(!s.empty() && !atomic_file(save,s.data(),s.size()))failed=true;}
  if(!failed && prefs.resume){
    ds::state::Writer writer;err.clear();
    if(!nds->save_state(writer,err) || !atomic_file(resume,writer.data().data(),writer.data().size())){
      std::fprintf(stderr,"DS resume capture failed: %s\n",err.c_str());failed=true;
    }else std::fprintf(stderr,"DS resume captured at core frame %llu\n",(unsigned long long)nds->frame_count);
  }
  double seconds=double(SDL_GetPerformanceCounter()-started)/SDL_GetPerformanceFrequency();
  std::fprintf(stderr,"DS: %d host iterations in %.2fs, core frames %llu\n",frame,seconds,(unsigned long long)nds->frame_count);
#if DSPERATE_JIT
  ds::jit::detach(*nds);
#endif
  active=nullptr;return failed?1:0;
}
