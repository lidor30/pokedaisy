// The m4a engine's SoundMainRAM in C. It's the ARM code every Pokémon game on the GBA copies to IWRAM and runs once a frame to
// mix its sample channels, more than half the instructions of a frame in Unbound, and a New 3DS emulates a GBA instruction in
// about 180 of its own cycles. 106 of the 136 GBA ROMs the app knows carry it byte for byte (only two literals apart), so a
// BKPT over its first instruction hands the whole function to this file.
//
// Everything here is the routine's own arithmetic. `ror 8` over a word of four samples is, one output sample at a time, a
// byte add, so the buffers are bytes here; what's left alike down to which channel fields a finished sample does or doesn't
// write. With PD_MIXER_CHECK (pd_sim) both run on the same state and every byte of the result is compared.
//
// Unbound, Odyssey, GS Chronicles and Rocket Edition carry another mixer in its place, ipatix's high quality one: it mixes
// both sides at once into 32-bit words (left in the low half, right in the high), writes a loop for the channel's pitch over its
// own code, and copies samples to the stack by DMA, all to be fast on a GBA. Here that's three loops and a plain read: hq_*.
#include "pd_mixer.h"

#include <stdio.h>
#include <string.h>

#include <mgba-util/crc32.h>
#include <mgba/internal/arm/isa-inlines.h>
#include <mgba/internal/gba/gba.h>

#include "../emu/pd_armgba.h"

#define HOOK 0xD7
#define HOOK_OP (0xBE00 | HOOK)
#define FIRST_OP 0x7943 // both mixers' `ldrb r3, [r0, 5]`
#define CHECK_EXIT_HOOK 0xD8

// SoundMainRAM: the first instructions, its size up to its literal pool's two addresses, their CRC.
static const uint8_t SIGNATURE[10] = { 0x43, 0x79, 0x00, 0x2B, 0x2C, 0xD0, 0x01, 0xA1, 0x08, 0x47 };
#define CODE_SIZE 0x6FC
#define CODE_CRC 0x4B7183D5u
#define DELTA_TABLE_AT 0x700  // gDeltaEncodingTable's address, past the code
#define BUFFER_LITERAL (-16)  // SoundMain's lt_SoundMainRAM_Buffer, before the routine
#define EXIT_AT 0x3A2         // _081DD24A: the channels are done

// The high quality mixer, all of it through its delta table (the same in every ROM that has it).
static const uint8_t HQ_SIGNATURE[10] = { 0x43, 0x79, 0x9B, 0x08, 0x40, 0xD0, 0x01, 0xA1, 0x08, 0x47 };
#define HQ_SIZE 0xB24
#define HQ_CRC 0x7bed3b06u
#define HQ_STABLE 0x18C        // the part of it that doesn't rewrite itself in IWRAM
#define HQ_DELTA_TABLE 0xB14
#define HQ_EXIT_AT 0x62A
#define HQ_MAX_SAMPLES 1600

#define PCM_DMA_BUF_SIZE 0x630
#define ID_NUMBER 0x68736D53u
#define VCOUNT_VBLANK 160
#define TOTAL_SCANLINES 228

// struct SoundInfo.
#define I_PCM_DMA_COUNTER 4
#define I_REVERB 5
#define I_MAX_CHANS 6
#define I_MASTER_VOLUME 7
#define I_DIV_FREQ 0x18
#define I_CHANS 0x50
#define I_PCM_BUFFER 0x350
// struct SoundChannel.
#define C_STATUS 0
#define C_TYPE 1
#define C_RIGHT 2
#define C_LEFT 3
#define C_ATTACK 4
#define C_DECAY 5
#define C_SUSTAIN 6
#define C_RELEASE 7
#define C_ENV 9
#define C_ENV_RIGHT 0xA
#define C_ENV_LEFT 0xB
#define C_ECHO_VOLUME 0xC
#define C_ECHO_LENGTH 0xD
#define C_COUNT 0x18
#define C_FW 0x1C
#define C_FREQUENCY 0x20
#define C_WAV 0x24
#define C_POINTER 0x28
#define C_XPI 0x3C
#define C_SIZE 0x40
// struct WaveData.
#define W_TYPE 0
#define W_FLAGS 3
#define W_LOOP_START 8
#define W_SIZE 0xC
#define W_DATA 0x10

#define SF_START 0x80
#define SF_STOP 0x40
#define SF_SPECIAL 0x20
#define SF_LOOP 0x10
#define SF_IEC 0x04
#define SF_ENV 0x03
#define SF_ENV_ATTACK 3
#define SF_ENV_DECAY 2
#define SF_ON (SF_START | SF_STOP | SF_IEC | SF_ENV)
#define TYPE_FIX 0x08
#define TYPE_REV 0x10
#define TYPE_CMP 0x20
#define WAVE_LOOP 0xC0
#define FRACTION_CLEAR 0x3F800000u

// Where the routine's memory is: the game's, or in a check a copy of it.
struct mem {
    uint8_t* ewram;
    uint8_t* iwram;
    const uint8_t* rom;
    size_t romSize;
};

enum mixer { MIXER_NONE, MIXER_STOCK, MIXER_HQ };

static uint32_t entry;           // SoundMainRAM in IWRAM
static uint32_t deltaTable;
static const uint8_t* stock;     // the mixers' code in the ROM, NULL = this game doesn't have it
static const uint8_t* hq;
static enum mixer running;       // which of them IWRAM holds
static bool attached;
static void (*forward16)(struct ARMCore* cpu, int immediate);

// The core the hook runs on: mGBA (its BKPT handler, the CPU it hands over) or armGBA (pd_armgba.h's BKPT hook, its CPU
// through pd_armgba_reg / _set_reg / _jump). The game's RAM and ROM are where the core's struct GBA points either way.
static bool onArmgba;
unsigned pd_mixer_mixes; // native mixes so far (the dev build's stat)
static struct GBA* hookedGba;
static struct ARMCore* mcpu;

static uint32_t cpu_reg(int n) {
    return onArmgba ? pd_armgba_reg(n) : (uint32_t) mcpu->gprs[n];
}

