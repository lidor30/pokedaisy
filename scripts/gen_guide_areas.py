#!/usr/bin/env python3
"""
Generates the GUIDE's per-area data (HERE's ITEMS and PEOPLE sections) from a
pret decomp checkout - pokefirered or pokeemerald at the commit build.sh pins:

  - ITEMS: item balls (map.json object events whose script is a `finditem`)
    and hidden items (map.json bg_events of type hidden_item), each with the
    flag the game sets once it's picked up;
  - PEOPLE: what a map's own scripts hand out - `giveitem` / `giveitem_msg`,
    `givemon`, `giveegg` - with the flag that marks it received, and the
    in-game trades (`setvar VAR_0x8008, INGAME_TRADE_*` -> the trade table's
    species / requestedSpecies, plus its FLAG_DID_* flag).

Everything is keyed by region map section (MAPSEC_*, numbered by their order
in src/data/region_map/region_map_sections.json - the value the game keeps in
gMapHeader.regionMapSectionId), so an area's buildings count with it: a city's
houses and marts share the city's section. Each thing carries the sub-map it's
on, humanised from the map's name ("RustboroCity_CuttersHouse" -> "CUTTERS
HOUSE"; "" = the area's main map).

Output is a Kotlin file of compact text lines, parsed lazily by GuideAreas.kt
(a mapOf() this big would overflow a JVM method):

  mapsec|kind|id|flag|qty|wants|where

    python3 scripts/gen_guide_areas.py firered $DECOMPS/pokefirered \
        > shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data/GuideAreasFireRedGen.kt
    python3 scripts/gen_guide_areas.py leafgreen $DECOMPS/pokefirered \
        > shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data/GuideAreasLeafGreenGen.kt
    python3 scripts/gen_guide_areas.py emerald $DECOMPS/pokeemerald \
        > shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data/GuideAreasEmeraldGen.kt
    python3 scripts/gen_guide_areas.py ruby $DECOMPS/pokeruby \
        > shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data/GuideAreasRubySapphireGen.kt
    python3 scripts/gen_guide_areas.py legacy $DECOMPS/pokelegacy \
        > shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data/GuideAreasEmeraldLegacyGen.kt

`legacy` is Pokémon Emerald Legacy v1.1.4's own tree (cRz-Shadows/Pokemon_Emerald_Legacy at V1.1.4, an older
pokeemerald): its map sections are #defines (the json names them `map_section`), its system flags move with its
MAX_TRAINERS_COUNT (opponents.h), and its key items are tagged KEY for its generated WHERE IS.

pokeruby (Ruby / Sapphire share one file - their maps are the same) has no
trade.h: its trades are read positionally from src/trade.c.
"""
import glob
import json
import os
import re
import subprocess
import sys

GAME, ROOT = sys.argv[1], sys.argv[2]
NAME = {"firered": "FIRERED", "leafgreen": "LEAFGREEN", "emerald": "EMERALD", "ruby": "RUBY_SAPPHIRE", "hns": "HEART_AND_SOUL",
        "legacy": "EMERALD_LEGACY"}[GAME]
# The build define the sources' version conditionals test (pokefirered builds
# both games from one tree: `.ifdef LEAFGREEN` in scripts, `#if
# defined(LEAFGREEN)` in C - trades, Game Corner prizes). Ruby / Sapphire
# share one file, generated as Ruby.
VERSION = {"firered": "FIRERED", "leafgreen": "LEAFGREEN", "emerald": "EMERALD", "ruby": "RUBY", "hns": "HNS", "legacy": "EMERALD"}[GAME]


def preprocess(text):
    """[text] with the other versions' conditional branches dropped:
    `.ifdef X` / `.ifndef X` / `.else` / `.endif` (scripts) and
    `#if defined(X)` / `#ifdef X` / `#elif defined(X)` / `#else` / `#endif` (C).
    Any other #if is kept whole (both branches), as before."""
    out, stack = [], []  # stack entries: [taking this branch, any branch taken yet, ours to judge]
    for line in text.splitlines():
        t = line.strip()
        # Heart and Soul's tree also switches on IS_FRLG / IS_HNS (constants/global.h).
        m = (re.match(r"\.ifdef\s+(\w+)$", t) or re.match(r"#ifdef\s+(\w+)$", t)
             or re.match(r"#if\s+defined\((\w+)\)$", t) or re.match(r"#if\s+IS_(FRLG|HNS)$", t))
        mn = re.match(r"\.ifndef\s+(\w+)$", t)
        me = re.match(r"#elif\s+defined\((\w+)\)$", t) or re.match(r"#elif\s+IS_(FRLG|HNS)$", t)
        if m or mn:
            yes = (m.group(1) == VERSION) if m else (mn.group(1) != VERSION)
            stack.append([yes, yes, True])
            continue
        if me and stack and stack[-1][2]:
            yes = not stack[-1][1] and me.group(1) == VERSION
            stack[-1][0] = yes
            stack[-1][1] |= yes
            continue
        if t in (".else", "#else") and stack and stack[-1][2]:
            stack[-1][0] = not stack[-1][1]
            stack[-1][1] = True
            continue
        if t in (".endif", "#endif") and stack and stack[-1][2]:
            stack.pop()
            continue
        if t.startswith("#if"):
            stack.append([True, True, False])
        elif t == "#endif" and stack:
            stack.pop()
        if all(s[0] for s in stack):
            out.append(line)
    return "\n".join(out)


