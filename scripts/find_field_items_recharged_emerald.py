#!/usr/bin/env python3
"""Finds USE / GIVE / TAKE's functions (FieldItems.kt) in Pokémon Recharged Emerald and writes their tables.

    scripts/find_field_items_recharged_emerald.py <v2.2.5.gba> <v2.2.8.gba> <Rebalanced v2.2.8.gba> [--no-write]

Recharged Emerald is Jaizu's pokeemerald rebuilt with modern gcc: no source, no symbols and nothing at a retail
address, so port_field_items.py's code matching can't map it. Every function is found instead through the game's own
code that uses it, from places the ROM itself points at:

  * gScriptCmdTable: the only literal-pool pair (start, end) 200..300 Thumb pointers apart. Its additem (0x44),
    removeitem (0x45) and checkitem (0x47) handlers each run ScriptReadHalfword, VarGet, ScriptReadHalfword, VarGet
    and then AddBagItem / RemoveBagItem / CheckBagHasItem.
  * gItems (the GF ROM header's +0xC8; 48-byte records, name[18], holdEffectParam +0x15, pocket +0x1E, fieldUseFunc
    +0x20). A Repel's fieldUseFunc is ItemUseOutOfBattle_Repel: VarGet(VAR_REPEL_STEP_COUNT) first, and through
    the task functions in its literal pools Task_UseRepel, whose VarSet calls give VarSet and every variable a Repel
    sets (Recharged's own: its Lure's two cleared, the Repel's steps, the last Repel used for its "use another?").
  * A Potion's fieldUseFunc (ItemUseOutOfBattle_Medicine) stores ItemUseCB_Medicine into gItemUseCB: there,
    GetMonData(mon, MON_DATA_HP / MAX_HP), the party menu's ExecuteTableBasedItemEffect_ (whose one BL is
    ExecuteTableBasedItemEffect), gPartyMenu, gPlayerParty and gItemEffectTable (every offered id has an entry).
  * SetMonData: the function most often called with MON_DATA_HELD_ITEM and a stack buffer (its switch starts at
    MON_DATA_STATUS).
  * ArePlayerFieldControlsLocked: script.c's Lock / Unlock / ArePlayerFieldControlsLocked trio over one byte.

Recharged numbers struct Pokemon's fields its own way: nine fields of its own sit before MON_DATA_ENCRYPT_SEPARATOR,
so MON_DATA_SPECIES is 20, HELD_ITEM 21, MOVE1 22, IS_EGG 54, HP 66 (GetBoxMonData compares with the separator;
ItemUseCB_Medicine passes HP / MAX_HP) - [PartyItemFns.monFieldShift]. Every address is checked here against the
ROM, then headless (docs/DEVELOPMENT.md's Recharged Emerald section), and written to
shared/.../companion/data/FieldItemsRechargedEmeraldGen.kt.
"""
import hashlib
import os
import struct
import sys
import zlib

B = 0x08000000
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "shared", "src", "commonMain", "kotlin", "com", "pokedaisy",
                   "app", "companion", "data", "FieldItemsRechargedEmeraldGen.kt")

BUILDS = (
    ("V2_2_5", "Pokemon Recharged Emerald (v2.2.5)", "67f44fb4cdbb16c0d1a495ac219f2979b1e3005f"),
    ("V2_2_8", "Pokemon Recharged Emerald (v2.2.8)", "56c9777215f5be627f7c3099e68985aefc10f2b7"),
    ("REBALANCED_V2_2_8", "Pokemon Recharged Emerald Rebalanced (v2.2.8)", "08b9c7903ae4c4e26d80ef945a90c004ae1e0ebb"),
)
# gPlayerParty / gPlayerPartyCount from NativeReader.kt's NATIVE_RECHARGED_EMERALD_* (checked against the code below).
PARTY = {"V2_2_5": (0x020373C8, 0x020373C5), "V2_2_8": (0x020373C8, 0x020373C5), "REBALANCED_V2_2_8": (0x020373DC, 0x020373D9)}

