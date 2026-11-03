#!/usr/bin/env python3
"""Pokémon Black's, White's, Black 2's and White 2's GUIDE data: HERE's wild Pokémon by zone, NEXT BOSS's teams and HERE's
item balls, read from the player's own cart (there is no pret decomp for Gen 5).

    python3 -I scripts/gen_gen5_guide.py <Pokemon Black (USA, Europe).nds>      # IRBO, rev 0
    python3 -I scripts/gen_gen5_guide.py <Pokemon White (USA, Europe).nds>      # IRAO, rev 0
    python3 -I scripts/gen_gen5_guide.py <Pokemon Black 2 (USA, Europe).nds>    # IREO, rev 0
    python3 -I scripts/gen_gen5_guide.py <Pokemon White 2 (USA, Europe).nds>    # IRDO, rev 0

Writes app/src/main/kotlin/com/pokedaisy/app/companion/data/GuideGen5BlackGen.kt (or GuideGen5WhiteGen.kt,
GuideGen5Black2Gen.kt, GuideGen5White2Gen.kt): numbers only (species, levels, items and moves by id, zone ids, trainer
ids, flags) - no text of the game's, which the companion takes from its own tables (Gen5TablesBlack.kt: White's names
are Black's). Gen5World (GuideGen5.kt) reads it back. Black and White differ in their wild Pokémon, the eighth leader
(Black's DRAYDEN, White's IRIS) and N's legend; Black 2 and White 2 only in their wild Pokémon.

Black 2 / White 2 keep every format below in other files (FILES_B2W2): text banks 90 (species), 382 / 383 (trainer
names / classes), 109 (places); 615 zones whose +0x14 holds the wild set in bits 0-12 (0x1FFF none; the top bits are
something else) and whose event file is their header's +0x16 (Black's is the zone id); wild sets /a/1/2/7, trainers
/a/0/9/1 and /a/0/9/2, events /a/1/2/6, the item balls' scripts /a/0/5/6 member 1240. Their bosses come twice, Normal
and Challenge Mode (sequel_bosses), and the Elite Four and IRIS again once the game is cleared.

The cart's data (Gen 5's numbered NARCs):
  - zones /a/0/1/2 member 0: 427 x 48 bytes, the zone's wild set at +0x14 (0xFFFF none), its place at +0x1A, bit 2
    of +0x1B set on the outdoor maps;
  - wild sets /a/1/2/6: one 0xE8-byte block, or four (spring, summer, autumn, winter) for a place the seasons change.
    A block: 8 rate bytes (grass, dark grass, rustling grass, surfing, rippling water, fishing, rippling fishing, 0),
    then 4-byte slots (u16 species: bits 0-10 the species, 11-15 the form; u8 min level; u8 max level): 12 grass, 12
    dark grass, 12 rustling grass, 5 surfing, 5 rippling water, 5 fishing, 5 rippling fishing;
  - trainers /a/0/9/2 (20 bytes: +0 bit 0 moves given / bit 1 items given, +1 class, +3 Pokémon count) and their
    Pokémon /a/0/9/3 (8 bytes: +2 level, +4 species, +6 form; then a u16 held item, then 4 u16 moves, as +0 says);
  - item balls: a zone's event file /a/1/2/5 (member = zone id: u32 size, u8 counts of furniture / people / warps /
    triggers, then 0x14-byte furniture and 0x24-byte people). A person running script 7000 + n is an item ball (its
    hide flag at +8 = picked up); script n of /a/0/5/7 member 864 sets VAR 0x800C to the item and 0x800D to the count
    (`28 00 0C 80 <item>` / `28 00 0D 80 <count>`).
Hidden items (furniture running script 8000 + n) are left out: their places are a dowsing table in overlay 10, but the
item each one gives wasn't found.

Wild lines (a raw string, split into chunks under the JVM's 64 KB constant limit):
  M|<zone>|<wild set>
  E|<set>|<season 0-3, or -1 all year>|<grass rate>|<12 slots>|<dark grass rate>|<12>|<rustling grass rate>|<12>|
    <surf rate>|<5>|<rippling water rate>|<5>|<fishing rate>|<5>|<rippling fishing rate>|<5>      slot = min-max:species
Boss lines: <key>|<trainer id>|<level species item m1,m2,m3,m4;...>
Area lines (GuideAreas.kt's format): <place>|ITEM|<item>|<flag>|<qty>|0|      (a place = mapPlaceIdsBlack's, the sequels' mapPlaceIdsBlack2's)
"""
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "app/src/main/kotlin/com/pokedaisy/app/companion/data")


