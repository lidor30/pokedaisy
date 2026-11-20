#!/usr/bin/env python3
"""Gen 4 (DS) name / data tables for the companion, from a pret decomp.

    python3 scripts/gen_gen4_tables.py platinum     # $DECOMPS/pokeplatinum
    python3 scripts/gen_gen4_tables.py heartgold    # $DECOMPS/pokeheartgold (HeartGold and SoulSilver: one table set)

Writes shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data/Gen4TablesPlatinum.kt (and the Town Map's in
Gen4TownMapPlatinum.kt): species names and types, moves
(name, type, power), items (name, the bag pocket the game files them in) and the place name of every map header (the
Location a save keeps is a map header id, Gen4Reader.kt), and each species' party-icon palette (0-2: which of the
icon archive's three palettes NdsArt draws it with; 494 = the egg's) - indices, no art - and FLY's 20 spots. Names are the game's own, as the decomp spells them: species
in capitals, moves and items in mixed case, as the English games show them.

The type ids are pokeplatinum's TYPE_* order (generated/pokemon_types.txt), which is Gen 3's: the shared type chart
and type names apply. The script asserts that.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data")

GEN3_TYPES = ["TYPE_NORMAL", "TYPE_FIGHTING", "TYPE_FLYING", "TYPE_POISON", "TYPE_GROUND", "TYPE_ROCK", "TYPE_BUG",
              "TYPE_GHOST", "TYPE_STEEL", "TYPE_MYSTERY", "TYPE_FIRE", "TYPE_WATER", "TYPE_GRASS", "TYPE_ELECTRIC",
              "TYPE_PSYCHIC", "TYPE_ICE", "TYPE_DRAGON", "TYPE_DARK"]

# The game's pockets -> the companion's (Telemetry.kt's POCKET_*); MEDICINE / MAIL / BATTLE ITEMS ride with ITEMS.
POCKETS = {"POCKET_ITEMS": "POCKET_ITEMS", "POCKET_MEDICINE": "POCKET_ITEMS", "POCKET_MAIL": "POCKET_ITEMS",
           "POCKET_BATTLE_ITEMS": "POCKET_ITEMS", "POCKET_BALLS": "POCKET_POKE_BALLS", "POCKET_TMHMS": "POCKET_TM_HM",
           "POCKET_BERRIES": "POCKET_BERRIES", "POCKET_KEY_ITEMS": "POCKET_KEY_ITEMS"}


def lines(path):
    with open(path) as f:
        return [l.strip() for l in f if l.strip()]


def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def platinum_species_moves(dec):
    """Platinum's species (name, types) and moves (name, type, power), as main() reads them."""
    types = lines(os.path.join(dec, "generated/pokemon_types.txt"))
    type_id = {t: i for i, t in enumerate(types)}
    species = {}
    for i, sym in enumerate(lines(os.path.join(dec, "generated/species.txt"))):
        if i == 0 or i > 493:
            continue
        d = json.load(open(os.path.join(dec, "res/pokemon", sym[len("SPECIES_"):].lower(), "data.json")))
        t1, t2 = d["types"]
        species[i] = (d["pokedex_data"]["en"]["name"], type_id[t1], type_id[t2])
    moves = {}
    for i, sym in enumerate(lines(os.path.join(dec, "generated/moves.txt"))):
        path = os.path.join(dec, "res/moves", sym[len("MOVE_"):].lower(), "data.json")
        if i and os.path.exists(path):
            d = json.load(open(path))
            moves[i] = (d["name"], type_id[d["type"]], d["power"])
    return species, moves


def narc_members(path):
    """A NARC's members' bytes (BTAF offsets into GMIF)."""
    d = open(path, "rb").read()
    assert d[:4] == b"NARC"
    o = int.from_bytes(d[0x0C:0x0E], "little")
    count = int.from_bytes(d[o + 8:o + 10], "little")
    fat = o + 12
    o += int.from_bytes(d[o + 4:o + 8], "little")
    o += int.from_bytes(d[o + 4:o + 8], "little")
    base = o + 8
    out = []
    for i in range(count):
        s0 = int.from_bytes(d[fat + i * 8:fat + i * 8 + 4], "little")
        e0 = int.from_bytes(d[fat + i * 8 + 4:fat + i * 8 + 8], "little")
        out.append(d[base + s0:base + e0])
    return out


