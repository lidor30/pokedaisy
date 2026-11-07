#!/usr/bin/env python3
"""Maps a DS cart's addresses from its US / Europe build into another language's by matching functions, not bytes.

    python3 scripts/port_nds_fns.py US.nds OTHER.nds [w:0x021D4158 c:0x0203BEF1 c:0x0215E841@10 ...] [--file ADDRS]
    python3 scripts/port_nds_fns.py --check scripts/port_nds_fns_known.txt [--rom-dir DIR] [--compare] [--only SECTION]

w: is a RAM / data word (a global), c: a function (odd = Thumb), @N the overlay it's in (overlays overlap: name it). An
ADDRS file has a `name kind addr[@overlay]` a line (kind w or c, # comments).

How (scripts/port_nds.py lines bytes up; this follows the code):
1. Both carts' ARM9 (its ITCM / DTCM blocks apart) and overlays, unpacked by port_nds.Cart. A function is only ever looked
   for in the same module of the other cart (ARM9, ITCM or overlay N).
2. A function is decoded from its start (Thumb if odd, ARM if even) to its last return, past every forward branch, at most
   MAX_FUNC bytes. Each instruction is kept exactly but the PC-relative parts: a literal load's offset, an ADR's, BL / BLX
   and branches leaving the function. Those come back as references, compared on their own: a literal word that is a small
   constant must be equal; one that is an address, and a call's target, must land where the mapping so far says (unknown:
   no say; known and elsewhere: no match).
3. Seeds: long functions (MIN_SEED instructions) whose normalised body occurs once in that module of each cart.
4. Propagation to a fixed point: a matched pair gives its callees (the same call slot = the same function), the code
   pointers in its literal pool and in data tables it loads, and votes for the RAM / data words it loads (US word -> the
   other's). Every derived pair is checked again by structure; two answers for one address, or one function for two, drop
   both.
5. Every other function the US cart calls or points to is looked for between its matched neighbours' matches: the same body
   with references that agree, and either ANCHORS of them landing right and no other such place in the window, or exactly
   its neighbours' shift (long, anchored, or held tight between them), or right after (before) its matched neighbour.
   A pair out of step with both its neighbours is dropped at the end (a linker keeps a module's functions in order).
6. A requested function whose body differs only in immediates (a buffer size, a language's constant) is matched loosely,
   with LOOSE_ANCHORS references landing right and no other place; a word no matched function loads, through the
   functions that load it, matched the same ways. Ambiguous = UNRESOLVED, never a guess.

Output is addresses only (never code or ROM bytes): a function with how it was found and how many matches confirmed it, a
word with how many matched functions vote for it. An UNRESOLVED one may carry a hint (what its voted neighbour words
suggest, or where the function after its matched neighbour starts): a lead to check, not an answer. Check every answer
live (nds_dump) before it goes into a table. --check runs a file of known pairs (port_nds_fns_known.txt, every pair checked
live) and counts exact / unresolved / wrong; scripts/test_port_nds_fns.py runs it as a test.
"""
import argparse
import bisect
import collections
import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from port_nds import Cart, u32  # noqa: E402

MAX_FUNC = 0x2000        # a function's decode stops here (unbounded: no match)
MIN_SEED = 24            # instructions a function needs to match by structure alone
ANCHORS = 2              # mapped references a searched match needs,
LONG_BODY = 24           # or 1 with this many instructions
BRACKET_BODY = 12        # a match at exactly its neighbours' shift needs this many instructions, or an anchor,
TIGHT = 0x40             # or both neighbours this close
LOOSE_ANCHORS = 4        # a requested function whose constants changed: mapped references landing right
WINDOW = 0x400           # first search window around the predicted place, then WIDE_WINDOW
WIDE_WINDOW = 0x4000
TABLE_WORDS = 64         # words followed into a mapped data table for code pointers


def u16(b, o):
    return b[o] | b[o + 1] << 8


def is_addr(w):
    """A word that is an address in the ARM9's view (main RAM, ITCM, DTCM), not a constant."""
    return 0x02000000 <= w < 0x02400000 or 0x01FF8000 <= w < 0x02000000 or 0x027E0000 <= w < 0x02800000


class Module:
    def __init__(self, key, base, data):
        self.key, self.base, self.data = key, base, bytes(data)
        self.end = base + len(data)
        self._tn = self._an = None

    def has(self, a):
        return self.base <= a < self.end

    def thumb_norm(self):
        """Every halfword normalised as if it were a Thumb instruction: the module's Thumb code as a searchable string."""
        if self._tn is None:
            d = self.data
            n = len(d) & ~1
            hs = struct.unpack_from(f"<{n // 2}H", d)
            out = []
            for h in hs:
                t = h >> 11
                if t in (0x1E, 0x1F, 0x1D, 0x1C):    # BL / BLX halves, B
                    h &= 0xF800
                elif t in (0x09, 0x14):              # LDR rd, [pc, #]; ADD rd, pc, #
                    h &= 0xFF00
                out.append(h)
            self._tn = struct.pack(f"<{len(out)}H", *out)
        return self._tn

    def loose_norm(self, thumb):
        """As thumb_norm / arm_norm with every immediate masked too (loosen)."""
        k = "_lt" if thumb else "_la"
        if getattr(self, k, None) is None:
            if thumb:
                s = self.thumb_norm()
                hs = struct.unpack(f"<{len(s) // 2}H", s)
                setattr(self, k, struct.pack(f"<{len(hs)}H", *(loosen(h, True) for h in hs)))
            else:
                s = self.arm_norm()
                ws = struct.unpack(f"<{len(s) // 4}I", s)
                setattr(self, k, struct.pack(f"<{len(ws)}I", *(loosen(w, False) for w in ws)))
        return getattr(self, k)

    def arm_norm(self):
        if self._an is None:
            d = self.data
            n = len(d) & ~3
            ws = struct.unpack_from(f"<{n // 4}I", d)
            out = []
            for w in ws:
                if (w >> 25) & 7 == 5:                       # B / BL / BLX imm
                    w &= 0xFF000000
                elif w & 0x0F7F0000 == 0x051F0000 or w & 0x0FEF0000 in (0x028F0000, 0x024F0000):
                    w &= 0xFFFFF000                          # LDR rd, [pc, #]; ADR
                out.append(w)
            self._an = struct.pack(f"<{len(out)}I", *out)
        return self._an


