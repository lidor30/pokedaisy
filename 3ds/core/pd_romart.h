// The games' art, found in the player's own ROM like the app's RomArt (none of
// it is bundled): each blob by its fingerprint (pd_blob_sigs in pd_map_gen.c,
// from RomArtSigsGen.kt), LZ77-decoded and CRC-checked. One pass over the ROM
// finds every blob of the game's family - the MAP's, the TRAINER CARD's and the PARTY's.
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
    PD_UB_PIC_MALE, PD_UB_PIC_MALE_PAL, PD_UB_PIC_FEMALE, PD_UB_PIC_FEMALE_PAL,
    PD_LZ_CARD_GFX, PD_LZ_CARD_FRONT, PD_LZ_CARD_BACK, PD_LZ_CARD_PAL0, PD_LZ_CARD_PAL1,
    PD_LZ_CARD_BADGES_PAL, PD_LZ_CARD_BADGES_GFX,
    PD_LZ_PIC_MALE, PD_LZ_PIC_MALE_PAL, PD_LZ_PIC_FEMALE, PD_LZ_PIC_FEMALE_PAL,
    PD_LZ_FONT_NORMAL, PD_LZ_FONT_NORMAL_WIDTHS,
    PD_RY_PIC_MALE, PD_RY_PIC_MALE_PAL, PD_RY_PIC_FEMALE, PD_RY_PIC_FEMALE_PAL,
    PD_RY_FONT_NORMAL, PD_RY_FONT_NORMAL_WIDTHS,
    PD_GZ_CARD_BADGES_PAL, PD_GZ_CARD_BADGES_GFX, PD_GZ_PIC_MALE, PD_GZ_PIC_MALE_PAL, PD_GZ_PIC_FEMALE, PD_GZ_PIC_FEMALE_PAL,
    PD_GZ_FONT_NORMAL,
    PD_SG_CARD_GFX, PD_SG_CARD_FRONT, PD_SG_CARD_BACK, PD_SG_CARD_BG,
    PD_SG_CARD_PAL0, PD_SG_CARD_PAL1, PD_SG_CARD_PAL2, PD_SG_CARD_PAL3, PD_SG_CARD_PAL4,
    PD_SG_CARD_FEMALE_PAL, PD_SG_CARD_BADGES_PAL, PD_SG_CARD_BADGES_GFX,
    PD_SG_PIC_MALE, PD_SG_PIC_MALE_PAL, PD_SG_PIC_FEMALE, PD_SG_PIC_FEMALE_PAL,
    PD_SG_FONT_NORMAL, PD_SG_FONT_NORMAL_WIDTHS,
    // The games' type tags: a 128x128 sheet of 32x12 tags, and its colours.
    PD_FR_TYPE_TAGS_GFX, PD_FR_TYPE_TAGS_PAL, PD_EM_TYPE_TAGS_GFX, PD_EM_TYPE_TAGS_PAL,
    // The party menus (RomArt's PARTIES and cfru): backdrop, slot tilemaps, Poke Ball, status icons, FONT_SMALL.
    PD_FR_PARTY_BG_GFX, PD_FR_PARTY_BG_PAL, PD_FR_PARTY_BG_MAP, PD_FR_SLOT_MAIN, PD_FR_SLOT_NO_HP, PD_FR_BALL_GFX, PD_FR_BALL_PAL,
    PD_FR_STATUS_GFX, PD_FR_STATUS_PAL, PD_FR_FONT_SMALL, PD_FR_HOLD_GFX, PD_FR_HOLD_PAL,
    PD_EM_PARTY_BG_GFX, PD_EM_PARTY_BG_PAL, PD_EM_PARTY_BG_MAP, PD_EM_SLOT_MAIN, PD_EM_SLOT_NO_HP, PD_EM_BALL_GFX, PD_EM_BALL_PAL,
    PD_EM_STATUS_GFX, PD_EM_STATUS_PAL, PD_EM_FONT_SMALL,
    PD_CFRU_PARTY_BG_GFX, PD_CFRU_PARTY_BG_PAL, PD_CFRU_PARTY_BG_MAP, PD_CFRU_SLOT_MAIN, PD_CFRU_SLOT_NO_HP, PD_CFRU_SLOT_EMPTY,
    PD_UB_STATUS_GFX, PD_UBFR_PARTY_BG_GFX, PD_UBFR_STATUS_GFX, PD_UBFR_FONT_SMALL, PD_RR_STATUS_GFX, PD_OD_STATUS_GFX, PD_AM_STATUS_GFX,
    PD_SS_PARTY_BG_PAL, PD_LGO_PARTY_BG_GFX, PD_LGO_PARTY_BG_PAL, PD_LGO_PARTY_BG_MAP, PD_LGO_STATUS_GFX,
    PD_HNS_PARTY_BG_GFX, PD_HNS_PARTY_BG_PAL, PD_HNS_PARTY_BG_MAP, PD_HNS_BALL_GFX, PD_HNS_BALL_PAL, PD_HNS_STATUS_GFX, PD_HNS_STATUS_PAL,
    PD_HNS_FONT_SMALL,
    PD_RE_PARTY_BG_GFX, PD_RE_PARTY_BG_PAL, PD_RE_PARTY_BG_MAP, PD_RE_STATUS_GFX, PD_RE_STATUS_PAL, PD_RE_FONT_SMALL,
    PD_EM_ES_PARTY_BG_GFX, PD_EM_ES_STATUS_GFX, PD_EM_ES_FONT_SMALL, PD_EM_DE_PARTY_BG_GFX, PD_EM_DE_STATUS_GFX, PD_EM_DE_FONT_SMALL,
    PD_EM_FR_PARTY_BG_GFX, PD_EM_FR_STATUS_GFX, PD_EM_FR_FONT_SMALL, PD_EM_IT_PARTY_BG_GFX, PD_EM_IT_STATUS_GFX, PD_EM_IT_FONT_SMALL,
    PD_FR_ES_PARTY_BG_GFX, PD_FR_ES_STATUS_GFX, PD_FR_ES_FONT_SMALL, PD_FR_DE_PARTY_BG_GFX, PD_FR_DE_STATUS_GFX, PD_FR_DE_FONT_SMALL,
    PD_FR_FR_PARTY_BG_GFX, PD_FR_FR_STATUS_GFX, PD_FR_FR_FONT_SMALL, PD_FR_IT_PARTY_BG_GFX, PD_FR_IT_STATUS_GFX, PD_FR_IT_FONT_SMALL,
    PD_BLOB_COUNT,
};

// Which games' ROMs a blob is looked for in; none = only the games whose party asks for it (pd_party_style).
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
    bool smol;      // expansion's smol compression instead of LZ77 (lz false)
};

extern const struct pd_blob_sig pd_blob_sigs[PD_BLOB_COUNT];

// Scans g's ROM for its family's blobs, once per ROM (about a second on a New
// 3DS: the host does it while the game loads); later calls return at once.
void pd_romart_scan(const struct pd_game* g);
// The blob's decoded bytes (pd_blob_sigs[b].size of them), or NULL when the
// ROM doesn't have it.
const uint8_t* pd_romart_blob(const struct pd_game* g, enum pd_blob b);

// One pass over g's ROM for [sigs]: out[i] = the decoded blob (malloc'd, sigs[i]->size bytes) or NULL.
void pd_romart_find(const struct pd_game* g, const struct pd_blob_sig* const* sigs, int n, uint8_t** out);


// GBA BIOS LZ77 (type 0x10); returns the decoded size or 0.
size_t pd_lz77(const uint8_t* src, size_t srcLen, uint8_t* dst, size_t dstLen);
uint32_t pd_crc32(const uint8_t* data, size_t len);

#endif