def heartgold(decomps):
    """HeartGold / SoulSilver (one build of the decomp each, the same tables): items, their pockets, map places and icon
    palettes. Species and moves are Platinum's - checked here name for name, type for type, power for power - so the
    companion shares those tables."""
    dec = os.path.join(decomps, "pokeheartgold")
    msg = lambda n: [m["en_US"] for m in json.load(open(os.path.join(dec, f"files/msgdata/msg/msg_{n:04d}.json")))["messages"]]
    def defines(path, prefix):
        return {k: int(v, 0) for k, v in re.findall(r"#define (" + prefix + r"\w+)\s+(\d+|0x[0-9A-Fa-f]+)\b",
                                                   open(os.path.join(dec, path)).read())}

    # Species / moves against Platinum's.
    pt_species, pt_moves = platinum_species_moves(os.path.join(decomps, "pokeplatinum"))
    types = {k: v for k, v in defines("include/constants/pokemon.h", "TYPE_").items() if k in GEN3_TYPES}
    assert [k for k, _ in sorted(types.items(), key=lambda kv: kv[1])] == GEN3_TYPES
    species_names = msg(237)
    base = json.load(open(os.path.join(dec, "files/poketool/personal/personal.json")))["baseStats"]
    for i in range(1, 494):
        mine = (species_names[i], types[base[i]["types"][0]], types[base[i]["types"][1]])
        assert mine == pt_species[i], (i, mine, pt_species[i])
    move_names = msg(750)
    waza = narc_members(os.path.join(dec, "files/poketool/waza/waza_tbl.narc"))
    for i in range(1, 468):
        m = waza[i]
        mine = (move_names[i], m[4], m[3])
        assert mine == pt_moves[i], (i, mine, pt_moves[i])

    # Items: msg 222 by id; the pocket from item_data.csv's row for the id's ITEM_ symbol.
    item_names = msg(222)
    item_ids = defines("include/constants/items.h", "ITEM_")
    pocket_of = {}
    with open(os.path.join(dec, "files/itemtool/itemdata/item_data.csv")) as f:
        head = f.readline().strip().split(",")
        col = head.index("fieldPocket")
        for row in f:
            cells = row.strip().split(",")
            if cells[0] in item_ids:
                pocket_of[item_ids[cells[0]]] = cells[col]
    items = {}
    for i, name in enumerate(item_names):
        if i == 0 or name in ("", "????"):
            continue
        items[i] = (name, POCKETS.get(pocket_of.get(i), "POCKET_ITEMS"))
    assert items[17][0] == "Potion" and items[item_ids["ITEM_APRICORN_BOX"]][1] == "POCKET_KEY_ITEMS", items.get(17)
    # Item icons: item.c's sItemNarcIds - NARC_item_icon_item_icon_NNN_NCGR / _NCLR are the members' indices.
    item_src = open(os.path.join(dec, "src/item.c")).read()
    item_icons = {}
    for sym, g, c in re.findall(r"\[(ITEM_\w+)\]\s*=\s*\{[^,]+,\s*NARC_item_icon_item_icon_(\d+)_NCGR,\s*NARC_item_icon_item_icon_(\d+)_NCLR", item_src):
        if item_ids.get(sym) in items:
            item_icons[item_ids[sym]] = (int(g), int(c))
    assert item_icons[1] == (2, 3), item_icons.get(1)
    # The machines' moves: item.c's sTMHMMoves, TM01..TM92 then HM01..HM08 (the item ids run on from ITEM_TM01).
    move_ids = defines("include/constants/moves.h", "MOVE_")
    tm_src = item_src[item_src.index("sTMHMMoves[] = {"):]
    tm_moves = [move_ids[m] for m in re.findall(r"(MOVE_\w+)", tm_src[:tm_src.index("};")])]
    tm01 = item_ids["ITEM_TM01"]
    assert len(tm_moves) == 100 and item_ids["ITEM_HM08"] == tm01 + 99, len(tm_moves)
    assert move_names[tm_moves[2]] == "Water Pulse" and move_names[tm_moves[92]] == "Cut", (move_names[tm_moves[2]], move_names[tm_moves[92]])

    # Map places: each map header's mapsec, a line of the location names (msg 279).
    place_names = msg(279)
    map_ids = defines("include/constants/maps.h", "MAP_")
    mapsecs = defines("include/constants/map_sections.h", "MAPSEC_")
    src = open(os.path.join(dec, "src/data/map_headers.h")).read()
    map_places, place_ids = {}, {}
    for sym, sec in re.findall(r"\[(MAP_\w+)\]\s*=\s*\{[^}]*?\.mapsec\s*=\s*(\w+)", src, re.S):
        name = place_names[mapsecs[sec]]
        if name:
            map_places[map_ids[sym]] = name
            place_ids[map_ids[sym]] = mapsecs[sec]
    assert map_places[map_ids["MAP_NEW_BARK"]] == "New Bark Town", map_places.get(map_ids["MAP_NEW_BARK"])
    assert map_places[map_ids["MAP_MOUNT_SILVER_CAVE_SUMMIT"]] == "Mt. Silver Cave"
    assert map_places[map_ids["MAP_FUCHSIA_POKECENTER_1F"]] == "Fuchsia City"

    # Icon palettes: pokemon_icon_idx.c's sPokemonPalNoBySpeciesAndForm, 0..493 then the egg (494).
    icon_src = open(os.path.join(dec, "src/pokemon_icon_idx.c")).read()
    i0 = icon_src.index("sPokemonPalNoBySpeciesAndForm[] = {")
    pals = [int(v) for v in re.findall(r"\d+", icon_src[i0 + 40:icon_src.index("};", i0)])]
    icon_pals = pals[:495]
    assert len(icon_pals) == 495 and icon_pals[1] == 1, icon_pals[:3]

    # Each map header's matrix and Pokégear coordinates (map_headers.h): the main matrix's maps put the player by their own
    # block; the rest (houses, caves) by their worldMapX / Y, as unk_02092BE8.c does for the Pokégear.
    header_fields = {}
    for sym, body in re.findall(r"\[(MAP_\w+)\]\s*=\s*\{(.*?)\n\s*\}", src, re.S):
        get = lambda k: (re.search(r"\." + k + r"\s*=\s*([^,\n]+)", body) or [None, None])[1]
        header_fields[map_ids[sym]] = (get("matrixId"), int(get("worldMapX") or 0), int(get("worldMapY") or 0))
    main_headers = sorted(h for h, (mx, _, _) in header_fields.items() if mx and mx.startswith("NARC_map_matrix_map_matrix_0000_"))
    world_map = {h: (x, y) for h, (mx, x, y) in header_fields.items() if h not in main_headers and (x or y)}
    assert map_ids["MAP_GOLDENROD"] in main_headers and len(main_headers) == 97, len(main_headers)

    # FLY: gMapFlypointParams (overlay_101_021F79B4.c) - name map, warp map, flypoint flag, -, the box on the Pokégear map
    # in main-matrix blocks (x, y, width, height). The game flies to the picked block's middle tile (x * 32 + 16).
    flags_src = defines("include/constants/flags.h", "FLAG_SYS_FLYPOINT_")
    flypoints = defines("include/constants/flypoints.h", "FLYPOINT_")
    fp_src = open(os.path.join(dec, "src/application/pokegear/map/overlay_101_021F79B4.c")).read()
    fp_table = fp_src[fp_src.index("gMapFlypointParams[] = {"):]
    fp_table = fp_table[:fp_table.index("};")]
    def region(x, y):   # Pokegear_RegionFromCoords: 0 Johto, 1 Kanto, 2 Indigo (any region flies there)
        if x > 21:
            if (x, y) == (25, 8):
                return 0
            if (x, y) == (28, 6) or (x == 28 and 8 < y < 13):
                return 2
            return 1
        return 0
    fly_spots = []
    for row in re.findall(r"\{([^{}]*)\}", fp_table):
        f = [c.strip() for c in row.split(",")]
        name_map, warp_map, fp = map_ids[f[0]], map_ids[f[1]], flypoints[f[2]]
        x, y, w, h = int(f[4], 0), int(f[5], 0), int(f[8], 0), int(f[9], 0)
        # ov101_021EA804: Indigo Plateau and Route 26 fly from either region
        reg = 2 if f[1] in ("MAP_INDIGO_PLATEAU", "MAP_ROUTE_26") else region(x, y)
        fly_spots.append((warp_map, x * 32 + 16, y * 32 + 16, flags_src["FLAG_SYS_FLYPOINT_PALLET"] + fp, name_map, reg))
    assert len(fly_spots) == 27 and map_places[fly_spots[0][4]] == "Pallet Town", fly_spots[0]

    # The Pokégear map (overlay_101_021E9270.c): 47 x 20 cells of 8 x 8 pixels, a cell per main-matrix block two rows
    # down (block (x, y) is cell (x, y + 2)). Each cell's place (a map section: a line of the location names) through the
    # main matrix (map_matrix_0000: width, height, header flag, altitude flag, name length, name, then u16 headers).
    mapsec_of = {map_ids[sym]: mapsecs[sec] for sym, sec in re.findall(r"\[(MAP_\w+)\]\s*=\s*\{[^}]*?\.mapsec\s*=\s*(\w+)", src, re.S)}
    mtx = open(os.path.join(dec, "files/fielddata/mapmatrix/map_matrix/map_matrix_0000_EVERYWHERE.bin"), "rb").read()
    mw, mh, has_headers = mtx[0], mtx[1], mtx[2]
    assert (mw, mh, has_headers) == (47, 17, 1), (mw, mh)
    hdr0 = 5 + mtx[4]
    def block_header(bx, by):
        return int.from_bytes(mtx[hdr0 + (by * mw + bx) * 2:hdr0 + (by * mw + bx) * 2 + 2], "little")
    pg_grid = []
    for cy in range(20):
        for cx in range(47):
            by = cy - 2
            h = block_header(cx, by) if 0 <= by < mh else 0
            sec = mapsec_of.get(h, 0) if h else 0
            pg_grid.append(sec if sec and place_names[sec] else 0xFFFF)
    assert place_names[pg_grid[(11 + 2) * 47 + 11]] == "Goldenrod City", pg_grid[(11 + 2) * 47 + 11]
    # The covers over towns not visited yet (ov101_021EAF40): each flypoint's piece of the map's tilemap, cut from
    # (srcX, srcY) and put on cell (x - destX, y - destY + 2), unless its flypoint flag is set. Cianwood never has one;
    # unk_05 0xFF none; Johto's (region 0) only once Kanto is unlocked (mapUnlockLevel 2).
    pg_covers = []
    for row in re.findall(r"\{([^{}]*)\}", fp_table):
        f = [c.strip() for c in row.split(",")]
        if f[0] == "MAP_CIANWOOD":
            continue
        unk5, x, y, sx, sy, w, h, dw, dh, dx, dy = (int(v, 0) for v in f[3:])
        if unk5 == 0xFF:
            continue
        pg_covers.append((flags_src["FLAG_SYS_FLYPOINT_PALLET"] + flypoints[f[2]], x - dx, y - dy + 2, dw, dh, sx, sy, region(x, y)))

    out_map = os.path.join(OUT_DIR, "Gen4TownMapHeartGold.kt")
    with open(out_map, "w") as f:
        f.write("// Generated by scripts/gen_gen4_tables.py heartgold from pret/pokeheartgold - do not edit.\n")
        f.write("package com.pokedaisy.app.companion.data\n\n")
        f.write("/** FLY's spots (gMapFlypointParams' order): the warp map, the tile flown to (the box's first block's middle), its\n")
        f.write(" * flypoint flag, the map it's named by and its region (0 Johto, 1 Kanto, 2 either). */\n")
        f.write("val flySpotsHeartGold: List<FlySpot> = listOf(\n")
        f.writelines(f"    FlySpot({m}, {x}, {z}, flag = {fl}, nameMap = {nm}, region = {rg}),\n" for m, x, z, fl, nm, rg in fly_spots)
        f.write(")\n\n/** The map headers on the main map matrix (Johto and Kanto outdoors: the player's own block is on the Pokégear map). */\n")
        f.write("val mainMatrixHeadersHeartGold: Set<Int> = setOf(\n")
        for i in range(0, len(main_headers), 16):
            f.write("    " + ", ".join(str(h) for h in main_headers[i:i + 16]) + ",\n")
        f.write(")\n\n/** Each place's name (the location names' lines, msg 279), by place id: the Pokégear map's sections. */\n")
        f.write("val placeNamesHeartGold: Map<Int, String> = mapOf(\n")
        f.writelines(f"    {i} to {kstr(nm)},\n" for i, nm in enumerate(place_names) if nm and i in set(pg_grid))
        f.write(")\n\n/** The Pokégear map's cells: 47 x 20, each one's place id as a u16 (FFFF = none), little-endian hex. */\n")
        f.write("const val POKEGEAR_GRID_HEARTGOLD =\n")
        hexs = "".join(f"{v & 0xFF:02X}{v >> 8:02X}" for v in pg_grid)
        for i in range(0, len(hexs), 120):
            f.write(f'    "{hexs[i:i + 120]}"' + (" +\n" if i + 120 < len(hexs) else "\n"))
        f.write("\n/** The covers over towns not visited yet: flypoint flag, destination cell x / y, width, height (cells), source\n")
        f.write(" * cell x / y in the map's tilemap, region (0 Johto: covered only once Kanto is unlocked). */\n")
        f.write("val pokegearCoversHeartGold: List<IntArray> = listOf(\n")
        f.writelines("    intArrayOf(" + ", ".join(str(v) for v in c) + "),\n" for c in pg_covers)
        f.write(")\n\n/** The other maps' Pokégear block (MapHeader_GetWorldMapCoords), where they have one. */\n")
        f.write("val worldMapHeartGold: Map<Int, Pair<Int, Int>> = mapOf(\n")
        f.writelines(f"    {h} to ({x} to {y}),\n" for h, (x, y) in sorted(world_map.items()))
        f.write(")\n")

    out = os.path.join(OUT_DIR, "Gen4TablesHeartGold.kt")
    with open(out, "w") as f:
        f.write("// Generated by scripts/gen_gen4_tables.py heartgold from pret/pokeheartgold - do not edit.\n")
        f.write("package com.pokedaisy.app.companion.data\n\n")
        f.write("// HeartGold / SoulSilver: their species and moves are Platinum's (checked by the generator), so those tables are\n")
        f.write("// shared; these are what differs.\n\n")
        f.write("val itemNamesHeartGold: Map<Int, String> = mapOf(\n")
        f.writelines(f"    {i} to {kstr(n)},\n" for i, (n, _) in sorted(items.items()))
        f.write(")\n\n/** The companion's pocket for each item: the game's own, MEDICINE / MAIL / BATTLE ITEMS under ITEMS. */\n")
        f.write("val itemPocketsHeartGold: Map<Int, Int> = mapOf(\n")
        f.writelines(f"    {i} to {p},\n" for i, (_, p) in sorted(items.items()) if p != "POCKET_ITEMS")
        f.write(")\n\n/** Each item's icon in item_icon.narc (a/0/1/8): its NCGR and NCLR members, packed (ncgr shl 16 or nclr). */\n")
        f.write("val itemIconsHeartGold: Map<Int, Int> = mapOf(\n")
        f.writelines(f"    {i} to 0x{g:04X}{c:04X},\n" for i, (g, c) in sorted(item_icons.items()))
        f.write(")\n\n/** Each species' party-icon palette (0-2) in the icon archive (a/0/2/0), 0..493 then the egg (494). */\n")
        f.write("val iconPalettesHeartGold: ByteArray = byteArrayOf(\n")
        for i in range(0, len(icon_pals), 32):
            f.write("    " + ", ".join(str(v) for v in icon_pals[i:i + 32]) + ",\n")
        f.write(")\n\n/** The machines' item ids: TM01 here, TM92 + 1 = HM01 .. HM08. */\n")
        f.write(f"const val HEARTGOLD_TM01 = {tm01}\n")
        f.write("\n/** Each machine's move (sTMHMMoves): TM01..TM92, then HM01..HM08. */\n")
        f.write("val tmMovesHeartGold: IntArray = intArrayOf(\n")
        for i in range(0, len(tm_moves), 16):
            f.write("    " + ", ".join(str(v) for v in tm_moves[i:i + 16]) + ",\n")
        f.write(")\n\n/** Each map header's place name (its map section), by map header id. */\n")
        f.write("val mapPlacesHeartGold: Map<Int, String> = mapOf(\n")
        f.writelines(f"    {i} to {kstr(n)},\n" for i, n in sorted(map_places.items()))
        f.write(")\n")
        # The line of the location names each header's place is: a cart's own text names it (NdsArt.names).
        f.write("\n/** Each map header's place as a line of the location names (its own text: NdsArt.names), by map header id. */\n")
        f.write("val mapPlaceIdsHeartGold: Map<Int, Int> = mapOf(\n")
        f.writelines(f"    {i} to {n},\n" for i, n in sorted(place_ids.items()))
        f.write(")\n")
    print(f"wrote {out}: {len(items)} items, {len(map_places)} map headers; species / moves = Platinum's")

    # The game's text characters (tools/msgenc/charmap.txt): for reading the cart's own text (the DEX's pages) at run
    # time - the text itself is never bundled. Control codes ({...}) left out; line and page breaks are "\n".
    chars = {}
    for line in open(os.path.join(dec, "tools/msgenc/charmap.txt"), encoding="utf-8"):
        line = line.rstrip("\n")
        m = re.match(r"\s*([0-9A-Fa-f]{4})=(.*)$", line)
        if not m or m.group(2).startswith("{"):
            continue
        v = m.group(2)
        v = {"\\n": "\n", "\\r": "\n", "\\f": "\n"}.get(v, v)
        chars.setdefault(int(m.group(1), 16), v)
    assert chars[0x0188] == "é" and chars[0x01DE] == " " and chars[0xE000] == "\n", (chars.get(0x0188), chars.get(0x01DE))
    codes = sorted(chars)
    out = os.path.join(OUT_DIR, "Gen4Charmap.kt")
    with open(out, "w", encoding="utf-8") as f:
        f.write("// Generated by scripts/gen_gen4_tables.py heartgold from pret/pokeheartgold's tools/msgenc/charmap.txt - do not edit.\n")
        f.write("package com.pokedaisy.app.companion.data\n\n")
        f.write("/** The DS games' text characters: [GEN4_CHAR_CODES]' n-th code is [GEN4_CHAR_VALUES]' n-th entry (split on U+0000). */\n")
        def esc(t):
            return "".join("\\u%04X" % ord(c) if ord(c) < 0x20 or ord(c) > 0x7E or c in '"\\$' else c for c in t)
        f.write("internal const val GEN4_CHAR_CODES = \"" + "".join("\\u%04X" % c for c in codes) + "\"\n")
        f.write("internal const val GEN4_CHAR_VALUES = \"" + "\\u0000".join(esc(chars[c]) for c in codes) + "\"\n")
    print(f"wrote {out}: {len(codes)} characters")


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ("platinum", "heartgold"):
        sys.exit(__doc__)
    decomps = os.environ.get("DECOMPS") or sys.exit("set DECOMPS (local.mk)")
    if sys.argv[1] == "heartgold":
        return heartgold(decomps)
    dec = os.path.join(decomps, "pokeplatinum")

    types = lines(os.path.join(dec, "generated/pokemon_types.txt"))
    assert types[:len(GEN3_TYPES)] == GEN3_TYPES, "pokeplatinum's type order isn't Gen 3's"
    type_id = {t: i for i, t in enumerate(types)}

    species = {}
    species_types = {}
    for i, sym in enumerate(lines(os.path.join(dec, "generated/species.txt"))):
        if i == 0 or i > 493:   # SPECIES_NONE; past ARCEUS: the egg and forms, read as their base species
            continue
        path = os.path.join(dec, "res/pokemon", sym[len("SPECIES_"):].lower(), "data.json")
        d = json.load(open(path))
        species[i] = d["pokedex_data"]["en"]["name"]
        t1, t2 = d["types"]
        species_types[i] = (type_id[t1], type_id[t2])
    assert species[387] == "TURTWIG" and species[493] == "ARCEUS", species.get(387)

    moves = {}
    for i, sym in enumerate(lines(os.path.join(dec, "generated/moves.txt"))):
        if i == 0:
            continue
        path = os.path.join(dec, "res/moves", sym[len("MOVE_"):].lower(), "data.json")
        if not os.path.exists(path):
            continue
        d = json.load(open(path))
        moves[i] = (d["name"], type_id[d["type"]], d["power"])
    assert moves[33][0] == "Tackle" and moves[467][0] == "Shadow Force", moves.get(33)

    items = {}
    for i, sym in enumerate(lines(os.path.join(dec, "generated/items.txt"))):
        if i == 0:
            continue
        path = os.path.join(dec, "res/items/data", sym[len("ITEM_"):].lower() + ".json")
        if not os.path.exists(path):
            continue
        d = json.load(open(path))
        if d["name"] in ("", "????"):
            continue
        items[i] = (d["name"], POCKETS.get(d["fieldPocket"], "POCKET_ITEMS"))
    assert items[17][0] == "Potion", items.get(17)

    # Item icons: each item's graphics and palette members in item_icon.narc (res/items/item_icon.order: member order).
    icon_order = lines(os.path.join(dec, "res/items/item_icon.order"))
    item_icons = {}
    for i, sym in enumerate(lines(os.path.join(dec, "generated/items.txt"))):
        path = os.path.join(dec, "res/items/data", sym[len("ITEM_"):].lower() + ".json")
        if i == 0 or i not in items or not os.path.exists(path):
            continue
        icon = json.load(open(path)).get("icon") or {}
        if icon.get("sprite") and icon.get("palette"):
            item_icons[i] = (icon_order.index(icon["sprite"].replace("_NCGR", ".NCGR")),
                             icon_order.index(icon["palette"].replace("_NCLR", ".NCLR")))
    assert item_icons[1] == (2, 3), item_icons.get(1)   # MASTER BALL: the archive's first icon

    # The machines' moves: itemproc's item_tm_move_map.h in the decomp's build, TM01..TM92 then HM01..HM08.
    move_syms = lines(os.path.join(dec, "generated/moves.txt"))
    tm_src = open(os.path.join(dec, "build/res/items/item_tm_move_map.h")).read()
    tm_moves = [move_syms.index(m) for m in re.findall(r"\[\d+\]\s*=\s*(MOVE_\w+)", tm_src)]
    assert len(tm_moves) == 100 and moves[tm_moves[2]][0] == "Water Pulse" and moves[tm_moves[96]][0] == "Defog", len(tm_moves)

    place_msgs = json.load(open(os.path.join(dec, "res/text/location_names.json")))["messages"]
    places = {m["id"]: m["en_US"] for m in place_msgs}
    place_index = {m["id"]: i for i, m in enumerate(place_msgs)}   # a place's id: its line in the location names
    header_syms = lines(os.path.join(dec, "generated/map_headers.txt"))
    src = open(os.path.join(dec, "include/data/map_headers.h")).read()
    label_of = dict(re.findall(r"\[(MAP_HEADER_\w+)\]\s*=\s*\{[^}]*?\.mapLabelTextID\s*=\s*(\w+)", src, re.S))
    map_places = {}
    for i, sym in enumerate(header_syms):
        label = label_of.get(sym)
        if label and places.get(label):
            map_places[i] = places[label]
    assert map_places[header_syms.index("MAP_HEADER_TWINLEAF_TOWN")] == "Twinleaf Town"
    assert map_places[header_syms.index("MAP_HEADER_TWINLEAF_TOWN_PLAYER_HOUSE_1F")] == "Twinleaf Town"

    # The icon palettes: speciesproc's species_icon_palettes.h in the decomp's build ([SPECIES_X] = n, [ICON_EGG] = n).
    syms = lines(os.path.join(dec, "generated/species.txt"))
    pal_src = open(os.path.join(dec, "build/res/pokemon/species_icon_palettes.h")).read()
    pal_of = {k: int(v) for k, v in re.findall(r"\[(SPECIES_\w+|ICON_EGG)\]\s*=\s*(\d+)", pal_src)}
    icon_pals = [pal_of.get(syms[i], 0) for i in range(494)] + [pal_of["ICON_EGG"]]
    assert icon_pals[387] == 1, icon_pals[387]   # TURTWIG

    # FLY's spots: spawn_locations.c's sSpawnLocations in order (spawn id = index + 1): the fly map header and tile.
    spawn_src = open(os.path.join(dec, "src/spawn_locations.c")).read()
    table = spawn_src[spawn_src.index("sSpawnLocations[] = {"):]
    table = table[:table.index("};")]
    fly_spots = []
    for row in re.findall(r"\{([^{}]*)\}", table):
        f = [x.strip() for x in row.split(",")]
        fly_spots.append((header_syms.index(f[3]), int(f[4], 0), int(f[5], 0)))
    assert len(fly_spots) == 20 and map_places[fly_spots[0][0]] == "Twinleaf Town" and fly_spots[0][1:] == (0x74, 0x376)

    # The Town Map: the main map matrix (matrix 0, 30 x 30 blocks of 32 x 32 tiles) as each block's place (the map
    # header's label), what the town map's cursor names; and the fly locations' block sprites (fly_locations.c), each
    # with the first-arrival flag that colours it (context.c's sTownMapFlyLocationUnlockFlags).
    matrix = json.load(open(os.path.join(dec, "res/field/matrices/map_matrix_000.json")))
    grid_headers = matrix["headers"]
    assert len(grid_headers) == 30 and all(len(r) == 30 for r in grid_headers)
    main_headers = sorted({header_syms.index(h) for r in grid_headers for h in r if h != "MAP_HEADER_EVERYWHERE"})
    def place_of(sym):
        label = label_of.get(sym)
        return place_index[label] if label and places.get(label) else 0xFFFF
    grid = [place_of(h) if h != "MAP_HEADER_EVERYWHERE" else 0xFFFF for r in grid_headers for h in r]
    header_places = {i: place_of(sym) for i, sym in enumerate(header_syms) if place_of(sym) != 0xFFFF}
    assert places[place_msgs[grid[26 * 30 + 3]]["id"]] == "Route 201", place_msgs[grid[26 * 30 + 3]]
    fl_src = open(os.path.join(dec, "src/applications/town_map/fly_locations.c")).read()
    shapes = ["FLY_LOCATION_SHAPE_1x1_SQUARE", "FLY_LOCATION_SHAPE_VERTICAL", "FLY_LOCATION_SHAPE_2x2_SQUARE",
              "FLY_LOCATION_SHAPE_TOP_LEFT_ANGLE", "FLY_LOCATION_SHAPE_TOP_RIGHT_ANGLE", "FLY_LOCATION_SHAPE_HORIZONTAL",
              "FLY_LOCATION_SHAPE_SMALL_RECTANGLE"]
    ctx_src = open(os.path.join(dec, "src/applications/town_map/context.c")).read()
    i0 = ctx_src.index("sTownMapFlyLocationUnlockFlags")
    unlock = re.findall(r"FIRST_ARRIVAL_\w+", ctx_src[i0:ctx_src.index("};", i0)])
    arrivals = lines(os.path.join(dec, "generated/first_arrival_to_zones.txt"))
    marks = []
    for header, body in re.findall(r"\{\s*\.mapHeader = (\w+),(.*?)\n    \}", fl_src, re.S):
        get = lambda k: re.search(r"\." + k + r" = ([^,]+),", body).group(1).strip()
        pos = lambda e: eval(e.replace("TOWN_MAP_GRID_SPACING", "7"), {})
        special = get("specialFlyLocationID")
        marks.append((pos(get("spriteX")) + 25, pos(get("spriteY")) - 34, shapes.index(get("blockShape")),
                      0 if get("palette").endswith("BLUE") else 1, arrivals.index(unlock[len(marks)]),
                      special in ("FLY_LOCATION_PAL_PARK", "FLY_LOCATION_VICTORY_ROAD")))
    assert len(marks) == len(unlock) == 20 and marks[0][:2] == (3 * 7 + 25, 27 * 7 - 34), marks[0]

    out = os.path.join(OUT_DIR, "Gen4TablesPlatinum.kt")
    with open(out, "w") as f:
        f.write("// Generated by scripts/gen_gen4_tables.py platinum from pret/pokeplatinum - do not edit.\n")
        f.write("package com.pokedaisy.app.companion.data\n\n")
        f.write("val speciesNamesPlatinum: Map<Int, String> = mapOf(\n")
        f.writelines(f"    {i} to {kstr(n)},\n" for i, n in sorted(species.items()))
        f.write(")\n\nval speciesTypesPlatinum: Map<Int, SpeciesTypes> = mapOf(\n")
        f.writelines(f"    {i} to SpeciesTypes({a}, {b}),\n" for i, (a, b) in sorted(species_types.items()))
        f.write(")\n\nval moveDataPlatinum: Map<Int, MoveInfo> = mapOf(\n")
        f.writelines(f"    {i} to MoveInfo({kstr(n)}, {t}, {p}),\n" for i, (n, t, p) in sorted(moves.items()))
        f.write(")\n\nval itemNamesPlatinum: Map<Int, String> = mapOf(\n")
        f.writelines(f"    {i} to {kstr(n)},\n" for i, (n, _) in sorted(items.items()))
        f.write(")\n\n/** The companion's pocket for each item: the game's own, MEDICINE / MAIL / BATTLE ITEMS under ITEMS. */\n")
        f.write("val itemPocketsPlatinum: Map<Int, Int> = mapOf(\n")
        f.writelines(f"    {i} to {p},\n" for i, (_, p) in sorted(items.items()) if p != "POCKET_ITEMS")
        f.write(")\n\n/** Each item's icon in item_icon.narc: its NCGR and NCLR members, packed (ncgr shl 16 or nclr). */\n")
        f.write("val itemIconsPlatinum: Map<Int, Int> = mapOf(\n")
        f.writelines(f"    {i} to 0x{g:04X}{c:04X},\n" for i, (g, c) in sorted(item_icons.items()))
        f.write(")\n\n/** Each species' party-icon palette (0-2) in pl_poke_icon.narc, 0..493 then the egg (494). */\n")
        f.write("val iconPalettesPlatinum: ByteArray = byteArrayOf(\n")
        for i in range(0, len(icon_pals), 32):
            f.write("    " + ", ".join(str(v) for v in icon_pals[i:i + 32]) + ",\n")
        f.write(")\n\n/** FLY's spots by spawn id - 1 (sSpawnLocations): the fly map header and its tile. */\n")
        f.write("val flySpotsPlatinum: List<FlySpot> = listOf(\n")
        f.writelines(f"    FlySpot({m}, {x}, {z}),\n" for m, x, z in fly_spots)
        f.write(")\n\n/** Each map header's place name (its map label), by map header id. */\n")
        f.write("val mapPlacesPlatinum: Map<Int, String> = mapOf(\n")
        f.writelines(f"    {i} to {kstr(n)},\n" for i, n in sorted(map_places.items()))
        f.write(")\n")
    # The machines' moves in a file of their own too (the tables above fill their file's static initializer).
    out_tm = os.path.join(OUT_DIR, "Gen4MachinesPlatinum.kt")
    with open(out_tm, "w") as f:
        f.write("// Generated by scripts/gen_gen4_tables.py platinum from pret/pokeplatinum - do not edit.\n")
        f.write("package com.pokedaisy.app.companion.data\n\n")
        f.write("/** Each machine's move (sTMHMMoves): TM01..TM92, then HM01..HM08 (HM05 is Defog here, Whirlpool in HG/SS). */\n")
        f.write("val tmMovesPlatinum: IntArray = intArrayOf(\n")
        for i in range(0, len(tm_moves), 16):
            f.write("    " + ", ".join(str(v) for v in tm_moves[i:i + 16]) + ",\n")
        f.write(")\n")
    # The Town Map's tables in a file of their own: one file's tables share one static initializer, and the JVM caps
    # its size (the species / moves / items / places together went past it).
    out_map = os.path.join(OUT_DIR, "Gen4TownMapPlatinum.kt")
    with open(out_map, "w") as f:
        f.write("// Generated by scripts/gen_gen4_tables.py platinum from pret/pokeplatinum - do not edit.\n")
        f.write("package com.pokedaisy.app.companion.data\n\n")
        f.write("/** Each place's name (the location names' lines), by place id: the Town Map's sections. */\n")
        f.write("val placeNamesPlatinum: Map<Int, String> = mapOf(\n")
        f.writelines(f"    {i} to {kstr(m['en_US'])},\n" for i, m in enumerate(place_msgs) if m["en_US"])
        f.write(")\n\n/** Each map header's place (its map label), by map header id. */\n")
        f.write("val mapPlaceIdsPlatinum: Map<Int, Int> = mapOf(\n")
        f.writelines(f"    {i} to {p},\n" for i, p in sorted(header_places.items()))
        f.write(")\n\n/** The map headers on the main map matrix (the overworld: the player's own position is on the Town Map). */\n")
        f.write("val mainMatrixHeadersPlatinum: Set<Int> = setOf(\n")
        for i in range(0, len(main_headers), 16):
            f.write("    " + ", ".join(str(h) for h in main_headers[i:i + 16]) + ",\n")
        f.write(")\n\n/** The Town Map's cursor grid: 30 x 30 blocks, each one's place id as a u16 (FFFF = none), little-endian hex. */\n")
        f.write("const val TOWN_MAP_GRID_PLATINUM =\n")
        hexs = "".join(f"{v & 0xFF:02X}{v >> 8:02X}" for v in grid)
        for i in range(0, len(hexs), 120):
            f.write(f'    "{hexs[i:i + 120]}"' + (" +\n" if i + 120 < len(hexs) else "\n"))
        f.write("\n/** The Town Map's fly locations: the block sprite's centre on the top screen, its shape (cell), its colour once\n")
        f.write(" * visited (0 blue, 1 red), the first-arrival id that marks it visited, and whether it's hidden until then. */\n")
        f.write("val townMapMarksPlatinum: List<TownMapMark> = listOf(\n")
        f.writelines(f"    TownMapMark({x}, {y}, {sh}, {pl}, {fa}, {str(hid).lower()}),\n" for x, y, sh, pl, fa, hid in marks)
        f.write(")\n")
    print(f"wrote {out}: {len(species)} species, {len(moves)} moves, {len(items)} items, {len(map_places)} map headers")


if __name__ == "__main__":
    main()