static void cpu_set(int n, uint32_t v) {
    if (onArmgba) pd_armgba_set_reg(n, v);
    else mcpu->gprs[n] = (int32_t) v;
}

// To [to], Thumb with bit 0 set.
static void cpu_jump(uint32_t to) {
    if (onArmgba) {
        pd_armgba_jump(to);
        return;
    }
    _ARMSetMode(mcpu, to & 1 ? MODE_THUMB : MODE_ARM);
    mcpu->gprs[ARM_PC] = (int32_t) (to & ~1u);
    if (to & 1) ThumbWritePC(mcpu);
    else ARMWritePC(mcpu);
}

static int cpu_vcount(void) {
    return onArmgba ? pd_armgba_vcount() : hookedGba->video.vcount;
}

static uint32_t le32(const uint8_t* p) {
    return (uint32_t) p[0] | (uint32_t) p[1] << 8 | (uint32_t) p[2] << 16 | (uint32_t) p[3] << 24;
}

static void put32(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t) v;
    p[1] = (uint8_t) (v >> 8);
    p[2] = (uint8_t) (v >> 16);
    p[3] = (uint8_t) (v >> 24);
}

// The RAM at [addr], and how many bytes are left of it; NULL off the two RAMs.
static uint8_t* ram(const struct mem* m, uint32_t addr, uint32_t* left) {
    uint32_t region = addr >> 24;
    if (region == 2) {
        uint32_t at = addr & 0x3FFFF;
        *left = 0x40000 - at;
        return m->ewram + at;
    }
    if (region == 3) {
        uint32_t at = addr & 0x7FFF;
        *left = 0x8000 - at;
        return m->iwram + at;
    }
    *left = 0;
    return NULL;
}

static uint8_t read8(const struct mem* m, uint32_t addr) {
    uint32_t region = addr >> 24, left;
    if (region >= 8 && region <= 0xD) {
        uint32_t at = addr & 0x1FFFFFF;
        return at < m->romSize ? m->rom[at] : 0;
    }
    const uint8_t* p = ram(m, addr, &left);
    return p ? *p : 0;
}

static uint32_t read32(const struct mem* m, uint32_t addr) {
    return (uint32_t) read8(m, addr) | (uint32_t) read8(m, addr + 1) << 8 | (uint32_t) read8(m, addr + 2) << 16 |
           (uint32_t) read8(m, addr + 3) << 24;
}

// A sample stream, read straight from where it starts; anything outside goes the long way.
struct stream {
    const struct mem* m;
    const uint8_t* base;
    uint32_t low, span;
};

static void stream_at(struct stream* s, const struct mem* m, uint32_t addr) {
    s->m = m;
    uint32_t region = addr >> 24, left;
    if (region >= 8 && region <= 0xD) {
        // A 16 MB stretch at a time, so an address is an offset from its start.
        uint32_t at = addr & 0x1000000;
        s->base = m->rom + at;
        s->low = addr & 0xFF000000u;
        s->span = at < m->romSize ? (uint32_t) (m->romSize - at < 0x1000000 ? m->romSize - at : 0x1000000) : 0;
        return;
    }
    const uint8_t* p = ram(m, addr, &left);
    s->base = p;
    s->low = addr;
    s->span = p ? left : 0;
}

static inline int sample(const struct stream* s, uint32_t addr) {
    uint32_t at = addr - s->low;
    return at < s->span ? (int8_t) s->base[at] : (int8_t) read8(s->m, addr);
}

// What the channel's two volumes add to an output byte for [v]: `mul`, `bic 0xFF0000`, the add into the word's top byte.
#define MIX(out, volume, v) ((out) = (uint8_t) ((out) + (uint8_t) (((uint32_t) (volume) * (uint32_t) (v)) >> 8)))

struct channel {
    const struct mem* m;
    uint8_t* c;
    uint8_t* out;
    int count;              // output samples
    int right, left;
    int32_t left2;          // r2: samples of the wave left
    uint32_t pointer, fw;   // r3, r9
    uint32_t loopPointer;
    int32_t loopLength;     // 0 = no loop
    uint32_t divFreq;
    uint8_t decoded[64];
    int8_t delta[16];
};

#ifdef PD_MIXER_CHECK
struct pd_mixer_stats pd_mixer_stats;
#define COUNT(name) (pd_mixer_stats.name++)
#else
#define COUNT(name)
#endif

// SoundMainRAM_Unk2: a compressed wave's sample [index]; a block of 64 is decoded when the index leaves the last one.
static int decoded(struct channel* h, uint32_t wave, uint32_t index) {
    uint8_t* c = h->c;
    uint32_t block = index >> 6;
    if (block != le32(c + C_XPI)) {
        put32(c + C_XPI, block);
        uint32_t at = 0x21 * block + wave + 0x10;
        uint32_t level = read8(h->m, at++);
        uint8_t* out = h->decoded;
        *out++ = (uint8_t) level;
        uint32_t byte = read8(h->m, at++);
        for (int left = 0x40;;) {
            level += (uint32_t) (int32_t) h->delta[byte & 0xF];
            *out++ = (uint8_t) level;
            left -= 2;
            if (left <= 0) break;
            byte = read8(h->m, at++);
            level += (uint32_t) (int32_t) h->delta[byte >> 4];
            *out++ = (uint8_t) level;
        }
    }
    return (int8_t) h->decoded[index & 0x3F];
}

// The sample's end: the channel stops (_081DD4F4's half of what's common).
static void stop(struct channel* h) {
    h->c[C_STATUS] = 0;
}