# ---- the cart: file system, NARC, text (self-contained: run with python3 -I) ----

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

    def narc(self, path):
        i = self.paths[path]
        return Narc(self.d[u32(self.d, self.fat + i * 8):u32(self.d, self.fat + i * 8 + 4)])


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
        s = ""
        for c in dec:
            if c >= 0xF000:
                break
            s += chr(c)
        out.append(s)
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


# ---- the data ----

BLOCK = 0xE8
# (count, offset) of each method's slots in a block: grass, dark grass, rustling grass, surf, rippling, fish, rippling fish.
METHODS = [(12, 0x08), (12, 0x38), (12, 0x68), (5, 0x98), (5, 0xAC), (5, 0xC0), (5, 0xD4)]

# The carts: name, output file, the Kotlin vals' prefix, the eighth leader, N's trainer and his legend's species (the
# sequels: no eighth-leader or N difference - None).
VERSIONS = {
    b"IRBO": ("Black", "GuideGen5BlackGen.kt", "BLACK", ("DRAYDEN", 133, "Drayden"), 587, 644),   # N with ZEKROM
    b"IRAO": ("White", "GuideGen5WhiteGen.kt", "WHITE", ("IRIS", 132, "Iris"), 586, 643),         # N with RESHIRAM
    b"IREO": ("Black 2", "GuideGen5Black2Gen.kt", "BLACK2", None, None, None),
    b"IRDO": ("White 2", "GuideGen5White2Gen.kt", "WHITE2", None, None, None),
}


class Files:
    """Where one cart keeps the guide's data: Black / White's NARCs, or the sequels' (the same formats, renumbered)."""

    def __init__(self, species, trainer_names, classes, places, zones, wild, tdata, tmons, events, scripts, balls,
                 wild_mask, events_by_header):
        self.species, self.trainer_names, self.classes, self.places = species, trainer_names, classes, places
        self.zones, self.wild, self.tdata, self.tmons, self.events, self.scripts = zones, wild, tdata, tmons, events, scripts
        self.balls = balls                         # the script file of the item balls' scripts (7000 + n)
        self.wild_mask = wild_mask                 # the zone header's +0x14: the wild set's bits (all set = none)
        self.events_by_header = events_by_header   # a zone's event file is its header's +0x16 (Black's: the zone id)


FILES_BW = Files(284, 190, 191, 89, 427, "/a/1/2/6", "/a/0/9/2", "/a/0/9/3", "/a/1/2/5", "/a/0/5/7", 864, 0xFFFF, False)
FILES_B2W2 = Files(90, 382, 383, 109, 615, "/a/1/2/7", "/a/0/9/1", "/a/0/9/2", "/a/1/2/6", "/a/0/5/6", 1240, 0x1FFF, True)


def bosses_for(version):
    """NEXT BOSS: key -> (trainer id, the name the cart gives it, its class). The three Striaton leaders: you face the
    one whose type beats your starter."""
    if version in (b"IREO", b"IRDO"):
        return sequel_bosses()
    _, _, _, (leader8, leader8_id, leader8_name), n_id, _ = VERSIONS[version]
    return {
        "CHILI": (11, "Chili", "Leader"), "CILAN": (12, "Cilan", "Leader"), "CRESS": (13, "Cress", "Leader"),
        "LENORA": (21, "Lenora", "Leader"), "BURGH": (22, "Burgh", "Leader"), "ELESA": (23, "Elesa", "Leader"),
        "CLAY": (24, "Clay", "Leader"), "SKYLA": (25, "Skyla", "Leader"), "BRYCEN": (131, "Brycen", "Leader"),
        leader8: (leader8_id, leader8_name, "Leader"),
        "SHAUNTAL": (228, "Shauntal", "Elite Four"), "MARSHAL": (229, "Marshal", "Elite Four"),
        "GRIMSLEY": (230, "Grimsley", "Elite Four"), "CAITLIN": (231, "Caitlin", "Elite Four"),
        # N's Castle: N with the version's legend, then GHETSIS.
        "N": (n_id, "N", "Team Plasma"), "GHETSIS": (232, "Ghetsis", "Team Plasma"),
        "ALDER": (407, "Alder", "Champion"),
        # The Elite Four's second round, once the story is over.
        "SHAUNTAL_2": (563, "Shauntal", "Elite Four"), "MARSHAL_2": (564, "Marshal", "Elite Four"),
        "GRIMSLEY_2": (565, "Grimsley", "Elite Four"), "CAITLIN_2": (566, "Caitlin", "Elite Four"),
    }


