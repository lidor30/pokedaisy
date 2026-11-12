#!/usr/bin/env python3
"""RIDE ON's vehicles (MAKEOVER > RIDE ON): a sport motorcycle and a scooter the player rides in place of the game's bike.
The vehicle is a sprite of its own (pk_wide.c's ride: its own 15-colour palette, behind the rider), the rider stays in the
hero's bike frames.
Our own pixel art, made here - no game art - at the game's bike angle: seen a little from above, the wheels flat ovals,
the tops of things lighter, everything low under the rider.

Each vehicle has three views in the hero's 32 x 32 bike frame: south (from the front), north (from behind) and west (the
side; the game mirrors it going east). The player keeps their own upper body from their bike frames (rows up to [CUT];
PlayerLook's frames are untouched there), the vehicle goes round and under it: a byte a pixel, the colour's index
in the vehicle's palette in the low 6 bits (0 = none), 0x40 FRONT (over the rider: a fairing, a windscreen, a leg shield),
0x80 LEG (the rider's leg and shoe: over the body, under FRONT). RideOn.kt maps the colours onto the hero's palette.

Writes app/src/main/kotlin/com/pokedaisy/app/companion/data/RideVehiclesGen.kt and, with --preview DIR, PNGs at 8x.

Usage: scripts/gen_ride_vehicles.py [--preview DIR]
"""
import base64
import os
import sys

K = (0, 0, 0)
TIRE = [(28, 28, 36), (64, 64, 76)]
RIM = [(150, 154, 168), (206, 210, 222)]
STEEL = [(64, 68, 84), (128, 134, 150), (196, 200, 214), (240, 242, 250)]
RED = [(110, 18, 30), (178, 30, 44), (226, 66, 66), (255, 150, 140)]
WHITE = [(176, 180, 196), (226, 228, 238), (252, 252, 255)]
BLACK = [(30, 30, 40), (60, 60, 74), (96, 96, 112)]
MINT = [(64, 146, 128), (106, 194, 168), (164, 230, 204), (224, 255, 240)]
CREAM = [(170, 140, 100), (214, 190, 140), (246, 232, 196)]
SEAT = [(40, 36, 44), (84, 78, 96)]
LIGHT = [(255, 210, 80), (255, 248, 190)]
TAIL = (236, 40, 40)
SMOKE = [(70, 84, 110), (120, 140, 170)]
GLASS = [(150, 196, 220), (206, 236, 248)]
BLUE = [(14, 34, 96), (26, 66, 168), (54, 116, 222), (128, 178, 250)]
CAR = [(96, 14, 30), (160, 24, 42), (212, 48, 58), (246, 112, 104), (255, 182, 170)]
TAN = [(150, 104, 64), (196, 150, 98), (232, 196, 140)]
NAVY = (57, 57, 123)
SHOE = (57, 57, 123)


class C:
    def __init__(self, w=40, h=44):
        self.w, self.h = w, h
        self.p = {}
        self.front = set()

    def put(self, x, y, c, front=False):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.p[(x, y)] = c
            if front: self.front.add((x, y))

    def rect(self, x0, y0, x1, y1, c, front=False):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, y, c, front)

    def oval(self, cx, cy, rx, ry, fn, front=False, edge=1.0):
        for y in range(int(cy - ry) - 1, int(cy + ry) + 2):
            for x in range(int(cx - rx) - 1, int(cx + rx) + 2):
                u, v = (x + 0.5 - cx) / rx, (y + 0.5 - cy) / ry
                d = u * u + v * v
                if d <= edge:
                    c = fn(d, u, v)
                    if c: self.put(x, y, c, front)

    def poly(self, pts, fn, front=False):
        xs, ys = [p[0] for p in pts], [p[1] for p in pts]
        for y in range(int(min(ys)), int(max(ys)) + 1):
            for x in range(int(min(xs)), int(max(xs)) + 1):
                px, py, inside, j = x + 0.5, y + 0.5, False, len(pts) - 1
                for i in range(len(pts)):
                    (xi, yi), (xj, yj) = pts[i], pts[j]
                    if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi:
                        inside = not inside
                    j = i
                if inside:
                    c = fn(x, y)
                    if c: self.put(x, y, c, front)

    def outline(self):
        add = {}
        for (x, y) in list(self.p):
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                q = (x + dx, y + dy)
                if q not in self.p and 0 <= q[0] < self.w and 0 <= q[1] < self.h:
                    add[q] = (x, y)
        for q, src in add.items():
            self.p[q] = K
            if src in self.front: self.front.add(q)


