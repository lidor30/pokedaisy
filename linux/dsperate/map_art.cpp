// GPL-3.0. Container/tile decoding adapted from upstream NdsArt.kt.
#include "map_art.h"
#include "data_gen.h"
#include <algorithm>
#include <stdexcept>
#include <string>
#include <cstdio>
namespace {
using Bytes=std::vector<uint8_t>;
uint16_t u16(const Bytes& b,size_t p){return b.at(p)|(uint16_t(b.at(p+1))<<8);}
uint32_t u32(const Bytes& b,size_t p){return u16(b,p)|(uint32_t(u16(b,p+2))<<16);}
Bytes slice(const Bytes& b,size_t p,size_t n){if(p>b.size() || n>b.size()-p)throw std::runtime_error("short art");return {b.begin()+p,b.begin()+p+n};}
size_t block(const Bytes& b,const std::string& tag){auto i=std::search(b.begin(),b.end(),tag.begin(),tag.end());if(i==b.end())throw std::runtime_error("missing block");return i-b.begin();}
Bytes file(const Bytes& rom,std::string path){
 auto fnt=slice(rom,u32(rom,0x40),u32(rom,0x44));unsigned id=0xf000;
 size_t begin=1;
 while(begin<path.size()){
  auto end=path.find('/',begin);std::string name=path.substr(begin,end-begin);
  size_t entry=(id&0xfff)*8,p=u32(fnt,entry);unsigned next=u16(fnt,entry+4);bool found=false;
  for(;;){int len=fnt.at(p++);if(!len)break;int n=len&127;auto bytes=slice(fnt,p,n);p+=n;std::string label(bytes.begin(),bytes.end());unsigned value=(len&128)?u16(fnt,p):next++;
   if(len&128)p+=2;
   if(label==name){id=value;found=true;break;}}
  if(!found)throw std::runtime_error("missing ROM file");
  if(end==std::string::npos)break;
  begin=end+1;
 }
 size_t fat=u32(rom,0x48)+id*8,start=u32(rom,fat),end=u32(rom,fat+4);
 if(end<start || end-start>32*1024*1024)throw std::runtime_error("bad FAT");
 return slice(rom,start,end-start);
}
Bytes member(const Bytes& narc,unsigned id){
 if(std::string(narc.begin(),narc.begin()+std::min(size_t(4),narc.size()))!="NARC")throw std::runtime_error("bad NARC");
 size_t fat=u16(narc,12);if(id>=u16(narc,fat+8))throw std::runtime_error("missing member");
 size_t offsets=fat+12+id*8,btnf=fat+u32(narc,fat+4),base=btnf+u32(narc,btnf+4)+8;
 size_t a=u32(narc,offsets),b=u32(narc,offsets+4);if(b<a)throw std::runtime_error("bad member");return slice(narc,base+a,b-a);
}
Bytes tiles(const Bytes& b){size_t o=block(b,"RAHC");return slice(b,o+8+u32(b,o+28),u32(b,o+24));}
std::vector<uint32_t> palette(const Bytes& b){size_t p=block(b,"TTLP")+24;std::vector<uint32_t> out;for(;p+1<b.size();p+=2){unsigned v=u16(b,p);out.push_back(0xff000000u|((v&31)*255/31)<<16|(((v>>5)&31)*255/31)<<8|(((v>>10)&31)*255/31));}return out;}
int pixel(const Bytes& b,int tile,int x,int y){size_t p=tile*32+y*4+x/2;if(p>=b.size())throw std::runtime_error("short tiles");return (b[p]>>(x%2*4))&15;}
std::vector<uint32_t> cell(const Bytes& cells,const Bytes& tile,const std::vector<uint32_t>& pal,int index,int overridePal,int side=16,bool mapped=false){
 size_t o=block(cells,"KBEC"),count=u16(cells,o+8),data=o+8+u32(cells,o+12),stride=u16(cells,o+10)==1?16:8,base=data+count*stride;
 if(index<0 || size_t(index)>=count)throw std::runtime_error("bad cell");
 size_t e=data+index*stride;int num=u16(cells,e);size_t start=base+u32(cells,e+4);
 static const int dims[3][4][2]={{{8,8},{16,16},{32,32},{64,64}},{{16,8},{32,8},{32,16},{64,32}},{{8,16},{8,32},{16,32},{32,64}}};
 const int shift=mapped?(u32(cells,o+16)&3):0;
 std::vector<uint32_t> out(side*side,0);
 for(int k=num-1;k>=0;k--){size_t p=start+k*6;unsigned a=u16(cells,p),b=u16(cells,p+2),c=u16(cells,p+4),shape=a>>14,size=b>>14;if(shape>=3)continue;
  int w=dims[shape][size][0],h=dims[shape][size][1],ox=b&511,oy=a&255;if(ox>=256)ox-=512;if(oy>=128)oy-=256;
  int bank=overridePal>=0?overridePal:c>>12;
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){
   int dx=side/2+ox+x,dy=side/2+oy+y;if(dx<0 || dx>=side || dy<0 || dy>=side)continue;
   int sx=(b&4096)?w-1-x:x,sy=(b&8192)?h-1-y:y;
   int v=pixel(tile,((c&1023)<<shift)+(sy/8)*(w/8)+sx/8,sx%8,sy%8);
   if(v)out[dy*side+dx]=pal.at(bank*16+v);
  }
 }
 return out;
}
}
static void draw_screen8(DsMapArt& art,const Bytes& tiles,const Bytes& screen,const std::vector<uint32_t>& pal,bool opaque){
  const size_t base=block(screen,"NRCS");
  const int cols=u16(screen,base+8)/8,rows=u16(screen,base+10)/8;
  for(int y=0;y<std::min(rows*8,art.height);++y)for(int x=0;x<std::min(cols*8,art.width);++x){
    const unsigned entry=u16(screen,base+20+2*((y/8)*cols+x/8));
    const size_t i=(entry&1023)*64+((entry&2048)?7-y%8:y%8)*8+((entry&1024)?7-x%8:x%8);
    const unsigned v=i<tiles.size()?tiles[i]:0;
    const unsigned color=(entry>>12)*256+v;
    if((v||opaque)&&color<pal.size())art.background[y*art.width+x]=pal[color];
  }
}
DsMapArt ds_map_art(const std::vector<uint8_t>& rom,DsGame game){
 using namespace pd_ds_data;
 try{
  if(ds_unova(game)){
    const auto narc=file(rom,ds_unova_sequel(game)?"/a/0/8/4":"/a/0/8/5");
    const auto pal=palette(member(narc,0));
    DsMapArt art;art.width=256;art.height=168;art.background.resize(256*168,pal.at(0));
    draw_screen8(art,tiles(member(narc,7)),member(narc,16),pal,false);
    draw_screen8(art,tiles(member(narc,5)),member(narc,17),pal,false);
    const auto headTiles=tiles(member(narc,8)),cells=member(narc,12);
    const auto headPal=palette(member(narc,2));
    auto head=cell(cells,headTiles,headPal,8,-1,48,true);
    const auto arrow=cell(cells,headTiles,headPal,7,-1,48,true);
    for(size_t i=0;i<head.size();++i)if(arrow[i]>>24)head[i]=arrow[i];
    art.heads.push_back(std::move(head));
    art.cursor=cell(cells,headTiles,headPal,12,-1,32,true);
    art.blocks.resize(6);
    art.blocks[0]=cell(cells,headTiles,headPal,4,-1,16,true);
    art.blocks[3]=ds_unova_sequel(game)?art.blocks[0]:cell(cells,headTiles,headPal,19,-1,16,true);
    art.blocks[4]=cell(cells,headTiles,headPal,20,-1,16,true);
    art.blocks[5]=cell(cells,headTiles,headPal,23,-1,16,true);
    const auto middle=cell(cells,headTiles,headPal,19,-1,16,true);
    for(size_t i=0;i<middle.size();++i)if(middle[i]>>24)art.blocks[5][i]=middle[i];
    art.base=art.background;
    return art;
  }
  if(game==DsGame::Johto){
    const auto narc=file(rom,"/a/1/4/4");
    DsMapArt art;art.width=47*8;art.height=20*8;art.background.resize(art.width*art.height);
    art.tiles=tiles(member(narc,10));art.screen=member(narc,11);
    block(art.screen,"NRCS");
    for(int i=0;i<6;++i)art.skins.push_back(palette(member(narc,20+i)));
    const auto headTiles=tiles(member(narc,1)),cells=member(narc,2);
    const auto headPal=palette(member(narc,0));
    art.cursor=cell(cells,headTiles,headPal,1,-1);
    for(int i=2;i<=3;++i)art.heads.push_back(cell(cells,headTiles,headPal,i,-1));
    return art;
  }
  auto narc=file(rom,mapPath);auto pal=palette(member(narc,mapAssets[0])),sprPal=palette(member(narc,mapAssets[1]));auto tile=tiles(member(narc,mapAssets[2]));
  std::vector<uint32_t> full(256*192,pal.at(0));
  for(int id:mapScreens){auto screen=member(narc,id);size_t o=block(screen,"NRCS");int cols=u16(screen,o+8)/8,rows=u16(screen,o+10)/8;
   for(int ty=0;ty<std::min(rows,24);ty++)for(int tx=0;tx<std::min(cols,32);tx++){
    unsigned e=u16(screen,o+20+(ty*cols+tx)*2);
    for(int y=0;y<8;y++)for(int x=0;x<8;x++){int v=pixel(tile,e&1023,(e&1024)?7-x:x,(e&2048)?7-y:y);if(v)full[(ty*8+y)*256+tx*8+x]=pal.at((e>>12)*16+v);}
   }
  }
  DsMapArt art;art.width=mapWidth;art.height=mapHeight;art.background.resize(art.width*art.height);
  for(int y=0;y<art.height;y++)std::copy_n(full.begin()+(y+mapTop)*256+mapLeft,art.width,art.background.begin()+y*art.width);
  auto blockTiles=tiles(member(narc,mapAssets[3])),cells=member(narc,mapAssets[4]);
  for(int shape=0;shape<7;shape++)for(int variant=0;variant<3;variant++)art.blocks.push_back(cell(cells,blockTiles,sprPal,shape,5+variant));
  auto headTiles=tiles(member(narc,mapAssets[5])),heads=member(narc,mapAssets[6]);
  for(int i=0;i<2;i++)art.heads.push_back(cell(heads,headTiles,sprPal,i,-1));
  art.base=art.background;
  return art;
 }catch(const std::exception& e){std::fprintf(stderr,"DS map art: %s\n",e.what());return {};}
}
void ds_map_update(DsMapArt& art,const DsCompanion& s){
  auto sprite=[&](const std::vector<uint32_t>& pixels,int cx,int cy){
    for(int y=0;y<16;++y)for(int x=0;x<16;++x){
      const int dx=cx-8+x,dy=cy-8+y;
      const auto v=pixels[y*16+x];
      if((v>>24)&&dx>=0&&dx<art.width&&dy>=0&&dy<art.height)art.background[dy*art.width+dx]=v;
    }
  };
  if(s.game==DsGame::Platinum&&s.valid&&!art.base.empty()&&(art.skin<0||art.arrivals!=s.arrivals)){
    art.skin=0;art.arrivals=s.arrivals;
    art.background=art.base;
    for(const auto& mark:pd_ds_data::mapMarks){
      const bool visited=mark.arrival<56&&((s.arrivals>>mark.arrival)&1);
      if(mark.hidden&&!visited)continue;
      const int index=mark.shape*3+(visited?1+mark.palette:0);
      if(index>=0&&size_t(index)<art.blocks.size())
        sprite(art.blocks[index],mark.x-int(pd_ds_data::mapLeft),mark.y-int(pd_ds_data::mapTop));
    }
    return;
  }
  if(ds_unova(s.game)&&s.valid&&!art.base.empty()&&art.arrivals!=s.arrivals){
    art.arrivals=s.arrivals;
    art.background=art.base;
    const bool sequel=ds_unova_sequel(s.game);
    const auto* points=sequel?pd_ds_data::sequelMapPoints:pd_ds_data::unovaMapPoints;
    const size_t count=sequel?std::size(pd_ds_data::sequelMapPoints):std::size(pd_ds_data::unovaMapPoints);
    for(size_t i=0;i<count;++i){
      const auto& p=points[i];
      const int kind=p[4],flag=p[5];
      int bit=-1;
      if(kind==4)bit=!sequel||p[6]==2463?62:p[6]==2464?63:-1;
      else if((kind==0||kind==3||kind==5)&&flag>=0)bit=flag-(sequel?2469:2480);
      if(bit<0||bit>=64||!(s.arrivals>>bit&1))continue;
      sprite(art.blocks[kind],p[2],p[3]);
    }
    return;
  }
  if(s.game!=DsGame::Johto||!s.valid||art.skins.empty()||
     (art.skin==s.mapSkin&&art.unlock==s.mapUnlock&&art.arrivals==s.arrivals))return;
  art.skin=s.mapSkin;art.unlock=s.mapUnlock;art.arrivals=s.arrivals;
  const auto& pal=art.skins[art.skin];
  const size_t base=block(art.screen,"NRCS")+20;
  // Pokégear composes a 47x20 view from a 64-wide grid of cartridge tiles.
  auto copy=[&](int dx,int dy,int w,int h,int sx,int sy){
    for(int ty=0;ty<h;++ty)for(int tx=0;tx<w;++tx){
      if(dx+tx<0||dx+tx>=47||dy+ty<0||dy+ty>=20)continue;
      const size_t entry=base+2*((sy+ty)*64+sx+tx);
      if(entry+1>=art.screen.size())continue;
      const unsigned e=u16(art.screen,entry),tile=e&1023;
      for(int y=0;y<8;++y)for(int x=0;x<8;++x){
        const size_t i=tile*64+((e&2048)?7-y:y)*8+((e&1024)?7-x:x);
        const unsigned color=i<art.tiles.size()?art.tiles[i]:0;
        art.background[((dy+ty)*8+y)*art.width+(dx+tx)*8+x]=color<pal.size()?pal[color]:0;
      }
    }
  };
  copy(0,0,47,20,0,0);
  if(!art.unlock)copy(22,0,6,20,48,0);
  if(art.unlock==1)copy(29,0,3,20,54,0);
  for(const auto& p:pd_ds_data::pgCovers){
    if(art.unlock<2&&p[7]==0)continue;
    if(!(art.arrivals>>(p[0]-pd_ds_data::pg_firstFlag)&1))copy(p[1],p[2],p[3],p[4],p[5],p[6]);
  }
  if(!(art.arrivals>>15&1))copy(1,9,5,6,48,27);
}
int ds_map_section(int x,int y){if(x<0 || x>=30 || y<0 || y>=30)return -1;int s=pd_ds_data::mapGrid[y*30+x];return s==65535?-1:s;}
const char* ds_map_name(int s){return s>=0 && size_t(s)<std::size(pd_ds_data::mapSections)?pd_ds_data::mapSections[s]:"";}

