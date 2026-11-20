#!/usr/bin/env python3
"""One-off: moves the Gen 3 NativeConfigs out of NativeReader.kt into gamedata/ (phase 1 of
the shared game data plan, gamedata/README.md).

Reads shared/.../companion/data/NativeReader.kt and writes:

- gamedata/schema/gen3_config.toml   every NativeConfig field: Kotlin type, default, its comments;
- gamedata/gen3/{retail,hacks}/*.toml one file per game family, each config with its comments;
- shared/src/androidUnitTest/.../LegacyNativeConfigs.kt  the old Kotlin blocks, renamed LEGACY_NATIVE_*, for
  GameDataMigrationTest until phase 1's gate passes (then that file and this script go);

and, with --apply, takes the config blocks (and the comments above them) out of NativeReader.kt.
scripts/gen_gamedata.py then writes NativeConfigsGen.kt from the TOML.

    python3 scripts/convert_native_configs.py [--apply]
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DATA = os.path.join(ROOT, "shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data")
READER = os.path.join(DATA, "NativeReader.kt")
GAMEDATA = os.path.join(ROOT, "gamedata")
LEGACY = os.path.join(ROOT, "shared/src/androidUnitTest/kotlin/com/pokedaisy/app/companion/data/LegacyNativeConfigs.kt")

# Which family file each config goes to (first match wins).
FAMILIES = [
    (r"FIRERED_REV[01]", "retail/firered"),
    (r"LEAFGREEN_REV[01]", "retail/leafgreen"),
    (r"EMERALD(_RETAIL|_ES|_DE|_FR|_IT|_JA)?", "retail/emerald"),
    (r"RUBY|SAPPHIRE", "retail/ruby_sapphire"),
    (r"UNBOUND.*", "hacks/unbound"),
    (r"GAIA.*", "hacks/gaia"),
    (r"RADICAL_RED.*", "hacks/radical_red"),
    (r"ODYSSEY", "hacks/odyssey"),
    (r"HEART_AND_SOUL", "hacks/heart_and_soul"),
    (r"LAZARUS", "hacks/lazarus"),
    (r"SOULGOLD.*", "hacks/soulgold"),
    (r"ROWE", "hacks/rowe"),
    (r"EMERALD_ROGUE", "hacks/emerald_rogue"),
    (r"EMERALD_SEAGLASS", "hacks/seaglass"),
    (r"CELIA", "hacks/celia"),
    (r"TMT2", "hacks/tmt2"),
    (r"EMERALD_KAIZO", "hacks/emerald_kaizo"),
    (r"EMERALD_LEGACY", "hacks/emerald_legacy"),
    (r"GLAZED", "hacks/glazed"),
    (r"IMPERIUM", "hacks/imperium"),
    (r"QUETZAL.*", "hacks/quetzal"),
    (r"AMETHYST.*", "hacks/amethyst"),
    (r"ORANGE_ISLANDS", "hacks/orange_islands"),
    (r"ULTRA_VIOLET", "hacks/ultra_violet"),
    (r"ROCKET_EDITION", "hacks/rocket_edition"),
    (r"ASH_GRAY", "hacks/ash_gray"),
    (r"GHOST_GREY.*", "hacks/ghost_grey"),
    (r"ELITE_REDUX.*", "hacks/elite_redux"),
    (r"TEAM_ROCKET", "hacks/team_rocket"),
    (r"SWORD_SHIELD", "hacks/sword_shield"),
    (r"GS_CHRONICLES", "hacks/gs_chronicles"),
    (r"RECHARGED_YELLOW.*", "hacks/recharged_yellow"),
    (r"RECHARGED_EMERALD.*", "hacks/recharged_emerald"),
    (r"MARIOMON.*", "hacks/mariomon"),
]

# Configs whose main notes sit above a helper declared just before them (their bag pocket order):
# those notes move into the config, and the helper gets a pointer to it.
HELPER_NOTES = {
    "LAZARUS": "LAZARUS_BAG_POCKET_ORDER",
    "SOULGOLD": "SOULGOLD_BAG_POCKET_ORDER",
    "ROWE": "ROWE_BAG_POCKET_ORDER",
    "EMERALD_ROGUE": "ROGUE_BAG_POCKET_ORDER",
    "TMT2": "TMT2_BAG_POCKET_ORDER",
    "IMPERIUM": "IMPERIUM_BAG_POCKET_ORDER",
    "RECHARGED_YELLOW_V1_8_0": "RECHARGED_YELLOW_BAG_POCKET_ORDER",
    "MARIOMON_V1_5_1": "MARIOMON_BAG_POCKET_ORDER",
}

# Kotlin type -> schema type. Anything else is a "ref": the name of a Kotlin val until phase 3.
TYPES = {"Long": "long", "Int": "int", "Boolean": "bool", "Char": "char", "String": "string",
         "ItemDescTable": "item_descs", "IntRange": "int_range"}
ITEM_DESC_FIELDS = ["base", "stride", "descOff", "idOff", "first", "count", "next"]
PRESETS = {"vanillaItems": "vanilla", "japaneseItems": "japanese"}

NUM = re.compile(r"-?(0x[0-9A-Fa-f_]+|[0-9][0-9_]*)L?")
IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


# --- Kotlin scanning ---

def skip_string(s, j):
    q = s[j]
    j += 1
    while s[j] != q:
        j += 2 if s[j] == "\\" else 1
    return j + 1


def match_paren(s, i):
    """s[i] opens a bracket; the index just past its match."""
    depth = 0
    j = i
    while j < len(s):
        if s.startswith("//", j):
            j = s.index("\n", j)
            continue
        if s.startswith("/*", j):
            j = s.index("*/", j) + 2
            continue
        c = s[j]
        if c in "\"'":
            j = skip_string(s, j)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return j + 1
        j += 1
    raise ValueError("unbalanced")


def split_args(body):
    """Top-level comma-separated pieces of an argument list, comments kept."""
    out, cur, depth, j = [], "", 0, 0
    while j < len(body):
        if body.startswith("//", j):
            k = body.find("\n", j)
            k = len(body) if k < 0 else k
            cur += body[j:k]
            j = k
            continue
        c = body[j]
        if c in "\"'":
            k = skip_string(body, j)
            cur += body[j:k]
            j = k
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += c
        j += 1
    out.append(cur)
    return out


def code_and_comment(line):
    """A line's code and its // comment (outside strings)."""
    j = 0
    while j < len(line):
        if line[j] in "\"'":
            j = skip_string(line, j)
            continue
        if line.startswith("//", j):
            return line[:j].rstrip(), line[j + 2:].strip()
        j += 1
    return line.rstrip(), None


