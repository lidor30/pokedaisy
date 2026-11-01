// GPL-3.0. DS layouts use physical output pixels, unlike the launcher's canvas.
#pragma once
#include <SDL.h>
inline bool ds_display_pixels(SDL_Renderer* renderer,int& width,int& height){
  // The ROM picker calls SDL_RenderSetLogicalSize and enables integer scaling.
  // Reset both before using output-pixel coordinates, including after resizes.
  return SDL_RenderSetLogicalSize(renderer,0,0)==0 &&
         SDL_RenderSetIntegerScale(renderer,SDL_FALSE)==0 &&
         SDL_RenderSetScale(renderer,1.0f,1.0f)==0 &&
         SDL_RenderSetViewport(renderer,nullptr)==0 &&
         SDL_GetRendererOutputSize(renderer,&width,&height)==0 && width>0 && height>0;
}
