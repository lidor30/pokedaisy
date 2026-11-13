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

// HeartGold / SoulSilver's command plate: a white line inside a dark outline, the colour over its darker half.
void family_plate(pd_canvas& canvas,Rect r,uint32_t color,bool enabled,uint32_t lower){
  const unsigned tone=enabled?100:50;
  pd_round_rect(&canvas,r.x,r.y,r.w,r.h,7,0x272527);
  pd_round_rect(&canvas,r.x+2,r.y+2,r.w-4,r.h-4,6,shade(white,tone));
  pd_round_rect(&canvas,r.x+4,r.y+4,r.w-8,r.h-8,4,shade(lower,tone));
  const int split=r.y+r.h/2;
  pd_fill(&canvas,r.x+5,r.y+5,r.w-10,split-r.y-5,shade(color,tone));
  pd_fill(&canvas,r.x+5,split,r.w-10,1,shade(color,80*tone/100));
}

// Black / White's and their sequels' command plates. FIGHT is a filled plate in its colour; the other three are dark,
// with the colour in their rim (the sequels) and their lettering.
void unova_command_plate(pd_canvas& canvas,Rect r,DsBattleLook look,const DsBattleColors& color,bool enabled,
                         bool filled){
  const unsigned tone=enabled?100:50;
  const bool sequel=look==DsBattleLook::Unova2;
  pd_round_rect(&canvas,r.x,r.y,r.w,r.h,4,shade(sequel||filled?0x202020:0x717171,tone));
  const uint32_t fillColor=sequel?color.face:0xe8344a;
  pd_round_rect(&canvas,r.x+1,r.y+1,r.w-2,r.h-2,3,shade(filled?fillColor:sequel?color.face:white,tone));
  if(filled){
    // Lit along the top, a darker foot: the games' red plate is a raised button.
    pd_fill(&canvas,r.x+4,r.y+2,r.w-8,2,pd_mix(shade(fillColor,tone),shade(white,tone),110));
    pd_fill(&canvas,r.x+2,r.y+r.h-7,r.w-4,5,shade(fillColor,tone*68/100));
    return;
  }
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

// 2x text placed by its capitals' top edge (the font's fifth row), not by its ink: every label in a row then shares a
// baseline, whatever its descenders. Returns the advance.
int caps2(pd_canvas& canvas,int x,int y,const char* text,uint32_t ink,uint32_t shadow){
  return pd_text_scaled(&canvas,x,y-8,text,ink,shadow,2);
}
// The same, centred in [left, left + width).
void caps2_centre(pd_canvas& canvas,int left,int width,int y,const char* text,uint32_t ink,uint32_t shadow){
  caps2(canvas,left+std::max(0,width-(2*pd_text_width(text)-2))/2,y,text,ink,shadow);
}

// A command plate's label, centred at 2x. "POKéMON" is the one label wider than its plate's face allows: it is set a
// font pixel tighter between letters, as the games' own narrower lettering fits it.
void command_label(pd_canvas& canvas,Rect card,const char* label,uint32_t ink,uint32_t shadow){
  const bool tight=2*pd_text_width(label)>card.w-22;
  int width=0;
  char glyph[5];
  auto each=[&](auto&& draw){
    int x=0;
    for(const char* p=label;*p;){
      int n=1;
      while((p[n]&0xc0)==0x80)++n;
      std::memcpy(glyph,p,n);glyph[n]=0;p+=n;
      draw(x);
      x+=2*(pd_text_width(glyph)-(tight?1:0));
    }
    return x;
  };
  width=each([](int){})-2*(tight?0:1);
  const int left=card.x+(card.w-width)/2, y=card.y+(card.h-18)/2;
  each([&](int x){caps2(canvas,left+x,y,glyph,ink,shadow);});
}

// Black / White's and their sequels' move plate is a hexagon: widest at mid-height, each end a point. A row's inset
// from the plate's box.
int hex_inset(int y,int height,int cut){
  return cut*std::abs(2*y-(height-1))/(height-1);
}

// The cursor the games draw when a button picks a command or move: four L-brackets on the plate's corners. Platinum's
// are pure red, 10 DS pixels along each arm and 4 thick on a 55-row card; on plates half that height the same
// proportions are a third of the plate's height along each arm and 3 DS pixels thick. A dark edge keeps them apart from
// the red FIGHT plate. [out] is how far they stand off the plate: it steps with the pulse, and is 0 while pressed.
constexpr uint32_t cursorRed=0xf80000,cursorDark=0x600000;
constexpr int cursorThick=6;
void bracket(pd_canvas& canvas,int x,int y,int dx,int dy,int arm){
  // (x, y) is the outer corner; dx / dy point into the card.
  constexpr int thick=cursorThick;
  const int hx=dx>0?x:x-arm+1, vy=dy>0?y:y-arm+1;
  const int hy=dy>0?y:y-thick+1, vx=dx>0?x:x-thick+1;
  pd_fill(&canvas,hx-1,hy-1,arm+2,thick+2,cursorDark);
  pd_fill(&canvas,vx-1,vy-1,thick+2,arm+2,cursorDark);
  pd_fill(&canvas,hx,hy,arm,thick,cursorRed);
  pd_fill(&canvas,vx,vy,thick,arm,cursorRed);
}

void corners(pd_canvas& canvas,Rect r,int out,int hexCut){
  const int arm=std::min(16,r.h/3)&~1;
  if(hexCut){
    // A hexagonal plate: along its top and bottom from where the ends lean in, then down each slanted end.
    constexpr int thick=cursorThick;
    for(int pass=0;pass<2;++pass){
      const uint32_t color=pass?cursorRed:cursorDark;
      const int grow=pass?0:1;
      for(int sy:{0,1}){
        const int y=sy?r.y+r.h-thick+out:r.y-out;
        pd_fill(&canvas,r.x+hexCut-out-grow,y-grow,arm+2*grow,thick+2*grow,color);
        pd_fill(&canvas,r.x+r.w-hexCut+out-arm-grow,y-grow,arm+2*grow,thick+2*grow,color);
        for(int i=0;i<arm;++i){
          const int row=sy?r.h-1-i:i;
          const int in=hex_inset(row,r.h,hexCut)-out;
          pd_fill(&canvas,r.x+in-grow,r.y+row+(sy?out:-out)-grow,thick+2*grow,1+2*grow,color);
          pd_fill(&canvas,r.x+r.w-in-thick-grow,r.y+row+(sy?out:-out)-grow,thick+2*grow,1+2*grow,color);
        }
      }
    }
    return;
  }
  bracket(canvas,r.x-out,r.y-out,1,1,arm);
  bracket(canvas,r.x+r.w-1+out,r.y-out,-1,1,arm);
  bracket(canvas,r.x-out,r.y+r.h-1+out,1,-1,arm);
  bracket(canvas,r.x+r.w-1+out,r.y+r.h-1+out,-1,-1,arm);
}

// A pressed plate: everything on it a step darker for the frames the tap is held, as the games' buttons dip.
void press(pd_canvas& canvas,Rect r){
  for(int y=std::max(0,r.y);y<std::min(canvas.h,r.y+r.h);++y){
    uint32_t* row=canvas.px+y*canvas.w;
    for(int x=std::max(0,r.x);x<std::min(canvas.w,r.x+r.w);++x)row[x]=shade(row[x]&0xffffff,72);
  }
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
    if(unova(look)){
      // No frame to sit in: the two lines start higher, and the ball rows take the band's foot.
      pd_clip(&canvas,r.x,r.y,r.w,r.h);
      caps2(canvas,r.x+16,r.y+4,"What will",ink,shadow);
      caps2(canvas,r.x+16,r.y+32,(nickname+" do?").c_str(),ink,shadow);
      pd_unclip(&canvas);
      return;
    }
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

// Platinum's own card is 55 rows; the strip's is 54 pixels, and its two rows of 2x lettering take 46 of them. So the
// frame keeps the game's pixels at their own size where there is room and drops rows where there isn't: the full 14
// columns of each side, and of the top its outline, white lip, coloured lip and dark edge (one row each of the game's
// three, two, four and one). Only the cream face stretches. The face darkens at the game's own row (40 of 55), which is
// two thirds down the details.
void native_move_card(pd_canvas& canvas,Rect dst,const uint32_t* lower,int slot){
  const auto tile=move_tile(slot);
  constexpr int lip=5,foot=3,side=14;
  constexpr int lipRows[]={2,4,6,9,14},footRows[]={50,51,53};
  const Rect name{tile.x+18,tile.y+17,92,11};
  // The outermost two badge rows are decorative lips. Keep the lettering,
  // its shadow, and the complete PP counter at 2×.
  const Rect details{tile.x+14,tile.y+32,96,12};
  const int lowFace=lip+2*name.h+2*8;
  for(int y=0;y<dst.h;++y){
    const bool edge=y<lip-1 || y>=dst.h-foot;
    const int sy=y<lip?lipRows[y]:y>=dst.h-foot?footRows[y-(dst.h-foot)]:y<lowFace?14:46;
    int left=0,right=tile.w-1;
    if(edge){
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
// The type badge's capitals: 7 pixels tall and 4 wide (M, T, W and Y five, I three), each row's bits from the left. The
// shared font's are 6 wide, and a six-letter type at 2x would be most of a card; these set one in 30 DS pixels, as the
// games' own condensed badge lettering does.
struct BadgeGlyph { char letter; uint8_t width; uint8_t rows[7]; };
constexpr BadgeGlyph badgeGlyphs[]={
  {'A',4,{0b0110,0b1001,0b1001,0b1111,0b1001,0b1001,0b1001}},
  {'B',4,{0b1110,0b1001,0b1001,0b1110,0b1001,0b1001,0b1110}},
  {'C',4,{0b0111,0b1000,0b1000,0b1000,0b1000,0b1000,0b0111}},
  {'D',4,{0b1110,0b1001,0b1001,0b1001,0b1001,0b1001,0b1110}},
  {'E',4,{0b1111,0b1000,0b1000,0b1110,0b1000,0b1000,0b1111}},
  {'F',4,{0b1111,0b1000,0b1000,0b1110,0b1000,0b1000,0b1000}},
  {'G',4,{0b0111,0b1000,0b1000,0b1011,0b1001,0b1001,0b0111}},
  {'H',4,{0b1001,0b1001,0b1001,0b1111,0b1001,0b1001,0b1001}},
  {'I',3,{0b111,0b010,0b010,0b010,0b010,0b010,0b111}},
  {'K',4,{0b1001,0b1010,0b1100,0b1100,0b1010,0b1001,0b1001}},
  {'L',4,{0b1000,0b1000,0b1000,0b1000,0b1000,0b1000,0b1111}},
  {'M',5,{0b10001,0b11011,0b10101,0b10101,0b10001,0b10001,0b10001}},
  {'N',4,{0b1001,0b1101,0b1101,0b1011,0b1011,0b1001,0b1001}},
  {'O',4,{0b0110,0b1001,0b1001,0b1001,0b1001,0b1001,0b0110}},
  {'P',4,{0b1110,0b1001,0b1001,0b1110,0b1000,0b1000,0b1000}},
  {'R',4,{0b1110,0b1001,0b1001,0b1110,0b1010,0b1001,0b1001}},
  {'S',4,{0b0111,0b1000,0b1000,0b0110,0b0001,0b0001,0b1110}},
  {'T',5,{0b11111,0b00100,0b00100,0b00100,0b00100,0b00100,0b00100}},
  {'U',4,{0b1001,0b1001,0b1001,0b1001,0b1001,0b1001,0b0110}},
  {'W',5,{0b10001,0b10001,0b10001,0b10101,0b10101,0b11011,0b10001}},
  {'Y',5,{0b10001,0b10001,0b01010,0b00100,0b00100,0b00100,0b00100}},
  {'?',4,{0b1110,0b0001,0b0001,0b0110,0b0100,0b0000,0b0100}},
};
const BadgeGlyph* badge_glyph(char letter){
  for(const auto& glyph:badgeGlyphs)if(glyph.letter==letter)return &glyph;
  return nullptr;
}
int badge_width(const char* label){
  int w=0;
  for(;*label;++label)if(const auto* glyph=badge_glyph(*label))w+=glyph->width+1;
  return w?w-1:0;
}
void badge_text(pd_canvas& canvas,int x,int y,const char* label,uint32_t color){
  for(;*label;++label){
    const auto* glyph=badge_glyph(*label);
    if(!glyph)continue;
    for(int row=0;row<7;++row)for(int column=0;column<glyph->width;++column)
      if(glyph->rows[row]&(1<<(glyph->width-1-column)))pd_fill(&canvas,x+2*column,y+2*row,2,2,color);
    x+=2*(glyph->width+1);
  }
}

// The badge beside the PP, the size the games print it: as tall as the PP's capitals and a rim, every type the same
// width (the games' is 32 DS pixels). Gen 4's is a rounded tab with a lit top edge; Unova's a flat block.
constexpr int badgeW=68,badgeH=20;
void type_badge(pd_canvas& canvas,int x,int y,int type,unsigned tone,bool flat){
  const char* label=card_type(type);
  const uint32_t color=shade(ds_type_color(type),tone);
  if(flat){
    pd_fill(&canvas,x,y,badgeW,badgeH,shade(color,55));
    pd_fill(&canvas,x+1,y+1,badgeW-2,badgeH-2,color);
  }else{
    pd_round_rect(&canvas,x,y,badgeW,badgeH,3,shade(color,55));
    pd_round_rect(&canvas,x+1,y+1,badgeW-2,badgeH-2,2,color);
    pd_fill(&canvas,x+3,y+1,badgeW-6,1,pd_mix(color,shade(white,tone),96));
  }
  const int left=x+(badgeW-2*badge_width(label))/2;
  badge_text(canvas,left+2,y+5,label,shade(color,45));
  badge_text(canvas,left,y+3,label,shade(white,tone));
}

// A card's lower row, as the games set it: the badge, "PP" beside it, and the count against the right edge. The count
// alone carries the low-PP tint. [y] is the badge's top; the lettering's capitals start a pixel under it.
void details_row(pd_canvas& canvas,int left,int right,int y,int type,int pp,int maxPp,unsigned tone,bool flat,
                 uint32_t ink,uint32_t shadow){
  type_badge(canvas,left,y,type,tone,flat);
  caps2(canvas,left+badgeW+(flat?4:6),y+1,"PP",ink,shadow);
  char amount[16];
  if(maxPp>0)std::snprintf(amount,sizeof amount,"%d/%d",pp,maxPp);
  else std::snprintf(amount,sizeof amount,"%d",pp);
  caps2(canvas,right-(2*pd_text_width(amount)-2),y+1,amount,pp_ink(pp,maxPp,ink),shadow);
}

// A move card's rows, shared by every drawn style. A card is 54 pixels tall: 2x capitals are 18 and a descender 4
// more, so the name's capitals start 7 down, the descenders end at 28, and the 20-pixel badge sits from 29.
constexpr int nameTop=7,detailsTop=29;

// HeartGold / SoulSilver's move card (a type-coloured frame, thick at the sides and thin above and below, around a face
// that is white over beige) and Platinum's (cream) when its own art isn't there yet. A move out of PP keeps its name
// readable on a greyed card, and its count is red.
void gen4_move_card(pd_canvas& canvas,Rect r,int move,int pp,int maxPp,bool enabled,DsBattleLook look){
  const int type=ds_move_type(move,DsGame::Platinum);
  const unsigned tone=enabled?100:78;
  uint32_t frame=move?ds_type_color(type):0x8a8a92;
  if(move && type==0)frame=0x8a79ab;
  if(move && type==13)frame=0xe2c415;
  frame=shade(frame,tone);
  const bool platinum=look==DsBattleLook::Platinum;
  const uint32_t faceTop=platinum?0xf8f0b8:0xfbfbfb, faceLow=platinum?0xe8d890:0xd8cab0;
  pd_round_rect(&canvas,r.x,r.y,r.w,r.h,6,shade(0x282028,tone));
  pd_round_rect(&canvas,r.x+2,r.y+2,r.w-4,r.h-4,5,frame);
  // Lit from the upper left: a pale top edge and left side, a darker right side and foot.
  pd_fill(&canvas,r.x+7,r.y+2,r.w-14,1,pd_mix(frame,shade(white,tone),140));
  pd_fill(&canvas,r.x+3,r.y+7,3,r.h-14,pd_mix(frame,shade(white,tone),90));
  pd_fill(&canvas,r.x+r.w-6,r.y+7,3,r.h-14,shade(frame,72));
  pd_fill(&canvas,r.x+7,r.y+r.h-4,r.w-14,2,shade(frame,72));
  const Rect face{r.x+11,r.y+4,r.w-22,r.h-8};
  pd_round_rect(&canvas,face.x-1,face.y-1,face.w+2,face.h+2,2,shade(frame,55));
  fill(canvas,face,shade(faceTop,tone));
  const int split=r.y+detailsTop-1;
  pd_fill(&canvas,face.x,split,face.w,face.y+face.h-split,shade(faceLow,tone));
  if(!move)return;
  const uint32_t ink=0x403848, shadow=shade(platinum?0xd0c890:0xc8c0b8,tone);
  caps2_centre(canvas,face.x,face.w,r.y+nameTop,ds_move(move),ink,shadow);
  details_row(canvas,face.x+3,face.x+face.w-5,r.y+detailsTop,type,pp,maxPp,tone,false,ink,
              shade(platinum?0xc0b070:0xb8a890,tone));
}

// A hexagon's rows inside [r], [dx] and [dy] in from its edges; every nested one keeps the outer one's slope.
void hex_plate(pd_canvas& canvas,Rect r,int cut,int dx,int dy,uint32_t color,int dots=0){
  for(int y=dy;y<r.h-dy;++y){
    const int in=hex_inset(y,r.h,cut)+dx;
    pd_fill(&canvas,r.x+in,r.y+y,r.w-2*in,1,color);
    // The plates' dark face is a fine grid of dots.
    if(dots && y%4==2)for(int x=(in+3)&~3;x<r.w-in;x+=4)pd_fill(&canvas,r.x+x,r.y+y,2,2,dots);
  }
}
// How far a Unova move plate's ends lean in: Black / White's are blunt, the sequels' sharper.
int hex_cut(DsBattleLook look){return look==DsBattleLook::Unova2?16:12;}

// Black / White's and their sequels' move plate: a dark dotted hexagon inside a type-coloured rim (Normal's is white).
// The first games' rim is one soft band; the sequels' is a band that is heavy at the points and a fine line inside it.
// White lettering; a move out of PP is greyed, its count red.
void unova_move_card(pd_canvas& canvas,Rect r,int move,int pp,int maxPp,bool enabled,DsBattleLook look){
  const int type=ds_move_type(move,DsGame::Black);
  const unsigned tone=enabled?100:60;
  uint32_t edge=move?ds_type_color(type):0x707070;
  if(move && type==0)edge=0xebebeb;
  if(move && type==10)edge=0xec3a30;
  edge=shade(edge,tone);
  const bool sequel=look==DsBattleLook::Unova2;
  const int cut=hex_cut(look);
  hex_plate(canvas,r,cut,0,0,0x101010);
  if(sequel){
    hex_plate(canvas,r,cut,2,1,edge);
    hex_plate(canvas,r,cut,7,3,0x101010);
    hex_plate(canvas,r,cut,9,5,shade(edge,80));
    hex_plate(canvas,r,cut,10,6,shade(0x282828,tone),shade(0x323232,tone));
  }else{
    hex_plate(canvas,r,cut,2,1,shade(edge,60));
    hex_plate(canvas,r,cut,3,2,edge);
    hex_plate(canvas,r,cut,7,5,pd_mix(edge,shade(white,tone),110));
    hex_plate(canvas,r,cut,8,6,shade(0x303030,tone),shade(0x3a3a3a,tone));
  }
  if(!move)return;
  const uint32_t ink=enabled?white:0xb0b0b0, shadow=0x181818;
  caps2_centre(canvas,r.x,r.w,r.y+nameTop,pd_ds_data::unovaMoves[move],ink,shadow);
  // The details row is the plate's lower slope: keep it inside the rim at its last row.
  const int in=hex_inset(detailsTop+badgeH,r.h,cut)+(sequel?12:11);
  details_row(canvas,r.x+in,r.x+r.w-in,r.y+detailsTop-1,type,pp,maxPp,tone,true,ink,shadow);
}

// The games' ball rows, a DS pixel to each 2x2: the party's seven wide, the other trainer's five.
void ball(pd_canvas& canvas,int x,int y,int state,bool small,uint32_t rim){
  static const char* const large[]={"..###..",".#ttt#.","#ttttt#","#kkokk#","#lllll#",".#lll#.","..###.."};
  static const char* const little[]={".###.","#ttt#","#kok#","#lll#",".###."};
  const char* const* rows=small?little:large;
  const int n=small?5:7;
  if(state==0){
    // An empty slot: a dot.
    pd_fill(&canvas,x+2*(n/2)-2,y+2*(n/2)-2,6,6,0x606060);
    pd_fill(&canvas,x+2*(n/2),y+2*(n/2),2,2,0x9a9a9a);
    return;
  }
  // Healthy, a status condition (the games tint it), fainted (greyed).
  const uint32_t top=state==1?0xe83030:state==2?0xd8a818:0x8a8a8a, low=state==3?0xb0b0b0:0xf8f8f8;
  for(int j=0;j<n;++j)for(int i=0;i<n;++i){
    const char c=rows[j][i];
    if(c=='.')continue;
    pd_fill(&canvas,x+2*i,y+2*j,2,2,c=='#'||c=='k'?rim:c=='t'?top:c=='o'?0xf8f8f8:low);
  }
}
// The party's row from [x] rightwards and, against a trainer, theirs ending at [right].
void ball_rows(pd_canvas& canvas,int x,int right,int y,const DsBattleView& view,uint32_t rim){
  if(!view.balls)return;
  for(int i=0;i<6;++i)ball(canvas,x+16*i,y,view.partyBalls[i],false,rim);
  if(view.trainer)for(int i=0;i<6;++i)ball(canvas,right-12*(6-i),y+4,view.foeBalls[i],true,rim);
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
                          const uint32_t* panelArt,bool pressed){
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
    // The ball rows the touch screen showed, along the foot of the prompt.
    // Their outline is dark on Gen 4's white box, and the band's own dark on Unova's.
    if(unovan)ball_rows(canvas,20,commandLeft-12,360,view,0x04181c);
    else ball_rows(canvas,30,commandLeft-(nativeBox?52:20),362,view,0x282828);
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
      else if(unovan)unova_command_plate(canvas,card,style.look,style.actions[i],enabled,i==0);
      else family_plate(canvas,card,style.actions[i].face,enabled,style.actions[i].dark);
      // FIGHT's lettering on its filled Unova plate is the colour's dark; the other Unova plates' is the colour.
      const uint32_t ink=!unovan?white:i==0?0x601018:style.actions[i].face;
      command_label(canvas,card,labels[i],enabled?ink:0xa0a0a0,
                    platinum?style.actions[i].shadow:unovan&&i==0?PD_NO_SHADOW:outline);
    }
    if(i==focus)selected=card;
  }
  if(moves){
    const Rect back{end+gap,top,right-end-gap,height};
    if(platinum)bevel(canvas,back,style.actions[3],true);
    else if(unovan)unova_command_plate(canvas,back,style.look,style.actions[3],true,false);
    else family_plate(canvas,back,style.actions[3].face,true,style.actions[3].dark);
    const int x=back.x+back.w/2,y=back.y+back.h/2;
    if(focus==4)selected=back;
    const uint32_t arrow=unovan?style.actions[3].face:white;
    pd_triangle(&canvas,x-13,y-9,18,PD_LEFT,arrow);
    pd_fill(&canvas,x-2,y-3,16,6,arrow);
    pd_fill(&canvas,x+8,y-11,6,11,arrow);
  }
  if(!selected.w)return;
  const int hexCut=moves&&unovan&&focus<4?hex_cut(style.look):0;
  if(pressed){
    // The tap is in flight: the plate dips and the brackets close onto it.
    press(canvas,selected);
    corners(canvas,selected,0,hexCut);
  }else if(active)corners(canvas,selected,ds_battle_pulse(frame)?4:2,hexCut);
}
