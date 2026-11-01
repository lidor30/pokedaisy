#!/usr/bin/env python3
"""Co-op lock (pk_coop_lock.c) and story events: retail English Emerald's story moments, trainer wins, addresses and
struct offsets, from pret/pokeemerald built byte for byte as retail (agbcc). Writes

- app/src/main/cpp/pk_coop_lock_emerald.h: the hook's addresses / CRC / struct offsets / script bytes and the story
  table (`COOP_LK_STORY`) as C data (everything `#define` or `static const`: the header may be included twice), and
  `COOP_LK_CODE[]`: scripts/coop_lock_emerald.s (the story hook's Thumb code) assembled with binutils at
  `COOP_LK_CODE_ADDR` = 0x08FFE000, every value above handed to it as `.set COOP_LK_<name>`, then 0xFF up to
  `COOP_LK_TABLE_ADDR` (0x08FFE800) and the story table as bytes (8 an event: script u32, id u16, kind u8, 0), with the head
  patch's words (`COOP_LK_HEAD_*`) and the `COOP_LK_ASM_*` mailbox layout pk_coop_lock.c checks;
- app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopStoryGen.kt: COOP_STORY_EVENTS, COOP_TRAINER_WINS and
  the ROM / RAM constants Kotlin needs.

    scripts/gen_coop_story.py <pokeemerald dir> [--kotlin-dir DIR] [--header FILE]

Needs the finished build (pokeemerald.gba + .elf), python3, cc and the build's tools/agbcc: same image and docker line as
gen_coop_emerald.py (which this reuses for the ELF / probe readers):

    MSYS_NO_PATHCONV=1 docker run --rm -v "D:/Code/pokedaisy/decomps:/decomps" -v "<worktree>:/repo" pokedaisy-decomp \\
        -c "python3 /repo/scripts/gen_coop_story.py /decomps/pokeemerald"

The rules (each a function below; docs/DEVELOPMENT.md's "Co-op lock and story events (Emerald)" repeats them):

- COORD: every `coord_events` entry of type `trigger` in data/maps/*/map.json whose `var` is not 0 (a VAR_TEMP_* one only when it battles a story trainer).
- FRAME: every `map_script_2` entry of each map's MAP_SCRIPT_ON_FRAME_TABLE (a VAR_TEMP_* var only when the script battles a story trainer).
- OBJECT: every object event whose script (followed through goto / call / goto_if / call_if / case / a trainerbattle's
  post-battle script to labels of the same map's scripts.inc) contains a `trainerbattle*` naming a story trainer (class
  LEADER, ELITE_FOUR, CHAMPION, AQUA_/MAGMA_ LEADER / ADMIN, or a name TRAINER_BRENDAN_* / MAY_* / WALLY_*), or a
  `setwildbattle` followed by `dowildbattle`. A story trainer whose object sees the player (trainer_type not NONE)
  is left out and listed: a sighted start goes through the generic approach script.
- Duplicates (one script pointer twice, the hook finds an event by its script) keep the first id; ids are 1..n in sorted
  (map name, kind, x, y) order, so a rerun gives the same table.
- Script addresses come from the ELF's symbols; a label not found fails the generator.
- Wins: the FLAG_BADGE0n_GET / FLAG_DEFEATED_* a story trainer's battle sets after its trainerbattle (its post-battle
  script, followed). Gym leaders are reported by whether their TM can still be got by talking to them (stdout).
- The ROM block check: 0x00FFE000-0x00FFEFFF (file offset) of the built ROM is all 0xFF; the CRC32 of
  ScriptContext_SetupScript's first 32 bytes is printed.
"""
import argparse
import collections
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import tempfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gen_coop_emerald as gce  # noqa: E402

REPO = os.path.join(HERE, "..")
OUT_H = os.path.join(REPO, "app", "src", "main", "cpp", "pk_coop_lock_emerald.h")
OUT_KT_DIR = os.path.join(REPO, "app", "src", "main", "kotlin", "com", "pokedaisy", "app", "companion", "data", "coop")

ROM_BASE = 0x08000000
BLOCK_OFF, BLOCK_END = 0x00FFE000, 0x00FFF000
CODE_ADDR = ROM_BASE + BLOCK_OFF         # the lock's block: our Thumb code, then the story table
TABLE_ADDR = CODE_ADDR + 0x800           # COOP_LK_STORY as bytes (8 an event), after the code
ASM = os.path.join(HERE, "coop_lock_emerald.s")
# ScriptContext_SetupScript's first four instructions, which the head patch displaces (checked against the ROM):
# push {r4, r5, lr}; adds r5, r0, #0; ldr r4, [pc, #32]; ldr r1, [pc, #36]
HEAD_INSNS = [0xB530, 0x1C05, 0x4C08, 0x4909]
KINDS = ["COORD", "OBJECT", "FRAME"]
STORY_CLASSES = {"TRAINER_CLASS_" + c for c in
                 ["LEADER", "ELITE_FOUR", "CHAMPION", "AQUA_LEADER", "AQUA_ADMIN", "MAGMA_LEADER", "MAGMA_ADMIN"]}
