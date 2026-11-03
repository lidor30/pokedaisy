#!/usr/bin/env python3
"""Writes 3ds/core/pd_games_gen.c: every game the app knows, as the app itself detects it.

    cd ui-preview && gradle gameExport -PgameExport -Proms=<ROMs folder> -Pout=<dir>
    python3 3ds/tools/gen_games.py <dir>

The export (ui-preview/src/render/GameExport.kt) runs each ROM through the app's own detection and writes what it
picked: its NativeConfig and its name tables. This turns them into C: a ROM's CRC32 finds its entry (pd_game_identify),
the entry its config and tables. The app's choices are never re-implemented here. Fields of struct pd_config come from
gamedata/targets/3ds.toml's parts, read against the exported config.
"""
import json
import os
import re
import sys
import tomllib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TARGET = os.path.join(ROOT, "gamedata/targets/3ds.toml")
OUT = os.path.join(ROOT, "3ds/core/pd_games_gen.c")

# gPartyMenu and its grid by game: PokeDaisyActivity.switchAddrsFor, where they aren't on the config.
SWITCH = {
    ("FIRERED", "BPRE", 1): (0x0203B0A0, False), ("ORANGE_ISLANDS", None, None): (0x0203B0A0, False),
    ("TMT2", None, None): (0x0203242C, False), ("TEAM_ROCKET", None, None): (0x0203DE64, False),
    ("WISHES", None, None): (0x02031BB0, False), ("AMETHYST", None, None): (0x0203B0A0, True),
    ("UNBOUND", None, None): (0x0203B0A0, True), ("RADICAL_RED", None, None): (0x0203B0A0, True),
    ("GAIA", None, None): (0x0203B0A0, False), ("ODYSSEY", None, None): (0x0203B0A0, True),
    ("SWORD_SHIELD", None, None): (0x0203B0A0, True), ("GS_CHRONICLES", None, None): (0x0203B0A0, True),
    ("HEART_AND_SOUL", None, None): (0x020341FC, False), ("CELIA", None, None): (0x0203B368, False),
    ("ROWE", None, None): (0x0203E774, True), ("IMPERIUM", None, None): (0x020370E0, True),
    ("DREAMSTONE", None, None): (0x02031E04, True), ("MONOMYTH", None, None): (0x0203D8D0, True),
    ("GLAZED", None, None): (0x0203CEC8, False), ("LAZARUS", None, None): (0x0201B67C, True),
    ("EMERALD_SEAGLASS", None, None): (0x02019964, False),
}
GRID_FROM_CONFIG = {"MARIOMON", "ELITE_REDUX"}
# RomArt's region maps: its tiles, palette, tilemap, the palette's first colour, the screen's kind.
REGION = {
    "seaglass": ("SGL_REGION_GFX", "EM_REGION_PAL", "EM_REGION_MAP", 112, 0), "lazarus": ("LZ_REGION_GFX", "LZ_REGION_PAL", "LZ_REGION_MAP", 112, 0),
    "soulgold": ("SG_REGION_GFX", "SG_REGION_PAL", "SG_REGION_MAP", 112, 0), "glazed": ("GZ_REGION_GFX", "GZ_REGION_PAL", "GZ_REGION_MAP", 112, 0),
    "rowe_hoenn": ("RW_REGION_GFX", "RW_REGION_PAL", "RW_REGION_MAP", 112, 0), "rowe_kanto": ("RW_KANTO_GFX", "RW_REGION_PAL", "RW_KANTO_MAP", 112, 0),
    "rowe_sevii": ("RW_SEVII_GFX", "RW_REGION_PAL", "RW_SEVII_MAP", 112, 0), "hns": ("HNS_REGION_GFX", "EM_REGION_PAL", "HNS_REGION_MAP", 112, 0),
    "recharged_emerald": ("RE_REGION_GFX", "RE_REGION_PAL", "RE_REGION_MAP", 112, 0), "imperium": ("IMP_REGION_GFX", "EM_REGION_PAL", "IMP_REGION_MAP", 112, 0),
    "mariomon": ("MM_REGION_GFX", "MM_REGION_PAL", "MM_REGION_MAP", 112, 0), "wishes": ("WSH_REGION_GFX", "WSH_REGION_PAL", "WSH_REGION_MAP", 112, 0),
    "quetzal_kanto": ("QTZ_KANTO_GFX", "QTZ_REGION_PAL", "QTZ_KANTO_MAP", 112, 0), "quetzal_sevii123": ("QTZ_SEVII123_GFX", "QTZ_REGION_PAL", "QTZ_SEVII123_MAP", 112, 0),
    "quetzal_sevii45": ("QTZ_SEVII45_GFX", "QTZ_REGION_PAL", "QTZ_SEVII45_MAP", 112, 0), "quetzal_sevii67": ("QTZ_SEVII67_GFX", "QTZ_REGION_PAL", "QTZ_SEVII67_MAP", 112, 0),
    "tr3_kanto": ("TR3_KANTO_GFX", "TR3_KANTO_PAL", "TR3_KANTO_MAP", 112, 0), "tr3_hoenn": ("TR3_HOENN_GFX", "TR3_HOENN_PAL", "TR3_HOENN_MAP", 112, 0),
    "tr3_sinnoh": ("TR3_SINNOH_GFX", "TR3_SINNOH_PAL", "TR3_SINNOH_MAP", 112, 0), "tr3_johto": ("TR3_JOHTO_GFX", "TR3_JOHTO_PAL", "TR3_JOHTO_MAP", 0, 0),
    "tr3_sevii123": ("TR3_SEVII123_GFX", "TR3_SEVII123_PAL", "TR3_SEVII123_MAP", 0, 0), "tr3_sevii45": ("TR3_SEVII45_GFX", "TR3_SEVII45_PAL", "TR3_SEVII45_MAP", 0, 0),
    "tr3_sevii67": ("TR3_SEVII67_GFX", "TR3_SEVII67_PAL", "TR3_SEVII67_MAP", 0, 0), "dreamstone": ("DSM_REGION_GFX", "DSM_REGION_PAL", "DSM_REGION_MAP", 0, 1),
    "monomyth": ("MMY_REGION_GFX", "MMY_REGION_PAL", "MMY_REGION_MAP", 112, 2),
}
# RomArt's party menus by art dir: slot tiles, their palette, the slot tilemaps (main, no HP, empty), the slot in tiles.
PARTY_FR = ("FR_PARTY_BG_GFX", "FR_PARTY_BG_PAL", "FR_SLOT_MAIN", "FR_SLOT_NO_HP", None, 10, 7)
PARTY_EM = ("EM_PARTY_BG_GFX", "EM_PARTY_BG_PAL", "EM_SLOT_MAIN", "EM_SLOT_NO_HP", None, 10, 7)
PARTY_CFRU = ("CFRU_PARTY_BG_GFX", "CFRU_PARTY_BG_PAL", "CFRU_SLOT_MAIN", "CFRU_SLOT_NO_HP", "CFRU_SLOT_EMPTY", 14, 5)
PARTY_FRAMES = {
    "partyfr": PARTY_FR, "partyem": PARTY_EM,
    "partyhns": ("HNS_PARTY_BG_GFX", "HNS_PARTY_BG_PAL") + PARTY_EM[2:],
    "partyre": ("RE_PARTY_BG_GFX", "RE_PARTY_BG_PAL") + PARTY_EM[2:],
    "partycfru": PARTY_CFRU, "partyswsh": ("CFRU_PARTY_BG_GFX", "SS_PARTY_BG_PAL") + PARTY_CFRU[2:],
    "partycfru_fr": ("UBFR_PARTY_BG_GFX", "CFRU_PARTY_BG_PAL") + PARTY_CFRU[2:],
    "partylg": ("LGO_PARTY_BG_GFX", "LGO_PARTY_BG_PAL") + PARTY_CFRU[2:],
}
# Status icons by art dir: the icons, their palette (CFRU's hacks kept FireRed's).
PARTY_STATUS = {
    "partyfr": ("FR_STATUS_GFX", "FR_STATUS_PAL"), "partyem": ("EM_STATUS_GFX", "EM_STATUS_PAL"),
    "partyhns": ("HNS_STATUS_GFX", "HNS_STATUS_PAL"), "partyre": ("RE_STATUS_GFX", "RE_STATUS_PAL"),
    "partycfru/ub": ("UB_STATUS_GFX", "FR_STATUS_PAL"), "partycfru/rr": ("RR_STATUS_GFX", "FR_STATUS_PAL"),
    "partycfru/od": ("OD_STATUS_GFX", "FR_STATUS_PAL"), "partycfru/am": ("AM_STATUS_GFX", "FR_STATUS_PAL"),
    "partycfru_fr": ("UBFR_STATUS_GFX", "FR_STATUS_PAL"), "partylg": ("LGO_STATUS_GFX", "FR_STATUS_PAL"),
}
# FONT_SMALL by art dir, and whether a glyph is two 8x8 tiles (FireRed's) rather than four (Emerald's).
PARTY_FONT = {"partyfr": ("FR_FONT_SMALL", True), "partyem": ("EM_FONT_SMALL", False), "partyhns": ("HNS_FONT_SMALL", False),
              "partyre": ("RE_FONT_SMALL", False), "partycfru_fr": ("UBFR_FONT_SMALL", True)}
