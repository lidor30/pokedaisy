#!/usr/bin/env python3
"""The FLY ON vehicles (MAKEOVER > FLY ON): an air balloon, a helicopter, a zeppelin and a small plane the player flies in place of
FREE FLIGHT's bird. Our own pixel art, made here - no game art. Each part is drawn with a colour ramp (dark to light, its
material's) by a normal lit from the top left, a glint where the light hits square on, then a one-pixel outline in the
part's own dark (a lighter one on the lit side) - the games' overworld look.

Each is drawn at the games' overworld angle - from the side and a little above: a basket's open top, a rotor's or a
propeller's spinning disc, the cabin's and the gondola's roofs, wings and fins spread to both sides (the near one bigger).
Each vehicle has a picture per animation frame (the burner's flame, the rotors, the propeller), facing west
(pk_flight.c mirrors it going east, as it does the bird). The player is drawn into it from their own overworld sprite:
only over the pixels marked SEAT (the cockpit glass, a window, the open air over a basket); the pixels marked FRONT (a
basket's rim, ropes, frames, glints) are drawn again over them.

Writes app/src/main/cpp/pk_flight_vehicles.h (palettes and indexed frames, a byte a pixel: palette index in the low 6
bits, FRONT 0x40, SEAT 0x80) and, with --preview DIR, a PNG of every frame at 8x.

Usage: scripts/gen_flight_vehicles.py [--preview DIR [--hero PNG]]
"""
import os
import sys

import math
import sys

L = (-0.55, -0.62, 0.56)
_n = math.sqrt(sum(c * c for c in L))
L = tuple(c / _n for c in L)


def hexc(s):
    s = s.lstrip('#')
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


class Mat:
    def __init__(self, ramp, outline, lit_outline=None, spec=None):
        self.ramp = [hexc(c) for c in ramp]       # dark .. light
        self.outline = hexc(outline)
        self.lit = hexc(lit_outline) if lit_outline else self.ramp[0]
        self.spec = hexc(spec) if spec else None

    def shade(self, n, ambient=0.18, bands=None):
        d = max(0.0, n[0] * L[0] + n[1] * L[1] + n[2] * L[2])
        b = ambient + (1 - ambient) * d
        k = len(self.ramp)
        i = int(b * k)
        return self.ramp[max(0, min(k - 1, i))]


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.c = [[None] * w for _ in range(h)]     # colour
        self.part = [[None] * w for _ in range(h)]  # which part (for outlines between parts)
        self.mat = [[None] * w for _ in range(h)]
        self.front = [[False] * w for _ in range(h)]
        self.seat = [[False] * w for _ in range(h)]
        self.nooutline = [[False] * w for _ in range(h)]

    def put(self, x, y, col, part=None, mat=None, front=False):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.c[y][x] = col
            self.part[y][x] = part
            self.mat[y][x] = mat
            self.front[y][x] = front

    def get(self, x, y):
        return self.c[y][x] if 0 <= x < self.w and 0 <= y < self.h else None

    def outline(self, parts=None, inner=True):
        """Edge pixels of each part take its material's outline: against nothing, or (inner) against a part drawn later
        that lies over it. Lit side (the gap above / left) the lighter outline."""
        new = []
        for y in range(self.h):
            for x in range(self.w):
                p = self.part[y][x]
                m = self.mat[y][x]
                if p is None or m is None or (parts and p not in parts) or self.nooutline[y][x]:
                    continue
                lit = dark = False
                for dx, dy in ((-1, 0), (0, -1), (1, 0), (0, 1)):
                    xx, yy = x + dx, y + dy
                    q = self.part[yy][xx] if 0 <= xx < self.w and 0 <= yy < self.h else None
                    if q is None:
                        if dx < 0 or dy < 0:
                            lit = True
                        else:
                            dark = True
                if dark:
                    new.append((x, y, m.outline))
                elif lit:
                    new.append((x, y, m.outline if m.lit is None else m.lit))
        for x, y, c in new:
            self.c[y][x] = c


def ellipsoid(cv, part, mat, cx, cy, rx, ry, fn=None, front=False, clip=None):
    """A lit ellipsoid seen side on (rx, ry); fn(x, y, u, v, n) may return another colour (stripes, panels)."""
    for y in range(int(cy - ry) - 1, int(cy + ry) + 2):
        for x in range(int(cx - rx) - 1, int(cx + rx) + 2):
            u = (x + 0.5 - cx) / rx
            v = (y + 0.5 - cy) / ry
            d = u * u + v * v
            if d > 1.0:
                continue
            if clip and not clip(x, y):
                continue
            nz = math.sqrt(max(0.0, 1 - d))
            n = (u, v, nz)
            col = mat.shade(n)
            if fn:
                r = fn(x, y, u, v, n)
                if r is not None:
                    col = r
            cv.put(x, y, col, part, mat, front)