namespace {
// The LZ10/LZ11 front-picture members use the same format as Android's Gen5Lz.
Bytes unpack_picture(const Bytes& packed){
  const int type=packed.at(0);
  if(type!=0x10 && type!=0x11)return {};
  size_t len=u32(packed,0)>>8,pos=4;
  if(!len){len=u32(packed,4);pos=8;}
  if(!len || len>65536)return {};
  Bytes out;
  out.reserve(len);
  while(out.size()<len){
    const int flags=packed.at(pos++);
    for(int bit=7;bit>=0 && out.size()<len;--bit){
      const int a=packed.at(pos++);
      if(!(flags&(1<<bit))){out.push_back(a);continue;}
      int n=(a>>4)+1,offset=a&15;
      if(type==0x10)n+=2;
      else if((a>>4)==0){
        const int b=packed.at(pos++);
        n=offset*16+(b>>4)+0x11;offset=b&15;
      }else if((a>>4)==1){
        const int b=packed.at(pos++),c=packed.at(pos++);
        n=offset*4096+b*16+(c>>4)+0x111;offset=c&15;
      }
      const size_t back=offset*256+packed.at(pos++)+1;
      if(back>out.size())return {};
      for(int i=0;i<n && out.size()<len;++i)out.push_back(out[out.size()-back]);
    }
  }
  return out;
}

void read_regional(DsDexArt& art,const Bytes& numbers,bool unova,int speciesCount){
  std::vector<int> order(unova?speciesCount:speciesCount+1);
  int last=0;
  for(int species=1;species<=speciesCount && size_t(species*2+2)<=numbers.size();++species){
    const int number=u16(numbers,species*2);
    if(number>=int(order.size()) || (!unova && number==0))continue;
    if(order[number])return; // inconsistent numbering
    order[number]=species;
    last=std::max(last,number);
  }
  const auto first=order.begin()+(!unova);
  const auto end=order.begin()+last+1;
  if(last>0 && std::find(first,end,0)==end){
    order.resize(last+1);
    art.regional=std::move(order);
  }
}

// Message-bank formats from Android's Gen4Text.kt and Gen5Text.kt.
std::vector<std::string> dex_text(const Bytes& bank,bool unova,int speciesCount){
  const size_t count=std::min<int>(u16(bank,unova?2:0),speciesCount+1);
  const size_t section=unova?u32(bank,12):0;
  const unsigned key=unova?0:u16(bank,2);
  std::vector<std::string> lines(count);
  for(size_t i=0;i<count;++i){
    const size_t record=section+4+i*8;
    if(record+8>bank.size())break;
    const uint32_t mask=(765u*(i+1)*key)&0xffff;
    const uint32_t repeated=mask|(mask<<16);
    const size_t start=section+(u32(bank,record)^repeated);
    const size_t length=unova?u16(bank,record+4):u32(bank,record+4)^repeated;
    if(!length || start>bank.size() || length>(bank.size()-start)/2)continue;
    std::vector<uint16_t> codes(length);
    if(unova){
      uint16_t seed=u16(bank,start+2*(length-1))^0xffff;
      for(size_t j=length;j>0;--j){
        codes[j-1]=u16(bank,start+2*(j-1))^seed;
        seed=(seed>>3)|(seed<<13);
      }
    }else{
      uint16_t seed=(i+1)*596947u;
      for(size_t j=0;j<length;++j,seed+=18749)
        codes[j]=u16(bank,start+2*j)^seed;
    }
    auto& out=lines[i];
    for(size_t j=0;j<length;){
      const uint16_t code=codes[j++];
      if(code==0xffff)break;
      if(code==(unova?0xf000:0xfffe)){
        if(j+1>=length)break;
        j+=2+codes[j+1];
        continue;
      }
      if(code==(unova?0xfffe:0xe000)){
        if(!out.empty() && out.back()!=' ')out+=' ';
      }else if(unova){
        if(code>=32 && code<127)out+=char(code);
        else if(code==0x2019)out+='\'';
        else if(code==0x00e9)out+="é";
        else if(code==0x246d || code==0x246e)out+=code==0x246d?"♂":"♀";
      }else{
        if(code==0x188){out+="é";continue;}
        if(code==0x1b3){out+='\'';continue;}
        if(code==0x1b4 || code==0x1b5){out+='"';continue;}
        if(code==0x1bb || code==0x1bc){out+=code==0x1bb?"♂":"♀";continue;}
        for(const auto& c:pd_ds_data::chars)if(c.code==code){out+=c.text;break;}
      }
    }
  }
  return lines;
}
}

