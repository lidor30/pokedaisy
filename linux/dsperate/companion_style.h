// GPL-3.0. DS tab backgrounds sampled by Android's DsBackdrops.kt and Theme.kt.
#pragma once

#include "companion.h"
extern "C" {
#include "pd_style.h"
#include "pd_ui.h"
}

inline uint32_t ds_type_color(int type){
  // The type IDs and colors already used by the DS battle strip.
  constexpr uint32_t colors[]={0xa8a878,0xc03028,0xa890f0,0xa040a0,0xe0c068,0xb8a038,
    0xa8b820,0x705898,0xb8b8d0,0x68a090,0xf08030,0x6890f0,0x78c850,0xf8d030,
    0xf85888,0x98d8d8,0x7038f8,0x705848};
  return type>=0 && unsigned(type)<sizeof(colors)/sizeof(colors[0])?colors[type]:0x8a8a8a;
}

// Android composites its night scrim over the game pattern before drawing windows or art.
inline uint32_t ds_backdrop_color(uint32_t rgb){
  if(!pd_dark_look)return rgb;
  constexpr uint32_t night=0x0A0D14;
  uint32_t result=0;
  for(int shift=0;shift<=16;shift+=8)
    result|=((((rgb>>shift)&255)*45+((night>>shift)&255)*55+50)/100)<<shift;
  return result;
}

inline void ds_companion_backdrop(pd_canvas* c,DsGame game,pd_tab tab,int pocket=0){
  auto fill=[&](int x,int y,int w,int h,uint32_t color){pd_fill(c,x,y,w,h,ds_backdrop_color(color));};
  if(tab==PD_TAB_BAG){
    if(game==DsGame::Johto){
      constexpr uint32_t colors[]={0xDB82AA,0xEB8251,0xE3A238,0x92C330,0x30BA59,0x18AAD3,0x4982F3,0xA259EB};
      fill(0,0,c->w,c->h,colors[pocket>=1 && pocket<=8?pocket-1:0]);
    }else if(ds_unova(game)){
      fill(0,0,c->w,c->h,0x2069D3);
      for(int y=3;y<c->h;y+=8)for(int x=3;x<c->w;x+=8){
        fill(x-1,y,3,1,0x00519A);
        fill(x,y-1,1,3,0x00519A);
      }
    }else{
      for(int y=0;y<c->h;y+=2)fill(0,y,c->w,2,y%4?0xEBC369:0xEBCB79);
    }
    return;
  }
  if(tab==PD_TAB_DEX && (game==DsGame::Johto || ds_unova(game))){
    const bool johto=game==DsGame::Johto,sequel=ds_unova_sequel(game);
    const int step=johto?40:8;
    const uint32_t base=johto?0xFBFBFB:sequel?0xBA2010:0x08B279;
    const uint32_t line=johto?0xAAAAAA:sequel?0x821808:0x008261;
    fill(0,0,c->w,c->h,base);
    for(int y=0;y<c->h;y+=step)fill(0,y,c->w,1,line);
    for(int x=0;x<c->w;x+=step)fill(x,0,1,c->h,line);
    return;
  }
  if(ds_unova(game)){
    fill(0,0,c->w,c->h,0x101820);
    for(int y=0;y<c->h;y+=32){
      fill(0,y+31,c->w,2,0x202020);
      fill(0,y,c->w,1,0x414141);
    }
    for(int x=0;x<c->w;x+=32){
      fill(x+31,0,2,c->h,0x202020);
      fill(x,0,1,c->h,0x414141);
    }
  }else if(game==DsGame::Platinum){
    for(int y=0;y<c->h;++y)fill(0,y,c->w,1,y%2?0x798292:0x383849);
  }else if(game==DsGame::Johto){
    for(int y=0;y<c->h;y+=4)fill(0,y,c->w,4,y%8?0x69CBCB:0x8ADBDB);
  }else pd_backdrop(c);
}
