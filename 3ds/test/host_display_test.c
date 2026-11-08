/* Optional host scaling capability; default 3DS UI stays unchanged. */
#include "../core/pd_dex.h"
#include "../core/pd_gb.h"
#include "../core/pd_settings.h"
#include "../core/pd_ui_internal.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
static uint32_t pixels[512*384];
static bool has(const struct pd_ui* ui,int id) {
    for(int i=0;i<ui->hitCount;i++)if(ui->hits[i].id==id)return true;
    return false;
}
static const struct pd_host_info noHost={0};
static void tap(struct pd_ui* ui,int id) {
    for(int i=0;i<ui->hitCount;i++)if(ui->hits[i].id==id) {
        struct pd_hit h=ui->hits[i];pd_ui_touch(ui,h.x+1,h.y+1,true);pd_ui_touch(ui,h.x+1,h.y+1,false);return;
    }
    assert(!"no such hit");
}
static void test_dex_machine_tables(void) {
    static const uint8_t bulbasaur[] = {0x20, 0x07, 0x35, 0x84, 0x08, 0x1E, 0xE4, 0};
    uint8_t rom[256] = {0x08, 0x01, 0x51, 0x01, 0x60, 0x01, 0x5b, 0x01};
    rom[100] = 15; // HM01 CUT
    rom[10] = 92;  // TM06 TOXIC
    memcpy(rom + 136, bulbasaur, sizeof bulbasaur);
    struct pd_config cfg = {.dexTmhmMoves=0x08000000, .dexTmhm=0x08000080};
    struct pd_game game = {.rom=rom, .romSize=sizeof rom, .cfg=&cfg};
    struct pd_machine_move machines[58];
    assert(pd_machine_moves(&game, 1, machines, 58) == 19);
    assert(machines[0].number == 6 && !machines[0].hm && machines[0].move == 92);
    assert(machines[15].number == 1 && machines[15].hm && machines[15].move == 15);
    rom[100] = 0;
    assert(pd_machine_moves(&game, 1, machines, 58) == -1);

    const struct pd_dex_x layout = {.stride=8, .levelUpOff=0, .teachableOff=4};
    cfg.expansion = &layout;
    cfg.speciesInfo = 0x08000090;
    const uint8_t ptrs[] = {0xA0, 0, 0, 8, 0xB0, 0, 0, 8};
    const uint8_t level[] = {33, 0, 1, 0, 0xff, 0xff, 0, 0};
    const uint8_t teach[] = {15, 0, 15, 0, 92, 0, 0xff, 0xff};
    memcpy(rom + 0x98, ptrs, sizeof ptrs);
    memcpy(rom + 0xA0, level, sizeof level);
    memcpy(rom + 0xB0, teach, sizeof teach);
    struct pd_level_move learn[2];
    assert(pd_dex_level_moves(&game, 1, learn, 2) == 1);
    assert(learn[0].move == 33 && learn[0].level == 1);
    assert(pd_machine_moves(&game, 1, machines, 58) == 2);
    assert(machines[0].move == 15 && machines[0].number == 0 && machines[1].move == 92);
}

static void test_dex_evolutions(void) {
    uint8_t rom[160] = {[40]=4, [42]=16, [44]=2, [80]=4, [82]=32, [84]=3};
    struct pd_config cfg = {.dexEvolutions=0x08000000, .speciesCount=4};
    struct pd_game game = {.rom=rom, .romSize=sizeof rom, .cfg=&cfg, .guide=PD_GUIDE_EMERALD};
    struct pd_dex_evolution links[4];
    assert(pd_dex_evolutions(&game, 2, links, 4) == 2);
    assert(links[0].from == 1 && links[0].to == 2 && links[0].parameter == 16);
    assert(links[1].from == 2 && links[1].to == 3 && links[1].parameter == 32);
    game.guide = PD_GUIDE_EMERALD_LEGACY;
    assert(pd_dex_evolutions(&game, 2, links, 4) == -1);
    game.guide = PD_GUIDE_EMERALD;
    rom[42] = 15;
    assert(pd_dex_evolutions(&game, 2, links, 4) == -1);
}