def revolve(cv, part, mat, cx, y0, prof, fn=None, front=False):
    """A surface of revolution about a vertical axis: prof[i] = half width at row y0 + i."""
    for i, r in enumerate(prof):
        if r <= 0:
            continue
        dr = (prof[i + 1] - prof[i - 1]) / 2 if 0 < i < len(prof) - 1 else 0
        for x in range(int(cx - r) - 1, int(cx + r) + 2):
            u = (x + 0.5 - cx) / r
            if abs(u) >= 1:
                continue
            nz = math.sqrt(1 - u * u)
            # the normal tilts down where the profile narrows below (dr < 0)
            ny = -dr * 0.9
            n = (u * 1.0, ny, nz)
            ln = math.sqrt(sum(c * c for c in n))
            n = tuple(c / ln for c in n)
            col = mat.shade(n)
            if fn:
                rr = fn(x, y0 + i, u, n)
                if rr is not None:
                    col = rr
            cv.put(x, y0 + i, col, part, mat, front)


def box(cv, part, mat, x0, y0, x1, y1, fn=None, front=False, round_=0):
    """A box seen from the front, lit on its top rows and left side (a rounded front)."""
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if round_ and ((x - x0 < round_ or x1 - x < round_) and (y - y0 < round_ or y1 - y < round_)):
                dx = min(x - x0, x1 - x) - round_ + 0.5
                dy = min(y - y0, y1 - y) - round_ + 0.5
                if dx * dx + dy * dy > round_ * round_:
                    continue
            u = (x + 0.5 - (x0 + x1 + 1) / 2) / ((x1 - x0 + 1) / 2)
            n = (u * 0.7, -0.2 if y == y0 else 0.1, math.sqrt(max(0.0, 1 - 0.49 * u * u)))
            col = mat.shade(n)
            if fn:
                r = fn(x, y, u)
                if r is not None:
                    col = r
            cv.put(x, y, col, part, mat, front)


def line(cv, part, col, pts, front=False, mat=None):
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        n = max(abs(x1 - x0), abs(y1 - y0), 1)
        for k in range(n + 1):
            cv.put(round(x0 + (x1 - x0) * k / n), round(y0 + (y1 - y0) * k / n), col, part, mat, front)


def poly(cv, part, mat, pts, front=False, light=None):
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    for y in range(int(min(ys)), int(max(ys)) + 1):
        for x in range(int(min(xs)), int(max(xs)) + 1):
            px, py = x + 0.5, y + 0.5
            inside = False
            j = len(pts) - 1
            for i in range(len(pts)):
                xi, yi = pts[i]
                xj, yj = pts[j]
                if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi:
                    inside = not inside
                j = i
            if inside:
                n = light(x, y) if light else (0, 0, 1)
                cv.put(x, y, mat.shade(n), part, mat, front)




B_RED = Mat(['#7a1c22', '#a8282a', '#d0403a', '#ec6450', '#f89a80'], '#4c1018', '#8c2028')
B_GOLD = Mat(['#8a5a18', '#b88020', '#e0aa30', '#f4d060', '#fcf0a8'], '#4c3010', '#8a5a18')
CLOTH = Mat(['#3c2a3a', '#5a3c48'], '#2a1c28')
METAL = Mat(['#404850', '#687480', '#98a4b0', '#d0d8e0'], '#283038')
WICKER = Mat(['#6a3c1c', '#8c5428', '#b07438', '#d09a54', '#ecc888'], '#3c200c', '#6a3c1c')
LEATHER = Mat(['#4a2814', '#6c3c1c', '#8c5228', '#ac6c38'], '#2c1408')
ROPE = (124, 104, 84)
ROPE_D = (84, 68, 56)
FLAME = [(248, 120, 24), (252, 184, 48), (252, 240, 168)]

B_W, B_H = 32, 58
CX = 16


