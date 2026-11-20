#!/usr/bin/env python3
"""Generates the in-game party-slot look for the CFRU-engine hacks - Pokemon
Unbound, Radical Red, Odyssey and Amethyst - straight from the ROMs (closed
binaries, no decomp; paths from scripts/host_roms.conf, sha1-pinned).

All four ship the SAME party menu: vanilla FireRed's party_menu.c machinery
(the palette-ID tables and LoadPartyBoxPalette logic are byte-identical to
vanilla) with a new 2x3 layout of 112x40 slot windows, new slot tilemaps and
new background graphics. So the frame is extracted once per ROM and asserted
identical; only the sprites (status icons, Poke Ball) differ per game.

Where everything lives (rev-0 FireRed addresses the hacks kept, found by
matching the running game's VRAM/OAM/palette RAM against the ROM - see
native-capture/mgba_dump.c's `vdump`):
  0x11EFB0 / 0x11EFCC / 0x11EFF4  literal pool of AllocPartyMenuBgGfx:
      -> gPartyMenuBg_Gfx / _Tilemap / _Pal (LZ77)
  0x45A010  sSinglePartyMenuWindowTemplate: six 14x5-tile slot windows, palettes 3..8
  0x45A180 / 0x45A1C8 / 0x45A210  14x5 slot tilemaps: main / no-HP (eggs) / empty
  0x459EE4  sPartyBoxInfoRects[1] {blitFunc, dims[24]} - what every slot uses
  0x459F04  sPartyMenuSpriteCoords[SINGLE][0]: mon, held item, status, ball centres
  0x45A474 / 0x45A574  CompressedSpriteSheet + SpritePalette of the menu Poke
      Ball (tag 0x4B0) and the status icons (tag 0x4B2)
  0x1EAF00 / 0x1EEF00  FONT_SMALL glyphs/widths - still vanilla, asserted, so
      the FireRed font asset is reused

Outputs:
  app/.../companion/ui/CfruPartyStylesGen.kt     one PartySlotStyle per game
  app/.../companion/data/GenderRatiosCfru.kt     gBaseStats genderRatio per game

The art isn't bundled: the app rebuilds it from the player's ROM
(companion/data/RomArt.kt, CFRU_* / <game>_STATUS_GFX in
scripts/gen_rom_art_sigs.py) under the paths the styles name:
  partycfru/slot_{normal,selected,fainted,selected_fainted,
      nohp_normal,nohp_selected,empty}.png   112x40 frames
  partycfru/<game>/status_icons.png  one row of 32x8 icons (PSN, PAR,
      SLP, FRZ/FRB, BRN, PKRS, FNT)
  partybg/cfru_tile.png              32x32 tile of the menu's grid backdrop
Unbound's Poke Ball (the only one drawn - the others' sprite is blank) is
FireRed's own bytes, so its style uses partyfr/pokeball.png. This script still
extracts the art to check the layout it writes into the styles.

Checked pixel-for-pixel against headless screenshots of all four games
(normal / selected / fainted / selected+fainted / egg / status states).

Usage: scripts/gen_cfru_party_assets.py
"""
import hashlib
import os
import struct

from PIL import Image

from decomps import decomp

HERE = os.path.dirname(os.path.abspath(__file__))
KT_UI = os.path.join(HERE, "../shared-ui/src/commonMain/kotlin/com/pokedaisy/app/companion/ui/CfruPartyStylesGen.kt")
KT_DATA = os.path.join(HERE, "../shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data/GenderRatiosCfru.kt")
FR_DECOMP = decomp("pokefirered")
FR_ROM = open(os.path.join(FR_DECOMP, "pokefirered_rev1.gba"), "rb").read()


def fr_symbols():
    import subprocess
    out = {}
    for line in subprocess.run(["nm", os.path.join(FR_DECOMP, "pokefirered_rev1.elf")], capture_output=True, text=True,
                               check=True).stdout.splitlines():
        p = line.split()
        if len(p) == 3:
            out[p[2]] = int(p[0], 16) - 0x08000000
    return out


FR_SYMS = fr_symbols()