STORY_NAME = re.compile(r"TRAINER_(BRENDAN|MAY|WALLY)_")
GYM_LEADERS = ["TRAINER_ROXANNE_1", "TRAINER_BRAWLY_1", "TRAINER_WATTSON_1", "TRAINER_FLANNERY_1", "TRAINER_NORMAN_1",
               "TRAINER_WINONA_1", "TRAINER_TATE_AND_LIZA_1", "TRAINER_JUAN_1"]
WIN_FLAG = re.compile(r"FLAG_(BADGE0[1-8]_GET|DEFEATED_\w+)")

# Functions / RAM the hook needs, (define name, ELF symbol).
FUNCTIONS = [("ScriptContext_SetupScript", "ScriptContext_SetupScript"),
             ("ArePlayerFieldControlsLocked", "ArePlayerFieldControlsLocked"),
             ("ScriptContext_IsEnabled", "ScriptContext_IsEnabled")]
RAM = ["sGlobalScriptContext", "sGlobalScriptContextStatus", "sLockFieldControls", "gObjectEvents", "gPlayerAvatar", "gSaveBlock1Ptr", "gSpecialVar_LastTalked",
       "gSelectedObjectEvent", "gSpecialVar_Facing"]
OFFSETS = [
    ("OBJ_SIZE", "sizeof(struct ObjectEvent)"),
    ("OBJ_CURRENT_X", "offsetof(struct ObjectEvent, currentCoords) + offsetof(struct Coords16, x)"),
    ("OBJ_CURRENT_Y", "offsetof(struct ObjectEvent, currentCoords) + offsetof(struct Coords16, y)"),
    # facingDirection is the low nibble of the u16 bit-field right before `range` (offsetof can't take a bit-field).
    ("OBJ_FACING", "offsetof(struct ObjectEvent, range) - 1"),
    ("AVATAR_OBJ_ID", "offsetof(struct PlayerAvatar, objectEventId)"),
    ("SB1_LOCATION_GROUP", "offsetof(struct SaveBlock1, location) + offsetof(struct WarpData, mapGroup)"),
    ("SB1_LOCATION_NUM", "offsetof(struct SaveBlock1, location) + offsetof(struct WarpData, mapNum)"),
    ("SB1_FLAGS", "offsetof(struct SaveBlock1, flags)"),
]
CONSTANTS = ["MOVEMENT_ACTION_WALK_NORMAL_DOWN", "MOVEMENT_ACTION_WALK_NORMAL_UP", "MOVEMENT_ACTION_WALK_NORMAL_LEFT",
             "MOVEMENT_ACTION_WALK_NORMAL_RIGHT", "MOVEMENT_ACTION_STEP_END", "LOCALID_PLAYER", "TRAINER_FLAGS_START",
             "TRAINERS_COUNT", "SAVE_STATUS_OK"]
PROBE_EXTRA = ["constants/opponents.h", "constants/flags.h", "constants/event_object_movement.h",
               "constants/event_objects.h", "save.h", "global.fieldmap.h", "constants/map_groups.h"]
SCRIPT_OPS = [("applymovement", "SCR_OP_APPLYMOVEMENT"), ("waitmovement", "SCR_OP_WAITMOVEMENT"),
              ("goto", "SCR_OP_GOTO"), ("releaseall", "SCR_OP_RELEASEALL"), ("end", "SCR_OP_END")]
MOVEMENTS = [("walk_down", "MOVEMENT_ACTION_WALK_NORMAL_DOWN"), ("walk_up", "MOVEMENT_ACTION_WALK_NORMAL_UP"),
             ("walk_left", "MOVEMENT_ACTION_WALK_NORMAL_LEFT"), ("walk_right", "MOVEMENT_ACTION_WALK_NORMAL_RIGHT")]

die = lambda msg: sys.exit("gen_coop_story: " + msg)  # noqa: E731


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


# ---- decomp data ---------------------------------------------------------------------------------------------------

def read_map_ids(decomp):
    """MAP_X -> (group, num) from include/constants/map_groups.h: `MAP_X = (num | (group << 8))`."""
    out = {}
    for m in re.finditer(r"(MAP_\w+)\s*=\s*\((\d+)\s*\|\s*\((\d+)\s*<<\s*8\)\)", read(
            os.path.join(decomp, "include", "constants", "map_groups.h"))):
        out[m.group(1)] = (int(m.group(3)), int(m.group(2)))
    if len(out) < 400:
        die("map_groups.h gave only %d maps" % len(out))
    return out


def read_trainers(decomp):
    """trainer constant -> class constant, from src/data/trainers.h."""
    out = {}
    for m in re.finditer(r"\[(TRAINER_\w+)\]\s*=\s*\{\s*\.trainerClass\s*=\s*(\w+)",
                         read(os.path.join(decomp, "src", "data", "trainers.h"))):
        out[m.group(1)] = m.group(2)
    if len(out) < 800:
        die("trainers.h gave only %d trainers" % len(out))
    return out


