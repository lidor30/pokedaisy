#pragma once
#include "companion.h"
extern "C" {
#include "pd_canvas.h"
}
void ds_party_view(pd_canvas*,const DsCompanion&,const std::vector<std::vector<uint32_t>>&,int,bool,int);
