#!/usr/bin/env python3
"""Generates PokeDaisy's display tables for a Generation 1 Game Boy Pokémon game
straight from the ROM the user owns (nothing is downloaded):

    scripts/gen_gen1_tables.py yellow <rom.gb>
    scripts/gen_gen1_tables.py yellow_de|yellow_fr|yellow_it|yellow_es <rom.gb>
    scripts/gen_gen1_tables.py red|blue <rom.gb> <english yellow.gb>
    scripts/gen_gen1_tables.py purered <PureRed v2.7.6.gb>
    scripts/gen_gen1_tables.py pureblue <PureBlue v2.7.6.gb> <PureRed v2.7.6.gb>
    scripts/gen_gen1_tables.py yellow_legacy <Yellow Legacy v1.0.10.gb> <english yellow.gb>

Yellow Legacy v1.0.10 (cRz-Shadows/Pokemon_Yellow_Legacy, a pret/pokeyellow fork: its `make` with rgbds 0.6.1 at tag
V1.0.10 is the patched cart byte for byte, so its pokeyellow.sym names every table) writes *YellowLegacy.kt: Yellow's
shapes at its own addresses, with its own moves, TMs, chart and growth rates. Its species, types, items and maps are
Yellow's (checked, `shares`), so only the rest is written.

PureRed / PureBlue v2.7.6 (Vortyne/pureRGB, a pret/pokered fork: its `make red blue` with rgbds 1.0.3 is both
releases byte for byte, so its pokered.sym names every table) write one set, *PureRgb.kt: PureBlue's tables are
PureRed's at the same addresses (pureblue checks it and writes nothing). PureRGB changed the tables' shapes: move and
item names behind pointer tables, 35-byte base data (MEW in the main table), 5-byte InternalMapEntries, types of its
own (NONE, CRYSTAL, TRI, FLOATING, MAGMA and BONEMERANG's second GROUND) and forms (FLOATING WEEZING, the spirits:
internal species carrying a base species' Dex number, its NonDexPokemonSpecies - left out of the names, which go by
Dex number).

Red and Blue (pret/pokered's builds, = retail) write nothing: every table, read from their own
addresses (pokered.sym), is checked equal to Yellow's, so the app keeps one set (the Yellow files).

Writes app/src/main/kotlin/.../companion/data/{SpeciesNames,SpeciesTypes,MoveData,
ItemNames,TypeChart,MapSecData,Gen1Species}<Suffix>.kt; for the German, French, Italian and
Spanish carts, only their names (YellowText<Lang>Gen.kt): their species ids, types, moves' types
and powers, base stats and type chart are English's byte for byte (checked here), their map ids too.
The European carts' addresses come from scripts/port_gen1.py (English's code, matched in theirs),
each checked by its contents below; they print with a European character set of their own
(German / French, Italian / Spanish: read off each cart's FontGraphics).

Addresses (bank:address) are pret/pokeyellow's symbols: its `make` output is
byte-identical to the retail ROM (sha1 cc7d0326...), so pokeyellow.sym names
every table. Names keep the game's own ALL-CAPS casing, as the game prints them.

Gen 1 keeps its species in an internal order (RHYDON is 1) - the party / battle
structs store that index, PokedexOrder maps it to the National Dex number, and
every table here is keyed by the Dex number (the reader converts on the way in).
"""
import hashlib
import os
import struct
import sys

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "kotlin",
                       "com", "pokedaisy", "app", "companion", "data")

