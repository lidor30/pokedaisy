#!/usr/bin/env python3
"""Retail FireRed (rev 0 / 1) and Emerald's engine fingerprints -> RetailEngineGen.kt.

A ROM hack made by editing retail in place (a map editor, a hex editor, a patch tool) keeps retail's code and RAM:
what its own hash can't tell us, its code can. RetailEngine.kt proves it per address, never by guessing:

  - RAM: every global the one-press picks, the POKéMON switch, EXPAND and the field reads use is loaded by the
    game's code from a literal pool. A site is the loading instruction (`ldr rN, [pc, #imm]`) and its pool word;
    its window is the 26 bytes around the instruction with the pool word blanked. If a hack's windows still match
    and its pool words still hold the address, the code that uses the global is retail's and so is the global's
    place (a rebuild moves the code, an in-place hack doesn't move a global without changing every user).
  - ROM: EXPAND's ROM tables (gMapGroups, the object graphics tables, ...) the same way, except a hack may have
    repointed one: where the window matches, the hack's own pool word says where its table is now. The sites
    vote; most must agree.

Up to SAMPLE sites an address, spread through the ROM. Written: offsets and CRC32s, plus retail's EXPAND rows and
RAM addresses, which the app has anyway. No ROM bytes.

    python3 scripts/gen_retail_engine.py [<gba ROM dir>]
    python3 scripts/gen_retail_engine.py --row <hack.gba> [<gba ROM dir>]

The dir holds the No-Intro retail ROMs (default ~/Downloads/Game ROMs & Emulation/gba). RetailEngineTest checks the
result against every hack we know (the hand-made EXPAND rows rebuilt, the rebuilds refused). --row prints the EXPAND
row the app would rebuild for a hack, as native-capture/mgba_dump's `widerow` command takes it, to check it headless
(`widerow ...`, `wideon 22`, `widecheck`).
"""
import math
import os
import re
import struct
import sys
import tomllib
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/kotlin/com/pokedaisy/app/companion/data/RetailEngineGen.kt"
WIDE = ROOT / "app/src/main/cpp/pk_wide_games.h"
WIDE_H = ROOT / "app/src/main/cpp/pk_wide.h"
GEN3 = ROOT / "gamedata/gen3/retail"

SAMPLE = 16          # sites kept an address
BEFORE, AFTER = 12, 14   # the window around a loading instruction
CODE_END = 0x01000000    # pool words searched below this (the whole 16 MB retail image)

BUILDS = [
    # id, code, rev, file, its TOML config, POKéMON switch's gPartyMenu (PokeDaisyActivity.switchAddrsFor)
    ("FIRERED_REV0", "BPRE", 0, "Pokemon - FireRed Version (USA).gba", "firered_rev0", 0x0203B0A0),
    ("FIRERED_REV1", "BPRE", 1, "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba", "firered_rev1", 0x0203B0A0),
    ("EMERALD", "BPEE", 0, "Pokemon - Emerald Version (USA, Europe).gba", "emerald_retail", 0x0203CEC8),
]
# The battle menus' cursors where the config has none: rev 0 shares rev 1's RAM (firered.toml's note), and the
# rev 0 hacks we checked headless (Ultra Violet & co.) use these. Proven per ROM like the rest.
CURSORS_REV0 = (0x02023FF8, 0x02023FFC)


def crc(b):
    return zlib.crc32(b) & 0xFFFFFFFF


def is_ram(v):
    return 0x02000000 <= v < 0x02040000 or 0x03000000 <= v < 0x03008000


def is_rom(v):
    return 0x08000000 <= v < 0x0A000000


def window(rom, u, p):
    """The bytes around the loading instruction at u, its pool word (at p) blanked if it falls inside."""
    w = bytearray(rom[u - BEFORE:u + AFTER])
    for k in range(4):
        if u - BEFORE <= p + k < u + AFTER:
            w[p + k - (u - BEFORE)] = 0
    return bytes(w)


