/* The emulator under the companion, picked at build time: mGBA's libretro core (the default) or armGBA's (-DPD_ARMGBA).
 * Either one's libretro.c is included below with its entry points renamed, so pd_libretro.c can define the real
 * retro_* functions around them and reach its static state without editing a line of upstream. What pd_libretro.c
 * needs from the emulator, besides those entry points, is the PD_BE_* set at the end. GPL-3.0, see ../NOTICE.
 *
 * With armGBA, EXPAND, the game's own functions (FLY, PC, the wild-encounter switch) and the field read run over armGBA's
 * CPU and renderer (pd_agba_view.c, pd_agba_call.c); only the Game Boy side is left out (pd_agba_stubs.c). */
#ifndef PD_BACKEND_H
#define PD_BACKEND_H

#define retro_set_environment mgba_retro_set_environment
#define retro_set_video_refresh mgba_retro_set_video_refresh
#define retro_set_input_state mgba_retro_set_input_state
#define retro_get_system_info mgba_retro_get_system_info
#define retro_get_system_av_info mgba_retro_get_system_av_info
#define retro_run mgba_retro_run
#define retro_load_game mgba_retro_load_game
#define retro_unload_game mgba_retro_unload_game
#define retro_unserialize mgba_retro_unserialize
#define retro_reset mgba_retro_reset
#define retro_load_game_special mgba_retro_load_game_special
/* (The prefix is mGBA's for both: it is the name pd_libretro.c calls them by.) */

#include "libretro.c"

#undef retro_set_environment
#undef retro_set_video_refresh
#undef retro_set_input_state
#undef retro_get_system_info
#undef retro_get_system_av_info
#undef retro_run
#undef retro_load_game
#undef retro_unload_game
#undef retro_unserialize
#undef retro_reset
#undef retro_load_game_special

#ifndef PD_ARMGBA

/* mGBA: its libretro.c keeps `core` (struct mCore *), `data`/`dataSize` (a copy of the ROM) and `outputBuffer`
 * (RGB565, 256 pixels a row) in statics. */
#define PD_BE_IS_GBA() (core->platform(core) == mPLATFORM_GBA)
#define PD_BE_IS_GB() (core->platform(core) == mPLATFORM_GB)
#define PD_BE_CRC32(out) core->checksum(core, (out), mCHECKSUM_CRC32)
#define PD_BE_ROM() ((const unsigned char *)data)
#define PD_BE_ROM_LEN() (dataSize)
#define PD_BE_CAPTURE(d, w, h, pitch) (1)
#define PD_BE_AFTER_LOAD(g) ((void)0)
#define PD_BE_BEFORE_UNLOAD() ((void)0)
#define PD_BE_INPUT_POLL() do { if (inputPollCallback) inputPollCallback(); } while (0)
#define PD_BE_AUDIO(buf, frames) audioCallback((buf), (frames))

#else /* PD_ARMGBA */

#include <stdint.h>
#include <string.h>
#include "pd_agba_view.h"

/* armGBA: a GBA only (its Game Boy side is Gambatte, which cannot be linked into this core: pd_agba_gb_stub.c). The
 * machine is `gba` (struct GBA *), the frame comes through the video callback (RGB565, any pitch), and the ROM is in
 * gba->rom. `core` is the view's core object (pd_agba_view.h) for the game-code layer. */
static struct mCore *core; /* the view's core object, set once a game is loaded (pd_agba_view.c) */
static uint16_t outputBuffer[256 * 160];
static size_t pdRomLen;
/* (A function, since pd_libretro.c has a local `gba` that would hide armGBA's.) */
static const uint8_t *pd_be_rom(void) { return gba->rom; }

static bool pd_be_capture(const void *d, unsigned w, unsigned h, size_t pitch) {
  if (!d || w > 256 || h > 160)
    return false;
  for (unsigned y = 0; y < h; y++)
    memcpy(outputBuffer + (size_t)y * 256, (const uint8_t *)d + (size_t)y * pitch, (size_t)w * sizeof *outputBuffer);
  return true;
}
/* What armGBA really loaded: a file over 32 MiB is cut to it, a Low Memory or pair-file load has no data / size. */
static size_t pd_be_rom_len(void) { return gba ? (size_t)gba->romSize : 0; }

static uint32_t pd_be_crc32(const uint8_t *p, size_t n) {
  uint32_t c = 0xffffffffu;
  for (size_t i = 0; i < n; i++) {
    c ^= p[i];
    for (int k = 0; k < 8; k++)
      c = (c >> 1) ^ (0xedb88320u & (0u - (c & 1u)));
  }
  return ~c;
}

#define PD_BE_IS_GBA() (1)
#define PD_BE_IS_GB() (0)
#define PD_BE_CRC32(out) (*(out) = pd_be_crc32(pd_be_rom(), pdRomLen))
#define PD_BE_ROM() (pd_be_rom())
#define PD_BE_ROM_LEN() (pdRomLen)
#define PD_BE_CAPTURE(d, w, h, pitch) pd_be_capture((d), (w), (h), (pitch))
#define PD_BE_AFTER_LOAD(g) (pdRomLen = pd_be_rom_len(), pd_agba_attach(), core = (struct mCore *)pd_agba_core())
#define PD_BE_BEFORE_UNLOAD() (core = NULL, pd_agba_detach())
#define PD_BE_INPUT_POLL() inputPoll()
#define PD_BE_AUDIO(buf, frames) audioBatch((buf), (frames))

#endif /* PD_ARMGBA */
#endif /* PD_BACKEND_H */
