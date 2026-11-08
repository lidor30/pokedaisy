#!/usr/bin/env python3
"""Ports the companion to the other-language releases of FireRed, LeafGreen,
Ruby and Sapphire, from the ROMs the user owns (nothing is downloaded).

    scripts/port_retail.py <roms dir> [--only CODE[,CODE]] [--no-write]

For every ROM under <roms dir> whose game code is one of those games in a
language other than English (and for English Ruby / Sapphire rev 0, which have
no config of their own), it

  1. picks the English ROM (in the same dir) whose code is most alike,
  2. maps every address of that English config into it - RAM globals and ROM
     tables through the literal pools of the code that loads them (the word at
     the same place in the language's code), battle handlers by their code
     bytes, then fallbacks for what those miss (BL-masked code search near the
     other handlers' shift; a validated content search for data tables),
  3. detects the record layouts that changed (Japanese has shorter names and
     records: dex entries, trainers, items) from known values - BULBASAUR's
     height / weight, item ids 1, 2, 3..., the probe trainer's party,
  4. reads the game's names (species, moves, items, natures, map sections) and
     the strings the configs probe (dex category, trainer); item descriptions
     aren't written (the app reads them from the player's ROM: itemDescs),
  5. writes gamedata/gen3/ports/<game>.toml (one config per ROM, by game code +
     revision) and runs scripts/gen_gamedata.py, app/.../companion/data/
     RetailPortsGen.kt (titles by SHA1, names by code) and GameText<CODE>Gen.kt
     per game code, plus build/ports/ports.json for scripts/verify_ports.py,
     which boots each ROM headlessly with the English save and checks it.

Every address carries how it was found ("lit 12" = 12 literal-pool votes,
"code" = unique code bytes, "masked" / "content" = a fallback) in the JSON.
"""
import collections
import hashlib
import json
import os
import re
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
DATA = os.path.join(ROOT, "app", "src", "main", "kotlin", "com", "pokedaisy", "app", "companion", "data")
OUT_JSON = os.path.join(ROOT, "build", "ports", "ports.json")

sys.path.insert(0, HERE)
from gen_emerald_lang_tables import CHARS, JAPANESE, LANG_CHARS  # noqa: E402

B = 0x08000000
# Argument bytes after an 0xFC control code, by code (charmap.txt's EXT_CTRL_CODE_*; Gen3Text's FC_ARGS).
FC_ARGS = [0, 1, 1, 1, 3, 1, 1, 0, 1, 0, 0, 2, 1, 1, 1, 0, 2, 1, 1, 1, 1, 0, 0, 0, 0]

# English references: game code + revision -> ROM file stem (in the roms dir, as
# No-Intro names it) and the Kotlin configs that hold its addresses. FireRed rev 0
# has no dex / guide tables of its own: rev 1's are mapped from rev 1's ROM.
ENGLISH = {
    "BPRE0": ("Pokemon - FireRed Version (USA)", "NATIVE_FIRERED_REV0", ("BPRE1", "POKEDEX_FIRERED_REV1", "GUIDE_TABLES_FIRERED_REV1")),
    "BPRE1": ("Pokemon - FireRed Version (USA, Europe) (Rev 1)", "NATIVE_FIRERED_REV1", ("BPRE1", "POKEDEX_FIRERED_REV1", "GUIDE_TABLES_FIRERED_REV1")),
    "BPGE0": ("Pokemon - LeafGreen Version (USA)", "NATIVE_LEAFGREEN_REV0", ("BPGE0", "POKEDEX_LEAFGREEN_REV0", "GUIDE_TABLES_LEAFGREEN_REV0")),
    "BPGE1": ("Pokemon - LeafGreen Version (USA, Europe) (Rev 1)", "NATIVE_LEAFGREEN_REV1", ("BPGE1", "POKEDEX_LEAFGREEN_REV1", "GUIDE_TABLES_LEAFGREEN_REV1")),
    "AXVE1": ("Pokemon - Ruby Version (USA, Europe) (Rev 1)", "NATIVE_RUBY", ("AXVE1", "POKEDEX_RUBY", "GUIDE_TABLES_RUBY")),
    "AXVE2": ("Pokemon - Ruby Version (USA, Europe) (Rev 2)", "NATIVE_RUBY", ("AXVE2", "POKEDEX_RUBY", "GUIDE_TABLES_RUBY")),
    "AXPE1": ("Pokemon - Sapphire Version (USA, Europe) (Rev 1)", "NATIVE_SAPPHIRE", ("AXPE1", "POKEDEX_SAPPHIRE", "GUIDE_TABLES_SAPPHIRE")),
    "AXPE2": ("Pokemon - Sapphire Version (USA, Europe) (Rev 2)", "NATIVE_SAPPHIRE", ("AXPE2", "POKEDEX_SAPPHIRE", "GUIDE_TABLES_SAPPHIRE")),
}

