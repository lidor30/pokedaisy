#!/usr/bin/env python3
"""AREA maps' per-ROM tables (AreaMap.kt; docs/DEVELOPMENT.md's "AREA maps").

    python3 scripts/gen_area_maps.py --scan <folder of GBA ROMs> [--report DIR]
        Finds each ROM's map tables and writes gamedata/gen3/area_maps.toml (one [rom.<crc>] per ROM, by CRC32).
        gMapGroups (and Unbound's XOR) and the engine's numbers come from EXPAND's rows (pk_wide_games.h) where the ROM
        has one, else from a scan of the ROM for the table; the rest is read from the ROM: each group's size, and where
        the secondary tileset's tiles start (the split that keeps every metatile's tiles inside its tileset's data).
        --report DIR draws a few indoor maps of each ROM there (PNG) to look at.
    python3 scripts/gen_area_maps.py [--check]
        Writes (or checks) AreaMapTablesGen.kt from gamedata/gen3/area_maps.toml: AREA_MAP_TABLES, by ROM CRC32.

Facts only (addresses, counts), nothing from the games' data.
"""
import argparse, collections, json, os, re, struct, sys, zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOML = os.path.join(ROOT, 'gamedata/gen3/area_maps.toml')
KOTLIN = os.path.join(ROOT, 'app/src/main/kotlin/com/pokedaisy/app/companion/data/AreaMapTablesGen.kt')
WIDE = os.path.join(ROOT, 'app/src/main/cpp/pk_wide_games.h')

# pk_wide.h's families: (primary metatiles, primary palettes, floor byte or -1).
FAMILIES = {
    'PK_WIDE_FAMILY_FR': (640, 7, 0x1A),
    'PK_WIDE_FAMILY_FR_EX': (640, 7, 0x1A),
    'PK_WIDE_FAMILY_EM': (512, 6, -1),
    'PK_WIDE_FAMILY_RS': (512, 6, -1),
    'PK_WIDE_FAMILY_RS_JA': (512, 6, -1),
    'PK_WIDE_FAMILY_EX': (512, 6, -1),
    'PK_WIDE_FAMILY_MM': (512, 6, -1),
    'PK_WIDE_FAMILY_EX32': (512, 6, -1),
}
# pk_wide.h's PK_WIDE_* indexes the generator reads.
A_MAP_GROUPS, A_XOR, A_METATILE_SIZE, A_METATILE_MASK, A_PRIMARY_OVERRIDE, A_LAYOUT_VERSIONED = 2, 17, 19, 20, 21, 29
OUTDOOR = {0, 1, 2, 3, 5, 6}
# Builds whose MapHeader keeps the map section as a u16 (the map type a byte on: +0x18), by name: SoulGold.
MAPSEC_WIDE = ('SoulGold',)

# ROMs whose numbers pass the checks but whose maps came out wrong when drawn (--report), or that are left out on
# purpose: name substring -> why. They stay off until someone works it out.
LATER = {
    'Team Rocket Edition 2026': 'its maps come out scrambled (expansion 1.14 with tilesets of its own), not worked out yet',
    'Ghost Grey': 'blocked at its developer\'s request (BlockedGames.kt)',
    'Mario Kart': 'not a Pokemon game',
    '(Pirate)': 'a pirate build, not supported',
    '(Debug Version)': 'a debug build, not supported',
    '(Pokemon Box)': 'not a release the app supports',
}
# LATER's entries that aren't games the app plays: left out without a note.
QUIET = {'Mario Kart', '(Pirate)', '(Debug Version)', '(Pokemon Box)'}


class Rom:
    def __init__(self, data):
        self.b = data

    def ok(self, a):
        return 0x08000000 <= a < 0x08000000 + len(self.b) - 4

    def rd(self, a, n):
        o = a - 0x08000000
        return self.b[o:o + n]

    def u8(self, a): return self.b[a - 0x08000000]
    def u32(self, a): return struct.unpack_from('<I', self.b, a - 0x08000000)[0]

    def lz77_size(self, a):
        h = self.rd(a, 4)
        return (h[1] | h[2] << 8 | h[3] << 16) if h[0] == 0x10 else None

    def lz77(self, a):
        src = self.rd(a, 0x48000)
        if src[0] != 0x10: return None
        size = src[1] | src[2] << 8 | src[3] << 16
        out = bytearray(); i = 4
        while len(out) < size:
            flags = src[i]; i += 1
            for bit in range(8):
                if len(out) >= size: break
                if flags & (0x80 >> bit):
                    d = src[i] << 8 | src[i + 1]; i += 2
                    n = (d >> 12) + 3; disp = (d & 0xFFF) + 1
                    for _ in range(n): out.append(out[-disp])
                else:
                    out.append(src[i]); i += 1
        return bytes(out)


