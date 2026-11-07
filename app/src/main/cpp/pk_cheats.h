// PokeDaisy — cheats on mGBA's cheat engine (pk_cheats.c).
#pragma once

#include <stddef.h>
#include <stdint.h>

struct mCore;

void pk_cheats_clear(struct mCore* core);
int pk_cheats_apply(struct mCore* core, const char* text, size_t len);
void pk_cheats_overlay(uint32_t addr, uint8_t* dst, int len);
void pk_cheats_swap_rom(struct mCore* core);
int pk_cheats_rom_patched(void);
int pk_cheats_patch(struct mCore* core, uint32_t expectAddr, const uint8_t* expect, int expectLen,
                    uint32_t addr, const uint8_t* value, int len);
void pk_cheats_unpatch(struct mCore* core);
int pk_cheats_unpatch_range(struct mCore* core, uint32_t addr, uint32_t len);
int pk_cheats_patch_overlaps(uint32_t addr, uint32_t len);
int pk_cheats_block(struct mCore* core, uint32_t addr, const uint8_t* data, int len, uint32_t expectCrc);
void pk_cheats_unblock(struct mCore* core);
int pk_cheats_block_overlaps(uint32_t addr, uint32_t len);
int pk_cheats_unblock_range(struct mCore* core, uint32_t addr, uint32_t len);
int pk_cheats_repalette(struct mCore* core, const uint8_t* from, int n, const uint8_t* to);
char* pk_cheats_check(const char* code, int type, const char* directive);

