#!/usr/bin/env python3
"""Checks every ROM scripts/port_retail.py ported, headlessly - no device.

    scripts/verify_ports.py [--only KEY[,KEY]] [--no-boot]

Each ported ROM, and the English ROM it was ported from, boots in the
pokedaisy-capture image (native-capture/mgba_dump) with that game's English
save (scripts/host_roms.conf: the other languages load it as is) and the
game's boot script, twice:

  field   on the field -> build/ports/dumps/<key>/field/{ewram,iwram}.bin + .ppm
  battle  a scripted wild battle (setwildbattle PIKACHU 5 poked into EWRAM,
          ScriptContext_SetupScript called on it), dumped at the action menu,
          then (FireRed / LeafGreen) A -> moves, B, RIGHT A -> bag, B, LEFT DOWN
          A -> party, each with gBattlerControllerFuncs[0] read (peeks.txt)

then runs PortsVerifyTest (app/src/test), which reads each dump with the
ported config and compares it with English's: the same party, bag, money and
place; the dex and guide tables found in the ROM; the probe trainer's party;
the battlers; the battle handlers. It writes build/ports/report.md.
"""
import json
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
PORTS = os.path.join(ROOT, "build", "ports")
CAPTURE = os.path.join(ROOT, "native-capture")
IMAGE = "pokedaisy-capture"

SAVES = {"BPR": "firered_vanilla", "BPG": "leafgreen_rev1", "AXV": "ruby_rev1", "AXP": "sapphire_rev1"}
# Japanese FireRed / LeafGreen can't read the English saves ("the saved data was lost"): their own,
# with no English ROM to compare with - PortsVerifyTest checks those absolutely (see it).
OWN_SAVES = {"BPRJ": "firered_ja", "BPGJ": "leafgreen_ja"}
BOOTS = {"BPR": "default.txt", "BPG": "leafgreen.txt", "AXV": "ruby_sapphire.txt", "AXP": "ruby_sapphire.txt"}
# FireRed / LeafGreen: B through what's left of the quest-log recap (the longer languages
# need more than the boot script's presses); harmless on the field.
AFTER_BOOT = {"BPR": "press B\nwait 60\n" * 4 + "wait 120\n", "BPG": "press B\nwait 60\n" * 4 + "wait 120\n"}
SCRIPT_RAM = 0x0203FF00  # free EWRAM for the poked battle script
# setwildbattle SPECIES_PIKACHU, 5, ITEM_NONE; dowildbattle; end
BATTLE_SCRIPT = [0xB6, 0x19, 0x00, 0x05, 0x00, 0x00, 0xB7, 0x02]


def conf(key):
    for line in open(os.path.join(HERE, "host_roms.conf"), encoding="utf-8"):
        f = line.rstrip("\n").split("|")
        if not line.startswith("#") and f[0] == key:
            return os.path.expanduser(f[3])
    sys.exit(f"{key} missing from host_roms.conf")


def boot_text(boot, code):
    return open(os.path.join(CAPTURE, "boot", boot)).read().rstrip("\n") + "\n" + AFTER_BOOT.get(code[:3], "")


def commands(boot, code, script_fn, controller, party_menu, battle_dir):
    out = boot_text(boot, code).rstrip("\n").split("\n")
    out += [f"poke8 0x{SCRIPT_RAM + i:X} 0x{b:02X}" for i, b in enumerate(BATTLE_SCRIPT)]
    out += [f"call 0x{script_fn:08X} 0x{SCRIPT_RAM:08X}", "wait 1300", "hold A 4", "wait 400"]
    if controller:
        out.append(f"peek32 0x{controller:08X}")
    out += [f"dump {battle_dir}", f"shot {battle_dir}.ppm"]
    if controller:
        out += ["hold A 4", "wait 60", f"peek32 0x{controller:08X}", "hold B 4", "wait 60",
                "hold RIGHT 4", "wait 20", "hold A 4", "wait 200", f"peek32 0x{controller:08X}",
                "hold B 4", "wait 200", "hold LEFT 4", "wait 20", "hold DOWN 4", "wait 20", "hold A 4", "wait 200",
                f"peek32 0x{controller:08X}"]
        if party_menu:
            out.append(f"peek32 0x{party_menu:08X}")
    return "\n".join(out) + "\n"


