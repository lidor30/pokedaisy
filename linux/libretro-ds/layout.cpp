// GPL-3.0. Layout maths/timing adapted from upstream DsLayoutSettings.kt and
// EmulatorEngine.kt; rendering uses the existing portable pixel canvas.
#include "layout.h"
#include <algorithm>
#include <cmath>
extern "C" {
#include "pd_canvas.h"
#include "pd_style.h"
}
void DsScenePolicy::tick(int bits,bool topBlank,bool bottom,bool floatBattle){
  const bool both=topBlank&&bottom,afterDark=darkBefore&&!both;
  darkBefore=both;bottomBlank=bottom;
  dark=both||(menu&&topBlank);
  const bool fades=bits&PK_NDS_FIELD_TOUCH_FADES;
  const bool dualScreenApp=(bits&PK_NDS_FIELD_MENU)&&
    !(bits&(PK_NDS_FIELD_BATTLE|PK_NDS_FIELD_TOUCH_ONLY|PK_NDS_FIELD_TOP_ONLY));
  dualScreenLitFrames=dualScreenApp&&!both?std::min(12,dualScreenLitFrames+1):0;
  if(!fades){fadeOpened=false;fadeClosed=false;}
  if(fades&&fadeOpened&&bottom){
    touch=false;touchFrames=0;fadeClosed=true;
  }
  if(!both){
    bool want=(bits&PK_NDS_FIELD_TOUCH)||(floatBattle&&(bits&PK_NDS_FIELD_TOUCH_BATTLE_MENU));
    if(fadeClosed)want=false;
    if(want&&!touch&&fades){
      if(bottom)fadeSeen=true;
      ++fadeFrames;
      // The scene debounce below already requires two lit frames. The
      // blank frame distinguishes the new panel from the old fading one.
      if(!(fadeSeen&&!bottom)&&fadeFrames<90)want=false;
    }else if(!want){fadeSeen=false;fadeFrames=0;}
    auto settle=[&](bool& current,bool next,int& frames){
      if(next==current){frames=0;return;}
      if(afterDark||++frames>=(next?2:6)){current=next;frames=0;}
    };
    const bool nextApp=(bits&PK_NDS_FIELD_MENU)&&!(bits&PK_NDS_FIELD_BATTLE);
    settle(touch,want,touchFrames);
    if(touch){fadeSeen=false;fadeFrames=0;if(fades)fadeOpened=true;}
    settle(menu,dualScreenApp,menuFrames);settle(app,nextApp,appFrames);
    settle(wide,bits&(PK_NDS_FIELD_SCREEN|PK_NDS_FIELD_BATTLE),wideFrames);
  }
  // Input locks also happen in a visible overworld, including early scripts.
  // Only a lit field without a native menu or battle should own the HUD.
  constexpr int fieldBits=PK_NDS_FIELD_KNOWN|PK_NDS_FIELD_SCREEN;
  const bool field=!topBlank && (bits&fieldBits)==fieldBits &&
    !(bits&(PK_NDS_FIELD_TOUCH|PK_NDS_FIELD_MENU|PK_NDS_FIELD_BATTLE));
  fieldFrames=field?std::min(20,fieldFrames+1):0;
  hud=fieldFrames>=20;
}
DsArrangement ds_arrange(int mode,const DsScenePolicy& s,bool followed,bool manual){
  DsArrangement a;
  if(mode==DS_ONE&&!followed)mode=DS_PIP;
  if(mode==DS_BOTTOM){a.touch={0,0,512,384};return a;}
  if(mode==DS_TOP){a.top={0,0,512,384};return a;}
  bool floats=mode==DS_ONE||mode==DS_PIP;
  if(floats&&s.menu){mode=DS_SIDE;a.modal=true;a.useField=s.app;}
  a.top={0,0,512,384};
  if(mode==DS_SIDE){a.top={0,96,256,192};a.touch={256,96,256,192};}
  else if(mode==DS_STACKED){a.top={128,0,256,192};a.touch={128,192,256,192};}
  else if(mode==DS_LARGE_SMALL){a.top={0,38,410,308};a.touch={410,269,102,77};}
  else if(mode==DS_PIP){a.touch={378,280,122,92};}
  if(floats&&!a.modal&&(manual||(s.touch&&!s.bottomBlank))){
    a.floating=true;a.touch={0,0,512,384};a.useField=s.app;
  }
  return a;
}

