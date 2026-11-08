// GPL-3.0. Gen4Reader.kt and Gen5Reader.kt save layouts and party decoding.
#include "companion.h"
#include "data_gen.h"
#include "pk_nds_field.h"
#include <cstring>
#include <algorithm>

namespace {
uint16_t u16(const uint8_t* p) { return p[0] | uint16_t(p[1])<<8; }
uint32_t u32(const uint8_t* p) { return u16(p) | uint32_t(u16(p+2))<<16; }
void crypt(uint8_t* p, size_t n, uint32_t seed) {
  for(size_t i=0;i<n;i+=2) { seed=seed*0x41c64e6du+0x6073u; uint16_t v=u16(p+i)^(seed>>16); p[i]=v; p[i+1]=v>>8; }
}
bool checksum(const uint8_t* p, uint16_t expected) {
  uint16_t sum=0;for(int i=0;i<128;i+=2)sum+=u16(p+i);return sum==expected;
}
bool sane(const uint8_t* p) { return p[4]>=1 && p[4]<=100 && u16(p+8)>=1 && u16(p+8)<=999 && u16(p+6)<=u16(p+8); }
std::string text(const uint8_t* p, int n) {
  std::string out;
  for(int i=0;i<n;i++) { uint16_t v=u16(p+2*i);if(v==0xffff)break;
    for(auto c:pd_ds_data::chars)if(c.code==v){out+=c.text;break;}
  } return out;
}
template<size_t N> const char* name(const char* const (&a)[N],int i) { return i>=0 && size_t(i)<N && *a[i]?a[i]:"?"; }
std::string utf16_text(const uint8_t* p,int count){
  std::string out;
  for(int i=0;i<count;++i){
    const unsigned ch=u16(p+2*i);
    if(ch==0||ch==0xffff)break;
    if(ch<0x80)out+=char(ch);
    else if(ch<0x800){out+=char(0xc0|(ch>>6));out+=char(0x80|(ch&63));}
    else if(ch<0xd800||ch>0xdfff){out+=char(0xe0|(ch>>12));out+=char(0x80|((ch>>6)&63));out+=char(0x80|(ch&63));}
  }
  return out;
}
}
bool ds_unova(DsGame game){return game==DsGame::Black||game==DsGame::White||ds_unova_sequel(game);}
bool ds_unova_sequel(DsGame game){return game==DsGame::Black2||game==DsGame::White2;}
int ds_egg_icon(DsGame game){return ds_unova(game)?650:pd_ds_data::iconEgg;}
const char* ds_canonical_sha1(const char* digest){
  using namespace pd_ds_data;
  // Decrypted dumps: the secure area differs, the rest of the file (every offset the
  // companion reads) is the retail layout. Listed per dump, so only ROMs somebody
  // has checked are accepted.
  if(!std::strcmp(digest,"1492557711d8cd46628fe3eeceae1dec52d33e70"))return heartGoldSha1;  // HeartGold (IPKE rev 0)
  if(!std::strcmp(digest,"d94d02ec44c8e2dfc9b744bb888cb64c49678dc2"))return black2Sha1;     // Black 2 (IREO rev 0)
  return digest;
}
DsGame ds_companion_game(const char* digest){
  using namespace pd_ds_data;
  if(!std::strcmp(digest,sha1))return DsGame::Platinum;
  if(!std::strcmp(digest,heartGoldSha1)||!std::strcmp(digest,soulSilverSha1))return DsGame::Johto;
  if(!std::strcmp(digest,blackSha1))return DsGame::Black;
  if(!std::strcmp(digest,whiteSha1))return DsGame::White;
  if(!std::strcmp(digest,black2Sha1))return DsGame::Black2;
  if(!std::strcmp(digest,white2Sha1))return DsGame::White2;
  return DsGame::Unsupported;
}
namespace {
bool decode_mon(const uint8_t* raw,size_t size,DsMon& mon,DsGame game) {
  const bool unova=ds_unova(game);
  const size_t record=unova?pd_ds_data::unova_monSize:pd_ds_data::monSize;
  if(size<record)return false;
  uint32_t pid=u32(raw);uint16_t flags=u16(raw+4),sum=u16(raw+6);
  uint8_t blocks[128];std::memcpy(blocks,raw+8,128);
  if(flags&2) { if(!checksum(blocks,sum))return false; }
  else { crypt(blocks,128,sum);if(!checksum(blocks,sum)){std::memcpy(blocks,raw+8,128);if(!checksum(blocks,sum))return false;} }
  const char* order=pd_ds_data::orders[((pid>>13)&31)%24];
  auto block=[&](char c){return blocks+32*(std::strchr(order,c)-order);};
  const uint8_t *a=block('A'),*b=block('B'),*c=block('C');
  int species=u16(a);if(species<1 || species>(unova?649:493))return false;
  uint8_t party[100];static_assert(pd_ds_data::monSize-pd_ds_data::boxSize==sizeof party);
  const size_t partySize=record-pd_ds_data::boxSize;
  std::memcpy(party,raw+pd_ds_data::boxSize,partySize);
  if(flags&1) { if(!sane(party))return false; }
  else {crypt(party,partySize,pid);if(!sane(party)){std::memcpy(party,raw+pd_ds_data::boxSize,partySize);if(!sane(party))return false;} }
  mon={};mon.species=species;mon.personality=pid;mon.level=party[4];mon.hp=u16(party+6);mon.maxHp=u16(party+8);
  mon.status=u32(party);mon.heldItem=u16(a+2);mon.experience=u32(a+8);mon.egg=(u32(b+16)>>30)&1;
  mon.gender=(b[24]&4)?0:(b[24]&2)?2:1;
  mon.nature=unova?b[25]:pid%25;
  mon.friendship=a[12]; // hatch counter for eggs, which the summary omits
  const uint32_t ivWord=u32(b+16);
  for(int i=0;i<6;++i){
    mon.stats[i]=u16(party+8+2*i);
    mon.ivs[i]=(ivWord>>(5*i))&31;
    mon.evs[i]=a[16+i];
  }
  mon.nickname=unova?utf16_text(c,11):text(c,11);
  if(mon.nickname.empty())mon.nickname=ds_species(species,game);
  for(int i=0;i<4;i++){mon.moves[i]=u16(b+2*i);mon.pp[i]=b[8+i];}return true;
}
struct Ram {
  const uint8_t* bytes;
  size_t size;
  const uint8_t* at(uint32_t a,size_t n) const {
    if(a<0x02000000 || a-0x02000000>size || n>size-(a-0x02000000))return nullptr;
    return bytes+a-0x02000000;
  }
  uint32_t word(uint32_t addr) const {
    const auto* p=at(addr,4);return p?u32(p):0;
  }
};

bool read_party(const Ram& ram,uint32_t addr,DsCompanion& out){
  using namespace pd_ds_data;
  const auto* head=ram.at(addr,8);
  if(!head||u32(head)>6||u32(head+4)>u32(head))return false;
  const size_t size=ds_unova(out.game)?unova_monSize:monSize;
  for(uint32_t i=0;i<u32(head+4);++i){
    const auto* record=ram.at(addr+8+i*size,size);
    DsMon mon;
    if(!record||!decode_mon(record,size,mon,out.game))return false;
    out.party.push_back(std::move(mon));
  }
  return true;
}

template<size_t N> bool read_bag(const Ram& ram,uint32_t addr,const int (&capacities)[N],DsCompanion& out){
  size_t slot=0;
  for(size_t pocket=0;pocket<N;++pocket)
    for(int i=0;i<capacities[pocket];++i,++slot){
      const auto* p=ram.at(addr+slot*4,4);
      if(!p)return false;
      if(u16(p)&&u16(p+2))out.bag.push_back({u16(p),u16(p+2),int(pocket)});
    }
  return true;
}

void read_dex(const Ram& ram,uint32_t address,DsCompanion& out,unsigned nationalOff){
  using namespace pd_ds_data;
  const bool unova=ds_unova(out.game);
  const unsigned count=unova?649:dexMax;
  const unsigned stride=unova?0x54:64, caughtBase=unova?8:dexCaughtBase;
  const auto* dex=ram.at(address,unova?caughtBase+5*stride:dexSize);
  if(!dex||u32(dex)!=dexMagic)return;
  out.dexValid=true;
  out.nationalDex=!unova&&dex[nationalOff]!=0;
  out.dexSeen.resize(count+1);out.dexCaught.resize(count+1);
  for(unsigned n=1;n<=count;++n){
    const auto bit=[&](unsigned base){return (dex[base+(n-1)/8]>>((n-1)%8))&1;};
    bool caught=bit(caughtBase),seen=bit(caughtBase+stride);
    if(unova)seen=seen||caught||bit(caughtBase+2*stride)||bit(caughtBase+3*stride)||bit(caughtBase+4*stride);
    out.dexSeen[n]=seen;out.dexCaught[n]=seen&&caught;
    out.seenCount+=seen;out.caughtCount+=out.dexCaught[n];
  }
}

uint32_t unova_save(const Ram& ram,DsGame game){
  using namespace pd_ds_data;
  const uint32_t controlPtr=game==DsGame::Black?unova_saveControlPtr:
    game==DsGame::White?0x020a72b8:game==DsGame::Black2?0x0209a338:0x0209a378;
  const uint32_t control=ram.word(controlPtr);
  if(!ram.at(control,unova_offBackupSystem+4))return 0;
  const uint32_t backup=ram.word(control+unova_offBackupSystem);
  if(!ram.at(backup,unova_offImage+4))return 0;
  return ram.word(backup+unova_offImage);
}

DsCompanion read_other_companion(const Ram& ram,DsGame game){
  using namespace pd_ds_data;
  DsCompanion out;
  out.game=game;
  if(game==DsGame::Johto){
    const uint32_t save=ram.word(johto_saveDataPtr);
    if(!ram.at(save,johto_locationOff+johto_bodyOff+4))return out;
    const uint32_t body=save+johto_bodyOff;
    const auto* trainer=ram.at(body+johto_trainerOff,johto_kantoBadgesOff+1);
    if(!trainer||!read_party(ram,body+johto_partyOff,out)||!read_bag(ram,body+johto_bagOff,johtoPockets,out))return {};
    out.trainer=text(trainer,8);out.money=u32(trainer+20);
    out.badges=trainer[26]|(trainer[johto_kantoBadgesOff]<<8);out.playerGender=trainer[24];
    out.map=ram.word(body+johto_locationOff);
    if(const auto* flags=ram.at(body+pg_flagsOff,gen4FlagBytes))
      out.eventFlags.assign(flags,flags+gen4FlagBytes);
    if(const auto* steps=ram.at(body+johto_repelStepsOff,1))out.repelSteps=*steps;
    read_dex(ram,body+johto_pokedexOff,out,johto_nationalDexOff);
    const uint32_t gear=ram.word(body+pg_wordOff);
    out.mapSkin=std::min(5u,(gear>>18)&127);out.mapUnlock=(gear>>27)&3;
    for(unsigned i=0;i<38;++i){
      const unsigned bit=pg_firstFlag+i;
      const auto* p=ram.at(body+pg_flagsOff+bit/8,1);
      if(p&&((*p>>(bit%8))&1))out.arrivals|=uint64_t(1)<<i;
    }
    const auto point=std::find_if(std::begin(pgWorld),std::end(pgWorld),[&](const auto& p){return p[0]==out.map;});
    const bool main=std::find(std::begin(pgHeaders),std::end(pgHeaders),out.map)!=std::end(pgHeaders);
    const uint32_t field=ram.word(pg_fieldSystemPtr), avatar=ram.word(field+pg_offPlayerAvatar);
    const uint32_t object=ram.word(avatar+pg_offAvatarMapObject);
    if(main&&ram.at(object,pg_offObjectZ+4)){
      out.mapX=ram.word(object+pg_offObjectX)/32;out.mapY=ram.word(object+pg_offObjectZ)/32+2;
    }else if(point!=std::end(pgWorld)){
      out.mapX=(*point)[1];out.mapY=(*point)[2]+2;
    }else{
      out.mapX=ram.word(body+johto_locationOff+pg_offSpecialSpawn+8)/32;
      out.mapY=ram.word(body+johto_locationOff+pg_offSpecialSpawn+12)/32+2;
    }
  }else if(ds_unova(game)){
    const uint32_t save=unova_save(ram,game);
    const bool sequel=ds_unova_sequel(game);
    if(!ram.at(save,sequel?0x26000:0x24000))return out;
    const auto* trainer=ram.at(save+unova_trainerOff,0x28);
    if(!trainer||!read_party(ram,save+unova_partyOff,out)||!read_bag(ram,save+unova_bagOff,unovaPockets,out))return {};
    out.trainer=utf16_text(trainer+4,8);out.money=ram.word(save+(sequel?0x21100:unova_moneyOff));
    out.badges=ram.word(save+(sequel?0x21104:unova_badgesOff))&255;
    out.playerGender=trainer[0x21];out.map=ram.word(save+unova_locationOff+4)&0xffff;
    const char* code=game==DsGame::Black?"IRBO":game==DsGame::White?"IRAO":game==DsGame::Black2?"IREO":"IRDO";
    const auto* field=pk_nds_field_game(code,0);
    if(field){
      const uint32_t system=ram.word(field->fieldSystemPtr);
      if(ram.at(system,field->offGameData+4)){
        const uint32_t gd=ram.word(system+field->offGameData);
        if(ram.at(gd,field->offZone+2))out.map=u16(ram.at(gd+field->offZone,2));
      }
    }
    const auto* zones=sequel?sequelMapZones:unovaMapZones;
    const size_t zoneCount=sequel?std::size(sequelMapZones):std::size(unovaMapZones);
    if(size_t(out.map)<zoneCount&&zones[out.map]>=0){
      const auto* points=sequel?sequelMapPoints:unovaMapPoints;
      const auto& p=points[zones[out.map]];
      out.mapX=p[2]/8;out.mapY=p[3]/8;
    }
    // Gen5Reader's event-work layout (u16 vars followed by flags).
    const uint32_t flags=save+(sequel?sequel_flagsOff:unova_flagsOff);
    const size_t flagBytes=sequel?sequel_flagBytes:unova_flagBytes;
    if(const auto* bits=ram.at(flags,flagBytes))out.eventFlags.assign(bits,bits+flagBytes);
    const unsigned first=sequel?2469:2480;
    const auto flag=[&](unsigned n){
      const auto* p=ram.at(flags+n/8,1);
      return p&&((*p>>(n%8))&1);
    };
    for(unsigned i=0;i<62;++i)if(flag(first+i))out.arrivals|=uint64_t(1)<<i;
    if(sequel){
      if(flag(2463))out.arrivals|=uint64_t(1)<<62;
      if(flag(2464))out.arrivals|=uint64_t(1)<<63;
    }else if(std::any_of(out.bag.begin(),out.bag.end(),[](const DsItem& i){return i.id==574;})){
      out.arrivals|=uint64_t(1)<<62; // Liberty Pass
    }
    if(const auto* steps=ram.at(save+(sequel?0x2192d:unova_repelStepsOff),1))out.repelSteps=*steps;
    read_dex(ram,save+(sequel?0x21400:unova_pokedexOff),out,0);
  }else return out;
  out.valid=true;
  return out;
}
}

