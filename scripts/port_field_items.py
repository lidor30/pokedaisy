#!/usr/bin/env python3
"""Maps USE / GIVE / TAKE's addresses (FieldItems.kt) into the other-language releases.

    scripts/port_field_items.py <roms dir> [--no-write]

The English tables come from pret's decomps built clean (each byte for byte the retail ROM: pokefirered as
firered / firered_rev1 / leafgreen / leafgreen_rev1, pokeemerald, pokeruby as ruby / sapphire and their revs) and
their ELFs. For every ROM under <roms dir> of those games in another language - and English FireRed / Ruby /
Sapphire rev 0, whose tables are written straight from their ELFs - it

  1. picks the English ROM of the same game most alike (port_retail.py's nearest_english),
  2. maps each function by its code bytes (a unique 32 / 24 / 16-byte run; else BL- and pool-masked near the
     others' shift), RAM globals and gItems through the literal pools that load them (port_retail.py's helpers),
  3. checks what it found against the ROM itself: gItems' records hold ids 1..8 and the Repel's 100 steps, and the
     mapped field-use routines are what gItems points at for a Potion, an Ether, a PP Up and a Repel,
  4. writes app/.../companion/data/FieldItemsPortsGen.kt (FIELD_ITEMS_PORTS, by game code + revision).

A ROM where anything fails to map or check is reported and left out (it keeps no USE / GIVE / TAKE).
"""
import os
import re
import statistics
import struct
import sys
import tomllib
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from port_retail import B, Rom, detect_items, map_code, map_code_masked, map_literal, nearest_english  # noqa: E402

OUT = os.path.join(HERE, "..", "app", "src", "main", "kotlin", "com", "pokedaisy", "app", "companion", "data",
                   "FieldItemsPortsGen.kt")

FUNCS = ("varGet", "varSet", "checkBagHasItem", "removeBagItem", "fieldControlsLocked",
         "getMonData", "setMonData", "itemNoEffect", "itemEffect", "addBagItem")
USE = ("medicine", "ppRecovery", "ppUp", "repel")  # the field-use routines of item 13, 34, 69, 86
USE_PROBE = dict(medicine=13, ppRecovery=34, ppUp=69, repel=86)

FR = dict(repelVar=0x4020, itemCount=375, pockets=(2, 4), pcSpecial=60)  # POCKET_KEY_ITEMS, POCKET_TM_CASE
EM = dict(repelVar=0x4021, itemCount=377, pockets=(5, 3), pcSpecial=63)  # POCKET_KEY_ITEMS, POCKET_TM_HM
RS = dict(repelVar=0x4021, itemCount=349, pockets=(5, 3), pcSpecial=60)

# FLY (Fly.kt): the English tables' addresses, from FLY_FIRERED_REV1 / FLY_LEAFGREEN_REV0 / _REV1 / FLY_EMERALD (records in
# gamedata/gen3/records/). Functions are mapped by code, gPartyMenu and gSaveBlock2Ptr through the literal pools,
# sMapFlyDestinations by its pool word or its bytes (the same map ids in every language).
FLY_FUNCS_FR = ("flagGet", "setUpFieldMoveFly", "getMapsecType", "fadeScreen", "lockControls", "unlockControls",
                "setFlyWarpDestination")
FLY_FUNCS_EM = FLY_FUNCS_FR[:-1] + ("toHealLocation", "toMapWarp", "returnToField")
FLY_FR1 = dict(flagGet=0x0806E6E4, setUpFieldMoveFly=0x08124B04, getMapsecType=0x080C35F0, fadeScreen=0x0807A82C,
               lockControls=0x08069954, unlockControls=0x08069960, setFlyWarpDestination=0x080C5290,
               partyMenu=0x0203B0A0, destinations=0x083F2F50, count=108)
FLY = {
    "BPRE1": FLY_FR1,
    "BPGE0": dict(FLY_FR1, flagGet=0x0806E6D0, setUpFieldMoveFly=0x08124A64, getMapsecType=0x080C35B0,
                  fadeScreen=0x0807A7EC, lockControls=0x08069940, unlockControls=0x0806994C,
                  setFlyWarpDestination=0x080C5250, destinations=0x083F2D1C),
    "BPGE1": dict(FLY_FR1, setUpFieldMoveFly=0x08124ADC, getMapsecType=0x080C35C4, fadeScreen=0x0807A800,
                  setFlyWarpDestination=0x080C5264, destinations=0x083F2D8C),
    "BPEE0": dict(flagGet=0x0809D790, setUpFieldMoveFly=0x081B5884, getMapsecType=0x08123D58, fadeScreen=0x080ABCD0,
                  lockControls=0x08098E54, unlockControls=0x08098E60, toHealLocation=0x08084CCC, toMapWarp=0x08084C28,
                  returnToField=0x080B69DC, partyMenu=0x0203CEC8, saveBlock2Ptr=0x03005D90, destinations=0x085A1E3C,
                  count=50),
    # Ruby rev 1 (FLY_RUBY): the map-type check in place of SetUpFieldMove_Fly (with gMapHeader), the cut-in's
    # gLastFieldPokeMenuOpened (slotAddr) and gSaveBlock2 itself (saveBlock2).
    "AXVE1": dict(flagGet=0x08069360, setUpFieldMoveFly=0x08054244, getMapsecType=0x080FB778, fadeScreen=0x0807D664,
                  lockControls=0x08065550, unlockControls=0x0806555C, toHealLocation=0x08053558, toMapWarp=0x080534B0,
                  returnToField=0x080865DC, slotAddr=0x03005CE0, saveBlock2=0x02024EA4, mapHeader=0x0202E828,
                  destinations=0x083E793C, count=50),
}
# English FireRed rev 0 is written from REFS, but no clean build gives its FLY: ported from rev 1 like the others.
FLY_FROM = {"BPRE0": "BPRE1", "AXVE0": "AXVE1", "AXPE0": "AXVE1", "AXPE1": "AXVE1"}

