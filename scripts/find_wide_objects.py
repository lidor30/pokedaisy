#!/usr/bin/env python3
"""Finds the far-off objects' addresses (pk_wide.h's OBJ_* / SPRITE_PAL_TAGS / ELEVATION_PRIO / CONTROLS_LOCKED slots,
and the save's templates) in a ROM hack with no symbols (a pokeemerald-expansion or other gcc build), from the ROM and
one RAM dump of the game standing on a map (a fixture: test-fixtures/resources/fixtures/<name>/{ewram,iwram}.bin).

    scripts/find_wide_objects.py ROM FIXTURE [FIXTURE...] --objects ADDR --sprites ADDR --save1 ADDR --header ADDR [--vanilla]

A FIXTURE is a fixture's name or any directory holding ewram.bin / iwram.bin (mgba_dump's `dump DIR`): several of them
(a walk dumped every few steps) give the votes more live objects than one.

--objects / --sprites / --header / --save1 are gObjectEvents, gSprites, gMapHeader and gSaveBlock1Ptr
(scripts/find_wide_addrs.py, the hack's NativeConfig). --vanilla: ObjectEvent as retail has it (spriteId +4, u8
graphicsId +5) instead of expansion's (u16 graphicsId +4, spriteId +0x23).

  OBJ_GFX_INFO      gObjectEventGraphicsInfoPointers: the table where each live object's graphics id leads to the
                    record (0x24 bytes, images at +0x1C) its sprite's images come from - the most votes
  OBJ_GFX_VAR_FROM  the 16 ids read from VAR_OBJ_GFX_ID_*: the first 16 entries in a row past 240 that aren't
                    records (expansion's OBJ_EVENT_GFX_VARS = NUM_OBJ_EVENT_GFX + 1; 240 in retail)
  OBJ_PALETTES      sObjectEventSpritePalettes: the longest run of {ROM data, u16 tag, 0} holding tag 0x1103
  SPRITE_PAL_TAGS   sSpritePaletteTags: the RAM u16[16] whose slots hold the live sprites' palette tags
  ELEVATION_PRIO, OBJ_FACING  retail's bytes, found as they are
  CONTROLS_LOCKED   UnlockPlayerFieldControls then ArePlayerFieldControlsLocked on one byte (`strb` / `ldrb` + k)
  SB1_TEMPLATES     where the map header's first object template is copied in SaveBlock1 (flags follow, 0x600 on)

Prints them for gen_wide_tables.py's HACKS; anything not found unambiguously is None.
"""
import argparse
import os
import re
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("rom")
    ap.add_argument("fixture", nargs="+")
    ap.add_argument("--objects", type=lambda v: int(v, 0), required=True)
    ap.add_argument("--sprites", type=lambda v: int(v, 0), required=True)
    ap.add_argument("--save1", type=lambda v: int(v, 0), required=True)
    ap.add_argument("--header", type=lambda v: int(v, 0), required=True)
    ap.add_argument("--vanilla", action="store_true")
    a = ap.parse_args()
    rom = open(a.rom, "rb").read()
    dumps = []
    for f in a.fixture:
        fx = f if os.path.isdir(f) else os.path.join(ROOT, "test-fixtures", "resources", "fixtures", f)
        dumps.append((open(os.path.join(fx, "ewram.bin"), "rb").read(), open(os.path.join(fx, "iwram.bin"), "rb").read()))
    ew, iw = dumps[0]

    def mem(addr, n, d=None):
        e, i = d or (ew, iw)
        if addr >> 24 == 2:
            return e[addr & 0x3FFFF:(addr & 0x3FFFF) + n]
        if addr >> 24 == 3:
            return i[addr & 0x7FFF:(addr & 0x7FFF) + n]
        return rom[addr - 0x08000000:addr - 0x08000000 + n]

    def u32(addr):
        return struct.unpack("<I", mem(addr, 4))[0]

    def is_rom(p):
        return 0x08000000 <= p < 0x08000000 + len(rom) - 0x24

    out = {}
    words = {}   # ROM pointer -> where it's stored
    for o in range(0, len(rom) - 3, 4):
        w = struct.unpack_from("<I", rom, o)[0]
        if is_rom(w):
            words.setdefault(w, []).append(o + 0x08000000)

    live = []   # (graphics id, sprite id, dump), each distinct (id, images) once
    seen = set()
    for d in dumps:
        for k in range(16):
            oe = mem(a.objects + 0x24 * k, 0x24, d)
            if not oe[0] & 1:
                continue
            gfx, sid = (oe[5], oe[4]) if a.vanilla else (struct.unpack_from("<H", oe, 4)[0], oe[0x23])
            images = struct.unpack("<I", mem(a.sprites + 0x44 * sid + 0xC, 4, d))[0]
            if (gfx, images) not in seen:
                seen.add((gfx, images))
                live.append((gfx, sid, d))
    votes = {}
    for gfx, sid, d in live:
        images = struct.unpack("<I", mem(a.sprites + 0x44 * sid + 0xC, 4, d))[0]
        for ref in words.get(images, []):
            for p in words.get(ref - 0x1C, []):
                votes[p - 4 * gfx] = votes.get(p - 4 * gfx, 0) + 1
    if not votes:
        print("no live object leads to a graphics table")
        return
    table = max(votes, key=votes.get)
    out["OBJ_GFX_INFO"] = table
    print(f"graphics table {table:#x}: {votes[table]} of {len(live)} live objects agree")

    def record(p):
        return is_rom(p) and is_rom(u32(p + 0x1C)) and is_rom(u32(p + 0x10))

    bad, k = [], 0
    while k < 4096 and is_rom(u32(table + 4 * k)):
        if not record(u32(table + 4 * k)):
            bad.append(k)
        k += 1
    var_from = next((b for b in bad if b >= 240 and all(b + i in bad for i in range(16))), 240)
    out["OBJ_GFX_VAR_FROM"] = var_from
    print(f"  {k} entries, var ids from {var_from:#x}")

    def good(o):
        p, t, z = struct.unpack_from("<IHH", rom, o)
        return 0x08000000 <= p < 0x0A000000 and 0x1000 <= t < 0x2000 and z == 0

    best = (0, 0)
    for m in re.finditer(rb"[\x08\x09]\x03\x11\x00\x00", rom):
        o = m.start() - 3
        if o % 4:
            continue
        s, e = o, o
        while s >= 8 and good(s - 8):
            s -= 8
        while good(e):
            e += 8
        if (e - s) // 8 > best[1]:
            best = (s, (e - s) // 8)
    out["OBJ_PALETTES"] = best[0] + 0x08000000 if best[1] else None
    print(f"palettes: {best[1]} entries")

    # sSpritePaletteTags: in each dump, the u16[16] whose slots hold that dump's live sprites' tags - one address all agree on.
    tags = None
    for d in dumps:
        pairs = set()   # (OBJ palette slot, tag) of each live sprite
        for gfx, sid, dd in live:
            if dd is d and gfx < var_from and record(u32(table + 4 * gfx)):
                slot = struct.unpack("<H", mem(a.sprites + 0x44 * sid + 4, 2, d))[0] >> 12
                pairs.add((slot, struct.unpack("<H", mem(u32(table + 4 * gfx) + 2, 2))[0]))
        if not pairs:
            continue
        here = {base + o for buf, base in ((d[1], 0x03000000), (d[0], 0x02000000)) for o in range(0, len(buf) - 32, 2)
                if all(struct.unpack_from("<H", buf, o + 2 * s)[0] == t for s, t in pairs)}
        tags = here if tags is None else tags & here
    tags = sorted(tags or [])
    out["SPRITE_PAL_TAGS"] = tags[0] if len(tags) == 1 else None
    print(f"palette tag lists: {[hex(t) for t in tags]} (agreed by every dump with a sprite)")

    for name, pattern in (("ELEVATION_PRIO", "02020202010201020102010201000002"), ("OBJ_FACING", "01010102010304020103040101010302")):
        hits = [m.start() + 0x08000000 for m in re.finditer(re.escape(bytes.fromhex(pattern)), rom)]
        out[name] = hits[0] if len(hits) == 1 else None

    locks = []
    for k in range(8):
        unlock = bytes.fromhex("0022014b") + struct.pack("<H", 0x7000 | k << 6 | 3 << 3 | 2) + bytes.fromhex("7047")
        get = bytes.fromhex("014b") + struct.pack("<H", 0x7800 | k << 6 | 3 << 3) + bytes.fromhex("7047")
        for m in re.finditer(re.escape(unlock), rom):
            x = struct.unpack_from("<I", rom, m.end())[0]
            g = m.end() + 4
            while g < m.end() + 8 and rom[g:g + 2] == b"\xc0\x46":   # nop padding
                g += 2
            if rom[g:g + 6] == get and struct.unpack_from("<I", rom, (g + 6 + 3) & ~3)[0] == x:
                locks.append(x + k)
    out["CONTROLS_LOCKED"] = locks[0] if len(set(locks)) == 1 else None

    sb1 = u32(a.save1)
    first = mem(u32(u32(a.header + 4) + 4), 16)
    at = ew.find(first, sb1 & 0x3FFFF, (sb1 & 0x3FFFF) + 0x4000) if first and sb1 >> 24 == 2 else -1
    out["SB1_TEMPLATES"] = at - (sb1 & 0x3FFFF) if at >= 0 else None
    print({k: (hex(v) if isinstance(v, int) else v) for k, v in out.items()})


if __name__ == "__main__":
    main()
