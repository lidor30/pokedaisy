// PokeDaisy — the Game Boy games' field actions (pk_gb_field.c): RAM writes and a call from the game's own loop.
#pragma once

#include <stdbool.h>
#include <stdint.h>

struct mCore;

/** Writes [len] bytes at a Game Boy address: (bank << 16) | D000-DFFF goes to that Color WRAM bank whatever SVBK maps,
 * a plain C000-DFFF address over the bus. Anything else (ROM, VRAM, I/O, HRAM) is refused. */
bool pk_gb_write(struct mCore* core, uint32_t addr, const uint8_t* src, int len);

/** Runs the game on until its CPU reaches [at] (an instruction of the game's main loop, right after its frame wait),
 * then calls [fn] (bank 0) with A = [a], B = [b], HL = [hl] and [at] pushed as its return address: the game itself runs the call, frames
 * and all, and comes back to its loop as if it had made it. Gives up (false, nothing changed) once the CPU has waited
 * for [maxHalts] more frames without passing [at] - the loop isn't running (a menu, a text box, a battle). */
bool pk_gb_call_at(struct mCore* core, uint16_t at, uint16_t fn, uint8_t a, uint8_t b, uint16_t hl, int maxHalts);
