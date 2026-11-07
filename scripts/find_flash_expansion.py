#!/usr/bin/env python3
"""Finds FLASH's functions (Flash.kt) in the gcc-built Emerald hacks the companion's FLY already knows and writes their records.

    scripts/find_flash_expansion.py <roms dir> [--no-write]

Lazarus, Emerald Imperium, Heart and Soul and SoulGold's builds (pokeemerald-expansion, no symbols): every address is found
from the ROM itself, then checked here and headless (scripts/verify_tm_flash.py), and written to
gamedata/gen3/records/expansion_flash.toml.

  * SetUpFieldMove_Flash: the expansion's gFieldMoveInfo entry for MOVE_FLASH ({setUp, isUnlocked, u16 move, u16 msg}:
    Heart and Soul, SoulGold), else the function holding FLAG_SYS_USE_FLASH (0x888) that reads gMapHeader.cave (Lazarus,
    Imperium: the older field-move table, find_tm_flash_recharged_emerald.py's way).
  * Inside it, in the game's order: the puzzle checks first (ShouldDoBrailleRegisteelEffect: gSpecialVar_Result =
    GetCursorSelectionMonId(), whose `ldr r3, =gPartyMenu; ldrb r0, [r3, #9]` gives the cursor), each storing
    FieldCallback_PrepareFadeInFromMenu into gFieldCallback2 and its own callback into gPostMenuFieldCallback; then
    gMapHeader's cave byte (+0x15; SoulGold's 16-bit map sections push it to +0x16) and FlagGet(the lit flag), then
    FieldCallback_Flash. Every callback stored into gPostMenuFieldCallback is a known one (FieldCallback_Flash first).
  * The badge: isUnlocked's `ldr r0, =FLAG; bl FlagGet` (Heart and Soul, SoulGold); else the party menu's
    FlagGet(FLAG_BADGE01_GET + fieldMove) (Imperium: 0x867 + FIELD_MOVE_FLASH 1), or its switch with a flag per field move,
    FLASH's being the case that goes on to FlagGet(the lit flag) for "already lit" (Lazarus: 0x869).

MON_DATA_MOVE1 / IS_EGG come from each game's FLY table (FieldItems.kt), probed when FLY was mapped.
"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from find_field_items_recharged_emerald import B, Rom, check  # noqa: E402
from find_tm_flash_recharged_emerald import BL, find_shape, halfwords, iwram, ewram, pools, thumb, walk  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "gamedata", "gen3", "records", "expansion_flash.toml")
MOVE_FLASH = 148
# record, ROM file, VarGet (FieldItems.kt: which build a file is), MON_DATA_MOVE1, MON_DATA_IS_EGG, gPartyMenu.slotId (its FLY table)
GAMES = [
    ("FLASH_LAZARUS", "Pokemon Lazarus (v2.0).gba", 0x08114644, 20, 52, 0x0201B685),
    ("FLASH_IMPERIUM", "Pokemon Emerald Imperium (v1.3.1).gba", 0x08116BFC, 20, 52, 0x020370E9),
    ("FLASH_HEART_AND_SOUL", "Pokemon Heart and Soul (v2.0.6).gba", 0x0811E678, 20, 54, 0x02034205),
    ("FLASH_SOULGOLD", "Pokemon SoulGold (v1.1.4).gba", 0x08133F38, 21, 55, 0x02038D2D),
    ("FLASH_SOULGOLD_V1_2", "Pokemon SoulGold (v1.2).gba", 0x0813466C, 21, 55, 0x02038D35),
    ("FLASH_SOULGOLD_V1_2B", "Pokemon SoulGold (v1.2) (Alt 1).gba", 0x0813465C, 21, 55, 0x02038D35),
    ("FLASH_SOULGOLD_V1_2C", "Pokemon SoulGold (v1.2) (Alt 2).gba", 0x081346C8, 21, 55, 0x02038D35),
    ("FLASH_SOULGOLD_V1_2_1", "Pokemon SoulGold (v1.2.1).gba", 0x08136B90, 21, 55, 0x02038B75),
]
VAR_GET_CRC = 0xAEDB0050


def set_up(rom):
    """(SetUpFieldMove_Flash, isUnlocked or None)."""
    th = lambda v: v & 1 and B <= v < B + len(rom.d)
    for o in range(0, len(rom.d) - 24, 4):
        a, b, m = struct.unpack_from("<IIH", rom.d, o)
        if m == MOVE_FLASH and th(a) and th(b) and th(rom.u32(B + o - 12)) and th(rom.u32(B + o + 12)):
            return a & ~1, b & ~1
    for o in range(0, min(len(rom.d), 0x02000000) - 4, 4):
        if struct.unpack_from("<I", rom.d, o)[0] != 0x888:
            continue
        for k in range(4, 0x100, 2):
            a = B + o - k
            if rom.u16(a) & 0xFF00 == 0xB500:
                if any((h & 0xF800) == 0x7800 and (h >> 6) & 31 in (0x15, 0x16) for h in halfwords(rom, a, k)):
                    return a, None
                break
    raise SystemExit("SetUpFieldMove_Flash not found")


def parse(rom, fn):
    """Its words in code order: gMapHeader + cave offset, the lit flag, FlagGet, the callbacks, gFieldCallback2..."""
    w = walk(rom, fn, 0x90)
    # the function ends at its last branch back before a pool: cut at the first pool word's address
    end = min((((a + 4) & ~3) + (h & 0xFF) * 4 for a, h, _, p in w if p is not None), default=fn + 0x90)
    w = [x for x in w if x[0] < end]
    loads = [(a, h, p) for a, h, _, p in w if p is not None]
    # gMapHeader: the word loaded right before the cave byte's ldrb
    cave_i = next(i for i, (_, h, _, _) in enumerate(w) if (h & 0xF800) == 0x7800 and (h >> 6) & 31 in (0x15, 0x16))
    cave_off = (w[cave_i][1] >> 6) & 31
    map_header = next(p for _, _, _, p in reversed(w[:cave_i]) if p is not None)
    check(ewram(map_header), "gMapHeader")
    # the lit flag: the first small constant loaded after the cave check, and the BL right after it is FlagGet
    lit_i = next(i for i, (_, _, _, p) in enumerate(w) if i > cave_i and p is not None and 0x800 <= p < 0x1000)
    lit = w[lit_i][3]
    flag_get = next(t for _, _, t, _ in w[lit_i:] if t is not None)
    words = [p for _, _, p in loads]
    gfc2 = next(v for v in words if iwram(v))
    check(words.count(gfc2) >= 2, "gFieldCallback2 stored on every path")
    # gPostMenuFieldCallback: the EWRAM word each path loads with a different callback after it (gSpecialVar_Result, also
    # loaded twice in SoulGold, always has FieldCallback_PrepareFadeInFromMenu after it)
    def followers(v):
        return {loads[i + 1][2] for i, (_, _, p) in enumerate(loads[:-1]) if p == v and thumb(loads[i + 1][2])}
    post = next(v for v in dict.fromkeys(words) if ewram(v) and words.count(v) >= 2 and len(followers(v)) >= 2)
    # callbacks: each thumb pointer loaded right before a store through gPostMenuFieldCallback
    cbs = []
    for i, (a, h, p) in enumerate(loads):
        if p == post:
            nxt = next((q for _, _, q in loads[i + 1:i + 2]), None)
            if nxt is not None and thumb(nxt):
                cbs.append(nxt & ~1)
    prep = [v for v in words if thumb(v) and (v & ~1) not in cbs]
    check(len(set(prep)) == 1, "one FieldCallback_PrepareFadeInFromMenu")
    # FieldCallback_Flash: the callback stored after the lit flag's check
    lit_at = w[lit_i][0]
    after = [i for i, (a, h, p) in enumerate(loads) if a > lit_at and p == post]
    check(after, "a store after the lit check")
    i = after[0]
    flash_cb = loads[i + 1][2] & ~1
    others = [c for c in dict.fromkeys(cbs) if c != flash_cb]
    # GetCursorSelectionMonId: the BL whose result goes into gSpecialVar_Result; its `ldr r3, =gPartyMenu; ldrb r0, [r3, #9]`
    slot = None
    for a, h, t, _ in w:
        if t is not None and t != flag_get:
            hw = halfwords(rom, t, 8)
            if len(hw) >= 2 and (hw[0] & 0xFF00) == 0x4B00 and (hw[1] & 0xF83F) == 0x7818:
                slot = pools(rom, t, 4)[0] + ((hw[1] >> 6) & 31)
                break
    return dict(setUpFieldMoveFlash=fn, flagGet=flag_get & ~1, litFlag=lit, mapHeader=map_header, caveOff=cave_off,
                menuFieldCallback=gfc2, postMenuFieldCallback=post, fieldCallbacks=[flash_cb] + others, partyMenuSlotId=slot)


def badge(rom, unlocked, flag_get, lit):
    if unlocked is not None:
        # isUnlocked: ldr r0, =FLAG (or movs + lsls) ; bl FlagGet
        p = pools(rom, unlocked, 0x10)
        b = [t for t in [x for _, _, x, _ in walk(rom, unlocked, 0x10)] if t is not None]
        check(b[:1] == [flag_get], "isUnlocked asks FlagGet")
        if p:
            return p[0]
        h = halfwords(rom, unlocked, 8)
        mv = next(x for x in h if (x & 0xF800) == 0x2000)
        sh = next(x for x in h if (x & 0xF800) == 0x0000 and x)
        return (mv & 0xFF) << ((sh >> 6) & 31)
    # the party menu's FlagGet(FLAG_BADGE01_GET + fieldMove): ldr r3, =0x867; adds r0, r4, r3; bl FlagGet
    hits = [a for a in find_shape(rom, [(0x4B00, 0xFF00), (0x18E0, 0xFFFF)] + BL)
            if pools(rom, a, 4) == [0x867] and [t for _, _, t, _ in walk(rom, a + 4, 4) if t] == [flag_get]]
    if len(hits) == 1:
        return 0x867 + 1   # FIELD_MOVE_FLASH
    # a switch with a flag per field move: FLASH's case goes on to FlagGet(lit) ("already lit")
    hits = find_shape(rom, [(0x4800, 0xFF00)] + BL + [(0x2801, 0xFFFF), (0xD100, 0xFF00)] + BL +
                      [(0x2801, 0xFFFF), (0xD000, 0xFF00), (0x4800, 0xFF00)] + BL)
    found = []
    for a in hits:
        ws = pools(rom, a, 0x1A)
        bls = [t for _, _, t, _ in walk(rom, a, 0x1A) if t]
        if len(ws) == 2 and ws[1] == lit and 0x860 <= ws[0] < 0x900 and bls[0] == flag_get and bls[2] == flag_get:
            found.append(ws[0])
    check(len(found) == 1, f"one FLASH badge check ({found})")
    return found[0]


def find(path, mon_move, mon_egg, fly_slot):
    rom = Rom(path)
    fn, unlocked = set_up(rom)
    f = parse(rom, fn)
    # the cursor: GetCursorSelectionMonId's when it's the plain read, which must then be the FLY table's; SoulGold's reads
    # more first (its save block), so there the FLY table's (checked when FLY was mapped)
    check(f["partyMenuSlotId"] in (None, fly_slot), f"gPartyMenu.slotId {f['partyMenuSlotId']} isn't FLY's")
    f["partyMenuSlotId"] = fly_slot
    f["badgeFlag"] = badge(rom, unlocked, f["flagGet"], f["litFlag"])
    f.update(monMoveField=mon_move, monEggField=mon_egg)
    f["crcs"] = [rom.crc32(v) for v in [f["flagGet"], f["setUpFieldMoveFlash"]] + f["fieldCallbacks"]]
    return f


def toml(name, file, f):
    h = lambda v: f"0x{v:08X}"
    cave = f"\ncaveOff = 0x{f['caveOff']:X}" if f["caveOff"] != 0x15 else ""
    return f"""
