// GPL-3.0. Platinum inventory adapter for the established ItemsScreen renderer.
#include "bag_view.h"
#include "browser.h"
#include "companion_style.h"
extern "C" {
#include "pd_games.h"
}
extern "C" {
#include "pd_ui.h"
}
namespace {
// Android's PlatinumBag, HeartGoldBag and BlackBag.
constexpr pd_bag_palette platinum{
  {{0x717992,2},{0xBAD3DB,1},{0xDBEBEB,2}},{{0x51619A,1},{0xA2AAF3,1}},
  3,2,2,0xFBFBFB,0,0x101820,0xAABABA,0x101820,0xAABABA,0xF3AA51,
  0x7151F3,0xFBFBFB,0x303030,0,0
};
constexpr pd_bag_palette johto{
  {{0x515151,2},{0xBABABA,2},{0xDBDBDB,1}},{{0x000000,1},{0x717179,1}},
  3,2,2,0xFBFBFB,0xDBDBDB,0x515151,0xDBDBDB,0x515151,0xDBDBDB,0xE30000,
  0x92929A,0xFBFBFB,0x515959,0,0
};
constexpr pd_bag_palette unova{
  {{0x282828,1},{0xB2B2B2,1},{0x282828,1},{0x616161,2}},{{0x004192,1},{0x1871E3,1}},
  4,3,2,0x303030,0x2069D3,0xFBFBFB,0x616161,0xFBFBFB,0x282828,0x088AFB,
  0x0049A2,0xFBFBFB,0x7992AA,0,0
};
// The same sampled palettes remapped with Android NightColors.
constexpr pd_bag_palette platinumNight{
  {{0x292A2C,2},{0x30383A,1},{0x343E3E,2}},{{0x25262C,1},{0x292B42,1}},
  3,2,2,0x3D3D3D,0,0xE4E8EC,0x414646,0xE4E8EC,0x414646,0xF3AA51,
  0x28233C,0xB6B6B6,0x303030,0,0
};
constexpr pd_bag_palette johtoNight{
  {{0x232323,2},{0x333333,2},{0x383838,1}},{{0x171717,1},{0x282829,1}},
  3,2,2,0x3D3D3D,0x4A4A4A,0xDBDBDB,0x4A4A4A,0xDBDBDB,0x4A4A4A,0xE30000,
  0x2D2D2E,0xB6B6B6,0x353636,0,0
};
constexpr pd_bag_palette unovaNight{
  {{0x1D1D1D,1},{0x323232,1},{0x1D1D1D,1},{0x252525,2}},{{0x18212C,1},{0x202934,1}},
  4,3,2,0x1E1E1E,0x2E3948,0xB6B6B6,0x373737,0xB6B6B6,0x2F2F2F,0x088AFB,
  0x19222E,0xB6B6B6,0x3A3F43,0,0
};
}
DsBagPockets ds_bag_view(pd_canvas* c,const DsCompanion& s,int pocket,int selected,bool details,int sort,int tabKey){
  pd_bag_source source{};source.ctx=const_cast<DsCompanion*>(&s);source.tabs=ds_bag_tabs(s.game);source.count=int(s.bag.size());
  source.pocketName=[](void* data,int tab){
    const auto game=static_cast<DsCompanion*>(data)->game;
    static const char* names[]={"ALL","ITEMS","KEY ITEMS","TMs/HMs","MAIL","MEDICINE","BERRIES","BALLS","BATTLE"};
    if(tab<0||tab>=ds_bag_tabs(game))return "";
    return names[ds_unova(game)&&tab>=4?tab+1:tab];
  };
  source.item=[](void* data,int row,int* pocket,pd_item* out,bool* quantity){
    const auto& bag=static_cast<DsCompanion*>(data)->bag;
    if(row<0||size_t(row)>=bag.size())return false;
    const auto& item=bag[row];
    *pocket=item.pocket+1;out->id=item.id;out->quantity=item.count;*quantity=item.pocket!=1;
    return true;
  };
  source.name=[](void* data,int id){return ds_item(id,static_cast<DsCompanion*>(data)->game);};
  pd_snapshot snapshot{};snapshot.bagOk=s.valid;
  pd_ui ui{};ui.bagSource=&source;ui.bagPocket=pocket;ui.bagSelected=selected;
  ui.bagDetails=details;ui.bagSort=sort;ui.bagTabs=tabKey>=0;ui.bagTabKey=tabKey;ui.shoulderTabs=true;ui.pressedId=-1;ui.focusId=-1;pd_game game{};
  if(ds_unova(s.game))ui.bagPalette=pd_dark_look?&unovaNight:&unova;
  else if(s.game==DsGame::Johto)ui.bagPalette=pd_dark_look?&johtoNight:&johto;
  else ui.bagPalette=pd_dark_look?&platinumNight:&platinum;
  ui.keepBackdrop=true;
  ds_companion_backdrop(c,s.game,PD_TAB_BAG,pocket);
  pd_ui_bag_view(&ui,c,&game,&snapshot);
  return {ui.bagPocket,ui.bagPocketMask};
}