def wheel_side(cv, cx, top, rows, front=False):
    """A wheel seen from the side and a little above, drawn row by row ([rows]: each row's width, centred on [cx]) so it
    rounds off at the bottom as at the top - an oval's last row and the outline under it, both as wide, read as a flat
    tyre. The tyre's top lit, the rim an inner oval, a dark hub."""
    n = len(rows)
    for i, w in enumerate(rows):
        y = top + i
        x0 = int(round(cx - w / 2))
        for x in range(x0, x0 + w):
            inner = 0 < i < n - 1 and abs(x + 0.5 - cx) < w / 2 - 1.5
            if inner:
                c = RIM[1] if i < n / 2 else RIM[0]
            else:
                c = TIRE[1] if i < n / 2 - 1 else TIRE[0]
            cv.put(x, y, c, front)
    cv.put(int(cx - 0.5), top + n // 2 - (1 if n % 2 == 0 else 0), STEEL[0], front)


def wheel_end(cv, cx, cy, rx=1.6, ry=3.2, front=True):
    """A wheel seen end on from above (front / back views): a narrow upright oval."""
    cv.oval(cx, cy, rx, ry, lambda d, u, v: TIRE[1] if u < 0 else TIRE[0], front)


def ramp_top(ramp, y, top):
    """The game's look from above: a part's top rows lightest, its sides darker below."""
    k = len(ramp) - 1 - min(len(ramp) - 1, max(0, y - top))
    return ramp[k]


# ----- sport motorcycle -----

def moto_side():
    cv = C()
    wheel_side(cv, 12, 31, [4, 6, 8, 8, 6, 4])
    wheel_side(cv, 29, 31, [4, 6, 8, 8, 6, 4])
    # swingarm and fork
    for i in range(6): cv.put(12 + i, 33 - i // 3, STEEL[1])
    for i in range(6): cv.put(29 - i // 2, 33 - i, STEEL[2])
    # belly pan and engine under the tank, the exhaust tucked under the tail
    cv.rect(17, 31, 25, 32, BLACK[0])
    cv.rect(18, 31, 23, 31, BLACK[1])
    for x in range(9, 15): cv.put(x, 31, STEEL[2]); cv.put(x, 32, STEEL[0])
    # tail cowl: raised, pointed, a light at its tip
    cv.poly([(5, 27), (15, 28.5), (15, 31), (8, 30.5)], lambda x, y: ramp_top(BLUE, y, 27))
    cv.put(5, 28, TAIL); cv.put(6, 28, TAIL)
    # seat
    cv.rect(13, 28, 18, 28, SEAT[1]); cv.rect(13, 29, 18, 29, SEAT[0])
    # tank and the long fairing to the nose, its top lit, a white race stripe down it
    cv.poly([(17, 27.5), (25, 26.5), (33.5, 29), (31, 32), (22, 32), (17, 30.5)], lambda x, y: ramp_top(BLUE, y, 27))
    for x in range(18, 33): cv.put(x, 29 if x < 30 else 30, WHITE[2] if x % 5 else WHITE[1])
    # windscreen in front of the hands, smoked
    for x, y in ((26, 25), (27, 25), (28, 25), (27, 24), (28, 24), (29, 26), (28, 26)):
        cv.put(x, y, SMOKE[1] if y == 24 else SMOKE[0], front=True)
    cv.put(33, 29, LIGHT[1]); cv.put(32, 29, LIGHT[0])
    cv.outline()
    return cv


def moto_front(back=False):
    """Centred on the rider (x 19.5: their eyes are at 18 and 21)."""
    cv = C()
    if back:
        wheel_end(cv, 20, 34.5, 2.2, 3.4)
        cv.poly([(16, 28), (24, 28), (23, 32), (17, 32)], lambda x, y: ramp_top(BLUE, y, 28), front=True)
        for x in range(18, 22): cv.put(x, 30, TAIL, front=True)
        cv.rect(23, 31, 24, 32, STEEL[2], front=True)
        cv.rect(18, 28, 21, 28, WHITE[2], front=True)
    else:
        wheel_end(cv, 20, 34.5)
        # the fairing from the front and above: wide at the top, twin lights, the screen over the chest
        cv.poly([(14, 27), (26, 27), (24, 32), (16, 32)], lambda x, y: ramp_top(BLUE, y, 27), front=True)
        for x in range(16, 24): cv.put(x, 28, WHITE[2], front=True)
        for x, c in ((17, LIGHT[1]), (18, LIGHT[0]), (21, LIGHT[0]), (22, LIGHT[1])): cv.put(x, 30, c, True)
        for x in range(16, 24): cv.put(x, 26, SMOKE[0], front=True)
        cv.put(14, 25, STEEL[2], True); cv.put(25, 25, STEEL[2], True)
    cv.outline()
    return cv


# ----- scooter -----

def scooter_side():
    cv = C()
    wheel_side(cv, 13, 32, [4, 6, 6, 6, 4])
    wheel_side(cv, 28, 32, [4, 6, 6, 6, 4])
    # the rear body: a low rounded shell, lit on top
    cv.oval(13, 30.5, 6.5, 3.2, lambda d, u, v: ramp_top(MINT, int(30.5 + v * 3.2), 27) if v < 0.5 else MINT[0])
    cv.put(6, 30, TAIL)
    # the exhaust, low under the rear shell
    for x in range(7, 12): cv.put(x, 33, STEEL[2] if x > 7 else STEEL[1])
    # the floorboard seen from above: a flat cream deck
    cv.rect(18, 30, 24, 31, CREAM[2]); cv.rect(18, 32, 24, 32, MINT[0])
    # leg shield, slanting forward from under the handlebar, and the front fender over the wheel
    cv.poly([(23, 28), (26, 28), (29, 32), (24, 32)], lambda x, y: MINT[2] if x < 26 else MINT[1])
    cv.rect(25, 32, 30, 32, MINT[2])
    # the handlebar where the hands are (24-26, row 27), the headset's light just ahead of them
    cv.rect(23, 27, 26, 27, MINT[3]); cv.put(22, 27, BLACK[1]); cv.put(27, 27, LIGHT[1]); cv.put(27, 28, LIGHT[0])
    cv.rect(9, 27, 17, 27, SEAT[1]); cv.rect(9, 28, 17, 28, SEAT[0])
    cv.outline()
    return cv


def scooter_front(back=False):
    """Centred on the rider (x 19.5)."""
    cv = C()
    wheel_end(cv, 20, 35.0, 1.6, 2.4)
    if back:
        cv.oval(20, 31, 6, 3, lambda d, u, v: ramp_top(MINT, int(31 + v * 3), 28), front=True)
        cv.put(19, 31, TAIL, True); cv.put(20, 31, TAIL, True)
        cv.rect(23, 33, 24, 33, STEEL[2], front=True)   # the exhaust's end
    else:
        cv.poly([(14, 28), (26, 28), (25, 33), (15, 33)], lambda x, y: ramp_top(MINT, y, 28), front=True)
        # the handlebar across, its grips under the hands, the headset light in the middle
        for x in range(14, 26): cv.put(x, 27, MINT[3] if 16 <= x <= 23 else BLACK[1], True)
        cv.put(19, 26, LIGHT[1], True); cv.put(20, 26, LIGHT[1], True)
        cv.put(14, 25, STEEL[2], True); cv.put(25, 25, STEEL[2], True)
    cv.outline()
    return cv



def leg_side(floor=False):
    """A leg from the hip down to the peg (or the floorboard), the shoe on it (the side view, facing east)."""
    pts = [(19, 29), (19, 30), (20, 31)] if not floor else [(19, 29), (20, 30)]
    shoe = [(20, 32), (21, 32)] if not floor else [(20, 31), (21, 31)]
    return [(x, y, NAVY) for x, y in pts] + [(x, y, SHOE) for x, y in shoe]


# ----- exhaust smoke -----
# Puffs from the exhaust, a sprite of their own behind the vehicle (pk_wide.c's ride): 16 x 8 frames in the vehicle's own
# palette, the first six seen from the side (drifting back and up, growing, thinning out), the next six from behind (drifting
# down and out, toward us). Two puffs half a cycle apart, so the cycle runs on without a gap.
SMOKE_W, SMOKE_H, SMOKE_STEPS = 16, 8, 6
SMOKE = [(206, 210, 222), (238, 240, 246)]


def smoke_frames():
    frames = []
    for side in (True, False):
        for k in range(SMOKE_STEPS):
            px = {}
            for off in (0, SMOKE_STEPS // 2):
                t = ((k + off) % SMOKE_STEPS) / SMOKE_STEPS
                r = 1.1 + t * 1.9
                if side:
                    cx, cy = 2.0 + t * 10.5, 5.4 - t * 2.4
                else:
                    cx, cy = 4.5 + t * 3.0, 2.0 + t * 3.4
                for y in range(SMOKE_H):
                    for x in range(SMOKE_W):
                        u, v = (x + 0.5 - cx) / (r * 1.2), (y + 0.5 - cy) / r
                        if u * u + v * v > 1:
                            continue
                        if t > 0.66 and (x + y + k) % 2:   # thinning out
                            continue
                        px[(x, y)] = SMOKE[1] if v < 0.1 or t > 0.5 else SMOKE[0]
            frames.append(px)
    return frames


# The views in the order RideOn.kt reads them (the bike sheet's standing frames: south, north, west); the art above is
# drawn in a 40 x 44 box whose (4, 4) is the frame's top left, the side facing east (mirrored here to the sheet's west).
VIEWS = ("south", "north", "west")
VIEW_H = 40   # the views reach 8 rows below the rider's 32 x 32 frame (the wheels): the sprite is 32 x 64
CUT = {"south": 25, "north": 25, "west": 24}   # the rider's last row kept (frame rows)
# exhaust: where the smoke's 16 x 8 sprite goes in each view (south, north, west: its top left in the view's 32 x 40, as
# stored - west mirrored), or None: from the front the smoke is behind the vehicle.
VEHICLES = [
    dict(name="MOTORCYCLE", south=lambda: moto_front(), north=lambda: moto_front(True), west=lambda: moto_side(), legs=leg_side(),
         exhaust=[None, (17, 28), (26, 22)]),
    dict(name="SCOOTER", south=lambda: scooter_front(), north=lambda: scooter_front(True), west=lambda: scooter_side(), legs=leg_side(True),
         exhaust=[None, (17, 30), (28, 24)]),
]


def frame(cv, legs, flip):
    """The view as {(x, y): (colour, flags)} in frame coordinates."""
    out = {}
    for (x, y), c in cv.p.items():
        out[(x, y)] = (c, 0x40 if (x, y) in cv.front else 0)
    for x, y, c in legs:
        if out.get((x, y), (None, 0))[1] != 0x40:
            out[(x, y)] = (c, 0x80)
    res = {}
    for (x, y), v in out.items():
        fx = (39 - x if flip else x) - 4
        fy = y - 4
        if 0 <= fx < 32 and 0 <= fy < VIEW_H:
            res[(fx, fy)] = v
    return res


def quantize(counts, limit=15):
    """At most [limit] colours (a sprite palette's 15): the closest two merged, the more used one kept, until it fits."""
    cols = dict(counts)
    merged = {c: c for c in cols}
    def dist(a, b):
        return (a[0] - b[0]) ** 2 * 3 + (a[1] - b[1]) ** 2 * 4 + (a[2] - b[2]) ** 2 * 2
    while len(cols) > limit:
        ks = list(cols)
        a, b = min(((x, y) for i, x in enumerate(ks) for y in ks[i + 1:]), key=lambda p: dist(*p))
        keep, drop = (a, b) if cols[a] >= cols[b] else (b, a)
        cols[keep] += cols.pop(drop)
        for k, v in merged.items():
            if v == drop:
                merged[k] = keep
    return merged


def encode(v):
    """The vehicle's palette (at most 15 colours: it's a sprite of its own) and its views; the rider's leg is 0x80 alone
    (RideOn.kt draws it into the rider's frame in the hero's own colours)."""
    pxs = []
    counts = {}
    for view in VIEWS:
        cv = v[view]()
        px = frame(cv, v["legs"] if view == "west" else [], view == "west")
        pxs.append(px)
        for (c, flags) in px.values():
            if flags != 0x80:
                counts[c] = counts.get(c, 0) + 1
    smoke = smoke_frames()
    for f in smoke:
        for c in f.values():
            counts[c] = counts.get(c, 0) + 1
    to = quantize(counts)
    pal = [None]
    index = {}
    views = []
    for px in pxs:
        data = bytearray(32 * VIEW_H)
        for (x, y), (c, flags) in px.items():
            if flags == 0x80:
                data[y * 32 + x] = 0x80
                continue
            c = to[c]
            if c not in index:
                index[c] = len(pal)
                pal.append(c)
            data[y * 32 + x] = index[c] | flags
        views.append(bytes(data))
    sm = bytearray(SMOKE_W * SMOKE_H * len(smoke))
    for i, f in enumerate(smoke):
        for (x, y), c in f.items():
            c = to[c]
            if c not in index:
                index[c] = len(pal)
                pal.append(c)
            sm[i * SMOKE_W * SMOKE_H + y * SMOKE_W + x] = index[c]
    assert len(pal) <= 16, v["name"]
    return pal, views, bytes(sm)


def write(out):
    lines = [
        "// Generated by scripts/gen_ride_vehicles.py - do not edit. RIDE ON's vehicles (our own pixel art): per vehicle,",
        "// its palette (0xRRGGBB, index 0 unused) and three 32 x 40 views (south, north, west; the first 32 rows the rider's",
        "// frame, the rest below it), a byte a pixel: the colour's",
        "// index in the low 4 bits (0 = none), 0x40 FRONT (the rider's frame left clear there: the vehicle shows over them), 0x80 the",
        "// rider's LEG (drawn into their frame in the hero's colours). The vehicle is a sprite of its own behind the rider.",
        "package com.pokedaisy.app.companion.data",
        "",
        "internal object RideVehiclesGen {",
        "    /** smoke: the exhaust's puffs, 16 x 8 frames (six from the side, six from behind), a byte a pixel (palette index);",
        "     * exhaust: the smoke sprite's top left per view (south, north, west: x, y; -128 none). */",
        "    class Art(val palette: IntArray, val views: List<ByteArray>, val cut: IntArray, val smoke: ByteArray, val exhaust: IntArray, val width: Int = 32)",
        "",
        "    private fun views(vararg b64: String) = b64.map { java.util.Base64.getDecoder().decode(it) }",
        "",
    ]
    names = []
    for v in VEHICLES:
        pal, views, smoke = encode(v)
        cols = ", ".join("0x%06X" % (c[0] << 16 | c[1] << 8 | c[2]) for c in pal[1:])
        lines.append(f"    val {v['name']} = Art(")
        lines.append(f"        intArrayOf(0, {cols}),")
        lines.append("        views(")
        for d in views:
            lines.append(f'            "{base64.b64encode(d).decode()}",')
        lines.append("        ),")
        lines.append(f"        intArrayOf({', '.join(str(CUT[w]) for w in VIEWS)}),")
        lines.append(f'        java.util.Base64.getDecoder().decode("{base64.b64encode(smoke).decode()}"),')
        ex = [c for e in v["exhaust"] for c in (e if e else (-128, -128))]
        lines.append(f"        intArrayOf({', '.join(map(str, ex))}),")
        lines.append("    )")
        lines.append("")
        names.append(v["name"])
    lines.append("}")
    with open(out, "w") as f:
        f.write("\n".join(lines) + "\n")
    print("wrote", out)


def preview(d):
    from PIL import Image
    os.makedirs(d, exist_ok=True)
    for v in VEHICLES:
        pal, views, smoke = encode(v)
        im = Image.new("RGB", (112 + 20 * 6, VIEW_H + 12), (96, 160, 120))
        for i, data in enumerate(views):
            ex = v["exhaust"][i]
            for p in range(32 * VIEW_H):
                b = data[p]
                if b & 0x3F:
                    im.putpixel((i * 36 + p % 32, p // 32), pal[b & 0x3F])
            if ex:   # the smoke's first frame of this view, behind the vehicle
                f = 0 if i == 2 else SMOKE_STEPS
                for q in range(SMOKE_W * SMOKE_H):
                    x, y = i * 36 + ex[0] + q % SMOKE_W, ex[1] + q // SMOKE_W
                    if smoke[f * SMOKE_W * SMOKE_H + q] and 0 <= x < im.width and 0 <= y < im.height and not (x - i * 36 < 32 and views[i][y * 32 + x - i * 36] & 0x3F if y < VIEW_H else False):
                        im.putpixel((x, y), pal[smoke[f * SMOKE_W * SMOKE_H + q]])
        for f in range(SMOKE_STEPS * 2):   # every smoke frame
            for q in range(SMOKE_W * SMOKE_H):
                if smoke[f * SMOKE_W * SMOKE_H + q]:
                    im.putpixel((112 + (f % 6) * 20 + q % SMOKE_W, (f // 6) * 10 + q // SMOKE_W + 30), pal[smoke[f * SMOKE_W * SMOKE_H + q]])
        im.resize((im.width * 8, im.height * 8), Image.NEAREST).save(os.path.join(d, v["name"].lower() + ".png"))


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    if "--preview" in sys.argv:
        preview(sys.argv[sys.argv.index("--preview") + 1])
    write(os.path.join(here, "../app/src/main/kotlin/com/pokedaisy/app/companion/data/RideVehiclesGen.kt"))
