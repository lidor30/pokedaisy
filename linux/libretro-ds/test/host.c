/* Minimal frontend contract test; no firmware, BIOS or commercial ROM bundled. */
#include "libretro.h"
#include <assert.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static unsigned fmt, w,h, frame,mask;
static unsigned char image[512*384*4];
static const char* directory;
static bool env(unsigned cmd,void* p){
 switch(cmd){
 case RETRO_ENVIRONMENT_SET_VARIABLES:return true;
 case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:fmt=*(unsigned*)p;return fmt==1||fmt==2;
 case RETRO_ENVIRONMENT_SET_GEOMETRY:return true;
 case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
 case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:*(const char**)p=directory;return true;
 case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:*(bool*)p=false;return true;
 default:return false;
 }
}
static void video(const void* p,unsigned x,unsigned y,size_t pitch){
 if(!p)return;
 assert(x<=512&&y<=384);w=x;h=y;unsigned b=fmt==1?4:2;
 for(unsigned j=0;j<y;++j)memcpy(image+j*x*b,(const char*)p+j*pitch,x*b);
}
static size_t audio(const int16_t* p,size_t n){(void)p;return n;}
static void poll(void){}
static int16_t input(unsigned p,unsigned d,unsigned i,unsigned id){(void)i;return !p&&d==RETRO_DEVICE_JOYPAD&&id<16?!!(mask&(1u<<id)):0;}
static void ppm(const char* root,const char* name){
 char path[1024];snprintf(path,sizeof path,"%s/%s.ppm",root,name);FILE* f=fopen(path,"wb");assert(f);
 fprintf(f,"P6\n%u %u\n255\n",w,h);
 for(unsigned j=0;j<w*h;++j){
  uint32_t p=fmt==1?((uint32_t*)image)[j]:((uint16_t*)image)[j];
  unsigned r,g,b;
  if(fmt==1){r=p>>16&255;g=p>>8&255;b=p&255;}
  else{r=(p>>11&31)*255/31;g=(p>>5&63)*255/63;b=(p&31)*255/31;}
  fputc(r,f);fputc(g,f);fputc(b,f);
 }fclose(f);
}
#define LOAD(n) __typeof__(&n) n##_fn=dlsym(so,#n);assert(n##_fn)
int main(int argc,char** argv){
 assert(argc>=4);directory=argv[3];
 void* so=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);if(!so){puts(dlerror());return 1;}
 LOAD(retro_set_environment);LOAD(retro_set_video_refresh);LOAD(retro_set_audio_sample_batch);
 LOAD(retro_set_input_poll);LOAD(retro_set_input_state);LOAD(retro_init);LOAD(retro_load_game);
 LOAD(retro_run);LOAD(retro_get_memory_data);LOAD(retro_get_memory_size);
 LOAD(retro_serialize_size);LOAD(retro_serialize);LOAD(retro_unserialize);
 LOAD(retro_unload_game);LOAD(retro_deinit);
 retro_set_environment_fn(env);retro_set_video_refresh_fn(video);retro_set_audio_sample_batch_fn(audio);
 retro_set_input_poll_fn(poll);retro_set_input_state_fn(input);retro_init_fn();
 struct retro_game_info game={.path=argv[2]};assert(retro_load_game_fn(&game));
 size_t saves=retro_get_memory_size_fn(RETRO_MEMORY_SAVE_RAM);
 void* save=retro_get_memory_data_fn(RETRO_MEMORY_SAVE_RAM);
 if(argc>4&&strcmp(argv[4],"-")){FILE* f=fopen(argv[4],"rb");assert(f);assert(saves&&fread(save,1,saves,f)==saves);fclose(f);}
 // Unmodified wrapper and enhanced core must accept each other's snapshots.
 if(argc>5&&strcmp(argv[5],"-")){FILE* f=fopen(argv[5],"rb");assert(f);fseek(f,0,SEEK_END);size_t n=ftell(f);rewind(f);void* p=malloc(n);assert(fread(p,1,n,f)==n);fclose(f);assert(retro_unserialize_fn(p,n));free(p);}
 unsigned frames=argc>6?atoi(argv[6]):60;
 for(frame=0;frame<frames;++frame){
  // Platinum: dismiss title, then CONTINUE. Harmless on diagnostic cart.
  mask=(frame%60>=30 && frame%60<34 && frame+240<frames)?1u<<RETRO_DEVICE_ID_JOYPAD_A:0;
  if(frame+200>=frames && frame+196<frames)mask=1u<<RETRO_DEVICE_ID_JOYPAD_START;
  if(getenv("PD_TEST_BUTTON"))mask=(frame%60>=30 && frame%60<34)?1u<<atoi(getenv("PD_TEST_BUTTON")):0;retro_run_fn();
 }
 mask=0;retro_run_fn();ppm(directory,"game");
 size_t n=retro_serialize_size_fn();assert(n>0);void* state=malloc(n);assert(retro_serialize_fn(state,n));
 char path[1024];snprintf(path,sizeof path,"%s/roundtrip.state",directory);FILE* f=fopen(path,"wb");assert(f&&fwrite(state,1,n,f)==n);fclose(f);
 void* ram=retro_get_memory_data_fn(RETRO_MEMORY_SYSTEM_RAM);size_t ramsize=retro_get_memory_size_fn(RETRO_MEMORY_SYSTEM_RAM);assert(ram&&ramsize==4*1024*1024);
 void* before=malloc(ramsize);memcpy(before,ram,ramsize);
 // States restore RAM exactly and retain the frontend's save pointer.
 for(int i=0;i<5;++i)retro_run_fn();assert(retro_unserialize_fn(state,n));assert(!memcmp(before,ram,ramsize));assert(save==retro_get_memory_data_fn(RETRO_MEMORY_SAVE_RAM));
 if(fmt==2){
  mask=(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)|(1u<<RETRO_DEVICE_ID_JOYPAD_L2);retro_run_fn();mask=0;retro_run_fn();assert(w==341&&h==256);assert(!memcmp(before,ram,ramsize));ppm(directory,"quick-ring");
  mask=1u<<RETRO_DEVICE_ID_JOYPAD_A;retro_run_fn();mask=0;retro_run_fn();assert(w==341&&h==256);assert(!memcmp(before,ram,ramsize));ppm(directory,"party");
  for(int i=0;i<3;++i){mask=1u<<RETRO_DEVICE_ID_JOYPAD_R;retro_run_fn();mask=0;retro_run_fn();ppm(directory,i==0?"bag":i==1?"map":"dex");}
  mask=1u<<RETRO_DEVICE_ID_JOYPAD_R;retro_run_fn();mask=0;retro_run_fn();ppm(directory,"settings");
  mask=1u<<RETRO_DEVICE_ID_JOYPAD_A;retro_run_fn();mask=0;retro_run_fn();ppm(directory,"settings-picker");
  if(getenv("PD_TEST_LAYOUT")){
    int mode=atoi(getenv("PD_TEST_LAYOUT"));assert(mode>=0&&mode<7);
    for(int i=0;i<mode;i++){mask=1u<<RETRO_DEVICE_ID_JOYPAD_DOWN;retro_run_fn();mask=0;retro_run_fn();}
    mask=1u<<RETRO_DEVICE_ID_JOYPAD_A;retro_run_fn();mask=0;retro_run_fn();
  }else{mask=1u<<RETRO_DEVICE_ID_JOYPAD_B;retro_run_fn();mask=0;retro_run_fn();}
  if(getenv("PD_TEST_GRID")){
    for(int i=0;i<6;i++){mask=1u<<RETRO_DEVICE_ID_JOYPAD_DOWN;retro_run_fn();mask=0;retro_run_fn();}
    mask=1u<<RETRO_DEVICE_ID_JOYPAD_A;retro_run_fn();mask=0;retro_run_fn();ppm(directory,"quick-settings");
  }
  mask=1u<<RETRO_DEVICE_ID_JOYPAD_L2;retro_run_fn();mask=0;retro_run_fn();assert(w==512&&h==384);
  mask=1u<<RETRO_DEVICE_ID_JOYPAD_L2;for(int i=0;i<24;i++)retro_run_fn();
  mask=0;retro_run_fn();assert(w==341&&h==256);
  mask=1u<<RETRO_DEVICE_ID_JOYPAD_B;retro_run_fn();mask=0;retro_run_fn();assert(w==512&&h==384);
  if(getenv("PD_TEST_GRID")){
    memcpy(before,ram,ramsize);
    mask=(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)|(1u<<RETRO_DEVICE_ID_JOYPAD_L2);retro_run_fn();mask=0;retro_run_fn();assert(w==341&&h==256);assert(!memcmp(before,ram,ramsize));ppm(directory,"quick-grid");
    mask=1u<<RETRO_DEVICE_ID_JOYPAD_B;retro_run_fn();mask=0;retro_run_fn();
  }

  ppm(directory,"chosen-layout");
  if(getenv("PD_TEST_MENU")){
    for(int i=0;i<120;++i)retro_run_fn();
    mask=1u<<RETRO_DEVICE_ID_JOYPAD_X;for(int i=0;i<4;++i)retro_run_fn();mask=0;
    for(int i=0;i<90;++i)retro_run_fn();ppm(directory,"native-menu");
    for(int n=0;n<2;++n){mask=1u<<RETRO_DEVICE_ID_JOYPAD_DOWN;for(int i=0;i<4;++i)retro_run_fn();mask=0;for(int i=0;i<4;++i)retro_run_fn();}
    mask=1u<<RETRO_DEVICE_ID_JOYPAD_A;for(int i=0;i<4;++i)retro_run_fn();mask=0;
    for(int i=0;i<120;++i)retro_run_fn();ppm(directory,"native-bag");
    mask=1u<<RETRO_DEVICE_ID_JOYPAD_B;retro_run_fn();mask=0;
    for(int i=0;i<60;++i)retro_run_fn();
  }
  mask=(1u<<RETRO_DEVICE_ID_JOYPAD_SELECT)|(1u<<RETRO_DEVICE_ID_JOYPAD_R2);retro_run_fn();mask=0;retro_run_fn();assert(w==512&&h==384);ppm(directory,"manual-touch");
  mask=1u<<RETRO_DEVICE_ID_JOYPAD_R2;retro_run_fn();mask=0;retro_run_fn();assert(w==512&&h==384);ppm(directory,"bottom");
 }
 assert(!retro_unserialize_fn(state,3));
 free(before);free(state);retro_unload_game_fn();retro_deinit_fn();dlclose(so);
 printf("PASS frames=%u format=%u save_bytes=%zu state_bytes=%zu\n",frames,fmt,saves,n);
}