def grid(r, lay):
    """A layout's map grid, w * h u16s: raw, or LZ77 when its header says so with exactly that size (Monomyth packs its
    indoor maps' grids)."""
    w, h = r.u32(lay), r.u32(lay + 4)
    g = r.u32(lay + 0xC)
    if r.lz77_size(g) == w * h * 2:
        return r.lz77(g)
    return r.rd(g, w * h * 2)


def layout_ok(r, lay):
    if not r.ok(lay): return False
    w, h = r.u32(lay), r.u32(lay + 4)
    return 1 <= w <= 1024 and 1 <= h <= 1024 and r.ok(r.u32(lay + 0xC)) and r.ok(r.u32(lay + 0x10)) and r.ok(r.u32(lay + 0x14))


def header_ok(r, h):
    return r.ok(h) and h % 4 == 0 and layout_ok(r, r.u32(h)) and (r.u32(h + 4) == 0 or r.ok(r.u32(h + 4)))


def group_lists(r, groups, xor):
    """Each group's list of header pointers: a group is a list in ROM (or none: 0, Rogue's unused groups) whose first entry
    points into ROM; it ends at the next list or the first entry that doesn't (a deleted map's header still counts:
    Amethyst's have a layout of 0xFFFFFFFF)."""
    lists = []
    g = 0
    while g < 256 and r.ok(groups + 4 * g):
        lst = r.u32(groups + 4 * g) ^ xor
        if lst == xor or lst == 0:
            # An empty group only inside the table: a real list must follow within a few.
            ahead = [r.u32(groups + 4 * (g + k)) ^ xor for k in range(1, 5) if r.ok(groups + 4 * (g + k))]
            if not any(r.ok(a) and a % 4 == 0 and header_ok(r, r.u32(a)) for a in ahead): break
            lists.append(0)
        elif r.ok(lst) and lst % 4 == 0 and r.ok(r.u32(lst)) and r.u32(lst) % 4 == 0:
            lists.append(lst)
        else:
            break
        g += 1
    while lists and lists[-1] == 0: lists.pop()
    starts = sorted(set(lists) - {0} | {groups})
    sizes = []
    for lst in lists:
        if lst == 0:
            sizes.append(0)
            continue
        nxt = min([s for s in starts if s > lst], default=lst + 4 * 1024)
        n = 0
        while lst + 4 * n < nxt and n < 1024:
            h = r.u32(lst + 4 * n)
            if not r.ok(h) or h % 4: break
            lay = r.u32(h)
            if not (r.ok(lay) or lay == 0xFFFFFFFF): break
            n += 1
        sizes.append(n)
    return lists, sizes