def is_story_trainer(name, classes):
    return classes.get(name) in STORY_CLASSES or bool(STORY_NAME.match(name))


def split_args(text):
    return [a.strip() for a in text.split(",") if a.strip()]


class Scripts:
    """One map's scripts.inc: label -> statements [(op, [args])]."""

    def __init__(self, path):
        self.labels = {}
        self.frame_tables = []
        cur = None
        for raw in read(path).splitlines():
            line = raw.split("@")[0].strip()
            if not line:
                continue
            m = re.match(r"^([A-Za-z_]\w*)::?$", line)
            if m:
                cur = self.labels.setdefault(m.group(1), [])
                continue
            if cur is None:
                continue
            m = re.match(r"^(\w+)\s*(.*)$", line)
            if m and not line.startswith("."):
                cur.append((m.group(1), split_args(m.group(2))))
            elif line.startswith(".") and not line.startswith(".2byte") and not line.startswith(".byte"):
                pass
            if m and m.group(1) == "map_script" and m.group(2).startswith("MAP_SCRIPT_ON_FRAME_TABLE"):
                self.frame_tables.append(split_args(m.group(2))[1])


def edges(stmts, with_continue):
    """[(target label, condition args or None, is_continue)] out of a label's statements."""
    out = []
    switch = "switch"
    for op, args in stmts:
        if op == "switch" and args:
            switch = args[0]
        if op in ("goto", "call") and args:
            out.append((args[0], None, False))
        elif op.startswith(("goto_if", "call_if")) and args:
            out.append((args[-1], args[:-1], False))
        elif op == "case" and len(args) >= 2:
            out.append((args[-1], [switch, args[0]], False))
        elif op == "trainerbattle_single" and len(args) >= 4 and args[3] != "FALSE":
            if with_continue:
                out.append((args[3], None, True))
        elif op == "trainerbattle_double" and len(args) >= 5 and args[4] != "FALSE":
            if with_continue:
                out.append((args[4], None, True))
    return out


def reach(scripts, root, with_continue=True):
    """Labels reachable from root in one map's scripts.inc (root first)."""
    seen, todo = [], [root]
    while todo:
        lab = todo.pop(0)
        if lab in seen or lab not in scripts.labels:
            continue
        seen.append(lab)
        todo += [t for t, _, _ in edges(scripts.labels[lab], with_continue)]
    return seen


def battles(scripts, root, classes):
    """(story trainers [in order], other trainers, scripted wild battle, continue scripts {trainer: [labels]})."""
    story, other, wild, cont = [], [], False, collections.defaultdict(list)
    for lab in reach(scripts, root):
        stmts = scripts.labels[lab]
        seen_set = False
        for i, (op, args) in enumerate(stmts):
            if op == "setwildbattle":
                seen_set = True
            elif op == "dowildbattle" and seen_set:
                wild = True
            tr = None
            if op in ("trainerbattle_single", "trainerbattle_double", "trainerbattle_rematch",
                      "trainerbattle_rematch_double", "trainerbattle_no_intro") and args:
                tr = args[0]
                if not op.startswith("trainerbattle_rematch"):
                    cont[tr].append((lab, i + 1))   # what the script does after the battle
                if op == "trainerbattle_single" and len(args) >= 4 and args[3] != "FALSE":
                    cont[tr].append((args[3], 0))
                if op == "trainerbattle_double" and len(args) >= 5 and args[4] != "FALSE":
                    cont[tr].append((args[4], 0))
            elif op == "trainerbattle" and len(args) >= 2:
                tr = args[1]
                cont[tr].append((lab, i + 1))
            if tr and tr.startswith("TRAINER_"):
                if is_story_trainer(tr, classes):
                    if tr not in story:
                        story.append(tr)
                elif tr not in other:
                    other.append(tr)
    return story, other, wild, cont


def win_flags(scripts, conts):
    """FLAG_BADGE0n_GET / FLAG_DEFEATED_* a trainer's battle leads to: from each (label, first statement) point on,
    that script's own statements and every script it goes on to (goto / call / case, same map)."""
    out = []
    for lab, start in conts:
        stmts = scripts.labels[lab][start:]
        statements = list(stmts)
        for t, _, _ in edges(stmts, True):
            for l in reach(scripts, t):
                statements += scripts.labels[l]
        for op, args in statements:
            if op == "setflag" and args and WIN_FLAG.fullmatch(args[0]) and args[0] not in out:
                out.append(args[0])
    return out