static void test_crystal_guide(void) {
    struct pd_settings settings = {0};
    struct pd_ui ui;
    pd_ui_init(&ui, &settings);
    ui.tab = PD_TAB_GUIDE;
    ui.guideController = true;
    const struct pd_config cfg = {.gb=&pd_gb_crystal};
    const struct pd_game game = {.cfg=&cfg, .kind=PD_GAME_GB, .guide=PD_GUIDE_GB_CRYSTAL};
    const struct pd_snapshot snap = {.valid=true, .flagsOk=true};
    struct pd_canvas canvas;
    pd_canvas_init(&canvas, 341, 256, pixels);
    pd_ui_draw(&ui, &canvas, &game, &snap, &noHost);
    assert(ui.overlay == PD_OVERLAY_GUIDE_NOTICE);
    pd_ui_guide_control(&ui, 1, 0, true);
    assert(ui.overlay == PD_OVERLAY_NONE && settings.guideNotice);
    pd_ui_guide_control(&ui, 0, 1, false);
    assert(ui.guidePage == 0 && ui.guidePageFocus == 1);
    pd_ui_guide_control(&ui, 0, 0, true);
    assert(ui.guidePage == 1 && ui.guideRowFocus == -1);
    pd_ui_draw(&ui, &canvas, &game, &snap, &noHost);
    assert(has(&ui, HIT_GUIDE_ROW));
    pd_ui_guide_control(&ui, 1, 0, false);
    pd_ui_guide_control(&ui, 0, 0, true);
    assert(ui.guideOpen == 0 && ui.guideLevel == 1);
    assert(pd_ui_back(&ui) && ui.guideOpen == -1);
    // The default touch host still uses the same guide page and row hit targets.
    ui.guideController = false;
    pd_ui_draw(&ui, &canvas, &game, &snap, &noHost);
    tap(&ui, HIT_GUIDE_PAGE);
    assert(ui.guidePage == 0);
}

static const char* test_pocket_name(void* ctx, int pocket) {
    (void) ctx;
    const char* names[] = {"ALL", "KEY", "ITEMS", "BALLS"};
    return names[pocket];
}

static const char* test_bag_name(void* ctx, int item) {
    (void) ctx;
    const char* names[] = {"", "Antidote", "Potion", "X Attack"};
    return names[item];
}

static bool test_bag_item(void* ctx, int row, int* pocket, struct pd_item* out, bool* quantity) {
    *out = ((const struct pd_item*)ctx)[row];
    *pocket = row == 2 ? 3 : 2;
    *quantity = true;
    return true;
}

static void test_handheld_navigation(void) {
    struct pd_settings settings;
    pd_settings_defaults(&settings);
    struct pd_ui ui;
    pd_ui_init(&ui, &settings);
    ui.shoulderTabs = true;
    ui.tab = PD_TAB_SETTINGS;
    struct pd_game game = {0};
    struct pd_snapshot snap = {.valid=true};
    struct pd_host_info host = {
        .batteryPct=-1, .hideScreenMode=true, .frontendOwnsPlayback=true,
        .hasExpand=true, .expandGame=true, .hasHud=true, .hasQuickMenu=true, .hasButtonRows=true,
    };
    struct pd_canvas canvas;
    pd_canvas_init(&canvas, 240, 180, pixels);
    pd_ui_draw(&ui, &canvas, &game, &snap, &host);
    assert(ui.settingsNavId == HIT_SETTING + SETTING_ASPECT);
    assert(pd_ui_settings_control(&ui, 1, false));
    assert(ui.settingsNavId == HIT_SETTING + SETTING_HUD);
    assert(pd_ui_settings_control(&ui, 20, false));
    assert(ui.settingsNavId == HIT_SETTING + SETTING_TAB_BAR && ui.settingsScroll > 0);
    pd_ui_draw(&ui, &canvas, &game, &snap, &host);
    assert(has(&ui, ui.settingsNavId));
    assert(pd_ui_settings_control(&ui, 0, true) && ui.overlay == PD_OVERLAY_TAB_BAR);
    assert(pd_ui_back(&ui));
    assert(pd_ui_settings_control(&ui, -20, false) && ui.settingsScroll == 0);

    int button = -1;
    assert(pd_ui_map_control(&button, 2, 1, 0, false, false, false) == PD_MAP_MOVE);
    assert(pd_ui_map_control(&button, 2, 0, 0, false, true, false) == PD_MAP_IDLE && button == 0);
    assert(pd_ui_map_control(&button, 2, 1, 0, false, false, false) == PD_MAP_IDLE && button == 1);
    assert(pd_ui_map_control(&button, 2, 0, 0, true, false, false) == PD_MAP_CHOOSE);
    assert(pd_ui_map_control(&button, 2, 0, -1, false, false, false) == PD_MAP_IDLE && button == -1);
    assert(pd_ui_map_control(&button, 2, 0, 0, false, false, true) == PD_MAP_OPEN_PLACES);
}

