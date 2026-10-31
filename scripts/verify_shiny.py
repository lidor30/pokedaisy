#!/usr/bin/env python3
"""SHINY ODDS on every retail version, headlessly - no device.

    scripts/verify_shiny.py [--only KEY[,KEY]]

Needs build/ports/{ports.json,english.json} (scripts/port_retail.py) for the other languages' ROM paths and script
functions, and the saves in scripts/host_roms.conf (as scripts/verify_ports.py). For each retail FireRed, LeafGreen,
Ruby, Sapphire and Emerald (every language and revision) it boots the game's save in the pokedaisy-capture image and:

  made    pokes `setwildbattle PIKACHU 5; end` into EWRAM, calls ScriptContext_SetupScript on it and dumps the RAM 30
          frames later (the foe made, no battle yet), with a savestate
  (JVM)   ShinyVerifyTest: where the game put the foe, ShinyOdds.Roller run there with the config the Poller picks
          -> <key>/pokes.txt
  shiny   from the state: the pokes, then `dowildbattle; end`, to the action menu -> shot + dump
  plain   the same without the pokes
  (JVM)   ShinyVerifyTest: the shiny battle's foe reads shiny in gBattleMons, the plain one's doesn't, the ROM gives
          PIKACHU a shiny icon palette

and here: the two battles' frames differ (the game drew the foe in other colours). Writes build/shiny/report.md and
build/shiny/sha1s.txt, the masked hashes (GPIO bytes zeroed, as the Poller hashes) of the versions that pass, for
ShinyOdds.RETAIL_SHA1S.
"""
import glob
import hashlib
import json
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
PORTS = os.path.join(ROOT, "build", "ports")
OUT = os.path.join(ROOT, "build", "shiny")
CAPTURE = os.path.join(ROOT, "native-capture")
IMAGE = "pokedaisy-capture"
MULTI = os.path.expanduser("~/Downloads/Game ROMs & Emulation/Pokemon Multi Language")

SAVES = {"BPR": "firered_vanilla", "BPG": "leafgreen_rev1", "AXV": "ruby_rev1", "AXP": "sapphire_rev1", "BPE": "emerald_vanilla"}
OWN_SAVES = {"BPRJ": "firered_ja", "BPGJ": "leafgreen_ja"}
BOOTS = {"BPR": "default.txt", "BPG": "leafgreen.txt", "AXV": "ruby_sapphire.txt", "AXP": "ruby_sapphire.txt", "BPE": "default.txt"}
AFTER_BOOT = {"BPR": "press B\nwait 60\n" * 4 + "wait 120\n", "BPG": "press B\nwait 60\n" * 4 + "wait 120\n",
              "BPE": "wait 120\n" + "press B\nwait 20\n" * 3}
SCRIPT_RAM = 0x0203FF00
MAKE = [0xB6, 0x19, 0x00, 0x05, 0x00, 0x00, 0x02, 0x00]   # setwildbattle SPECIES_PIKACHU, 5, ITEM_NONE; end
FIGHT = [0xB7, 0x02, 0x00, 0x00]                          # dowildbattle; end
PROBES = [8, 15, 16, 31, 32, 63, 64, 127, 128, 255, 256]  # ShinyVerifyTest.PROBES: shiny values to watch in battle
WHOLE = [0xB6, 0x19, 0x00, 0x05, 0x00, 0x00, 0xB7, 0x02]  # setwildbattle; dowildbattle; end: CFRU's setwildbattle reads
                                                          # its arguments its own way and only battles in one script

# The versions the port data doesn't list: English Ruby / Sapphire rev 2 (rev 1's addresses) and the Emeralds
# (ScriptContext_SetupScript as each one's *_battle fixture README has it).
EXTRA = [
    ("AXVE2", "AXVE", 2, "Pokemon - Ruby Version (USA, Europe) (Rev 2)", "AXVE1"),
    ("AXPE2", "AXPE", 2, "Pokemon - Sapphire Version (USA, Europe) (Rev 2)", "AXPE1"),
    ("BPEE0", "BPEE", 0, "Pokemon - Emerald Version (USA, Europe)", 0x08098EF9),
    ("BPES0", "BPES", 0, "Pokemon - Edicion Esmeralda (Spain)", 0x08098F0C),
    ("BPED0", "BPED", 0, "Pokemon - Smaragd-Edition (Germany)", 0x08098F14),
    ("BPEF0", "BPEF", 0, "Pokemon - Version Emeraude (France)", 0x08098F08),
    ("BPEI0", "BPEI", 0, "Pokemon - Versione Smeraldo (Italy)", 0x08098F0C),
    ("BPEJ0", "BPEJ", 0, "Pocket Monsters - Emerald (Japan)", 0x08098880),
]