GAMES = {
    "yellow": dict(
        suffix="Yellow",
        label="Pokemon Yellow Version (USA, Europe)",
        sha1="cc7d03262ebfaf2f06772c1a480c7d9d5f4a38e1",
        monster_names=(0x3A, 0x4000), name_len=10, internal_count=190,
        pokedex_order=(0x10, 0x50B1),
        base_stats=(0x0E, 0x43DE), base_stats_size=28, dex_count=151,
        moves=(0x0E, 0x4000), move_count=165, move_names=(0x2F, 0x4000),
        item_names=(0x01, 0x45B7), item_count=0x61,
        tm_moves=(0x04, 0x632D),   # TechnicalMachines: TM01-50 then HM01-05, a move id each
        type_names=(0x09, 0x7D63), type_count=0x1B,
        type_effects=(0x0F, 0x65FA),
        external_maps=(0x1C, 0x539C), internal_maps=(0x1C, 0x540B), first_indoor_map=0x25, map_count=0xF8,
        checks=dict(species={1: "BULBASAUR", 25: "PIKACHU", 150: "MEWTWO", 151: "MEW"},
                    types={1: (0x16, 0x03), 25: (0x17, 0x17), 81: (0x17, 0x17), 35: (0x00, 0x00)},
                    moves={1: ("POUND", 0, 40), 44: ("BITE", 0, 60), 16: ("GUST", 0, 40), 85: ("THUNDERBOLT", 0x17, 95)},
                    items={1: "MASTER BALL", 4: "POKé BALL", 20: "POTION", 0xC9: "TM01", 0xC4: "HM01"},
                    maps={0: "PALLET TOWN", 1: "VIRIDIAN CITY", 0x0C: "ROUTE 1"}),
    ),
    # Pokémon Red / Blue (USA, Europe): pret/pokered builds both byte for byte (its roms.sha1), and pokered.sym
    # puts every table below at the same address in both. MEW's base stats sit apart (MewBaseStats, bank 1).
    **{v: dict(
        suffix=v.title(),
        label=f"Pokemon {v.title()} Version (USA, Europe)",
        sha1=sha1, same_as="yellow",
        monster_names=(0x07, 0x421E), name_len=10, internal_count=190,
        pokedex_order=(0x10, 0x5024),
        base_stats=(0x0E, 0x43DE), base_stats_size=28, dex_count=151, mew_base_stats=(0x01, 0x425B),
        moves=(0x0E, 0x4000), move_count=165, move_names=(0x2C, 0x4000),
        item_names=(0x01, 0x472B), item_count=0x61,
        tm_moves=(0x04, 0x7773),
        type_names=(0x09, 0x7DAE), type_count=0x1B,
        type_effects=(0x0F, 0x6474),
        external_maps=(0x1C, 0x5313), internal_maps=(0x1C, 0x5382), first_indoor_map=0x25, map_count=0xF8,
        checks=dict(species={1: "BULBASAUR", 25: "PIKACHU", 150: "MEWTWO", 151: "MEW"},
                    types={1: (0x16, 0x03), 25: (0x17, 0x17), 81: (0x17, 0x17), 35: (0x00, 0x00), 151: (0x18, 0x18)},
                    moves={1: ("POUND", 0, 40), 44: ("BITE", 0, 60), 16: ("GUST", 0, 40), 85: ("THUNDERBOLT", 0x17, 95)},
                    items={1: "MASTER BALL", 4: "POKé BALL", 20: "POTION", 0xC9: "TM01", 0xC4: "HM01"},
                    maps={0: "PALLET TOWN", 1: "VIRIDIAN CITY", 0x0C: "ROUTE 1"}),
    ) for v, sha1 in (("red", "ea9bcae617fdf159b045185467ae58b2e4a48b9a"),
                      ("blue", "d7037c83e1ae5b39bde3c30787637ba1d4c48ce2"))},
    # PureRed / PureBlue v2.7.6 (Vortyne/pureRGB at tag v2.7.6: pokered.sym / pokeblue.sym put every table here at the
    # same address in both builds).
    **{v: dict(
        suffix="PureRgb", charset="PURE", growth_rates=True, town_map="regionmap/pure_red.png / pure_blue.png",
        label=f"Pokemon Pure{v[4:].title()} v2.7.6",
        sha1=sha1, **({"same_as": "purered"} if v == "pureblue" else {}),
        monster_names=(0x33, 0x55C6), name_len=10, internal_count=0xBE, non_dex=(0x2C, 0x5465),
        pokedex_order=(0x10, 0x5155),
        base_stats=(0x2C, 0x6445), base_stats_size=35, dex_count=151,
        moves=(0x0E, 0x458D), move_count=165, move_name_ptrs=(0x2C, 0x4000),
        item_name_ptrs=(0x01, 0x430F), item_count=0x53,
        tm_moves=(0x04, 0x7E19),
        type_names=(0x0E, 0x4050), type_count=0x1B,
        type_effects=(0x0F, 0x645A),
        external_maps=(0x1C, 0x4D85), internal_maps=(0x1C, 0x4DF4), internal_entry_size=5,
        first_indoor_map=0x25, map_count=0xF8,
        checks=dict(species={1: "BULBASAUR", 25: "PIKACHU", 110: "WEEZING", 150: "MEWTWO", 151: "MEW"},
                    types={1: (0x16, 0x03), 25: (0x17, 0x17), 35: (0x00, 0x00), 151: (0x18, 0x18)},
                    moves={1: ("POUND", 0, 40), 85: ("THUNDERBOLT", 0x17, 95)},
                    items={1: "MASTER BALL", 4: "POKé BALL", 5: "HYPER BALL", 20: "POTION", 0xC9: "TM01", 0xC4: "HM01"},
                    maps={0: "PALLET TOWN", 7: "SAFFRON CITY", 8: "FUCHSIA CITY", 0x0C: "ROUTE 1"}),
    ) for v, sha1 in (("purered", "2e94d09c1e16a57eb079404c030f26fdeeac949d"),
                      ("pureblue", "d419fe244fa17196f7df46ddab47c840e7573652"))},
    # Yellow Legacy v1.0.10: every address from its own pokeyellow.sym.
    "yellow_legacy": dict(
        suffix="YellowLegacy", growth_rates=True, shares=("SpeciesNames", "SpeciesTypes", "ItemNames", "MapSecData"),
        # Its status moves are BIRD, id 6: no chart entry, so nothing resists them; the game prints the slot as NORMAL.
        typeless=6,
        label="Pokemon Yellow Legacy v1.0.10",
        sha1="2ac87de42afc70ef6016805acb3b206644417f97",
        monster_names=(0x3A, 0x4000), name_len=10, internal_count=190,
        pokedex_order=(0x10, 0x5279),
        base_stats=(0x3B, 0x5272), base_stats_size=28, dex_count=151,
        moves=(0x0E, 0x4000), move_count=165, move_names=(0x2F, 0x4000),
        item_names=(0x01, 0x45A9), item_count=0x61,
        tm_moves=(0x04, 0x65B5),
        type_names=(0x09, 0x7DF2), type_count=0x1B,
        type_effects=(0x0F, 0x6622),
        external_maps=(0x1C, 0x543A), internal_maps=(0x1C, 0x54A9), first_indoor_map=0x25, map_count=0xF8,
        checks=dict(species={1: "BULBASAUR", 25: "PIKACHU", 150: "MEWTWO", 151: "MEW"},
                    types={},
                    moves={1: ("POUND", 0, 40)},
                    items={1: "MASTER BALL", 4: "POKé BALL", 20: "POTION", 0xC9: "TM01", 0xC4: "HM01"},
                    maps={0: "PALLET TOWN", 1: "VIRIDIAN CITY", 0x0C: "ROUTE 1"}),
    ),
    # The European carts: English's code, their own text. Addresses from scripts/port_gen1.py, checked by contents.
    **{f"yellow_{lang}": dict(
        suffix=f"Yellow{lang.title()}", label=label, sha1=sha1, names_only=True, charset=cs, machine_prefixes=pre,
        monster_names=(0x3A, 0x4000), name_len=10, internal_count=190,
        pokedex_order=(0x10, order), base_stats=(0x0E, 0x43DE), base_stats_size=28, dex_count=151,
        moves=(0x0E, 0x4000), move_count=165, move_names=(0x2F, 0x4000),
        item_names=(0x01, 0x45B8), item_count=0x61,
        type_effects=(0x0F, effects),
        external_maps=(0x1C, ext), internal_maps=(0x1C, intl), first_indoor_map=0x25, map_count=0xF8,
        checks=checks)
       for lang, label, sha1, cs, pre, order, effects, ext, intl, checks in [
        ("de", "Pokemon - Gelbe Edition (Germany)", "42f3714eec6eca25200d42461ff08d57c98f6d1d", "DF", ("TM", "VM"),
         0x5023, 0x6609, 0x5399, 0x5408,
         dict(species={1: "BISASAM", 25: "PIKACHU", 150: "MEWTU"}, moves={85: "DONNERBLITZ"},
              items={1: "MEISTERBALL", 4: "POKéBALL", 20: "TRANK"}, maps={0: "ALABASTIA", 0x0C: "ROUTE 1"})),
        ("fr", "Pokemon - Version Jaune (France)", "0aceec0ef7aa2ca5aa831554598d91f61a925591", "DF", ("CT", "CS"),
         0x5036, 0x6610, 0x53A0, 0x540F,
         dict(species={1: "BULBIZARRE", 25: "PIKACHU", 150: "MEWTWO"}, moves={85: "TONNERRE"},
              items={1: "MASTER BALL", 4: "POKé BALL", 20: "POTION"}, maps={0: "BOURG PALETTE", 0x0C: "ROUTE 1"})),
        ("it", "Pokemon - Versione Gialla (Italy)", "05bb8e99f24d498613930949730afa8024e77d08", "IS", ("MT", "MN"),
         0x5043, 0x660C, 0x539C, 0x540B,
         dict(species={1: "BULBASAUR", 25: "PIKACHU"}, moves={85: "FULMINE"},
              items={1: "MASTER BALL", 4: "POKé BALL", 20: "POZIONE"}, maps={0: "BIANCAVILLA", 0x0C: "PERCORSO 1"})),
        ("es", "Pokemon - Edicion Amarilla (Spain)", "1dc242039218fba50928d1afb66b70565b6b9daf", "IS", ("MT", "MO"),
         0x5041, 0x660A, 0x539F, 0x540E,
         dict(species={1: "BULBASAUR", 25: "PIKACHU"}, moves={85: "RAYO"},
              items={1: "MASTER BALL", 4: "POKé BALL", 20: "POCIÓN"}, maps={0: "PUEBLO PALETA", 0x0C: "RUTA 1"})),
    ]},
}