# The BOXES tab's moves (BoxSwap.kt): BoxMonToMon, BoxMonRestorePP, GetBoxMonData and gPokemonStoragePtr, from the English
# tables (BOX_SWAP_*), mapped like FLY's. Ruby / Sapphire have no BoxMonToMon (their PC builds the Pokémon another way).
BOX_FUNCS = ("boxMonToMon", "boxMonRestorePP", "getBoxMonData")
BOX = {
    "BPRE1": dict(boxMonToMon=0x0803E788, boxMonRestorePP=0x080442F8, getBoxMonData=0x0803FD58, storage=0x03005010),
    "BPGE1": dict(boxMonToMon=0x0803E788, boxMonRestorePP=0x080442F8, getBoxMonData=0x0803FD58, storage=0x03005010),
    "BPGE0": dict(boxMonToMon=0x0803E774, boxMonRestorePP=0x080442E4, getBoxMonData=0x0803FD44, storage=0x03005010),
    "BPEE0": dict(boxMonToMon=0x08069004, boxMonRestorePP=0x0806E930, getBoxMonData=0x0806A674, storage=0x03005D94),
}
BOX_FROM = {"BPRE0": "BPRE1"}

# USE on a TM / HM (TmTeach.kt: the game's own TM screen) and FLASH from the QUICK MENU (Flash.kt), from the English records in
# gamedata/gen3/records/ (TM_* / FLASH_*), mapped like FLY's: functions by code, RAM globals through the literal pools. No TM
# screen on Ruby / Sapphire (their party menu has no exit to point at the field).
RECORDS = os.path.join(HERE, "..", "gamedata", "gen3", "records")
TM_REC = {"BPRE0": "TM_FIRERED_REV0", "BPRE1": "TM_FIRERED_REV1", "BPGE0": "TM_LEAFGREEN_REV0", "BPGE1": "TM_LEAFGREEN_REV1",
          "BPEE0": "TM_EMERALD"}
FLASH_REC = {"BPRE0": "FLASH_FIRERED_REV0", "BPRE1": "FLASH_FIRERED_REV1", "BPGE0": "FLASH_LEAFGREEN_REV0",
             "BPGE1": "FLASH_LEAFGREEN_REV1", "BPEE0": "FLASH_EMERALD", "AXVE0": "FLASH_RUBY_REV0", "AXVE1": "FLASH_RUBY",
             "AXPE0": "FLASH_SAPPHIRE_REV0", "AXPE1": "FLASH_SAPPHIRE"}
TM_FUNCS = ("itemUseCbTmhm", "showPartyMenuForItemUse", "initPartyMenu", "setMainCallback2", "returnToField", "cleanupOverworld",
            "fadeScreen", "lockControls", "unlockControls")   # TmFns.functions' order (its codeCrcs)


def record(name, _cache={}):
    """A gamedata record by name, its `base` merged in."""
    if not _cache:
        for f in sorted(os.listdir(RECORDS)):
            if f.endswith(".toml"):
                with open(os.path.join(RECORDS, f), "rb") as fh:
                    _cache.update(tomllib.load(fh).get("record", {}))
    r = dict(_cache[name])
    base = r.pop("base", None)
    return {**record(base), **r} if base else r


def ref(stem, fam, **a):
    return dict(stem=stem, fam=fam, a=a)