PARTY_BALL = {"partyfr": ("FR_BALL_GFX", "FR_BALL_PAL"), "partyem": ("EM_BALL_GFX", "EM_BALL_PAL"), "partyhns": ("HNS_BALL_GFX", "HNS_BALL_PAL")}
for _lang in ("es", "de", "fr", "it"):
    _up = _lang.upper()
    PARTY_FRAMES[f"partyfr_{_lang}"] = (f"FR_{_up}_PARTY_BG_GFX",) + PARTY_FR[1:]
    PARTY_FRAMES[f"partyem_{_lang}"] = (f"EM_{_up}_PARTY_BG_GFX",) + PARTY_EM[1:]
    PARTY_STATUS[f"partyfr_{_lang}"] = (f"FR_{_up}_STATUS_GFX", "FR_STATUS_PAL")
    PARTY_STATUS[f"partyem_{_lang}"] = (f"EM_{_up}_STATUS_GFX", "EM_STATUS_PAL")
    PARTY_FONT[f"partyfr_{_lang}"] = (f"FR_{_up}_FONT_SMALL", True)
    PARTY_FONT[f"partyem_{_lang}"] = (f"EM_{_up}_FONT_SMALL", False)
# RomArt's backdrops: tiles, palette, tilemap, what's blanked, one 32x32 cell (the 3DS draws the whole screen otherwise).
PARTY_BACKDROP = {
    "partybg/firered.png": ("FR_PARTY_BG_GFX", "FR_PARTY_BG_PAL", "FR_PARTY_BG_MAP", 1),
    "partybg/emerald.png": ("EM_PARTY_BG_GFX", "EM_PARTY_BG_PAL", "EM_PARTY_BG_MAP", 1),
    "partybg/hns.png": ("HNS_PARTY_BG_GFX", "HNS_PARTY_BG_PAL", "HNS_PARTY_BG_MAP", 2),
    "partybg/recharged_emerald.png": ("RE_PARTY_BG_GFX", "RE_PARTY_BG_PAL", "RE_PARTY_BG_MAP", 1),
    "partybg/cfru_tile.png": ("CFRU_PARTY_BG_GFX", "CFRU_PARTY_BG_PAL", "CFRU_PARTY_BG_MAP", 0),
    "partybg/swsh_tile.png": ("CFRU_PARTY_BG_GFX", "SS_PARTY_BG_PAL", "CFRU_PARTY_BG_MAP", 0),
    "partybg/letsgo_tile.png": ("LGO_PARTY_BG_GFX", "LGO_PARTY_BG_PAL", "LGO_PARTY_BG_MAP", 0),
}
FIRERED_BACKDROP = [0xFF4AADA5, 0xFF398C8C, 0xFF216B63]
EMERALD_BACKDROP = [0xFFCED67B, 0xFFB5B55A, 0xFF8C9C29]
# A map with a head of its own: its 16x16 sprite and palette.
REGION_HEAD = {"monomyth": ("MMY_PLAYER_GFX", "MMY_PLAYER_PAL")}
EMERALD_LIKE = {"BPEE", "BPES", "BPED", "BPEF", "BPEI"}
HOENN_ENGINE = ("BPE", "AXV", "AXP")


def walk(cfg, items, path):
    node = items if path.startswith("@items.") else cfg
    parts = path.split(".")[1:] if path.startswith("@items.") else path.split(".")
    for p in parts:
        if node is None:
            return None
        if isinstance(node, list):
            i = int(p)
            node = node[i] if i < len(node) else None
        else:
            node = node.get(p)
    return node


def c_list(v):
    def one(x):
        return "{ " + ", ".join(one(i) for i in x) + " }" if isinstance(x, list) else str(int(x))
    return "{ " + ", ".join(one(i) for i in v) + " }" if v else "{ 0 }"


def c_str(s):
    out = s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ")
    return '"' + out.replace("?", "\\?") + '"'


def c_field(name, v, none=None):
    if v is None or v == none:
        return None
    if isinstance(v, bool):
        return f"    .{name} = {'true' if v else 'false'},"
    if isinstance(v, list):
        if not v:
            return None
        return f"    .{name} = {c_list(v)},"
    if isinstance(v, str):
        return f"    .{name} = {c_str(v)},"
    if isinstance(v, (int, float)):
        return f"    .{name} = {int(v)},"
    return None


