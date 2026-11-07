// GPL-3.0. Screen policy only; the game owns all battle menus and actions.
#pragma once
struct DsBattleView { bool bottom=false; };
inline DsBattleView ds_battle_view(int mode,bool battle,bool selecting,bool normalBottom,int overrideScreen=-1){
  if(!mode || !battle)return {normalBottom};
  if(overrideScreen>=0)return {overrideScreen==1};
  return {selecting};
}
