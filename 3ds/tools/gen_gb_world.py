#!/usr/bin/env python3
"""Writes 3ds/core/pd_gb_world_gen.c: the Game Boy guides' worlds, from the app's own generated Kotlin.

GuideGen1Gen.kt (Yellow, Red, Blue), GuideCrystalGen.kt and GuidePolishedCrystalGen.kt hold the wild Pokémon and the
bosses' teams as raw `|` lines (Gen1World / Gen2WildTables / Gen2BossTables read them at run time). Here they become C
tables, with what those classes work out kept the same way: a slot's odds are percent x 256, so the merge rounds as the
app's does. Which boss counts as beaten comes from GuideGen1.kt's lines and the crBoss / pcBoss lists.

    python3 3ds/tools/gen_gb_world.py
"""
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DATA = os.path.join(ROOT, "app/src/main/kotlin/com/pokedaisy/app/companion/data")
OUT = os.path.join(ROOT, "3ds/core/pd_gb_world_gen.c")

GEN1_ODDS = [51, 51, 39, 25, 25, 25, 13, 13, 11, 3]  # WildMonEncounterSlotChances, of 256
POLISHED_GRASS = [30, 30, 20, 10, 5, 3, 2]
WATER_ODDS = [60, 30, 10]


def read(name):
    with open(os.path.join(DATA, name), encoding="utf-8") as f:
        return f.read()


def raw_lines(text, name):
    m = re.search(rf"val {name}: List<String> = listOf\((.*?)\n\)", text, re.S)
    if not m:
        raise SystemExit(f"no {name}")
    return [l for block in re.findall(r'"""(.*?)"""', m.group(1), re.S) for l in block.split("\n") if l.strip()]


def const(text, name):
    m = re.search(rf"const val {name} = (0x[0-9A-Fa-f]+|\d+)", text)
    if not m:
        raise SystemExit(f"no {name}")
    return int(m.group(1), 0)


def ints(text, name):
    m = re.search(rf"val {name} = intArrayOf\(([^)]*)\)", text)
    if not m:
        raise SystemExit(f"no {name}")
    return [int(x) for x in m.group(1).split(",")]


def c_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


class Writer:
    def __init__(self):
        self.out = []
        self.rows = {}

    def slots(self, prefix, rows):
        """rows of (species, level, odds x 256): a static array, shared when equal."""
        if not rows:
            return "0"
        key = tuple(rows)
        if key not in self.rows:
            name = f"{prefix}_s{len(self.rows)}"
            self.rows[key] = name
            body = ", ".join(f"{{ {s}, {l}, {o} }}" for s, l, o in rows)
            self.out.append(f"static const struct pd_gbw_slot {name}[] = {{ {body} }};")
        return self.rows[key]


def rows_ref(w, p, rows):
    return f"{{ {w.slots(p, rows)}, {len(rows)} }}"


def team_rows(w, p, name, mons):
    body = []
    for lv, sp, item, moves in mons:
        m = (moves + [0, 0, 0, 0])[:4]
        body.append(f"{{ {sp}, {lv}, {item}, {{ {m[0]}, {m[1]}, {m[2]}, {m[3]} }} }}")
    w.out.append(f"static const struct pd_gbw_mon {p}_{name}[] = {{ " + ", ".join(body) + " };")
    return f"{p}_{name}"


def parse_team(s):
    mons = []
    for m in s.split(";"):
        if not m.strip():
            continue
        f = m.split(" ")
        moves = [int(x) for x in f[3].split(",") if x] if len(f) > 3 else []
        mons.append((int(f[0]), int(f[1]), int(f[2]), moves))
    return mons