# English references, from the clean builds' ELFs (llvm-nm). PC anywhere's (Pc.kt: ScriptContext_SetupScript, the PC
# special's function, gSpecials) were found in each retail ROM by the clean builds' code and gSpecials' entry.
REFS = {
    "BPRE0": ref("Pokemon - FireRed Version (USA)", FR, varGet=0x0806E568, varSet=0x0806E584,
                 checkBagHasItem=0x08099F40, removeBagItem=0x0809A1D8, fieldControlsLocked=0x08069958,
                 getMonData=0x0803FBE8, setMonData=0x0804037C, itemNoEffect=0x08042414, itemEffect=0x080413C0,
                 addBagItem=0x0809A084, items=0x083DB028, party=0x02024284, partyCount=0x02024029,
                 medicine=0x080A16E0, ppRecovery=0x080A16FC, ppUp=0x080A1718, repel=0x080A1998,
                 setupScript=0x08069AE4, pcFn=0x0808C6A8, specials=0x0815FD60),
    "BPRE1": ref("Pokemon - FireRed Version (USA, Europe) (Rev 1)", FR, varGet=0x0806E57C, varSet=0x0806E598,
                 checkBagHasItem=0x08099F54, removeBagItem=0x0809A1EC, fieldControlsLocked=0x0806996C,
                 getMonData=0x0803FBFC, setMonData=0x08040390, itemNoEffect=0x08042428, itemEffect=0x080413D4,
                 addBagItem=0x0809A098, items=0x083DB098, party=0x02024284, partyCount=0x02024029,
                 medicine=0x080A16F4, ppRecovery=0x080A1710, ppUp=0x080A172C, repel=0x080A19AC,
                 setupScript=0x08069AF8, pcFn=0x0808C6BC, specials=0x0815FDD8),
    "BPGE0": ref("Pokemon - LeafGreen Version (USA)", FR, varGet=0x0806E568, varSet=0x0806E584,
                 checkBagHasItem=0x08099F14, removeBagItem=0x0809A1AC, fieldControlsLocked=0x08069958,
                 getMonData=0x0803FBE8, setMonData=0x0804037C, itemNoEffect=0x08042414, itemEffect=0x080413C0,
                 addBagItem=0x0809A058, items=0x083DAE64, party=0x02024284, partyCount=0x02024029,
                 medicine=0x080A16B4, ppRecovery=0x080A16D0, ppUp=0x080A16EC, repel=0x080A196C,
                 setupScript=0x08069AE4, pcFn=0x0808C67C, specials=0x0815FD3C),
    "BPGE1": ref("Pokemon - LeafGreen Version (USA, Europe) (Rev 1)", FR, varGet=0x0806E57C, varSet=0x0806E598,
                 checkBagHasItem=0x08099F28, removeBagItem=0x0809A1C0, fieldControlsLocked=0x0806996C,
                 getMonData=0x0803FBFC, setMonData=0x08040390, itemNoEffect=0x08042428, itemEffect=0x080413D4,
                 addBagItem=0x0809A06C, items=0x083DAED4, party=0x02024284, partyCount=0x02024029,
                 medicine=0x080A16C8, ppRecovery=0x080A16E4, ppUp=0x080A1700, repel=0x080A1980,
                 setupScript=0x08069AF8, pcFn=0x0808C690, specials=0x0815FDB4),
    "BPEE0": ref("Pokemon - Emerald Version (USA, Europe)", EM, varGet=0x0809D694, varSet=0x0809D6B0,
                 checkBagHasItem=0x080D6724, removeBagItem=0x080D6AA4, fieldControlsLocked=0x08098E6C,
                 getMonData=0x0806A518, setMonData=0x0806ACAC, itemNoEffect=None, itemEffect=0x0806BD04,
                 addBagItem=0x080D6928, items=0x085839A0, party=0x020244EC, partyCount=0x020244E9,
                 medicine=0x080FDEA0, ppRecovery=0x080FDEF4, ppUp=0x080FDF10, repel=0x080FE0BC,
                 setupScript=0x08098EF8, pcFn=0x080C7578, specials=0x081DBA64),
    "AXVE0": ref("Pokemon - Ruby Version (USA, Europe)", RS, varGet=0x08069254, varSet=0x08069270,
                 checkBagHasItem=0x080A92D4, removeBagItem=0x080A9538, fieldControlsLocked=0x08065548,
                 getMonData=0x0803CB60, setMonData=0x0803D1FC, itemNoEffect=None, itemEffect=0x0803E18C,
                 addBagItem=0x080A9424, items=0x083C5564, party=0x03004360, partyCount=0x03004350,
                 medicine=0x080C9DB0, ppRecovery=0x080C9E3C, ppUp=0x080C9E58, repel=0x080CA014,
                 setupScript=0x080655B8, pcFn=0x08096100, specials=0x0814B194),
    "AXVE1": ref("Pokemon - Ruby Version (USA, Europe) (Rev 1)", RS, varGet=0x08069274, varSet=0x08069290,
                 checkBagHasItem=0x080A92F4, removeBagItem=0x080A9558, fieldControlsLocked=0x08065568,
                 getMonData=0x0803CB60, setMonData=0x0803D1FC, itemNoEffect=None, itemEffect=0x0803E18C,
                 addBagItem=0x080A9444, items=0x083C5580, party=0x03004360, partyCount=0x03004350,
                 medicine=0x080C9DD0, ppRecovery=0x080C9E5C, ppUp=0x080C9E78, repel=0x080CA034,
                 setupScript=0x080655D8, pcFn=0x08096120, specials=0x0814B1B4),
    "AXPE0": ref("Pokemon - Sapphire Version (USA, Europe)", RS, varGet=0x08069258, varSet=0x08069274,
                 checkBagHasItem=0x080A92D4, removeBagItem=0x080A9538, fieldControlsLocked=0x0806554C,
                 getMonData=0x0803CB60, setMonData=0x0803D1FC, itemNoEffect=None, itemEffect=0x0803E18C,
                 addBagItem=0x080A9424, items=0x083C55BC, party=0x03004360, partyCount=0x03004350,
                 medicine=0x080C9DB0, ppRecovery=0x080C9E3C, ppUp=0x080C9E58, repel=0x080CA014,
                 setupScript=0x080655BC, pcFn=0x08096100, specials=0x0814B194),
    "AXPE1": ref("Pokemon - Sapphire Version (USA, Europe) (Rev 1)", RS, varGet=0x08069278, varSet=0x08069294,
                 checkBagHasItem=0x080A92F4, removeBagItem=0x080A9558, fieldControlsLocked=0x0806556C,
                 getMonData=0x0803CB60, setMonData=0x0803D1FC, itemNoEffect=None, itemEffect=0x0803E18C,
                 addBagItem=0x080A9444, items=0x083C55DC, party=0x03004360, partyCount=0x03004350,
                 medicine=0x080C9DD0, ppRecovery=0x080C9E5C, ppUp=0x080C9E78, repel=0x080CA034,
                 setupScript=0x080655DC, pcFn=0x08096120, specials=0x0814B1B4),
}
# The English ROMs written straight from their ELF (no hand table in FieldItems.kt).
ENGLISH_OUT = ("BPRE0", "AXVE0", "AXPE0")


# ScriptContext2_Enable / _Disable / _IsEnabled back to back (each `ldr; movs; strb; bx lr` / `ldr; ldrb; bx lr` with
# its pool word): fieldControlsLocked is the third. Too short to map by code where the functions around it moved.
CONTROLS_TRIO = re.compile(rb"\x01\x49\x01\x20\x08\x70\x70\x47(.{4})\x01\x49\x00\x20\x08\x70\x70\x47\1\x01\x48\x00\x78\x70\x47\x00\x00\1", re.S)


def short_code(src, rom, a, guess):
    """A short function (the code before its literal pool, up to its pop / bx) found by that code alone, literal loads'
    offsets and BL targets ignored: the hit nearest [guess], or None. For the small ones whose pool words fill the
    48 bytes map_code_masked compares (ReturnToFieldFromFlyMapSelect and the like) in a rebuilt game."""
    parts = []
    for i in range(16):
        h = struct.unpack_from("<H", src.d, (a & ~1) - B + 2 * i)[0]
        if h >> 11 == 0b01001:
            parts.append(b"." + bytes([h >> 8]))
        elif h >> 11 in (0b11110, 0b11111):
            parts.append(b"..")
        else:
            parts.append(re.escape(struct.pack("<H", h)))
        if h & 0xFF00 == 0xBC00 or h & 0xFF87 == 0x4700:   # pop {...} / bx rN
            break
    if len(parts) < 6:
        return None
    hits = [B + m.start() + (a & 1) for m in re.finditer(b"".join(parts), rom.d[:rom.code_end], re.S) if m.start() % 2 == 0]
    return min(hits, key=lambda x: abs(x - guess)) if hits else None


def controls_trio(rom, guess):
    """ScriptContext2_IsEnabled from the trio nearest [guess] (FireRed has a second trio for another byte), or None."""
    hits = [B + m.start() + 24 for m in CONTROLS_TRIO.finditer(rom.d, 0, rom.code_end)]
    return min(hits, key=lambda a: abs(a - guess)) if hits else None


def crc(rom, a):
    o = a - B
    return zlib.crc32(rom.d[o:o + 32])


