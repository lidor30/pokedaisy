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
import struct
import subprocess
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from port_field_items import REFS, record  # noqa: E402

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
# Recharged Emerald's bag tables (FieldItemsRechargedEmeraldGen.kt): GetMonData, SetMonData, AddBagItem,
# ArePlayerFieldControlsLocked, gPlayerParty.
# The gcc-built hacks FLY knows (FieldItems.kt's tables): GetMonData, SetMonData, ArePlayerFieldControlsLocked, gPlayerParty,
# ScriptContext_SetupScript (PC anywhere's), how far their MON_DATA ids sit past vanilla's. FLASH only (no TM screen there):
# no dark map is found in their map tables here, so FLASH runs where the save stands, counted as a cave.
EXPANSION = {
    "LAZARUS": (0x081C2F54, 0x081C36D0, 0x0820B218, 0x0201B960, 0x0820B2B0, 7),
    "IMPERIUM": (0x081BDE00, 0x081BE590, 0x08200894, 0x020375F8, 0x08200930, 7),
    "HEART_AND_SOUL": (0x081BE7F8, 0x081BF848, 0x08207FF8, 0x02034764, 0x08208094, 7),
    "SOULGOLD": (0x081ED710, 0x081EDD68, 0x082363D8, 0x0203901C, 0x0823647C, 8),
    "SOULGOLD_V1_2": (0x081EE340, 0x081EE998, 0x08236CB8, 0x02039024, 0x08236D5C, 8),
    "SOULGOLD_V1_2B": (0x081EE2D4, 0x081EE92C, 0x08236C20, 0x02039024, 0x08236CC4, 8),
    "SOULGOLD_V1_2C": (0x081EE3A0, 0x081EE9F8, 0x08236D18, 0x02039024, 0x08236DBC, 8),
    "SOULGOLD_V1_2_1": (0x081F1748, 0x081F1D28, 0x0823BB60, 0x02038E68, 0x0823BC04, 8),
}
RECHARGED = {
    "RECHARGED_EMERALD_V2_2_5": (0x08169D20, 0x0816A2D4, 0x0811AD64, 0x081AEF94, 0x020373C8),
    "RECHARGED_EMERALD_V2_2_8": (0x0816AEDC, 0x0816B4A4, 0x0811BB44, 0x081B093C, 0x020373C8),
    "RECHARGED_EMERALD_REBALANCED_V2_2_8": (0x0816B1F0, 0x0816B7B8, 0x0811BC6C, 0x081B0A58, 0x020373DC),
}
# The hacks edited into retail's code in place (TmTeach.kt's tmFnsFor, Flash.kt's flashFnsFor): their host_roms.conf save,
# boot script and the retail code they run (FireRed rev 0 / 1's or Emerald's tables). Their saves' parties and maps are their
# own, so the TM screen is backed out of with B, and FLASH goes to the first dark map in the hack's own map table
# (find_cave), else runs where the save stands with the map counted as a cave. Left out, as the app leaves them: Amethyst (its
# TM path restarted the game: CleanupOverworldWindowsAndTilemaps never returned), and FLASH on Rocket Edition / Ash Gray (their
# player sprites never finish the field move's pose: the cut-in waits forever, the controls locked).
NO_FLASH = {"Rocket Edition", "Ash Gray", "Ash Gray Fork"}
NO_TM = {"Amethyst 1.3.0", "Amethyst 1.4.1"}
# Hacks whose saves hold a Pokemon that can learn HM05 (party slot): taught through the game's screens like the ports.
TEACH_HACKS = {"Emerald Legacy": 0, "Recharged Emerald 2.2.5": 0, "Recharged Emerald 2.2.8": 0, "Recharged Emerald Rebalanced": 0}
HACKS = {
    "Ultra Violet": ("ultra_violet", "default.txt", "FR0"), "Rocket Edition": ("rocket_edition", "firered_hack.txt", "FR0"),
    "Ash Gray": ("ash_gray", "firered_hack.txt", "FR0"), "Ash Gray Fork": ("ash_gray_fork", "firered_hack.txt", "FR0"),
    "Ghost Grey 1.0.4": ("ghost_grey", "ghost_grey.txt", "FR0"), "Ghost Grey 1.0.2": ("ghost_grey_v102", "ghost_grey.txt", "FR0"),
    "Ghost Grey 1.0.1": ("ghost_grey_v101", "ghost_grey.txt", "FR0"),
    "Let's Go Pikachu": ("lets_go_pikachu", "lets_go.txt", "FR0"), "Let's Go Eevee": ("lets_go_eevee", "lets_go.txt", "FR0"),
    "Let's Go Pikachu TO": ("lets_go_pikachu_translation", "lets_go.txt", "FR0"),
    "Let's Go Eevee TO": ("lets_go_eevee_translation", "lets_go.txt", "FR0"),
    "Orange Islands": ("orange_islands", "default.txt", "FR0"), "Odyssey": ("odyssey", "default.txt", "FR0"),
    "Gaia": ("gaia", "default.txt", "FR0"), "Unbound": ("unbound", "default.txt", "FR0"),
    "Unbound FR": ("unbound_fr", "default.txt", "FR0"), "Radical Red": ("radical_red", "default.txt", "FR0"),
    "Amethyst 1.3.0": ("amethyst", "wait 600|default.txt", "FR0"), "Amethyst 1.4.1": ("amethyst_v141", "wait 600|default.txt", "FR0"),
    "Sword and Shield UP Casual": ("sword_shield_up_casual", "default.txt", "FR0"),
    "GS Chronicles": ("gs_chronicles", "gs_chronicles.txt", "FR0"),
    "FireRed Randomized rev 0": ("firered_randomized_rev0", "default.txt", "FR0"),
    "FireRed Randomized rev 1": ("firered_randomized", "default.txt", "FR1"),
    "Glazed": ("glazed", "default.txt", "EM"), "Emerald Kaizo": ("emerald_kaizo", "default.txt", "EM"),
    "Emerald Randomized": ("emerald_randomized", "default.txt", "EM"),
    "Emerald Legacy": ("emerald_legacy", "default.txt", "EMERALD_LEGACY"),
    "Recharged Emerald 2.2.5": ("recharged_emerald_v225", "recharged_emerald.txt", "RECHARGED_EMERALD_V2_2_5"),
    "Recharged Emerald 2.2.8": ("recharged_emerald", "recharged_emerald.txt", "RECHARGED_EMERALD_V2_2_8"),
    "Recharged Emerald Rebalanced": ("recharged_emerald_rebalanced", "recharged_emerald.txt", "RECHARGED_EMERALD_REBALANCED_V2_2_8"),
    "Lazarus": ("lazarus_srm", "default.txt", "LAZARUS"), "Emerald Imperium": ("imperium", "default.txt", "IMPERIUM"),
    "Heart and Soul": ("heart_and_soul", "rhh_splash.txt", "HEART_AND_SOUL"),
    "SoulGold 1.1.4": ("soulgold", "default.txt", "SOULGOLD"), "SoulGold 1.2": ("soulgold_v12", "default.txt", "SOULGOLD_V1_2"),
    "SoulGold 1.2 Alt 1": ("soulgold_v12b", "default.txt", "SOULGOLD_V1_2B"),
    "SoulGold 1.2 Alt 2": ("soulgold_v12c", "default.txt", "SOULGOLD_V1_2C"),
    "SoulGold 1.2.1": ("soulgold_v121", "default.txt", "SOULGOLD_V1_2_1"),
}
PARTY_STRIDE = 100