def comment_text(line):
    """The words of a // or KDoc comment line."""
    t = line.strip()
    for p in ("/**", "//", "/*", "*/", "*"):
        if t.startswith(p):
            t = t[len(p):]
            break
    if t.endswith("*/"):
        t = t[:-2]
    return t.strip()


def parse_args(body):
    """[(name, rhs code, [leading comments], trailing comment)], [comments after the last arg]."""
    chunks = split_args(body)
    args, end = [], []
    for ci, chunk in enumerate(chunks):
        lines = chunk.split("\n")
        # A comment on the chunk's first line, before any code, sits after the previous arg's comma.
        first_code, first_comment = code_and_comment(lines[0])
        if not first_code.strip() and first_comment is not None and len(lines) > 1 and args:
            name, rhs, lead, trail = args[-1]
            args[-1] = (name, rhs, lead, first_comment if trail is None else trail + " " + first_comment)
            lines = lines[1:]
        lead, code_lines, inner = [], [], []
        for line in lines:
            code, comment = code_and_comment(line)
            if code.strip():
                code_lines.append(code.strip())
                if comment is not None:
                    inner.append(comment)
            elif comment is not None:
                (inner if code_lines else lead).append(comment)
        if not code_lines:
            end.extend(lead + inner)
            continue
        code = " ".join(code_lines)
        m = re.match(r"(\w+)\s*=\s*(.*)$", code, re.S)
        if not m:
            sys.exit(f"convert: positional argument {code!r}")
        trail = None
        if len(code_lines) == 1 and inner:
            trail, inner = " ".join(inner), []
        args.append((m.group(1), m.group(2).strip(), lead + inner, trail))
    return args, end


