#!/usr/bin/env python3
"""Generates companion/data/save/SaveTablesGen.kt - what the save editor may offer, per game family
(Ruby / Sapphire, Emerald, FireRed / LeafGreen), from the pret decomps under $DECOMPS
(pokeruby, pokeemerald, pokefirered):

- the bag items a player can hold in each pocket (every named item but key items, unused
  "????????" slots and the e-Reader's Enigma Berry), with the pocket the game files it in;
- each species' level-up learnset, TM / HM compatibility and move-tutor compatibility, the
  TM / HM moves and tutor moves, and each species' pre-evolution - so a move is offered only if
  the Pokémon could have learned it in that game.

A save on its own has no ROM to read these from, and they're numbers (ids, levels, bits), like
ExpTables.kt / MoveStatsGen.kt. Other-language releases share their English game's data.

Usage: scripts/gen_save_tables.py
"""
import json
import os
import re

from decomps import decomp

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "../shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data/save/SaveTablesGen.kt")

FAMILIES = [("RS", "pokeruby"), ("E", "pokeemerald"), ("FRLG", "pokefirered")]
POCKETS = {"POCKET_ITEMS": 1, "POCKET_POKE_BALLS": 2, "POCKET_TM_HM": 3, "POCKET_TM_CASE": 3,
           "POCKET_BERRIES": 4, "POCKET_BERRY_POUCH": 4, "POCKET_KEY_ITEMS": 5}
NUM_SPECIES = 412   # SPECIES_EGG: species 0..411


def strip_comments(text):
    return re.sub(r"//[^\n]*|/\*.*?\*/", "", text, flags=re.S)


class Game:
    def __init__(self, root):
        self.root = root

    def read(self, rel):
        with open(os.path.join(self.root, rel)) as f:
            return strip_comments(f.read())

    def defines(self, rel, prefix):
        """#define <prefix>X value, aliases resolved; plus enums starting at <prefix>NONE."""
        text = self.read(rel)
        raw = dict(re.findall(r"#define (" + prefix + r"\w+)\s+(\w+)", text))
        out = {}
        if prefix + "NONE" in text and "enum" in text:
            start = text.index(prefix + "NONE")
            body = text[start:text.index("}", start)]
            for i, n in enumerate(re.findall(r"\b(" + prefix + r"\w+)", body)):
                out[n] = i
        def value(v, depth=0):
            if re.fullmatch(r"\d+|0x[0-9A-Fa-f]+", v):
                return int(v, 0)
            if v in out:
                return out[v]
            if v in raw and depth < 8:
                return value(raw[v], depth + 1)
            return None
        for k, v in raw.items():
            r = value(v)
            if r is not None:
                out[k] = r
        return out


def items_of(g, fam, items):
    """(id, pocket) for every holdable item."""
    rows = []
    if fam == "FRLG":
        for it in json.load(open(os.path.join(g.root, "src/data/items.json")))["items"]:
            rows.append((items[it["itemId"]], it["english"], it["pocket"]))
    else:
        text = g.read("src/data/items.h" if fam == "E" else "src/data/items_en.h")
        for m in re.finditer(r'\.name\s*=\s*_\("([^"]*)"\).*?\.itemId\s*=\s*(\w+).*?\.pocket\s*=\s*(\w+)', text, re.S):
            rows.append((items[m.group(2)], m.group(1), m.group(3)))
    enigma = items["ITEM_ENIGMA_BERRY"]
    out = sorted({(i, POCKETS[p]) for i, name, p in rows
                  if i and name != "????????" and POCKETS[p] != 5 and i != enigma})
    assert len({i for i, _ in out}) == len(out), "an item in two pockets"
    return out