// SoundMainRAM_Unk1: compressed and reversed waves.
static void mix_special(struct channel* h) {
    uint8_t* c = h->c;
    const struct mem* m = h->m;
    uint32_t wave = le32(c + C_WAV);
    uint32_t r3 = h->pointer;
    int type = c[C_TYPE];
    if (!(c[C_STATUS] & SF_SPECIAL)) {
        c[C_STATUS] |= SF_SPECIAL;
        if (type & TYPE_REV) {
            r3 = read32(m, wave + W_SIZE) + wave * 2 + 0x20 - r3;
            put32(c + C_POINTER, r3);
        }
        if ((read8(m, wave + W_TYPE) | read8(m, wave + W_TYPE + 1) << 8)) {
            r3 = r3 - wave - 0x10;
            put32(c + C_POINTER, r3);
        }
    }
    uint32_t step = type & TYPE_FIX ? 0x800000u : h->divFreq * le32(c + C_FREQUENCY);
    bool compressed = read8(m, wave + W_TYPE) | read8(m, wave + W_TYPE + 1) << 8;
    bool reversed = type & TYPE_REV;
    uint8_t* o = h->out;
    int32_t r2 = h->left2;
    uint32_t r9 = h->fw;
    int32_t r0, r1;
    struct stream s;
    if (compressed) {
        COUNT(compressed);
        put32(c + C_XPI, 0xFF000000u);
        if (reversed) {
            r3 -= 1;
            r0 = decoded(h, wave, r3);
            r3 -= 1;
            r1 = decoded(h, wave, r3) - r0;
        } else {
            r0 = decoded(h, wave, r3);
            r3 += 1;
            r1 = decoded(h, wave, r3) - r0;
        }
    } else {
        if (!reversed) {
            h->pointer = r3;
            return;
        }
        COUNT(reversed);
        stream_at(&s, m, r3);
        r3 -= 1;
        r0 = sample(&s, r3);
        r1 = sample(&s, r3 - 1) - r0;
    }
    for (int i = 0; i < h->count; i++, o++) {
        int32_t v = r0 + ((int32_t) (r9 * (uint32_t) r1) >> 23);
        MIX(o[0], h->right, v);
        MIX(o[PCM_DMA_BUF_SIZE], h->left, v);
        r9 += step;
        uint32_t advance = r9 >> 23;
        if (!advance) continue;
        r9 &= ~FRACTION_CLEAR;
        r2 -= (int32_t) advance;
        if (r2 <= 0) {
            if (!compressed || reversed || !h->loopLength) {
                r2 = 0;
                stop(h);
                h->pointer = r3;
                h->left2 = r2;
                h->fw = r9;
                return;
            }
            // Looping: from the loop's start, as far past it as the step overshot.
            r3 = read32(m, wave + W_LOOP_START);
            uint32_t past = (uint32_t) -r2;
            for (;;) {
                r2 += h->loopLength;
                if (r2 > 0) break;
                past -= (uint32_t) h->loopLength;
            }
            r3 += past;
            r0 = decoded(h, wave, r3);
            r3 += 1;
            r1 = decoded(h, wave, r3) - r0;
            continue;
        }
        if (compressed) {
            advance -= 1;
            if (!advance) {
                r0 += r1;
            } else {
                r3 = reversed ? r3 - advance : r3 + advance;
                r0 = decoded(h, wave, r3);
            }
            r3 = reversed ? r3 - 1 : r3 + 1;
            r1 = decoded(h, wave, r3) - r0;
        } else {
            r3 -= advance;
            r0 = sample(&s, r3);
            r1 = sample(&s, r3 - 1) - r0;
        }
    }
    r3 = compressed ? (reversed ? r3 + 2 : r3 - 1) : r3 + 1;
    h->pointer = r3;
    h->left2 = r2;
    h->fw = r9;
}

// TONEDATA_TYPE_FIX: a sample out for a sample in. False: the wave ended.
static bool mix_fixed(struct channel* h) {
    struct stream s;
    uint32_t p = h->pointer;
    int32_t r2 = h->left2;
    uint8_t* o = h->out;
    stream_at(&s, h->m, p);
    COUNT(fixed);
    for (int i = 0; i < h->count; i++, o++) {
        int v = sample(&s, p++);
        MIX(o[0], h->right, v);
        MIX(o[PCM_DMA_BUF_SIZE], h->left, v);
        if (--r2 == 0) {
            if (!h->loopLength) return false;
            r2 = h->loopLength;
            p = h->loopPointer;
        }
    }
    h->pointer = p;
    h->left2 = r2;
    return true;
}

// The usual channel: resampled by its frequency, a line between two samples. False: the wave ended.
static bool mix_resampled(struct channel* h) {
    struct stream s;
    uint32_t r3 = h->pointer, r9 = h->fw;
    int32_t r2 = h->left2;
    uint32_t step = h->divFreq * le32(h->c + C_FREQUENCY);
    uint8_t* o = h->out;
    stream_at(&s, h->m, r3);
    COUNT(resampled);
    int32_t r0 = sample(&s, r3);
    r3 += 1;
    int32_t r1 = sample(&s, r3) - r0;
    for (int i = 0; i < h->count; i++, o++) {
        int32_t v = r0 + ((int32_t) (r9 * (uint32_t) r1) >> 23);
        MIX(o[0], h->right, v);
        MIX(o[PCM_DMA_BUF_SIZE], h->left, v);
        r9 += step;
        uint32_t advance = r9 >> 23;
        if (!advance) continue;
        r9 &= ~FRACTION_CLEAR;
        r2 -= (int32_t) advance;
        if (r2 <= 0) {
            if (!h->loopLength) return false;
            uint32_t past = (uint32_t) -r2;
            for (;;) {
                r2 += h->loopLength;
                if (r2 > 0) break;
                past -= (uint32_t) h->loopLength;
            }
            r3 = h->loopPointer + past;
            r0 = sample(&s, r3);
        } else {
            advance -= 1;
            if (!advance) {
                r0 += r1;
            } else {
                r3 += advance;
                r0 = sample(&s, r3);
            }
        }
        r3 += 1;
        r1 = sample(&s, r3) - r0;
    }
    h->pointer = r3 - 1;
    h->left2 = r2;
    h->fw = r9;
    return true;
}