# host_roms.conf key -> Kotlin name, asset subdir, sha1, egg nickname as the
# game prints it, live gBaseStats (the one with literal-pool refs - Radical
# Red and Amethyst keep a stale vanilla copy at 0x254784 too), species count.
GAMES = {
    "unbound": dict(name="Unbound", sub="ub", sha1="b4776b82a4c7915d0fadeaa27e013523f99dfd94",
                    egg="Egg", base_stats=0x19E0C9C, species=1294),
    "radical_red": dict(name="RadicalRed", sub="rr", sha1="964f951a0fdaf209e4ea1344883ef0d557bb3a80",
                        egg="Egg", base_stats=0x17B98EC, species=1323),
    "odyssey": dict(name="Odyssey", sub="od", sha1="8745ddbdbfadf6abaf66de4e9055923b62eb4668",
                    egg="EGG", base_stats=0x254784, species=412),
    "amethyst": dict(name="Amethyst", sub="am", sha1="00e70c0384a5f1698588034201fd5b849d3542e2",
                     egg="Egg", base_stats=0x1BC257C, species=1268),
    # v1.4.1: the same party menu and sprites (checked equal), so no style of its own -
    # AmethystPartyStyle serves both; only its gender ratios (its own species ids).
    "amethyst_v141": dict(name="AmethystV141", sub="am", sha1="91291aade04b4b111cd03ae7b6e2ff460e1edd8a",
                          egg="Egg", base_stats=0x1A98390, species=1294, style=False),
    # Sword and Shield Ultimate Plus: the same tiles, slot tilemaps and layout in a palette of its
    # own (Galar red / charcoal), so its frames are its own (RomArt's partyswsh/) and so are the
    # text / gender / HP colours read from that palette. Gender ratios: gen_cfru_tables.py's.
    # Its status icons are Odyssey's bytes (sub "od", RomArt's OD_STATUS_GFX).
    "sword_shield_up": dict(name="SwordShield", sub="od", sha1="afe4c8e0f9c7186860fcea5f25d8318f5d7929b9",
                            egg="Egg", base_stats=0x19AFBEC, species=1268, own_palette="partyswsh", ratios=False),
}

WIN_W, WIN_H = 14, 5
WIN_PAL = 3
TILEMAPS = {"main": 0x45A180, "nohp": 0x45A1C8, "empty": 0x45A210}  # 70 bytes + 2 padding apart
STATES = {  # vanilla LoadPartyBoxPalette: (idx 4,5,6 <- ids1), (idx 1,7,8 <- ids2)
    "normal": ((52, 53, 54), (49, 55, 56)),
    "selected": ((116, 117, 118), (97, 103, 104)),
    "fainted": ((84, 85, 86), (81, 87, 88)),
    "selected_fainted": ((148, 149, 150), (97, 103, 104)),
}
FRAMES = dict((k, ("main", v)) for k, v in STATES.items())
FRAMES["nohp_normal"] = ("nohp", STATES["normal"])
FRAMES["nohp_selected"] = ("nohp", STATES["selected"])
FRAMES["empty"] = ("empty", STATES["normal"])  # PARTY_PAL_NO_MON falls through to the default ids
# Widen the box inside its dark diagonal band, where every column is
# identical (asserted).
STRETCH_COL = 60
# Heighten it by inserting copies of plain rows (side borders + fill only):
# above the name, between the name and the HP box, below the level/HP line.
# The band's two diagonal edges get no uniform row, so the app redraws them as
# straight lines between their end points at the new height (DiagonalBand).
STRETCH_ROWS = (8, 19, 32)
FILL = (4, 5, 6)  # light fill, diagonal edge, dark band (window palette indices)


def conf_rom(key):
    for line in open(os.path.join(HERE, "host_roms.conf")):
        f = line.rstrip("\n").split("|")
        if not line.startswith("#") and f[0] == key:
            path = os.path.expanduser(f[2])
            # Unbound's dump is named "Pokémon Unbound (...)" on some machines.
            alt = path.replace("/Pokemon - ", "/Pokémon ")
            return path if os.path.exists(path) or not os.path.exists(alt) else alt
    raise SystemExit(f"{key} missing from host_roms.conf")


def lz77(rom, off):
    assert rom[off] == 0x10, hex(off)
    size = rom[off + 1] | rom[off + 2] << 8 | rom[off + 3] << 16
    out, p = bytearray(), off + 4
    while len(out) < size:
        flags = rom[p]; p += 1
        for b in range(8):
            if len(out) >= size:
                break
            if flags & (0x80 >> b):
                x = rom[p] << 8 | rom[p + 1]; p += 2
                for _ in range((x >> 12) + 3):
                    out.append(out[-((x & 0xFFF) + 1)])
            else:
                out.append(rom[p]); p += 1
    return bytes(out)


