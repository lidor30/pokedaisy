#!/usr/bin/env python3
"""Checks USE on a TM / HM (TmTeach.kt) and FLASH (Flash.kt) in the games port_field_items.py mapped, headlessly.

    scripts/verify_tm_flash.py [--only KEY[,KEY]]

Each game boots in the pokedaisy-capture image (native-capture/mgba_dump) with a save (the English one for the other
languages, as verify_ports.py does; Japanese FireRed / LeafGreen their own) and calls the mapped functions the way the
companion does:

  TM screen   HM05 put in the bag (AddBagItem), then tmStart / tmFinish's calls: the controls locked, FadeScreen to black,
              CleanupOverworldWindowsAndTilemaps, gSpecialVar_ItemId / gItemUseCB / the cursor written,
              CB2_ShowPartyMenuForItemUse, gMain.callback2 = CB2_InitPartyMenu, the exit pointed at CB2_ReturnToField.
              With the English saves the game's screens are then played through (a Pokemon that can learn it, its
              fourth move cleared first so no "forget a move?" comes): it must know FLASH after. Japanese FireRed / LeafGreen's saves back out with B instead.
              Either way the field must come back: gMain.callback2 the field's again, the controls free.
  FLASH       a warp into a dark cave (Rock Tunnel / Granite Cave), FLASH set on the lead Pokemon (SetMonData), then
              useFlash's calls: the lit flag clear, SetUpFieldMove_Flash = TRUE with the post-menu callback the mapped
              FieldCallback_Flash, that callback run: the lit flag set, the controls free, SetUpFieldMove_Flash FALSE.

Screenshots of each step go into build/tmflash/<key>/ (and a contact sheet, sheet.png, with Pillow). Writes
build/tmflash/tm_flash.md; exits 1 if any game failed.
"""
import json
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
OUT = os.path.join(ROOT, "build", "tmflash")
IMAGE = "pokedaisy-capture"
FIELD_ITEMS = os.path.join(ROOT, "build", "ports", "field_items.json")   # port_field_items.py's
SCRATCH = 0x0203FC00   # free EWRAM in every game here: the warp script, then the move id
HM05, FLASH = 343, 148
B_PRESSES = "press B\nwait 60\n" * 4 + "wait 120\n"

SAVES = {"BPR": "firered_vanilla", "BPG": "leafgreen_rev1", "BPE": "emerald_vanilla", "AXV": "ruby_rev1", "AXP": "sapphire_rev1"}
OWN_SAVES = {"BPRJ": "firered_ja", "BPGJ": "leafgreen_ja"}
BOOTS = {"BPR": ("default.txt", B_PRESSES), "BPG": ("leafgreen.txt", B_PRESSES), "BPE": ("default.txt", "wait 120\n"),
         "AXV": ("ruby_sapphire.txt", "wait 120\n"), "AXP": ("ruby_sapphire.txt", "wait 120\n")}
# The English saves' slot that can learn HM05. Its fourth move is cleared first, so the game teaches it into the free slot
# with no "forget a move?" (whose text runs longer in some languages): pick it, then B through the messages and out.
TEACH_SLOT = {"BPR": 0, "BPG": 2, "BPE": 0}
TEACH = ["press A", "wait 150"] + ["press B", "wait 120"] * 8 + ["wait 200"]
BACK_OUT = ["wait 150"] + ["press B", "wait 120"] * 4 + ["wait 200"]
# A dark cave to warp into (group, map, warp, x, y): FireRed / LeafGreen's Rock Tunnel, Hoenn's Granite Cave.
CAVES = {"BPR": (1, 81, 0xFF, 17, 3), "BPG": (1, 81, 0xFF, 17, 3), "BPE": (24, 8, 0, 0, 0), "AXV": (24, 8, 0, 0, 0),
         "AXP": (24, 8, 0, 0, 0)}
MON_DATA_MOVE4 = 16
PARTY_STRIDE = 100


def conf(key):
    for line in open(os.path.join(HERE, "host_roms.conf"), encoding="utf-8"):
        f = line.rstrip("\n").split("|")
        if not line.startswith("#") and f[0] == key:
            return os.path.expanduser(f[3])
    sys.exit(f"{key} missing from host_roms.conf")


h = lambda v: f"0x{v:X}"


