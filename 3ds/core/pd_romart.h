// The games' art, found in the player's own ROM like the app's RomArt (none of
// it is bundled): each blob by its fingerprint (pd_blob_sigs in pd_map_gen.c,
// from RomArtSigsGen.kt), LZ77-decoded and CRC-checked. One pass over the ROM
// finds every blob of the game's family - the MAP's and the TRAINER CARD's.
#ifndef PD_ROMART_H
#define PD_ROMART_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "pd_game.h"

enum pd_blob {
    // The region map and the player's head on it.
    PD_FR_REGION_GFX, PD_FR_REGION_PAL, PD_FR_KANTO_MAP, PD_FR_SEVII123_MAP, PD_FR_SEVII45_MAP, PD_FR_SEVII67_MAP,
    PD_FR_PLAYER_RED_GFX, PD_FR_PLAYER_LEAF_GFX, PD_FR_PLAYER_PAL,
    PD_EM_REGION_GFX, PD_EM_REGION_PAL, PD_EM_REGION_MAP,
    PD_EM_PLAYER_BRENDAN_GFX, PD_EM_PLAYER_BRENDAN_PAL, PD_EM_PLAYER_MAY_GFX, PD_EM_PLAYER_MAY_PAL,
    // The TRAINER CARD (TrainerCardArt): tiles, front / back / backdrop
    // tilemaps, a palette per star count, the girl's palette, badges, the
    // star, FireRed's stickers, the trainer pics and FONT_NORMAL.
    PD_FR_CARD_GFX, PD_FR_CARD_FRONT, PD_FR_CARD_BACK, PD_FR_CARD_BG,
    PD_FR_CARD_PAL0, PD_FR_CARD_PAL1, PD_FR_CARD_PAL2, PD_FR_CARD_PAL3, PD_FR_CARD_PAL4,
    PD_FR_CARD_FEMALE_PAL, PD_FR_CARD_BADGES_PAL, PD_FR_CARD_BADGES_GFX,
    PD_FR_CARD_STICKERS_GFX, PD_FR_CARD_STICKER_PAL1, PD_FR_CARD_STICKER_PAL2, PD_FR_CARD_STICKER_PAL3,
    PD_FR_CARD_STICKER_PAL4,
    PD_FR_PIC_RED, PD_FR_PIC_RED_PAL, PD_FR_PIC_LEAF, PD_FR_PIC_LEAF_PAL,
    PD_FR_FONT_NORMAL, PD_FR_FONT_NORMAL_WIDTHS,
    PD_EM_CARD_GFX, PD_EM_CARD_FRONT, PD_EM_CARD_BACK, PD_EM_CARD_BG,
    PD_EM_CARD_PAL0, PD_EM_CARD_PAL1, PD_EM_CARD_PAL2, PD_EM_CARD_PAL3, PD_EM_CARD_PAL4,
    PD_EM_CARD_FEMALE_PAL, PD_EM_CARD_BADGES_PAL, PD_EM_CARD_BADGES_GFX,
    PD_EM_PIC_BRENDAN, PD_EM_PIC_BRENDAN_PAL, PD_EM_PIC_MAY, PD_EM_PIC_MAY_PAL,
    PD_EM_FONT_NORMAL, PD_EM_FONT_NORMAL_WIDTHS,
    PD_CARD_STAR_PAL,
    PD_LG_CARD_PAL5,
    PD_BLOB_COUNT,
};

// Which games' ROMs a blob is looked for in.
#define PD_BLOB_FIRERED 1
#define PD_BLOB_EMERALD 2

// RomBlob: size and CRC32 of the decoded bytes, and a 16-byte window at
// headOff into the raw bytes whose hash is head (pre = its 16-bit prefilter).
struct pd_blob_sig {
    bool lz;
    uint32_t size, crc;
    uint32_t headOff;
    uint64_t head;
    uint16_t pre;
    uint8_t family; // PD_BLOB_*
};

extern const struct pd_blob_sig pd_blob_sigs[PD_BLOB_COUNT];

// Scans g's ROM for its family's blobs, once per ROM (about a second on a New
// 3DS: the host does it while the game loads); later calls return at once.
void pd_romart_scan(const struct pd_game* g);
// The blob's decoded bytes (pd_blob_sigs[b].size of them), or NULL when the
// ROM doesn't have it.
const uint8_t* pd_romart_blob(const struct pd_game* g, enum pd_blob b);

// GBA BIOS LZ77 (type 0x10); returns the decoded size or 0.
size_t pd_lz77(const uint8_t* src, size_t srcLen, uint8_t* dst, size_t dstLen);
uint32_t pd_crc32(const uint8_t* data, size_t len);

#endif
