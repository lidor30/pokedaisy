#!/usr/bin/env python3
"""Gen 5 (DS) name / data tables for the companion, read from the player's own Pokémon Black cart (there is no pret
decomp for Gen 5, so everything comes from the cart's file system and its ARM9).

    python3 -I scripts/gen_gen5_tables.py <Black.nds> [<White.nds> [<Black 2.nds> <White 2.nds>]]   # (USA, Europe)

Writes app/src/main/kotlin/com/pokedaisy/app/companion/data/Gen5TablesBlack.kt (species, moves, places) and
Gen5ItemsBlack.kt (items, their pockets and icons, the party icons' palettes): two files, since one
class's static initializer can't hold them all. Numbers and the game's own names only - no art, no prose.
The carts are IRBO / IRAO / IREO / IRDO rev 0 (USA, Europe).

White's cart has Black's names, types, moves, items, icons and zones (checked here, table for table): only its Town Map
differs - White Forest (zone 424) where Black City (zone 0) is, the same place on the map - so with White's cart as well
it writes Gen5TownMapWhite.kt (its Town Map and FLY spots) and nothing else of White's.

Black 2 and White 2 are the same engine with the archives renumbered (CARTS: the text banks, 615 zones, the Town Map's
places in /a/0/8/5): their species and moves are Black's (checked), so with their carts it writes Gen5TablesBlack2.kt
(places, zones, the 12 key items they added, the item icons and party icon palettes, which moved, their Unova Dex) and
Gen5TownMapBlack2.kt / Gen5TownMapWhite2.kt. Both list Black City and White Forest at the same point; each version keeps
its own. White 2's other tables are Black 2's (checked).

Where each comes from (Gen 5 keeps its data in numbered NARCs, `/a/x/y/z`):
  - text: /a/0/0/2, Gen 5's encrypted UTF-16 message files (a line starting 0xF100 is 9-bit packed): species names are
    bank 70 (in the game's own mixed case, as its menus print them), moves 203, items 54, place names 89;
  - personal /a/0/1/6: 0x3C-byte records, types at +6 / +7;
  - moves /a/0/2/1: 0x24-byte records, type +0, power +3;
  - items /a/0/2/4: 0x24-byte records, the u16 at +8 holds the bag pocket in bits 7-10 (0 items - Poké Balls too in
    Gen 5 -, 1 medicine, 2 TMs / HMs, 3 berries, 4 key items);
  - zones /a/0/1/2 member 0: 427 x 48 bytes, the place name line at +0x1A, the wild set at +0x14 (0xFFFF none), the
    zone's own spawn x / y / z at +0x24 / +0x28 / +0x2C (u32);
  - the Town Map's places /a/0/8/6 member 0: 54-byte records (zone, ..., the arrival flag at +0x1E, 0xFFFF none):
    the towns FLY can reach are the ones with an arrival flag. The spawn x / z are tiles of the main matrix for the
    towns on it; Castelia City, the Pokémon League, Black City and Victory Road have matrices of their own, and their
    headers hold other numbers there (3 / 1, 19 / 49, 43 / 38, 0 / 0) - not checked in game yet;
  - ARM9 (BLZ-compressed): the item icon table (a (graphics, palette) u16 pair per item, members of the item icon
    NARC /a/0/2/5), the party icons' palette per entry of /a/0/0/7 (a byte per icon: low nibble the palette 0-2 of
    member 0's three, high nibble the female icon's; 650 = the egg, 651 the Manaphy egg, then forms). Each is found by
    its contents and checked (the TM / HM table too, though Gen5DexBlack.kt keeps its own copy of it).

Type ids: Gen 5's skip Gen 3's ??? (9 is FIRE), so 9 and up are moved up by one to the app's (Gen 3's) ids.
"""
import struct
import sys
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "app/src/main/kotlin/com/pokedaisy/app/companion/data")


# ---- the cart: file system, NARC, text, BLZ (self-contained: run with python3 -I) ----

def u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


