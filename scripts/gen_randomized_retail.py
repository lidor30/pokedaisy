#!/usr/bin/env python3
"""Retail FireRed / Emerald run through Universal Pokemon Randomizer ZX -> RandomizedRetailGen.kt.

A randomizer seed changes thousands of bytes, so a hash of the whole file can't recognise "retail FireRed,
randomized". What can: every byte UPR may write sits at a fixed offset of the retail layout (its tables, the
item balls and hidden items it finds by walking the maps, its code patches - all at retail's places, never moved;
what grows goes into retail's 0xFF free space). So, like RetailDumps' trimmed / save-patched dumps (and on top of
their ranges), a ROM of the same code and revision whose bytes [0, end) hash to [sha1] once
  - the species name table is upper-cased (`fold`: UPR rewrites it on every run, in lower case with its
    "lower-case names" tweak - folded it is retail's again, so names are still checked), and
  - every site UPR can write is zeroed (`mask`)
is retail with UPR's data: retail's code, RAM and table addresses, its own species / moves / items / places by
retail's ids. The companion reads it as retail and takes from the ROM what the randomizer changed
(RandomizedRetail.kt, docs/DEVELOPMENT.md's "Randomized ROMs").

The sites come from UPR's own offsets file and patches (read from its checkout for facts only: $UPR_SRC, a clone of
github.com/Ajarmar/universal-pokemon-randomizer-zx at the tag the companion supports) and from walking the retail
ROM the way UPR does (wild headers, map events, trainer parties, learnsets). Written: offsets, lengths and hashes
only, no ROM bytes. Also written, for the GUIDE: where each item ball's / hidden item's item id sits (by its flag),
which in-game trade table row each trade is, and each gift / static Pokemon's species bytes - so HERE can read what
the randomizer put there.

    UPR_SRC=<upr checkout> python3 scripts/gen_randomized_retail.py <FireRed rev 0> <FireRed rev 1> <Emerald>
    UPR_SRC=... python3 scripts/gen_randomized_retail.py <three ROMs> --check <folder of UPR outputs>
--check checks every .gba in the folder whose code / revision is one of the three: every byte it changed is a masked
(or folded) one, and it hashes to its game's sha1. Run it over a fresh batch of seeds and settings after a UPR update.
"""
import hashlib
import os
import re
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/kotlin/com/pokedaisy/app/companion/data/RandomizedRetailGen.kt"
DATA = ROOT / "app/src/main/kotlin/com/pokedaisy/app/companion/data"
UPR_VERSION = "4.6.1"

# UPR's offsets-file entry for each retail ROM, and the guide data the game reads.
GAMES = {
    ("BPRE", 0): ("Fire Red (U) 1.0", "GUIDE_AREAS_FIRERED_RAW", "GuideAreasFireRedGen.kt"),
    ("BPRE", 1): ("Fire Red (U) 1.1", "GUIDE_AREAS_FIRERED_RAW", "GuideAreasFireRedGen.kt"),
    ("BPEE", 0): ("Emerald (U)", "GUIDE_AREAS_EMERALD_RAW", "GuideAreasEmeraldGen.kt"),
}

SPECIES = 411           # internal ids 1..411 (CHIMECHO): the table rows UPR rewrites
BASE_STRIDE = 28        # struct BaseStats
MOVE_STRIDE = 12        # struct BattleMove
TRAINER_STRIDE = 40     # struct Trainer
TRADE_STRIDE = 60       # struct InGameTrade
FLAG_HIDDEN_ITEMS_START_FRLG = 0x3E8
FLAG_HIDDEN_ITEMS_START_EMERALD = 0x1F4


def parse_int(s):
    s = s.strip().lower()
    return int(s[2:], 16) if s.startswith("0x") else int(s)


