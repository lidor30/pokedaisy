#!/usr/bin/env python3
"""Checks the BOXES tab's moves (BoxSwap.kt) in the games whose tables port_field_items.py mapped, headlessly.

    scripts/verify_box_swap.py [--only KEY[,KEY]]

Each game boots in the pokedaisy-capture image (native-capture/mgba_dump) with a save (the English one for the other
languages, as verify_ports.py does), twice. The first run dumps RAM on the field, where this script picks the first box
Pokemon (a vanilla record, its checksum good). The second boots the same way and calls the mapped functions the way
BoxSwap.kt does, then dumps again:

  ArePlayerFieldControlsLocked        0 (on the field, controls free: the moves' gate)
  GetBoxMonData(slot, PERSONALITY)    the record's own personality
  GetBoxMonData(slot, SPECIES)        the record's own species
  BoxMonToMon(slot, scratch)          a struct Pokemon whose first 80 bytes are the record's, its level 1..100, its HP
                                      full, no status (what a Pokemon taken out of the PC is)
  GetMonData(scratch, SPECIES)        the same species
  BoxMonRestorePP(slot)               returns; the record still decrypts with a good checksum, the same Pokemon

FireRed / LeafGreen / Emerald's English tables (BOX_SWAP_*) are checked too, as the harness's own control, and the
hacks edited into English FireRed rev 0 in place (Ultra Violet, Rocket Edition, Ash Gray, Ghost Grey), which run
rev 0's tables. Writes build/boxcheck/box_swap.md; exits 1 if any game failed.
"""
import json
import os
import re
import shutil
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
OUT = os.path.join(ROOT, "build", "boxcheck")
IMAGE = "pokedaisy-capture"
FIELD_ITEMS = os.path.join(ROOT, "build", "ports", "field_items.json")   # port_field_items.py's
SCRATCH = 0x0203F800   # 100 bytes of EWRAM no game here uses (checked zero in each first dump)
B_PRESSES = "press B\nwait 60\n" * 4 + "wait 120\n"

# Saves (scripts/host_roms.conf keys) and boot scripts by game code; the English save loads in the other languages.
SAVES = {"BPR": "firered_vanilla", "BPG": "leafgreen_rev1", "BPE": "emerald_vanilla"}
BOOTS = {"BPR": ("default.txt", B_PRESSES), "BPG": ("leafgreen.txt", B_PRESSES), "BPE": ("default.txt", "wait 120\n")}
# The English tables (BoxSwap.kt) and the party ones they run with (FieldItems.kt's FIELD_ITEMS_*).
ENGLISH = {
    "BPRE1": dict(rom="Pokemon - FireRed Version (USA, Europe) (Rev 1)", fieldControlsLocked=0x0806996C, getMonData=0x0803FBFC,
                  box=dict(boxMonToMon=0x0803E788, boxMonRestorePP=0x080442F8, getBoxMonData=0x0803FD58, storage=0x03005010)),
    "BPEE0": dict(rom="Pokemon - Emerald Version (USA, Europe)", fieldControlsLocked=0x08098E6C, getMonData=0x0806A518,
                  box=dict(boxMonToMon=0x08069004, boxMonRestorePP=0x0806E930, getBoxMonData=0x0806A674, storage=0x03005D94)),
}
# Hacks on English FireRed rev 0's code: rev 0's tables (fieldItemsForGame), their own saves / boots.
HACKS = {"ultra_violet": ("default.txt", B_PRESSES), "rocket_edition": ("firered_hack.txt", ""),
         "ash_gray": ("firered_hack.txt", ""), "ghost_grey": ("ghost_grey.txt", "")}
# A save with nothing boxed: the game's own SendMonToPC (rev 0's) puts the party's lead into box 1 first, in both runs.
PREP = {"ultra_violet": "call 0x08040B91 0x02024284\nwait 2\n"}


def conf(key):
    for line in open(os.path.join(HERE, "host_roms.conf"), encoding="utf-8"):
        f = line.rstrip("\n").split("|")
        if not line.startswith("#") and f[0] == key:
            return os.path.expanduser(f[2]), os.path.expanduser(f[3])
    sys.exit(f"{key} missing from host_roms.conf")