# Addresses no config holds (the names, the scripted battle's entry, the POKéMON
# switch's party menu), in one English ROM per family; mapped like the rest.
# FireRed rev 1: the pinned (patched) pokefirered build's symbols, found in the retail
# ROM by their bytes / literal pools. Ruby rev 1: pokeruby builds it byte for byte.
ANCHORS = {
    "FR": ("BPRE1", dict(species=0x08245F50, moves=0x08247104, items=0x083DB098, natures=0x08463EC0,
                         mapnames=0x083F1D1C, small_widths=0x081EEF70, script=0x08069AF8, party_menu=0x0203B0A0,
                         storage=0x03005010)),
    "RS": ("AXVE1", dict(species=0x081F7184, moves=0x081F8338, items=0x083C5580, natures=0x083C1020,
                         mapsecs=0x083E73E0, script=0x080655D8)),
}
# The party menu's art a language redraws (its HP label, status abbreviations, font): FireRed
# rev 1's gPartyMenuBg_Gfx / gStatusGfx_Icons (LZ77) and sFontSmallLatinGlyphs (raw, 0x4000).
FR_ART = dict(party_bg=0x08E82700, status=0x08E82EA0, font=0x081EAF70)
FAMILY = {"BPR": "FR", "BPG": "FR", "AXV": "RS", "AXP": "RS"}
# Battle handlers the code / masked-code search misses, by game code: gBattlerControllerFuncs[0] read in a scripted wild
# battle after FIGHT opened (Japanese Sapphire rev 1 on the English save; the same bytes in every Japanese R / S build).
HEADLESS = {
    "AXVJ": dict(handleInputChooseMove=0x080298FC),
    "AXPJ": dict(handleInputChooseMove=0x080298FC),
}
FR_MAPSEC_START = 0x58  # sMapNames[0] = MAPSEC_PALLET_TOWN
FR_MAPSEC_COUNT = 109
RS_MAPSEC_COUNT = 88
NUM_SPECIES, MOVES_COUNT, NUM_NATURES = 412, 355, 25
ITEMS_COUNT = {"FR": 375, "RS": 348}

TITLES = {
    "BPRD": "Pokémon Feuerrote Edition", "BPRF": "Pokémon Version Rouge Feu", "BPRI": "Pokémon Versione Rosso Fuoco",
    "BPRS": "Pokémon Edición Rojo Fuego", "BPRJ": "ポケットモンスター ファイアレッド",
    "BPGD": "Pokémon Blattgrüne Edition", "BPGF": "Pokémon Version Vert Feuille", "BPGI": "Pokémon Versione Verde Foglia",
    "BPGS": "Pokémon Edición Verde Hoja", "BPGJ": "ポケットモンスター リーフグリーン",
    "AXVD": "Pokémon Rubin-Edition", "AXVF": "Pokémon Version Rubis", "AXVI": "Pokémon Versione Rubino",
    "AXVS": "Pokémon Edición Rubí", "AXVJ": "ポケットモンスター ルビー", "AXVE": "Pokémon Ruby",
    "AXPD": "Pokémon Saphir-Edition", "AXPF": "Pokémon Version Saphir", "AXPI": "Pokémon Versione Zaffiro",
    "AXPS": "Pokémon Edición Zafiro", "AXPJ": "ポケットモンスター サファイア", "AXPE": "Pokémon Sapphire",
}
LANG_KEY = {"D": "de", "F": "fr", "I": "it", "S": "es", "J": "ja", "E": "en"}

# The dex page's wording, as each language's Emerald prints it (headless screenshots).
DEX_WORDING = {
    "E": {}, "D": dict(categorySuffix="", metric=True), "F": dict(categorySuffix="", metric=True),
    "S": dict(categoryPrefix="POKéMON", categorySuffix="", metric=True),
    "I": dict(categoryPrefix="POKéMON", categorySuffix="", metric=True),
    "J": dict(categorySuffix="ポケモン", categorySeparator="", metric=True, decimalPoint=".", unitSpace=False),
}


# --- Kotlin configs ---------------------------------------------------------------
def kotlin_blocks():
    out = {}
    for f in ("NativeConfigsGen.kt", "GameDataRecordsGen.kt", "Pokedex.kt", "GuideRom.kt"):
        s = open(os.path.join(DATA, f), encoding="utf-8").read()
        # `val X = Cls(` ... `)`, or the records' `val X: Cls by lazy {` + the same, indented (one-line vals have no block)
        for m in re.finditer(r"^val (\w+)(?:: \w+ by lazy \{\n    | = )(?:(\w+)\.copy\(|\w+\()\n(.*?)^(?:    )?\)", s, re.S | re.M):
            body = re.sub(r"//[^\n]*", "", m.group(3))
            out[m.group(1)] = (m.group(2), {k: int(v, 16) for k, v in re.findall(r"(\w+) = (0x[0-9A-Fa-f]+)L?", body)},
                               {k: int(v) for k, v in re.findall(r"(\w+) = (\d+)\b,", body)})
    return out


BLOCKS = kotlin_blocks()


def resolve(name):
    base, hexes, ints = BLOCKS[name]
    h, i = resolve(base) if base else ({}, {})
    return {**h, **hexes}, {**i, **ints}


# --- the ROM ----------------------------------------------------------------------
class Rom:
    def __init__(self, path):
        self.path = path
        self.d = open(path, "rb").read()
        self.code = self.d[0xAC:0xB0].decode("ascii", "replace")
        self.rev = self.d[0xBC]
        self.sha1 = hashlib.sha1(self.d).hexdigest()
        self.lang = self.code[3]
        # Ruby / Sapphire share Emerald's per-language POKéBLOCK glyphs (LANG_CHARS).
        self.chars = JAPANESE if self.lang == "J" else {**CHARS, **LANG_CHARS.get(LANG_KEY.get(self.lang), {})}
        self.code_end = min(len(self.d), 0x400000)
        self._words = None
        self._ctx = None

    def u32(self, a):
        return struct.unpack_from("<I", self.d, a - B)[0]

    def u16(self, a):
        return struct.unpack_from("<H", self.d, a - B)[0]

    def u8(self, a):
        return self.d[a - B]

    def is_ptr(self, v):
        return B <= v < B + len(self.d)

    def text(self, a, maxlen, strict=True):
        out = []
        skip = 0
        for b in self.d[a - B:a - B + maxlen]:
            if skip > 0:
                skip -= 1
                continue
            if b == 0xFF:
                break
            if b == 0xFD:
                skip = 1
                continue
            if b == 0xFC:  # a control code (colour, font, spacing) and its arguments
                skip = -1
                continue
            if skip == -1:
                skip = FC_ARGS[b] if b < len(FC_ARGS) else 0
                continue
            if b in (0xFA, 0xFB, 0xFE):
                out.append("　" if self.lang == "J" else " ")
                continue
            if b not in self.chars:
                if strict:
                    return None
                out.append("?")
                continue
            out.append(self.chars[b])
        s = "".join(out)
        return s.strip() if self.lang == "J" else " ".join(s.split())

    def words(self):
        """Every aligned word in the code region, by value (literal pools)."""
        if self._words is None:
            w = collections.defaultdict(list)
            for i in range(0, self.code_end - 3, 4):
                w[self.d[i:i + 4]].append(i)
            self._words = w
        return self._words

    def contexts(self):
        """The 16 bytes before each aligned word in the code region -> its offset, if unique."""
        if self._ctx is None:
            c = {}
            for i in range(16, self.code_end - 3, 4):
                k = self.d[i - 16:i]
                c[k] = -1 if k in c else i
            self._ctx = c
        return self._ctx