def conf(key):
    for line in open(os.path.join(HERE, "host_roms.conf"), encoding="utf-8"):
        f = line.rstrip("\n").split("|")
        if not line.startswith("#") and f[0] == key:
            return os.path.expanduser(f[3])
    sys.exit(f"{key} missing from host_roms.conf")


def multi_rom(name):
    found = glob.glob(os.path.join(MULTI, glob.escape(name), "*.gba"))
    return found[0] if found else None


_by_sha1 = None


def find_rom(path, sha1):
    """[path], or (its folder renamed since port_retail.py ran) the ROM under MULTI with that SHA-1."""
    global _by_sha1
    if os.path.isfile(path) or not sha1:
        return path
    if _by_sha1 is None:
        _by_sha1 = {hashlib.sha1(open(f, "rb").read()).hexdigest(): f
                    for f in glob.glob(os.path.join(glob.escape(MULTI), "**", "*.gba"), recursive=True)}
    return _by_sha1.get(sha1, path)


def masked_sha1(path):
    data = bytearray(open(path, "rb").read())
    data[0xC4:0xCA] = b"\0" * 6
    return hashlib.sha1(data).hexdigest()


def conf_rom(key):
    for line in open(os.path.join(HERE, "host_roms.conf"), encoding="utf-8"):
        f = line.rstrip("\n").split("|")
        if not line.startswith("#") and f[0] == key:
            return os.path.expanduser(f[2])
    sys.exit(f"{key} missing from host_roms.conf")


def boot_text(boot, code, cut=False):
    """A boot script (native-capture/boot/), or [boot] itself when it holds commands; [cut]: only its lines before the
    first `poke` (the battle boot scripts start their own battle after reaching the field)."""
    text = boot if "\n" in boot else open(os.path.join(CAPTURE, "boot", boot)).read()
    lines = text.rstrip("\n").split("\n")
    if cut:
        lines = lines[:next((i for i, l in enumerate(lines) if l.startswith("poke")), len(lines))]
    return "\n".join(lines) + "\n" + AFTER_BOOT.get(code[:3], "")


GBA = os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba")
ORANGE_ISLANDS_BOOT = "wait 400\npress START\n" + "wait 90\npress A\n" * 4 + "wait 90\npress B\nwait 200\n"
# The hacks on vanilla's encrypted struct with the original species ids: (key, game code, revision, host_roms.conf key for
# ROM + save, or (ROM, save) paths, boot script (cut at its battle) or commands, ScriptContext_SetupScript, script RAM).
# Script functions and recipes from each one's *_battle fixture README.
# warp 3, 69, -1, (32, 8); end - through rev 0's ScriptContext_SetupScript, then left and right in the grass.
UNBOUND_GRASS = ([f"poke8 0x{0x0203FF00 + i:X} 0x{b:02X}" for i, b in enumerate([0x39, 3, 69, 0xFF, 32, 0, 8, 0, 0x02])]
                 + ["call 0x08069AE5 0x0203FF00", "wait 150"] + ["hold RIGHT 17", "hold LEFT 17"] * 40 + ["wait 30"])

# pokeemerald-expansion's setwildbattle: species, level, item, then a second mon's (for a double), dowildbattle, end.
EXPANSION_WHOLE = [0xB6, 0x19, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xB7, 0x02]
MARIOMON_WHOLE = [0xB6, 0x93, 0x03] + EXPANSION_WHOLE[3:]   # its species 915 (its PIKACHU slot's name is blank)

