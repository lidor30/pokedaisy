#!/usr/bin/env python3
"""WIDE (pk_wide.c) for every retail release the user owns, and the ROM hacks in HACKS: maps the English references' addresses
into each ROM and writes app/src/main/cpp/pk_wide_games.h, a table keyed by the ROM's CRC32.

    scripts/gen_wide_tables.py <roms dir> [<more roms dirs>...]

Every retail .gba under the dirs (by SHA-1: the app's GameTitles / RetailPortsGen list - hacks share the
codes but aren't taken) whose game code is FireRed / LeafGreen (BPR / BPG), Emerald (BPE) or Ruby / Sapphire (AXV / AXP) gets
the English reference's addresses (FireRed rev 1, Emerald, Ruby rev 1: checked frame by frame headless) mapped
through the literal pools of the code that loads them (port_retail.py's map_literal: the word at the
same place in the other ROM's code). A ROM with an address no pool maps is left out and reported.
build/wide/wide.json keeps every address with its votes, for scripts/verify_wide.py, which boots
each ROM headlessly and checks the rebuild against the real frames.
"""
import hashlib
import json
import struct
import os
import re
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
sys.path.insert(0, HERE)
from port_retail import Rom, map_literal, map_code  # noqa: E402

OUT_H = os.path.join(ROOT, "app", "src", "main", "cpp", "pk_wide_games.h")
OUT_JSON = os.path.join(ROOT, "build", "wide", "wide.json")

# pk_wide.h's PK_WIDE_* address slots, in order.
SLOTS = ["VMAP", "MAP_HEADER", "MAP_GROUPS", "SAVE_BLOCK1_PTR", "CAMERA_OFFSET", "OBJECT_EVENTS", "SPRITES",
         "COORD_OFFSET", "COORD_OFFSET_Y", "SPRITE_ORDER", "GMAIN", "CONTROLS_LOCKED", "OBJ_GFX_INFO", "OBJ_PALETTES", "OBJ_FACING",
         "SPRITE_PAL_TAGS", "ELEVATION_PRIO", "MAP_GROUPS_XOR", "NORMAL_BG3", "METATILE_SIZE", "METATILE_MASK", "PRIMARY_OVERRIDE", "PAN_OFF",
         "OBJ_GFX_TABLES", "OBJ_GFX_TABLE_MAX", "OBJ_GFX_VAR_FROM", "OBJ_GFX_FF_VAR", "SB1_OFFSETS", "SB1_POS", "LAYOUT_VERSIONED",
         "OBJ_GFX_COUNT", "TEMPLATE_KIND_AT", "SB1_FLAGS_AT", "TRANSITION_PTR", "QUEST_LOG", "EXPANDED_FLAGS"]

# The references: what was mapped onto retail from the decomp ELFs and checked headless (pk_wide.c's
# history), plus ScriptContext_SetupScript for the checks' scripted warps and battles.
REFERENCES = {
    "FR": dict(code="BPRE", rev=1, script=0x08069AF8, a=dict(
        VMAP=0x03005040, MAP_HEADER=0x02036DFC, MAP_GROUPS=0x08352718, SAVE_BLOCK1_PTR=0x03005008,
        CAMERA_OFFSET=0x03000E90, OBJECT_EVENTS=0x02036E38, SPRITES=0x0202063C, COORD_OFFSET=0x02021BC8,
        COORD_OFFSET_Y=0x02021BCA, SPRITE_ORDER=0x02021800, GMAIN=0x030030F0,
        CONTROLS_LOCKED=0x03000F9C,   # sScriptContext2Enabled, from ScriptContext2_IsEnabled's literal
        # The far-off objects' tables (the decomp ELF's statics, mapped from its patched build through the pools).
        OBJ_GFX_INFO=0x0839FE20, OBJ_PALETTES=0x083A51C8, OBJ_FACING=0x0839FDCD, SPRITE_PAL_TAGS=0x03000DE8,
        ELEVATION_PRIO=0x083A70EC,
        # battle_transition.c's sTransitionData (the decomp ELF's static, checked in retail rev 1 headless: set from
        # BattleTransition_StartOnField to the transition's end): the battle is coming before anything shows.
        TRANSITION_PTR=0x02039A2C,
        # quest_log.c's gQuestLogState: 2 / 3 while "Previously on your quest" replays the field after Continue (the
        # camera stays at 1x through it: its scripted text boxes zoomed in and out).
        QUEST_LOG=0x0203ADFA)),
    "EM": dict(code="BPEE", rev=0, script=0x08098EF8, a=dict(
        VMAP=0x03005DC0, MAP_HEADER=0x02037318, MAP_GROUPS=0x08486578, SAVE_BLOCK1_PTR=0x03005D8C,
        CAMERA_OFFSET=0x03000E20, OBJECT_EVENTS=0x02037350, SPRITES=0x02020630, COORD_OFFSET=0x02021BBC,
        COORD_OFFSET_Y=0x02021BBE, SPRITE_ORDER=0x020217F4, GMAIN=0x030022C0,
        # sLockFieldControls and the far-off objects' tables: the decomp ELF's (a patched build: its ROM data mapped onto
        # retail through the literal pools - the code and RAM are retail's).
        CONTROLS_LOCKED=0x03000F2C, OBJ_GFX_INFO=0x08505620, OBJ_PALETTES=0x0850BBC8, OBJ_FACING=0x085055CD,
        SPRITE_PAL_TAGS=0x03000CF0, ELEVATION_PRIO=0x0850E634)),
    # pret/pokeruby's ruby_rev1 build is byte for byte retail Ruby rev 1: its ELF's symbols, as they are.
    # SaveBlock1 is static there (no pointer), gSpriteCoordOffsetY isn't next to X.
    "RS": dict(code="AXVE", rev=1, script=0x080655D8, a=dict(
        VMAP=0x03004870, MAP_HEADER=0x0202E828, MAP_GROUPS=0x083085A0, SAVE_BLOCK1_PTR=0x02025734,
        CAMERA_OFFSET=0x03000590, OBJECT_EVENTS=0x030048A0, SPRITES=0x02020004, COORD_OFFSET=0x030024D0,
        COORD_OFFSET_Y=0x030027E0, SPRITE_ORDER=0x03002410, GMAIN=0x03001770,
        # The ELF's sElevationToPriority has no name in pokeruby: InitObjectPriorityByZCoord's literal.
        CONTROLS_LOCKED=0x030006A4, OBJ_GFX_INFO=0x0836DC70, OBJ_PALETTES=0x08373794, OBJ_FACING=0x0836DC21,
        SPRITE_PAL_TAGS=0x03000300, ELEVATION_PRIO=0x08376078)),
}
# Addresses a ROM may go without (0 = unknown: pk_wide.c then does without what they give).
OPTIONAL = {"CONTROLS_LOCKED", "OBJ_GFX_INFO", "OBJ_PALETTES", "OBJ_FACING", "SPRITE_PAL_TAGS", "ELEVATION_PRIO",
            "TRANSITION_PTR", "QUEST_LOG"}