// The channel's envelope for this frame; false: it's off now.
static bool envelope(uint8_t* c, const struct mem* m, uint32_t wave, const uint8_t* info, bool volumes) {
    int st = c[C_STATUS];
    uint32_t env;
    if (st & SF_START) {
        if (st & SF_STOP) {
            c[C_STATUS] = 0;
            return false;
        }
        st = SF_ENV_ATTACK;
        c[C_STATUS] = (uint8_t) st;
        uint32_t count = le32(c + C_COUNT);
        put32(c + C_POINTER, wave + W_DATA + count);
        put32(c + C_COUNT, read32(m, wave + W_SIZE) - count);
        env = 0;
        c[C_ENV] = 0;
        put32(c + C_FW, 0);
        if (read8(m, wave + W_FLAGS) & WAVE_LOOP) {
            st |= SF_LOOP;
            c[C_STATUS] = (uint8_t) st;
        }
        goto attack;
    }
    env = c[C_ENV];
    if (st & SF_IEC) {
        int before = c[C_ECHO_LENGTH];
        c[C_ECHO_LENGTH] = (uint8_t) (before - 1);
        if (before > 1) goto set;
        c[C_STATUS] = 0;
        return false;
    }
    if (st & SF_STOP) {
        env = env * c[C_RELEASE] >> 8;
        if (env > c[C_ECHO_VOLUME]) goto set;
    echo:
        env = c[C_ECHO_VOLUME];
        if (!env) {
            c[C_STATUS] = 0;
            return false;
        }
        st |= SF_IEC;
        c[C_STATUS] = (uint8_t) st;
        goto set;
    }
    if ((st & SF_ENV) == SF_ENV_DECAY) {
        env = env * c[C_DECAY] >> 8;
        if (env > c[C_SUSTAIN]) goto set;
        env = c[C_SUSTAIN];
        if (!env) goto echo;
        st -= 1;
        c[C_STATUS] = (uint8_t) st;
        goto set;
    }
    if ((st & SF_ENV) != SF_ENV_ATTACK) goto set;
attack:
    env += c[C_ATTACK];
    if (env >= 0xFF) {
        env = 0xFF;
        st -= 1;
        c[C_STATUS] = (uint8_t) st;
    }
set:
    c[C_ENV] = (uint8_t) env;
    if (!volumes) return true;
    uint32_t level = (uint32_t) (info[I_MASTER_VOLUME] + 1) * env >> 4;
    c[C_ENV_RIGHT] = (uint8_t) (c[C_RIGHT] * level >> 8);
    c[C_ENV_LEFT] = (uint8_t) (c[C_LEFT] * level >> 8);
    return true;
}

// The whole of it, from SoundMainRAM's first instruction to the exit. False: nothing done (its memory isn't where it should
// be), and the game's own code has to run.
static bool mix(const struct mem* m, uint32_t infoAt, int dmaCounter, uint32_t buffer, int samples, int stopLine, int vcount) {
    uint32_t left, bufferLeft;
    uint8_t* info = ram(m, infoAt, &left);
    uint8_t* out = ram(m, buffer, &bufferLeft);
    if (!info || left < I_PCM_BUFFER + 2 * PCM_DMA_BUF_SIZE || !out || samples <= 0 || samples & 3 ||
        bufferLeft < (uint32_t) PCM_DMA_BUF_SIZE + (uint32_t) samples + 16) return false;

    int reverb = info[I_REVERB];
    if (reverb) {
        COUNT(reverb);
        uint8_t* p = out;
        const uint8_t* q = dmaCounter == 2 ? info + I_PCM_BUFFER : out + samples;
        for (int i = 0; i < samples; i++, p++, q++) {
            int32_t v = (int8_t) p[PCM_DMA_BUF_SIZE] + (int8_t) p[0] + (int8_t) q[PCM_DMA_BUF_SIZE] + (int8_t) q[0];
            v = v * reverb >> 9;
            if (v & 0x80) v += 1;
            p[PCM_DMA_BUF_SIZE] = p[0] = (uint8_t) v;
        }
    } else {
        int bytes = (samples & 4) + (samples & 8) + (samples >> 4 ? samples >> 4 : 1) * 16;
        memset(out, 0, (size_t) bytes);
        memset(out + PCM_DMA_BUF_SIZE, 0, (size_t) bytes);
    }

    static struct channel h;
    h.m = m;
    h.out = out;
    h.count = samples;
    h.divFreq = le32(info + I_DIV_FREQ);
    for (int i = 0; i < 16; i++) h.delta[i] = (int8_t) read8(m, deltaTable + (uint32_t) i);
    int chans = info[I_MAX_CHANS];
    uint8_t* c = info + I_CHANS;
    for (;;) {
        if (stopLine) {
            int line = vcount < VCOUNT_VBLANK ? vcount + TOTAL_SCANLINES : vcount;
            if (line >= stopLine) break;
        }
        uint32_t wave = le32(c + C_WAV);
        if ((c[C_STATUS] & SF_ON) && envelope(c, m, wave, info, true)) {
            h.c = c;
            h.loopLength = 0;
            h.loopPointer = 0;
            if (c[C_STATUS] & SF_LOOP) {
                uint32_t start = read32(m, wave + W_LOOP_START);
                h.loopPointer = wave + W_DATA + start;
                h.loopLength = (int32_t) (read32(m, wave + W_SIZE) - start);
            }
            h.left2 = (int32_t) le32(c + C_COUNT);
            h.pointer = le32(c + C_POINTER);
            h.fw = le32(c + C_FW);
            h.right = c[C_ENV_RIGHT];
            h.left = c[C_ENV_LEFT];
            int type = c[C_TYPE];
            if (type & (TYPE_CMP | TYPE_REV)) {
                mix_special(&h);
                put32(c + C_FW, h.fw);
                put32(c + C_COUNT, (uint32_t) h.left2);
                put32(c + C_POINTER, h.pointer);
            } else if (type & TYPE_FIX) {
                if (mix_fixed(&h)) {
                    put32(c + C_COUNT, (uint32_t) h.left2);
                    put32(c + C_POINTER, h.pointer);
                } else {
                    stop(&h);
                    COUNT(ended);
                }
            } else if (mix_resampled(&h)) {
                put32(c + C_FW, h.fw);
                put32(c + C_COUNT, (uint32_t) h.left2);
                put32(c + C_POINTER, h.pointer);
            } else {
                stop(&h);
                COUNT(ended);
            }
        }
        if (--chans <= 0) break;
        c += C_SIZE;
    }
    put32(info, ID_NUMBER);
    return true;
}

// --- the high quality mixer ---

static uint32_t hqWords[HQ_MAX_SAMPLES + 16];