def map_literal(src, dst, v):
    """v's literal-pool words in src, read at the same place in dst: (value, votes). A value
    the configs keep a few bytes into a table (speciesToNational: table + 2) is looked for
    as the table's aligned address, and the offset added back."""
    for back in (0, 1, 2, 3) if v & 3 else (0,):
        val, n = _map_literal(src, dst, v - back)
        if val is not None:
            return val + back, n
    return None, 0


def _map_literal(src, dst, v):
    votes = collections.Counter()
    ctx = dst.contexts()
    for i in src.words().get(struct.pack("<I", v), [])[:60]:
        j = ctx.get(src.d[i - 16:i], -1)
        if j < 0:
            # after the word instead (the code before it may have changed)
            after = src.d[i + 4:i + 24]
            k = dst.d.find(after, 0, dst.code_end)
            if k < 0 or dst.d.find(after, k + 1, dst.code_end) >= 0:
                continue
            j = k - 4
        votes[struct.unpack_from("<I", dst.d, j)[0]] += 1
    if not votes:
        return None, 0
    (val, n), = votes.most_common(1)
    return val, n


def map_code(src, dst, a):
    off = (a & ~1) - B
    for n in (32, 24, 16):
        w = src.d[off:off + n]
        k = dst.d.find(w, 0, dst.code_end)
        if k >= 0 and dst.d.find(w, k + 1, dst.code_end) < 0:
            return k + B + (a & 1)
    return None


def masked(data, off, n):
    """Thumb code with BL targets (and literal-pool loads' offsets) blanked."""
    out = bytearray(data[off:off + n])
    i = 0
    while i + 3 < n:
        hi = out[i + 1]
        if (hi & 0xF8) == 0xF0 and (out[i + 3] & 0xF8) == 0xF8:  # BL pair
            out[i:i + 4] = b"\0\0\0\0"
            i += 4
            continue
        if (hi & 0xF8) == 0x48:  # ldr rX, [pc, #imm]
            out[i] = 0
        i += 2
    return bytes(out)


def map_code_masked(src, dst, a, guess, radius=0x2000):
    """The function at a, searched for around guess with BL targets blanked."""
    off = (a & ~1) - B
    want = masked(src.d, off, 48)
    best = None
    for k in range(max(0, guess - B - radius) & ~1, min(dst.code_end, guess - B + radius), 2):
        if dst.d[k] != src.d[off] and want[0] != 0:
            continue
        got = masked(dst.d, k, 48)
        score = sum(1 for x, y in zip(want, got) if x == y)
        if best is None or score > best[0]:
            best = (score, k)
    if best and best[0] >= 44:
        return best[1] + B + (a & 1)
    return None


# HARDY in each language (its Emerald's natures): what a nature table found by shape must start with.
FIRST_NATURE = {"E": "HARDY", "D": "ROBUST", "F": "HARDI", "I": "ARDITA", "S": "FUERTE", "J": "がんばりや"}


