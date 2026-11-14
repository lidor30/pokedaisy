// PokeDaisy — the running game's state behind pk_api.h (pk_session.c), for the parts of pokedaisy_jni.c that haven't
// moved under pk_api.h yet: they still reach into it as `g`. Each area that moves takes its fields with it; when the
// last has, this header goes and the state is pk_session.c's own. Only pk_session.c and pokedaisy_jni.c include it.
// GPL-3.0 (see NOTICE).
#ifndef PK_SESSION_H
#define PK_SESSION_H

#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>

#include <mgba/core/core.h>
#include <mgba/core/rewind.h>

#include "pk_api.h"
#include "pk_gb_audio.h"

#define PK_SAMPLE_RATE PK_API_SAMPLE_RATE
#define PK_AUDIO_SCRATCH_SAMPLES 8192   // per-channel; * 2 shorts for interleave

struct pk_session {
    struct mCore* core;
    color_t* video;
    unsigned vw, vh;
    struct VFile* saveVf;
    bool saveReadOnly;                  // nothing we load can reach the file
    // A GBA game's ROM file, mapped read-only for the companion's ROM reads (pk_read_rom_file): mGBA's own mapping
    // can be swapped for a copy mid-game. Set and dropped under pk_coreLock's write lock, with the core.
    struct VFile* romFileVf;
    const uint8_t* romFile;
    size_t romFileSize;
    int16_t audio[PK_AUDIO_SCRATCH_SAMPLES * 2];
    int audioAvail;                     // interleaved shorts ready in audio[]
    struct pk_gb_audio gbAudio;         // a Game Boy game's sound, resampled here (pk_gb_audio.c); zeroed for a GBA game
    // REWIND (pk_rewind.c): a state every PK_REWIND_INTERVAL frames while rewindEntries > 0;
    // while rewinding, each frame goes back one of them instead.
    struct mCoreRewindContext rewind;
    int rewindEntries;
    int rewindTick;
    bool rewinding;
    // WIDE (pk_wide.c): the window around the frame with the rebuilt scene, wideW x wideH (0 = not
    // drawn: the screen shows `video`). The window comes from WideCamera (ZOOM, the look-around) per frame.
    uint32_t* wide;
    int wideW, wideH;
    uint32_t wideCrc;                   // the game WIDE draws (its ROM's CRC32; 0 = off)
    volatile int viewX, viewY, viewW, viewH;
    volatile bool wideSkipNext;         // the next frame won't be shown: only note it (pk_wide_skip); false = draw
    bool wideSkipped;                   // the last frame was only noted, not drawn: wide / wideW still hold the one before
};

/** The player's game. */
extern struct pk_session pk_session;
#define g pk_session

/** Guards g.core's lifetime, not its state: the emu thread owns frames, saves and teardown, but the companion's ROM
 * readers (Pokédex, GUIDE, icons) read from IO threads, and one in flight while the game closed read a core
 * pk_api_deinit had just freed. Readers share it; init / teardown take it alone. */
extern pthread_rwlock_t pk_coreLock;

int pk_is_gba(const struct mCore* core);
/** A Game Boy core's options: no Super Game Boy border, half volume. Nothing on a GBA core. */
void pk_gb_config(struct mCore* core);
/** [len] bytes over [core]'s bus at [addr], word reads where aligned. */
void pk_read_range(struct mCore* core, uint32_t addr, uint8_t* dst, int len);
/** REWIND off, its buffer freed. */
void pkRewindOff(void);

#endif