ITEM_SIZE, NAME_LEN = 48, 18
PARAM_OFF, POCKET_OFF, FIELD_USE_OFF = 0x15, 0x1E, 0x20
# Its pockets: 1 Items, 2 Medicine, 3 Poké Balls, 4 TMs / HMs, 5 Berries, 6 Key Items.
POCKET_TM_HM, POCKET_KEY_ITEMS = 4, 6
ITEM_COUNT = 408  # 0..407 are real records (407 DEXNAV); 408 on is other data
# Vanilla struct Pokemon field ids (pokeemerald's enum) and how far Recharged moved those from MON_DATA_SPECIES on.
MON_DATA_ENCRYPT_SEPARATOR, MON_DATA_HELD_ITEM, MON_DATA_STATUS, MON_DATA_HP = 10, 12, 55, 57
VAR_REPEL_STEP_COUNT = 0x4021
# The ids FieldItems.kt's MEDICINE_ITEMS lists (Emerald's). The same routine also runs for the flutes (39 BLUE FLUTE:
# out, as in every table) and Recharged's own PEWTER ARARE (62), whose gItemEffectTable entry is NULL - the game
# says it won't have any effect on anyone - so it stays out too.
MEDICINE = set(range(13, 34)) | {38, 44, 63, 64, 65, 66, 67, 70, 133, 134, 135, 136, 137, 139, 141, 142}
NO_EFFECT_MEDICINE = {62}
FLUTES = {39, 40, 41, 42, 43}
PP_ITEMS = {34, 35, 36, 37, 138, 69, 71}


class Rom:
    def __init__(self, path):
        self.d = open(path, "rb").read()

    def u16(self, a):
        return struct.unpack_from("<H", self.d, a - B)[0]

    def u32(self, a):
        return struct.unpack_from("<I", self.d, a - B)[0]

    def crc32(self, fn):
        return zlib.crc32(self.d[(fn & ~1) - B:(fn & ~1) - B + 32]) & 0xFFFFFFFF

    def walk(self, fn, limit):
        """(addr, halfword, BL target or None, pool word or None) through [fn, fn + limit)."""
        a, end = fn & ~1, (fn & ~1) + limit
        while a < end:
            h = self.u16(a)
            if (h & 0xF800) == 0xF000 and (self.u16(a + 2) & 0xF800) == 0xF800:
                off = ((h & 0x7FF) << 12) | ((self.u16(a + 2) & 0x7FF) << 1)
                if off & 0x400000:
                    off -= 0x800000
                yield a, h, a + 4 + off, None
                a += 4
                continue
            pool = None
            if (h & 0xF800) == 0x4800:
                pool = self.u32(((a + 4) & ~3) + (h & 0xFF) * 4)
            yield a, h, None, pool
            a += 2

    def until_return(self, fn, limit=0x80):
        """The instructions up to the first return (`bx` or `pop {.., pc}`)."""
        out = []
        for ins in self.walk(fn, limit):
            out.append(ins)
            h = ins[1]
            if (h & 0xFF87) == 0x4700 or (h & 0xFF00) == 0xBD00:
                break
        return out

    def bls(self, fn, limit=0x80):
        return [t for _, _, t, _ in self.until_return(fn, limit) if t is not None]

    def pools(self, fn, limit=0x80):
        return [p for _, _, _, p in self.until_return(fn, limit) if p is not None]


def check(cond, what):
    if not cond:
        raise SystemExit("check failed: " + what)