DsDexArt ds_dex_art(const std::vector<uint8_t>& rom,DsGame game){
  DsDexArt art;
  if(game==DsGame::Unsupported)return art;
  const bool unova=ds_unova(game),johto=game==DsGame::Johto;
  const int count=unova?649:493;
  try{
    // The same archives and member indices used by Android's NdsArt.kt.
    const auto personal=file(rom,unova?"/a/0/1/6":johto?"/a/0/0/2":"/poketool/personal/pl_personal.narc");
    Bytes heights,weights;
    if(!unova){
      try{
        const auto zukan=file(rom,johto?"/a/0/7/4":"/application/zukanlist/zkn_data/zukan_data_gira.narc");
        heights=member(zukan,0);weights=member(zukan,1);
      }catch(const std::exception&){}
    }
    std::vector<DsDexDetails> details(count+1);
    for(int species=1;species<=count;++species){
      const auto record=member(personal,species);
      if(record.size()<6)throw std::runtime_error("short personal record");
      auto& entry=details[species];
      std::copy_n(record.begin(),6,entry.stats.begin());
      const size_t growthOffset=unova?0x15:0x13;
      if(record.size()>growthOffset && record[growthOffset]<6)entry.growth=record[growthOffset];
      const size_t machineOffset=unova?0x28:0x1c;
      if(record.size()>=machineOffset+entry.machineBits.size()){
        std::copy_n(record.begin()+machineOffset,entry.machineBits.size(),entry.machineBits.begin());
        entry.hasMachines=true;
      }
      if(unova && record.size()>=0x28){
        entry.heightDm=u16(record,0x24);entry.weightHg=u16(record,0x26);
      }else if(!unova && size_t(species*4+4)<=std::min(heights.size(),weights.size())){
        entry.heightDm=u32(heights,species*4);entry.weightHg=u32(weights,species*4);
      }
    }
    art.details=std::move(details);
    const auto numbers=unova?member(personal,ds_unova_sequel(game)?709:668):
      member(file(rom,johto?"/a/1/3/8":"/poketool/pl_pokezukan.narc"),0);
    read_regional(art,numbers,unova,count);
  }catch(const std::exception&){}
  if(!art.details.empty()){
    try{
      const auto messages=file(rom,unova?"/a/0/0/2":johto?"/a/0/2/7":"/msgdata/pl_msg.narc");
      const bool soulSilver=johto && rom.size()>0x0e && rom[0x0d]=='P' && rom[0x0e]=='G';
      const bool sequel=ds_unova_sequel(game);
      const int description=sequel?442:unova?(game==DsGame::White?236:235):johto?(soulSilver?804:803):706;
      const int category=sequel?464:unova?260:johto?816:711;
      auto descriptions=dex_text(member(messages,description),unova,count);
      auto categories=dex_text(member(messages,category),unova,count);
      for(size_t i=1;i<art.details.size();++i){
        if(i<descriptions.size())art.details[i].description=std::move(descriptions[i]);
        if(i<categories.size())art.details[i].category=std::move(categories[i]);
      }
    }catch(const std::exception&){}
  }
  try{art.pictures=file(rom,unova||johto?"/a/0/0/4":"/poketool/pokegra/pl_pokegra.narc");}
  catch(const std::exception&){}
  try{art.learnsets=file(rom,unova?"/a/0/1/8":johto?"/a/0/3/3":"/poketool/personal/wotbl.narc");}
  catch(const std::exception&){}
  try{
    const auto evo=file(rom,unova?"/a/0/1/9":johto?"/a/0/3/4":"/poketool/personal/evo.narc");
    std::vector<DsDexEvolution> entries;
    for(int species=1;species<=count;++species){
      const auto record=member(evo,species);
      for(size_t i=0;i+6<=record.size() && i<42;i+=6){
        const int method=u16(record,i),param=u16(record,i+2),to=u16(record,i+4);
        if(method && to>0 && to<=count && to!=species)entries.push_back({species,to,method,param});
      }
    }
    if(unova){
      // The two Gen 5 trade-pair species are stored without their partner ID.
      std::vector<int> pair;
      for(const auto& link:entries)
        if(link.method==7 && std::find(pair.begin(),pair.end(),link.from)==pair.end())pair.push_back(link.from);
      if(pair.size()==2)for(auto& link:entries)
        if(link.method==7 && !link.param)link.param=link.from==pair[0]?pair[1]:pair[0];
    }
    art.evolutions=std::move(entries);
  }catch(const std::exception&){}
  return art;
}