def tm_report(scripts, root):
    """('collectable' | 'not collectable' | 'no TM', detail): can the gym leader's TM be got by talking to them later?
    The talk path is the object's script without the post-battle script (only run after a won battle); it is
    collectable when a giveitem ITEM_TM* is reachable through edges conditioned on nothing but the TM's own
    FLAG_RECEIVED_TM_* receipt flag and the rematch check (VAR_RESULT)."""
    def gives(lab):
        return [a[0] for op, a in scripts.labels.get(lab, []) if op == "giveitem" and a and a[0].startswith("ITEM_TM")]

    def search(allowed_only):
        seen = {root}
        todo = [(root, [])]
        while todo:
            lab, conds = todo.pop(0)
            if gives(lab):
                return gives(lab)[0], conds
            for t, cond, _ in edges(scripts.labels.get(lab, []), with_continue=not allowed_only):
                c = conds
                if cond:
                    c = conds + [" ".join(cond)]
                    if allowed_only and not all(x.startswith(("FLAG_RECEIVED_TM_", "VAR_RESULT")) for x in [cond[0]]):
                        continue
                if t not in seen and t in scripts.labels:
                    seen.add(t)
                    todo.append((t, c))
        return None

    got = search(True)
    if got:
        return "collectable", "%s via %s" % (got[0], ", ".join(got[1]) or "no conditions")
    got = search(False)
    if got:
        return "not collectable", "%s behind %s" % (got[0], ", ".join(got[1]) or "the post-battle script")
    return "no TM", "no giveitem ITEM_TM* reachable"


# ---- events --------------------------------------------------------------------------------------------------------

def collect_events(decomp, map_ids, classes):
    """-> (events [dict], sighted [(map, trainer, label)], stats). Unsorted, undeduplicated."""
    events, sighted = [], []
    maps_dir = os.path.join(decomp, "data", "maps")
    for name in sorted(os.listdir(maps_dir)):
        mj = os.path.join(maps_dir, name, "map.json")
        if not os.path.isfile(mj):
            continue
        data = json.loads(read(mj))
        if data["id"] not in map_ids:
            continue
        group, num = map_ids[data["id"]]
        sp = os.path.join(maps_dir, name, "scripts.inc")
        scripts = Scripts(sp) if os.path.isfile(sp) else Scripts(os.devnull)

        def ev(kind, x, y, script, trainers, wins):
            events.append(dict(map=name, kind=kind, group=group, num=num, x=x, y=y, script=script,
                               trainers=trainers, wins=wins))

        for c in data.get("coord_events", []):
            var = c.get("var", "0")
            if c.get("type") != "trigger" or var == "0" or c["script"].startswith("0x"):
                continue
            story, _, _, cont = battles(scripts, c["script"], classes)
            if var.startswith("VAR_TEMP_") and not story:
                continue   # a VAR_TEMP scene is kept only when it battles a story trainer
            ev("COORD", c["x"], c["y"], c["script"], story, {t: win_flags(scripts, cont[t]) for t in story})
        for table in scripts.frame_tables:
            for op, args in scripts.labels.get(table, []):
                if op == "map_script_2" and len(args) == 3:
                    story, _, _, cont = battles(scripts, args[2], classes)
                    if args[0].startswith("VAR_TEMP_") and not story:
                        continue   # a VAR_TEMP scene is kept only when it battles a story trainer
                    ev("FRAME", -1, -1, args[2], story, {t: win_flags(scripts, cont[t]) for t in story})
        for o in data.get("object_events", []):
            script = o.get("script", "0x0")
            if script.startswith("0x") or script not in scripts.labels:
                continue
            story, _, wild, cont = battles(scripts, script, classes)
            if not story and not wild:
                continue
            if story and o.get("trainer_type", "TRAINER_TYPE_NONE") != "TRAINER_TYPE_NONE":
                for t in story:
                    sighted.append((name, t, script))
                continue
            e = dict(map=name, kind="OBJECT", group=group, num=num, x=o["x"], y=o["y"], script=script,
                     trainers=story, wins={t: win_flags(scripts, cont[t]) for t in story})
            e["tm"] = {t: tm_report(scripts, script) for t in story if classes.get(t) == "TRAINER_CLASS_LEADER"}
            events.append(e)
    return events, sighted


def number_events(events):
    """Sorted (map, kind, x, y, label); one script pointer once; ids 1..n."""
    events.sort(key=lambda e: (e["map"], KINDS.index(e["kind"]), e["x"], e["y"], e["script"]))
    out, seen, dropped = [], set(), []
    for e in events:
        if e["script"] in seen:
            dropped.append(e)
            continue
        seen.add(e["script"])
        e["id"] = len(out) + 1
        out.append(e)
    return out, dropped


# ---- output --------------------------------------------------------------------------------------------------------

def kt_flag_list(values):
    return "listOf(" + ", ".join("0x%X" % v for v in values) + ")"


