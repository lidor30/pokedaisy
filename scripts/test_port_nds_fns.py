#!/usr/bin/env python3
"""Tests for port_nds_fns.py: the decoder and normaliser on hand-assembled code, the matcher on two made-up carts, and
the known pairs (port_nds_fns_known.txt) on the real carts when they're there (skipped otherwise).

    python3 scripts/test_port_nds_fns.py [-v]

ROMs: POKEDAISY_ROM_DIR's sibling `nds` (the gba folder's), else ~/Downloads/Game ROMs & Emulation/nds.
"""
import os
import struct
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import port_nds_fns as P  # noqa: E402


# ---- a tiny assembler for the few instructions the tests need

def bl(at, target, blx=False):
    """Thumb BL / BLX at address [at] to [target]: two halfwords."""
    off = (target & ~1) - (at + 4)
    if blx:
        off = (target & ~3) - ((at + 4) & ~3)
    v = (off >> 1) & 0x3FFFFF
    return [0xF000 | (v >> 11), (0xE800 if blx else 0xF800) | (v & 0x7FF)]


def ldr_pc(rd, at, pool):
    """Thumb LDR rd, [pc, #] at [at] loading the word at [pool]."""
    imm = (pool - ((at + 4) & ~3)) // 4
    assert 0 <= imm < 256 and pool % 4 == 0
    return 0x4800 | rd << 8 | imm


def thumb_fn(at, calls, words, filler=0):
    """A Thumb function at [at] (calls in the same coordinates): push, [filler] distinct movs, a literal load per word,
    a BL per call, pop, its pool after. Returns (bytes, its address with the Thumb bit)."""
    hs = [0xB510]                                   # push {r4, lr}
    hs += [0x2000 | (k + 1) & 0xFF for k in range(filler)]   # movs r0, #k
    loads = len(words)
    body = len(hs) + loads + 2 * len(calls) + 1     # + pop
    pool_at = at + body * 2
    pool_at += (-pool_at) % 4
    pos = at + len(hs) * 2
    code = list(hs)
    for i in range(loads):
        code.append(ldr_pc(0, pos, pool_at + 4 * i))
        pos += 2
    for t in calls:
        code += bl(pos, t)
        pos += 4
    code.append(0xBD10)                             # pop {r4, pc}
    pos += 2
    if pos % 4:
        code.append(0x46C0)                         # nop (padding)
        pos += 2
    raw = struct.pack(f"<{len(code)}H", *code) + b"".join(struct.pack("<I", w) for w in words)
    return raw, at | 1


class FakeSide(P.Side):
    """A Side over modules built in memory."""

    def __init__(self, mods):
        self.path = "<memory>"
        self.cart = None
        self.mods = {k: P.Module(k, b, d) for k, (b, d) in mods.items()}
        self._funcs = {}


def layout(base, pieces, size=0x400):
    """A module image: each (offset, bytes) placed, zeros between."""
    img = bytearray(size)
    for off, raw in pieces:
        img[off:off + len(raw)] = raw
    return base, bytes(img)


