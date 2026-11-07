#!/usr/bin/env python3
"""Lists what the Linux / 3DS companion (3ds/core) has against the Android app, per ROM and per feature, so the
"Parity with the Android app" table in docs/PLORPOS_V0.2.md is written from facts, not memory.

Per hack config (gamedata/targets/3ds.toml): the app's records (pokedex, guideTables -> GuideId, trainerCard) against the
C config's parts and the GUIDE pd_game.c gives it; the GUIDE's area data; place names. Then the Game Boy and
feature rows, each a check of the C sources for the piece that does it.

    python3 3ds/tools/parity_audit.py            # markdown report
    python3 3ds/tools/parity_audit.py --gaps     # only what's missing
"""
import os
import re
import sys
import tomllib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
KT = os.path.join(ROOT, "app/src/main/kotlin/com/pokedaisy/app/companion/data")
CORE = os.path.join(ROOT, "3ds/core")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def gamedata_configs():
    """Every gamedata config key -> its fields with `base` resolved."""
    raw = {}
    for sub in ("retail", "hacks", "ports"):
        d = os.path.join(ROOT, "gamedata/gen3", sub)
        for name in sorted(os.listdir(d)):
            if name.endswith(".toml"):
                raw.update(tomllib.loads(read(os.path.join(d, name))).get("config", {}))

    def resolved(key, seen=()):
        c = raw[key]
        base = c.get("base")
        out = dict(resolved(base, seen + (key,))) if base in raw and base not in seen else {}
        out.update(c)
        return out

    return {k: resolved(k) for k in raw}


def guide_ids():
    """GUIDE_TABLES_* -> GuideId name (FIRERED when none is given, .copy() inherits)."""
    text = "\n".join(read(os.path.join(KT, f)) for f in os.listdir(KT) if f.startswith("Guide") and f.endswith(".kt"))
    out, parent = {}, {}
    for m in re.finditer(r"^(?:private |internal )?val (GUIDE_TABLES_\w+)\s*(?::\s*\w+\s*)?=\s*(GUIDE_TABLES_\w+\.copy\(|GuideTables\()", text, re.M):
        body = text[m.end():]
        nxt = re.search(r"^(val|fun|private|internal|object|class|/\*\*) ", body, re.M)
        body = body[:nxt.start()] if nxt else body
        g = re.search(r"\bguide\s*=\s*GuideId\.(\w+)", body)
        if g:
            out[m.group(1)] = g.group(1)
        elif m.group(2).startswith("GUIDE_TABLES_"):
            parent[m.group(1)] = m.group(2)[:-len(".copy(")]
        else:
            out[m.group(1)] = "FIRERED"
    for k, p in parent.items():
        while p in parent:
            p = parent[p]
        # a parent that lives in gamedata/ (retail's records): the game it names
        out[k] = out.get(p) or {"GUIDE_TABLES_EMERALD": "EMERALD", "GUIDE_TABLES_FIRERED": "FIRERED",
                                "GUIDE_TABLES_LEAFGREEN": "LEAFGREEN"}.get(p, "?")
    return out


def c_known_roms():
    """C config name (PD_CONFIG_X's X) -> set of PD_GUIDE_ ids pd_game.c gives its ROMs."""
    text = read(os.path.join(CORE, "pd_game.c"))
    out = {}
    for m in re.finditer(r'\{"[0-9a-f]{40}",\s*&PD_CONFIG_(\w+),\s*PD_GAME_\w+,\s*"[^"]*",\s*PD_GUIDE_(\w+)\}', text):
        out.setdefault(m.group(1), set()).add(m.group(2))
    return out


def c_guides():
    text = read(os.path.join(CORE, "pd_guide.c"))
    body = text[text.index("const struct pd_guide* pd_guide_for("):text.index("int pd_quetzal_region") if "int pd_quetzal_region" in text else None]
    guides = set(re.findall(r"case PD_GUIDE_(\w+):", body))
    areas = set(re.findall(r"case PD_GUIDE_(\w+):(?: case PD_GUIDE_\w+:)* \*count = pd_areas_", text))
    # a fall-through case line (RUBY:) shares the next one's areas
    for m in re.finditer(r"case PD_GUIDE_(\w+):\n\s*case PD_GUIDE_\w+: \*count", text):
        areas.add(m.group(1))
    return guides, areas


