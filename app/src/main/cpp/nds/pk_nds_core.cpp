// pk_nds.h's calls, handed to the DS core that's up: melonDS (pk_nds.cpp), DSperate (pk_nds_dsp.cpp) or BananaDS
// (libpkbananads.so, bananads/pk_nds_bnd.cpp). See
// pk_nds_cores.h. With no DS up every call goes to melonDS's, which does nothing then (as it always did).

#include "pk_nds_cores.h"

#include <atomic>

namespace {

std::atomic<int> wanted {PK_NDS_CORE_MELONDS};   // the next init's (pk_nds_set_core)
std::atomic<int> up {PK_NDS_CORE_MELONDS};       // the one up (melonDS's calls with none: they do nothing then)

} // namespace

extern "C" {

void pk_nds_set_core(int core)
{
    wanted.store(core == PK_NDS_CORE_DSPERATE || core == PK_NDS_CORE_BANANADS ? core : PK_NDS_CORE_MELONDS);
}

int pk_nds_core(void)
{
    if (pk_ndsd_active()) return PK_NDS_CORE_DSPERATE;
    if (pk_ndsb_active()) return PK_NDS_CORE_BANANADS;
    return pk_ndsm_active() ? PK_NDS_CORE_MELONDS : -1;
}

bool pk_nds_init(const char* romPath, const char* savePath, int sampleRate)
{
    pk_nds_deinit();
    const int core = wanted.load();
    bool ok = core == PK_NDS_CORE_DSPERATE ? pk_ndsd_init(romPath, savePath, sampleRate)
        : core == PK_NDS_CORE_BANANADS ? pk_ndsb_init(romPath, savePath, sampleRate)
        : pk_ndsm_init(romPath, savePath, sampleRate);
    up.store(ok ? core : PK_NDS_CORE_MELONDS);
    return ok;
}

void pk_nds_deinit(void)
{
    pk_ndsd_deinit();
    pk_ndsb_deinit();
    pk_ndsm_deinit();
    up.store(PK_NDS_CORE_MELONDS);
}

bool pk_nds_active(void) { return pk_ndsd_active() || pk_ndsb_active() || pk_ndsm_active(); }

#define PK_NDS_DISPATCH(ret, name, params, args) \
    ret pk_nds_##name params \
    { \
        switch (up.load(std::memory_order_relaxed)) { \
        case PK_NDS_CORE_DSPERATE: return pk_ndsd_##name args; \
        case PK_NDS_CORE_BANANADS: return pk_ndsb_##name args; \
        default: return pk_ndsm_##name args; \
        } \
    }
PK_NDS_CORE_CALLS(PK_NDS_DISPATCH)
#undef PK_NDS_DISPATCH

} // extern "C"