def conf_line(key):
    for line in open(os.path.join(HERE, "host_roms.conf"), encoding="utf-8"):
        f = line.rstrip("\n").split("|")
        if not line.startswith("#") and f[0] == key:
            return f
    sys.exit(f"{key} missing from host_roms.conf")


def conf(key):
    return os.path.expanduser(conf_line(key)[3])


def conf_rom(key):
    return os.path.expanduser(conf_line(key)[2])


h = lambda v: f"0x{v:X}"


def script(t):
    """The commands after the boot for target [t] (see targets())."""
    o = [f"peek32 {h(t['tm']['mainCallback2'])}" if t.get("tm") else "wait 1"]
    lock = t["fieldControlsLocked"]
    gmd = t["getMonData"]
    if t.get("tm"):
        tm = t["tm"]
        slot, presses = (0, BACK_OUT) if t["back"] else (t["slot"], TEACH)
        mon = t["party"] + slot * PARTY_STRIDE
        o += [f"poke16 {h(SCRATCH + 0x20)} 0", f"call {h(t['setMonData'])} {h(mon)} {MON_DATA_MOVE4 + t.get('monShift', 0)} {h(SCRATCH + 0x20)}",
              f"call {h(t['addBagItem'])} {HM05} 1",
              f"call {h(tm['lockControls'])}", f"call {h(tm['fadeScreen'])} 1 0", "wait 30", f"call {h(tm['cleanupOverworld'])}",
              f"poke16 {h(tm['specialVarItemId'])} {HM05}", f"poke32 {h(tm['itemUseCb'])} {h(tm['itemUseCbTmhm'] | 1)}",
              f"poke8 {h(tm['partyMenu'] + tm['partyMenuSlotOff'])} 0", f"call {h(tm['showPartyMenuForItemUse'])}",
              f"peek32 {h(tm['mainCallback2'])}", f"poke32 {h(tm['partyMenu'])} {h(tm['returnToField'] | 1)}", "wait 120",
              "shot tm_menu.ppm"]
        o += ["press DOWN", "wait 20"] * slot
        o += presses + ["shot tm_after.ppm"]
        o += [f"call {h(gmd)} {h(mon)} {m}" for m in range(MON_DATA_MOVE4 - 3 + t.get('monShift', 0), MON_DATA_MOVE4 + 1 + t.get('monShift', 0))]
        o += [f"peek32 {h(tm['mainCallback2'])}", f"call {h(lock)}"]
    f = t.get("flash")
    if not f:
        return "\n".join(o) + "\n"
    if t["cave"]:
        g, n, w, x, y = t["cave"]
        # the expansion asserts unless a waitstate follows the warp
        warp = [0x39, g, n, w, x & 255, x >> 8, y & 255, y >> 8] + ([0x27] if t.get("waitstate") else []) + [2]
        o += [f"poke8 {h(SCRATCH + i)} {h(b)}" for i, b in enumerate(warp)]
        o += [f"call {h(t['setupScript'])} {h(SCRATCH)}", "wait 500"]
    else:
        # No dark cave known on this hack's maps: the map counted as one (gMapHeader.cave), the lit flag clear as on any
        # map load. The light opening up then shows nothing, but the calls and the flag are the same.
        o += [f"poke8 {h(f['mapHeader'] + f.get('caveOff', 0x15))} 1"]
    o += [f"poke16 {h(SCRATCH + 0x20)} {FLASH}", f"call {h(t['setMonData'])} {h(t['party'])} {MON_DATA_MOVE4 + t.get('monShift', 0)} {h(SCRATCH + 0x20)}",
          f"call {h(t['getMonData'])} {h(t['party'])} {MON_DATA_MOVE4 + t.get('monShift', 0)}",
          f"call {h(lock)}", f"call {h(f['flagGet'])} {h(f['litFlag'])}", "shot flash_dark.ppm",
          *([f"call {h(f['badgeFn'] | 1)} 0"] if f.get("badgeFn") else []),
          f"poke8 {h(f['partyMenuSlotId'])} 0", f"call {h(f['setUpFieldMoveFlash'])}", f"peek32 {h(f['postMenuFieldCallback'])}",
          f"poke32 {h(f['menuFieldCallback'])} 0", f"poke32 {h(f['postMenuFieldCallback'])} 0",
          f"call {h(f['fieldCallbacks'][0] | 1)}", "wait 30", "shot flash_cutin.ppm", "wait 700",
          f"call {h(f['flagGet'])} {h(f['litFlag'])}", f"call {h(lock)}", f"call {h(f['setUpFieldMoveFlash'])}",
          "shot flash_glow.ppm", "hold DOWN 30", "shot flash_lit.ppm"]
    return "\n".join(o) + "\n"