# SoulGold's: two more u16 fields (12 bytes after the level's, zero = nop padding where it reads fewer).
SOULGOLD_WHOLE = [0xB6, 0x19, 0x00, 0x05] + [0] * 12 + [0xB7, 0x02]
QUETZAL_WHOLE = [0xB6, 0x10, 0x00, 0x03] + [0] * 7 + [0xB7, 0x02]   # SPECIES_PIDGEY, 3
ROWE_WHOLE = [0xB6, 0x09, 0x01, 0x03] + [0] * 7 + [0xB7, 0x02]      # WURMPLE, 3: its branch kept
ELITE_REDUX_WHOLE = [0xB6, 0x07, 0x01, 0x05] + [0] * 9 + [0xB7, 0x02]   # six u16s: ZIGZAGOON 263, level 5, then zeros
SG_BOOT = open(os.path.join(CAPTURE, "boot", "rhh_splash.txt")).read() + "press B\nwait 30\n" * 3
ROWE_BOOT = open(os.path.join(CAPTURE, "boot", "rhh_splash.txt")).read() + "wait 120\n"
TEAM_ROCKET = (GBA + "/Pokemon Team Rocket Dragonsden Version.gba",
               os.path.expanduser("~/Downloads/Game ROMs & Emulation/rom saves from discord/mGBA Romhack Saves/Pokemon - Team Rocket Edition.srm"))
QZ_EN_SAV = os.path.expanduser("~/Documents/RetroArch/saves/mGBA/Pokemon Quetzal (Alpha 9.0) (English).sav")
QZ_ES_SAV = os.path.expanduser("~/Documents/RetroArch/saves/mGBA/Pokemon Quetzal (Alpha 9.0) (Spanish).sav")
LAZARUS_BOOT = open(os.path.join(CAPTURE, "boot", "default.txt")).read() + "press B\nwait 20\n" * 3
IMPERIUM_GRASS = ["hold DOWN 40"] + ["hold LEFT 17", "hold RIGHT 17"] * 30 + ["wait 30"]