def write_kotlin(path, events, wins, k, addr):
    L = ["// Generated by scripts/gen_coop_story.py from pret/pokeemerald built byte for byte as retail English Emerald",
         "// (BPEE, SHA-1 %s) - don't edit by hand." % gce.RETAIL_SHA1,
         "package com.pokedaisy.app.companion.data.coop", "",
         "val COOP_STORY_EVENTS: List<StoryEvent> = listOf("]
    for e in events:
        L.append('    StoryEvent(%d, StoryKind.%s, %d, %d, %d, %d, 0x%08XL, "%s", %s),' % (
            e["id"], e["kind"], e["group"], e["num"], e["x"], e["y"], e["addr"], e["script"],
            "listOf(" + ", ".join(str(t) for t in e["trainer_ids"]) + ")" if e["trainer_ids"] else "emptyList()"))
    L += [")", "", "val COOP_TRAINER_WINS: List<TrainerWin> = listOf("]
    for tid, flags in wins:
        L.append("    TrainerWin(%d, %s)," % (tid, kt_flag_list(flags)))
    L += [")", "",
          "const val COOP_TRAINERS_COUNT: Int = %d" % k["TRAINERS_COUNT"],
          "const val COOP_TRAINER_FLAGS_START = 0x%X" % k["TRAINER_FLAGS_START"],
          "const val COOP_SAVEBLOCK1_PTR: Long = 0x%08XL" % addr["gSaveBlock1Ptr"],
          "const val COOP_SB1_FLAGS_OFF = 0x%X" % k["SB1_FLAGS"],
          "const val COOP_PLAYER_PARTY_COUNT = 0x%08XL" % addr["gPlayerPartyCount"],
          "const val COOP_TRY_SAVING_DATA: Long = 0x%08XL" % addr["TrySavingData"],
          "const val COOP_SAVE_STATUS_OK = %d" % k["SAVE_STATUS_OK"], ""]
    with open(path, "w", newline="\n") as f:
        f.write("\n".join(L))


def write_header(path, events, addr, crc, k, ops, movements, lk):
    L = ["// Generated by scripts/gen_coop_story.py from pret/pokeemerald built byte for byte as retail English",
         "// Emerald (BPEE, SHA-1 %s) - don't edit by hand." % gce.RETAIL_SHA1,
         "// Functions are even addresses (no Thumb bit); COOP_LK_CRC_<f> = CRC32 of the %d ROM bytes at <f>." % gce.CRC_BYTES,
         "#pragma once", "", "#include <stdint.h>", "", "// Functions (ROM code) and their code CRCs."]
    w = 44
    for name, elf in FUNCTIONS:
        L.append("#define %-*s 0x%08Xu" % (w, "COOP_LK_" + name, addr[elf]))
    for name, elf in FUNCTIONS:
        L.append("#define %-*s 0x%08Xu" % (w, "COOP_LK_CRC_" + name, crc[elf]))
    L += ["", "// RAM."]
    for name in RAM:
        L.append("#define %-*s 0x%08Xu" % (w, "COOP_LK_" + name, addr[name]))
    L += ["", "// Struct offsets (agbcc, the decomp's headers)."]
    for name, expr in OFFSETS:
        L.append("#define %-*s %d   // %s" % (w, "COOP_LK_" + name, k[name], expr))
    L += ["", "// Script command bytes (data/script_cmd_table.inc) and walk movement bytes (the re-entry / step-back scripts)."]
    for name, const in SCRIPT_OPS:
        L.append("#define %-*s 0x%02Xu   // %s" % (w, "COOP_LK_OP_" + name, ops[const], const))
    for name, const in MOVEMENTS:
        L.append("#define %-*s 0x%02Xu   // %s" % (w, "COOP_LK_MOVE_" + name, k[const], const))
    L.append("#define %-*s 0x%02Xu   // MOVEMENT_ACTION_STEP_END" % (w, "COOP_LK_MOVE_step_end", k["MOVEMENT_ACTION_STEP_END"]))
    L.append("#define %-*s 0x%02Xu   // LOCALID_PLAYER" % (w, "COOP_LK_LOCALID_PLAYER", k["LOCALID_PLAYER"]))
    L += ["", "// The story events: script (what ScriptContext_SetupScript receives), id (1..n), kind (0 COORD, 1 OBJECT,",
          "// 2 FRAME), map group / number, tile (FRAME: -1, -1)."]
    L.append("#define COOP_LK_STORY_COUNT %d" % len(events))
    L.append("static const struct { uint32_t script; uint16_t id; uint8_t kind, group, num; int16_t x, y; } "
             "COOP_LK_STORY[COOP_LK_STORY_COUNT] = {")
    for e in events:
        L.append("    { 0x%08Xu, %d, %d, %d, %d, %d, %d },   // %s %s" % (
            e["addr"], e["id"], KINDS.index(e["kind"]), e["group"], e["num"], e["x"], e["y"], e["kind"], e["script"]))
    L += ["};", ""]
    L += ["// Our Thumb code (scripts/coop_lock_emerald.s) and the story table, one block at COOP_LK_CODE_ADDR over the file's",
          "// empty 0xFF bytes (the code, 0xFF up to COOP_LK_TABLE_ADDR, then the table's %d bytes: script u32, id u16, kind u8, 0)." %
          (8 * len(events)),
          "// COOP_LK_ENTRY_<NAME> = its routines (even: add the Thumb bit to point at one).",
          "#define %-*s 0x%08Xu" % (w, "COOP_LK_CODE_ADDR", CODE_ADDR),
          "#define %-*s 0x%08Xu" % (w, "COOP_LK_TABLE_ADDR", TABLE_ADDR)]
    for name, a in lk["entries"]:
        L.append("#define %-*s 0x%08Xu" % (w, "COOP_LK_ENTRY_" + name, a))
    L += ["", "// The head patch: ScriptContext_SetupScript's first 8 bytes (two words, little-endian) as the file has them, and ours",
          "// (`ldr r3, [pc, #0]; bx r3`, then the literal: COOP_LK_ENTRY_SETUP | 1); the function goes on at COOP_LK_RESUME.",
          "#define %-*s 0x%08Xu" % (w, "COOP_LK_HEAD_ORIG0", lk["orig0"]),
          "#define %-*s 0x%08Xu" % (w, "COOP_LK_HEAD_ORIG1", lk["orig1"]),
          "#define %-*s 0x%08Xu" % (w, "COOP_LK_HEAD_NEW0", lk["new0"]),
          "#define %-*s 0x%08Xu" % (w, "COOP_LK_RESUME", lk["resume"]),
          "#define %-*s 0x%08Xu" % (w, "COOP_LK_POOL_R4", lk["pool_r4"]),
          "#define %-*s 0x%08Xu" % (w, "COOP_LK_POOL_R1", lk["pool_r1"]),
          "", "// The mailbox layout and values the code was written for (pk_coop_lock.c checks them against its struct)."]
    for name, value in lk["asm_syms"]:
        L.append(("#define %-*s 0x%08Xu" if value > 0xFFFF else "#define %-*s %d") % (w, "COOP_LK_ASM_" + name, value))
    block = bytearray(lk["code"]) + b"\xff" * (TABLE_ADDR - CODE_ADDR - len(lk["code"]))
    for e in events:
        block += struct.pack("<IHBB", e["addr"], e["id"], KINDS.index(e["kind"]), 0)
    L += ["", "static const uint8_t COOP_LK_CODE[%d] = {" % len(block)]
    for i in range(0, len(block), 16):
        L.append("    " + " ".join("0x%02X," % b for b in block[i:i + 16]))
    L += ["};", ""]
    with open(path, "w", newline="\n") as f:
        f.write("\n".join(L))