def port(rom, refs, key):
    """The mapped table for rom (from English refs[key]), or (None, why)."""
    r = REFS[key]
    src = refs[key]
    a = r["a"]
    out, how = {}, {}
    # Functions: exact code first, the rest by masked code around the exact ones' shift.
    for n in FUNCS + USE:
        if a.get(n) is None:
            out[n] = None
            continue
        v = map_code(src, rom, a[n])
        if v is not None:
            out[n], how[n] = v, "code"
    shifts = [out[n] - a[n] for n in out if out[n] is not None]
    shift = int(statistics.median(shifts)) if shifts else 0
    for n in FUNCS + USE:
        if a.get(n) is None or out.get(n) is not None:
            continue
        v = map_code_masked(src, rom, a[n], a[n] + shift, radius=0x8000)
        if v is None and n == "fieldControlsLocked":
            v = controls_trio(rom, a[n] + shift)
        if v is None:
            return None, f"{n} not found"
        out[n], how[n] = v, "masked"
    for n in ("party", "partyCount", "items"):
        v, votes = map_literal(src, rom, a[n])
        if v is None and n == "items":
            # A rebuilt game (Japanese Emerald, Ruby, Sapphire): its pools don't line up; the table by its records.
            v, votes = detect_items(rom, None)[0], "content"
        if v is None:
            return None, f"{n} not found"
        out[n], how[n] = v, f"lit {votes}"
    base, stride, name_len = detect_items(rom, out["items"])
    if base != out["items"]:
        return None, f"gItems {out['items']:#x} isn't the item table (found {base and hex(base)})"
    if rom.u8(base + 86 * stride + name_len + 5) != 100:
        return None, "REPEL's steps aren't 100"
    for n, item in USE_PROBE.items():
        ptr = rom.u32(base + item * stride + name_len + 14) & ~1
        if ptr != out[n]:
            return None, f"{n} {out[n]:#x}, but item {item} runs {ptr:#x}"
    if rom.code[:3] != src.code[:3]:
        return None, "no reference of its game"
    out.update(stride=stride, name_len=name_len, how=how)
    out["pc"] = port_pc(src, rom, r, shift)
    fly_key = key if key in FLY else FLY_FROM.get(key)
    out["fly"] = port_fly(refs[fly_key], rom, fly_key, shift) if fly_key else None
    out["box"] = port_box(src, rom, key, shift) if key in BOX else None
    out["tm"] = port_tm(src, rom, key, shift) if key in TM_REC else None
    out["flash"] = port_flash(src, rom, key, shift) if key in FLASH_REC else None
    fl_key = key if key in FLIGHT_REC else FLIGHT_FROM.get(key)
    out["flight"] = port_flight(refs[fl_key], rom, fl_key, shift) if fl_key and out["fly"] else None
    return out, None


def port_fn(src, rom, a, shift):
    """A function mapped by its code: exact, else masked near the shift, else its short code alone; or None."""
    v = map_code(src, rom, a)
    if v is None:
        v = map_code_masked(src, rom, a, a + shift, radius=0x10000)
    if v is None:
        v = short_code(src, rom, a, a + shift)
    return v


def port_ram(src, rom, a, anchors=()):
    """A RAM global through the literal pools that load it, or None (not EWRAM / IWRAM). [anchors]: (English, mapped)
    functions of the feature: where the pools don't line up, the word at the same place in a mapped function's own pool
    (the first 0x80 bytes after its start) as the English function keeps [a] in."""
    v, _ = map_literal(src, rom, a)
    if v is None:
        want = struct.pack("<I", a)
        for s_fn, d_fn in anchors:
            so, do = (s_fn & ~1) - B, (d_fn & ~1) - B
            k = next((k for k in range(0, 0x80, 4) if src.d[so + k:so + k + 4] == want), None) if so % 4 == do % 4 else None
            if k is not None:
                v = struct.unpack_from("<I", rom.d, do + k)[0]
                break
    return v if v is not None and 0x02000000 <= v < 0x03008000 else None


def port_tm(src, rom, key, shift):
    """The TM screen's addresses mapped from English [key]'s record, or None. gMain.callback2 goes as gMain (+4), the party
    cursor as gPartyMenu (+partyMenuSlotOff), the way the pools load them."""
    r = record(TM_REC[key])
    got = dict(partyMenuSlotOff=r["partyMenuSlotOff"])
    for n in TM_FUNCS:
        got[n] = port_fn(src, rom, r[n], shift)
        if got[n] is None:
            return None
    anchors = [(r[n], got[n]) for n in TM_FUNCS]
    for n in ("specialVarItemId", "itemUseCb", "partyMenu"):
        got[n] = port_ram(src, rom, r[n], anchors)
        if got[n] is None:
            return None
    main = port_ram(src, rom, r["mainCallback2"] - 4, anchors)
    if main is None:
        return None
    got["mainCallback2"] = main + 4
    return got


def port_flash(src, rom, key, shift):
    """FLASH's addresses mapped from English [key]'s record, or None. FireRed / Emerald's cursor goes as gPartyMenu + 9,
    Ruby / Sapphire's is gLastFieldPokeMenuOpened itself."""
    r = record(FLASH_REC[key])
    got = dict(badgeFlag=r["badgeFlag"], litFlag=r["litFlag"])
    for n in ("flagGet", "setUpFieldMoveFlash"):
        got[n] = port_fn(src, rom, r[n], shift)
        if got[n] is None:
            return None
    got["fieldCallbacks"] = [port_fn(src, rom, a, shift) for a in r["fieldCallbacks"]]
    if None in got["fieldCallbacks"]:
        return None
    anchors = [(r["setUpFieldMoveFlash"], got["setUpFieldMoveFlash"])] + list(zip(r["fieldCallbacks"], got["fieldCallbacks"]))
    for n in ("menuFieldCallback", "postMenuFieldCallback", "mapHeader"):
        got[n] = port_ram(src, rom, r[n], anchors)
        if got[n] is None:
            return None
    rs = key[:3] in ("AXV", "AXP")
    slot = port_ram(src, rom, r["partyMenuSlotId"] if rs else r["partyMenuSlotId"] - 9, anchors)
    if slot is None:
        return None
    got["partyMenuSlotId"] = slot if rs else slot + 9
    return got