def leading_comments(lines, idx):
    """The comment lines right above lines[idx] (blank lines between paragraphs kept), and where they start."""
    j = idx
    in_block = False
    while j > 0:
        t = lines[j - 1].strip()
        if in_block:
            j -= 1
            if t.startswith("/*"):
                in_block = False
            continue
        if t == "" or t.startswith("//"):
            j -= 1
            continue
        if t.endswith("*/"):
            in_block = not t.startswith("/*")
            j -= 1
            continue
        break
    while j < idx and lines[j].strip() == "":
        j += 1
    out = [comment_text(l) if l.strip() else "" for l in lines[j:idx]]
    return out, j


# --- Kotlin values -> TOML values ---

def toml_int(lit):
    lit = lit.rstrip("L")
    if lit.startswith("-0x"):
        return str(-int(lit[1:].replace("_", ""), 16))
    return lit


def toml_str(s):
    if "'" not in s and "\n" not in s:
        return "'" + s + "'"
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def eval_numeric(expr):
    """A sum of number literals, as an int; None if it isn't one."""
    if not re.fullmatch(r"[0-9A-Fa-fxL_+\- ]+", expr) or not re.search(r"[+\-]", expr.lstrip("-")):
        return None
    total = 0
    for sign, lit in re.findall(r"([+\-]?)\s*(0x[0-9A-Fa-f_]+|[0-9][0-9_]*)L?", expr):
        v = int(lit.replace("_", ""), 0)
        total += -v if sign == "-" else v
    return total


def call_parts(rhs):
    m = re.fullmatch(r"(\w+)\((.*)\)", rhs, re.S)
    if not m:
        return None, None
    return m.group(1), [a.strip() for a in split_args(m.group(2)) if a.strip()]


def item_descs(rhs):
    """vanillaItems(...) / japaneseItems(...) / ItemDescTable(...) as an inline table; None if not one."""
    fn, args = call_parts(rhs)
    if fn in PRESETS:
        names = ["base", "count"]
        out = [("preset", toml_str(PRESETS[fn]))]
    elif fn == "ItemDescTable":
        names = ITEM_DESC_FIELDS
        out = []
    else:
        return None
    for i, a in enumerate(args):
        m = re.match(r"(\w+)\s*=\s*(.*)$", a, re.S)
        name, val = (m.group(1), m.group(2)) if m else (names[i], a)
        if name == "next":
            v = item_descs(val)
        elif NUM.fullmatch(val):
            v = toml_int(val)
        elif IDENT.fullmatch(val):
            v = "{ ref = " + toml_str(val) + " }"
        else:
            v = None
        if v is None:
            return None
        out.append((name, v))
    return "{ " + ", ".join(f"{k} = {v}" for k, v in out) + " }"