def find_map_groups(r):
    """gMapGroups by its shape: a table of pointers to lists of map headers, 3+ groups, the most maps wins."""
    best = None
    b = r.b
    n = len(b) // 4
    words = struct.unpack_from('<%dI' % n, b)
    for i in range(n):
        w = words[i]
        if not 0x08000000 <= w < 0x08000000 + len(b) or w & 3: continue
        lst = words[(w - 0x08000000) // 4]
        if not 0x08000000 <= lst < 0x08000000 + len(b) or lst & 3: continue
        if not header_ok(r, lst): continue
        addr = 0x08000000 + 4 * i
        lists, sizes = group_lists(r, addr, 0)
        good = sum(1 for m in maps(r, lists, sizes).values() if header_ok(r, m))
        if len(lists) >= 3 and (best is None or good > best[1]):
            best = (addr, good)
    return best[0] if best else None


def maps(r, lists, sizes):
    out = {}
    for g, (lst, n) in enumerate(zip(lists, sizes)):
        for k in range(n):
            h = r.u32(lst + 4 * k)
            if header_ok(r, h): out[(g, k)] = h
    return out


def tileset(r, a):
    """A compressed tileset's tile count: LZ77's size, or smol's (pokeemerald-expansion: modes 1-6, imageSize words of
    4 bytes); None for raw tiles (no length)."""
    comp = r.u8(a)
    t = r.u32(a + 4)
    if comp:
        size = r.lz77_size(t)
        if size is None and r.u8(t) & 0xF in range(1, 7):
            size = (r.u32(t) >> 4 & 0x3FFF) * 4
        return (size or 0) // 32
    return None


def infer_split(r, headers, metas, metatile_size, mask, version=None):
    """Where secondary tiles start: of 512 / 640 / 768 / 1024, the split under which the most tile entries of the maps'
    secondary metatiles land in data - below it inside the layout's primary tiles, past it inside the secondary's. Only
    compressed tilesets say how many tiles they have; returns ((split, share that fit, entries seen), biggest primary)."""
    prim, sec = {}, {}
    used = collections.defaultdict(set)
    pairs = set()
    for h in headers.values():
        lay = r.u32(h)
        # Heart and Soul: only the layouts of one kind (MapLayout +0x18: 0 Emerald's, else FireRed's).
        if version is not None and (r.u8(lay + 0x18) != 0) != version: continue
        p, s = r.u32(lay + 0x10), r.u32(lay + 0x14)
        if not (r.ok(p) and r.ok(s)): continue
        prim[p] = tileset(r, p)
        sec[s] = tileset(r, s)
        pairs.add((p, s))
        cells = grid(r, lay)
        for i in range(0, len(cells) - 1, 2):
            mid = (cells[i] | cells[i + 1] << 8) & mask
            if mid != mask and mid >= metas: used[(p, s)].add(mid - metas)
    best = None
    for split in (512, 640, 768, 1024):
        fit = total = 0
        for (p, s), ids in used.items():
            n1, n2 = prim[p], sec[s]
            if not n1 or not n2: continue
            m = r.u32(s + 0xC)
            for k in ids:
                ent = r.rd(m + metatile_size * k, metatile_size)
                for j in range(0, metatile_size, 2):
                    t = (ent[j] | ent[j + 1] << 8) & 0x3FF
                    if t == 0: continue
                    total += 1
                    fit += (t < split and t < n1) or (split <= t < split + n2)
        if total and (best is None or fit / total > best[1] + 1e-9):
            best = (split, fit / total, total)
    biggest = max([v for v in prim.values() if v] or [0])
    return best or (0, 0.0, 0), biggest


def area_sample(r, headers, floor_off):
    """A few multi-floor indoor areas (by section), for the report."""
    by = collections.defaultdict(list)
    for (g, k), h in headers.items():
        t = r.u8(h + 0x17)   # (SoulGold's is at +0x18: the report only)
        if t in OUTDOOR: continue
        by[(r.u8(h + 0x14), g)].append((g, k))
    return [v for v in sorted(by.values(), key=len, reverse=True) if len(v) >= 2][:3]


def render(r, h, t, path):
    lay = r.u32(h)
    w, hh = r.u32(lay), r.u32(lay + 4)
    cells = grid(r, lay)
    sets = []
    for k, a in enumerate((r.u32(lay + 0x10), r.u32(lay + 0x14))):
        comp = r.u8(a)
        tiles = r.lz77(r.u32(a + 4)) if comp else r.rd(r.u32(a + 4), 0x8000)
        pals = r.rd(r.u32(a + 8), 16 * 32)
        sets.append((tiles, pals, r.u32(a + 0xC)))
    split, metas, npal, msize, mask = t['primaryTiles'], t['primaryMetatiles'], t['primaryPalettes'], t['metatileSize'], t['metatileMask']
    pal = []
    for p in range(16):
        src = sets[0][1] if p < npal else sets[1][1]
        row = []
        for c in range(16):
            v = src[p * 32 + 2 * c] | src[p * 32 + 2 * c + 1] << 8 if p < 13 else 0
            row.append(bytes((((v & 31) << 3), ((v >> 5 & 31) << 3), ((v >> 10 & 31) << 3))))
        pal.append(row)
    W, H = w * 16, hh * 16
    img = bytearray(W * H * 3)
    cache = {}
    for my in range(hh):
        for mx in range(w):
            mid = (cells[2 * (my * w + mx)] | cells[2 * (my * w + mx) + 1] << 8) & mask
            if mid == mask: continue
            if mid not in cache:
                base = sets[0][2] + msize * mid if mid < metas else sets[1][2] + msize * (mid - metas)
                ent = r.rd(base, msize)
                px = [pal[0][0]] * 256
                for k in range(msize // 2):
                    e = ent[2 * k] | ent[2 * k + 1] << 8
                    tile = e & 0x3FF
                    tiles = sets[0][0] if tile < split else sets[1][0]
                    o = (tile if tile < split else tile - split) * 32
                    if tiles is None or o + 32 > len(tiles): continue
                    q = k % 4
                    for yy in range(8):
                        sy = 7 - yy if e & 0x800 else yy
                        for xx in range(8):
                            sx = 7 - xx if e & 0x400 else xx
                            v = tiles[o + sy * 4 + sx // 2]
                            v = v >> 4 if sx & 1 else v & 15
                            if v: px[((q >> 1) * 8 + yy) * 16 + (q & 1) * 8 + xx] = pal[e >> 12][v]
                cache[mid] = px
            px = cache[mid]
            for yy in range(16):
                o = ((my * 16 + yy) * W + mx * 16) * 3
                img[o:o + 48] = b''.join(px[yy * 16:yy * 16 + 16])
    raw = b''.join(b'\0' + bytes(img[y * W * 3:(y + 1) * W * 3]) for y in range(H))
    def chunk(tag, d): return struct.pack('>I', len(d)) + tag + d + struct.pack('>I', zlib.crc32(tag + d) & 0xffffffff)
    open(path, 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 2, 0, 0, 0))
                           + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))


