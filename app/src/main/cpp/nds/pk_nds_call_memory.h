// Host memory access for the shared DS field jobs.
#pragma once

#include "pk_nds_call.h"

struct PkNdsCallMemory {
    void* context;
    uint32_t (*read32)(void*, uint32_t);
    void (*write32)(void*, uint32_t, uint32_t);
    void (*readItcm)(void*, uint32_t, void*, uint32_t);
    void (*writeItcm)(void*, uint32_t, const void*, uint32_t);
};

bool pk_nds_call_supported(const PkNdsFieldGame* game);
int pk_nds_call_action(const PkNdsCallMemory& mem, const PkNdsFieldGame* game, int action, const uint32_t args[4]);
int pk_nds_call_ops(const PkNdsCallMemory& mem, const PkNdsFieldGame* game, const PkNdsOp* ops, int n);
int pk_nds_call_script(const PkNdsCallMemory& mem, const PkNdsFieldGame* game, const uint8_t* bytes, uint32_t len);
void pk_nds_call_itcm_write(const PkNdsCallMemory& mem, uint32_t addr, const void* src, uint32_t len);
void pk_nds_call_frame(const PkNdsCallMemory& mem, const PkNdsFieldGame* game);
