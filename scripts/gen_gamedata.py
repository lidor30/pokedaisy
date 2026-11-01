#!/usr/bin/env python3
"""Writes the per-game code every platform builds from gamedata/ (see gamedata/README.md).

gamedata/ is where every platform's per-game data comes from; this is the only thing that
reads it. It checks every file against the schemas in gamedata/schema/ (and each schema
against the Kotlin class it describes), resolves `base` / `from`, and writes:
  - the app's NativeConfigsGen.kt: one Kotlin val per config, comments included;
  - the app's GameDataRecordsGen.kt: the records and lists in gamedata/gen3/records/;
  - each target in gamedata/targets/: 3ds.toml -> the 3DS / Linux companion's
    3ds/core/pd_configs_gen.h (its struct pd_config per config).

    python3 scripts/gen_gamedata.py           # write
    python3 scripts/gen_gamedata.py --check   # exit 1 if the written file is stale (CI)
    python3 scripts/gen_gamedata.py --new-schema <name> <File.kt> <Class>
                                              # start a schema from a Kotlin data class
"""
import glob
import os
import re
import sys
import tomllib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
GAMEDATA = os.path.join(ROOT, "gamedata")
SCHEMAS = os.path.join(GAMEDATA, "schema")
DATA = os.path.join(ROOT, "app/src/main/kotlin/com/pokedaisy/app/companion/data")
KOTLIN_OUT = os.path.join(DATA, "NativeConfigsGen.kt")
RECORDS_OUT = os.path.join(DATA, "GameDataRecordsGen.kt")
RECORDS = os.path.join(GAMEDATA, "gen3", "records")
TARGETS = os.path.join(GAMEDATA, "targets")

SCHEMA_VERSION = 1
CONFIG_SCHEMA = "gen3_config"
GROUP_ORDER = ["retail", "ports", "hacks"]
RETAIL_ORDER = ["firered", "leafgreen", "emerald", "ruby_sapphire"]
META_KEYS = {"base", "private", "lazy", "port"}
TYPES = {"long", "int", "bool", "char", "string", "item_descs", "int_range", "long_list", "int_list", "int_set",
         "long_set", "int_map", "ref"}
KOTLIN_TYPES = {"Long": "long", "Int": "int", "Boolean": "bool", "Char": "char", "String": "string",
                "ItemDescTable": "item_descs", "IntRange": "int_range"}
ITEM_DESC_FIELDS = {"preset": "string", "base": "long", "stride": "int", "descOff": "int", "idOff": "int",
                    "first": "int", "count": "int", "next": "item_descs"}
PRESETS = {"vanilla": "vanillaItems", "japanese": "japaneseItems"}
NONE = "none"  # a nullable field's null


class GameDataError(Exception):
    pass


def fail(where, msg):
    raise GameDataError(f"{where}: {msg}")


# --- a small TOML reader that keeps what tomllib drops: comments and how numbers were written ---

class RawInt(int):
    """An int that remembers its literal (hex stays hex in the Kotlin)."""

    def __new__(cls, value, raw):
        o = super().__new__(cls, value)
        o.raw = raw
        return o


VALUE_TOKEN = re.compile(r"""\s*(?:(?P<num>[+-]?(?:0x[0-9A-Fa-f_]+|[0-9][0-9_]*))
                         |(?P<bool>true|false)(?![\w])
                         |(?P<lit>'[^'\n]*')
                         |(?P<str>"(?:[^"\\\n]|\\.)*")
                         |(?P<open>[\[{]))""", re.X)


def parse_value(text, pos, where):
    m = VALUE_TOKEN.match(text, pos)
    if not m:
        fail(where, f"can't read a value at {text[pos:pos + 30]!r}")
    if m.group("num"):
        raw = m.group("num")
        return RawInt(int(raw.replace("_", ""), 0), raw), m.end()
    if m.group("bool"):
        return m.group("bool") == "true", m.end()
    if m.group("lit"):
        return m.group("lit")[1:-1], m.end()
    if m.group("str"):
        return tomllib.loads("v = " + m.group("str"))["v"], m.end()
    pos = m.end()
    if m.group("open") == "[":
        items = []
        while True:
            pos = skip_ws(text, pos)
            if text[pos] == "]":
                return items, pos + 1
            v, pos = parse_value(text, pos, where)
            items.append(v)
            pos = skip_ws(text, pos)
            if text[pos] == ",":
                pos += 1
    table = {}
    while True:
        pos = skip_ws(text, pos)
        if text[pos] == "}":
            return table, pos + 1
        k = re.compile(r"([A-Za-z0-9_-]+)\s*=").match(text, pos)
        if not k:
            fail(where, f"bad inline table near {text[pos:pos + 30]!r}")
        v, pos = parse_value(text, k.end(), where)
        table[k.group(1)] = v
        pos = skip_ws(text, pos)
        if text[pos] == ",":
            pos += 1


def skip_ws(text, pos):
    while pos < len(text) and text[pos] in " \t":
        pos += 1
    return pos