def wide_rows():
    text = open(WIDE).read()
    for m in re.finditer(r'\{ 0x([0-9a-f]{8}), (PK_WIDE_FAMILY_\w+), \{([^}]*)\} \},\s*// ([^\n]*)', text):
        a = [int(x, 16) for x in m.group(3).split(',')]
        yield int(m.group(1), 16), m.group(2), a, m.group(4).strip()


def engine_guess(code):
    return 'PK_WIDE_FAMILY_FR' if code[:3] in ('BPR', 'BPG', 'PUV') else 'PK_WIDE_FAMILY_EM'


def scan(rom_dir, report):
    files = {}
    for dp, _, fn in os.walk(rom_dir):
        for f in fn:
            if f.lower().endswith('.gba'):
                p = os.path.join(dp, f)
                files.setdefault(zlib.crc32(open(p, 'rb').read()) & 0xFFFFFFFF, p)
    rows = {crc: (fam, a, note) for crc, fam, a, note in wide_rows()}
    out, skipped = [], []
    for crc, path in sorted(files.items(), key=lambda kv: os.path.basename(kv[1])):
        name = os.path.basename(path)
        hit = next((k for k in LATER if k in name), None)
        if hit:
            if hit not in QUIET: skipped.append((name, LATER[hit]))
            continue
        data = open(path, 'rb').read()
        code = data[0xAC:0xB0].decode('latin-1')
        if code[:3] not in ('BPR', 'BPG', 'BPE', 'AXV', 'AXP', 'PUV', 'MON'):
            continue
        r = Rom(data)
        if crc in rows:
            fam, a, note = rows[crc]
            groups, xor = a[A_MAP_GROUPS], a[A_XOR]
            msize, mask = a[A_METATILE_SIZE] or 16, a[A_METATILE_MASK] or 0x3FF
            metas = a[A_PRIMARY_OVERRIDE] or FAMILIES[fam][0]
            versioned = a[A_LAYOUT_VERSIONED]
        else:
            fam = 'PK_WIDE_FAMILY_FR' if code[:3] in ('BPR', 'BPG', 'PUV') else 'PK_WIDE_FAMILY_EM'
            groups, xor, msize, mask, versioned = find_map_groups(r), 0, 16, 0x3FF, 0
            metas = FAMILIES[fam][0]
        if not groups:
            skipped.append((name, 'gMapGroups not found by its shape'))
            continue
        lists, sizes = group_lists(r, groups, xor)
        if len(lists) < 3:
            skipped.append((name, 'gMapGroups at %08X reads as %d groups' % (groups, len(lists))))
            continue
        headers = maps(r, lists, sizes)
        # Compressed tilesets are LZ77 (0x10) or smol (newer pokeemerald-expansion, SoulGold: modes 1-6); nothing else.
        packed = collections.Counter()
        for h in headers.values():
            lay = r.u32(h)
            for a_ in (r.u32(lay + 0x10), r.u32(lay + 0x14)):
                if r.ok(a_) and r.u8(a_) and r.ok(r.u32(a_ + 4)):
                    b0 = r.u8(r.u32(a_ + 4))
                    packed[b0 == 0x10 or b0 & 0xF in range(1, 7)] += 1
        if packed[False] > packed[True] // 50:
            skipped.append((name, 'its tilesets are compressed some other way (not LZ77 or smol)'))
            continue
        alt = None
        if versioned:
            # Two kinds of layout (Heart and Soul): +0x18 = 0 the engine's (Emerald's numbers), else FireRed's.
            (split, share, seen), biggest = infer_split(r, headers, FAMILIES[fam][0], msize, mask, version=False)
            (asplit, ashare, aseen), _ = infer_split(r, headers, metas, msize, mask, version=True)
            share = min(share, ashare)
            alt = (asplit, metas, 7)
            metas = FAMILIES[fam][0]
        else:
            (split, share, seen), biggest = infer_split(r, headers, metas, msize, mask)
        # A primary bigger than the split is fine (the game loads it, then the secondary over its end: R.O.W.E.).
        if share < 0.98:
            skipped.append((name, 'where secondary tiles start is unclear (best: %d, %.1f%% of %d tile entries fit): its tilesets '
                                  'are laid out its own way' % (split, share * 100, seen) if seen else
                                  'no LZ77 tilesets (another compression): the reader needs it first'))
            continue
        t = dict(primaryTiles=split, primaryMetatiles=metas, primaryPalettes=FAMILIES[fam][1], floorNumOff=FAMILIES[fam][2],
                 metatileSize=msize, metatileMask=mask, mapSecWide=any(k in name for k in MAPSEC_WIDE), alt=alt)
        out.append((crc, name, groups, xor, sizes, t, fam, None))
        print('%08x %-62s %3d groups %4d maps split %d (%.1f%%) %s' % (crc, name[:62], len(sizes), sum(sizes), split, share * 100,
                                                                  '' if crc in rows else '(scanned)'))
        if report:
            os.makedirs(report, exist_ok=True)
            for area in area_sample(r, headers, t['floorNumOff']):
                for g, k in area[:2]:
                    render(r, headers[(g, k)], t, os.path.join(report, '%s_%d_%d.png' % (name.rsplit('.', 1)[0][:40].replace(' ', '_'), g, k)))
    # EXPAND rows whose ROM isn't on this machine: a sibling build with the same gMapGroups (and XOR, family) has the same
    # maps (SoulGold 1.2.1's second build, Sword and Shield's base build, Orange Islands' beta): its row, noted as copied.
    # AreaMap.matches checks it against the ROM when it's played.
    done = {crc for crc, *_ in out}
    for crc, fam, a, note in wide_rows():
        if crc in done or crc in files: continue
        name = note.split(': ', 1)[-1]
        def stem(n): return n.rsplit('.', 1)[0].split(' (')[0]   # the same game ("Pokemon Orange Islands"), not just its table address
        sib = next((o for o in out if o[2] == a[A_MAP_GROUPS] and o[3] == a[A_XOR] and o[6] == fam and stem(o[1]) == stem(name)), None)
        if sib is None: continue
        out.append((crc, name, sib[2], sib[3], sib[4], sib[5], fam, sib[1]))
        print('%08x %-62s copied from %s (no ROM here)' % (crc, name[:62], sib[1]))
    for name, why in skipped:
        print('SKIPPED %s: %s' % (name, why))
    write_toml(out, skipped)