def balloon(frame):
    cv = Canvas(B_W, B_H)
    # envelope: a dome over a tapering skirt
    prof = []
    R, C, END = 14.6, 14.6, 32
    for i in range(END):
        y = i + 0.5
        if y <= C:
            r = math.sqrt(max(0, R * R - (C - y) ** 2))
        else:
            t = (y - C) / (END - C)
            r = R - (R - 4.6) * t ** 1.5
        prof.append(r)

    def gore(x, y, u, n):
        phi = math.asin(max(-1, min(1, u)))
        g = int(math.floor((phi + math.pi / 2) / (math.pi / 6)))
        m = B_RED if g % 2 == 0 else B_GOLD
        col = m.shade(n, ambient=0.12)
        # a soft glint high on the left
        if (u + 0.42) ** 2 * 2.2 + ((y - 7) / 5.0) ** 2 < 0.10:
            col = m.ramp[-1]
        return col

    revolve(cv, 'env', B_RED, CX, 0, prof, gore)
    # gore seams: a darker pixel line along each boundary, only on the shaded half (they'd clutter the lit side)
    cv.outline(['env'])
    # the skirt (dark cloth) round the mouth
    for y in (32, 33):
        for x in range(CX - 5, CX + 5):
            cv.put(x, y, CLOTH.ramp[1] if x < CX + 2 else CLOTH.ramp[0], 'skirt', CLOTH)
    cv.outline(['skirt'])
    # ropes: two in front, from the skirt's corners to the basket's; the back pair darker and inside them
    ropes = [((CX - 6, 33), (CX - 9, 49)), ((CX + 5, 33), (CX + 8, 49))]
    for (a, b) in ropes:
        line(cv, 'rope', ROPE, [a, b], front=True)
    for (a, b) in [((CX - 4, 34), (CX - 6, 48)), ((CX + 3, 34), (CX + 5, 48))]:
        line(cv, 'ropeb', ROPE_D, [a, b])
    # burner: a small metal can under the mouth
    box(cv, 'burner', METAL, CX - 2, 34, CX + 1, 34)
    cv.outline(['burner'])
    # flame, up into the mouth
    fl = [
        [(CX - 1, 30, 0), (CX - 1, 31, 1), (CX, 31, 0), (CX - 1, 32, 2), (CX, 32, 1), (CX - 2, 33, 0), (CX - 1, 33, 2), (CX, 33, 2), (CX + 1, 33, 0)],
        [(CX - 1, 32, 0), (CX, 32, 0), (CX - 1, 33, 1), (CX, 33, 2), (CX + 1, 33, 0)],
        [],
    ][frame]
    for x, y, k in fl:
        cv.put(x, y, FLAME[k], 'flame', None)
    # basket, seen a little from above: its open top an oval (the far half of the rim behind the rider, the near half in
    # front of them), the wicker below it
    for y in range(46, 53):
        for x in range(CX - 10, CX + 10):
            u, v = (x + 0.5 - CX) / 9.0, (y + 0.5 - 49.5) / 2.6
            d = u * u + v * v
            if d > 1.0:
                continue
            if d > 0.55:
                cv.put(x, y, LEATHER.ramp[3] if v < 0 else LEATHER.ramp[1], 'rim', LEATHER, front=v >= 0)
            else:
                cv.put(x, y, WICKER.ramp[0] if v < 0.3 else WICKER.ramp[1], 'inside', WICKER)

    def weave(x, y, u):
        n = (u * 0.7, 0.1, math.sqrt(max(0, 1 - 0.49 * u * u)))
        k = WICKER.ramp.index(WICKER.shade(n))
        if (x + y) % 2 == 0:
            k = max(0, k - 1)
        if y == 51:
            k = max(0, k - 2)   # the rim's shadow on the wicker
        return WICKER.ramp[k]

    box(cv, 'basket', WICKER, CX - 8, 52, CX + 7, 56, weave, front=True, round_=2)
    cv.outline(['rim', 'basket'])
    # the seat: open air between the ropes, and inside the basket's open top (the near rim over the rider's waist)
    for y in range(35, 52):
        for x in range(CX - 8, CX + 8):
            if cv.c[y][x] is None or cv.part[y][x] in ('ropeb', 'inside'):
                cv.seat[y][x] = True
    return cv


BLUE = Mat(['#16306a', '#1f4a9c', '#2c68c8', '#4a8ae4', '#86b8f4'], '#0c1838', '#16306a')
H_GLASS = Mat(['#3a6a88', '#5a94b4', '#84bcd8', '#b4e0f0'], '#1c3040')
H_STEEL = Mat(['#3a3e48', '#5c6270', '#8a909c', '#c0c6d0'], '#20242c')
DARK = Mat(['#1c2030', '#2c3244'], '#10131c')
H_GOLD = Mat(['#9a6a14', '#d09a24', '#f4c848'], '#5a3c08')
BLADE = [(52, 54, 62), (120, 124, 134), (176, 180, 188)]

