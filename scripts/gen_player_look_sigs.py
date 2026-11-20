#!/usr/bin/env python3
"""Fingerprints of the player's own graphics in FireRed / LeafGreen and Emerald, for PLAYER LOOK
(companion/data/PlayerLook.kt): playing one game as another game's hero, the sprites taken from the player's own
ROM of that game. Nothing of the ROM is bundled: per graphic this stores, like gen_rom_art_sigs.py,

  size    decoded length (LZ77 graphics: the decompressed length)
  crc     CRC32 of the decoded bytes
  headOff / head   a 16-byte window of the graphic's raw ROM bytes and its 64-bit hash (RomArt.head)
  pre     the window's 16-bit prefilter (RomArt.pre)

What each graphic is comes from the pinned decomps: FireRed's and Ruby's built .4bpp / .gbapal / .lz files, Emerald's
PNGs and JASC palettes converted here as its build does (gbagfx's -mwidth / -mheight: one frame's tiles after
another). Every one is then found in the retail ROMs (FireRed rev 1, Emerald, Ruby rev 1: scripts/host_roms.conf's
firered_vanilla / emerald_vanilla / ruby_rev1), and the window is taken from there.

Usage: scripts/gen_player_look_sigs.py [ROM ...]   (needs $DECOMPS/pokefirered and $DECOMPS/pokeruby built, and
$DECOMPS/pokeemerald;
extra ROMs are only reported: which graphics each one holds)
"""
import os
import sys
import zlib

from decomps import decomp
import smol
from gen_rom_art_sigs import head, lz77, pre

HERE = os.path.dirname(os.path.abspath(__file__))
KOTLIN = os.path.join(HERE, "../shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data/PlayerLookSigsGen.kt")
FR = decomp("pokefirered", "graphics")
EM = decomp("pokeemerald", "graphics")
RS = decomp("pokeruby", "graphics")
HNS = decomp("pokehns", "graphics")

# (part, file under graphics/, lz, metatile width x height in tiles or None)
OW_FR = [("FR_NORMAL", "normal", (2, 4)), ("FR_SURF_RUN", "surf_run", (2, 4)), ("FR_BIKE", "bike", (4, 4)),
         ("FR_ITEM", "item", (2, 4)), ("FR_FISH", "fish", (4, 4)), ("FR_VS_SEEKER_BIKE", "vs_seeker_bike", (4, 4))]
OW_EM = [("EM_WALKING", "walking", (2, 4)), ("EM_RUNNING", "running", (2, 4)), ("EM_MACH_BIKE", "mach_bike", (4, 4)),
         ("EM_ACRO_BIKE", "acro_bike", (4, 4)), ("EM_SURFING", "surfing", (4, 4)), ("EM_FIELD_MOVE", "field_move", (4, 4)),
         ("EM_FISHING", "fishing", (4, 4)), ("EM_UNDERWATER", "underwater", (4, 4)), ("EM_WATERING", "watering", (4, 4)),
         ("EM_DECORATING", "decorating", (2, 4))]


def fr_char(name, ow):
    g = "red" if name == "RED" else "leaf"
    return [(p, f"{FR}/object_events/pics/people/{ow}_{f}.4bpp", False) for p, f, _ in OW_FR] + [
        ("OW_PAL", f"{FR}/object_events/palettes/player.gbapal", False),
        ("OW_REFLECTION_PAL", f"{FR}/object_events/palettes/player_reflection.gbapal", False),
        ("BACK", f"{FR}/trainers/back_pics/{g}_back_pic.4bpp", False),
        ("BACK_PAL", f"{FR}/trainers/palettes/{g}_back_pic.gbapal", True),
        ("FRONT", f"{FR}/trainers/front_pics/{g}_front_pic.4bpp", True),
        ("FRONT_PAL", f"{FR}/trainers/palettes/{g}.gbapal", True),
        ("MAP_HEAD", f"{FR}/region_map/player_icon_{g}.4bpp", True),
        ("MAP_HEAD_PAL", f"{FR}/region_map/player_icon_{g}.gbapal", False),
    ]


