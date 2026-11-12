// SPDX-License-Identifier: GPL-3.0-or-later
// Platinum palette and pixel geometry adapted from upstream DsBattleMenu.kt.
#include "battle_strip.h"
#include "companion_style.h"
#include "data_gen.h"
#include <algorithm>
#include <array>
#include <cstdio>
#include <cstring>
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

const DsBattleStyle* ds_battle_style(const char* sha1){
  using namespace pd_ds_data;
  static const DsBattleStyle gold{144,146,ds_platinum_battle_style.actions,DsBattleLook::HeartGold};
  static const DsBattleStyle silver{144,146,ds_platinum_battle_style.actions,DsBattleLook::SoulSilver};
  static const DsBattleStyle unova{145,149,{{
    {0xff6e82,0,0,0,0,0}, {0xfbb228,0,0,0,0,0},
    {0x51cb28,0,0,0,0,0}, {0x28baf3,0,0,0,0,0},
  }},DsBattleLook::Unova};
  static const DsBattleStyle unova2{146,149,{{
    {0xfb2018,0x921818,0,0,0,0}, {0xfbcb28,0x927108,0,0,0,0},
    {0x71db30,0x207108,0,0,0,0}, {0x20a2fb,0x005992,0,0,0,0},
  }},DsBattleLook::Unova2};
  if(!std::strcmp(sha1,pd_ds_data::sha1))return &ds_platinum_battle_style;
  if(!std::strcmp(sha1,heartGoldSha1))return &gold;
  if(!std::strcmp(sha1,soulSilverSha1))return &silver;
  if(!std::strcmp(sha1,blackSha1)||!std::strcmp(sha1,whiteSha1))return &unova;
  if(!std::strcmp(sha1,black2Sha1)||!std::strcmp(sha1,white2Sha1))return &unova2;
  return nullptr;
}

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

bool unova(DsBattleLook look){return look==DsBattleLook::Unova || look==DsBattleLook::Unova2;}

Rect inset(Rect r,int n){return {r.x+n,r.y+n,r.w-2*n,r.h-2*n};}

// The Unova plates lean in opposite directions at either end. Draw the
// nested rims and the texture on integer scanlines inside that silhouette.
int slant(int y,int height,int knee,int cut){
  return y<knee?cut*(knee-y)/knee:cut*(y-knee)/(height-1-knee);
}

int plate_cut(Rect r){return std::clamp(r.w/11,8,21);}

void angled_plate(pd_canvas& canvas,Rect r,int cut,uint32_t color,DsBattleLook look,
                  unsigned tone,bool texture=false){
  for(int y=0;y<r.h;++y){
    const int left=slant(y,r.h,r.h/3,cut);
    const int right=slant(y,r.h,2*r.h/3,cut);
    const uint32_t row=texture&&y%4==0?0x383838:color;
    pd_fill(&canvas,r.x+left,r.y+y,r.w-left-right,1,shade(row,tone));
    if(texture && (look==DsBattleLook::Unova || y%4!=0)){
      const uint32_t grid=look==DsBattleLook::Unova?0x3c3c3c:0x323232;
      for(int x=(left+4)&~3;x<r.w-right;x+=4)
        pd_fill(&canvas,r.x+x,r.y+y,1,1,shade(grid,tone));
    }
  }
}

void family_plate(pd_canvas& canvas,Rect r,DsBattleLook look,uint32_t color,bool enabled,bool move,
                  uint32_t lower=0){
  const unsigned tone=enabled?100:50;
  if(unova(look)){
    const bool sequel=look==DsBattleLook::Unova2;
    const int cut=plate_cut(r);
    angled_plate(canvas,r,cut,0x181818,look,tone);
    angled_plate(canvas,inset(r,1),cut,color,look,tone);
    angled_plate(canvas,inset(r,3),cut,shade(color,50),look,tone);
    angled_plate(canvas,inset(r,4),cut,0x141414,look,tone);
    angled_plate(canvas,inset(r,6),cut,sequel?0x282828:0x303030,look,tone,true);
    return;
  }

  pd_round_rect(&canvas,r.x,r.y,r.w,r.h,7,0x272527);
  pd_round_rect(&canvas,r.x+2,r.y+2,r.w-4,r.h-4,6,shade(white,tone));
  pd_round_rect(&canvas,r.x+4,r.y+4,r.w-8,r.h-8,4,shade(move?shade(color,55):lower,tone));
  const int split=r.y+r.h/2;
  if(move){
    pd_round_rect(&canvas,r.x+6,r.y+5,r.w-12,r.h-10,3,shade(color,tone));
    pd_fill(&canvas,r.x+6,split,r.w-12,r.y+r.h-6-split,shade(color,65*tone/100));
    const Rect face{r.x+13,r.y+7,r.w-26,r.h-14};
    fill(canvas,face,shade(white,tone));
    pd_fill(&canvas,face.x,split,face.w,2,shade(0xe3dbc3,tone));
    pd_fill(&canvas,face.x,split+2,face.w,face.y+face.h-split-2,shade(0xcbbaa2,tone));
  }else {
    pd_fill(&canvas,r.x+5,r.y+5,r.w-10,split-r.y-5,shade(color,tone));
    pd_fill(&canvas,r.x+5,split,r.w-10,1,shade(color,80*tone/100));
  }
}