# The slots that aren't addresses, as retail has them.
CONSTANTS = dict(MAP_GROUPS_XOR=0, NORMAL_BG3=0x3014, METATILE_SIZE=16, METATILE_MASK=0x3FF, PRIMARY_OVERRIDE=0, PAN_OFF=8,
                 OBJ_GFX_TABLES=0, OBJ_GFX_TABLE_MAX=0, OBJ_GFX_VAR_FROM=240, OBJ_GFX_FF_VAR=0, SB1_OFFSETS=0, SB1_POS=0, LAYOUT_VERSIONED=0,
                 OBJ_GFX_COUNT=0, TEMPLATE_KIND_AT=0, SB1_FLAGS_AT=0, EXPANDED_FLAGS=0)
# CFRU's flags 0x900-0x18FF (its GetFlagPointer, hooked over FireRed's: the same in Unbound, Radical Red, Amethyst and
# Sword and Shield). The map objects they hide were drawn past the edges from SaveBlock1's bytes after the flags.
CFRU_FLAGS = dict(EXPANDED_FLAGS=0x0203B174)
# CFRU's graphics lookup (its GetEventObjectGraphicsInfo, in place of FireRed's): Unbound looks in tables 1-2 (a table of
# tables at 0x08A69530), takes table 0's 240+ as they are and table 0xFF's from vars 0x4080+; Radical Red in every
# table (0x091468CC), table 0's 240+ from FireRed's vars, table 0xFF's from vars past SaveBlock1's (not drawn).
CFRU_UNBOUND = dict(OBJ_GFX_INFO=0x088110E0, OBJ_GFX_TABLES=0x08A69530, OBJ_GFX_TABLE_MAX=2, OBJ_GFX_VAR_FROM=256,
                    OBJ_GFX_FF_VAR=0x4080, **CFRU_FLAGS)

# ROM hacks, by SHA-1. One with `pending` (why) is mapped and checked, but its row is under PK_WIDE_PENDING (only
# verify_wide.py's build defines it): its middle isn't exact yet, so the app doesn't offer WIDE for it. A CFRU hack keeps FireRed rev 0's RAM and most of its code, so its addresses are mapped
# through the literal pools like a retail port's (`mapped`), then `a` overrides what the hack moved; a hack on
# its own build (pokeemerald-expansion, gcc) gives every address in `a`. `save` is its scripts/host_roms.conf name.
# The far-off objects in the expansion builds (no symbols): the graphics table is where a live object's id leads to
# the record its sprite's images come from, its 16 var ids the first 16 non-records past 240, the palettes the
# {data, tag} run holding tag 0x1103, sSpritePaletteTags the RAM u16[16] whose slots hold the live sprites' tags,
# the controls lock the byte UnlockPlayerFieldControls / ArePlayerFieldControlsLocked share; the save's templates
# where the map header's first one is copied (flags 0x600 on), vars from the app's GuideTables.
SG_OBJECTS = dict(SPRITE_PAL_TAGS=0x030020DC, CONTROLS_LOCKED=0x03001F74, OBJ_GFX_VAR_FROM=0x14A,
                  SB1_OFFSETS=0x1298 | 0x1B42 << 16)
# CFRU-style graphics lookups that only fill table 0 (the template's byte 3 still says 0xFF for a var's id).
def cfru(info, tables, ff_var=0):
    return dict(OBJ_GFX_INFO=info, OBJ_GFX_TABLES=tables, OBJ_GFX_TABLE_MAX=0xFE, OBJ_GFX_FF_VAR=ff_var)