def contact_sheet(keys, out, cols=6):
    """Every ROM's field screenshot in one PNG (the report's order), to look over at a glance."""
    import struct
    import zlib
    shots = []
    for k in keys:
        f = os.path.join(PORTS, "dumps", k, "field.ppm")
        if os.path.exists(f):
            d = open(f, "rb").read().split(b"\n", 3)
            shots.append((tuple(map(int, d[1].split())), d[3]))
    if not shots:
        return
    (w, h), rows = shots[0][0], (len(shots) + cols - 1) // cols
    canvas = bytearray(b"\x30" * (cols * w * rows * h * 3))
    for n, (_, px) in enumerate(shots):
        cx, cy = (n % cols) * w, (n // cols) * h
        for y in range(h):
            o = ((cy + y) * cols * w + cx) * 3
            canvas[o:o + w * 3] = px[y * w * 3:(y + 1) * w * 3]
    raw = b"".join(b"\0" + bytes(canvas[y * cols * w * 3:(y + 1) * cols * w * 3]) for y in range(rows * h))
    chunk = lambda t, b: struct.pack(">I", len(b)) + t + b + struct.pack(">I", zlib.crc32(t + b))
    with open(out, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", cols * w, rows * h, 8, 2, 0, 0, 0)) +
                chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def main():
    ports = json.load(open(os.path.join(PORTS, "ports.json"), encoding="utf-8"))
    refs = json.load(open(os.path.join(PORTS, "english.json"), encoding="utf-8"))
    only = set(sys.argv[sys.argv.index("--only") + 1].split(",")) if "--only" in sys.argv else None
    if only:
        ports = [p for p in ports if p["key"] in only]

    work = os.path.join(PORTS, "dumps")
    if "--no-boot" not in sys.argv:
        os.makedirs(work, exist_ok=True)
        roms = os.path.join(work, "roms")
        os.makedirs(roms, exist_ok=True)
        runs = []  # (key, rom path, save key, boot, script fn, controller, party menu)
        for k, r in refs.items():
            if any(p["english"] == k and p["code"] not in OWN_SAVES for p in ports):
                runs.append((f"EN_{k}", r["path"], SAVES[k[:3]], BOOTS[k[:3]], r["anchors"]["script"],
                             r["fields"].get("battlerControllerFuncs"), r["anchors"].get("party_menu")))
        for p in ports:
            ctl = p["fields"].get("battlerControllerFuncs", [None])[0]
            runs.append((p["key"], p["path"], OWN_SAVES.get(p["code"], SAVES[p["code"][:3]]), BOOTS[p["code"][:3]], p["anchors"]["script"][0],
                         ctl, p["anchors"].get("party_menu", [None])[0]))
        sh = ["set -e", "cd /work"]
        for key, path, save, boot, script_fn, ctl, pm in runs:
            d = os.path.join(work, key)
            shutil.rmtree(d, ignore_errors=True)
            os.makedirs(os.path.join(d, "field"))
            os.makedirs(os.path.join(d, "battle"))
            shutil.copy(path, os.path.join(roms, key + ".gba"))
            for run in ("field", "battle"):
                shutil.copy(conf(save), os.path.join(d, run + ".sav"))
            code = key[3:] if key.startswith("EN_") else key
            with open(os.path.join(d, "field.txt"), "w") as f:
                f.write(boot_text(boot, code) + f"shot /work/{key}/field.ppm\ndump /work/{key}/field\n")
            with open(os.path.join(d, "battle.txt"), "w") as f:
                f.write(commands(boot, code, script_fn, ctl, pm, f"/work/{key}/battle"))
            sh.append(f"./mgba_dump roms/{key}.gba {key}/field.sav < {key}/field.txt > {key}/field.log 2>&1")
            sh.append(f"./mgba_dump roms/{key}.gba {key}/battle.sav < {key}/battle.txt > {key}/peeks.txt 2>&1")
            sh.append(f"echo {key}")
        with open(os.path.join(work, "run.sh"), "w") as f:
            f.write("\n".join(sh) + "\n")
        subprocess.run(["docker", "image", "inspect", IMAGE], check=True, capture_output=True)
        # Built from the repo root: mgba_dump compiles in the app's own C files (pk_wide.c, pk_cheats.c, ...).
        subprocess.run(["docker", "run", "--rm", "-v", f"{ROOT}:/repo", "-v", f"{work}:/work", "-w", "/repo/native-capture", IMAGE, "-c",
                        "gcc -O2 -Wall -o /work/mgba_dump mgba_dump.c -I/usr/include -L/usr/lib/aarch64-linux-gnu -lmgba -lm "
                        "&& bash /work/run.sh"], check=True)
        shutil.rmtree(roms, ignore_errors=True)
    # The JVM side: PortsVerifyTest reads build/ports/verify.tsv and dumps/.
    with open(os.path.join(PORTS, "verify.tsv"), "w", encoding="utf-8") as f:
        for p in ports:
            # "-": no English run to compare with (its own save), PortsVerifyTest checks it absolutely
            f.write("\t".join([p["key"], p["path"], "-" if p["code"] in OWN_SAVES else p["english"],
                               refs[p["english"]]["path"], refs[p["english"]]["native"],
                               str(p["probe_trainer"]), p["file"], p["tables_path"], p["guide"]]) + "\n")
    contact_sheet([p["key"] for p in ports], os.path.join(PORTS, "fields.png"))
    r = subprocess.run([os.path.join(ROOT, "gradlew"), "-p", ROOT, ":app:testDebugUnitTest", "--tests", "*PortsVerifyTest", "--rerun", "-q"])
    report = os.path.join(PORTS, "report.md")
    if os.path.exists(report):
        print(open(report, encoding="utf-8").read())
    sys.exit(r.returncode)


if __name__ == "__main__":
    main()