// A channel's two volumes, packed, to its scale for a sample doubled: `lsrs; adc 0x8000; bic 0xFF00`.
static uint32_t hq_half(uint32_t fp) {
    uint32_t carry = fp & 1;
    return ((fp >> 1) + 0x8000 + carry) & ~0xFF00u;
}

// TONEDATA_TYPE_FIX. False: the wave ended, the channel is off and its fields stay.
static bool hq_fixed(struct channel* h, uint32_t fp, int count) {
    struct stream s;
    uint32_t p = h->pointer;
    int32_t r2 = h->left2;
    stream_at(&s, h->m, p);
    COUNT(fixed);
    for (int i = 0; i < count; i++) {
        hqWords[i] += fp * (uint32_t) sample(&s, p++);
        if (--r2 == 0) {
            if (!h->loopLength) return false;
            r2 = h->loopLength;
            p = h->loopPointer;
        }
    }
    h->pointer = p;
    h->left2 = r2;
    return true;
}

// A wave built from its header's bytes, by the type in its second: pulse, saw, triangle. [p] is that byte's address.
static void hq_synth(struct channel* h, uint32_t fp, uint32_t step, int kind, uint32_t p, int count) {
    uint32_t r7 = h->fw;
    int32_t r2 = h->left2;
    const struct mem* m = h->m;
    uint32_t delta = step << 3;
    if (kind == 0) {
        r2 = (int32_t) ((uint32_t) r2 + ((uint32_t) read8(m, p + 2) << 24));
        uint32_t r6 = (uint32_t) r2 + ((uint32_t) read8(m, p + 4) << 24);
        if ((int32_t) r6 < 0) r6 = ~r6;
        uint32_t edge = (r6 >> 8) * read8(m, p + 3) + ((uint32_t) read8(m, p + 1) << 24);
        uint32_t level = fp << 6;
        for (int i = 0; i < (count + 7) / 8 * 8; i++) {
            if (r7 < edge) hqWords[i] += level;
            else hqWords[i] -= level;
            r7 += delta;
        }
    } else if (kind == 1) {
        uint32_t f = (fp >> 1) & ~0xFF00u;
        for (int i = 0; i < (count + 3) / 4 * 4; i++) {
            r7 += delta;
            int32_t v = (int32_t) (r7 >> 24) - 0x70 - (int32_t) ((r7 << 1) >> 27);
            r2 = v + (r2 >> 1);
            hqWords[i] += f * (uint32_t) r2;
        }
    } else {
        for (int i = 0; i < (count + 3) / 4 * 4; i++) {
            r7 += delta;
            int32_t v = (int32_t) r7 >= 0 ? ((int32_t) r7 >> 23) - 0x80 : 0x180 - (int32_t) (r7 >> 23);
            hqWords[i] += fp * (uint32_t) v;
        }
    }
    h->fw = r7;
    h->left2 = r2;
}

// The usual channel. False: the wave ended, the channel is off and its fields stay.
static bool hq_resampled(struct channel* h, uint32_t fp, int count) {
    uint8_t* c = h->c;
    struct stream s;
    uint32_t r3 = h->pointer, r7 = h->fw;
    int32_t r2 = h->left2;
    int type = c[C_TYPE];
    if (!r2) {
        type |= 0x40;
        c[C_TYPE] = (uint8_t) type;
    }
    uint32_t step = h->divFreq * le32(c + C_FREQUENCY);
    stream_at(&s, h->m, r3);
    int32_t r6 = sample(&s, r3);
    r3 += 1;
    int32_t ip = sample(&s, r3);
    if (type & 0x40) {
        hq_synth(h, fp, step, ip == 0 ? 0 : ip == 1 ? 1 : 2, r3, count);
        h->pointer = r3 - 1;
        return true;
    }
    ip -= r6;
    COUNT(resampled);
    uint32_t f = hq_half(fp);
    // How far the frame takes it: `umlal` over the type byte the code leaves in the top word.
    uint64_t reach = ((uint64_t) (uint32_t) type << 32 | r7) + (uint64_t) step * (uint32_t) count;
    uint32_t need = (uint32_t) ((uint32_t) reach >> 23) | (uint32_t) ((uint32_t) (reach >> 32) << 9);
    if (r2 <= (int32_t) need) {
        // The wave may end inside this frame: a sample at a time.
        for (int i = 0; i < count; i++) {
            int32_t v = ((int32_t) (r7 * (uint32_t) ip) >> 22) + r6 * 2;
            hqWords[i] += f * (uint32_t) v;
            r7 += step;
            uint32_t advance = r7 >> 23;
            if (!advance) continue;
            r2 -= (int32_t) advance;
            if (r2 <= 0) {
                if (!h->loopLength) return false;
                r3 = h->loopPointer;
                uint32_t past = (uint32_t) -r2;
                r2 += h->loopLength;
                r3 += past;
                r6 = sample(&s, r3);
            } else {
                advance -= 1;
                if (!advance) {
                    r6 += ip;
                } else {
                    r3 += advance;
                    r6 = sample(&s, r3);
                }
            }
            r3 += 1;
            ip = sample(&s, r3) - r6;
            r7 &= ~FRACTION_CLEAR;
        }
        h->pointer = r3 - 1;
        h->left2 = r2;
        h->fw = r7;
        return true;
    }
    // The three loops the code writes over itself, by the channel's pitch: under a sample out per sample in, under two, and past.
    // All but the first of eight multiplies are `mulne` on the flags the last sample's `subs` left, so where two samples are
    // equal the multiply is skipped and the last value goes through the shift in its place, which is -1 for a negative one.
    uint32_t end = r3 + need - 1;
    r2 -= (int32_t) need;
    int32_t rest = (int32_t) step - 0x800000;
    int loops = (count + 7) / 8 * 8;
    int32_t r9 = 0;
    bool same = false;
    if (rest < 0) {
        for (int i = 0; i < loops; i++) {
            if (!(i & 7) || !same) r9 = (int32_t) (r7 * (uint32_t) ip);
            r9 = (r9 >> 22) + r6 * 2;
            hqWords[i] += f * (uint32_t) r9;
            r7 += (uint32_t) rest;
            same = false;
            if ((int32_t) r7 >= 0) {
                r6 += ip;
                r3 += 1;
                ip = sample(&s, r3) - r6;
                same = !ip;
            }
            r7 &= 0x7FFFFF;
        }
    } else {
        f <<= 1;
        rest -= 0x800000;
        if (rest < 0) {
            for (int i = 0; i < loops; i++) {
                if (!(i & 7) || !same) r9 = (int32_t) (r7 * (uint32_t) ip);
                r9 = (r9 >> 23) + r6;
                hqWords[i] += f * (uint32_t) r9;
                r6 += ip;
                r7 += (uint32_t) rest;
                if ((int32_t) r7 >= 0) {
                    r3 += 1;
                    r6 = sample(&s, r3);
                }
                r3 += 1;
                ip = sample(&s, r3) - r6;
                same = !ip;
                r7 &= 0x7FFFFF;
            }
        } else {
            rest += 0x800000;
            for (int i = 0; i < loops; i++) {
                if (!(i & 7) || !same) r9 = (int32_t) (r7 * (uint32_t) ip);
                r9 = (r9 >> 23) + r6;
                hqWords[i] += f * (uint32_t) r9;
                r7 += (uint32_t) rest;
                r3 += r7 >> 23;
                r6 = sample(&s, r3);
                r3 += 1;
                ip = sample(&s, r3) - r6;
                same = !ip;
                r7 &= 0x7FFFFF;
            }
        }
    }
    h->pointer = end;
    h->left2 = r2;
    h->fw = r7;
    return true;
}

