#!/usr/bin/env python3
"""Maps a DS cart's addresses from its US / Europe build into another language's, the way the French carts were found.

    python3 scripts/port_nds.py US.nds OTHER.nds WORD ADDR[@OVERLAY] ...

WORD is a global: every literal-pool word of that value in the US ARM9 and overlays, each aligned with the other cart's
code around it, votes for the other cart's word (the US `sFieldSysPtr` -> the French one). ADDR is code: its bytes aligned
in the same module (ARM9 or, after @, the overlay it's in: overlays overlap, so name it), printed with how much matched.
Give a global as `w:0x021D4158`, code as `c:0x0203BEF1` or `c:0x0215E841@10`. Reads the two ROMs only; print, then check
each address live in nds_dump (docs/DEVELOPMENT.md's "Other languages"). scripts/port_nds_fns.py matches functions instead
and says UNRESOLVED where this one would answer wrong: try it first.
"""
import collections
import struct
import sys


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


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


class Cart:
    """Its ARM9 and overlays, unpacked: {module: (RAM address, bytes)}; the ARM9 is "arm9"."""

    def __init__(self, path):
        # Only the header, ARM9, overlay table and overlay files are read (the Gen 5 carts are 256 / 512 MB).
        with open(path, "rb") as f:
            def read(off, n):
                f.seek(off)
                return f.read(n)
            h = self.header = read(0, 0x200)
            self.mods = {"arm9": self.arm9(read)}
            fat, table, size = u32(h, 0x48), u32(h, 0x50), u32(h, 0x54)
            t = read(table, size)
            for i in range(size // 32):
                oid, ram, _, _, _, _, fid, flags = struct.unpack_from("<8I", t, i * 32)
                start, end = struct.unpack("<2I", read(fat + fid * 8, 8))
                d = read(start, end - start)
                self.mods[oid] = (ram, blz(d) if flags >> 24 & 1 else d)

    def arm9(self, read):
        h = self.header
        off, ram, size = u32(h, 0x20), u32(h, 0x28), u32(h, 0x2C)
        d = read(off, size)
        # crt0 points at the module params; their +0x14 is where the compressed part ends (0: not compressed).
        for o in range(0, 0x1000, 4):
            w = u32(d, o)
            if ram < w < ram + size - 0x20:
                end = u32(d, w - ram + 0x14)
                if ram + 0x4000 < end <= ram + size:
                    t = end - ram
                    inc, info = u32(d, t - 4), u32(d, t - 8)
                    if 0 < inc < 0x400000 and 8 <= info >> 24 <= 0x20 and info & 0xFFFFFF <= t:
                        return ram, blz(d[:t]) + d[t:]
        return ram, d


def same(a, ao, b, bo, n):
    if ao < 0 or bo < 0 or ao + n > len(a) or bo + n > len(b):
        return -1
    return sum(x == y for x, y in zip(a[ao:ao + n], b[bo:bo + n]))


def nearest(a, o, b, n, reach, start=0):
    """The offset in b nearest o whose n bytes (from o + start) look most like a's: (matching bytes, offset)."""
    best = (-1, o)
    for shift in sorted(range(-reach, reach + 1, 2), key=abs):
        s = same(a, o + start, b, o + shift + start, n)
        if s > best[0]:
            best = (s, o + shift)
        if s == n:
            break
    return best


def word(us, other, value):
    votes = collections.Counter()
    pat = struct.pack("<I", value)
    for k, (_, a) in us.mods.items():
        if k not in other.mods:
            continue
        b = other.mods[k][1]
        i = a.find(pat)
        while i >= 0:
            if i % 4 == 0:
                s, p = nearest(a, i, b, 64, 0x400, -32)
                if s >= 40 and p % 4 == 0 and p + 4 <= len(b):
                    votes[u32(b, p)] += 1
            i = a.find(pat, i + 1)
    return votes


def code(us, other, addr, module):
    out = []
    a0 = addr & ~1
    for k, (ra, a) in us.mods.items():
        if module is not None and k != module or not ra <= a0 < ra + len(a) or k not in other.mods:
            continue
        rb, b = other.mods[k]
        s, p = nearest(a, a0 - ra, b, 64, 0x1000)
        out.append((100 * s // 64, k, rb + p + (addr & 1)))
    return sorted(out, key=lambda x: -x[0])


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    us, other = Cart(sys.argv[1]), Cart(sys.argv[2])
    for arg in sys.argv[3:]:
        kind, _, rest = arg.partition(":")
        addr, _, mod = rest.partition("@")
        addr = int(addr, 0)
        if kind == "w":
            print(f"{addr:#010x}", [(hex(v), n) for v, n in word(us, other, addr).most_common(3)])
        else:
            print(f"{addr:#010x}", [(f"{p}%", k, hex(v)) for p, k, v in code(us, other, addr, int(mod) if mod else None)[:3]])


if __name__ == "__main__":
    main()