HACKS = [
    dict(name="Unbound v2.1.1.1", sha1="b4776b82a4c7915d0fadeaa27e013523f99dfd94", family="FR", mapped=True,
         save="unbound", a=dict(MAP_GROUPS=0x08B70498, MAP_GROUPS_XOR=0x00B749DE, NORMAL_BG3=0, **CFRU_UNBOUND)),
    dict(name="Unbound v2.1.1.1 FR", sha1="0ce2a880aa097f1dce4e1db8ee513d0e82d15859", family="FR", mapped=True,
         save="unbound_fr", a=dict(MAP_GROUPS=0x08B70498, MAP_GROUPS_XOR=0x00B749DE, NORMAL_BG3=0, **CFRU_UNBOUND)),
    dict(name="Radical Red v4.1", sha1="964f951a0fdaf209e4ea1344883ef0d557bb3a80", family="FR", mapped=True,
         save="radical_red", a=dict(MAP_GROUPS=0x083526A8, OBJ_GFX_INFO=0x08EB1000, OBJ_GFX_TABLES=0x091468CC,
                                    OBJ_GFX_TABLE_MAX=0xFE, **CFRU_FLAGS)),
    # Binary FireRed hacks: FireRed rev 0's RAM and code, mapped through the literal pools; gMapGroups is the app's
    # GuideTables value (one pool vote), the graphics lookup as each hack's GetObjectEventGraphicsInfo has it.
    dict(name="Odyssey v4.1.1", sha1="8745ddbdbfadf6abaf66de4e9055923b62eb4668", family="FR", mapped=True,
         save="odyssey", a=dict(MAP_GROUPS=0x083526A8, OBJ_GFX_INFO=0x09226280)),
    dict(name="Gaia v3.2", sha1="d5b1e77975fcda831e0e9a7b527906bf3f40ecd0", family="FR", mapped=True,
         save="gaia", a=dict(MAP_GROUPS=0x083528DC, OBJ_GFX_INFO=0x089D6CD0)),
    dict(name="Amethyst v1.3.0", sha1="00e70c0384a5f1698588034201fd5b849d3542e2", family="FR", mapped=True,
         save="amethyst", a=dict(MAP_GROUPS=0x083526A8, **cfru(0x0839FDB0, 0x089B03D8), **CFRU_FLAGS)),
    dict(name="Amethyst v1.4.1", sha1="91291aade04b4b111cd03ae7b6e2ff460e1edd8a", family="FR", mapped=True,
         save="amethyst_v141", a=dict(MAP_GROUPS=0x083526A8, **cfru(0x0839FDB0, 0x089F2598), **CFRU_FLAGS)),
    dict(name="Orange Islands", sha1="8bac897de515f88e6ce92d0712ce2c018a394b37", family="FR", mapped=True,
         pending="the top tile row differs once the walk turns north (half the walk frames)",
         save="orange_islands", a=dict(MAP_GROUPS=0x083526A8, **cfru(0x08801C80, 0x081A2000, 0x4080))),
    # Beta 5.7: the same RAM and tables as the build above (every address shared, checked headless on the same save).
    dict(name="Orange Islands Beta 5.7", sha1="d2e3800e69e44c9649c3d9a15dc1cd12c1d90501", family="FR", mapped=True,
         pending="as Orange Islands",
         save="orange_islands", a=dict(MAP_GROUPS=0x083526A8, **cfru(0x08801C80, 0x081A2000, 0x4080))),
    dict(name="Celia's Stupid Romhack v1.1.4", sha1="3cbd5a2e72ce60cfae1e0c28d83e01f855c51537", family="FR",
         mapped=True, save="celia", a=dict(MAP_GROUPS=0x089DCD7C,
         # FireRed's ScriptContext2_Enable / _Disable / _IsEnabled, moved with the rest (0x080765C8): the field reads'.
         CONTROLS_LOCKED=0x03000FA4)),
    *[dict(name=f"Sword and Shield Ultimate Plus{v}", sha1=sha, family="FR", mapped=True, save=save,
           a=dict(MAP_GROUPS=0x087B1010, **cfru(0x08EB1000, 0x094BBFCC), **CFRU_FLAGS))
      for v, sha, save in [("", "afe4c8e0f9c7186860fcea5f25d8318f5d7929b9", "sword_shield_up"),
                           (" (casual)", "daa2f89934d2c0447d5271dabeba86bfba04e1fd", "sword_shield_up_casual"),
                           (" (performance)", "4858cc3d7b335bb5eed553861d7a4a17bad9ce2c", "sword_shield_up_performance"),
                           (" (casual, performance)", "932323d1fa3732d8673d1319fa86ed4df41087b9",
                            "sword_shield_up_casual_performance")]],
    # GS Chronicles: CFRU's graphics lookup (FireRed's GetObjectEventGraphicsInfo hooked to 0x09C84B31, whose pool
    # names table 0 and the table of tables) and its flags; gMapGroups is FireRed's, rewritten in place.
    # Its primary tilesets hold 512 metatiles, not FireRed's 640 (each tileset's metatiles run 0x2000 bytes to its
    # attributes). It boots through its own splash (boot_file) and uses 0x0203FF00, so the check's battle script goes
    # elsewhere.
    dict(name="GS Chronicles v2.7.6", sha1="25f8840ecb58e584edf18ffde35f845e94f3f17c", family="FR", mapped=True,
         save="gs_chronicles", boot_file="gs_chronicles.txt", script_ram=0x0203F400,
         a=dict(MAP_GROUPS=0x083526A8, PRIMARY_OVERRIDE=512, **cfru(0x081C68F4, 0x09D0C80C), **CFRU_FLAGS)),
    dict(name="Ultra Violet v1.22", sha1="3b6c51d8fb6b513bdd01b7bda3ed8965bc985d58", family="FR", mapped=True,
         save="ultra_violet", a=dict(MAP_GROUPS=0x0872AF04, OBJ_GFX_INFO=0x08741100)),
    # FireRed Rocket Edition and Ash Gray: FireRed rev 0 edited in place, booted with retail FireRed's save (their own
    # quest log recap needs firered_hack.txt's B presses). Both moved the object palettes (mapped from the pools).
    dict(name="FireRed Rocket Edition", sha1="033d05374ae34eb84fae62c693ca8f3da23dd1bd", family="FR", mapped=True,
         save="rocket_edition", boot_file="firered_hack.txt", a=dict(MAP_GROUPS=0x083526A8, OBJ_GFX_INFO=0x0839FDB0)),
    *[dict(name=f"Ash Gray {v}", sha1=sha, family="FR", mapped=True, save=save, boot_file="firered_hack.txt",
           a=dict(MAP_GROUPS=0x0873E2F4, OBJ_GFX_INFO=0x0839FDB0))
      for v, sha, save in [("v4.5.3", "1ee1557db1e4c2e0e94c6306efcc40506bc40cc9", "ash_gray"),
                           ("Fork Bug Fix v1.0", "92ed82145c013069472153391c7aab1cfbf37b75", "ash_gray_fork")]],
    # Pokémon Ghost Grey (32 MB): FireRed rev 0 edited in place with rev 0's map groups and object tables, booted with
    # its own 1.0.3 save (its boot script: the splash, CONTINUE, the quest log recap's B presses).
    *[dict(name=f"Ghost Grey {v}", sha1=sha, family="FR", mapped=True, save=save, boot_file="ghost_grey.txt",
           a=dict(MAP_GROUPS=0x083526A8, OBJ_GFX_INFO=0x0839FDB0))
      for v, sha, save in [("v1.0.1", "20dfaea1d5317cf95fe0b493208edce7d50b865f", "ghost_grey_v101"),
                           ("v1.0.2", "74337272566a74625d6dedefe47d2d153eb48ec1", "ghost_grey_v102"),
                           ("v1.0.4", "0dafc0116b74406a2df491e26ccb40dd362fb0b7", "ghost_grey")]],
    # Binary Emerald hacks: Emerald's code, mapped the same way.
    dict(name="Glazed 9.2.0", sha1="e10105d8544469c6a11ca2cf510289981df3c3b0", family="EM", mapped=True,
         save="glazed", a=dict(MAP_GROUPS=0x08486578)),
    # Emerald Kaizo: retail Emerald edited in place (95% byte for byte); no save of its own - retail's boots in it.
    dict(name="Emerald Kaizo v2.0", sha1="a7c4e34fafb53f2d5283eb6d43dad285c0dd40a8", family="EM", mapped=True,
         save="emerald_kaizo", a=dict(MAP_GROUPS=0x08486578)),
    # Emerald Legacy v1.1.4: pokeemerald rebuilt from its own source (agbcc, = the release byte for byte): every address
    # is its ELF's symbol. Emerald's map and sprites with ghoulslash's overworld expansion - ObjectEvent.spriteId at
    # +0x23, a u16 graphicsId at +2 of a template (its kind at +1, as Rogue's), 300 graphics records then the
    # VAR_OBJ_GFX_ID_* ids from 301 - so the EX family; SaveBlock1 grew (templates 0xDD8, vars 0x1510: offsetof).
    dict(name="Emerald Legacy v1.1.4", sha1="cbd71af65ee3dcd53280a6a12014bfb11e8f626a", family="EX",
         save="emerald_legacy", script=0x0809A294, a=dict(
             VMAP=0x03005FA0, MAP_HEADER=0x020374A4, MAP_GROUPS=0x084AF90C, SAVE_BLOCK1_PTR=0x03005F6C,
             CAMERA_OFFSET=0x03000E20, OBJECT_EVENTS=0x020374DC, SPRITES=0x02020630, COORD_OFFSET=0x02021BBC,
             COORD_OFFSET_Y=0x02021BBE, SPRITE_ORDER=0x020217F4, GMAIN=0x030024A0, CONTROLS_LOCKED=0x03000F2C,
             OBJ_GFX_INFO=0x085353B0, OBJ_PALETTES=0x0853C4B4, OBJ_FACING=0x0853535D, ELEVATION_PRIO=0x0853F004,
             SPRITE_PAL_TAGS=0x03000CF0, OBJ_GFX_VAR_FROM=301, OBJ_GFX_COUNT=300, TEMPLATE_KIND_AT=1,
             SB1_OFFSETS=0xDD8 | 0x1510 << 16)),
    # R.O.W.E.: Emerald's code (mapped), its own maps - 12-bit metatile ids (elevation in the top 4 bits) and a 2048-
    # metatile primary tileset; gSprites moved (find_wide_addrs.py).
    dict(name="R.O.W.E. v2.1.9.1", sha1="81bd0f4bfa1c04ab2c6faab1bddd10e8a390ea77", family="EM", mapped=True,
         pending="the map is found now, but one NPC's sprite isn't drawn and a building comes out of the wrong metatiles",
         save="rowe", a=dict(METATILE_MASK=0xFFF, PRIMARY_OVERRIDE=2048, SPRITES=0x0201F5D0)),
    # Quetzal: its own SaveBlock1 (pos 0x470 in, the app's NativeConfig), no far-off objects (its own templates).
    dict(name="Quetzal", sha1="d0658315da1e8827f66f15c3d3a000fe747e163e", family="EM", mapped=True, save="quetzal",
         pending="most map frames differ (its own engine: not mapped beyond the RAM yet)",
         a=dict(MAP_GROUPS=0x089286BC, SAVE_BLOCK1_PTR=0x030055BC, SB1_POS=0x470, MAP_HEADER=0x02038CB0,
                OBJ_GFX_INFO=0)),
    dict(name="Quetzal (Spanish)", sha1="fe346b5b0eb022e3a103f81e7dcefba6f1dc542f", family="EM", mapped=True,
         pending="as Quetzal",
         save="quetzal_es", a=dict(MAP_GROUPS=0x08934070, SAVE_BLOCK1_PTR=0x030055BC, SB1_POS=0x470,
                                   MAP_HEADER=0x02038CB0, OBJ_GFX_INFO=0)),
    # Super Mariomon: Emerald's code, its RAM 0x80 lower from the sprites on (sSpriteOrder: find_wide_addrs.py).
    *[dict(name=f"Super Mariomon v{v}", sha1=sha, family="EM", mapped=True, save=f"mariomon_v{v.replace('.', '_')}",
           a=dict(SPRITE_ORDER=0x02021774, MAP_GROUPS=groups))
      for v, sha, groups in [("1.5.2", "06e79d12afa578db97c23c1f7ba771fe2f8fe905", 0x086986B0),
                             ("1.5.1", "831fe0f97301f17b24a9cd4a64ef0520835c4dde", 0x0869821C),
                             ("1.3", "043512c2497871c018df606fb90ffbd4c81430f4", 0x08680194),
                             ("1.2.2", "08d1c275c0ba4d559b33427292702d61ad49fb5c", 0x086801D4),
                             ("1.2.1", "43dd208b23eade69aa3384301e934ecafbf6c2b3", 0x086801F4)]],
    # More expansion builds (scripts/find_wide_addrs.py, find_wide_objects.py; ScriptContext_SetupScript from the
    # battle fixtures or the signature push {r4-r6, lr}; ldr r4, =ctx; movs r6, r0; movs r0, r4; movs r5, #0).
    dict(name="Seaglass v3.0", sha1="b9f4d332d30fc88c379f9e037f9eae3b2755ead4", family="EX", save="emerald_seaglass",
         boot=["press B", "wait 30"] * 3, script=0x081EF598, a=dict(
             VMAP=0x03002464, MAP_HEADER=0x0200B04C, MAP_GROUPS=0x08B45148, SAVE_BLOCK1_PTR=0x030051B8,
             CAMERA_OFFSET=0x030023F4, OBJECT_EVENTS=0x0200564C, SPRITES=0x02039810, COORD_OFFSET=0x0203AD1C,
             COORD_OFFSET_Y=0x0203AD1E, SPRITE_ORDER=0x0203A954, GMAIN=0x030014B4, CONTROLS_LOCKED=0x03005644,
             OBJ_GFX_INFO=0x085CE6BC, OBJ_PALETTES=0x085D4234, OBJ_FACING=0x085CE668, ELEVATION_PRIO=0x085D7618,
             SPRITE_PAL_TAGS=0x03006644, OBJ_GFX_VAR_FROM=0x103, SB1_OFFSETS=0xDC0 | 0x14EC << 16)),
    dict(name="Emerald Imperium v1.3.1", sha1="1d20091c4d936f5eb122db8780554dd0829ffb63", family="EX", save="imperium",
         boot=["press B", "wait 30"] * 3, script=0x08200930, a=dict(
             VMAP=0x030050AC, MAP_HEADER=0x0200A68C, MAP_GROUPS=0x08FC2F74, SAVE_BLOCK1_PTR=0x03006218,
             CAMERA_OFFSET=0x03000988, OBJECT_EVENTS=0x0200A6C4, SPRITES=0x0203D2A8, COORD_OFFSET=0x0203E7B4,
             COORD_OFFSET_Y=0x0203E7B6, SPRITE_ORDER=0x0203E3EC, GMAIN=0x03004218, CONTROLS_LOCKED=0x03000E84,
             OBJ_GFX_INFO=0x0860AB14, OBJ_PALETTES=0x086108F0, OBJ_FACING=0x0860AAC0, ELEVATION_PRIO=0x08613DD0,
             SPRITE_PAL_TAGS=0x03001204, OBJ_GFX_VAR_FROM=0x106, SB1_OFFSETS=0x13C4 | 0x1AF0 << 16)),
    # Rogue: gMapGroups from its save (the current map's header -> its group's array -> the table).
    dict(name="Emerald Rogue v2.2.1-EX", sha1="7600af1fe08444c850c3c1227fd7dfd81336ae8e", family="EX",
         save="emerald_rogue", boot=["press B", "wait 30"] * 3, script=0x081FC240, a=dict(
             VMAP=0x03002410, MAP_HEADER=0x020264B4, MAP_GROUPS=0x0928BF04, SAVE_BLOCK1_PTR=0x03003614,
             CAMERA_OFFSET=0x030023A0, OBJECT_EVENTS=0x02020A48, SPRITES=0x0203C690, COORD_OFFSET=0x0203DB9C,
             COORD_OFFSET_Y=0x0203DB9E, SPRITE_ORDER=0x0203D7D4, GMAIN=0x030014B4, CONTROLS_LOCKED=0x03003C1C,
             # Its templates: graphicsId a u16 at +2 (kind at +1); records to 0x268, vars not drawn (where its vars
             # sit isn't known: 0xFFFF = none); no palette tag list found, so ROM palettes untinted.
             OBJ_GFX_INFO=0x08ACD2C8, OBJ_PALETTES=0x08AFE298, OBJ_FACING=0x08ACA598, ELEVATION_PRIO=0x08B018C0,
             OBJ_GFX_VAR_FROM=0xFFFF, OBJ_GFX_COUNT=0x269, TEMPLATE_KIND_AT=1, SB1_OFFSETS=0xED0)),
    # Heart and Soul: SaveBlock1's pos 4 bytes in; FireRed's 640 primary metatiles on its own layouts (MapLayout +0x18 =
    # 2), Emerald's 512 on the Emerald ones it kept (0); the RHH splash boot (default.txt's A presses talk to the follower).
    dict(name="Heart and Soul v2.0.6", sha1="79ee6df0869c1773c8c6a5f764afc1f8d833d8bb", family="EX",
         save="heart_and_soul", boot_file="rhh_splash.txt", script=0x08208094, a=dict(
             VMAP=0x0300306C, MAP_HEADER=0x02001D0C, MAP_GROUPS=0x092DF5A4, SAVE_BLOCK1_PTR=0x030041D8, SB1_POS=4,
             CAMERA_OFFSET=0x03000BDC, OBJECT_EVENTS=0x020017F4, SPRITES=0x0203A764, COORD_OFFSET=0x0203A2DE,
             COORD_OFFSET_Y=0x0203A2E0, SPRITE_ORDER=0x0203A29C, GMAIN=0x03005BD8, PAN_OFF=12,
             CONTROLS_LOCKED=0x03001FDD, PRIMARY_OVERRIDE=640, LAYOUT_VERSIONED=1,
             OBJ_GFX_INFO=0x0850C6F4, OBJ_PALETTES=0x085036B0, OBJ_FACING=0x084FFF58, ELEVATION_PRIO=0x08500360,
             OBJ_GFX_VAR_FROM=0xFFFF, OBJ_GFX_COUNT=0x224, SB1_OFFSETS=0x138C | 0x1ABC << 16)),
    dict(name="Too Many Types 2 v1.5.2", sha1="3fa8e61ec1727cc51540c54677623f736a446e1b", family="EX", save="tmt2",
         boot=["press B", "wait 30"] * 3, script=0x081DC368, a=dict(
             VMAP=0x030046A8, MAP_HEADER=0x02001BBC, MAP_GROUPS=0x09047730, SAVE_BLOCK1_PTR=0x030057D8,
             CAMERA_OFFSET=0x03000A8C, OBJECT_EVENTS=0x020016AC, SPRITES=0x0203661C, COORD_OFFSET=0x02036196,
             COORD_OFFSET_Y=0x02036198, SPRITE_ORDER=0x02036154, GMAIN=0x03004084, CONTROLS_LOCKED=0x03000E6C,
             OBJ_GFX_INFO=0x0858E658, OBJ_PALETTES=0x08588ACC, OBJ_FACING=0x085855A4, ELEVATION_PRIO=0x085859CC,
             OBJ_GFX_VAR_FROM=0xFFFF, OBJ_GFX_COUNT=0x10D, SB1_OFFSETS=0xEA8 | 0x15D4 << 16)),
    # Recharged Yellow: a gcc pokefirered - FireRed's map, triple-layer metatiles, expansion's objects.
    *[dict(name=f"Recharged Yellow v{v}", sha1=sha, family="FR_EX", save=save, boot=["press B", "wait 30"] * 3,
           script=script, a=dict(VMAP=vmap, MAP_HEADER=hdr, MAP_GROUPS=groups, SAVE_BLOCK1_PTR=sb1,
                                 CAMERA_OFFSET=0x03000928, OBJECT_EVENTS=oe, SPRITES=spr, COORD_OFFSET=coord,
                                 COORD_OFFSET_Y=coord + 2, SPRITE_ORDER=order, GMAIN=0x03003B64, METATILE_SIZE=24,
                                 # Its GetObjectEventGraphicsInfo: ids to 0x158 from the table, 0x15A.. from vars
                                 # (it subtracts 0x15A), 0x200.. Pokémon; SaveBlock1 reordered (flags 0xEFB, vars
                                 # 0x1028: the app's GuideTables; the templates after them). The controls lock: the
                                 # byte that's 1 under a scripted message and 0 through the whole test walk.
                                 SPRITE_PAL_TAGS=0x03001194, OBJ_GFX_VAR_FROM=0x15A, OBJ_GFX_COUNT=0x159,
                                 SB1_OFFSETS=0x1398 | 0x1028 << 16, SB1_FLAGS_AT=0xEFB, **objs))
      for v, sha, save, script, vmap, hdr, groups, sb1, oe, spr, coord, order, objs in [
          ("1.8.0", "be5033598e91d0e80fa93e551406f02a7876d7c2", "recharged_yellow_v180", 0x081A3540, 0x03004990,
           0x0200B6CC, 0x08A356F0, 0x03005AB4, 0x02005CCC, 0x0203BBF0, 0x0203D0FC, 0x0203CD34,
           dict(OBJ_GFX_INFO=0x08779694, CONTROLS_LOCKED=0x03005EB0, OBJ_PALETTES=0x0878AC50, ELEVATION_PRIO=0x0878DDEC, OBJ_FACING=0x087791BC)),
          ("1.9.0", "ac30d1ed80e75104a3fb92f71e2b9a51c26e80c8", "recharged_yellow_v190", 0x081A4550, 0x03004990,
           0x0200B6D4, 0x08A35758, 0x03005AB4, 0x02005CD4, 0x0203BBF8, 0x0203D104, 0x0203CD3C,
           dict(OBJ_GFX_INFO=0x08776A6C, CONTROLS_LOCKED=0x03005EA8, OBJ_PALETTES=0x08788028, ELEVATION_PRIO=0x0878B1C4, OBJ_FACING=0x08776594)),
          ("1.9.4", "7d9fae6cf31c07bc7fa5cf7afe137ad5385cefb1", "recharged_yellow_v194", 0x081A56DC, 0x03004990,
           0x0200B6D4, 0x08B16238, 0x03005AB4, 0x02005CD4, 0x0203BC04, 0x0203D110, 0x0203CD48,
           dict(OBJ_GFX_INFO=0x088569E8, CONTROLS_LOCKED=0x03005EA8, OBJ_PALETTES=0x08867FA4, ELEVATION_PRIO=0x0886B140, OBJ_FACING=0x08856510)),
          ("1.9.7", "ed60654d7525ba005bfc8f799bf0ecba2ef08a2e", "recharged_yellow", 0x081A5898, 0x030049AC,
           0x0200B6D4, 0x08B3F134, 0x03005AD0, 0x02005CD4, 0x0203BC04, 0x0203D110, 0x0203CD48,
           dict(OBJ_GFX_INFO=0x0887EE9C, CONTROLS_LOCKED=0x03005EC4, OBJ_PALETTES=0x08890458, ELEVATION_PRIO=0x088935F4, OBJ_FACING=0x0887E9C4))]],
    # pokeemerald-expansion builds: scripts/find_wide_addrs.py's dumps (gMapHeader, gSaveBlock1Ptr and gMapGroups from
    # the app's NativeConfig / GuideTables), ScriptContext_SetupScript from their battle fixtures' READMEs.
    dict(name="Lazarus v2.0", sha1="7dcdc7e280bc4631487e13dd37e6e0cea04adea6", family="EX", save="lazarus_srm",
         boot=["press B", "wait 30"] * 3, script=0x0820B2B0, a=dict(
             VMAP=0x03002468, MAP_HEADER=0x0200B06C, MAP_GROUPS=0x08FAF098, SAVE_BLOCK1_PTR=0x03003664,
             CAMERA_OFFSET=0x030023F8, OBJECT_EVENTS=0x0200566C, SPRITES=0x0203B5CC, COORD_OFFSET=0x0203CAD8,
             COORD_OFFSET_Y=0x0203CADA, SPRITE_ORDER=0x0203C710, GMAIN=0x030014B8, METATILE_SIZE=24,
             OBJ_GFX_INFO=0x087AC934, OBJ_PALETTES=0x087B6704, OBJ_FACING=0x087AC8E0, ELEVATION_PRIO=0x087B9C3C,
             SPRITE_PAL_TAGS=0x03004B38, CONTROLS_LOCKED=0x03003B38, OBJ_GFX_VAR_FROM=0x1ED,
             SB1_OFFSETS=0xCE8 | 0x1414 << 16)),
    dict(name="SoulGold v1.1.4", sha1="ea5d369cc8a31cbf1cfacb7c9470ea670f08957b", family="EX", save="soulgold",
         boot=["press B", "wait 30"] * 3, script=0x0823647C, a=dict(
             VMAP=0x03002F58, MAP_HEADER=0x02003828, MAP_GROUPS=0x09587364, SAVE_BLOCK1_PTR=0x030040C4,
             CAMERA_OFFSET=0x03000CB8, OBJECT_EVENTS=0x02003314, SPRITES=0x0203BF38, COORD_OFFSET=0x0203BAB2,
             COORD_OFFSET_Y=0x0203BAB4, SPRITE_ORDER=0x0203BA70, GMAIN=0x030055C0, PAN_OFF=-4, METATILE_SIZE=24,
             METATILE_MASK=0x7FF, PRIMARY_OVERRIDE=1024, OBJ_GFX_INFO=0x084FDF3C, OBJ_PALETTES=0x084F6B48,
             OBJ_FACING=0x084F34C8, ELEVATION_PRIO=0x084F3918, **SG_OBJECTS)),
    # v1.2's two builds: the same RAM, 8 bytes on from v1.1.4's sprite globals; their own ROM data.
    dict(name="SoulGold v1.2", sha1="805d880ee229fb6dc3ce03d7b03baf48f0d759d0", family="EX", save="soulgold_v12",
         boot=["press B", "wait 30"] * 3, script=0x08236D5C, a=dict(
             VMAP=0x03002F58, MAP_HEADER=0x02003828, MAP_GROUPS=0x09592634, SAVE_BLOCK1_PTR=0x030040C4,
             CAMERA_OFFSET=0x03000CB8, OBJECT_EVENTS=0x02003314, SPRITES=0x0203BF40, COORD_OFFSET=0x0203BABA,
             COORD_OFFSET_Y=0x0203BABC, SPRITE_ORDER=0x0203BA78, GMAIN=0x030055C0, PAN_OFF=-4, METATILE_SIZE=24,
             METATILE_MASK=0x7FF, PRIMARY_OVERRIDE=1024, OBJ_GFX_INFO=0x084FF56C, OBJ_PALETTES=0x084F7E0C,
             OBJ_FACING=0x084F476C, ELEVATION_PRIO=0x084F4BDC, **SG_OBJECTS)),
    dict(name="SoulGold v1.2 (second build)", sha1="5d6a036260fdbde96f85b5c1b92d0256d3aebafb", family="EX",
         save="soulgold_v12b", boot=["press B", "wait 30"] * 3, script=0x08236CC4, a=dict(
             VMAP=0x03002F58, MAP_HEADER=0x02003828, MAP_GROUPS=0x09592590, SAVE_BLOCK1_PTR=0x030040C4,
             CAMERA_OFFSET=0x03000CB8, OBJECT_EVENTS=0x02003314, SPRITES=0x0203BF40, COORD_OFFSET=0x0203BABA,
             COORD_OFFSET_Y=0x0203BABC, SPRITE_ORDER=0x0203BA78, GMAIN=0x030055C0, PAN_OFF=-4, METATILE_SIZE=24,
             METATILE_MASK=0x7FF, PRIMARY_OVERRIDE=1024, OBJ_GFX_INFO=0x084FF4D4, OBJ_PALETTES=0x084F7D74,
             OBJ_FACING=0x084F46D4, ELEVATION_PRIO=0x084F4B44, **SG_OBJECTS)),
    # The third v1.2 build (from main): v1.2's RAM again (find_wide_addrs.py), its own ROM data.
    dict(name="SoulGold v1.2 (third build)", sha1="2be7c99aa473308debe1d03ff2015bab7d9addb5", family="EX",
         save="soulgold_v12c", boot=["press B", "wait 30"] * 3, script=0x08236DBC, a=dict(
             VMAP=0x03002F58, MAP_HEADER=0x02003828, MAP_GROUPS=0x095C04C0, SAVE_BLOCK1_PTR=0x030040C4,
             CAMERA_OFFSET=0x03000CB8, OBJECT_EVENTS=0x02003314, SPRITES=0x0203BF40, COORD_OFFSET=0x0203BABA,
             COORD_OFFSET_Y=0x0203BABC, SPRITE_ORDER=0x0203BA78, GMAIN=0x030055C0, PAN_OFF=-4, METATILE_SIZE=24,
             METATILE_MASK=0x7FF, PRIMARY_OVERRIDE=1024, OBJ_GFX_INFO=0x084FF660, OBJ_PALETTES=0x084F7F00,
             OBJ_FACING=0x084F4860, ELEVATION_PRIO=0x084F4CD0, **SG_OBJECTS)),
    # v1.2.1's two builds (2 bytes of item data apart): IWRAM as before, EWRAM moved (gMapHeader / gObjectEvents +8,
    # the sprite globals -0xD8: their dumps against the third v1.2 build's), its own ROM data.
    *[dict(name=f"SoulGold v1.2.1{n}", sha1=sha, family="EX", save=save, boot=["press B", "wait 30"] * 3, script=0x0823BC04,
           a=dict(VMAP=0x03002F58, MAP_HEADER=0x02003830, MAP_GROUPS=0x095CC3A8, SAVE_BLOCK1_PTR=0x030040C4,
                  CAMERA_OFFSET=0x03000CB8, OBJECT_EVENTS=0x0200331C, SPRITES=0x0203BE68, COORD_OFFSET=0x0203B9E2,
                  COORD_OFFSET_Y=0x0203B9E4, SPRITE_ORDER=0x0203B9A0, GMAIN=0x030055C0, PAN_OFF=-4, METATILE_SIZE=24,
                  METATILE_MASK=0x7FF, PRIMARY_OVERRIDE=1024, OBJ_GFX_INFO=0x085070EC, OBJ_PALETTES=0x084FF98C,
                  OBJ_FACING=0x084FC2EC, ELEVATION_PRIO=0x084FC75C, **SG_OBJECTS))
      for n, sha, save in [("", "32988f918ca09923466ae3bc5bbfe5f2a771c728", "soulgold_v121"),
                           (" (second build)", "dacb6e40e7f572ddefd2dfa10e99c0da6835b062", "soulgold_v121b")]],
    # Elite Redux: its own gcc pokeemerald (not expansion) with expansion-like objects (a u16 graphicsId at +4,
    # spriteId +0x23; templates' graphicsId a u16 at +2, byte 1 padding - no clones) and R.O.W.E.'s maps (12-bit metatile
    # ids, 2048 primary metatiles). Its GetObjectEventGraphicsInfo (inlined): ids 271.. from VAR_OBJ_GFX_ID_* (vars at
    # SaveBlock1 + 0x1C78), 270 the table's end; templates at 0x1378, flags 0x600 on. No sSpritePaletteTags list (its
    # NPCs' palette tags are nowhere in RAM). The builds share the IWRAM; 2.65.4.1b's EWRAM sits 8 on from the objects.
    *[dict(name=f"Elite Redux {v}", sha1=sha, family="EX", save=save, boot_file="elite_redux.txt", script=script, a=dict(
          VMAP=0x03002210, MAP_HEADER=hdr, MAP_GROUPS=groups, SAVE_BLOCK1_PTR=0x0300340C, CAMERA_OFFSET=0x030021A0,
          OBJECT_EVENTS=oe, SPRITES=spr, COORD_OFFSET=coord, COORD_OFFSET_Y=coord + 2, SPRITE_ORDER=order,
          GMAIN=0x03003420, CONTROLS_LOCKED=0x03004518, METATILE_MASK=0xFFF, PRIMARY_OVERRIDE=2048,
          OBJ_GFX_VAR_FROM=271, OBJ_GFX_COUNT=270, TEMPLATE_KIND_AT=1, SB1_OFFSETS=0x1378 | 0x1C78 << 16, **objs))
      for v, sha, save, script, hdr, groups, oe, spr, coord, order, objs in [
          ("2.65.3b", "5b38a681b6924ef3e0bb9f6cfbf22426c4c4e523", "elite_redux_v2653", 0x081F6154, 0x020277B8, 0x0917995C,
           0x02021DBC, 0x0203D218, 0x0203E7A4, 0x0203E3DC,
           dict(OBJ_GFX_INFO=0x08A69538, OBJ_PALETTES=0x08A6E13C, OBJ_FACING=0x08A66E14, ELEVATION_PRIO=0x08A70EE8)),
          ("2.65.4.1b", "2f3e17e55c6afe23da97ec0f96a067eeb142ad15", "elite_redux", 0x081F3984, 0x020277C0, 0x091F54A0,
           0x02021DC4, 0x0203D220, 0x0203E7AC, 0x0203E3E4,
           dict(OBJ_GFX_INFO=0x08A76A00, OBJ_PALETTES=0x08A7B604, OBJ_FACING=0x08A742DC, ELEVATION_PRIO=0x08A7E3B0))]],
]
FAMILY = {"BPR": "FR", "BPG": "FR", "BPE": "EM", "AXV": "RS", "AXP": "RS"}
# Hacks under a game code of their own (only taken by SHA-1, as every hack).
HACK_CODES = {"PUV"}
KOTLIN = os.path.join(ROOT, "app", "src", "main", "kotlin", "com", "pokedaisy", "app")


