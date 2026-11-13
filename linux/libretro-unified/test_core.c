/* Real libretro lifecycle/state test for either packaged core; no ROM data here. */
#include "libretro.h"
#include <assert.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static unsigned legacy_queries, geometry_updates;
static bool quick_pressed;
static int armgba_options, hidden_options; // armGBA's options the frontend was given, and how many it should not see
static bool hidden_key(const char* k){
 static const char* const prefix[]={"armgba_lcd_","armgba_gb_","armgba_link_","armgba_low_memory","armgba_threaded_video",
  "armgba_player1_saves","armgba_load_save","armgba_send_save","armgba_back_up_save","armgba_player2_game"};
 for(unsigned i=0;i<sizeof prefix/sizeof*prefix;i++)if(!strncmp(k,prefix[i],strlen(prefix[i])))return true;
 return false;}
static void count_options(const struct retro_core_options_v2* o){
 for(const struct retro_core_option_v2_definition* d=o?o->definitions:NULL;d&&d->key;d++)
  if(!strncmp(d->key,"armgba_",7)){armgba_options++;if(hidden_key(d->key))hidden_options++;}}
static const char* dir;static unsigned fmt,w,h;static unsigned char frame[1024*768*4];
static bool env(unsigned cmd,void* p){switch(cmd){
 case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:fmt=*(unsigned*)p;return fmt==RETRO_PIXEL_FORMAT_RGB565 || fmt==RETRO_PIXEL_FORMAT_XRGB8888;
 case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:*(unsigned*)p=2;return true;
 case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:count_options(((struct retro_core_options_v2_intl*)p)->us);return true;
 case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:count_options(p);return true;
 case RETRO_ENVIRONMENT_SET_VARIABLES:return true;
 case RETRO_ENVIRONMENT_SET_GEOMETRY:++geometry_updates;return true;
 case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:return true;
 case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:*(const char**)p=dir;return true;
 case RETRO_ENVIRONMENT_GET_VARIABLE:{struct retro_variable* v=p;if(!strcmp(v->key,"pokedaisy_panel")){v->value="1024x768";return true;}return false;}
 case RETRO_ENVIRONMENT_GET_THROTTLE_STATE:
  if (!getenv("PD_MODERN_THROTTLE")) return false;
  *(struct retro_throttle_state*)p = (struct retro_throttle_state){RETRO_THROTTLE_VSYNC, 60.0f};
  return true;
 case RETRO_ENVIRONMENT_GET_FASTFORWARDING:++legacy_queries;*(bool*)p=getenv("PD_LEGACY_FF")!=NULL;return true;
 case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:*(bool*)p=false;return true;
 default:return false;}}