def gen2_wild(w, raw, p, grass_odds):
    grass, water, fish_of, tree_of, rock_of = {}, {}, {}, {}, {}
    fish, trees, time_fish = {}, {}, {}

    def pairs(s):
        return [(int(x.split(":")[1]), int(x.split(":")[0])) for x in s.split()]

    def rows(s):
        return [(int(x.split(":")[1]), int(x.split(":")[2]), int(x.split(":")[0])) for x in s.split()]

    for line in raw:
        f = line.split("|")
        k = f[0]
        key = (int(f[1]) << 8 | int(f[2])) if k in "GWFTR" else None
        if k == "G":
            grass[key] = ([int(x) for x in f[3].split(",")], pairs(f[4]))
        elif k == "W":
            water[key] = (int(f[3]), pairs(f[4]))
        elif k == "F":
            fish_of[key] = int(f[3])
        elif k == "T":
            tree_of[key] = int(f[3])
        elif k == "R":
            rock_of[key] = int(f[3])
        elif k == "FG":
            fish[int(f[1])] = [rows(x) for x in f[2:]]
        elif k == "TS":
            trees[int(f[1])] = (rows(f[2]), rows(f[3]) if len(f) > 3 else [])
        elif k == "TG":
            a, b = f[2].split(":"), f[3].split(":")
            time_fish[int(f[1])] = (int(a[0]), int(a[1]), int(b[0]), int(b[1]))
    n = len(grass_odds)
    maps = []
    fish_ids = sorted(fish)
    tree_ids = sorted(trees)
    for k in sorted(set(grass) | set(water) | set(fish_of) | set(tree_of) | set(rock_of)):
        rates, gs = grass.get(k, ([0, 0, 0], []))
        rates = (rates + [0, 0, 0])[:3]
        g = w.slots(p, [(sp, lv, grass_odds[i % n] * 256) for i, (sp, lv) in enumerate(gs)])
        rate, ws = water.get(k, (0, []))
        wa = w.slots(p, [(sp, lv, WATER_ODDS[i] * 256) for i, (sp, lv) in enumerate(ws)])
        fi = fish_ids.index(fish_of[k]) if k in fish_of and fish_of[k] in fish else -1
        ti = tree_ids.index(tree_of[k]) if k in tree_of and tree_of[k] in trees else -1
        ri = tree_ids.index(rock_of[k]) if k in rock_of and rock_of[k] in trees else -1
        maps.append(f"    {{ {k}, {{ {rates[0]}, {rates[1]}, {rates[2]} }}, {g}, {rate}, {wa}, {fi}, {ti}, {ri} }},")
    # A rod's odds are counts of 256; a tree's percent.
    fish_rows = []
    for i in fish_ids:
        rods = [[(sp, lv, o * 100) for sp, lv, o in r] for r in (fish[i] + [[], [], []])[:3]]
        fish_rows.append("    { { " + ", ".join(rows_ref(w, p, r) for r in rods) + " } },")
    tree_rows = []
    for i in tree_ids:
        c, r = trees[i]
        tree_rows.append("    { " + rows_ref(w, p, [(s, l, o * 256) for s, l, o in c]) + ", " + rows_ref(w, p, [(s, l, o * 256) for s, l, o in r]) + " },")
    time_rows = [f"    {{ {a}, {b}, {c}, {d} }}," for a, b, c, d in (time_fish[i] for i in sorted(time_fish))]
    if time_fish and sorted(time_fish) != list(range(len(time_fish))):
        raise SystemExit("time fish groups aren't 0..n")
    return maps, fish_rows, tree_rows, time_rows


def gen2_bosses(w, text, guide_text, p, id_prefix, boss_fn, raw, faithful=None):
    keys, by_class = {}, {}
    order = []
    for line in raw:
        f = line.split("|")
        cls = int(f[1]) << 8 | int(f[2])
        keys[f[0]] = cls
        by_class[cls] = parse_team(f[4])
        order.append(cls)
    alt = {}
    for line in faithful or []:
        f = line.split("|")
        alt[int(f[1]) << 8 | int(f[2])] = parse_team(f[4])
    teams, index = [], {}
    for i, cls in enumerate(order):
        n = team_rows(w, p, f"t{i}", by_class[cls])
        fn, fc = "0", 0
        if cls in alt:
            fn = team_rows(w, p, f"f{i}", alt[cls])
            fc = len(alt[cls])
        teams.append(f"    {{ {n}, {len(by_class[cls])}, {fn}, {fc} }},")
        index[cls] = i

    def flag(name):
        return const(text, name)

    bosses = []
    pat = re.compile(rf'^\s*{boss_fn}\("([^"]+)", "([^"]+)", (\w+), "(\w+)"(?:, (\w+)\((\w+)\))?\)(?: \{{ p -> (.*) \}})?,?\s*$', re.M)
    for m in pat.finditer(guide_text):
        title, where, done, key, rule, own, lam = m.groups()
        a, b, kind = 0, 0, "PD_GBW_DONE_ANY"
        d = 0 if done == "0" else flag(done)
        if rule and rule.endswith("League"):
            a, b = flag(f"{id_prefix}_EVENT_BEAT_ELITE_FOUR"), flag(own)
        elif rule and rule.endswith("Rematch"):
            a, b, kind = flag(f"{id_prefix}_EVENT_BEAT_ELITE_FOUR_AGAIN"), flag(own), "PD_GBW_DONE_REMATCH"
        elif lam:
            names = re.findall(r"p\.flag\((\w+)\)", lam)
            if " && " not in lam or len(names) != 2:
                raise SystemExit(f"can't read {lam}")
            a, b, kind = flag(names[0]), flag(names[1]), "PD_GBW_DONE_BOTH"
        else:
            a = d
        bosses.append(f"    {{ {c_str(title)}, {c_str(where)}, {kind}, {a}u, {b}u, {index[keys[key]]}, 1, 0 }},")
    calls = len(re.findall(rf"^\s*{boss_fn}\(\"", guide_text, re.M))
    if calls != len(bosses):
        raise SystemExit(f"{boss_fn}: read {len(bosses)} of {calls}")
    return teams, bosses


