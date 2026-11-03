// GPL-3.0. Experimental live Gen 4 reader, adapted from Gen4Reader.kt.
#pragma once
#include <cstddef>
#include <cstdint>
#include <array>
#include <vector>
#include <string>

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
  bool valid=false, dexValid=false, nationalDex=false;
  std::vector<uint8_t> dexSeen, dexCaught;
  int seenCount=0, caughtCount=0;
  int mapX=-1,mapY=-1,playerGender=0;
  uint64_t arrivals=0;
  int money=0, badges=0, map=0;
  std::string trainer;
  std::vector<DsMon> party;
  std::vector<DsItem> bag;
};
bool ds_decode_mon(const uint8_t* raw, size_t size, DsMon& mon);
DsCompanion ds_read_companion(const uint8_t* ram, size_t size);
const char* ds_species(int id);
const char* ds_move(int id);
const char* ds_item(int id);
const char* ds_place(int id);

const char* ds_nature(int id);
const char* ds_status(const DsMon& mon);