// Compressed and reversed waves. False: the wave ended, the channel is off and its fields stay.
static bool hq_special(struct channel* h, uint32_t fp, int count) {
    uint8_t* c = h->c;
    const struct mem* m = h->m;
    uint32_t wave = le32(c + C_WAV);
    uint32_t r3 = h->pointer;
    int type = c[C_TYPE];
    if (!(c[C_STATUS] & SF_SPECIAL)) {
        c[C_STATUS] |= SF_SPECIAL;
        if (type & TYPE_REV) {
            r3 = read32(m, wave + W_SIZE) + wave * 2 + 0x20 - r3;
            put32(c + C_POINTER, r3);
        }
        if (read8(m, wave + W_TYPE) | read8(m, wave + W_TYPE + 1) << 8) {
            r3 = r3 - wave - 0x10;
            put32(c + C_POINTER, r3);
        }
    }
    uint32_t f = hq_half(fp);
    uint32_t r7 = h->fw;
    uint32_t step = type & TYPE_FIX ? 0x800000u : h->divFreq * le32(c + C_FREQUENCY);
    bool compressed = read8(m, wave + W_TYPE) | read8(m, wave + W_TYPE + 1) << 8;
    bool reversed = type & TYPE_REV;
    int32_t r2 = h->left2;
    int32_t r6, ip;
    struct stream s;
    if (compressed) {
        COUNT(compressed);
        put32(c + C_XPI, 0xFF000000u);
        r3 = reversed ? r3 - 1 : r3;
        r6 = decoded(h, wave, r3);
        r3 = reversed ? r3 - 1 : r3 + 1;
        ip = decoded(h, wave, r3) - r6;
    } else {
        if (!reversed) {
            h->pointer = r3 - 1;
            return true;
        }
        COUNT(reversed);
        stream_at(&s, m, r3);
        r3 -= 1;
        r6 = sample(&s, r3);
        ip = sample(&s, r3 - 1) - r6;
    }
    for (int i = 0; i < count; i++) {
        int32_t v = ((int32_t) (r7 * (uint32_t) ip) >> 22) + r6 * 2;
        hqWords[i] += f * (uint32_t) v;
        r7 += step;
        uint32_t advance = r7 >> 23;
        if (!advance) continue;
        r2 -= (int32_t) advance;
        bool wrapped = false;
        uint32_t past = 0;
        if (r2 <= 0) {
            if (!h->loopLength) return false;
            r3 = h->loopPointer;
            past = (uint32_t) -r2;
            r2 += h->loopLength;
            wrapped = true;
        }
        if (!compressed) {
            uint32_t by = wrapped ? past : advance;
            r3 -= by;
            r6 = sample(&s, r3);
            ip = sample(&s, r3 - 1) - r6;
        } else {
            uint32_t by = wrapped ? past : advance - 1;
            if (!wrapped && !by) {
                r6 += ip;
            } else {
                r3 = reversed ? r3 - by : r3 + by;
                r6 = decoded(h, wave, r3);
            }
            r3 = reversed ? r3 - 1 : r3 + 1;
            ip = decoded(h, wave, r3) - r6;
        }
        r7 &= ~FRACTION_CLEAR;
    }
    h->pointer = compressed ? (reversed ? r3 + 2 : r3 - 1) : r3 + 1;
    h->left2 = r2;
    h->fw = r7;
    return true;
}

// A word's half to the buffer's byte: clamped to 15 bits, the top 8 of them.
static uint8_t hq_byte(int32_t v) {
    if (v >= 0x4000) v = 0x3F80;
    if (v < -0x4000) v = -0x4000;
    return (uint8_t) (v >> 7);
}

