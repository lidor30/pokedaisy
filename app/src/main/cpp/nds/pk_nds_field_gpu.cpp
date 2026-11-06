// pk_nds_field.h's one read of melonDS itself (the rest of the field read is RAM only: pk_nds_field.cpp).

#include "pk_nds_field.h"

#include <NDS.h>

int pk_nds_bg3_tiles(melonDS::NDS* nds, int bg)
{
    uint32_t disp = nds->GPU.GPU2D_A.DispCnt;
    if (!(disp & (1 << (8 + bg)))) return 0;   // the BG is off
    if (bg == 3 && (disp & 7) > 1) return 0;   // not a text BG in this mode
    if (bg == 2 && (disp & 7) > 3) return 0;
    uint16_t cnt = nds->GPU.GPU2D_A.BGCnt[bg];
    uint32_t base = 0x06000000u + ((disp >> 27) & 7) * 0x10000 + ((cnt >> 8) & 0x1F) * 0x800;
    int n = 0;
    for (uint32_t i = 0; i < 32 * 24; i++)
        if (nds->ARM9Read16(base + i * 2) & 0x3FF) n++;
    return n;
}