def port_box(src, rom, key, shift):
    """The BOXES moves' addresses mapped from English [key]'s, or None (the port keeps the tab, without the moves).
    Checked: every function found, gPokemonStoragePtr a word right after a literal-pool IWRAM word (gSaveBlock2Ptr)."""
    f = BOX[key]
    got = {}
    for n in BOX_FUNCS:
        v = map_code(src, rom, f[n])
        if v is None:
            v = map_code_masked(src, rom, f[n], f[n] + shift, radius=0x10000)
        if v is None:
            return None
        got[n] = v
    v, _ = map_literal(src, rom, f["storage"])
    if v is None or not 0x03000000 <= v < 0x03008000:
        return None
    got["storage"] = v
    return got


def port_fly(src, rom, key, shift):
    """FLY's addresses mapped from English [key]'s, or None (the port keeps its other actions). Checked: every
    function found, sMapFlyDestinations' bytes identical to English's (the same towns), gPartyMenu a RAM word."""
    f = FLY.get(key)
    if f is None:
        return None
    funcs = FLY_FUNCS_EM if "toHealLocation" in f else FLY_FUNCS_FR
    got = {}
    for n in funcs:
        v = map_code(src, rom, f[n])
        if v is None:
            v = map_code_masked(src, rom, f[n], f[n] + shift, radius=0x10000)
        if v is None:
            v = short_code(src, rom, f[n], f[n] + shift)
        if v is None:
            return None
        got[n] = v
    for n in ("partyMenu", "saveBlock2Ptr", "slotAddr", "saveBlock2", "mapHeader"):
        if n in f:
            v, _ = map_literal(src, rom, f[n])
            if v is None or not 0x02000000 <= v < 0x03008000:
                return None
            got[n] = v
    size = f["count"] * 3
    want = src.d[f["destinations"] - B:f["destinations"] - B + size]
    v, _ = map_literal(src, rom, f["destinations"])
    if v is None or rom.d[v - B:v - B + size] != want:
        k = rom.d.find(want)
        v = B + k if k >= 0 and rom.d.find(want, k + 1) < 0 else None
    if v is None:
        return None
    got["destinations"] = v
    got["count"] = f["count"]
    return got


def port_pc(src, rom, r, shift):
    """PC anywhere's addresses mapped like the rest, or None (the ROM keeps its other actions): the PC special's entry
    in the mapped gSpecials must be the mapped function."""
    a = r["a"]
    got = {}
    for n in ("setupScript", "pcFn"):
        v = map_code(src, rom, a[n])
        if v is None:
            v = map_code_masked(src, rom, a[n], a[n] + shift, radius=0x8000)
        if v is None:
            return None
        got[n] = v
    idx = r["fam"]["pcSpecial"]
    got["specials"], _ = map_literal(src, rom, a["specials"])
    if got["specials"] is None:
        # FireRed's ScrCmd_special pool doesn't line up with English's: the table whose [idx] is the PC function and
        # which some literal pool loads, if there's exactly one.
        entry = struct.pack("<I", got["pcFn"] | 1)
        tables = [B + o - 4 * idx for o in range(0, len(rom.d) - 3, 4) if rom.d[o:o + 4] == entry]
        tables = [t for t in tables if rom.d.find(struct.pack("<I", t)) >= 0]
        got["specials"] = tables[0] if len(tables) == 1 else None
        if got["specials"] is None:
            # A rebuild with other specials before it: the index too, where a pool loads the table it gives.
            found = [(i, B + o - 4 * i) for o in range(0, len(rom.d) - 3, 4) if rom.d[o:o + 4] == entry
                     for i in range(idx - 8, idx + 9) if rom.d.find(struct.pack("<I", B + o - 4 * i)) >= 0]
            if len(found) == 1:
                idx, got["specials"] = found[0]
    if got["specials"] is None or rom.u32(got["specials"] + 4 * idx) != got["pcFn"] | 1:
        return None
    got["special"] = idx
    return got


def kt(rom, key, m):
    fam = REFS[key]["fam"]
    h = lambda v: f"0x{v:08X}L"
    funcs = [m[n] for n in ("varGet", "varSet", "checkBagHasItem", "removeBagItem", "fieldControlsLocked")]
    party = [m[n] for n in ("getMonData", "setMonData", "itemNoEffect", "itemEffect", "addBagItem") if m[n] is not None]
    masked = sorted(n for n, v in m["how"].items() if v == "masked")
    return f"""    // {os.path.basename(rom.path)} (SHA1 {rom.sha1[:8]}...), from {key}{'; masked: ' + ', '.join(masked) if masked else ''}.
    "{rom.code}{rom.rev}" to FieldItemFns(
        varGet = {h(m['varGet'])}, varSet = {h(m['varSet'])}, checkBagHasItem = {h(m['checkBagHasItem'])}, removeBagItem = {h(m['removeBagItem'])},
        fieldControlsLocked = {h(m['fieldControlsLocked'])}, repelVar = 0x{fam['repelVar']:04X}, items = {h(m['items'])},
        codeCrcs = listOf({', '.join(f'0x{crc(rom, v):08X}L' for v in funcs)}),
        itemNameLen = {m['name_len']},
        party = PartyItemFns(
            getMonData = {h(m['getMonData'])}, setMonData = {h(m['setMonData'])}, itemNoEffect = {h(m['itemNoEffect']) if m['itemNoEffect'] else 'null'},
            itemEffect = {h(m['itemEffect'])}, addBagItem = {h(m['addBagItem'])}, party = {h(m['party'])}, partyCount = {h(m['partyCount'])},
            scratch = 0x0203FFF0L, itemCount = {fam['itemCount']}, unholdablePockets = setOf({', '.join(map(str, fam['pockets']))}),
            codeCrcs = listOf({', '.join(f'0x{crc(rom, v):08X}L' for v in party)}),
            useFuncs = setOf({', '.join(h(m[n]) for n in USE)}),
        ),{pc_kt(rom, fam, m.get('pc'))}{fly_kt(rom, m.get('fly'))}{flight_kt(rom, m.get('flight'))}
    ),"""