def read_toml(path):
    """[(table name, {lead, entries: [(key, value, lead, trail, line)], end, line})], checked against tomllib."""
    rel = os.path.relpath(path, ROOT)
    # tomllib is the authority on the syntax; this reader must agree with it (checked at the end).
    try:
        with open(path, "rb") as fh:
            official = tomllib.load(fh)
    except tomllib.TOMLDecodeError as e:
        fail(rel, f"not valid TOML: {e}")
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().split("\n")
    tables = [{"name": "", "lead": [], "entries": [], "end": [], "line": 0}]
    groups = [[]]  # comment groups since the last key / header, split at blank lines

    def flush_end():
        tables[-1]["end"] += [c for g in groups for c in g]

    for n, line in enumerate(lines, 1):
        t = line.strip()
        where = f"{rel}:{n}"
        if not t:
            if groups[-1]:
                groups.append([])
            continue
        if t.startswith("#"):
            groups[-1].append(t[1:][1:] if t[1:2] == " " else t[1:])
            continue
        h = re.fullmatch(r"\[([A-Za-z0-9_.-]+)\]", t)
        if h:
            lead = groups.pop() if groups[-1] else []  # the comments right above a header are its own
            flush_end()
            tables.append({"name": h.group(1), "lead": lead, "entries": [], "end": [], "line": n})
            groups = [[]]
            continue
        k = re.match(r"([A-Za-z0-9_-]+)\s*=", t)
        if not k:
            fail(where, f"can't read {t!r}")
        value, pos = parse_value(t, k.end(), where)
        rest = t[pos:].strip()
        if rest and not rest.startswith("#"):
            fail(where, f"unexpected {rest!r} after the value")
        lead = []
        for g in groups:
            if g:
                lead += ([""] if lead else []) + g
        tables[-1]["entries"].append((k.group(1), value, lead, (rest[1:].strip() or None) if rest else None, n))
        groups = [[]]
    flush_end()
    mine = {}
    for tab in tables:
        node = mine
        for part in filter(None, tab["name"].split(".")):
            node = node.setdefault(part, {})
        for key, value, *_ in tab["entries"]:
            node[key] = value
    if official != mine:
        fail(rel, "this file uses TOML the generator doesn't read (keep to one `key = value` a line)")
    return tables


# --- Kotlin data classes ---

def skip_string(s, j):
    q = s[j]
    j += 1
    while s[j] != q:
        j += 2 if s[j] == "\\" else 1
    return j + 1


def match_paren(s, i):
    """s[i] opens a bracket; the index just past its match (comments and strings skipped)."""
    depth, j = 0, i
    while j < len(s):
        if s.startswith("//", j):
            j = s.index("\n", j)
            continue
        if s.startswith("/*", j):
            j = s.index("*/", j) + 2
            continue
        if s[j] in "\"'":
            j = skip_string(s, j)
            continue
        if s[j] in "([{":
            depth += 1
        elif s[j] in ")]}":
            depth -= 1
            if depth == 0:
                return j + 1
        j += 1
    raise ValueError("unbalanced")


def kotlin_class_fields(kt_file, cls):
    """[{name, kotlin, default, comments}] of a data class's constructor."""
    src = open(os.path.join(DATA, kt_file), encoding="utf-8").read()
    m = re.search(rf"^data class {cls}\(", src, re.M)
    if not m:
        raise GameDataError(f"no `data class {cls}(` in {kt_file}")
    body = src[m.end():match_paren(src, m.end() - 1) - 1]
    body = re.sub(r",[ \t]*val ", ",\nval ", body)  # one line, several fields (data class TrainerMonLayout(...))
    fields, comments, in_doc = [], [], False
    for line in body.split("\n"):
        t = line.strip()
        if in_doc or t.startswith("/*"):
            in_doc = not t.endswith("*/")
            text = re.sub(r"^/\*\*?|\*/$", "", t).strip()
            text = text[1:].strip() if text.startswith("*") else text
            if text:
                comments.append(text)
            continue
        if t.startswith("//"):
            comments.append(t[2:].strip())
            continue
        f = re.match(r"val (\w+): ([^=]+?)(?:\s*=\s*(.*?))?,?(?:\s*//\s*(.*))?$", t)
        if f:
            fields.append({"name": f.group(1), "kotlin": f.group(2).strip(), "default": f.group(3),
                           "comments": comments + ([f.group(4)] if f.group(4) else [])})
            comments = []
    return fields


def schema_type(kotlin):
    return KOTLIN_TYPES.get(kotlin.rstrip("?").split("<")[0], "ref")


def toml_default(kotlin_value, ftype):
    v = kotlin_value.strip()
    if ftype == "ref" and re.fullmatch(r"[A-Za-z_][\w.]*", v) and v != "null":
        return "'" + v + "'"
    if v == "null":
        return f"'{NONE}'"
    if re.fullmatch(r"-?(0x[0-9A-Fa-f_]+|[0-9][0-9_]*)L?", v):
        return v.rstrip("L")
    if v in ("true", "false"):
        return v
    if re.fullmatch(r"'.'", v) or re.fullmatch(r'"[^"\\\']*"', v):
        return "'" + v[1:-1] + "'"
    return "{ kotlin = '" + v.replace("'", "\\'") + "' }" if "'" not in v else '{ kotlin = "' + v + '" }'