def write_toml(out, skipped):
    lines = ['# AREA maps (AreaMap.kt): each ROM\'s map tables, by its CRC32. Written by scripts/gen_area_maps.py --scan from the',
             '# player\'s own ROMs (gMapGroups from EXPAND\'s rows, pk_wide_games.h, or a scan; the rest read from the ROM) - rerun',
             '# it rather than editing. The 3DS / Linux companion can draw the same maps from this file.',
             '#',
             '# mapGroups / mapGroupsXor / mapGroupSizes: gMapGroups. primaryTiles / primaryMetatiles / primaryPalettes: where the',
             '# secondary tileset starts. floorNumOff: MapHeader\'s floor byte (-1 = none). metatileSize: 16, 24 for three layers.',
             '# metatileMask: a map cell\'s metatile id bits (also its undefined square). mapSecWide: MapHeader\'s map section is a',
             '# u16 at +0x14, its map type at +0x18 (SoulGold). altTiles / altMetatiles / altPalettes: the numbers for a layout whose',
             '# byte +0x18 isn\'t 0 (Heart and Soul\'s FireRed-style layouts; the others above).', '']
    for crc, name, groups, xor, sizes, t, fam, copied in sorted(out, key=lambda o: o[1]):
        lines += ['[rom.%08x]' % crc, 'name = %s' % json.dumps(name)]
        if copied: lines.append('# Its ROM wasn\'t on hand: %s\'s row (the same gMapGroups).' % copied)
        lines.append('mapGroups = 0x%08X' % groups)
        if xor: lines.append('mapGroupsXor = 0x%08X' % xor)
        lines.append('mapGroupSizes = [%s]' % ', '.join(map(str, sizes)))
        for k in ('primaryTiles', 'primaryMetatiles', 'primaryPalettes', 'floorNumOff', 'metatileSize'):
            lines.append('%s = %d' % (k, t[k]))
        lines.append('metatileMask = 0x%X' % t['metatileMask'])
        if t['mapSecWide']: lines.append('mapSecWide = true')
        if t.get('alt'):
            lines += ['altTiles = %d' % t['alt'][0], 'altMetatiles = %d' % t['alt'][1], 'altPalettes = %d' % t['alt'][2]]
        lines.append('')
    if skipped:
        lines.append('# Not yet (scripts/gen_area_maps.py --scan says why):')
        lines += ['#   %s: %s' % s for s in skipped]
    open(TOML, 'w').write('\n'.join(lines).rstrip() + '\n')
    print('wrote %s: %d ROMs, %d skipped' % (os.path.relpath(TOML, ROOT), len(out), len(skipped)))


