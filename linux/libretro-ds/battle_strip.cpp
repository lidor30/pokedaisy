// SPDX-License-Identifier: GPL-3.0-or-later
#include "battle_strip.h"
#include "data_gen.h"
#include "companion.h"
extern "C" {
#include "pd_snapshot.h"
}
#include <algorithm>

bool DsBattleView::enabled(int slot) const {
  if(slot<0 || slot>=4)return false;
  if(panel==DsBattlePanel::Actions)return slot!=3 || !trainer;
  return panel==DsBattlePanel::Moves && moves[slot]>0 && pp[slot]>0;
}

namespace {
using namespace pd_ds_data;
std::vector<uint8_t> basePp;
// The cards' max PP: base PP and a fifth of it per PP Up (0 to 3), as the games count it; 0 when not known.
int max_pp(int move,int ups){
  if(move<=0 || size_t(move)>=basePp.size() || ups<0 || ups>3)return 0;
  const int base=basePp[move];
  return base?base+base*ups/5:0;
}
bool in_ram(uint32_t addr,unsigned last=0){
  return !(addr&3) && addr>=0x02000000 && addr<=0x023ffffc-last;
}

uint32_t gen4_battle_system(const PkNdsFieldGame* game,uint32_t (*read32)(uint32_t)) {
  const auto fs=read32(game->fieldSystemPtr);
  if(!in_ram(fs,game->offProcessManager))return {};
  const auto manager=read32(fs+game->offProcessManager);
  if(!in_ram(manager,battle_offChild))return {};
  const auto child=read32(manager+battle_offChild);
  const auto init=read32(game->battleTemplate);
  if(!init || !in_ram(child,battle_offAppData) || read32(child)!=init)return {};
  return read32(child+battle_offAppData);
}

DsBattleView read_gen4(const PkNdsFieldGame* game,uint32_t (*read32)(uint32_t)) {
  const auto system=gen4_battle_system(game,read32);
  if(!in_ram(system,game->offBattleContext))return {};
  const auto type=read32(system+battle_offBattleType);
  if(type&2)return {};
  const auto ctx=read32(system+game->offBattleContext);
  if(!in_ram(ctx,battle_offBattleMons+0x68) ||
     pk_nds_battle_command(game,read32)!=PK_NDS_BATTLE_SELECTING)return {};
  const auto step=read32(ctx)&0xff;
  if(step<1 || step>4)return {};
  // BattleMon fields from Android's Gen4Reader.decodeGen4BattleMon.
  const auto mon=ctx+battle_offBattleMons;
  DsBattleView view;
  view.species=read32(mon)&0xffff;
  if(view.species<1 || view.species>=int(std::size(species)))return {};
  view.personality=read32(mon+0x68);
  view.trainer=type&1;
  view.panel=step<=2?DsBattlePanel::Actions:DsBattlePanel::Moves;
  // BattleMon's ppCur[4] at +0x2C, then ppUps[4] at +0x30 (level follows at +0x34, as Gen4Reader reads it).
  const auto pp=read32(mon+0x2c), ups=read32(mon+0x30);
  for(int slot=0;slot<4;++slot){
    const auto move=(read32(mon+0x0c+4*(slot/2))>>(16*(slot%2)))&0xffff;
    if(move>=std::size(moves))return {};
    view.moves[slot]=move;
    view.pp[slot]=(pp>>(8*slot))&0xff;
    const int most=max_pp(move,(ups>>(8*slot))&0xff);
    view.maxPp[slot]=most>=view.pp[slot]?most:0;
  }
  return view;
}

uint32_t gen5_battle_proc(const PkNdsFieldGame* game,uint32_t (*read32)(uint32_t)) {
  const auto gs=read32(game->fieldSystemPtr);
  if(!in_ram(gs,game->offProcSys))return {};
  const auto ps=read32(gs+game->offProcSys);
  if(!in_ram(ps,game->offCurrentProc))return {};
  const auto proc=read32(ps+game->offCurrentProc);
  if(!in_ram(proc,game->offProcWork) || read32(proc+game->offProcOverlay)!=unsigned(game->battleOverlay))return {};
  return proc;
}

DsBattleView read_gen5(const PkNdsFieldGame* game,uint32_t (*read32)(uint32_t)) {
  const auto proc=gen5_battle_proc(game,read32);
  if(!proc)return {};
  // Gen5Reader.Gen5Battle: the setup's competitor and rule, and the
  // main module's first active player battler.
  const auto setup=read32(proc+0x10), main=read32(proc+game->offProcWork);
  if(!in_ram(setup,4) || read32(setup+4)!=0 || !in_ram(main,0xcc))return {};
  const auto mon=read32(main+0xcc), client=read32(main+game->offClient);
  if(!in_ram(mon,0x130) || !in_ram(client,0x54))return {};
  const auto view=read32(client+0x54);
  const bool sequel=game->battleOverlay==167;
  const unsigned scdOffset=sequel?0x1c4:0x180, inputOffset=sequel?0xbc:0xb0;
  if(!in_ram(view,scdOffset))return {};
  const auto scd=read32(view+scdOffset);
  if(!in_ram(scd,inputOffset))return {};
  const auto input=read32(scd+inputOffset);
  if(!in_ram(input,0x58))return {};
  // The shared field reader already checked the command, wait flag, and slide.
  const auto panel=read32(input+0x58)&0xff;
  if(panel!=1 && panel!=2)return {};
  DsBattleView result;
  result.gen5=true;
  result.trainer=read32(setup)!=0;
  result.species=read32(mon+0x0c)&0xffff;
  if(result.species<1 || result.species>=int(std::size(unovaSpecies)))return {};
  result.panel=panel==1?DsBattlePanel::Actions:DsBattlePanel::Moves;
  for(int slot=0;slot<4;++slot){
    const auto offset=0x104+slot*14;
    const auto id=(read32(mon+(offset&~3u))>>((offset&3)*8))&0xffff;
    if(id>=std::size(unovaMoves))return {};
    const auto pp=offset+2;
    result.moves[slot]=id;
    result.pp[slot]=(read32(mon+(pp&~3u))>>((pp&3)*8))&0xff;
    // The byte after the current PP holds the max; trusted only where it is the base with 0 to 3 PP Ups.
    const int most=(read32(mon+((pp+1)&~3u))>>(((pp+1)&3)*8))&0xff;
    for(int k=0;k<=3;++k)
      if(most && most==max_pp(int(id),k) && most>=result.pp[slot])result.maxPp[slot]=most;
  }
  return result;
}
}