def u32(rom, off):
    return struct.unpack_from("<I", rom, off)[0]


def ptr(rom, off):
    v = u32(rom, off)
    assert v >> 25 == 4, (hex(off), hex(v))  # 0x08xxxxxx / 0x09xxxxxx
    return v & 0x1FFFFFF


def expand(v5):
    return (v5 << 3) | (v5 >> 2)  # mGBA's 5->8 bit expansion (what the game shows)


def colors(pal):
    return [(expand(c & 31), expand((c >> 5) & 31), expand((c >> 10) & 31))
            for c in struct.unpack_from("<%dH" % (len(pal) // 2), pal)]


def tile_px(gfx, t, x, y):
    b = gfx[t * 32 + y * 4 + x // 2]
    return (b >> 4) if x & 1 else b & 15


def sprite_sheet(gfx, cols, rows, pal):
    """1D-mapped OBJ tiles -> RGBA, cols x rows tiles."""
    im = Image.new("RGBA", (cols * 8, rows * 8), (0, 0, 0, 0))
    p = im.load()
    for ty in range(rows):
        for tx in range(cols):
            for y in range(8):
                for x in range(8):
                    v = tile_px(gfx, ty * cols + tx, x, y)
                    if v:
                        p[tx * 8 + x, ty * 8 + y] = pal[v] + (255,)
    return im


def uniform_lines(imgs, axis):
    res = None
    for im in imgs:
        W, H = im.size
        if axis == 0:
            line = lambda i: [im.getpixel((i, y)) for y in range(H)]; n = W
        else:
            line = lambda i: [im.getpixel((x, i)) for x in range(W)]; n = H
        s = {i for i in range(1, n) if line(i) == line(i - 1)}
        res = s if res is None else res & s
    return res


def extract(key, cfg):
    rom = open(conf_rom(key), "rb").read()
    assert hashlib.sha1(rom).hexdigest() == cfg["sha1"], (key, "unexpected ROM (sha1)")

    # Window templates: six 14x5 slots in palettes 3..8 (the layout this
    # script's geometry assumes).
    for i in range(6):
        bg, left, top, w, h, palno = rom[0x45A010 + i * 8:0x45A010 + i * 8 + 6]
        assert (bg, w, h, palno) == (0, WIN_W, WIN_H, WIN_PAL + i), (key, "window template", i)

    gfx = lz77(rom, ptr(rom, 0x11EFB0))
    pal = colors(lz77(rom, ptr(rom, 0x11EFF4)))
    bgmap = lz77(rom, ptr(rom, 0x11EFCC))

    imap = {tm: [[tile_px(gfx, rom[off + (y // 8) * WIN_W + x // 8], x % 8, y % 8) for x in range(WIN_W * 8)]
                 for y in range(WIN_H * 8)] for tm, off in TILEMAPS.items()}
    frames, state_cols = {}, {}
    for st, (tm, (ids1, ids2)) in FRAMES.items():
        cols = list(pal[WIN_PAL * 16:WIN_PAL * 16 + 16])
        for idx, pid in zip((4, 5, 6), ids1): cols[idx] = pal[pid]
        for idx, pid in zip((1, 7, 8), ids2): cols[idx] = pal[pid]
        img = Image.new("RGBA", (WIN_W * 8, WIN_H * 8), (0, 0, 0, 0))
        out = img.load()
        for ty in range(WIN_H):
            for tx in range(WIN_W):
                t = rom[TILEMAPS[tm] + ty * WIN_W + tx]
                for y in range(8):
                    for x in range(8):
                        v = tile_px(gfx, t, x, y)
                        if v:
                            out[tx * 8 + x, ty * 8 + y] = cols[v] + (255,)
        frames[st] = img
        state_cols[st] = cols

    # The party-menu backdrop (BG1): a navy grid with a line every 32px. One
    # clean 32x32 cell (clear of the zig-zag connectors between the slot
    # columns) in the screen's own phase - tiled from (0, 0) it reproduces the
    # grid exactly.
    full = Image.new("RGBA", (256, 256))
    fp = full.load()
    for ty in range(32):
        for tx in range(32):
            e = struct.unpack_from("<H", bgmap, (ty * 32 + tx) * 2)[0]
            t, hf, vf, pb = e & 0x3FF, (e >> 10) & 1, (e >> 11) & 1, e >> 12
            for y in range(8):
                for x in range(8):
                    v = tile_px(gfx, t, 7 - x if hf else x, 7 - y if vf else y)
                    fp[tx * 8 + x, ty * 8 + y] = pal[pb * 16 + v] + (255,)
    backdrop = full.crop((32, 32, 64, 64))
    assert full.crop((64, 64, 96, 96)).tobytes() == backdrop.tobytes(), (key, "grid cell not periodic")

    # Sprites: sheets are {ptr, size, tag} + {ptr, tag}.
    assert u32(rom, 0x45A478) == 0x04B00400 and u32(rom, 0x45A578) == 0x04B20400, key
    ball_gfx = lz77(rom, ptr(rom, 0x45A474))
    ball = None
    if any(ball_gfx):
        # FireRed's Poke Ball and palette, byte for byte (what partyfr/pokeball.png is made of).
        fr_ball = lz77(FR_ROM, FR_SYMS["gPartyMenuPokeball_Gfx"])
        fr_pal = lz77(FR_ROM, FR_SYMS["gPartyMenuPokeball_Pal"])
        assert ball_gfx == fr_ball and lz77(rom, ptr(rom, 0x45A47C)) == fr_pal, (key, "Poke Ball isn't FireRed's")
        ball = "partyfr/pokeball.png"
    st_img = sprite_sheet(lz77(rom, ptr(rom, 0x45A574)), 4 * 7, 1, colors(lz77(rom, ptr(rom, 0x45A57C))))

    # Text rects (x, y, w, h): nickname, level, gender, HP, max HP, HP bar.
    dims = rom[0x459EE8:0x459EE8 + 24]
    coords = rom[0x459F04:0x459F0C]  # mon, item, status, ball centres (screen px)
    left, top = rom[0x45A011], rom[0x45A012]  # slot 0 window, in tiles

    # FONT_SMALL: vanilla, so FireRed's generated font asset applies as-is.
    font = open(os.path.join(FR_DECOMP, "graphics/fonts/latin_small.hwlatfont"), "rb").read()
    assert rom[0x1EAF00:0x1EAF00 + len(font)] == font, (key, "FONT_SMALL glyphs differ from vanilla")

    # gBaseStats: 28-byte records; sanity-check Bulbasaur/Charmander and that
    # code really points at this copy.
    bs = cfg["base_stats"]
    assert rom[bs + 28:bs + 34] == bytes([45, 49, 49, 45, 65, 65]), key
    assert rom[bs + 28 * 4:bs + 28 * 4 + 6] == bytes([39, 52, 43, 65, 60, 50]), key
    assert rom.count(struct.pack("<I", 0x08000000 | bs)) >= 40, (key, "gBaseStats copy isn't referenced")
    ratios = [rom[bs + s * 28 + 0x10] for s in range(cfg["species"])]

    return dict(frames=frames, imap=imap, state_cols=state_cols, backdrop=backdrop, ball=ball, status=st_img, dims=dims, coords=coords,
                win=(left * 8, top * 8), pal=pal, ratios=ratios)


def diagonal_band(idx, state_cols):
    """The main/no-HP frames' fill: light | edge | dark band | edge | light,
    split by two straight single-pixel diagonals. Measured, then checked to be
    exactly what DiagonalBand's rule redraws (so at the original height the
    app's rebuilt frame is pixel-identical), and that the rule can tell the
    three fill colours apart from everything else in every state."""
    main = idx["main"]
    rows = [y for y in range(len(main)) if 5 in main[y]]
    top, bottom = rows[0], rows[-1]
    edges = lambda y: [x for x, v in enumerate(main[y]) if v == 5]
    (lt, rt), (lb, rb) = edges(top), edges(bottom)
    for tm in ("main", "nohp"):
        m = idx[tm]
        for y in range(top, bottom + 1):
            t = (y - top) / (bottom - top)
            xl, xr = round(lt + (lb - lt) * t), round(rt + (rb - rt) * t)
            for x, v in enumerate(m[y]):
                if v in FILL:
                    want = 5 if x in (xl, xr) else 4 if (x < xl or x > xr) else 6
                    assert v == want, (tm, x, y, v, want)
        for r in STRETCH_ROWS:  # plain rows: borders + fill only
            assert set(m[r]) <= {0, 1, 7, 8} | set(FILL), (tm, r, set(m[r]))
            assert top < r < bottom
    for st, cols in state_cols.items():
        if st == "empty":
            continue
        tm = "nohp" if st.startswith("nohp") else "main"
        used = {v for row in idx[tm] for v in row} - {0}
        fill = [cols[v] for v in FILL]
        assert len(set(fill)) == 3, st
        assert not set(fill) & {cols[v] for v in used - set(FILL)}, (st, "fill colour shared")
    # The empty slot is alternating stripe/blank rows: grows by whole pairs.
    e = idx["empty"]
    stripe = next(y for y in range(len(e) // 2, len(e)) if any(e[y]) and not any(e[y + 1]))
    assert e[stripe] == e[stripe - 2] == e[stripe + 2] and not any(e[stripe - 1]), stripe
    return dict(top=top, bottom=bottom, lt=lt, lb=lb, rt=rt, rb=rb, stripe=stripe)


def argb(c):
    return "0xFF%02X%02X%02X" % c


def main():
    got = {k: extract(k, cfg) for k, cfg in GAMES.items()}
    first = next(iter(got.values()))
    for k, g in got.items():  # one shared frame set
        assert g["imap"] == first["imap"], (k, "slot tiles / tilemaps differ")
        assert g["dims"] == first["dims"] and g["coords"] == first["coords"] and g["win"] == first["win"], k
        if GAMES[k].get("own_palette"):
            continue
        for st, im in g["frames"].items():
            assert im.tobytes() == first["frames"][st].tobytes(), (k, st, "frame differs between games")
        assert g["backdrop"].tobytes() == first["backdrop"].tobytes(), k
        assert g["dims"] == first["dims"] and g["coords"] == first["coords"] and g["win"] == first["win"], k
        assert g["pal"] == first["pal"], k

    frames = first["frames"]
    ucols = uniform_lines(frames.values(), 0)
    assert STRETCH_COL in ucols, sorted(ucols)
    bbox = frames["normal"].getbbox()
    assert all(f.getbbox() == bbox for st, f in frames.items() if st != "empty")  # empty: its own outline

    band = diagonal_band(first["imap"], first["state_cols"])

    dims, (wx, wy) = first["dims"], first["win"]
    rect = lambda i: tuple(dims[i * 4:i * 4 + 4])
    name, level, gender, hp, maxhp, bar = (rect(i) for i in range(6))
    mon_cx, mon_cy, _, _, st_cx, st_cy, ball_cx, ball_cy = first["coords"]
    pal = first["pal"]
    n = [pal[WIN_PAL * 16 + i] for i in range(16)]
    for i, pid in zip((4, 5, 6, 1, 7, 8), STATES["normal"][0] + STATES["normal"][1]):
        n[i] = pal[pid]

    def colours(p):
        """The style's colours, out of a party palette [p] (the window's 16 with the normal state's ids)."""
        w = [p[WIN_PAL * 16 + i] for i in range(16)]
        for i, pid in zip((4, 5, 6, 1, 7, 8), STATES["normal"][0] + STATES["normal"][1]):
            w[i] = p[pid]
        return dict(text=w[3], textShadow=w[2], male=p[59], maleShadow=p[60], female=p[75], femaleShadow=p[76],
                    hpGreenTop=p[58], hpGreen=p[57], hpYellowTop=p[74], hpYellow=p[73], hpRedTop=p[90], hpRed=p[89],
                    hpEmptyTop=w[13], hpEmpty=w[2])

    styles = []
    for k, cfg in GAMES.items():
        if not cfg.get("style", True):
            continue
        g = got[k]
        ball_asset = "null" if g["ball"] is None else f'"{g["ball"]}"'
        own = cfg.get("own_palette")
        status = f"partycfru/{cfg['sub']}/status_icons.png"
        tail = ")\n"
        if own:
            c = colours(g["pal"])
            # Its slots are drawn solid: the game shows the palette's own fill (0x212121), no blending.
            tail = (f").copy(\n    frameDir = \"{own}\",\n" +
                    "".join(f"    {f} = {argb(v)},\n" for f, v in c.items()) + "    blendOverBackdrop = false,\n)\n")
        styles.append(f'''{"/** Its own palette over the shared slot: RomArt's " + own + "/ frames and its colours. */" + chr(10) if own else ""}val {cfg["name"]}PartyStyle = cfruPartyStyle(
    statusAsset = "{status}",
    pokeballAsset = {ball_asset},
    eggName = "{cfg["egg"]}",
{tail}''')

    kt = f"""package com.pokedaisy.app.companion.ui

// GENERATED by scripts/gen_cfru_party_assets.py from the Unbound / Radical
// Red / Odyssey / Amethyst / Sword and Shield Ultimate Plus ROMs - do not hand-edit; rerun the script instead.

/** The shared CFRU party slot (identical frame, font and layout in all five ROMs; Sword and Shield's in its own palette). */
private fun cfruPartyStyle(statusAsset: String, pokeballAsset: String?, eggName: String) = PartySlotStyle(
    frameDir = "partycfru",
    fontAsset = "partyfr/font_small.png",
    statusAsset = statusAsset,
    pokeballAsset = pokeballAsset,
    glyphWidths = FireRedPartyStyle.glyphWidths,
    lvGlyph = 0x105,
    padGlyph = 0x00,
    text = {argb(n[3])}, textShadow = {argb(n[2])},
    male = {argb(pal[59])}, maleShadow = {argb(pal[60])},
    female = {argb(pal[75])}, femaleShadow = {argb(pal[76])},
    hpGreenTop = {argb(pal[58])}, hpGreen = {argb(pal[57])},
    hpYellowTop = {argb(pal[74])}, hpYellow = {argb(pal[73])},
    hpRedTop = {argb(pal[90])}, hpRed = {argb(pal[89])},
    hpEmptyTop = {argb(n[13])}, hpEmpty = {argb(n[2])},
    slotW = {WIN_W * 8}, slotH = {WIN_H * 8},
    nameX = {name[0]}, nameY = {name[1]},
    levelX = {level[0]}, levelY = {level[1]},
    genderX = {gender[0]}, genderY = {gender[1]},
    hpX = {hp[0]}, maxHpX = {maxhp[0]}, hpY = {hp[1]},
    barX = {bar[0]}, barY = {bar[1]}, barW = {bar[2]},
    // Sprite centres (sPartyMenuSpriteCoords) -> top-left, window-relative.
    iconX = {mon_cx} - 16 - {wx}, iconY = {mon_cy} - 16 - {wy},
    // AnimateSelectedPartyIcon: an icon not at x 16 rests 4px LEFT.
    iconRestDx = -4, iconRestDy = 0,
    ballX = {ball_cx} - 16 - {wx}, ballY = {ball_cy} - 16 - {wy},
    ballFollowsIcon = true,
    statusX = {st_cx} - 16 - {wx}, statusY = {st_cy} - 4 - {wy},
    leftCol = {STRETCH_COL}, midCol = {STRETCH_COL},
    topRow = {STRETCH_ROWS[0]}, midRow = {STRETCH_ROWS[1]}, bottomRow = {STRETCH_ROWS[2]}, stretchRows = true,
    band = DiagonalBand(
        top = {band["top"]}, bottom = {band["bottom"]},
        leftTopX = {band["lt"]}, leftBottomX = {band["lb"]},
        rightTopX = {band["rt"]}, rightBottomX = {band["rb"]},
        emptyStripeRow = {band["stripe"]},
    ),
    visibleW = {bbox[2]}, visibleH = {bbox[3]},
    regionX0 = 0, regionY0 = {mon_cy} - 16 - {wy},
    eggName = eggName,
    upperCaseNames = false,
    hasEmptySlot = true,
    blendOverBackdrop = true,
)

""" + "\n".join(styles)
    open(KT_UI, "w").write(kt)

    parts = []
    for k, cfg in GAMES.items():
        if not cfg.get("ratios", True):
            continue
        hexs = "".join("%02x" % r for r in got[k]["ratios"])
        parts.append(f"""/** {cfg["name"]}: gBaseStats @ ROM 0x{cfg["base_stats"]:X}, species 0..{cfg["species"] - 1}. */
val genderRatios{cfg["name"]}: IntArray by lazy {{ unhexRatios("{hexs}") }}
""")
    open(KT_DATA, "w").write("""package com.pokedaisy.app.companion.data

// GENERATED by scripts/gen_cfru_party_assets.py from each hack's live
// gBaseStats (genderRatio = u8 at +0x10 of each 28-byte record, by the hack's
// own species ids) - do not hand-edit. Same meaning as genderRatiosFireRed:
// 0 always male, 254 always female, 255 genderless, else female when
// (personality & 0xFF) < ratio.

internal fun unhexRatios(s: String) = IntArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16) }

""" + "\n".join(parts))
    print("wrote", KT_UI, KT_DATA)


if __name__ == "__main__":
    main()