def load_ini(path):
    """UPR's gen3_offsets.ini: per entry its values, arrays, string values, static / roamer lists, TM / tutor texts."""
    entries, cur = {}, None
    for line in open(path, encoding="utf-8"):
        q = line.split("//", 1)[0].strip()
        if not q:
            continue
        if q.startswith("[") and q.endswith("]"):
            cur = {"v": {}, "a": {}, "s": {}, "static": [], "roam": [], "texts": [], "ips": {}}
            entries[q[1:-1]] = cur
            continue
        k, v = (x.strip() for x in q.split("=", 1))
        if k in ("StaticPokemon{}", "RoamingPokemon{}"):
            one = {m.group(1): [parse_int(x) for x in m.group(2).split(",") if x.strip()]
                   for m in re.finditer(r"([A-Za-z]+)=\[([^\]]*)\]", v)}
            cur["static" if k.startswith("Static") else "roam"].append(one)
        elif k in ("TMText[]", "MoveTutorText[]"):
            p = v[1:-1].split(",", 5)
            cur["texts"].append({"num": parse_int(p[0]), "bank": parse_int(p[1]), "map": parse_int(p[2]),
                                 "person": parse_int(p[3]), "off": parse_int(p[4]), "tutor": k.startswith("Move")})
        elif k == "CopyFrom":
            o = entries[v]
            for part in ("v", "a", "s"):
                cur[part].update(o[part])
            if cur["v"].get("CopyTMText") == 1:
                cur["texts"] += o["texts"]
        elif k.endswith("Tweak"):
            cur["ips"][k] = v
        elif k.endswith("Locator") or k.endswith("Prefix") or k in ("Game", "Type", "TableFile", "CRC32"):
            cur["s"][k] = v
        elif v.startswith("["):
            cur["a"][k] = [parse_int(x) for x in v[1:-1].split(",") if x.strip()]
        else:
            cur["v"][k] = parse_int(v)
    return entries


def ips_records(path):
    """(offset, length) of each record of an IPS patch."""
    d = open(path, "rb").read()
    assert d[:5] == b"PATCH", path
    i, out = 5, []
    while d[i:i + 3] != b"EOF":
        off = int.from_bytes(d[i:i + 3], "big")
        n = int.from_bytes(d[i + 3:i + 5], "big")
        i += 5
        if n == 0:
            n = int.from_bytes(d[i:i + 2], "big")
            i += 3
        else:
            i += n
        out.append((off, n))
    return out


class Rom:
    def __init__(self, path):
        self.d = open(path, "rb").read()

    def u8(self, o): return self.d[o]
    def u16(self, o): return int.from_bytes(self.d[o:o + 2], "little")
    def u32(self, o): return int.from_bytes(self.d[o:o + 4], "little")
    def ptr(self, o): return self.u32(o) - 0x08000000

    def findall(self, hexs):
        b, out = bytes.fromhex(hexs), []
        i = self.d.find(b)
        while i >= 0:
            out.append(i)
            i = self.d.find(b, i + 1)
        return out

    def find1(self, hexs):
        f = self.findall(hexs)
        assert len(f) == 1, (hexs, f)
        return f[0]

    def to_ff(self, o):
        """Offset of the 0xFF ending the text at o."""
        while self.d[o] != 0xFF:
            o += 1
        return o


def text_bytes(name):
    return bytes(0xBB + ord(c) - ord("A") for c in name)


# Items UPR never places or replaces (Gen3Constants' allowed items, in our words): key items, HMs and the unused ids.
NOT_RANDOMIZED = (set(range(259, 289)) | set(range(349, 377)) | set(range(339, 347)) | set(range(52, 63)) | {72, 82}
                  | set(range(87, 93)) | set(range(99, 103)) | {105} | set(range(112, 121)) | set(range(176, 179))
                  | set(range(226, 254)) | {267, 347, 348})


def item_randomized(item):
    return 1 <= item <= 376 and item not in NOT_RANDOMIZED


def map_banks(rom, groups):
    """gMapGroups: each bank's map header pointers (a bank ends where the next begins, or at the table)."""
    banks, o = [], groups
    while True:
        if any(groups < b <= o for b in banks):
            break
        q = rom.ptr(o)
        if not 0 <= q < len(rom.d):
            break
        banks.append(q)
        o += 4
    out = []
    for b in banks:
        maps, o = [], b
        while not any(b < x <= o for x in banks) and not b < groups <= o:
            q = rom.ptr(o)
            if not 0 <= q < len(rom.d):
                break
            maps.append(q)
            o += 4
        out.append(maps)
    return out


