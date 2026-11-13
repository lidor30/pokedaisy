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
    a.floating=true;a.touch={80,60,352,264};a.useField=s.app;
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
  const DsArrangement floatingTouch{{0,0,512,384},{80,60,352,264},true,false,fieldCached};
  if(app){
    if(bits&PK_NDS_FIELD_TOUCH_ONLY){
      if(scene.touch&&!scene.bottomBlank)return floatingTouch;
    }else if(bits&PK_NDS_FIELD_TOP_ONLY){
      if(!topBlank)return {{80,60,352,264},{},false,true,fieldCached};
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
    // Whole scales and shrunk windows copy pixels. A popup between whole scales (352x264 is 1.375x) is area-weighted:
    // each output pixel takes the DS pixels it covers by how much, so a DS pixel is a solid block with at most a
    // one-pixel blend at its edge. Nearest-neighbour there doubled some rows and columns and not others: uneven strokes.
    const bool between=(r.w>256 && r.w%256) || (r.h>192 && r.h%192);
    if(!between){
      for(int y=0;y<r.h;++y)for(int x=0;x<r.w;++x)
        if(r.x+x>=0&&r.x+x<512&&r.y+y>=0&&r.y+y<384)
          out[(r.y+y)*512+r.x+x]=src[(y*192/r.h)*256+x*256/r.w]&0xffffff;
      return;
    }
    // Per output column / row: the first DS pixel it covers and that pixel's share in 256ths (the rest is the next one's).
    auto spans=[](int n,int source,int* first,int* share){
      for(int i=0;i<n;++i){
        const int a=i*source*256/n, b=(i+1)*source*256/n; // in 256ths of a DS pixel
        const int edge=(a/256+1)*256;
        first[i]=std::min(a/256,source-1);
        share[i]=b<=edge?256:(edge-a)*256/(b-a);
      }
    };
    int cx[512],wx[512],cy[384],wy[384];
    spans(r.w,256,cx,wx);spans(r.h,192,cy,wy);
    // p's share w (of 256) and q's the rest, per channel: red and blue in one multiply (each lane stays under 16 bits).
    auto mix=[](uint32_t p,uint32_t q,int w){
      if(w>=256)return p&0xffffff;
      const uint32_t v=uint32_t(256-w);
      const uint32_t rb=(((p&0xff00ff)*uint32_t(w)+(q&0xff00ff)*v)>>8)&0xff00ff;
      const uint32_t g=(((p&0xff00)>>8)*uint32_t(w)+((q&0xff00)>>8)*v)>>8;
      return rb|(g<<8);
    };
    for(int y=0;y<r.h;++y){
      const int oy=r.y+y;if(oy<0||oy>=384)continue;
      const uint32_t* r0=src+cy[y]*256;const uint32_t* r1=src+std::min(cy[y]+1,191)*256;
      for(int x=0;x<r.w;++x){
        const int ox=r.x+x;if(ox<0||ox>=512)continue;
        // Most output pixels lie inside one DS pixel: copied, with no blend.
        if(wx[x]>=256 && wy[y]>=256){out[oy*512+ox]=r0[cx[x]]&0xffffff;continue;}
        const int x1=std::min(cx[x]+1,255);
        const uint32_t top=mix(r0[cx[x]],r0[x1],wx[x]);
        out[oy*512+ox]=wy[y]>=256?top:mix(top,mix(r1[cx[x]],r1[x1],wx[x]),wy[y]);
      }
    }
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
  }else{
    // A 3x3 box two DS pixels apart, darkened to two thirds. Separable: sums across, then down (the same nine pixels'
    // sum), at DS resolution, each value doubled into a 2x2 block of the frame.
    static uint32_t across[192][256][3];
    for(int sy=0;sy<192;++sy)for(int sx=0;sx<256;++sx){
      unsigned r=0,g=0,b=0;
      for(int dx=-2;dx<=2;dx+=2){
        const uint32_t p=background[sy*256+std::clamp(sx+dx,0,255)];
        r+=(p>>16)&255;g+=(p>>8)&255;b+=p&255;
      }
      across[sy][sx][0]=r;across[sy][sx][1]=g;across[sy][sx][2]=b;
    }
    for(int sy=0;sy<192;++sy){
      auto* row=out+sy*1024;
      const auto* up=across[std::max(sy-2,0)];const auto* mid=across[sy];const auto* down=across[std::min(sy+2,191)];
      for(int sx=0;sx<256;++sx){
        const unsigned r=up[sx][0]+mid[sx][0]+down[sx][0],g=up[sx][1]+mid[sx][1]+down[sx][1],b=up[sx][2]+mid[sx][2]+down[sx][2];
        row[2*sx]=row[2*sx+1]=((r/9*2/3)<<16)|((g/9*2/3)<<8)|(b/9*2/3);
      }
      std::copy_n(row,512,row+512);
    }
  }
  pd_canvas c;pd_canvas_init(&c,512,384,out);
  auto frame=[&](DsRect r){
    pd_round_rect(&c,r.x-5,r.y-5,r.w+10,r.h+10,3,0x303840);
    pd_round_rect(&c,r.x-3,r.y-3,r.w+6,r.h+6,2,0xf8f8f8);
  };
  // The cached field held while a native app loads (both screens black between its fade out and in): dimmed as the
  // popup that follows dims it, so the game's fade to black isn't undone by a bright frozen field, and the popup
  // arrives over the backdrop it keeps.
  const bool waiting=a.useField&&field&&a.top.w<=0&&a.touch.w<=0&&!a.modal&&!a.floating;
  if(waiting)pd_dim(&c,110);
  if(a.modal){pd_dim(&c,110);frame({a.top.x,a.top.y,a.top.w+a.touch.w,a.top.h});}
  if(!a.floating&&!fullTop)blit(top,a.top);
  if(a.floating){
    if(overlay)overlay(out);
    // Dim what stays in view around the popup (it covers its own rect whole): pd_dim's sums, by region.
    auto dim=[&](int x,int y,int w,int h){
      if(w<=0||h<=0)return;
      pd_canvas part;pd_canvas_init(&part,w,1,out);
      for(int row=y;row<y+h;++row){part.px=out+row*512+x;pd_dim(&part,130);}
    };
    const DsRect t=a.touch;
    const int ty=std::clamp(t.y,0,384),tb=std::clamp(t.y+t.h,0,384),tx=std::clamp(t.x,0,512),tr=std::clamp(t.x+t.w,0,512);
    dim(0,0,512,ty);dim(0,tb,512,384-tb);dim(0,ty,tx,tb-ty);dim(tr,ty,512-tr,tb-ty);
    frame(a.touch);
  }else if(a.touch.w>0&&a.top.w==512)frame(a.touch);
  if(!a.floating && overlay)overlay(out);
  blit(bottom,a.touch);
}
