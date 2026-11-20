#!/usr/bin/env python3
"""Generates Pokémon Amethyst's species / item name and move tables from the ROM
the user owns (closed binary, no source; nothing is downloaded).

    scripts/gen_amethyst_tables.py <version> <rom.gba> [--check] [--only=species,items,moves]

Writes shared/src/commonMain/kotlin/.../companion/data/{SpeciesNames,ItemNames,MoveData}<Suffix>.kt
(--only picks some: v1.3.0's moves were added after its hand-extracted species names).
--check compares against the files already there instead. Both versions' item
names come from it; v1.3.0's species names are still the hand extraction that
came before it, which leaves out UNOWN's !/? forms and TYPE: NULL (--check
shows those three).

  - species names: an 11-byte Gen 3 text array indexed by Amethyst's own
    species ids (no struct around it); "?" placeholders are left out.
  - items: 44-byte struct Item records, name (14 bytes) at +0, keyed by the
    slot index - what the game's own name lookup indexes (gItems[itemId]); the
    record's u16 itemId field at +14 is stale in a few slots. 723 of them
    (0..722, garbage past that); "????????" placeholders are left out.
  - moves (GitHub #96: every move past Gen 3's 354 read "Move#N"): gMoveNames'
    13-byte names - the game's own, so "ThunderPunch" (no long-name table: CFRU
    hacks like Unbound have one, Amethyst doesn't) - and gBattleMoves' 12-byte
    records, power at +1, type at +2 (CFRU type ids, Fairy 23). Each the copy
    the code references (41 / 430 literal-pool words; vanilla's are still in the
    ROM, unreferenced). 828 of them: the records stop being moves at 829. Both
    versions' are the same, so v1.4.1 shares v1.3.0's MoveDataAmethyst.kt
    (moves_suffix; its --check compares against that).

Each version's addresses come from the literal pools of the code that reads
them (gen_cfru_party_assets.py has the matching gBaseStats / gender ratios).
"""
import hashlib
import os
import re
import sys

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "shared", "src", "commonMain", "kotlin",
                       "com", "pokedaisy", "app", "companion", "data")

VERSIONS = {
    "v1.3.0": dict(suffix="Amethyst", val="Amethyst", sha1="00e70c0384a5f1698588034201fd5b849d3542e2",
                   species_names=0x0986315C, species=1268, items=0x0872DB0C, item_slots=723,
                   move_names=0x08931964, battle_moves=0x089B6358, moves=828),
    # v1.4.1: 26 Hisuian forms inserted at 1234 push the Gigantamax forms up by 26
    # (species 0..1293); ~90 more items fill v1.3.0's "????????" slots.
    "v1.4.1": dict(suffix="AmethystV141", val="AmethystV141", sha1="91291aade04b4b111cd03ae7b6e2ff460e1edd8a",
                   species_names=0x09864F38, species=1294, items=0x0872DB0C, item_slots=723,
                   move_names=0x08958398, battle_moves=0x089FFCA8, moves=828, moves_suffix="Amethyst"),
}

CHARS = {0x00: " ", 0x1B: "é", 0xAB: "!", 0xAC: "?", 0xAD: ".", 0xAE: "-", 0xB0: "…", 0xB4: "'",
         0xB5: "♂", 0xB6: "♀", 0xB8: ",", 0xBA: "/", 0xF0: ":", 0x5B: "%"}
CHARS.update({0xA1 + i: str(i) for i in range(10)})
CHARS.update({0xBB + i: chr(65 + i) for i in range(26)})
CHARS.update({0xD5 + i: chr(97 + i) for i in range(26)})


def text(rom, off, n):
    out = []
    for b in rom[off:off + n]:
        if b == 0xFF:
            break
        if b not in CHARS:
            return None
        out.append(CHARS[b])
    s = "".join(out).strip()
    return s if s and set(s) != {"?"} else None


def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def species_names(rom, v):
    base = v["species_names"] - 0x08000000
    out = {}
    for i in range(1, v["species"]):
        n = text(rom, base + i * 11, 11)
        if n:
            out[i] = n
    return out


def item_names(rom, v):
    base = v["items"] - 0x08000000
    out = {}
    for slot in range(1, v["item_slots"]):
        n = text(rom, base + slot * 44, 14)
        if n:
            out[slot] = n
    return out