H_W, H_H = 56, 40


def disc(cv, cx, cy, rx, ry, frame, blades=2, thick=1):
    """A spinning rotor / propeller seen from above at an angle: the blades where they are this frame ([thick] pixels,
    the extra ones below / beside them a shade lighter), their tips lighter still; drawn over everything (FRONT). No
    blur dots (they read as grey specks floating round the blades)."""
    for k in range(blades):
        ang = math.pi * (frame / 3.0 + k / blades + 1 / 12.0)   # never quite end on: an end-on blade read as a stick
        across = (0, 1) if abs(math.cos(ang) * rx) >= abs(math.sin(ang) * ry) else (1, 0)
        for t in range(-40, 41):
            tt = t / 40.0
            x = int(round(cx + math.cos(ang) * rx * tt))
            y = int(round(cy + math.sin(ang) * ry * tt))
            tip = abs(tt) >= 0.85
            for j in range(thick):
                c = BLADE[1] if tip else (BLADE[0] if j == 0 else BLADE[1])
                cv.put(x + across[0] * j, y + across[1] * j, c, 'blade', None, True)


def helicopter(frame):
    """From the side and a little above: the cabin's roof and the tops of the skids show, the rotor a wide disc."""
    cv = Canvas(H_W, H_H)
    W, H = H_W, H_H
    cx, cy = 22, 24
    # the far skid first (behind the cabin), then the boom, the cabin, the near skid in front
    for x in range(11, 33):
        cv.put(x, 31, H_STEEL.ramp[1], 'skidb', H_STEEL)
    for sx in (15, 28):
        line(cv, 'strutb', H_STEEL.ramp[0], [(sx + 1, 28), (sx + 2, 31)])
    for x in range(30, 51):
        t = (x - 30) / 20
        r = 3.4 * (1 - t) + 1.5 * t
        c = 22.0 - 5.0 * t
        for y in range(int(c - r) - 1, int(c + r) + 2):
            v = (y + 0.5 - c) / r
            if abs(v) >= 1:
                continue
            n = (0.15, v - 0.35, math.sqrt(max(0.0, 1 - v * v)))
            cv.put(x, y, BLUE.shade(n), 'boom', BLUE)
    poly(cv, 'fin', BLUE, [(46, 17), (49, 7), (53, 7), (52, 17)], light=lambda x, y: (-0.4, -0.2, 0.9))
    # the tail plane seen from above: a flat diamond across the boom
    poly(cv, 'stab', BLUE, [(44, 16), (50, 13), (53, 15), (47, 20)], light=lambda x, y: (0, -0.8, 0.6))
    cv.outline(['boom', 'fin', 'stab', 'skidb', 'strutb'])
    # the cabin: an egg lit from above, its front glass the seat
    def cab(x, y, u, v, n):
        if u < 0.12 and -0.85 < v < 0.5:
            cv.seat[y][x] = True
            c = H_GLASS.shade(n, ambient=0.3)
            if 0 <= (x + y * 0.8) - 31.5 <= 1.2 and v > 0.2:
                c = (232, 248, 252)   # a glint low in the glass, under the rider's face
            return c
        if 0.2 < v < 0.4 and u > -0.05:
            return H_GOLD.shade(n)
        if v < -0.45:
            return BLUE.ramp[-1] if u > 0.2 and v < -0.7 else BLUE.ramp[3]   # the roof, lit
        return None
    ellipsoid(cv, 'cab', BLUE, cx, cy, 13, 10, cab)
    for y in range(H):
        for x in range(W):
            if cv.c[y][x] == (232, 248, 252):
                cv.front[y][x] = True
                cv.seat[y][x] = False
    cv.outline(['cab'])
    for y in range(H):
        for x in range(W):
            if cv.seat[y][x] and cv.c[y][x] in (BLUE.outline, BLUE.lit, H_GLASS.outline):
                cv.seat[y][x] = False
                cv.front[y][x] = True
    # the engine cowling on the roof and the mast to the rotor
    ellipsoid(cv, 'cowl', BLUE, 25, 15, 7, 2.6, lambda x, y, u, v, n: BLUE.ramp[-1] if v < -0.3 else None)
    cv.outline(['cowl'])
    for y in range(8, 13):
        cv.put(25, y, H_STEEL.ramp[2], 'mast', H_STEEL)
        cv.put(26, y, H_STEEL.ramp[0], 'mast', H_STEEL)
    # the near skid, in front, and its struts
    for sx in (14, 29):
        line(cv, 'strut', H_STEEL.ramp[1], [(sx, 31), (sx - 1, 35)], front=True)
    for x in range(7, 36):
        cv.put(x, 35, H_STEEL.ramp[3] if x < 20 else H_STEEL.ramp[2], 'skid', H_STEEL, True)
        cv.put(x, 36, H_STEEL.ramp[0], 'skid', H_STEEL, True)
    cv.put(6, 34, H_STEEL.ramp[2], 'skid', H_STEEL, True)
    cv.outline(['skid', 'strut'])
    # the rotor: a wide disc over it all, the tail rotor a small one on the fin
    disc(cv, 25.5, 7, 25, 5, frame, thick=2)
    tr = [[(50, 9), (50, 10), (50, 11), (50, 12), (50, 13)], [(48, 11), (49, 11), (50, 11), (51, 11), (52, 11)],
          [(48, 9), (49, 10), (50, 11), (51, 12), (52, 13)]][frame]
    for x, y in tr:
        cv.put(x, y, BLADE[1], 'trotor', None, True)
    return cv