# FREE FLIGHT (Flight.kt): the English records in gamedata/gen3/records/ (pret's symbols for each build), mapped into the
# other languages like FLY's: functions by code, RAM globals through the literal pools, gFieldEffectObjectTemplate_Bird,
# gMapGroups and sHealLocations through theirs, each checked against the ROM. English FireRed rev 0 keeps its own record
# (the hacks on its code get it only where Flight.kt's flightAllowed lets them); Ruby / Sapphire rev 0 map from rev 1.
FLIGHT_REC = {"BPRE0": "FLIGHT_FIRERED_REV0", "BPRE1": "FLIGHT_FIRERED_REV1", "BPGE0": "FLIGHT_LEAFGREEN_REV0",
              "BPGE1": "FLIGHT_LEAFGREEN_REV1", "BPEE0": "FLIGHT_EMERALD", "AXVE1": "FLIGHT_RUBY", "AXPE1": "FLIGHT_SAPPHIRE"}
FLIGHT_FROM = {"AXVE0": "AXVE1", "AXPE0": "AXPE1"}
FLIGHT_FUNCS = ("lockControls", "unlockControls", "createSprite", "destroySprite", "startSpriteAnim", "behaviorAt",
                "collisionAt", "elevationAt", "objectAt", "playSe", "drawMetatileAt")   # FlightFns.functions, then the rest
FLIGHT_MORE_FUNCS = ("riderTemplate", "riderGfx", "drawView", "cb2Overworld", "spriteDummy")
FLIGHT_RAM = ("objectEvents", "playerAvatar", "sprites", "vmap", "connectionFlags", "saveBlock1Ptr", "saveBlock2Ptr", "main",
              "lockByte", "paletteFade", "spriteOffset", "spriteOffsetY", "mapHeader")
FLIGHT_ROM = ("birdTemplate", "mapGroups", "healLocations")


def why(rom, what):
    """port_flight's refusal, reported."""
    print(f"  {rom.code}{rom.rev} FLIGHT: {what} not found / didn't check")
    return None


def port_table_entry(src, rom, a):
    """[a] reached through a table of pointers (gFieldEffectObjectTemplatePointers[FLDEFFOBJ_BIRD]): the table found in
    English as the aligned word run holding [a] whose start a literal pool loads, mapped, and read at the same index."""
    want = struct.pack("<I", a)
    k = src.d.find(want)
    while k >= 0:
        if k % 4 == 0:
            for back in range(0, 64):
                base = k - 4 * back
                w = struct.unpack_from("<I", src.d, base)[0] if base >= 0 else 1
                if w and not B <= w < B + len(src.d):   # a table of pointers (some entries NULL)
                    break
                if struct.pack("<I", B + base) in src.words():
                    t, _ = map_literal(src, rom, B + base)
                    if t is not None and B <= t < B + len(rom.d):
                        v = rom.u32(t + 4 * back)
                        if B <= v < B + len(rom.d):
                            return v
        k = src.d.find(want, k + 1)
    return None


def port_flight(src, rom, key, shift):
    """FREE FLIGHT's addresses mapped from English [key]'s record, or None. Checked: every function and global found, the
    bird template's callback the mapped SpriteCallbackDummy, every map group's headers in the ROM, sHealLocations' entries
    the same bytes as English's."""
    rec_name = FLIGHT_REC.get(key)
    if rec_name is None:
        return None
    r = record(rec_name)
    got = {}
    anchors = []
    for n in FLIGHT_FUNCS + FLIGHT_MORE_FUNCS:
        a = r.get(n, 0)
        if not a:
            continue
        v = port_fn(src, rom, a, shift)
        if v is None:
            return why(rom, n)
        got[n] = v
        anchors.append((a, v))
    # MapGridGetCollisionAt and MapGridGetElevationAt start with the same 32 bytes (one reads the square's bits 10-11, the
    # other 12-15): mapped by code, both land on one. The collision one sits at English's distance from the elevation one,
    # and its own code (up to its literal pool) must be English's there.
    if True:   # always: the code alone can't tell them apart
        x, dist = got["elevationAt"], r["collisionAt"] - r["elevationAt"]
        # They first differ past their literal pool (the map layout's RAM address, moved in a rebuild) and BLs: compared
        # over 0x48 bytes with RAM words and BL halves left out.
        def same(a, b):
            for k in range(0, 0x48, 2):
                h = struct.unpack_from("<H", src.d, b - B + k)[0]
                w = struct.unpack_from("<I", src.d, b - B + (k & ~3))[0]
                if 0x02000000 <= w < 0x03008000 or h >> 11 in (0b11110, 0b11111):
                    continue
                if rom.d[a - B + k:a - B + k + 2] != src.d[b - B + k:b - B + k + 2]:
                    return False
            return True
        pair = next(((e, e + dist) for e in (x, x - dist) if same(e, r["elevationAt"]) and same(e + dist, r["collisionAt"])), None)
        if pair is None:
            return why(rom, "collisionAt / elevationAt (apart by English's distance)")
        got["elevationAt"], got["collisionAt"] = pair
    for n in FLIGHT_RAM:
        a = r.get(n, 0)
        if not a:
            continue
        v = port_ram(src, rom, a, anchors)
        if v is None:
            return why(rom, n)
        got[n] = v
    for n in FLIGHT_ROM:
        v, _ = map_literal(src, rom, r[n])
        if v is None:
            v = port_table_entry(src, rom, r[n])
        if v is None or not B <= v < B + len(rom.d):
            return why(rom, n)
        got[n] = v
    # The bird's template: its callback is SpriteCallbackDummy, as English's (mapped by code)
    cb = port_fn(src, rom, src.u32(r["birdTemplate"] + 0x14), shift)
    if cb is None or rom.u32(got["birdTemplate"] + 0x14) != cb:
        return why(rom, "the bird template's callback")
    for g, size in enumerate(r["mapGroupSizes"]):
        lst = rom.u32(got["mapGroups"] + 4 * g)
        if not B <= lst < B + len(rom.d):
            return why(rom, f"map group {g}")
        for m in range(size):
            hdr = rom.u32(lst + 4 * m)
            if not B <= hdr < B + len(rom.d) or not B <= rom.u32(hdr) < B + len(rom.d):
                return why(rom, f"map {g}.{m}")
    size = r["healLocationSize"] * r["healLocationCount"]
    if rom.d[got["healLocations"] - B:got["healLocations"] - B + size] != src.d[r["healLocations"] - B:r["healLocations"] - B + size]:
        return why(rom, "sHealLocations' entries")
    got["rec"] = rec_name
    return got


