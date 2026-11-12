// GPL-3.0. DS adapters hosted inside the portable PokéDaisy companion shell.
#pragma once
#include "companion.h"
#include "map_view.h"
#include "dex_view.h"
#include <string>
extern "C" {
#include "pd_settings.h"
}
struct DsCompanionUi {
  enum Row { Layout, Battles, Hud, CompanionButton, ScreenButton, PenSpeed, QuickStyle, Reset, BattleStrip, HudMinimal, HudBalls, HudRepel, HudMap, DarkMode, AutoRun, TabBar, MoreTabs, HudButton, HudSize, Rows };
  pd_settings settings{};
  pd_ui frame{}, options{};
  pd_snapshot battle{};
  unsigned displayRevision=0;
  int layout=0, autoBattles=1, companionButton=0, screenButton=1, penSpeed=1;
  bool battleStrip=true,hudMinimal=false,darkMode=false,autoRun=false;
  int selected=0,page=0,scroll=0,pocket=0,filter=0,mapX=0,mapY=0;
  int learnRow=0, learnLast=0;
  bool summary=false,details=false;
  int bagTabKey=-1; // the D-pad cursor on the bag's pocket tab bar: that tab's pocket id; -1 = on the list
  unsigned bagPockets=1;
  int dexEntry=0,dexRow=0,dexFilterFocus=-1;
  int placeRow=-1,mapAction=-1;
  int guideLoadedPage=-1,guideMap=-1;
  std::vector<GuideRow> guideRows;
  std::vector<DsMapPlace> mapPlaces;
  DsDexArt dexArt;
  int dexMode=-1,dexCachedSpecies=0,dexDetailRow=0;
  DsDexSection dexSection=DsDexSection::Info;
  std::vector<DsDexEvolution> dexEvolutions;
  std::vector<DsGuideSpot> dexSpots;
  bool dexSpotsReady=false;
  DsDexPage dexPage;
  int setting=0,picker=-1,choice=0;
  std::string path,error;
  void init(const std::string& directory,int legacyLayout=0,bool legacyHud=false);
  void set_game(DsGame game,bool soulSilverVersion=false);
  bool save();
  void update_battle();
  void draw(pd_canvas*,const DsCompanion&,bool,const DsMapArt&,const std::vector<std::vector<uint32_t>>&);
  bool control(unsigned pressed); // true closes the companion
  unsigned companion_id() const;
  int hud_id() const;
  int screen_id() const;
  int pen_step() const;
private:
  pd_game presentation{};
  pd_battle_source battleSource{};
  const DsCompanion* snapshot=nullptr;
  const DsMapArt* art=nullptr;
  const std::vector<std::vector<uint32_t>>* icons=nullptr;
  bool supported=false;
  bool soulSilver=false;
  DsGame game=DsGame::Platinum;
  const std::vector<int>* dex_order() const;
  void defaults();
  void draw_body(pd_ui*,pd_canvas*);
  void draw_settings(pd_canvas*);
  int value(int row) const;
  void apply(int row,int value);
};