def sequel_bosses():
    """Black 2's and White 2's bosses (the same trainers in both): each in its Normal Mode team and its Challenge Mode
    one (KEY_C; the League's scripts battle the Challenge trainer when that mode is on, the Normal one otherwise - Easy
    Mode is the Normal team at lower levels), the Elite Four and IRIS again once the game is cleared (KEY_2, KEY_2C)."""
    out = {}

    def add(key, normal, challenge, name, cls):
        out[key] = (normal, name, cls)
        out[key + "_C"] = (challenge, name, cls)

    for key, n, c, name in (("CHEREN", 156, 764, "Cheren"), ("ROXIE", 157, 765, "Roxie"), ("BURGH", 154, 766, "Burgh"),
                            ("ELESA", 153, 767, "Elesa"), ("CLAY", 158, 768, "Clay"), ("SKYLA", 155, 769, "Skyla"),
                            ("DRAYDEN", 159, 770, "Drayden"), ("MARLON", 160, 771, "Marlon")):
        add(key, n, c, name, "Leader")
    out["GHETSIS"] = (345, "Ghetsis", "Team Plasma")
    for key, n, c, n2, c2, name in (("SHAUNTAL", 38, 772, 143, 777, "Shauntal"), ("GRIMSLEY", 40, 773, 145, 778, "Grimsley"),
                                    ("MARSHAL", 39, 774, 144, 779, "Marshal"), ("CAITLIN", 41, 775, 146, 780, "Caitlin")):
        add(key, n, c, name, "Elite Four")
        add(key + "_2", n2, c2, name, "Elite Four")
    add("IRIS", 341, 776, "Iris", "Champion")
    add("IRIS_2", 536, 781, "Iris", "Champion")
    return out


def slots(b, n, off):
    out = []
    for k in range(n):
        sp, lo, hi = u16(b, off + 4 * k), b[off + 4 * k + 2], b[off + 4 * k + 3]
        out.append(f"{lo}-{hi}:{sp & 0x7FF}")
    return " ".join(out)


