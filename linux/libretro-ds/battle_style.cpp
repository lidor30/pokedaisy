// SPDX-License-Identifier: GPL-3.0-or-later
// Platinum palette and pixel geometry adapted from upstream DsBattleMenu.kt.
#include "battle_strip.h"
#include "companion.h"
#include <algorithm>
#include <array>
#include <cstdio>
extern "C" {
#include "pd_canvas.h"
#include "pd_tables.h"
}

const DsBattleStyle ds_platinum_battle_style={144,146,{{
  {0xeb3838,0x8a4141,0xaa4141,0xc34141,0xfb9aaa,0x592020},
  {0xe39a28,0x8a6938,0xaa7930,0xcb8a28,0xfbeb8a,0x614100},
  {0x59b228,0x517141,0x498230,0x499228,0xb2eb8a,0x204918},
  {0x2892cb,0x415971,0x38698a,0x3079aa,0xb2e3fb,0x102059},
}}};

namespace {
struct Rect {int x,y,w,h;};
constexpr uint32_t white=0xfbfbfb,outline=0x303030;
uint32_t shade(uint32_t color,unsigned percent){
  return (((color>>16)*percent/100)<<16)|((((color>>8)&255)*percent/100)<<8)|
    ((color&255)*percent/100);
}

void fill(pd_canvas& canvas,Rect r,uint32_t color){
  pd_fill(&canvas,r.x,r.y,r.w,r.h,color);
}

void bevel(pd_canvas& canvas,Rect r,const DsBattleColors& color,bool enabled){
  const unsigned tone=enabled?100:55;
  pd_round_rect(&canvas,r.x,r.y,r.w,r.h,3,outline);
  pd_round_rect(&canvas,r.x+1,r.y+1,r.w-2,r.h-2,2,shade(0xdad3d2,tone));
  const Rect face{r.x+3,r.y+3,r.w-6,r.h-6};
  fill(canvas,face,shade(color.face,tone));
  pd_fill(&canvas,face.x,face.y,face.w,2,shade(color.lit,tone));
  pd_fill(&canvas,face.x,face.y+2,2,face.h-4,shade(color.light,tone));
  pd_fill(&canvas,face.x+face.w-2,face.y+2,2,face.h-4,shade(color.mid,tone));
  pd_fill(&canvas,face.x,face.y+face.h-3,face.w,3,shade(color.dark,tone));
}

// Center the ink bounds rather than the font's line box. The shared glyphs
// have blank rows above the letters, which put labels onto the lower bevel.
void text2(pd_canvas& canvas,Rect area,const char* text,uint32_t color,uint32_t shadow,bool center){
  constexpr uint32_t clear=0xffffffff;
  const int w=std::clamp(area.w/2,1,256);
  std::array<uint32_t,256*16> buffer;
  std::fill_n(buffer.begin(),w*16,clear);
  pd_canvas glyphs;
  pd_canvas_init(&glyphs,w,16,buffer.data());
  pd_text_fit(&glyphs,0,0,w-1,text,color,shadow);
  int minX=w,maxX=-1,minY=16,maxY=-1;
  for(int y=0;y<16;++y)for(int x=0;x<w;++x){
    if(buffer[y*w+x]==clear)continue;
    minX=std::min(minX,x);maxX=std::max(maxX,x);
    minY=std::min(minY,y);maxY=std::max(maxY,y);
  }
  if(maxX<0)return;
  const int left=area.x+(center?std::max(0,area.w-2*(maxX-minX+1))/2:0);
  const int top=area.y+std::max(0,area.h-2*(maxY-minY+1))/2;
  pd_clip(&canvas,area.x,area.y,area.w,area.h);
  for(int y=minY;y<=maxY;++y)for(int x=minX;x<=maxX;++x){
    if(buffer[y*w+x]!=clear)
      pd_fill(&canvas,left+2*(x-minX),top+2*(y-minY),2,2,buffer[y*w+x]);
  }
  pd_unclip(&canvas);
}

void corners(pd_canvas& canvas,Rect r,unsigned frame){
  // The highlight hugs the edge so it cannot enter the text or next card.
  const uint32_t accent=(frame/12)%2?0xff6a80:0xffa2ae;
  for(int sx:{-1,1})for(int sy:{-1,1}){
    const int x=sx<0?r.x:r.x+r.w;
    const int y=sy<0?r.y:r.y+r.h;
    pd_fill(&canvas,sx<0?x:x-11,sy<0?y:y-2,11,2,accent);
    pd_fill(&canvas,sx<0?x:x-2,sy<0?y:y-9,2,9,accent);
  }
}

void message(pd_canvas& canvas,Rect r,const std::string& nickname){
  // Checkerwork and cool white center from Platinum's native battle dialogue.
  fill(canvas,r,0x414041);
  for(int y=4;y<r.h-4;y+=6)for(int x=2;x<r.w-2;x+=6){
    if(x>=14 && x+6<=r.w-14)continue;
    const auto color=((x/6+y/6)&1)?0x73717b:0x525052;
    pd_fill(&canvas,r.x+x,r.y+y,std::min(6,r.w-x-2),std::min(6,r.h-y-4),color);
  }
  pd_fill(&canvas,r.x+12,r.y+3,r.w-24,r.h-6,0x9b96a1);
  pd_fill(&canvas,r.x+14,r.y+5,r.w-28,r.h-10,0xfffaff);
  const std::string prompt="What will "+nickname+" do?";
  const int width=r.w-48;
  if(pd_text_width(prompt.c_str())*2+2<=width)
    text2(canvas,{r.x+24,r.y+8,width,32},prompt.c_str(),0x38343c,0xb2aab5,false);
  else {
    const std::string subject=nickname+" do?";
    text2(canvas,{r.x+24,r.y+8,width,30},"What will",0x38343c,0xb2aab5,false);
    text2(canvas,{r.x+24,r.y+36,width,30},subject.c_str(),0x38343c,0xb2aab5,false);
  }
}

// Platinum USA rev 1's native move panel is two 124x55 cards per row.
Rect move_tile(int slot){return {2+128*(slot%2),24+64*(slot/2),124,55};}

uint32_t lower_pixel(const uint32_t* pixels,int x,int y){
  return pixels[y*256+x]&0xffffff;
}

// Platinum USA rev 1's native command panel, in 256x192 touch-screen pixels.
// RUN reaches the bottom of the DS framebuffer, so its lower edge is closed
// with the same dark lip as the other tiles.
struct NativeCommand { Rect tile,label; int faceX,blankY; uint32_t color; };
constexpr NativeCommand nativeCommands[]={
  {{20,34,216,93},{111,79,35,10},65,54,0xee3839},
  {{1,144,78,47},{30,165,21,10},21,160,0xe69929},
  {{177,144,78,47},{192,165,49,10},209,160,0x5ab229},
  {{89,152,78,40},{118,173,21,10},109,169,0x2991cd},
};

bool close_color(uint32_t a,uint32_t b){
  for(int shift:{0,8,16})
    if(std::abs(int((a>>shift)&255)-int((b>>shift)&255))>10)return false;
  return true;
}

bool native_face(const uint32_t* pixels,const NativeCommand& src){
  return close_color(lower_pixel(pixels,src.faceX,src.blankY),src.color);
}

bool native_label(const uint32_t* pixels,const NativeCommand& src){
  // The coloured tile is rendered before the lettering during its entrance.
  // Wait for the native text layer before retaining the frame.
  int lightPixels=0;
  for(int y=src.label.y;y<src.label.y+src.label.h;++y)
    for(int x=src.label.x;x<src.label.x+src.label.w;++x){
      const uint32_t p=lower_pixel(pixels,x,y);
      if(((p>>16)&255)>215 && ((p>>8)&255)>215 && (p&255)>215 && ++lightPixels>=8)
        return true;
    }
  return false;
}

void copy_mark(pd_canvas& canvas,const uint32_t* pixels,Rect src,int x,int y,int scale,
               uint32_t face,unsigned tone){
  for(int j=0;j<src.h;++j)for(int i=0;i<src.w;++i){
    const uint32_t ink=lower_pixel(pixels,src.x+i,src.y+j);
    if(ink!=face)pd_fill(&canvas,x+i*scale,y+j*scale,scale,scale,shade(ink,tone));
  }
}

bool native_command(pd_canvas& canvas,Rect dst,const uint32_t* pixels,int id,bool enabled){
  if(!pixels)return false;
  const auto& src=nativeCommands[id];
  if(!native_face(pixels,src) || !native_label(pixels,src))return false;
  const unsigned tone=enabled?100:55;
  const uint32_t face=lower_pixel(pixels,src.faceX,src.blankY);
  // Keep the lit lip and side texture at native pixel density. Repeat a
  // clean center sample instead of stretching the original button lettering.
  constexpr int sides=13,top=11,foot=4;
  for(int y=0;y<dst.h;++y){
    const bool bottom=y>=dst.h-foot;
    const int sy=y<top?src.tile.y+y:
      bottom&&id!=3?src.tile.y+src.tile.h-(dst.h-y):src.blankY;
    // The source corners sit against the green bottom-screen background.
    // Find the original dark outline on the row before copying the corners.
    int left=0,right=src.tile.w-1;
    auto edge=[&](int x){
      const auto p=lower_pixel(pixels,src.tile.x+x,sy);
      return ((p>>16)&255)<95 && ((p>>8)&255)<95 && (p&255)<95;
    };
    if(y<top || bottom){
      while(left<sides && !edge(left))++left;
      while(right>=src.tile.w-sides && !edge(right))--right;
    }
    for(int x=0;x<dst.w;++x){
      const int sx=x<sides?x:x>=dst.w-sides?src.tile.w-(dst.w-x):src.tile.w/2;
      if(sx<left || sx>right)continue;
      const uint32_t color=bottom&&id==3&&y>=dst.h-2?outline:
        lower_pixel(pixels,src.tile.x+sx,sy);
      canvas.px[(dst.y+y)*canvas.w+dst.x+x]=shade(color,tone);
    }
  }
  constexpr int scale=2;
  const int x=dst.x+(dst.w-scale*src.label.w)/2;
  copy_mark(canvas,pixels,src.label,x,dst.y+(dst.h-scale*src.label.h)/2+2,scale,face,tone);
  return true;
}

bool move_tile_ready(const uint32_t* lower,int slot){
  const auto tile=move_tile(slot);
  // The panel fades in before its labels. Require the cream face and some
  // dark ink in the name row, away from the sides and the PP counter.
  const uint32_t cream=lower_pixel(lower,tile.x+20,tile.y+14);
  if((cream>>16)<228 || ((cream>>8)&255)<215 || (cream&255)<145 ||
     (cream&255)>200)return false;
  int ink=0;
  for(int y=tile.y+15;y<tile.y+28;++y)
    for(int x=tile.x+22;x<tile.x+103;++x){
      const uint32_t p=lower_pixel(lower,x,y);
      if((p>>16)<100 && ((p>>8)&255)<100 && (p&255)<100 && ++ink>=8)return true;
    }
  return false;
}

// Copy the lettering and badges separately from the frame so the compact
// layout can keep their proportions. The sample column is blank in this row.
void move_ink(pd_canvas& canvas,Rect dst,const uint32_t* lower,Rect src,int blankX){
  for(int y=0;y<dst.h;++y){
    const int sy=src.y+y*src.h/dst.h;
    const auto background=lower_pixel(lower,blankX,sy);
    auto* row=canvas.px+(dst.y+y)*canvas.w+dst.x;
    for(int x=0;x<dst.w;++x){
      const auto p=lower_pixel(lower,src.x+x*src.w/dst.w,sy);
      if(p!=background)row[x]=p;
    }
  }
}

void native_move_card(pd_canvas& canvas,Rect dst,const uint32_t* lower,int slot){
  const auto tile=move_tile(slot);
  constexpr int lip=5,foot=4,side=9;
  constexpr int lipRows[]={0,1,4,6,12};
  for(int y=0;y<dst.h;++y){
    const int sy=y<lip?lipRows[y]:y>=dst.h-foot?tile.h-(dst.h-y):y<dst.h/2?14:46;
    int left=0,right=tile.w-1;
    if(y<lip || y>=dst.h-foot){
      auto outline_at=[&](int x){
        const uint32_t p=lower_pixel(lower,tile.x+x,tile.y+sy);
        return (p>>16)<95 && ((p>>8)&255)<95 && (p&255)<95;
      };
      while(left<side && !outline_at(left))++left;
      while(right>=tile.w-side && !outline_at(right))--right;
    }
    auto* row=canvas.px+(dst.y+y)*canvas.w+dst.x;
    for(int x=0;x<dst.w;++x){
      const int sx=x<side?x:x>=dst.w-side?tile.w-(dst.w-x):
        side+(x-side)*(tile.w-2*side)/(dst.w-2*side);
      if(sx>=left && sx<=right)row[x]=lower_pixel(lower,tile.x+sx,tile.y+sy);
    }
  }
  move_ink(canvas,{dst.x+(dst.w-138)/2,dst.y+3,138,18},lower,
           {tile.x+18,tile.y+15,92,12},tile.x+16);
  move_ink(canvas,{dst.x+(dst.w-128)/2,dst.y+22,128,18},lower,
           {tile.x+14,tile.y+31,96,14},tile.x+53);
}

void move_card(pd_canvas& canvas,Rect r,int move,int pp,bool enabled){
  // Same Gen 4 order as the app's move type IDs.
  static constexpr uint32_t types[]={0xa8a878,0xc03028,0xa890f0,0xa040a0,0xe0c068,0xb8a038,
    0xa8b820,0x705898,0xb8b8d0,0x68a090,0xf08030,0x6890f0,0x78c850,0xf8d030,
    0xf85888,0x98d8d8,0x7038f8,0x705848};
  const int type=ds_move_type(move);
  const uint32_t color=type>=0 && type<int(std::size(types))?types[type]:0x8a8a8a;
  const unsigned tone=enabled?100:55;
  pd_round_rect(&canvas,r.x,r.y,r.w,r.h,3,outline);
  pd_round_rect(&canvas,r.x+1,r.y+1,r.w-2,r.h-2,2,shade(color,tone));
  pd_fill(&canvas,r.x+4,r.y+3,r.w-8,r.h-6,shade(0xe3d369,tone));
  pd_fill(&canvas,r.x+6,r.y+4,r.w-12,r.h-8,shade(0xf3ebaa,tone));
  if(!move)return;
  const auto* name=ds_move(move);
  const int x=r.x+18+std::max(0,(r.w-36-pd_text_width(name))/2);
  pd_text_fit(&canvas,x,r.y+4,r.x+r.w-18-x,name,0x383830,0xcbc38a);
  const auto* typeName=pd_type_name(nullptr,type);
  pd_fill(&canvas,r.x+20,r.y+22,pd_text_width(typeName)+8,13,shade(color,tone));
  pd_text(&canvas,r.x+24,r.y+20,typeName,white,PD_NO_SHADOW);
  char amount[12];
  std::snprintf(amount,sizeof amount,"PP %d",pp);
  pd_text_right(&canvas,r.x+r.w-20,r.y+20,amount,0x383830,PD_NO_SHADOW);
}
}