SILVER = Mat(['#5a6070', '#7c8496', '#a4acbc', '#c8cedb', '#eef1f6'], '#2c3040', '#5a6070')
Z_RED = Mat(['#7a1c22', '#a8282a', '#d0403a', '#ec6450'], '#4c1018', '#7a1c22')
CABIN = Mat(['#6a3c1c', '#8c5428', '#b07438', '#d09a54'], '#3c200c', '#6a3c1c')
GLASS = Mat(['#3a6a88', '#5a94b4', '#84bcd8', '#b4e0f0'], '#1c3040')
STEEL = Mat(['#3a3e48', '#5c6270', '#8a909c', '#c0c6d0'], '#20242c')

Z_W, Z_H = 60, 44


def zeppelin(frame):
    """From the side and a little above: the envelope lit along its top, the tail's fins spread to both sides (the far one
    up behind, the near one down in front), the gondola's roof and its windows."""
    cv = Canvas(Z_W, Z_H)
    W, H = Z_W, Z_H
    cx, cy, rx, ry = 27, 13, 25.5, 9.6
    # the far side fin (behind the envelope) and the top fin
    poly(cv, 'fin', Z_RED, [(44, 9), (52, 3), (57, 3), (54, 10)], light=lambda x, y: (-0.3, -0.6, 0.7))
    poly(cv, 'fin', Z_RED, [(46, 7), (51, 0), (56, 0), (55, 7)], light=lambda x, y: (-0.3, -0.4, 0.86))
    cv.outline(['fin'])

    def skin(x, y, u, v, n):
        m = SILVER
        if 0.05 < v < 0.3:
            m = Z_RED          # a red band the length of it, a little below the middle: we see more of the top
        col = m.shade(n, ambient=0.2)
        k = m.ramp.index(col)
        lon = math.asin(max(-1, min(1, u)))
        if abs(((lon + math.pi / 2) / (math.pi / 9)) % 1 - 0.5) > 0.42 and abs(u) < 0.92 and m is SILVER:
            k = max(0, k - 1)
        if u < -0.9:
            k = max(0, k - 1)
        if v < -0.55 and abs(u) < 0.8:
            k = min(len(m.ramp) - 1, k + 1)   # the top, in the light
        if (u + 0.45) ** 2 * 3 + (v + 0.55) ** 2 < 0.05:
            k = len(m.ramp) - 1
        return m.ramp[k]

    ellipsoid(cv, 'env', SILVER, cx, cy, rx, ry, skin)
    cv.outline(['env'])
    # the near side fin, in front of the envelope's tail
    poly(cv, 'finn', Z_RED, [(44, 17), (55, 16), (59, 23), (51, 23)], light=lambda x, y: (-0.2, -0.7, 0.7), front=True)
    cv.outline(['finn'])
    # the gondola: its roof seen from above (lighter), then its front with the windows
    def roof(x, y, u):
        return CABIN.ramp[3] if y < 23 else CABIN.ramp[2]
    box(cv, 'cabin', CABIN, 16, 21, 38, 23, roof)
    def cabin(x, y, u):
        n = (u * 0.7, 0.1, math.sqrt(max(0, 1 - 0.49 * u * u)))
        return CABIN.shade(n)
    box(cv, 'cabin', CABIN, 15, 24, 37, 39, cabin, round_=2)
    cv.outline(['cabin'])
    for (x0, x1) in ((17, 19), (21, 31), (33, 35)):
        for y in range(26, 38):
            for x in range(x0, x1 + 1):
                n = (0.0, (y - 31) / 7, 0.9)
                cv.put(x, y, GLASS.shade(n, ambient=0.35), 'win', GLASS)
                cv.seat[y][x] = (x0 == 21)
        for k in range(3):
            if x0 + k + 1 <= x1 and x0 != 21:
                cv.put(x0 + k + 1, 27 + k, (232, 248, 252), 'win', GLASS, True)
                cv.seat[27 + k][x0 + k + 1] = False
    for y in range(25, 39):
        for x in range(16, 37):
            if cv.part[y][x] == 'cabin':
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    xx, yy = x + dx, y + dy
                    if cv.part[yy][xx] == 'win':
                        cv.c[y][x] = CABIN.ramp[0]
                        cv.front[y][x] = True
                        break
    for x in range(17, 36, 3):
        cv.put(x, 38, CABIN.ramp[3], 'cabin', CABIN, True)
    ellipsoid(cv, 'pod', STEEL, 40, 33, 3, 2)
    cv.outline(['pod'])
    disc(cv, 44, 33, 1.6, 5, frame, blades=1)
    for y in range(H):
        for x in range(W):
            if cv.part[y][x] in ('cabin', 'env', 'fin', 'pod'):
                cv.front[y][x] = True
    return cv