def kotlin():
    import tomllib
    data = tomllib.load(open(TOML, 'rb')).get('rom', {})
    lines = ['// Generated by scripts/gen_area_maps.py from gamedata/gen3/area_maps.toml - don\'t edit by hand.',
             'package com.pokedaisy.app.companion.data', '',
             '/** AREA maps\' tables by ROM CRC32 (AreaMap.kt, [AreaMaps]). */',
             'val AREA_MAP_TABLES: Map<Long, AreaMapTables> by lazy {', '    mapOf(']
    for crc, v in data.items():
        lines.append('        // %s' % v['name'])
        extra = ''
        if v.get('mapSecWide'): extra += ', mapSecWide = true'
        if 'altTiles' in v:
            extra += ', alt = AreaMapTables.Numbers(%d, %d, %d)' % (v['altTiles'], v['altMetatiles'], v['altPalettes'])
        lines.append('        0x%sL to AreaMapTables(0x%08XL, 0x%XL, listOf(%s), %d, %d, %d, %d, %d, 0x%X%s),' % (
            crc, v['mapGroups'], v.get('mapGroupsXor', 0), ', '.join(map(str, v['mapGroupSizes'])),
            v['primaryTiles'], v['primaryMetatiles'], v['primaryPalettes'], v['floorNumOff'], v['metatileSize'], v['metatileMask'],
            extra))
    lines += ['    )', '}', '']
    return '\n'.join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scan')
    ap.add_argument('--report')
    ap.add_argument('--check', action='store_true')
    o = ap.parse_args()
    if o.scan:
        scan(o.scan, o.report)
    text = kotlin()
    if o.check:
        if not os.path.exists(KOTLIN) or open(KOTLIN).read() != text:
            sys.exit('gen_area_maps: %s is stale - run python3 scripts/gen_area_maps.py' % os.path.relpath(KOTLIN, ROOT))
        return
    open(KOTLIN, 'w').write(text)
    print('gen_area_maps: wrote %s' % os.path.relpath(KOTLIN, ROOT))


if __name__ == '__main__':
    main()