def decrypt(r):
    """A vanilla 80-byte BoxPokemon's personality, species and whether its checksum holds."""
    pid, otid = struct.unpack_from("<II", r, 0)
    key = pid ^ otid
    data = b"".join(struct.pack("<I", struct.unpack_from("<I", r, 32 + 4 * k)[0] ^ key) for k in range(12))
    good = sum(struct.unpack("<24H", data)) & 0xFFFF == struct.unpack_from("<H", r, 0x1C)[0]
    order = ["GAEM", "GAME", "GEAM", "GEMA", "GMAE", "GMEA", "AGEM", "AGME", "AEGM", "AEMG", "AMGE", "AMEG",
             "EGAM", "EGMA", "EAGM", "EAMG", "EMGA", "EMAG", "MGAE", "MGEA", "MAGE", "MAEG", "MEGA", "MEAG"][pid % 24]
    g = order.index("G") * 12
    return pid, struct.unpack_from("<H", data, g)[0], good


class Ram:
    def __init__(self, d):
        self.ew = open(os.path.join(d, "ewram.bin"), "rb").read()
        self.iw = open(os.path.join(d, "iwram.bin"), "rb").read()

    def read(self, a, n):
        if 0x02000000 <= a < 0x02040000:
            return self.ew[a - 0x02000000:a - 0x02000000 + n]
        return self.iw[a - 0x03000000:a - 0x03000000 + n]

    def u32(self, a):
        return struct.unpack("<I", self.read(a, 4))[0]


def targets(only):
    out = {}
    ports = json.load(open(FIELD_ITEMS)) if os.path.isfile(FIELD_ITEMS) else {}
    for key, p in ports.items():
        if p.get("box"):
            boot, after = BOOTS[key[:3]]
            out[key] = dict(rom=p["path"], save=conf(SAVES[key[:3]])[1], boot=boot, after=after,
                            fieldControlsLocked=p["fieldControlsLocked"], getMonData=p["getMonData"], box=p["box"])
    for key, e in ENGLISH.items():
        rom, save = conf(SAVES[key[:3]])
        boot, after = BOOTS[key[:3]]
        out[key] = dict(rom=rom, save=save, boot=boot, after=after, **{k: v for k, v in e.items() if k != "rom"})
    rev0 = ports.get("BPRE0")
    if rev0 and rev0.get("box"):
        for key, (boot, after) in HACKS.items():
            rom, save = conf(key)
            out[key] = dict(rom=rom, save=save, boot=boot, after=after, fieldControlsLocked=rev0["fieldControlsLocked"],
                            getMonData=rev0["getMonData"], box=rev0["box"])
    return {k: v for k, v in out.items() if not only or k in only}


def run(work, key, t, cmds):
    d = os.path.join(work, key)
    os.makedirs(d, exist_ok=True)
    boot = open(os.path.join(ROOT, "native-capture", "boot", t["boot"])).read().rstrip("\n") + "\n" + t["after"] + PREP.get(key, "")
    with open(os.path.join(d, "cmds.txt"), "w") as f:
        f.write(boot + cmds)
    r = subprocess.run(["docker", "run", "--rm", "-v", f"{work}:/work", "-w", f"/work/{key}", IMAGE, "-c",
                        "/work/mgba_dump rom.gba rom.sav < cmds.txt"], capture_output=True, text=True)
    return r.stdout + r.stderr