P_BODY = Mat(['#8a5a10', '#c08818', '#e8b828', '#f8d850', '#fcf0a0'], '#4c3008', '#8a5a10')
P_TRIM = Mat(['#7a1c22', '#a8282a', '#d0403a', '#ec6450', '#f89a80'], '#4c1018', '#7a1c22')
P_GLASS = Mat(['#3a6a88', '#5a94b4', '#84bcd8', '#b4e0f0'], '#1c3040')
P_TYRE = Mat(['#1c1e24', '#30343c', '#484c56'], '#0c0e12')

P_W, P_H = 60, 44


def plane(frame):
    """A small, chunky propeller plane from the side and a little above: a round body, its wings spread out (the far one
    up behind the body, the near one down in front of it, bigger: nearer), the tail plane the same, a tall bubble canopy
    (the seat, the rider's head and shoulders in it), a spinning propeller at the nose; its wheels tucked away in flight."""
    cv = Canvas(P_W, P_H)
    W, H = P_W, P_H
    top_lit = lambda x, y: (0.15, -0.3, 0.94)
    # far wing and far tail plane, behind the body
    poly(cv, 'wingb', P_TRIM, [(19, 20), (33, 20), (35, 7), (27, 7)], light=lambda x, y: (0.1, -0.5, 0.8))
    poly(cv, 'tailb', P_TRIM, [(46, 19), (54, 19), (56, 13), (52, 13)], light=lambda x, y: (0.1, -0.5, 0.8))
    cv.outline(['wingb', 'tailb'])
    # the fuselage: round at the nose, tapering to the tail, lit along its top, a red line down its side
    def body(x, y, u, v, n):
        if 0.22 < v < 0.5 and x > 9:
            return P_TRIM.shade(n)
        if v < -0.5:
            return P_BODY.ramp[-1] if v < -0.8 else P_BODY.ramp[3]
        return None
    taper = lambda x, y: not (x > 36 and abs(y + 0.5 - 21) > 7.0 - (x - 36) * 0.19)
    ellipsoid(cv, 'body', P_BODY, 29, 21, 25, 7.0, body, clip=taper)
    poly(cv, 'fin', P_TRIM, [(46, 18), (51, 6), (57, 6), (56, 18)], light=lambda x, y: (-0.4, -0.2, 0.9))
    cv.outline(['body', 'fin'])
    # the canopy on top, the seat
    def glass(x, y, u, v, n):
        cv.seat[y][x] = True
        c = P_GLASS.shade(n, ambient=0.3)
        if 0 <= (x - y * 0.9) - 11.5 <= 1.2 and v < -0.2 and x > 26:
            c = (232, 248, 252)   # a glint on the canopy's back, behind the rider's head
        return c
    ellipsoid(cv, 'canopy', P_GLASS, 21.5, 15, 8, 7.5, glass, clip=lambda x, y: y <= 20)
    for y in range(H):
        for x in range(W):
            if cv.c[y][x] == (232, 248, 252):
                cv.front[y][x] = True
                cv.seat[y][x] = False
    cv.outline(['canopy'])
    for y in range(H):
        for x in range(W):
            if cv.part[y][x] == 'canopy' and cv.c[y][x] in (P_GLASS.outline, P_GLASS.lit):
                cv.seat[y][x] = False
                cv.front[y][x] = True
    # the near wing and tail plane, in front of the body, their tops in the light
    poly(cv, 'wing', P_TRIM, [(17, 24), (33, 24), (40, 41), (27, 41)], light=top_lit, front=True)
    for y in range(25, 41):
        x = 17 + (y - 24) * 10 // 17 + 2
        cv.put(x, y, P_TRIM.ramp[-1], 'wing', P_TRIM, True)   # a lit leading edge
        cv.put(x + 1, y, P_TRIM.ramp[-1], 'wing', P_TRIM, True)
    poly(cv, 'tail', P_TRIM, [(45, 23), (54, 23), (58, 31), (51, 31)], light=top_lit, front=True)
    cv.outline(['wing', 'tail'])
    # the spinner and the propeller
    ellipsoid(cv, 'spinner', P_TRIM, 4.5, 21, 2.6, 3.2, front=True)
    cv.outline(['spinner'])
    propeller(cv, 2, 21, 9, frame)
    for y in range(H):
        for x in range(W):
            if cv.part[y][x] in ('body', 'fin'):
                cv.front[y][x] = True
    return cv