bool ds_decode_mon(const uint8_t* raw,size_t size,DsMon& mon,DsGame game){
  return decode_mon(raw,size,mon,game);
}
DsCompanion ds_read_companion(const uint8_t* ram,size_t size,DsGame game) {
  const Ram memory{ram,size};
  if(game!=DsGame::Platinum)return read_other_companion(memory,game);
  DsCompanion out;
  auto at=[&](uint32_t a,size_t n){return memory.at(a,n);};
  const uint8_t* ptr=at(pd_ds_data::saveDataPtr,4);if(!ptr)return out;
  uint32_t base=u32(ptr);if(base<0x02000000 || base>=0x023e0000)return out;base+=pd_ds_data::bodyOff;
  if(!read_party(memory,base+pd_ds_data::partyOff,out))return {};
  auto trainer=at(base+pd_ds_data::trainerOff,28);if(!trainer)return {};out.trainer=text(trainer,8);out.money=u32(trainer+20);out.badges=trainer[26];
  auto loc=at(base+pd_ds_data::locationOff,4);if(loc)out.map=u32(loc);
  if(!read_bag(memory,base+pd_ds_data::bagOff,pd_ds_data::pockets,out))return {};
  if(const auto* steps=at(base+pd_ds_data::repelStepsOff,1))out.repelSteps=*steps;
  read_dex(memory,base+pd_ds_data::pokedexOff,out,pd_ds_data::nationalDexOff);
  out.playerGender=trainer[24];
  auto read=[&](uint32_t addr)->uint32_t{auto p=at(addr,4);return p?u32(p):0;};
  uint32_t fs=read(pd_ds_data::fieldSystemPtr),header=out.map;
  uint32_t x=read(base+pd_ds_data::locationOff+8),z=read(base+pd_ds_data::locationOff+12);
  if(at(fs,4)){
    uint32_t location=read(fs+pd_ds_data::map_offLocation),avatar=read(fs+pd_ds_data::map_offPlayerAvatar);
    uint32_t obj=at(avatar,4)?read(avatar+pd_ds_data::map_offAvatarMapObject):0;
    if(at(location,4) && at(obj,4)){header=read(location);x=read(obj+pd_ds_data::map_offObjectX);z=read(obj+pd_ds_data::map_offObjectZ);out.map=header;}
  }
  if(std::find(std::begin(pd_ds_data::mainHeaders),std::end(pd_ds_data::mainHeaders),int(header))==std::end(pd_ds_data::mainHeaders)){
    x=read(base+pd_ds_data::locationOff+pd_ds_data::map_offExitLocation+8);z=read(base+pd_ds_data::locationOff+pd_ds_data::map_offExitLocation+12);
  }
  if(x/32<30 && z/32<30){out.mapX=x/32;out.mapY=z/32;}
  uint32_t flags=base+pd_ds_data::map_varsOff+pd_ds_data::map_numVars*2;
  if(const auto* bits=at(flags,pd_ds_data::gen4FlagBytes))
    out.eventFlags.assign(bits,bits+pd_ds_data::gen4FlagBytes);
  for(unsigned i=0;i<56;i++){unsigned bit=pd_ds_data::map_firstArrivalFlag+i;auto p=at(flags+bit/8,1);if(p && ((*p>>(bit%8))&1))out.arrivals|=uint64_t(1)<<i;}
  out.valid=true;return out;
}
bool ds_event_flag(const DsCompanion& s,unsigned flag){
  return flag>0 && flag/8<s.eventFlags.size() && (s.eventFlags[flag/8]&(1u<<(flag%8)));
}
int ds_unova_month(const uint8_t* ram,size_t size,DsGame game){
  if(!ds_unova(game))return 0;
  const Ram memory{ram,size};
  const auto save=unova_save(memory,game);
  // GuideGen5's GEN5_VAR_MONTH is variable 1 in the save's u16 event-work array.
  const auto vars=ds_unova_sequel(game)?pd_ds_data::sequel_varsOff:pd_ds_data::unova_varsOff;
  const auto* month=memory.at(save+vars+2,2);
  return month?u16(month):0;
}
const char* ds_species(int id,DsGame game){return ds_unova(game)?name(pd_ds_data::unovaSpecies,id):name(pd_ds_data::species,id);}
const char* ds_move(int id,DsGame game){return ds_unova(game)?name(pd_ds_data::unovaMoves,id):name(pd_ds_data::moves,id);}
const char* ds_item(int id,DsGame game){
  using namespace pd_ds_data;
  if(game==DsGame::Johto)return name(johtoItems,id);
  if(ds_unova_sequel(game)&&id>=0&&size_t(id)<std::size(sequelItems)&&*sequelItems[id])return sequelItems[id];
  return ds_unova(game)?name(unovaItems,id):name(items,id);
}
const char* ds_place(int id,DsGame game){
  using namespace pd_ds_data;
  if(game==DsGame::Johto)return name(johtoPlaces,id);
  if(ds_unova_sequel(game))return name(sequelPlaces,id);
  return ds_unova(game)?name(unovaPlaces,id):name(places,id);
}

