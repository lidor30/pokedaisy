// GPL-3.0. Reuse app-owned map data and cartridge artwork across the Map and HUD.
#include "map_view.h"
#include "data_gen.h"
#include "companion_style.h"
#include <string_view>
#include <algorithm>
extern "C" {
#include "pd_ui.h"
}

namespace {
bool has_art(const DsMapArt& art){
  return art.width>0&&art.height>0&&art.background.size()==size_t(art.width)*art.height;
}
struct MapHead {
  const std::vector<uint32_t>* pixels=nullptr;
  int size=0;
};
MapHead head(const DsCompanion& s,const DsMapArt& art){
  const bool unova=ds_unova(s.game);
  const unsigned index=unova?0:s.playerGender==1?1:0;
  return index<art.heads.size()?MapHead{&art.heads[index],unova?48:16}:MapHead{};
}
const char* map_label(const DsCompanion& s,int x,int y){
  using namespace pd_ds_data;
  if(s.game==DsGame::Platinum)return ds_map_name(ds_map_section(x,y));
  if(s.game==DsGame::Johto&&x>=0&&x<47&&y>=0&&y<20){
    const unsigned id=pgGrid[y*47+x];
    return id<std::size(pgPlaces)?pgPlaces[id]:"";
  }
  if(ds_unova(s.game)){
    const bool sequel=ds_unova_sequel(s.game);
    const auto* points=sequel?sequelMapPoints:unovaMapPoints;
    const size_t count=sequel?std::size(sequelMapPoints):std::size(unovaMapPoints);
    for(size_t i=0;i<count;++i)if(points[i][2]/8==x&&points[i][3]/8==y)return ds_place(points[i][0],s.game);
  }
  return "";
}
void map_head(pd_canvas* c,const DsCompanion& s,const DsMapArt& art,int x,int y){
  const auto sprite=head(s,art);
  if(!sprite.pixels)return;
  for(int row=0;row<sprite.size;++row)for(int col=0;col<sprite.size;++col){
    const auto p=(*sprite.pixels)[row*sprite.size+col];
    if(p>>24)pd_fill(c,x-sprite.size/2+col,y-sprite.size/2+row,1,1,p&0xffffff);
  }
}
}

DsMapPosition ds_map_cursor_max(DsGame game){
  if(game==DsGame::Johto)return {46,19};
  if(ds_unova(game))return {31,20};
  return {29,29};
}

std::vector<DsMapPlace> ds_map_places(const DsCompanion& s){
  std::vector<DsMapPlace> places;
  const auto bounds=ds_map_cursor_max(s.game);
  for(int y=0;y<=bounds.y;++y)for(int x=0;x<=bounds.x;++x){
    const std::string_view name=map_label(s,x,y);
    if(name.empty())continue;
    const auto same=[&](const DsMapPlace& place){return name==place.name;};
    if(std::none_of(places.begin(),places.end(),same))places.push_back({{x,y},name.data()});
  }
  std::sort(places.begin(),places.end(),[](const auto& a,const auto& b){
    return std::string_view(a.name)<std::string_view(b.name);
  });
  return places;
}

DsMapPosition ds_map_position(const DsCompanion& s){
  using namespace pd_ds_data;
  if(s.mapX<0||s.mapY<0)return {};
  if(s.game==DsGame::Platinum)return {int(mapOriginX)+s.mapX*int(mapTile)+3,int(mapOriginY)+s.mapY*int(mapTile)+3};
  if(ds_unova(s.game)){
    const bool sequel=ds_unova_sequel(s.game);
    const auto* zones=sequel?sequelMapZones:unovaMapZones;
    const size_t count=sequel?std::size(sequelMapZones):std::size(unovaMapZones);
    if(size_t(s.map)>=count||zones[s.map]<0)return {};
    const auto& p=(sequel?sequelMapPoints:unovaMapPoints)[zones[s.map]];
    return {p[2],p[3]};
  }
  return {s.mapX*8+4,s.mapY*8+4};
}

static void map_actions(pd_canvas* c,pd_map_viewport view,int selected){
  const int y=view.y+20, right=view.x+view.w-4;
  const char* labels[]={"PLACES","ME"};
  const int widths[]={pd_text_width(labels[0])+16,pd_text_width(labels[1])+16};
  for(int i=0;i<2;++i){
    const int x=right-widths[1]-(i?0:widths[0]+4), w=widths[i];
    pd_title_box(c,x,y,w,22,selected==i?PD_ROW_SELECTED:PD_TITLE_FILL);
    if(selected==i)pd_fill(c,x+3,y+18,w-6,2,PD_VALUE);
    pd_text(c,x+8,pd_text_y(y,22),labels[i],selected==i?PD_VALUE:PD_TITLE_TEXT,PD_TITLE_SHADOW);
  }
}

void ds_map_view(pd_canvas* c,const DsCompanion& s,const DsMapArt& art,int mapX,int mapY,int action){
  ds_companion_backdrop(c,s.game,PD_TAB_MAP);
  if(!has_art(art)){pd_ui_map_view(c,nullptr);return;}
  auto pixels=art.background;
  pd_canvas page;pd_canvas_init(&page,art.width,art.height,pixels.data());
  const auto player=ds_map_position(s);
  if(player.x>=0&&player.y>=0)map_head(&page,s,art,player.x,player.y);
  const char* label=map_label(s,mapX,mapY);
  if(!*label&&mapX==s.mapX&&mapY==s.mapY)label=ds_place(s.map,s.game);
  const bool platinum=s.game==DsGame::Platinum;
  const int tile=platinum?pd_ds_data::mapTile:8;
  const int x=mapX<0?-1:mapX*tile+(platinum?int(pd_ds_data::mapOriginX):0);
  const int y=mapY<0?-1:mapY*tile+(platinum?int(pd_ds_data::mapOriginY):0);
  if(!art.cursor.empty()&&x>=0&&y>=0){
    const int side=ds_unova(s.game)?32:16;
    const int cx=mapX==s.mapX&&mapY==s.mapY&&player.x>=0?player.x:x+tile/2;
    const int cy=mapX==s.mapX&&mapY==s.mapY&&player.y>=0?player.y:y+tile/2;
    for(int row=0;row<side;++row)for(int col=0;col<side;++col){
      const auto p=art.cursor[row*side+col];
      if(p>>24)pd_fill(&page,cx-side/2+col,cy-side/2+row,1,1,p&0xffffff);
    }
  }
  pd_map_source source{pixels.data(),art.width,art.height,*label?label:"No location",
    art.cursor.empty()?x:-1,art.cursor.empty()?y:-1,tile,tile};
  map_actions(c,pd_ui_map_view(c,&source),action);
}

void ds_draw_minimap(pd_canvas* c,const DsCompanion& s,const DsMapArt& art,int x,int y,int w,int h){
  const auto player=ds_map_position(s);
  if(!has_art(art)||player.x<0||player.y<0)return;
  w=std::min(w,art.width);h=std::min(h,art.height);
  const int left=std::clamp(player.x-w/2,0,art.width-w);
  const int top=std::clamp(player.y-h/2,0,art.height-h);
  for(int row=0;row<h;++row)for(int col=0;col<w;++col)
    pd_fill(c,x+col,y+row,1,1,art.background[(top+row)*art.width+left+col]&0xffffff);
  pd_clip(c,x,y,w,h);
  map_head(c,s,art,x+player.x-left,y+player.y-top);
  pd_unclip(c);
}
