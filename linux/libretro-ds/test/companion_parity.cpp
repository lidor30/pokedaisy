#include "companion_ui.h"
#include "browser.h"
#include "guide_view.h"
#include "layout.h"
#include "libretro.h"
#include <algorithm>
#include <cassert>
#include <array>
#include <fstream>
#include <cstdio>
#include <vector>
int main(int argc,char**argv) {
  assert(argc==4 || argc==7);
  using G=DsGame;
  const int field=PK_NDS_FIELD_KNOWN|PK_NDS_FIELD_SCREEN;
  assert(ds_auto_run(field,true,false));
  assert(!ds_auto_run(field,false,false) && !ds_auto_run(field,true,true));
  assert(!ds_auto_run(PK_NDS_FIELD_KNOWN,true,false));
  for(int blocker:{PK_NDS_FIELD_LOCKED,PK_NDS_FIELD_BATTLE,PK_NDS_FIELD_MENU,
                   PK_NDS_FIELD_BIKE,PK_NDS_FIELD_SURF,PK_NDS_FIELD_TOUCH})
    assert(!ds_auto_run(field|blocker,true,false));
  assert(ds_fly_spots(G::Platinum).front().header==411);
  for(G game:{G::Platinum,G::Johto,G::Black,G::White,G::Black2,G::White2})
    for(int month=1;month<=12;++month)assert(ds_wild_rates(game,month,false).count>0);
  assert(ds_wild_rates(G::Johto,1,true).count>0);
  assert(ds_fly_spots(G::Black).front().flag==2480);
  assert(ds_fly_spots(G::White2).front().flag==2472);
  DsCompanion johtoField;
  johtoField.game=G::Johto;johtoField.mapX=14;johtoField.mapY=10;johtoField.eventFlags.resize(512);
  const auto& kanto=ds_fly_spots(G::Johto).front();
  johtoField.eventFlags[kanto.flag/8]|=1u<<(kanto.flag%8);
  assert(!ds_fly_unlocked(johtoField,kanto));
  johtoField.mapX=28;johtoField.mapY=8;
  assert(ds_fly_unlocked(johtoField,kanto));
  DsCompanion area;
  area.game=G::Platinum;area.map=3;
  const auto items=ds_guide_rows(area,GuidePage::Here);
  assert(!items.empty() && items.front().heading && items.front().title=="TO DO");
  assert(std::any_of(items.begin(),items.end(),[](const GuideRow& row){
    return row.title=="OLD ROD" && row.detail=="TO DO";
  }));
  assert(!ds_guide_rows(area,GuidePage::Tips).empty() && !ds_guide_rows(area,GuidePage::Stuck).empty());
  assert(ds_guide_page_count(G::Black2)==4 && ds_guide_page_count(G::Platinum)==5);
  assert(ds_move_power(33,G::Platinum)==35 && ds_move_power(33,G::Black2)==50);
  const auto hidden=std::find_if(items.begin(),items.end(),[](const GuideRow& row){
    return row.title=="HIDDEN ITEM" && row.answer==ds_item(90,G::Platinum);
  });
  assert(hidden!=items.end() && !hidden->hint.empty() && !hidden->answer.empty());
  area.eventFlags.resize(112);
  area.eventFlags[891/8]=1u<<(891%8);
  const auto collected=ds_guide_rows(area,GuidePage::Here);
  assert(collected.back().title==ds_item(90,G::Platinum) && collected.back().done);
  const auto platinumTips=ds_guide_rows(area,GuidePage::WhereIs);
  assert(platinumTips.front().title=="HM01 CUT" && platinumTips.front().hint=="ETERNA CITY.");
  area.game=G::Johto;
  const auto ssTips=ds_guide_rows(area,GuidePage::WhereIs,true);
  assert(std::any_of(ssTips.begin(),ssTips.end(),[](const GuideRow& row){return row.title=="SILVER WING";}));
  area.game=G::Black2;
  area.bag={{421,1,0}};
  const auto sequelTips=ds_guide_rows(area,GuidePage::WhereIs);
  assert(sequelTips[1].title=="HM02 FLY" && sequelTips[1].hint=="ROUTE 5." && sequelTips[1].done);
  DsCompanion progress;
  auto bosses=[&](){return ds_guide_rows(progress,GuidePage::NextBoss);};
  auto setFlag=[&](int flag){progress.eventFlags[flag/8]|=1u<<(flag%8);};
  assert(bosses().empty());
  progress.eventFlags.resize(383);
  auto upcoming=bosses();
  assert(upcoming[0].title=="NEXT: LEADER ROARK" && upcoming[0].heading);
  assert(upcoming[1].title=="Pokémon 1" && upcoming[1].detail=="Lv12" &&
         upcoming[1].hint.find("Rock")!=std::string::npos && upcoming[1].answer.find("Stealth Rock")!=std::string::npos);
  progress.badges=3;
  assert(bosses().front().title=="NEXT: LEADER FANTINA");
  progress.badges=255;setFlag(1621);
  assert(bosses().front().title=="NEXT: ELITE FOUR BERTHA");
  setFlag(2404);
  assert(bosses().empty());
  progress.game=G::Johto;progress.badges=0xff|(1<<10);
  assert(bosses().front().title=="NEXT: LEADER SABRINA");
  progress.game=G::White;progress.badges=0x7f;
  assert(bosses().front().title=="NEXT: LEADER IRIS");
  progress.badges=0xff;
  for(int flag=0x969;flag<=0x96c;++flag)setFlag(flag);
  assert(bosses()[1].answer.find("Reshiram")!=std::string::npos);
  progress.game=G::Black;
  assert(bosses()[1].answer.find("Zekrom")!=std::string::npos);
  progress.dexSeen.resize(2);progress.dexSeen[1]=1;
  assert(bosses().empty());
  progress.game=G::Black2;progress.badges=0;
  upcoming=bosses();
  assert(upcoming[0].title.find("(NORMAL)")!=std::string::npos);
  assert(upcoming[3].title.find("(CHALLENGE)")!=std::string::npos);
  assert(upcoming[0].detail.find("ASPERTIA CITY GYM - 2 POKéMON")!=std::string::npos);
  progress.badges=0xff;progress.eventFlags.assign(383,0);setFlag(0x375);
  assert(bosses()[1].detail=="Lv56");
  setFlag(0x960);
  assert(bosses()[1].detail=="Lv72");
  const auto hgSpots=ds_guide_spots(G::Johto,10);
  const auto ssSpots=ds_guide_spots(G::Johto,10,true);
  auto route30=[](const DsGuideSpot& spot){
    return spot.map==10 && spot.encounter.chance==31 && (spot.variants&2);
  };
  assert(std::any_of(hgSpots.begin(),hgSpots.end(),route30));
  assert(std::none_of(ssSpots.begin(),ssSpots.end(),route30));
  DsDexArt personal;
  personal.details.resize(2);
  personal.details[1].hasMachines=true;
  personal.details[1].machineBits[0]=1;    // TM01
  personal.details[1].machineBits[12]=1;   // HM05 in Gen 4, HM02 in Gen 5
  const auto platinum=ds_dex_page(personal,G::Platinum,1).machines;
  const auto johto=ds_dex_page(personal,G::Johto,1).machines;
  const auto unova=ds_dex_page(personal,G::Black2,1).machines;
  assert(platinum.size()==2 && platinum[0].number==1 && platinum[0].move==264);
  assert(platinum[1].hm && platinum[1].number==5 && platinum[1].move==432);
  assert(johto.size()==2 && johto[1].move==250);
  assert(unova.size()==2 && unova[0].move==468 && unova[1].hm && unova[1].number==2 && unova[1].move==19);
  struct Case { G game; int money,items; std::vector<int> species; };
  const Case cases[]={
    {G::Johto,593506,0,{154,181,197,164,195,20}},
    {G::Johto,575648,0,{157,196,18,62,162,20}},
    {G::Black,684260,185,{381,245,639,521,612}},
    {G::White,684260,185,{381,245,639,521,612}},
    {G::Black2,656841,50,{500,571,547,520,55,505}},
    {G::White2,544628,49,{497,227,579,505,127,184}},
  };
  for(int i=0;i<argc-1;i++) {
    std::ifstream f(argv[i+1],std::ios::binary);
    std::vector<uint8_t> ram(4*1024*1024);
    assert(f.read(reinterpret_cast<char*>(ram.data()),ram.size()));
    const auto& expect=cases[i];
    const auto s=ds_read_companion(ram.data(),ram.size(),expect.game);
    printf("%s: valid=%d team=%zu money=%d bag=%zu first=%s %d/%d\n",argv[i+1],s.valid,s.party.size(),s.money,s.bag.size(),s.party.empty()?"":s.party[0].nickname.c_str(),s.party.empty()?0:s.party[0].hp,s.party.empty()?0:s.party[0].maxHp);
    fflush(stdout);
    assert(s.valid && s.party.size()==expect.species.size() && s.money==expect.money);
    for(size_t n=0;n<s.party.size();n++) assert(s.party[n].species==expect.species[n] && s.party[n].maxHp>0);
    assert(expect.items ? s.bag.size()==size_t(expect.items) : !s.bag.empty());
    if(i==0) {
      assert(s.party[0].nickname=="HDChipCard" && s.party[0].hp==321 && s.party[1].personality==0x83DE92E4);
    }
    if(i==1) assert(s.party[1].hp==276);
    if(i==2 || i==3) {
      const auto& latios=s.party.front();
      assert((latios.moves==std::array<int,4>{406,473,58,85}));
      assert(latios.nature==10 && latios.friendship==4);
      assert((latios.stats==std::array<int,6>{302,194,196,350,359,256}));
      assert((latios.ivs==std::array<int,6>{31,31,31,31,31,31}));
      assert((latios.evs==std::array<int,6>{4,0,0,252,252,0}));
      assert(s.bag[0].id==575 && s.bag[0].count==155);
    }
    if(i==4) assert((s.party[0].moves==std::array<int,4>{394,372,457,535}));

    assert(s.dexValid && s.caughtCount>0 && s.seenCount>=s.caughtCount);
    if(i==3)assert(s.caughtCount==638);
    if(i==5)assert(s.seenCount==230);
    DsCompanionUi ui;
    ui.init("");
    ui.set_game(expect.game,i==1);
    std::vector<uint32_t> pixels(341*256);
    pd_canvas canvas;
    pd_canvas_init(&canvas,341,256,pixels.data());
    const DsMapArt map;
    const std::vector<std::vector<uint32_t>> icons;
    auto draw=[&]{ui.draw(&canvas,s,true,map,icons);};
    auto press=[&](unsigned button){assert(!ui.control(1u<<button));};
    draw();
    assert(ui.frame.tab==PD_TAB_PARTY);
    press(RETRO_DEVICE_ID_JOYPAD_DOWN);
    assert(ui.selected==2);
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.summary && ui.page==0);
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.summary && ui.page==1);
    press(RETRO_DEVICE_ID_JOYPAD_DOWN);
    assert(ui.summary && ui.page==1 && ui.selected==3);
    press(RETRO_DEVICE_ID_JOYPAD_LEFT);
    assert(ui.summary && ui.page==0);
    press(RETRO_DEVICE_ID_JOYPAD_SELECT);
    assert(ui.page==2);
    press(RETRO_DEVICE_ID_JOYPAD_SELECT);
    assert(ui.page==0);
    draw();
    press(RETRO_DEVICE_ID_JOYPAD_B);
    press(RETRO_DEVICE_ID_JOYPAD_R);
    assert(ui.frame.tab==PD_TAB_BAG);
    draw();
    press(RETRO_DEVICE_ID_JOYPAD_LEFT);
    int lastPocket=0;
    for(const auto& item:s.bag)lastPocket=std::max(lastPocket,item.pocket+1);
    assert(lastPocket>0 && ui.pocket==lastPocket);
    press(RETRO_DEVICE_ID_JOYPAD_RIGHT);
    assert(ui.pocket==0);
    for(int sort:{1,2,0}){
      press(RETRO_DEVICE_ID_JOYPAD_SELECT);
      assert(ui.frame.bagSort==sort && ui.scroll==0);
      draw();
    }
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.details);
    draw();
    press(RETRO_DEVICE_ID_JOYPAD_B);
    press(RETRO_DEVICE_ID_JOYPAD_R);
    assert(ui.frame.tab==PD_TAB_MAP && s.mapX>=0 && s.mapY>=0);
    draw();
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.mapX==s.mapX && ui.mapY==s.mapY);
    press(RETRO_DEVICE_ID_JOYPAD_B);
    assert(ui.mapAction==0 && ui.placeRow<0);
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.placeRow==0 && !ui.mapPlaces.empty());
    draw();
    press(RETRO_DEVICE_ID_JOYPAD_DOWN);
    const auto place=ui.mapPlaces[ui.placeRow].position;
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.placeRow<0 && ui.mapX==place.x && ui.mapY==place.y);
    press(RETRO_DEVICE_ID_JOYPAD_B);
    press(RETRO_DEVICE_ID_JOYPAD_RIGHT);
    assert(ui.mapAction==1 && ui.mapX==place.x && ui.mapY==place.y);
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.mapAction<0 && ui.mapX==s.mapX && ui.mapY==s.mapY);
    press(RETRO_DEVICE_ID_JOYPAD_R);
    assert(ui.frame.tab==PD_TAB_GUIDE);
    draw();
    assert(ui.frame.overlay==PD_OVERLAY_GUIDE_NOTICE);
    press(RETRO_DEVICE_ID_JOYPAD_B);
    assert(ui.frame.tab==PD_TAB_PARTY && !ui.settings.guideNotice);
    for(int p=0;p<3;++p)press(RETRO_DEVICE_ID_JOYPAD_R);
    draw();
    assert(ui.frame.overlay==PD_OVERLAY_GUIDE_NOTICE);
    press(RETRO_DEVICE_ID_JOYPAD_RIGHT);
    press(RETRO_DEVICE_ID_JOYPAD_A);
    draw();
    assert(ui.frame.overlay==PD_OVERLAY_NONE && ui.settings.guideNotice);
    for(int p=0;p<3;++p)press(RETRO_DEVICE_ID_JOYPAD_DOWN);
    assert(ui.frame.guidePage==int(GuidePage::Here));
    press(RETRO_DEVICE_ID_JOYPAD_A);
    draw();
    assert(ui.frame.guidePage==int(GuidePage::WhereIs) && !ui.guideRows.empty());
    press(RETRO_DEVICE_ID_JOYPAD_RIGHT);
    press(RETRO_DEVICE_ID_JOYPAD_A);
    draw();
    assert(ui.frame.guideLevel==1);
    press(RETRO_DEVICE_ID_JOYPAD_A);
    draw();
    assert(ui.frame.guideLevel==2);
    press(RETRO_DEVICE_ID_JOYPAD_B);
    assert(ui.frame.guideOpen<0 && ui.frame.tab==PD_TAB_GUIDE);
    if(ui.guideRows.size()>=14){
      for(int row=0;row<13;++row){press(RETRO_DEVICE_ID_JOYPAD_DOWN);draw();}
      assert(ui.frame.guideRowFocus==13 && ui.frame.guideScroll>0);
    }
    press(RETRO_DEVICE_ID_JOYPAD_LEFT);
    press(RETRO_DEVICE_ID_JOYPAD_UP);
    press(RETRO_DEVICE_ID_JOYPAD_UP);
    press(RETRO_DEVICE_ID_JOYPAD_A);
    draw();
    assert(ui.frame.guidePage==int(GuidePage::NextBoss));
    if(!ui.guideRows.empty()){
      press(RETRO_DEVICE_ID_JOYPAD_RIGHT);
      press(RETRO_DEVICE_ID_JOYPAD_A);
      assert(ui.frame.guideRowFocus==0 && ui.frame.guideLevel==1);
    }
    press(RETRO_DEVICE_ID_JOYPAD_LEFT);
    press(RETRO_DEVICE_ID_JOYPAD_UP);
    press(RETRO_DEVICE_ID_JOYPAD_A);
    draw();
    assert(ui.frame.guidePage==int(GuidePage::Here));
    const int known=expect.game==G::Johto?7:6;
    assert(ds_guide_here(expect.game,known,12,1).count>0);
    assert(ds_guide_here(expect.game,-1,12,1).count==0);
    press(RETRO_DEVICE_ID_JOYPAD_R);
    assert(ui.frame.tab==PD_TAB_DEX);
    draw();
    press(RETRO_DEVICE_ID_JOYPAD_LEFT);
    assert(ui.dexFilterFocus==0);
    press(RETRO_DEVICE_ID_JOYPAD_DOWN);
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.filter==1 && ui.dexFilterFocus==-1);
    const auto caught=ds_dex_rows(s,1,nullptr);
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.dexEntry==caught.at(0).species && ui.dexEntry>0);
    draw();
    press(RETRO_DEVICE_ID_JOYPAD_RIGHT);
    assert(ui.dexEntry==caught.at(1).species && ui.dexRow==1);
    press(RETRO_DEVICE_ID_JOYPAD_B);
    assert(ui.dexEntry==0 && ui.dexRow==1 && ui.filter==1);
    press(RETRO_DEVICE_ID_JOYPAD_SELECT);
    assert(ui.filter==2);
    const auto seen=ds_dex_rows(s,2,nullptr);
    const int seenOnly=seen.empty()?0:seen.front().species;
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.dexEntry==seenOnly && (!seenOnly || !s.dexCaught[seenOnly]));
    if(ui.dexEntry)press(RETRO_DEVICE_ID_JOYPAD_B);
    press(RETRO_DEVICE_ID_JOYPAD_SELECT);
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.filter==3 && ui.dexEntry==0);

    // Inject a small, deliberately reordered regional table to exercise the
    // numbered rows and controller switch without distributing a cartridge.
    const int first=caught.at(0).species,second=caught.at(1).species;
    ui.dexArt.regional=ds_unova(expect.game)?std::vector<int>{second,first}:std::vector<int>{0,second,first};
    ui.dexArt.evolutions={{first,second,4,16},{second,caught.at(2).species,4,32}};
    ui.dexMode=1;
    press(RETRO_DEVICE_ID_JOYPAD_SELECT);
    press(RETRO_DEVICE_ID_JOYPAD_LEFT);
    for(int step=0;step<4;++step)press(RETRO_DEVICE_ID_JOYPAD_DOWN);
    assert(ui.dexFilterFocus==4);
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.dexMode==0 && ui.dexRow==0 && ui.filter==0);
    const auto regional=ds_dex_rows(s,0,&ui.dexArt.regional);
    assert(regional.size()==2 && regional.front().species==second);
    assert(regional.front().number==(ds_unova(expect.game)?0:1));
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.dexEntry==second);
    press(RETRO_DEVICE_ID_JOYPAD_SELECT);
    assert(ui.dexSection==DsDexSection::Evolve);
    draw();
    assert(ui.dexEvolutions.size()==2 && ui.dexEvolutions.front().from==first);
    press(RETRO_DEVICE_ID_JOYPAD_DOWN);
    assert(ui.dexDetailRow==1);
    press(RETRO_DEVICE_ID_JOYPAD_SELECT);
    assert(ui.dexSection==DsDexSection::Area && ui.dexDetailRow==0);
    draw();
    assert(ui.dexSpotsReady);
    press(RETRO_DEVICE_ID_JOYPAD_SELECT);
    assert(ui.dexSection==DsDexSection::Moves && ui.dexDetailRow==0);
    draw();
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.dexDetailRow==int(ui.dexPage.moves.size())+1);
    press(RETRO_DEVICE_ID_JOYPAD_A);
    assert(ui.dexDetailRow==0);
    press(RETRO_DEVICE_ID_JOYPAD_RIGHT);
    assert(ui.dexEntry==first && ui.dexSection==DsDexSection::Moves);
    press(RETRO_DEVICE_ID_JOYPAD_R);
    assert(ui.frame.tab==PD_TAB_SETTINGS && ui.dexEntry==0);
    draw();
    press(RETRO_DEVICE_ID_JOYPAD_R);
    assert(ui.frame.tab==PD_TAB_PARTY);
  }
}