def new_schema(name, kt_file, cls):
    out = [f"# {cls}'s fields ({kt_file}), checked against it by scripts/gen_gamedata.py.",
           "# type: long | int | bool | char | string | item_descs | int_range | long_list | int_list | int_set |",
           "# long_set | int_map | ref (a Kotlin val's name);",
           "# record names the schema of a value a config may spell out as a sub-table.",
           "", f"version = {SCHEMA_VERSION}", f"kotlin_class = '{cls}'", f"kotlin_file = '{kt_file}'"]
    for f in kotlin_class_fields(kt_file, cls):
        out.append("")
        out += [("# " + c).rstrip() for c in f["comments"]]
        out += [f"[field.{f['name']}]", f"kotlin = '{f['kotlin']}'", f"type = '{schema_type(f['kotlin'])}'"]
        if f["default"] is not None:
            out.append(f"default = {toml_default(f['default'], schema_type(f['kotlin']))}")
    path = os.path.join(SCHEMAS, name + ".toml")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out) + "\n")
    print(f"gen_gamedata: wrote {os.path.relpath(path, ROOT)}")


# --- schemas ---

_schemas = {}


def load_schema(name):
    """A schema's fields: {name: {kotlin, type, nullable, required, record?}}, with its records loaded too."""
    if name in _schemas:
        return _schemas[name]
    path = os.path.join(SCHEMAS, name + ".toml")
    rel = os.path.relpath(path, ROOT)
    if not os.path.exists(path):
        raise GameDataError(f"no schema {rel}")
    tables = read_toml(path)
    top = dict((k, v) for k, v, *_ in tables[0]["entries"])
    if top.get("version") != SCHEMA_VERSION:
        fail(rel, f"schema version {top.get('version')}, this generator reads {SCHEMA_VERSION}")
    fields = {}
    for tab in tables[1:]:
        if not tab["name"].startswith("field."):
            fail(f"{rel}:{tab['line']}", f"unexpected table [{tab['name']}]")
        f = dict((k, v) for k, v, *_ in tab["entries"])
        if f.get("type") not in TYPES:
            fail(f"{rel}:{tab['line']}", f"type {f.get('type')!r} isn't one of {sorted(TYPES)}")
        f["nullable"] = f["kotlin"].endswith("?")
        f["required"] = "default" not in f
        fields[tab["name"][len("field."):]] = f
    schema = {"name": name, "class": top["kotlin_class"], "fields": fields}
    _schemas[name] = schema
    try:
        kotlin = {f["name"]: f["kotlin"] for f in kotlin_class_fields(top["kotlin_file"], top["kotlin_class"])}
    except GameDataError as e:
        fail(rel, str(e))
    for f in kotlin.keys() - fields.keys():
        fail(rel, f"{top['kotlin_class']} has `{f}` but the schema doesn't")
    for f in fields.keys() - kotlin.keys():
        fail(rel, f"the schema has `{f}` but {top['kotlin_class']} doesn't")
    for f, ktype in kotlin.items():
        if fields[f]["kotlin"] != ktype:
            fail(rel, f"`{f}` is {ktype} in {top['kotlin_class']}, {fields[f]['kotlin']} in the schema")
    for f in fields.values():
        if "record" in f:
            load_schema(f["record"])
    return schema


# --- configs ---

def file_rank(path):
    rel = os.path.relpath(path, os.path.join(GAMEDATA, "gen3"))
    group, name = rel.split(os.sep)[0], os.path.splitext(os.path.basename(rel))[0]
    g = GROUP_ORDER.index(group) if group in GROUP_ORDER else len(GROUP_ORDER)
    return (g, RETAIL_ORDER.index(name) if group == "retail" and name in RETAIL_ORDER else len(RETAIL_ORDER), rel)


def load_configs(schema):
    configs = {}
    paths = [p for p in glob.glob(os.path.join(GAMEDATA, "gen3", "**", "*.toml"), recursive=True)
             if not p.startswith(RECORDS + os.sep)]
    for path in sorted(paths, key=file_rank):
        rel = os.path.relpath(path, ROOT)
        tables = read_toml(path)
        if tables[0]["entries"]:
            fail(f"{rel}:{tables[0]['entries'][0][4]}", "keys before the first [config.*]")
        for i, tab in enumerate(tables[1:]):
            where = f"{rel}:{tab['line']}"
            m = re.fullmatch(r"config\.([a-z0-9_]+)((?:\.\w+)*)", tab["name"])
            if not m:
                fail(where, f"[{tab['name']}] isn't [config.<lower_snake_name>] or a record of one")
            key, path_parts = m.group(1), [p for p in m.group(2).split(".") if p]
            if path_parts:
                if key not in configs or configs[key]["file"] != rel:
                    fail(where, f"[{tab['name']}] must follow [config.{key}] in the same file")
                add_record(configs[key], path_parts, tab, schema, rel)
                continue
            if key in configs:
                fail(where, f"config {key} is also in {configs[key]['file']}")
            meta, entries = {}, []
            for e in tab["entries"]:
                (meta.__setitem__(e[0], e[1]) if e[0] in META_KEYS else entries.append(e))
            check_entries(entries, schema, rel)
            configs[key] = {"key": key, "file": rel, "rank": (file_rank(path), i), "where": where,
                            "lead": tab["lead"], "entries": entries, "end": tab["end"],
                            "base": meta.get("base"), "private": meta.get("private", False),
                            "lazy": meta.get("lazy", False), "port": meta.get("port")}
    for c in configs.values():
        if c["base"] is not None and c["base"] not in configs:
            fail(c["where"], f"base {c['base']!r} isn't a config")
        for name, value, _, _, line in c["entries"]:
            if isinstance(value, dict) and "from" in value and value["from"] not in configs:
                fail(f"{c['file']}:{line}", f"from {value['from']!r} isn't a config")
    ports = {}
    for c in configs.values():
        root, chain = c, set()
        while root["base"]:
            if root["key"] in chain:
                fail(c["where"], "its base chain loops")
            chain.add(root["key"])
            root = configs[root["base"]]
        set_fields = {e[0] for e in root["entries"]}
        missing = [n for n, f in schema["fields"].items() if f["required"] and n not in set_fields]
        if missing:
            fail(root["where"], f"has no base, so it must set {', '.join(missing)}")
        if c["port"]:
            if c["port"] in ports:
                fail(c["where"], f"port {c['port']} is also {ports[c['port']]}")
            ports[c["port"]] = c["key"]
    return configs