def assemble(equ):
    """coop_lock_emerald.s at CODE_ADDR with `.set COOP_LK_<name>, value` for every value in front: (code bytes,
    [(ENTRY name, address)], [(ASM name, value)]) - its lock_entry_<NAME> labels and its absolute lock_* symbols."""
    with open(ASM) as f:
        src = f.read()
    with tempfile.TemporaryDirectory() as tmp:
        sfile, o, elf, bin_ = (os.path.join(tmp, n) for n in ("lock.s", "lock.o", "lock.elf", "lock.bin"))
        with open(sfile, "w") as f:
            f.write("".join("    .set COOP_LK_%s, 0x%X\n" % (n, v) for n, v in equ.items()) + src)
        subprocess.run(["arm-none-eabi-as", "-mcpu=arm7tdmi", "-mthumb", "-o", o, sfile], check=True)
        subprocess.run(["arm-none-eabi-ld", "-nostdlib", "-Ttext=0x%08X" % CODE_ADDR, "-e", "0x%08X" % CODE_ADDR,
                        "-o", elf, o], check=True)
        subprocess.run(["arm-none-eabi-objcopy", "-O", "binary", "-j", ".text", elf, bin_], check=True)
        with open(bin_, "rb") as f:
            code = f.read()
        syms = gce.read_symbols(elf)
    if not 0 < len(code) <= TABLE_ADDR - CODE_ADDR:
        die("our code is %d bytes: it must fit in 0x%X" % (len(code), TABLE_ADDR - CODE_ADDR))
    entries = []
    for name, found in sorted(syms.items()):
        m = re.fullmatch(r"lock_entry_([A-Z0-9_]+)", name)
        if m:
            a = found[0][0] & ~1
            if not CODE_ADDR <= a < CODE_ADDR + len(code) or found[0][1] != "T":
                die("%s at 0x%08X isn't a global in our code" % (name, a))
            entries.append((m.group(1), a))
    if not entries:
        die("no lock_entry_ labels in %s" % ASM)
    asm_syms = [(name[len("lock_"):].upper(), found[0][0]) for name, found in sorted(syms.items())
                if name.startswith("lock_") and not name.startswith("lock_entry_") and found[0][1] == "A"]
    return code, entries, asm_syms


