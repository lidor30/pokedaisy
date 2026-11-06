// GPL-3.0. National Gen 4/5 data for the established companion dex list.
#include "dex_view.h"
#include "browser.h"
extern "C" {
#include "pd_ui.h"

}
void ds_dex_view(pd_canvas* c,const DsCompanion& s,int filter,int selected){
  struct Context {const DsCompanion& s;std::vector<int> ids;} ctx{s,{}};
  for(size_t n=1;n<s.dexSeen.size();n++)if(ds_dex_visible(s,n,filter))ctx.ids.push_back(int(n));
  pd_dex_source source{};source.ctx=&ctx;source.count=int(ctx.ids.size());
  source.seenCount=s.seenCount;source.caughtCount=s.caughtCount;
  source.filterLabel=filter==1?"SEEN":filter==2?"CAUGHT":"ALL";
  source.national=[](void* data,int row){return static_cast<Context*>(data)->ids.at(row);};
  source.seen=[](void* data,int n){const auto& s=static_cast<Context*>(data)->s;return n>0 && size_t(n)<s.dexSeen.size() && s.dexSeen[n]!=0;};
  source.caught=[](void* data,int n){const auto& s=static_cast<Context*>(data)->s;return n>0 && size_t(n)<s.dexCaught.size() && s.dexCaught[n]!=0;};
  source.name=[](void* data,int n){return ds_species(n,static_cast<Context*>(data)->s.game);};
  pd_ui ui{};ui.dexSource=&source;ui.pressedId=-1;
  pd_game game{};pd_snapshot snapshot{};
  pd_ui_dex_view(&ui,c,&game,&snapshot,selected);
}
