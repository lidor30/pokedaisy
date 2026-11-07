// GPL-3.0. DS adapters hosted inside the portable PokéDaisy companion shell.
#pragma once
#include "companion.h"
#include "map_art.h"
#include <string>
extern "C" {
#include "pd_settings.h"
}
struct DsCompanionUi {
  enum Row { Layout, Battles, Hud, CompanionButton, ScreenButton, PenSpeed, QuickStyle, Reset, BattleStrip, HudMinimal, HudBalls, HudRepel, HudMap, Rows };
  pd_settings settings{};
  pd_ui frame{}, options{};
  unsigned displayRevision=0;
  int layout=0, autoBattles=1, companionButton=0, screenButton=1, penSpeed=1;
  bool battleStrip=true,hudMinimal=false;
  int selected=0,page=0,scroll=0,pocket=0,filter=0,mapX=0,mapY=0;
  bool summary=false,details=false;
  int setting=0,picker=-1,choice=0;
  std::string path,error;
  void init(const std::string& directory,int legacyLayout=0,bool legacyHud=false);
  void set_game(DsGame game);
  bool save();
  void draw(pd_canvas*,const DsCompanion&,bool,const DsMapArt&,const std::vector<std::vector<uint32_t>>&);
  bool control(unsigned pressed); // true closes the companion
  unsigned companion_id() const;
  int screen_id() const;
  int pen_step() const;
private:
  const DsCompanion* snapshot=nullptr;
  const DsMapArt* art=nullptr;
  const std::vector<std::vector<uint32_t>>* icons=nullptr;
  bool supported=false;
  DsGame game=DsGame::Platinum;
  void defaults();
  void draw_body(pd_ui*,pd_canvas*);
  void draw_settings(pd_canvas*);
  int value(int row) const;
  void apply(int row,int value);
};
