// melonDS memory adapter for the shared DS field jobs.
#include "pk_nds_call_memory.h"
#include "NDS.h"

using namespace melonDS;

static PkNdsCallMemory callMemory(NDS* nds)
{
    return {
        nds,
        [](void* ctx, u32 addr) {
            auto* n = static_cast<NDS*>(ctx);
            u32 value = 0;
            for (int i = 0; i < 4; ++i)
                value |= (u32)n->MainRAM[(addr + i) & n->MainRAMMask] << (8 * i);
            return value;
        },
        [](void* ctx, u32 addr, u32 value) {
            auto* n = static_cast<NDS*>(ctx);
            for (int i = 0; i < 4; ++i)
                n->MainRAM[(addr + i) & n->MainRAMMask] = (u8)(value >> (8 * i));
        },
        [](void* ctx, u32 addr, void* dst, u32 len) {
            auto* n = static_cast<NDS*>(ctx);
            auto* bytes = static_cast<u8*>(dst);
            for (u32 i = 0; i < len; ++i)
                bytes[i] = n->ARM9.ITCM[(addr + i) & (ITCMPhysicalSize - 1)];
        },
        [](void* ctx, u32 addr, const void* src, u32 len) {
            auto* n = static_cast<NDS*>(ctx);
            const auto* bytes = static_cast<const u8*>(src);
            for (u32 i = 0; i < len; ++i)
                n->ARM9.ITCM[(addr + i) & (ITCMPhysicalSize - 1)] = bytes[i];
            for (u32 a = addr & ~3u; a < addr + len; a += 4)
                n->JIT.CheckAndInvalidate<0, ARMJIT_Memory::memregion_ITCM>(a);
        }
    };
}

int pk_nds_call_action(NDS* nds, const PkNdsFieldGame* game, int action, const u32 args[4])
{
    return nds ? pk_nds_call_action(callMemory(nds), game, action, args) : 0;
}

int pk_nds_call_ops(NDS* nds, const PkNdsFieldGame* game, const PkNdsOp* ops, int n)
{
    return nds ? pk_nds_call_ops(callMemory(nds), game, ops, n) : 0;
}

int pk_nds_call_script(NDS* nds, const PkNdsFieldGame* game, const u8* bytes, u32 len)
{
    return nds ? pk_nds_call_script(callMemory(nds), game, bytes, len) : 0;
}

void pk_nds_call_itcm_write(NDS* nds, u32 addr, const void* src, u32 len)
{
    if (nds) pk_nds_call_itcm_write(callMemory(nds), addr, src, len);
}

void pk_nds_call_frame(NDS* nds, const PkNdsFieldGame* game)
{
    if (nds) pk_nds_call_frame(callMemory(nds), game);
}
