// Decoder permutations, Android MonStats rules, and real captured-RAM UI.
#include "../core/pd_ui_internal.h"
#include "../core/pd_tables.h"
#include "../desktop/pd_png.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void put16(uint8_t* b, unsigned n) { b[0]=n; b[1]=n>>8; }
static void put32(uint8_t* b, uint32_t n) { put16(b,n);put16(b+2,n>>16); }
static uint32_t get32(const uint8_t* b) {
    return (uint32_t)b[0]|(uint32_t)b[1]<<8|(uint32_t)b[2]<<16|(uint32_t)b[3]<<24;
}

// Lexicographic permutations generated independently of the decoder's table.
static void check_mon(const int* order, int permutation) {
    uint8_t raw[100]={0}, logical[4][12]={{0}}, block[48];
    uint32_t pid=permutation, key=pid^0x12345678, iv=0;
    put32(raw,pid);put32(raw+4,0x12345678);
    put16(logical[0],25);put16(logical[0]+2,13);put32(logical[0]+4,123456);
    put16(logical[1],33);logical[1][8]=35;
    for(int i=0;i<6;i++) {
        logical[2][i]=i*17;
        iv|=(uint32_t)(31-i)<<(i*5);
        put16(raw+0x58+i*2,100+i*11);
    }
    put32(logical[3]+4,iv);
    unsigned sum=0;
    for(int i=0;i<4;i++)memcpy(block+i*12,logical[order[i]],12);
    for(int i=0;i<48;i+=2)sum+=block[i]+(block[i+1]<<8);
    put16(raw+28,sum);
    for(int i=0;i<48;i+=4)put32(raw+32+i,get32(block+i)^key);
    raw[0x54]=50;put16(raw+0x56,77);
    struct pd_mon mon;
    assert(pd_decode_party_mon(raw,&mon));
    assert(mon.species==25 && mon.moves[0]==33 && mon.pp[0]==35);
    assert(mon.experience==123456 && mon.heldItem==13 && mon.hp==77);
    assert(mon.stats.valid && mon.stats.nature==(int)(pid%25));
    assert(mon.stats.hasIvs && mon.stats.hasEvs && mon.stats.hasNature && mon.stats.hasHiddenPower);
    assert(mon.stats.hiddenPowerType==pd_hidden_power_type(&mon.stats));
    assert(mon.stats.hiddenPowerPower==pd_hidden_power_power(&mon.stats));
    for(int i=0;i<6;i++) {
        assert(mon.stats.ivs[i]==31-i && mon.stats.evs[i]==i*17);
        assert(mon.stats.values[i]==100+i*11);
    }
    raw[32]^=1;
    assert(!pd_decode_party_mon(raw,&mon) && !mon.stats.valid);
}

