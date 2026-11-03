// What every host (Android, 3DS, Linux) does around a core's save and clock. Moved out of pokedaisy_jni.c so a
// host can't leave them out. GPL-3.0 (see NOTICE).
#ifndef PK_HOST_H
#define PK_HOST_H

struct mCore;

/** Plenty for a Gen 3 save: 276 frames were measured on Emerald. */
#define PK_FINISH_SAVE_FRAMES 600

/** The game wrote its save within the last ~15 frames: mGBA's dirty flag, cleared (and the file synced) once the
 * writes have been quiet that long. A Gen 3 save spans several frames, sector after sector. */
int pk_save_dirty(struct mCore* core);
/** Lets a save the game is writing finish before the core stops (leaving the game, a suspend state): the loop used
 * to stop between any two frames, so leaving mid-"SAVING..." left the newer slot half written - the game said its
 * save was corrupt and loaded the one before. Runs frames with no keys, undrawn, until mGBA has synced the save, at
 * most [maxFrames]; [afterFrame] (may be NULL) runs after each, for the host to throw the audio away. Returns the
 * frames run. On the thread that runs the core. */
int pk_finish_save(struct mCore* core, int maxFrames, void (*afterFrame)(void));
/** A clock the save file brought back broken: mGBA keeps the cart RTC's state in 16 bytes after the save data and
 * restores it on reset (GBASavedataRTCRead), so a save dumped from a real cartridge whose battery had died - its
 * power-fail flag set - or written by a tool that left the 24-hour bit off made Ruby / Sapphire / Emerald say "The
 * internal battery has run dry" on every boot (sErrorStatus 0x20 / 0x10), where emulators that ignore those bytes
 * didn't. The emulated battery never runs dry: such a clock goes back to mGBA's fresh one (24-hour, the device's
 * time), and mGBA writes it into the save with the next save. 12-hour is only undone for the Pokémon games (they
 * always set 24-hour); a state loaded from before this keeps the old byte too, so call it after a reset and after a
 * state load. */
void pk_heal_rtc(struct mCore* core);

#endif