def em_char(g):
    return [(p, (f"{EM}/object_events/pics/people/{g}/{f}.png", wh), False) for p, f, wh in OW_EM] + [
        ("OW_PAL", f"{EM}/object_events/palettes/{g}.pal", False),
        ("OW_REFLECTION_PAL", f"{EM}/object_events/palettes/{g}_reflection.pal", False),
        ("UNDERWATER_PAL", f"{EM}/object_events/palettes/player_underwater.pal", False),
        ("BACK", (f"{EM}/trainers/back_pics/{g}.png", (8, 8)), False),
        # One palette for both of Emerald's pictures (the front and back palette tables point at the same data).
        ("BACK_PAL", f"{EM}/trainers/palettes/{g}.pal", True),
        ("FRONT", (f"{EM}/trainers/front_pics/{g}.png", (8, 8)), True),
        ("FRONT_PAL", f"{EM}/trainers/palettes/{g}.pal", True),
        ("MAP_HEAD", (f"{EM}/pokenav/region_map/{g}_icon.png", (2, 2)), False),
        ("MAP_HEAD_PAL", f"{EM}/pokenav/region_map/{g}_icon.png", False),
    ]


# Ruby / Sapphire: Emerald's sheets but walking and running in one (RS_NORMAL), its other sheets the same size and
# frames as Emerald's (so the same parts); the pictures compressed, the back one too. No reflection palette: both
# heroes' reflections use palette 10 (tag 0x1102), one flat water colour (PlayerLook makes one for these heroes from
# their own palette when another game wears them).
def rs_char(g, pal):
    return [("RS_NORMAL", f"{RS}/object_events/pics/people/{g}/normal.4bpp", False)] + [
        (p, f"{RS}/object_events/pics/people/{g}/{f}.4bpp", False) for p, f, _ in OW_EM if p not in ("EM_WALKING", "EM_RUNNING")] + [
        ("OW_PAL", f"{RS}/object_events/palettes/{pal}.gbapal", False),
        ("UNDERWATER_PAL", f"{RS}/object_events/palettes/11.gbapal", False),
        ("BACK", f"{RS}/trainers/{g}_back.4bpp", True),
        # One palette for both pictures, as in Emerald.
        ("BACK_PAL", f"{RS}/trainers/{g}.gbapal", True),
        ("FRONT", f"{RS}/trainers/{g}.4bpp", True),
        ("FRONT_PAL", f"{RS}/trainers/{g}.gbapal", True),
        ("MAP_HEAD", f"{RS}/pokenav/{g}_icon.4bpp", False),
        ("MAP_HEAD_PAL", f"{RS}/pokenav/{g}_icon.gbapal", False),
    ]


# Heart and Soul's Gold and Kris (pokehns-expansion at 2.0.6): Emerald's sheets frame for frame, worn by the other
# games only (Heart and Soul itself isn't dressed up). The front picture is smol-compressed, the rest raw. Its sparse
# checkout needs graphics/object_events/pics/people/{gold,kris}/, their palettes, trainer pictures and region map icons.
def hns_char(g):
    return [(p, (f"{HNS}/object_events/pics/people/{g}/{f}_hns.png", wh), False) for p, f, wh in OW_EM] + [
        ("OW_PAL", f"{HNS}/object_events/palettes/{g}_hns.pal", False),
        ("OW_REFLECTION_PAL", f"{HNS}/object_events/palettes/{g}_reflection_hns.pal", False),
        ("BACK", (f"{HNS}/trainers/back_pics/{g}_hns.png", (8, 8)), False),
        ("BACK_PAL", f"{HNS}/trainers/back_pics/{g}_hns.pal", False),
        ("FRONT", (f"{HNS}/trainers/front_pics/{g}_hns.png", (8, 8)), "smol"),
        ("FRONT_PAL", f"{HNS}/trainers/palettes/{g}_hns.pal", False),
        ("MAP_HEAD", (f"{HNS}/pokenav/region_map/{g}_icon.png", (2, 2)), False),
        ("MAP_HEAD_PAL", f"{HNS}/pokenav/region_map/{g}_icon.png", False),
    ]


CHARS = {"RED": ("firered", fr_char("RED", "red")), "LEAF": ("firered", fr_char("LEAF", "green")),
         "BRENDAN": ("emerald", em_char("brendan")), "MAY": ("emerald", em_char("may")),
         "RS_BRENDAN": ("ruby", rs_char("brendan", "08")), "RS_MAY": ("ruby", rs_char("may", "17")),
         "GOLD": ("heart_and_soul", hns_char("gold")), "KRIS": ("heart_and_soul", hns_char("kris"))}