# {file}: found by scripts/find_flash_expansion.py.
[record.{name}]
schema = 'gen3_flash'
flagGet = {h(f['flagGet'])}
badgeFlag = 0x{f['badgeFlag']:X}
litFlag = 0x{f['litFlag']:X}
setUpFieldMoveFlash = {h(f['setUpFieldMoveFlash'])}
menuFieldCallback = {h(f['menuFieldCallback'])}
postMenuFieldCallback = {h(f['postMenuFieldCallback'])}
fieldCallbacks = [{', '.join(h(v) for v in f['fieldCallbacks'])}] # FieldCallback_Flash, then the puzzles'
partyMenuSlotId = {h(f['partyMenuSlotId'])}
mapHeader = {h(f['mapHeader'])}{cave}
codeCrcs = [{', '.join(h(c) for c in f['crcs'])}]
monMoveField = {f['monMoveField']}
monEggField = {f['monEggField']}
"""


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        sys.exit(__doc__)
    out = ["# FLASH (Flash.kt) in the gcc-built Emerald hacks: written by scripts/find_flash_expansion.py from the player's ROMs\n"
           "# (each checked headless by scripts/verify_tm_flash.py).\n"]
    for name, file, var_get, mon_move, mon_egg, fly_slot in GAMES:
        path = os.path.join(args[0], file)
        if not os.path.isfile(path):
            sys.exit(f"missing {file}")
        check(Rom(path).crc32(var_get) == VAR_GET_CRC, f"{file} is the build FieldItems.kt has at VarGet {var_get:#x}")
        f = find(path, mon_move, mon_egg, fly_slot)
        print(name, {k: (hex(v) if isinstance(v, int) else [hex(x) for x in v]) for k, v in f.items() if k != "crcs"})
        out.append(toml(name, file, f))
    if "--no-write" not in sys.argv:
        with open(OUT, "w", encoding="utf-8") as fh:
            fh.write("".join(out))
        print("wrote", os.path.relpath(OUT))


if __name__ == "__main__":
    main()