def add_record(config, path_parts, tab, schema, rel):
    """A [config.<key>.<field>(.<field>...)] sub-table: the record that field holds, spelled out."""
    where = f"{rel}:{tab['line']}"
    entries, s = config["entries"], schema
    for depth, part in enumerate(path_parts):
        f = s["fields"].get(part)
        if f is None:
            fail(where, f"`{part}` isn't a {s['class']} field")
        if "record" not in f:
            fail(where, f"`{part}` holds no record to spell out (its schema field has no `record`)")
        s = load_schema(f["record"])
        existing = next((e for e in entries if e[0] == part), None)
        if depth == len(path_parts) - 1:
            if existing:
                fail(where, f"`{part}` is already set")
            base = None
            fields = []
            for e in tab["entries"]:
                if e[0] == "base":
                    base = e[1]
                else:
                    fields.append(e)
            check_entries(fields, s, rel)
            record = {"record": s["name"], "class": s["class"], "base": base, "entries": fields, "end": tab["end"]}
            if base is None:
                missing = [n for n, ff in s["fields"].items() if ff["required"] and n not in {e[0] for e in fields}]
                if missing:
                    fail(where, f"has no base, so it must set {', '.join(missing)}")
            entries.append((part, record, tab["lead"], None, tab["line"]))
        else:
            if not existing or not isinstance(existing[1], dict) or "record" not in existing[1]:
                fail(where, f"[{'.'.join(path_parts[:depth + 1])}] must come first")
            entries = existing[1]["entries"]


def check_entries(entries, schema, rel):
    seen = set()
    for name, value, _, _, line in entries:
        where = f"{rel}:{line}"
        f = schema["fields"].get(name)
        if f is None:
            fail(where, f"`{name}` isn't a {schema['class']} field (gamedata/schema/{schema['name']}.toml)")
        if name in seen:
            fail(where, f"`{name}` is set twice")
        seen.add(name)
        check_value(value, f["type"], f["nullable"], where, name)


def check_value(value, ftype, nullable, where, name):
    if isinstance(value, dict) and set(value) in ({"kotlin"}, {"ref"}, {"from"}, {"from", "field"}):
        if not all(isinstance(v, str) for v in value.values()):
            fail(where, f"`{name}`: {value} must hold strings")
        return
    if value == NONE:
        if not nullable:
            fail(where, f"`{name}` can't be {NONE}")
        return
    ok = {
        "long": lambda v: isinstance(v, int) and not isinstance(v, bool),
        "int": lambda v: isinstance(v, int) and not isinstance(v, bool) and -2**31 <= v < 2**31,
        "bool": lambda v: isinstance(v, bool),
        "char": lambda v: isinstance(v, str) and len(v) == 1,
        "string": lambda v: isinstance(v, str),
        "ref": lambda v: isinstance(v, str) and re.fullmatch(r"[A-Za-z_][\w.]*", v),
        "int_range": lambda v: isinstance(v, list) and len(v) == 2 and all(isinstance(x, int) for x in v),
        "long_list": lambda v: isinstance(v, list) and all(isinstance(x, int) and not isinstance(x, bool) for x in v),
        "int_list": lambda v: isinstance(v, list) and all(isinstance(x, int) and not isinstance(x, bool) for x in v),
        "int_set": lambda v: isinstance(v, list) and all(isinstance(x, int) and not isinstance(x, bool) for x in v)
        and len(set(v)) == len(v),
        "long_set": lambda v: isinstance(v, list) and all(isinstance(x, int) and not isinstance(x, bool) for x in v)
        and len(set(v)) == len(v),
        # a Map<Int, Int> as [[key, value], ...] (TOML's table keys can't be numbers)
        "int_map": lambda v: isinstance(v, list) and all(isinstance(p, list) and len(p) == 2 and all(
            isinstance(x, int) and not isinstance(x, bool) for x in p) for p in v) and len({p[0] for p in v}) == len(v),
        "item_descs": lambda v: isinstance(v, dict),
    }[ftype](value)
    if not ok:
        fail(where, f"`{name}` should be a {ftype}, not {value!r}")
    if ftype == "item_descs":
        for k, v in value.items():
            if k not in ITEM_DESC_FIELDS:
                fail(where, f"`{name}.{k}` isn't an item_descs field ({', '.join(ITEM_DESC_FIELDS)})")
            check_value(v, ITEM_DESC_FIELDS[k], k == "next", where, f"{name}.{k}")
        if value.get("preset") is not None:
            if value["preset"] not in PRESETS:
                fail(where, f"`{name}.preset` is one of {sorted(PRESETS)}")
            if set(value) - {"preset", "base", "count"}:
                fail(where, f"`{name}` with a preset takes only base and count")
        elif not {"base", "stride", "descOff"} <= set(value):
            fail(where, f"`{name}` needs base, stride and descOff (or a preset)")