def sites(rom, value):
    """Every Thumb `ldr rN, [pc, #imm]` that loads a pool word holding value: (instruction, pool word) offsets."""
    out = []
    pat = struct.pack("<I", value)
    i = rom.find(pat)
    while 0 <= i < CODE_END:
        if i % 4 == 0:
            for u in range(max(BEFORE, i - 1020), i, 2):
                h = rom[u] | rom[u + 1] << 8
                if h >> 11 == 0b01001 and ((u + 4) & ~3) + (h & 0xFF) * 4 == i:
                    out.append((u, i))
        i = rom.find(pat, i + 1)
    return out


def sample(lst):
    if len(lst) <= SAMPLE:
        return lst
    return [lst[(2 * k + 1) * len(lst) // (2 * SAMPLE)] for k in range(SAMPLE)]


def wide_slots():
    """PK_WIDE_ADDRS' slot names, in order (pk_wide.h)."""
    text = WIDE_H.read_text()
    body = text[text.index("PK_WIDE_VMAP"):text.index("PK_WIDE_ADDRS,")]
    return re.findall(r"^\s*(PK_WIDE_[A-Z0-9_]+)\s*[,=]", "    " + body, re.M)


def wide_rows():
    rows = {}
    for m in re.finditer(r"\{ 0x([0-9a-f]{8}), (PK_WIDE_FAMILY_\w+), \{ ([^}]*)\}", WIDE.read_text()):
        rows[int(m[1], 16)] = [int(x, 16) for x in m[3].split(",") if x.strip()]
    return rows


# EXPAND's ROM slots that may be dropped (0 = the far-off people aren't drawn: pk_wide.c's ghosts) when a hack's
# can't be proven; any other ROM slot that can't be is a refusal.
OPTIONAL = {"PK_WIDE_OBJ_GFX_INFO", "PK_WIDE_OBJ_PALETTES", "PK_WIDE_OBJ_FACING", "PK_WIDE_SPRITE_PAL_TAGS",
            "PK_WIDE_ELEVATION_PRIO"}


def config(name):
    cfgs = {}
    for f in GEN3.glob("*.toml"):
        cfgs.update(tomllib.loads(f.read_text()).get("config", {}))
    c = dict(cfgs[name])
    while "base" in c:
        base = dict(cfgs[c.pop("base")])
        base.update(c)
        c = base
    return c


def kt_sites(entries):
    """addr=u:p:crc,u:p:crc;addr=... (hex)."""
    return ";".join(f"{a:x}=" + ",".join(f"{u:x}:{p:x}:{c:x}" for u, p, c in s) for a, s in entries)


def chunk(s, width=110):
    parts = [s[i:i + width] for i in range(0, len(s), width)] or [""]
    return " +\n            ".join(f'"{p}"' for p in parts)


def needed(n):
    return n if n <= 2 else max(2, (3 * n + 3) // 4)


def votes(retail, hack, value):
    """RetailEngine.votes: the hack's pool words where retail's window around a site still matches."""
    out = {}
    for u, p in sample(sites(retail, value)):
        if p + 4 <= len(hack) and window(hack, u, p) == window(retail, u, p):
            v = struct.unpack_from("<I", hack, p)[0]
            out[v] = out.get(v, 0) + 1
    return out, len(sample(sites(retail, value)))


def derived_row(rom_dir, hack_path):
    """--row: what RetailEngine.check gives the hack (the same rules), as mgba_dump's `widerow` line."""
    hack = Path(hack_path).read_bytes()
    code, rev = hack[0xAC:0xB0].decode(errors="replace"), hack[0xBC]
    build = next((b for b in BUILDS if b[1] == code and b[2] == rev), None)
    if not build:
        sys.exit(f"no retail build for {code} rev {rev}")
    bid, _, _, file, cfg_name, party_menu = build
    retail = (rom_dir / file).read_bytes()
    row = list(wide_rows()[crc(retail)])
    slots = wide_slots()
    cfg = config(cfg_name)
    action, move = (cfg.get("actionCursor"), cfg.get("moveCursor")) if "actionCursor" in cfg else CURSORS_REV0
    for a in sorted({v for v in row if is_ram(v)} | {action, move, party_menu, cfg["playerParty"]}):
        got, n = votes(retail, hack, a)
        top = max(got.items(), key=lambda kv: kv[1], default=(None, 0))
        if top[1] < needed(n) or top[0] != a:
            sys.exit(f"{bid}: RAM {a:#010x} not proven ({got.get(a, 0)} of {n})")
    for i, v in enumerate(list(row)):
        if not is_rom(v):
            continue
        got, n = votes(retail, hack, v)
        matched = sum(got.values())
        top = max(got.items(), key=lambda kv: kv[1], default=(None, 0))
        if top[0] is not None and top[1] >= needed(n) and top[1] * 10 >= matched * 9 and is_rom(top[0]):
            row[i] = top[0]
        elif slots[i] in OPTIONAL:
            row[i] = 0
        else:
            sys.exit(f"{bid}: {slots[i]} not proven")
    print(f"widerow {crc(retail):08x} " + ",".join(f"{v & 0xFFFFFFFF:x}" for v in row))


def main():
    rom_dir = Path(os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba"))
    args = sys.argv[1:]
    if args[:1] == ["--row"]:
        return derived_row(Path(args[2]) if len(args) > 2 else rom_dir, args[1])
    if args:
        rom_dir = Path(args[0])
    slots = wide_slots()
    rows = wide_rows()
    out = []
    for bid, code, rev, file, cfg_name, party_menu in BUILDS:
        rom = (rom_dir / file).read_bytes()
        assert rom[0xAC:0xB0].decode() == code and rom[0xBC] == rev, file
        rcrc = crc(rom)
        row = rows[rcrc]
        assert len(row) == len(slots), (len(row), len(slots))
        cfg = config(cfg_name)
        action, move = (cfg.get("actionCursor"), cfg.get("moveCursor")) if "actionCursor" in cfg else CURSORS_REV0
        party = cfg["playerParty"]
        ram = sorted({v for v in row if is_ram(v)} | {action, move, party_menu, party})
        ram_entries, rom_entries = [], []
        for a in ram:
            s = sample(sites(rom, a))
            assert s, f"{bid}: no code loads {a:#x}"
            ram_entries.append((a, [(u, p, crc(window(rom, u, p))) for u, p in s]))
        for i, v in enumerate(row):
            if is_rom(v):
                s = sample(sites(rom, v))
                assert s or slots[i] in OPTIONAL, f"{bid}: no code loads {slots[i]} {v:#x}"
                rom_entries.append((i, [(u, p, crc(window(rom, u, p))) for u, p in s]))
        optional = [i for i, n in enumerate(slots) if n in OPTIONAL]
        out.append(f"""    RetailEngineBuild(
        id = "{bid}", code = "{code}", rev = {rev}, retailCrc = 0x{rcrc:08X}.toInt(),
        actionCursor = 0x{action:08X}L, moveCursor = 0x{move:08X}L, partyMenu = 0x{party_menu:08X}L, playerParty = 0x{party:08X}L,
        wideRow = "{",".join(f"{v:x}" for v in row)}",
        optionalSlots = setOf({", ".join(map(str, optional))}),
        ram =
            {chunk(kt_sites(ram_entries))},
        rom =
            {chunk(kt_sites(rom_entries))},
    ),""")
        n_ram = sum(len(s) for _, s in ram_entries)
        n_rom = sum(len(s) for _, s in rom_entries)
        print(f"{bid}: {len(ram_entries)} RAM globals ({n_ram} sites), {len(rom_entries)} ROM slots ({n_rom} sites)")
    OUT.write_text(f"""package com.pokedaisy.app.companion.data

// Generated by scripts/gen_retail_engine.py from the retail ROMs and pk_wide_games.h - don't edit. Offsets and
// CRC32s only, no ROM bytes. ram / rom: address (or EXPAND slot) = instruction:pool word:window CRC, hex.

val RETAIL_ENGINE_BUILDS = listOf(
{chr(10).join(out)}
)
""")


if __name__ == "__main__":
    main()