HACKS = [
    ("KAIZO", "BPEE", 0, "emerald_kaizo_battle", ("emerald_kaizo_battle.txt", True), 0x08098EF9, 0x0203FF00),
    ("LEGACY", "BPEE", 0, "emerald_legacy_battle", ("emerald_legacy_battle.txt", True), 0x0809A295, 0x0203FF00),
    ("RE_V225", "BPEE", 0, "recharged_emerald_v225_battle", ("recharged_emerald_battle_v225.txt", True), 0x081AF031, 0x0203F800),
    ("RE_V228", "BPEE", 0, "recharged_emerald_battle", ("recharged_emerald_battle_v228.txt", True), 0x081B09D5, 0x0203F800),
    ("RE_REBALANCED", "BPEE", 0, "recharged_emerald_rebalanced_battle", ("recharged_emerald_battle_rebalanced.txt", True), 0x081B0AF1, 0x0203F800),
    ("GLAZED", "BPEE", 0, "glazed", ("rhh_splash.txt", False), 0x08098EF9, 0x0203FF00),
    ("ULTRA_VIOLET", "BPRE", 0, "ultra_violet", ("default.txt", False), 0x08069AE5, 0x0203FF00),
    ("ORANGE_ISLANDS", "BPRE", 0, "orange_islands", (ORANGE_ISLANDS_BOOT, False), 0x08069AE5, 0x0203FF00),
    ("ROCKET_EDITION", "BPRE", 0, "rocket_edition", ("firered_hack.txt", False), 0x08069AE5, 0x0203FF00),
    ("ASH_GRAY", "BPRE", 0, "ash_gray", ("firered_hack.txt", False), 0x08069AE5, 0x0203FF00),
    ("ASH_GRAY_FORK", "BPRE", 0, "ash_gray_fork", ("firered_hack.txt", False), 0x08069AE5, 0x0203FF00),
    ("GHOST_GREY_104", "BPRE", 0, "ghost_grey", ("ghost_grey.txt", False), 0x08069AE5, 0x0203FF00),
    ("GHOST_GREY_102", "BPRE", 0, (GBA + "/Pokemon Ghost Grey (v1.0.2).gba", None), ("ghost_grey.txt", False), 0x08069AE5, 0x0203FF00),
    ("GHOST_GREY_101", "BPRE", 0, "ghost_grey_v101", ("ghost_grey.txt", False), 0x08069AE5, 0x0203FF00),
    ("RY_V180", "BPRE", 0, "recharged_yellow_v180", ("default.txt", False), 0x081A3541, 0x0203FF00),
    ("RY_V190", "BPRE", 0, "recharged_yellow_v190", ("default.txt", False), 0x081A4551, 0x0203FF00),
    ("RY_V194", "BPRE", 0, "recharged_yellow_v194", ("default.txt", False), 0x081A56DD, 0x0203FF00),
    ("RY_V197", "BPRE", 0, "recharged_yellow", ("default.txt", False), 0x081A5899, 0x0203FF00),
    ("CELIA", "BPRE", 0, "celia", ("default.txt", False), 0x0807676D, 0x0203FF00),
    # CFRU (plaintext boxes): FireRed rev 0's ScriptContext_SetupScript, kept by all of them.
    # The same, and its save stands on snow with no encounters: warped into map 3.69's grass first (found from the
    # layout's metatile behaviours), then paced there.
    ("UNBOUND", "BPRE", 0, "unbound", ("default.txt", False), ("walk", None, 0x0202402C, UNBOUND_GRASS), 0x0203FF00),
    ("UNBOUND_FR", "BPRE", 0, "unbound_fr", ("default.txt", False), ("walk", None, 0x0202402C, UNBOUND_GRASS), 0x0203FF00),
    ("GAIA", "BPRE", 0, "gaia", ("default.txt", False), 0x08069AE5, 0x0203FF00),
    ("RADICAL_RED", "BPRE", 0, "radical_red", ("default.txt", False), 0x08069AE5, 0x0203FF00),
    ("ODYSSEY", "BPRE", 0, "odyssey", ("default.txt", False), 0x08069AE5, 0x0203FF00),
    # CFRU's scripted setwildbattle makes an empty foe here: a walked encounter in Route 17's grass instead (the
    # amethyst_battle fixture's walk, cut before it presses A on the battle's text).
    ("AMETHYST", "BPRE", 0, "amethyst", ("amethyst_battle.txt", False), ("walk", "wait 400", 0x0202402C), 0x0203FF00),
    ("AMETHYST_V141", "BPRE", 0, "amethyst_v141", ("amethyst_battle.txt", False), ("walk", "wait 400", 0x0202402C), 0x0203FF00),
    ("SWSH", "BPRE", 0, "sword_shield_up", ("default.txt", False), 0x08069AE5, 0x0203FF00),
    ("SWSH_CASUAL", "BPRE", 0, "sword_shield_up_casual", ("default.txt", False), 0x08069AE5, 0x0203FF00),
    ("SWSH_PERFORMANCE", "BPRE", 0, "sword_shield_up_performance", ("default.txt", False), 0x08069AE5, 0x0203FF00),
    ("SWSH_CASUAL_PERFORMANCE", "BPRE", 0, "sword_shield_up_casual_performance", ("default.txt", False), 0x08069AE5, 0x0203FF00),
    # pokeemerald-expansion (National Dex ids, the vanilla box): each fixture's recipe.
    ("LAZARUS", "BPEE", 0, "lazarus", (LAZARUS_BOOT, False), 0x0820B2B0, 0x0203FF00, EXPANSION_WHOLE),
    ("SEAGLASS", "BPEE", 0, "emerald_seaglass", (LAZARUS_BOOT, False), 0x081EF598, 0x0203FF00, EXPANSION_WHOLE),
    ("TMT2", "BPEE", 0, "tmt2", ("tmt2_battle.txt", True), 0x081DC369, 0x0203FF00, EXPANSION_WHOLE),
    ("MARIOMON_V1_5_2", "BPEE", 0, "mariomon_v1_5_2", ("mariomon_battle.txt", True), 0x080BEE71, 0x0203FF00, MARIOMON_WHOLE),
    ("MARIOMON_V1_5_1", "BPEE", 0, "mariomon_v1_5_1", ("mariomon_battle.txt", True), 0x080BED91, 0x0203FF00, MARIOMON_WHOLE),
    ("MARIOMON_V1_3", "BPEE", 0, "mariomon_v1_3", ("mariomon_battle.txt", True), 0x080BCF15, 0x0203FF00, MARIOMON_WHOLE),
    ("MARIOMON_V1_2_2", "BPEE", 0, "mariomon_v1_2_2", ("mariomon_battle.txt", True), 0x080BCF95, 0x0203FF00, MARIOMON_WHOLE),
    ("MARIOMON_V1_2_1", "BPEE", 0, "mariomon_v1_2_1", ("mariomon_battle.txt", True), 0x080BCF95, 0x0203FF00, MARIOMON_WHOLE),
    # Their own boxes (plaintext, or Team Rocket's flags a byte early), National Dex ids too.
    ("SOULGOLD", "BPEE", 0, "soulgold", (SG_BOOT, False), 0x0823647C, 0x0203FF00, SOULGOLD_WHOLE),
    ("SOULGOLD_V12", "BPEE", 0, "soulgold_v12", (SG_BOOT, False), 0x08236D5C, 0x0203FF00, SOULGOLD_WHOLE),
    ("SOULGOLD_V12B", "BPEE", 0, "soulgold_v12b", (SG_BOOT, False), 0x08236CC4, 0x0203FF00, SOULGOLD_WHOLE),
    ("SOULGOLD_V12C", "BPEE", 0, "soulgold_v12c", (SG_BOOT, False), 0x08236DBC, 0x0203FF00, SOULGOLD_WHOLE),
    ("SOULGOLD_V121", "BPEE", 0, "soulgold_v121", (SG_BOOT, False), 0x0823BC04, 0x0203FF00, SOULGOLD_WHOLE),
    ("SOULGOLD_V121B", "BPEE", 0, "soulgold_v121b", (SG_BOOT, False), 0x0823BC04, 0x0203FF00, SOULGOLD_WHOLE),
    ("QUETZAL", "BPEE", 0, "quetzal", ("rhh_splash.txt", False), 0x080DB9CC, 0x0203FF00, QUETZAL_WHOLE),
    ("QUETZAL_ES", "BPEE", 0, (GBA + "/Pokemon Quetzal (Alpha 9.0) (Spanish).gba", QZ_ES_SAV), ("rhh_splash.txt", False), 0x080DB9E5, 0x0203FF00, QUETZAL_WHOLE),
    ("QUETZAL_V1", "BPEE", 0, (GBA + "/Pokemon Quetzal (Alpha 9v1) (English).gba", QZ_EN_SAV), ("rhh_splash.txt", False), 0x080DE340, 0x0203FF00, QUETZAL_WHOLE),
    ("QUETZAL_ES_V1", "BPEE", 0, (GBA + "/Pokemon Quetzal (Alpha 9v1) (Spanish).gba", QZ_ES_SAV), ("rhh_splash.txt", False), 0x080DE358, 0x0203FF00, QUETZAL_WHOLE),
    ("QUETZAL_084", "BPEE", 0, (GBA + "/Pokemon Quetzal (Alpha 0.8.4) (English).gba", QZ_ES_SAV), ("rhh_splash.txt", False), 0x080D34C5, 0x0203FF00, QUETZAL_WHOLE),
    ("QUETZAL_ES_084", "BPEE", 0, (GBA + "/Pokemon Quetzal (Alpha 0.8.4) (Spanish).gba", QZ_ES_SAV), ("rhh_splash.txt", False), 0x080D34E1, 0x0203FF00, QUETZAL_WHOLE),
    ("QUETZAL_MOEMON_084", "BPEE", 0, (GBA + "/Moemon Quetzal (Alpha 0.8.4 Hotfix) (English).gba", QZ_ES_SAV), ("rhh_splash.txt", False), 0x080D35BD, 0x0203FF00, QUETZAL_WHOLE),
    ("QUETZAL_MOEMON_ES_084", "BPEE", 0, (GBA + "/Moemon Quetzal (Alpha 0.8.4 Hotfix) (Spanish).gba", QZ_ES_SAV), ("rhh_splash.txt", False), 0x080D35D9, 0x0203FF00, QUETZAL_WHOLE),
    ("ROWE", "BPEE", 0, "rowe", (ROWE_BOOT, False), 0x080D94E5, 0x0203F300, ROWE_WHOLE),
    ("ELITE_REDUX", "BPEE", 0, "elite_redux", ("elite_redux.txt", False), 0x081F3985, 0x0203FF00, ELITE_REDUX_WHOLE),
    ("ELITE_REDUX_V2653", "BPEE", 0, "elite_redux_v2653", ("elite_redux.txt", False), 0x081F6155, 0x0203FF00, ELITE_REDUX_WHOLE),
    ("TEAM_ROCKET", "BPEE", 0, TEAM_ROCKET, ("team_rocket_battle.txt", True), 0x080CEDC5, 0x0203F800, EXPANSION_WHOLE),
    # No ScriptContext_SetupScript found: walked encounters (imperium_battle's Route 101 grass).
    ("IMPERIUM", "BPEE", 0, "imperium", ("rhh_splash.txt", False), ("walk", None, 0x02037850, IMPERIUM_GRASS), 0x0203FF00),
    ("GS_CHRONICLES", "BPRE", 0, "gs_chronicles", ("gs_chronicles.txt", False), 0x08069AE5, 0x0203F400),
]