def dependencies(c):
    deps = {c["base"]} if c["base"] else set()
    for _, value, *_ in c["entries"]:
        if isinstance(value, dict) and "from" in value:
            deps.add(value["from"])
    return deps


def in_order(configs):
    """Every config after the ones it builds on, else in file order (Kotlin initialises a file's vals top down)."""
    done, out = set(), []
    pending = sorted(configs.values(), key=lambda c: c["rank"])
    while pending:
        ready = next((c for c in pending if dependencies(c) <= done), None)
        if ready is None:
            fail(pending[0]["where"], "its base / from refs loop")
        pending.remove(ready)
        done.add(ready["key"])
        out.append(ready)
    return out


# --- Kotlin ---

def kt_name(key):
    return "NATIVE_" + key.upper()


def kt_int(v, long):
    # Hex addresses carry the L, as NativeReader.kt wrote them; a decimal literal is a Long where one is expected.
    raw = getattr(v, "raw", None) or str(int(v))
    return raw + ("L" if long and "x" in raw else "")


def kt_value(value, ftype, field, indent):
    if isinstance(value, dict) and "record" in value:
        return kt_call(f"{value['base']}.copy" if value["base"] else value["class"],
                       value["entries"], value["end"], load_schema(value["record"]), indent)
    if isinstance(value, dict) and "kotlin" in value:
        return value["kotlin"]
    if isinstance(value, dict) and "ref" in value:
        return value["ref"]
    if isinstance(value, dict) and "from" in value:
        return f"{kt_name(value['from'])}.{value.get('field', field)}"
    if value == NONE:
        return "null"
    if ftype in ("long", "int"):
        return kt_int(value, ftype == "long")
    if ftype == "bool":
        return "true" if value else "false"
    if ftype == "char":
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
    if ftype == "string":
        return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'
    if ftype == "ref":
        return value
    if ftype == "int_range":
        return f"{kt_int(value[0], False)}..{kt_int(value[1], False)}"
    if ftype in ("long_list", "int_list"):
        return "emptyList()" if not value else \
            "listOf(" + ", ".join(kt_int(v, ftype == "long_list") + ("L" if ftype == "long_list" and "x" not in
                                                                         kt_int(v, False) else "") for v in value) + ")"
    if ftype in ("int_set", "long_set"):
        return "emptySet()" if not value else "setOf(" + ", ".join(
            kt_int(v, ftype == "long_set") + ("L" if ftype == "long_set" and "x" not in kt_int(v, False) else "")
            for v in value) + ")"
    if ftype == "int_map":
        return "emptyMap()" if not value else \
            "mapOf(" + ", ".join(f"{kt_int(k, False)} to {kt_int(v, False)}" for k, v in value) + ")"
    if ftype == "item_descs":
        args = {k: kt_value(v, ITEM_DESC_FIELDS[k], k, indent) for k, v in value.items() if k != "preset"}
        if value.get("preset"):
            return f"{PRESETS[value['preset']]}({', '.join(args[k] for k in ('base', 'count') if k in args)})"
        named = [f"{k} = {args[k]}" for k in ("idOff", "first", "count", "next") if k in args]
        return f"ItemDescTable({', '.join([args['base'], args['stride'], args['descOff']] + named)})"
    raise AssertionError(ftype)


def kt_comments(lines, indent):
    return [f"{indent}//" + (" " + l if l else "") for l in lines]


def kt_call(head, entries, end, schema, indent):
    """`head(` + one named argument a line + `)`, at the given indentation."""
    if not entries and not end:
        return head + "()"
    inner = indent + "    "
    out = [head + "("]
    for name, value, lead, trail, _ in entries:
        out += kt_comments(lead, inner)
        v = kt_value(value, schema["fields"][name]["type"], name, inner)
        out.append(f"{inner}{name} = {v}," + (f" // {trail}" if trail else ""))
    out += kt_comments(end, inner)
    out.append(indent + ")")
    return "\n".join(out)


