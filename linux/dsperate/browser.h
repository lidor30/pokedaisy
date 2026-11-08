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
  if(!n || n>=s.dexSeen.size())return false;
  const bool seen=s.dexSeen[n],caught=n<s.dexCaught.size() && s.dexCaught[n];
  switch(filter){
    case 1:return caught;
    case 2:return seen && !caught;
    case 3:return !seen;
    default:return true;
  }
}
struct DsDexRow { int species,number; };
inline std::vector<DsDexRow> ds_dex_rows(const DsCompanion& s,int filter,const std::vector<int>* regional){
  std::vector<DsDexRow> rows;
  const size_t size=regional?regional->size():s.dexSeen.size();
  rows.reserve(size);
  for(size_t number=0;number<size;++number){
    const int species=regional?(*regional)[number]:int(number);
    if(ds_dex_visible(s,species,filter))rows.push_back({species,int(number)});
  }
  return rows;
}