def runs():
    """Every version to check: dicts of key, code, rev, path (ROM), save (a path), boot (commands), fn, ram, hack."""
    ports = json.load(open(os.path.join(PORTS, "ports.json"), encoding="utf-8"))
    refs = json.load(open(os.path.join(PORTS, "english.json"), encoding="utf-8"))
    out = []
    def add(key, code, rev, path, save, boot, fn, ram=SCRIPT_RAM, hack=False, whole=False):
        out.append(dict(key=key, code=code, rev=rev, path=path, save=save, boot=boot, fn=fn, ram=ram, hack=hack, whole=whole))
    for k, r in refs.items():
        add(k, k[:4], int(k[4]), r["path"], conf(SAVES[k[:3]]), boot_text(BOOTS[k[:3]], k[:4]), r["anchors"]["script"])
    for p in ports:
        if p["key"] in refs:
            continue
        add(p["key"], p["code"], p["rev"], find_rom(p["path"], p.get("sha1")), conf(OWN_SAVES.get(p["code"], SAVES[p["code"][:3]])),
            boot_text(BOOTS[p["code"][:3]], p["code"]), p["anchors"]["script"][0])
    for key, code, rev, name, script in EXTRA:
        path = multi_rom(name)
        if path:
            fn = refs[script]["anchors"]["script"] if isinstance(script, str) else script
            add(key, code, rev, path, conf(SAVES[code[:3]]), boot_text(BOOTS[code[:3]], code), fn)
    for key, code, rev, where, (boot, cut), fn, ram, *flags in HACKS:
        rom, save = (conf_rom(where), conf(where)) if isinstance(where, str) else where
        if save is None:
            save = conf("ghost_grey")
        if os.path.isfile(rom):
            if isinstance(fn, tuple):   # ("walk", the boot line it stops before, gEnemyParty[, lines after the boot])
                if fn[1]:
                    text = open(os.path.join(CAPTURE, "boot", boot)).read()
                    text = text[:text.index("\n" + fn[1])]
                else:
                    text = boot_text(boot, code, cut).rstrip("\n") + "\n" + "\n".join(fn[3])
                add(key, code, rev, rom, save, text, 0, ram, hack=True, whole=True)
                out[-1]["walk"] = fn[2]
            else:
                add(key, code, rev, rom, save, boot_text(boot, code, cut), fn, ram, hack=True, whole=flags[0] if flags else False)
    return out