const char* ds_nature(int id){return name(pd_ds_data::natures,id);}
const char* ds_status(const DsMon& m){
  if(m.hp==0)return "FAINTED";
  if(m.status&7)return "SLEEP";
  if(m.status&0x88)return "POISON";
  if(m.status&0x10)return "BURN";
  if(m.status&0x20)return "FREEZE";
  if(m.status&0x40)return "PARALYSIS";
  return "OK";
}

int ds_move_type(int id,DsGame game){
  using namespace pd_ds_data;
  if(ds_unova(game))return id>0&&size_t(id)<std::size(unovaTypes)?unovaTypes[id]:-1;
  return id>0&&size_t(id)<std::size(moveMeta)?moveMeta[id][0]:-1;
}
int ds_move_power(int id,DsGame game){
  using namespace pd_ds_data;
  if(ds_unova(game))return id>0 && size_t(id)<std::size(unovaPower)?unovaPower[id]:0;
  return id>0 && size_t(id)<std::size(moveMeta)?moveMeta[id][1]:0;
}
int ds_species_type(int id,int slot,DsGame game){
  using namespace pd_ds_data;
  if(id<1||slot<0||slot>1)return -1;
  if(ds_unova(game))return size_t(id)<std::size(unovaSpeciesTypes)?unovaSpeciesTypes[id][slot]:-1;
  return size_t(id)<std::size(speciesTypes)?speciesTypes[id][slot]:-1;
}

