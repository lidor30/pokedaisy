// GPL-3.0. Live Gen 4/5 reader adapted from the Android companion.
#pragma once
#include <cstddef>
#include <cstdint>
#include <array>
#include <vector>
#include <string>

enum class DsGame { Unsupported, Platinum, Johto, Black, White, Black2, White2 };
bool ds_unova(DsGame game);
bool ds_unova_sequel(DsGame game);
DsGame ds_companion_game(const char* sha1);
int ds_egg_icon(DsGame game);
struct DsMon {
  int species=0, level=0, hp=0, maxHp=0, heldItem=0;
  uint32_t personality=0, experience=0, status=0;
  bool egg=false;
  int gender=0, nature=0;
  std::array<int,4> moves{}, pp{};
  std::string nickname;
};
struct DsItem { int id, count, pocket; };
struct DsCompanion {
  DsGame game=DsGame::Platinum;
  bool valid=false, dexValid=false, nationalDex=false;
  std::vector<uint8_t> dexSeen, dexCaught;
  int seenCount=0, caughtCount=0;
  int mapX=-1,mapY=-1,mapSkin=0,mapUnlock=0,playerGender=0;
  uint64_t arrivals=0;
  int repelSteps=0, money=0, badges=0, map=0;
  std::string trainer;
  std::vector<DsMon> party;
  std::vector<DsItem> bag;
};
bool ds_decode_mon(const uint8_t* raw, size_t size, DsMon& mon);
DsCompanion ds_read_companion(const uint8_t* ram, size_t size,DsGame game=DsGame::Platinum);
const char* ds_species(int id,DsGame game=DsGame::Platinum);
const char* ds_move(int id,DsGame game=DsGame::Platinum);
const char* ds_item(int id,DsGame game=DsGame::Platinum);
const char* ds_place(int id,DsGame game=DsGame::Platinum);

const char* ds_nature(int id);
const char* ds_status(const DsMon& mon);

int ds_move_type(int id,DsGame game=DsGame::Platinum);
int ds_move_power(int id);
int ds_species_type(int id,int slot,DsGame game=DsGame::Platinum);

enum class DsRepelResult { Used, NotAvailable, StillActive, NoneLeft };
DsRepelResult ds_use_repel(const uint8_t* ram,size_t size,DsGame game,int item,
                         void (*write8)(uint32_t,uint8_t));