def script(key, p):
    fam = key[:3]
    o = [f"peek32 {h(p['tm']['mainCallback2'])}" if p.get("tm") else "wait 1"]
    lock = p["fieldControlsLocked"]
    gmd = p["getMonData"]
    if p.get("tm"):
        t = p["tm"]
        own = key[:4] in OWN_SAVES
        slot, presses = (0, BACK_OUT) if own else (TEACH_SLOT[fam], TEACH)
        mon = p["party"] + slot * PARTY_STRIDE
        o += [f"poke16 {h(SCRATCH + 0x20)} 0", f"call {h(p['setMonData'])} {h(mon)} {MON_DATA_MOVE4} {h(SCRATCH + 0x20)}",
              f"call {h(p['addBagItem'])} {HM05} 1",
              f"call {h(t['lockControls'])}", f"call {h(t['fadeScreen'])} 1 0", "wait 30", f"call {h(t['cleanupOverworld'])}",
              f"poke16 {h(t['specialVarItemId'])} {HM05}", f"poke32 {h(t['itemUseCb'])} {h(t['itemUseCbTmhm'] | 1)}",
              f"poke8 {h(t['partyMenu'] + t['partyMenuSlotOff'])} 0", f"call {h(t['showPartyMenuForItemUse'])}",
              f"peek32 {h(t['mainCallback2'])}", f"poke32 {h(t['partyMenu'])} {h(t['returnToField'] | 1)}", "wait 120",
              "shot tm_menu.ppm"]
        o += ["press DOWN", "wait 20"] * slot
        o += presses + ["shot tm_after.ppm"]
        o += [f"call {h(gmd)} {h(mon)} {m}" for m in range(MON_DATA_MOVE4 - 3, MON_DATA_MOVE4 + 1)]
        o += [f"peek32 {h(t['mainCallback2'])}", f"call {h(lock)}"]
    f = p["flash"]
    g, n, w, x, y = CAVES[fam]
    warp = [0x39, g, n, w, x & 255, x >> 8, y & 255, y >> 8, 2]
    o += [f"poke8 {h(SCRATCH + i)} {h(b)}" for i, b in enumerate(warp)]
    o += [f"call {h(p['pc']['setupScript'])} {h(SCRATCH)}", "wait 500",
          f"poke16 {h(SCRATCH + 0x20)} {FLASH}", f"call {h(p['setMonData'])} {h(p['party'])} {MON_DATA_MOVE4} {h(SCRATCH + 0x20)}",
          f"call {h(p['getMonData'])} {h(p['party'])} {MON_DATA_MOVE4}",
          f"call {h(lock)}", f"call {h(f['flagGet'])} {h(f['litFlag'])}", "shot flash_dark.ppm",
          f"poke8 {h(f['partyMenuSlotId'])} 0", f"call {h(f['setUpFieldMoveFlash'])}", f"peek32 {h(f['postMenuFieldCallback'])}",
          f"poke32 {h(f['menuFieldCallback'])} 0", f"poke32 {h(f['postMenuFieldCallback'])} 0",
          f"call {h(f['fieldCallbacks'][0] | 1)}", "wait 30", "shot flash_cutin.ppm", "wait 700",
          f"call {h(f['flagGet'])} {h(f['litFlag'])}", f"call {h(lock)}", f"call {h(f['setUpFieldMoveFlash'])}",
          "hold DOWN 30", "shot flash_lit.ppm"]
    return "\n".join(o) + "\n"


def run(work, key, p):
    d = os.path.join(work, key)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    fam = key[:3]
    shutil.copy(p["path"], os.path.join(d, "rom.gba"))
    shutil.copy(conf(OWN_SAVES.get(key[:4], SAVES[fam])), os.path.join(d, "rom.sav"))
    boot, after = BOOTS[fam]
    with open(os.path.join(d, "cmds.txt"), "w") as fh:
        fh.write(open(os.path.join(ROOT, "native-capture", "boot", boot)).read().rstrip("\n") + "\n" + after + "wait 400\n"
                 + script(key, p))
    r = subprocess.run(["docker", "run", "--rm", "-v", f"{work}:/work", "-w", f"/work/{key}", IMAGE, "-c",
                        "/work/mgba_dump rom.gba rom.sav < cmds.txt"], capture_output=True, text=True)
    log = r.stdout + r.stderr
    open(os.path.join(d, "log.txt"), "w").write(log)
    return log