def per_frame(text, peek):
    """[text]'s waits / presses / holds one frame at a time, [peek] read after each frame: how a walked encounter's
    frame is found (the same commands, cut at that frame, then take the state there)."""
    out = []
    step = lambda: out.extend(["wait 1", f"peek32 0x{peek:08X}"])
    for line in text.split("\n"):
        w = line.split()
        if not w or w[0].startswith("#"):
            continue
        if w[0] == "wait":
            for _ in range(int(w[1])):
                step()
        elif w[0] == "press":
            out.append(f"keys {w[1]}"); step(); out.append("keys NONE"); step()
        elif w[0] == "hold":
            out.append(f"keys {w[1]}")
            for _ in range(int(w[2])):
                step()
            out.append("keys NONE"); step()
        else:
            out.append(line)
    return out


def frames_in(lines, n):
    """[lines] up to the end of frame [n] (counting `wait 1`s)."""
    out, seen = [], 0
    for l in lines:
        out.append(l)
        if l == "wait 1":
            seen += 1
            if seen == n:
                break
    return out


def pokes(addr, data):
    return [f"poke8 0x{addr + i:X} 0x{b:02X}" for i, b in enumerate(data)]


def docker(sh):
    script = os.path.join(OUT, "run.sh")
    with open(script, "w") as f:
        f.write("\n".join(["set -e", "cd /work"] + sh) + "\n")
    subprocess.run(["docker", "run", "--rm", "-v", f"{ROOT}:/repo", "-v", f"{OUT}:/work", "-w", "/repo/native-capture", IMAGE, "-c",
                    "test -x /work/mgba_dump || gcc -O2 -Wall -o /work/mgba_dump mgba_dump.c -I/usr/include "
                    "-L/usr/lib/aarch64-linux-gnu -lmgba -lm; bash /work/run.sh"], check=True)