def main():
    src = sys.argv[1]
    games = json.load(open(os.path.join(src, "games.json")))
    target = tomllib.load(open(TARGET, "rb"))
    parts = target["part"]
    out = ["// Generated by 3ds/tools/gen_games.py from the app's own detection - don't edit by hand.",
           '#include "pd_games.h"', '#include "pd_gb.h"', '#include "pd_guide.h"', '#include "pd_style.h"', ""]

    # --- tables, each kept once ---
    seen = {}

    def emit(prefix, text_lines, key):
        if key in seen:
            return seen[key]
        name = f"{prefix}{len(seen)}"
        seen[key] = name
        out.extend(line.replace("@", name) for line in text_lines)
        return name

    def name_table(kind, mapping):
        if not mapping:
            return None, 0
        top = max(int(k) for k in mapping) + 1
        vals = [mapping.get(str(i)) for i in range(top)]
        key = (kind, json.dumps(vals, sort_keys=True, ensure_ascii=False))
        lines = [f"static const char* const @[{top}] = {{"]
        lines += ["    " + (c_str(v) if v else "0") + "," for v in vals]
        lines += ["};", ""]
        return emit(kind + "_", lines, key), top

    sets = {}

    def table_set(tid):
        if tid in sets:
            return sets[tid]
        t = json.load(open(os.path.join(src, f"tables-{tid}.json")))
        species, nSpecies = name_table("sp", t["species"])
        items, nItems = name_table("it", t["items"])
        mapsec = {k: v[0] for k, v in t["mapsecs"].items()}
        secs, nSecs = name_table("ms", mapsec)
        moves = t["moves"]
        top = max(int(k) for k in moves) + 1
        rows = [moves.get(str(i)) for i in range(top)]
        key = ("mv", json.dumps(rows, ensure_ascii=False))
        lines = [f"static const struct pd_move_info @[{top}] = {{"]
        lines += ["    { " + (c_str(r[0]) if r else "0") + (f", {r[1]}, {r[2]}" if r else ", 0, 0") + " }," for r in rows]
        lines += ["};", ""]
        mv = emit("mv_", lines, key)
        types = t["types"]
        topT = max(int(k) for k in types) + 1
        rowsT = [types.get(str(i), [255, 255]) for i in range(topT)]
        key = ("ty", json.dumps(rowsT))
        ty = emit("ty_", [f"static const unsigned char @[{topT}][2] = {{"] +
                  ["    " + ", ".join("{ %d, %d }" % (a & 255, b & 255) for a, b in rowsT[i:i + 8]) + "," for i in range(0, topT, 8)] +
                  ["};", ""], key)
        chart = sorted((int(k) // 100, int(k) % 100, v) for k, v in t["chart"].items())
        key = ("ch", json.dumps(chart))
        ch = emit("ch_", [f"static const struct pd_chart_row @[{max(len(chart), 1)}] = {{"] +
                  ["    " + ", ".join("{ %d, %d, %d }" % c for c in chart[i:i + 8]) + "," for i in range(0, len(chart), 8)] +
                  ["};", ""], key)
        tn, nTypes = name_table("tn", t["typeNames"])
        gender = t.get("gender")
        ge, nGender = "0", 0
        if isinstance(gender, list) and gender:
            key = ("ge", json.dumps(gender))
            nGender = len(gender)
            ge = emit("ge_", [f"static const unsigned char @[{nGender}] = {{"] +
                      ["    " + ", ".join(str(g & 255) for g in gender[i:i + 24]) + "," for i in range(0, nGender, 24)] +
                      ["};", ""], key)
        at = {int(k): v for k, v in t["mapsecs"].items() if len(v) >= 6}
        nAt = max(at, default=-1) + 1
        rows = ", ".join("{ " + ", ".join(str(max(0, min(255, int(x)))) if int(x) >= 0 else "255" for x in (at[i][1:6] if i in at else (255, 0, 0, 0, 0))) + " }" for i in range(nAt))
        atName = emit("ma_", [f"static const unsigned char @[{max(nAt, 1)}][5] = {{ {rows} }};", ""], ("ma", rows)) if nAt else "0"
        name = f"set_{len(sets)}"
        out.extend([f"static const struct pd_table_set {name} = {{",
                    f"    {species or 0}, {nSpecies}, {mv}, {top}, {items or 0}, {nItems}, {ty}, {topT}, {ch}, {len(chart)},",
                    f"    {tn or 0}, {nTypes}, {secs or 0}, {nSecs}, {ge}, {nGender}, {atName}, {nAt},", "};", ""])
        sets[tid] = name
        return name

    # --- guides: the retail ones in pd_guide_gen.c have the League's rules; the others are the app's own, exported ---
    guides = {"FIRERED": ("&pd_guide_firered", "pd_areas_firered", "&pd_areas_firered_count"),
              "LEAFGREEN": ("&pd_guide_leafgreen", "pd_areas_leafgreen", "&pd_areas_leafgreen_count"),
              "EMERALD": ("&pd_guide_emerald", "pd_areas_emerald", "&pd_areas_emerald_count")}
    AREA_KINDS = {"ITEM": "PD_AREA_ITEM", "HIDDEN": "PD_AREA_HIDDEN", "GIFT": "PD_AREA_GIFT", "KEY": "PD_AREA_KEY",
                  "MON": "PD_AREA_MON", "EGG": "PD_AREA_EGG", "TRADE": "PD_AREA_TRADE"}

    def guide(gid):
        if gid in guides:
            return guides[gid]
        path = os.path.join(src, f"guide-{gid}.json")
        if not os.path.exists(path):
            guides[gid] = ("0", "0", "0")
            return guides[gid]
        d = json.load(open(path))
        low = gid.lower()
        page_rows = []
        for pi, page in enumerate(d["pages"]):
            sec_rows = []
            for si, sec in enumerate(page["sections"]):
                name = f"g_{low}_{pi}_{si}"
                rows = []
                for e in sec["entries"]:
                    h = e["have"]
                    have = (h[1:] + [0, 0, 0])[:3]
                    kind = ["PD_HAVE_NONE", "PD_HAVE_FLAG", "PD_HAVE_ITEM", "PD_HAVE_CAUGHT"][h[0]]
                    areas = c_str("|".join(e["areas"])) if e["areas"] else "NULL"
                    hint = c_str(e["hint"]) if e["hint"] else "NULL"
                    rows.append(f"    {{ {c_str(e['title'])}, {c_str(e['answer'])}, {hint}, {kind}, {{ {have[0] & 0xFFFF}, {have[1] & 0xFFFF}, {have[2] & 0xFFFF} }}, {areas} }},")
                out.extend([f"static const struct pd_guide_entry {name}[] = {{"] + rows + ["};"])
                note = c_str(sec["note"]) if sec["note"] else "NULL"
                sec_rows.append(f"    {{ {c_str(sec['heading'])}, {note}, {name}, {len(rows)} }},")
            out.extend([f"static const struct pd_guide_section g_{low}_{pi}[] = {{"] + sec_rows + ["};"])
            page_rows.append(f"    {{ {c_str(page['title'])}, g_{low}_{pi}, {len(sec_rows)} }},")
        pages = "0"
        if page_rows:
            pages = f"g_{low}_pages"
            out.extend([f"static const struct pd_guide_page {pages}[] = {{"] + page_rows + ["};"])
        boss_rows = [f"    {{ {c_str(b['title'])}, {c_str(b['where'])}, {b['done'] & 0xFFFF}, PD_BOSS_FIXED, {b['trainer'] & 0xFFFF}, 0 }}," for b in d["bosses"]]
        bosses = "0"
        if boss_rows:
            bosses = f"g_{low}_bosses"
            out.extend([f"static const struct pd_boss {bosses}[] = {{"] + boss_rows + ["};"])
        out.extend([f"static const struct pd_guide g_{low} = {{ {pages}, {len(page_rows)}, {bosses}, {len(boss_rows)}, {'true' if d['verified'] else 'false'}, 0, 0 }};", ""])
        rows = [f"    {{ {a[0]}, {AREA_KINDS[a[1]]}, {a[2]}, {a[3] & 0xFFFF}, {a[4]}, {a[5]}, {c_str(a[6])} }}," for a in d["areas"]]
        areas, count = "0", "0"
        if rows:
            areas = f"a_{low}"
            count = f"a_{low}_count"
            out.extend([f"static const struct pd_area_thing {areas}[] = {{"] + rows + ["};", f"static const int {count} = {len(rows)};", ""])
        guides[gid] = (f"&g_{low}", areas, "&" + count if rows else "0")
        return guides[gid]

    # --- layouts ---
    def party_layout(cfg):
        p = cfg["partyMonLayout"]
        if p == VANILLA_PARTY:
            return "0"
        key = ("pl", json.dumps(p, sort_keys=True))
        return "&" + emit("pl_", [
            "static const struct pd_party_layout @ = {",
            f"    {p['size']}, {p['status']}, {p['level']}, {p['hp']}, {p['maxHp']}, {p['flags']}, {p['plainBox']}, {p['hpBits']},",
            f"    {p['statusShift']}, {p['statusMask']}u, {p['eggMask']}, {str(p['eggInIvWord']).lower()}, {p['nicknameOff']}, {p['nicknameLen']},",
            f"    {p['plainSpeciesMask']}, {p['plainItemMask']}, {str(p['hasStats']).lower()}, {str(p['rowe']).lower()}, {str(p['eliteRedux']).lower()},",
            f"    {str(p['monomythKey']).lower()},",
            "};", ""], key)

    def battle_layout(cfg):
        b = cfg["battleMonLayout"]
        if b == VANILLA_BATTLE:
            return "0"
        key = ("bl", json.dumps(b, sort_keys=True))
        return "&" + emit("bl_", [
            "static const struct pd_battle_layout @ = {",
            f"    {b['size']}, {b['species']}, {b['speciesMask']}, {b['moves']}, {b['pp']}, {b['type1']}, {b['type2']}, {b['hp']}, {b['level']},",
            f"    {b['maxHp']}, {b['status1']},",
            "};", ""], key)

    def party_fns(items):
        p = items.get("party") if items else None
        if not p or not items.get("bagActions", True):
            return "0"

        def u16s(prefix, values):
            values = sorted(int(v) for v in values)
            if not values:
                return "0", 0
            name = emit(prefix + "_", [f"static const uint16_t @[{len(values)}] = {{ " + ", ".join(map(str, values)) + " };", ""],
                        (prefix, json.dumps(values)))
            return name, len(values)

        med, nMed = u16s("med", p["medicine"])
        one, nOne = u16s("pp1", p["ppOneMove"])
        al, nAll = u16s("ppa", p["ppAllMoves"])
        mail, nMail = u16s("mail", p["mail"])
        funcs = sorted(int(v) for v in p.get("useFuncs") or [])
        uf = "0"
        if funcs:
            uf = emit("uf_", [f"static const uint32_t @[{len(funcs)}] = {{ " + ", ".join(f"{f}u" for f in funcs) + " };", ""], ("uf", json.dumps(funcs)))
        pockets = sum(1 << int(v) for v in p["unholdablePockets"])
        crcs = ", ".join(f"{int(c)}u" for c in p["codeCrcs"])
        key = ("pf", json.dumps(p, sort_keys=True))
        return "&" + emit("pf_", [
            "static const struct pd_party_fns @ = {",
            f"    {p['getMonData']}u, {p['setMonData']}u, {p.get('itemNoEffect') or 0}u, {p['itemEffect']}u, {p['addBagItem']}u, {p['party']}u, {p['partyCount']}u, {p['scratch']}u,",
            f"    {p['itemCount']}, {pockets}u, {{ {crcs} }}, {med}, {nMed}, {one}, {nOne}, {al}, {nAll}, {mail}, {nMail}, {uf}, {len(funcs)},",
            f"    {p['hpUp']}, {p['shedinja']}, {p['monFieldShift']},",
            "};", ""], key)

    vanilla = next(g for g in games if g["code"] == "BPRE" and g["rev"] == 1 and g["config"])
    VANILLA_PARTY = vanilla["config"]["partyMonLayout"]
    VANILLA_BATTLE = vanilla["config"]["battleMonLayout"]

    sig_text = open(os.path.join(ROOT, "app/src/main/kotlin/com/pokedaisy/app/companion/data/RomArtSigsGen.kt")).read()

    def sig(name):
        m = re.search(r"\b" + name + r"\((true|false), (\d+), (0x[0-9A-F]+)L, (\d+), (-?\d+)L, (0x[0-9A-F]+)(, smol = true)?\)", sig_text)
        lz, size, crc, off, head, pre, smol = m.groups()
        text = f"{{ {lz}, {size}, {crc}u, {off}, 0x{int(head) & (2**64 - 1):016X}ull, {pre}, 0, {'true' if smol else 'false'} }}"
        return emit("sg_", [f"static const struct pd_blob_sig @ = {text};", ""], ("sg", name))

    def regions(g):
        pages = [REGION.get(n) for n in g.get("regionImages") or []]
        names = g.get("regionImages") or []
        if not pages or not all(pages) or names in (["hoenn"], ["kanto", "sevii123", "sevii45", "sevii67"]):
            return "0, 0"
        rows = []
        for n, (gfx, pal, mp, first, kind) in zip(names, pages):
            label = n.split("_")[-1].upper() if "_" in n else n.upper()
            head = REGION_HEAD.get(n)
            heads = f"&{sig(head[0])}, &{sig(head[1])}" if head else "0, 0"
            rows.append(f"{{ &{sig(gfx)}, &{sig(pal)}, &{sig(mp)}, {first}, {kind}, {c_str(label)}, {heads} }}")
        text = ", ".join(rows)
        name = emit("rp_", [f"static const struct pd_region_page @[{len(rows)}] = {{ {text} }};", ""], ("rp", text))
        return f"{name}, {len(rows)}"

    def blob(name):
        return "PD_" + name if name else "-1"

    def rgb(c):
        return f"0x{int(c) & 0xFFFFFF:06X}"

    def asset_dir(path):
        return path.rsplit("/", 1)[0]

    def party_style(g):
        st = g.get("partyStyle")
        if not st:
            return "0"
        gfx, pal, main, nohp, empty, tiles_w, tiles_h = PARTY_FRAMES[st["frameDir"]]
        status, status_pal = PARTY_STATUS[asset_dir(st["statusAsset"])]
        font, half = PARTY_FONT[asset_dir(st["fontAsset"])]
        ball, ball_pal = PARTY_BALL[asset_dir(st["pokeballAsset"])] if st["pokeballAsset"] else (None, None)
        widths = st["glyphWidths"]
        assert len(widths) == 512, len(widths)
        wname = emit("gw_", ["static const unsigned char @[512] = { " + ", ".join(str(w) for w in widths) + " };", ""], ("gw", tuple(widths)))
        band = st["band"]
        lines = ["static const struct pd_party_style @ = {",
                 f"    {blob(gfx)}, {blob(pal)}, {blob(main)}, {blob(nohp)}, {blob(empty)}, {blob(ball)}, {blob(ball_pal)}, {blob(status)}, "
                 f"{blob(status_pal)}, {blob(font)}, {'true' if half else 'false'}, {tiles_w}, {tiles_h},",
                 f"    {wname}, {st['lvGlyph']}, {st['padGlyph']},",
                 "    " + ", ".join(rgb(st[k]) for k in ("text", "textShadow", "male", "maleShadow", "female", "femaleShadow", "hpGreenTop", "hpGreen",
                                                         "hpYellowTop", "hpYellow", "hpRedTop", "hpRed", "hpEmptyTop", "hpEmpty")) + ",",
                 "    " + ", ".join(str(st[k]) for k in ("slotW", "slotH", "nameX", "nameY", "levelX", "levelY", "genderX", "genderY", "hpX", "maxHpX", "hpY",
                                                         "barX", "barY", "barW", "iconX", "iconY", "iconRestDx", "iconRestDy", "ballX", "ballY", "statusX",
                                                         "statusY", "leftCol", "midCol", "topRow", "midRow", "bottomRow", "visibleW", "visibleH",
                                                         "regionX0", "regionY0")) + ",",
                 "    " + ", ".join("true" if st[k] else "false" for k in ("ballFollowsIcon", "stretchRows", "upperCaseNames", "hasEmptySlot",
                                                                           "blendOverBackdrop")) + ",",
                 "    " + ("true, " + ", ".join(str(band[k]) for k in ("top", "bottom", "leftTopX", "leftBottomX", "rightTopX", "rightBottomX",
                                                                       "emptyStripeRow")) if band else "false, 0, 0, 0, 0, 0, 0, 0") + ",",
                 f"    {c_str(st['eggName'])},",
                 "};", ""]
        return "&" + emit("ps_", lines, ("ps", json.dumps(st, sort_keys=True), gfx, pal, status, status_pal, font, ball, ball_pal))

    def party_backdrops(g):
        """buildGameBackground's picks, those the player's own ROM can make; the first found wins, else the stripes."""
        art = g.get("backdropArt")
        if not art:
            return "0, 0"
        gfx, pal, bmap, blank = PARTY_BACKDROP[art[0]]
        st = g.get("partyStyle")
        # A translation's tiles only move the CANCEL text: its own stand in for English's, like RomArt's.
        if st and st["frameDir"] not in ("partyhns", "partyre") and (art[0] in ("partybg/firered.png", "partybg/emerald.png", "partybg/cfru_tile.png")):
            gfx = PARTY_FRAMES[st["frameDir"]][0]
        back = g.get("backdrop")
        rows = []

        def row(g2, p2, m2, b2, frm, to):
            colours = ", ".join(rgb(c) for c in (frm or [0, 0, 0]) + (to or [0, 0, 0]))
            rows.append(f"{{ {blob(g2)}, {blob(p2)}, {blob(m2)}, {b2}, {'true' if to else 'false'}, {{ {colours} }} }}")

        if g["kind"] == "TEAM_ROCKET":
            row(gfx, pal, bmap, blank, None, None)
            row("EM_PARTY_BG_GFX", "EM_PARTY_BG_PAL", "EM_PARTY_BG_MAP", 1, EMERALD_BACKDROP, FIRERED_BACKDROP)
        elif back and art[0] == "partybg/firered.png":
            # FireRed's art in the game's colours, or Emerald's where that's what the ROM kept: the same tiles and tilemap.
            row(gfx, pal, bmap, blank, FIRERED_BACKDROP, back)
            row("EM_PARTY_BG_GFX", "EM_PARTY_BG_PAL", "EM_PARTY_BG_MAP", 1, EMERALD_BACKDROP, back)
        else:
            row(gfx, pal, bmap, blank, None, None)
        name = emit("pb_", [f"static const struct pd_party_backdrop @[{len(rows)}] = {{ " + ", ".join(rows) + " };", ""], ("pb", tuple(rows)))
        return f"{name}, {len(rows)}"

    def look_of(g):
        back = g.get("backdrop")
        if not back:
            # Art the app rebuilds from a ROM: its stripes' colours (EMERALD_BACKDROP, FIRERED_BACKDROP); Ghost Grey's is plain white.
            emerald = g["code"][:3] in HOENN_ENGINE and g["kind"] not in ("UNBOUND",)
            back = EMERALD_BACKDROP if emerald else FIRERED_BACKDROP
            if not g.get("backdropArt") and not g.get("gb"):
                back = [0xFFFFFFFF] * 3
        return ("{ &" + party_palette(g["party-light"]) + ", &" + party_palette(g["party-dark"]) + " }, { &" + bag_palette(g["bag-light"])
                + ", &" + bag_palette(g["bag-dark"]) + " }, { " + ", ".join(rgb(c) for c in back) + " }, " + regions(g) + ", "
                + ("true" if g.get("readsRomRegionMap") else "false") + ", " + party_style(g) + ", " + party_backdrops(g))

    def gb_config(c):
        def ints(prefix, ctype, values):
            key = (prefix, json.dumps(values))
            return emit(prefix + "_", [f"static const {ctype} @[{len(values)}] = {{ " + ", ".join(str(v) for v in values) + " };", ""], key)

        gen = c["gen"]
        chars = emit("chars_", ["static const char* const @[256] = {"] +
                     ["    " + ", ".join(c_str(x) for x in c["chars"][i:i + 16]) + "," for i in range(0, 256, 16)] + ["};", ""],
                     ("chars", json.dumps(c["chars"])))
        f = [f"    .gen = {gen},", f"    .chars = {chars},"]

        def put(field, value):
            f.append(f"    .{field} = {value},")

        def addr(field, path=None):
            v = c.get(path or field)
            if v:
                put(field, f"{int(v) & 0xFFFFFFFF}u")

        for name in ("partyCount", "partyMons", "money", "battleMon", "enemyMon", "tileMap", "moveMenuType"):
            addr(name)
        if gen == 1:
            for name, path in (("battleMode", "isInBattle"), ("otPartyCount", "enemyPartyCount"), ("otPartyMons", "enemyMons"),
                               ("otActive", "enemyMonPartyPos"), ("gameTime", "playTime"), ("outSlot", "playerMonNumber"),
                               ("dexCaught", "dexOwned"), ("dexSeen", "dexSeen"), ("bagCount", "bagCount"), ("curMap", "curMap"),
                               ("yCoord", "yCoord"), ("xCoord", "xCoord"), ("topMenuItemY", "topMenuItemY"), ("partyMenuType", "partyMenuType")):
                addr(name, path)
            for name in ("battleMenuLeftX", "battleMenuRightX", "bagSlots", "battlePartyMenu"):
                put(name, c[name])
            t = c["internalToDex"]
            put("internalToDex", ints("dex", "uint16_t", t))
            put("internalCount", len(t))
        else:
            for name, path in (("battleMode", "battleMode"), ("otPartyCount", "otPartyCount"), ("otPartyMons", "otPartyMons"),
                               ("otActive", "curOTMon"), ("gameTime", "gameTime"), ("outSlot", "curBattleMon"), ("dexCaught", "dexCaught"),
                               ("dexSeen", "dexSeen"), ("numItems", "numItems"), ("numKeyItems", "numKeyItems"), ("numBalls", "numBalls"),
                               ("tmsHms", "tmsHms"), ("mapGroup", "mapGroup"), ("coords", "coords"), ("backupMapGroup", "backupMapGroup"),
                               ("playerGender", "playerGender"), ("menu2D", "menu2D"), ("menuCursor", "menuCursor"),
                               ("partyMenuAction", "partyMenuAction"), ("numMedicine", "numMedicine"), ("numBerries", "numBerries")):
                addr(name, path)
            lm = sorted((int(k), int(v)) for k, v in c["landmarks"].items())
            rows = ", ".join(f"{{ {k}, {v} }}" for k, v in lm)
            put("landmarks", emit("lm_", [f"static const struct pd_gb_landmark @[{len(lm)}] = {{ {rows} }};", ""], ("lm", rows)))
            put("landmarkCount", len(lm))
            put("genderRatios", ints("gr", "uint8_t", c["genderRatios"]))
            put("genderCount", len(c["genderRatios"]))
            put("tmItems", ints("tm", "uint16_t", c["tmItems"]))
            put("tmCount", len(c["tmItems"]))
            for name in ("speciesCount", "itemsMax", "ballsMax", "keyItemsMax", "medicineMax", "berriesMax", "cursorTile"):
                put(name, c[name])
            for name in ("tmBits", "polished"):
                if c[name]:
                    put(name, "true")
            if c.get("forms"):
                put("forms", ints("forms", "uint32_t", c["forms"]))
                put("formCount", len(c["forms"]))
        gf = c.get("field")
        if gf and gf.get("crystal"):
            put("field", "&" + emit("gf_", ["static const struct pd_gb_field @ = { .crystal = true };", ""], ("gf", "crystal")))
        elif gf:
            sg = gf["signatures"]
            rows = ", ".join(f"{{ {int(x['first'])}u, {c_str(x['second'])} }}" for x in sg)
            sn = emit("gs_", [f"static const struct pd_gb_sig @[{len(sg)}] = {{ {rows} }};", ""], ("gs", rows))
            names = ("loopAfterWait", "bankswitch", "pcRoutine", "wildDataPointers", "walkCounter", "flags5", "inBattle", "badges", "tileset",
                     "townsVisited", "destination", "flags6", "partyCount", "partyMoves", "curMap", "grassRate", "waterRate")
            body2 = ", ".join(f".{n} = {int(gf[n])}u" for n in names)
            text = (f"{{ {body2}, .pikachuFlags = {int(gf['pikachuFlags'])}, .pikachuImage = {int(gf['pikachuImage'])}, .pcBank = {gf['pcBank']}, "
                    f".wildBank = {gf['wildBank']}, .sigs = {sn}, .sigCount = {len(sg)} }}")
            put("field", "&" + emit("gf_", [f"static const struct pd_gb_field @ = {text};", ""], ("gf", text)))
        art = c.get("art")
        if art:
            def off(pair):
                if not pair:
                    return 0
                bank, a = pair["first"], pair["second"]
                return a if bank == 0 else bank * 0x4000 + (a - 0x4000)

            def rgb(v):
                return f"0x{int(v) & 0xFFFFFF:06X}u"

            a = []
            if gen == 1:
                a += [f".iconPointers = {off(art['iconPointers'])}u", f".iconPointerCount = {art['iconPointerCount']}",
                      f".monPartyData = {off(art['monPartyData'])}u", f".dexCount = {art['dexCount']}",
                      f".iconSheet = {off(art.get('iconSheet'))}u",
                      ".iconColors = { " + ", ".join(rgb(v) for v in art["iconColors"]) + " }",
                      f".townMap = {off(art['townMap'])}u", f".townMapTiles = {off(art['townMapTiles'])}u",
                      ".townMapColors = { " + ", ".join(rgb(v) for v in art["townMapColors"]) + " }",
                      f".playerSprite = {off(art['playerSprite'])}u",
                      ".playerColors = { " + ", ".join(rgb(v) for v in (art.get("playerColors") or art["townMapColors"])) + " }"]
            else:
                banks = art.get("iconBanks")
                unown = art.get("unownIcons")
                a += [f".icons = {off(art['icons'])}u", f".iconsBank = {art['icons']['first']}", f".monMenuIcons = {off(art.get('monMenuIcons'))}u",
                      f".eggIcon = {art['eggIcon']}", f".partyMenuObPals = {off(art.get('partyMenuObPals'))}u",
                      f".iconPalettes = {off(art.get('iconPalettes'))}u", f".iconFarPointers = {off(art.get('iconFarPointers'))}u",
                      f".iconPalIndex = {off(art.get('iconPalIndex'))}u", f".iconObjPals = {off(art.get('iconObjPals'))}u",
                      f".minis = {off(art.get('minis'))}u", f".monPalettes = {off(art.get('monPalettes'))}u",
                      f".cosmeticForms = {c.get('cosmeticForms', 0)}"]
                maps = ", ".join(f"{off(m)}u" for m in art["townMaps"][:6])
                sprites = ", ".join(f"{off(m)}u" for m in art["playerSprites"][:3])
                a += [f".townMapGfx = {off(art['townMapGfx'])}u", f".townMaps = {{ {maps} }}", f".townMapCount = {min(len(art['townMaps']), 6)}",
                      f".townMapPalMap = {off(art['townMapPalMap'])}u", f".pokegearPals = {off(art['pokegearPals'])}u",
                      f".objectPals = {off(art['objectPals'])}u", f".playerSprites = {{ {sprites} }}",
                      f".townMapPalCount = {art['townMapPalCount']}", f".townMapPal0From = {art['townMapPal0From']}",
                      f".playerPalFrom = {art.get('playerPalFrom', 0)}", f".townMapsLz = {'true' if art['townMapsLz'] else 'false'}",
                      f".townMapFlips = {'true' if art['townMapFlips'] else 'false'}", f".playerLz = {'true' if art.get('playerLz') else 'false'}"]
                if banks:
                    a += [f".bankLow = {banks['first']}", f".bankHigh = {banks['second']}"]
                if unown:
                    a += [f".unownIcons = {off(unown['first'])}u", f".unownBank = {unown['second']}"]
            text = "{ " + ", ".join(a) + " }"
            put("art", "&" + emit("art_", [f"static const struct pd_gb_art @ = {text};", ""], ("art", text)))
        body = "\n".join(f)
        if body not in gb_configs:
            gb_configs[body] = f"gb_{len(gb_configs)}"
            out.append(f"static const struct pd_gb_config {gb_configs[body]} = {{")
            out.extend(f)
            out.extend(["};", ""])
        return gb_configs[body]

    def col(v):
        """A colour: 0xRRGGBB, or no shadow for a clear one."""
        v = int(v)
        if v == -1:
            return "PD_SLOT_TEXT_OWN"
        return "PD_NO_SHADOW" if (v >> 24) & 0xFF == 0 else f"0x{v & 0xFFFFFF:06X}"

    def slot(a):
        return "{ " + ", ".join(col(x) for x in a[:5]) + f", {col(a[5])}, {col(a[6])} }}"

    def party_palette(p):
        ball = p["ball"]
        keys = ",".join(sorted(ball))
        if keys != "H,K,R,W":
            raise SystemExit(f"ball letters {keys}")
        e = p["empty"]
        text = (f"{{ {slot(p['normal'])}, {slot(p['selected'])}, {slot(p['fainted'])}, {slot(p['selectedFainted'])}, "
                f"{slot(e) if e else '{ 0 }'}, {'true' if e else 'false'}, "
                + ", ".join(col(p[k]) for k in ("text", "textShadow", "male", "maleShadow", "female", "femaleShadow", "hpFrame", "hpInner",
                                                "hpLabel", "hpLabelShade"))
                + ", " + ", ".join("{ " + col(p[k][0]) + ", " + col(p[k][1]) + " }" for k in ("hpGreen", "hpYellow", "hpRed", "hpEmpty"))
                + ", { " + ", ".join(col(ball[k]) for k in "KRHW") + f" }}, {round(p['emptyAlpha'] * 255)} }}")
        return emit("pp_", [f"static const struct pd_party_palette @ = {text};", ""], ("pp", text))

    def layers(a, n):
        a = a[:n]
        return "{ " + ", ".join(f"{{ {col(c)}, {w} }}" for c, w in a) + " }", len(a)

    def bag_palette(b):
        panel, n = layers(b["panelLayers"], 6)
        desc, nd = layers(b["descLayers"], 4)
        back = b.get("backdrop")
        text = (f"{{ {panel}, {desc}, {n}, {b['tabLayerCount']}, {nd}, {col(b['fill'])}, {col(b['divider']) if b['divider'] is not None else 0}, "
                + ", ".join(col(b[k]) for k in ("text", "textShadow", "tabText", "tabTextShadow", "tabUnderline", "descFill", "descText", "descTextShadow"))
                + f", {'true' if b['divider'] is not None else 'false'}, {'true' if back else 'false'}, "
                + (f"{col(back[0])}, {col(back[1])}" if back else "0, 0") + " }")
        return emit("bp_", [f"static const struct pd_bag_palette @ = {text};", ""], ("bp", text))

    def look_c(l):
        def lay(name, n):
            return layers(l[name], n)
        t, tn = lay("titleLayers", 4)
        li, ln = lay("listLayers", 6)
        ch, cn = lay("chipLayers", 4)
        fm, fn = lay("fieldMoveLayers", 3)
        names = ("titleFill", "titleText", "titleShadow", "listFill", "rowSelected", "label", "labelShadow", "value", "valueShadow", "success",
                 "fieldMove", "fieldMoveShadow", "featureFill", "featureText", "featureShadow", "muted", "mutedShadow", "frameDark", "frameLight",
                 "onBackdrop", "onBackdropShadow", "tabSelectedFill", "tabIdleFill")
        tail = ("groupTitleText", "groupTitleShadow", "divider", "badgeText", "newBadge", "favorite", "imageWell", "hintBar", "hintShadow")
        fill = l["groupTitleFill"]
        return ("{ " + f"{t}, {li}, {ch}, {fm}, {tn}, {ln}, {cn}, {fn}, " + ", ".join(col(l[k]) for k in names)
                + f", {col(fill) if fill != -1 else 'PD_NO_SHADOW'}, " + ", ".join(col(l[k]) for k in tail) + f", {round(l['scrim'] * 255)} }}")

    configs = {}
    gb_configs = {}
    entries = []
    for g in games:
        cfg = g["config"]
        if g.get("gb"):
            entries.append((g["crc32"], g["size"], g["file"], g["title"], g["language"], "pd_gb_blank_config", table_set(g["tables"]),
                            "PD_GAME_GB", g["kind"], ("0", "0", "0"), "&" + gb_config(g["gb"]), look_of(g)))
            continue
        if not cfg:
            continue
        items = g.get("fieldItems")
        lines = []
        kind = g["kind"]
        code = g["code"]
        vanilla_dex = cfg.get("pokedex") and not cfg["pokedex"].get("expansion") and cfg["pokedex"].get("speciesCount", 0) <= 412 \
            and cfg["pokedex"].get("entryDescOff") == 16 and not cfg["pokedex"].get("entryCategoryPtr")
        wanted = ["ram", "foes", "battle", "icons"]
        if vanilla_dex:
            wanted.append("dex")
        gt = cfg.get("guideTables")
        # The live pages read vanilla's trainer and wild records; the others keep their text and flags.
        vanilla_guide = gt and gt["trainerMon"] is None and gt["trainerStride"] == 40 and gt["trainerPartyOff"] == 36 \
            and gt["trainerNameOff"] == 4 and gt["wildLayout"] is None and gt["wildSets"] == 1 and not gt["learnsetCfru"] \
            and not gt["learnsetPairs"]
        if vanilla_guide:
            wanted.append("guide")
        style = (cfg.get("trainerCard") or {}).get("style")
        if style in ("KANTO", "HOENN"):
            wanted.append("card")
        if items and items.get("fly"):
            wanted.append("fly")
        if items:
            wanted.append("field")
        for part in wanted:
            for field, spec in parts[part].items():
                path, none = (spec["path"], spec.get("none")) if isinstance(spec, dict) else (spec, None)
                v = walk(cfg, items, path)
                if field == "cardStyle":
                    v = {"KANTO": "PD_CARD_KANTO", "HOENN": "PD_CARD_HOENN"}.get(v)
                    if v:
                        lines.append(f"    .cardStyle = {v},")
                    continue
                if field == "bagOrder":
                    continue
                if isinstance(v, dict):
                    continue
                line = c_field(field, v, none)
                if line:
                    lines.append(line)
        # What the app's knownCursorAddrs and switchAddrsFor give.
        switch = SWITCH.get((kind, code, g["rev"])) or SWITCH.get((kind, None, None))
        menu = switch[0] if switch else cfg.get("partyMenu") or 0
        grid = switch[1] if switch else kind in GRID_FROM_CONFIG
        if kind == "EMERALD" and not menu:
            menu = 0x0203CB94 if code == "BPEJ" else 0x0203CEC8 if code in EMERALD_LIKE else 0
        if kind == "FIRERED" and (g.get("fireRedHack") or g.get("letsGo")):
            menu = 0x0203B0A0
            grid = bool(g.get("letsGo"))
        lines = [l for l in lines if not l.startswith("    .partyMenu")]
        if menu:
            lines.append(f"    .partyMenu = {menu},")
        if grid:
            lines.append("    .partyMenuGrid = true,")
        order = cfg["bagPocketOrder"]
        lines.append(f"    .bagOrder = {c_list(order)},")
        lines.append(f"    .bagPocketCount = {len(order) if cfg['bagPockets'] else 0},")
        for field in ("monStride", "speciesMask", "moveMask", "ppMask", "saveBlock1PosOff", "playerGenderOff", "frameCounterOff", "eggSpecies", "maxMoney",
                      "monIconStride", "monPalIdxStride", "monPalIdxMask", "monIconPalettes"):
            lines.append(f"    .{field} = {int(cfg[field])},")
        for field in ("staticSaveBlocks", "battleStructStatic", "mapSecWide", "monIconPacked"):
            if cfg[field]:
                lines.append(f"    .{field} = true,")
        if not cfg.get("itemPalCompressed", True):
            lines.append("    .itemPalRaw = true,")
        if cfg.get("extraItemIconTable"):
            lines += [f"    .extraItemIconTable = {cfg['extraItemIconTable']}u,", f"    .extraItemFirst = {cfg['extraItemFirst']},",
                      f"    .extraItemCount = {cfg['extraItemCount']},", f"    .extraItemStride = {cfg['extraItemStride']},"]
        if kind == "UNBOUND":
            # Its config keeps FireRed's tables; the app reads these through UnboundIconSource.
            lines = [l for l in lines if not l.startswith(("    .monIconTable", "    .monIconPalette", "    .itemIconTable"))]
            lines += [f"    .monIconTable = {0x09A217FC}u,", "    .monIconRecord = true,", f"    .itemIconTable = {0x0825A554}u,"]
        d = cfg.get("itemDescs")
        if d:
            lines += [f"    .itemDescBase = {d['base']},", f"    .itemDescStride = {d['stride']},", f"    .itemDescOff = {d['descOff']},",
                      f"    .itemDescFirst = {d['first']},", f"    .itemDescCount = {min(d['count'], 0x7FFFFFFF)},"]
        special = cfg.get("bag3") or cfg.get("itemFlagsBag")
        if special:
            pockets = {int(k): int(v) for k, v in (special.get("pockets") or {}).items()}
            n = max(pockets, default=0) + 1
            name = emit("ip_", [f"static const uint8_t @[{n}] = {{ " + ", ".join(str(pockets.get(i, 0)) for i in range(n)) + " };", ""],
                        ("ip", json.dumps(pockets, sort_keys=True)))
            lines += [f"    .itemPockets = {name},", f"    .itemPocketCount = {n},"]
        if cfg.get("bag3"):
            b3 = cfg["bag3"]
            lines += [f"    .bag3Off = {b3['off']}u,", f"    .bag3TailOff = {b3['tailOff']}u,", f"    .bag3SplitAt = {b3['splitAt']},",
                      f"    .bag3KeyBit = {b3['keyItemsBit']},"]
        if cfg.get("itemFlagsBag"):
            fb = cfg["itemFlagsBag"]
            lines += [f"    .itemFlagsOff = {fb['off']}u,", f"    .itemFlagsCount = {fb['count']},"]
        dx = cfg.get("pokedex")
        if vanilla_dex and dx.get("evolutions") and not dx.get("evoLayout"):
            lines += [f"    .dexEvolutions = {dx['evolutions']}u,", f"    .dexSpeciesCount = {dx['speciesCount']},"]
            if dx.get("tmhmLearnsets") and dx.get("tmhmMoves"):
                lines += [f"    .dexTmhm = {dx['tmhmLearnsets']}u,", f"    .dexTmhmMoves = {dx['tmhmMoves']}u,"]
            if gt and vanilla_guide and gt.get("mapGroups") and not gt.get("mapGroupsXor"):
                lines.append(f"    .dexMapGroups = {gt['mapGroups']}u,")
        ps = cfg.get("pcStorage")
        ptr = int(cfg.get("pokemonStoragePtr") or 0)
        if ps and ptr:
            lines += [f"    .pokemonStoragePtr = {ptr}u,", f"    .pcBoxes = {ps['boxes']},", f"    .pcRecord = {ps['recordSize']},",
                      f"    .pcFormat = {dict(ENCRYPTED=0, PLAIN=1, CFRU=2)[ps['format']]},"]
            if ps["direct"]:
                lines.append("    .pcDirect = true,")
            cf = ps.get("cfru")
            if cf:
                extra = ", ".join(f"{int(a)}u" for a in cf["extraBoxes"])
                lines += [f"    .cfruStorage = {cf['storage']}u,", f"    .cfruMoves = {cf['movesTable']}u,", f"    .cfruExtra = {{ {extra} }},"]
        bs = g.get("boxSwap")
        if bs:
            crcs = ", ".join(f"{int(c)}u" for c in bs["codeCrcs"])
            lines += [f"    .boxToMon = {bs['boxMonToMon']}u,", f"    .boxRestorePP = {bs['boxMonRestorePP']}u,",
                      f"    .boxGetData = {bs['getBoxMonData']}u,", f"    .boxStoragePtr = {bs['storagePtr']}u,", f"    .boxCrcs = {{ {crcs} }},"]
        lines.append(f"    .party = {party_layout(cfg)},")
        lines.append(f"    .partyFns = {party_fns(items)},")
        lines.append(f"    .battle = {battle_layout(cfg)},")
        key = "\n".join(lines)
        if key not in configs:
            configs[key] = f"cfg_{len(configs)}"
            out.append(f"static const struct pd_config {configs[key]} = {{")
            out.extend(lines)
            out.extend(["};", ""])
        engine = "PD_GAME_EMERALD" if code[:3] in HOENN_ENGINE or (cfg.get("bagPocketOrder")[:2] == [0, 0] and kind != "UNBOUND" and False) else "PD_GAME_FIRERED"
        if code[:3] in ("BPR", "BPG"):
            engine = "PD_GAME_FIRERED"
        gd = guide(g["guide"]) if g.get("guide") else ("0", "0", "0")
        entries.append((g["crc32"], g["size"], g["file"], g["title"], g["language"], configs[key], table_set(g["tables"]), engine, kind, gd, "0", look_of(g)))

    # Monomyth's own natures (natureNamesMonomyth).
    kt = open(os.path.join(ROOT, "app/src/main/kotlin/com/pokedaisy/app/companion/data/MonomythGen.kt"), encoding="utf-8").read()
    natures = re.findall(r'"([^"]*)"', kt[kt.index("natureNamesMonomyth"):kt.index(")", kt.index("listOf(", kt.index("natureNamesMonomyth")) + 7)])
    assert len(natures) == 250, len(natures)
    out.append("const char* const pd_monomyth_natures[250] = {")
    out.extend(f'    "{n}",' for n in natures)
    out.extend(["};", ""])

    out.append("const struct pd_game_entry pd_game_entries[] = {")
    for crc, size, file, title, lang, cfg, ts, engine, kind, gd, gb, look in sorted(entries):
        name = file.rsplit(".", 1)[0]
        count = gd[2]
        out.append(f"    {{ 0x{crc:08X}u, {c_str(name)}, {c_str(title)}, '{lang}', &{cfg}, &{ts}, {engine}, {c_str(kind)}, {gd[0]}, {gd[1]}, {count}, {gb}, {look} }},")
    out += ["};", f"const int pd_game_entry_count = {len(entries)};", ""]
    looks = json.load(open(os.path.join(sys.argv[1], "looks.json")))
    out.append("const struct pd_theme pd_themes[] = {")
    # POKéDAISY first: it's what the app starts in.
    for t in sorted(looks["themes"], key=lambda t: (t["id"] != 8, t["seasonal"], t["id"])):
        wash = t["wash"]
        wash = "0xFFFFFF" if wash == -1 else f"0x{int(wash) & 0xFFFFFF:06X}"
        out.append(f"    {{ {t['id']}, {c_str(t['label'])}, {wash}, {'true' if t['rainbow'] else 'false'}, {'true' if t['seasonal'] else 'false'},")
        out.append(f"      {{ {look_c(t['light'])},\n        {look_c(t['dark'])} }} }},")
    out += ["};", f"const int pd_theme_count = {len(looks['themes'])};", ""]
    out.append("const struct pd_look pd_gb_looks[PD_GB_LOOK_COUNT][2] = {")
    for k in ("YELLOW", "RED", "PURE_RED", "CRYSTAL"):
        g2 = looks["gb"][k]
        out.append(f"    {{ {look_c(g2['light'])},\n      {look_c(g2['dark'])} }},")
    out += ["};", ""]
    fr = next(g for g in games if g["code"] == "BPRE" and g["rev"] == 1 and g["config"])
    a = party_palette(fr["party-light"]), party_palette(fr["party-dark"]), bag_palette(fr["bag-light"]), bag_palette(fr["bag-dark"])
    idx = next(i for i, l in enumerate(out) if l.startswith("const struct pd_game_entry pd_game_entries"))
    out.insert(idx, "")
    out[idx + 1:idx + 1] = []
    out += [f"const struct pd_party_palette* const pd_party_default[2] = {{ &{a[0]}, &{a[1]} }};",
            f"const struct pd_bag_palette* const pd_bag_default[2] = {{ &{a[2]}, &{a[3]} }};",
            f"const struct pd_party_palette* pd_party_cur = &{a[0]};", f"const struct pd_bag_palette* pd_bag_cur = &{a[2]};", ""]
    open(OUT, "w", encoding="utf-8").write("\n".join(out))
    print(f"wrote {os.path.relpath(OUT, ROOT)}: {len(entries)} ROMs, {len(configs)} configs, {len(sets)} table sets, {os.path.getsize(OUT) // 1024} KB")


if __name__ == "__main__":
    main()
