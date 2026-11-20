#ifndef PK_LINK_API_H
#define PK_LINK_API_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PK_LINK_MAX 4

/** One caller thread owns the link session, separately from pk_api's single game. */
bool pk_link_open_with_rtc(int n, const char* const* roms, const char* const* saves);
void pk_link_close(int maxFrames);
int pk_link_count(void);
bool pk_link_run_frame(void);
void pk_link_set_keys(int i, uint32_t keys);
unsigned pk_link_video_w(int i);
unsigned pk_link_video_h(int i);
/** Copy the last frame as packed RGBA, with opaque alpha. Returns false if it does not fit. */
bool pk_link_copy_video(int i, void* out, size_t capacity);
/** Copy and consume at most maxFrames interleaved stereo frames. */
int pk_link_read_audio(int i, int16_t* out, int maxFrames);
void pk_link_set_audio_rate(double rate);
bool pk_link_saving(void);
void pk_link_status(char* out, size_t size);

#ifdef __cplusplus
}
#endif
#endif
