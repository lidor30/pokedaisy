// GPL-3.0. The Game Boy renderer's per-game addresses (pk_wide_gb.c), Polished Crystal's profile included.
#pragma once
#include <stddef.h>
#include <stdint.h>

enum {
    GB_SPRITES1,          // Gen 1: wSpriteStateData1, 16 x 16 bytes (picture id, image index $FF = hidden, screen Y / X, facing)
    GB_SPRITES2,          // Gen 1: wSpriteStateData2, map Y / X + 4 at +4 / +5, grass priority +7, VRAM slot +0xE
    GB_OVERWORLD,         // wOverworldMap(Blocks): (width + 6) x (height + 6) blocks
    GB_VIEW_PTR,          // the screen's top-left block, in it (wCurrentTileBlockMapViewPointer / wOverworldMapAnchor)
    GB_YCOORD,            // wYCoord (wXCoord follows)
    GB_YBLOCK,            // wYBlockCoord / wPlayerMetatileY (X follows): which half of that block
    GB_TILESET,           // wCurMapTileset / wMapTileset
    GB_MAP_HEIGHT,        // wCurMapHeight / wMapHeight (the width follows)
    GB_CONNECTIONS,       // wCurMapConnections / wMapConnections (bit 3 north .. 0 east); the four headers follow, north first
    GB_TILESET_BANK,      // wTilesetBank / wTilesetBlocksBank (the blockset's address follows)
    GB_BG_BLOCK,          // wMapBackgroundTile / wMapBorderBlock
    GB_IN_BATTLE,         // wIsInBattle / wBattleMode
    GB_FONT_LOADED,       // Gen 1: wFontLoaded, bit 0 while a text box (or the START menu) is up; Crystal: wStateFlags, bit 6
    GB_TOGGLE_FLAGS,      // Gen 1: wToggleableObjectFlags (a bit per global object: set = hidden)
    GB_TOGGLE_LIST,       // Gen 1: wToggleableObjectList: (sprite slot, global object) pairs, $FF-terminated
    GB_MAP_HEADER_PTRS,   // Gen 1: MapHeaderPointers (ROM, linear offset); Crystal: MapGroupPointers
    GB_MAP_HEADER_BANKS,  // Gen 1: MapHeaderBanks (ROM, linear offset)
    GB_START_ITEM,        // the START menu item picked last: Gen 1's wBattleAndStartSavedMenuItem (0 POKéDEX .. 5 OPTION,
                          // one less before the Pokédex); Crystal's wMenuSelection (STARTMENUITEM_*, 5 OPTION)
    GB_EVENT_FLAGS,       // wEventFlags (Gen 1: EVENT_GOT_POKEDEX is flag 37; Crystal: the connected maps' objects' flags)
    GB_PAL_MAP,           // Crystal: wTilesetPalettes, the tileset's palette map (in bank GB_PAL_BANK)
    GB_PAL_BANK,          // Crystal: the palette maps' ROM bank
    GB_OBJECTS,           // Crystal: wObjectStructs, 13 x 0x28 (the player first)
    GB_MAP_OBJECTS,       // Crystal: wMapObjects, 16 x 16 (the player first)
    GB_OBJECT_MASKS,      // Crystal: wObjectMasks, a byte per map object (non-zero: not shown)
    GB_USED_SPRITES,      // Crystal: wUsedSprites, (sprite, VRAM tile) pairs
    GB_VAR_SPRITES,       // Crystal: wVariableSprites
    GB_SPRITE_DATA,       // Crystal: OverworldSprites (ROM, linear): 6 bytes a sprite, type +4, palette +5
    GB_MOVE_DATA,         // Crystal: SpriteMovementData (ROM, linear): 6 bytes a movement, facing +1
    GB_VBLANK_MODE,       // Crystal: hVBlank (HRAM), VBLANK_CUTSCENE (1) through a battle's flashes and wipe
    GB_HSCX,              // Crystal: hSCX (HRAM; hSCY follows): the scroll the next frame is drawn with
    GB_TIME_OF_DAY,       // Crystal: wTimeOfDay (0 morning, 1 day, 2 night)
    GB_HOURS,             // Crystal: hHours (HRAM)
    GB_CLEAR,             // Crystal Clear: per-tile attributes and compressed connected maps
    PC_METATILES, PC_ATTRIBUTES, PC_SCRATCH, PC_MAP_ATTR_BANK, PC_LOADED_PALS,
    GB_CONFIG
};

typedef int (*pk_wide_gb_decode_fn)(const unsigned char*, size_t, size_t,
                                    unsigned char*, size_t);
