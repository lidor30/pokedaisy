// Calls the game's own functions between frames (FieldItems / FLY / PC on Android, the Linux host's FLY): the GBA
// core's CPU run from a Thumb function to its return, then put back exactly as it was. Moved out of
// pokedaisy_jni.c so every host shares it. GPL-3.0 (see NOTICE).
#ifndef PK_CALL_H
#define PK_CALL_H

#include <stdint.h>

struct mCore;

/** Calls Thumb function [fn] with r0-r3 = [args]; 1 once it returned ([r0], if given, = what it returned), 0 if it
 * couldn't run now ([pk_call_safe]) or never returned. A GBA core only. */
int pk_call(struct mCore* core, uint32_t fn, const uint32_t args[4], uint32_t* r0);
/** Whether the game's code can be called now: parked on a `b .`, or waiting for VBlank at its main loop's depth. */
int pk_call_safe(struct mCore* core);
/** The step cap of this thread's pk_calls (2,000,000; the co-op save's call raises it: pk_coop_after.c); 0 = the default. */
void pk_call_set_cap(long steps);
/** Learns the main loop's depth: once after every frame the host runs (GBA only). */
void pk_rest_note(struct mCore* core);
/** Forgets it: a new game. */
void pk_rest_reset(void);

#endif
