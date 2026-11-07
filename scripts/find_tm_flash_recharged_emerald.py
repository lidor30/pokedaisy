#!/usr/bin/env python3
"""Finds the TM screen's (TmTeach.kt) and FLASH's (Flash.kt) functions in Pokémon Recharged Emerald and writes their records.

    scripts/find_tm_flash_recharged_emerald.py <v2.2.5.gba> <v2.2.8.gba> <Rebalanced v2.2.8.gba> [--no-write]

Like find_field_items_recharged_emerald.py (whose ROM helpers this uses): no source and no symbols, so every function is
followed from what the ROM itself points at, through the game's own code that uses it. Found first by watching the game
headless (mgba_dump's watch8 on gMain.callback2 while the START menu opens and closes the bag), then written down here:

  * The TM's fieldUseFunc (gItems[289], ItemUseOutOfBattle_TMHM): gSpecialVar_ItemId, then its task chain:
    BootUpSoundTMHM -> Task_ShowTMHMContainedMessage -> UseTMHMYesNo -> sUseTMHMYesNo[0] = UseTMHM, which stores
    ItemUseCB_TMHM into gItemUseCB and calls SetUpItemUseCallback, whose sItemUseCallbacks[0] is
    CB2_ShowPartyMenuForItemUse. Recharged's has a battle branch of its own (gMain.inBattle) but the field call is
    retail's: InitPartyMenu(..., exitCallback), which keeps exitCallback at gPartyMenu + 0 and ends in
    SetMainCallback2(CB2_InitPartyMenu). gMain from SetMainCallback2's pool (callback2 at +4).
  * CB2_ReturnToField: gcc inlined IsOverworldLinkActive (gMain.callback1 == CB1_OverworldLink), then
    FieldClearVBlankHBlankCallbacks and SetMainCallback2(CB2_ReturnToFieldLocal) - its code's shape, found once.
  * CleanupOverworldWindowsAndTilemaps: two calls and three TRY_FREE_AND_SET_NULLs (the START menu's BAG callback runs it).
  * FadeScreen: its switch on the mode (`cmp r0, #3; bhi`) right after its prologue, as in all three builds.
  * Lock / UnlockPlayerFieldControls: the script.c trio (field_controls_locked).
  * FLASH: SetUpFieldMove_Flash is the field-move table's entry 1 (CUT, FLASH, ... as retail): ShouldDoBrailleRegisteelEffect
    first (SetUpPuzzleEffectRegisteel), else gMapHeader.cave and FlagGet(FLAG_SYS_USE_FLASH 0x888) (FieldCallback_Flash),
    both through gFieldCallback2 / gPostMenuFieldCallback. CursorCb_FieldMove asks FlagGet(0x867 + fieldMove) for the field
    moves up to 7 (retail's rule, FLAG_BADGE01_GET 0x867): FLASH's badge is 0x868.

Recharged's MON_DATA ids sit 9 past vanilla's from SPECIES on (find_field_items_recharged_emerald.py's shift): FLASH's
MOVE1 / IS_EGG are 22 / 54. Each record is checked against the ROM here, then headless (scripts/verify_tm_flash.py), and
written to gamedata/gen3/records/recharged_emerald.toml.
"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from find_field_items_recharged_emerald import (B, BUILDS, FIELD_USE_OFF, ITEM_SIZE, PARTY, Rom, check,  # noqa: E402
                                                field_controls_locked, medicine)

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "gamedata", "gen3", "records", "recharged_emerald.toml")
ITEM_TM01, FLAG_SYS_USE_FLASH, FLAG_BADGE01_GET, FIELD_MOVE_FLASH = 289, 0x888, 0x867, 1
MON_DATA_MOVE1, MON_DATA_IS_EGG = 13, 45
TM_ORDER = ("itemUseCbTmhm", "showPartyMenuForItemUse", "initPartyMenu", "setMainCallback2", "returnToField",
            "cleanupOverworld", "fadeScreen", "lockControls", "unlockControls")


def is_rom(v):
    return B <= v < B + 0x02000000


def thumb(v):
    return v & 1 and is_rom(v)


def ewram(v):
    return 0x02000000 <= v < 0x02040000


def iwram(v):
    return 0x03000000 <= v < 0x03008000


def walk(rom, fn, limit):
    return list(rom.walk(fn, limit))


def pools(rom, fn, limit):
    return [p for _, _, _, p in walk(rom, fn, limit) if p is not None]


def bls(rom, fn, limit):
    return [t for _, _, t, _ in walk(rom, fn, limit) if t is not None]


def halfwords(rom, fn, limit):
    return [h for _, h, _, _ in walk(rom, fn, limit)]


def find_shape(rom, shape, end=0x01000000):
    """Every Thumb address whose halfwords match [shape] ((value, mask) pairs; a BL is two halfwords masked out)."""
    d, hits = rom.d, []
    first, fmask = shape[0]
    for o in range(0, min(len(d), end) - 2 * len(shape), 2):
        if struct.unpack_from("<H", d, o)[0] & fmask != first:
            continue
        if all(struct.unpack_from("<H", d, o + 2 * k)[0] & m == v for k, (v, m) in enumerate(shape)):
            hits.append(B + o)
    return hits


ANY = (0, 0)
BL = [(0xF000, 0xF800), (0xF800, 0xF800)]


def tm(rom, items, smc2):
    use = rom.u32(items + ITEM_TM01 * ITEM_SIZE + FIELD_USE_OFF) & ~1
    p = rom.pools(use, 0x40)
    special_var = next(v for v in p if ewram(v))
    boot_up = next(v for v in p if thumb(v))
    contained = next(v for v in rom.pools(boot_up, 0x30) if thumb(v))
    yes_no = next(v for v in pools(rom, contained, 0x5C) if thumb(v))
    use_tmhm = rom.u32(next(v for v in rom.pools(yes_no, 0x14) if is_rom(v) and not v & 1))
    check(thumb(use_tmhm), "sUseTMHMYesNo[0]")
    up = rom.pools(use_tmhm, 0x14)
    item_use_cb = next(v for v in up if iwram(v))
    tmhm = next(v for v in up if thumb(v))
    set_up_cb = rom.bls(use_tmhm, 0x14)[-1]
    tables = [v for v in pools(rom, set_up_cb, 0x5C) if is_rom(v) and not v & 1]
    show = next(rom.u32(t) for t in tables if thumb(rom.u32(t)))
    sp = pools(rom, show, 0xC4)
    main = next(v for v in sp if iwram(v))
    check(rom.pools(smc2, 0x10) == [main], "SetMainCallback2 writes gMain")
    init = bls(rom, show, 0xC4)
    init_party = next(t for t in init if smc2 in bls(rom, t, 0xFC) and any(ewram(v) for v in pools(rom, t, 0xFC)))
    # gPartyMenu: a word both use (CB2_ShowPartyMenuForItemUse writes slotId, +9; InitPartyMenu its exitCallback, +0),
    # not gPlayerParty.
    shared = [v for v in sp if ewram(v) and v in pools(rom, init_party, 0xFC) and v != special_var]
    w = walk(rom, init_party, 0xFC)
    cb2_init = None
    for i, (_, _, t, _) in enumerate(w):
        if t == smc2:
            cb2_init = next((p for _, _, _, p in reversed(w[:i]) if p is not None), None)
            break
    check(thumb(cb2_init or 0), "CB2_InitPartyMenu")
    return dict(specialVarItemId=special_var, itemUseCb=item_use_cb, itemUseCbTmhm=tmhm & ~1, showPartyMenuForItemUse=show & ~1,
                partyMenuCandidates=shared, initPartyMenu=cb2_init & ~1, mainCallback2=main + 4, setMainCallback2=smc2,
                initPartyMenuFn=init_party)


def return_to_field(rom, main, smc2):
    # ldr r2, =gMain; ldr r3, =CB1_OverworldLink; ldr r2, [r2]; push {lr}; cmp r2, r3; beq; bl; ldr r0, =local; bl SMC2
    hits = find_shape(rom, [(0x4A00, 0xFF00), (0x4B00, 0xFF00), (0x6812, 0xFFFF), (0xB500, 0xFFFF), (0x429A, 0xFFFF),
                            (0xD000, 0xFF00)] + BL + [(0x4800, 0xFF00)] + BL)
    hits = [h for h in hits if rom.pools(h, 0x24)[:1] == [main] and smc2 in rom.bls(h, 0x24)]
    check(len(hits) == 1, f"one CB2_ReturnToField ({len(hits)})")
    return hits[0]


def cleanup_overworld(rom):
    free = [(0x4C00, 0xFF00), (0x6820, 0xFFFF), (0x2800, 0xFFFF), (0xD000, 0xFF00)] + BL + [(0x2300, 0xFFFF), (0x6023, 0xFFFF)]
    hits = find_shape(rom, [(0xB510, 0xFFFF)] + BL + BL + free * 3 + [(0xBC10, 0xFFFF), (0xBC01, 0xFFFF), (0x4700, 0xFFFF)])
    check(len(hits) == 1, f"one CleanupOverworldWindowsAndTilemaps ({len(hits)})")
    return hits[0]


def fade_screen(rom):
    # push {r4-r7, lr}; lsls r0, #24; lsls r1, #24; sub sp, #16; lsrs r0, #24; asrs rN, r1, #24; cmp r0, #3; bhi
    hits = find_shape(rom, [(0xB5F0, 0xFFFF), (0x0600, 0xFFFF), (0x0609, 0xFFFF), (0xB084, 0xFFFF), (0x0E00, 0xFFFF),
                            (0x1608, 0xFFF8), (0x2803, 0xFFFF), (0xD800, 0xFF00)])
    check(len(hits) == 1, f"one FadeScreen ({len(hits)})")
    return hits[0]


def flash(rom, gfc2):
    """SetUpFieldMove_Flash from the field-move table, FlagGet, the callbacks, gMapHeader, the badge rule."""
    found = []
    for o in range(0, min(len(rom.d), 0x01000000) - 4, 4):
        v = struct.unpack_from("<I", rom.d, o)[0]
        if v != FLAG_SYS_USE_FLASH:
            continue
        # the function whose pool this is: back to its push, then its code holds `ldrb r3, [r3, #0x15]; cmp r3, #1`
        for k in range(4, 0x100, 2):
            a = B + o - k
            if rom.u16(a) & 0xFF00 == 0xB500:
                hw = halfwords(rom, a, k)
                if 0x7D5B in hw and 0x2B01 in hw and gfc2 in pools(rom, a, k):
                    found.append(a)
                break
    check(len(found) == 1, f"one SetUpFieldMove_Flash ({len(found)})")
    fn = found[0]
    w = walk(rom, fn, 0x4C)
    p = [x for _, _, _, x in w if x is not None]
    flag_get = next(t for i, (_, _, t, _) in enumerate(w) if t is not None and FLAG_SYS_USE_FLASH in
                    [x for _, _, _, x in w[max(0, i - 3):i] if x is not None])
    post = next(v for v in p if ewram(v) and p.count(v) == 2)
    cbs = [v for v in p if thumb(v) and p.count(v) == 1]
    check(len(cbs) == 2, "SetUpPuzzleEffectRegisteel and FieldCallback_Flash")
    # gMapHeader: the word loaded into r3 right before `ldrb r3, [r3, #0x15]` (its cave byte)
    cave = next(i for i, (_, h, _, _) in enumerate(w) if h == 0x7D5B)
    map_header = next(x for _, h, _, x in reversed(w[:cave]) if x is not None and (h >> 8) & 7 == 3)
    check(ewram(map_header), "gMapHeader")
    # the field-move table: entry 1 (FLASH) points at it, entry 0 at another field move; a pool word holds its start
    table = [B + o - 8 for o in range(0, len(rom.d) - 3, 4) if struct.unpack_from("<I", rom.d, o)[0] == fn | 1]
    table = [t for t in table if thumb(rom.u32(t)) and rom.d.find(struct.pack("<I", t)) >= 0]
    check(len(table) == 1, "the field-move table, FLASH second")
    # CursorCb_FieldMove's badge check: ldr r3, =FLAG_BADGE01_GET; adds r0, r4, r3; bl FlagGet
    badge = [h for h in find_shape(rom, [(0x4B00, 0xFF00), (0x18E0, 0xFFFF)] + BL)
             if rom.pools(h, 4) == [FLAG_BADGE01_GET] and rom.bls(h + 4, 4) == [flag_get]]
    check(len(badge) == 1, f"CursorCb_FieldMove's FlagGet(FLAG_BADGE01_GET + fieldMove) ({len(badge)})")
    return dict(flagGet=flag_get & ~1, setUpFieldMoveFlash=fn, menuFieldCallback=gfc2, postMenuFieldCallback=post,
                fieldCallbacks=[cbs[1] & ~1, cbs[0] & ~1], mapHeader=map_header, badgeFlag=FLAG_BADGE01_GET + FIELD_MOVE_FLASH,
                litFlag=FLAG_SYS_USE_FLASH)


def find(path, build):
    rom = Rom(path)
    items = rom.u32(B + 0x100 + 0xC8)
    party, _ = PARTY[build]
    # SetMainCallback2: `movs r2, #0x87; movs r1, #0; ldr r3, =gMain; lsls r2, #3; str r0, [r3, #4]; strb r1, [r3, r2]; bx lr`
    smc2 = find_shape(rom, [(0x2287, 0xFFFF), (0x2100, 0xFFFF), (0x4B00, 0xFF00), (0x00D2, 0xFFFF), (0x6058, 0xFFFF),
                            (0x5499, 0xFFFF), (0x4770, 0xFFFF)], end=0x1000)
    check(len(smc2) == 1, "one SetMainCallback2")
    t = tm(rom, items, smc2[0])
    cands = [v for v in t.pop("partyMenuCandidates") if v != party]
    check(len(cands) == 1, f"one gPartyMenu ({[hex(v) for v in cands]})")
    t["partyMenu"] = cands[0]
    t.pop("initPartyMenuFn")
    fcl = field_controls_locked(rom)
    t.update(returnToField=return_to_field(rom, t["mainCallback2"] - 4, smc2[0]), cleanupOverworld=cleanup_overworld(rom),
             fadeScreen=fade_screen(rom), lockControls=fcl - 24, unlockControls=fcl - 12)
    check(rom.u16(t["lockControls"]) == 0x2201 and rom.u16(t["unlockControls"]) == 0x2200, "the lock trio")
    # gFieldCallback2: CB2_ReturnToFieldWithOpenMenu stores FieldCallback_ReturnToFieldOpenMenu there; SetUpFieldMove_Flash
    # stores FieldCallback_PrepareFadeInFromMenu. The IWRAM word both a bag exit and FLASH store into: the one FLASH loads.
    shift = medicine(rom, items, party)["shift"]
    f = None
    # gFieldCallback2: the IWRAM word SetUpFieldMove_Flash loads (every function with a 0x888 pool word is tried)
    for o in range(0, min(len(rom.d), 0x01000000) - 4, 4):
        if struct.unpack_from("<I", rom.d, o)[0] != FLAG_SYS_USE_FLASH:
            continue
        for k in range(4, 0x100, 2):
            a = B + o - k
            if rom.u16(a) & 0xFF00 == 0xB500:
                ws = [v for v in pools(rom, a, k) if iwram(v)]
                if ws and 0x7D5B in halfwords(rom, a, k):
                    f = flash(rom, ws[0])
                break
        if f:
            break
    check(f is not None, "FLASH")
    f.update(monMoveField=MON_DATA_MOVE1 + shift, monEggField=MON_DATA_IS_EGG + shift)
    t["crcs"] = [rom.crc32(t[k]) for k in TM_ORDER]
    f["crcs"] = [rom.crc32(v) for v in [f["flagGet"], f["setUpFieldMoveFlash"]] + f["fieldCallbacks"]]
    return t, f


def toml(build, path, t, f):
    h = lambda v: f"0x{v:08X}"
    name = os.path.basename(path)
    return f"""
