// GPL-3.0. Reuse app-owned map marks/grid and ROM-decoded assets.
#include "map_view.h"
#include "data_gen.h"
extern "C" {
#include "pd_ui.h"
}
void ds_map_view(pd_canvas* c,const DsCompanion& s,const DsMapArt& art,int mapX,int mapY){
  if(art.width<1 || art.height<1 || art.width>4096 || art.height>4096 ||
     art.background.size()!=size_t(art.width)*art.height){pd_ui_map_view(c,nullptr);return;}
  // Work on a transient composed page; cartridge art remains reusable/unmodified.
  auto pixels=art.background;
  auto sprite=[&](const std::vector<uint32_t>& img,int cx,int cy){
    if(img.size()!=256)return;
    for(int y=0;y<16;y++)for(int x=0;x<16;x++){
      int dx=cx-8+x,dy=cy-8+y;
      if(dx>=0 && dx<art.width && dy>=0 && dy<art.height && (img[y*16+x]>>24))
        pixels[dy*art.width+dx]=img[y*16+x]&0xffffff;
    }
  };
  for(auto mark:pd_ds_data::mapMarks){
    bool visited=mark.arrival<56 && ((s.arrivals>>mark.arrival)&1);
    if(mark.hidden && !visited)continue;
    int variant=visited?1+mark.palette:0,index=mark.shape*3+variant;
    if(index>=0 && index<int(art.blocks.size()))sprite(art.blocks[index],mark.x-pd_ds_data::mapLeft,mark.y-pd_ds_data::mapTop);
  }
  if(s.mapX>=0 && s.mapX<30 && s.mapY>=0 && s.mapY<30 && art.heads.size()>=2)
    sprite(art.heads[s.playerGender==1?1:0],pd_ds_data::mapOriginX+s.mapX*pd_ds_data::mapTile+3,pd_ds_data::mapOriginY+s.mapY*pd_ds_data::mapTile+3);
  const char* name=ds_map_name(ds_map_section(mapX,mapY));
  pd_map_source source{pixels.data(),art.width,art.height,*name?name:"No location",
    mapX>=0 && mapX<30?pd_ds_data::mapOriginX+mapX*pd_ds_data::mapTile:-1,
    mapY>=0 && mapY<30?pd_ds_data::mapOriginY+mapY*pd_ds_data::mapTile:-1,7,7};
  pd_ui_map_view(c,&source);
}