def propeller(cv, x, cy, r, frame):
    """A two-bladed propeller at the nose, seen from the side (its disc edge on): the blade upright at full length, then
    shorter (turning toward us), then a stub at the hub (edge on), then shorter again - four frames that read as a spin.
    No blur specks round it. Drawn over everything (FRONT)."""
    half = [r, r * 2 // 3, 2, r * 2 // 3][frame]
    line(cv, 'prop', BLADE[0], [(x, cy - half), (x, cy + half)], front=True)
    if half > 2:
        line(cv, 'prop', BLADE[1], [(x - 1, cy - half + 1), (x - 1, cy + half - 1)], front=True)
    cv.put(x, cy - half, BLADE[1], 'prop', None, True)   # its tips a shade lighter
    cv.put(x, cy + half, BLADE[1], 'prop', None, True)


HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "../app/src/main/cpp/pk_flight_vehicles.h")
SIZE = 64   # pk_flight.c's FL_SRC: a picture fits 64 x 64 around its anchor

# name, frames, the sequence of frames (one per [ticks] game frames), the player's sprite's top left (its 16 x 32 frame:
# a hero's cap starts 13 rows down), the art point drawn at the bird's place, its lean (share of the heading, at most),
# whether the rider turns to the back / front going up / down (the balloon's open basket; else always from the side).
VEHICLES = [
    dict(name="BALLOON", build=balloon, frames=3, sequence=[0, 0, 1, 0, 1, 1, 0, 0, 0, 1, 2, 2, 1, 0], ticks=4,
         player=(8, 23), anchor=(16, 32), turn=(0.0, 0.0), upDown=True),
    dict(name="HELICOPTER", build=helicopter, frames=3, sequence=[0, 1, 2, 1], ticks=2,
         player=(11, 3), anchor=(24, 20), turn=(0.3, 0.26)),
    dict(name="ZEPPELIN", build=zeppelin, frames=2, sequence=[0, 1], ticks=3,
         player=(20, 13), anchor=(28, 22), turn=(0.2, 0.1)),
    dict(name="PLANE", build=plane, frames=4, sequence=[0, 1, 2, 3], ticks=2,
         player=(14, -3), anchor=(28, 21), turn=(0.3, 0.26)),
]


def encode(v):
    """Every frame's pixels and one palette for them all."""
    pal = [None]
    index = {}
    out = []
    w = h = 0
    for f in range(v["frames"]):
        cv = v["build"](f)
        w, h = cv.w, cv.h
        for y in range(h):
            for x in range(w):
                c = cv.c[y][x]
                b = 0
                if c is not None:
                    if c not in index:
                        index[c] = len(pal)
                        pal.append(c)
                    b = index[c]
                    if cv.front[y][x]:
                        b |= 0x40
                if cv.seat[y][x]:
                    b |= 0x80
                out.append(b)
    assert len(pal) <= 64, (v["name"], len(pal))
    ax, ay = v["anchor"]
    assert ax <= SIZE // 2 and w - ax <= SIZE // 2 and ay <= SIZE // 2 and h - ay <= SIZE // 2, v["name"]
    return w, h, out, pal


