#!/usr/bin/env python3
"""Checks WIDE (shared/src/commonMain/cpp/pk_wide.c) on every ROM scripts/gen_wide_tables.py put in its table,
headlessly - no device.

    scripts/verify_wide.py [--only CRC[,CRC]] [--margin N]

Each ROM boots in the pokedaisy-capture image (native-capture/mgba_dump, built here with the app's
pk_wide.c) with its game's save (scripts/host_roms.conf, as scripts/verify_ports.py picks them) and runs:

  map     BG0 off (text boxes, popups), walking a loop with `widecheck`: WIDE's rebuild of the middle
          240x160 must equal every map frame the game draws, pixel for pixel;
  battle  only BG3 + sprites on, a scripted wild battle (setwildbattle poked into EWRAM, the ROM's own
          ScriptContext_SetupScript called on it) through RUN and back, with `widecheck all`.

Prints a line per ROM and writes build/wide/verify.md. A ROM passes with some map and battle frames
checked and none differing.
"""
import json
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
OUT = os.path.join(ROOT, "build", "wide")
IMAGE = "pokedaisy-capture"

sys.path.insert(0, HERE)
from verify_ports import AFTER_BOOT, BOOTS, OWN_SAVES, SAVES, conf  # noqa: E402

SAVES = {**SAVES, "BPE": "emerald_vanilla"}
BOOTS = {**BOOTS, "BPE": "default.txt"}
AFTER_BOOT = {**AFTER_BOOT, "BPE": "wait 120\n"}
SCRIPT_RAM = 0x0203FF00
# setwildbattle SPECIES 16, 5, ITEM_NONE; dowildbattle; end. Expansion's setwildbattle takes a second mon (SoulGold's
# more): the zeros cover those, and are nops where it doesn't.
BATTLE = [0xB6, 0x10, 0x00, 0x05] + [0x00] * 12 + [0xB7, 0x02]
WALK = [("DOWN", 24), ("B,LEFT", 48), ("B,UP", 48), ("B,RIGHT", 96), ("B,DOWN", 48), ("LEFT", 48), ("UP", 64), ("B,RIGHT", 64)]


def boot_lines(code, extra=(), boot_file=None):
    code = code if code[:3] in BOOTS else "BPRE"   # a hack under its own code (Ultra Violet): FireRed's boot
    name = boot_file or BOOTS[code[:3]]
    text = open(os.path.join(ROOT, "native-capture", "boot", name)).read() + ("" if boot_file else AFTER_BOOT.get(code[:3], ""))
    return [l for l in text.splitlines() if l.strip() and not l.startswith(("#", "shot"))] + list(extra)


def map_run(code, margin, extra=(), boot_file=None):
    out = [f"wideon {margin}", "layers 0x1E"] + boot_lines(code, extra, boot_file) + ["widecheck"]
    for k, n in WALK:
        out += [f"keys {k}", f"wait {n}"]
    return out + ["keys NONE", "widestats"]


def battle_run(code, script, margin, extra=(), boot_file=None, script_ram=SCRIPT_RAM):
    slow = code[:3] in ("BPE", "AXV", "AXP")   # Emerald and Ruby / Sapphire print their battle text slower
    out = [f"wideon {margin}"] + boot_lines(code, extra, boot_file) + ["wait 60", "layers 0x18", "wait 2", "widecheck all"]
    out += [f"poke8 {script_ram + i:#x} {b:#x}" for i, b in enumerate(BATTLE)]
    out += [f"call {script} {script_ram:#x}", f"wait {520 if slow else 420}", "press A", f"wait {480 if slow else 330}",
            "press RIGHT", "wait 12", "press DOWN", "wait 12", "press A", "wait 90", "press A", "wait 300", "widestats"]
    return out


def run(work, commands):
    open(os.path.join(work, "cmds.txt"), "w").write("\n".join(commands) + "\n")
    shutil.copy(os.path.join(work, "save.sav"), os.path.join(work, "save_w.sav"))
    r = subprocess.run(["docker", "run", "--rm", "-v", f"{work}:/work", "-w", "/work", IMAGE, "-c",
                        "./mgba_dump rom.gba save_w.sav < cmds.txt"], capture_output=True, text=True)
    stats = [l for l in r.stdout.splitlines() if l.startswith("wide: ") and " frames, " in l]
    off = [l for l in r.stdout.splitlines() if l.startswith("widecheck: ")]
    m = re.search(r"(\d+) frames, (\d+) battle, (\d+) unverified.*centre checked on (\d+): (\d+) exact", stats[-1]) if stats else None
    return m, off


def main():
    args = sys.argv[1:]
    only = set(args[args.index("--only") + 1].split(",")) if "--only" in args else None
    margin = int(args[args.index("--margin") + 1]) if "--margin" in args else 22
    roms = json.load(open(os.path.join(OUT, "wide.json")))
    subprocess.run(["docker", "image", "inspect", IMAGE], capture_output=True, check=True)
    tool = os.path.join(OUT, "mgba_dump")
    subprocess.run(["docker", "run", "--rm", "-v", f"{ROOT}:/repo", "-v", f"{OUT}:/out", "-w", "/repo/native-capture", IMAGE,
                    "-c", "gcc -O2 -Wall -DPK_WIDE_PENDING -o /out/mgba_dump mgba_dump.c -I/usr/include -L/usr/lib/aarch64-linux-gnu -lmgba -lm"],
                   check=True)
    rows = ["| ROM | CRC | map frames exact | battle frames exact | |", "|---|---|---|---|---|"]
    for e in roms:
        if e["missing"] or (only and e["crc"] not in only):
            continue
        work = os.path.join(OUT, "runs", e["crc"])
        os.makedirs(work, exist_ok=True)
        save = conf(e.get("save") or OWN_SAVES.get(e["code"], SAVES[e["code"][:3]]))
        if not os.path.exists(save):
            line = f"| {e.get('name') or e['file']} | {e['crc']} | - | - | NO SAVE |"
            print(line, flush=True)
            rows.append(line)
            continue
        shutil.copy(e["path"], os.path.join(work, "rom.gba"))
        shutil.copy(save, os.path.join(work, "save.sav"))
        shutil.copy(tool, os.path.join(work, "mgba_dump"))
        mm, moff = run(work, map_run(e["code"], margin, e.get("boot", []), e.get("boot_file")))
        bm, boff = run(work, battle_run(e["code"], e["script"], margin, e.get("boot", []), e.get("boot_file"),
                                        int(e.get("script_ram") or SCRIPT_RAM))) if e["script"] else (None, [])
        # The map run checks map frames only; the battle run (BG3 + sprites) checks its battle frames.
        maps = f"{mm.group(5)}/{mm.group(4)}" if mm else "?"
        battle_off = [l for l in boff if "(battle)" in l]
        battles = f"{int(bm.group(2)) - len(battle_off)}/{bm.group(2)}" if bm else "?"
        ok = mm and int(mm.group(4)) > 100 and not moff and bm and int(bm.group(2)) > 100 and not battle_off
        verdict = 'ok' if ok else 'pending' if e.get('pending') else 'CHECK'   # pending: not in the app's table
        line = f"| {e.get('name') or e['file']} | {e['crc']} | {maps} | {battles} | {verdict} |"
        print(line, flush=True)
        rows.append(line)
    open(os.path.join(OUT, "verify.md"), "w").write("\n".join(rows) + "\n")


if __name__ == "__main__":
    main()