def evaluate(expr, table, depth=0):
    if depth > 20:
        raise ValueError(expr)
    expr = re.sub(r"\b([A-Z_][A-Z0-9_]*)\b",
                  lambda m: "(%d)" % evaluate(table[m.group(1)], table, depth + 1) if m.group(1) in table else m.group(1),
                  expr)
    return int(eval(expr))


def enum_values(path, prefix, table):
    """Values of a C enum whose members start with prefix (items.h in pokeemerald)."""
    src = open(path, encoding="utf-8").read()
    value = 0
    # Every member counts towards the numbering (expansion's item enum has
    # helpers like FIRST_MAIL_INDEX between the ITEM_s); all are kept.
    for m in re.finditer(r"^\s*([A-Z][A-Z0-9_]*)\s*(=\s*([^,/\n]+))?,", src, re.M):
        if m.group(1) in table:
            value = evaluate(table[m.group(1)], table) + 1
            continue
        if m.group(3):
            value = evaluate(m.group(3), table)
        table[m.group(1)] = str(value)
        value += 1



def defines(path, seen=None):
    """#defines in [path] and the constants headers it #includes (expansion
    splits flags.h into flags_frlg.h / flags_hns.h ...)."""
    seen = seen if seen is not None else set()
    if path in seen or not os.path.exists(path):
        return {}
    seen.add(path)
    out = {}
    for line in preprocess(open(path, encoding="utf-8").read()).splitlines():
        inc = re.match(r'#include\s+"(constants/\w+\.h)"', line)
        if inc:
            out.update(defines(os.path.join(ROOT, "include", inc.group(1)), seen))
        m = re.match(r"#define\s+(\w+)\s+(.+?)\s*(//.*)?$", line)
        if m:
            out[m.group(1)] = m.group(2)
    return out



C = {}
# opponents.h: MAX_TRAINERS_COUNT places the system flags (an older tree keeps it there, not in flags.h's includes).
for rel in ["include/constants/opponents.h", "include/constants/flags.h", "include/constants/species.h", "include/constants/items.h"]:
    C.update(defines(os.path.join(ROOT, rel)))
if "ITEM_POTION" not in C:
    enum_values(os.path.join(ROOT, "include/constants/items.h"), "ITEM_", C)
# pokeemerald names HMs by move (ITEM_HM_CUT = ITEM_HM01 ...), from tms_hms.h's list.
tmhm = os.path.join(ROOT, "include/constants/tms_hms.h")
if os.path.exists(tmhm) and "ITEM_HM01" in C:
    txt = open(tmhm, encoding="utf-8").read()
    for kind in ["TM", "HM"]:
        block = re.search(r"#define FOREACH_%s\(F\)(.*?)\n\n" % kind, txt, re.S)
        if block:
            for i, move in enumerate(re.findall(r"F\((\w+)\)", block.group(1))):
                C["ITEM_%s_%s" % (kind, move)] = "%d" % (evaluate("ITEM_%s01" % kind, C) + i)


def num(name):
    """A constant's value; None when it isn't one (or aliases a name no header defines)."""
    try:
        return evaluate(C[name], C) if name in C else None
    except (NameError, SyntaxError, ValueError):
        return None


sections_json = json.load(open(os.path.join(ROOT, "src/data/region_map/region_map_sections.json")))
if GAME == "hns":
    # Its own list, numbered from 1 (region_map_sections.constants.json.txt, IS_HNS):
    # NEW BARK TOWN is 63 = 0x3F, what the live map header reads there.
    MAPSEC = {s["id"]: i + 1 for i, s in enumerate(sections_json["hns_map_sections"])}