class Rom:
    def __init__(self, path):
        self.d = open(path, "rb").read()
        h = self.d
        self.fnt = h[u32(h, 0x40):u32(h, 0x40) + u32(h, 0x44)]
        self.fat = u32(h, 0x48)
        self.paths = {}
        self._walk(0xF000, "")

    def _walk(self, did, prefix):
        e = (did & 0xFFF) * 8
        p = u32(self.fnt, e)
        fid = u16(self.fnt, e + 4)
        while True:
            ln = self.fnt[p]
            p += 1
            if ln == 0:
                break
            name = self.fnt[p:p + (ln & 0x7F)].decode("latin1")
            p += ln & 0x7F
            if ln & 0x80:
                self._walk(u16(self.fnt, p), prefix + "/" + name)
                p += 2
            else:
                self.paths[prefix + "/" + name] = fid
                fid += 1

    def fileid(self, i):
        return self.d[u32(self.d, self.fat + i * 8):u32(self.d, self.fat + i * 8 + 4)]

    def file(self, path):
        return self.fileid(self.paths[path])

    def narc(self, path):
        return Narc(self.file(path))

    def arm9(self):
        """The ARM9 binary, its BLZ-compressed part unpacked (the module params' compressed_static_end says where)."""
        h = self.d
        off, ram, size = u32(h, 0x20), u32(h, 0x28), u32(h, 0x2C)
        d = h[off:off + size]
        params = d.find(bytes.fromhex("dec006212106c0de")) - 0x1C
        end = u32(d, params + 0x14)
        return (blz(d[:end - ram]) + d[end - ram:]) if end else d


class Narc:
    def __init__(self, d):
        assert d[:4] == b"NARC"
        o = u16(d, 0xC)
        self.fat = o + 12
        self.count = u16(d, o + 8)
        o += u32(d, o + 4)
        o += u32(d, o + 4)
        self.base = o + 8
        self.d = d

    def __len__(self):
        return self.count

    def __getitem__(self, i):
        assert 0 <= i < self.count
        a, b = u32(self.d, self.fat + i * 8), u32(self.d, self.fat + i * 8 + 4)
        return self.d[self.base + a:self.base + b]


def blz(data):
    data = bytearray(data)
    n = len(data)
    a, inc = u32(data, n - 8), u32(data, n - 4)
    if inc == 0:
        return bytes(data)
    hdr, enc = a >> 24, a & 0xFFFFFF
    out = data + bytearray(inc)
    start, ip, op = n - enc, n - hdr, n + inc
    while ip > start:
        ip -= 1
        flags = out[ip]
        for _ in range(8):
            if ip <= start:
                break
            if flags & 0x80:
                ip -= 2
                b0, b1 = out[ip], out[ip + 1]
                disp = (((b1 & 0xF) << 8) | b0) + 3
                for _ in range((b1 >> 4) + 3):
                    op -= 1
                    out[op] = out[op + disp]
            else:
                ip -= 1
                op -= 1
                out[op] = out[ip]
            flags = (flags << 1) & 0xFF
    return bytes(out)


def text5(d):
    """A Gen 5 message file -> its lines (section 0): each line XOR-encrypted, a line starting 0xF100 9-bit packed."""
    secoff = u32(d, 0xC)
    out = []
    for i in range(u16(d, 2)):
        off, ln, _ = struct.unpack_from("<IHH", d, secoff + 4 + i * 8)
        enc = list(struct.unpack_from("<%dH" % ln, d, secoff + off))
        key = enc[-1] ^ 0xFFFF if ln else 0
        dec = [0] * ln
        for j in range(ln - 1, -1, -1):
            dec[j] = enc[j] ^ key
            key = ((key >> 3) | (key << 13)) & 0xFFFF
        if dec and dec[0] == 0xF100:
            bits, nb, chars = 0, 0, []
            for w in dec[1:]:
                bits |= w << nb
                nb += 16
                while nb >= 9:
                    c = bits & 0x1FF
                    bits >>= 9
                    nb -= 9
                    if c == 0x1FF:
                        break
                    chars.append(c)
                else:
                    continue
                break
            dec = chars + [0xFFFF]
        s, j = "", 0
        while j < len(dec):
            c = dec[j]
            if c == 0xFFFF:
                break
            if c == 0xFFFE:
                s += "\n"
            elif c == 0xF000:
                s += "{%x}" % dec[j + 1]
                j += 2 + dec[j + 2]
            elif c == 0x246D:
                s += "♂"
            elif c == 0x246E:
                s += "♀"
            else:
                s += chr(c)
            j += 1
        out.append(s)
    return out