def png_4bpp(path, mw, mh):
    """gbagfx png -> 4bpp with -mwidth / -mheight: metatiles in reading order, tiles in each in reading order."""
    from PIL import Image
    img = Image.open(path)
    assert img.mode == "P", path
    w, h = img.size
    px = img.load()
    out = bytearray()
    for my in range(h // (mh * 8)):
        for mx in range(w // (mw * 8)):
            for ty in range(mh):
                for tx in range(mw):
                    x0, y0 = (mx * mw + tx) * 8, (my * mh + ty) * 8
                    for y in range(8):
                        for x in range(0, 8, 2):
                            out.append((px[x0 + x, y0 + y] & 15) | (px[x0 + x + 1, y0 + y] & 15) << 4)
    return bytes(out)


def gbapal(colours):
    out = bytearray()
    for r, g, b in colours[:16]:
        out += ((r >> 3) | (g >> 3) << 5 | (b >> 3) << 10).to_bytes(2, "little")
    return bytes(out)


def content(src):
    if isinstance(src, tuple):
        path, (mw, mh) = src
        return png_4bpp(path, mw, mh)
    if src.endswith(".pal"):
        lines = open(src).read().split()
        n = int(lines[2])
        return gbapal([tuple(map(int, lines[3 + i * 3:6 + i * 3])) for i in range(n)])
    if src.endswith(".png"):  # its palette
        from PIL import Image
        p = Image.open(src).getpalette()[:48]
        return gbapal([tuple(p[i:i + 3]) for i in range(0, 48, 3)])
    return open(src, "rb").read()


def find_smol(rom, data):
    """Smol streams decoding to [data]: tried at every 4-aligned target of a ROM pointer (a picture is reached through
    its table), the ROM's pointers collected once."""
    import struct
    global _PTRS
    if _PTRS is None or _PTRS[0] is not rom:
        targets = sorted({w - 0x08000000 for (w,) in struct.iter_unpack("<I", rom[:len(rom) & ~3])
                          if 0x08000000 <= w < 0x08000000 + len(rom) and w % 4 == 0})
        _PTRS = (rom, targets)
    out = []
    for off in _PTRS[1]:
        if rom[off] & 0x0F not in range(1, 7):
            continue
        try:
            d, n = smol.decode(rom[off:off + 0x10000])
        except Exception:
            continue
        if d[:len(data)] == data and len(d) >= len(data):
            out.append((off, n))
    return out


_PTRS = None


def find_all(rom, data, lz):
    """Offsets of [data] in [rom]: as is, LZ77-compressed (4-aligned) or smol-compressed (at a pointer's target)."""
    if lz == "smol":
        return find_smol(rom, data)
    if not lz:
        out, i = [], rom.find(data)
        while i >= 0:
            out.append((i, len(data)))
            i = rom.find(data, i + 1)
        return out
    out, hdr = [], bytes([0x10]) + len(data).to_bytes(3, "little")
    i = rom.find(hdr)
    while i >= 0:
        if i % 4 == 0:
            try:
                d, n = lz77(rom, i)
                if d == data:
                    out.append((i, n))
            except (IndexError, AssertionError):
                pass
        i = rom.find(hdr, i + 1)
    return out


# The sprites the games draw with the player's palette slot (paletteNum 0, no tag of their own): the surf blob, the
# jump shadows, the warp arrows and Emerald's FLY bird. A hero dressed up from the other game keeps the colours they
# use where they are (PlayerLook.wear). FireRed's FLY bird uses all 16 and is left to take the new colours.
SHARED = {"FIRERED": [f"{FR}/object_events/pics/misc/surf_blob.4bpp", f"{FR}/field_effects/pics/arrow.4bpp"] +
                     [f"{FR}/field_effects/pics/shadow_{s}.4bpp" for s in ("small", "medium", "large", "extra_large")],
          "EMERALD": [f"{EM}/field_effects/pics/{n}.png" for n in ("surf_blob", "bird", "arrow")] +
                     [f"{EM}/field_effects/pics/shadow_{s}.png" for s in ("small", "medium", "large", "extra_large")],
          "RUBY": [f"{RS}/field_effect_objects/pics/{n}.4bpp" for n in ("surf_blob", "bird", "arrow")] +
                  [f"{RS}/field_effect_objects/pics/shadow_{s}.4bpp" for s in ("small", "medium", "large", "extra_large")]}


def colours_used(path):
    if path.endswith(".png"):
        from PIL import Image
        return set(Image.open(path).getdata())
    return {n for b in open(path, "rb").read() for n in (b & 15, b >> 4)}


def host_rom(key):
    for line in open(os.path.join(HERE, "host_roms.conf")):
        p = line.rstrip("\n").split("|")
        if p[0] == key:
            return os.path.expanduser(p[2])
    raise SystemExit(f"no {key} in host_roms.conf")


def window(rom, off, raw_len):
    copies = rom.count(rom[off:off + raw_len])
    for distinct in (6, 4, 3):
        w = next((w for w in range(0, raw_len - 15, 2) if len(set(rom[off + w:off + w + 16])) >= distinct
                  and rom.count(rom[off + w:off + w + 16]) == copies), None)
        if w is not None:
            return w
    # A palette many places keep (FireRed's player palette: Red's and Leaf's): any varied window, the CRC decides.
    return next(w for w in range(0, raw_len - 15, 2) if len(set(rom[off + w:off + w + 16])) >= 3)


def main():
    roms = {"firered": open(host_rom("firered_vanilla"), "rb").read(), "emerald": open(host_rom("emerald_vanilla"), "rb").read(),
            "ruby": open(host_rom("ruby_rev1"), "rb").read(), "heart_and_soul": open(host_rom("heart_and_soul"), "rb").read()}
    rows, seen = [], {}
    for char, (game, parts) in CHARS.items():
        rom = roms[game]
        for part, src, lz in parts:
            data = content(src)
            hits = find_all(rom, data, lz)
            if not hits:
                raise SystemExit(f"{char} {part}: not in retail {game} ({src})")
            off, raw_len = hits[0]
            w = window(rom, off, raw_len)
            win = rom[off + w:off + w + 16]
            h = head(win)
            h = h - (1 << 64) if h >= 1 << 63 else h
            seen[(char, part)] = (data, lz)
            rows.append(f"    {char}_{part}(LookChar.{char}, LookPart.{part}, {str(lz is True).lower()}, {len(data)}, "
                        f"0x{zlib.crc32(data):08X}L, {w}, {h}L, 0x{pre(win):04X}{', smol = true' if lz == 'smol' else ''}),")
            print(f"{char} {part}: {len(hits)} in retail {game} @{off:#x}, raw {raw_len}, decoded {len(data)}, window +{w}")
    kt = """package com.pokedaisy.app.companion.data

// GENERATED by scripts/gen_player_look_sigs.py from the pinned pokefirered / pokeemerald
// graphics, found in the retail ROMs - do not hand-edit; rerun the script instead.

/** A player graphic [PlayerLook] looks for in a ROM - see the script for the fields. */
enum class LookBlob(
    val char: LookChar, val part: LookPart, override val lz: Boolean, override val size: Int, override val crc: Long,
    override val headOff: Int, override val head: Long, override val pre: Int,
    /** pokeemerald-expansion's smol compression (Heart and Soul's front pictures), not LZ77. */
    override val smol: Boolean = false,
) : LookFingerprint {
""" + "\n".join(rows) + "\n}\n" + """
/** Palette slots the game's own sprites share with the player (the surf blob, shadows, warp arrows - see the script). */
val LOOK_SHARED_COLOURS: Map<LookEngine, Set<Int>> = mapOf(
""" + "".join(f"    LookEngine.{e} to setOf({', '.join(str(i) for i in sorted(set().union(*map(colours_used, ps)) - {0}))}),\n" for e, ps in SHARED.items()) + ")\n"
    open(KOTLIN, "w").write(kt)
    print("wrote", KOTLIN)
    for path in sys.argv[1:]:
        rom = open(path, "rb").read()
        have = [f"{c}.{p}" for (c, p), (d, lz) in seen.items() if find_all(rom, d, lz)]
        chars = [c for c in CHARS if all(f"{c}.{p}" in have for p, _, _ in CHARS[c][1])]
        print(f"{os.path.basename(path)}: {len(have)} graphics, whole: {' '.join(chars) or '-'}")


if __name__ == "__main__":
    main()
