// PokeDaisy — the co-op story lock on retail English Emerald (pk_coop_lock.c): while it is on, a story script (a
// cutscene, a gym leader, a rival...) doesn't start by itself; it waits in a hook on ScriptContext_SetupScript until the
// app says go, so both players can start it together. Plain C over `struct mCore*`, no JNI, so native-capture/mgba_dump.c
// compiles this very file in and tests it headless (scripts/coop_headless_check.sh).
//
// The hook: ScriptContext_SetupScript's first 8 bytes (a `pk_cheats_patch`) jump into our Thumb code in the ROM's
// empty tail (0x08FFE000, `pk_cheats_block`). The code knows the story events by
// their script pointer (a table in the same block, scripts/gen_coop_story.py). Everything goes through pk_cheats, so
// the Poller's hash and RetroAchievements' still see the file's bytes, and uninstall puts each byte back.
#pragma once

#include <stdint.h>

#include "pk_coop.h"   // pk_coop_call_fn

struct mCore;

#define PK_COOP_LOCK_MAILBOX 0x0203F400u   // struct pk_coop_lock_mailbox (EWRAM the game never uses)
#define PK_COOP_LOCK_RAM_SIZE 0x100u       // the lock's whole RAM: zeroed by install and uninstall

enum { PK_COOP_LOCK_COORD = 0, PK_COOP_LOCK_OBJECT = 1, PK_COOP_LOCK_FRAME = 2 };   // = scripts/gen_coop_story.py's KINDS

// The mailbox at PK_COOP_LOCK_MAILBOX, in game RAM (our Thumb code writes the hold into it and reads `active` / `allowId`).
// `heldX` / `heldY` are the player's tile as the game keeps it when the hold began (after the step back for a COORD).
struct pk_coop_lock_mailbox {
    uint8_t active;            // 1 while the lock is on
    uint8_t heldKind;          // PK_COOP_LOCK_*
    uint16_t heldId;           // the held story event's id, 0 = none
    uint16_t allowId;          // the id the next start of is let through (once), 0 = none
    uint8_t heldMapGroup, heldMapNum;
    int16_t heldX, heldY;
    uint8_t heldFacing;        // the player's facing when the hold began (1 south, 2 north, 3 west, 4 east)
    uint8_t selectedObjectEvent;
    uint16_t lastTalked;
};

// Installs the hook: 1 = every CRC matched and the block and both patches are in; 0 = nothing changed (see
// pk_coop_lock_refused). `call` runs a game function between frames (the app's pk_call), for go and its field check.
int  pk_coop_lock_install(struct mCore* core, pk_coop_call_fn call);
// Takes the patches and the block out, zeroes 0x0203F400-0x0203F4FF.
void pk_coop_lock_uninstall(struct mCore* core);
// Why the last install refused: the first function whose code didn't match, "block overlap", "patch <addr>" or
// "not a GBA game"; "" after one went in.
const char* pk_coop_lock_refused(void);
int  pk_coop_lock_installed(void);
// Forgets the install without touching the core (the app's teardown / all-out ROM calls already took the changes out).
void pk_coop_lock_reset(void);
// Switches the lock on or off; off also clears any hold and the allowed id.
void pk_coop_lock_set_active(struct mCore* core, int on);
// After every main-core frame: clears a hold the player walked away from (map change; a COORD: more than 1 tile (Chebyshev)
// from the recorded tile; an OBJECT: the tile or the facing changed; a FRAME clears on a map change only). RAM only.
void pk_coop_lock_tick(struct mCore* core);
// The held event's id, 0 = none; *kind (if not NULL) is PK_COOP_LOCK_*.
int  pk_coop_lock_held(struct mCore* core, int* kind);
// Starts the held event `id`: 1 = started / allowed, 0 = not held (or not this id), -1 = not now (a script is running,
// the field controls are locked, or there is no pk_call-safe frame): nothing changed, ask again on a later frame.
// FRAME: allows the id and lets the game's own ON_FRAME check run it. OBJECT: puts the talked-to object back and
// sets the script up. COORD: steps the player back onto the trigger tile, then the script.
int  pk_coop_lock_go(struct mCore* core, int id);
void pk_coop_lock_cancel(struct mCore* core);   // drops the hold
// Puts the file's own bytes into `dst`, a bus read of [addr, addr + len), where the lock's head patch is in (co-op's
// retail check CRC-checks ScriptContext_SetupScript's code, which the head patch changes while the lock is on).
void pk_coop_lock_original_bytes(uint32_t addr, uint8_t* dst, int len);