def jvm():
    subprocess.run([os.path.join(ROOT, "gradlew"), "-p", ROOT, ":app:testDebugUnitTest", "--tests", "*ShinyVerifyTest", "--rerun", "-q"],
                   check=True)


def ppm(path):
    d = open(path, "rb").read().split(b"\n", 3)
    return d[3]


def main():
    global PROBES
    todo = runs()
    if "--probes" in sys.argv:   # other shiny values to watch (ShinyVerifyTest reads them from probes.txt)
        PROBES = [int(v) for v in sys.argv[sys.argv.index("--probes") + 1].split(",")]
    if "--only" in sys.argv:
        only = set(sys.argv[sys.argv.index("--only") + 1].split(","))
        todo = [r for r in todo if r["key"] in only]
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(os.path.join(OUT, "roms"))
    open(os.path.join(OUT, "probes.txt"), "w").write(",".join(map(str, PROBES)))
    with open(os.path.join(OUT, "runs.tsv"), "w", encoding="utf-8") as f:
        for r in todo:
            f.write(f"{r['key']}\t{r['code']}\t{r['rev']}\t{r['path']}\n")

    # Walked encounters: one pass reading the foe slot every frame finds the frame the encounter makes the foe.
    walks = [r for r in todo if r.get("walk")]
    if walks:
        find = []
        for r in walks:
            d = os.path.join(OUT, r["key"])
            os.makedirs(d, exist_ok=True)
            shutil.copy(r["path"], os.path.join(OUT, "roms", r["key"] + ".gba"))
            shutil.copy(r["save"], os.path.join(d, "find.sav"))
            r["frames"] = per_frame(r["boot"], r["walk"])
            open(os.path.join(d, "find.txt"), "w").write("\n".join(r["frames"]) + "\n")
            find.append(f"./mgba_dump roms/{r['key']}.gba {r['key']}/find.sav < {r['key']}/find.txt 2>&1 | grep -iE '^[0-9a-f]{{8}}: ' > {r['key']}/find.log; echo find {r['key']}")
        docker(find)
        for r in walks:
            vals = [l.split(": ")[1].strip() for l in open(os.path.join(OUT, r["key"], "find.log"))]
            first = next((i for i, v in enumerate(vals) if v != vals[0] and v != "00000000"), None)
            r["cut"] = None if first is None else first + 1 + 3   # 3 frames after the foe is there: the device's window
            print(f"{r['key']}: foe made at frame {None if first is None else first + 1}")

    made = []
    for r in todo:
        key = r["key"]
        d = os.path.join(OUT, key)
        os.makedirs(os.path.join(d, "made"))
        shutil.copy(r["path"], os.path.join(OUT, "roms", key + ".gba"))
        shutil.copy(r["save"], os.path.join(d, "game.sav"))
        if r.get("walk"):
            if not r.get("cut"):
                continue
            lines = frames_in(r["frames"], r["cut"]) + ["keys NONE", f"savestate /work/{key}/made.ss", f"dump /work/{key}/made"]
        else:
            # [whole]: the battle starts in the same script, the state is taken in its transition (the rewrite's real window).
            lines = r["boot"].rstrip("\n").split("\n") + pokes(r["ram"], (r["whole"] if isinstance(r["whole"], list) else WHOLE) if r["whole"] else MAKE)
            lines += [f"call 0x{r['fn']:08X} 0x{r['ram']:08X}", "wait 30", f"savestate /work/{key}/made.ss", f"dump /work/{key}/made"]
        open(os.path.join(d, "made.txt"), "w").write("\n".join(lines) + "\n")
        made.append(f"./mgba_dump roms/{key}.gba {key}/game.sav < {key}/made.txt > {key}/made.log 2>&1; echo made {key}")
    docker(made)
    jvm()

    battles = []
    probe = "--probe" in sys.argv
    for r in todo:
        key, fn, ram = r["key"], r["fn"], r["ram"]
        d = os.path.join(OUT, key)
        def lines_of(name):
            f = os.path.join(d, name)
            return [l for l in open(f).read().split("\n") if l] if os.path.exists(f) else []
        runs_ = [("shiny", lines_of("pokes.txt")), ("plain", [])]
        if probe:
            runs_ += [(f"probe{v}", lines_of(f"probe{v}.txt")) for v in PROBES if lines_of(f"probe{v}.txt")]
        for run, extra in runs_:
            os.makedirs(os.path.join(d, run), exist_ok=True)
            lines = [f"loadstate /work/{key}/made.ss"] + extra
            if not r["whole"]:
                lines += pokes(ram, FIGHT) + [f"call 0x{fn:08X} 0x{ram:08X}"]
            lines += ["wait 1300", "hold A 4", "wait 400",
                      f"shot /work/{key}/{run}.ppm", f"dump /work/{key}/{run}", f"vdump /work/{key}/{run}"]
            open(os.path.join(d, run + ".txt"), "w").write("\n".join(lines) + "\n")
            battles.append(f"./mgba_dump roms/{key}.gba {key}/game.sav < {key}/{run}.txt > {key}/{run}.log 2>&1; echo {run} {key}")
    docker(battles)
    jvm()
    shutil.rmtree(os.path.join(OUT, "roms"), ignore_errors=True)

    jvm_rows = {}
    for line in open(os.path.join(OUT, "jvm.tsv"), encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        jvm_rows[f[0]] = f[1:]
    rows, passed = [], []
    for r in todo:
        key, path = r["key"], r["path"]
        j = jvm_rows.get(key, ["?"] * 6)
        d = os.path.join(OUT, key)
        try:
            a, b = ppm(os.path.join(d, "shiny.ppm")), ppm(os.path.join(d, "plain.ppm"))
            diff = sum(1 for i in range(0, min(len(a), len(b)), 3) if a[i:i + 3] != b[i:i + 3])
        except OSError:
            diff = -1
        target, found = j[0], j[1] if len(j) > 1 else "-"
        # What the game drew (ShinyVerifyTest: the foe's colours in the OBJ palettes), and its own shiny check: the
        # smallest probe value it drew in the normal colours (every smaller one shiny).
        drawn = j[5] if len(j) > 5 else "-"
        rule = "-"
        if probe and len(j) > 7:
            seen = [(int(x.split(":")[0]), x.split(":")[2]) for x in j[7].split(",") if x.count(":") == 2]
            below = next((v for v, d_ in seen if d_ == "plain"), None)
            if seen and all(d_ == "shiny" for _, d_ in seen):
                rule = "above 256"
            elif below is not None and all(d_ == "shiny" for v, d_ in seen if v < below) and all(d_ == "plain" for v, d_ in seen if v > below):
                rule = f"below {below}"
            else:
                rule = "unclear: " + ",".join(f"{v}{d_[:1]}" for v, d_ in seen)
        # A hack without dex tables has no shiny icons (it keeps the normal ones): not a failure there.
        ok = (len(j) >= 7 and target in found.split(",") and j[2] == "rewritten" and j[3] == "shiny" and j[4] == "plain"
              and drawn == "shiny/plain" and (j[6] == "icon" or r["hack"]))
        rows.append(f"| {key} | {target} | {found} | {' | '.join(j[2:5])} | {drawn} | {j[6] if len(j) > 6 else '-'} | {diff} | {rule} | {'yes' if ok else '**NO**'} |")
        if ok:
            passed.append((key, masked_sha1(path), rule))
    report = ["| Version | gEnemyParty | Foe found at | Rewrite | Shiny battle | Plain battle | Drawn (shiny/plain) | Icon | Pixels differ | Shiny rule | Pass |",
              "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"] + rows
    open(os.path.join(OUT, "report.md"), "w").write("\n".join(report) + "\n")
    open(os.path.join(OUT, "sha1s.txt"), "w").write("".join(f"{k}\t{h}\t{rule}\n" for k, h, rule in passed))
    print("\n".join(report))
    print(f"\n{len(passed)} of {len(todo)} passed")
    sys.exit(0 if len(passed) == len(todo) else 1)


if __name__ == "__main__":
    main()
