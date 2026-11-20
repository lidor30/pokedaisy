// PokeDaisy — what a JNI file other than pokedaisy_jni.c needs of the player's core (defined in pokedaisy_jni.c, one block marked
// "coop-lock (plan 5 Task 5)" there): the core lock and a game call. pkMainCore() is the core itself.
#pragma once

#include <stdint.h>

struct mCore;

struct mCore* pkMainCore(void);                  // the player's core, NULL with no game
void pk_core_rdlock(void);                       // the core lock the JNI wrappers take: shared for RAM reads / writes and game calls...
void pk_core_wrlock(void);                       // ...exclusive for install / uninstall (the ROM overlay changes)
void pk_core_unlock(void);
int pk_core_is_gba(const struct mCore* core);
// Calls the game's Thumb function `fn` with r0-r3 = args between frames (pk_call): r0 back, 0xFFFFFFFF if it couldn't run or
// never returned. A pk_coop_call_fn.
uint32_t pk_core_call(struct mCore* core, uint32_t fn, const uint32_t args[4]);
