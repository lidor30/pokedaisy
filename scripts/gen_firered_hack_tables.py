#!/usr/bin/env python3
"""Generates what a FireRed 1.0 hack edited in place names differently from FireRed,
from the ROMs the user owns (16 MB binary hacks of BPRE rev 0; no source, nothing
is downloaded). The app runs these as FIRERED with the result laid over the shared
tables (ActiveTables' `fireRedHack`, a `HackText`).

    scripts/gen_firered_hack_tables.py rocket_edition <Pokemon FireRed Rocket Edition.gba> <firered-rev0.gba>
    scripts/gen_firered_hack_tables.py ash_gray <Pokemon Ash Gray (v4.5.3).gba> <Pokemon Ash Gray Fork Bug Fix (v1.0).gba> <firered-rev0.gba>
    scripts/gen_firered_hack_tables.py ghost_grey <Pokemon Ghost Grey (v1.0.1).gba> <(v1.0.2).gba> <(v1.0.4).gba> <firered-rev0.gba>
    scripts/gen_firered_hack_tables.py odyssey <Pokemon Odyssey (v4.1.1).gba> <firered-rev0.gba>
    scripts/gen_firered_hack_tables.py lets_go <Pokemon Let's Go Pikachu.gba> <... (Translation Only).gba>
        <Pokemon Let's Go Eevee.gba> <... (Translation Only).gba> <firered-rev0.gba>

Each table is found through the literal-pool word retail rev 0 (sha1 41cb23d8...)
loads it from, so a repointed one is followed: gSpeciesNames, gBaseStats (28 bytes,
types +6 / +7, genderRatio +0x10), gMoveNames + gBattleMoves, gItems, sMapNames
(pointers from MAPSEC 0x58), gTypeNames and gTypeEffectiveness.

FireRed Rocket Edition moved gMoveNames (0x08901800) and gBattleMoves (0x08900000) to
add moves 355..486 (387 on are the later games' moves; 487+ are "-" filler), and
gTypeNames (0x08961B50) / gTypeEffectiveness (0x089619E0) for a FAIRY type at id 23
(CLEFAIRY is 23 / 23). Its text is mixed case ("Bulbasaur"), so its whole tables are
written in the ROM's own casing (`ownCase`). Ash Gray keeps every table at rev 0's
address and edits it in place: a few species slots, 25 moves' type / power, key
items, the Orange Islands' map sections, gender ratios (its PIKACHU) and one type
matchup (ELECTRIC on GROUND hits for half) and its own region map (`ownRegionMap`: the
MAP reads it from the ROM); its Fork Bug Fix build shares all of it
(checked). Ash Gray's names are FireRed's capitals, title-cased like the shared
tables (FireRed's own casing puts them back in capitals on screen).

Pokémon Ghost Grey (JoeyZeed, 32 MB) sits on the same repointed tables as Rocket Edition
(the moves at 0x08900000 / 0x08901800, gTypeNames / gTypeEffectiveness with FAIRY at 23, ability
names 0x08950000) and renames every species slot 1..411 (its ghosts: ROWLET, LITWICK, SPIRITLE ...
and its story's trainer pictures past them). Mixed case (`ownCase`); GHOST on GHOST 1.2x, GHOST on
PSYCHIC neutral. Its region map's art is its own (`ownRegionMap`). v1.0.2 and v1.0.4 share every
table; v1.0.1 differs in one move (PURIFY: PSYCHIC, later DARK), so it gets a table set of its own
(`groups`: each group's builds must agree).

Pokémon Odyssey v4.1.1 (32 MB, runs as its own GameKind.ODYSSEY, not FIRERED: ActiveTables reads
`fireRedHackOdyssey` for it directly) keeps rev 0's gSpeciesNames / gBaseStats / gItems / gTypeNames in place
and rewrites ~120 species slots (its regional forms and newer species: ROAR. MOON 234, TLACHTGA 404 ...);
its moves (to 477) and gTypeEffectiveness are repointed. Type 9 (FireRed's "???") is AETHER (GitHub #206:
its CUBONE is GROUND / AETHER, shown as Fairy before). Mixed case.

Pokémon Let's Go Pikachu / Eevee (Kevin Pokefan, English translation; 32 MB, no version in the game) sits on Rocket Edition's
moves (to 510, FAIRY 23, ability names 0x08950000) and grew the species to 987 ids: gSpeciesNames 0x08A000E8, gBaseStats
0x08A02B50 (the newer species in FireRed's Hoenn slots and past EGG 412, regional forms as their own ids - A-RAICHU,
G-MEOWTH ...), so its tables carry every species and their growth rates (`species_count`). Pikachu and Eevee differ in
species / items (each its own HackText, `groups`' fourth field); each one's "Translation Only" build only puts three moves'
effect / accuracy back, so it shares its build's tables. Mixed case.
"""
import hashlib
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_emerald_lang_tables import CHARS  # noqa: E402

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "kotlin",
                       "com", "pokedaisy", "app", "companion", "data")