# ---- the tables ----

def gen3_type(t):
    """Gen 5's type id -> the app's (Gen 3's: ??? is 9 there)."""
    assert 0 <= t <= 16, t
    return t + 1 if t >= 9 else t


POCKETS = {0: "POCKET_ITEMS", 1: "POCKET_MEDICINE", 2: "POCKET_TM_HM", 3: "POCKET_BERRIES", 4: "POCKET_KEY_ITEMS"}


def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def find_u16_table(arm9, values, what):
    pat = struct.pack("<%dH" % len(values), *values)
    i = arm9.find(pat)
    assert i >= 0 and arm9.find(pat, i + 1) < 0, what
    return i


class Cart:
    """Where one cart keeps what read() takes: Black / White and Black 2 / White 2 are one engine, numbered apart."""

    def __init__(self, code, name, banks, zones, town, item_icon0, icon_count, pal_count, other_zone=None):
        self.code, self.name = code, name
        self.species, self.moves, self.items, self.places = banks   # text banks in /a/0/0/2
        self.zones = zones                     # zone headers in /a/0/1/2 member 0 (48 bytes each)
        self.town = town                       # the Town Map's place list (54-byte records, member 0)
        self.item_icon0 = item_icon0           # item 0's (graphics, palette) pair, which the item icon table opens with
        self.icon_count = icon_count           # party icon entries in /a/0/0/7
        self.pal_count = pal_count             # the ARM9's icon palette table's bytes (Black 2's runs 5 past its icons)
        # Black 2 / White 2 list both Black City and White Forest at the same point; each version shows its own and
        # drops this zone (the other version's) from its Town Map and FLY.
        self.other_zone = other_zone


CARTS = {
    b"IRBO": Cart(b"IRBO", "Black", (70, 203, 54, 89), 427, "/a/0/8/6", (1001, 1002), 712, 712),
    b"IRAO": Cart(b"IRAO", "White", (70, 203, 54, 89), 427, "/a/0/8/6", (1001, 1002), 712, 712),
    b"IREO": Cart(b"IREO", "Black 2", (90, 403, 64, 109), 615, "/a/0/8/5", (1021, 1022), 751, 756, other_zone=424),
    b"IRDO": Cart(b"IRDO", "White 2", (90, 403, 64, 109), 615, "/a/0/8/5", (1021, 1022), 751, 756, other_zone=0),
}