static bool hq_mix(const struct mem* m, uint32_t infoAt, int dmaCounter, uint32_t buffer, int samples) {
    uint32_t left, bufferLeft;
    uint8_t* info = ram(m, infoAt, &left);
    uint8_t* out = ram(m, buffer, &bufferLeft);
    if (!info || left < I_PCM_BUFFER + 2 * PCM_DMA_BUF_SIZE || !out || samples <= 0 || samples > HQ_MAX_SAMPLES || samples & 3 ||
        bufferLeft < (uint32_t) PCM_DMA_BUF_SIZE + (uint32_t) samples + 16) return false;

    int reverb = info[I_REVERB] >> 2;
    if (reverb) {
        COUNT(reverb);
        uint32_t both = (uint32_t) reverb | (uint32_t) reverb << 16;
        const uint8_t* p = out;
        const uint8_t* q = dmaCounter == 2 ? info + I_PCM_BUFFER : out + samples;
        for (int i = 0; i < samples; i++, p++, q++) {
            int32_t v = (int8_t) p[PCM_DMA_BUF_SIZE] + (int8_t) p[0] + (int8_t) q[PCM_DMA_BUF_SIZE] + (int8_t) q[0];
            if (v < 0) v += 4;
            hqWords[i] = both * (uint32_t) v;
        }
    } else {
        int words = (samples & 4) + (samples & 8) + (samples >> 4 ? samples >> 4 : 1) * 16;
        memset(hqWords, 0, (size_t) words * sizeof(*hqWords));
    }

    static struct channel h;
    h.m = m;
    h.divFreq = le32(info + I_DIV_FREQ);
    for (int i = 0; i < 16; i++) h.delta[i] = (int8_t) read8(m, deltaTable + (uint32_t) i);
    int chans = info[I_MAX_CHANS];
    uint8_t* c = info + I_CHANS;
    for (;;) {
        uint32_t wave = le32(c + C_WAV);
        if ((c[C_STATUS] & SF_ON) && envelope(c, m, wave, info, false)) {
            uint32_t level = (uint32_t) (info[I_MASTER_VOLUME] + 1) * c[C_ENV];
            uint32_t fp = (c[C_LEFT] * level >> 13) | (c[C_RIGHT] * level >> 13) << 16;
            if (fp) {
                h.c = c;
                h.loopLength = 0;
                h.loopPointer = 0;
                if (c[C_STATUS] & SF_LOOP) {
                    uint32_t start = read32(m, wave + W_LOOP_START);
                    h.loopPointer = wave + W_DATA + start;
                    h.loopLength = (int32_t) (read32(m, wave + W_SIZE) - start);
                }
                h.left2 = (int32_t) le32(c + C_COUNT);
                h.pointer = le32(c + C_POINTER);
                h.fw = le32(c + C_FW);
                int type = c[C_TYPE];
                bool fixed = type & TYPE_FIX;
                bool on = fixed ? hq_fixed(&h, fp, samples) : type & (TYPE_CMP | TYPE_REV) ? hq_special(&h, fp, samples) : hq_resampled(&h, fp, samples);
                if (!on) {
                    stop(&h);
                    COUNT(ended);
                } else {
                    if (!fixed) put32(c + C_FW, h.fw);
                    put32(c + C_COUNT, (uint32_t) h.left2);
                    put32(c + C_POINTER, h.pointer);
                }
            }
        }
        if (--chans <= 0) break;
        c += C_SIZE;
    }
    for (int i = 0; i < samples; i++) {
        out[PCM_DMA_BUF_SIZE + i] = hq_byte((int16_t) hqWords[i]);
        out[i] = hq_byte((int16_t) (hqWords[i] >> 16));
    }
    put32(info, ID_NUMBER);
    return true;
}

// The registers and stack SoundMain left: what the routine works from.
struct call {
    uint32_t info, buffer;
    int dmaCounter, samples, stopLine;
};

static struct call call_of(const struct mem* m) {
    uint32_t sp = cpu_reg(13);
    return (struct call) { read32(m, sp + 0x18), cpu_reg(5), (int) cpu_reg(4), (int) cpu_reg(8), (int) read32(m, sp + 0x14) };
}

// The routine's last instructions: the saved registers back, and `bx` to its caller.
static void leave(const struct mem* m) {
    uint32_t sp = cpu_reg(13);
    uint32_t saved[8];
    for (int i = 0; i < 8; i++) saved[i] = read32(m, sp + 0x1C + 4u * (uint32_t) i);
    uint32_t back = read32(m, sp + 0x1C + 0x20);
    for (int i = 0; i < 4; i++) cpu_set(8 + i, saved[i]);
    for (int i = 0; i < 4; i++) cpu_set(4 + i, saved[4 + i]);
    cpu_set(0, saved[0]);
    cpu_set(1, saved[1]);
    cpu_set(2, saved[2]);
    cpu_set(3, back);
    cpu_set(13, sp + 0x1C + 0x24);
    cpu_jump(back);
}

static void jump_thumb(uint32_t to) {
    cpu_jump(to | 1);
}

// IWRAM code: on armGBA through its write, which throws out what it compiled there.
static void put_hook(struct GBA* gba, uint32_t addr, uint16_t op) {
    if (onArmgba) {
        uint8_t v[2] = { (uint8_t) op, (uint8_t) (op >> 8) };
        pd_armgba_write(0x03000000u | (addr & 0x7FFF), v, 2);
        return;
    }
    uint8_t* p = (uint8_t*) gba->memory.iwram + (addr & 0x7FFF);
    p[0] = (uint8_t) op;
    p[1] = (uint8_t) (op >> 8);
}

#ifdef PD_MIXER_CHECK
static uint8_t copyEwram[0x40000], copyIwram[0x8000];
static uint32_t checkInfo;

static void check_exit(struct GBA* gba) {
    uint32_t left;
    struct mem real = { (uint8_t*) gba->memory.wram, (uint8_t*) gba->memory.iwram, (const uint8_t*) gba->memory.rom, gba->memory.romSize };
    struct mem copy = { copyEwram, copyIwram, real.rom, real.romSize };
    uint8_t* a = ram(&real, checkInfo, &left);
    uint8_t* b = ram(&copy, checkInfo, &left);
    pd_mixer_stats.mixes++;
    // Past the ident: the channels the game has, then both halves of the buffer.
    size_t chans = (size_t) a[I_MAX_CHANS] * C_SIZE;
    bool same = !memcmp(a + I_CHANS, b + I_CHANS, chans) && !memcmp(a + I_PCM_BUFFER, b + I_PCM_BUFFER, 2 * PCM_DMA_BUF_SIZE);
    if (!same && pd_mixer_stats.differed++ < 4) {
        for (size_t i = 0; i < chans; i++) {
            if (a[I_CHANS + i] != b[I_CHANS + i]) {
                printf("mixer: channel %d +0x%02X emulated %02X native %02X (type %02X)\n", (int) (i / C_SIZE), (int) (i % C_SIZE),
                       a[I_CHANS + i], b[I_CHANS + i], a[I_CHANS + i / C_SIZE * C_SIZE + C_TYPE]);
                break;
            }
        }
        for (int i = 0; i < 2 * PCM_DMA_BUF_SIZE; i++) {
            if (a[I_PCM_BUFFER + i] != b[I_PCM_BUFFER + i]) {
                printf("mixer: buffer +%d emulated %02X native %02X\n", i, a[I_PCM_BUFFER + i], b[I_PCM_BUFFER + i]);
                break;
            }
        }
    }
}
#endif