class Func:
    """A decoded function: its normalised instructions (toks), where it ends, and its references in order."""

    __slots__ = ("addr", "thumb", "start", "end", "toks", "refs", "first_pool", "tail")

    def __init__(self, addr, thumb, start, end, toks, refs, first_pool, pool=()):
        self.addr, self.thumb, self.start, self.end = addr, thumb, start, end
        self.toks, self.refs, self.first_pool = toks, refs, first_pool
        self.tail = max([w + 4 for w in pool] + [end])     # past its literal pool


def loosen(t, thumb):
    """An instruction token with its immediates masked (a constant or struct offset that changed between builds)."""
    if t < 0:
        return t
    if thumb:
        if t >= 0x10000:
            return t
        if t < 0x1800 or 0x6000 <= t < 0x9000:     # shifts by imm5; ldr / str [rn, #imm5]
            return t & 0xF83F
        if 0x1C00 <= t < 0x2000:                   # add / sub rd, rn, #imm3
            return t & 0xFE3F
        if 0x2000 <= t < 0x4000 or 0x9000 <= t < 0xA000 or 0xA800 <= t < 0xB000:
            return t & 0xFF00                      # mov / cmp / add / sub #imm8; sp-relative
        if t & 0xFF00 == 0xB000:                   # add / sub sp, #imm7
            return t & 0xFF80
        return t
    if t >= 1 << 32:
        return t
    op = (t >> 25) & 7
    if op in (1, 2):                               # data processing / ldr / str with an immediate
        return t & ~0xFFF
    if op == 0 and t & 0x00400090 == 0x00400090:   # ldrh / strh with an immediate
        return t & ~0xF0F
    return t


def sext(v, bits):
    return v - (1 << bits) if v & (1 << (bits - 1)) else v


def decode_thumb(m, addr):
    d, base = m.data, m.base
    o = (addr & ~1) - base
    n = len(d)
    if o < 0 or o + 2 > n:
        return None
    toks, refs, pool = [], [], set()
    first_pool = None
    maxfwd = o
    pos, lim = o, min(n, o + MAX_FUNC)
    while pos + 2 <= lim:
        if pos in pool:                       # a literal pool inside the function (after a branch over it)
            if first_pool is None:
                first_pool = pos
            toks.append(-1)
            pos += 4
            continue
        h = u16(d, pos)
        t = h >> 11
        end = False
        if t == 0x1E:                         # BL / BLX prefix
            if pos + 4 > n:
                return None
            h2 = u16(d, pos + 2)
            t2 = h2 >> 11
            if t2 not in (0x1F, 0x1D):
                return None
            off = sext(((h & 0x7FF) << 12) | ((h2 & 0x7FF) << 1), 23)
            tgt = base + pos + 4 + off
            tgt = tgt & ~3 if t2 == 0x1D else tgt | 1
            toks.append(0x10000 | t2)
            refs.append(("C", tgt))
            pos += 4
            continue
        if t in (0x1F, 0x1D):                 # a lone BL suffix: not code
            return None
        if t == 0x09:                         # LDR rd, [pc, #imm]
            wo = ((pos + 4) & ~3) + (h & 0xFF) * 4
            if wo + 4 > n:
                return None
            pool.add(wo)
            toks.append(0x20000 | (h & 0xFF00))
            refs.append(("L", u32(d, wo)))
        elif t == 0x14:                       # ADR
            toks.append(0x20000 | (h & 0xFF00))
            refs.append(("A", base + ((pos + 4) & ~3) + (h & 0xFF) * 4))
        elif h >> 12 == 0xD and (h >> 8) & 0xF < 0xE:     # B<cond>
            tgt = pos + 4 + sext(h & 0xFF, 8) * 2
            if tgt < o:
                toks.append(0x30000 | (h & 0xFF00))
                refs.append(("B", base + tgt | 1))
            else:
                toks.append(h)
                maxfwd = max(maxfwd, tgt)
        elif t == 0x1C:                       # B
            tgt = pos + 4 + sext(h & 0x7FF, 11) * 2
            if o <= tgt < lim:
                toks.append(h)
                if tgt > pos:
                    maxfwd = max(maxfwd, tgt)
                else:
                    end = True
            else:
                toks.append(0x30000 | 0xE000)
                refs.append(("B", base + tgt | 1))
                end = True
        else:
            toks.append(h)
            # pop {..., pc}; bx lr / bx rN; mov pc, rN; add pc, rN is a jump table: carry on through it
            end = h & 0xFF00 == 0xBD00 or h & 0xFF87 == 0x4700 or h & 0xFF87 == 0x4687
        pos += 2
        if end and pos > maxfwd:
            return Func(addr, True, o, pos, toks, refs, first_pool, pool)
    return None