def wram(a):
    """D000-DFFF in WRAM bank 1, as the host's read takes it; below, the bus."""
    return 0x1D000 + (a - 0xD000) if a >= 0xD000 else a


def emit_guide(w, name, p, parts, fields):
    maps, fish, trees, time_fish, teams, bosses = parts
    lines = []
    for label, kind, rows in (("maps", "pd_gbw_wild_map", maps), ("fish", "pd_gbw_fish", fish), ("trees", "pd_gbw_trees", trees),
                              ("time", "pd_gbw_time_fish", time_fish), ("teams", "pd_gbw_team", teams), ("bosses", "pd_gbw_boss", bosses)):
        if rows:
            lines += [f"static const struct {kind} {p}_{label}[] = {{"] + rows + ["};"]
    ref = lambda label, rows: f".{label} = {p}_{label}, .{label}Count = {len(rows)}," if rows else ""
    lines.append(f"const struct pd_gbw_world {name} = {{")
    for label, rows in (("maps", maps), ("fish", fish), ("trees", trees), ("time", time_fish), ("teams", teams), ("bosses", bosses)):
        r = ref(label, rows)
        if r:
            lines.append("    " + r)
    lines += ["    " + x for x in fields] + ["};", ""]
    w.out += lines


def main():
    w = Writer()
    w.out += ["// GENERATED by 3ds/tools/gen_gb_world.py from the app's GuidePolishedCrystalGen.kt - do not hand-edit.",
              '#include "pd_gb_world.h"', ""]
    pc = read("GuidePolishedCrystalGen.kt")
    pcg = read("GuidePolishedCrystal.kt")
    maps, fish, trees, time_fish = gen2_wild(w, raw_lines(pc, "PC_WILD_RAW"), "gp", POLISHED_GRASS)
    teams, bosses = gen2_bosses(w, pc, pcg, "gp", "PC", "pcBoss", raw_lines(pc, "PC_BOSSES_RAW"), raw_lines(pc, "PC_BOSSES_FAITHFUL_RAW"))
    c = lambda n: const(pc, f"PC_{n}")
    base = ints(pc, "PC_BADGE_BASE_LEVELS")
    w.out.append(f"static const uint8_t gp_badge_levels[] = {{ {', '.join(map(str, base))} }};")
    emit_guide(w, "pd_gbw_polished", "gp", (maps, fish, trees, time_fish, teams, bosses), [
        ".gen = 2, .grassSlots = 7, .times = 3, .surfBoost = 4,",
        f".eventFlags = {wram(c('EVENT_FLAGS'))}u, .eventBytes = {c('EVENT_FLAG_BYTES')},",
        f".banks = {{ {{ {wram(c('BADGES'))}u, 2 }}, {{ {wram(c('TRADE_FLAGS'))}u, {c('TRADE_FLAG_BYTES')} }} }}, .bankCount = 2,",
        f".timeOfDay = {wram(c('TIME_OF_DAY'))}u, .dayIndex = {c('DAY')}, .nightIndex = {c('NITE')}, .eveningIndex = {c('EVE')},",
        f".badgeLevels = gp_badge_levels, .badgeLevelCount = {len(base)}, .levelFromBadges = {c('LEVEL_FROM_BADGES')},",
        f".shoreDay = {c('SPECIES_CORSOLA')}, .shoreNight = {c('SPECIES_STARYU')},",
    ])
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(w.out) + "\n")


if __name__ == "__main__":
    main()
