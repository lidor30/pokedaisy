"""Generate v3.2.3 normal expansion addresses from official symbols, no ROM data.
Usage: python3 scripts/gen_polished_wide.py release.sym  (writes app/src/main/cpp/pk_wide_gb_polished_tables.h)
Official release: https://github.com/Rangi42/polishedcrystal/releases/tag/v3.2.3
ROM SHA1: 6930b48af5844d373e3c9130f26d6dd1084cf4ed; CRC32 f98367e4.
"""
from pathlib import Path
import re,sys,hashlib
raw=Path(sys.argv[1]).read_bytes()
assert hashlib.sha256(raw).hexdigest()=="be1ca87ad487ade20701ed71fcae4e99bf16b908af727b84575c862b73ea2955"
symbols={n:(int(b,16)<<16)|int(a,16) for b,a,n in re.findall(r"^([0-9a-fA-F]+):([0-9a-fA-F]+) (\S+)$",raw.decode(),re.M)}
fields=dict(GB_OVERWORLD='wOverworldMapBlocks',GB_VIEW_PTR='wOverworldMapAnchor',GB_YCOORD='wYCoord',GB_YBLOCK='wMetatileStandingY',GB_TILESET='wMapTileset',GB_MAP_HEIGHT='wMapHeight',GB_CONNECTIONS='wMapConnections',GB_TILESET_BANK='wTilesetDataBank',GB_BG_BLOCK='wMapBorderBlock',GB_IN_BATTLE='wBattleMode',GB_FONT_LOADED='wStateFlags',GB_MAP_HEADER_PTRS='MapGroupPointers',GB_START_ITEM='wMenuSelection',GB_EVENT_FLAGS='wEventFlags',GB_OBJECTS='wObjectStructs',GB_OBJECT_MASKS='wObjectMasks',GB_VAR_SPRITES='wVariableSprites',GB_SPRITE_DATA='SpriteHeaders',GB_MOVE_DATA='SpriteMovementData',GB_VBLANK_MODE='hVBlank',GB_HSCX='hSCX',GB_TIME_OF_DAY='wTimeOfDay',GB_HOURS='hHours',PC_METATILES='wDecompressedMetatiles',PC_ATTRIBUTES='wDecompressedAttributes',PC_SCRATCH='wDecompressScratch')
rows=[]
fields.update(GB_MAP_OBJECTS='wMapObjects', PC_LOADED_PALS='wLoadedObjPal0')
for key,name in fields.items():
 a=symbols[name]
 if key in {'GB_MAP_HEADER_PTRS','GB_SPRITE_DATA','GB_MOVE_DATA'}:a=(a>>16)*0x4000+(a&0x3fff)
 rows.append(f'    [{key}] = 0x{a:x},')
rows.append('    [PC_MAP_ATTR_BANK] = '+hex(symbols['NewBarkTown_MapAttributes']>>16)+',')
assert symbols['wOverworldMapBlocksEnd']-symbols['wOverworldMapBlocks']==1326
assert symbols['wObjectStructsEnd']-symbols['wObjectStructs']==13*34
assert symbols['wMapObjectsEnd']-symbols['wMapObjects']==21*14
out='/* Generated from official v3.2.3 symbols (SHA256 '+hashlib.sha256(raw).hexdigest()+'). GPL-3.0. No ROM bytes. */\n#define PC_WIDE_CRC 0xf98367e4u\n#define PC_WIDE_SHA1 "6930b48af5844d373e3c9130f26d6dd1084cf4ed"\nstatic const uint32_t pcWideConfig[GB_CONFIG] = {\n'+'\n'.join(rows)+'\n};\n'
(Path(__file__).resolve().parents[1]/'app/src/main/cpp/pk_wide_gb_polished_tables.h').write_text(out)