def run(work, key, t):
    d = os.path.join(work, key)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    shutil.copy(t["path"], os.path.join(d, "rom.gba"))
    shutil.copy(t["save"], os.path.join(d, "rom.sav"))
    with open(os.path.join(d, "cmds.txt"), "w") as fh:
        pre, _, boot = t["boot"].rpartition("|")   # "wait 600|default.txt": a longer splash first
        fh.write((pre + "\n" if pre else "") + open(os.path.join(ROOT, "native-capture", "boot", boot)).read().rstrip("\n") + "\n" + t["after"]
                 + "wait 400\n" + script(t))
    r = subprocess.run(["docker", "run", "--rm", "-v", f"{work}:/work", "-w", f"/work/{key}", IMAGE, "-c",
                        "/work/mgba_dump rom.gba rom.sav < cmds.txt"], capture_output=True, text=True)
    log = r.stdout + r.stderr
    open(os.path.join(d, "log.txt"), "w").write(log)
    return log


def check(t, log):
    calls = re.findall(r"^call 0x[0-9A-Fa-f]+\(.*?\): (?:returned 0x([0-9a-fA-F]+)|(DID NOT RETURN))", log, re.M)
    if any(nr for _, nr in calls):
        return "a call didn't return"
    r = [int(v, 16) for v, _ in calls]
    peeks = [int(v, 16) for v in re.findall(r"^[0-9a-f]{8}: ([0-9a-f]{8})$", log, re.M)]
    problems, notes = [], []
    if t.get("tm"):
        tm = t["tm"]
        field, opened, back = peeks[0], peeks[1], peeks[2]
        # r: SetMonData (the 4th move cleared), AddBagItem, lock, fade, cleanup, show, 4 moves, lock.
        r = r[1:]
        moves, locked = r[5:9], r[9]
        if r[0] != 1:
            problems.append(f"AddBagItem {r[0]}")
        if opened & ~1 != tm["initPartyMenu"] & ~1:
            problems.append(f"callback2 {opened:#x} after the show, not CB2_InitPartyMenu")
        if back != field:
            problems.append(f"callback2 {back:#x} after, the field's is {field:#x}")
        if locked:
            problems.append("controls locked after the TM screen")
        if not t["back"] and FLASH not in moves:
            problems.append(f"FLASH not learned: moves {moves}")
        notes.append("TM backed out" if t["back"] else "TM taught")
        r = r[10:]
    f = t.get("flash")
    if f:
        # r: setupScript (the warp: none on a hack), SetMonData, GetMonData, lock, lit, setUp, cb, lit, lock, setUp.
        if not t["cave"]:
            r = [0] + r
        post = peeks[-1]
        if f.get("badgeFn"):
            notes.append(f"its badge rule says {'yes' if r[5] else 'no'}")
            r = r[:5] + r[6:]
        want = [(r[2] == FLASH, f"move {r[2]}"), (r[3] == 0, "controls locked in the cave"), (r[4] == 0, "already lit"),
                (r[5] == 1, f"SetUpFieldMove_Flash {r[5]}"), (post & ~1 == f["fieldCallbacks"][0] & ~1, f"post callback {post:#x}"),
                (r[7] == 1, "not lit after"), (r[9] == 0, f"SetUpFieldMove_Flash {r[9]} lit"),
                # Counted as a cave on a lit map, the light's task never lets go (retail FireRed does the same).
                (r[8] == 0 or not t["cave"], "controls locked after")]
        problems += [why for ok, why in want if not ok]
        notes.append("FLASH lit the cave" if t["cave"] else "FLASH lit a map counted as a cave")
        if t.get("badge"):
            notes.append(t["badge"])
    elif t.get("flashOff"):
        notes.append(t["flashOff"])
    return "; ".join(problems) if problems else "ok: " + ", ".join(notes)