// The hook's BKPT on either core; false: not one of ours.
static bool on_hook(int immediate) {
    struct GBA* gba = hookedGba;
#ifdef PD_MIXER_CHECK
    if (immediate == CHECK_EXIT_HOOK) {
        check_exit(gba);
        uint32_t at = entry + (running == MIXER_HQ ? HQ_EXIT_AT : EXIT_AT);
        put_hook(gba, at, 0x9806); // its `ldr r0, [sp, 0x18]`
        jump_thumb(at);
        return true;
    }
#endif
    if (immediate != HOOK) return false;
    pd_mixer_mixes++;
    struct mem real = { (uint8_t*) gba->memory.wram, (uint8_t*) gba->memory.iwram, (const uint8_t*) gba->memory.rom, gba->memory.romSize };
    struct call k = call_of(&real);
#ifdef PD_MIXER_CHECK
    memcpy(copyEwram, real.ewram, sizeof(copyEwram));
    memcpy(copyIwram, real.iwram, sizeof(copyIwram));
    struct mem copy = { copyEwram, copyIwram, real.rom, real.romSize };
    if (running == MIXER_HQ) hq_mix(&copy, k.info, k.dmaCounter, k.buffer, k.samples);
    else mix(&copy, k.info, k.dmaCounter, k.buffer, k.samples, 0, cpu_vcount());
    checkInfo = k.info;
    put_hook(gba, entry, FIRST_OP);
    put_hook(gba, entry + (running == MIXER_HQ ? HQ_EXIT_AT : EXIT_AT), 0xBE00 | CHECK_EXIT_HOOK);
    jump_thumb(entry);
#else
    bool done = running == MIXER_HQ ? hq_mix(&real, k.info, k.dmaCounter, k.buffer, k.samples)
                                    : mix(&real, k.info, k.dmaCounter, k.buffer, k.samples, k.stopLine, cpu_vcount());
    if (done) {
        leave(&real);
    } else {
        // Not where it should be: the game's own code from here on.
        put_hook(gba, entry, FIRST_OP);
        attached = false;
        jump_thumb(entry);
    }
#endif
    return true;
}

static void hook16(struct ARMCore* cpu, int immediate) {
    mcpu = cpu;
    if (!on_hook(immediate)) forward16(cpu, immediate);
}

static bool armgba_hook(uint32_t address, unsigned immediate) {
    (void) address;
    return on_hook((int) immediate);
}

static const uint8_t* find_code(const uint8_t* rom, size_t size, const uint8_t* signature, size_t length, uint32_t crc) {
    for (size_t at = 0x1000; at + length < size; at += 2) {
        if (rom[at] == signature[0] && rom[at + 2] == signature[2] && !memcmp(rom + at, signature, 10) && doCrc32(rom + at, length) == crc) return rom + at;
    }
    return NULL;
}

bool pd_mixer_attach(struct mCore* core) {
    attached = false;
    running = MIXER_NONE;
    stock = hq = NULL;
    if (pd_armgba_is(core)) pd_armgba_set_bkpt(NULL); // a game before may have left it in
    if (core->platform(core) != mPLATFORM_GBA) return false;
    struct GBA* gba = core->board;
    const uint8_t* rom = (const uint8_t*) gba->memory.rom;
    size_t size = gba->memory.romSize;
    if (!rom || size < 0x1000) return false;
    // SoundMain's literal pool: where the game copies its mixer, whichever it is.
    entry = 0;
    for (size_t at = 0x1000; at + 16 < size; at += 4) {
        if (le32(rom + at) == ID_NUMBER && le32(rom + at + 8) == 0x04000006 && le32(rom + at + 12) == I_PCM_BUFFER) {
            uint32_t literal = le32(rom + at + 4);
            if (literal >> 24 == 3 && literal & 1) entry = literal & ~1u;
            break;
        }
    }
    if (!entry) return false;
    stock = find_code(rom, size, SIGNATURE, CODE_SIZE, CODE_CRC);
    hq = find_code(rom, size, HQ_SIGNATURE, HQ_SIZE, HQ_CRC);
    if (!stock && !hq) return false;
    onArmgba = pd_armgba_is(core);
    hookedGba = gba;
    if (onArmgba) {
        pd_armgba_set_bkpt(armgba_hook);
    } else if (gba->cpu->irqh.bkpt16 != hook16) {
        forward16 = gba->cpu->irqh.bkpt16;
        gba->cpu->irqh.bkpt16 = hook16;
    }
    attached = true;
    return true;
}

void pd_mixer_frame(struct mCore* core) {
    if (!attached) return;
    struct GBA* gba = core->board;
    const uint8_t* p = (const uint8_t*) gba->memory.iwram + (entry & 0x7FFF);
    if ((p[0] | p[1] << 8) != FIRST_OP) return;
    // All of it: which mixer the game copied in isn't the ROM's to say. (The high quality one rewrites itself past its start.)
    if (stock && !memcmp(p, stock, CODE_SIZE)) {
        running = MIXER_STOCK;
        deltaTable = le32(stock + DELTA_TABLE_AT);
    } else if (hq && !memcmp(p, hq, HQ_STABLE)) {
        running = MIXER_HQ;
        deltaTable = (uint32_t) (entry + HQ_DELTA_TABLE);
    } else {
        return;
    }
    put_hook(gba, entry, HOOK_OP);
}

void pd_mixer_release(struct mCore* core) {
    if (!attached) return;
    struct GBA* gba = core->board;
    const uint8_t* p = (const uint8_t*) gba->memory.iwram + (entry & 0x7FFF);
    if (p[0] == (uint8_t) HOOK_OP && p[1] == HOOK_OP >> 8) put_hook(gba, entry, FIRST_OP);
}