def read(rom, code=b"IRBO"):
    assert rom.d[0xC:0x10] == code and rom.d[0x1E] == 0, f"not {code.decode()} rev 0"
    cart = CARTS[code]
    sequel = code in (b"IREO", b"IRDO")
    texts = rom.narc("/a/0/0/2")
    species_names = text5(texts[cart.species])
    move_names = text5(texts[cart.moves])
    item_names = text5(texts[cart.items])
    place_names = text5(texts[cart.places])
    assert species_names[495] == "Snivy" and move_names[1] == "Pound" and item_names[4] == "Poké Ball", "text banks"
    if sequel:
        assert place_names[117] == "Aspertia City", place_names[117]
    else:
        assert place_names[47] == "Gear Station", place_names[47]

    personal = rom.narc("/a/0/1/6")
    species = {}
    for i in range(1, 650):
        p = personal[i]
        species[i] = (species_names[i], gen3_type(p[6]), gen3_type(p[7]))
    assert species[495][1:] == (12, 12) and species[1][1:] == (12, 3), (species[495], species[1])   # GRASS; GRASS / POISON
    assert species[635][1:] == (17, 16), species[635]   # HYDREIGON: DARK / DRAGON

    move_data = rom.narc("/a/0/2/1")
    moves = {}
    for i in range(1, len(move_names)):
        m = move_data[i]
        moves[i] = (move_names[i], gen3_type(m[0]), m[3])
    assert moves[1] == ("Pound", 0, 40) and moves[559] == ("Fusion Bolt", 13, 100), (moves[1], moves[559])

    item_data = rom.narc("/a/0/2/4")
    items = {}
    for i in range(1, len(item_names)):
        name = item_names[i]
        if not name or name == "???":
            continue
        pocket = (u16(item_data[i], 8) >> 7) & 0xF
        items[i] = (name, POCKETS[pocket])
    assert items[4] == ("Poké Ball", "POCKET_ITEMS") and items[17] == ("Potion", "POCKET_MEDICINE"), (items[4], items[17])
    assert items[328][1] == "POCKET_TM_HM" and items[149][1] == "POCKET_BERRIES" and items[450][1] == "POCKET_KEY_ITEMS"

    arm9 = rom.arm9()
    # Item icons: (graphics, palette) per item from item 0 (the "?" icon, the archive's last pair) on.
    at = find_u16_table(arm9, list(cart.item_icon0) + [2, 3, 4, 5, 6, 7], "item icon table")
    icon_narc = rom.narc("/a/0/2/5")
    item_icons = {}
    for i in items:
        g, c = struct.unpack_from("<2H", arm9, at + 4 * i)
        assert icon_narc[g][:4] == b"RGCN" and icon_narc[c][:4] == b"RLCN", (i, g, c)
        item_icons[i] = (g, c)
    # Party icon palettes: a byte per icon entry; species 1-3 BULBASAUR's line (1), 4-6 CHARMANDER's (0), ...
    pal_at = arm9.find(bytes([0x00, 0x11, 0x11, 0x11, 0x00, 0x00, 0x00, 0x00, 0x22, 0x22, 0x11, 0x11]))
    assert pal_at >= 0
    icon_count = (len(rom.narc("/a/0/0/7")) - 7) // 2
    icon_pals = [arm9[pal_at + k] & 0xF for k in range(icon_count)]
    assert icon_count == cart.icon_count and all(v <= 2 for v in icon_pals) \
        and (arm9[pal_at + cart.pal_count] > 0x22 or arm9[pal_at + cart.pal_count] & 0xF > 2), icon_count
    # TM / HM moves: TM01 HONE CLAWS, TM02 DRAGON CLAW, TM03 PSYSHOCK, TM04 CALM MIND ...
    tm_at = find_u16_table(arm9, [468, 337, 473, 347], "TM table")
    tm_moves = list(struct.unpack_from("<101H", arm9, tm_at))
    assert tm_moves[92:98] == [15, 19, 57, 70, 127, 291] and tm_moves[98:] == [528, 249, 555], tm_moves[92:]   # HM01-06, TM93-95

    zones = rom.narc("/a/0/1/2")[0]
    nz = cart.zones
    assert len(zones) == nz * 48
    map_place_ids, spawn = {}, {}
    for z in range(nz):
        r = zones[z * 48:z * 48 + 48]
        map_place_ids[z] = r[0x1A]
        spawn[z] = (u32(r, 0x24), u32(r, 0x2C))
    if sequel:
        assert place_names[map_place_ids[427]] == "Aspertia City" and place_names[map_place_ids[465]] == "Humilau City"
    else:
        assert place_names[map_place_ids[66]] == "Gear Station"
    assert place_names[map_place_ids[317]] == "Route 1"

    town = rom.narc(cart.town)[0]
    records = [town[k * 54:k * 54 + 54] for k in range(len(town) // 54)]
    records = [r for r in records if u16(r, 0) != cart.other_zone]
    fly = []
    for r in records:
        zone, flag = u16(r, 0), u16(r, 0x1E)
        if flag != 0xFFFF:
            fly.append((zone, spawn[zone][0], spawn[zone][1], flag))
    assert fly[0][0] == 389 and len(fly) == (24 if sequel else 16), (len(fly), fly)   # NUVEMA TOWN first

    # The Town Map's places themselves (town_map below): zone, place, the point on the map, kind, arrival flag.
    town_map = []
    for r in records:
        zone = u16(r, 0)
        town_map.append((zone, map_place_ids[zone], u16(r, 4), u16(r, 6), u16(r, 0x16), u16(r, 0x1E)))
    route1 = next(k for k, t in enumerate(town_map) if t[0] == 317)
    assert len(town_map) == (84 if sequel else 61) and town_map[route1][:4] == (317, map_place_ids[317], 232, 148), town_map[route1]
    assert len({t[1] for t in town_map}) == len(town_map)   # a place each
    # Each zone's place on the Town Map: its own record, else its parent zone's (+0x18: a house, a cave's floors), else
    # one of the same place name (Black's zones 289-316 are the towns' and routes' other versions, under lines of their own).
    by_zone = {t[0]: k for k, t in enumerate(town_map)}
    by_place = {place_names[t[1]]: k for k, t in enumerate(town_map)}
    town_zones = []
    for z in range(nz):
        parent = u16(zones, z * 48 + 0x18)
        town_zones.append(by_zone.get(z, by_zone.get(parent, by_place.get(place_names[map_place_ids[z]], -1))))
    if sequel:
        assert town_zones[465] == by_zone[465] and town_zones[427] == by_zone[427], (town_zones[465], town_zones[427])
    else:
        assert town_zones[68] == by_zone[62] and town_zones[317] == 38, (town_zones[68], town_zones[317])   # Gear Station: Nimbasa

    shown = [u16(r, 0x20) for r in records]
    return species, moves, items, item_icons, icon_pals, place_names, map_place_ids, fly, (town_map, town_zones, shown)


def write_fly(f, name, fly):
    f.write("\n/**\n * The towns FLY can reach (the Town Map's places with an arrival flag, in its order): the zone, the zone's own\n"
            " * spawn x / z (its header's +0x24 / +0x2C - where a warp into the zone puts the player; not checked as FLY's\n"
            " * landing tile yet) and the arrival flag that unlocks it.\n */\n")
    f.write(f"val flySpots{name}: List<FlySpot> = listOf(\n")
    f.writelines(f"    FlySpot({z}, {x}, {y}, flag = {fl}),\n" for z, x, y, fl in fly)
    f.write(")\n")


def write_town_map(town_map, town_zones, name="Black", code="IRBO", fly=None, shown=None):
    """The Town Map's file; White's (name "White") and the sequels' also hold their FLY spots ([fly]), Black's are in
    Gen5TablesBlack.kt. The sequels' places carry what shows them ([shown], their +0x20 condition)."""
    sequel = code in ("IREO", "IRDO")
    path = CARTS[code.encode()].town
    out = os.path.join(OUT_DIR, f"Gen5TownMap{name.replace(' ', '')}.kt")
    upper = name.replace(" ", "").upper()
    name = name.replace(" ", "")
    with open(out, "w") as f:
        f.write(f"// Generated by scripts/gen_gen5_tables.py from the Pokémon {name} cart ({code} rev 0) - do not edit.\n"
                "package com.pokedaisy.app.companion.data\n\n")
        if sequel:
            f.write(f"/**\n * The Town Map's places ({path[1:]} member 0: 54-byte records, in the cart's order, the other version's Black City /\n"
                    " * White Forest left out): the zone, its place, its point on the map (the touch screen's pixels, +4 / +6), its kind\n"
                    " * (+0x16: 0 a town, 1 a route, 2 a place, 3 one of the new places FLY reaches - PWT, Pokéstar Studios, Join\n"
                    " * Avenue, Victory Road -, 4 shown once [Gen5TownMapPlace.shownBy] says so, 5 Unity Tower), its arrival flag\n"
                    " * (+0x1E; -1 none) and what shows it (+0x20: a flag, 0xF00n a special; -1 always).\n */\n")
        else:
            f.write("/**\n * The Town Map's places (a/0/8/6 member 0: 54-byte records, in the cart's order): the zone, its place, its point\n"
                    " * on the map (the touch screen's pixels, +4 / +6), its kind (+0x16: 0 a town, 1 a route, 2 a place, 3 Victory\n"
                    " * Road, 4 Liberty Garden, 5 Unity Tower) and its arrival flag (+0x1E; -1 none).\n */\n")
        f.write(f"val townMapPlaces{name}: List<Gen5TownMapPlace> = listOf(\n")
        for k, (zone, place, x, y, kind, flag) in enumerate(town_map):
            show = f", shownBy = {shown[k]}" if shown and shown[k] != 0xFFFF else ""
            f.write(f"    Gen5TownMapPlace({zone}, {place}, {x}, {y}, {kind}, {flag if flag != 0xFFFF else -1}{show}),\n")
        f.write(f")\n\n/** Each zone's place on the Town Map (an index into [townMapPlaces{name}], -1 none), by zone id. */\n")
        f.write(f"val townMapZones{name}: IntArray by lazy {{ TOWN_MAP_ZONES_{upper}.split(',').map {{ it.toInt() }}.toIntArray() }}\n\n")
        f.write(f"private const val TOWN_MAP_ZONES_{upper} =\n")
        ids = [str(v) for v in town_zones]
        rows = [",".join(ids[i:i + 40]) for i in range(0, len(ids), 40)]
        f.write("    " + " +\n    ".join(f'"{row}{"," if i < len(rows) - 1 else ""}"' for i, row in enumerate(rows)) + "\n")
        if fly is not None:
            write_fly(f, name, fly)
    print(f"wrote {out}: {len(town_map)} places")


def write(species, moves, items, item_icons, icon_pals, place_names, map_place_ids, fly, town=None):
    if town:
        write_town_map(town[0], town[1])
    head = "// Generated by scripts/gen_gen5_tables.py from the Pokémon Black cart (IRBO rev 0) - do not edit.\n" \
           "package com.pokedaisy.app.companion.data\n\n"
    out = os.path.join(OUT_DIR, "Gen5TablesBlack.kt")
    with open(out, "w") as f:
        f.write(head)
        f.write("/** Each species' name (the game's own casing), 1..649. */\n")
        f.write("val speciesNamesBlack: Map<Int, String> = mapOf(\n")
        f.writelines(f"    {i} to {kstr(n)},\n" for i, (n, _, _) in sorted(species.items()))
        f.write(")\n\n/** Each species' types, in the app's (Gen 3's) type ids. */\n")
        f.write("val speciesTypesBlack: Map<Int, SpeciesTypes> = mapOf(\n")
        f.writelines(f"    {i} to SpeciesTypes({a}, {b}),\n" for i, (_, a, b) in sorted(species.items()))
        f.write(")\n\n/** Each move's name, type (the app's ids) and power (0: none, 1: it varies). */\n")
        f.write("val moveDataBlack: Map<Int, MoveInfo> = mapOf(\n")
        f.writelines(f"    {i} to MoveInfo({kstr(n)}, {t}, {p}),\n" for i, (n, t, p) in sorted(moves.items()))
        f.write(")\n\n/** The place names (the Town Map's and the map labels' lines), by place id. */\n")
        f.write("val placeNamesBlack: Map<Int, String> = mapOf(\n")
        used = sorted(set(map_place_ids.values()))
        f.writelines(f"    {i} to {kstr(place_names[i])},\n" for i in used)
        f.write(")\n\n/** Each zone's place (its map label), by zone id (the save's map: a zone). */\n")
        f.write("val mapPlaceIdsBlack: Map<Int, Int> by lazy {\n")
        f.write("    MAP_PLACE_IDS_BLACK.split(',').withIndex().associate { (zone, place) -> zone to place.toInt() }\n}\n\n")
        f.write("/** Each zone's place name, by zone id. */\n")
        f.write("val mapPlacesBlack: Map<Int, String> by lazy {\n")
        f.write("    mapPlaceIdsBlack.mapValues { (_, place) -> placeNamesBlack[place].orEmpty() }\n}\n\n")
        f.write("/** Zone id -> place id, comma-separated by zone. */\n")
        f.write("private const val MAP_PLACE_IDS_BLACK =\n")
        ids = [str(map_place_ids[z]) for z in range(len(map_place_ids))]
        for k in range(0, len(ids), 40):
            f.write('    "' + ",".join(ids[k:k + 40]) + ("," if k + 40 < len(ids) else "") + '"' +
                    (" +\n" if k + 40 < len(ids) else "\n"))
        write_fly(f, "Black", fly)
    print(f"wrote {out}: {len(species)} species, {len(moves)} moves, {len(map_place_ids)} zones, {len(fly)} fly spots")

    out = os.path.join(OUT_DIR, "Gen5ItemsBlack.kt")
    with open(out, "w") as f:
        f.write(head)
        f.write("val itemNamesBlack: Map<Int, String> = mapOf(\n")
        f.writelines(f"    {i} to {kstr(n)},\n" for i, (n, _) in sorted(items.items()))
        f.write(")\n\n/** The companion's pocket for each item: the game's own five (Poké Balls ride in ITEMS in Gen 5);\n"
                " * ITEMS is left out. */\n")
        f.write("val itemPocketsBlack: Map<Int, Int> = mapOf(\n")
        f.writelines(f"    {i} to {p},\n" for i, (_, p) in sorted(items.items()) if p != "POCKET_ITEMS")
        f.write(")\n\n/** Each item's icon in the item icon archive (a/0/2/5): its NCGR and NCLR members, packed (ncgr shl 16 or nclr). */\n")
        f.write("val itemIconsBlack: Map<Int, Int> = mapOf(\n")
        f.writelines(f"    {i} to 0x{g:04X}{c:04X},\n" for i, (g, c) in sorted(item_icons.items()))
        f.write(")\n\n/** Each party icon's palette (0-2: member 0's three) in the icon archive (a/0/0/7, the icon is member\n"
                " * 7 + 2 * entry): species 0..649, then the egg (650), the Manaphy egg (651) and the forms. The female icons of\n"
                " * FRILLISH / JELLICENT (592, 593) use palette 1 instead. */\n")
        f.write("val iconPalettesBlack: ByteArray = byteArrayOf(\n")
        for i in range(0, len(icon_pals), 32):
            f.write("    " + ", ".join(str(v) for v in icon_pals[i:i + 32]) + ",\n")
        f.write(")\n")
    print(f"wrote {out}: {len(items)} items")


def sequel_regional(rom):
    """The sequels' Unova Dex: /a/0/1/6 member 709, a u16 per species - its place there (VICTINI 0 .. GENESECT 300) or
    999. In the dex's order."""
    v = struct.unpack_from("<650H", rom.narc("/a/0/1/6")[709], 0)
    order = [s for _, s in sorted((x, s) for s, x in enumerate(v) if x != 999)]
    assert len(order) == 301 and order[:2] == [494, 495] and order[-1] == 649, order[:4]
    return order


def write_sequel(black2, white2, black, regional):
    """Black 2's and White 2's files: Gen5TablesBlack2.kt (places, zones, the items they added, the item icons and party
    icon palettes, which moved) and each version's Town Map with its FLY spots. Their species and moves are Black's."""
    species, moves, items, item_icons, icon_pals, place_names, map_place_ids, fly, town = black2
    assert species == black[0] and moves == black[1], "the sequels' species and moves are Black's"
    assert all(items.get(i) == v for i, v in black[2].items()), "the sequels keep Black's items"
    # White 2's tables are Black 2's but for its Town Map (White Forest for Black City) and FLY.
    assert white2[:7] == black2[:7], [k for k, (a, b) in enumerate(zip(white2[:7], black2[:7])) if a != b]
    write_town_map(town[0], town[1], name="Black 2", code="IREO", fly=fly, shown=town[2])
    write_town_map(white2[8][0], white2[8][1], name="White 2", code="IRDO", fly=white2[7], shown=white2[8][2])
    out = os.path.join(OUT_DIR, "Gen5TablesBlack2.kt")
    with open(out, "w") as f:
        f.write("// Generated by scripts/gen_gen5_tables.py from the Pokémon Black 2 cart (IREO rev 0) - do not edit.\n"
                "package com.pokedaisy.app.companion.data\n\n")
        f.write("/** The place names (text bank 109: the Town Map's and the map labels' lines), by place id. */\n")
        f.write("val placeNamesBlack2: Map<Int, String> = mapOf(\n")
        used = sorted(set(map_place_ids.values()))
        f.writelines(f"    {i} to {kstr(place_names[i])},\n" for i in used)
        f.write(")\n\n/** Each zone's place (its map label), by zone id (the save's map: a zone). */\n")
        f.write("val mapPlaceIdsBlack2: Map<Int, Int> by lazy {\n")
        f.write("    MAP_PLACE_IDS_BLACK2.split(',').withIndex().associate { (zone, place) -> zone to place.toInt() }\n}\n\n")
        f.write("/** Each zone's place name, by zone id. */\n")
        f.write("val mapPlacesBlack2: Map<Int, String> by lazy {\n")
        f.write("    mapPlaceIdsBlack2.mapValues { (_, place) -> placeNamesBlack2[place].orEmpty() }\n}\n\n")
        f.write("/** Zone id -> place id, comma-separated by zone. */\n")
        f.write("private const val MAP_PLACE_IDS_BLACK2 =\n")
        ids = [str(map_place_ids[z]) for z in range(len(map_place_ids))]
        for k in range(0, len(ids), 40):
            f.write('    "' + ",".join(ids[k:k + 40]) + ("," if k + 40 < len(ids) else "") + '"' +
                    (" +\n" if k + 40 < len(ids) else "\n"))
        added = {i: v for i, v in sorted(items.items()) if i not in black[2]}
        f.write("\n/** The items the sequels added to Black's ([itemNamesBlack]): the key items 627-638. */\n")
        f.write("val itemNamesBlack2Added: Map<Int, String> = mapOf(\n")
        f.writelines(f"    {i} to {kstr(n)},\n" for i, (n, _) in added.items())
        f.write(")\n\n/** Their pockets (every one a key item). */\n")
        f.write("val itemPocketsBlack2Added: Map<Int, Int> = mapOf(\n")
        f.writelines(f"    {i} to {p},\n" for i, (_, p) in added.items())
        f.write(")\n\n/** Each item's icon in the item icon archive (a/0/2/5; the sequels renumbered it from item 505 on): its NCGR\n"
                " * and NCLR members, packed (ncgr shl 16 or nclr). */\n")
        f.write("val itemIconsBlack2: Map<Int, Int> = mapOf(\n")
        f.writelines(f"    {i} to 0x{g:04X}{c:04X},\n" for i, (g, c) in sorted(item_icons.items()))
        f.write(")\n\n/** Each party icon's palette (0-2: member 0's three) in the icon archive (a/0/0/7, the icon is member 8 + 2 *\n"
                " * entry: the sequels added a second palette at 1): species 0..649, then the egg (650), the Manaphy egg (651)\n"
                " * and the forms (the sequels added some). */\n")
        f.write("val iconPalettesBlack2: ByteArray = byteArrayOf(\n")
        for i in range(0, len(icon_pals), 32):
            f.write("    " + ", ".join(str(v) for v in icon_pals[i:i + 32]) + ",\n")
        f.write(")\n\n/** The sequels' Unova Dex (a/0/1/6 member 709), in its order: VICTINI first, then SNIVY ... - 301 species,\n"
                " * older ones among them (the reader's National Dex check: anything seen outside it). */\n")
        f.write("val unovaDexBlack2: IntArray = intArrayOf(\n")
        for i in range(0, len(regional), 16):
            f.write("    " + ", ".join(str(v) for v in regional[i:i + 16]) + ",\n")
        f.write(")\n")
    print(f"wrote {out}: {len(map_place_ids)} zones, {len(added)} added items")


def main():
    if len(sys.argv) not in (2, 3, 5):
        sys.exit(__doc__)
    black = read(Rom(sys.argv[1]))
    write(*black)
    if len(sys.argv) >= 3:
        white = read(Rom(sys.argv[2]), b"IRAO")
        # Everything but the FLY spots and the Town Map is Black's (the lists' order: read's return).
        assert white[:7] == black[:7], [k for k, (a, b) in enumerate(zip(white[:7], black[:7])) if a != b]
        write_town_map(white[8][0], white[8][1], name="White", code="IRAO", fly=white[7])
    if len(sys.argv) == 5:
        black2 = Rom(sys.argv[3])
        write_sequel(read(black2, b"IREO"), read(Rom(sys.argv[4]), b"IRDO"), black, sequel_regional(black2))


if __name__ == "__main__":
    main()