def level_up(g, fam, species, moves):
    text = g.read("src/data/pokemon/level_up_learnsets.h")
    sets = {}
    for m in re.finditer(r"(\w+LevelUpLearnset)\[\]\s*=\s*\{(.*?)\};", text, re.S):
        sets[m.group(1)] = [(int(l), moves[mv]) for l, mv in re.findall(r"LEVEL_UP_MOVE\(\s*(\d+),\s*(\w+)\)", m.group(2))]
    ptrs = g.read("src/data/pokemon/level_up_learnset_pointers.h")
    table = [[] for _ in range(NUM_SPECIES)]
    designated = re.findall(r"\[(SPECIES_\w+)\]\s*=\s*(\w+)", ptrs)
    if designated:
        for sp, name in designated:
            if species[sp] < NUM_SPECIES:
                table[species[sp]] = sets[name]
    else:   # pokeruby: positional, species 0 first
        names = re.findall(r"\b(g\w+LevelUpLearnset)\b", ptrs.split("{", 1)[1])
        for i, name in enumerate(names[:NUM_SPECIES]):
            table[i] = sets[name]
    return table


def tmhm(g, fam, species, items):
    text = g.read("src/data/pokemon/tmhm_learnsets.h")
    table = [0] * NUM_SPECIES
    if fam == "E":
        names = re.findall(r"F\((\w+)\)", g.read("include/constants/tms_hms.h"))
        assert len(names) == 58
        for m in re.finditer(r"\[(SPECIES_\w+)\]\s*=\s*\{\s*\.learnset\s*=\s*\{(.*?)\}\s*\}", text, re.S):
            bits = 0
            for n in re.findall(r"\.(\w+)\s*=\s*TRUE", m.group(2)):
                bits |= 1 << names.index(n)
            table[species[m.group(1)]] = bits
    else:
        first = items["ITEM_TM01_FOCUS_PUNCH"]
        for m in re.finditer(r"\[(SPECIES_\w+)\]\s*=\s*TMHM_LEARNSET\((.*?)\)\s*,?\s*(?=\[|\})", text, re.S):
            bits = 0
            for n in re.findall(r"TMHM\((\w+)\)", m.group(2)):
                bits |= 1 << (items["ITEM_" + n] - first)
            if species[m.group(1)] < NUM_SPECIES:
                table[species[m.group(1)]] = bits
    return table


def tutors(g, fam, species, moves):
    if fam == "RS":
        return [], [0] * NUM_SPECIES
    text = g.read("src/data/pokemon/tutor_learnsets.h")
    order = re.findall(r"\[TUTOR_(MOVE_\w+)\]\s*=\s*(MOVE_\w+)", text)
    tutor_moves = [moves[mv] for _, mv in order]
    index = {name: i for i, (name, _) in enumerate(order)}
    table = [0] * NUM_SPECIES
    body = text[text.index("TutorLearnsets"):]
    for m in re.finditer(r"\[(SPECIES_\w+)\]\s*=\s*(.*?)(?=,\s*\[|,?\s*\};)", body, re.S):
        bits = 0
        for n in re.findall(r"TUTOR\((MOVE_\w+)\)", m.group(2)):
            bits |= 1 << index[n]
        if species[m.group(1)] < NUM_SPECIES:
            table[species[m.group(1)]] = bits
    return tutor_moves, table