def move_data(rom, v):
    names, stats = v["move_names"] - 0x08000000, v["battle_moves"] - 0x08000000
    out = {}
    for i in range(1, v["moves"] + 1):
        n = text(rom, names + i * 13, 13)
        if n:
            out[i] = (n, rom[stats + i * 12 + 2], rom[stats + i * 12 + 1])
    return out


def kt_moves(name, entries):
    return f"val {name}: Map<Int, MoveInfo> = mapOf(\n" + \
        "".join(f"    {i} to MoveInfo({kstr(n)}, {t}, {p}),\n" for i, (n, t, p) in sorted(entries.items())) + ")\n"


def kt_map(name, entries):
    return f"val {name}: Map<Int, String> = mapOf(\n" + \
        "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(entries.items())) + ")\n"


def existing(path):
    src = open(path, encoding="utf-8").read()
    moves = re.findall(r'^\s+(\d+) to MoveInfo\("((?:[^"\\]|\\.)*)", (\d+), (\d+)\),$', src, re.M)
    if moves:
        return {int(a): (b.replace('\\"', '"').replace("\\\\", "\\"), int(t), int(p)) for a, b, t, p in moves}
    return {int(a): b.replace('\\"', '"').replace("\\\\", "\\")
            for a, b in re.findall(r'^\s+(\d+) to "((?:[^"\\]|\\.)*)",$', src, re.M)}


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in VERSIONS:
        sys.exit(f"usage: {sys.argv[0]} <{'|'.join(VERSIONS)}> <rom.gba> [--check]")
    v = VERSIONS[sys.argv[1]]
    rom = open(sys.argv[2], "rb").read()
    sha1 = hashlib.sha1(rom).hexdigest()
    if sha1 != v["sha1"]:
        sys.exit(f"sha1 {sha1} is not Amethyst {sys.argv[1]} ({v['sha1']})")
    species, items, moves = species_names(rom, v), item_names(rom, v), move_data(rom, v)
    assert species[551] == "Tepig" and species[1] == "Bulbasaur", "species table address is wrong"
    assert items[1] == "Master Ball" and items[13] == "Potion", "item table address is wrong"
    # The issue's two (#96), Gen 3's first and a Fairy move (CFRU's type 23).
    for i, m in {1: ("Pound", 0, 40), 7: ("Fire Punch", 10, 75), 475: ("Worry Seed", 12, 0),
                 602: ("Power Trip", 17, 20), 461: ("Fairy Wind", 23, 40)}.items():
        assert moves.get(i) == m, ("move table address is wrong", i, moves.get(i), m)
    files = {f"SpeciesNames{v['suffix']}.kt": (f"speciesNames{v['val']}", species),
             f"ItemNames{v['suffix']}.kt": (f"itemNames{v['val']}", items),
             f"MoveData{v.get('moves_suffix', v['suffix'])}.kt": (f"moveData{v.get('moves_suffix', v['val'])}", moves)}
    only = next((a.split("=", 1)[1].split(",") for a in sys.argv if a.startswith("--only=")), None)
    if only:
        kinds = {"species": "SpeciesNames", "items": "ItemNames", "moves": "MoveData"}
        files = {f: e for f, e in files.items() if any(f.startswith(kinds[k]) for k in only)}
    if "--check" in sys.argv:
        for f, (_, got) in files.items():
            want = existing(os.path.join(OUT_DIR, f))
            bad = sorted(set(want) ^ set(got)) + [i for i in want if i in got and want[i] != got[i]]
            print(f, "matches" if not bad else f"differs at {bad[:20]}")
        return
    what = {"species": "11-byte species name array, keyed by Amethyst's own species ids",
            "items": "struct Item names, keyed by item id (the slot)",
            "moves": "gMoveNames (13-byte names, as the game shows them) + gBattleMoves' type / power, by move id"}
    for f, (name, entries) in files.items():
        kind = "species" if f.startswith("Species") else "items" if f.startswith("Item") else "moves"
        header = (f"package com.pokedaisy.app.companion.data\n\n"
                  f"// Generated by scripts/gen_amethyst_tables.py from Pokemon Amethyst {sys.argv[1]}\n"
                  f"// (sha1 {v['sha1'][:8]}...) - do not hand-edit. {what[kind]}.\n")
        with open(os.path.join(OUT_DIR, f), "w", encoding="utf-8") as fh:
            fh.write(header + (kt_moves(name, entries) if kind == "moves" else kt_map(name, entries)))
        print("wrote", f, len(entries))


if __name__ == "__main__":
    main()
