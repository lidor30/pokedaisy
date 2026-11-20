// PokeDaisy — isolated mGBA session for FF music and game sound clips. GPL-3.0 (see NOTICE).
#include <stdlib.h>
#include <string.h>

#include <mgba/core/blip_buf.h>
#include <mgba/core/config.h>
#include <mgba/core/core.h>
#include <mgba-util/vfs.h>
#include <mgba/internal/arm/isa-inlines.h>
#include <mgba/internal/gb/gb.h>
#include <mgba/internal/gb/io.h>
#include <mgba/internal/sm83/sm83.h>

#include "pk_call.h"
#include "pk_session.h"  // pk_read_range and the shared audio buffer size

struct pk_sound_renderer {
    struct mCore* core;
    color_t* video;  // mGBA needs a video buffer even when frames are never displayed
    struct pk_gb_audio gbAudio;
    int16_t audio[PK_AUDIO_SCRATCH_SAMPLES * 2];
    int audioAvail;
};

struct pk_sound_renderer* pk_api_sound_open(const char* romPath) {
    if (!romPath) return NULL;
    struct VFile* rom = VFileOpen(romPath, O_RDONLY);
    if (!rom) return NULL;

    struct mCore* core = mCoreFindVF(rom);
    if (!core) {
        rom->close(rom);
        return NULL;
    }
    if (!core->init(core)) {
        rom->close(rom);
        core->deinit(core);
        return NULL;
    }
    struct pk_sound_renderer* render = calloc(1, sizeof(*render));
    if (!render) {
        rom->close(rom);
        core->deinit(core);
        return NULL;
    }
    render->core = core;
    mCoreInitConfig(core, NULL);
    pk_gb_config(core);

    unsigned w, h;
    core->desiredVideoDimensions(core, &w, &h);
    render->video = calloc((size_t) w * h, sizeof(color_t));
    if (!render->video) {
        rom->close(rom);
        pk_api_sound_close(render);
        return NULL;
    }
    core->setVideoBuffer(core, render->video, w);
    core->setAudioBufferSize(core, 2048);
    blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), PK_API_SAMPLE_RATE);
    blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), PK_API_SAMPLE_RATE);
    if (!core->loadROM(core, rom)) {
        rom->close(rom);
        pk_api_sound_close(render);
        return NULL;
    }
    // Keep this session independent of the player's battery save and video.
    core->reset(core);
    pk_gb_audio_attach(&render->gbAudio, core, PK_API_SAMPLE_RATE);
    return render;
}

void pk_api_sound_close(struct pk_sound_renderer* render) {
    if (!render) return;
    pk_gb_audio_detach(&render->gbAudio, render->core);
    mCoreConfigDeinit(&render->core->config);
    render->core->deinit(render->core);
    free(render->video);
    free(render);
}

void pk_api_sound_frame(struct pk_sound_renderer* render) {
    if (!render) return;
    struct mCore* core = render->core;
    core->runFrame(core);
    if (render->gbAudio.left) {
        render->audioAvail = pk_gb_audio_read(&render->gbAudio, render->audio, PK_AUDIO_SCRATCH_SAMPLES) * 2;
        return;
    }
    struct blip_t* left = core->getAudioChannel(core, 0);
    struct blip_t* right = core->getAudioChannel(core, 1);
    int available = blip_samples_avail(left);
    if (available > PK_AUDIO_SCRATCH_SAMPLES) available = PK_AUDIO_SCRATCH_SAMPLES;
    if (available > 0) {
        blip_read_samples(left, render->audio, available, 1);
        blip_read_samples(right, render->audio + 1, available, 1);
    }
    render->audioAvail = available * 2;
}

int pk_api_sound_audio(struct pk_sound_renderer* render, int16_t* dst, int capacity) {
    if (!render || !dst || capacity <= 0 || render->audioAvail <= 0) return 0;
    int count = render->audioAvail < capacity ? render->audioAvail : capacity;
    memcpy(dst, render->audio, (size_t) count * sizeof(*dst));
    return count;
}

bool pk_api_sound_read(struct pk_sound_renderer* render, uint32_t addr, uint8_t* dst, int len) {
    if (!render || !dst || len <= 0 || len > (1 << 24)) return false;
    pk_read_range(render->core, addr, dst, len);
    return true;
}

// Calls the ROM's own m4aSongNumStart(songId, alt) at `addr` (FfMusicRenderer
// finds it with M4aSongs) on the render core, cleanly (pk_call). `alt` picks
// Heart and Soul's alternate soundtrack. Other games ignore r1.
bool pk_api_sound_song(struct pk_sound_renderer* render, uint32_t addr, int songId, int alt) {
    if (!render || !render->core->cpu || !pk_is_gba(render->core)) {
        return false;
    }
    const uint32_t args[4] = {(uint32_t) songId, (uint32_t) alt, 0, 0};
    return pk_call(render->core, addr, args, NULL);
}