else:
    # An older tree (Emerald Legacy) names each entry's constant `map_section` instead of `id`.
    MAPSEC = {s.get("id", s.get("map_section")): i for i, s in enumerate(sections_json["map_sections"])}

# Script label -> item, for item balls (data/scripts/item_ball_scripts.inc and map scripts).
FIND = {}
for path in glob.glob(os.path.join(ROOT, "data/**/*.inc"), recursive=True):
    src = preprocess(open(path, encoding="utf-8").read())
    # pokeruby's labels end in an address comment ("Label:: @ 81B1A44").
    for m in re.finditer(r"^(\w+)::\s*(?:@.*)?\n\s*finditem\s+(ITEM_\w+)(?:\s*,\s*(\d+))?", src, re.M):
        FIND[m.group(1)] = (m.group(2), int(m.group(3) or 1))

# In-game trades: index -> (species you get, species they want).
TRADES = {}
trade_src = next((p for p in [os.path.join(ROOT, "src/data/ingame_trades.h"), os.path.join(ROOT, "src/data/trade.h")] if os.path.exists(p)), None)
if trade_src:
    for m in re.finditer(r"\[(INGAME_TRADE_\w+)\]\s*=\s*\{(.*?)\n\s*\}", preprocess(open(trade_src, encoding="utf-8").read()), re.S):
        body = m.group(2)
        get = re.search(r"\.species\s*=\s*(SPECIES_\w+)", body).group(1)
        want = re.search(r"\.requestedSpecies\s*=\s*(SPECIES_\w+)", body).group(1)
        TRADES[m.group(1)] = (num(get), num(want))
else:
    # pokeruby: positional gIngameTrades[] in src/trade.c (the first copy; a
    # German one follows), each {name, species, ..., playerSpecies}; scripts
    # pick one with a plain `setvar VAR_0x8008, N`.
    src = open(os.path.join(ROOT, "src/trade.c"), encoding="utf-8").read()
    table = re.search(r"gIngameTrades\[\]\s*=\s*\{(.*?)\n\};", src, re.S).group(1)
    table = re.split(r"\n#(?:else|elif)", table)[0]
    for i, body in enumerate(re.findall(r"\{(.*?)\}", table, re.S)):
        sp = re.findall(r"SPECIES_\w+", body)
        TRADES[str(i)] = (num(sp[0]), num(sp[-1]))


# Key items (src/data/items.h's .pocket = POCKET_KEY_ITEMS), tagged KEY instead of
# GIFT so the generated WHERE IS can list them. Only for games whose guide has no
# hand-written WHERE IS (Heart and Soul, Emerald Legacy); FireRed / Emerald keep plain GIFTs.
KEY_ITEMS = set()
if GAME in ("hns", "legacy"):
    items_src = preprocess(open(os.path.join(ROOT, "src/data/items.h"), encoding="utf-8").read())
    for m in re.finditer(r"\[(ITEM_\w+)\]\s*=\s*\{(.*?)\n\s*\},", items_src, re.S):
        if "POCKET_KEY_ITEMS" in m.group(2) and num(m.group(1)):
            KEY_ITEMS.add(num(m.group(1)))


def humanise(map_name):
    """'RustboroCity_DevonCorp_3F' -> 'DEVON CORP 3F' (the parts after the place)."""
    parts = [p for p in map_name.split("_")[1:] if p != "hns"]
    words = []
    for p in parts:
        p = re.sub(r"(?<=[a-z])(?=[A-Z0-9])|(?<=[0-9])(?=[A-Z][a-z])|(?<=[A-Z])(?=[A-Z][a-z])", " ", p)
        words.append(p)
    text = " ".join(words).upper()
    return (text.replace("POKEMON", "POKéMON").replace("POKE MART", "POKé MART").replace("POKEMART", "POKé MART")
            .replace("POKE ", "POKé ").strip())


def blocks(script_text):
    """Label -> the lines until the next label."""
    out, label, lines = [], None, []
    for line in script_text.splitlines():
        m = re.match(r"^(\w+)::?\s*(?:@.*)?$", line)
        if m:
            if label:
                out.append((label, lines))
            label, lines = m.group(1), []
        elif label:
            lines.append(line.strip())
    if label:
        out.append((label, lines))
    return out


