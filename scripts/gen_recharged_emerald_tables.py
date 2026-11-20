#!/usr/bin/env python3
"""Generates Pokémon Recharged Emerald's names, from the ROMs the user owns (Jaizu's pokeemerald
rebuild with modern gcc, BPEE, 16 MB, no source; nothing is downloaded). The app runs it as EMERALD
with these laid over the shared tables (ActiveTables' `emeraldHack`, a `HackText`).

    scripts/gen_recharged_emerald_tables.py <v2.2.5.gba> <v2.2.8.gba> <Rebalanced v2.2.8.gba> <retail Emerald.gba>

Every table comes from the GF ROM header (0x08000100): gSpeciesNames (+0x44), gMoveNames (+0x48),
gBaseStats (+0xBC: 28 bytes, types +6 / +7), gItems (+0xC8: 48-byte records, name[18], pocket
+0x1E, the slot is the id - vanilla's 1..376 plus its own up to 408, like Recharged Yellow's) and
gBattleMoves (+0xCC: type +2, power +1). Its text is mixed case ("Pound", "Potion"; species in
capitals), so every name is written as the ROM has it (`ownCase`). Species ids, the type ids and
gTypeEffectiveness are retail Emerald's (checked: the chart is retail's byte for byte); the names
tables run on into other languages' copies (French species, Portuguese moves: its text option),
which aren't read. v2.2.5 and v2.2.8 share every name, type and power (checked); Rebalanced
re-types nine species and changes 17 moves' type or power (v2.2.x keeps retail's). Map names stay the shared
Emerald table's (the boxes are Emerald's).
"""
import hashlib
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_emerald_lang_tables import CHARS  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "shared", "src", "commonMain", "kotlin",
                   "com", "pokedaisy", "app", "companion", "data", "RechargedEmeraldGen.kt")
EMERALD_SHA1 = "f3ae088181bf583e55daf962a92bb46f4f1d07b7"
B = 0x08000000
SPECIES, MOVES, ITEMS = 412, 355, 409
CH = dict(CHARS)


def u8(d, a):
    return d[a - B]


def u32(d, a):
    return struct.unpack_from("<I", d, a - B)[0]


def text(d, a, maxlen):
    out = []
    for b in d[a - B:a - B + maxlen]:
        if b == 0xFF:
            break
        if b in (0xFA, 0xFB, 0xFE):
            out.append(" ")
            continue
        if b not in CH:
            return None
        out.append(CH[b])
    s = " ".join("".join(out).split())
    return s if s and set(s) not in ({"?"}, {"-"}) else None


def header(d, off):
    return struct.unpack_from("<I", d, 0x100 + off)[0]


def read(d, item_stride):
    species_names, move_names = header(d, 0x44), header(d, 0x48)
    base_stats, items, battle_moves = header(d, 0xBC), header(d, 0xC8), header(d, 0xCC)
    sp, ty = {}, {}
    for i in range(1, SPECIES):
        sp[i] = text(d, species_names + 11 * i, 11)
        ty[i] = (u8(d, base_stats + 28 * i + 6), u8(d, base_stats + 28 * i + 7))
    mv = {}
    for i in range(1, MOVES):
        a = battle_moves + 12 * i
        mv[i] = (text(d, move_names + 13 * i, 13), u8(d, a + 2), u8(d, a + 1))
    it = {i: text(d, items + item_stride * i, 18 if item_stride == 48 else 14) for i in range(1, ITEMS)}
    return dict(species=sp, types=ty, moves=mv, items={k: v for k, v in it.items() if v})


def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def block(name, t, base, label):
    ty = {k: v for k, v in t["types"].items() if base["types"][k] != v}
    return (
        f"// {label}\n"
        f"internal val {name}: HackText by lazy {{\n"
        "    HackText(\n"
        "        species = mapOf(\n" +
        "".join(f"            {i} to {kstr(s)},\n" for i, s in sorted(t["species"].items()) if s) + "        ),\n"
        "        // What it types differently from retail Emerald.\n"
        "        types = mapOf(\n" +
        "".join(f"            {i} to SpeciesTypes({a}, {b}),\n" for i, (a, b) in sorted(ty.items())) + "        ),\n"
        "        moves = mapOf(\n" +
        "".join(f"            {i} to MoveInfo({kstr(n)}, {tp}, {p}),\n" for i, (n, tp, p) in sorted(t["moves"].items()) if n) +
        "        ),\n"
        "        items = mapOf(\n" +
        "".join(f"            {i} to {kstr(s)},\n" for i, s in sorted(t["items"].items())) + "        ),\n"
        "        mapSecs = emptyMap(),\n"
        "        ownCase = true,\n"
        "    )\n"
        "}\n"
    )


def main():
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    v225, v228, rb, em = (open(p, "rb").read() for p in sys.argv[1:])
    if hashlib.sha1(em).hexdigest() != EMERALD_SHA1:
        sys.exit("the last ROM isn't retail Emerald (sha1 f3ae0881...)")
    for d in (v225, v228, rb):
        assert d[0xAC:0xB0] == b"BPEE" and len(d) == 16 << 20
    base = read(em, 44)
    a, b, r = read(v225, 48), read(v228, 48), read(rb, 48)
    assert a == b, "v2.2.5 and v2.2.8 differ"
    # Retail's species names and move names (casing aside) hold; the items add their own.
    assert all((base["species"][k] or "").upper() == (v or "").upper() for k, v in b["species"].items())
    assert all((base["moves"][k][0] or "").replace(" ", "").upper() == (n or "").replace(" ", "").upper()
               for k, (n, _, _) in b["moves"].items())
    assert b["items"][61] == "Linking Cord" and b["items"][13] == "Potion" and len(b["types"]) == 411
    assert r["types"][38] == (10, 7) and sum(1 for k in r["types"] if r["types"][k] != base["types"][k]) == 9
    head = ("package com.pokedaisy.app.companion.data\n\n"
            "// GENERATED by scripts/gen_recharged_emerald_tables.py from the Pokemon Recharged Emerald ROMs\n"
            "// (v2.2.5, v2.2.8 and Rebalanced v2.2.8) - do not hand-edit. Their names in the ROM's own casing\n"
            "// and what they type differently from retail Emerald, by retail's ids (ActiveTables' emeraldHack).\n\n")
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(head + block("hackTextRechargedEmerald", b, base, "v2.2.5 and v2.2.8 (the same tables).") + "\n" +
                block("hackTextRechargedEmeraldRebalanced", r, base, "Rebalanced v2.2.8: nine species re-typed, its own move powers."))
    print("wrote", os.path.relpath(OUT))
    for n, t in (("v2.2.x", b), ("Rebalanced", r)):
        moves = sum(1 for k, (nm, tp, p) in t["moves"].items() if base["moves"][k][1:] != (tp, p))
        print(f"{n}: {len(t['species'])} species, {len(t['items'])} items, {moves} moves with retail's type / power changed")


if __name__ == "__main__":
    main()
