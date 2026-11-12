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

// Dex metadata and the compressed archives. Decode art and moves only for the open entry.
struct DsDexDetails {
  std::array<uint8_t,6> stats{}; // cartridge order: HP, ATK, DEF, SPE, SPA, SPD
  uint32_t heightDm=0,weightHg=0;
  int growth=-1;
  std::string category,description;
  std::array<uint8_t,13> machineBits{};
  bool hasMachines=false;
};
struct DsDexMove { int level,move; };
struct DsDexMachine { bool hm; int number,move; };
struct DsDexPage {
  std::vector<uint32_t> sprite;
  std::vector<DsDexMove> moves;
  std::vector<DsDexMachine> machines;
  int side=0;
};
struct DsDexEvolution { int from,to,method,param; };
struct DsDexArt {
  std::vector<DsDexDetails> details;
  std::vector<int> regional; // species by displayed regional number (Gen 5 starts at zero)
  std::vector<uint8_t> pictures,learnsets;
  std::vector<DsDexEvolution> evolutions;
};
DsDexArt ds_dex_art(const std::vector<uint8_t>&,DsGame);
DsDexPage ds_dex_page(const DsDexArt&,DsGame,int species);
std::vector<DsDexMove> ds_level_moves(const DsDexArt&,DsGame,int species);
std::vector<DsDexEvolution> ds_dex_evolutions(const DsDexArt&,int species);

std::vector<std::vector<uint32_t>> ds_hud_item_icons(const std::vector<uint8_t>& rom, DsGame game);
// Each move's base PP from the cart's move table (empty when it doesn't check out): the battle strip's max PP.
std::vector<uint8_t> ds_move_base_pp(const std::vector<uint8_t>& rom, DsGame game);