def received_flag(lines, at):
    """The flag a gift at lines[at] is marked with: a setflag after it, else a goto_if_set before it."""
    for line in lines[at + 1:]:
        m = re.match(r"setflag\s+(FLAG_\w+)", line)
        if m and not m.group(1).startswith(("FLAG_HIDE_", "FLAG_TEMP_")):
            return num(m.group(1)) or 0
        if line in ("end", "release", "return", "releaseall"):
            break
    for line in reversed(lines[:at]):
        m = re.match(r"goto_if_set\s+(FLAG_\w+)", line)
        if m and not m.group(1).startswith(("FLAG_HIDE_", "FLAG_TEMP_")):
            return num(m.group(1)) or 0
    return 0


rows = set()
for mj in sorted(glob.glob(os.path.join(ROOT, "data/maps/*/map.json"))):
    # Heart and Soul's tree keeps Emerald's and FRLG's maps beside its own
    # (`_hns`), sharing map sections with them: only its own count.
    if GAME == "hns" and not os.path.basename(os.path.dirname(mj)).endswith("_hns"):
        continue
    m = json.load(open(mj, encoding="utf-8"))
    sec = MAPSEC.get(m.get("region_map_section"))
    if sec is None:
        continue
    where = humanise(m["name"])
    for o in m.get("object_events", []):
        found = FIND.get(o.get("script", ""))
        if found and num(found[0]):
            rows.add((sec, "ITEM", num(found[0]), num(o.get("flag", "0")) or 0, found[1], 0, where))
    for b in m.get("bg_events", []):
        if b.get("type") == "hidden_item" and num(b.get("item", "")):
            rows.add((sec, "HIDDEN", num(b["item"]), num(b.get("flag", "0")) or 0, int(b.get("quantity", 1) or 1), 0, where))
    scripts = os.path.join(os.path.dirname(mj), "scripts.inc")
    if not os.path.exists(scripts):
        continue
    for label, lines in blocks(preprocess(open(scripts, encoding="utf-8").read())):
        for i, line in enumerate(lines):
            g = (re.match(r"giveitem\s+(ITEM_\w+)(?:\s*,\s*(\d+))?", line)
                 or re.match(r"giveitem_msg\s+\w+\s*,\s*(ITEM_\w+)(?:\s*,\s*(\d+))?", line))
            if g and num(g.group(1)):
                kind = "KEY" if num(g.group(1)) in KEY_ITEMS else "GIFT"
                rows.add((sec, kind, num(g.group(1)), received_flag(lines, i), int(g.group(2) or 1), 0, where))
            g = re.match(r"(givemon|giveegg)\s+(SPECIES_\w+)", line)
            if g and num(g.group(2)):
                rows.add((sec, "MON" if g.group(1) == "givemon" else "EGG", num(g.group(2)), received_flag(lines, i), 1, 0, where))
            g = re.match(r"setvar\s+VAR_0x8008\s*,\s*(INGAME_TRADE_\w+|\d+)", line)
            # A bare number is only a trade where the block asks for the trade's species.
            if g and g.group(1).isdigit() and not any("GetInGameTradeSpeciesInfo" in l for l in lines):
                g = None
            if g and g.group(1) in TRADES:
                get, want = TRADES[g.group(1)]
                flag = next((num(f) for f in re.findall(r"(?:goto_if_set|setflag)\s+(FLAG_\w*TRADE\w*)", "\n".join(lines))), 0) or 0
                rows.add((sec, "TRADE", get, flag, 1, want, where))

# One line per thing; the same gift in two branches (MACH / ACRO BIKE picks aside) counts once.
seen = set()
lines = []
for sec, kind, ident, flag, qty, want, where in sorted(rows):
    key = (sec, kind, ident, where) if kind != "ITEM" and kind != "HIDDEN" else (sec, kind, ident, flag, where)
    if key in seen:
        continue
    seen.add(key)
    lines.append("%d|%s|%d|%d|%d|%d|%s" % (sec, kind, ident, flag, qty, want, where))

commit = subprocess.run(["git", "-C", ROOT, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
chunks, cur = [], []
for line in lines:
    cur.append(line)
    if sum(len(x) + 1 for x in cur) > 40000:
        chunks.append(cur)
        cur = []
if cur:
    chunks.append(cur)

print("package com.pokedaisy.app.companion.data")
print()
print("// Generated by scripts/gen_guide_areas.py from %s@%s - don't edit by hand." % (os.path.basename(os.path.abspath(ROOT)), commit[:10]))
print("// %d things in %d areas. Line format: mapsec|kind|id|flag|qty|wants|where (see GuideAreas.kt)." % (len(lines), len({l.split('|')[0] for l in lines})))
print("internal val GUIDE_AREAS_%s_RAW: List<String> = listOf(" % NAME)
for chunk in chunks:
    print('    """')
    for line in chunk:
        print(line)
    print('""",')
print(")")
