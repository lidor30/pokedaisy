// GPL-3.0. Portable port of upstream DS layout/scene policy (54f4c66).
#pragma once
#include <cstdint>
#include "pk_nds_field.h"
struct DsScenePolicy {
  bool touch=false,menu=false,app=false,wide=false,dark=false,bottomBlank=false,hud=false;
  bool darkBefore=false,fadeSeen=false,fadeOpened=false,fadeClosed=false;
  int touchFrames=0,menuFrames=0,appFrames=0,wideFrames=0,fadeFrames=0,fieldFrames=0;
  int dualScreenLitFrames=0;
  void reset(){*this=DsScenePolicy{};}
  void tick(int bits,bool topBlank,bool bottomBlank,bool floatBattle);
};
struct DsRect {
  int x=0,y=0,w=0,h=0;
  bool contains(int px,int py)const{return w>0&&h>0&&px>=x&&py>=y&&px<x+w&&py<y+h;}
};
// Saved settings keep their old IDs: AUTO becomes ONE SCREEN; append new modes.
enum DsLayout { DS_ONE=0,DS_TOP=1,DS_BOTTOM=2,DS_SIDE=3,DS_STACKED=4,DS_LARGE_SMALL=5,DS_PIP=6,DS_LAYOUTS=7 };
inline bool ds_auto_run(int field,bool moving,bool aHeld){
  constexpr int ready=PK_NDS_FIELD_KNOWN|PK_NDS_FIELD_SCREEN;
  constexpr int blocked=PK_NDS_FIELD_LOCKED|PK_NDS_FIELD_BATTLE|PK_NDS_FIELD_BIKE|
    PK_NDS_FIELD_SURF|PK_NDS_FIELD_MENU|PK_NDS_FIELD_TOUCH;
  return moving && !aHeld && (field&(ready|blocked))==ready;
}
struct DsArrangement {
  DsRect top,touch;
  bool floating=false,modal=false,useField=false;
};
struct DsPen {bool valid=false;int x=0,y=0;};
DsArrangement ds_arrange(int mode,const DsScenePolicy&,bool followed,bool manual=false);
DsArrangement ds_arrange_one_screen(DsArrangement normal,const DsScenePolicy&,int bits,
                                    bool topBlank,bool fieldCached,bool fieldMenuFlow);
DsPen ds_pen(const DsArrangement&,int x,int y);
// What [out] holds from the last ds_compose, for a popup over the held field: its backdrop (the field, dimmed, and the
// popup's frame) is the same every frame, so only the live screens are drawn while the arrangement and the field
// picture (fieldRevision) stay. The caller keeps [out] between frames and writes outside the screens' rectangles only
// through ds_compose; a compose without [kept] into the same buffer must clear held.
struct DsBackdrop { bool held=false; DsArrangement a; unsigned field=0; };
// 512x384 stage; sources are unmodified native 256x192 framebuffers.
void ds_compose(uint32_t* out,const DsArrangement&,const uint32_t* top,const uint32_t* bottom,const uint32_t* field=nullptr,
                void (*overlay)(uint32_t*)=nullptr,DsBackdrop* kept=nullptr,unsigned fieldRevision=0);