CHARS = {0x7F: " ", 0xBA: "é", 0xE0: "'", 0xE3: "-", 0xE6: "?", 0xE7: "!", 0xE8: ".", 0xEF: "♂", 0xF5: "♀",
         0xF3: "/", 0xF4: ",", 0x9A: "(", 0x9B: ")", 0x9C: ":", 0x9D: ";", 0x9E: "[", 0x9F: "]", 0xF2: ".",
         0x54: "POKé", 0x4A: "PKMN", 0xE1: "PK", 0xE2: "MN", 0xBB: "'d", 0xBC: "'l", 0xBD: "'s", 0xBE: "'t",
         0xBF: "'v", 0xE4: "'r", 0xE5: "'m", 0x75: "…", 0xF1: "×", 0x4E: " ", 0x4F: " "}
CHARS.update({0x80 + i: chr(65 + i) for i in range(26)})
CHARS.update({0xA0 + i: chr(97 + i) for i in range(26)})
CHARS.update({0xF6 + i: str(i) for i in range(10)})

# The European carts drop English's 'd 'l 's 't 'v ligatures at 0xBB-0xBF for accented letters
# (FontGraphics tiles 0xBA-0xDF, read off each cart's font).
_EU = {k: v for k, v in CHARS.items() if not 0xBA <= k <= 0xBF and k not in (0xE4, 0xE5)}
CHARSETS = {
    "EN": CHARS,
    # PureRGB (constants/charmap.asm, home/text.asm's TextShortcutCommandJumpTable): one-byte words from 0x33, quotes
    # where the brackets were, an arrow, + and %.
    "PURE": {**CHARS, **dict(zip(range(0x33, 0x48), ["like", "an", "You", "er", "with", "at", "ight", "have", " a ", "here",
                                                     "to ", " to", "or", "ing", "the", "you", "opponent", "user",
                                                     "POKéMON", "TRAINER TIPS", "TEAM "])),
             0x4D: "is", 0x56: "...", 0x5B: "PC", 0x5C: "TM", 0x5D: "TRAINER", 0x5E: "ROCKET", 0x74: "·",
             0x9E: "“", 0x9F: "”", 0xE9: "→", 0xEA: "+", 0xEB: "%"},
    "DF": {**_EU, **dict(zip(range(0xBA, 0xCD), "àèéùßçÄÖÜäöüëïâôûêî")), 0xE4: "+",
           **dict(zip(range(0xD4, 0xE0), ["c'", "d'", "j'", "l'", "m'", "n'", "p'", "s'", "'s", "t'", "u'", "y'"]))},
    "IS": {**_EU, **dict(zip(range(0xBA, 0xD8), "àèéùÀÁÄÖÜäöüÈÉÌÍÑÒÓÙÚáìíñòóúº&")), 0xE4: "¿", 0xE5: "¡",
           **dict(zip(range(0xD8, 0xDF), ["'d", "'l", "'m", "'r", "'s", "'t", "'v"]))},
}


