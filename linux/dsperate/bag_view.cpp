// GPL-3.0. Platinum inventory adapter for the established ItemsScreen renderer.
#include "bag_view.h"
#include "browser.h"
extern "C" {
#include "pd_ui.h"
}
void ds_bag_view(pd_canvas* c,const DsCompanion& s,int pocket,int selected,bool details){
  pd_bag_source source{};source.ctx=const_cast<DsCompanion*>(&s);source.tabs=9;
  source.pocketName=[](void*,int tab){
    // Gen4Reader.kt's save-pocket order; these are UI labels, not another registry.
    static const char* names[]={"ALL","ITEMS","KEY ITEMS","TMs/HMs","MAIL","MEDICINE","BERRIES","BALLS","BATTLE"};
    return tab>=0 && tab<9?names[tab]:"";
  };
  source.count=[](void* data,int tab){return ds_bag_count(*static_cast<DsCompanion*>(data),tab);};
  source.item=[](void* data,int tab,int row,pd_item* out,bool* quantity){
    if(row<0)return false;
    const auto& bag=static_cast<DsCompanion*>(data)->bag;
    for(const auto& item:bag)if(ds_in_pocket(item,tab)){
      if(row--!=0)continue;
      out->id=item.id;out->quantity=item.count;*quantity=item.pocket!=1;return true;
    }return false;
  };
  source.name=[](void*,int id){return ds_item(id);};
  pd_snapshot snapshot{};snapshot.bagOk=s.valid;
  pd_ui ui{};ui.bagSource=&source;ui.bagPocket=pocket;ui.bagSelected=selected;
  ui.bagDetails=details;ui.pressedId=-1;pd_game game{};
  pd_ui_bag_view(&ui,c,&game,&snapshot);
}