def check(key, p, log):
    calls = re.findall(r"^call 0x[0-9A-Fa-f]+\(.*?\): (?:returned 0x([0-9a-fA-F]+)|(DID NOT RETURN))", log, re.M)
    if any(nr for _, nr in calls):
        return "a call didn't return"
    r = [int(v, 16) for v, _ in calls]
    peeks = [int(v, 16) for v in re.findall(r"^[0-9a-f]{8}: ([0-9a-f]{8})$", log, re.M)]
    problems, notes = [], []
    if p.get("tm"):
        t = p["tm"]
        field, opened, back = peeks[0], peeks[1], peeks[2]
        # r: SetMonData (the 4th move cleared), AddBagItem, lock, fade, cleanup, show, 4 moves, lock.
        r = r[1:]
        moves, locked = r[5:9], r[9]
        own = key[:4] in OWN_SAVES
        if r[0] != 1:
            problems.append(f"AddBagItem {r[0]}")
        if opened & ~1 != t["initPartyMenu"] & ~1:
            problems.append(f"callback2 {opened:#x} after the show, not CB2_InitPartyMenu")
        if back != field:
            problems.append(f"callback2 {back:#x} after, the field's is {field:#x}")
        if locked:
            problems.append("controls locked after the TM screen")
        if not own and FLASH not in moves:
            problems.append(f"FLASH not learned: moves {moves}")
        notes.append("TM backed out" if own else "TM taught")
        r = r[10:]
    # r: setupScript, SetMonData, GetMonData, lock, lit, setUp, cb, lit, lock, setUp.
    post = peeks[-1]
    f = p["flash"]
    want = [(r[2] == FLASH, f"move {r[2]}"), (r[3] == 0, "controls locked in the cave"), (r[4] == 0, "already lit"),
            (r[5] == 1, f"SetUpFieldMove_Flash {r[5]}"), (post & ~1 == f["fieldCallbacks"][0] & ~1, f"post callback {post:#x}"),
            (r[7] == 1, "not lit after"), (r[8] == 0, "controls locked after"), (r[9] == 0, f"SetUpFieldMove_Flash {r[9]} lit")]
    problems += [why for ok, why in want if not ok]
    notes.append("FLASH lit the cave")
    return "; ".join(problems) if problems else "ok: " + ", ".join(notes)


def sheet(work, keys):
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return
    shots = ("tm_menu", "tm_after", "flash_dark", "flash_cutin", "flash_lit")
    img = Image.new("RGB", (240 * len(shots) + 70, 160 * len(keys)), "white")
    d = ImageDraw.Draw(img)
    for row, key in enumerate(keys):
        d.text((4, 160 * row + 70), key, fill="black")
        for col, s in enumerate(shots):
            path = os.path.join(work, key, s + ".ppm")
            if os.path.isfile(path):
                img.paste(Image.open(path), (70 + 240 * col, 160 * row))
    img.save(os.path.join(OUT, "sheet.png"))


def main():
    only = set(sys.argv[sys.argv.index("--only") + 1].split(",")) if "--only" in sys.argv else None
    ports = json.load(open(FIELD_ITEMS))
    work = os.path.join(OUT, "runs")
    os.makedirs(work, exist_ok=True)
    subprocess.run(["docker", "run", "--rm", "-v", f"{ROOT}:/repo", "-v", f"{work}:/work", "-w", "/repo/native-capture", IMAGE,
                    "-c", "gcc -O2 -Wall -o /work/mgba_dump mgba_dump.c -I/usr/include -L/usr/lib/aarch64-linux-gnu -lmgba -lm"],
                   check=True)
    rows, failed, keys = [], 0, []
    for key, p in sorted(ports.items()):
        if not p.get("flash") or (only and key not in only):
            continue
        res = check(key, p, run(work, key, p))
        failed += not res.startswith("ok")
        keys.append(key)
        rows.append(f"| {key} | {os.path.basename(p['path'])} | {res} |")
        print(f"{key:7} {res}", flush=True)
    sheet(work, keys)
    with open(os.path.join(OUT, "tm_flash.md"), "w") as fh:
        fh.write("# TM screen and FLASH, headless\n\n| key | ROM | result |\n|---|---|---|\n" + "\n".join(rows) + "\n")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
