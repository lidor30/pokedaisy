// BananaDS (DS CORE's BANANADS) on an ABI it isn't built for (anything but AArch64: Gengis Engine's kernels are
// AArch64 code): a core that never starts, so DS CORE BANANADS falls back to melonDS's error path ("can't start").
#include "pk_nds_cores.h"

#include <cstring>

extern "C" {

bool pk_ndsb_init(const char*, const char*, int) { return false; }
void pk_ndsb_deinit(void) {}
bool pk_ndsb_active(void) { return false; }
void pk_ndsb_set_wild_rates(const int32_t*, int) {}

void pk_ndsb_set_keys(uint32_t) {}
void pk_ndsb_touch(bool, int, int) {}
void pk_ndsb_run_frame(void) {}
uint32_t* pk_ndsb_top(void) { return nullptr; }
uint32_t* pk_ndsb_bottom(void) { return nullptr; }
int pk_ndsb_read_audio(int16_t*, int) { return 0; }
void pk_ndsb_set_audio_rate(double) {}
bool pk_ndsb_flush_save(void) { return false; }
int pk_ndsb_finish_save(int) { return 0; }
bool pk_ndsb_save_state(const char*) { return false; }
bool pk_ndsb_load_state(const char*) { return false; }
int pk_ndsb_state_matches_save(const char*) { return -1; }
void pk_ndsb_read(uint32_t, uint8_t* dst, int len) { memset(dst, 0, (size_t) len); }
bool pk_ndsb_write(uint32_t, const uint8_t*, int) { return false; }
int pk_ndsb_field_bits(void) { return 0; }
int pk_ndsb_action(int, const uint32_t[4]) { return 0; }
int pk_ndsb_action_status(int) { return -1; }
bool pk_ndsb_action_results(int, uint32_t*) { return false; }
void pk_ndsb_set_wild_off(bool) {}
void pk_ndsb_set_keep_start_menu(bool) {}
void pk_ndsb_set_cpu_boost(bool) {}
int pk_ndsb_set_cheats(const uint32_t*, int, const int32_t*, int) { return -1; }
int pk_ndsb_bgm(void) { return -1; }
int pk_ndsb_bgm_state(void) { return 0x7F00; }
bool pk_ndsb_music_start(void) { return false; }
int pk_ndsb_music_read(int16_t*, int) { return 0; }
void pk_ndsb_music_fade_out(int) {}
void pk_ndsb_music_stop(void) {}
void pk_ndsb_wide_enable(bool) {}
void pk_ndsb_set_draw(bool, bool) {}
bool pk_ndsb_frame_drawn(void) { return false; }
bool pk_ndsb_wide_draw(uint32_t*, int, int, int, int) { return false; }
int pk_ndsb_wide_scene(void) { return 4; }
int pk_ndsb_fade(void) { return 0; }
void pk_ndsb_wide_window(int, int, int, int) {}
void pk_ndsb_set_scale(int) {}
int pk_ndsb_scale(void) { return 1; }
bool pk_ndsb_presented(void) { return false; }
bool pk_ndsb_gpu_frame_info(int64_t, int32_t[6]) { return false; }
bool pk_ndsb_rom_code(char[5]) { return false; }
int pk_ndsb_rom_revision(void) { return -1; }
uint32_t pk_ndsb_rom_size(void) { return 0; }
const uint8_t* pk_ndsb_dtcm(void) { return nullptr; }

} // extern "C"