std::vector<DsDexEvolution> ds_dex_evolutions(const DsDexArt& art,int species){
  std::vector<DsDexEvolution> links;
  for(const auto& evo:art.evolutions)
    if(evo.from==species || evo.to==species)links.push_back(evo);
  return links;
}

std::vector<DsDexMove> ds_level_moves(const DsDexArt& art,DsGame game,int species){
  std::vector<DsDexMove> moves;
  const bool unova=ds_unova(game);
  if(species<=0 || species>(unova?649:493))return moves;
  try{
    const auto record=member(art.learnsets,species);
    const size_t stride=unova?4:2;
    for(size_t i=0;i+stride<=record.size();i+=stride){
      const int word=u16(record,i);
      if(word==0xffff)break;
      const int move=unova?word:word&0x1ff,level=unova?u16(record,i+2):word>>9;
      if(move && level<=100)moves.push_back({level,move});
    }
  }catch(const std::exception&){}
  return moves;
}

DsDexPage ds_dex_page(const DsDexArt& art,DsGame game,int species){
  DsDexPage page;
  const bool unova=ds_unova(game);
  if(species<=0 || species>(unova?649:493))return page;
  if(size_t(species)<art.details.size() && art.details[species].hasMachines){
    const auto& bits=art.details[species].machineBits;
    const int* moves=unova?pd_ds_data::tmMovesBlack:
      game==DsGame::Johto?pd_ds_data::tmMovesHeartGold:pd_ds_data::tmMovesPlatinum;
    const int total=unova?101:100,tmCount=unova?95:92;
    for(int i=0;i<total;++i){
      if(!(bits[i/8]&(1u<<(i%8))))continue;
      const bool hm=i>=tmCount;
      page.machines.push_back({hm,hm?i-tmCount+1:i+1,moves[i]});
    }
  }
  page.moves=ds_level_moves(art,game,species);
  try{
    const int base=species*(unova?20:6);
    const auto pal=palette(member(art.pictures,base+(unova?18:4)));
    if(pal.size()<16)return page;
    Bytes chars;
    // Try the primary picture, then the alternate gender slot if it is empty.
    for(int id : {unova?0:3,unova?1:2}){
      try{
        auto image=member(art.pictures,base+id);
        if(unova)image=unpack_picture(image);
        chars=tiles(image);
        if(chars.size()>=(unova?4608:6400))break;
      }catch(const std::exception&){chars.clear();}
    }
    const int side=unova?96:80;
    if(chars.size()<(unova?4608:6400))return page;
    page.sprite.resize(side*side);
    page.side=side;
    if(unova){
      constexpr int blocks[][4]={{0,0,64,64},{64,0,32,64},{0,64,64,32},{64,64,32,32}};
      int tile=0;
      for(const auto& b:blocks)for(int ty=0;ty<b[3];ty+=8)for(int tx=0;tx<b[2];tx+=8,++tile)
        for(int y=0;y<8;++y)for(int x=0;x<8;++x){
          const int color=pixel(chars,tile,x,y);
          if(color)page.sprite[(b[1]+ty+y)*side+b[0]+tx+x]=pal[color];
        }
    }else{
      uint32_t seed=u16(chars,0);
      for(int i=0;i<6400;i+=2){
        const auto v=u16(chars,i)^uint16_t(seed);
        chars[i]=v;chars[i+1]=v>>8;
        seed=seed*1103515245u+24691u;
      }
      for(int y=0;y<side;++y)for(int x=0;x<side;++x){
        const int color=(chars[y*side+x/2]>>(x%2*4))&15;
        if(color)page.sprite[y*side+x]=pal[color];
      }
    }
  }catch(const std::exception&){page.sprite.clear();page.side=0;}
  return page;
}