// Parks the render core's main loop on `spin` (a Thumb `b .`) so the booted
// game can't change the music while a song records; the VBlank interrupt
// keeps the sound engine running. Steps first until the CPU is in the main
// context (System/User mode, IRQs on) with the VBlank IRQ fully enabled (IME,
// IE bit 0, DISPSTAT bit 3) - games switch it off around loads, and parking
// then left the music frozen (Unbound). Tested with mgba_dump's `park`.
bool pk_api_sound_park_gba(struct pk_sound_renderer* render, uint32_t spin) {
    if (!render || !render->core->cpu || !pk_is_gba(render->core)) {
        return false;
    }
    struct mCore* core = render->core;
    struct ARMCore* cpu = (struct ARMCore*) core->cpu;
    for (int i = 0; i < 5000000; i++) {
        if ((cpu->privilegeMode == MODE_SYSTEM || cpu->privilegeMode == MODE_USER) && !cpu->cpsr.i && !cpu->halted
            && (core->busRead16(core, 0x04000208) & 1) && (core->busRead16(core, 0x04000200) & 1)
            && (core->busRead16(core, 0x04000004) & 8)) {
            cpu->gprs[ARM_PC] = (int32_t) (spin & ~1u);
            _ARMSetMode(cpu, MODE_THUMB);
            ThumbWritePC(cpu);
            return true;
        }
        core->step(core);
    }
    return false;
}

// Park the Game Boy render core at the first instruction with
// interrupts on (IME, IE's VBlank bit, the LCD on), its PC goes to `spin` - a
// `jr @` (18 FE) in ROM bank 0 - and only the VBlank handler, which runs the
// sound engine every frame, keeps going. Tested with mgba_dump's `gbpark`.
bool pk_api_sound_park_gb(struct pk_sound_renderer* render, uint16_t spin) {
    if (!render || pk_is_gba(render->core)) {
        return false;
    }
    struct mCore* core = render->core;
    struct GB* gb = (struct GB*) core->board;
    struct SM83Core* cpu = (struct SM83Core*) core->cpu;
    for (int i = 0; i < 5000000; i++) {
        core->step(core);   // ends on an instruction boundary (SM83_CORE_FETCH)
        if (gb->memory.ime && (gb->memory.ie & 1) && (gb->memory.io[GB_REG_LCDC] & 0x80) && !cpu->halted && !cpu->irqPending) {
            // A spin in WRAM (Red / Blue: bank 0 holds no `jr @`) is written there first - a byte the
            // parked game never touches again.
            if (spin >= 0xC000 && spin < 0xDFFF) {
                core->busWrite8(core, spin, 0x18);
                core->busWrite8(core, (uint16_t) (spin + 1), 0xFE);
            }
            cpu->pc = spin;
            cpu->memory.setActiveRegion(cpu, cpu->pc);
            return true;
        }
    }
    return false;
}

// Calls the game's `fn` (bank 0) with A = `a`, C = `c` on the parked GB render core
// and returns once it comes back to `ret` (the park loop, pushed as the return
// address), interrupts held off meanwhile so the VBlank handler can't run halfway
// through it. Gen 1: PlayMusic(a = song id, c = its audio bank); Crystal: PlayMusic(de = song id), so DE is set to c too.
bool pk_api_sound_call_gb(struct pk_sound_renderer* render, uint16_t fn, uint8_t a, uint8_t c, uint16_t ret) {
    if (!render || pk_is_gba(render->core)) {
        return false;
    }
    struct mCore* core = render->core;
    struct GB* gb = (struct GB*) core->board;
    struct SM83Core* cpu = (struct SM83Core*) core->cpu;
    bool ime = gb->memory.ime;
    bool pending = cpu->irqPending;
    gb->memory.ime = false;
    cpu->irqPending = false;
    cpu->sp -= 2;
    core->busWrite8(core, cpu->sp, (uint8_t) (ret & 0xFF));
    core->busWrite8(core, (uint16_t) (cpu->sp + 1), (uint8_t) ((ret >> 8) & 0xFF));
    cpu->a = a;
    cpu->c = c;
    cpu->d = 0;   // and DE = c: Crystal's PlayMusic takes its song in E
    cpu->e = c;
    cpu->halted = false;
    cpu->pc = fn;
    cpu->memory.setActiveRegion(cpu, cpu->pc);
    bool ok = false;
    for (int i = 0; i < 2000000; i++) {
        core->step(core);
        if (cpu->pc == ret) {
            ok = true;
            break;
        }
    }
    gb->memory.ime = ime;
    cpu->irqPending = pending;
    return ok;
}
