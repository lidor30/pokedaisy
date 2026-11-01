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
std::vector<uint32_t> cell(const Bytes& cells,const Bytes& tile,const std::vector<uint32_t>& pal,int index,int overridePal){
 size_t o=block(cells,"KBEC"),count=u16(cells,o+8),data=o+8+u32(cells,o+12),stride=u16(cells,o+10)==1?16:8,base=data+count*stride;
 if(index<0 || size_t(index)>=count)throw std::runtime_error("bad cell");
 size_t e=data+index*stride;int num=u16(cells,e);size_t start=base+u32(cells,e+4);
 static const int dims[3][4][2]={{{8,8},{16,16},{32,32},{64,64}},{{16,8},{32,8},{32,16},{64,32}},{{8,16},{8,32},{16,32},{32,64}}};
 std::vector<uint32_t> out(256,0);
 for(int k=num-1;k>=0;k--){size_t p=start+k*6;unsigned a=u16(cells,p),b=u16(cells,p+2),c=u16(cells,p+4),shape=a>>14,size=b>>14;if(shape>=3)continue;
  int w=dims[shape][size][0],h=dims[shape][size][1],ox=b&511,oy=a&255;if(ox>=256)ox-=512;if(oy>=128)oy-=256;
  int bank=overridePal>=0?overridePal:c>>12;
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){
   int dx=8+ox+x,dy=8+oy+y;if(dx<0 || dx>=16 || dy<0 || dy>=16)continue;
   int sx=(b&4096)?w-1-x:x,sy=(b&8192)?h-1-y:y;
   int v=pixel(tile,(c&1023)+(sy/8)*(w/8)+sx/8,sx%8,sy%8);
   if(v)out[dy*16+dx]=pal.at(bank*16+v);
  }
 }
 return out;
}
}
DsMapArt ds_map_art(const std::vector<uint8_t>& rom){
 using namespace pd_ds_data;
 try{
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
  return art;
 }catch(const std::exception& e){std::fprintf(stderr,"DS map art: %s\n",e.what());return {};}
}
int ds_map_section(int x,int y){if(x<0 || x>=30 || y<0 || y>=30)return -1;int s=pd_ds_data::mapGrid[y*30+x];return s==65535?-1:s;}
const char* ds_map_name(int s){return s>=0 && size_t(s)<std::size(pd_ds_data::mapSections)?pd_ds_data::mapSections[s]:"";}

std::vector<std::vector<uint32_t>> ds_party_icons(const std::vector<uint8_t>& rom){
  using namespace pd_ds_data;
  try{
    auto narc=file(rom,iconPath);auto pal=palette(member(narc,0));
    std::vector<std::vector<uint32_t>> icons(iconEgg+1);
    for(int species=1;species<=iconEgg;species++){
      auto tile=tiles(member(narc,iconBase+species));auto& icon=icons[species];icon.resize(32*32,0);
      int bank=iconPalettes[species];
      for(int y=0;y<32;y++)for(int x=0;x<32;x++){
        int v=pixel(tile,(y/8)*4+x/8,x%8,y%8);
        if(v)icon[y*32+x]=pal.at(bank*16+v);
      }
    }
    return icons;
  }catch(const std::exception& e){std::fprintf(stderr,"DS party art: %s\n",e.what());return {};}
}