def badge_rule(path, flash):
    """flashBadgeRule (Flash.kt) on the ROM file: (flash table or None, a note). A CFRU hack's own rule goes in as badgeFn."""
    if not flash.get("badgeCheck"):
        return flash, None
    rom = open(path, "rb").read()
    at = lambda a, n: rom[(a & ~1) - 0x08000000:][:n]
    u16 = lambda b, o: struct.unpack_from("<H", b, o)[0]
    check = flash["badgeCheck"]
    b = at(check, 16)
    if zlib.crc32(b) == flash["badgeCheckCrc"]:
        return flash, "retail badge rule"
    if b[4:14] == bytes([1, 0x20]) + bytes(8):
        return flash, "no badge needed"
    if u16(b, 0) == 0x4800 and u16(b, 2) == 0x4700:
        hook = struct.unpack_from("<I", b, 4)[0] & ~1
        h = at(hook, 18)
        hi, lo = u16(h, 2), u16(h, 4)
        off = ((hi & 0x7FF) << 12) | ((lo & 0x7FF) << 1)
        off = off - 0x800000 if off & 0x400000 else off
        target = lambda o: struct.unpack_from("<I", rom, ((hook + o + 4) & ~3) + (u16(h, o) & 0xFF) * 4 - 0x08000000)[0] & ~1
        if (u16(h, 0) == 0x1C20 and u16(h, 6) == 0x2800 and hi & 0xF800 == 0xF000 and lo & 0xF800 == 0xF800
                and u16(h, 10) & 0xFF00 == 0x4800 and target(10) == check + 0x3C and target(14) == check + 0x16):
            return dict(flash, badgeFn=hook + 6 + off), f"the hack's own badge rule at {hook + 6 + off:#x}"
    return None, "FLASH off: the party menu's badge check is unknown"


