#!/usr/bin/env python3
"""The DS games' GUIDE data: HERE's wild Pokémon by map header and NEXT BOSS's teams, from pret's decomps.

    python3 scripts/gen_gen4_guide.py      # $DECOMPS/pokeplatinum and $DECOMPS/pokeheartgold

Writes shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data/GuideGen4PlatinumGen.kt and GuideGen4HeartGoldGen.kt:
numbers only (species, levels, items and moves by id, map header ids, trainer ids) - no text of the games', which the
companion takes from its own tables. Gen4World (GuideGen4.kt) reads them back.

Wild lines (a raw string, split into chunks under the JVM's 64 KB constant limit):
  M|<map header>|<encounter set>                    the set a map header's wild Pokémon come from
  Platinum: E|<set>|<grass rate>|<12 x level:species>|<day a,b>|<night a,b>|<surf rate>|<5 x min-max:species>|
            <old rate>|<5 slots>|<good rate>|<5 slots>|<super rate>|<5 slots>
  HG / SS:  E|<set>|<grass rate>|<12 x level:morn,day,nite>|<surf rate>|<5 slots>|<rock smash rate>|<2 slots>|
            <old rate>|<5 slots>|<good rate>|<5 slots>|<super rate>|<5 slots>|<night fish species>
Boss lines: <key>|<trainer id>|<level species item m1,m2,m3,m4;...>
Area lines (GuideAreas.kt's format, HERE's ITEMS): <place>|ITEM or HIDDEN|<item>|<flag>|<qty>|0|<sub-map, "" outdoors>
  - a place is a map section: Platinum's location name line of the map's label (mapPlaceIdsPlatinum has each map
    header's), HG / SS's map header mapsec (HG_HEADER_PLACES). A map's buildings count with the place they're in.
  - Platinum: item balls are object events running visible-item script 7000 + n (scripts_visible_items.s' n-th entry:
    VAR_0x8008 the item, VAR_0x8009 the count; done = the object's hidden flag); hidden items bg events running 8000 + n
    (include/data/field/hidden_items.h: done = HIDDEN_ITEM_FLAGS_START + n).
  - HG / SS: item balls run std_itemball_* = 7000 + n (scr_seq_0141.s' n-th: VAR_SPECIAL_x8008 / x8009; done = the
    object's FLAG_HIDE_ITEMBALL_*), hidden items std_hiddenitem_* = 8000 + n (sHiddenItemParam[n]: done =
    HIDDEN_ITEMS_FLAG_BASE + its flag index).

Platinum: map_headers.h's wildEncountersArchiveID -> pl_enc_data.naix's index -> res/field/encounters/<name>.json;
trainers generated/trainers.txt (id = line), res/trainers/data/<name>.json. HeartGold / SoulSilver: map_headers.h's
wildEncounterBank -> encounter_tables_narc.h's ENCDATA index -> gs_enc_data.json's entry (each game's branch where
they differ); trainers files/poketool/trainer/trainers.json (id = index).
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data")


def lines(path):
    with open(path) as f:
        return [l.strip() for l in f if l.strip()]


def defines(path, prefix):
    out = {}
    for m in re.finditer(r"#define\s+(" + prefix + r"\w+)\s+(0x[0-9A-Fa-f]+|\d+)\b", open(path).read()):
        out.setdefault(m.group(1), int(m.group(2), 0))
    return out


def chunks(rows, size=40000):
    out, cur = [], ""
    for r in rows:
        if cur and len(cur) + len(r) + 1 > size:
            out.append(cur)
            cur = ""
        cur += r + "\n"
    if cur:
        out.append(cur)
    return out


def kraw(name, rows, doc):
    s = f"/** {doc} */\ninternal val {name}: List<String> = listOf(\n"
    for c in chunks(rows):
        s += '    """\n' + c + '""",\n'
    return s + ")\n"


def platinum(dec):
    species = {s: i for i, s in enumerate(lines(os.path.join(dec, "generated/species.txt")))}
    moves = {s: i for i, s in enumerate(lines(os.path.join(dec, "generated/moves.txt")))}
    items = {s: i for i, s in enumerate(lines(os.path.join(dec, "generated/items.txt")))}
    headers = lines(os.path.join(dec, "generated/map_headers.txt"))
    src = open(os.path.join(dec, "include/data/map_headers.h")).read()
    enc_of = dict(re.findall(r"\[(MAP_HEADER_\w+)\]\s*=\s*\{[^}]*?\.wildEncountersArchiveID\s*=\s*(\w+)", src, re.S))
    naix = {}
    for path in [os.path.join(r, f) for r, _, fs in os.walk(os.path.join(dec, "build")) for f in fs if f == "pl_enc_data.naix"]:
        naix = {m.group(1): int(m.group(2)) for m in re.finditer(r"#define (\w+) (\d+)", open(path).read())}
        break
    assert naix["encounters_eterna_forest"] == 8, naix.get("encounters_eterna_forest")

    def sp(s):
        return species[s] if s and s != "SPECIES_NONE" else 0

    rows = []
    sets = {}
    for i, h in enumerate(headers):
        e = enc_of.get(h)
        if e and e != "ENCOUNTERS_NONE":
            rows.append(f"M|{i}|{naix[e]}")
            sets[naix[e]] = e
    assert rows, "no map header has wild Pokémon"
    for idx, name in sorted(sets.items()):
        d = json.load(open(os.path.join(dec, "res/field/encounters", name + ".json")))
        land = " ".join(f"{m['level']}:{sp(m['species'])}" for m in d["land_encounters"])
        assert len(d["land_encounters"]) == 12, name
        def water(key):
            return " ".join(f"{m['level_min']}-{m['level_max']}:{sp(m['species'])}" for m in d[key])
        rows.append("|".join([
            "E", str(idx), str(d["land_rate"]), land,
            ",".join(str(sp(s)) for s in d["day"]), ",".join(str(sp(s)) for s in d["night"]),
            str(d["surf_rate"]), water("surf_encounters"),
            str(d["old_rod_rate"]), water("old_rod_encounters"),
            str(d["good_rod_rate"]), water("good_rod_encounters"),
            str(d["super_rod_rate"]), water("super_rod_encounters"),
        ]))
    eterna = json.load(open(os.path.join(dec, "res/field/encounters/encounters_eterna_forest.json")))
    assert eterna["land_encounters"][0]["species"] == "SPECIES_BUNEARY"

    trainers = lines(os.path.join(dec, "generated/trainers.txt"))
    bosses = []
    for key, name in [("ROARK", "leader_roark"), ("GARDENIA", "leader_gardenia"), ("FANTINA", "leader_fantina"),
                      ("MAYLENE", "leader_maylene"), ("WAKE", "leader_wake"), ("BYRON", "leader_byron"),
                      ("CANDICE", "leader_candice"), ("VOLKNER", "leader_volkner"), ("AARON", "elite_four_aaron"),
                      ("BERTHA", "elite_four_bertha"), ("FLINT", "elite_four_flint"), ("LUCIAN", "elite_four_lucian"),
                      ("CYNTHIA", "champion_cynthia")]:
        tid = trainers.index("TRAINER_" + name.upper())
        d = json.load(open(os.path.join(dec, "res/trainers/data", name + ".json")))
        team = ";".join(
            f"{p['level']} {species[p['species']]} {items.get(p.get('item') or 'ITEM_NONE', 0)} "
            + ",".join(str(moves[m]) for m in (p.get("moves") or []))
            for p in d["party"])
        bosses.append(f"{key}|{tid}|{team}")
    vf = defines(os.path.join(dec, "build/generated/vars_flags.h"), "FLAG_")
    assert vf["FLAG_DEFEATED_TRAINER_LEADER_ROARK"] == 1360 + trainers.index("TRAINER_LEADER_ROARK")

    # HERE's items: each map header's events, by the place of its label.
    places = json.load(open(os.path.join(dec, "res/text/location_names.json")))["messages"]
    place_index = {m["id"]: i for i, m in enumerate(places) if m["en_US"]}
    label_of = dict(re.findall(r"\[(MAP_HEADER_\w+)\]\s*=\s*\{[^}]*?\.mapLabelTextID\s*=\s*(\w+)", src, re.S))
    events_of = dict(re.findall(r"\[(MAP_HEADER_\w+)\]\s*=\s*\{[^}]*?\.eventsArchiveID\s*=\s*(\w+)", src, re.S))
    matrix = json.load(open(os.path.join(dec, "res/field/matrices/map_matrix_000.json")))["headers"]
    outdoors = {h for r in matrix for h in r}
    vis_src = open(os.path.join(dec, "res/field/scripts/scripts_visible_items.s")).read()
    entries = re.findall(r"^\s*ScriptEntry (\w+)", vis_src, re.M)
    def body(label):
        m = re.search(r"^" + label + r":\n(.*?)\n\s*(?:End|GoTo)", vis_src, re.S | re.M)
        return m.group(1) if m else ""
    hid_src = open(os.path.join(dec, "include/data/field/hidden_items.h")).read()
    hidden = {vf[f]: (items[i], int(q)) for i, q, f in
              re.findall(r"HIDDEN_ITEM_ENTRY\((ITEM_\w+),\s*(\d+),\s*\d+,\s*(FLAG_\w+)\)", hid_src)}
    hid_start = vf["HIDDEN_ITEM_FLAGS_START"] if "HIDDEN_ITEM_FLAGS_START" in vf else 730
    areas = []
    for h in headers:
        place = place_index.get(label_of.get(h))
        ev = events_of.get(h)
        if place is None or not ev or ev == "events_empty":
            continue
        path = os.path.join(dec, "res/field/events", ev + ".json")
        if not os.path.exists(path):
            continue
        where = "" if h in outdoors else h[len("MAP_HEADER_"):].replace("_", " ")
        d = json.load(open(path))
        def num(v):
            try:
                return int(v, 0) if isinstance(v, str) else int(v)
            except ValueError:
                return -1
        for o in d["object_events"]:
            sc = num(o["script"])
            if 7000 <= sc < 8000 and sc - 7000 < len(entries):
                b = body(entries[sc - 7000])
                it = re.search(r"VAR_0x8008, (ITEM_\w+)", b)
                q = re.search(r"VAR_0x8009, (\d+)", b)
                fl = vf.get(o["hidden_flag"], 0)
                if it and fl:
                    areas.append(f"{place}|ITEM|{items[it.group(1)]}|{fl}|{q.group(1) if q else 1}|0|{where}")
        for e in d["bg_events"]:
            sc = num(e["script"])
            if 8000 <= sc < 8800 and hid_start + sc - 8000 in hidden:
                it, q = hidden[hid_start + sc - 8000]
                areas.append(f"{place}|HIDDEN|{it}|{hid_start + sc - 8000}|{q}|0|{where}")
    areas = sorted(set(areas), key=lambda l: (int(l.split("|")[0]), l))
    potion = [l for l in areas if f"|ITEM|{items['ITEM_POTION']}|{vf['FLAG_OBTAINED_ROUTE_202_POTION']}|" in l]
    assert potion and potion[0].endswith("|"), potion   # ROUTE 202's item ball, outdoors

    out = os.path.join(OUT_DIR, "GuideGen4PlatinumGen.kt")
    with open(out, "w") as f:
        f.write("// Generated by scripts/gen_gen4_guide.py from pret/pokeplatinum - do not edit.\n")
        f.write("package com.pokedaisy.app.companion.data\n\n")
        f.write(f"/** TRAINER_DEFEATED_FLAGS_START: a trainer's defeated flag is this + its id. */\n")
        f.write(f"internal const val PT_TRAINER_FLAGS = 1360\n")
        f.write(f"internal const val PT_FLAG_GAME_COMPLETED = {vf['FLAG_GAME_COMPLETED']}\n\n")
        f.write(kraw("PT_WILD_RAW", rows, "Platinum's wild Pokémon (see the generator's doc for the line formats)."))
        f.write("\n" + kraw("PT_BOSSES_RAW", bosses, "Platinum's gym leaders, Elite Four and champion: key|trainer id|team."))
        f.write("\n" + kraw("PT_AREAS_RAW", areas, "Platinum's item balls and hidden items by place (GuideAreas.kt's lines)."))
    print(f"wrote {out}: {len(sets)} encounter sets, {len(bosses)} bosses, {len(areas)} items")


def heartgold(dec):
    species = defines(os.path.join(dec, "include/constants/species.h"), "SPECIES_")
    moves = defines(os.path.join(dec, "include/constants/moves.h"), "MOVE_")
    items = defines(os.path.join(dec, "include/constants/items.h"), "ITEM_")
    maps = defines(os.path.join(dec, "include/constants/maps.h"), "MAP_")
    narc = open(os.path.join(dec, "include/encounter_tables_narc.h")).read()
    enc_index = {m.group(1): int(m.group(2), 10) for m in re.finditer(r"#define (ENCDATA_\w+)\s+ENCDATA\(_(\d+)\)", narc)}
    src = open(os.path.join(dec, "src/data/map_headers.h")).read()
    enc_of = dict(re.findall(r"\[(MAP_\w+)\]\s*=\s*\{[^}]*?\.wildEncounterBank\s*=\s*(\w+)", src, re.S))
    data = json.load(open(os.path.join(dec, "files/fielddata/encountdata/gs_enc_data.json")))["encounters"]
    assert data[1]["map"] == "R29" and enc_index["ENCDATA_R29"] == 1

    def pick(v, game):
        """[v] with every {HEARTGOLD: .., SOULSILVER: ..} branch, at any depth, resolved to [game]'s."""
        if isinstance(v, dict):
            if set(v) == {"HEARTGOLD", "SOULSILVER"}:
                return pick(v[game], game)
            return {k: pick(x, game) for k, x in v.items()}
        if isinstance(v, list):
            return [pick(x, game) for x in v]
        return v

    def sp(s):
        return species[s] if s and s != "SPECIES_NONE" else 0

    out_rows = {}
    for game in ("HEARTGOLD", "SOULSILVER"):
        rows = []
        used = set()
        for m, e in enc_of.items():
            if e != "ENCDATA_NA" and m in maps:
                rows.append(f"M|{maps[m]}|{enc_index[e]}")
                used.add(enc_index[e])
        for idx in sorted(used):
            d = pick(data[idx], game)
            land = d["land"]
            grass = " ".join(
                f"{pick(m['level'], game)}:" + ",".join(str(sp(pick(m['species'][t], game))) for t in ("morn", "day", "nite"))
                for m in land["mons"]) if land["mons"] else ""
            assert not land["mons"] or len(land["mons"]) == 12, d["map"]
            assert "{" not in grass, (d["map"], grass)
            def water(block):
                return " ".join(
                    f"{pick(m['level'], game)['min']}-{pick(m['level'], game)['max']}:{sp(pick(m['species'], game))}"
                    for m in block["mons"])
            fish = d["fishing"]
            rows.append("|".join([
                "E", str(idx), str(pick(land["rate"], game)), grass,
                str(d["surf"]["rate"]), water(d["surf"]),
                str(d["rock_smash"]["rate"]), water(d["rock_smash"]),
                str(fish["old_rod"]["rate"]), water(fish["old_rod"]),
                str(fish["good_rod"]["rate"]), water(fish["good_rod"]),
                str(fish["super_rod"]["rate"]), water(fish["super_rod"]),
                str(sp(pick(d.get("nightFish"), game))),
            ]))
        out_rows[game] = rows

    trainers = json.load(open(os.path.join(dec, "files/poketool/trainer/trainers.json")))["trainers"]
    bosses = []
    # Each boss is the first trainer of its class (the later ones are the rematches).
    for key, cls in [("FALKNER", "LEADER_FALKNER"), ("BUGSY", "LEADER_BUGSY"), ("WHITNEY", "LEADER_WHITNEY"),
                     ("MORTY", "LEADER_MORTY"), ("CHUCK", "LEADER_CHUCK"), ("JASMINE", "LEADER_JASMINE"),
                     ("PRYCE", "LEADER_PRYCE"), ("CLAIR", "LEADER_CLAIR"), ("WILL", "ELITE_FOUR_WILL"),
                     ("KOGA", "ELITE_FOUR_KOGA"), ("BRUNO", "ELITE_FOUR_BRUNO"), ("KAREN", "ELITE_FOUR_KAREN"),
                     ("LANCE", "CHAMPION"), ("LT_SURGE", "LEADER_LT_SURGE"), ("SABRINA", "LEADER_SABRINA"),
                     ("ERIKA", "LEADER_ERIKA"), ("MISTY", "LEADER_MISTY"), ("JANINE", "LEADER_JANINE"),
                     ("BROCK", "LEADER_BROCK"), ("BLAINE", "LEADER_BLAINE"), ("BLUE", "LEADER_BLUE"),
                     ("RED", "PKMN_TRAINER_RED")]:
        tid = next(i for i, t in enumerate(trainers) if t["class"] == "TRAINERCLASS_" + cls)
        t = trainers[tid]
        assert t["type"] == "TRTYPE_MON_ITEM_MOVES", (cls, t["type"])
        team = ";".join(
            f"{p['level']} {species[p['species']]} {items.get(p.get('item') or 'ITEM_NONE', 0)} "
            + ",".join(str(moves[m]) for m in (p.get("moves") or []) if m != "MOVE_NONE")
            for p in t["party"])
        bosses.append(f"{key}|{tid}|{team}")
    assert bosses[0].startswith("FALKNER|20|9 16 "), bosses[0]
    fl = defines(os.path.join(dec, "include/constants/flags.h"), "")

    # HERE's items and each map header's place (its mapsec).
    mapsecs = defines(os.path.join(dec, "include/constants/map_sections.h"), "MAPSEC_")
    sec_of = dict(re.findall(r"\[(MAP_\w+)\]\s*=\s*\{[^}]*?\.mapsec\s*=\s*(\w+)", src, re.S))
    bank_of = dict(re.findall(r"\[(MAP_\w+)\]\s*=\s*\{[^}]*?\.eventsBank\s*=\s*NARC_zone_event_(\w+)_bin", src, re.S))
    matrix_of = dict(re.findall(r"\[(MAP_\w+)\]\s*=\s*\{[^}]*?\.matrixId\s*=\s*(\w+)", src, re.S))
    std = defines(os.path.join(dec, "include/constants/std_script.h"), "std_")
    ball_src = open(os.path.join(dec, "files/fielddata/script/scr_seq/scr_seq_0141.s")).read()
    def ball(n):
        m = re.search(r"^scr_seq_0141_%03d:\n(.*?)\n\s*(?:End|GoTo)" % n, ball_src, re.S | re.M)
        if not m:
            return None
        it = re.search(r"VAR_SPECIAL_x8008, (\w+)", m.group(1))
        q = re.search(r"VAR_SPECIAL_x8009, (\d+)", m.group(1))
        if not it:
            return None
        v = it.group(1)
        return (int(v) if v.isdigit() else items[v]), int(q.group(1)) if q else 1
    hid_src = open(os.path.join(dec, "src/data/fieldmap/hidden_items.h")).read()
    hidden = [(items[i], int(q), int(f)) for i, q, f in
              re.findall(r"\{\s*(ITEM_\w+),\s*(\d+),\s*\d+,\s*\d+,\s*(\d+)\s*\}", hid_src)]
    header_places = {}
    areas = []
    for m, msec in sec_of.items():
        if m not in maps or msec not in mapsecs:
            continue
        place = mapsecs[msec]
        header_places[maps[m]] = place
        bank = bank_of.get(m)
        path = os.path.join(dec, "files/fielddata/eventdata/zone_event", f"{bank}.json") if bank else ""
        if not bank or not os.path.exists(path):
            continue
        where = "" if matrix_of.get(m, "").endswith("0000_EVERYWHERE_bin") else m[len("MAP_"):].replace("_", " ")
        d = json.load(open(path))
        for o in d.get("objects", []):
            sid = std.get(str(o.get("scriptId")))
            if sid is not None and 7000 <= sid < 8000 and (b := ball(sid - 7000)) and o["eventFlag"] in fl:
                areas.append(f"{place}|ITEM|{b[0]}|{fl[o['eventFlag']]}|{b[1]}|0|{where}")
        for e in d.get("bgs", []):
            sid = std.get(str(e.get("scriptId")))
            if sid is not None and 8000 <= sid < 8800 and sid - 8000 < len(hidden):
                it, q, f = hidden[sid - 8000]
                areas.append(f"{place}|HIDDEN|{it}|{fl['HIDDEN_ITEMS_FLAG_BASE'] + f}|{q}|0|{where}")
    areas = sorted(set(areas), key=lambda l: (int(l.split("|")[0]), l))
    assert f"{mapsecs['MAPSEC_ROUTE_29']}|ITEM|{items['ITEM_POTION']}|{fl['FLAG_HIDE_ITEMBALL_R29_POTION']}|1|0|" in areas
    assert f"{mapsecs['MAPSEC_ROUTE_29']}|HIDDEN|{items['ITEM_NUGGET']}|{fl['FLAG_HIDDENITEM_R29_R30_T21_NUGGET']}|1|0|" in areas

    out = os.path.join(OUT_DIR, "GuideGen4HeartGoldGen.kt")
    with open(out, "w") as f:
        f.write("// Generated by scripts/gen_gen4_guide.py from pret/pokeheartgold - do not edit.\n")
        f.write("package com.pokedaisy.app.companion.data\n\n")
        f.write("/** TRAINER_FLAG_BASE: a trainer's defeated flag is this + its id. */\n")
        f.write(f"internal const val HG_TRAINER_FLAGS = {fl['TRAINER_FLAG_BASE']}\n")
        f.write(f"internal const val HG_FLAG_GAME_CLEAR = {fl['FLAG_GAME_CLEAR']}\n")
        for k in ("WILL", "KOGA", "BRUNO", "KAREN"):
            f.write(f"internal const val HG_FLAG_DEFEATED_{k} = {fl['FLAG_DEFEATED_' + k]}\n")
        f.write("\n" + kraw("HG_WILD_RAW", out_rows["HEARTGOLD"], "HeartGold's wild Pokémon (the generator's doc has the line formats)."))
        f.write("\n" + kraw("SS_WILD_RAW", out_rows["SOULSILVER"], "SoulSilver's wild Pokémon."))
        f.write("\n" + kraw("HG_BOSSES_RAW", bosses, "HeartGold / SoulSilver's gym leaders, Elite Four, champion and RED: key|trainer id|team."))
        f.write("\n" + kraw("HG_AREAS_RAW", areas, "HeartGold / SoulSilver's item balls and hidden items by place (GuideAreas.kt's lines)."))
        f.write("\n/** Each map header's place (its mapsec): header:place, comma-separated. */\n")
        f.write("internal const val HG_HEADER_PLACES =\n")
        hp = ",".join(f"{h}:{p}" for h, p in sorted(header_places.items()))
        for i in range(0, len(hp), 110):
            f.write(f'    "{hp[i:i + 110]}"' + (" +\n" if i + 110 < len(hp) else "\n"))
    print(f"wrote {out}: {len(out_rows['HEARTGOLD'])} lines a game, {len(bosses)} bosses")


def main():
    decomps = os.environ.get("DECOMPS") or sys.exit("set DECOMPS (local.mk)")
    platinum(os.path.join(decomps, "pokeplatinum"))
    heartgold(os.path.join(decomps, "pokeheartgold"))


if __name__ == "__main__":
    main()