FIRERED_REV0_SHA1 = "41cb23d8dccc8ebd7c649cd8fbb58eeace6e2fdc"
B = 0x08000000
SPECIES_COUNT, ITEMS_COUNT, FIRST_MAPSEC, SECTIONS, TYPE_COUNT = 412, 375, 0x58, 109, 24
# Retail rev 0's addresses: the hack's table is the word at the literal pools that hold these in rev 0.
REV0 = {
    "species_names": 0x08245EE0, "base_stats": 0x08254784, "move_names": 0x08247094,
    "battle_moves": 0x08250C04, "items": 0x083DB028, "map_names": 0x083F1CAC,
    "type_names": 0x0824F1A0, "type_chart": 0x0824F050,
}
GAMES = {
    "rocket_edition": dict(
        label="Pokemon FireRed Rocket Edition", out="RocketEditionGen.kt", name="RocketEdition",
        sha1s=["033d05374ae34eb84fae62c693ca8f3da23dd1bd"], own_case=True, own_region_map=False, moves_count=487,
        type_names_app={23: "Fairy"},
    ),
    "ash_gray": dict(
        label="Pokemon Ash Gray v4.5.3", out="AshGrayGen.kt", name="AshGray",
        sha1s=["1ee1557db1e4c2e0e94c6306efcc40506bc40cc9", "92ed82145c013069472153391c7aab1cfbf37b75"],
        own_case=False, own_region_map=True, moves_count=355, type_names_app={},
    ),
    "ghost_grey": dict(
        label="Pokemon Ghost Grey", out="GhostGreyGen.kt", name="GhostGrey",
        sha1s=["20dfaea1d5317cf95fe0b493208edce7d50b865f", "74337272566a74625d6dedefe47d2d153eb48ec1",
               "0dafc0116b74406a2df491e26ccb40dd362fb0b7"],
        # (name suffix, its builds' sha1s, what it is): one HackText each.
        groups=[("GhostGrey", ["74337272566a74625d6dedefe47d2d153eb48ec1", "0dafc0116b74406a2df491e26ccb40dd362fb0b7"],
                 "v1.0.2 and v1.0.4 (the same tables)."),
                ("GhostGreyV101", ["20dfaea1d5317cf95fe0b493208edce7d50b865f"], "v1.0.1: PURIFY is PSYCHIC there.")],
        own_case=True, own_region_map=True, moves_count=487, type_names_app={23: "Fairy"},
    ),
    "odyssey": dict(
        label="Pokemon Odyssey v4.1.1", out="OdysseyGen.kt", name="Odyssey",
        sha1s=["8745ddbdbfadf6abaf66de4e9055923b62eb4668"], own_case=True, own_region_map=True, moves_count=478,
        type_names_app={9: "Aether"},
    ),
    "lets_go": dict(
        label="Pokemon Let's Go Pikachu / Eevee", out="LetsGoGen.kt", name="LetsGoPikachu",
        sha1s=["62bb900925118796fb963b7df92d02cb548c404e", "11f6ee3890a0aaeb95903471ec73413c846e86fc",
               "4a41d539cbf6108cfe3344db5ba997fc5660d77d", "5b6146adec1a30f88c565e8f555ab7a578825598"],
        # (name suffix, sha1s, what, a table set of its own rather than the first's with its moves)
        groups=[("LetsGoPikachu", ["62bb900925118796fb963b7df92d02cb548c404e", "11f6ee3890a0aaeb95903471ec73413c846e86fc"],
                 "Let's Go Pikachu and its Translation Only build (the same tables).", True),
                ("LetsGoEevee", ["4a41d539cbf6108cfe3344db5ba997fc5660d77d", "5b6146adec1a30f88c565e8f555ab7a578825598"],
                 "Let's Go Eevee and its Translation Only build (the same tables).", True)],
        own_case=True, own_region_map=False, moves_count=511, species_count=987, type_names_app={23: "Fairy"},
    ),
}
CH = dict(CHARS)