def script_cmd_table(rom):
    hits = set()
    d = rom.d
    for i in range(0, len(d) - 8, 4):
        a, b = struct.unpack_from("<II", d, i)
        if B <= a < b < B + len(d) and not (a | b) & 3 and 200 <= (b - a) // 4 <= 300:
            if all(rom.u32(a + 4 * k) & 1 and B <= rom.u32(a + 4 * k) < B + len(d) for k in range((b - a) // 4)):
                hits.add(a)
    check(len(hits) == 1, "one gScriptCmdTable")
    return hits.pop()


def bag_functions(rom, table):
    """VarGet and the three bag functions, from additem / removeitem / checkitem."""
    found, var_get = {}, set()
    for op, name in ((0x44, "addBagItem"), (0x45, "removeBagItem"), (0x47, "checkBagHasItem")):
        calls = rom.bls(rom.u32(table + 4 * op))
        check(len(calls) == 5 and calls[0] == calls[2] and calls[1] == calls[3], "script command 0x%X's shape" % op)
        var_get.add(calls[1])
        found[name] = calls[4]
    check(len(var_get) == 1, "one VarGet")
    found["varGet"] = var_get.pop()
    return found


def field_controls_locked(rom):
    """script.c's LockPlayerFieldControls / UnlockPlayerFieldControls / ArePlayerFieldControlsLocked, gcc's code:
    movs r2,#1; ldr r3,=X; strb r2,[r3]; bx lr; .word X; movs r2,#0; ...; ldr r3,=X; ldrb r0,[r3]; bx lr; nop; .word X."""
    hits = []
    pat = bytes.fromhex("0122014b1a707047")
    i = rom.d.find(pat)
    while i != -1:
        x = struct.unpack_from("<I", rom.d, i + 8)[0]
        if rom.d[i + 12:i + 20] == bytes.fromhex("0022014b1a707047") and struct.unpack_from("<I", rom.d, i + 20)[0] == x \
                and rom.d[i + 24:i + 32] == bytes.fromhex("014b18787047c046") and struct.unpack_from("<I", rom.d, i + 32)[0] == x:
            hits.append(B + i + 24)
        i = rom.d.find(pat, i + 1)
    check(len(hits) == 1, "one ArePlayerFieldControlsLocked")
    return hits[0]


def code_ptrs(rom, fn, limit=0x80, whole=False):
    """Thumb pointers [fn] loads from its literal pools: up to its first return, or ([whole]) through [limit] bytes."""
    pools = [p for _, _, _, p in rom.walk(fn, limit) if p is not None] if whole else rom.pools(fn, limit)
    return [p for p in pools if p & 1 and B <= p < B + len(rom.d)]


def repel(rom, items, var_get):
    """ItemUseOutOfBattle_Repel -> Task_StartUseRepel -> Task_UseRepel: VarSet and the variables a Repel sets."""
    use = rom.u32(items + 86 * ITEM_SIZE + FIELD_USE_OFF) & ~1
    calls = rom.bls(use, 0x30)
    check(calls[:1] == [var_get], "the Repel asks VarGet first")
    check(VAR_REPEL_STEP_COUNT in rom.pools(use, 0x30), "VarGet(VAR_REPEL_STEP_COUNT)")
    for start in code_ptrs(rom, use):
        for task in code_ptrs(rom, start, 0x40, whole=True):
            sets, last_pool, last_ops = [], None, []
            for a, h, t, p in rom.walk(task, 0x60):
                if p is not None and (h >> 8) & 7 == 0:
                    last_pool = p
                if t is not None:
                    if last_pool is not None and 0x4000 <= last_pool < 0x4100:
                        # what r1 is: movs r1, #0 / the BL's result (movs r1, r0) / a halfword (gSpecialVar_ItemId)
                        src = "zero" if 0x2100 in last_ops else "result" if 0x0001 in last_ops else "load" \
                            if any((o & 0xFFC7) == 0x8801 or (o & 0xF807) == 0x8801 for o in last_ops) else "?"
                        sets.append((t, last_pool, src))
                    last_pool, last_ops = None, []
                else:
                    last_ops.append(h)
            if len(sets) >= 2 and len({t for t, _, _ in sets}) == 1:
                var_set = sets[0][0]
                steps = [v for _, v, s in sets if s == "result"]
                clears = [v for _, v, s in sets if s == "zero"]
                last = [v for _, v, s in sets if s == "load"]
                check(steps == [VAR_REPEL_STEP_COUNT], "Task_UseRepel sets the steps")
                order = [v for _, v, _ in sets]
                check(order == clears + steps + last, "clears, steps, last Repel: " + str([hex(v) for v in order]))
                return dict(repel=use, varSet=var_set, clears=clears, last=last[0] if last else 0)
    raise SystemExit("Task_UseRepel not found")


def medicine(rom, items, party):
    """ItemUseCB_Medicine: GetMonData, ExecuteTableBasedItemEffect, the field shift, gPartyMenu / gPlayerParty."""
    use = rom.u32(items + 13 * ITEM_SIZE + FIELD_USE_OFF) & ~1
    cbs = code_ptrs(rom, use, 0x20)
    check(len(cbs) == 1, "ItemUseOutOfBattle_Medicine stores one callback")
    cb = cbs[0] & ~1
    check(party in rom.pools(cb, 0x240), "ItemUseCB_Medicine indexes gPlayerParty")
    get_mon, max_hp, wrapper = set(), None, None
    ops = []
    for a, h, t, p in rom.walk(cb, 0x240):
        if t is None:
            ops.append(h)
            continue
        imm = [o & 0xFF for o in ops[-3:] if (o & 0xFF00) in (0x2100, 0x3100)]  # movs / adds r1, #imm
        if imm and 60 <= imm[-1] <= 80:
            get_mon.add(t)
            max_hp = max(max_hp or 0, imm[-1])
        # ExecuteTableBasedItemEffect_(gPartyMenu.slotId, item, 0): ldrb r0, [rX, #9] just before
        if any((o & 0xFFC7) == 0x7A40 for o in ops[-3:]) and wrapper is None:
            wrapper = t
        ops = []
    check(len(get_mon) == 1 and max_hp, "one GetMonData for HP / MAX_HP")
    shift = max_hp - 1 - MON_DATA_HP
    inner = rom.bls(wrapper, 0x50)
    check(len(inner) == 1 and party in rom.pools(wrapper, 0x50), "ExecuteTableBasedItemEffect_'s one call")
    # gItemEffectTable (indexed from ITEM_POTION, for IsHPRecoveryItem): the pool word whose first entry is a Potion's
    # effect (HEAL_HP in byte 4, 20 HP in byte 6).
    rom_ptr = lambda w: B <= w < B + len(rom.d)
    effects = [p for _, _, _, p in rom.walk(cb, 0x240) if p is not None and rom_ptr(p) and not p & 3 and rom_ptr(rom.u32(p))
               and rom.d[rom.u32(p) - B + 4] == 4 and rom.d[rom.u32(p) - B + 6] == 20]
    check(len(set(effects)) == 1, "one gItemEffectTable")
    return dict(medicine=use, getMonData=get_mon.pop(), itemEffect=inner[0], shift=shift, effects=effects[0])


def get_box_mon_separator(rom, get_mon):
    """GetMonData(mon, field) -> GetMonData3 -> GetBoxMonData: `cmp r1, #ENCRYPT_SEPARATOR; bgt` near its top."""
    for fn in [get_mon] + rom.bls(get_mon, 0x20):
        for f in [fn] + rom.bls(fn, 0x80):
            for a, h, t, p in rom.walk(f, 0x20):
                if (h & 0xFF00) == 0x2900 and (rom.u16(a + 2) & 0xFF00) == 0xDC00:
                    return h & 0xFF
    raise SystemExit("GetBoxMonData's separator not found")


def set_mon_data(rom, held_item):
    d = rom.d
    hw = struct.unpack_from("<%dH" % (len(d) // 2), d)
    counts = {}
    for i in range(len(hw) - 1):
        h = hw[i]
        if (h & 0xF800) == 0xF000 and (hw[i + 1] & 0xF800) == 0xF800:
            prev = hw[max(0, i - 6):i]
            if (0x2100 | held_item) in prev and any((p & 0xFF00) == 0xAA00 or p == 0x466A for p in prev):
                off = ((h & 0x7FF) << 12) | ((hw[i + 1] & 0x7FF) << 1)
                if off & 0x400000:
                    off -= 0x800000
                t = B + 2 * i + 4 + off
                counts[t] = counts.get(t, 0) + 1
    best = sorted(counts.items(), key=lambda kv: -kv[1])
    check(len(best) >= 2 and best[0][1] >= 3 * best[1][1], "one SetMonData by far")
    return best[0][0]


def find(path, build):
    rom = Rom(path)
    party, party_count = PARTY[build]
    items = rom.u32(B + 0x100 + 0xC8)
    table = script_cmd_table(rom)
    f = bag_functions(rom, table)
    f["fieldControlsLocked"] = field_controls_locked(rom)
    r = repel(rom, items, f["varGet"])
    f["varSet"] = r["varSet"]
    m = medicine(rom, items, party)
    f["getMonData"], f["itemEffect"] = m["getMonData"], m["itemEffect"]
    shift = m["shift"]
    check(get_box_mon_separator(rom, f["getMonData"]) == MON_DATA_ENCRYPT_SEPARATOR + shift, "the separator moved as HP did")
    f["setMonData"] = set_mon_data(rom, MON_DATA_HELD_ITEM + shift)
    # SetMonData's switch: `movs r3, r1; subs r3, #MON_DATA_STATUS` first.
    check(rom.u16(f["setMonData"]) == 0x000B and rom.u16(f["setMonData"] + 2) == 0x3B00 | (MON_DATA_STATUS + shift),
          "SetMonData switches from MON_DATA_STATUS")
    use = dict(medicine=m["medicine"], ppRecovery=rom.u32(items + 34 * ITEM_SIZE + FIELD_USE_OFF) & ~1,
               ppUp=rom.u32(items + 69 * ITEM_SIZE + FIELD_USE_OFF) & ~1, repel=r["repel"])
    # gItems: ids where FieldItems.kt expects them, the Repels' steps, every USE id on its routine.
    rec = lambda i, o, n=1: rom.d[items + i * ITEM_SIZE + o - B:items + i * ITEM_SIZE + o - B + n]
    check([rec(i, PARAM_OFF)[0] for i in (86, 83, 84)] == [100, 200, 250], "the Repels' steps")
    by_use = {}
    for i in range(ITEM_COUNT):
        by_use.setdefault(struct.unpack_from("<I", rec(i, FIELD_USE_OFF, 4))[0] & ~1, set()).add(i)
    check(by_use[use["medicine"]] - FLUTES == MEDICINE | NO_EFFECT_MEDICINE, "the medicine ids")
    effect = lambda i: rom.u32(m["effects"] + 4 * (i - 13))
    check(all(effect(i) for i in MEDICINE | PP_ITEMS), "every USE id has an effect entry")
    check(not any(effect(i) for i in NO_EFFECT_MEDICINE), "PEWTER ARARE has none")
    check(by_use[use["ppRecovery"]] == {34, 35, 36, 37, 138} and by_use[use["ppUp"]] == {69, 71}, "the PP items")
    check(by_use[use["repel"]] == {83, 84, 86}, "the Repels")
    check(rec(259, POCKET_OFF)[0] == POCKET_KEY_ITEMS and rec(289, POCKET_OFF)[0] == POCKET_TM_HM, "the pockets")
    check(1 <= rec(ITEM_COUNT - 1, POCKET_OFF)[0] <= 6 and not 1 <= rec(ITEM_COUNT, POCKET_OFF)[0] <= 6, "gItems' end")
    order = ("varGet", "varSet", "checkBagHasItem", "removeBagItem", "fieldControlsLocked")
    party_order = ("getMonData", "setMonData", "itemEffect", "addBagItem")
    return dict(f=f, items=items, party=party, partyCount=party_count, shift=shift, use=use, repelClears=r["clears"],
                lastRepel=r["last"], crcs=[rom.crc32(f[k]) for k in order], partyCrcs=[rom.crc32(f[k]) for k in party_order])


def kotlin(results):
    h = lambda v: "0x%08XL" % v
    out = ["package com.pokedaisy.app.companion.data", "",
           "// Generated by scripts/find_field_items_recharged_emerald.py from the user's ROMs - don't edit by hand.",
           "// USE / GIVE / TAKE's tables (FieldItems.kt) for Pokémon Recharged Emerald, a gcc rebuild of pokeemerald:",
           "// every function found through the game's own code that calls it, checked against the ROM and headless.",
           "// scratch: the last EWRAM global its code loads sits below 0x0203D4C0, and nothing past 0x0203D600 was",
           "// ever written in the captures (field, menus, battles, an in-game save).", ""]
    for build, stem, sha1 in BUILDS:
        r, f = results[build], results[build]["f"]
        out += [
            "/** %s (SHA1 %s...). */" % (stem, sha1[:8]),
            "val FIELD_ITEMS_RECHARGED_EMERALD_%s = FieldItemFns(" % build,
            "    varGet = %s, varSet = %s, checkBagHasItem = %s, removeBagItem = %s," % (
                h(f["varGet"]), h(f["varSet"]), h(f["checkBagHasItem"]), h(f["removeBagItem"])),
            "    fieldControlsLocked = %s, repelVar = 0x%04X, items = %s," % (h(f["fieldControlsLocked"]), VAR_REPEL_STEP_COUNT, h(r["items"])),
            "    codeCrcs = listOf(%s)," % ", ".join(h(c) for c in r["crcs"]),
            "    itemNameLen = %d, itemParamOffset = 0x%02X," % (NAME_LEN, PARAM_OFF),
            "    repelClearVars = listOf(%s), lastRepelVar = 0x%04X," % (", ".join("0x%04X" % v for v in r["repelClears"]), r["lastRepel"]),
            "    party = PartyItemFns(",
            "        getMonData = %s, setMonData = %s, itemNoEffect = null, itemEffect = %s," % (
                h(f["getMonData"]), h(f["setMonData"]), h(f["itemEffect"])),
            "        addBagItem = %s, party = %s, partyCount = %s, scratch = 0x0203FFF0L," % (
                h(f["addBagItem"]), h(r["party"]), h(r["partyCount"])),
            "        itemCount = %d, unholdablePockets = setOf(%d, %d), monFieldShift = %d," % (ITEM_COUNT, POCKET_KEY_ITEMS, POCKET_TM_HM, r["shift"]),
            "        codeCrcs = listOf(%s)," % ", ".join(h(c) for c in r["partyCrcs"]),
            "        useFuncs = setOf(%s)," % ", ".join(h(r["use"][k]) for k in ("medicine", "ppRecovery", "ppUp", "repel")),
            "    ),",
            ")",
            "",
        ]
    return "\n".join(out)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 3:
        raise SystemExit(__doc__)
    results = {}
    for (build, stem, sha1), path in zip(BUILDS, args):
        check(hashlib.sha1(open(path, "rb").read()).hexdigest() == sha1, "%s is %s" % (path, stem))
        results[build] = find(path, build)
        r = results[build]
        print(build, " ".join("%s=%s" % (k, hex(v)) for k, v in sorted(r["f"].items())),
              "shift=%d" % r["shift"], "clears=%s" % [hex(v) for v in r["repelClears"]], "last=%s" % hex(r["lastRepel"]))
    if "--no-write" not in sys.argv:
        open(OUT, "w").write(kotlin(results))
        print("wrote", os.path.relpath(OUT))


if __name__ == "__main__":
    main()
