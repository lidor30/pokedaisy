// Around a core's save and clock, for every host: pk_host.h. GPL-3.0 (see NOTICE).
#include "pk_host.h"

#include <stdbool.h>
#include <string.h>

#include <mgba/core/core.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/overrides.h>
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

// A FireRed / LeafGreen / Emerald hack whose Game Freak title (0x108) was renamed: mGBA gives an unknown Pokemon ROM its
// clock (and FLASH1M) only while that title still reads "pokemon red version" / "pokemon emerald version", so these got
// none - Oops All Eevees! ("pokemon oops all eevees") then said "The correct system time could not be read." after its
// title screen (Discord, 1.6.2-beta.1; headless the same). Quetzal 0.8.4's and Emerald Rogue's titles are renamed too.
// The retail carts keep their own titles, and a ROM with no such title (Dreamstone) isn't touched.
static bool renamedPokemonHack(const struct GBA* gba) {
    const char* rom = (const char*) gba->memory.rom;
    if (!rom || gba->memory.romSize < 0x120) return false;
    const char* code = rom + 0xAC;
    if (strncmp(code, "BPR", 3) && strncmp(code, "BPG", 3) && strncmp(code, "BPE", 3)) return false;
    const char* title = rom + 0x108;
    if (strncmp(title, "pokemon ", 8)) return false;
    static const char* const retail[] = { "pokemon red version", "pokemon green version", "pokemon emerald version" };
    for (size_t i = 0; i < sizeof retail / sizeof *retail; i++)
        if (!strncmp(title, retail[i], strlen(retail[i]) + 1)) return false;
    return true;
}

void pk_heal_rtc(struct mCore* core) {
    if (!core || core->platform(core) != mPLATFORM_GBA) return;
    struct GBA* gba = (struct GBA*) core->board;
    // The clock mGBA gives a hack that kept its title (GBAOverrideApplyDefaults' Pokemon rule), read from the save's
    // last 16 bytes like it; its savetype is left to the save's own detection.
    if (!(gba->memory.hw.devices & HW_RTC) && renamedPokemonHack(gba)) {
        struct GBACartridgeOverride o = { .savetype = SAVEDATA_AUTODETECT, .hardware = HW_RTC, .idleLoop = IDLE_LOOP_NONE,
                                          .vbaBugCompat = gba->vbaBugCompat };
        memcpy(o.id, (const char*) gba->memory.rom + 0xAC, sizeof o.id);
        GBAOverrideApply(gba, &o);
        PK_LOG("RTC on: a Pokemon hack with its own title (%.24s)", (const char*) gba->memory.rom + 0x108);
    }
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
