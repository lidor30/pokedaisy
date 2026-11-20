// PokeDaisy - FOLLOWER on Pokemon Crystal (pk_gb_follow.c): the party's lead drawn a step behind the player into the
// finished frame, nothing of the game written. GPL-3.0 (see NOTICE).
#pragma once
#include <stdint.h>

struct mCore;

/** Whether the cart (by CRC32) is one the follower knows: Crystal rev 0 / 1 / Australia and the German cart. */
int pk_gb_follow_knows(uint32_t crc);
/** Whether this core runs a cart the follower knows (its first frame checks the cart by CRC32; false before). Any thread. */
int pk_gb_follow_runs(const struct mCore* core);
/** The game jumped (a state loaded, a rewind): the path starts again and the follower waits for the first step. */
void pk_gb_follow_lost(void);
/** A core was (re)made: the next frame checks its cart again (a new core can reuse the old one's address). */
void pk_gb_follow_forget(void);
/** The game that's starting (its CRC32): a cart it doesn't know draws nothing. */
void pk_gb_follow_configure(uint32_t crc);
/** Its two 16 x 16 frames, one after the other, as ARGB (512 ints), or NULL for none. */
void pk_gb_follow_set(const uint32_t* argb);
/** Whether it's wanted this frame (FOLLOWER on, the field up); it still hides itself in battles, menus and on water. */
void pk_gb_follow_show(int on);
/** After each frame of a Game Boy core: follows the path and draws into the frame (XBGR, 160 x 144). */
void pk_gb_follow_frame(struct mCore* core, uint32_t* video, int w, int h, int stride);
/** One line on where it is and why it's hidden, for logs and mgba_dump. */
void pk_gb_follow_info(char* out, int n);