void unova_command_plate(pd_canvas& canvas,Rect r,DsBattleLook look,const DsBattleColors& color,bool enabled){
  const unsigned tone=enabled?100:50;
  const bool sequel=look==DsBattleLook::Unova2;
  pd_round_rect(&canvas,r.x,r.y,r.w,r.h,4,shade(sequel?0x202020:0x717171,tone));
  pd_round_rect(&canvas,r.x+1,r.y+1,r.w-2,r.h-2,3,shade(sequel?color.face:white,tone));
  fill(canvas,inset(r,3),shade(sequel?color.dark:0x9a9a9a,tone));
  if(sequel)fill(canvas,inset(r,4),shade(0x202020,tone));
  const Rect face=inset(r,sequel?5:4);
  fill(canvas,face,shade(sequel?0x282828:0x383838,tone));
  if(sequel){
    for(int y=0;y<face.h;y+=3)pd_fill(&canvas,face.x,face.y+y,face.w,1,shade(0x383838,tone));
  }else{
    for(int y=2;y<face.h-6;y+=4)
      for(int x=2+(y%8)/2;x<face.w-2;x+=4)
        pd_fill(&canvas,face.x+x,face.y+y,1,1,shade(0x414141,tone));
    for(int y=1;y<=3;++y)
      pd_fill(&canvas,face.x,face.y+face.h-2*y,face.w,2,shade(0x181818+0x080808*y,tone));
  }
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

// The cursor the games draw when a button picks a command or move (Platinum's move screen, Black 2's): solid red
// L-brackets two DS pixels thick, just past each corner. Black / White 2 follow the plate's slant with a white inner
// line. They stand out on any plate colour, the red FIGHT plate too, because their outer half lies on the band.
constexpr uint32_t cursorRed=0xf0141c,cursorDark=0x600000;
void bracket(pd_canvas& canvas,int x,int y,int dx,int dy,int arm,int thick){
  // (x, y) is the outer corner; dx / dy point into the card.
  const int hx=dx>0?x:x-arm+1, vy=dy>0?y:y-arm+1;
  const int hy=dy>0?y:y-thick+1, vx=dx>0?x:x-thick+1;
  pd_fill(&canvas,hx-(dx>0),hy-(dy>0),arm+1,thick+1,cursorDark);
  pd_fill(&canvas,vx-(dx>0),vy-(dy>0),thick+1,arm+1,cursorDark);
  pd_fill(&canvas,hx,hy,arm,thick,cursorRed);
  pd_fill(&canvas,vx,vy,thick,arm,cursorRed);
}

void corners(pd_canvas& canvas,Rect r,unsigned frame,DsBattleLook look,bool moves){
  (void)frame;(void)moves;
  if(unova(look)){
    // Along the plate's top and bottom from where its ends lean in, then down each slanted end.
    const int cut=plate_cut(r);
    constexpr int arm=18,rise=12,thick=4;
    for(int sy:{0,1}){
      const int y=sy?r.y+r.h-thick:r.y;
      const int topRow=sy?r.h-1:0;
      const int lx=r.x+slant(topRow,r.h,r.h/3,cut), rx=r.x+r.w-slant(topRow,r.h,2*r.h/3,cut);
      pd_fill(&canvas,lx-1,y-1,arm+2,thick+2,cursorDark);
      pd_fill(&canvas,rx-arm-1,y-1,arm+2,thick+2,cursorDark);
      pd_fill(&canvas,lx,y,arm,thick,cursorRed);
      pd_fill(&canvas,rx-arm,y,arm,thick,cursorRed);
      pd_fill(&canvas,lx+2,sy?y:y+thick-1,arm-4,1,white);
      pd_fill(&canvas,rx-arm+2,sy?y:y+thick-1,arm-4,1,white);
      for(int i=0;i<rise;++i){
        const int row=sy?r.h-1-i:i;
        const int left=r.x+slant(row,r.h,r.h/3,cut), right=r.x+r.w-slant(row,r.h,2*r.h/3,cut);
        pd_fill(&canvas,left-1,r.y+row,thick+2,1,cursorDark);
        pd_fill(&canvas,right-thick-1,r.y+row,thick+2,1,cursorDark);
        pd_fill(&canvas,left,r.y+row,thick,1,cursorRed);
        pd_fill(&canvas,right-thick,r.y+row,thick,1,cursorRed);
        pd_fill(&canvas,left+thick-1,r.y+row,1,1,white);
        pd_fill(&canvas,right-thick,r.y+row,1,1,white);
      }
    }
    return;
  }
  constexpr int arm=14,thick=4,out=2;
  bracket(canvas,r.x-out,r.y-out,1,1,arm,thick);
  bracket(canvas,r.x+r.w-1+out,r.y-out,-1,1,arm,thick);
  bracket(canvas,r.x-out,r.y+r.h-1+out,1,-1,arm,thick);
  bracket(canvas,r.x+r.w-1+out,r.y+r.h-1+out,-1,-1,arm,thick);
}

void message(pd_canvas& canvas,Rect r,const std::string& nickname,DsBattleLook look){
  const std::string prompt="What will "+nickname+" do?";
  if(look!=DsBattleLook::Platinum){
    if(!unova(look)){
      // DsBattleMenu.kt's two Johto message frame palettes.
      const bool silver=look==DsBattleLook::SoulSilver;
      fill(canvas,r,0x494141);
      fill(canvas,inset(r,2),silver?0xaabadb:0xeba238);
      pd_fill(&canvas,r.x+2,r.y+2,r.w-5,r.h-5,silver?0xdbebfb:0xfbdb69);
      fill(canvas,inset(r,3),0x616169);
      pd_fill(&canvas,r.x+8,r.y+4,r.w-16,r.h-9,0xfbfbfb);
    }
    const auto ink=unova(look)?white:0x515159;
    const auto shadow=look==DsBattleLook::Unova2?0x82aabe:unova(look)?0x75a29e:0xa2a2aa;
    text2(canvas,{r.x+16,r.y+9,r.w-30,30},"What will",ink,shadow,false);
    text2(canvas,{r.x+16,r.y+38,r.w-30,30},(nickname+" do?").c_str(),ink,shadow,false);
    return;
  }
  // Checkerwork and cool white center from Platinum's native battle dialogue.
  fill(canvas,r,0x414041);
  for(int y=4;y<r.h-4;y+=6)for(int x=2;x<r.w-2;x+=6){
    if(x>=14 && x+6<=r.w-14)continue;
    const auto color=((x/6+y/6)&1)?0x73717b:0x525052;
    pd_fill(&canvas,r.x+x,r.y+y,std::min(6,r.w-x-2),std::min(6,r.h-y-4),color);
  }
  pd_fill(&canvas,r.x+12,r.y+3,r.w-24,r.h-6,0x9b96a1);
  pd_fill(&canvas,r.x+14,r.y+5,r.w-28,r.h-10,0xfffaff);
  const int width=r.w-48;
  if(pd_text_width(prompt.c_str())*2+2<=width)
    text2(canvas,{r.x+24,r.y+8,width,32},prompt.c_str(),0x38343c,0xb2aab5,false);
  else {
    const std::string subject=nickname+" do?";
    text2(canvas,{r.x+24,r.y+8,width,30},"What will",0x38343c,0xb2aab5,false);
    text2(canvas,{r.x+24,r.y+36,width,30},subject.c_str(),0x38343c,0xb2aab5,false);
  }
}

// Gen 4's "What will X do?" box is the game's own on the top screen behind the strip, in whichever frame the player
// picked (Platinum has twenty). Keep that frame: its left edge, then a clean column of its interior stretched across,
// then its right edge, narrowed to [width]. The column just inside the right edge is blank in every frame (the text is
// left-aligned and short). False when the box isn't there (light, even interior), and the drawn frame is used.
bool native_message_frame(pd_canvas& canvas,int top,int width){
  constexpr int left=26,right=44,probe=512-right-2;
  const int rows=384-top;
  if(rows<24 || width<left+right+40)return false;
  for(int y=top+14;y<384-12;++y){
    const uint32_t p=canvas.px[y*canvas.w+probe]&0xffffff;
    if(((p>>16)&255)<200 || ((p>>8)&255)<200 || (p&255)<200)return false;
  }
  std::array<uint32_t,(left+right+1)*96> saved{};
  if(rows>96)return false;
  for(int y=0;y<rows;++y){
    const uint32_t* row=canvas.px+(top+y)*canvas.w;
    for(int x=0;x<left;++x)saved[y*(left+right+1)+x]=row[x];
    for(int x=0;x<right;++x)saved[y*(left+right+1)+left+x]=row[512-right+x];
    saved[y*(left+right+1)+left+right]=row[probe];
  }
  for(int y=0;y<rows;++y){
    uint32_t* row=canvas.px+(top+y)*canvas.w;
    const uint32_t* src=saved.data()+y*(left+right+1);
    for(int x=0;x<width;++x)
      row[x]=x<left?src[x]:x>=width-right?src[left+x-(width-right)]:src[left+right];
  }
  return true;
}

void native_message_text(pd_canvas& canvas,int top,int width,const std::string& nickname){
  // The games' grey ink and its light shadow, where the native line starts.
  constexpr uint32_t ink=0x505058,shadow=0xb0b0b8;
  const std::string prompt="What will "+nickname+" do?";
  const int x=30,room=width-44-x-4;
  if(pd_text_width(prompt.c_str())*2+2<=room)
    text2(canvas,{x,top+8,room,32},prompt.c_str(),ink,shadow,false);
  else {
    text2(canvas,{x,top+6,room,30},"What will",ink,shadow,false);
    text2(canvas,{x,top+34,room,30},(nickname+" do?").c_str(),ink,shadow,false);
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

// Keep glyphs, shadows and tracking on the same integer pixel grid.
void move_ink(pd_canvas& canvas,int x,int y,const uint32_t* lower,Rect src,int blankX){
  for(int sy=0;sy<src.h;++sy){
    const auto background=lower_pixel(lower,blankX,src.y+sy);
    auto* row=canvas.px+(y+2*sy)*canvas.w+x;
    for(int sx=0;sx<src.w;++sx){
      const auto p=lower_pixel(lower,src.x+sx,src.y+sy);
      if(p==background)continue;
      row[2*sx]=row[2*sx+1]=p;
      row[canvas.w+2*sx]=row[canvas.w+2*sx+1]=p;
    }
  }
}

void native_move_card(pd_canvas& canvas,Rect dst,const uint32_t* lower,int slot){
  const auto tile=move_tile(slot);
  constexpr int lip=3,foot=3,side=9;
  constexpr int lipRows[]={0,4,12};
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
  const Rect name{tile.x+18,tile.y+16,92,12};
  // The outermost two badge rows are decorative lips. Keep the lettering,
  // its shadow, and the complete PP counter at 2×.
  const Rect details{tile.x+14,tile.y+32,96,12};
  move_ink(canvas,dst.x+dst.w/2-name.w,dst.y+lip,lower,name,tile.x+16);
  move_ink(canvas,dst.x+dst.w/2-details.w,dst.y+lip+2*name.h,lower,details,tile.x+53);
}

// The type names the games print on their move cards: capitals, cut to six (ELECTR, PSYCHC), in type order.
const char* const cardTypes[]={"NORMAL","FIGHT","FLYING","POISON","GROUND","ROCK","BUG","GHOST","STEEL","???",
  "FIRE","WATER","GRASS","ELECTR","PSYCHC","ICE","DRAGON","DARK"};
const char* card_type(int type){return type>=0 && type<int(std::size(cardTypes))?cardTypes[type]:"???";}

// PP as the cards show it: current / max where the max is known, tinted as it runs low (half, a quarter, none).
uint32_t pp_ink(int pp,int maxPp,uint32_t ink){
  if(maxPp<=0)return pp?ink:0xd82818;
  if(pp==0)return 0xd82818;
  if(pp*4<=maxPp)return 0xe06a10;
  if(pp*2<=maxPp)return 0xc89800;
  return ink;
}
void pp_label(char* out,size_t n,int pp,int maxPp){
  if(maxPp>0)std::snprintf(out,n,"PP %d/%d",pp,maxPp);
  else std::snprintf(out,n,"PP %d",pp);
}

// 2x text against a right edge: its ink width, then left-aligned there.
void text2_right(pd_canvas& canvas,int right,Rect row,const char* text,uint32_t ink,uint32_t shadow){
  const int w=2*pd_text_width(text)+2;
  text2(canvas,{std::max(row.x,right-w),row.y,std::min(w,right-row.x),row.h},text,ink,shadow,false);
}

void type_badge(pd_canvas& canvas,int x,int y,int type,unsigned tone){
  const char* label=card_type(type);
  const int w=pd_text_width(label)+8;
  const uint32_t color=ds_type_color(type);
  pd_round_rect(&canvas,x,y,w,13,2,shade(shade(color,60),tone));
  pd_round_rect(&canvas,x+1,y+1,w-2,11,2,shade(color,tone));
  pd_fill(&canvas,x+2,y+1,w-4,1,shade(0xffffff,tone*35/100)|shade(color,tone));
  pd_text(&canvas,x+4,y-1,label,shade(white,tone),shade(0x303030,tone));
}

// HeartGold / SoulSilver's move card (a type-coloured frame, white over beige) and Platinum's (cream) when its own art
// isn't there yet: the name across the top, the type badge and PP below, both at the size the games print them.
void gen4_move_card(pd_canvas& canvas,Rect r,int move,int pp,int maxPp,bool enabled,DsBattleLook look){
  const int type=ds_move_type(move,DsGame::Platinum);
  const unsigned tone=enabled?100:55;
  uint32_t frame=ds_type_color(type);
  if(type==0)frame=0x8a79ab;
  if(type==13)frame=0xe2c415;
  const bool platinum=look==DsBattleLook::Platinum;
  const uint32_t faceTop=platinum?0xf8f0b8:0xfbfbfb, faceLow=platinum?0xe8d890:0xd8cab0;
  pd_round_rect(&canvas,r.x,r.y,r.w,r.h,6,shade(0x282028,tone));
  pd_round_rect(&canvas,r.x+2,r.y+2,r.w-4,r.h-4,5,shade(frame,tone));
  pd_fill(&canvas,r.x+6,r.y+2,r.w-12,2,shade(0xffffff,tone*60/100)|shade(frame,tone));
  const int split=r.y+r.h/2;
  pd_fill(&canvas,r.x+3,split,r.w-6,r.y+r.h-3-split,shade(frame,70*tone/100));
  const Rect face{r.x+8,r.y+5,r.w-16,r.h-10};
  pd_round_rect(&canvas,face.x-1,face.y-1,face.w+2,face.h+2,3,shade(0x484048,tone));
  fill(canvas,face,shade(faceTop,tone));
  pd_fill(&canvas,face.x,face.y+face.h/2,face.w,face.h-face.h/2,shade(faceLow,tone));
  if(!move)return;
  const uint32_t ink=enabled?0x403848:0x8a8a8a, shadow=shade(platinum?0xd0c890:0xc8c0b8,tone);
  text2(canvas,{face.x+4,face.y+1,face.w-8,face.h/2},ds_move(move),ink,shadow,true);
  const int row=face.y+face.h/2;
  type_badge(canvas,face.x+6,row+(face.h-face.h/2-13)/2,type,tone);
  char amount[16];
  pp_label(amount,sizeof amount,pp,maxPp);
  text2_right(canvas,face.x+face.w-6,{face.x+60,row,face.w-66,face.h-face.h/2},amount,
              enabled?pp_ink(pp,maxPp,ink):0x8a8a8a,shadow);
}

// Black / White's and their sequels' angled move plate: a dark face inside a type-coloured rim (Normal's is white),
// white lettering (grey once a move is out of PP), the badge and PP below.
void unova_move_card(pd_canvas& canvas,Rect r,int move,int pp,int maxPp,bool enabled,DsBattleLook look){
  const int type=ds_move_type(move,DsGame::Black);
  const unsigned tone=enabled?100:55;
  uint32_t edge=ds_type_color(type);
  if(type==0)edge=0xebebeb;
  if(type==10)edge=0xec3a30;
  family_plate(canvas,r,look,edge,enabled,true);
  if(!move)return;
  const uint32_t ink=enabled?white:0x7a7a7a, shadow=enabled?0x202020:PD_NO_SHADOW;
  const int cut=plate_cut(r);
  text2(canvas,{r.x+cut,r.y+5,r.w-2*cut,r.h/2-4},pd_ds_data::unovaMoves[move],ink,shadow,true);
  const int row=r.y+r.h/2;
  type_badge(canvas,r.x+cut+6,row+(r.h-r.h/2-13)/2-2,type,tone);
  char amount[16];
  pp_label(amount,sizeof amount,pp,maxPp);
  text2_right(canvas,r.x+r.w-cut-6,{r.x+cut+56,row-2,r.w-2*cut-62,r.h-r.h/2-2},amount,
              enabled?pp_ink(pp,maxPp,ink):0x7a7a7a,shadow);
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
  const bool moves=view.panel==DsBattlePanel::Moves;
  const int bandTop=2*style.bandTop-(moves?14:0);
  const bool platinum=style.look==DsBattleLook::Platinum;
  const bool unovan=unova(style.look);
  constexpr int gap=4,right=508,commandLeft=270;
  // Gen 4's prompt box is the game's own frame, narrowed beside the plates: taken before the band covers it.
  const bool nativeBox=!moves && !unovan && native_message_frame(canvas,bandTop,commandLeft-4);
  // DsBattleMenu.kt's drawBand: the two Unova families have distinct message bands.
  const int bandHeight=384-bandTop;
  const uint32_t band=platinum?0x000000:style.look==DsBattleLook::Unova?0x0c3c34:
    style.look==DsBattleLook::Unova2?0x0c3851:0x202020;
  const int bandLeft=nativeBox?commandLeft-4:0;
  pd_fill(&canvas,bandLeft,bandTop,512-bandLeft,bandHeight,band);
  auto line=[&](int y,uint32_t color){pd_fill(&canvas,0,y*2,512,2,color);};
  if(style.look==DsBattleLook::Unova){
    const int mid=bandTop+bandHeight*35/100;
    pd_fill(&canvas,0,mid,512,384-mid,0x08302c);
    line(147,0x4d201c);line(148,0x24201c);line(187,0x24201c);line(188,0x4d201c);
  }else if(style.look==DsBattleLook::Unova2){
    pd_fill(&canvas,0,bandTop+bandHeight*30/100,512,bandHeight*40/100,0x184155);
    line(147,0x083855);line(148,0x305d7d);line(187,0x285165);line(188,0x41697d);
  }
  const int top=moves?bandTop:2*style.textTop;
  const int height=(moves?384:382)-top;
  const int rowGap=moves?2:4;
  const int left=moves?6:commandLeft, end=moves?right-52:right;
  if(!moves){
    if(nativeBox)native_message_text(canvas,bandTop,commandLeft-4,nickname);
    else message(canvas,{4,top,commandLeft-8,height},nickname,style.look);
  }
  const int cellW=(end-left-gap)/2,cellH=(height-rowGap)/2;
  Rect selected{};
  static const char* const labels[]={"FIGHT","BAG","POKéMON","RUN"};
  for(int i=0;i<4;++i){
    const Rect card{left+(i%2)*(cellW+gap),top+(i/2)*(cellH+rowGap),cellW,cellH};
    const bool enabled=view.enabled(i);
    if(moves){
      if(platinum && panelArt && view.moves[i] && move_tile_ready(panelArt,i))
        native_move_card(canvas,card,panelArt,i);
      else if(unovan)unova_move_card(canvas,card,view.moves[i],view.pp[i],view.maxPp[i],enabled,style.look);
      else gen4_move_card(canvas,card,view.moves[i],view.pp[i],view.maxPp[i],enabled,style.look);
    }
    else if(!platinum || !native_command(canvas,card,panelArt,i,enabled)){
      if(platinum)bevel(canvas,card,style.actions[i],enabled);
      else if(unovan)unova_command_plate(canvas,card,style.look,style.actions[i],enabled);
      else family_plate(canvas,card,style.look,style.actions[i].face,enabled,false,style.actions[i].dark);
      const uint32_t ink=unovan?style.actions[i].face:white;
      text2(canvas,{card.x+5,card.y+6,card.w-10,card.h-11},labels[i],enabled?ink:0xa0a0a0,
            platinum?style.actions[i].shadow:outline,true);
    }
    if(i==focus)selected=card;
  }
  if(moves){
    const Rect back{end+gap,top,right-end-gap,height};
    if(platinum)bevel(canvas,back,style.actions[3],true);
    else family_plate(canvas,back,style.look,style.actions[3].face,true,false,style.actions[3].dark);
    const int x=back.x+back.w/2,y=back.y+back.h/2;
    pd_triangle(&canvas,x-13,y-9,18,PD_LEFT,white);
    pd_fill(&canvas,x-2,y-3,16,6,white);
    pd_fill(&canvas,x+8,y-11,6,11,white);
  }
  if(active && selected.w)corners(canvas,selected,frame,style.look,moves);
}