def main():
    out = {}
    tm_moves = None
    for fam, name in FAMILIES:
        g = Game(decomp(name))
        species = g.defines("include/constants/species.h", "SPECIES_")
        moves = g.defines("include/constants/moves.h", "MOVE_")
        items = g.defines("include/constants/items.h", "ITEM_")
        assert items.get("ITEM_KINGS_ROCK") == 187 and moves["MOVE_TACKLE"] == 33 and species["SPECIES_TREECKO"] == 277, fam
        tutor_moves, tutor_table = tutors(g, fam, species, moves)
        out[fam] = dict(
            items=items_of(g, fam, items),
            levelup=level_up(g, fam, species, moves),
            tmhm=tmhm(g, fam, species, items),
            tutor_moves=tutor_moves,
            tutors=tutor_table,
        )
        if fam == "E":
            names = re.findall(r"F\((\w+)\)", g.read("include/constants/tms_hms.h"))
            tm_moves = [moves["MOVE_" + n] for n in names]
            evo = g.read("src/data/pokemon/evolution.h")
            pre = [0] * NUM_SPECIES
            for m in re.finditer(r"\[SPECIES_(\w+)\]\s*=\s*\{(.*?\})\}", evo, re.S):
                for target in re.findall(r"SPECIES_(\w+)\}", m.group(2)):
                    t = species["SPECIES_" + target]
                    if t < NUM_SPECIES and pre[t] == 0:
                        pre[t] = species["SPECIES_" + m.group(1)]
    # Spot checks against the games: Bulbasaur learns Tackle at 1 and Vine Whip at 10 everywhere; its TMs include
    # TOXIC (TM06) and CUT (HM01); FireRed's tutors are 15, Emerald's 30; Ivysaur comes from Bulbasaur.
    for fam in out:
        lu = out[fam]["levelup"][1]
        assert (1, 33) in lu and (10, 22) in lu, (fam, lu)
        assert out[fam]["tmhm"][1] & (1 << 5) and out[fam]["tmhm"][1] & (1 << 50), fam
    assert len(out["FRLG"]["tutor_moves"]) == 15 and len(out["E"]["tutor_moves"]) == 30
    assert pre[2] == 1 and pre[3] == 2 and tm_moves[0] == 264 and tm_moves[50] == 15

    def ints(values, per=24):
        return "\n".join("    " + ", ".join(str(v) for v in values[i:i + per]) + "," for i in range(0, len(values), per))

    def strings(values):
        return "\n".join(f'    "{v}",' for v in values)

    with open(OUT, "w") as f:
        f.write("""package com.pokedaisy.app.companion.data.save

// GENERATED by scripts/gen_save_tables.py from pokeruby / pokeemerald / pokefirered - do not hand-edit.
// What the save editor may offer per game family: holdable bag items, and the moves each species can learn
// (level-up, TM / HM, move tutor). Species are internal indexes (Treecko = 277).
""")
        f.write(f"\n/** gTMHMMoves: TM01..TM50, then HM01..HM08 (the same in every Gen 3 game). */\ninternal val tmHmMoves = intArrayOf(\n{ints(tm_moves)}\n)\n")
        f.write(f"\n/** Each species' pre-evolution (0 = none), from gEvolutionTable. */\ninternal val preEvolution = intArrayOf(\n{ints(pre)}\n)\n")
        for fam in out:
            d = out[fam]
            flat = [x for pair in d["items"] for x in pair]
            f.write(f"\n/** {fam}: holdable items as (item id, pocket: 1 items / 2 balls / 3 TMs & HMs / 4 berries) pairs. */\n")
            f.write(f"internal val bagItems{fam} = intArrayOf(\n{ints(flat)}\n)\n")
            f.write(f"\n/** {fam}: level-up learnsets per species, each move as 5 hex digits (level, move). */\n")
            f.write(f"internal val levelUp{fam} = listOf(\n{strings(''.join('%02x%03x' % (l, mv) for l, mv in s) for s in d['levelup'])}\n)\n")
            f.write(f"\n/** {fam}: TM / HM compatibility per species, a 58-bit mask in hex (bit 0 = TM01, bit 50 = HM01). */\n")
            f.write(f"internal val tmHm{fam} = listOf(\n{strings('%x' % b for b in d['tmhm'])}\n)\n")
            f.write(f"\n/** {fam}: the move tutors' moves, and each species' tutor mask in hex (bit i = tutorMoves[i]). */\n")
            f.write(f"internal val tutorMoves{fam} = intArrayOf({', '.join(map(str, d['tutor_moves']))})\n")
            f.write(f"internal val tutors{fam} = listOf(\n{strings('%x' % b for b in d['tutors'])}\n)\n")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