def kotlin(configs, schema):
    out = [
        "package com.pokedaisy.app.companion.data",
        "",
        "// Generated by scripts/gen_gamedata.py from gamedata/gen3/ - don't edit by hand: change the",
        "// TOML there and run `make gamedata` (gamedata/README.md). One val per config, each built on",
        "// its `base` with .copy(), so a config is still its own object (fireRedHackFor goes by identity).",
    ]
    last_file = None
    for c in in_order(configs):
        if c["file"] != last_file:
            out += ["", f"// ---- {c['file']} ----"]
            last_file = c["file"]
        out.append("")
        out += kt_comments(c["lead"], "")
        head = f"{kt_name(c['base'])}.copy" if c["base"] else "NativeConfig"
        decl = f"{'private ' if c['private'] else ''}val {kt_name(c['key'])}"
        if c["lazy"]:
            out.append(f"{decl} by lazy {{")
            out.append("    " + kt_call(head, c["entries"], c["end"], schema, "    "))
            out.append("}")
        else:
            out.append(f"{decl} = " + kt_call(head, c["entries"], c["end"], schema, ""))
    ports = sorted((c["port"], c["key"]) for c in configs.values() if c["port"])
    if ports:
        out += ["", "/** The ported configs, by game code + revision (\"BPRD0\"): gamedata/gen3/ports/. */",
                "val RETAIL_PORTS: Map<String, () -> NativeConfig> = mapOf("]
        out += [f"    \"{port}\" to {{ {kt_name(key)} }}," for port, key in ports]
        out.append(")")
    return "\n".join(out) + "\n"


# --- records: gamedata/gen3/records/ ---

RECORD_NAME = re.compile(r"[A-Z][A-Z0-9_]*")
LIST_TYPES = {"Int": "int", "Long": "long"}


def load_records():
    """{NAME: record} from gamedata/gen3/records/: [record.NAME] (a schema's class, optionally built on
    another record with `base`) and [list.NAME] (`of` = Int / Long, `items` = numbers or Kotlin names)."""
    records = {}
    for path in sorted(glob.glob(os.path.join(RECORDS, "*.toml"))):
        rel = os.path.relpath(path, ROOT)
        tables = read_toml(path)
        if tables[0]["entries"]:
            fail(f"{rel}:{tables[0]['entries'][0][4]}", "keys before the first [record.*] / [list.*]")
        for i, tab in enumerate(tables[1:]):
            where = f"{rel}:{tab['line']}"
            m = re.fullmatch(r"(record|list)\.([A-Za-z0-9_]+)", tab["name"])
            if not m or not RECORD_NAME.fullmatch(m.group(2)):
                fail(where, f"[{tab['name']}] isn't [record.<UPPER_SNAKE>] or [list.<UPPER_SNAKE>]")
            kind, name = m.groups()
            if name in records:
                fail(where, f"{name} is also in {records[name]['file']}")
            # `of` / `items` are a list's own keys; a record's `items` is a field (FieldItemFns.items).
            keys = ("schema", "base", "of", "items") if kind == "list" else ("schema", "base", "kotlin")
            meta = {e[0]: e[1] for e in tab["entries"] if e[0] in keys}
            entries = [e for e in tab["entries"] if e[0] not in keys]
            r = {"name": name, "kind": kind, "file": rel, "where": where, "rank": (rel, i),
                 "lead": tab["lead"], "end": tab["end"], "entries": entries, "base": meta.get("base"),
                 "kotlin": meta.get("kotlin", True)}
            if kind == "list":
                if entries or meta.get("of") not in LIST_TYPES or not isinstance(meta.get("items"), list):
                    fail(where, "a list has `of` (Int or Long) and `items`, nothing else")
                for item in meta["items"]:
                    if not (isinstance(item, int) or (isinstance(item, str) and re.fullmatch(r"[A-Za-z_][\w.]*", item))):
                        fail(where, f"list item {item!r} isn't a number or a Kotlin name")
                r.update(of=meta["of"], items=meta["items"])
            else:
                if "schema" not in meta:
                    fail(where, "a record names its `schema`")
                r["schema"] = load_schema(meta["schema"])
                check_entries(entries, r["schema"], rel)
            records[name] = r
    for r in records.values():
        if r["kind"] != "record":
            continue
        root, seen = r, set()
        while root["base"] in records:
            if root["name"] in seen:
                fail(r["where"], "its base chain loops")
            seen.add(root["name"])
            base = records[root["base"]]
            if base["kind"] != "record" or base["schema"]["name"] != r["schema"]["name"]:
                fail(r["where"], f"base {root['base']} isn't a {r['schema']['class']} record")
            root = base
        if root["base"] is None:
            set_fields = {e[0] for e in root["entries"]}
            missing = [n for n, f in r["schema"]["fields"].items() if f["required"] and n not in set_fields]
            if missing:
                fail(root["where"], f"has no base, so it must set {', '.join(missing)}")
    return records