# Where retail's code loads gMapGroups from (a literal-pool word, kept in place by the hacks edited into it).
MAP_GROUPS_POOL = dict(FR0=0x0805524C, FR1=None, EM=0x08084AA4)
MAP_GROUPS = dict(EMERALD_LEGACY=0x084AF90C,   # a rebuild's own gMapGroups (its ELF); the expansion hacks' from their GUIDE records
                  LAZARUS=0x08FAF098, IMPERIUM=0x08FC2F74, HEART_AND_SOUL=0x092DF5A4, SOULGOLD=0x09587364,
                  SOULGOLD_V1_2=0x09592634, SOULGOLD_V1_2B=0x09592590, SOULGOLD_V1_2C=0x095C04C0, SOULGOLD_V1_2_1=0x095CC3A8)
CAVE_OFF = {b: 0x16 for b in ("SOULGOLD", "SOULGOLD_V1_2", "SOULGOLD_V1_2B", "SOULGOLD_V1_2C", "SOULGOLD_V1_2_1")}


def find_cave(path, base):
    """A dark map in the hack's own map table (gMapHeader.cave set, a warp to arrive by): (group, map, 0, 0, 0), or None."""
    rom = open(path, "rb").read()
    u32 = lambda a: struct.unpack_from("<I", rom, a - 0x08000000)[0]
    ok = lambda v: 0x08000000 <= v < 0x08000000 + len(rom) - 0x20
    pool = MAP_GROUPS_POOL.get(base)
    if not pool and base not in MAP_GROUPS:
        return None
    groups = MAP_GROUPS.get(base) or u32(pool)
    starts = []
    for g in range(64):
        v = u32(groups + 4 * g) if ok(groups + 4 * g) else 0
        if not ok(v):
            break
        starts.append(v)
    for g, gp in enumerate(starts):
        for n in range(200):
            if n and gp + 4 * n in starts:
                break
            hp = u32(gp + 4 * n)
            if not ok(hp) or not ok(u32(hp)) or not ok(u32(hp + 4)):
                break
            events = u32(hp + 4)
            if rom[hp - 0x08000000 + CAVE_OFF.get(base, 0x15)] == 1 and rom[events - 0x08000000 + 1] > 0:
                return (g, n, 0, 0, 0)
    return None