def read_script_ops(decomp):
    t = read(os.path.join(decomp, "data", "script_cmd_table.inc"))
    ops = {}
    for m in re.finditer(r"script_cmd_table_entry\s+(SCR_OP_\w+)\s+\w+\s+@\s+0x([0-9a-f]+)", t):
        ops[m.group(1)] = int(m.group(2), 16)
    # the comment is the table index; check it against the entries' order
    order = re.findall(r"script_cmd_table_entry\s+(SCR_OP_\w+)\s", t)
    for i, n in enumerate(order):
        if ops.get(n) != i:
            die("%s: table index %d, comment 0x%x" % (n, i, ops.get(n, -1)))
    return ops


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("decomp")
    ap.add_argument("--kotlin-dir", default=OUT_KT_DIR)
    ap.add_argument("--header", default=OUT_H)
    args = ap.parse_args()
    decomp = os.path.abspath(args.decomp)

    with open(os.path.join(decomp, "pokeemerald.gba"), "rb") as f:
        rom = f.read()
    sha1 = hashlib.sha1(rom).hexdigest()
    if sha1 != gce.RETAIL_SHA1:
        die("pokeemerald.gba is %s, not retail's %s" % (sha1, gce.RETAIL_SHA1))
    if rom[BLOCK_OFF:BLOCK_END] != b"\xff" * (BLOCK_END - BLOCK_OFF):
        die("the ROM isn't 0xFF over file offsets 0x%X-0x%X (the lock's code block)" % (BLOCK_OFF, BLOCK_END - 1))
    syms = gce.read_symbols(os.path.join(decomp, "pokeemerald.elf"))

    addr = {}
    for _, elf in FUNCTIONS:
        addr[elf] = gce.symbol(syms, elf, "Tt") & ~1
    addr["TrySavingData"] = gce.symbol(syms, "TrySavingData", "Tt") & ~1
    for n in RAM + ["gPlayerPartyCount"]:
        addr[n] = gce.symbol(syms, n, "BbDd")
        if not (0x02000000 <= addr[n] < 0x02040000 or 0x03000000 <= addr[n] < 0x03008000):
            die("%s at 0x%08X isn't in EWRAM / IWRAM" % (n, addr[n]))
    crc = {elf: zlib.crc32(rom[addr[elf] - ROM_BASE:addr[elf] - ROM_BASE + gce.CRC_BYTES]) & 0xFFFFFFFF
           for _, elf in FUNCTIONS}

    map_ids = read_map_ids(decomp)
    classes = read_trainers(decomp)
    ops = read_script_ops(decomp)
    raw, sighted = collect_events(decomp, map_ids, classes)
    events, dropped = number_events(raw)

    # Script addresses.
    for e in events:
        found = {a for a, _ in syms.get(e["script"], [])}
        if len(found) != 1:
            die("script label %s (%s): %d symbols in the ELF" % (e["script"], e["map"], len(found)))
        e["addr"] = found.pop()
        if not ROM_BASE <= e["addr"] < ROM_BASE + len(rom):
            die("%s at 0x%08X isn't in the ROM" % (e["script"], e["addr"]))

    # Numeric ids of trainers and flags (one agbcc probe).
    trainer_names = sorted({t for e in events for t in e["trainers"]})
    flag_names = sorted({f for e in events for fl in e["wins"].values() for f in fl})
    gce.PROBE_HEADERS += [h for h in PROBE_EXTRA if h not in gce.PROBE_HEADERS]
    exprs = [x for _, x in OFFSETS] + CONSTANTS + trainer_names + flag_names
    vals = gce.probe_values(decomp, exprs)
    probe = dict(zip([n for n, _ in OFFSETS] + CONSTANTS + trainer_names + flag_names, vals))
    k = probe
    for e in events:
        e["trainer_ids"] = [probe[t] for t in e["trainers"]]
    wins_by_trainer = {}
    for e in events:
        for t, fl in e["wins"].items():
            cur = wins_by_trainer.setdefault(t, [])
            cur += [f for f in fl if f not in cur]
    wins = sorted((probe[t], [probe[f] for f in fl]) for t, fl in wins_by_trainer.items() if fl)

    # Step 1: the generator's own checks.
    n_by = collections.Counter(e["kind"] for e in events)
    assert n_by["COORD"] >= 8, "fewer than 8 COORD events"
    assert n_by["FRAME"] >= 8, "fewer than 8 FRAME events"
    in_objects = {t for e in events if e["kind"] == "OBJECT" for t in e["trainers"]}
    for g in GYM_LEADERS:
        assert g in in_objects, "%s is in no OBJECT event" % g
    assert sorted(wins_by_trainer.get("TRAINER_ROXANNE_1", [])) == sorted(
        ["FLAG_BADGE01_GET", "FLAG_DEFEATED_RUSTBORO_GYM"]), "ROXANNE's wins: %s" % wins_by_trainer.get("TRAINER_ROXANNE_1")
    assert any(e["kind"] == "COORD" and e["script"] == "Route101_EventScript_StartBirchRescue" for e in events), \
        "Route 101's Birch scene is not a COORD event"
    assert any("TRAINER_WALLACE" in e["trainers"] for e in events), "TRAINER_WALLACE is in no event"
    assert [e["id"] for e in events] == list(range(1, len(events) + 1)), "ids aren't 1..n"
    assert len({e["addr"] for e in events}) == len(events), "a script pointer twice"
    assert len(events) < 0xFFFF
    for g, n in [(e["group"], e["num"]) for e in events]:
        assert 0 <= g < 128 and 0 <= n < 256
    assert k["TRAINERS_COUNT"] == 855 and k["TRAINER_FLAGS_START"] == 0x500

    # The head patch: the first four instructions must be exactly the ones the stub replays; the stub reads the two
    # literal-pool words (`ldr r4, [pc, #32]` at +4, `ldr r1, [pc, #36]` at +6) from the function's own pool.
    fn = addr["ScriptContext_SetupScript"]
    off = fn - ROM_BASE
    halfwords = list(struct.unpack("<4H", rom[off:off + 8]))
    if halfwords != HEAD_INSNS:
        die("ScriptContext_SetupScript starts %s, not %s" % (["%04X" % h for h in halfwords],
                                                               ["%04X" % h for h in HEAD_INSNS]))
    pool_r4 = ((fn + 4 + 4) & ~3) + 32
    pool_r1 = ((fn + 6 + 4) & ~3) + 36
    word = lambda a: struct.unpack("<I", rom[a - ROM_BASE:a - ROM_BASE + 4])[0]  # noqa: E731
    assert word(pool_r4) == addr["sGlobalScriptContext"], "the pool word for r4 isn't sGlobalScriptContext"
    assert 0x08000000 <= word(pool_r1) < 0x0A000000, "the pool word for r1 isn't a ROM address (gScriptCmdTable)"
    equ = {}
    for name, elf in FUNCTIONS:
        equ[name] = addr[elf]
        equ["CRC_" + name] = crc[elf]
    for name in RAM:
        equ[name] = addr[name]
    for name, _ in OFFSETS:
        equ[name] = k[name]
    for name, const in SCRIPT_OPS:
        equ["OP_" + name] = ops[const]
    for name, const in MOVEMENTS:
        equ["MOVE_" + name] = k[const]
    equ["MOVE_step_end"] = k["MOVEMENT_ACTION_STEP_END"]
    equ["LOCALID_PLAYER"] = k["LOCALID_PLAYER"]
    equ["STORY_COUNT"] = len(events)
    equ["TABLE_ADDR"] = TABLE_ADDR
    equ["RESUME"] = fn + 8
    equ["POOL_R4"] = pool_r4
    equ["POOL_R1"] = pool_r1
    code, entries, asm_syms = assemble(equ)
    if rom[BLOCK_OFF:BLOCK_END] != b"\xff" * (BLOCK_END - BLOCK_OFF):
        die("the ROM isn't empty (0xFF) where the lock's block goes")
    assert dict(entries)["SETUP"] & 1 == 0
    lk = {"code": code, "entries": entries, "asm_syms": asm_syms, "new0": 0x4B00 | (0x4718 << 16),  # ldr r3, [pc, #0]; bx r3
          "orig0": word(fn), "orig1": word(fn + 4), "resume": fn + 8, "pool_r4": pool_r4, "pool_r1": pool_r1}
    write_header(args.header, events, addr, crc, k, ops, MOVEMENTS, lk)
    os.makedirs(args.kotlin_dir, exist_ok=True)
    write_kotlin(os.path.join(args.kotlin_dir, "CoopStoryGen.kt"), events, wins, k, addr)

    print("wrote %s and %s" % (os.path.normpath(args.header), os.path.normpath(os.path.join(args.kotlin_dir, "CoopStoryGen.kt"))))
    print("ScriptContext_SetupScript 0x%08X, first 32 bytes CRC32 0x%08X; ROM 0x%X-0x%X all 0xFF" % (
        addr["ScriptContext_SetupScript"], crc["ScriptContext_SetupScript"], BLOCK_OFF, BLOCK_END - 1))
    print("\nGym leader TMs (can the TM be got by talking to the leader later?):")
    for e in events:
        for t, (verdict, detail) in e.get("tm", {}).items():
            print("  %-26s %-16s %s" % (t, verdict, detail))
    print("\nSighted story trainers left out (%d):" % len(sighted))
    for m, t, s in sighted:
        print("  %-28s %-32s %s" % (m, t, s))
    print("\nDuplicate scripts dropped (%d):" % len(dropped))
    for e in dropped:
        print("  %-8s %-28s (%d, %d) %s" % (e["kind"], e["map"], e["x"], e["y"], e["script"]))
    print("\nStory events with trainers:")
    for e in events:
        if e["trainers"]:
            print("  #%-3d %-6s %-26s %s: %s" % (e["id"], e["kind"], e["map"], e["script"], ", ".join(e["trainers"])))
    print("\nwrote %d events (%d COORD, %d OBJECT, %d FRAME), %d trainer wins with flags, %d of them OBJECT with "
          "trainers, %d scripted wild battles" % (
              len(events), n_by["COORD"], n_by["OBJECT"], n_by["FRAME"], len(wins),
              sum(1 for e in events if e["kind"] == "OBJECT" and e["trainers"]),
              sum(1 for e in events if e["kind"] == "OBJECT" and not e["trainers"])))


if __name__ == "__main__":
    main()
