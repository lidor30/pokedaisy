// GPL-3.0. Pure scene/geometry tests plus actual pixel composition, no fake game state.
#include "layout.h"
#include <cassert>
#include <vector>
#include <cstdio>
int main(){
  const int field=PK_NDS_FIELD_KNOWN|PK_NDS_FIELD_SCREEN;
  DsScenePolicy s;
  for(int i=0;i<19;i++){s.tick(field,false,false,true);assert(!s.hud);}
  s.tick(field,false,false,true);assert(s.hud);
  s.tick(field|PK_NDS_FIELD_LOCKED,false,false,true);assert(!s.hud);
  s.tick(PK_NDS_FIELD_TOUCH,false,false,true);assert(!s.touch);
  s.tick(PK_NDS_FIELD_TOUCH,false,false,true);assert(s.touch);
  auto floating=ds_arrange(DS_ONE,s,true);assert(floating.floating && floating.touch.w==352);
  for(int i=0;i<5;i++){s.tick(field,false,false,true);assert(s.touch);}
  s.tick(field,false,false,true);assert(!s.touch);
  s.tick(PK_NDS_FIELD_TOUCH,true,true,true);assert(s.dark && !s.touch);
  s.tick(PK_NDS_FIELD_TOUCH,false,false,true);assert(s.touch); // immediate after both-black fade
  s.reset();
  int fading=PK_NDS_FIELD_TOUCH|PK_NDS_FIELD_TOUCH_FADES;
  s.tick(fading,false,true,true);assert(s.fadeSeen&&!s.touch);
  for(int i=0;i<12;i++){s.tick(fading,false,false,true);assert(!s.touch);}
  s.tick(fading,false,false,true);assert(s.touch);
  s.reset();for(int i=0;i<90;i++)s.tick(fading,false,false,true);
  assert(!s.touch);s.tick(fading,false,false,true);assert(s.touch); // bounded no-black fallback
  s.reset();s.tick(PK_NDS_FIELD_TOUCH_BATTLE_MENU,false,false,false);s.tick(PK_NDS_FIELD_TOUCH_BATTLE_MENU,false,false,false);assert(!s.touch);
  s.tick(PK_NDS_FIELD_TOUCH_BATTLE_MENU,false,false,true);s.tick(PK_NDS_FIELD_TOUCH_BATTLE_MENU,false,false,true);assert(s.touch);
  s.tick(PK_NDS_FIELD_MENU|PK_NDS_FIELD_TOUCH,false,false,true);s.tick(PK_NDS_FIELD_MENU|PK_NDS_FIELD_TOUCH,false,false,true);
  auto modal=ds_arrange(DS_ONE,s,true);assert(modal.modal && modal.useField && !modal.floating);
  s.reset();auto fallback=ds_arrange(DS_ONE,s,false);assert(fallback.touch.w>0&&!fallback.floating);
  auto manual=ds_arrange(DS_ONE,s,true,true);assert(manual.floating);
  auto side=ds_arrange(DS_SIDE,s,true);auto stacked=ds_arrange(DS_STACKED,s,true);
  auto large=ds_arrange(DS_LARGE_SMALL,s,true);auto pip=ds_arrange(DS_PIP,s,true);
  assert(side.top.w==256&&side.touch.x==256&&side.touch.y==96);
  assert(stacked.top.x==128&&stacked.touch.y==192);
  assert(large.top.w>large.touch.w&&large.top.x+large.top.w==large.touch.x);
  assert(pip.touch.x>256&&pip.touch.y>192);
  assert(!ds_pen(side,10,100).valid && !ds_pen(side,300,40).valid);
  auto p=ds_pen(side,256,96);assert(p.valid&&p.x==0&&p.y==0);
  p=ds_pen(side,511,287);assert(p.x==255&&p.y==191);
  assert(!ds_pen(side,512,287).valid);
  for(auto a:{side,stacked,large,pip,manual}){
    p=ds_pen(a,a.touch.x+a.touch.w-1,a.touch.y+a.touch.h-1);assert(p.valid&&p.x>=253&&p.y>=189);
    assert(!ds_pen(a,a.touch.x-1,a.touch.y).valid);
  }
  std::vector<uint32_t> top(256*192,0xff00ff00),bottom(256*192,0xff0000ff),out(512*384);
  top[0]=0xffff0000;top[255]=0xffffffff;top[191*256]=0xff00ffff;top.back()=0xffffff00;
  ds_compose(out.data(),ds_arrange(DS_ONE,s,true),top.data(),bottom.data());
  assert(out[0]==0xff0000&&out[511]==0xffffff&&out[383*512]==0x00ffff&&out.back()==0xffff00);
  ds_compose(out.data(),manual,top.data(),bottom.data(),top.data());
  assert(out[manual.touch.y*512+manual.touch.x]==0x0000ff);
  ds_compose(out.data(),side,top.data(),bottom.data());assert(out[96*512]==0xff0000&&out[96*512+256]==0x0000ff);
  std::puts("PASS upstream scene timing, fade timeout, HUD gates, layouts, pointer bounds and unclipped source corners");
}