class Rom:
    def __init__(self, data, chars=CHARS):
        self.d = data
        self.chars = chars

    @staticmethod
    def off(bank, addr):
        return addr if bank == 0 else bank * 0x4000 + (addr - 0x4000)

    def u8(self, bank, addr):
        return self.d[self.off(bank, addr)]

    def text(self, bank, addr, maxlen=40):
        out = []
        o = self.off(bank, addr)
        for b in self.d[o:o + maxlen]:
            if b == 0x50:
                return "".join(out)
            if b not in self.chars:
                raise ValueError(f"unknown char {b:02X} at {bank:02X}:{addr:04X}")
            out.append(self.chars[b])
        raise ValueError(f"unterminated text at {bank:02X}:{addr:04X}")

    def text_list(self, bank, addr, count):
        """`count` 0x50-terminated strings back to back (MoveNames, ItemNames)."""
        out = []
        for _ in range(count):
            s = self.text(bank, addr)
            out.append(s)
            o = self.off(bank, addr)
            addr += self.d.index(0x50, o) - o + 1
        return out


def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


# Set to a list, [write] collects (name, body) there instead of writing files (Red / Blue's check against Yellow).
SINK = None


def write(name, header, body):
    if SINK is not None:
        SINK.append((name, body))
        return
    path = os.path.join(OUT_DIR, name)
    with open(path, "w") as f:
        f.write("package com.pokedaisy.app.companion.data\n\n" + header + "\n" + body)
    print("wrote", os.path.relpath(path))


