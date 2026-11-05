// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstdint>
#include <string>
#include "pk_nds_field.h"

enum class DsBattlePanel { Native, Actions, Moves };
struct DsBattleView {
  DsBattlePanel panel=DsBattlePanel::Native;
  bool trainer=false;
  int species=0;
  uint32_t personality=0;
  std::array<int,4> moves{},pp{};
  bool enabled(int slot) const;
};

// Call only after the exact Platinum ROM identity has been established.
DsBattleView ds_read_platinum_battle(const PkNdsFieldGame*,uint32_t (*read32)(uint32_t),int scene);

struct DsBattleTap { bool down=false; int x=0,y=0; };
struct DsBattleStrip {
  DsBattlePanel panel=DsBattlePanel::Native;
  int actionFocus=0,moveFocus=0,tapFrames=0;
  DsBattleTap tap{};
  void select(const DsBattleView&,int dx,int dy,bool accept,bool back);
  DsBattleTap tick();
};

// Keep theme selection separate from ROM telemetry and touch coordinates.
// Only Platinum is instantiated until the other ROM readers are qualified.
struct DsBattleColors { uint32_t face,dark,mid,light,lit,shadow; };
struct DsBattleStyle {
  int bandTop,textTop;
  std::array<DsBattleColors,4> actions;
};
extern const DsBattleStyle ds_platinum_battle_style;
bool ds_platinum_has_command_art(const uint32_t* lower);
bool ds_platinum_has_move_art(const uint32_t* lower,const DsBattleView& view);
void ds_draw_battle_strip(uint32_t* stage,const DsBattleStyle&,const DsBattleView&,
                          const std::string& nickname,int focus,bool active,unsigned frame,
                          const uint32_t* panelArt=nullptr);