def check(work, key, t):
    d = os.path.join(work, key)
    os.makedirs(os.path.join(d, "before"), exist_ok=True)
    os.makedirs(os.path.join(d, "after"), exist_ok=True)
    shutil.copy(t["rom"], os.path.join(d, "rom.gba"))
    b = t["box"]
    # 1. the field: the first boxed Pokémon.
    shutil.copy(t["save"], os.path.join(d, "rom.sav"))
    run(work, key, t, f"dump /work/{key}/before\n")
    before = Ram(os.path.join(d, "before"))
    storage = before.u32(b["storage"])
    if not 0x02000000 <= storage < 0x02040000:
        return f"storage pointer reads {storage:#x}"
    if any(before.read(SCRATCH, 100)):
        return "scratch isn't free"
    slot = None
    for i in range(14 * 30):
        rec = before.read(storage + 4 + 80 * i, 80)
        if any(rec) and decrypt(rec)[2]:
            slot = storage + 4 + 80 * i
            break
    if slot is None:
        return "no boxed Pokemon in the save"
    rec = before.read(slot, 80)
    pid, species, _ = decrypt(rec)
    # 2. the calls, from the same boot.
    shutil.copy(t["save"], os.path.join(d, "rom.sav"))
    calls = [(t["fieldControlsLocked"], ()), (b["getBoxMonData"], (slot, 0)), (b["getBoxMonData"], (slot, 11)),
             (b["boxMonToMon"], (slot, SCRATCH)), (t["getMonData"], (SCRATCH, 11)), (b["boxMonRestorePP"], (slot,))]
    cmds = "".join(f"call 0x{fn | 1:08X} {' '.join(f'0x{a:08X}' for a in args)}\n" for fn, args in calls)
    log = run(work, key, t, cmds + "wait 2\n" + f"dump /work/{key}/after\n")
    # The last ones: a PREP call comes first.
    got = re.findall(r"call 0x[0-9A-Fa-f]+\(.*?\): (?:returned 0x([0-9a-fA-F]+)|(DID NOT RETURN))", log)[-len(calls):]
    if len(got) != len(calls) or any(nr for _, nr in got):
        return f"calls: {got}"
    r = [int(v, 16) for v, _ in got]
    after = Ram(os.path.join(d, "after"))
    mon = after.read(SCRATCH, 100)
    level, hp, max_hp, status = mon[0x54], *struct.unpack_from("<HH", mon, 0x56), struct.unpack_from("<I", mon, 0x50)[0]
    apid, aspecies, agood = decrypt(after.read(slot, 80))
    problems = [
        r[0] != 0 and f"controls locked ({r[0]})",
        r[1] != pid and f"GetBoxMonData personality {r[1]:#x}, record {pid:#x}",
        r[2] != species and f"GetBoxMonData species {r[2]}, record {species}",
        mon[:80] != rec and "BoxMonToMon's first 80 bytes aren't the record",
        not 1 <= level <= 100 and f"level {level}",
        (max_hp == 0 or hp != max_hp) and f"HP {hp}/{max_hp}",
        status != 0 and f"status {status:#x}",
        r[4] != species and f"GetMonData species {r[4]}",
        not (agood and apid == pid and aspecies == species) and "the record after BoxMonRestorePP",
    ]
    problems = [p for p in problems if p]
    return "; ".join(problems) if problems else f"ok: species {species}, Lv{level}, HP {hp}/{max_hp}"


def main():
    only = set(sys.argv[sys.argv.index("--only") + 1].split(",")) if "--only" in sys.argv else None
    work = os.path.join(OUT, "swap")
    os.makedirs(work, exist_ok=True)
    subprocess.run(["docker", "run", "--rm", "-v", f"{ROOT}:/repo", "-v", f"{work}:/work", "-w", "/repo/native-capture", IMAGE,
                    "-c", "gcc -O2 -Wall -o /work/mgba_dump mgba_dump.c -I/usr/include -L/usr/lib/aarch64-linux-gnu -lmgba -lm"],
                   check=True)
    rows, failed = [], 0
    for key, t in sorted(targets(only).items()):
        res = check(work, key, t)
        failed += not res.startswith("ok")
        rows.append(f"| {key} | {os.path.basename(t['rom'])} | {res} |")
        print(f"{key:15} {res}")
    with open(os.path.join(OUT, "box_swap.md"), "w") as f:
        f.write("# BOXES moves, headless\n\n| key | ROM | result |\n|---|---|---|\n" + "\n".join(rows) + "\n")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
