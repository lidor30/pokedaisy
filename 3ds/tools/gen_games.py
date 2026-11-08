#!/usr/bin/env python3
"""Writes 3ds/core/pd_games_gen.c: what the app shows of every game it knows beyond gamedata's config.

    cd ui-preview && gradle gameExport -PgameExport -Proms=<ROMs folder> -Pout=<dir>
    python3 3ds/tools/gen_games.py <dir>

The export (ui-preview/src/render/GameExport.kt) runs each ROM through the app's own detection and writes what it
picked. The configs, names and guides the 3DS / Linux companion reads are gamedata's (gamedata/targets/3ds.toml ->
pd_configs_gen.h, pd_text_gen.c); this writes only what gamedata doesn't hold, from the app's Kotlin as it chose it for
that ROM: the game's look (party / bag palettes, slot style, party backdrop, a hack's region map pages and sections, the
themes), its PC's moves (BoxSwapFns), its field items where the app picks them in code (fieldItemsForGame: a hack's
FLY / PC / Repel, USE / GIVE / TAKE) and its own bags (Quetzal's BAG3, Elite Redux's owned bits), and a Game Boy cart's
DEX tables. A ROM's SHA-1 finds its entry (pd_game_identify); the field items overlay the config's empty fields.
"""
import json
import os
import re
import sys
import tomllib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TARGET = os.path.join(ROOT, "gamedata/targets/3ds.toml")
OUT = os.path.join(ROOT, "3ds/core/pd_games_gen.c")

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
           '#include "pd_games.h"', '#include "pd_gb.h"', '#include "pd_style.h"', ""]

    # --- tables, each kept once ---
    seen = {}

    def emit(prefix, text_lines, key):
        if key in seen:
            return seen[key]
        name = f"{prefix}{len(seen)}"
        seen[key] = name
        out.extend(line.replace("@", name) for line in text_lines)
        return name

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
        f = [".party = { &" + party_palette(g["party-light"]) + ", &" + party_palette(g["party-dark"]) + " }",
             ".bag = { &" + bag_palette(g["bag-light"]) + ", &" + bag_palette(g["bag-dark"]) + " }",
             ".backdrop = { " + ", ".join(rgb(c) for c in back) + " }"]
        rp = regions(g)
        if rp != "0, 0":
            name, count = rp.split(", ")
            f += [f".regionPages = {name}", f".regionPageCount = {count}"]
        if g.get("readsRomRegionMap"):
            f.append(".romRegionMap = true")
        st = party_style(g)
        if st != "0":
            f.append(f".partyStyle = {st}")
        pb = party_backdrops(g)
        if pb != "0, 0":
            name, count = pb.split(", ")
            f += [f".partyBackdrops = {name}", f".partyBackdropCount = {count}"]
        return f

    def gb_dex(d, ints):
        """A cart's Gen1DexTables / Gen2DexTables as file offsets (Gen2Dex.off)."""
        def off(p):
            if not p:
                return 0
            return p["second"] if p["first"] == 0 else p["first"] * 0x4000 + p["second"] - 0x4000

        def strings(prefix, values):
            return emit(prefix + "_", [f"static const char* const @[{len(values)}] = {{"] +
                        ["    " + ", ".join(c_str(x.replace("\n", "\x01")).replace("\x01", "\\n") for x in values[i:i + 16]) + ","
                         for i in range(0, len(values), 16)] + ["};", ""],
                        (prefix, json.dumps(values)))

        a = [f".count = {d['count']}", f".metric = {'true' if d['metric'] else 'false'}", f".unitSpace = {'true' if d['unitSpace'] else 'false'}",
             f".decimal = '{d['decimal']}'", f".suffix = {c_str(d['suffix'])}", f".chars = {strings('dexchars', d['chars'])}"]
        if d.get("gen1"):
            t = d["gen1"]
            breaks = ", ".join(str(x) for x in t["picsBankBreaks"][:4])
            a += [f".baseData = {off(t['baseStats'])}u", f".dexEntryPointers = {off(t['dexEntryPointers'])}u", f".palettes = {off(t['monsterPalettes'])}u",
                  f".baseDataSize = {t['baseStatsSize']}", f".colors = {off(t['cgbPalettes'])}u", f".mewBaseStats = {off(t.get('mewBaseStats'))}u",
                  f".dexEntryData = {off(t.get('dexEntryData'))}u", f".categoryMapping = {off(t.get('categoryMapping'))}u",
                  f".categoryStrings = {off(t.get('categoryStrings'))}u", f".picsBank = {t['picsBank']}", f".picsBankBreaks = {{ {breaks} }}",
                  f".fossilKabutops = {t['fossilKabutops']}", f".fossilKabutopsBank = {t['fossilKabutopsBank']}", f".mewPicsBank = {t['mewPicsBank']}",
                  f".picBankOffset = {t['picBankOffset']}", f".textFar = {t['textFar']}"]
        else:
            t = d["gen2"]
            a += [f".baseData = {off(t['baseData'])}u", f".dexEntryPointers = {off(t['dexEntryPointers'])}u", f".palettes = {off(t['palettes'])}u",
                  f".baseDataSize = {t['baseDataSize']}", f".dexEntryBanks = {off(t['dexEntryBanks'])}u", f".picPointers = {off(t['picPointers'])}u",
                  f".unownPicPointers = {off(t['unownPicPointers'])}u", f".picsBanks = {off(t['picsBanks'])}u", f".textDict = {off(t.get('textDict'))}u",
                  f".picBankFixes = {off(t.get('picBankFixes'))}u", f".unown = {t['unown']}", f".dexEntryFirstBank = {t['dexEntryFirstBank']}",
                  f".picBankDirect = {'true' if t['picBankDirect'] else 'false'}",
                  f".dexEntryFar = {'true' if t.get('dexEntryFar') else 'false'}"]
            p = t.get("packed")
            if p:
                a += [f".packedTree = {off(p['tree'])}u", f".packedRareChars = {off(p['rareChars'])}u", f".packedRareRanges = {off(p['rareRanges'])}u",
                      f".packedControlChars = {off(p['controlChars'])}u"]
            if t.get("dexOrder"):
                a += [f".dexOrder = {ints('dexorder', 'uint16_t', t['dexOrder'])}", f".dexOrderCount = {len(t['dexOrder'])}"]
            if t.get("polished"):
                a += [f".polished = {ints('polishedaddrs', 'int32_t', t['polished'])}", f".polishedChars = {strings('polishedchars', d['polishedChars'])}",
                      f".polishedSpecies = {d['polishedSpecies']}"]
        text = "{ " + ", ".join(a) + " }"
        return "&" + emit("dex_", [f"static const struct pd_gb_dex @ = {text};", ""], ("dex", text))

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

    patches = {}
    entries = []
    for g in games:
        cfg, gbc = g["config"], g.get("gb")
        if not cfg and not gbc:
            continue
        f = [f".sha1 = {c_str(g['sha1'])}", f".file = {c_str(g['file'].rsplit('.', 1)[0])}", f".title = {c_str(g['title'])}",
             f".language = '{g['language']}'", f".gameKind = {c_str(g['kind'])}"]
        f += look_of(g)
        if cfg:
            # What the app picks in code and gamedata doesn't hold: a config of its own that pd_game_identify lays over
            # gamedata's empty fields (its FLY / PC / Repel, USE / GIVE / TAKE, a bag of its own).
            items = g.get("fieldItems")
            lines = []
            for part in (["fly"] if items and items.get("fly") else []) + (["field"] if items else []):
                for field, spec in parts[part].items():
                    path, none = (spec["path"], spec.get("none")) if isinstance(spec, dict) else (spec, None)
                    v = walk(cfg, items, path)
                    if isinstance(v, dict):
                        continue
                    line = c_field(field, v, none)
                    if line:
                        lines.append(line)
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
            fns = party_fns(items)
            if fns != "0":
                lines.append(f"    .partyFns = {fns},")
            if lines:
                key = "\n".join(lines)
                if key not in patches:
                    patches[key] = f"patch_{len(patches)}"
                    out.append(f"static const struct pd_config {patches[key]} = {{")
                    out.extend(lines)
                    out.extend(["};", ""])
                f.append(f".fieldCfg = &{patches[key]}")
            bs = g.get("boxSwap")
            if bs:
                crcs = ", ".join(f"{int(c)}u" for c in bs["codeCrcs"])
                text = (f"{{ {bs['boxMonToMon']}u, {bs['boxMonRestorePP']}u, {bs['getBoxMonData']}u, {bs['storagePtr']}u, "
                        f"{{ {crcs} }} }}")
                f.append(".boxSwap = &" + emit("bs_", [f"static const struct pd_box_swap @ = {text};", ""], ("bs", text)))
        if gbc and gbc.get("dex"):
            def ints(prefix, ctype, values):
                return emit(prefix + "_", [f"static const {ctype} @[{len(values)}] = {{ " + ", ".join(str(v) for v in values) + " };", ""],
                            (prefix, json.dumps(values)))
            f.append(".gbDex = " + gb_dex(gbc["dex"], ints))
        # A Game Boy cart's own art (party icons, town maps, the player): pd_gb.c / pd_gb_map.c draw it where the core's
        # own tables (Yellow, Red / Blue, Crystal, Gold / Silver) don't cover the cart.
        art = gbc.get("art") if gbc else None
        if art:
            def off(pair):
                if not pair:
                    return 0
                bank, a = pair["first"], pair["second"]
                return a if bank == 0 else bank * 0x4000 + (a - 0x4000)

            def rgb(v):
                return f"0x{int(v) & 0xFFFFFF:06X}u"

            a = []
            if gbc["gen"] == 1:
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
                      f".cosmeticForms = {gbc.get('cosmeticForms', 0)}"]
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
            f.append(".gbArt = &" + emit("art_", [f"static const struct pd_gb_art @ = {text};", ""], ("art", text)))
        if (g.get("regionImages") and regions(g) != "0, 0") or (gbc and gbc.get("art")):
            # A hack's own map pages (or a Game Boy cart's town maps): its sections' places on them, from the app's MapSecData.
            t = json.load(open(os.path.join(src, f"tables-{g['tables']}.json")))
            at = {int(k): v for k, v in t["mapsecs"].items() if len(v) >= 6}
            nAt = max(at, default=-1) + 1
            if nAt:
                rows = ", ".join("{ " + ", ".join(str(max(0, min(255, int(x)))) if int(x) >= 0 else "255"
                                                  for x in (at[i][1:6] if i in at else (255, 0, 0, 0, 0))) + " }" for i in range(nAt))
                f += [".mapsecAt = " + emit("ma_", [f"static const unsigned char @[{nAt}][5] = {{ {rows} }};", ""], ("ma", rows)),
                      f".mapsecAtCount = {nAt}"]
        entries.append((g["sha1"], "    { " + ", ".join(f) + " },"))

    # Monomyth's own natures (natureNamesMonomyth).
    kt = open(os.path.join(ROOT, "app/src/main/kotlin/com/pokedaisy/app/companion/data/MonomythGen.kt"), encoding="utf-8").read()
    natures = re.findall(r'"([^"]*)"', kt[kt.index("natureNamesMonomyth"):kt.index(")", kt.index("listOf(", kt.index("natureNamesMonomyth")) + 7)])
    assert len(natures) == 250, len(natures)
    out.append("const char* const pd_monomyth_natures[250] = {")
    out.extend(f'    "{n}",' for n in natures)
    out.extend(["};", ""])

    out.append("const struct pd_game_entry pd_game_entries[] = {")
    seen1 = set()
    for sha1, line in sorted(entries):
        if sha1 in seen1:  # the same ROM under two names
            continue
        seen1.add(sha1)
        out.append(line)
    out += ["};", f"const int pd_game_entry_count = {len(seen1)};", ""]
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
    print(f"wrote {os.path.relpath(OUT, ROOT)}: {len(seen1)} ROMs, {len(patches)} field-item configs, {os.path.getsize(OUT) // 1024} KB")


if __name__ == "__main__":
    main()