void ds_battle_set_base_pp(std::vector<uint8_t> table){basePp=std::move(table);}

DsBattleView ds_read_battle(const PkNdsFieldGame* game,uint32_t (*read32)(uint32_t),int scene) {
  if(!game || game->battleManagerPtr || !(scene&PK_NDS_FIELD_TOUCH_BATTLE_MENU))return {};
  const auto view=game->gen==5?read_gen5(game,read32):read_gen4(game,read32);
  if(view.panel==DsBattlePanel::Moves &&
     std::none_of(view.pp.begin(),view.pp.end(),[](int n){return n>0;}))return {};
  return view;
}

void ds_read_battle_companion(const PkNdsFieldGame* game,uint32_t (*read32)(uint32_t),
                              const uint8_t* ram,size_t ramSize,const DsCompanion& save,pd_snapshot& out) {
  out={};
  out.foeActive=out.foeNext=-1;
  if(!game || !ram || !save.valid || game->battleManagerPtr)return;
  auto at=[&](uint32_t addr,size_t count)->const uint8_t* {
    if(addr<0x02000000)return nullptr;
    const size_t offset=addr-0x02000000;
    return offset<=ramSize && count<=ramSize-offset?ram+offset:nullptr;
  };
  auto u16=[](const uint8_t* b){return unsigned(b[0]) | (unsigned(b[1])<<8);};
  const bool gen5=game->gen==5;
  uint32_t foeParty=0,foePersonality=0;
  std::array<uint32_t,2> identities{},mons{};
  if(gen5){
    // Gen5Reader.Gen5Battle: setup and the battle module's two client parties.
    const auto proc=gen5_battle_proc(game,read32);
    if(!proc)return;
    const auto setup=read32(proc+0x10),main=read32(proc+game->offProcWork);
    if(!in_ram(setup,0x28) || !in_ram(main,0xe8))return;
    const auto rule=read32(setup+4);
    if(rule!=0)return; // Suggestions target one foe at a time.
    out.isTrainer=read32(setup)!=0;
    foeParty=read32(setup+0x28);
    mons={read32(main+0xcc),read32(main+0xe8)};
  }else{
    // Gen4Reader.Gen4Battle: BattleSystem.parties and BattleContext.battleMons.
    const auto system=gen4_battle_system(game,read32);
    if(!in_ram(system,std::max<unsigned>(game->offBattleContext,0x6c)))return;
    const auto ctx=read32(system+game->offBattleContext);
    if(!in_ram(ctx,battle_offBattleMons+2*0xc0))return;
    const auto type=read32(system+battle_offBattleType);
    if(type&2)return;
    out.isTrainer=(type&1)!=0;
    foeParty=read32(system+0x6c);
    for(unsigned i=0;i<mons.size();++i)mons[i]=ctx+battle_offBattleMons+i*0xc0;
  }
  for(unsigned i=0;i<mons.size();++i){
    const auto* b=at(mons[i],gen5?0x140:0xc0);
    if(!b)continue;
    auto& m=out.battlers[i];
    const int speciesId=u16(b+(gen5?0x0c:0));
    if(speciesId<1 || speciesId>(gen5?649:493))continue;
    m.level=b[gen5?0x18:0x34];
    if(m.level<1 || m.level>100)continue;
    m.maxHp=gen5?u16(b+0x0e):int(read32(mons[i]+0x50));
    if(m.maxHp<=0 || m.maxHp>999)continue;
    m.species=speciesId;
    m.hp=std::clamp(gen5?int(u16(b+0x10)):int(read32(mons[i]+0x4c)),0,m.maxHp);
    const unsigned types=gen5?0xf8:0x24;
    auto battle_type=[&](int type) {
      if(gen5 && type>=9)++type; // The portable chart keeps the Gen 4 gap at 9.
      return type<=17 && type!=9?type:PD_TYPE_NONE;
    };
    m.type1=battle_type(b[types]);
    m.type2=battle_type(b[types+1]);
    m.status=gen5?0:read32(mons[i]+0x6c);
    for(int k=0;k<PD_NUM_MOVES;++k){
      m.moves[k]=u16(b+(gen5?0x104+k*14:0x0c+k*2));
      m.pp[k]=b[gen5?0x106+k*14:0x2c+k];
    }
    identities[i]=gen5?b[0x19]:read32(mons[i]+0x68);
    if(i==PD_POS_OPPONENT_LEFT){
      if(gen5)out.foeActive=identities[i]%6;
      else foePersonality=identities[i];
    }
  }
  if(!out.battlers[PD_POS_PLAYER_LEFT].species || !out.battlers[PD_POS_OPPONENT_LEFT].species)return;
  out.valid=out.inBattle=true;
  auto copy=[](pd_mon& dst,const DsMon& src){
    dst.species=src.species;dst.level=src.level;dst.hp=src.hp;dst.maxHp=src.maxHp;
    dst.status=src.status;dst.personality=src.personality;dst.egg=src.egg;
    std::copy(src.moves.begin(),src.moves.end(),dst.moves);
    std::copy(src.pp.begin(),src.pp.end(),dst.pp);
  };
  for(const auto& mon:save.party){
    if(out.partyCount==PD_PARTY_SIZE)break;
    copy(out.party[out.partyCount++],mon);
  }
  // The active slot's save record can lag HP and PP during a turn.
  const auto& live=out.battlers[PD_POS_PLAYER_LEFT];
  for(int i=0;i<out.partyCount;++i){
    auto& mon=out.party[i];
    const auto id=identities[PD_POS_PLAYER_LEFT];
    const bool same=gen5?id==unsigned(i):id==mon.personality;
    if(!same || live.species!=mon.species)continue;
    mon.hp=live.hp;mon.maxHp=live.maxHp;mon.status=live.status;
    std::copy(std::begin(live.moves),std::end(live.moves),mon.moves);
    std::copy(std::begin(live.pp),std::end(live.pp),mon.pp);
    break;
  }
  if(!out.isTrainer || !in_ram(foeParty,8)){out.foeActive=-1;return;}
  const unsigned count=read32(foeParty+4), stride=gen5?unova_monSize:monSize;
  if(!count || count>PD_PARTY_SIZE){out.foeActive=-1;return;}
  for(unsigned i=0;i<count;++i){
    const auto* record=at(foeParty+8+i*stride,stride);
    DsMon foe;
    if(!record || !ds_decode_mon(record,stride,foe,save.game)){
      out.foeActive=-1;return;
    }
    copy(out.foes[i],foe);
    if(!gen5 && foe.personality==foePersonality && foe.species==out.battlers[PD_POS_OPPONENT_LEFT].species)
      out.foeActive=int(i);
  }
  out.foeCount=int(count);
  if(out.foeActive>=out.foeCount)out.foeActive=-1;
}

