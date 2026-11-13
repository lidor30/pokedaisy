// The two DS cores behind pk_nds.h: melonDS (pk_nds.cpp, pk_ndsm_*) and DSperate (pk_nds_dsp.cpp, pk_ndsd_*), each with
// the same functions under its own prefix. pk_nds_core.cpp's pk_nds_* hand every call to the one that's up; the rest of
// the app never knows which (pk_nds_set_core picks it before pk_nds_init).
#pragma once

#include "pk_nds.h"

// X(return type, name, (parameters), (arguments)): the calls that go to the running core. pk_nds_core.cpp defines the
// public ones from it; both cores define theirs. The ones not here are the cores' own (pk_nds.h says which).
#define PK_NDS_CORE_CALLS(X) \
    X(void, set_keys, (uint32_t keys), (keys)) \
    X(void, touch, (bool down, int x, int y), (down, x, y)) \
    X(void, run_frame, (void), ()) \
    X(uint32_t*, top, (void), ()) \
    X(uint32_t*, bottom, (void), ()) \
    X(int, read_audio, (int16_t* out, int maxFrames), (out, maxFrames)) \
    X(void, set_audio_rate, (double rate), (rate)) \
    X(bool, flush_save, (void), ()) \
    X(int, finish_save, (int maxFrames), (maxFrames)) \
    X(bool, save_state, (const char* path), (path)) \
    X(bool, load_state, (const char* path), (path)) \
    X(int, state_matches_save, (const char* path), (path)) \
    X(void, read, (uint32_t addr, uint8_t* dst, int len), (addr, dst, len)) \
    X(bool, write, (uint32_t addr, const uint8_t* src, int len), (addr, src, len)) \
    X(int, field_bits, (void), ()) \
    X(int, action, (int action, const uint32_t args[4]), (action, args)) \
    X(int, action_status, (int seq), (seq)) \
    X(bool, action_results, (int seq, uint32_t* out), (seq, out)) \
    X(void, set_wild_off, (bool off), (off)) \
    X(void, set_keep_start_menu, (bool on), (on)) \
    X(void, set_cpu_boost, (bool on), (on)) \
    X(int, set_cheats, (const uint32_t* words, int nWords, const int32_t* lengths, int nCheats), (words, nWords, lengths, nCheats)) \
    X(int, bgm, (void), ()) \
    X(int, bgm_state, (void), ()) \
    X(bool, music_start, (void), ()) \
    X(int, music_read, (int16_t* out, int maxFrames), (out, maxFrames)) \
    X(void, music_fade_out, (int frames), (frames)) \
    X(void, music_stop, (void), ()) \
    X(void, wide_enable, (bool on), (on)) \
    X(void, set_draw, (bool draw, bool drawNext), (draw, drawNext)) \
    X(bool, frame_drawn, (void), ()) \
    X(bool, wide_draw, (uint32_t* out, int x, int y, int w, int h), (out, x, y, w, h)) \
    X(int, wide_scene, (void), ()) \
    X(int, fade, (void), ()) \
    X(void, wide_window, (int x, int y, int w, int h), (x, y, w, h)) \
    X(void, set_scale, (int scale), (scale)) \
    X(int, scale, (void), ()) \
    X(bool, presented, (void), ()) \
    X(bool, gpu_frame_info, (int64_t timestamp, int32_t out[6]), (timestamp, out)) \
    X(bool, rom_code, (char out[5]), (out)) \
    X(int, rom_revision, (void), ()) \
    X(uint32_t, rom_size, (void), ()) \
    X(const uint8_t*, dtcm, (void), ())

#ifdef __cplusplus
extern "C" {
#endif

#define PK_NDS_DECLARE(ret, name, params, args) ret pk_ndsm_##name params; ret pk_ndsd_##name params;
PK_NDS_CORE_CALLS(PK_NDS_DECLARE)
#undef PK_NDS_DECLARE

bool pk_ndsm_init(const char* romPath, const char* savePath, int sampleRate);
void pk_ndsm_deinit(void);
bool pk_ndsm_active(void);
bool pk_ndsd_init(const char* romPath, const char* savePath, int sampleRate);
void pk_ndsd_deinit(void);
bool pk_ndsd_active(void);

// The magic word each core's states start with (pk_nds_state_matches_save tells another core's state from its own).
#define PK_NDS_STATE_MAGIC_MELONDS 0x53444B50u   // "PKDS"
#define PK_NDS_STATE_MAGIC_DSPERATE 0x50444B50u  // "PKDP"

#ifdef __cplusplus
}
#endif