DsArrangement ds_arrange_one_screen(DsArrangement normal,const DsScenePolicy& scene,int bits,
                                    bool topBlank,bool fieldCached,bool fieldMenuFlow){
  const bool field=bits&PK_NDS_FIELD_SCREEN;
  const bool app=bits&PK_NDS_FIELD_MENU;
  const bool battle=bits&PK_NDS_FIELD_BATTLE;
  if(battle)return normal;

  // Before the first field scene, title and setup can use both displays.
  if(!field&&!app&&!fieldCached&&!topBlank&&!scene.bottomBlank)
    return ds_arrange(DS_STACKED,scene,true);

  const DsArrangement cachedField{{},{},false,false,true};
  const DsArrangement liveField{{0,0,512,384},{}};
  const DsArrangement floatingTouch{{0,0,512,384},{0,0,512,384},true,false,fieldCached};
  if(app){
    if(bits&PK_NDS_FIELD_TOUCH_ONLY){
      if(scene.touch&&!scene.bottomBlank)return floatingTouch;
    }else if(bits&PK_NDS_FIELD_TOP_ONLY){
      if(!topBlank)return {{0,0,512,384},{},false,true,fieldCached};
    }else if((!topBlank&&!scene.bottomBlank)||scene.dualScreenLitFrames==12){
      return {{0,96,256,192},{256,96,256,192},false,true,fieldCached};
    }
    return fieldCached?cachedField:normal;
  }

  if(fieldMenuFlow&&fieldCached&&topBlank)return cachedField;
  if(field){
    // A closed app's debounced layout can linger while the overworld comes
    // back. Only the current game's X menu should keep the touch view up.
    if((bits&PK_NDS_FIELD_TOUCH)&&scene.touch&&!scene.bottomBlank)return floatingTouch;
    return liveField;
  }
  return normal;
}
DsPen ds_pen(const DsArrangement& a,int x,int y){
  if(!a.touch.contains(x,y))return {};
  return {true,std::clamp((x-a.touch.x)*256/a.touch.w,0,255),std::clamp((y-a.touch.y)*192/a.touch.h,0,191)};
}
void ds_compose(uint32_t* out,const DsArrangement& a,const uint32_t* top,const uint32_t* bottom,const uint32_t* field,
                void (*overlay)(uint32_t*)){
  auto blit=[&](const uint32_t* src,const DsRect& r){
    if(r.w<=0||r.h<=0)return;
    for(int y=0;y<r.h;++y)for(int x=0;x<r.w;++x)
      if(r.x+x>=0&&r.x+x<512&&r.y+y>=0&&r.y+y<384)
        out[(r.y+y)*512+r.x+x]=src[(y*192/r.h)*256+x*256/r.w]&0xffffff;
  };
  const uint32_t* background=a.useField&&field?field:top;
  // A small box blur for exposed margins; field-backed modal scenes stay sharp.
  const bool fullTop=a.top.x==0&&a.top.y==0&&a.top.w==512&&a.top.h==384&&!a.modal&&!a.useField;
  if(fullTop||(a.useField&&field)){
    // The common One Screen layout is an exact 2× copy. Fill each source
    // pixel once and duplicate the row instead of running two scaled blits.
    for(int y=0;y<192;++y){
      auto* row=out+y*1024;
      for(int x=0;x<256;++x)row[2*x]=row[2*x+1]=background[y*256+x]&0xffffff;
      std::copy_n(row,512,row+512);
    }
  }else for(int y=0;y<384;++y)for(int x=0;x<512;++x){
    int sx=x/2,sy=y/2;unsigned r=0,g=0,b=0,n=0;
    for(int dy=-2;dy<=2;dy+=2)for(int dx=-2;dx<=2;dx+=2){
      uint32_t p=background[std::clamp(sy+dy,0,191)*256+std::clamp(sx+dx,0,255)];
      r+=(p>>16)&255;g+=(p>>8)&255;b+=p&255;++n;
    }
    out[y*512+x]=((r/n*2/3)<<16)|((g/n*2/3)<<8)|(b/n*2/3);
  }
  pd_canvas c;pd_canvas_init(&c,512,384,out);
  auto frame=[&](DsRect r){
    pd_round_rect(&c,r.x-5,r.y-5,r.w+10,r.h+10,3,0x303840);
    pd_round_rect(&c,r.x-3,r.y-3,r.w+6,r.h+6,2,0xf8f8f8);
  };
  if(a.modal){pd_dim(&c,110);frame({a.top.x,a.top.y,a.top.w+a.touch.w,a.top.h});}
  if(!a.floating&&!fullTop)blit(top,a.top);
  if(a.floating){
    if(overlay)overlay(out);
    pd_dim(&c,130);frame(a.touch);
  }else if(a.touch.w>0&&a.top.w==512)frame(a.touch);
  if(!a.floating && overlay)overlay(out);
  blit(bottom,a.touch);
}
