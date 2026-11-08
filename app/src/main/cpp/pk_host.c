// Around a core's save and clock, for every host: pk_host.h. GPL-3.0 (see NOTICE).
#include "pk_host.h"

#include <stdbool.h>
#include <string.h>

#include <mgba/core/core.h>
#include <mgba/internal/gba/gba.h>
#ifdef M_CORE_GB
#include <mgba/internal/gb/gb.h>
#endif

#ifdef __ANDROID__
#include <android/log.h>
#define PK_LOG(...) __android_log_print(ANDROID_LOG_INFO, "pokedaisy/jni", __VA_ARGS__)
#else
#define PK_LOG(...)
#endif

int pk_save_dirty(struct mCore* core) {
    if (core->platform(core) == mPLATFORM_GBA) return ((struct GBA*) core->board)->memory.savedata.dirty != 0;
#ifdef M_CORE_GB
    if (core->platform(core) == mPLATFORM_GB) return ((struct GB*) core->board)->sramDirty != 0;
#endif
    return 0;
}

int pk_finish_save(struct mCore* core, int maxFrames, void (*afterFrame)(void)) {
    int n = 0;
    if (pk_save_dirty(core)) core->setKeys(core, 0);
    while (n < maxFrames && pk_save_dirty(core)) {
        core->runFrame(core);
        if (afterFrame) afterFrame();
        n++;
    }
    return n;
}

void pk_heal_rtc(struct mCore* core) {
    if (!core || core->platform(core) != mPLATFORM_GBA) return;
    struct GBA* gba = (struct GBA*) core->board;
    struct GBARTC* rtc = &gba->memory.hw.rtc;
    if (!(gba->memory.hw.devices & HW_RTC)) return;
    const char* code = gba->memory.rom && gba->memory.romSize > 0xB0 ? (const char*) gba->memory.rom + 0xAC : "";
    bool pokemon = !strncmp(code, "AXV", 3) || !strncmp(code, "AXP", 3) || !strncmp(code, "BPE", 3);
    if (!RTCControlIsPoweroff(rtc->control) && (RTCControlIsHour24(rtc->control) || !pokemon)) return;
    PK_LOG("RTC from the save: control 0x%02x (%s), reset to a running 24-hour clock", (unsigned) rtc->control,
           RTCControlIsPoweroff(rtc->control) ? "battery dead" : "12-hour");
    rtc->control = RTCControlFillHour24(0);
    rtc->offset = 0;
}