def android_areas():
    """GuideIds whose guide has area data (GuideAreas*Gen.kt), by file stem upper-snake."""
    out = set()
    for f in os.listdir(KT):
        m = re.match(r"GuideAreas(\w+)Gen\.kt$", f)
        if m:
            out.add(re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", m.group(1)).upper())
    return out


def c_mapsec_named(cfg_name):
    """Whether C names this config's map sections: pd_text.mapsecs / mapsecsRom, else its engine's retail names
    (`make -C 3ds test-roms` checks each ROM really gets 20+). Before those, only Emerald Legacy had names."""
    if "mapsecsRom" in read(os.path.join(CORE, "pd_snapshot.c")):
        return True
    return cfg_name == "EMERALD_LEGACY"


ALIASES = {  # GuideId -> PD_GUIDE_ name where they differ
    "YELLOW": "GB_YELLOW", "RED": "GB_RED", "BLUE": "GB_BLUE", "CRYSTAL": "GB_CRYSTAL",
    "POLISHED_CRYSTAL": "GB_POLISHED",
}


def per_rom():
    cfgs = gamedata_configs()
    target = tomllib.loads(read(os.path.join(ROOT, "gamedata/targets/3ds.toml")))["config"]
    gids = guide_ids()
    known = c_known_roms()
    cg, ca = c_guides()
    aa = android_areas()
    rows = []
    for key, t in target.items():
        if key.startswith(("port_", "firered", "leafgreen", "emerald_retail", "ruby", "sapphire")):
            continue
        a = cfgs.get(key, {})
        name = t["name"]
        parts = set(t.get("parts", []))
        gid = gids.get(a.get("guideTables", ""), None) if a.get("guideTables") else None
        cguide = sorted(known.get(name, set()) - {"NONE"})
        row = {
            "config": key, "name": name,
            "dex": (bool(a.get("pokedex")), "dex" in parts),
            "card": (bool(a.get("trainerCard")), "card" in parts),
            "guide": (gid, ",".join(cguide) or "-"),
            # Kaizo: the app's EMERALD guide over Kaizo's own wild table (C's EMERALD_KAIZO id)
            "guide_ok": gid is None or any(ALIASES.get(gid, gid) == c or (gid == "EMERALD" and c == "EMERALD_KAIZO") for c in cguide if c in cg),
            "areas": (gid in aa if gid else False, any(g in ca for g in cguide)),
            "names": (True, c_mapsec_named(name)),
        }
        rows.append(row)
    return rows


# Feature rows: (row, what Android has, a C check: a regex over 3ds/core + linux sources, or a callable)
def c_sources():
    out = ""
    for d in (CORE, os.path.join(ROOT, "linux")):
        for f in os.listdir(d):
            if f.endswith((".c", ".h")):
                out += read(os.path.join(d, f))
    return out


FEATURES = [
    ("Shiny icons", "recoloured party icons from the ROM's shiny palette (MonArt shiny pal)", r"pd_draw_mon_icon_shiny"),
    ("Hack item descriptions", "each hack's item descriptions read from its ROM (RomItemText)", r"rom_item_description"),
    ("GB DEX pages: Gen 1", "sprite, category, size, text, stats (Gen1Dex)", r"pd_gb_dex_entry"),
    ("GB DEX pages: Gen 2", "sprite, category, size, text, stats (Gen2Dex)", r"pd_gb2_dex_entry"),
    ("GB DEX: Prism", "Prism's list (Gen2SpeciesPrism)", r"pd_gb_prism = \{[^}]*dexCount"),
    ("GB GUIDE: Gen 1 HERE / WHERE IS", "wild Pokémon and things by map (GuideGen1Gen)", r"gb_wild_here"),
    ("GB GUIDE: Crystal", "NEXT BOSS incl. RED, HERE with time of day (GuideCrystal, GuideCrystalGen)", r"PD_GUIDE_GB_CRYSTAL"),
    ("GB GUIDE: Crystal headbutt trees", "HERE's headbutt common / rare lists", r"PD_WILD_HEADBUTT"),
    ("GB GUIDE: Polished Crystal", "NEXT BOSS, HERE (GuidePolishedCrystal, GuidePolishedCrystalGen)", r"PD_GUIDE_GB_POLISHED"),
    ("GB MAP", "town map / Pokegear (PokegearMap.kt)", r"pd_gb_town_map"),
    ("GB CARD", "trainer card for Game Boy games", r"pd_gb_card"),
    ("GB look: ROM font", "the game's own font (Gen1Art / Gen2Art)", r"pd_gb_font_use"),
    ("GB look: ROM party icons (Red, Blue, Yellow, Crystal, Gold, Silver)", "the game's party icons", r"pd_gb_icon\b"),
    ("GB look: ROM party icons (Crystal Clear, Prism, Polished, PureRGB)", "their LZ / sheet icons", r"gb_icons_lz"),
    ("GB look: palettes", "Red / Blue tint, PureRGB ink", r"pd_gb_ink"),
    ("GB party switch", "POKéMON switch in battle (GbPartySwitch.kt)", r"pd_gb_party_switch|gb_switch"),
    ("GB quick menu", "quick-menu field items on Game Boy", r"gb_quick|pd_gb_field"),
    ("Hack CARD: Imperium", "IMPERIUM card style", r"PD_CARD_IMPERIUM"),
    ("European Yellow", "DE/FR/IT/ES Yellow configs (YellowLanguages.kt)", r"YELLOW_DE|YELLOW_FR"),
]


def card_styles():
    kt = read(os.path.join(KT, "TrainerCard.kt"))
    android = re.findall(r"\b([A-Z_0-9]+)\((?:false|true)\)", kt[kt.index("enum class CardStyle"):kt.index("}", kt.index("enum class CardStyle"))])
    target = tomllib.loads(read(os.path.join(ROOT, "gamedata/targets/3ds.toml")))
    c = set(target["names"]["CardStyle"])
    return android, c


def main():
    gaps = "--gaps" in sys.argv
    yes = lambda b: "yes" if b else "no"
    out = ["# PokeDaisy companion: Android vs Linux/3DS C", "", "## Per hack config", "",
           "| config | DEX app/C | CARD app/C | GUIDE app / C | areas app/C | place names C |",
           "| --- | --- | --- | --- | --- | --- |"]
    missing = []
    for r in per_rom():
        d, c, g, a, n = r["dex"], r["card"], r["guide"], r["areas"], r["names"]
        bad = []
        if d[0] and not d[1]:
            bad.append("dex")
        if c[0] and not c[1]:
            bad.append("card")
        if not r["guide_ok"]:
            bad.append("guide")
        if a[0] and not a[1]:
            bad.append("areas")
        if not n[1]:
            bad.append("names")
        if bad:
            missing.append(f"{r['config']}: {', '.join(bad)}")
        if gaps and not bad:
            continue
        out.append(f"| {r['config']} | {yes(d[0])}/{yes(d[1])} | {yes(c[0])}/{yes(c[1])} | {g[0] or '-'} / {g[1]} | "
                   f"{yes(a[0])}/{yes(a[1])} | {yes(n[1])} |")
    android, c = card_styles()
    out += ["", "## Card styles", "", f"app: {', '.join(android)}", f"C missing: {', '.join(s for s in android if s not in c) or 'none'}"]
    src = c_sources()
    out += ["", "## Features", "", "| feature | app | C |", "| --- | --- | --- |"]
    for name, app, rx in FEATURES:
        ok = bool(re.search(rx, src))
        if not ok:
            missing.append(name)
        if gaps and ok:
            continue
        out.append(f"| {name} | {app} | {yes(ok)} |")
    out += ["", f"## Gaps ({len(missing)})", ""] + [f"- {m}" for m in missing]
    print("\n".join(out))


if __name__ == "__main__":
    main()