DsRepelResult ds_use_repel(const uint8_t* bytes,size_t size,DsGame game,int item,
                         void (*write8)(uint32_t,uint8_t)){
  using namespace pd_ds_data;
  using R=DsRepelResult;
  const int steps=item==79?100:item==76?200:item==77?250:0;
  if(!steps||!write8||game==DsGame::Unsupported)return R::NotAvailable;
  const Ram ram{bytes,size};
  uint32_t bag=0,counter=0;
  int slots=0;
  if(ds_unova(game)){
    const uint32_t save=unova_save(ram,game);
    if(!ram.at(save,ds_unova_sequel(game)?0x26000:0x24000))return R::NotAvailable;
    bag=save+unova_bagOff;counter=save+(ds_unova_sequel(game)?0x2192d:unova_repelStepsOff);
    slots=unovaPockets[0];
  }else{
    const bool johto=game==DsGame::Johto;
    const uint32_t save=ram.word(johto?johto_saveDataPtr:saveDataPtr);
    if(save<0x02000000||save>=0x023e0000)return R::NotAvailable;
    const uint32_t body=save+(johto?johto_bodyOff:bodyOff);
    bag=body+(johto?johto_bagOff:bagOff);counter=body+(johto?johto_repelStepsOff:repelStepsOff);
    slots=johto?johtoPockets[0]:pockets[0];
  }
  const auto* items=ram.at(bag,slots*4);
  const auto* active=ram.at(counter,1);
  if(!items||!active)return R::NotAvailable;
  if(*active)return R::StillActive;
  // Validate both ranges before touching RAM, then complete the update between emulation frames.
  for(int i=0;i<slots;++i){
    if(u16(items+i*4)!=item||!u16(items+i*4+2))continue;
    const unsigned left=u16(items+i*4+2)-1;
    if(left){
      write8(bag+i*4+2,left&255);
      write8(bag+i*4+3,left>>8);
    }else{
      for(int j=i*4;j<(slots-1)*4;++j)write8(bag+j,items[j+4]);
      for(int j=(slots-1)*4;j<slots*4;++j)write8(bag+j,0);
    }
    write8(counter,steps);
    return R::Used;
  }
  return R::NoneLeft;
}