def record_kotlin(records):
    out = [
        "package com.pokedaisy.app.companion.data",
        "",
        "// Generated by scripts/gen_gamedata.py from gamedata/gen3/records/ - don't edit by hand: change the",
        "// TOML there and run `make gamedata` (gamedata/README.md). Each one is built on first use, so",
        "// they can name each other and vals in any other file, whichever file is initialised first.",
    ]
    last_file = None
    for r in sorted(records.values(), key=lambda r: r["rank"]):
        # `kotlin = false` (plorpOS's Linux port, scripts/port_records.py): a record whose Kotlin val the app
        # still keeps itself, here for the 3DS / Linux target only.
        if r.get("kotlin") is False:
            continue
        if r["file"] != last_file:
            out += ["", f"// ---- {r['file']} ----"]
            last_file = r["file"]
        out.append("")
        out += kt_comments(r["lead"], "")
        if r["kind"] == "list":
            items = ", ".join(v if isinstance(v, str) else kt_int(v, r["of"] == "Long") for v in r["items"])
            out.append(f"val {r['name']}: List<{r['of']}> by lazy {{ listOf({items}) }}")
            continue
        cls = r["schema"]["class"]
        head = f"{r['base']}.copy" if r["base"] else cls
        out.append(f"val {r['name']}: {cls} by lazy {{")
        out.append("    " + kt_call(head, r["entries"], r["end"], r["schema"], "    "))
        out.append("}")
    return "\n".join(out) + "\n"


# --- C targets: gamedata/targets/ ---

def schema_default(field, where):
    if field["required"]:
        return None
    return field["default"]


class Resolver:
    """Follows a target's paths through configs, records and lists to plain values."""

    def __init__(self, configs, records, schema):
        self.configs, self.records, self.schema = configs, records, schema
        self.attached = {}  # {config: {name: record}}: a target's `records`, which `@name.` paths start from
        self.overrides = {}  # {config: {field: value}}: a target's `set`, on that config only (not what's built on it)

    def config_fields(self, key):
        c = self.configs[key]
        fields = self.config_fields(c["base"]) if c["base"] else {}
        for name, value, *_ in c["entries"]:
            fields[name] = value
        return fields

    def record_fields(self, name, where):
        r = self.records.get(name)
        if r is None or r["kind"] != "record":
            fail(where, f"{name} isn't a record in gamedata/gen3/records/ (still a Kotlin val?)")
        fields = dict(self.record_fields(r["base"], where)[0]) if r["base"] else {}
        for n, value, *_ in r["entries"]:
            fields[n] = value
        return fields, r["schema"]

    def value(self, fields, schema, name, where, key):
        """A field of a config / record: set, else its schema's default; `from` followed."""
        f = schema["fields"].get(name)
        if f is None:
            fail(where, f"`{name}` isn't a {schema['class']} field")
        v = fields[name] if name in fields else schema_default(f, where)
        if isinstance(v, dict) and "from" in v and set(v) <= {"from", "field"}:
            other = self.config_fields(v["from"])
            return self.value(other, self.schema, v.get("field", name), where, v["from"])
        return v, f

    def walk(self, key, path, where):
        fields, schema = dict(self.config_fields(key)), self.schema
        fields.update(self.overrides.get(key, {}))
        parts = path.split(".")
        if parts[0].startswith("@"):
            record = self.attached.get(key, {}).get(parts[0][1:])
            if record is None:
                fail(where, f"{path}: the target gives {key} no `{parts[0][1:]}` record (its `records`)")
            fields, schema = self.record_fields(record, where)
            parts = parts[1:]
        for depth, part in enumerate(parts):
            last = depth == len(parts) - 1
            if isinstance(fields, list):
                if not part.isdigit():
                    fail(where, f"{path}: `{part}` indexes a list")
                i = int(part)
                return (fields[i] if i < len(fields) else None), {"type": "long" if ftype == "long_list" else "int"}
            v, f = self.value(fields, schema, part, where, key)
            if last:
                return v, f
            ftype = f["type"]
            if ftype in ("long_list", "int_list"):
                fields = v or []
                continue
            if isinstance(v, dict) and "record" in v:  # a sub-table
                inner = self.record_fields(v["base"], where)[0] if v["base"] else {}
                inner = dict(inner)
                for n, val, *_ in v["entries"]:
                    inner[n] = val
                fields, schema = inner, load_schema(v["record"])
            elif v == NONE or v is None:
                if "record" not in f:
                    fail(where, f"{path}: {key} has no `{part}` and it holds no record with defaults")
                fields, schema = {}, load_schema(f["record"])
            elif isinstance(v, str):
                fields, schema = self.record_fields(v, where)
            else:
                fail(where, f"{path}: `{part}` of {key} is {v!r}, which a C target can't follow")
        raise AssertionError


def c_value(v, f, names, lists, where, none):
    if f["type"] in ("long_list", "int_list", "int_set", "long_set", "int_map") and (not isinstance(v, list) or not v):
        return "{ { 0 } }" if f["type"] == "int_map" else "{ 0 }"
    if f["type"] in ("long_list", "int_list", "int_set", "long_set", "int_map") and isinstance(v, list):
        return "{ " + ", ".join(c_value(i, {"type": "int"}, names, lists, where, None) if not isinstance(i, list)
                                else "{ " + ", ".join(str(int(x)) for x in i) + " }" for i in v) + " }"
    if isinstance(v, dict):
        fail(where, f"{v} is Kotlin; the C needs the value in gamedata/")
    if v is None or v == NONE or (none is not None and v == none):
        return "0"
    if f["type"] == "string":
        return '"' + v.replace("\\", "\\\\").replace('"', '\\"') + '"'
    if f["type"] == "bool":
        return "true" if v else "false"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return (getattr(v, "raw", None) or str(int(v))).replace("_", "")
    if isinstance(v, str):
        if v in lists:
            return "{ " + ", ".join(c_value(i, {"type": "int"}, names, lists, where, None) for i in lists[v]["items"]) + " }"
        node = names
        for part in v.split("."):
            node = node.get(part) if isinstance(node, dict) else None
        if not isinstance(node, str):
            fail(where, f"{v} has no C name in the target's [names]")
        return node
    fail(where, f"can't write {v!r} in C")