std::vector<std::vector<uint32_t>> ds_party_icons(const std::vector<uint8_t>& rom,DsGame game){
  using namespace pd_ds_data;
  try{
    const bool unova=ds_unova(game),sequel=ds_unova_sequel(game);
    const bool johto=game==DsGame::Johto;
    auto narc=file(rom,unova?"/a/0/0/7":johto?"/a/0/2/0":iconPath);
    auto pal=palette(member(narc,0));
    const int egg=ds_egg_icon(game);
    const auto* banks=sequel?sequelIconPalettes:unova?unovaIconPalettes:johto?johtoIconPalettes:iconPalettes;
    std::vector<std::vector<uint32_t>> icons(egg+1);
    for(int species=1;species<=egg;species++){
      const int id=(sequel?8:unova||johto?7:iconBase)+(unova?2:1)*species;
      Bytes tile;
      try {
        const auto graphic=member(narc,id);
        tile=johto||unova?slice(graphic,block(graphic,"RAHC")+32,512):tiles(graphic);
      }catch(const std::exception&){
        if(!johto&&!unova)throw;
        continue;
      }
      const unsigned bank=banks[species];
      if((bank+1)*16>pal.size())continue;
      auto& icon=icons[species];icon.resize(32*32,0);
      for(int y=0;y<32;y++)for(int x=0;x<32;x++){
        int v=pixel(tile,(y/8)*4+x/8,x%8,y%8);
        if(v)icon[y*32+x]=pal.at(bank*16+v);
      }
    }
    return icons;
  }catch(const std::exception& e){std::fprintf(stderr,"DS party art: %s\n",e.what());return {};}
}