def read(rom):
    version = rom.d[0xC:0x10]
    assert version in VERSIONS and rom.d[0x1E] == 0, "not Pokémon Black / White / Black 2 / White 2 (IRBO / IRAO / IREO / IRDO rev 0)"
    sequel = version in (b"IREO", b"IRDO")
    fs = FILES_B2W2 if sequel else FILES_BW
    texts = rom.narc("/a/0/0/2")
    species = text5(texts[fs.species])
    trainer_names, classes = text5(texts[fs.trainer_names]), text5(texts[fs.classes])
    places = text5(texts[fs.places])
    assert species[495].upper() == "SNIVY", species[495]

    zones = rom.narc("/a/0/1/2")[0]
    assert len(zones) == fs.zones * 48, len(zones)
    wild = rom.narc(fs.wild)
    rows, sets = [], set()
    place_of = {}
    for z in range(len(zones) // 48):
        r = zones[z * 48:z * 48 + 48]
        place_of[z] = r[0x1A]
        w = u16(r, 0x14) & fs.wild_mask
        if w != fs.wild_mask:
            rows.append(f"M|{z}|{w}")
            sets.add(w)
    for w in sorted(sets):
        d = wild[w]
        assert len(d) in (BLOCK, 4 * BLOCK), (w, len(d))
        for season in range(len(d) // BLOCK):
            b = d[season * BLOCK:(season + 1) * BLOCK]
            f = [str(w), str(season if len(d) > BLOCK else -1)]
            for m, (n, off) in enumerate(METHODS):
                f += [str(b[m]), slots(b, n, off)]
            rows.append("E|" + "|".join(f))
    if sequel:
        # ROUTE 19 (zone 437): PATRAT / PURRLOIN at 2-4 in the grass.
        r19 = next(l for l in rows if l.startswith(f"E|{u16(zones, 437 * 48 + 0x14) & fs.wild_mask}|")).split("|")
        assert places[place_of[437]] == "Route 19" and {s.split(":")[1] for s in r19[4].split()} == {"504", "509"}, r19[3:5]
    else:
        # ROUTE 1 (zone 317): PATRAT / LILLIPUP at 2-4 in the grass, AUDINO in rustling grass.
        r1 = next(l for l in rows if l.startswith(f"E|{u16(zones, 317 * 48 + 0x14)}|")).split("|")
        assert {s.split(":")[1] for s in r1[4].split()} == {"504", "506"} and r1[3] != "0", r1[3:5]
        assert {s.split(":")[1] for s in r1[8].split()} == {"531"}, r1[8]
        # MOOR OF ICIRRUS (zone 346): its grass is gone in winter.
        moor = [l.split("|") for l in rows if l.startswith(f"E|{u16(zones, 346 * 48 + 0x14)}|")]
        assert [m[3] != "0" for m in moor] == [True, True, True, False], [m[2:4] for m in moor]

    tdata, tmons = rom.narc(fs.tdata), rom.narc(fs.tmons)
    bosses = []
    for key, (tid, name, cls) in bosses_for(version).items():
        td, tp = tdata[tid], tmons[tid]
        assert trainer_names[tid] == name and classes[td[1]] == cls, (key, trainer_names[tid], classes[td[1]])
        fmt, count = td[0], td[3]
        size = 8 + (2 if fmt & 2 else 0) + (8 if fmt & 1 else 0)
        assert fmt & 1, key   # every boss has its moves set
        team = []
        for k in range(count):
            m = tp[k * size:(k + 1) * size]
            o = 8
            item = 0
            if fmt & 2:
                item = u16(m, o)
                o += 2
            moves = [u16(m, o + 2 * j) for j in range(4)]
            team.append(f"{m[2]} {u16(m, 4) & 0x7FF} {item} " + ",".join(str(v) for v in moves if v))
        bosses.append(f"{key}|{tid}|" + ";".join(team))
    if sequel:
        # CHEREN's Normal team: PATRAT 11, LILLIPUP 13; his Challenge one adds a PIDOVE. GHETSIS ends with HYDREIGON.
        assert bosses[0].startswith("CHEREN|156|11 504 0 ") and ";13 506 0 " in bosses[0], bosses[0]
        assert next(b for b in bosses if b.startswith("CHEREN_C|")).count(";") == 2
        assert ";52 635 " in next(b for b in bosses if b.startswith("GHETSIS|")), "GHETSIS"
    else:
        assert bosses[0].startswith("CHILI|11|12 506 0 44,526;14 513 0 510,526"), bosses[0]
        assert f"52 {VERSIONS[version][5]} " in next(b for b in bosses if b.startswith("N|")), "N's legend"

    events = rom.narc(fs.events)
    scripts = rom.narc(fs.scripts)[fs.balls]
    entries = []
    while u16(scripts, 4 * len(entries)) != 0xFD13:
        k = len(entries)
        entries.append(u32(scripts, 4 * k) + 4 * k + 4)

    def ball(n):
        s = scripts[entries[n]:entries[n] + 32]
        assert s[2:6] == b"\x28\x00\x0c\x80" and s[8:12] == b"\x28\x00\x0d\x80", (n, s.hex())
        return u16(s, 6), u16(s, 12)

    areas = []
    for z in range(len(place_of) if fs.events_by_header else min(len(events), len(place_of))):
        e = u16(zones, z * 48 + 0x16) if fs.events_by_header else z
        if e >= len(events):
            continue
        d = events[e]
        if len(d) < 8:
            continue
        nf, nn = d[4], d[5]
        o = 8 + nf * 0x14
        for i in range(nn):
            sc, flag = u16(d, o + 10), u16(d, o + 8)
            if 7000 <= sc < 7000 + len(entries) and flag:
                item, qty = ball(sc - 7000)
                areas.append(f"{place_of[z]}|ITEM|{item}|{flag}|{qty}|0|")
            o += 0x24
    areas = sorted(set(areas), key=lambda l: (int(l.split("|")[0]), l))
    check = 446 if sequel else 317   # ROUTE 20 / ROUTE 1
    assert places[place_of[check]] == ("Route 20" if sequel else "Route 1") and \
        any(l.startswith(f"{place_of[check]}|ITEM|") for l in areas)
    return version, rows, bosses, areas, len(sets)


def write(version, rows, bosses, areas, nsets):
    name, file, prefix = VERSIONS[version][:3]
    sequel = version in (b"IREO", b"IRDO")
    places = "mapPlaceIdsBlack2's" if sequel else "mapPlaceIdsBlack's"
    who = "gym leaders (Normal and Challenge Mode), GHETSIS, the Elite Four and the champion, again once the game is cleared" \
        if sequel else "gym leaders, Elite Four, N, Ghetsis and the champion"
    out = os.path.join(OUT_DIR, file)
    with open(out, "w") as f:
        f.write(f"// Generated by scripts/gen_gen5_guide.py from the Pokémon {name} cart ({version.decode()} rev 0) - do not edit.\n")
        f.write("package com.pokedaisy.app.companion.data\n\n")
        f.write(kraw(f"{prefix}_WILD_RAW", rows, f"{name}'s wild Pokémon by zone (see the generator's doc for the line formats)."))
        f.write("\n" + kraw(f"{prefix}_BOSSES_RAW", bosses, f"{name}'s {who}: key|trainer id|team."))
        f.write("\n" + kraw(f"{prefix}_AREAS_RAW", areas, f"{name}'s item balls by place (GuideAreas.kt's lines; places are {places})."))
    print(f"wrote {out}: {nsets} wild sets, {len(bosses)} bosses, {len(areas)} item balls")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    write(*read(Rom(sys.argv[1])))


if __name__ == "__main__":
    main()