def flight_kt(rom, fl):
    if not fl:
        return ""
    if fl.get("own"):
        return f"\n        flight = {fl['rec']},"
    h = lambda v: f"0x{v:08X}L"
    r = record(fl["rec"])
    names = [n for n in FLIGHT_FUNCS + FLIGHT_MORE_FUNCS + FLIGHT_RAM + FLIGHT_ROM if n in fl]
    fields = ", ".join(f"{n} = {h(fl[n])}" for n in names)
    funcs = [fl[n] for n in FLIGHT_FUNCS] + [fl[n] for n in ("riderTemplate", "riderGfx", "drawView") if n in fl]
    crcs = ", ".join(f"0x{crc(rom, v):08X}L" for v in funcs)
    return f"\n        flight = {fl['rec']}.copy(\n            {fields},\n            codeCrcs = listOf({crcs}),\n        ),"


def fly_kt(rom, fly):
    if not fly:
        return ""
    h = lambda v: f"0x{v:08X}L"
    em = "toHealLocation" in fly
    rs = "slotAddr" in fly
    funcs = FLY_FUNCS_EM if em else FLY_FUNCS_FR
    crcs = ", ".join(f"0x{crc(rom, fly[n]):08X}L" for n in funcs)
    common = (f"flagGet = {h(fly['flagGet'])}, setUpFieldMoveFly = {h(fly['setUpFieldMoveFly'])}, "
              f"getMapsecType = {h(fly['getMapsecType'])},\n            fadeScreen = {h(fly['fadeScreen'])}, "
              f"lockControls = {h(fly['lockControls'])}, unlockControls = {h(fly['unlockControls'])}, "
              f"partyMenuSlotId = {h(fly['slotAddr'] if rs else fly['partyMenu'] + 9)}, destinations = {h(fly['destinations'])},\n            ")
    if rs:
        return (f"\n        fly = FLY_RUBY.copy(\n            {common}mapHeader = {h(fly['mapHeader'])}, codeCrcs = listOf({crcs}),\n"
                f"            healWarp = FLY_RUBY.healWarp!!.copy(toHealLocation = {h(fly['toHealLocation'])}, "
                f"toMapWarp = {h(fly['toMapWarp'])}, returnToField = {h(fly['returnToField'])}, "
                f"saveBlock2Ptr = {h(fly['saveBlock2'])}),\n        ),")
    if em:
        return (f"\n        fly = FLY_EMERALD.copy(\n            {common}codeCrcs = listOf({crcs}),\n"
                f"            healWarp = FLY_EMERALD.healWarp!!.copy(toHealLocation = {h(fly['toHealLocation'])}, "
                f"toMapWarp = {h(fly['toMapWarp'])}, returnToField = {h(fly['returnToField'])}, "
                f"saveBlock2Ptr = {h(fly['saveBlock2Ptr'])}),\n        ),")
    return (f"\n        fly = FLY_FIRERED_REV1.copy(\n            {common}setFlyWarpDestination = {h(fly['setFlyWarpDestination'])},\n"
            f"            codeCrcs = listOf({crcs}),\n        ),")

def tm_kt(rom, tm):
    h = lambda v: f"0x{v:08X}L"
    fns = ", ".join(f"{n} = {h(tm[n])}" for n in ("itemUseCbTmhm", "showPartyMenuForItemUse", "initPartyMenu", "setMainCallback2",
                                                 "returnToField", "cleanupOverworld", "fadeScreen", "lockControls", "unlockControls"))
    return (f'    // {os.path.basename(rom.path)}.\n    "{rom.code}{rom.rev}" to TmFns(\n'
            f"        specialVarItemId = {h(tm['specialVarItemId'])}, itemUseCb = {h(tm['itemUseCb'])}, partyMenu = {h(tm['partyMenu'])}, "
            f"partyMenuSlotOff = {tm['partyMenuSlotOff']}, mainCallback2 = {h(tm['mainCallback2'])},\n        {fns},\n"
            f"        codeCrcs = listOf({', '.join(f'0x{crc(rom, tm[n]):08X}L' for n in TM_FUNCS)}),\n    ),")


def flash_kt(rom, fl):
    h = lambda v: f"0x{v:08X}L"
    fns = [fl["flagGet"], fl["setUpFieldMoveFlash"]] + fl["fieldCallbacks"]
    return (f'    // {os.path.basename(rom.path)}.\n    "{rom.code}{rom.rev}" to FlashFns(\n'
            f"        flagGet = {h(fl['flagGet'])}, badgeFlag = 0x{fl['badgeFlag']:X}, litFlag = 0x{fl['litFlag']:X}, "
            f"setUpFieldMoveFlash = {h(fl['setUpFieldMoveFlash'])},\n"
            f"        menuFieldCallback = {h(fl['menuFieldCallback'])}, postMenuFieldCallback = {h(fl['postMenuFieldCallback'])}, "
            f"fieldCallbacks = listOf({', '.join(h(v) for v in fl['fieldCallbacks'])}),\n"
            f"        partyMenuSlotId = {h(fl['partyMenuSlotId'])}, mapHeader = {h(fl['mapHeader'])},\n"
            f"        codeCrcs = listOf({', '.join(f'0x{crc(rom, v):08X}L' for v in fns)}),\n    ),")


def box_kt(rom, box):
    h = lambda v: f"0x{v:08X}L"
    return (f'    // {os.path.basename(rom.path)}.\n    "{rom.code}{rom.rev}" to BoxSwapFns(\n'
            f"        boxMonToMon = {h(box['boxMonToMon'])}, boxMonRestorePP = {h(box['boxMonRestorePP'])}, "
            f"getBoxMonData = {h(box['getBoxMonData'])}, storagePtr = {h(box['storage'])},\n"
            f"        codeCrcs = listOf({', '.join(f'0x{crc(rom, box[n]):08X}L' for n in BOX_FUNCS)}),\n    ),")


def pc_kt(rom, fam, pc):
    if not pc:
        return ""
    h = lambda v: f"0x{v:08X}L"
    return (f"\n        pc = PcFns({h(pc['setupScript'])}, {pc.get('special', fam['pcSpecial'])}, {h(pc['specials'])}, {h(pc['pcFn'] | 1)}, "
            f"listOf(0x{crc(rom, pc['setupScript']):08X}L, 0x{crc(rom, pc['pcFn']):08X}L)),")