std::vector<std::vector<uint32_t>> ds_hud_item_icons(const std::vector<uint8_t>& rom,DsGame game){
  using namespace pd_ds_data;
  const auto range=[](const auto& a){return std::pair{std::begin(a),std::end(a)};};
  const auto [first,last]=ds_unova_sequel(game)?range(sequelHudItemArt):ds_unova(game)?range(unovaHudItemArt):
    game==DsGame::Johto?range(johtoHudItemArt):range(platinumHudItemArt);
  try {
    const auto narc=file(rom,ds_unova(game)?"/a/0/2/5":game==DsGame::Johto?"/a/0/1/8":"/itemtool/itemdata/item_icon.narc");
    const auto maxId=std::max_element(first,last,[](const auto& a,const auto& b){return a[0]<b[0];});
    std::vector<std::vector<uint32_t>> icons((*maxId)[0]+1);
    for(auto i=first;i!=last;++i){
      try {
        const auto pal=palette(member(narc,(*i)[1]&0xffff));
        const auto graphic=member(narc,(*i)[1]>>16);
        const auto tile=slice(graphic,block(graphic,"RAHC")+32,512);
        if(pal.size()<16)continue;
        auto& icon=icons[(*i)[0]];
        icon.resize(1024);
        for(int y=0;y<32;++y)for(int x=0;x<32;++x){
          const int v=pixel(tile,(y/8)*4+x/8,x%8,y%8);
          if(v)icon[y*32+x]=pal[v];
        }
      }catch(const std::exception&){}
    }
    return icons;
  }catch(const std::exception& e){std::fprintf(stderr,"DS HUD item art: %s\n",e.what());return {};}
}

// Each move's base PP, from the cart's own move table (Platinum's pl_waza_tbl.narc, HeartGold / SoulSilver's a/0/1/1,
// a 16-byte record per move with PP at +6; Black / White and their sequels' a/0/2/1, PP at +5). Checked against two
// moves every one of these games gives 35 (Pound, Tackle); empty when the cart doesn't match.
std::vector<uint8_t> ds_move_base_pp(const std::vector<uint8_t>& rom,DsGame game){
 try{
  const bool unova=ds_unova(game);
  const auto narc=file(rom,unova?"/a/0/2/1":game==DsGame::Platinum?"/poketool/waza/pl_waza_tbl.narc":"/a/0/1/1");
  const unsigned count=u16(narc,u16(narc,12)+8), at=unova?5:6;
  std::vector<uint8_t> pp(count,0);
  for(unsigned id=1;id<count;++id){
   const auto record=member(narc,id);
   if(record.size()>at)pp[id]=record[at];
  }
  if(count<=33 || pp[1]!=35 || pp[33]!=35)return {};
  return pp;
 }catch(const std::exception&){return {};}
}