def decode_arm(m, addr):
    d, base = m.data, m.base
    o = addr - base
    n = len(d)
    if addr & 3 or o < 0 or o + 4 > n:
        return None
    toks, refs, pool = [], [], set()
    first_pool = None
    maxfwd = o
    pos, lim = o, min(n, o + MAX_FUNC)
    while pos + 4 <= lim:
        if pos in pool:
            if first_pool is None:
                first_pool = pos
            toks.append(-1)
            pos += 4
            continue
        w = u32(d, pos)
        cond = w >> 28
        end = False
        if (w >> 25) & 7 == 5:
            off = sext(w & 0xFFFFFF, 24) * 4
            tgt = pos + 8 + off
            if cond == 0xF:                   # BLX imm: to Thumb
                tgt = base + tgt + ((w >> 23) & 2) | 1
                toks.append(0x100000000 | (w & 0xFF000000))
                refs.append(("C", tgt))
            elif w >> 24 & 1:                 # BL
                toks.append(0x100000000 | (w & 0xFF000000))
                refs.append(("C", base + tgt))
            elif o <= tgt < lim:              # B inside
                toks.append(w)
                if tgt > pos:
                    maxfwd = max(maxfwd, tgt)
                elif cond == 0xE:
                    end = True
            else:
                toks.append(0x100000000 | (w & 0xFF000000))
                refs.append(("B", base + tgt))
                end = cond == 0xE
        elif w & 0x0F7F0000 == 0x051F0000:    # LDR rd, [pc, #+-imm]
            wo = pos + 8 + ((w & 0xFFF) if w & 0x800000 else -(w & 0xFFF))
            if wo < 0 or wo + 4 > n or wo & 3:
                return None
            if wo > pos:
                pool.add(wo)
            toks.append(0x100000000 | (w & 0xFFFFF000))
            refs.append(("L", u32(d, wo)))
        elif w & 0x0FEF0000 in (0x028F0000, 0x024F0000):  # ADR
            rot = (w >> 8 & 0xF) * 2
            imm = w & 0xFF
            imm = (imm >> rot | imm << (32 - rot)) & 0xFFFFFFFF
            a = pos + 8 + (imm if w & 0x0FEF0000 == 0x028F0000 else -imm)
            toks.append(0x100000000 | (w & 0xFFFFF000))
            refs.append(("A", base + a))
        else:
            toks.append(w)
            if cond == 0xE:
                # bx lr / bx rN; ldmia sp!, {..., pc}; ldr pc, [sp], #4; mov pc, lr
                end = (w & 0x0FFFFFF0 == 0x012FFF10 or w & 0x0FFF8000 == 0x08BD8000 or w == 0xE49DF004
                       or w == 0xE1A0F00E)
        pos += 4
        if end and pos > maxfwd:
            return Func(addr, False, o, pos, toks, refs, first_pool, pool)
    return None


def decode(m, addr):
    return decode_thumb(m, addr) if addr & 1 else decode_arm(m, addr)


class Side:
    """One cart's modules, its function decodes cached."""

    def __init__(self, path):
        self.path = path
        self.cart = Cart(path)
        self.mods = {}
        for k, (base, data) in self.cart.mods.items():
            if k == "arm9":
                for key, b, blob in split_arm9(base, data):
                    self.mods[key] = Module(key, b, blob)
            else:
                self.mods[k] = Module(k, base, data)
        self._funcs = {}

    def func(self, key, addr):
        k = (key, addr)
        if k not in self._funcs:
            m = self.mods.get(key)
            self._funcs[k] = decode(m, addr) if m else None
        return self._funcs[k]

    def module_of(self, ctx, a):
        """The module an address seen from module [ctx] lands in: ctx itself, else the ARM9's parts; else 'ram'."""
        for key in (ctx, "arm9", "itcm", "dtcm"):
            m = self.mods.get(key)
            if m and m.has(a & ~1):
                return key
        return "ram" if is_addr(a) else None