def u8(d, a):
    return d[a - B]


def u16(d, a):
    return struct.unpack_from("<H", d, a - B)[0]


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


def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def title(s):
    """The shared tables' casing: "GS BALL" -> "Gs Ball", "MAIDEN‘S PEAK" -> "Maiden‘s Peak"."""
    out, start = [], True
    for c in s:
        out.append(c.upper() if start else c.lower())
        start = not c.isalpha() and c not in "‘’'é"
    return "".join(out)


def find_pools(fr):
    """Every rev 0 literal-pool offset holding each table's address (the hack's word there is its table)."""
    out = {}
    for k, v in REV0.items():
        out[k] = [i for i in range(0, 0x1E0000, 4) if struct.unpack_from("<I", fr, i)[0] == v]
        assert out[k], k
    return out


def tables(d, pools):
    t = {}
    for k, ps in pools.items():
        words = {struct.unpack_from("<I", d, p)[0] for p in ps}
        # gBattleMoves' pool words include a couple that are code in some hacks: keep ROM pointers only,
        # the one most words agree on.
        cands = [w for w in (struct.unpack_from("<I", d, p)[0] for p in ps) if B <= w < B + len(d)]
        t[k] = max(set(cands), key=cands.count)
        assert t[k] in words
    return t


def read_all(d, t, moves_count, species_count=SPECIES_COUNT):
    sp, ty, gr, gw = {}, {}, {}, {}
    for i in range(1, species_count):
        sp[i] = text(d, t["species_names"] + 11 * i, 11)
        b = t["base_stats"] + 28 * i
        ty[i] = (u8(d, b + 6), u8(d, b + 7))
        gr[i] = u8(d, b + 0x10)
        gw[i] = u8(d, b + 0x13)
    mv = {}
    for i in range(1, moves_count):
        n = text(d, t["move_names"] + 13 * i, 13)
        a = t["battle_moves"] + 12 * i
        mv[i] = (n, u8(d, a + 2), u8(d, a + 1)) if n else None
    it = {}
    for slot in range(1, ITEMS_COUNT):
        a = t["items"] + 44 * slot
        n = text(d, a, 14)
        it[slot] = n if n and u16(d, a + 14) == slot else None
    ms = {}
    for i in range(SECTIONS):
        p = u32(d, t["map_names"] + 4 * i)
        ms[FIRST_MAPSEC + i] = text(d, p, 32) if B <= p < B + len(d) else None
    tn = {i: text(d, t["type_names"] + 7 * i, 7) for i in range(TYPE_COUNT)}
    chart, a = {}, t["type_chart"]
    while u8(d, a) != 0xFF:
        atk, dfn, x = u8(d, a), u8(d, a + 1), u8(d, a + 2)
        a += 3
        if atk != 0xFE:  # 0xFE: the Foresight separator
            chart[atk * 100 + dfn] = x * 10
    return dict(species=sp, types=ty, gender=gr, growth=gw, moves=mv, items=it, mapsecs=ms, type_names=tn, chart=chart)