def kmap(name, typ, items):
    lines = [f"val {name}: Map<Int, {typ}> = mapOf("]
    lines += [f"    {k} to {v}," for k, v in items]
    return "\n".join(lines) + "\n)\n"


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in GAMES:
        sys.exit(__doc__)
    g = GAMES[sys.argv[1]]
    data = open(sys.argv[2], "rb").read()
    if hashlib.sha1(data).hexdigest() != g["sha1"]:
        sys.exit(f"{sys.argv[2]} isn't {g['label']} (sha1 {g['sha1'][:8]}...)")
    if g.get("names_only"):
        if len(sys.argv) != 4:
            sys.exit(f"usage: {sys.argv[0]} {sys.argv[1]} <rom.gb> <english yellow.gb>")
        return names_only(g, data, open(sys.argv[3], "rb").read())
    if g.get("same_as"):
        if len(sys.argv) != 4:
            sys.exit(f"usage: {sys.argv[0]} {sys.argv[1]} <rom.gb> <{g['same_as']}.gb>")
        return same_as(g, data, open(sys.argv[3], "rb").read())
    if g.get("shares"):
        if len(sys.argv) != 4:
            sys.exit(f"usage: {sys.argv[0]} {sys.argv[1]} <rom.gb> <english yellow.gb>")
        return shares_yellow(g, data, open(sys.argv[3], "rb").read())
    tables(g, data)


def shares_yellow(g, data, en):
    """A hack that kept some of Yellow's tables: those are checked equal to Yellow's and not written."""
    global SINK
    ref = GAMES["yellow"]
    if hashlib.sha1(en).hexdigest() != ref["sha1"]:
        sys.exit(f"the English cart isn't {ref['label']}")
    SINK = []
    tables(g, data)
    mine = SINK
    SINK = []
    tables(ref, en)
    theirs = dict(SINK)
    SINK = None
    for name, body in mine:
        if name.removesuffix(f"{g['suffix']}.kt") in g["shares"]:
            yellow_name = name.replace(g["suffix"], ref["suffix"])
            assert body.replace(g["suffix"], "@") == theirs[yellow_name].replace(ref["suffix"], "@"), f"{name} isn't {yellow_name}"
        else:
            head = (f"// GENERATED by scripts/gen_gen1_tables.py from the {g['label']} ROM\n"
                    f"// (sha1 {g['sha1'][:8]}...) - do not hand-edit.\n")
            write(name, head, body)


def same_as(g, data, ref_data):
    """Red / Blue: every table above, read from their own addresses, must come out as Yellow's (it does: the
    same species order, names, types, moves, items, chart and map names / cells), so they use Yellow's files."""
    global SINK
    ref = GAMES[g["same_as"]]
    if hashlib.sha1(ref_data).hexdigest() != ref["sha1"]:
        sys.exit(f"the reference cart isn't {ref['label']}")
    SINK = []
    tables(g, data)
    mine = SINK
    SINK = []
    tables(ref, ref_data)
    theirs = SINK
    SINK = None
    for (name, body), (rname, rbody) in zip(mine, theirs):
        assert body.replace(g["suffix"], "@") == rbody.replace(ref["suffix"], "@"), f"{name} isn't {rname}"
    print(f"{g['label']}: every table equals {ref['label']}'s - nothing to write")