def to_toml(field, rhs, ftype, configs):
    """(TOML value, a note for a trailing comment or None)."""
    rhs = " ".join(rhs.split())
    if rhs == "null":
        return toml_str("none"), None
    if NUM.fullmatch(rhs):
        return toml_int(rhs), None
    if rhs in ("true", "false"):
        return rhs, None
    if re.fullmatch(r"'.'", rhs):
        return toml_str(rhs[1]), None
    if re.fullmatch(r'"[^"\\]*"', rhs):
        return toml_str(rhs[1:-1]), None
    if ftype == "item_descs":
        v = item_descs(rhs)
        if v:
            return v, None
    if ftype == "int_range":
        m = re.fullmatch(r"(\d+)\s*\.\.\s*(\d+)", rhs)
        if m:
            return f"[{m.group(1)}, {m.group(2)}]", None
    if IDENT.fullmatch(rhs):
        if ftype == "ref":
            return toml_str(rhs), None
        return "{ ref = " + toml_str(rhs) + " }", None
    m = re.fullmatch(r"NATIVE_(\w+)\.(\w+)", rhs)
    if m and m.group(1) in configs:
        key = m.group(1).lower()
        if m.group(2) == field:
            return "{ from = " + toml_str(key) + " }", None
        return "{ from = " + toml_str(key) + ", field = " + toml_str(m.group(2)) + " }", None
    if ftype in ("long", "int"):
        v = eval_numeric(rhs)
        if v is not None:
            lit = f"0x{v:08X}" if v > 0xFFFFFF else str(v)
            return lit, "= " + rhs.replace("L", "")
    return "{ kotlin = " + toml_str(rhs) + " }", None


# --- the data class -> schema ---

def parse_schema(src):
    start = src.index("data class NativeConfig(")
    end = match_paren(src, src.index("(", start))
    fields, pending = [], []
    for line in src[src.index("(", start) + 1:end - 1].split("\n"):
        t = line.strip()
        if t.startswith("//"):
            pending.append(comment_text(t))
            continue
        m = re.match(r"val (\w+): ([^=]+?)(?:\s*=\s*(.*?))?,?$", t)
        if m:
            ktype = m.group(2).strip()
            base_type = ktype.rstrip("?").split("<")[0]
            fields.append({
                "name": m.group(1), "kotlin": ktype, "type": TYPES.get(base_type, "ref"),
                "nullable": ktype.endswith("?"), "default": m.group(3), "comments": pending,
            })
            pending = []
    return fields


def write_schema(fields):
    out = [
        "# Every NativeConfig field (NativeReader.kt's data class), checked against it by",
        "# scripts/gen_gamedata.py. type: long | int | bool | char | string | item_descs | int_range | ref",
        "# (a ref names a Kotlin val until phase 3 moves those records into gamedata/ too).",
        "# A field without a default must be set by every config with no base.",
        "",
        "version = 1",
    ]
    for f in fields:
        out.append("")
        out += [("# " + c).rstrip() for c in f["comments"]]
        out.append(f"[field.{f['name']}]")
        out.append(f"kotlin = {toml_str(f['kotlin'])}")
        out.append(f"type = {toml_str(f['type'])}")
        if f["default"] is not None:
            v, _ = to_toml(f["name"], f["default"], f["type"], set())
            out.append(f"default = {v}")
    path = os.path.join(GAMEDATA, "schema", "gen3_config.toml")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out) + "\n")


# --- the configs ---

CONFIG = re.compile(r"^(private )?val NATIVE_(\w+) = (NativeConfig|NATIVE_(\w+)\.copy)\(", re.M)


def parse_configs(src):
    lines = src.split("\n")
    line_at = [0]
    for line in lines:
        line_at.append(line_at[-1] + len(line) + 1)
    names = {m.group(2) for m in CONFIG.finditer(src)}
    configs = []
    for m in CONFIG.finditer(src):
        line_no = src.count("\n", 0, m.start())
        open_at = m.end() - 1
        close_at = match_paren(src, open_at)
        end_line = src.count("\n", 0, close_at)
        rest, after = code_and_comment(lines[end_line][close_at - line_at[end_line]:])
        if rest.strip():
            sys.exit(f"convert: code after NATIVE_{m.group(2)}'s closing paren")
        lead, lead_start = leading_comments(lines, line_no)
        moved = None
        if m.group(2) in HELPER_NOTES:
            helper = HELPER_NOTES[m.group(2)]
            at = next(i for i, l in enumerate(lines) if re.match(rf"val {helper}\b", l))
            notes, start = leading_comments(lines, at)
            if len(notes) < 5:
                sys.exit(f"convert: no notes above {helper}")
            moved = (start, at - 1)
            lead = notes + [""] + lead
        args, end = parse_args(src[open_at + 1:close_at - 1])
        configs.append({
            "name": m.group(2), "private": bool(m.group(1)), "base": m.group(4),
            "lead": lead, "args": args, "end": end + ([after] if after else []), "moved": moved,
            "span": (lead_start, end_line), "kotlin": "\n".join(lines[lead_start:end_line + 1]),
        })
    return configs, names