def diff(a, b):
    return {k: v for k, v in b.items() if v is not None and a.get(k) != v}


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in GAMES:
        sys.exit(__doc__)
    g = GAMES[sys.argv[1]]
    roms = [open(p, "rb").read() for p in sys.argv[2:-1]]
    fr = open(sys.argv[-1], "rb").read()
    if hashlib.sha1(fr).hexdigest() != FIRERED_REV0_SHA1:
        sys.exit(f"not retail FireRed rev 0 (sha1 {FIRERED_REV0_SHA1})")
    if sorted(hashlib.sha1(d).hexdigest() for d in roms) != sorted(g["sha1s"]):
        sys.exit(f"pass every {g['label']} build: sha1s {g['sha1s']}")
    pools = find_pools(fr)
    base = read_all(fr, tables(fr, pools), 355)
    species_count = g.get("species_count", SPECIES_COUNT)
    by_sha = {hashlib.sha1(d).hexdigest(): d for d in roms}
    groups = [x if len(x) == 4 else (*x, False) for x in (g.get("groups") or [(g["name"], g["sha1s"], None)])]
    head = ("package com.pokedaisy.app.companion.data\n\n"
            f"// GENERATED by scripts/gen_firered_hack_tables.py from the {g['label']} ROM"
            + ("s" if len(groups) > 1 else "") + "\n"
            f"// (sha1 {g['sha1s'][0][:8]}...) against retail FireRed rev 0 - do not hand-edit. What it names /\n"
            "// types differently, by FireRed's ids (ActiveTables lays these over the shared tables).\n\n")
    blocks, full = [], {}
    for suffix, shas, what, own in groups:
        hacks = [read_all(by_sha[sh], tables(by_sha[sh], pools), g["moves_count"], species_count) for sh in shas]
        for h in hacks[1:]:
            assert h == hacks[0], f"the builds' tables differ ({suffix})"
        # A full table set for the first group and any marked as its own (Let's Go Eevee); else the first's with its moves.
        if not full or own:
            full[suffix] = hacks[0]
            blocks.append(block(sys.argv[1], g, base, hacks[0], suffix, what))
        else:
            name = next(iter(full))
            blocks.append(moves_block((name, full[name]), hacks[0], suffix, what))
    path = os.path.join(OUT_DIR, g["out"])
    with open(path, "w", encoding="utf-8") as f:
        f.write(head + "\n".join(blocks))
    print("wrote", os.path.relpath(path))


def moves_block(first, h, suffix, what):
    """A later group that differs from the first in moves only: the first's tables with its moves over them."""
    name, f = first
    assert all(h[k] == f[k] for k in h if k != "moves"), f"{suffix} differs from {name} past its moves"
    mv = {k: v for k, v in h["moves"].items() if v and f["moves"].get(k) != v}
    assert mv
    print(f"{suffix}: {name}'s tables, {len(mv)} moves its own")
    return (f"// {what}\n"
            f"internal val fireRedHack{suffix}: HackText by lazy {{\n"
            f"    with(fireRedHack{name}) {{\n"
            f"        HackText(species, types, moves + mapOf(\n" +
            "".join(f"            {i} to MoveInfo({kstr(n)}, {t_}, {p}),\n" for i, (n, t_, p) in sorted(mv.items())) +
            "        ), items, mapSecs, typeNames, typeChart, genderRatios, ownCase, ownRegionMap, growthRates = growthRates)\n"
            "    }\n"
            "}\n")