def tables(g, data):
    rom = Rom(data, CHARSETS[g.get("charset", "EN")])
    sfx, c = g["suffix"], g["checks"]
    head = (f"// GENERATED by scripts/gen_gen1_tables.py from the {g['label']} ROM\n"
            f"// (sha1 {g['sha1'][:8]}...) - do not hand-edit.")

    # Species: internal index -> Dex number, names by internal index, base stats by Dex number.
    ob, oa = g["pokedex_order"]
    internal_to_dex = [0] + [rom.u8(ob, oa + i) for i in range(g["internal_count"])]
    mb, ma = g["monster_names"]
    names = {}
    forms = set()
    if "non_dex" in g:   # PureRGB's forms: names of their own ("TORCHED") over a base species' Dex number
        o = rom.off(*g["non_dex"])
        while data[o] != 0xFF:
            forms.add(data[o])
            o += 1
    for idx in range(1, g["internal_count"] + 1):
        dex = internal_to_dex[idx]
        if dex == 0 or idx in forms:
            continue  # MISSINGNO. slots, forms
        assert dex not in names, f"Dex No. {dex} named twice"
        raw = data[rom.off(mb, ma + (idx - 1) * g["name_len"]):][:g["name_len"]]
        names[dex] = "".join(rom.chars[b] for b in raw.split(b"\x50")[0])
    bb, ba = g["base_stats"]
    types = {}
    for dex in range(1, g["dex_count"] + 1):
        o = rom.off(bb, ba + (dex - 1) * g["base_stats_size"])
        if dex == 151 and "mew_base_stats" in g:
            o = rom.off(*g["mew_base_stats"])
        assert data[o] == dex, f"base stats {dex}: dex id {data[o]}"
        types[dex] = (data[o + 6], data[o + 7])
    assert all(names[k] == v for k, v in c["species"].items()), "species names"
    assert all(types[k] == v for k, v in c["types"].items()), "species types"

    # Moves: 6-byte entries (animation, effect, power, type, accuracy, pp) + MoveNames.
    if "move_name_ptrs" in g:   # PureRGB: MoveNameJumpTable, a pointer per move into the same bank
        nb, na = g["move_name_ptrs"]
        move_names = [rom.text(nb, struct.unpack_from("<H", data, rom.off(nb, na + 2 * i))[0]) for i in range(g["move_count"])]
    else:
        nb, na = g["move_names"]
        move_names = rom.text_list(nb, na, g["move_count"])
    mvb, mva = g["moves"]
    moves = {}
    for i in range(g["move_count"]):
        o = rom.off(mvb, mva + i * 6)
        assert data[o] == i + 1, f"move {i + 1}: animation {data[o]}"
        moves[i + 1] = (move_names[i], data[o + 3], data[o + 2])
    assert all(moves[k] == v for k, v in c["moves"].items()), "moves"

    # Items: ItemNames for 1..item_count; HM01-05 (0xC4-0xC8) and TM01-50 (0xC9-0xFA)
    # are named by the game's own code, the way it prints them.
    if "item_name_ptrs" in g:   # PureRGB: ItemNameJumpTable, likewise
        ib, ia = g["item_name_ptrs"]
        items = {i + 1: rom.text(ib, struct.unpack_from("<H", data, rom.off(ib, ia + 2 * i))[0]) for i in range(g["item_count"])}
    else:
        ib, ia = g["item_names"]
        items = {i + 1: n for i, n in enumerate(rom.text_list(ib, ia, g["item_count"]))}
    items.update({0xC4 + i: f"HM{i + 1:02d}" for i in range(5)})
    items.update({0xC9 + i: f"TM{i + 1:02d}" for i in range(50)})
    assert all(items[k] == v for k, v in c["items"].items()), "items"

    # Types: a pointer table (the unused ids point at NORMAL - left out) + TypeEffects
    # (attacker, defender, x10 multiplier; 0xFF ends it).
    tb, ta = g["type_names"]
    type_names = {}
    for t in range(g["type_count"]):
        p = struct.unpack_from("<H", data, rom.off(tb, ta + 2 * t))[0]
        n = rom.text(tb, p)
        if n == "NORMAL" and t != 0 and t != g.get("typeless"):
            continue
        type_names[t] = n
    eb, ea = g["type_effects"]
    chart = {}
    o = rom.off(eb, ea)
    while data[o] != 0xFF:
        atk, dfn, mult = data[o], data[o + 1], data[o + 2]
        chart[atk * 100 + dfn] = mult * 10
        o += 3

    # Map names + town-map tiles: ExternalMapEntries (outdoor maps, 3 bytes: yx nibbles,
    # name pointer) and InternalMapEntries (4 bytes: last map id + 1 of the group, yx, name; PureRGB adds a 5th, the
    # map whose wild Pokémon its town map lists).
    xb, xa = g["external_maps"]
    ib2, ia2 = g["internal_maps"]
    internal = []
    o = rom.off(ib2, ia2)
    while data[o] != 0xFF:
        internal.append((data[o], data[o + 1], struct.unpack_from("<H", data, o + 2)[0]))
        o += g.get("internal_entry_size", 4)
    maps = {}
    for m in range(g["map_count"]):
        if m < g["first_indoor_map"]:
            o = rom.off(xb, xa + 3 * m)
            yx, p = data[o], struct.unpack_from("<H", data, o + 1)[0]
        else:
            entry = next((e for e in internal if m < e[0]), None)
            if entry is None:
                continue
            yx, p = entry[1], entry[2]
        maps[m] = (rom.text(xb, p), yx & 0xF, yx >> 4)
    assert all(maps[k][0] == v for k, v in c["maps"].items()), "maps"

    # TMs / HMs: Gen 1 gives items no description text; the TM's own move says what it does.
    tb2, ta2 = g["tm_moves"]
    tm = {}
    for i in range(55):
        mv = data[rom.off(tb2, ta2 + i)]
        item = 0xC9 + i if i < 50 else 0xC4 + (i - 50)
        tm[item] = f"Teaches {moves[mv][0]} to a POKéMON." + (" Can be used again." if i >= 50 else "")
    write(f"ItemDescriptionsTm{sfx}.kt", head + " TechnicalMachines: what each TM / HM teaches\n"
          "// (the game has no item descriptions; ItemDescriptionsYellow.kt has the rest, hand-written).\n",
          kmap(f"tmDescriptions{sfx}", "String", [(k, kstr(v)) for k, v in sorted(tm.items())]))

    growth = ""
    if g.get("growth_rates"):   # PureRGB moved 25 species to other curves (Gen 1's ids are Gen 3's: 0, 3, 4, 5)
        rates = [data[rom.off(bb, ba + (dex - 1) * g["base_stats_size"]) + 19] for dex in range(1, g["dex_count"] + 1)]
        assert set(rates) <= {0, 3, 4, 5}, "growth rates"
        growth = (f"\n// BaseStats' growth rate by Dex number (index 0 unused), the ids ExpTables.kt's curves go by.\n"
                  f"val gen1GrowthRates{sfx} = intArrayOf(\n    0,\n" + "".join(f"    {r},\n" for r in rates) + ")\n")
    write(f"Gen1Species{sfx}.kt", head + " PokedexOrder: the party / battle structs'\n"
          "// internal species index -> National Dex number (0 = MISSINGNO.).\n",
          f"val gen1InternalToDex{sfx} = intArrayOf(\n" +
          "".join(f"    {d},\n" for d in internal_to_dex) + ")\n" + growth)
    write(f"SpeciesNames{sfx}.kt", head + " MonsterNames, keyed by National Dex number.\n",
          kmap(f"speciesNames{sfx}", "String", [(k, kstr(names[k])) for k in sorted(names)]))
    write(f"SpeciesTypes{sfx}.kt", head + f" BaseStats types, ids per typeNames{sfx}.\n",
          kmap(f"speciesTypeData{sfx}", "SpeciesTypes", [(k, f"SpeciesTypes({a}, {b})") for k, (a, b) in sorted(types.items())]))
    write(f"MoveData{sfx}.kt", head + " MoveNames + Moves (type, power).\n",
          kmap(f"moveData{sfx}", "MoveInfo", [(k, f"MoveInfo({kstr(n)}, {t}, {p})") for k, (n, t, p) in sorted(moves.items())]))
    write(f"ItemNames{sfx}.kt", head + " ItemNames, plus the HMs / TMs the game names in code.\n",
          kmap(f"itemNames{sfx}", "String", [(k, kstr(v)) for k, v in sorted(items.items())]))
    write(f"TypeChart{sfx}.kt", head + " TypeNames (Gen 1 ids: 0-8 Normal..Ghost,\n"
          "// 20-26 Fire..Dragon) and TypeEffects: key = atkType*100+defType -> percent, pairs not\n"
          "// listed are 100. Gen 1's own chart, Ghost -> Psychic 0x included.\n",
          kmap(f"typeNames{sfx}", "String", [(k, kstr(v)) for k, v in sorted(type_names.items())]) + "\n" +
          kmap(f"typeEffectiveness{sfx}", "Int", sorted(chart.items())))
    # A town-map coordinate is where the game puts the player's 16x16 head (OAM (x*8+24, y*8+24),
    # TownMapCoordsToOAMCoords): its top-left 8px cell, i.e. the place's own square, is (x+2, y+1).
    write(f"MapSecData{sfx}.kt", head + " Map names by map id (wCurMap), from the town\n"
          "// map's ExternalMapEntries / InternalMapEntries; x / y = the place's 8px cell on the town map\n"
          f"// ({g.get('town_map', 'regionmap/yellow.png')}, which Gen1Art rebuilds from the ROM).\n",
          kmap(f"mapSecData{sfx}", "MapSecInfo", [(k, f"MapSecInfo({kstr(n)}, 0, {x + 2}, {y + 1}, 1, 1)") for k, (n, x, y) in sorted(maps.items())]))
    print(f"{len(names)} species, {len(moves)} moves, {len(items)} items, {len(type_names)} types, {len(maps)} maps")