class Decode(unittest.TestCase):
    BASE = 0x02000000

    def test_thumb_function(self):
        callee = self.BASE + 0x100 | 1
        raw, a = thumb_fn(self.BASE, [callee], [0x021C07DC, 0x1234])
        m = P.Module("arm9", self.BASE, raw + b"\0" * 0x200)
        f = P.decode(m, a)
        self.assertIsNotNone(f)
        self.assertTrue(f.thumb)
        self.assertEqual(f.refs, [("L", 0x021C07DC), ("L", 0x1234), ("C", callee)])
        self.assertEqual(f.end, 2 * (1 + 2 + 2 + 1))        # push, 2 loads, BL, pop
        self.assertEqual(f.tail, len(raw))                  # past its pool

    def test_return_inside_branch_is_not_the_end(self):
        hs = [0x2800, 0xD001, 0x2001, 0xBD00, 0x2002, 0xBD00, 0xFFFF]   # cmp; beq +2; movs; pop; movs; pop
        m = P.Module("arm9", self.BASE, struct.pack(f"<{len(hs)}H", *hs))
        f = P.decode(m, self.BASE | 1)
        self.assertEqual(f.end, 12)
        self.assertEqual(f.refs, [])

    def test_blx_and_tail_branch(self):
        at = self.BASE + 0x100
        back = (0 - (0x100 + 4 + 4)) // 2 & 0x7FF
        hs = bl(at, self.BASE + 0x200, blx=True) + [0xE000 | back]     # blx; b to before the function (a tail branch)
        m = P.Module("arm9", self.BASE, b"\0" * 0x100 + struct.pack(f"<{len(hs)}H", *hs) + b"\0" * 0x200)
        f = P.decode(m, at | 1)
        self.assertEqual(f.refs, [("C", self.BASE + 0x200), ("B", self.BASE | 1)])
        self.assertEqual(f.end, 0x100 + 6)

    def test_arm_function(self):
        ws = [0xE92D4010,                       # stmdb sp!, {r4, lr}
              0xE59F0008,                       # ldr r0, [pc, #8]  -> word 5
              0xEB000000 | ((0x100 - (8 + 8)) // 4 & 0xFFFFFF),   # bl base + 0x100
              0xE8BD8010,                       # ldmia sp!, {r4, pc}
              0xE1A00000,                       # (padding)
              0x021D4158]
        m = P.Module("arm9", self.BASE, struct.pack(f"<{len(ws)}I", *ws) + b"\0" * 0x200)
        f = P.decode(m, self.BASE)
        self.assertFalse(f.thumb)
        self.assertEqual(f.refs, [("L", 0x021D4158), ("C", self.BASE + 0x100)])
        self.assertEqual(f.end, 16)

    def test_normalised_code_is_the_same_elsewhere(self):
        """The same function at another address, calling elsewhere and loading another global: equal instructions."""
        a_raw, a = thumb_fn(0x40, [0x300 | 1], [0x021C07DC])
        b_raw, b = thumb_fn(0x62, [0x380 | 1], [0x021C09BC])
        ma = P.Module(1, 0x02200000, layout(0, [(0x40, a_raw)])[1])
        mb = P.Module(1, 0x02200000, layout(0, [(0x62, b_raw)])[1])
        fa, fb = P.decode(ma, 0x02200000 + a), P.decode(mb, 0x02200000 + b)
        self.assertEqual(fa.toks, fb.toks)
        self.assertNotEqual(a_raw, b_raw)
        needle = ma.thumb_norm()[fa.start:fa.end]
        self.assertEqual(mb.thumb_norm().find(needle), fb.start)

    def test_loose_masks_immediates_only(self):
        self.assertEqual(P.loosen(0x2102, True), P.loosen(0x2103, True))      # movs r1, #2 / #3
        self.assertNotEqual(P.loosen(0x2102, True), P.loosen(0x2202, True))   # movs r1 / movs r2
        self.assertEqual(P.loosen(0xBD10, True), 0xBD10)

    def test_parse_addr(self):
        self.assertEqual(P.parse_addr("0x0215E841@10"), (0x0215E841, 10))
        self.assertEqual(P.parse_addr("0x0203BEF1"), (0x0203BEF1, None))


class Match(unittest.TestCase):
    """Two made-up carts: the other's overlay moved 0x20 and its globals 0x40, plus a decoy."""

    def carts(self):
        base = 0x02200000
        us_g, ot_g = 0x021C0000, 0x021C0040
        # US: F (long, calls G and S) at 0x40, G at 0x200, S (a stub, `bx lr`) at 0x2F0, H at 0x300
        g_raw, _ = thumb_fn(0x200, [], [us_g + 8], filler=3)
        h_raw, _ = thumb_fn(0x300, [], [], filler=2)
        f_raw, _ = thumb_fn(0x40, [0x200 | 1, 0x2F0 | 1], [us_g, 0x55], filler=30)
        stub = struct.pack("<2H", 0x4770, 0x46C0)
        ptrs = struct.pack("<2I", base + 0x40 | 1, base + 0x300 | 1)        # a table pointing at F and H
        us = layout(base, [(0x40, f_raw), (0x200, g_raw), (0x2F0, stub), (0x300, h_raw), (0x3F0, ptrs)])
        # other: everything 0x20 later; the stub after G too; two copies of H (ambiguous, no anchors)
        g2, _ = thumb_fn(0x220, [], [ot_g + 8], filler=3)
        h2, _ = thumb_fn(0x320, [], [], filler=2)
        h3, _ = thumb_fn(0x380, [], [], filler=2)
        f2, _ = thumb_fn(0x60, [0x220 | 1, 0x310 | 1], [ot_g, 0x55], filler=30)
        ot = layout(base, [(0x60, f2), (0x220, g2), (0x310, stub), (0x320, h2), (0x380, h3)])
        return FakeSide({7: us}), FakeSide({7: ot})

    def test_seed_calls_and_votes(self):
        us, ot = self.carts()
        mp = P.Mapper(us, ot).run()
        base = 0x02200000
        self.assertEqual(mp.fmap.get((7, base + 0x40 | 1)), base + 0x60 | 1)      # F by structure (a seed)
        self.assertEqual(mp.fmap.get((7, base + 0x200 | 1)), base + 0x220 | 1)    # G through F's call slot
        self.assertEqual(mp.fmap.get((7, base + 0x2F0 | 1)), base + 0x310 | 1)    # the stub through the other slot
        self.assertEqual(mp.word(0x021C0000)[:2], (0x021C0040, 1))                # F's global
        self.assertEqual(mp.word(0x021C0008)[:2], (0x021C0048, 1))                # G's
        self.assertIsNone(mp.word(0x021C0100))

    def test_ambiguous_is_unresolved(self):
        us, ot = self.carts()
        mp = P.Mapper(us, ot).run()
        got, text = P.answer(mp, "c", 0x02200300 | 1, 7)
        self.assertIsNone(got)
        self.assertTrue(text.startswith("UNRESOLVED"))

    def test_answer_text(self):
        us, ot = self.carts()
        mp = P.Mapper(us, ot).run()
        got, text = P.answer(mp, "c", 0x02200040 | 1, 7)
        self.assertEqual(got, 0x02200061)
        self.assertIn("0x02200061", text)


# Exact answers per section today: a run may do better, never worse, and never wrong.
EXPECT = {
    "platinum": 25, "heartgold": 23, "soulsilver": 23, "black": 13, "white": 13, "heartgold_de": 19, "soulsilver_de": 19,
    "black_to_white": 13, "black2_to_white2": 13, "heartgold_to_origin": 20,
}


class KnownPairs(unittest.TestCase):
    def test_known_pairs(self):
        roms = P.rom_dir()
        path = os.path.join(HERE, "port_nds_fns_known.txt")
        sections = P.read_check_file(path)
        present = [s for s, u, o, _ in sections if os.path.exists(os.path.join(roms, u)) and os.path.exists(os.path.join(roms, o))]
        if not present:
            self.skipTest(f"no ROMs in {roms}")
        res = P.check(path, roms, verbose="-v" in sys.argv)
        for section in present:
            exact, unres, wrong, total = res[section][:4]
            with self.subTest(section=section):
                self.assertEqual(wrong, 0, f"{section}: {wrong} wrong")
                self.assertGreaterEqual(exact, EXPECT.get(section, 0), f"{section}: {exact} exact of {total}")


if __name__ == "__main__":
    unittest.main()