def block(key, g, base, h, suffix, what):
    own = g["own_case"]
    if own:
        # Mixed-case text: every name as the ROM has it (the shared tables are title-cased, not the game's).
        sp = {k: v for k, v in h["species"].items() if v}
        mv = {k: v for k, v in h["moves"].items() if v}
        it = {k: v for k, v in h["items"].items() if v}
        ms = {k: v for k, v in h["mapsecs"].items() if v}
    else:
        sp = {k: title(v) for k, v in diff(base["species"], h["species"]).items()}
        mv = {k: (title(n), t_, p) for k, (n, t_, p) in diff(base["moves"], h["moves"]).items()}
        it = {k: title(v) for k, v in diff(base["items"], h["items"]).items()}
        ms = {k: title(v) for k, v in diff(base["mapsecs"], h["mapsecs"]).items()}
    ty = diff(base["types"], h["types"])
    gr = diff(base["gender"], h["gender"])
    species_count = g.get("species_count", SPECIES_COUNT)
    chart = h["chart"] if h["chart"] != base["chart"] else None
    new_types = {i: n for i, n in h["type_names"].items() if n and base["type_names"].get(i) is None}

    if key == "rocket_edition":
        assert sp[1] == "Bulbasaur" and ty[35] == (23, 23) and new_types == {23: "Fairy"}
        assert mv[463] == ("Play Rough", 23, 90) and 487 not in mv and it[369] == "Steroids"
        assert chart[2316] == 200 and chart[1623] == 0
    elif key == "ghost_grey":
        assert sp[1] == "Rowlet" and sp[14] == "Poltersect" and sp[28] == "Hexray" and new_types == {23: "Fairy"}
        assert ty[1] == (12, 7) and ty[23] == (7, 23) and it[17] == "SourPatchBag" and ms[110] == "Jaspar Town"
        assert chart[707] == 120 and chart[714] == 100 and 486 not in mv
        assert mv[451] == ("Purify", 17 if suffix == "GhostGrey" else 14, 10)
    elif key == "lets_go":
        assert sp[25] == "Pikachu" and sp[386] == "Snom" and sp[411] == "Fuecoco" and sp[413] == "Falinks"
        assert sp[986] == "Urshifu" and new_types == {23: "Fairy"} and ty[35] == (23, 23)
        assert mv[463] == ("Play Rough", 23, 90) and 511 not in mv
        # The versions' own: Pikachu's P-WOOPER / WICKED BLOW, Eevee's WOOPER / SURGING STRIKES.
        assert (sp[194], mv[510]) == (("P-Wooper", ("Wicked Blow", 17, 80)) if suffix == "LetsGoPikachu"
                                      else ("Wooper", ("SurgingStrks", 11, 25)))
    elif key == "odyssey":
        assert sp[104] == "Cubone" and ty[104] == (4, 9) and sp[234] == "Roar. Moon" and sp[404] == "Tlachtga"
        assert new_types == {9: "Aether"} and mv[477] == ("Twineedle", 6, 60) and 478 not in mv and it[1] == "Master Ball"
        assert chart[409] != 100 or chart[904] != 100
    else:
        assert gr and chart[1304] == 50 and it[349] == "Gs Ball" and ms[143] == "Valencia Island"
        assert not new_types
    assert set(new_types) == set(g["type_names_app"])

    gender_hex = "".join("%02x" % (gr.get(i, base["gender"].get(i, 255))) for i in range(species_count))
    # Past FireRed's 412 species its gExperienceTables curve per species (FireRed's own for the rest).
    growth_hex = "".join("%02x" % h["growth"].get(i, 0) for i in range(species_count)) if species_count > SPECIES_COUNT else None
    body = (
        (f"// {what}\n" if what else "") +
        f"internal val fireRedHack{suffix}: HackText by lazy {{\n"
        f"    HackText(\n"
        f"        species = mapOf(\n" +
        "".join(f"            {i} to {kstr(s)},\n" for i, s in sorted(sp.items())) + "        ),\n"
        f"        types = mapOf(\n" +
        "".join(f"            {i} to SpeciesTypes({a}, {b}),\n" for i, (a, b) in sorted(ty.items())) + "        ),\n"
        f"        moves = mapOf(\n" +
        "".join(f"            {i} to MoveInfo({kstr(s)}, {t_}, {p}),\n" for i, (s, t_, p) in sorted(mv.items())) + "        ),\n"
        f"        items = mapOf(\n" +
        "".join(f"            {i} to {kstr(s)},\n" for i, s in sorted(it.items())) + "        ),\n"
        "        // Section names only: the boxes and cursor grid are FireRed's.\n"
        f"        mapSecs = mapOf(\n" +
        "".join(f"            {i} to {kstr(s)},\n" for i, s in sorted(ms.items())) + "        ),\n"
        f"        typeNames = mapOf(\n" +
        "".join(f"            {i} to {kstr(s)},\n" for i, s in sorted(g["type_names_app"].items())) + "        ),\n" +
        ("        typeChart = null,\n" if chart is None else
         "        // gTypeEffectiveness: key = atkType*100+defType -> percent, pairs not listed are 100.\n"
         "        typeChart = mapOf(\n" + "".join(f"            {k} to {v},\n" for k, v in chart.items()) + "        ),\n") +
        ("        genderRatios = null,\n" if not gr else
         "        // gBaseStats genderRatio by species id (it changed some of FireRed's).\n"
         f"        genderRatios = unhexRatios(\"{gender_hex}\"),\n") +
        f"        ownCase = {str(own).lower()},\n"
        f"        ownRegionMap = {str(g['own_region_map']).lower()},\n" +
        ("" if growth_hex is None else
         "        // gBaseStats growthRate by species id, every one of its species.\n"
         f"        growthRates = unhexBytes(\"{growth_hex}\"),\n") +
        "    )\n"
        "}\n")
    print(f"{suffix}: {len(sp)} species, {len(ty)} types, {len(mv)} moves, {len(it)} items, {len(ms)} map sections, "
          f"{len(gr)} gender ratios, chart {'own' if chart else 'FireRed'}")
    return body


if __name__ == "__main__":
    main()