def names_only(g, data, en):
    """A European cart: its names over English's tables, after checking those tables are English's bytes."""
    e = GAMES["yellow"]
    if hashlib.sha1(en).hexdigest() != e["sha1"]:
        sys.exit(f"the English cart isn't {e['label']}")
    rom, eng = Rom(data, CHARSETS[g["charset"]]), Rom(en)
    for what, n in (("pokedex_order", g["internal_count"]), ("base_stats", 28 * 151), ("moves", 6 * 165), ("type_effects", 3 * 82)):
        (b, a), (eb, ea) = g[what], e[what]
        assert data[rom.off(b, a):][:n] == en[eng.off(eb, ea):][:n], f"{what} isn't English's"
    ob, oa = g["pokedex_order"]
    internal_to_dex = [0] + [rom.u8(ob, oa + i) for i in range(g["internal_count"])]
    mb, ma = g["monster_names"]
    species = {}
    for idx in range(1, g["internal_count"] + 1):
        dex = internal_to_dex[idx]
        if dex:
            raw = data[rom.off(mb, ma + (idx - 1) * g["name_len"]):][:g["name_len"]]
            species[dex] = "".join(rom.chars[b] for b in raw.split(b"\x50")[0])
    nb, na = g["move_names"]
    moves = {i + 1: n for i, n in enumerate(rom.text_list(nb, na, g["move_count"]))}
    ib, ia = g["item_names"]
    items = {i + 1: n for i, n in enumerate(rom.text_list(ib, ia, g["item_count"]))}
    tm, hm = g["machine_prefixes"]
    items.update({0xC4 + i: f"{hm}{i + 1:02d}" for i in range(5)})
    items.update({0xC9 + i: f"{tm}{i + 1:02d}" for i in range(50)})
    # The prefixes as the game's GetMachineName writes them (two letters, no terminator, in bank 0).
    pre = bytes(0x80 + ord(c) - 65 for c in tm + hm)
    assert data[:0x4000].count(pre) == 1, "machine prefixes"
    xb, xa = g["external_maps"]
    ib2, ia2 = g["internal_maps"]
    internal, o = [], rom.off(ib2, ia2)
    while data[o] != 0xFF:
        internal.append((data[o], data[o + 1], struct.unpack_from("<H", data, o + 2)[0]))
        o += 4
    # The town map cells must be English's: the app's map boxes are English's (mapSecDataYellow).
    eint, o = [], eng.off(*e["internal_maps"])
    while en[o] != 0xFF:
        eint.append(en[o:o + 2])
        o += 4
    assert [bytes(x[:2]) for x in internal] == eint, "internal map entries"
    maps = {}
    for m in range(g["map_count"]):
        if m < g["first_indoor_map"]:
            o = rom.off(xb, xa + 3 * m)
            assert data[o] == en[eng.off(*e["external_maps"]) + 3 * m], f"map {m}'s cell"
            p = struct.unpack_from("<H", data, o + 1)[0]
        else:
            entry = next((x for x in internal if m < x[0]), None)
            if entry is None:
                continue
            p = entry[2]
        maps[m] = rom.text(xb, p)
    c = g["checks"]
    for what, got in (("species", species), ("moves", moves), ("items", items), ("maps", maps)):
        for k, v in c[what].items():
            assert got[k] == v, f"{what} {k}: {got[k]!r} != {v!r}"
    head = (f"// GENERATED by scripts/gen_gen1_tables.py from the {g['label']} ROM\n"
            f"// (sha1 {g['sha1'][:8]}...) - do not hand-edit. Its names over English Yellow's tables, by the same\n"
            "// ids (Dex number, move id, item id, map id); everything else is English's byte for byte.\n")
    sfx = g["suffix"]
    write(f"YellowText{sfx[6:]}Gen.kt", head,
          kmap(f"speciesNames{sfx}", "String", [(k, kstr(v)) for k, v in sorted(species.items())]) + "\n" +
          kmap(f"moveNames{sfx}", "String", [(k, kstr(v)) for k, v in sorted(moves.items())]) + "\n" +
          kmap(f"itemNames{sfx}", "String", [(k, kstr(v)) for k, v in sorted(items.items())]) + "\n" +
          kmap(f"mapNames{sfx}", "String", [(k, kstr(v)) for k, v in sorted(maps.items())]))
    print(f"{len(species)} species, {len(moves)} moves, {len(items)} items, {len(maps)} maps")


if __name__ == "__main__":
    main()
