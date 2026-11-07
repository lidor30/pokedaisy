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