const char* ds_battle_species(const DsBattleView& view){
  return view.gen5?unovaSpecies[view.species]:species[view.species];
}

void DsBattleStrip::select(const DsBattleView& view,int dx,int dy,bool accept,bool back) {
  if(view.panel!=panel && view.panel==DsBattlePanel::Moves){
    moveFocus=0;
    moveReadyFrames=0;
    while(moveFocus<3 && !view.enabled(moveFocus))++moveFocus;
  }
  panel=view.panel;
  if(panel==DsBattlePanel::Native || tapFrames)return;
  const bool moves=panel==DsBattlePanel::Moves;
  int& focus=moves?moveFocus:actionFocus;
  if(!view.enabled(focus))for(int i=0;i<4;++i)if(view.enabled(i)){focus=i;break;}
  const int next=std::clamp(focus%2+dx,0,1)+2*std::clamp(focus/2+dy,0,1);
  if(view.enabled(next))focus=next;
  // Gen 5 briefly reports a ready move panel between two native slides.
  // Wait for it to stay ready before injecting a tap, as Android does.
  if(moves&&view.gen5&&moveReadyFrames<4 && ++moveReadyFrames<4)return;
  // Native touch targets from Android's NdsBattleTaps.Layout.
  static constexpr DsBattleTap actions[]={{true,128,90},{true,40,170},{true,215,170},{true,128,170}};
  static constexpr DsBattleTap cards[]={{true,64,42},{true,192,42},{true,64,113},{true,192,113}};
  static constexpr DsBattleTap unovaActions[]={{true,128,80},{true,30,172},{true,222,172},{true,128,178}};
  static constexpr DsBattleTap unovaCards[]={{true,63,56},{true,191,56},{true,63,104},{true,191,104}};
  if(moves && back){
    tap=view.gen5?DsBattleTap{true,230,175}:DsBattleTap{true,128,175};
    moveCommitFrames=18;
    cancelingMove=true;
  }
  else if(accept && view.enabled(focus)){
    tap=moves?(view.gen5?unovaCards:cards)[focus]:(view.gen5?unovaActions:actions)[focus];
    // A single battle briefly retains the old move panel while resolving
    // the target. Let an actual native prompt through if it persists.
    if(moves){moveCommitFrames=18;cancelingMove=false;}
  }
  else return;
  tapFrames=5;
}
DsBattleTap DsBattleStrip::tick(){
  if(moveCommitFrames>0)--moveCommitFrames;
  return tapFrames && tapFrames-->2?tap:DsBattleTap{};
}
