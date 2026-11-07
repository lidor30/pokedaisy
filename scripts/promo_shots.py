#!/usr/bin/env python3
"""The website's game pages' pictures (pokedaisy.com/g/<slug>/): the real app on a player's own ROM and save.

  python3 scripts/promo_shots.py unbound            # capture + render + stitch
  python3 scripts/promo_shots.py unbound capture    # only the game frames (mgba_dump, headless, in Docker) and
                                                    # the shots made from them alone (ORIGINAL, EXPAND, ZOOM)
  python3 scripts/promo_shots.py unbound frames     # those shots again from the frames already captured
  python3 scripts/promo_shots.py unbound stitch     # only Paparazzi's tiles -> website/public/g/<slug>/

capture boots the game (a disposable copy of the save) with the GAMES entry's boot script, turns EXPAND on at the
Thor's margin and writes `wideshot`s into app/build/field-hud/<slug>-{wide,zoom}.png. render runs GamePromoShotsTest
(Paparazzi), which draws the HUD / QUICK MENU over those frames and the companion's tabs from the GAMES entry's
fixture, in tiles of at most 1000 px. stitch puts the tiles back together as lossless WebP (plus og.png for link
previews). A new page: an entry here, its fixture and its test methods in GamePromoShotsTest, its entry in
website/src/lib/gamePages.js.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
FIELD_HUD = ROOT / "app/build/field-hud"
SNAPSHOTS = ROOT / "app/src/test/snapshots/images"
OUT = ROOT / "website/public/g"
IMAGE = "pokedaisy-capture"
MARGIN = 22  # the Thor's 16:9 top screen: 240 + 2 x 22 = 284 GBA px across

GAMES = {
    "unbound": {
        "rom": "~/Downloads/Game ROMs & Emulation/gba/Pokemon Unbound (v2.1.1.1).gba",
        "save": "~/Downloads/Pokemon - Unbound (v2.1.1.1).sav",
        "boot": "native-capture/boot/unbound_promo.txt",
        # ZOOM's window around the 240x160 frame (its top-left at 0, 0): 1.8x out, the screen's 16:9.
        "zoom": (-164, -80, 568, 320),
    },
    "soulgold": {
        "rom": "~/Downloads/Game ROMs & Emulation/gba/Pokemon SoulGold (v1.1.4).gba",
        "save": "~/Documents/RetroArch/saves/mGBA/Pokemon SoulGold (v1.1.4).srm",
        "boot": "native-capture/boot/soulgold_promo.txt",
        "zoom": (-164, -80, 568, 320),
        # Its clock tints the map (day / night): a midday frame, whatever time it is here.
        "hour": 12,
    },
    "firered": {
        "rom": "~/Downloads/Game ROMs & Emulation/gba/Pokemon - FireRed Version (USA, Europe) (Rev 1).gba",
        "save": "~/Documents/RetroArch/saves/mGBA/Pokemon - FireRed Version (USA, Europe) (Rev 1).srm",
        "boot": "native-capture/boot/firered_hack.txt",
        "zoom": (-164, -80, 568, 320),
        "hour": 12,
    },
    "leafgreen": {
        "rom": "~/Downloads/Game ROMs & Emulation/gba/Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba",
        "save": "~/Documents/RetroArch/saves/mGBA/Pokemon - LeafGreen Version (USA, Europe) (Rev 1).sav",
        "boot": "native-capture/boot/leafgreen.txt",
        "zoom": (-164, -80, 568, 320),
        "hour": 12,
    },
    "emerald": {
        "rom": "~/Downloads/Game ROMs & Emulation/gba/Pokemon - Emerald Version (USA, Europe).gba",
        "save": "~/Documents/RetroArch/saves/mGBA/Pokemon - Emerald Version (USA, Europe).srm",
        "boot": "native-capture/boot/default.txt",
        "zoom": (-164, -80, 568, 320),
        "hour": 12,
    },
    "lazarus": {
        "rom": "~/Downloads/Game ROMs & Emulation/gba/Pokemon Lazarus (v2.0).gba",
        "save": "~/Documents/RetroArch/saves/mGBA/Pokemon Lazarus (v2.0).srm",
        "boot": "native-capture/boot/lazarus_promo.txt",
        "zoom": (-164, -80, 568, 320),
        "hour": 12,
    },
    "emerald-rogue": {
        "rom": "~/Downloads/Game ROMs & Emulation/gba/Pokemon Emerald Rogue (v2.2.1-EX).gba",
        "save": "~/Documents/RetroArch/saves/mGBA/Pokemon Emerald Rogue (v2.2.1-EX).sav",
        "boot": "native-capture/boot/default.txt",
        "zoom": (-164, -80, 568, 320),
        "hour": 12,
    },
}


def capture(slug, game):
    work = Path(tempfile.mkdtemp(prefix="promo-"))
    shutil.copy(os.path.expanduser(game["rom"]), work / "rom.gba")
    shutil.copy(os.path.expanduser(game["save"]), work / "save.sav")
    zx, zy, zw, zh = game["zoom"]
    script = (ROOT / game["boot"]).read_text() + (
        f"wideon {MARGIN}\nwait 30\nwideshot /s/wide.ppm\n"
        f"wideview {zx} {zy} {zw} {zh}\nwait 20\nwideshot /s/zoom.ppm\n"
    )
    (work / "run.txt").write_text(script)
    # The game's clock (mGBA's RTC reads the container's local time): moved to [hour] by the time zone.
    tz = []
    if "hour" in game:
        # mGBA restores the clock from the 16 bytes past the save data, when a save has them: cut, it reads the time.
        data = (work / "save.sav").read_bytes()
        if len(data) % 1024 == 16:
            (work / "save.sav").write_bytes(data[:-16])
        off = (datetime.now(timezone.utc).hour - game["hour"]) % 24
        tz = ["-e", f"TZ=UTC{off - 24 if off > 12 else off:+d}"]
    # Built from the repo root: wideon needs the app's pk_wide.c (native-capture/README.md).
    build = "gcc -O2 -Wall -o /s/mgba_dump mgba_dump.c -I/usr/include -L/usr/lib/aarch64-linux-gnu -lmgba -lm"
    subprocess.run(
        ["docker", "run", "--rm", *tz, "-v", f"{ROOT}:/repo", "-v", f"{work}:/s", "-w", "/repo/native-capture", IMAGE,
         "-c", f"{build} && /s/mgba_dump /s/rom.gba /s/save.sav < /s/run.txt > /s/log.txt 2>&1"],
        check=True,
    )
    FIELD_HUD.mkdir(parents=True, exist_ok=True)
    for name in ("wide", "zoom"):
        Image.open(work / f"{name}.ppm").save(FIELD_HUD / f"{slug}-{name}.png")
        print(f"{FIELD_HUD / f'{slug}-{name}.png'}")
    shutil.rmtree(work)
    frames(slug)


def screen(img, w, h):
    """[img] on a [w] x [h] screen at the largest scale that fits, centred on black: sharp pixels (each one 4x first,
    then smoothed down, like the app's filtered scale) - the Thor's top screen shows the GBA at 6.75x."""
    k = min(w / img.width, h / img.height)
    big = img.resize((img.width * 4, img.height * 4), Image.NEAREST)
    shown = big.resize((round(img.width * k), round(img.height * k)), Image.LANCZOS)
    out = Image.new("RGB", (w, h))
    out.paste(shown, ((w - shown.width) // 2, (h - shown.height) // 2))
    return out


def frames(slug):
    """The top screen without the app's own drawing on it: ORIGINAL (the GBA's 240x160 between bars), EXPAND and
    ZOOM straight from the captured frames - no app build needed."""
    out = OUT / slug
    out.mkdir(parents=True, exist_ok=True)
    wide = Image.open(FIELD_HUD / f"{slug}-wide.png").convert("RGB")
    shots = {
        "top-original": screen(wide.crop((MARGIN, 0, MARGIN + 240, 160)), 1920, 1080),
        "top-expand": screen(wide, 1920, 1080),
        "top-zoom": screen(Image.open(FIELD_HUD / f"{slug}-zoom.png").convert("RGB"), 1920, 1080),
    }
    for name, img in shots.items():
        save(img, out / f"{name}.webp")
    og(shots["top-expand"], out)


def save(img, path):
    img.save(path, "WEBP", quality=92, method=6)
    print(f"{path}  {path.stat().st_size // 1024} KB")


def og(img, out):
    """Link previews (Reddit, Discord): 1200x630, cut from a 16:9 top screen."""
    img.resize((1200, 675), Image.LANCZOS).crop((0, 22, 1200, 652)).save(out / "og.png", optimize=True)


def render(slug):
    subprocess.run(
        ["./gradlew", ":app:recordPaparazziDebug", "--tests", f"*GamePromoShotsTest.{slug}_*", "-q"], cwd=ROOT, check=True,
    )


def stitch(slug):
    # Paparazzi's names: <package>_<Class>_<method>_<col>_<row>.png
    pattern = re.compile(rf"GamePromoShotsTest_{slug}_(\w+?)_(\d+)_(\d+)\.png$")
    shots = {}
    for f in SNAPSHOTS.iterdir():
        m = pattern.search(f.name)
        if m:
            shots.setdefault(m.group(1), {})[(int(m.group(2)), int(m.group(3)))] = f
    if not shots:
        sys.exit(f"no GamePromoShotsTest tiles for {slug} in {SNAPSHOTS}")
    out = OUT / slug
    out.mkdir(parents=True, exist_ok=True)
    for name, tiles in sorted(shots.items()):
        # The test's sizes: the top screen 1920x1080, the companion 1240x1080, in 2 x 2 tiles.
        w, h = (1920, 1080) if name.startswith("top_") else (1240, 1080)
        cols, rows = max(c for c, _ in tiles) + 1, max(r for _, r in tiles) + 1
        tw, th = -(-w // cols), -(-h // rows)
        img = Image.new("RGB", (w, h))
        for (c, r), f in tiles.items():
            img.paste(Image.open(f).convert("RGB").crop((0, 0, tw, th)), (c * tw, r * th))
        path = out / f"{name.replace('_', '-')}.webp"
        img.save(path, "WEBP", lossless=True, quality=100, method=6)
        print(f"{path}  {path.stat().st_size // 1024} KB")
        if name == "top_hud":
            og(img, out)  # the HUD over EXPAND says more than EXPAND alone


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in GAMES:
        sys.exit(f"usage: {sys.argv[0]} <{'|'.join(GAMES)}> [capture|frames|render|stitch]")
    slug, step = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "all")
    if step in ("all", "capture"):
        capture(slug, GAMES[slug])
    if step == "frames":
        frames(slug)
    if step in ("all", "render"):
        render(slug)
    if step in ("all", "stitch"):
        stitch(slug)


if __name__ == "__main__":
    main()