def family(name):
    for pattern, fam in FAMILIES:
        if re.fullmatch(pattern, name):
            return fam
    sys.exit(f"convert: no family file for NATIVE_{name}")


def write_configs(configs, names, fields):
    types = {f["name"]: f["type"] for f in fields}
    files = {}
    for c in configs:
        files.setdefault(family(c["name"]), []).append(c)
    for fam, cs in files.items():
        out = [f"# Gen 3 configs ({fam.split('/')[0]}). The format and the commands: gamedata/README.md."]
        for c in cs:
            out.append("")
            out += [("# " + l).rstrip() if l else "#" for l in c["lead"]]
            out.append(f"[config.{c['name'].lower()}]")
            if c["base"]:
                out.append(f"base = {toml_str(c['base'].lower())}")
            if c["private"]:
                out.append("private = true")
            for name, rhs, lead, trail in c["args"]:
                if name not in types:
                    sys.exit(f"convert: NATIVE_{c['name']}.{name} isn't a NativeConfig field")
                value, note = to_toml(name, rhs, types[name], names)
                out += [("# " + l).rstrip() for l in lead]
                comment = f"{trail} ({note})" if note and trail else note or trail
                out.append(f"{name} = {value}" + (f"  # {comment}" if comment else ""))
            if c["end"]:
                out += [("# " + l).rstrip() for l in c["end"]]
        path = os.path.join(GAMEDATA, "gen3", fam + ".toml")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write("\n".join(out) + "\n")
    return files


def write_legacy(configs, names):
    rename = re.compile(r"\bNATIVE_(" + "|".join(sorted(names, key=len, reverse=True)) + r")\b")
    out = [
        "package com.pokedaisy.app.companion.data",
        "",
        "// The NativeConfigs as NativeReader.kt wrote them before gamedata/ (phase 1), renamed",
        "// LEGACY_NATIVE_*: GameDataMigrationTest checks the generated NativeConfigsGen.kt equals",
        "// them. Written by scripts/convert_native_configs.py; goes once that gate has passed.",
        "",
    ]
    for c in configs:
        out.append(rename.sub(r"LEGACY_NATIVE_\1", c["kotlin"]))
        out.append("")
    os.makedirs(os.path.dirname(LEGACY), exist_ok=True)
    with open(LEGACY, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out))


def strip_reader(src, configs):
    lines = src.split("\n")
    drop, pointers = set(), {}
    for c in configs:
        drop.update(range(c["span"][0], c["span"][1] + 1))
        if c["moved"]:
            start, end = c["moved"]
            drop.update(range(start, end + 1))
            pointers[start] = f"// The game's notes: [config.{c['name'].lower()}] in gamedata/gen3/{family(c['name'])}.toml."
    kept = []
    for i, l in enumerate(lines):
        if i in pointers:
            kept.append(pointers[i])
        if i not in drop:
            kept.append(l)
    text = re.sub(r"\n{3,}", "\n\n", "\n".join(kept))
    with open(READER, "w", encoding="utf-8") as fh:
        fh.write(text)


def main():
    src = open(READER, encoding="utf-8").read()
    fields = parse_schema(src)
    configs, names = parse_configs(src)
    write_schema(fields)
    files = write_configs(configs, names, fields)
    write_legacy(configs, names)
    if "--apply" in sys.argv:
        strip_reader(src, configs)
    print(f"convert: {len(fields)} fields, {len(configs)} configs in {len(files)} files")


if __name__ == "__main__":
    main()
