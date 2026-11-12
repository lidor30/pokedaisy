// See pk_nds_music.h.

#include "pk_nds_music.h"
#include "pk_nds_wide.h"
#include "pk_nds_platform.h"

#include <NDS.h>
#include <Savestate.h>

#include <algorithm>
#include <cstdio>
#include <memory>

using namespace melonDS;

namespace {

// The music instance's host: it has no cart, so no save to write - and if anything ever tried, nothing would be written.
PkNdsHost musicHost {nullptr, nullptr, nullptr};

struct Music {
    std::unique_ptr<NDS> nds;
    int sampleRate = 0;
    bool playing = false;
    float gain = 1.f, gainStep = 0.f;   // pk_nds_music_fade: down by gainStep a frame
    uint32_t cnt[16] = {};   // the channels' SOUNDxCNT last frame, and the frames they've all held while one played
    int still = 0;
};

Music music;

// What the ARM7 sends the ARM9 (the sound driver's replies) taken off its FIFO, as the stopped ARM9 would have: the
// FIFO holds 16 words, and once it's full the driver waits for room with interrupts off - for good, the song stops
// advancing and the channels loop what they hold (a buzz; a copy taken mid-battle filled it within 13 frames).
void drainArm7Fifo(NDS& n)
{
    for (int i = 0; i < 16 && !(n.ARM9Read16(0x04000184) & 0x100); i++) n.ARM9Read32(0x04100000);
}

// A flag in the shared work area the ARM7's sound side sets (bit 0 of 0x027FFF96 - Black and its sequels address it
// through 0x02FFFF96, the same byte: it spins with the BIOS's WaitByLoop while it's set, then sets it) and the ARM9 clears
// in its own frame (Black 2's 0x02080DA0). Stopped, the ARM9 never did: the second time the ARM7 wanted it, it spun for
// good, the sequencer stopped and the channels held their notes - a drone (Black 2 in Humilau City, 15 frames in).
// Cleared as the ARM9 would; the same code is in Platinum, HeartGold, Black, White and White 2.
void releaseArm7Flag(NDS& n)
{
    u16 f = n.ARM7Read16(0x027FFF96);
    if (f & 1) n.ARM7Write16(0x027FFF96, f & ~1);
}

} // namespace

bool pk_nds_music_begin(NDS* live, int sampleRate)
{
    music.playing = false;
    if (!live) return false;
    Savestate save;
    pk_nds_wide_sync();   // EXPAND's slices may still be drawing from [live]
    if (!live->DoSavestate(&save) || save.Error) return false;
    save.Finish();

    if (!music.nds || music.sampleRate != sampleRate) {
        NDSArgs args;
        args.OutputSampleRate = sampleRate;
        args.JIT = std::nullopt;   // its ARM7 idles most of the time: the interpreter is enough, and keeps it small
        music.nds = std::make_unique<NDS>(std::move(args), &musicHost);
        music.nds->Reset();
        music.nds->Start();
        music.sampleRate = sampleRate;
    }
    NDS& n = *music.nds;
    Savestate load(save.Buffer(), save.Length(), false);
    bool loaded = !load.Error && n.DoSavestate(&load) && !load.Error;
    // Its JIT compiler, built and reset though unused, put this thread's JIT memory in write mode (Apple Silicon only:
    // pthread_jit_write_protect_np is per thread): back to executing, or [live]'s JIT code faults.
    live->JIT.JitEnableExecute();
    if (!loaded) return false;
    // The ARM9 stopped for good: halted with no interrupt that could wake it. The game's logic, its screens and its
    // commands to the sound driver all stop with it; the ARM7 plays on.
    n.IME[0] = 0;
    n.IE[0] = 0;
    n.ARM9.Halt(1);
    // Nobody sees its screens: no 2D lines, no 3D, no display capture (which would draw anyway). Half its time a frame
    // went on them (Black: 1.0 -> 0.45 ms on the Mac), and the copy runs beside a game going 2-4x on the device.
    n.GPU.SkipDraw = n.GPU.SkipDrawNext = n.GPU.SkippingFrame = true;
    n.GPU.GPU2D_A.CaptureCnt &= ~(1u << 31);
    // Whatever the SPU had mixed but not handed out yet belongs to the game's own stream.
    int16_t drop[2048];
    while (n.SPU.ReadOutput(drop, 1024) > 0) {}
    music.gain = 1.f;
    music.gainStep = 0.f;
    music.still = 0;
    for (auto& c : music.cnt) c = 0;
    music.playing = true;
    return true;
}

int pk_nds_music_frame(int16_t* out, int maxFrames)
{
    if (!music.playing) return 0;
    drainArm7Fifo(*music.nds);
    releaseArm7Flag(*music.nds);
    music.nds->RunFrame();
    int got = music.nds->SPU.ReadOutput(out, maxFrames);
    // A driver that sequences writes its channels every few ms (envelopes, notes): their control words standing still
    // while one plays means it has stopped, and the channels loop what they hold - a buzz.
    bool changed = false, sounding = false;
    for (int ch = 0; ch < 16; ch++) {
        uint32_t c = music.nds->ARM7Read32(0x04000400 + ch * 16);
        changed |= c != music.cnt[ch];
        sounding |= (c >> 31) != 0;
        music.cnt[ch] = c;
    }
    music.still = changed || !sounding ? 0 : music.still + 1;
    if (music.gainStep > 0.f || music.gain < 1.f) {
        // Ramped across the frame, so the fade has no steps.
        float from = music.gain, to = std::max(0.f, music.gain - music.gainStep);
        for (int i = 0; i < got; i++) {
            float g = from + (to - from) * i / std::max(1, got);
            out[i * 2] = (int16_t) (out[i * 2] * g);
            out[i * 2 + 1] = (int16_t) (out[i * 2 + 1] * g);
        }
        music.gain = to;
    }
    return got;
}

// Debug: the instance's ARM7 (PC, LR, SP, halted, IME / IE / IF) and the IPC between the CPUs.
void pk_nds_music_debug(char* out, int len)
{
    if (!music.nds) { snprintf(out, len, "none"); return; }
    NDS& n = *music.nds;
    int k = snprintf(out, len, "arm7 r0 %08X r1 %08X r4 %08X pc %08X lr %08X sp %08X halted %d ime %X ie %08X if %08X | "
        "ipcsync %04X fifocnt %04X | flag %04X |", n.ARM7.R[0], n.ARM7.R[1], n.ARM7.R[4], n.ARM7.R[15], n.ARM7.R[14],
        n.ARM7.R[13], (int) n.ARM7.Halted, n.IME[1], n.IE[1], n.IF[1], n.ARM7Read16(0x04000180), n.ARM7Read16(0x04000184),
        n.ARM7Read16(0x027FFF96));
    // The channels sounding (their SOUNDxCNT; the other registers are write-only)
    for (int ch = 0; ch < 16 && k > 0 && k < len; ch++) {
        u32 c = n.ARM7Read32(0x04000400 + ch * 16);
        if (c >> 31) k += snprintf(out + k, len - k, " %d:%08X", ch, c);
    }
}

int pk_nds_music_stalled(void) { return music.playing ? music.still : 0; }

void pk_nds_music_fade(int frames)
{
    if (music.gainStep == 0.f && music.gain > 0.f) music.gainStep = music.gain / std::max(1, frames);
}

void pk_nds_music_end(bool release)
{
    music.playing = false;
    if (release) music.nds.reset();
}