# {name}: found by scripts/find_tm_flash_recharged_emerald.py.
[record.TM_RECHARGED_EMERALD_{build}]
schema = 'gen3_tm'
specialVarItemId = {h(t['specialVarItemId'])}
itemUseCb = {h(t['itemUseCb'])}
itemUseCbTmhm = {h(t['itemUseCbTmhm'])}
showPartyMenuForItemUse = {h(t['showPartyMenuForItemUse'])}
partyMenu = {h(t['partyMenu'])}
partyMenuSlotOff = 9
initPartyMenu = {h(t['initPartyMenu'])}
mainCallback2 = {h(t['mainCallback2'])}
setMainCallback2 = {h(t['setMainCallback2'])}
returnToField = {h(t['returnToField'])}
cleanupOverworld = {h(t['cleanupOverworld'])}
fadeScreen = {h(t['fadeScreen'])}
lockControls = {h(t['lockControls'])}
unlockControls = {h(t['unlockControls'])}
codeCrcs = [{', '.join(h(c) for c in t['crcs'])}]

[record.FLASH_RECHARGED_EMERALD_{build}]
schema = 'gen3_flash'
flagGet = {h(f['flagGet'])}
badgeFlag = 0x{f['badgeFlag']:X} # FLAG_BADGE02_GET
litFlag = 0x{f['litFlag']:X} # FLAG_SYS_USE_FLASH
setUpFieldMoveFlash = {h(f['setUpFieldMoveFlash'])}
menuFieldCallback = {h(f['menuFieldCallback'])}
postMenuFieldCallback = {h(f['postMenuFieldCallback'])}
fieldCallbacks = [{h(f['fieldCallbacks'][0])}, {h(f['fieldCallbacks'][1])}] # FieldCallback_Flash, SetUpPuzzleEffectRegisteel
partyMenuSlotId = {h(t['partyMenu'] + 9)}
mapHeader = {h(f['mapHeader'])}
codeCrcs = [{', '.join(h(c) for c in f['crcs'])}]
monMoveField = {f['monMoveField']}
monEggField = {f['monEggField']}
"""


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != len(BUILDS):
        sys.exit(__doc__)
    out = ["# The TM screen (TmTeach.kt) and FLASH (Flash.kt) in Recharged Emerald: written by\n"
           "# scripts/find_tm_flash_recharged_emerald.py from the player's ROMs (each checked headless by scripts/verify_tm_flash.py).\n"]
    for (build, _, _), path in zip(BUILDS, args):
        t, f = find(path, build)
        print(build, {k: hex(v) if isinstance(v, int) else v for k, v in t.items() if k != "crcs"})
        print(build, {k: (hex(v) if isinstance(v, int) else [hex(x) for x in v]) for k, v in f.items() if k != "crcs"})
        out.append(toml(build, path, t, f))
    if "--no-write" not in sys.argv:
        with open(OUT, "w", encoding="utf-8") as fh:
            fh.write("".join(out))
        print("wrote", os.path.relpath(OUT))


if __name__ == "__main__":
    main()