static void video(const void* p,unsigned x,unsigned y,size_t pitch){if(!p)return;assert(x<=1024&&y<=768);w=x;h=y;unsigned bytes=fmt==RETRO_PIXEL_FORMAT_RGB565?2:4;for(unsigned j=0;j<y;j++)memcpy(frame+j*x*bytes,(const char*)p+j*pitch,x*bytes);}
static size_t audio(const int16_t* d,size_t n){(void)d;return n;}
static void poll(void){}
static int16_t input(unsigned p,unsigned d,unsigned i,unsigned b){
 (void)i;
 if(p || d!=RETRO_DEVICE_JOYPAD || !quick_pressed)return 0;
 if(b==RETRO_DEVICE_ID_JOYPAD_MASK)return 1<<RETRO_DEVICE_ID_JOYPAD_Y;
 return b==RETRO_DEVICE_ID_JOYPAD_Y;
}
#define LOAD(n) __typeof__(&n) n##_fn=dlsym(so,#n);assert(n##_fn)
int main(int argc,char** argv){assert(argc==4);dir=argv[3];void* so=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);if(!so){puts(dlerror());return 1;}
 LOAD(retro_set_environment);LOAD(retro_set_video_refresh);LOAD(retro_set_audio_sample_batch);LOAD(retro_set_input_poll);LOAD(retro_set_input_state);LOAD(retro_init);LOAD(retro_load_game);LOAD(retro_run);LOAD(retro_serialize_size);LOAD(retro_serialize);LOAD(retro_unserialize);LOAD(retro_get_memory_data);LOAD(retro_get_memory_size);LOAD(retro_unload_game);LOAD(retro_deinit);LOAD(retro_get_system_info);
 retro_set_environment_fn(env);retro_set_video_refresh_fn(video);retro_set_audio_sample_batch_fn(audio);retro_set_input_poll_fn(poll);retro_set_input_state_fn(input);retro_init_fn();
 // No game ("Start Core"): refused, the companion needs one.
 assert(!retro_load_game_fn(NULL));
 // The GBA core over armGBA: only GBA files, and none of armGBA's options the core can't follow (pd_libretro.c's HELD).
 if(armgba_options){
  struct retro_system_info si={0};retro_get_system_info_fn(&si);
  if(strcmp(si.valid_extensions,"gba|bin")){printf("FAIL %s: the GBA core offers %s\n",argv[1],si.valid_extensions);return 1;}
  if(hidden_options){printf("FAIL %s: %d of armGBA's options shown that the core holds\n",argv[1],hidden_options);return 1;}
 }
 // mGBA requests in-memory content, DSperate requests a full path. Supplying both satisfies either contract.
 FILE* f=fopen(argv[2],"rb");assert(f);fseek(f,0,SEEK_END);size_t size=ftell(f);rewind(f);void* rom=malloc(size);assert(fread(rom,1,size,f)==size);fclose(f);
 struct retro_game_info game={.path=argv[2],.data=rom,.size=size};assert(retro_load_game_fn(&game));for(int n=0;n<120;n++)retro_run_fn();assert(w&&h);
 if(armgba_options){
  assert(w==480 && h==320);
  const unsigned before=geometry_updates;
  unsigned char original[480*320*4];
  const size_t pixel_size=fmt==RETRO_PIXEL_FORMAT_RGB565?2:4;
  const size_t row_size=w*pixel_size;
  memcpy(original,frame,h*row_size);
  assert(memcmp(original,original+2*pixel_size,pixel_size));
  for(unsigned y=0;y<h;y+=2){
   const unsigned char* row=original+y*row_size;
   assert(!memcmp(row,row+row_size,row_size));
   for(unsigned x=0;x<w;x+=2)
    assert(!memcmp(row+x*pixel_size,row+(x+1)*pixel_size,pixel_size));
  }
  quick_pressed=true;
  retro_run_fn();
  assert(w==480 && h==320 && geometry_updates==before);
  assert(memcmp(frame,original,w*h*(fmt==RETRO_PIXEL_FORMAT_RGB565?2:4)));
  quick_pressed=false;
  retro_run_fn();
  quick_pressed=true;
  retro_run_fn();
  quick_pressed=false;
  assert(w==480 && h==320 && geometry_updates==before);
 }
 size_t n=retro_serialize_size_fn();assert(n);void* state=malloc(n);assert(retro_serialize_fn(state,n));
 size_t ram_size=retro_get_memory_size_fn(RETRO_MEMORY_SYSTEM_RAM);void* ram=retro_get_memory_data_fn(RETRO_MEMORY_SYSTEM_RAM);void* copy=malloc(ram_size?ram_size:1);if(ram_size){assert(ram);memcpy(copy,ram,ram_size);}
 void* save=retro_get_memory_data_fn(RETRO_MEMORY_SAVE_RAM);size_t save_size=retro_get_memory_size_fn(RETRO_MEMORY_SAVE_RAM);size_t mark=save_size<64?save_size:64;
 // The game saves after the state was taken: loading the state keeps that save (a state never carries the battery save).
 if(save)memset(save,0xA5,mark);
 for(int n=0;n<7;n++)retro_run_fn();assert(retro_unserialize_fn(state,n));
 if(save){const unsigned char* s=retro_get_memory_data_fn(RETRO_MEMORY_SAVE_RAM);for(size_t i=0;i<mark;i++)if(s[i]!=0xA5){printf("FAIL %s: loading a state put the battery save back (byte %zu)\n",argv[2],i);return 1;}}if(ram_size)assert(!memcmp(copy,retro_get_memory_data_fn(RETRO_MEMORY_SYSTEM_RAM),ram_size));assert(save==retro_get_memory_data_fn(RETRO_MEMORY_SAVE_RAM));
 if(getenv("PD_MODERN_THROTTLE"))assert(legacy_queries==0);
 else if(getenv("PD_LEGACY_FF"))assert(legacy_queries>0);
 printf("PASS %s: %ux%u RAM=%zu state=%zu SRAM=%zu\n",argv[2],w,h,ram_size,n,retro_get_memory_size_fn(RETRO_MEMORY_SAVE_RAM));
 printf("Legacy FF callback queries: %u\n",legacy_queries);
 free(copy);free(state);retro_unload_game_fn();retro_deinit_fn();free(rom);dlclose(so);return 0;}
