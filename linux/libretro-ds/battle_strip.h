// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>
#include "pk_nds_field.h"

struct DsCompanion;
struct pd_snapshot;

enum class DsBattlePanel { Native, Actions, Moves };
enum class DsBattleLook { Platinum, HeartGold, SoulSilver, Unova, Unova2 };
struct DsBattleView {
  DsBattlePanel panel=DsBattlePanel::Native;
  bool trainer=false;
  bool gen5=false;
  int species=0;
  uint32_t personality=0;
  std::array<int,4> moves{},pp{};
  std::array<int,4> maxPp{}; // 0 = not known (no base-PP table for this cart): the card shows PP alone
  bool enabled(int slot) const;
};

// Each move's base PP from the cart's own move table (ds_move_base_pp), for maxPp. An empty table: no maxPp.
void ds_battle_set_base_pp(std::vector<uint8_t> table);
// Call only after the exact supported ROM identity has been established.
DsBattleView ds_read_battle(const PkNdsFieldGame*,uint32_t (*read32)(uint32_t),int scene);
void ds_read_battle_companion(const PkNdsFieldGame*,uint32_t (*read32)(uint32_t),
                              const uint8_t* ram,size_t ramSize,const DsCompanion&,pd_snapshot&);
const char* ds_battle_species(const DsBattleView&);

struct DsBattleTap { bool down=false; int x=0,y=0; };
struct DsBattleStrip {
  DsBattlePanel panel=DsBattlePanel::Native;
  int actionFocus=0,moveFocus=0,tapFrames=0;
  int moveReadyFrames=0;
  int moveCommitFrames=0;
  bool cancelingMove=false;
  DsBattleTap tap{};
  void select(const DsBattleView&,int dx,int dy,bool accept,bool back);
  DsBattleTap tick();
};

// Keep theme selection separate from ROM telemetry and touch coordinates.
struct DsBattleColors { uint32_t face,dark,mid,light,lit,shadow; };
struct DsBattleStyle {
  int bandTop,textTop;
  std::array<DsBattleColors,4> actions;
  DsBattleLook look=DsBattleLook::Platinum;
};
extern const DsBattleStyle ds_platinum_battle_style;
const DsBattleStyle* ds_battle_style(const char* sha1);
bool ds_platinum_has_command_art(const uint32_t* lower);
bool ds_platinum_has_move_art(const uint32_t* lower,const DsBattleView& view);
inline uint16_t ds_rgb565(uint32_t p) {
  return ((p>>8)&0xf800)|((p>>5)&0x7e0)|((p>>3)&31);
}
void ds_draw_battle_strip(uint32_t* stage,const DsBattleStyle&,const DsBattleView&,
                          const std::string& nickname,int focus,bool active,unsigned frame,
                          const uint32_t* panelArt=nullptr);