def split_arm9(base, data):
    """The ARM9 image without the blocks it copies to ITCM / DTCM at boot (crt0's module params: autoload list)."""
    n = len(data)
    for o in range(0, min(0x1000, n - 4), 4):
        w = u32(data, o)
        if not base < w < base + n - 0x1C:
            continue
        p = struct.unpack_from("<4I", data, w - base)
        ls, le, ast = p[0], p[1], p[2]
        if not (base <= ast <= ls < le <= base + n):
            continue
        size = 12 if (le - ls) % 12 == 0 else 16
        out = [("arm9", base, data[:ast - base])]
        src = ast - base
        for i in range((le - ls) // size):
            run, sz = struct.unpack_from("<2I", data, ls - base + i * size)
            if run == 0x01FF8000 and "itcm" not in [x[0] for x in out]:
                out.append(("itcm", run, data[src:src + sz]))
            elif run == 0x027E0000 and "dtcm" not in [x[0] for x in out]:
                out.append(("dtcm", run, data[src:src + sz]))
            src += sz
        return out
    return [("arm9", base, data)]


class Mapper:
    def __init__(self, us, other):
        self.us, self.ot = us, other
        self.keys = [k for k in us.mods if k in other.mods]
        self.fmap = {}                              # (module, US function address) -> other address
        self.rev = {}                               # (module, other address) -> US address
        self.rejected = set()                       # (module, US, other) pairs out of step with their neighbours
        self.how = {}                               # (module, US address) -> (source, evidence count)
        self.conflict = set()
        self.dvotes = collections.defaultdict(lambda: collections.defaultdict(set))   # (module|'ram', word) -> other -> voters
        self.queue = []
        self.walked = set()
        self.sorted = {}
        self._starts = {}
        self._loaders = None

    # ---- lookups

    def known(self, key):
        if key in self.conflict:
            return None
        v = self.fmap.get(key)
        if v is not None:
            return v
        votes = self.dvotes.get(key)
        if votes and len(votes) == 1:
            return next(iter(votes))
        return None

    def compare(self, ctx, fu, fo):
        """(agrees, anchors) for two decodes with equal instructions."""
        anchors = 0
        for (ku, vu), (ko, vo) in zip(fu.refs, fo.refs):
            if ku != ko:
                return False, 0
            if ku == "L" and not is_addr(vu):
                if vu != vo:
                    return False, 0
                continue
            if is_addr(vu) != is_addr(vo):
                return False, 0
            mod = self.us.module_of(ctx, vu)
            if mod is None:
                continue
            k = self.known((mod, vu))
            if k is None:
                continue
            if k != vo:
                return False, 0
            anchors += 1
        return True, anchors

    def check(self, key, ua, oa):
        """US function [ua] against the other cart's [oa] in module [key]: (agrees, anchors, instructions)."""
        fu, fo = self.us.func(key, ua), self.ot.func(key, oa)
        if fu is None or fo is None or fu.toks != fo.toks:
            return False, 0, 0
        ok, anchors = self.compare(key, fu, fo)
        return ok, anchors, len(fu.toks)

    # ---- matches

    def add(self, key, ua, oa, source):
        k = (key, ua)
        if k in self.conflict or (key, ua, oa) in self.rejected:
            return False
        have = self.fmap.get(k)
        if have is not None:
            if have != oa:
                self.conflict.add(k)
                del self.fmap[k]
                self.sorted.pop(key, None)
                return False
            s, n = self.how[k]
            self.how[k] = (s, n + 1)
            return False
        if self.rev.get((key, oa), ua) != ua:   # one other function for two US ones: neither is sure
            self.conflict.add(k)
            other = (key, self.rev[(key, oa)])
            self.conflict.add(other)
            self.fmap.pop(other, None)
            self.sorted.pop(key, None)
            return False
        self.fmap[k] = oa
        self.rev[(key, oa)] = ua
        self.how[k] = (source, 1)
        if key in self.sorted:                  # keep the sorted view current for the next search
            xs, ds = self.sorted[key]
            j = bisect.bisect_left(xs, ua & ~1)
            xs.insert(j, ua & ~1)
            ds.insert(j, (oa & ~1) - (ua & ~1))
        self.queue.append(k)
        return True

    def derive(self, key, ua, oa, source):
        """A pair implied by a matched one (a call slot, a code pointer): kept only if its structure agrees too."""
        if key not in self.ot.mods or (key, ua) in self.conflict:
            return
        if not self.ot.mods[key].has(oa & ~1) or (ua ^ oa) & 1:
            return
        have = self.fmap.get((key, ua))
        if have is not None:
            self.add(key, ua, oa, source)
            return
        ok, _, _ = self.check(key, ua, oa)
        if ok:
            self.add(key, ua, oa, source)

    def propagate(self):
        while self.queue:
            key, ua = self.queue.pop()
            oa = self.fmap.get((key, ua))
            if oa is None:
                continue
            fu, fo = self.us.func(key, ua), self.ot.func(key, oa)
            if fu is None or fo is None or fu.toks != fo.toks:
                continue
            for (kind, vu), (_, vo) in zip(fu.refs, fo.refs):
                if not is_addr(vu) or not is_addr(vo):
                    continue
                mod = self.us.module_of(key, vu)
                if mod is None:
                    continue
                if kind in ("C", "B"):
                    if mod != "ram":
                        self.derive(mod, vu, vo, "call")
                    continue
                # a literal / ADR: a code pointer is a function, anything else a vote for that word
                if mod != "ram" and vu & 1 and self.us.func(mod, vu) is not None:
                    self.derive(mod, vu, vo, "pointer")
                    continue
                self.dvotes[(mod, vu)][vo].add((key, ua))
                if mod != "ram":
                    self.walk(mod, vu, vo)

    def walk(self, mod, du, do):
        """Code pointers in a mapped data table: the same word of both carts, while the two tables keep step."""
        if (mod, du) in self.walked:
            return
        self.walked.add((mod, du))
        mu, mo = self.us.mods[mod], self.ot.mods.get(mod)
        if mo is None or du & 3 or do & 3:
            return
        for i in range(TABLE_WORDS):
            a, b = du - mu.base + 4 * i, do - mo.base + 4 * i
            if a + 4 > len(mu.data) or b + 4 > len(mo.data) or a < 0 or b < 0:
                return
            wu, wo = u32(mu.data, a), u32(mo.data, b)
            if is_addr(wu) != is_addr(wo) or not is_addr(wu) and wu != wo:
                return
            if is_addr(wu) and wu & 1 and (wu ^ wo) & 1 == 0:
                m2 = self.us.module_of(mod, wu)
                if m2 not in (None, "ram"):
                    self.derive(m2, wu, wo, "table")

    # ---- seeds and search

    def starts(self, key):
        """The US module's function starts: BL / BLX targets, and Thumb code pointers in data that open with a push."""
        if key not in self._starts:
            self._starts[key] = self._find_starts(key)
        return self._starts[key]

    def _find_starts(self, key):
        m = self.us.mods[key]
        d, base = m.data, m.base
        found = collections.Counter()
        n = len(d)
        for pos in range(0, n - 3, 2):
            h = d[pos + 1]
            if h >> 3 == 0x1E and d[pos + 3] >> 3 in (0x1F, 0x1D):
                h1, h2 = u16(d, pos), u16(d, pos + 2)
                off = sext(((h1 & 0x7FF) << 12) | ((h2 & 0x7FF) << 1), 23)
                tgt = base + pos + 4 + off
                tgt = tgt & ~3 if h2 >> 11 == 0x1D else tgt | 1
                if m.has(tgt & ~1):
                    found[tgt] += 1
        for pos in range(0, n - 3, 4):
            w = u32(d, pos)
            if w & 1 and m.has(w & ~1):
                o = (w & ~1) - base
                if d[o + 1] in (0xB4, 0xB5):
                    found[w] += 1
            elif w >> 24 == 0xEB:             # ARM BL, always
                tgt = base + pos + 8 + sext(w & 0xFFFFFF, 24) * 4
                if m.has(tgt):
                    found[tgt] += 1
        out = []
        for a, c in found.items():
            o = (a & ~1) - base
            if a & 1:
                if c >= 2 or d[o + 1] in (0xB4, 0xB5):
                    out.append(a)
            elif c >= 2 or u32(d, o) >> 16 == 0xE92D:
                out.append(a)
        return sorted(out)

    def search_key(self, side, key, f):
        m = side.mods[key]
        s = m.thumb_norm() if f.thumb else m.arm_norm()
        unit = 2 if f.thumb else 4
        stop = f.first_pool if f.first_pool is not None else f.end
        return s, unit, s[f.start:stop]       # the normalised strings keep the module's byte layout

    def occurrences(self, hay, needle, unit, lo=0, hi=None, limit=None):
        hi = len(hay) if hi is None else hi
        out = []
        i = hay.find(needle, max(0, lo), hi + len(needle))
        while i >= 0:
            if i % unit == 0:
                out.append(i)
                if limit and len(out) >= limit:
                    break
            i = hay.find(needle, i + 1, hi + len(needle))
        return out

    def seed(self):
        for key in self.keys:
            mu, mo = self.us.mods[key], self.ot.mods[key]
            for ua in self.starts(key):
                f = self.us.func(key, ua)
                if f is None or len(f.toks) < MIN_SEED or (key, ua) in self.fmap:
                    continue
                hay_u, unit, needle = self.search_key(self.us, key, f)
                if len(needle) < MIN_SEED * unit // 2:
                    continue
                if len(self.occurrences(hay_u, needle, unit, limit=2)) != 1:
                    continue
                hay_o = mo.thumb_norm() if f.thumb else mo.arm_norm()
                hits = self.occurrences(hay_o, needle, unit, limit=2)
                if len(hits) != 1:
                    continue
                oa = mo.base + hits[0] + (ua & 1)
                ok, _, _ = self.check(key, ua, oa)
                if ok:
                    self.add(key, ua, oa, "seed")
        self.propagate()

    def shifts(self, key):
        if key not in self.sorted:
            pairs = sorted((ua & ~1, oa & ~1) for (k, ua), oa in self.fmap.items() if k == key)
            self.sorted[key] = ([p[0] for p in pairs], [p[1] - p[0] for p in pairs])
        return self.sorted[key]

    def orig(self, key, x):
        """The mapped address (Thumb bit and all) behind a sorted entry."""
        return x | 1 if (key, x | 1) in self.fmap else x

    def adjacent(self, key, ua, f, xs, i):
        """[ua] sits right after its matched predecessor's literal pool (or right before its matched successor): the same
        body right after (before) their match is its own, the linker keeping a module's functions in order."""
        mu, mo = self.us.mods[key], self.ot.mods[key]
        pads = (0, 2) if ua & 1 else (0,)
        tries = []
        if i > 0:
            pu = self.orig(key, xs[i - 1])
            fp, fq = self.us.func(key, pu), self.ot.func(key, self.fmap[(key, pu)])
            if fp and fq and 0 <= (ua & ~1) - (mu.base + fp.tail) < 4:
                tries += [mo.base + fq.tail + p + (ua & 1) for p in pads]
        if i < len(xs) and 0 <= xs[i] - (mu.base + f.tail) < 4:
            no = self.fmap[(key, self.orig(key, xs[i]))] & ~1
            gap = xs[i] - (ua & ~1)
            tries += [no - gap + p + (ua & 1) for p in ((0, -2, 2) if ua & 1 else (0,))]
        for c in tries:
            if mo.has(c & ~1) and self.rev.get((key, c), ua) == ua:
                ok, anchors, _ = self.check(key, ua, c)
                if ok:
                    return c, anchors, "adjacent"
        return None

    @staticmethod
    def between(xs, ds, i):
        """A test that an other-cart address lies between the matches of the US neighbours around sorted place i."""
        lo = xs[i - 1] + ds[i - 1] if i > 0 else -1
        hi = xs[i] + ds[i] if i < len(xs) else 1 << 32
        return lambda a: lo < (a & ~1) < hi

    def find(self, key, ua):
        """Search the other module near where [ua]'s matched neighbours moved: (other address, anchors, how) or None."""
        if key not in self.ot.mods:
            return None
        f = self.us.func(key, ua)
        if f is None:
            return None
        mo = self.ot.mods[key]
        xs, ds = self.shifts(key)
        i = bisect.bisect_left(xs, ua & ~1)
        prev = ds[i - 1] if i > 0 else None
        nxt = ds[i] if i < len(xs) else None
        near = [d for d in (prev, nxt) if d is not None] or [0]
        hay_o = mo.thumb_norm() if f.thumb else mo.arm_norm()
        unit = 2 if f.thumb else 4
        _, _, needle = self.search_key(self.us, key, f)
        if not needle:
            return None
        o = (ua & ~1) - self.us.mods[key].base
        # exactly at both neighbours' shift: long enough or anchored, or held tight between them (a stub)
        if prev is not None and prev == nxt:
            oa = ua + prev
            tight = (ua & ~1) - xs[i - 1] <= TIGHT and xs[i] - (ua & ~1) <= TIGHT
            if mo.has(oa & ~1) and self.rev.get((key, oa), ua) == ua:
                ok, anchors, n = self.check(key, ua, oa)
                if ok and (n >= BRACKET_BODY or anchors >= 1 or tight):
                    return oa, anchors, "bracket"
        adj = self.adjacent(key, ua, f, xs, i)
        if adj:
            return adj
        inside = self.between(xs, ds, i)
        for w in (WINDOW, WIDE_WINDOW):
            lo, hi = o + min(near) - w, o + max(near) + w
            good = []
            for p in self.occurrences(hay_o, needle, unit, lo, hi):
                oa = mo.base + p + (ua & 1)
                if self.rev.get((key, oa), ua) != ua or not inside(oa):
                    continue
                ok, anchors, n = self.check(key, ua, oa)
                if ok:
                    good.append((oa, anchors, n))
            if len(good) > 1:
                return None
            if len(good) == 1:
                oa, anchors, n = good[0]
                if anchors >= ANCHORS or anchors >= 1 and n >= LONG_BODY:
                    return oa, anchors, "search"
                if n < MIN_SEED:
                    return None
                if w == WIDE_WINDOW:                # long, and alone in the wide window too
                    return oa, anchors, "search"
        return None

    def find_loose(self, key, ua):
        """A requested function whose instructions are the US one's but for immediates (a buffer size, a language's
        constant): only with LOOSE_ANCHORS mapped references landing right and no other such place in the window."""
        if key not in self.ot.mods:
            return None
        fu = self.us.func(key, ua)
        if fu is None:
            return None
        mo, mu = self.ot.mods[key], self.us.mods[key]
        unit = 2 if fu.thumb else 4
        stop = fu.first_pool if fu.first_pool is not None else fu.end
        needle = mu.loose_norm(fu.thumb)[fu.start:stop]
        hay = mo.loose_norm(fu.thumb)
        want = [loosen(t, fu.thumb) for t in fu.toks]
        xs, ds = self.shifts(key)
        i = bisect.bisect_left(xs, ua & ~1)
        near = [ds[j] for j in (i - 1, i) if 0 <= j < len(xs)] or [0]
        o = (ua & ~1) - mu.base
        inside = self.between(xs, ds, i)
        good = []
        for p in self.occurrences(hay, needle, unit, o + min(near) - WIDE_WINDOW, o + max(near) + WIDE_WINDOW):
            oa = mo.base + p + (ua & 1)
            if self.rev.get((key, oa), ua) != ua or not inside(oa):
                continue
            fo = self.ot.func(key, oa)
            if fo is None or [loosen(t, fo.thumb) for t in fo.toks] != want:
                continue
            anchors, ok = 0, True
            for (ku, vu), (ko, vo) in zip(fu.refs, fo.refs):
                if ku != ko or is_addr(vu) != is_addr(vo):
                    ok = False
                    break
                if not is_addr(vu):
                    continue
                mod = self.us.module_of(key, vu)
                k = self.known((mod, vu)) if mod else None
                if k is None:
                    continue
                if k != vo:
                    ok = False
                    break
                anchors += 1
            if ok:
                good.append((oa, anchors))
        if len(good) == 1 and good[0][1] >= LOOSE_ANCHORS:
            return good[0][0], good[0][1], "loose"
        return None

    def sweep(self, rounds=4):
        todo = {key: self.starts(key) for key in self.keys}
        for _ in range(rounds):
            added = 0
            for key, starts in todo.items():
                self.sorted.pop(key, None)
                for ua in starts:
                    if (key, ua) in self.fmap or (key, ua) in self.conflict:
                        continue
                    r = self.find(key, ua)
                    if r:
                        if self.add(key, ua, r[0], r[2]):
                            added += 1
                self.propagate()
                self.sorted.pop(key, None)
            if not added:
                break

    def prune(self):
        """Drops a pair whose shift differs from both its neighbours' while theirs agree: a linker keeps a module's
        functions in order, so it moved alone only by mistake (a data word that looked like a code pointer)."""
        dropped = 0
        for key in self.keys:
            self.sorted.pop(key, None)
            xs, ds = self.shifts(key)
            for i in range(1, len(xs) - 1):
                if ds[i - 1] == ds[i + 1] != ds[i] and xs[i + 1] - xs[i - 1] <= 0x2000:
                    for ua in (xs[i] | 1, xs[i]):
                        oa = self.fmap.pop((key, ua), None)
                        if oa is not None:
                            self.rev.pop((key, oa), None)
                            self.rejected.add((key, ua, oa))
                            dropped += 1
            self.sorted.pop(key, None)
        return dropped

    def run(self):
        self.seed()
        self.sweep()
        if self.prune():
            self.sweep(rounds=1)
        return self

    # ---- answers

    def code(self, ua, module=None):
        """[(module, other address, how, evidence)] for a US function address; empty = unresolved."""
        keys = [module] if module is not None else [k for k in self.keys if self.us.mods[k].has(ua & ~1)]
        out = []
        for key in keys:
            if key not in self.us.mods or not self.us.mods[key].has(ua & ~1):
                continue
            k = (key, ua)
            if k in self.fmap:
                out.append((key, self.fmap[k]) + self.how[k] + ("vote",))
                continue
            if k in self.conflict:
                continue
            r = self.find(key, ua) or self.find_loose(key, ua)
            if r:
                out.append((key, r[0], r[2], r[1], "anchor"))
        return out

    def word(self, w):
        """(other word, voters, rivals) for a US RAM / data word, or None."""
        votes = collections.defaultdict(set)
        for (mod, vu), d in self.dvotes.items():
            if vu == w:
                for vo, who in d.items():
                    votes[vo] |= who
        how = "votes"
        if not votes:
            # no matched function loads it: the unmatched ones that do, matched now by search or loosely (find_loose)
            for key, ua in self.loaders(w):
                r = self.find(key, ua) or self.find_loose(key, ua)
                fu, fo = self.us.func(key, ua), r and self.ot.func(key, r[0])
                if not fo:
                    continue
                for (ku, vu), (_, vo) in zip(fu.refs, fo.refs):
                    if ku == "L" and vu == w and is_addr(vo):
                        votes[vo].add((key, ua))
                how = "loose votes"
        if not votes:
            return None
        ranked = sorted(votes.items(), key=lambda x: -len(x[1]))
        best, who = ranked[0]
        return best, len(who), [(v, len(s)) for v, s in ranked[1:]], how

    def loaders(self, w):
        """The US functions (of those it calls or points to) that load the word [w] from their literal pool."""
        if self._loaders is None:
            self._loaders = collections.defaultdict(list)
            for key in self.keys:
                for ua in self.starts(key):
                    f = self.us.func(key, ua)
                    for k, v in f.refs if f else ():
                        if k == "L" and is_addr(v):
                            self._loaders[v].append((key, ua))
        return self._loaders.get(w, [])

    def follows(self, key, ua):
        """For an unmatched function right after a matched one (its literal pool, then this): where the function after
        that one's match starts in the other cart, if it opens with the same instruction. A hint, not an answer: the
        body differs, and a build can add or drop a function there."""
        if key not in self.ot.mods or key not in self.us.mods:
            return None
        mu, mo = self.us.mods[key], self.ot.mods[key]
        o = (ua & ~1) - mu.base
        if o == 0:                              # the module's first function
            tail = 0
        else:
            xs, _ = self.shifts(key)
            i = bisect.bisect_left(xs, ua & ~1)
            if i == 0:
                return None
            pu = self.orig(key, xs[i - 1])
            po = self.fmap.get((key, pu))
            fu, fo = self.us.func(key, pu), po is not None and self.ot.func(key, po)
            if not fu or not fo or not 0 <= o - fu.tail < 4:
                return None
            tail = fo.tail
        unit = 2 if ua & 1 else 4
        su = mu.thumb_norm() if ua & 1 else mu.arm_norm()
        so = mo.thumb_norm() if ua & 1 else mo.arm_norm()
        for pad in range(0, 4, unit):
            c = tail + pad
            if su[o:o + unit] == so[c:c + unit]:
                return mo.base + c + (ua & 1)
        return None

    def hint(self, w, reach=0x400):
        """What the nearest voted words below and above [w] suggest, when they moved alike (a hint, not an answer)."""
        below = above = None
        for (mod, vu), d in self.dvotes.items():
            if len(d) != 1:
                continue
            vo = next(iter(d))
            if w - reach <= vu < w and (below is None or vu > below[0]):
                below = (vu, vo - vu)
            if w < vu <= w + reach and (above is None or vu < above[0]):
                above = (vu, vo - vu)
        if below and above and below[1] == above[1]:
            return w + below[1]
        return None


def parse_addr(s):
    addr, _, mod = s.partition("@")
    return int(addr, 0), (int(mod, 0) if mod and mod not in ("arm9", "itcm", "dtcm") else mod or None)


def answer(mp, kind, addr, mod):
    """(other value or None, text)"""
    if kind == "w":
        r = mp.word(addr)
        if r is None:
            h = mp.hint(addr)
            return None, "UNRESOLVED" + (f" (neighbours suggest {h:#010x})" if h else "")
        v, n, rivals, how = r
        if rivals:
            return None, f"UNRESOLVED ({v:#010x} {n} {how}, also " + ", ".join(f"{x:#010x} {c}" for x, c in rivals) + ")"
        return v, f"{v:#010x}  {n} {how if n != 1 else how[:-1]}"
    res = mp.code(addr, mod)
    vals = {r[1] for r in res}
    if len(vals) == 1:
        key, v, how, ev, unit = res[0]
        where = "" if mod is not None else f" @{key}" if key != "arm9" else ""
        return v, f"{v:#010x}{where}  {how}, {ev} {unit}{'s' if ev != 1 else ''}"
    if len(vals) > 1:
        some = ", ".join(f"{r[1]:#010x} @{r[0]}" for r in res[:4]) + (f" and {len(res) - 4} more" if len(res) > 4 else "")
        return None, f"UNRESOLVED (in {len(res)} modules: {some}; name the overlay)"
    keys = [mod] if mod is not None else [k for k in mp.keys if mp.us.mods[k].has(addr & ~1)]
    after = {mp.follows(k, addr) for k in keys} - {None}
    if len(after) == 1:
        return None, f"UNRESOLVED (its body differs; the function after its matched neighbour starts at {after.pop():#010x})"
    return None, "UNRESOLVED"


def read_addr_file(path):
    """Lines of `name kind addr[@overlay]` (kind w or c), # comments."""
    out = []
    for line in open(path):
        line = line.split("#", 1)[0].split()
        if len(line) >= 3:
            out.append((line[0], line[1], line[2]))
    return out


def rom_dir():
    d = os.environ.get("POKEDAISY_ROM_DIR")
    if d:
        return os.path.join(os.path.dirname(os.path.normpath(d)), "nds")
    return os.path.expanduser("~/Downloads/Game ROMs & Emulation/nds")


def read_check_file(path):
    """[(section, us file, other file, [(name, kind, us addr, module, other addr)])] from a check file."""
    out, cur = [], None
    for raw in open(path):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        m = re.match(r"\[(.+)\]$", line)
        if m:
            cur = [m.group(1), None, None, []]
            out.append(cur)
        elif "=" in line and line.split("=", 1)[0].strip() in ("us", "other"):
            k, v = (x.strip() for x in line.split("=", 1))
            cur[1 if k == "us" else 2] = v
        else:
            name, kind, a, b = line.split()
            addr, mod = parse_addr(a)
            cur[3].append((name, kind, addr, mod, int(b, 0)))
    return [tuple(x) for x in out]


def check(path, roms, compare=False, only=None, verbose=True):
    """Runs every section; returns {section: (exact, unresolved, wrong, total)} (+ port_nds's when compare)."""
    results = {}
    for section, usf, otf, pairs in read_check_file(path):
        if only and section not in only:
            continue
        up, op = os.path.join(roms, usf), os.path.join(roms, otf)
        if not (os.path.exists(up) and os.path.exists(op)):
            if verbose:
                print(f"[{section}] skipped: ROM missing")
            continue
        us, ot = Side(up), Side(op)
        mp = Mapper(us, ot).run()
        exact = unres = wrong = 0
        pexact = punres = pwrong = 0
        if verbose:
            print(f"[{section}] {len(mp.fmap)} functions mapped, {len(mp.conflict)} conflicts")
        for name, kind, addr, mod, want in pairs:
            got, text = answer(mp, kind, addr, mod)
            mark = "ok" if got == want else "--" if got is None else "WRONG"
            exact += got == want
            unres += got is None
            wrong += got is not None and got != want
            extra = ""
            if compare:
                pv = port_nds_answer(us.cart, ot.cart, kind, addr, mod)
                pexact += pv == want
                punres += pv is None
                pwrong += pv is not None and pv != want
                extra = f"   port_nds: {'ok' if pv == want else '--' if pv is None else 'WRONG'}"
            if verbose:
                at = f"@{mod}" if mod is not None else ""
                print(f"  {mark:5} {name:28} {kind} {addr:#010x}{at:5} -> {text}  (want {want:#010x}){extra}")
        results[section] = (exact, unres, wrong, len(pairs)) + ((pexact, punres, pwrong) if compare else ())
        if verbose:
            line = f"[{section}] exact {exact}, unresolved {unres}, wrong {wrong} of {len(pairs)}"
            if compare:
                line += f"   (port_nds.py: exact {pexact}, unresolved {punres}, wrong {pwrong})"
            print(line)
    return results


def port_nds_answer(us, ot, kind, addr, mod):
    import port_nds
    if kind == "w":
        v = port_nds.word(us, ot, addr).most_common(1)
        return v[0][0] if v else None
    r = port_nds.code(us, ot, addr, mod if isinstance(mod, int) else None)
    return r[0][2] if r else None


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__.split("\n", 2)[2])
    ap.add_argument("roms", nargs="*", help="US.nds OTHER.nds, then w:WORD / c:ADDR[@OVERLAY] ...")
    ap.add_argument("--file", help="addresses, one `name kind addr[@overlay]` a line (kind w or c)")
    ap.add_argument("--check", help="a check file of known pairs (port_nds_fns_known.txt)")
    ap.add_argument("--rom-dir", default=rom_dir(), help="where --check finds the ROMs")
    ap.add_argument("--compare", action="store_true", help="--check: score port_nds.py on the same pairs")
    ap.add_argument("--only", nargs="*", help="--check: these sections only")
    a = ap.parse_intermixed_args()
    if a.check:
        res = check(a.check, a.rom_dir, a.compare, a.only)
        sys.exit(1 if any(r[2] for r in res.values()) else 0)
    if len(a.roms) < 2:
        ap.error("give US.nds OTHER.nds")
    mp = Mapper(Side(a.roms[0]), Side(a.roms[1])).run()
    print(f"# {len(mp.fmap)} functions mapped, {len(mp.conflict)} conflicts")
    items = []
    for arg in a.roms[2:]:
        kind, _, rest = arg.partition(":")
        items.append((arg, kind, rest))
    if a.file:
        items += read_addr_file(a.file)
    for name, kind, spec in items:
        addr, mod = parse_addr(spec)
        _, text = answer(mp, kind[0], addr, mod)
        at = f"@{mod}" if mod is not None else ""
        print(f"{name:28} {kind[0]} {addr:#010x}{at:5} -> {text}")


if __name__ == "__main__":
    main()