def targets():
    """Every game to check: the ports (field_items.json), then the hacks edited into retail's code."""
    out = {}
    ports = json.load(open(FIELD_ITEMS))
    for key, p in ports.items():
        if not p.get("flash"):
            continue
        fam = key[:3]
        boot, after = BOOTS[fam]
        own = key[:4] in OWN_SAVES
        out[key] = dict(p, setupScript=p["pc"]["setupScript"], save=conf(OWN_SAVES.get(key[:4], SAVES[fam])), boot=boot,
                        after=after, back=own, slot=TEACH_SLOT.get(fam, 0), cave=CAVES[fam])
    fr0 = dict(ports["BPRE0"], setupScript=ports["BPRE0"]["pc"]["setupScript"])
    legacy = dict(record("FIELD_ITEMS_EMERALD_LEGACY_PARTY"), fieldControlsLocked=record("FIELD_ITEMS_EMERALD_LEGACY")["fieldControlsLocked"],
                  party=0x020244EC, setupScript=0x0809A294)   # gPlayerParty and ScriptContext_SetupScript from its ELF
    bases = dict(FR0=(fr0, "FIRERED_REV0"), FR1=(REFS["BPRE1"]["a"], "FIRERED_REV1"), EM=(REFS["BPEE0"]["a"], "EMERALD"),
                 EMERALD_LEGACY=(legacy, "EMERALD_LEGACY"))
    # Recharged Emerald (FieldItemsRechargedEmeraldGen.kt's tables): no ScriptContext_SetupScript known, so no warp
    # (setupScript None: FLASH where the save stands, counted as a cave); its MON_DATA ids 9 past vanilla's.
    for build, (getmon, setmon, addbag, fcl, party) in RECHARGED.items():
        bases[build] = (dict(getMonData=getmon, setMonData=setmon, addBagItem=addbag, fieldControlsLocked=fcl, party=party,
                             setupScript=None, monShift=9), build)
    for game, (getmon, setmon, fcl, party, setup, shift) in EXPANSION.items():
        bases[game] = (dict(getMonData=getmon, setMonData=setmon, addBagItem=None, fieldControlsLocked=fcl, party=party,
                            setupScript=setup, monShift=shift), game)
    for name, (save_key, boot, base) in HACKS.items():
        rom, save = conf_rom(save_key), conf(save_key)
        if not os.path.isfile(rom):
            print(f"{name}: no ROM ({os.path.basename(rom)}), skipped")
            continue
        fields, rec = bases[base]
        tm = record("TM_" + rec) if base not in EXPANSION else None
        flash, badge = badge_rule(rom, record("FLASH_" + rec))
        if name in NO_TM:
            tm = None
        if name in NO_FLASH:
            flash, badge = None, "FLASH off: the cut-in never finishes"
        cave = None if fields["setupScript"] is None else find_cave(rom, base) if base != "FR1" else CAVES["BPR"]
        out[name] = dict({k: fields[k] for k in ("party", "setMonData", "getMonData", "addBagItem", "fieldControlsLocked",
                                                  "setupScript")},
                         path=rom, save=save, boot=boot, after=BOOTS["BPR" if base.startswith("FR") else "BPE"][1] if boot.endswith("default.txt") else "",
                         back=name not in TEACH_HACKS, slot=TEACH_HACKS.get(name, 0), cave=cave, tm=tm, flash=flash, badge=badge, flashOff=None if flash else badge,
                         monShift=fields.get("monShift", 0), waitstate=base in EXPANSION)
    return out


def sheet(work, keys):
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return
    shots = ("tm_menu", "tm_after", "flash_dark", "flash_cutin", "flash_glow")
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
    work = os.path.join(OUT, "runs")
    os.makedirs(work, exist_ok=True)
    subprocess.run(["docker", "run", "--rm", "-v", f"{ROOT}:/repo", "-v", f"{work}:/work", "-w", "/repo/native-capture", IMAGE,
                    "-c", "gcc -O2 -Wall -o /work/mgba_dump mgba_dump.c -I/usr/include -L/usr/lib/aarch64-linux-gnu -lmgba -lm"],
                   check=True)
    rows, failed, keys = [], 0, []
    for key, t in targets().items():
        if only and key not in only:
            continue
        res = check(t, run(work, key, t))
        failed += not res.startswith("ok")
        keys.append(key)
        rows.append(f"| {key} | {os.path.basename(t['path'])} | {res} |")
        print(f"{key:24} {res}", flush=True)
    sheet(work, keys)
    with open(os.path.join(OUT, "tm_flash.md"), "w") as fh:
        fh.write("# TM screen and FLASH, headless\n\n| key | ROM | result |\n|---|---|---|\n" + "\n".join(rows) + "\n")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