static uint8_t ram[0x40000], iram[0x8000];
static bool read_ram(void* ctx,uint32_t addr,void* out,size_t n) {
    (void)ctx;
    if(addr>=0x02000000 && n<=sizeof ram && addr-0x02000000<=sizeof ram-n) {
        memcpy(out,ram+addr-0x02000000,n);return true;
    }
    if(addr>=0x03000000 && n<=sizeof iram && addr-0x03000000<=sizeof iram-n) {
        memcpy(out,iram+addr-0x03000000,n);return true;
    }
    return false;
}
static void load(const char* dir,const char* file,void* out,size_t n) {
    char path[1024];snprintf(path,sizeof path,"%s/%s",dir,file);
    FILE* f=fopen(path,"rb");assert(f);assert(fread(out,1,n,f)==n);fclose(f);
}
static bool has(struct pd_ui* ui,int id) {
    for(int i=0;i<ui->hitCount;i++)if(ui->hits[i].id==id)return true;
    return false;
}
static void tap(struct pd_ui* ui,int id) {
    for(int i=0;i<ui->hitCount;i++)if(ui->hits[i].id==id) {
        struct pd_hit h=ui->hits[i];
        pd_ui_touch(ui,h.x+h.w/2,h.y+h.h/2,true);
        pd_ui_touch(ui,h.x+h.w/2,h.y+h.h/2,false);return;
    }
    assert(!"Missing control");
}
int main(int argc,char** argv) {
    int permutation=0;
    for(int a=0;a<4;a++)for(int b=0;b<4;b++)for(int c=0;c<4;c++)for(int d=0;d<4;d++) {
        if(a==b||a==c||a==d||b==c||b==d||c==d)continue;
        int order[]={a,b,c,d};check_mon(order,permutation++);
    }
    assert(permutation==24);
    assert(pd_nature_raises(0)==-1 && pd_nature_lowers(24)==-1);
    assert(pd_nature_raises(3)==1 && pd_nature_lowers(3)==4); // Adamant
    assert(pd_nature_raises(-1)==-1 && pd_nature_lowers(25)==-1);
    struct pd_mon_stats stats={.valid=true};
    assert(!strcmp(pd_type_name(pd_hidden_power_type(&stats)),"Fighting"));
    assert(pd_hidden_power_power(&stats)==30);
    memset(stats.ivs,31,sizeof stats.ivs);
    assert(!strcmp(pd_type_name(pd_hidden_power_type(&stats)),"Dark"));
    assert(pd_hidden_power_power(&stats)==70);
    stats.valid=false;assert(pd_hidden_power_type(&stats)==PD_TYPE_NONE);
    if(argc==4) {
        load(argv[1],"ewram.bin",ram,sizeof ram);load(argv[1],"iwram.bin",iram,sizeof iram);
        uint8_t header[0xC0]={0};memcpy(header+0xAC,argv[2],4);
        header[0xBC]=strcmp(argv[2],"BPEE")?1:0;
        struct pd_game game;pd_game_detect(&game,header,0x1000000);
        struct pd_snapshot snap;pd_snapshot_read(&snap,&game,read_ram,NULL);
        assert(snap.partyCount>0 && snap.party[0].stats.valid);
        struct pd_settings settings={.ffSpeed=2};struct pd_ui ui;pd_ui_init(&ui,&settings);
        struct pd_host_info host={0};struct pd_canvas canvas;
        static uint32_t pixels[1024*768];
        const int sizes[][2]={{480,320},{512,384},{1024,768},{341,256},{320,240}};
        for(unsigned i=0;i<sizeof sizes/sizeof *sizes;i++) {
            int w=sizes[i][0],h=sizes[i][1];pd_canvas_init(&canvas,w,h,pixels);
            ui.tab=PD_TAB_PARTY;ui.summarySlot=0;ui.summaryStats=false;
            pd_ui_draw(&ui,&canvas,&game,&snap,&host);
            if(h<256){assert(!has(&ui,HIT_SUMMARY_STATS));continue;}
            assert(has(&ui,HIT_SUMMARY_STATS));tap(&ui,HIT_SUMMARY_STATS);
            assert(ui.summaryStats);pd_ui_draw(&ui,&canvas,&game,&snap,&host);
            char path[1024];snprintf(path,sizeof path,"%s/%s-stats-%dx%d.png",argv[3],argv[2],w,h);
            assert(pd_png_write(path,w,h,pixels));
            // A computed-stats-only adapter must never leak unsupported mechanics,
            // even when those unused fields contain old/nonzero values.
            struct pd_mon_stats saved=snap.party[0].stats;
            struct pd_mon_stats* optional=&snap.party[0].stats;
            optional->hasIvs=optional->hasEvs=optional->hasNature=optional->hasHiddenPower=false;
            pd_ui_draw(&ui,&canvas,&game,&snap,&host);
            uint32_t* expected=malloc((size_t)w*h*sizeof *expected);assert(expected);
            memcpy(expected,pixels,(size_t)w*h*sizeof *expected);
            memset(optional->ivs,31,sizeof optional->ivs);
            memset(optional->evs,255,sizeof optional->evs);
            optional->nature=3;optional->hiddenPowerType=10;optional->hiddenPowerPower=60;
            pd_ui_draw(&ui,&canvas,&game,&snap,&host);
            assert(!memcmp(expected,pixels,(size_t)w*h*sizeof *expected));free(expected);
            snap.party[0].stats=saved;
            tap(&ui,HIT_SUMMARY_STATS);assert(!ui.summaryStats);
            snap.party[0].stats.valid=false;
            pd_ui_draw(&ui,&canvas,&game,&snap,&host);assert(!has(&ui,HIT_SUMMARY_STATS));
            snap.party[0].stats.valid=true;
            int species=snap.party[0].species;snap.party[0].species=PD_SPECIES_EGG;
            pd_ui_draw(&ui,&canvas,&game,&snap,&host);assert(!has(&ui,HIT_SUMMARY_STATS));
            snap.party[0].species=species;
        }
        puts("PASS captured RAM, STATS/MOVES controls, compact/unknown/egg concealment");
    }
    puts("PASS 24 encrypted orders, checksum rejection, IV/EV/stat fields, nature and Hidden Power boundaries");
    return 0;
}