int main(void) {
    test_handheld_navigation();
    test_crystal_guide();
    test_dex_evolutions();
    test_dex_machine_tables();
    struct pd_settings settings={.ffSpeed=2};struct pd_ui ui;
    pd_ui_init(&ui,&settings);ui.tab=PD_TAB_SETTINGS;
    struct pd_snapshot snap={0};struct pd_game game={0};
    struct pd_host_info host={.hasState=true};struct pd_canvas c;
    for(int size=0;size<2;size++) {
        pd_canvas_init(&c,size?512:320,size?384:240,pixels);
        pd_ui_draw(&ui,&c,&game,&snap,NULL);assert(has(&ui,HIT_SETTING+SETTING_SCREEN));
        pd_ui_draw(&ui,&c,&game,&snap,&host);assert(has(&ui,HIT_SETTING+SETTING_SCREEN));
        host.hideScreenMode=true;pd_ui_draw(&ui,&c,&game,&snap,&host);
        assert(!has(&ui,HIT_SETTING+SETTING_SCREEN));
        for(int row=SETTING_FF;row<=SETTING_LEAVE;row++)assert(has(&ui,HIT_SETTING+row));
        for(int i=0;i<ui.hitCount;i++)if(ui.hits[i].id==HIT_SETTING+SETTING_FF) {
            struct pd_hit h=ui.hits[i];pd_ui_touch(&ui,h.x+1,h.y+1,true);pd_ui_touch(&ui,h.x+1,h.y+1,false);break;
        }
        assert(ui.overlay==PD_OVERLAY_PICK_FF);assert(pd_ui_back(&ui));host.hideScreenMode=false;
    }
    // EXPAND hosts: ASPECT opens a pick-list; AUTO ZOOM IN shows once it isn't
    // ORIGINAL and flips in place; a game EXPAND can't do shows ASPECT untappable.
    pd_canvas_init(&c,512,384,pixels);
    struct pd_host_info wide={.hideScreenMode=true,.hasExpand=true,.expandGame=true};
    pd_settings_defaults(&settings);ui.overlay=PD_OVERLAY_NONE;
    pd_ui_draw(&ui,&c,&game,&snap,NULL);assert(!has(&ui,HIT_SETTING+SETTING_ASPECT));
    pd_ui_draw(&ui,&c,&game,&snap,&wide);
    assert(has(&ui,HIT_SETTING+SETTING_ASPECT) && !has(&ui,HIT_SETTING+SETTING_AUTO_ZOOM));
    tap(&ui,HIT_SETTING+SETTING_ASPECT);assert(ui.overlay==PD_OVERLAY_PICK_ASPECT);
    pd_ui_draw(&ui,&c,&game,&snap,&wide);tap(&ui,HIT_OPTION+PD_ASPECT_ZOOM_OUT);
    assert(ui.overlay==PD_OVERLAY_NONE && settings.aspect==PD_ASPECT_ZOOM_OUT);
    assert(pd_ui_take_action(&ui)==PD_ACTION_SETTINGS_CHANGED);
    pd_ui_draw(&ui,&c,&game,&snap,&wide);assert(has(&ui,HIT_SETTING+SETTING_AUTO_ZOOM) && settings.autoZoom);
    tap(&ui,HIT_SETTING+SETTING_AUTO_ZOOM);assert(!settings.autoZoom);
    assert(pd_ui_take_action(&ui)==PD_ACTION_SETTINGS_CHANGED);
    wide.expandGame=false;pd_ui_draw(&ui,&c,&game,&snap,&wide);
    assert(!has(&ui,HIT_SETTING+SETTING_ASPECT) && !has(&ui,HIT_SETTING+SETTING_AUTO_ZOOM));
    // Saved and read back; a file from before (no aspect / autozoom keys) keeps the defaults.
    const char* path="/tmp/pd-host-display-settings.ini";
    assert(pd_settings_save(&settings,path));
    struct pd_settings back;assert(pd_settings_load(&back,path));
    assert(back.aspect==PD_ASPECT_ZOOM_OUT && !back.autoZoom && back.ffSpeed==settings.ffSpeed);
    FILE* old=fopen(path,"w");fputs("screen=1\nff=3\nguide=0\n",old);fclose(old);
    assert(pd_settings_load(&back,path) && back.aspect==PD_ASPECT_ORIGINAL && back.autoZoom && back.ffSpeed==3);
    remove(path);
    // PARTY at a Linux canvas size: Android's slot, edge to edge, three columns past 2:1 (426x240's tab).
    // The selected look (orange outline) follows the host's D-pad focus, else slot 0; empty slots are
    // faint and take no taps.
    static uint32_t partyPx[426*240];
    pd_canvas_init(&c,426,240,partyPx);
    struct pd_snapshot party={.valid=true,.partyCount=3};
    for(int i=0;i<3;i++){party.party[i].species=1+i;party.party[i].level=5;party.party[i].hp=10;party.party[i].maxHp=20;}
    // A FireRed rev 1 header: a supported game (no ROM bytes, so no icons).
    uint8_t header[0xC0]={0};memcpy(header+0xAC,"BPRE",4);header[0xBC]=1;
    struct pd_game fr;pd_game_detect(&fr,header,0x1000000);assert(fr.cfg);
    ui.tab=PD_TAB_PARTY;ui.summarySlot=-1;ui.focusId=-1;
    pd_ui_draw(&ui,&c,&fr,&party,&noHost);
    struct pd_hit cell[3];int slots=0;
    for(int i=0;i<ui.hitCount;i++)if(ui.hits[i].id>=HIT_SLOT&&ui.hits[i].id<HIT_SLOT+3)cell[ui.hits[i].id-HIT_SLOT]=ui.hits[i],slots++;
    assert(slots==3 && cell[1].y==cell[0].y && cell[2].y==cell[0].y && cell[1].x>cell[0].x && cell[2].x>cell[1].x);
    assert(cell[2].x+cell[2].w-cell[0].x>426*9/10); // edge to edge (less the Poke Ball overhang)
    // Slot i's frame top edge (a slot's hit is its frame).
    #define FRAME_TOP(i) partyPx[cell[i].y*426+cell[i].x+20]
    assert(FRAME_TOP(0)==0xFF7331 && FRAME_TOP(2)==0x4A4A63);
    ui.focusId=HIT_SLOT+2;pd_ui_draw(&ui,&c,&fr,&party,&noHost);
    assert(FRAME_TOP(0)==0x4A4A63 && FRAME_TOP(2)==0xFF7331);
    // The empty row's outlines are blended into the backdrop, never drawn solid.
    int solid=0;
    for(int y=cell[0].y+cell[0].h;y<240;y++)for(int x=0;x<426;x++)solid+=partyPx[y*426+x]==0x4A4A63;
    assert(solid==0);
    // The handheld shoulders include More, with disabled cards off the bar.
    pd_ui_init(&ui,&settings);
    ui.shoulderTabs = ui.handheldParty = true;
    settings.tabBar = (1 << PD_TAB_PARTY) | (1 << PD_TAB_BAG);
    pd_ui_draw(&ui,&c,&fr,&party,&noHost);
    assert(!has(&ui,HIT_TAB + PD_TAB_MAP) && has(&ui,HIT_MORE));
    pd_ui_next_tab(&ui,1);
    assert(ui.tab == PD_TAB_BAG && ui.overlay == PD_OVERLAY_NONE);
    pd_ui_next_tab(&ui,1);
    assert(ui.overlay == PD_OVERLAY_MORE && pd_ui_tab_menu_count(&ui) >= 1);
    pd_ui_next_tab(&ui,1);
    assert(ui.tab == PD_TAB_SETTINGS && ui.overlay == PD_OVERLAY_NONE);
    pd_ui_next_tab(&ui,-1);
    assert(ui.overlay == PD_OVERLAY_MORE);
    pd_ui_tab_menu_choose(&ui,0);
    assert(ui.moreOpened && ui.overlay == PD_OVERLAY_NONE);
    pd_ui_next_tab(&ui,-1);
    assert(ui.tab == PD_TAB_BAG && !ui.moreOpened);

    // A and Select act on the open Pokémon regardless of the last cursor hit.
    ui.tab = PD_TAB_PARTY;
    ui.summarySlot = 0;
    ui.focusId = HIT_TAB + PD_TAB_MAP;
    party.party[0].stats.valid = party.party[1].stats.valid = true;
    pd_ui_party_summary_control(&ui,&party,0,0,true,false);
    assert(ui.tab == PD_TAB_PARTY && ui.summaryStats);
    ui.summaryCanLearn = true;
    ui.summaryLearnLast = 5;
    pd_ui_party_summary_control(&ui,&party,0,0,false,true);
    assert(ui.summaryLearn && !ui.summaryStats);
    pd_ui_party_summary_control(&ui,&party,1,0,false,false);
    assert(ui.summaryLearnRow == 1);
    pd_ui_party_summary_control(&ui,&party,0,1,false,false);
    assert(ui.summarySlot == 1 && ui.summaryLearnRow == 0);
    pd_ui_party_summary_control(&ui,&party,0,0,true,false);
    assert(!ui.summaryLearn && !ui.summaryStats);
    settings.tabBar = PD_TAB_BAR_DEFAULT;
    // In handheld battles, only the Party chip changes, even with focus enabled.
    pd_ui_init(&ui,&settings);
    ui.shoulderTabs = true;
    pd_ui_draw(&ui,&c,&fr,&party,&noHost);
    bool chipsBefore[PD_TAB_COUNT];
    for (int i=0;i<PD_TAB_COUNT;i++) chipsBefore[i]=has(&ui,HIT_TAB+i);
    const bool moreBefore=has(&ui,HIT_MORE);
    ui.keyFocus = ui.battleOnBar = true;
    ui.tab = PD_TAB_BATTLE;
    pd_ui_draw(&ui,&c,&fr,&party,&noHost);
    assert(!has(&ui,HIT_SHOULDER_L) && !has(&ui,HIT_SHOULDER_R));
    assert(has(&ui,HIT_TAB+PD_TAB_BATTLE) && !has(&ui,HIT_TAB+PD_TAB_PARTY));
    for (int i=0;i<PD_TAB_COUNT;i++)
        if (i!=PD_TAB_BATTLE && i!=PD_TAB_PARTY) assert(chipsBefore[i]==has(&ui,HIT_TAB+i));
    assert(moreBefore==has(&ui,HIT_MORE));

    // The open Bag tab runs on into the list (one shape, like the app's bag): no backdrop left between the two frames.
    struct pd_game bagGame=fr;
    party.bagOk=true;
    for(int gb=0;gb<2;gb++){
        if(gb)bagGame.kind=PD_GAME_GB;
        pd_ui_init(&ui,&settings);
        ui.tab=PD_TAB_BAG;
        pd_backdrop(&c);
        const int panelX=MARGIN+c.w*27/100-3;
        const int y=CONTENT_Y+11;
        uint32_t gap[4];
        memcpy(gap,partyPx+y*c.w+panelX-4,sizeof gap);
        pd_ui_bag_view(&ui,&c,&bagGame,&party);
        assert(memcmp(gap,partyPx+y*c.w+panelX-4,sizeof gap));
        assert(has(&ui,HIT_BAG_TAB) && has(&ui,HIT_BAG_LIST));
    }
    // The DS inventory uses the shared Bag sort and hides empty pockets.
    struct pd_item bagItems[] = {{2, 8}, {1, 1}, {3, 12}};
    const struct pd_bag_source bagSource = {
        .ctx=bagItems, .tabs=4, .count=3, .pocketName=test_pocket_name,
        .item=test_bag_item, .name=test_bag_name,
    };
    pd_ui_init(&ui,&settings);
    ui.shoulderTabs = true;
    ui.bagSource = &bagSource;
    ui.bagDetails = true;
    ui.bagPocket = 1; // empty, with occupied pockets 2 and 3
    pd_ui_bag_view(&ui,&c,&fr,&party);
    assert(ui.bagPocket == 0 && ui.bagTabCount == 3 && ui.bagPocketMask == 13);
    // Its tabs are keyed by its own pocket ids, so a tap opens that pocket.
    assert(has(&ui,HIT_BAG_TAB) && has(&ui,HIT_BAG_TAB+2) && has(&ui,HIT_BAG_TAB+3) && !has(&ui,HIT_BAG_TAB+1));
    assert(has(&ui,HIT_BAG_SORT));
    {
        // The D-pad's walk down the pocket tab bar stops at its ends, and its cursor is drawn on the tab it is on.
        const int keys[] = {0, 2, 3};
        assert(pd_ui_bag_tab_step(keys, 3, 0, 1) == 2 && pd_ui_bag_tab_step(keys, 3, 2, 1) == 3);
        assert(pd_ui_bag_tab_step(keys, 3, 3, 1) == 3 && pd_ui_bag_tab_step(keys, 3, 0, -1) == 0);
        assert(pd_ui_bag_tab_step(keys, 3, 3, -1) == 2 && pd_ui_bag_tab_step(keys, 3, 2, 0) == 2);
        static uint32_t plain[426 * 240];
        const size_t bytes = sizeof plain;
        assert((size_t) c.w * c.h * sizeof(uint32_t) == bytes);
        ui.bagTabs = false;
        pd_ui_bag_view(&ui,&c,&fr,&party);
        memcpy(plain, partyPx, bytes);
        ui.bagTabs = true;
        ui.bagTabKey = 2;
        pd_ui_bag_view(&ui,&c,&fr,&party);
        assert(memcmp(plain, partyPx, bytes) != 0);
        ui.bagTabs = false;
        pd_ui_bag_view(&ui,&c,&fr,&party);
        assert(!memcmp(plain, partyPx, bytes));
    }
    const int first[] = {2, 1, 3, 2};
    for (int i=0;i<4;i++) {
        ui.bagDetails = true;
        ui.bagSelected = 0;
        pd_ui_bag_view(&ui,&c,&fr,&party);
        assert(ui.bagItemShown == first[i]);
        pd_ui_bag_cycle_sort(&ui);
    }
    struct pd_bag_source empty = bagSource;
    empty.count = 0;
    ui.bagSource = &empty;
    const int seam = MARGIN + c.w * 27 / 100 - 4, seamY = CONTENT_Y + 11;
    pd_backdrop(&c);
    uint32_t handheldGap[4];
    memcpy(handheldGap, partyPx + seamY * c.w + seam, sizeof handheldGap);
    pd_ui_bag_view(&ui,&c,&fr,&party);
    assert(!memcmp(handheldGap, partyPx + seamY * c.w + seam, sizeof handheldGap));
    assert(ui.bagTabCount == 1 && ui.bagPocketMask == 1 && !has(&ui,HIT_BAG_TAB+1));
    // Native GBA pockets also use the common sorting and keep cartridge order in Default.
    ui.bagSource = NULL;
    party.bag[PD_POCKET_BALLS].count = 3;
    memcpy(party.bag[PD_POCKET_BALLS].items,bagItems,sizeof bagItems);
    ui.bagSort = 0;
    const int gbaFirst[] = {2, 3, 3, 2}; // Ultra Ball, Great Ball, Great Ball, Ultra Ball
    for (int i=0;i<4;i++) {
        ui.bagSelected = 0;
        pd_ui_bag_view(&ui,&c,&fr,&party);
        assert(ui.bagTabCount == 2 && ui.bagItemShown == gbaFirst[i]);
        pd_ui_bag_cycle_sort(&ui);
    }
    puts("PASS default/null host, optional scaling row, remaining settings and FF interaction, EXPAND rows, party grid");
}