def find_pointer_table(rom, count, mapped, english, key="natures", maxlen=12, radius=0x40000):
    """A table of [count] pointers to short names (the natures, the map names), nearest to
    where the other anchors' shift puts English's."""
    shifts = [v - english[k] for k, (v, _) in mapped.items() if v is not None and english[k] >= B and k != key]
    guess = english[key] + (sorted(shifts)[len(shifts) // 2] if shifts else 0)
    best = None
    for off in range(max(0, guess - B - radius) & ~3, min(len(rom.d) - 4 * count, guess - B + radius), 4):
        if not B <= struct.unpack_from("<I", rom.d, off)[0] < B + len(rom.d):
            continue
        ptrs = struct.unpack_from(f"<{count}I", rom.d, off)
        if not all(B <= q < B + len(rom.d) for q in ptrs):
            continue
        if len(set(ptrs)) < count * 3 // 4 or not all((t := rom.text(q, 40)) is not None and len(t) <= maxlen for q in ptrs):
            continue
        if key == "natures" and rom.text(ptrs[0], 20) != FIRST_NATURE[rom.lang]:
            continue
        if best is None or abs(off + B - guess) < abs(best - guess):
            best = off + B
    return best


def find_table_by_pointees(src, dst, table, guess, entries=4, stride=8):
    """A table of pointers to language-neutral data (icons): the place in dst whose words
    point at the same bytes English's first entries point at, nearest to guess."""
    want = []
    for k in range(entries * stride // 4):
        q = src.u32(table + 4 * k)
        if not src.is_ptr(q):
            return None
        blob = src.d[q - B:q - B + 24]
        hits = [m.start() + B for m in re.finditer(re.escape(blob), dst.d)]
        if not hits:
            return None
        want.append(set(hits))
    cands = [m.start() + B for m in re.finditer(re.escape(b"".join(struct.pack("<I", h) for h in [min(want[0])])), dst.d)]
    cands = []
    for h0 in want[0]:
        for m in re.finditer(re.escape(struct.pack("<I", h0)), dst.d):
            x = m.start() + B
            if x % 4 == 0 and all(dst.u32(x + 4 * k) in want[k] for k in range(len(want))):
                cands.append(x)
    return min(cands, key=lambda x: abs(x - guess), default=None)


def find_name_run(rom, guess, strides, count, radius=0x40000):
    """The table of [count]+1 fixed-size names nearest to guess (entry 0 may be a placeholder)."""
    d = rom.d
    lo, hi = max(0, guess - B - radius), min(len(d) - 16 * (count + 1), guess - B + radius)
    for dist in range(0, radius):
        for x in (guess - B - dist, guess - B + dist) if dist else (guess - B,):
            if not lo <= x < hi:
                continue
            for st in strides:
                if all(d[x + st * k + st - 1] in (0x00, 0xFF) for k in range(1, 6)) and \
                        all((t := rom.text(x + B + st * i, st)) and len(t) >= 2 for i in range(1, count + 1)):
                    return x + B
    return None


def lz77(d, off):
    """GBA LZ77 at off -> decoded bytes, or None."""
    if off < 0 or off + 4 > len(d) or d[off] != 0x10:
        return None
    size = d[off + 1] | d[off + 2] << 8 | d[off + 3] << 16
    if not 0 < size <= 0x10000:
        return None
    out, p = bytearray(), off + 4
    try:
        while len(out) < size:
            flags = d[p]
            p += 1
            for b in range(8):
                if len(out) >= size:
                    break
                if flags & (0x80 >> b):
                    x = d[p] << 8 | d[p + 1]
                    p += 2
                    back = (x & 0xFFF) + 1
                    if back > len(out):
                        return None
                    for _ in range((x >> 12) + 3):
                        out.append(out[-back])
                else:
                    out.append(d[p])
                    p += 1
    except IndexError:
        return None
    return bytes(out)


def find_similar_lz(src, dst, a, radius=0x80000):
    """The LZ77 blob in dst most like src's at a (same decoded size), near the same offset."""
    want = lz77(src.d, a - B)
    if want is None:
        return None
    best = None
    lo, hi = max(0, a - B - radius) & ~3, min(len(dst.d), a - B + radius)
    for off in range(lo, hi, 4):
        if dst.d[off] != 0x10 or (dst.d[off + 1] | dst.d[off + 2] << 8 | dst.d[off + 3] << 16) != len(want):
            continue
        got = lz77(dst.d, off)
        if got is None:
            continue
        same = sum(1 for x, y in zip(got, want) if x == y)
        if best is None or same > best[0]:
            best = (same, off)
    return (best[1] + B, best[0], len(want)) if best and best[0] > len(want) * 3 // 4 else None


# --- layouts ------------------------------------------------------------------------
def valid_names(rom, base, stride, ids):
    names = [rom.text(base + stride * i, stride) for i in ids]
    return all(n for n in names), names


def detect_stride(rom, base, options, ids=(1, 2, 3, 4, 5)):
    for s in options:
        ok, _ = valid_names(rom, base, s, ids)
        if ok:
            return s
    return None


def detect_items(rom, guess):
    """(table, stride, name length) by item ids 1..8 in consecutive records."""
    for stride in (44, 40):
        for name_len in (14, 10):
            for base in ([guess] if guess else []):
                if all(rom.is_ptr(base) and rom.u16(base + stride * k + name_len) == k for k in range(1, 9)):
                    return base, stride, name_len
    # content: MASTER BALL's record has id 1, the next id 2, ...
    for stride, name_len in ((44, 14), (40, 10)):
        pat = re.compile(re.escape(b"\x01\x00"))
        for m in pat.finditer(rom.d):
            r = m.start() - name_len
            if r < 0 or r % 4:
                continue
            if all(r + stride * k + name_len + 2 <= len(rom.d) and rom.d[r + stride * k + name_len:r + stride * k + name_len + 2]
                   == struct.pack("<H", k + 1) for k in range(8)):
                return r - stride + B, stride, name_len
    return None, None, None


def detect_dex(rom, entries):
    """(stride, category length, height offset, description offset) from BULBASAUR / IVYSAUR (7 / 69, 10 / 130)."""
    for st in (0x24, 0x20, 0x1C):
        for ho in range(4, 0x10):
            if (rom.u16(entries + st + ho), rom.u16(entries + st + ho + 2), rom.u16(entries + 2 * st + ho),
                    rom.u16(entries + 2 * st + ho + 2)) == (7, 69, 10, 130):
                desc = next((o for o in range(ho + 4, st, 2) if rom.is_ptr(rom.u32(entries + st + o))), None)
                if desc is not None:
                    return st, ho, ho, desc
    return None


def detect_trainers(rom, table, probe):
    """(stride, name length, party size offset, party offset): the layout under which the probe
    and the 40 trainers after it all have a name, a party size 1-6 and a party pointer."""
    for st, name_len in ((0x28, 12), (0x20, 6)):
        for po in range(st - 4, 8, -4):
            ok = 0
            for t in range(probe, probe + 40):
                rec = table + st * t
                if rom.is_ptr(rom.u32(rec + po)) and 1 <= rom.u8(rec + po - 4) <= 6 and rom.text(rec + 4, name_len):
                    ok += 1
            if ok >= 36:
                return st, name_len, po - 4, po
    return None


# --- one port -----------------------------------------------------------------------
def nearest_english(rom, english):
    cands = [k for k in english if k[:3] == rom.code[:3]]
    def score(k):
        e = english[k].d
        return sum(1 for i in range(0x1000, 0x101000, 0x800) if e[i:i + 16] == rom.d[i:i + 16])
    return max(cands, key=score)


def port(rom, english):
    fam = FAMILY[rom.code[:3]]
    ref_key = nearest_english(rom, english)
    ref = english[ref_key]
    stem, native, (dex_key, dex_cfg, guide_cfg) = ENGLISH[ref_key]
    found, how = {}, {}

    def put(name, value, method):
        found[name] = value
        how[name] = method

    # 1. the native config: RAM and ROM data by literals, handlers by code
    hexes, _ = resolve(native)
    code_fields = {k: v for k, v in hexes.items() if k.startswith(("handleInput", "completeWhen", "waitForMon"))}
    for k, v in hexes.items():
        if not (0x02000000 <= v < 0x0A000000) or k in code_fields:
            continue
        val, n = map_literal(ref, rom, v)
        if val is not None:
            put(k, val, f"lit {n}")
    shifts = []
    for k, v in code_fields.items():
        a = map_code(ref, rom, v)
        if a is not None:
            put(k, a & ~1, "code")
            shifts.append((a & ~1) - v)
    # Icon tables (pointers to the same graphics in every language) by what they point at.
    data_shifts = [found[k] - hexes[k] for k in found if hexes[k] >= B and k not in code_fields]
    data_shift = sorted(data_shifts)[len(data_shifts) // 2] if data_shifts else 0
    for k in ("monIconTable", "itemIconTable", "monIconPaletteTable"):
        if k in hexes and k not in found:
            x = find_table_by_pointees(ref, rom, hexes[k], hexes[k] + data_shift, stride=8 if k == "itemIconTable" else 4)
            if x is not None:
                put(k, x, "content")
    shift = sorted(shifts)[len(shifts) // 2] if shifts else 0
    for k, v in code_fields.items():
        if k not in found:
            a = map_code_masked(ref, rom, v, v + shift)
            if a is not None:
                put(k, a & ~1, "masked")

    # What neither finds, read off the game headless (the code moved too much for the masked bytes).
    for k, v in HEADLESS.get(rom.code[:4], {}).items():
        if k in code_fields and k not in found:
            put(k, v, "headless")

    # struct Main's inBattle byte (Ruby / Sapphire's config has it): the offset is a literal
    # too - Japanese Ruby / Sapphire's Main is 4 bytes shorter than English's (0x439, not 0x43D).
    if "inBattleOff" in hexes:
        val, n = map_literal(ref, rom, hexes["inBattleOff"])
        if val is not None and 0x400 <= val < 0x480:
            put("inBattleOff", val, f"lit {n}")

    # 2. dex + guide tables, from the English ROM that holds them
    dex_src = english[dex_key]
    tables = {}
    for cfg in (dex_cfg, guide_cfg):
        h, ints = resolve(cfg)
        for k, v in h.items():
            if 0x08000000 <= v < 0x0A000000:
                val, n = map_literal(dex_src, rom, v)
                tables[(cfg, k)] = (val, f"lit {n}" if val is not None else "missing")
    # Tables the language didn't change (speciesToNational, ...) whose code did: their bytes,
    # nearest to where the other tables moved.
    shifts = [t[0] - h0 for (c, k), t in tables.items() if t[0] is not None
              for h0 in [resolve(c)[0][k]]]
    shift = sorted(shifts)[len(shifts) // 2] if shifts else 0
    for (c, k), (val, m) in list(tables.items()):
        if val is None:
            v = resolve(c)[0][k]
            # the longest run that's there: 32 bytes of 1, 2, 3... match other tables too
            for n in (400, 128, 32):
                blob = dex_src.d[v - B:v - B + n]
                hits = [x.start() + B for x in re.finditer(re.escape(blob), rom.d)]
                if hits:
                    tables[(c, k)] = (min(hits, key=lambda x: abs(x - (v + shift))), f"content {n}")
                    break
    # gAbilityNames: no literal pool of its own in some builds. Its shape instead: records of
    # 13 (8 in Japanese) bytes, each ending in a terminator / padding, 20 real names in a row -
    # the run nearest to where speciesInfo moved. (Entry 0 isn't fixed: "-------", Spanish "(?)".)
    dex_h, dex_i = resolve(dex_cfg)
    ab = tables[(dex_cfg, "abilityNames")]
    if not ab[1].startswith("lit "):
        a0 = dex_h["abilityNames"]
        guess = a0 + (tables[(dex_cfg, "speciesInfo")][0] or a0) - dex_h["speciesInfo"]
        tables[(dex_cfg, "abilityNames")] = (find_name_run(rom, guess, (13, 8), 20), "content")

    # 3. layouts
    entries = tables[(dex_cfg, "entries")][0]
    dex_layout = detect_dex(rom, entries) if entries else None
    guide_h, guide_i = resolve(guide_cfg)
    probe = guide_i.get("probeTrainer", 414)
    trainers = tables[(guide_cfg, "trainers")][0]
    tr_layout = detect_trainers(rom, trainers, probe) if trainers else None
    ability_len = detect_stride(rom, tables[(dex_cfg, "abilityNames")][0], (13, 8), ids=(1, 2, 3)) if tables[(dex_cfg, "abilityNames")][0] else None

    # 4. names + probes
    anchor_key, anchors = ANCHORS[fam]
    anchor_rom = english[anchor_key]
    a = {}
    for k, v in anchors.items():
        val, n = map_literal(anchor_rom, rom, v)
        if val is None and k == "script":
            val = map_code(anchor_rom, rom, v)
            n = "code"
        a[k] = (val, n)
    if a["natures"][0] is None:
        a["natures"] = (find_pointer_table(rom, NUM_NATURES, a, anchors), "content")
    if fam == "FR" and a["mapnames"][0] is None:
        a["mapnames"] = (find_pointer_table(rom, FR_MAPSEC_COUNT, a, anchors, "mapnames", maxlen=24), "content")
    species_stride = detect_stride(rom, a["species"][0], (11, 6)) if a["species"][0] else None
    move_stride = detect_stride(rom, a["moves"][0], (13, 8)) if a["moves"][0] else None
    items_base, item_stride, item_name_len = detect_items(rom, a["items"][0])
    names = read_names(rom, fam, a, species_stride, move_stride, items_base, item_stride, item_name_len)

    probe_name = None
    if tr_layout:
        st, nl, so, po = tr_layout
        probe_name = rom.text(trainers + st * probe + 4, nl)
    probe_category = None
    if dex_layout:
        st, cl, ho, do = dex_layout
        probe_category = rom.text(entries + st, cl)

    art = {}
    if fam == "FR" and rom.lang != "J":
        for k in ("party_bg", "status"):
            hit = find_similar_lz(anchor_rom, rom, FR_ART[k])
            if hit:
                art[k] = hit
        font, _ = map_literal(anchor_rom, rom, FR_ART["font"])
        if font:
            en_font = anchor_rom.d[FR_ART["font"] - B:FR_ART["font"] - B + 0x4000]
            art["font"] = (font, sum(1 for x, y in zip(rom.d[font - B:font - B + 0x4000], en_font) if x == y), 0x4000)

    return dict(
        art=art,
        key=f"{rom.code}{rom.rev}", code=rom.code, rev=rom.rev, sha1=rom.sha1, file=os.path.basename(rom.path),
        path=os.path.abspath(rom.path), english_path=os.path.abspath(ref.path), tables_path=os.path.abspath(dex_src.path),
        family=fam, english=ref_key, native=native, dex=dex_cfg, guide=guide_cfg,
        fields={k: [found[k], how[k]] for k in found}, missing=[k for k in hexes if 0x02000000 <= hexes[k] < 0x0A000000 and k not in found],
        tables={f"{c}.{k}": [v, m] for (c, k), (v, m) in tables.items()},
        dex_layout=dex_layout, trainer_layout=tr_layout, ability_len=ability_len,
        probe_name=probe_name, probe_category=probe_category, probe_trainer=probe,
        anchors={k: [v, str(n)] for k, (v, n) in a.items()},
        layout=dict(species=species_stride, moves=move_stride, items=[items_base, item_stride, item_name_len]),
        counts={k: len(v) for k, v in names.items() if isinstance(v, (dict, list))},
        names=names,
    )


def read_names(rom, fam, a, species_stride, move_stride, items_base, item_stride, item_name_len):
    out = {}
    if species_stride:
        sp = {i: rom.text(a["species"][0] + species_stride * i, species_stride) for i in range(1, NUM_SPECIES)}
        # unused slots: "?" ("(?)" in Spanish)
        out["species"] = {i: n for i, n in sp.items() if n and set(n) - set("?？¿() ")}
    if move_stride:
        out["moves"] = {i: n for i in range(1, MOVES_COUNT) if (n := rom.text(a["moves"][0] + move_stride * i, move_stride))}
    if items_base:
        items = {}
        for i in range(1, ITEMS_COUNT[fam]):
            r = items_base + item_stride * i
            n = rom.text(r, item_name_len)
            if n and set(n) not in ({"?"}, {"？"}):
                items[i] = n
        out["items"] = items
    if a["natures"][0]:
        out["natures"] = [rom.text(rom.u32(a["natures"][0] + 4 * i), 20) or "?" for i in range(NUM_NATURES)]
    if fam == "FR" and a["mapnames"][0]:
        out["mapsecs"] = {FR_MAPSEC_START + i: rom.text(rom.u32(a["mapnames"][0] + 4 * i), 30, strict=False)
                          for i in range(FR_MAPSEC_COUNT)}
    if fam == "RS" and a["mapsecs"][0]:
        out["mapsecs"] = {i: rom.text(rom.u32(a["mapsecs"][0] + 8 * i + 4), 30, strict=False) for i in range(RS_MAPSEC_COUNT)}
    if fam == "FR" and a.get("small_widths", (None,))[0]:
        out["small_widths"] = list(rom.d[a["small_widths"][0] - B:][:512])
    return out


# --- Kotlin -------------------------------------------------------------------------
def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def kmap(name, m):
    return (f"internal val {name}: Map<Int, String> by lazy {{\n    mapOf(\n" +
            "".join(f"        {i} to {kstr(n)},\n" for i, n in sorted(m.items())) + "    )\n}\n")


def write_text(p):
    code, n = p["code"], p["names"]
    sfx = code[0] + code[1:].lower()
    path = os.path.join(DATA, f"GameText{sfx}Gen.kt")
    widths = n.get("small_widths", [])
    with open(path, "w", encoding="utf-8") as f:
        f.write(f"package com.pokedaisy.app.companion.data\n\n"
                f"// Generated by scripts/port_retail.py from {p['file']}\n"
                f"// (sha1 {p['sha1'][:8]}...) - do not hand-edit. The game's own names, by English's ids.\n\n" +
                kmap(f"speciesNames{sfx}", n.get("species", {})) + "\n" + kmap(f"moveNames{sfx}", n.get("moves", {})) + "\n" +
                kmap(f"itemNames{sfx}", n.get("items", {})) + "\n" +
                f"internal val natureNames{sfx}: List<String> = listOf(\n" +
                "".join(f"    {kstr(x)},\n" for x in n.get("natures", [])) + ")\n\n" +
                kmap(f"mapSecNames{sfx}", {k: v for k, v in n.get("mapsecs", {}).items() if v}) + "\n" +
                f"internal val smallFontWidths{sfx}: IntArray by lazy {{ intArrayOf({', '.join(map(str, widths))}) }}\n")
    return sfx


def tstr(s):
    """A TOML string (JSON's escapes are TOML's)."""
    return json.dumps(s, ensure_ascii=False)


def config_key(native):
    """NATIVE_SAPPHIRE -> sapphire, its key in gamedata/."""
    return native[len("NATIVE_"):].lower()


def toml_config(p):
    """The port as a gamedata/ config: English's config + every address mapped, its dex / guide tables spelled out."""
    lang = p["code"][3]
    out = [f"[config.port_{p['key'].lower()}]", f"base = '{config_key(p['native'])}'", "private = true", "lazy = true",
           f"port = '{p['key']}'", f"language = '{lang}'"]
    if lang != "E":  # English rev 0 has no names of its own
        out.append(f"gameCode = '{p['code']}'")
    out.append("trainerCard = 'none'")
    for k, (v, _) in sorted(p["fields"].items()):
        out.append(f"{k} = 0x{v:X}" if k == "inBattleOff" else f"{k} = 0x{v:08X}")
    items_base, item_stride, _ = p["layout"]["items"]
    if items_base:  # the item descriptions (RomItemText): Japanese records are 40 bytes
        count = ", count = { ref = 'RUBY_SAPPHIRE_ITEMS' }" if p["family"] == "RS" else ""
        out.append(f"itemDescs = {{ preset = '{'japanese' if item_stride == 40 else 'vanilla'}', base = 0x{items_base:08X}{count} }}")
    else:
        out.append("itemDescs = 'none'")
    if p["anchors"].get("party_menu", [None])[0]:
        out.append(f"partyMenu = 0x{p['anchors']['party_menu'][0]:08X}")
    # BOXES: gPokemonStoragePtr (FireRed's family: no English config holds it - the hacks share those) or the English
    # config's own, mapped (LeafGreen's, Ruby / Sapphire's gPokemonStorage); 0 where neither mapped, never English's.
    if "pokemonStoragePtr" not in p["fields"]:
        storage = p["anchors"].get("storage", [None])[0]
        out.append(f"pokemonStoragePtr = 0x{storage or 0:08X}")
    dex = [f"{k.split('.')[1]} = 0x{v:08X}" for k, (v, m) in sorted(p["tables"].items()) if k.startswith(p["dex"] + ".") and v]
    if p["dex_layout"]:
        st, cl, ho, do = p["dex_layout"]
        dex += [f"entryStride = 0x{st:X}", f"entryCategoryLen = {cl}", f"entryHeightOff = {ho}", f"entryDescOff = 0x{do:X}"]
        if p["family"] == "RS":  # Ruby / Sapphire's text has a second page, the pointer after the first
            dex.append(f"descriptionPage2Off = 0x{do + 4:X}")
    if p["ability_len"]:
        dex.append(f"abilityNameLength = {p['ability_len']}")
    if p["probe_category"]:
        dex.append(f"probeCategory = {tstr(p['probe_category'])}")
    for k, v in DEX_WORDING[lang].items():
        dex.append(f"{k} = " + (tstr(v) if isinstance(v, str) else str(v).lower()))
    guide = [f"{k.split('.')[1]} = 0x{v:08X}" for k, (v, m) in sorted(p["tables"].items()) if k.startswith(p["guide"] + ".") and v]
    if p["trainer_layout"]:
        st, nl, so, po = p["trainer_layout"]
        guide += [f"trainerStride = 0x{st:X}", f"trainerNameLen = {nl}", f"trainerSizeOff = 0x{so:X}", f"trainerPartyOff = 0x{po:X}"]
    if p["probe_name"]:
        guide.append(f"probeName = {tstr(p['probe_name'])}")
    out += ["", f"[config.port_{p['key'].lower()}.pokedex]", f"base = '{p['dex']}'"] + dex
    out += ["", f"[config.port_{p['key'].lower()}.guideTables]", f"base = '{p['guide']}'"] + guide
    return "\n".join(out)


# One gamedata/ file per game, by the game code's first three letters.
PORT_FILES = {"BPR": "firered", "BPG": "leafgreen", "AXV": "ruby", "AXP": "sapphire"}


def write_ports(ports):
    by_file = collections.defaultdict(list)
    for p in sorted(ports, key=lambda p: p["key"]):
        by_file[PORT_FILES[p["code"][:3]]].append(p)
    ports_dir = os.path.join(ROOT, "gamedata", "gen3", "ports")
    os.makedirs(ports_dir, exist_ok=True)
    for name, ps in by_file.items():
        with open(os.path.join(ports_dir, name + ".toml"), "w", encoding="utf-8") as f:
            f.write("# Generated by scripts/port_retail.py from the other-language ROMs - don't edit by hand; rerun it.\n"
                    "# Each config is English's with every address mapped into that ROM (build/ports/ports.json says\n"
                    "# how each one was found), checked by scripts/verify_ports.py. RETAIL_PORTS lists them by `port`.\n" +
                    "".join(f"\n# {p['file']} (sha1 {p['sha1'][:8]}...): English {p['english']}'s config, mapped.\n"
                            + toml_config(p) + "\n" for p in ps))
    path = os.path.join(DATA, "RetailPortsGen.kt")
    texts = sorted({p["code"] for p in ports if p["code"][3] != "E"})
    with open(path, "w", encoding="utf-8") as f:
        f.write("package com.pokedaisy.app.companion.data\n\n"
                "// Generated by scripts/port_retail.py from the other-language FireRed / LeafGreen /\n"
                "// Ruby / Sapphire ROMs - do not hand-edit; rerun the script instead. The ported configs\n"
                "// themselves are in gamedata/gen3/ports/ (RETAIL_PORTS: NativeConfigsGen.kt).\n\n"
                "/** Their games' own titles, by SHA1 (GameTitles). */\n"
                "val RETAIL_PORT_TITLES: Map<String, String> = mapOf(\n" +
                "".join(f"    \"{p['sha1']}\" to {kstr(TITLES[p['code']])},\n" for p in sorted(ports, key=lambda p: p["key"])) + ")\n\n"
                "/** Their game codes' titles (GameInfo). */\n"
                "val RETAIL_PORT_CODE_TITLES: Map<String, String> = mapOf(\n" +
                "".join(f"    \"{c}\" to {kstr(TITLES[c])},\n" for c in sorted({p['code'] for p in ports})) + ")\n\n"
                "/** Each language's names, by game code; English's map rectangles under them. */\n"
                "internal val RETAIL_PORT_TEXTS: Map<String, () -> GameText> = mapOf(\n" +
                "".join(f"    \"{c}\" to {{ {text_ctor(c)} }},\n" for c in texts) + ")\n")


def text_ctor(code):
    sfx = code[0] + code[1:].lower()
    base = "mapSecData" if FAMILY[code[:3]] == "FR" else "mapSecDataEmerald"
    return (f"GameText({{ speciesNames{sfx} }}, {{ moveNames{sfx} }}, {{ itemNames{sfx} }}, "
            f"natureNames{sfx}, {{ mapSecNames{sfx} }}, {{ smallFontWidths{sfx} }}, {{ {base} }})")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        sys.exit(__doc__)
    roms_dir = args[0]
    only = None
    if "--only" in sys.argv:
        only = set(sys.argv[sys.argv.index("--only") + 1].split(","))
    paths = [os.path.join(dp, f) for dp, _, fs in os.walk(roms_dir) for f in fs if f.endswith(".gba")]
    english, targets = {}, []
    by_stem = {os.path.splitext(os.path.basename(p))[0]: p for p in paths}
    for k, (stem, _, _) in ENGLISH.items():
        if stem not in by_stem:
            sys.exit(f"English reference missing: {stem}.gba")
        english[k] = Rom(by_stem[stem])
    for p in sorted(paths):
        name = os.path.basename(p)
        if any(x in name for x in ("Pirate", "Debug", "Pokemon Box")):
            continue
        r = Rom(p)
        if r.code[:3] not in FAMILY:
            continue
        english_rev0 = r.lang == "E" and r.code[:3] in ("AXV", "AXP") and r.rev == 0
        if r.lang == "E" and not english_rev0:
            continue
        if only and r.code not in only and f"{r.code}{r.rev}" not in only:
            continue
        targets.append(r)
    ports = []
    for r in targets:
        try:
            p = port(r, english)
        except Exception as e:  # report it, go on with the rest
            print(f"{r.code}{r.rev:<2} {os.path.basename(r.path)[:44]:44} FAILED: {type(e).__name__}: {e}")
            continue
        ports.append(p)
        miss = p["missing"] + [k for k, (v, m) in p["tables"].items() if v is None]
        print(f"{p['key']:6} {p['file'][:44]:44} ref {p['english']}  fields {len(p['fields'])}  "
              f"dex {p['dex_layout']} trainers {p['trainer_layout']} probe {p['probe_name']!r}/{p['probe_category']!r}  "
              f"names {p['counts']}  missing {miss}")
    # The English references the ports compare against: their own script / party menu anchors.
    refs = {}
    for k in sorted({p["english"] for p in ports}):
        fam = FAMILY[k[:3]]
        anchor_key, anchors = ANCHORS[fam]
        e = english[k]
        hexes, _ = resolve(ENGLISH[k][1])
        ra = {}
        for name in ("script", "party_menu"):
            if name in anchors:
                v = anchors[name] if k == anchor_key else (map_literal(english[anchor_key], e, anchors[name])[0]
                                                            if name == "party_menu" else map_code(english[anchor_key], e, anchors[name]))
                ra[name] = v
        refs[k] = dict(path=os.path.abspath(e.path), native=ENGLISH[k][1], anchors=ra,
                       fields={n: hexes[n] for n in hexes if n.startswith(("handleInput", "completeWhen", "waitForMon", "battlerController"))})
    os.makedirs(os.path.dirname(OUT_JSON), exist_ok=True)
    with open(OUT_JSON, "w", encoding="utf-8") as f:
        json.dump(ports, f, ensure_ascii=False, indent=1)
    with open(os.path.join(os.path.dirname(OUT_JSON), "english.json"), "w", encoding="utf-8") as f:
        json.dump(refs, f, indent=1)
    if "--no-write" not in sys.argv:
        for p in ports:
            if p["code"][3] != "E":
                write_text(p)
        write_ports(ports)
        subprocess.run([sys.executable, os.path.join(HERE, "gen_gamedata.py")], check=True)
        print("wrote gamedata/gen3/ports/ + RetailPortsGen.kt +", len({p['code'] for p in ports}), "GameText files;", OUT_JSON)


if __name__ == "__main__":
    main()