def sites(rom, e, emerald):
    """(start, length, count, stride, what) of every byte UPR may write; and the names to fold, the field items by
    flag, the trades and the statics for the guide."""
    v, a, s = e["v"], e["a"], e["s"]
    out = []

    def add(start, length, what, count=1, stride=0):
        assert start >= 0 and length > 0, what
        out.append((start, length, count, stride, what))

    names, items, moves, stats = rom.ptr(0x144), rom.ptr(0x1C8), rom.ptr(0x1CC), rom.ptr(0x1BC)
    fold = [(names + 11, SPECIES * 11)]
    # Base stats: stats, types, catch rate, held items, gender, growth rate, abilities.
    for first, n in ((0, 9), (12, 5), (19, 1), (22, 2)):
        add(stats + BASE_STRIDE + first, n, "base stats", SPECIES, BASE_STRIDE)
    add(moves + MOVE_STRIDE, 5, "move effect / power / type / accuracy / PP", v["MoveCount"], MOVE_STRIDE)
    p = s.get("DeoxysStatPrefix")
    add(rom.find1(p) + len(p) // 2, 12, "Deoxys' stats in code")
    evo = v["PokemonEvolutions"]
    add(evo + 40, 251 * 40, "evolutions 1..251")
    add(evo + 277 * 40, 135 * 40, "evolutions 277..411")
    # Learnsets: the pointer table, and the lists in place (one run in retail).
    ms = v["PokemonMovesets"]
    add(ms + 4, SPECIES * 4, "learnset pointers")
    lists = []
    for i in range(1, SPECIES + 1):
        q = st = rom.ptr(ms + i * 4)
        while rom.u16(q) != 0xFFFF:
            q += 2
        lists.append((st, q + 2))
    lists.sort()
    add(lists[0][0], lists[-1][1] - lists[0][0], "learnsets")
    q = v["EggMoves"]
    while rom.u16(q) != 0xFFFF:
        q += 2
    add(v["EggMoves"], q - v["EggMoves"], "egg moves")
    add(v["PokemonTMHMCompat"] + 8, SPECIES * 8, "TM / HM compatibility")
    tutors = v["MoveTutorMoves"]
    add(v["MoveTutorData"], tutors * 2, "tutor moves")
    per = ((tutors + 7) & ~7) // 8
    add(v["MoveTutorData"] + tutors * 2 + per, SPECIES * per, "tutor compatibility")
    add(v["TmMoves"], 100, "TM moves")
    add(v["TmMovesDuplicate"], 100, "TM moves (the copy)")
    add(v["ItemImages"] + 289 * 8 + 4, 4, "TM item palettes", 50, 8)
    add(items + 289 * 44 + 0x14, 4, "TM item descriptions", 50, 44)
    add(items + 44 + 16, 2, "item prices", v["ItemCount"] - 1, 44)
    # Trainers: party flags, name, double battle, party size and pointer; the parties in place (one run in retail).
    td, tc = v["TrainerData"], v["TrainerCount"]
    for first, n in ((0, 1), (4, 12), (24, 1), (32, 1), (36, 4)):
        add(td + TRAINER_STRIDE + first, n, "trainers", tc - 1, TRAINER_STRIDE)
    parties = []
    for i in range(1, tc):
        o = td + i * TRAINER_STRIDE
        pp = rom.ptr(o + 36)
        parties.append((pp, pp + rom.u8(o + 32) * (16 if rom.u8(o) & 1 else 8)))
    parties.sort()
    assert all(parties[k][1] <= parties[k + 1][1] for k in range(len(parties) - 1))
    add(parties[0][0], max(z for _, z in parties) - parties[0][0], "trainer parties")
    add(v["TrainerClassNames"], v["TrainerClassCount"] * v["TrainerClassNameLength"], "trainer class names")
    if "MossdeepStevenTeamOffset" in v:
        add(v["MossdeepStevenTeamOffset"], 60, "Steven's team at Mossdeep")
    # Wild Pokemon: every encounter set's slots (4 bytes a slot), from the header table.
    wild = rom.ptr(rom.findall("0348048009E00000FFFF0000")[0] + 12)
    o, seen = wild, set()
    while not (rom.u8(o) == 0xFF and rom.u8(o + 1) == 0xFF):
        for k, slots in ((4, 12), (8, 5), (12, 5), (16, 10)):
            q = rom.ptr(o + k)
            if 0 <= q < len(rom.d) and rom.d[q] != 0 and rom.ptr(q + 4) not in seen:
                seen.add(rom.ptr(q + 4))
                add(rom.ptr(q + 4), slots * 4, "wild slots")
        o += 20
    # Starters (and FireRed's Oak naming them), statics, roamers, gifts.
    sb = v["StarterPokemon"]
    if emerald:
        for off in (0, 2, 4):
            add(sb + off, 2, "starter")
        add(v["StarterItems"], 4, "starter held item code")
    else:
        for off in (0, 5, 515, 520, 461, 466):
            add(sb + off, 2, "starter")
        add(sb + 218, 2, "starter held item")
        for name in ("BULBASAUR", "CHARMANDER", "SQUIRTLE"):
            at = rom.findall(text_bytes(name).hex())[0]
            add(at, rom.to_ff(at) + 1 - at, "Oak's line naming a starter")
    statics = {}
    for one in e["static"]:
        for x in one.get("Species", []):
            add(x, 2, "static species")
        for x in one.get("Level", []):
            add(x, 1, "static level")
        if one.get("Species"):
            statics.setdefault(rom.u16(one["Species"][0]), []).append(one["Species"][0])
    for one in e["roam"]:
        for x in one.get("Species", []):
            add(x, 2, "roamer species")
        for x in one.get("Level", []):
            add(x, 1, "roamer level")
    for x in a.get("GhostMarowakSpeciesOffsets", []):
        add(x, 2, "ghost species")
    for x in a.get("GhostMarowakLevelOffsets", []):
        add(x, 1, "ghost level")
    if "GhostMarowakGenderOffset" in v:
        add(v["GhostMarowakGenderOffset"], 1, "ghost gender")
    for x in a.get("FossilLevelOffsets", []):
        add(x, 2, "fossil level")
    if "StaticFirstBattleSpeciesOffset" in v:
        add(v["StaticFirstBattleSpeciesOffset"], 2, "first battle species")
        add(v["StaticFirstBattleLevelOffset"], 1, "first battle level")
    if emerald:
        cr = v["CreateInitialRoamerMonFunctionStartOffset"]
        for off, n in ((8, 4), (14, 1), (28, 4), (48, 4)):
            add(cr + off, n, "roamer code")
    # In-game trades: nickname, species, IVs, OT ID, held item, OT name, wanted species.
    for first, n in ((0, 20), (24, 2), (40, 2), (43, 11), (56, 2)):
        add(v["TradeTableOffset"] + first, n, "in-game trades", v["TradeTableSize"], TRADE_STRIDE)
    trades = [(rom.u16(v["TradeTableOffset"] + i * TRADE_STRIDE + 12), rom.u16(v["TradeTableOffset"] + i * TRADE_STRIDE + 56), i)
              for i in range(v["TradeTableSize"])]
    skip = set(a["SkipShops"])
    for i, so in enumerate(a["ShopItemOffsets"]):
        if i in skip:
            continue
        q = so
        while rom.u16(q) != 0:
            q += 2
        add(so, q - so, "shop")
    pick, step = rom.find1(s["PickupTableStartLocator"]), (2 if emerald else 4)
    add(pick, 2, "pickup items", v["PickupItemCount"], step)
    if emerald:
        add(v["IntroSpriteOffset"], 2, "intro")
        add(v["IntroCryOffset"], 2, "intro")
    else:
        add(v["IntroCryOffset"], 1, "intro")
        add(v["IntroOtherOffset"], 1, "intro")
        add(v["IntroSpriteOffset"], 8, "intro")
    # The misc tweaks' and the obedience / evolution patches' own bytes.
    add(v["RunIndoorsTweakOffset"], 1, "run indoors")
    add(v["CatchingTutorialOpponentMonOffset"], 4 if emerald else 2, "catching tutorial")
    if v.get("CatchingTutorialPlayerMonOffset"):
        add(v["CatchingTutorialPlayerMonOffset"], 4, "catching tutorial")
    add(v["PCPotionOffset"], 2, "PC potion")
    q = v["TypeEffectivenessOffset"]
    while rom.d[q] != 0xFE:
        q += 3
    add(v["TypeEffectivenessOffset"], q - v["TypeEffectivenessOffset"], "type chart")
    add(rom.find1("0640002E1BD08C20" if emerald else "02200540002D29D0") + 0x12, 2, "running shoes check")
    obey = rom.find1("CD21490088420FD0")
    add(obey, 4, "obedience check")
    add(obey + 0x16, 2, "obedience check")
    if not emerald:
        add(rom.find1("972814DD"), 4, "Kanto dex evolution check")
        add(rom.find1("972808D9"), 4, "Kanto dex evolution check")
    friend = rom.find1("DB2900D8")
    for off in ((0, 38, 66) if emerald else (0,)):
        add(friend + off, 1, "friendship evolution")
    add(rom.find1("FE2E2FD90020"), 4, "guaranteed catch")
    if emerald:
        script = rom.find1("3229610825F00129E40816CD40010003") - 8
        add(rom.find1((script + 0x08000000).to_bytes(4, "little").hex()), 4, "national dex")
    else:
        add(rom.find1("292908258101"), 6, "national dex")
        for x in rom.findall("260D809301210D800100"):
            add(x, 10, "national dex")
        add(rom.find1("257D011604800000260D80D400"), 8, "national dex")
        add(rom.find1("1604800000260D80D4001908800580190980068083000880830109802109803C"), 5, "national dex")
        add(rom.find1("00B5064800880028") + 9, 1, "national dex")
    patches = Path(os.environ["UPR_SRC"]) / "src/com/dabomstew/pkrandom/patches"
    for name in e["ips"].values():
        for off, n in ips_records(patches / (name + ".ips")):
            add(off, n, "patch " + name)
    # Item balls (their script's item) and hidden items, by walking the maps; TM / tutor text pointers.
    groups = rom.ptr(rom.findall("80180068890B091808687047")[0] + 12)
    field, ball_pic = {}, v["ItemBallPic"]
    for b, maps in enumerate(map_banks(rom, groups)):
        for m, hdr in enumerate(maps):
            ev = rom.ptr(hdr + 4)
            if not 0 <= ev < len(rom.d):
                continue
            objs, nobj, bgs, nbg = rom.ptr(ev + 4), rom.d[ev], rom.ptr(ev + 16), rom.d[ev + 3]
            for i in range(nobj):
                o = objs + i * 24
                so = rom.ptr(o + 16)
                if rom.d[o + 1] != ball_pic or so < 0:
                    continue
                x = rom.d[so:so + 12]
                if x[:3] == b"\x1A\x00\x80" and x[5:8] == b"\x1A\x01\x80" and x[10] == 9 and x[11] in (0, 1):
                    if item_randomized(rom.u16(so + 3)):
                        add(so + 3, 2, "item ball")
                    field[rom.u16(o + 20)] = so + 3
            for i in range(nbg):
                o = bgs + i * 12
                if 5 <= rom.d[o + 5] <= 7 and rom.u16(o + 8) != 0:
                    if item_randomized(rom.u16(o + 8)):
                        add(o + 8, 2, "hidden item")
                    field[FLAG_HIDDEN_ITEMS_START_EMERALD + rom.u16(o + 10) if emerald else FLAG_HIDDEN_ITEMS_START_FRLG + rom.d[o + 10]] = o + 8
            for t in e["texts"]:
                if t["bank"] != b or t["map"] != m:
                    continue
                so = rom.ptr(objs + (t["person"] - 1) * 24 + 16)
                if not emerald and t["tutor"] and (t["num"] == 5 or 8 <= t["num"] <= 11):
                    so = rom.ptr(so + 1)
                elif not emerald and t["tutor"] and t["num"] == 7:
                    so = rom.ptr(so + 0x1F)
                at = so + t["off"]
                if 0 <= at < len(rom.d) - 3 and rom.d[at + 3] in (8, 9):
                    word = rom.d[at:at + 4]
                    for k in range(max(0, at - 500), min(len(rom.d), at + 500) - 3):
                        if rom.d[k:k + 4] == word:
                            add(k, 4, "TM / tutor text pointer")
    return out, fold, field, trades, statics


def expand(descs):
    """Merged [start, end) ranges of the descriptors."""
    rs = sorted((s + i * st, s + i * st + n) for s, n, c, st, _ in descs for i in range(c))
    out = []
    for x, z in rs:
        if out and x <= out[-1][1]:
            out[-1][1] = max(out[-1][1], z)
        else:
            out.append([x, z])
    return out


def normalized(data, end, ranges, fold):
    b = bytearray(data[:end])
    for x, n in fold:
        b[x:x + n] = bytes(c - 0x1A if 0xD5 <= c <= 0xEE else c for c in b[x:x + n])
    for x, z in ranges:
        b[x:min(z, end)] = bytes(max(0, min(z, end) - x))
    return bytes(b)


def retail_dumps():
    """RetailDumpsGen.kt's rows: (code, rev) -> (end, [(start, end)], retail sha1)."""
    out = {}
    src = (DATA / "RetailDumpsGen.kt").read_text()
    for m in re.finditer(r'RetailDump\("(\w+)", (\d+), (0x[0-9A-F]+), listOf\(([^)]*)\), "(\w+)", "(\w+)"\)', src):
        rs = [(int(x, 16), int(y, 16)) for x, y in re.findall(r"(0x[0-9A-F]+) until (0x[0-9A-F]+)", m.group(4))]
        out[(m.group(1), int(m.group(2)))] = (int(m.group(3), 16), rs, m.group(6))
    return out


def guide_areas(name, file):
    """The guide's ITEM / HIDDEN flags, TRADE (species, wants) and MON / EGG species, from its generated area data."""
    raw = (DATA / file).read_text()
    lines = [l.split("|") for l in re.findall(r"^\d+\|[A-Z]+\|[^\n]*$", raw, re.M)]
    return lines


def encode(descs):
    """'start:len[:count:stride]' in hex, comma-separated, sorted."""
    return ",".join(("%x:%x" % (s, n)) + (":%x:%x" % (c, st) if c > 1 else "")
                    for s, n, c, st, _ in sorted(set(d[:4] + ("",) for d in descs)))


def chunked(s, width=110):
    parts = [s[i:i + width] for i in range(0, len(s), width)]
    return "\n".join('        "%s" +' % p for p in parts)[:-2]


def main():
    args = sys.argv[1:]
    check = None
    if "--check" in args:
        i = args.index("--check")
        check = args[i + 1]
        args = args[:i] + args[i + 2:]
    if len(args) != 3 or "UPR_SRC" not in os.environ:
        sys.exit(__doc__)
    ini = load_ini(Path(os.environ["UPR_SRC"]) / "src/com/dabomstew/pkrandom/config/gen3_offsets.ini")
    dumps = retail_dumps()
    games = {}
    for path in args:
        rom = Rom(path)
        key = (rom.d[0xAC:0xB0].decode(), rom.d[0xBC])
        end, dump_ranges, retail_sha1 = dumps[key]
        assert hashlib.sha1(rom.d).hexdigest() == retail_sha1, (path, "not the retail cart")
        entry, guide_val, guide_file = GAMES[key]
        e = ini[entry]
        assert int(e["s"]["CRC32"], 16) == zlib.crc32(rom.d), (path, "UPR's entry is for another ROM")
        descs, fold, field, trades, statics = sites(rom, e, key[0] == "BPEE")
        descs += [(x, z - x, 1, 0, "retail dump") for x, z in dump_ranges]
        ranges = expand(descs)
        sha1 = hashlib.sha1(normalized(rom.d, end, ranges, fold)).hexdigest()
        # The guide's entries, matched to the ROM: every ITEM / HIDDEN flag to its item's site (its item id is
        # retail's there), every TRADE to its table row, MON / EGG to a static of that species when it's the only one.
        lines = guide_areas(guide_val, guide_file)
        items, trade_rows, mons = {}, {}, {}
        for _, kind, ident, flag, *_rest in lines:
            ident, flag = int(ident), int(flag)
            if kind in ("ITEM", "HIDDEN") and flag in field:
                assert rom.u16(field[flag]) == ident, (key, kind, ident, flag)
                items[flag] = field[flag]
            elif kind == "TRADE":
                wants = int(_rest[1])
                rows = [i for g, w, i in trades if (g, w) == (ident, wants)]
                if len(rows) == 1:
                    trade_rows["%d/%d" % (ident, wants)] = rows[0]
            elif kind in ("MON", "EGG") and len(statics.get(ident, [])) == 1:
                mons[ident] = statics[ident][0]
        missing = [l for l in lines if l[1] in ("ITEM", "HIDDEN") and int(l[3]) not in items]
        print("%s rev %d: %d descriptors, %d ranges, %d bytes masked outside free space; %d field items (%d unmapped), "
              "%d trades, %d statics" % (key[0], key[1], len(descs), len(ranges),
                                         sum(z - x for x, z in ranges if not any(dx <= x and z <= dz for dx, dz in dump_ranges)),
                                         len(items), len(missing), len(trade_rows), len(mons)))
        games[key] = dict(rom=rom, end=end, descs=descs, ranges=ranges, fold=fold, sha1=sha1, retail_sha1=retail_sha1,
                          crc=zlib.crc32(rom.d), items=items, trades=trade_rows, mons=mons,
                          base=rom.ptr(0x1BC), moves=rom.ptr(0x1CC), chart=e["v"]["TypeEffectivenessOffset"],
                          trade_table=e["v"]["TradeTableOffset"])
    if check:
        return run_check(games, check)
    write(games)


def run_check(games, folder):
    bad = 0
    files = sorted(p for p in Path(folder).iterdir() if p.suffix == ".gba")
    for p in files:
        d = p.read_bytes()
        key = (d[0xAC:0xB0].decode(errors="replace"), d[0xBC])
        g = games.get(key)
        if not g:
            continue
        r = g["rom"].d
        inside = bytearray(len(r))
        for x, z in g["ranges"]:
            inside[x:z] = b"\x01" * (z - x)
        folded = bytearray(len(r))
        for x, n in g["fold"]:
            folded[x:x + n] = b"\x01" * n
        outside = [i for i in range(min(len(r), g["end"])) if d[i] != r[i] and not inside[i]
                   and not (folded[i] and (d[i] - 0x1A if 0xD5 <= d[i] <= 0xEE else d[i]) == r[i])]
        ok = hashlib.sha1(normalized(d, g["end"], g["ranges"], g["fold"])).hexdigest() == g["sha1"] and len(d) == len(r)
        if outside or not ok:
            bad += 1
            print("MISS %s: %d bytes outside the mask (first %s), hash %s" % (
                p.name, len(outside), [hex(x) for x in outside[:8]], "ok" if ok else "differs"))
    print("checked %d ROMs: %s" % (len(files), "all match" if not bad else "%d don't" % bad))
    sys.exit(1 if bad else 0)


def write(games):
    rows = []
    for (code, rev), g in sorted(games.items()):
        rows.append("""    RandomizedRetailRom(
        code = "%s", rev = %d, end = 0x%X,
        sha1 = "%s",
        retailSha1 = "%s", retailCrc = 0x%08X.toInt(),
        baseStats = 0x%08XL, battleMoves = 0x%08XL, typeChart = 0x%08XL, trades = 0x%08XL,
        mask =
%s,
        fold = "%s",
        fieldItems = "%s",
        tradeRows = "%s",
        statics = "%s",
    ),""" % (code, rev, g["end"], g["sha1"], g["retail_sha1"], g["crc"], 0x08000000 + g["base"], 0x08000000 + g["moves"],
             0x08000000 + g["chart"], 0x08000000 + g["trade_table"], chunked(encode(g["descs"])),
             ",".join("%x:%x" % (x, n) for x, n in g["fold"]),
             ",".join("%x:%x" % (f, o) for f, o in sorted(g["items"].items())),
             ",".join("%s:%d" % kv for kv in sorted(g["trades"].items())),
             ",".join("%x:%x" % (s, o) for s, o in sorted(g["mons"].items()))))
    OUT.write_text("""package com.pokedaisy.app.companion.data

// Generated by scripts/gen_randomized_retail.py from the retail ROMs and Universal Pokemon Randomizer ZX %s's
// offsets and patches - don't edit. Offsets and hashes only: no ROM bytes. Hex: mask = start:len[:count:stride],
// fold = start:len, fieldItems = flag:offset, tradeRows = species/wants:row, statics = species:offset.

val RANDOMIZED_RETAIL = listOf(
%s
)
""" % (UPR_VERSION, "\n".join(rows)))
    print("wrote", OUT.relative_to(ROOT))


if __name__ == "__main__":
    main()
