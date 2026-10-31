// GPL-3.0. Reproduce launcher scaling before drawing a DS frame or companion.
#include "display.h"
#include <cassert>
#include <cstdio>
#include <initializer_list>
int main(){
  assert(SDL_Init(0)==0);
  for(auto size:{SDL_Point{1024,768},SDL_Point{1280,720},SDL_Point{640,480}}){
    auto* surface=SDL_CreateRGBSurfaceWithFormat(0,size.x,size.y,32,SDL_PIXELFORMAT_ARGB8888);
    assert(surface);auto* renderer=SDL_CreateSoftwareRenderer(surface);assert(renderer);
    for(auto canvas:{SDL_Point{341,256},SDL_Point{256,192}}){
      assert(SDL_RenderSetLogicalSize(renderer,canvas.x,canvas.y)==0);
      assert(SDL_RenderSetIntegerScale(renderer,SDL_TRUE)==0);
      int w=0,h=0;assert(ds_display_pixels(renderer,w,h));assert(w==size.x && h==size.y);
      int lw=-1,lh=-1;SDL_RenderGetLogicalSize(renderer,&lw,&lh);assert(lw==0 && lh==0);
      float sx=0,sy=0;SDL_RenderGetScale(renderer,&sx,&sy);assert(sx==1 && sy==1);
      SDL_Rect viewport;SDL_RenderGetViewport(renderer,&viewport);
      assert(viewport.x==0 && viewport.y==0 && viewport.w==w && viewport.h==h);
      SDL_SetRenderDrawColor(renderer,0,0,0,255);assert(SDL_RenderClear(renderer)==0);
      SDL_SetRenderDrawColor(renderer,255,0,0,255);
      // Opposite physical corners must both remain visible after the picker.
      assert(SDL_RenderDrawPoint(renderer,0,0)==0);
      assert(SDL_RenderDrawPoint(renderer,w-1,h-1)==0);
      SDL_RenderPresent(renderer);
      auto* pixels=static_cast<Uint32*>(surface->pixels);
      assert(pixels[0]==0xffff0000u);
      assert(pixels[(h-1)*(surface->pitch/4)+w-1]==0xffff0000u);
    }
    SDL_DestroyRenderer(renderer);SDL_FreeSurface(surface);
  }
  SDL_Quit();std::puts("PASS DS output pixels after launcher logical/integer scaling at three display sizes");
}
