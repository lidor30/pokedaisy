// GPL-3.0. Gen4Reader.kt's checksum, shuffle and decryption rules.
#include "companion.h"
#include "data_gen.h"
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
}
bool ds_decode_mon(const uint8_t* raw,size_t size,DsMon& mon) {
  if(size<pd_ds_data::monSize)return false;
  uint32_t pid=u32(raw);uint16_t flags=u16(raw+4),sum=u16(raw+6);
  uint8_t blocks[128];std::memcpy(blocks,raw+8,128);
  if(flags&2) { if(!checksum(blocks,sum))return false; }
  else { crypt(blocks,128,sum);if(!checksum(blocks,sum)){std::memcpy(blocks,raw+8,128);if(!checksum(blocks,sum))return false;} }
  const char* order=pd_ds_data::orders[((pid>>13)&31)%24];
  auto block=[&](char c){return blocks+32*(std::strchr(order,c)-order);};
  const uint8_t *a=block('A'),*b=block('B'),*c=block('C');
  int species=u16(a);if(species<1 || species>493)return false;
  uint8_t party[100];static_assert(pd_ds_data::monSize-pd_ds_data::boxSize==sizeof party);
  std::memcpy(party,raw+pd_ds_data::boxSize,sizeof party);
  if(flags&1) { if(!sane(party))return false; }
  else {crypt(party,sizeof party,pid);if(!sane(party)){std::memcpy(party,raw+pd_ds_data::boxSize,sizeof party);if(!sane(party))return false;} }
  mon={};mon.species=species;mon.personality=pid;mon.level=party[4];mon.hp=u16(party+6);mon.maxHp=u16(party+8);
  mon.status=u32(party);mon.heldItem=u16(a+2);mon.experience=u32(a+8);mon.egg=(u32(b+16)>>30)&1;
  mon.gender=(b[24]&4)?0:(b[24]&2)?2:1;mon.nature=pid%25;
  mon.nickname=text(c,11);if(mon.nickname.empty())mon.nickname=ds_species(species);
  for(int i=0;i<4;i++){mon.moves[i]=u16(b+2*i);mon.pp[i]=b[8+i];}return true;
}
DsCompanion ds_read_companion(const uint8_t* ram,size_t size) {
  DsCompanion out;
  auto at=[&](uint32_t a,size_t n)->const uint8_t* {
    if(a<0x02000000 || a-0x02000000>size || n>size-(a-0x02000000))return nullptr;
    return ram+a-0x02000000;
  };
  const uint8_t* ptr=at(pd_ds_data::saveDataPtr,4);if(!ptr)return out;
  uint32_t base=u32(ptr);if(base<0x02000000 || base>=0x023e0000)return out;base+=pd_ds_data::bodyOff;
  const uint8_t* head=at(base+pd_ds_data::partyOff,8);if(!head || u32(head)>6 || u32(head+4)>6)return out;
  for(uint32_t i=0;i<u32(head+4);i++) {
    auto p=at(base+pd_ds_data::partyOff+8+i*pd_ds_data::monSize,pd_ds_data::monSize);
    DsMon m;if(!p || !ds_decode_mon(p,pd_ds_data::monSize,m))return {};out.party.push_back(m);
  }
  auto trainer=at(base+pd_ds_data::trainerOff,28);if(!trainer)return {};out.trainer=text(trainer,8);out.money=u32(trainer+20);out.badges=trainer[26];
  auto loc=at(base+pd_ds_data::locationOff,4);if(loc)out.map=u32(loc);
  size_t slot=0;int pocket=0;
  for(int capacity:pd_ds_data::pockets) {for(int i=0;i<capacity;i++,slot++) {
    auto p=at(base+pd_ds_data::bagOff+slot*4,4);if(!p)return {};
    if(u16(p) && u16(p+2))out.bag.push_back({u16(p),u16(p+2),pocket});
  }pocket++;}
  auto dex=at(base+pd_ds_data::pokedexOff,pd_ds_data::dexSize);
  if(dex && u32(dex)==pd_ds_data::dexMagic){
    out.dexValid=true;out.nationalDex=dex[pd_ds_data::nationalDexOff]!=0;
    out.dexSeen.resize(pd_ds_data::dexMax+1);out.dexCaught.resize(pd_ds_data::dexMax+1);
    for(unsigned n=1;n<=pd_ds_data::dexMax;n++){
      unsigned byte=(n-1)/8,bit=(n-1)%8;
      out.dexSeen[n]=(dex[pd_ds_data::dexSeenBase+byte]>>bit)&1;
      out.dexCaught[n]=out.dexSeen[n] && ((dex[pd_ds_data::dexCaughtBase+byte]>>bit)&1);
      out.seenCount+=out.dexSeen[n];out.caughtCount+=out.dexCaught[n];
    }
  }
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
  for(unsigned i=0;i<56;i++){unsigned bit=pd_ds_data::map_firstArrivalFlag+i;auto p=at(flags+bit/8,1);if(p && ((*p>>(bit%8))&1))out.arrivals|=uint64_t(1)<<i;}
  out.valid=true;return out;
}
const char* ds_species(int id){return name(pd_ds_data::species,id);}
const char* ds_move(int id){return name(pd_ds_data::moves,id);}
const char* ds_item(int id){return name(pd_ds_data::items,id);}
const char* ds_place(int id){return name(pd_ds_data::places,id);}

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