bool ds_platinum_has_command_art(const uint32_t* lower){
  if(!lower)return false;
  for(int i=0;i<3;++i)
    if(!native_face(lower,nativeCommands[i]) || !native_label(lower,nativeCommands[i]))return false;
  return true;
}

bool ds_platinum_has_move_art(const uint32_t* lower,const DsBattleView& view){
  if(!lower || view.panel!=DsBattlePanel::Moves)return false;
  bool ready=false;
  for(int i=0;i<4;++i){
    if(!view.enabled(i))continue;
    if(!move_tile_ready(lower,i))return false;
    ready=true;
  }
  return ready;
}

void ds_draw_battle_strip(uint32_t* stage,const DsBattleStyle& style,const DsBattleView& view,
                          const std::string& nickname,int focus,bool active,unsigned frame,
                          const uint32_t* panelArt){
  if(view.panel==DsBattlePanel::Native)return;
  pd_canvas canvas;
  pd_canvas_init(&canvas,512,384,stage);
  const int bandTop=2*style.bandTop;
  pd_fill(&canvas,0,bandTop,512,384-bandTop,0x414041);
  pd_fill(&canvas,0,bandTop,512,2,0x29272d);
  const bool moves=view.panel==DsBattlePanel::Moves;
  const int top=2*style.textTop, height=382-top;
  constexpr int gap=4,right=508,commandLeft=270;
  const int left=moves?6:commandLeft, end=moves?right-60:right;
  if(!moves)message(canvas,{4,top,commandLeft-8,height},nickname);
  const int cellW=(end-left-gap)/2,cellH=(height-gap)/2;
  Rect selected{};
  static const char* const labels[]={"FIGHT","BAG","POKéMON","RUN"};
  for(int i=0;i<4;++i){
    const Rect card{left+(i%2)*(cellW+gap),top+(i/2)*(cellH+gap),cellW,cellH};
    const bool enabled=view.enabled(i);
    if(moves){
      if(panelArt && view.moves[i] && move_tile_ready(panelArt,i))
        native_move_card(canvas,card,panelArt,i);
      else move_card(canvas,card,view.moves[i],view.pp[i],enabled);
    }
    else if(!native_command(canvas,card,panelArt,i,enabled)){
      bevel(canvas,card,style.actions[i],enabled);
      text2(canvas,{card.x+5,card.y+6,card.w-10,card.h-11},labels[i],
            enabled?white:0xa0a0a0,style.actions[i].shadow,true);
    }
    if(i==focus)selected=card;
  }
  if(moves){
    const Rect back{end+gap,top,right-end-gap,height};
    bevel(canvas,back,style.actions[3],true);
    const int x=back.x+back.w/2,y=back.y+back.h/2;
    pd_triangle(&canvas,x-13,y-9,18,PD_LEFT,white);
    pd_fill(&canvas,x-2,y-3,16,6,white);
    pd_fill(&canvas,x+8,y-11,6,11,white);
  }
  if(active && selected.w)corners(canvas,selected,frame);
}
