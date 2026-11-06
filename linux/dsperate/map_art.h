// GPL-3.0. DS map assets decoded from the owner's cartridge, as NdsArt.kt does.
#pragma once
#include <cstdint>
#include <vector>
#include "companion.h"
struct DsMapArt {
  std::vector<uint32_t> background,base;
  std::vector<std::vector<uint32_t>> blocks,heads;
  std::vector<uint32_t> cursor;
  std::vector<uint8_t> tiles,screen;
  std::vector<std::vector<uint32_t>> skins;
  int width=0,height=0,skin=-1,unlock=-1;
  uint64_t arrivals=0;
};
DsMapArt ds_map_art(const std::vector<uint8_t>& rom,DsGame game=DsGame::Platinum);
void ds_map_update(DsMapArt&,const DsCompanion&);
int ds_map_section(int x,int y);
const char* ds_map_name(int section);

// First 32x32 frame per national species; zero pixels are transparent.
std::vector<std::vector<uint32_t>> ds_party_icons(const std::vector<uint8_t>& rom,DsGame game=DsGame::Platinum);

std::vector<std::vector<uint32_t>> ds_hud_item_icons(const std::vector<uint8_t>& rom, DsGame game);
