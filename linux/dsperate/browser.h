// GPL-3.0. Bounds shared by Platinum companion input and list rendering.
#pragma once
#include "companion.h"
#include <algorithm>
inline int ds_bag_tabs(DsGame game){return ds_unova(game)?6:9;}
inline bool ds_in_pocket(const DsItem& item,int pocket){return pocket==0 || item.pocket==pocket-1;}
inline int ds_bag_count(const DsCompanion& s,int pocket){
  int count=0;for(const auto& item:s.bag)count+=ds_in_pocket(item,pocket);return count;
}
inline bool ds_dex_visible(const DsCompanion& s,size_t n,int filter){
  return n>0 && n<s.dexSeen.size() && (!filter || (filter==1?s.dexSeen[n]:n<s.dexCaught.size() && s.dexCaught[n]));
}
inline int ds_dex_count(const DsCompanion& s,int filter){
  int count=0;for(size_t n=1;n<s.dexSeen.size();n++)count+=ds_dex_visible(s,n,filter);return count;
}