def c_target(path, configs, records, schema):
    rel = os.path.relpath(path, ROOT)
    tables = read_toml(path)
    top = {k: v for k, v, *_ in tables[0]["entries"]}
    names, parts, wanted = {}, {}, []
    for tab in tables[1:]:
        where = f"{rel}:{tab['line']}"
        entries = {k: (v, line) for k, v, _, _, line in tab["entries"]}
        if tab["name"] == "names" or tab["name"].startswith("names."):
            node = names
            for p in tab["name"].split(".")[1:]:
                node = node.setdefault(p, {})
            node.update({k: v for k, (v, _) in entries.items()})
        elif tab["name"].startswith("part."):
            parts[tab["name"][5:]] = [(k, v, f"{rel}:{line}") for k, (v, line) in entries.items()]
        elif tab["name"].startswith("config."):
            key = tab["name"][7:]
            if key not in configs:
                fail(where, f"{key} isn't a config")
            for p in entries.get("parts", ([], 0))[0]:
                if p not in parts:
                    fail(where, f"no [part.{p}]")
            attached = entries.get("records", ({}, 0))[0]
            for name in attached.values():
                if records.get(name, {}).get("kind") != "record":
                    fail(where, f"{name} isn't a record in gamedata/gen3/records/")
            # `set = { field = value }`: config fields the target sets itself, as the app's code does with a .copy(...)
            # (retail FireRed rev 0's trainer card: Poller.kt's NATIVE_FIRERED_REV0.copy(trainerCard = ...)).
            for f in entries.get("set", ({}, 0))[0]:
                if f not in schema["fields"]:
                    fail(where, f"set: `{f}` isn't a config field")
            wanted.append((key, entries["name"][0], entries["parts"][0], tab["lead"], where, attached,
                           entries.get("set", ({}, 0))[0]))
        else:
            fail(where, f"unexpected [{tab['name']}]")
    lists = {n: r for n, r in records.items() if r["kind"] == "list"}
    res = Resolver(configs, records, schema)
    res.attached = {w[0]: w[5] for w in wanted}
    res.overrides = {w[0]: w[6] for w in wanted}
    out = [
        f"// Generated by scripts/gen_gamedata.py from gamedata/ ({rel}) - don't edit by hand: change",
        "// the TOML there and run `make gamedata` (gamedata/README.md). Included by pd_game.c only.",
        f"#ifndef PD_CONFIGS_GEN_H",
        f"#define PD_CONFIGS_GEN_H",
        "",
        f'#include "pd_game.h"',
    ]
    for key, cname, want, lead, where, *_ in wanted:
        c = configs[key]
        out.append("")
        out += kt_comments(lead, "")
        out.append(f"// {c['file']}: {key} ({', '.join(want)})")
        out.append(f"static const struct {top['struct']} {top['prefix']}{cname} = {{")
        for part in want:
            for field, spec, fwhere in parts[part]:
                spec_path, none = (spec["path"], spec.get("none")) if isinstance(spec, dict) else (spec, None)
                v, f = res.walk(key, spec_path, fwhere)
                out.append(f"    .{field} = {c_value(v, f, names, lists, fwhere, none)},")
        out.append("};")
    out += ["", "#endif"]
    return os.path.join(ROOT, top["output"]), "\n".join(out) + "\n"


def main():
    if "--new-schema" in sys.argv:
        i = sys.argv.index("--new-schema")
        try:
            new_schema(*sys.argv[i + 1:i + 4])
        except (TypeError, GameDataError) as e:
            sys.exit(f"gen_gamedata: --new-schema <name> <File.kt> <Class>: {e}")
        return
    try:
        schema = load_schema(CONFIG_SCHEMA)
        configs = load_configs(schema)
        records = load_records()
        outputs = [(KOTLIN_OUT, kotlin(configs, schema)), (RECORDS_OUT, record_kotlin(records))]
        for target in sorted(glob.glob(os.path.join(TARGETS, "*.toml"))):
            outputs.append(c_target(target, configs, records, schema))
    except GameDataError as e:
        sys.exit(f"gen_gamedata: {e}")
    summary = f"{len(configs)} configs, {len(records)} records"
    if "--check" in sys.argv:
        stale = [os.path.relpath(path, ROOT) for path, text in outputs
                 if (open(path, encoding="utf-8").read() if os.path.exists(path) else "") != text]
        if stale:
            sys.exit(f"gen_gamedata: {', '.join(stale)} stale or edited by hand - run `make gamedata` and commit the result")
        print(f"gen_gamedata: {len(outputs)} files up to date ({summary})")
        return
    for path, text in outputs:
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)
    print(f"gen_gamedata: {summary} -> {', '.join(os.path.relpath(p, ROOT) for p, _ in outputs)}")


if __name__ == "__main__":
    main()