def retail_sha1s():
    """The retail releases the app knows by SHA-1: GameTitles' RETAIL map (its HACKS come after it) and
    the generated other-language ports (RETAIL_PORT_TITLES). Hacks share the game codes, but WIDE is
    checked on retail only."""
    hand = open(os.path.join(KOTLIN, "GameTitles.kt")).read()
    hand = hand[hand.index("private val RETAIL"):hand.index("private val HACKS")]
    ports = open(os.path.join(KOTLIN, "companion", "data", "RetailPortsGen.kt")).read()
    ports = ports[ports.index("RETAIL_PORT_TITLES"):]
    return set(re.findall(r'"([0-9a-f]{40})" to', hand + ports))


def roms(dirs):
    for d in dirs:
        for root, _, files in os.walk(d):
            for f in sorted(files):
                if f.lower().endswith(".gba"):
                    yield os.path.join(root, f)


def map_shapes(rom, groups):
    """The width x height of the first maps of the first groups, read through gMapGroups - the same
    in every language and revision, so a mapped MAP_GROUPS that gives the reference's shapes is right
    (it only ever gets one literal-pool vote, and the pixel checks never reach the connected maps)."""
    out = []
    try:
        for g in range(6):
            arr = rom.u32(groups + 4 * g)
            for n in range(8):
                hdr = rom.u32(arr + 4 * n)
                lay = rom.u32(hdr)
                out.append((rom.u32(lay), rom.u32(lay + 4)))
    except (struct.error, IndexError):
        return None
    return out


