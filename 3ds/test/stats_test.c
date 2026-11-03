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
// A CFRU-style plaintext box (Unbound, Radical Red): no checksum, substructs in G-A-E-M order at +0x20.
static void check_plain_mon(void) {
    uint8_t raw[100]={0};
    put32(raw,0x12345678);put32(raw+4,0xCAFE0001);
    uint8_t* box=raw+0x20;
    put16(box,6);put16(box+2,13);put32(box+4,9000);            // growth: species, item, exp
    put16(box+0x0C,33);box[0x0C+8]=35;                          // attacks
    for(int i=0;i<6;i++)box[0x18+i]=i*9;                        // EVs
    uint32_t iv=0;for(int i=0;i<6;i++)iv|=(uint32_t)(i+10)<<(i*5);
    put32(box+0x28,iv);
    raw[0x54]=42;put16(raw+0x56,70);put16(raw+0x58,90);
    for(int i=0;i<6;i++)put16(raw+0x58+i*2,90+i*7);
    struct pd_mon mon;
    assert(pd_decode_party_mon(raw,&mon));
    assert(mon.species==6 && mon.heldItem==13 && mon.moves[0]==33 && mon.pp[0]==35);
    assert(mon.level==42 && mon.hp==70 && mon.maxHp==90 && !mon.hasExperience);
    assert(mon.stats.valid && mon.stats.nature==(int)(0x12345678u%25));
    for(int i=0;i<6;i++){assert(mon.stats.ivs[i]==i+10&&mon.stats.evs[i]==i*9&&mon.stats.values[i]==90+i*7);}
    raw[0x12]=3<<3;                                             // Mint: nature XOR 3 (kept when < 25)
    assert(pd_decode_party_mon(raw,&mon));
    int expect=(int)(0x12345678u%25)^3;assert(mon.stats.nature==(expect<25?expect:(int)(0x12345678u%25)));
    memset(box,0,48);                                           // an empty slot
    assert(!pd_decode_party_mon(raw,&mon));
    // A layout with its own box offset and plain experience (Quetzal-like) and the egg bit in the IV word.
    struct pd_party_layout l=pd_party_layout_vanilla;
    l.size=104;l.plainBox=0x28;l.plainExp=true;l.eggInIvWord=true;l.maxHp=0x5A;l.level=0x58;l.hp=0x5A-2;
    uint8_t big[104]={0};put32(big,1);box=big+0x28;
    put16(box,25);put32(box+4,777);put32(box+0x28,1u<<30);
    big[0x58]=7;put16(big+0x5A,31);
    assert(pd_decode_party_mon_l(big,&l,&mon));
    assert(mon.species==PD_SPECIES_EGG && mon.experience==777 && mon.hasExperience && mon.level==7);
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
static void check_shiny(void) {
    // SoulGold: a plaintext box; shiny value < 256, flipped by bit 6 of the byte at +0x1F.
    uint8_t raw[96] = {0};
    struct pd_mon m;
    raw[0x20] = 25; // species
    raw[0] = 5;     // personality 5, OT id 0 -> shiny value 5
    assert(pd_decode_party_mon_l(raw, &pd_party_layout_soulgold, &m) && m.shiny);
    raw[0] = 0x34; raw[1] = 0x12; // value 0x1234
    assert(pd_decode_party_mon_l(raw, &pd_party_layout_soulgold, &m) && !m.shiny);
    raw[0x1F] = 0x40; // the modifier alone = shiny
    assert(pd_decode_party_mon_l(raw, &pd_party_layout_soulgold, &m) && m.shiny);
    raw[0] = 5; raw[1] = 0; // ... on a shiny personality = not
    assert(pd_decode_party_mon_l(raw, &pd_party_layout_soulgold, &m) && !m.shiny);
    assert(!pd_decode_party_mon(raw, &m) || !m.shiny); // retail layout never marks
    // R.O.W.E.: +0x29's 0x80; Elite Redux: +0x17's 0x0C.
    uint8_t r[0x4C] = {0};
    r[0x1C] = 25;
    assert(pd_decode_party_mon_l(r, &pd_party_layout_rowe, &m) && !m.shiny);
    r[0x29] = 0x80;
    assert(pd_decode_party_mon_l(r, &pd_party_layout_rowe, &m) && m.shiny);
    memset(r, 0, sizeof r); r[0x10] = 25;
    assert(pd_decode_party_mon_l(r, &pd_party_layout_elite_redux, &m) && !m.shiny);
    r[0x17] = 0x04;
    assert(pd_decode_party_mon_l(r, &pd_party_layout_elite_redux, &m) && m.shiny);
    puts("PASS shiny marks (SoulGold value + modifier, R.O.W.E., Elite Redux bits, retail never)");
}

int main(int argc,char** argv) {
    check_shiny();
    int permutation=0;
    for(int a=0;a<4;a++)for(int b=0;b<4;b++)for(int c=0;c<4;c++)for(int d=0;d<4;d++) {
        if(a==b||a==c||a==d||b==c||b==d||c==d)continue;
        int order[]={a,b,c,d};check_mon(order,permutation++);
    }
    assert(permutation==24);
    check_plain_mon();
    assert(pd_nature_raises(0)==-1 && pd_nature_lowers(24)==-1);
    assert(pd_nature_raises(3)==1 && pd_nature_lowers(3)==4); // Adamant
    assert(pd_nature_raises(-1)==-1 && pd_nature_lowers(25)==-1);
    struct pd_mon_stats stats={.valid=true};
    assert(!strcmp(pd_type_name(NULL,pd_hidden_power_type(&stats)),"Fighting"));
    assert(pd_hidden_power_power(&stats)==30);
    memset(stats.ivs,31,sizeof stats.ivs);
    assert(!strcmp(pd_type_name(NULL,pd_hidden_power_type(&stats)),"Dark"));
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
            assert(has(&ui,HIT_SUMMARY_INFO));tap(&ui,HIT_SUMMARY_INFO);assert(ui.summaryInfo && !ui.summaryStats);
            pd_ui_draw(&ui,&canvas,&game,&snap,&host);
            snprintf(path,sizeof path,"%s/%s-info-%dx%d.png",argv[3],argv[2],w,h);
            assert(pd_png_write(path,w,h,pixels));
            tap(&ui,HIT_SUMMARY_INFO);assert(!ui.summaryInfo);
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