def main():
    args = [x for x in sys.argv[1:] if not x.startswith("--")]
    if not args:
        sys.exit(__doc__)
    paths = [os.path.join(dp, f) for dp, _, fs in os.walk(args[0]) for f in fs if f.endswith(".gba")]
    by_stem = {os.path.splitext(os.path.basename(p))[0]: p for p in paths}
    refs = {}
    for k, r in REFS.items():
        if r["stem"] not in by_stem:
            sys.exit(f"English reference missing: {r['stem']}.gba")
        refs[k] = Rom(by_stem[r["stem"]])
        if f"{refs[k].code}{refs[k].rev}" != k:
            sys.exit(f"{r['stem']} is {refs[k].code}{refs[k].rev}, not {k}")
    # English FireRed rev 0's box functions, from rev 1's (the ports of rev 0 map theirs from these).
    for key, fr in BOX_FROM.items():
        shift = int(statistics.median(REFS[key]["a"][n] - REFS[fr]["a"][n] for n in FUNCS if REFS[key]["a"].get(n)))
        box = port_box(refs[fr], refs[key], fr, shift)
        if box is None:
            sys.exit(f"{key}: the box functions didn't map from {fr}")
        BOX[key] = box
    blocks, box_blocks, tm_blocks, flash_blocks, done, js = [], [], [], [], set(), {}
    for p in sorted(paths):
        name = os.path.basename(p)
        if any(x in name for x in ("Pirate", "Debug", "Pokemon Box")):
            continue
        rom = Rom(p)
        key = f"{rom.code}{rom.rev}"
        if rom.code[:3] not in ("BPR", "BPG", "BPE", "AXV", "AXP") or key in done:
            continue
        if rom.lang == "E" and key not in ENGLISH_OUT:
            continue
        if rom.lang == "E":
            m = dict(REFS[key]["a"], how={}, stride=44, name_len=14)
            m["pc"] = {n: m[n] for n in ("setupScript", "pcFn", "specials")}
            if key in FLY_FROM:
                m["fly"] = port_fly(refs[FLY_FROM[key]], rom, FLY_FROM[key], 0)
            m["box"] = BOX.get(key)
            if key in FLIGHT_REC:
                m["flight"] = dict(rec=FLIGHT_REC[key], own=True)
            elif key in FLIGHT_FROM:
                m["flight"] = port_flight(refs[FLIGHT_FROM[key]], rom, FLIGHT_FROM[key], 0) if m.get("fly") else None
            ref_key = key
        else:
            cands = {k: v for k, v in refs.items() if k[:3] == rom.code[:3]}
            if not cands:
                print(f"{key:6} {name[:48]:48} no English reference of its game")
                continue
            ref_key = nearest_english(rom, cands)
            m, why = port(rom, refs, ref_key)
            if m is None:
                print(f"{key:6} {name[:48]:48} FAILED from {ref_key}: {why}")
                continue
        done.add(key)
        blocks.append(kt(rom, ref_key, m))
        if m.get("box"):
            box_blocks.append(box_kt(rom, m["box"]))
        if m.get("tm"):
            tm_blocks.append(tm_kt(rom, m["tm"]))
        if m.get("flash"):
            flash_blocks.append(flash_kt(rom, m["flash"]))
        js[key] = dict(path=os.path.abspath(p), ref=ref_key, family=rom.code[:3], **{k: v for k, v in m.items() if k != "how"})
        masked = [n for n, v in m["how"].items() if v == "masked"]
        print(f"{key:6} {name[:48]:48} from {ref_key}  items {m['items']:#x} x{m['name_len'] + 30}"
              f"  party {m['party']:#x}{'  masked ' + ','.join(masked) if masked else ''}{'' if m.get('pc') else '  NO PC'}{'' if m.get('fly') else '  NO FLY'}{'' if m.get('flight') else '  NO FLIGHT'}"
              f"{'' if m.get('box') else '  NO BOX MOVES'}{'' if m.get('tm') or rom.code[:3] in ('AXV', 'AXP') or rom.lang == 'E' else '  NO TM'}"
              f"{'' if m.get('flash') or rom.lang == 'E' else '  NO FLASH'}")
    # The hand-written English tables' FLY (FieldItems.kt), checked the same way: printed for FLY_RUBY's file.
    for key in ("AXVE1", "AXPE1"):
        if key in refs:
            f = port_fly(refs["AXVE1"], refs[key], "AXVE1", 0)
            print(f"{key} FLY: {fly_kt(refs[key], f) if f else 'NOT FOUND'}")
    out_json = os.path.join(HERE, "..", "build", "ports", "field_items.json")
    os.makedirs(os.path.dirname(out_json), exist_ok=True)
    import json
    with open(out_json, "w") as f:
        json.dump(js, f, indent=1)
    if "--no-write" in sys.argv:
        return
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("""package com.pokedaisy.app.companion.data

// Generated by scripts/port_field_items.py from the user's ROMs - don't edit by hand.
// USE / GIVE / TAKE's tables (FieldItems.kt), with PC anywhere's (Pc.kt) and FLY's (Fly.kt), for the other-language releases and English
// FireRed / Ruby / Sapphire rev 0, by game code + revision; each checked headless. Then the BOXES moves', the TM screen's and FLASH's.

val FIELD_ITEMS_PORTS: Map<String, FieldItemFns> = mapOf(
""" + "\n".join(blocks) + """
)

/** The BOXES tab's moves (BoxSwap.kt) for the same games, by the same keys (scripts/verify_box_swap.py checks them). */
val BOX_SWAP_PORTS: Map<String, BoxSwapFns> = mapOf(
""" + "\n".join(box_blocks) + """
)

/** USE on a TM / HM (TmTeach.kt) for the same games, by the same keys (scripts/verify_tm_flash.py checks them). */
val TM_PORTS: Map<String, TmFns> = mapOf(
""" + "\n".join(tm_blocks) + """
)

/** FLASH from the QUICK MENU (Flash.kt) for the same games, by the same keys (scripts/verify_tm_flash.py checks them). */
val FLASH_PORTS: Map<String, FlashFns> = mapOf(
""" + "\n".join(flash_blocks) + "\n)\n")
    print("wrote", os.path.relpath(OUT), len(blocks), "tables")


if __name__ == "__main__":
    main()