def ghost_tables(rom, a):
    """The far-off objects' ROM tables as the reference has them: the elevation -> priority bytes, the first
    graphics infos (a 16x32 sheet with images), palettes ({data, tag}); a mapped one with a single vote is
    only trusted once its contents check out. A (simplified) fingerprint, None if it can't be read."""
    try:
        elev = rom.d[a["ELEVATION_PRIO"] - 0x08000000:][:16] if a.get("ELEVATION_PRIO") else None
        infos = []
        for i in range(4) if a.get("OBJ_GFX_INFO") else ():
            info = rom.u32(a["OBJ_GFX_INFO"] + 4 * i)
            infos.append((struct.unpack_from("<hh", rom.d, info - 0x08000000 + 8), rom.u32(info + 0x1C) >> 24))
        pals = [rom.u32(a["OBJ_PALETTES"] + 8 * i) >> 24 for i in range(4)] if a.get("OBJ_PALETTES") else None
        facing = rom.d[a["OBJ_FACING"] - 0x08000000:][:8] if a.get("OBJ_FACING") else None
        return elev, infos, pals, facing
    except (struct.error, IndexError):
        return None


def main():
    dirs = [os.path.expanduser(d) for d in sys.argv[1:]]
    if not dirs:
        sys.exit(__doc__)
    retail = retail_sha1s()
    hacks = {h["sha1"]: h for h in HACKS}
    found, hacked = {}, {}
    for path in roms(dirs):
        with open(path, "rb") as f:
            head = f.read(0xC0)
        code = head[0xAC:0xB0].decode("ascii", "replace")
        if code[:3] not in FAMILY and code[:3] not in HACK_CODES:
            continue
        data = open(path, "rb").read()
        sha1 = hashlib.sha1(data).hexdigest()
        if sha1 in retail:
            found.setdefault(zlib.crc32(data), path)   # the same dump in two dirs once
        elif sha1 in hacks:
            hacked.setdefault(zlib.crc32(data), (path, hacks[sha1]))
    refs = {}
    for fam, r in REFERENCES.items():
        ref = next((p for p in found.values() if open(p, "rb").read(0xC0)[0xAC:0xB0].decode() == r["code"]
                    and open(p, "rb").read(0xC0)[0xBC] == r["rev"]), None)
        if not ref:
            sys.exit(f"the {fam} reference ({r['code']} rev {r['rev']}) isn't in the dirs")
        refs[fam] = Rom(ref)
    out, report = [], []
    jobs = [(crc, path, None) for crc, path in sorted(found.items(), key=lambda kv: kv[1])]
    jobs += [(crc, path, hack) for crc, (path, hack) in sorted(hacked.items(), key=lambda kv: kv[1][0])]
    for crc, path, hack in jobs:
        dst = Rom(path)
        fam = hack["family"] if hack else FAMILY[dst.code[:3]]
        src, ref = refs.get(fam), REFERENCES.get(fam)
        if fam == "RS" and dst.lang == "J":
            fam = "RS_JA"   # mapped from English Ruby, but its own engine variant (pk_wide.c's FAMILIES)
        addrs, votes, missing = {}, {}, []
        for slot in SLOTS:
            if hack and slot in hack["a"]:
                val, n = hack["a"][slot], "hack"
            elif slot in CONSTANTS:
                val, n = CONSTANTS[slot], "-"
            elif hack and not hack.get("mapped"):
                val, n = None, 0
            elif slot not in ref["a"]:
                val, n = 0, "-"   # an optional one the reference hasn't got
            elif dst.d == src.d:
                val, n = ref["a"][slot], "ref"
            else:
                val, n = map_literal(src, dst, ref["a"][slot])
            if val is None and slot in OPTIONAL:
                val, n = 0, 0
            if val is None:
                missing.append(slot)
            addrs[slot], votes[slot] = val, n
        if not hack and addrs["MAP_GROUPS"] is not None and map_shapes(dst, addrs["MAP_GROUPS"]) != map_shapes(src, ref["a"]["MAP_GROUPS"]):
            missing.append("MAP_GROUPS (its maps don't match the reference's)")
        if not hack and ghost_tables(dst, addrs) != ghost_tables(src, ref["a"]):
            # Not the reference's tables: no far-off objects for it (WIDE itself doesn't need them).
            print(f"{os.path.basename(path)}: far-off objects' tables don't match the reference's, left off")
            for k in ("OBJ_GFX_INFO", "OBJ_PALETTES", "OBJ_FACING", "ELEVATION_PRIO"):
                addrs[k], votes[k] = 0, 0
        if hack and "script" in hack:
            script = hack["script"]
        else:
            script = ref["script"] if dst.d == src.d else map_code(src, dst, ref["script"] | 1)
        entry = dict(file=os.path.basename(path), path=path, crc=f"{crc:08x}", code=dst.code, rev=dst.rev, family=fam,
                     addrs={k: (f"{v:#010x}" if v is not None else None) for k, v in addrs.items()}, votes=votes,
                     script=f"{script:#010x}" if script else None, missing=missing, save=hack["save"] if hack else None,
                     boot=hack.get("boot", []) if hack else [], boot_file=hack.get("boot_file") if hack else None,
                     script_ram=hack.get("script_ram") if hack else None,
                     name=hack["name"] if hack else None)
        report.append(entry)
        if hack and hack.get("pending"):
            print(f"pending {entry['name']}: {hack['pending']}")
            entry["pending"] = hack["pending"]
        if missing:
            print(f"left out {entry['file']}: no {', '.join(missing)}")
            continue
        out.append(entry)
        print(f"{dst.code} rev {dst.rev} {crc:08x} {entry['file']}: " +
              " ".join(f"{k}={votes[k]}" for k in SLOTS))
    os.makedirs(os.path.dirname(OUT_JSON), exist_ok=True)
    json.dump(report, open(OUT_JSON, "w"), indent=1)
    lines = [
        "// Generated by scripts/gen_wide_tables.py from the user's own ROMs - don't edit by hand.",
        "// WIDE's addresses per release (retail and the hacks it knows), by ROM CRC32 (pk_wide.c looks them up).",
        "#pragma once",
        "",
        "static const struct PkWideRom {",
        "    uint32_t crc;",
        "    int family;   // PK_WIDE_FAMILY_*",
        "    int32_t a[PK_WIDE_ADDRS];",
        "} PK_WIDE_ROMS[] = {",
    ]
    def row(e):
        vals = ", ".join(e["addrs"][s] for s in SLOTS)
        return f"    {{ 0x{e['crc']}, PK_WIDE_FAMILY_{e['family']}, {{ {vals} }} }},   // {e['name'] or f"{e['code']} rev {e['rev']}"}: {e['file']}"
    lines += [row(e) for e in out if not e.get("pending")]
    lines += ["};", ""]
    # Pending hacks: checked by verify_wide.py (its build defines PK_WIDE_PENDING, which lets WIDE draw them); the app
    # reads their field with them (SCENE SPEEDS' TEXT, AUTO-RUN, RUN INDOORS, WILD ENCOUNTERS: pk_field_configure).
    lines += [
        "// Hacks WIDE doesn't draw yet (`pending` in gen_wide_tables.py): only the field reads use them, unless",
        "// PK_WIDE_PENDING (verify_wide.py's build) lets WIDE draw them too.",
        "static const struct PkWideRom PK_FIELD_ONLY_ROMS[] = {",
    ]
    lines += [row(e) for e in out if e.get("pending")] or ["    { 0, 0, { 0 } },"]
    lines += ["};", ""]
    open(OUT_H, "w").write("\n".join(lines))
    pending = sum(1 for e in out if e.get("pending"))
    print(f"{len(out) - pending} ROMs -> {os.path.relpath(OUT_H, ROOT)} (+{pending} pending, for the checks); "
          f"{len(report) - len(out)} left out")


if __name__ == "__main__":
    main()
