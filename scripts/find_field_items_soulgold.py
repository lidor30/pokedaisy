#!/usr/bin/env python3
"""Finds what a Repel needs (FieldItems.kt's FIELD_ITEMS_SOULGOLD*) in Pokemon SoulGold's builds.

    scripts/find_field_items_soulgold.py <roms dir>

SoulGold is pokeemerald-expansion with no source; FLY and the PC were mapped already (FieldItems.kt). For REPEL
(GitHub #242), from places the ROM itself points at:

  * gScriptCmdTable: ScriptContext_SetupScript's (PcFns.setupScript) literal pool holds it and its end. Its pointers
    are into the ROM's 0x0A000000 mirror. checkitem (0x47) and removeitem (0x45) end in CheckBagHasItem /
    RemoveBagItem, after ScriptReadHalfword, VarGet, ScriptReadHalfword, VarGet.
  * gItemsInfo (NativeConfig.itemDescs' base: 44-byte records): a Repel's holdEffectParam (its steps) at +9, its
    fieldUseFunc at -12. ItemUseOutOfBattle_Repel checks VarGet(VAR_REPEL_STEP_COUNT): a Repel goes on when the low 15
    bits are 0 or bit 15 (a Lure's mark) is set; Task_StartUseRepel sets Task_UseRepel, whose VarSets are the steps
    (VAR_REPEL_STEP_COUNT) and the Repel used (VAR_LAST_REPEL_LURE_USED), before RemoveUsedItem.

Prints each build's values and checks they hold; FieldItems.kt carries them by hand.
"""
import os
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from find_field_items_recharged_emerald import Rom, check  # noqa: E402

B = 0x08000000
# (name, file, ScriptContext_SetupScript, gItemsInfo's description base (ItemDescTable), VarGet, VarSet)
BUILDS = [
    ("SOULGOLD", "Pokemon SoulGold (v1.1.4).gba", 0x0823647C, 0x087520A0, 0x08133F38, 0x08133FCC),
    ("SOULGOLD_V1_2", "Pokemon SoulGold (v1.2).gba", 0x08236D5C, 0x08753834, 0x0813466C, 0x08134700),
    ("SOULGOLD_V1_2B", "Pokemon SoulGold (v1.2) (Alt 1).gba", 0x08236CC4, 0x0875379C, 0x0813465C, 0x081346F0),
    ("SOULGOLD_V1_2C", "Pokemon SoulGold (v1.2) (Alt 2).gba", 0x08236DBC, 0x08753928, 0x081346C8, 0x0813475C),
    ("SOULGOLD_V1_2_1", "Pokemon SoulGold (v1.2.1).gba", 0x0823BC04, 0x0875B360, 0x08136B90, 0x08136C24),
    ("SOULGOLD_V1_2_1 (second build)", "Pokemon SoulGold (v1.2.1) (Alt 1).gba", 0x0823BC04, 0x0875B360, 0x08136B90, 0x08136C24),
]
ITEM_SIZE, PARAM_OFF, USE_OFF = 0x2C, 9, -12
REPELS = {114: 100, 115: 200, 116: 250}
VAR_REPEL_STEP_COUNT, VAR_LAST_REPEL_LURE_USED = 0x4021, 0x40BD


def mirror(p):
    return p - 0x02000000 if p >= 0x0A000000 else p


def script_table(rom, setup):
    pools = rom.pools(setup, 0x60)
    for a, b in zip(pools, pools[1:]):
        if B <= a < b < B + len(rom.d) and 200 <= (b - a) // 4 <= 300:
            return a
    raise SystemExit("no gScriptCmdTable in ScriptContext_SetupScript's pool")


def bag_fn(rom, table, op, var_get):
    calls = rom.bls(mirror(rom.u32(table + 4 * op)) & ~1)
    check(len(calls) >= 5 and calls[0] == calls[2] and calls[1] == calls[3] == var_get, "script command 0x%X's shape" % op)
    return calls[-1]


def repel(rom, items, var_get, var_set, remove):
    use = rom.u32(items + 114 * ITEM_SIZE + USE_OFF) & ~1
    for i in REPELS:
        check(rom.u32(items + i * ITEM_SIZE + USE_OFF) & ~1 == use, "every Repel runs one fieldUseFunc")
        check(rom.d[items + i * ITEM_SIZE + PARAM_OFF - B] == REPELS[i], "item %d's steps" % i)
    ins = list(rom.walk(use, 0x24))
    check(ins[2][3] == VAR_REPEL_STEP_COUNT and ins[4][2] == var_get, "ItemUseOutOfBattle_Repel: VarGet(VAR_REPEL_STEP_COUNT)")
    check(ins[5][1] == 0x0400 and (ins[6][1] & 0xFF00) == 0xD500, "... bit 15 (a Lure) tested")
    start = [p for _, _, _, p in ins if p is not None and p & 1 and B <= p < B + len(rom.d)][0] & ~1
    task = [p for _, _, _, p in rom.walk(start, 0x40) if p is not None and p & 1 and B <= p < B + len(rom.d)][0] & ~1
    sets, last_pool = [], None
    for a, h, t, p in rom.walk(task, 0x40):
        if p is not None:
            last_pool = p
        if t == var_set:
            sets.append(last_pool)
    check(sets == [VAR_REPEL_STEP_COUNT, VAR_LAST_REPEL_LURE_USED], "Task_UseRepel's VarSets: " + str(sets))
    return use, task


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    for name, file, setup, items, var_get, var_set in BUILDS:
        path = os.path.join(sys.argv[1], file)
        if not os.path.exists(path):
            print(f"{name}: no {file}")
            continue
        rom = Rom(path)
        table = script_table(rom, setup)
        check_fn = bag_fn(rom, table, 0x47, var_get)
        remove_fn = bag_fn(rom, table, 0x45, var_get)
        use, task = repel(rom, items, var_get, var_set, remove_fn)
        crc = [zlib.crc32(rom.d[f - B:f - B + 32]) & 0xFFFFFFFF for f in (check_fn, remove_fn)]
        print(f"{name}: gScriptCmdTable {table:#010x}, checkBagHasItem {check_fn:#010x}, removeBagItem {remove_fn:#010x}, "
              f"items {items:#010x}, Repel use {use:#010x}, Task_UseRepel {task:#010x}, CRCs {crc[0]:#010x} {crc[1]:#010x}")


if __name__ == "__main__":
    main()