def write_header():
    lines = [
        "// Generated by scripts/gen_flight_vehicles.py - do not edit. The FLY ON vehicles (our own pixel art): a byte a",
        "// pixel, palette index in the low 6 bits (0 = none), 0x40 FRONT (drawn over the player), 0x80 SEAT (the player shows).",
        "#ifndef PK_FLIGHT_VEHICLES_H",
        "#define PK_FLIGHT_VEHICLES_H",
        "#include <stdint.h>",
        "",
        "struct pk_flight_vehicle {",
        "    int w, h, frames;",
        "    const uint8_t* pixels;        // frames * h * w",
        "    const uint32_t* palette;      // 64 ABGR colours (0 unused)",
        "    const uint8_t* sequence;      // which frame, one per [ticks] game frames",
        "    int sequenceLength, ticks;",
        "    int playerX, playerY;         // the player's sprite (16 x 32), its top left in the picture",
        "    int anchorX, anchorY;         // drawn at the bird's place",
        "    float turnShare, turnMax;     // its lean: a share of the heading's angle, at most (radians)",
        "    int upDown;                   // the rider shown from behind / the front going up / down (else from the side)",
        "};",
        "",
    ]
    rows = []
    for v in VEHICLES:
        n = v["name"].lower()
        w, h, px, pal = encode(v)
        abgr = [0] + [0xFF000000 | (c[2] << 16) | (c[1] << 8) | c[0] for c in pal[1:]]
        abgr += [0] * (64 - len(abgr))
        lines.append(f"static const uint8_t pk_vehicle_{n}_pixels[{len(px)}] = {{")
        for i in range(0, len(px), w):
            lines.append("    " + ",".join(str(b) for b in px[i:i + w]) + ",")
        lines.append("};")
        lines.append(f"static const uint32_t pk_vehicle_{n}_palette[64] = {{")
        for i in range(0, 64, 8):
            lines.append("    " + ", ".join(f"0x{c:08X}u" for c in abgr[i:i + 8]) + ",")
        lines.append("};")
        lines.append(f"static const uint8_t pk_vehicle_{n}_sequence[{len(v['sequence'])}] = {{ " + ", ".join(map(str, v["sequence"])) + " };")
        lines.append("")
        px0, py0 = v["player"]
        ax, ay = v["anchor"]
        ts, tm = v["turn"]
        rows.append(f"    {{ {w}, {h}, {v['frames']}, pk_vehicle_{n}_pixels, pk_vehicle_{n}_palette, pk_vehicle_{n}_sequence, "
                    f"{len(v['sequence'])}, {v['ticks']}, {px0}, {py0}, {ax}, {ay}, {ts}f, {tm}f, {1 if v.get('upDown') else 0} }},   // {v['name']}")
    lines.append("// PK_FLIGHT_VEHICLE's values from 1 (0 = the game's bird)")
    lines.append("static const struct pk_flight_vehicle pk_flight_vehicles[] = {")
    lines += rows
    lines.append("};")
    lines.append(f"#define PK_FLIGHT_VEHICLE_COUNT {len(VEHICLES)}")
    lines.append("")
    lines.append("#endif")
    with open(OUT, "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"wrote {OUT}")


def preview(d, hero=None):
    """Every frame at 8x; with [hero] (a 16 x 32 PNG of a hero standing, facing west, clear around it) seated in it the way
    pk_flight.c's flSeatPlayer does: over SEAT pixels only, FRONT pixels over them again."""
    from PIL import Image
    os.makedirs(d, exist_ok=True)
    hp = Image.open(hero).convert("RGBA").load() if hero else None
    for v in VEHICLES:
        for f in range(v["frames"]):
            cv = v["build"](f)
            im = Image.new("RGB", (cv.w * 8, cv.h * 8), (120, 176, 96))
            p = im.load()
            px0, py0 = v["player"]
            for y in range(cv.h):
                for x in range(cv.w):
                    c = cv.c[y][x] or ((150, 196, 120) if cv.seat[y][x] and not hp else None)
                    if hp and cv.seat[y][x] and not cv.front[y][x] and 0 <= x - px0 < 16 and 0 <= y - py0 < 32:
                        h = hp[x - px0, y - py0]
                        if h[3]:
                            c = h[:3]
                    if c is None:
                        continue
                    for yy in range(8):
                        for xx in range(8):
                            p[x * 8 + xx, y * 8 + yy] = c
            im.save(os.path.join(d, f"{v['name'].lower()}_{f}.png"))


if __name__ == "__main__":
    if "--preview" in sys.argv:
        preview(sys.argv[sys.argv.index("--preview") + 1], sys.argv[sys.argv.index("--hero") + 1] if "--hero" in sys.argv else None)
    write_header()
