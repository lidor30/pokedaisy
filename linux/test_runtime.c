/* Exercise the actual SDL/mGBA host. No fixture injection into emulated RAM. */
#define POCKET_TEST
#define main linux_app_main
#include "main.c"
#undef main
#include <assert.h>
static bool gba, live;
static uint32_t pausedAt;
static const char* output;
static void tap(SDL_Keycode code) {
  SDL_Event e={.type=SDL_KEYDOWN};e.key.keysym.sym=code;
  assert(SDL_PushEvent(&e)==1);e.type=SDL_KEYUP;assert(SDL_PushEvent(&e)==1);
}
static char liveName[48]="live";
static char liveSize[16]="";
static void shot(const char* name) {
  char path[1200];snprintf(path,sizeof path,"%s/%s-%s.png",output,liveName,name);
  assert(capture_frame(path,NULL));
}
static void drive(struct mCore* c, unsigned keys, int n) {
  c->setKeys(c,keys);while(n-->0)c->runFrame(c);c->setKeys(c,0);
}
// One frame of the host's loop as FLY sees it: the game, pk_call's main loop depth, the field read, the scene.
static int fly_frame(struct mCore* c,const struct pd_settings* s) {
  c->runFrame(c);pk_rest_note(c);int f=pk_field_state(c,0,100);wide_tick(c,false,s);return f;
}
// FLY as the quick menu runs it (main.c's flyAsk / flyStage), on the real game: its towns, a flight to one, the
// landing there with the field the player's again. Once a run (the first window size).
static bool flownOnce;
static void test_fly(struct mCore* c,struct pd_game* game,const uint8_t* rom,size_t len,struct pd_snapshot* snap,
                     const struct pd_settings* s,unsigned gw,unsigned gh) {
  const struct pd_config* cfg=game->cfg;
  if(flownOnce)return;
  flownOnce=true;
  if(!cfg||!cfg->flyFlagGet){printf("%s FLY: none in this config\n",liveName);return;}
  assert(fly_supported(cfg,rom,len)); // the generated addresses and CRCs fit the retail ROM
  int flags=0;
  for(int i=0;i<120;i++)flags=fly_frame(c,s);
  for(int i=0;i<300 && !pk_call_safe(c);i++)flags=fly_frame(c,s);
  assert(pk_call_safe(c));
  pd_snapshot_read(snap,game,read_memory,c);
  struct fly_targets t;
  bool busy=(flags&PK_FIELD_LOCKED) || pk_wide_scene()!=PK_WIDE_SCENE_FIELD;
  fly_targets(c,cfg,rom,len,snap,busy,snap->inBattle,&t);
  if(t.result==FLY_NO_FLYER && !strcmp(game->code,"BPRE") && game->revision==1) {
    // The FireRed save's party knows no FLY: teach it to the lead as the first move, with the game's own
    // SetMonData (FieldItems.kt's FireRed rev 1 table: SetMonData, gPlayerParty, its scratch word; MON_DATA_MOVE1 13).
    c->busWrite16(c,0x0203FFF0,19);
    const uint32_t args[4]={0x02024284,13,0x0203FFF0,0};
    assert(pk_call(c,0x08040390,args,NULL));
    c->busWrite16(c,0x0203FFF0,0);
    pd_snapshot_read(snap,game,read_memory,c);
    fly_targets(c,cfg,rom,len,snap,busy,snap->inBattle,&t);
  }
  printf("%s FLY (flags %x, scene %d): %s, %d towns, flyer slot %d\n",liveName,flags,pk_wide_scene(),t.result==FLY_OK?"ready":fly_reason(t.result),t.count,t.flyerSlot);
  if(t.result!=FLY_OK)return; // this save can't fly (no badge / nobody knows it): nothing more to check
  assert(t.count>0 && t.flyerSlot>=0);
  const char* names[FLY_MAX_TOWNS];
  for(int i=0;i<t.count;i++){const char* n=pd_mapsec_name(game,t.towns[i]);names[i]=n&&*n?n:"?";printf("  %02X %s\n",t.towns[i],names[i]);}
  int to=-1;
  for(int i=t.count-1;i>=0 && to<0;i--)if(t.towns[i]!=snap->mapsec)to=t.towns[i];
  assert(to>=0);
  present_quick(game,NULL,0,0,"FLY",names,t.count,t.count>1?1:0,gw,gh);shot("fly-list");
  for(int i=0;i<300 && !pk_call_safe(c);i++)flags=fly_frame(c,s);
  assert(fly_start(c,cfg,rom,len,snap,to,&t,false,false)==FLY_OK);
  uint32_t cb=fly_main_callback(c,cfg);
  for(int i=0;i<FLY_FADE_FRAMES;i++)flags=fly_frame(c,s);
  for(int i=0;i<300 && !pk_call_safe(c);i++)flags=fly_frame(c,s);
  assert(fly_finish(c,cfg,rom,len,to,&t,fly_main_callback(c,cfg)!=cb));
  // The game's own cut-in, flight and landing, then the field free for a second.
  int free=0,f=0;
  for(;f<1500 && free<60;f++){
    flags=fly_frame(c,s);
    free=!(flags&PK_FIELD_LOCKED) && pk_wide_scene()==PK_WIDE_SCENE_FIELD?free+1:0;
  }
  pd_snapshot_read(snap,game,read_memory,c);
  printf("%s FLY: landed in %02X %s after %d frames (asked for %02X)\n",liveName,snap->mapsec,
         pd_mapsec_name(game,snap->mapsec),f,to);
  assert(free>=60 && snap->mapsec==to);
  present_game(gw,gh);shot("fly-landed");
}
static void wait_safe(struct mCore* c,const struct pd_settings* s,int* flags) {
  for(int i=0;i<300 && !pk_call_safe(c);i++)*flags=fly_frame(c,s);
  assert(pk_call_safe(c));
}
// REPEL, BIKE and PC as the quick menu runs them (main.c's jobs), on the real game. Once a run.
static bool itemsOnce;
static void test_field_items(struct mCore* c,struct pd_game* game,const uint8_t* rom,size_t len,
                             struct pd_snapshot* snap,const struct pd_settings* s,unsigned gw,unsigned gh) {
  const struct pd_config* cfg=game->cfg;
  if(itemsOnce)return;
  itemsOnce=true;
  if(!cfg||!cfg->fieldVarGet){printf("%s ITEMS: none in this config\n",liveName);return;}
  assert(field_items_supported(cfg,rom,len) && pc_supported(cfg,rom,len) && bike_supported(cfg));
  int flags=0;
  for(int i=0;i<60;i++)flags=fly_frame(c,s);
  pd_snapshot_read(snap,game,read_memory,c);
  // REPEL: the strongest kind held, else nothing to check (the save has none).
  int repel=0;
  for(int k=0;k<REPEL_KINDS;k++)if(bag_count(snap,repel_kinds[k][0])>0)repel=repel_kinds[k][0];
  // None in the bag: one REPEL put in with the game's own AddBagItem (FieldItems.kt's PartyItemFns.addBagItem).
  uint32_t addBagItem=!strcmp(game->code,"BPEE")?0x080D6928:!strcmp(game->code,"BPGE")?(game->revision?0x0809A06C:0x0809A058)
                     :!strcmp(game->code,"BPRE")&&game->revision==1?0x0809A098:0;
  if(!repel && addBagItem) {
    wait_safe(c,s,&flags);
    const uint32_t args[4]={86,1,0,0};uint32_t ok=0;
    assert(pk_call(c,addBagItem,args,&ok) && ok==1);
    for(int i=0;i<4;i++)flags=fly_frame(c,s);
    pd_snapshot_read(snap,game,read_memory,c);
    repel=86;
  }
  if(repel) {
    int before=bag_count(snap,repel),steps=0;
    assert(repel_steps(c,cfg)==0);
    wait_safe(c,s,&flags);
    assert(repel_use(c,cfg,rom,len,repel,false,false,&steps)==ITEM_USED && steps>=100);
    assert(repel_steps(c,cfg)==steps);
    wait_safe(c,s,&flags);
    assert(repel_use(c,cfg,rom,len,repel,false,false,&steps)==ITEM_STILL_ACTIVE);
    for(int i=0;i<4;i++)flags=fly_frame(c,s);
    pd_snapshot_read(snap,game,read_memory,c);
    assert(bag_count(snap,repel)==before-1);
    printf("%s REPEL: item %d used, %d steps, %d left in the bag\n",liveName,repel,repel_steps(c,cfg),before-1);
  } else printf("%s REPEL: none in the bag\n",liveName);
  // BIKE: SELECT with the bike registered; on, then off again.
  int bike=bag_bike(snap);
  if(bike) {
    for(int round=0;round<2;round++) {
      bool was=flags&PK_FIELD_BIKE;uint16_t old=0;
      wait_safe(c,s,&flags);
      assert(bike_register(c,cfg,rom,len,bike,false,false,&old)==ITEM_USED);
      bool took=false;
      for(int tries=0;tries<15 && !took;tries++) {
        c->setKeys(c,1u<<SELECT);for(int i=0;i<2;i++)flags=fly_frame(c,s);c->setKeys(c,0);
        for(int i=0;i<40 && !took;i++){flags=fly_frame(c,s);took=((flags&PK_FIELD_BIKE)!=0)!=was;}
      }
      for(int i=0;i<20;i++)flags=fly_frame(c,s);
      bike_restore(c,cfg,bike,old);
      assert(took);
      printf("%s BIKE: %s (item %d), the register put back to %d\n",liveName,was?"got off":"got on",bike,old);
      if(!round){present_game(gw,gh);shot("bike");}
      for(int i=0;i<60;i++)flags=fly_frame(c,s);
    }
  } else printf("%s BIKE: none in the bag\n",liveName);
  // PC: the game's own PC menu opens (the field's controls held by its script), B backs out of it.
  wait_safe(c,s,&flags);
  assert(pc_open(c,cfg,rom,len,false,false)==ITEM_USED);
  for(int i=0;i<90;i++)flags=fly_frame(c,s);
  assert(flags&PK_FIELD_LOCKED);
  present_game(gw,gh);shot("pc");
  for(int k=0;k<12 && (flags&PK_FIELD_LOCKED);k++) {
    c->setKeys(c,1u<<B);for(int i=0;i<4;i++)flags=fly_frame(c,s);c->setKeys(c,0);
    for(int i=0;i<40;i++)flags=fly_frame(c,s);
  }
  assert(!(flags&PK_FIELD_LOCKED));
  printf("%s PC: opened and closed, the field free again\n",liveName);
}
static void test_frame(struct mCore* c,bool companion,int frame,struct pd_ui* ui,struct pd_snapshot* snap) {
  if(live) {
    assert(frame==0);
    bool isGba=c->platform(c)==mPLATFORM_GBA;
    // A copy: mGBA swaps its ROM mapping for a writable one once the game touches
    // the cartridge's GPIO registers (FireRed does), so the block's pointer goes stale.
    size_t len=0;const unsigned char* block=isGba?c->getMemoryBlock(c,0x08,&len):NULL;
    unsigned char* rom=malloc(len?len:1);assert(rom);if(len)memcpy(rom,block,len);
    struct pd_game game={0};
    if(isGba){pd_game_detect(&game,rom,len);game.rom=rom;game.romSize=len;}
    char crcName[16];uint32_t crc=0;c->checksum(c,&crc,mCHECKSUM_CRC32);snprintf(crcName,sizeof crcName,"%08x",crc);
    snprintf(liveName,sizeof liveName,"live-%s%s",isGba?game.code:crcName,liveSize);
    bool companionGame=isGba && game.cfg;
    // native-capture/boot/default.txt's way in: START, then A on CONTINUE and
    // through the recap pages, then B (FireRed's quest log), until the field
    // has been up for a second. The party alone isn't enough: Emerald reads it
    // from the save before its intro.
    struct pd_settings probe=*ui->settings;probe.aspect=PD_ASPECT_EXPAND;
    int fieldFrames=0,step=0;
    for(int f=0;f<6000 && fieldFrames<60;f++) {
      unsigned keys=0;
      if(f==120)keys=1<<3;                              // START
      else if(f>150 && fieldFrames==0 && f%30==0)keys=++step<=40?1:2; // A, then B
      c->setKeys(c,keys);c->runFrame(c);wide_tick(c,false,&probe);c->setKeys(c,0);
        bool field=wideSupported?pk_wide_scene()==PK_WIDE_SCENE_FIELD:f>1500;
      fieldFrames=field?fieldFrames+1:0;
    }
    assert(fieldFrames>=60);
    if(companionGame){
      pd_snapshot_read(snap,&game,read_memory,c);
      assert(snap->valid && snap->partyCount>0 && snap->posOk);
    }
    drive(c,0,120);pd_snapshot_read(snap,&game,read_memory,c);
    // WILD ENCOUNTERS: OFF holds the game's switch at 1 while the field is free, ON puts it back.
    uint32_t wild=isGba&&pk_field_configure(crc)?pk_wild_switch_find(rom,len):0;
    if(wild) {
      pk_field_wild(wild,false);
      uint8_t on=0;
      for(int i=0;i<4;i++){c->runFrame(c);pk_field_state(c,0,0);}
      on=c->busRead8(c,wild);assert(on==1);
      for(int i=0;i<4;i++){c->runFrame(c);pk_field_state(c,0,100);}
      on=c->busRead8(c,wild);assert(on==0);
      printf("%s WILD ENCOUNTERS switch 0x%08X held off and back\n",liveName,wild);
    }
    unsigned gw,gh;c->desiredVideoDimensions(c,&gw,&gh);
    present_game(gw,gh);shot("game");
    if(wideSupported) {
      // EXPAND and ZOOM OUT while walking: the window's middle must be the
      // game's own frame, pixel for pixel, on every frame it shows (frames the
      // game wrote VRAM in mid-draw can't be redrawn and don't count).
      struct pd_settings* s=ui->settings;int keep=s->aspect;
      int ow,oh;assert(!SDL_GetRendererOutputSize(renderer,&ow,&oh));
      unsigned fw=(unsigned)pk_wide_frame_w(),fh=(unsigned)pk_wide_frame_h();
      assert(fw==gw && fh==gh);
      int fieldShots=0;
      for(int a=PD_ASPECT_EXPAND;a<=PD_ASPECT_ZOOM_OUT;a++) {
        s->aspect=a;int shown=0,checked=0;
        for(int i=0;i<240;i++) {
          c->setKeys(c,(i/24)%2?1<<5:1<<4); // walk LEFT / RIGHT
          c->runFrame(c);wide_tick(c,false,s);
          if(!wideReady)continue;
          shown++;
          if(pk_wide_last_vram_torn())continue;
          if(!isGba && pk_wide_gb_last_torn())continue;
          checked++;
          int mx=(int)(wideW-fw)/2,my=(int)(wideH-fh)/2;
          for(unsigned y=0;y<fh;y++)for(unsigned x=0;x<fw;x++)
            assert(wideFrame[(y+my)*wideW+x+mx]==wideSource[y*fw+x]);
        }
        // Walking can step through a door: let the next map come up.
        c->setKeys(c,0);
        for(int i=0;i<600 && (i<30 || !wideReady);i++){c->runFrame(c);wide_tick(c,false,s);}
        fprintf(stderr,"%s %s: %ux%u window, %d of 240 frames expanded, %d checked exact (scene %d, mode %d, fault %d)\n",
                liveName,pd_aspect_name(a),wideW,wideH,shown,checked,pk_wide_scene(),pk_wide_last_mode(),pk_wide_fault());
        assert(shown>=30 && checked>=20);
        if(!wideReady) {
          // Walking found a wild Pokemon (or a menu): what was shown checked out.
          fprintf(stderr,"%s %s: left the field (scene %d)\n",liveName,pd_aspect_name(a),pk_wide_scene());
          continue;
        }
        fieldShots|=1<<a;
        unsigned w,h;wide_window(ow,oh,fw,fh,a,&w,&h);assert(w==wideW && h==wideH);
        present_game(gw,gh);shot(a==PD_ASPECT_EXPAND?"expand":"zoomout");
        // The window reaches the screen's edges: what's left is under two scale
        // steps (one for the division, one for keeping the margins even).
        int scale=lastGameRect.w/(int)wideW;
        assert(scale>=1 && lastGameRect.h==(int)wideH*scale);
        assert(ow-lastGameRect.w<2*scale || wideW>=PK_WIDE_MAX_VIEW_W);
        assert(oh-lastGameRect.h<2*scale || wideH>=PK_WIDE_MAX_VIEW_H);
      }
      assert(fieldShots); // at least one mode was checked through to its screenshot
      // The game menu over the expanded picture: its window, the game around it.
      static const char* items[]={"Resume game","Party","Save state","Load state","Quit to game list"};
      struct pd_menu m;pd_menu_init(&m,"GAME",items,5);
      s->aspect=PD_ASPECT_EXPAND;for(int i=0;i<4;i++){c->runFrame(c);wide_tick(c,false,s);}
      present_menu(&m,gw,gh);shot("menu");
      assert(lastGameRect.w>=ow-2*(lastGameRect.w/(int)wideW) && wideReady);
      // The QUICK MENU over the game, a dimmed tile with its reason in the middle.
      // The QUICK MENU over the game: all nine tiles (the bag's own REPEL / BIKE icons), a dimmed one with its reason.
      struct quick_item q[]={{.action=QUICK_PC},{.action=QUICK_FLY,.species=snap->party[0].species},
                             {.action=QUICK_REPEL,.item=84,.why="80 steps left"},{.action=QUICK_BIKE,.item=360},
                             {.action=QUICK_WILD,.off=true},{.action=QUICK_SAVE_STATE},
                             {.action=QUICK_LOAD_STATE,.why="No state saved yet"},{.action=QUICK_FF,.lit=true},
                             {.action=QUICK_COMPANION}};
      present_quick(&game,q,9,1,NULL,NULL,0,0,gw,gh);shot("quick");
      present_quick(&game,q,9,2,NULL,NULL,0,0,gw,gh);shot("quick-dim");
      const char* kinds[]={"REPEL x2  100 steps","MAX REPEL x1  250 steps"};
      present_quick(&game,q,9,2,"REPEL",kinds,2,1,gw,gh);shot("quick-repel");
      // The HUD over EXPAND (the screen's corners) and over the game's own picture, a Repel working.
      if(companionGame) {
        struct hud_state hs={80,100};
        pd_snapshot_read(snap,&game,read_memory,c);
        companion_canvas();hudDrawnKey=0;
        present_hud_game(&game,snap,&hs,0,gw,gh);shot("hud");assert(hudShown);
        s->aspect=PD_ASPECT_ORIGINAL;wideReady=false;hudDrawnKey=0;
        present_hud_game(&game,snap,&hs,0,gw,gh);shot("hud-original");assert(hudShown);
        s->aspect=PD_ASPECT_EXPAND;for(int i=0;i<4;i++){c->runFrame(c);wide_tick(c,false,s);}
      }
      s->aspect=keep;wideReady=false;
    }
    if(!companionGame) {
      free(rom);
      printf("PASS %s: boots to the field, emulation%s\n",liveName,wideSupported?" and EXPAND":" only");
      running=0;return;
    }
    struct pd_host_info host={0};ui->tab=PD_TAB_PARTY;
    pd_ui_draw(ui,&canvas,&game,snap,&host);present();shot("party");
    bool found=false;
    for(int i=0;i<ui->hitCount;i++)if(ui->hits[i].id==HIT_SLOT) {
      struct pd_hit h=ui->hits[i];pd_ui_touch(ui,h.x+1,h.y+1,true);pd_ui_touch(ui,h.x+1,h.y+1,false);found=true;break;
    }
    assert(found && ui->summarySlot==0);
    pd_ui_draw(ui,&canvas,&game,snap,&host);present();shot("summary");
    assert(pd_ui_back(ui) && ui->summarySlot==-1);
    // The real region map at the host's canvas size, and PLACES through the
    // same focused-button activation used by the physical controls.
    ui->tab=PD_TAB_MAP;
    pd_ui_draw(ui,&canvas,&game,snap,&host);
    map_cursor_sync(ui,&game,snap);
    focus_new_screen();focus_resolve(ui);
    int mapIndex=focus_index(ui,HIT_MAP);
    assert(mapIndex>=0);
    assert(map_cursor_active(ui));
    int playerPage,playerX,playerY;
    assert(pd_map_player_tile(&game,snap,&playerPage,&playerX,&playerY));
    assert(mapCursor.tx==playerX && mapCursor.ty==playerY);
    int x=mapCursor.tx,y=mapCursor.ty;
    focus_move(ui,RIGHT);assert(mapCursor.tx==x+1 && mapCursor.ty==y);
    focus_move(ui,LEFT);assert(mapCursor.tx==x && mapCursor.ty==y);
    focus_move(ui,DOWN);assert(mapCursor.tx==x && mapCursor.ty==y+1);
    focus_move(ui,UP);assert(mapCursor.tx==x && mapCursor.ty==y);
    // Every tile, including blank ocean and tiles underneath the map's buttons:
    // A inspects the cursor's tile, never the centre or an overlaid button.
    int checkedTiles=0;
    for(int ty=mapCursor.minY;ty<=mapCursor.maxY;ty++)for(int tx=mapCursor.minX;tx<=mapCursor.maxX;tx++) {
      while(mapCursor.tx<tx)focus_move(ui,RIGHT);
      while(mapCursor.tx>tx)focus_move(ui,LEFT);
      while(mapCursor.ty<ty)focus_move(ui,DOWN);
      while(mapCursor.ty>ty)focus_move(ui,UP);
      int section,dungeon;
      pd_map_pick(&game,playerPage,tx,ty,&section,&dungeon);
      if(tx==playerX && ty==playerY)section=dungeon=-1;
      assert(ui->mapSel==section && ui->mapDungeon==dungeon); // updates without A
      focus_activate(ui);
      assert(ui->overlay==PD_OVERLAY_NONE);
      checkedTiles++;
      assert(ui->mapSel==section && ui->mapDungeon==dungeon);
      pd_ui_draw(ui,&canvas,&game,snap,&host);
      map_cursor_sync(ui,&game,snap);focus_resolve(ui);
      assert(mapCursor.tx==tx && mapCursor.ty==ty);
    }
    // The grid clamps at each edge rather than jumping into another control.
    for(int k=0;k<40;k++)focus_move(ui,RIGHT);
    for(int k=0;k<40;k++)focus_move(ui,DOWN);
    assert(mapCursor.tx==mapCursor.maxX && mapCursor.ty==mapCursor.maxY && focusId==HIT_MAP);
    for(int k=0;k<40;k++)focus_move(ui,LEFT);
    for(int k=0;k<40;k++)focus_move(ui,UP);
    assert(mapCursor.tx==mapCursor.minX && mapCursor.ty==mapCursor.minY && focusId==HIT_MAP);
    // ME must also reset an empty selection: both old/new section IDs are -1.
    assert(ui->mapSel==-1 && ui->mapDungeon==-1);
    pd_ui_draw(ui,&canvas,&game,snap,&host);
    assert(map_cursor_back(ui));
    int emptyMe=focus_index(ui,HIT_MAP_ME);assert(emptyMe>=0);
    focusId=HIT_MAP_ME;focusAt=ui->hits[emptyMe];focus_activate(ui);
    pd_ui_draw(ui,&canvas,&game,snap,&host);map_cursor_sync(ui,&game,snap);
    assert(mapCursor.tx==playerX && mapCursor.ty==playerY);
    focusId=HIT_MAP;focus_resolve(ui);
    while(mapCursor.tx<playerX)focus_move(ui,RIGHT);
    while(mapCursor.ty<playerY)focus_move(ui,DOWN);
    focus_activate(ui);pd_ui_draw(ui,&canvas,&game,snap,&host);
    focus_draw(ui,focus_resolve(ui));present();shot("map");
    int placesIndex=focus_index(ui,HIT_MAP_PLACES);
    assert(placesIndex>=0);
    assert(map_cursor_back(ui) && focusId==HIT_MAP_PLACES);
    assert(!map_cursor_back(ui));
    focus_move(ui,DOWN);assert(map_cursor_active(ui));
    assert(map_cursor_back(ui));
    focus_activate(ui);
    assert(ui->overlay==PD_OVERLAY_PLACES);
    pd_ui_draw(ui,&canvas,&game,snap,&host);
    focus_resolve(ui);focus_draw(ui,focus_resolve(ui));present();shot("map-places");
    int placeIndex=focus_index(ui,focusId);
    assert(placeIndex>=0 && focusId>=HIT_PLACE);
    focus_move(ui,DOWN);assert(focusId>=HIT_PLACE);
    focus_move(ui,UP);assert(focusId>=HIT_PLACE);
    focus_activate(ui);
    assert(ui->overlay==PD_OVERLAY_NONE && ui->mapSel>=0);
    pd_ui_draw(ui,&canvas,&game,snap,&host);map_cursor_sync(ui,&game,snap);
    focus_resolve(ui);assert(map_cursor_active(ui));
    focus_draw(ui,focus_resolve(ui));present();shot("map-place-selected");
    assert(map_cursor_back(ui));
    int me=focus_index(ui,HIT_MAP_ME);assert(me>=0);focusId=HIT_MAP_ME;focusAt=ui->hits[me];
    focus_activate(ui);pd_ui_draw(ui,&canvas,&game,snap,&host);map_cursor_sync(ui,&game,snap);
    assert(mapCursor.tx==playerX && mapCursor.ty==playerY);
    // Another tab invalidates the cursor so the next visit starts at ME again.
    ui->tab=PD_TAB_PARTY;map_cursor_sync(ui,&game,snap);assert(!mapCursor.ready);
    printf("PASS live region map: %d tiles, live names, edge clamps, PLACES, selection and ME\n",checkedTiles);
    // SETTINGS with HUD and TAB BAR, their pages, then GUIDE taken off the bar: MORE opens it.
    // As the host has it: no SCREEN row, ASPECT with AUTO ZOOM IN (ZOOM OUT), the HUD; scrolled to its end too.
    host.hasHud=true;host.hideScreenMode=true;host.hasExpand=true;host.expandGame=wideSupported;host.count=1;
    host.labels[0]="HOST";snprintf(host.values[0],40,"PokeDaisy Linux");
    int keepAspect=ui->settings->aspect;ui->settings->aspect=PD_ASPECT_ZOOM_OUT;ui->tab=PD_TAB_SETTINGS;
    pd_ui_draw(ui,&canvas,&game,snap,&host);present();shot("settings");
    bool leaveShown=false;
    for(int i=0;i<ui->hitCount;i++)leaveShown|=ui->hits[i].id==HIT_SETTING+SETTING_LEAVE;
    ui->settingsScroll=1000;pd_ui_draw(ui,&canvas,&game,snap,&host);present();shot("settings-end");
    bool leaveNow=false;
    for(int i=0;i<ui->hitCount;i++)leaveNow|=ui->hits[i].id==HIT_SETTING+SETTING_LEAVE;
    assert(leaveNow);printf("%s SETTINGS: LEAVE GAME %s, after scrolling shown\n",liveName,leaveShown?"shown":"below");
    ui->settingsScroll=0;ui->settings->aspect=keepAspect;
    ui->overlay=PD_OVERLAY_HUD;pd_ui_draw(ui,&canvas,&game,snap,&host);present();shot("settings-hud");
    ui->overlay=PD_OVERLAY_TAB_BAR;pd_ui_draw(ui,&canvas,&game,snap,&host);present();shot("settings-tabbar");
    int keepTabs=ui->settings->tabBar;
    ui->settings->tabBar&=~(1<<PD_TAB_GUIDE);ui->overlay=PD_OVERLAY_MORE;
    pd_ui_draw(ui,&canvas,&game,snap,&host);present();shot("more");
    bool more=false;
    for(int i=0;i<ui->hitCount;i++)if(ui->hits[i].id==HIT_OPTION){struct pd_hit h=ui->hits[i];pd_ui_touch(ui,h.x+1,h.y+1,true);pd_ui_touch(ui,h.x+1,h.y+1,false);more=true;break;}
    assert(more && ui->tab==PD_TAB_GUIDE && ui->overlay==PD_OVERLAY_NONE);
    pd_ui_draw(ui,&canvas,&game,snap,&host);present();shot("guide-from-more");
    ui->settings->tabBar=keepTabs;ui->tab=PD_TAB_PARTY;
    uint32_t before=c->frameCounter(c);
    assert(state(c,"integration.ss",true));drive(c,0,20);assert(state(c,"integration.ss",false));
    assert(c->frameCounter(c)==before);unlink("integration.ss");
    test_field_items(c,&game,rom,len,snap,ui->settings,gw,gh);
    test_fly(c,&game,rom,len,snap,ui->settings,gw,gh);
    free(rom);
    printf("PASS real GBA save, party=%d, companion Summary/back and state reload\n",snap->partyCount);
    running=0;return;
  }
  assert(c->platform(c)==(gba?mPLATFORM_GBA:mPLATFORM_GB));
  if(frame==0) {
    assert(!companion && lastGameRect.w>0 && lastGameRect.h>0);
    pausedAt=c->frameCounter(c);tap(SDLK_TAB);
  } else if(frame==1) {
    assert(companion && c->frameCounter(c)==pausedAt);
    if(audio)assert(SDL_GetQueuedAudioSize(audio)==0);
    tap(SDLK_x);
  } else if(frame==2) {
    assert(!companion && c->frameCounter(c)==pausedAt);
    uint32_t before=c->frameCounter(c);
    assert(state(c,"integration.ss",true));drive(c,0,12);assert(state(c,"integration.ss",false));
    assert(c->frameCounter(c)==before);unlink("integration.ss");
    tap(SDLK_ESCAPE);
  } else if(frame==3) {
    assert(companion);tap(SDLK_x);
  } else if(frame==4) {
    assert(!companion);
    assert(keyboard(SDLK_z)==A && joy(0)==B && joy(1)==A);
    assert(controller_button(SDL_CONTROLLER_BUTTON_B)==A);
    assert(controller_button(SDL_CONTROLLER_BUTTON_A)==B);
    // Unknown raw devices must not inherit the TrimUI button indices.
    controller=NULL;rawTrimui=false;held=pressed=0;
    SDL_Event e={.type=SDL_JOYBUTTONDOWN};e.jbutton.button=1;
    assert(SDL_PushEvent(&e)==1);events();assert(!held && !pressed);
    rawTrimui=true;assert(SDL_PushEvent(&e)==1);events();assert(down(A));held=pressed=0;
  } else if(frame==5)assert(c->frameCounter(c)>pausedAt);
}
// The EXPAND window on common handheld panels: the game's own scale, the
// screen's shape, even margins; ZOOM OUT one scale less.
static void test_wide_window(void) {
  static const struct {int ow,oh;unsigned fw,fh;int aspect;unsigned w,h;} cases[]={
    {1024,768,240,160,PD_ASPECT_EXPAND,256,192}, // TrimUI Brick: 4x
    {1280,720,240,160,PD_ASPECT_EXPAND,320,180}, // Smart Pro: 4x, full screen
    {1280,720,240,160,PD_ASPECT_ZOOM_OUT,426,240},
    {640,480,240,160,PD_ASPECT_EXPAND,320,240},  // RG35XX / RGB20 class: 2x
    {720,720,240,160,PD_ASPECT_EXPAND,240,240},  // RGB30 / Cube: 3x, square
    {750,560,240,160,PD_ASPECT_EXPAND,250,186},  // Miyoo Mini v4: 3x
    {960,544,240,160,PD_ASPECT_EXPAND,320,180},  // RG503: 3x
    {1920,1080,240,160,PD_ASPECT_EXPAND,320,180},// RG556 / desktop: 6x
    {1920,1080,240,160,PD_ASPECT_ZOOM_OUT,384,216},
    {320,240,240,160,PD_ASPECT_ZOOM_OUT,320,240},// 1x can't go lower
    {1280,720,160,144,PD_ASPECT_EXPAND,256,144}, // Game Boy: 5x
    {1024,768,160,144,PD_ASPECT_EXPAND,204,152},
    {4096,2160,240,160,PD_ASPECT_ZOOM_OUT,340,180},
  };
  for(unsigned i=0;i<sizeof cases/sizeof*cases;i++) {
    unsigned w,h;wide_window(cases[i].ow,cases[i].oh,cases[i].fw,cases[i].fh,cases[i].aspect,&w,&h);
    if(w!=cases[i].w || h!=cases[i].h)fprintf(stderr,"%dx%d: %ux%u\n",cases[i].ow,cases[i].oh,w,h);
    assert(w==cases[i].w && h==cases[i].h);
    assert((w-cases[i].fw)%2==0 && (h-cases[i].fh)%2==0);
  }
}
// The companion's canvas: the largest whole scale keeping 320x240.
static void test_companion_size(void) {
  static const struct {int ow,oh,w,h;} cases[]={
    {640,480,320,240},{1280,720,426,240},{1024,768,341,256},{1920,1080,480,270},{720,720,360,360},
    {750,560,375,280},{960,544,480,272},{512,384,512,384},{320,240,320,240},{300,200,320,240},
  };
  for(unsigned i=0;i<sizeof cases/sizeof*cases;i++) {
    int w,h;companion_size(cases[i].ow,cases[i].oh,&w,&h);
    if(w!=cases[i].w||h!=cases[i].h)fprintf(stderr,"%dx%d: %dx%d\n",cases[i].ow,cases[i].oh,w,h);
    assert(w==cases[i].w&&h==cases[i].h);
  }
}
// A pad plugged in and out while running (SDL's virtual joystick, which SDL
// maps as a game controller): opened on its own, its buttons are the game's
// (SDL's B, the right face button, is the game's A), and pulling it lets go.
static void test_controller_hotplug(void) {
#if SDL_VERSION_ATLEAST(2, 24, 0)
  assert(!SDL_Init(SDL_INIT_JOYSTICK|SDL_INIT_GAMECONTROLLER));
  controller=NULL;held=pressed=0;
  SDL_VirtualJoystickDesc desc={.version=SDL_VIRTUAL_JOYSTICK_DESC_VERSION,.type=SDL_JOYSTICK_TYPE_GAMECONTROLLER,
    .naxes=SDL_CONTROLLER_AXIS_MAX,.nbuttons=SDL_CONTROLLER_BUTTON_MAX,.name="PokeDaisy test pad"};
  int index=SDL_JoystickAttachVirtualEx(&desc);assert(index>=0);
  events();assert(controller);
  SDL_Joystick* js=SDL_GameControllerGetJoystick(controller);
  assert(!SDL_JoystickSetVirtualButton(js,SDL_CONTROLLER_BUTTON_B,1));
  events();assert(down(A) && (held&(1u<<A)));
  assert(!SDL_JoystickSetVirtualAxis(js,SDL_CONTROLLER_AXIS_TRIGGERRIGHT,32767));
  events();assert(held&(1u<<R2));
  assert(!SDL_JoystickDetachVirtual(index));
  events();assert(!controller && !held);
  SDL_QuitSubSystem(SDL_INIT_JOYSTICK|SDL_INIT_GAMECONTROLLER);
  puts("PASS controller plugged in and out, its buttons the game's");
#endif
}
// Modal hit lists retain the controls below the scrim for touch rendering.
// Controller focus must neither start there nor move back through the scrim.
static void test_modal_focus(void) {
  struct pd_ui u={0};u.tab=PD_TAB_MAP;u.overlay=PD_OVERLAY_PLACES;u.startId=HIT_MAP;
  u.hits[0]=(struct pd_hit){0,0,300,200,HIT_MAP,false};
  u.hits[1]=(struct pd_hit){200,0,70,22,HIT_MAP_PLACES,false};
  u.hits[2]=(struct pd_hit){0,0,320,240,HIT_SCRIM,false};
  u.hits[3]=(struct pd_hit){10,30,300,180,HIT_SCRIM,true};
  u.hits[4]=(struct pd_hit){10,48,300,18,HIT_PLACE+1,true};
  u.hits[5]=(struct pd_hit){10,66,300,18,HIT_PLACE+2,true};
  u.hitCount=6;
  focusId=HIT_MAP;focus_new_screen();
  assert(focus_resolve(&u)==4 && focusId==HIT_PLACE+1);
  assert(focus_index(&u,HIT_MAP)==-1);
  focus_move(&u,DOWN);assert(focusId==HIT_PLACE+2);
  focus_move(&u,UP);assert(focusId==HIT_PLACE+1);
  focus_move(&u,UP);assert(focusId==HIT_PLACE+1);
  // A list with no selectable entries has no focus, never the hidden map.
  u.hitCount=4;focus_new_screen();assert(focus_resolve(&u)==-1);
  // Outside an overlay, L/R own the tab bar; D-pad stays on content.
  u.overlay=PD_OVERLAY_NONE;u.hitCount=2;u.startId=-1;
  u.hits[0]=(struct pd_hit){0,0,100,30,HIT_MAP,false};
  u.hits[1]=(struct pd_hit){0,50,100,22,HIT_TAB+PD_TAB_MAP,false};
  focus_new_screen();assert(focus_resolve(&u)==0);
  focus_move(&u,DOWN);assert(focusId==HIT_MAP);
  focusId=-1;focus_new_screen();
  puts("PASS modal focus stays above the scrim and D-pad skips tab chips");
}
int main(int argc,char** argv) {
  test_modal_focus();
  test_wide_window();
  test_companion_size();
  if(argc<4)test_controller_hotplug();
  if(argc==4 || argc==5) {
    // ROM DATA OUT [WxH,WxH,...]: once per window size (default 512x384).
    char sizes[256];snprintf(sizes,sizeof sizes,"%s",argc==5?argv[4]:"");
    char* next=sizes;int result=0;
    do {
      char* size=strsep(&next,",");
      if(*size){setenv("POCKET_WINDOW",size,1);snprintf(liveSize,sizeof liveSize,"-%s",size);}
      live=true;output=argv[3];running=1;
      char* args[]={"pokedaisy-linux","--data",argv[2],"--frames","1",argv[1],NULL};
      result|=linux_app_main(6,args);
    } while(next);
    return result;
  }
  char dir[]="/tmp/pd-linux-runtime-XXXXXX";assert(mkdtemp(dir));
  char* args[]={"pokedaisy-linux","--data",dir,"--frames","8","diagnostic.gb",NULL};
  assert(!linux_app_main(6,args));
  char path[1200];snprintf(path,sizeof path,"%s/saves/diagnostic.gb.sav",dir);
  FILE* f=fopen(path,"rb");assert(f && fgetc(f)==0x5a);fclose(f);
  gba=true;running=1;audio=0;messageUntil=0;held=pressed=0;args[5]="diagnostic.gba";
  assert(!linux_app_main(6,args));
  puts("PASS actual GB/GBA diagnostic execution, audio pause, SDL input, menus, state roundtrip and battery save");
  return 0;
}
