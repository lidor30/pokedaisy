#!/usr/bin/env python3
"""plorpOS's Linux port: moves the records the Gen 3 configs name (PokedexTables, GuideTables, TrainerCardSave,
DexFlags, FieldItemFns, FlyFns, PcFns, PartyItemFns, HealWarp) from the app's Kotlin vals into
gamedata/gen3/records/ported.toml, so every config reaches the 3DS / Linux target (gamedata/targets/3ds.toml).

Each record is written `kotlin = false`: the app keeps its own val (gen_gamedata.py writes no Kotlin for it), so the
app builds as before. Then a round trip: gen_gamedata's Kotlin for each ported record must say what the source val
says, argument by argument (numbers by value, the rest by text); any difference, and any val it can't read (a
helper call, a val that isn't a call of the record's class or a .copy), is listed and fails the run.

    python3 -I scripts/port_records.py           # write ported.toml, check the round trip
"""
import ast
import glob
import importlib.util
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
KT = os.path.join(ROOT, "app/src/main/kotlin")
OUT = os.path.join(ROOT, "gamedata/gen3/records/ported.toml")


def load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(ROOT, "scripts", name + ".py"))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


gg = load("gen_gamedata")
cv = load("convert_native_configs")

FUN = r"^(?:(?:private|internal)\s+)?fun\s+{name}\s*\("


def expand_helper(name, body, sources):
    """A helper the app builds records with (`fun rechargedEmeraldDex(entries: Long, ...) = X.copy(...)`), called
    with `body`: (head, its arguments with the call's values put in), or None if `name` isn't one."""
    for src in sources.values():
        m = re.search(FUN.format(name=re.escape(name)), src, re.M)
        if not m:
            continue
        pend = cv.match_paren(src, m.end() - 1)
        params = []
        for p in cv.split_args(src[m.end():pend - 1]):
            pm = re.match(r"\s*(\w+)\s*:\s*[\w<>?]+\s*(?:=\s*(.*))?$", p.strip(), re.S)
            if pm:
                params.append((pm.group(1), pm.group(2)))
        em = re.match(r"\s*=\s*([\w.]+)\s*\(", src[pend:])
        if not em:
            return None
        start = pend + em.end() - 1
        call = src[start + 1:cv.match_paren(src, start) - 1]
        given = [x.strip() for x in cv.split_args(body) if x.strip()]
        vals = {}
        for i, (pn, default) in enumerate(params):
            named = [g for g in given if re.match(rf"{pn}\s*=", g)]
            if named:
                vals[pn] = named[0].split("=", 1)[1].strip()
            elif i < len(given) and "=" not in given[i].split("(")[0]:
                vals[pn] = given[i]
            else:
                vals[pn] = default if default is not None else "0"
        # The parameters, put in by name (a named argument's own name, before its `=`, stays).
        out = re.sub(r"(?<![\w.])(\w+)(?!\s*=[^=])", lambda mm: "(" + vals[mm.group(1)] + ")"
                     if mm.group(1) in vals else mm.group(1), call)
        return em.group(1), out
    return None


def kt_sources():
    out = {}
    for path in glob.glob(os.path.join(KT, "**", "*.kt"), recursive=True):
        out[path] = open(path, encoding="utf-8").read()
    return out


VAL = r"^(?:(?:private|internal)\s+)?val\s+{name}\b(?:\s*:\s*[\w<>?, ]+)?\s*(?:=|by\s+lazy\s*\{{)\s*"


def find_val(name, sources):
    """(file, rhs code, the comment lines above it) of `val NAME = ...`, or None. FIELD_ITEMS_PORTS_<KEY>: the
    `"<KEY>" to FieldItemFns(...)` entry of the app's FIELD_ITEMS_PORTS map (English FireRed / Ruby / Sapphire rev 0)."""
    pm = re.fullmatch(r"FIELD_ITEMS_PORTS_([A-Z0-9]{5})", name)
    pat = (r'^\s*"%s"\s+to\s+' % pm.group(1)) if pm else VAL.format(name=re.escape(name))
    for path, src in sources.items():
        if pm and "val FIELD_ITEMS_PORTS" not in src:
            continue
        m = re.search(pat, src, re.M)
        if not m:
            continue
        start = m.end()
        mm = re.match(r"([\w.]+)\s*\(", src[start:])
        if not mm:
            return path, src[start:src.index("\n", start)].strip(), []
        end = cv.match_paren(src, start + mm.end() - 1)
        lines = src[:m.start()].split("\n")
        lead, _ = cv.leading_comments(lines + [""], len(lines))
        return path, src[start:end], lead
    return None


def num(expr):
    """A Kotlin integer expression's value (literals, + - * shl or and), else None."""
    e = " ".join(expr.split())
    if not re.fullmatch(r"[-+*() 0-9A-Fa-fxXL_]*(?:\b(?:shl|shr|or|and)\b[-+*() 0-9A-Fa-fxXL_]*)*", e):
        return None
    e = re.sub(r"(0[xX][0-9A-Fa-f_]+|\d[\d_]*)L\b", r"\1", e).replace("_", "")
    e = e.replace(" shl ", " << ").replace(" shr ", " >> ").replace(" or ", " | ").replace(" and ", " & ")
    try:
        tree = ast.parse(e, mode="eval")
    except SyntaxError:
        return None
    ok = (ast.Expression, ast.BinOp, ast.UnaryOp, ast.Constant, ast.Add, ast.Sub, ast.Mult, ast.LShift,
          ast.RShift, ast.BitOr, ast.BitAnd, ast.USub, ast.UAdd)
    if not all(isinstance(n, ok) for n in ast.walk(tree)):
        return None
    v = eval(compile(tree, "<num>", "eval"))
    return v if isinstance(v, int) else None


def norm(rhs):
    """An argument as the round trip compares it: a number by value, else its code without spaces or L suffixes."""
    v = num(rhs)
    if v is not None:
        return v
    s = re.sub(r"(0[xX][0-9A-Fa-f_]+|\b\d[\d_]*)L\b", r"\1", " ".join(rhs.split()))
    s = re.sub(r"0[xX]([0-9A-Fa-f_]+)", lambda m: str(int(m.group(1).replace("_", ""), 16)), s)
    s = s.replace(" ", "").rstrip(",")
    while True:  # (2800) -> 2800: a helper's argument put in where its parameter was
        t = re.sub(r"\((-?\d+)\)", r"\1", s)
        if t == s:
            return s
        s = t


def to_toml(field, rhs, f):
    """The TOML for one argument (via the configs' converter), with whole-number expressions folded."""
    ftype = f["type"]
    if ftype in ("int", "long") and re.fullmatch(r"-?(0x[0-9A-Fa-f_]+|\d[\d_]*)L?", " ".join(rhs.split())):
        return cv.toml_int(" ".join(rhs.split())), None  # a literal as the source writes it
    if ftype in ("int", "long"):
        v = num(rhs)
        if v is not None:
            lit = (f"0x{v:08X}" if v > 0xFFFFFF else str(v)) if v >= 0 else str(v)
            plain = " ".join(rhs.split())
            note = None if re.fullmatch(r"-?(0x[0-9A-Fa-f_]+|\d[\d_]*)L?", plain) else "= " + plain.replace("L", "")
            return lit, note
    if ftype in ("int_list", "long_list"):
        m = re.fullmatch(r"listOf\((.*)\)", " ".join(rhs.split()), re.S)
        if m:
            items = [num(a) for a in cv.split_args(m.group(1)) if a.strip()]
            if all(i is not None for i in items):
                lits = [" ".join(a.split()).strip("() ") for a in cv.split_args(m.group(1)) if a.strip()]
                return "[" + ", ".join(cv.toml_int(l) if re.fullmatch(r"-?(0x[0-9A-Fa-f_]+|\d+)L?", l)
                                       else str(i) for l, i in zip(lits, items)) + "]", None
    plain = " ".join(rhs.split())
    # setOf(2, 4) / mapOf(15 to 11) / emptyList(): TOML lists (as the hand-written records have them) - left as
    # Kotlin, the C target would get { 0 } for them.
    if ftype in ("int_set", "long_set", "int_list", "long_list") and re.fullmatch(r"empty(List|Set)\(\)", plain):
        return "[]", None
    m = re.fullmatch(r"setOf\((.*)\)", plain)
    if m and ftype in ("int_set", "long_set"):
        items = [num(a) for a in cv.split_args(m.group(1)) if a.strip()]
        if all(i is not None for i in items):
            return "[" + ", ".join(str(i) for i in items) + "]", None
    m = re.fullmatch(r"mapOf\((.*)\)", plain)
    if m and ftype == "int_map":
        pairs = [re.fullmatch(r"\s*(.+?)\s+to\s+(.+?)\s*", a) for a in cv.split_args(m.group(1)) if a.strip()]
        kv = [(num(p.group(1)), num(p.group(2))) if p else (None, None) for p in pairs]
        if all(k is not None and v is not None for k, v in kv):
            return "[" + ", ".join(f"[{k}, {v}]" for k, v in kv) + "]", None
    if ftype == "ref" and re.fullmatch(r"[A-Za-z_]\w*\.[A-Z_][A-Z0-9_]*", plain):  # an enum value: CardStyle.LEGACY
        return cv.toml_str(plain), None
    return cv.to_toml(field, rhs, ftype, {})


def main():
    sources = kt_sources()
    existing = gg.load_records()
    schema = gg.load_schema("gen3_config")
    configs = gg.load_configs(schema)
    by_class = {}
    for path in glob.glob(os.path.join(ROOT, "gamedata/schema/*.toml")):
        s = gg.load_schema(os.path.basename(path)[:-5])
        by_class[s["class"]] = s

    # What every config names, then what those records name, until nothing new.
    want = {}  # name -> schema
    def need(name, s):
        if isinstance(name, str) and re.fullmatch(r"[A-Z][A-Z0-9_]*", name) and name not in want:
            if name not in existing or existing[name]["file"].endswith("ported.toml"):
                want[name] = s

    for c in configs.values():
        for e in c["entries"]:
            f = schema["fields"].get(e[0])
            if f and "record" in f:
                need(str(e[1]), gg.load_schema(f["record"]))

    # ... and what the 3DS / Linux target names itself: a config's `records = { items = 'FIELD_ITEMS_...' }` (the app
    # picks those by game in code, FieldItems.kt fieldItemsFor, not through the config).
    target = gg.read_toml(os.path.join(ROOT, "gamedata/targets/3ds.toml"))
    for tab in target:
        for e in tab["entries"]:
            if e[0] == "records" and isinstance(e[1], dict) and isinstance(e[1].get("items"), str):
                need(e[1]["items"], gg.load_schema("gen3_field_items"))

    done, problems, records = set(), [], []
    inline = set()  # (record, field) written inline: its own record, checked on its own
    queue = []  # (name, schema, head, body, file, comments) of records written inline in another
    while set(want) - done or queue:
        if queue:
            name, s, head, body, path, lead = queue.pop()
        else:
            name = sorted(set(want) - done)[0]
            done.add(name)
            s = want[name]
            found = find_val(name, sources)
            if not found:
                problems.append(f"{name}: no `val {name}` in the app's Kotlin")
                continue
            path, rhs, lead = found
            if re.fullmatch(r"[A-Z][A-Z0-9_]*", rhs):  # an alias: the same record under another name
                need(rhs, s)
                records.append({"name": name, "schema": s, "base": rhs, "file": os.path.relpath(path, ROOT),
                                "lead": lead, "entries": [], "src": ""})
                continue
            m = re.fullmatch(r"([\w.]+)\((.*)\)", rhs, re.S)
            if not m:
                problems.append(f"{name} ({os.path.relpath(path, ROOT)}): not a call: {rhs[:60]}")
                continue
            head, body = m.group(1), m.group(2)
        rel = os.path.relpath(path, ROOT)
        if not head.endswith(".copy") and head != s["class"]:
            ex = expand_helper(head, body, sources)
            if not ex:
                problems.append(f"{name} ({rel}): {head}(...) isn't {s['class']}(...), a .copy or a helper")
                continue
            head, body = ex
        base = None
        head = head.replace("!!", "")
        if head.endswith(".copy"):
            base = head[:-5]
            fm = re.fullmatch(r"([A-Z][A-Z0-9_]*)\.(\w+)", base)  # POKEDEX_QUETZAL.flags.copy(...): that record's own
            known = existing.get(fm.group(1)) if fm else None
            if known:  # FLY_EMERALD.healWarp: the record that field of a gamedata record names
                ref = next((str(e[1]) for e in known["entries"] if e[0] == fm.group(2)), None)
                if not ref:
                    problems.append(f"{name}: {base} isn't set in {fm.group(1)}")
                    continue
                base = ref
            elif fm:  # its inline record gets this name when fm's record is ported
                base = fm.group(1) + "_" + re.sub(r"(?<!^)([A-Z])", r"_\1", fm.group(2)).upper()
            else:
                need(base, s)
        elif head != s["class"]:
            problems.append(f"{name} ({rel}): {head}(...) isn't {s['class']}(...) or a .copy")
            continue
        # Positional arguments (DexFlags(SAVE_BLOCK_1, 0x310, ...)): the constructor's order = the schema's.
        pieces = [x for x in cv.split_args(body) if x.strip()]
        if not base and pieces and not re.match(r"\s*(?://[^\n]*\n\s*)*\w+\s*=[^=]", pieces[0]):
            names = list(s["fields"])
            body = ", ".join(f"{names[i]} = {x.strip()}" if not re.match(r"\s*\w+\s*=[^=]", x) else x.strip()
                             for i, x in enumerate(pieces))
        try:
            args, end = cv.parse_args(body)
        except SystemExit as e:
            problems.append(f"{name}: {e}")
            continue
        entries = []
        for field, val, alead, trail in args:
            f = s["fields"].get(field)
            if not f:
                problems.append(f"{name}: `{field}` isn't a {s['class']} field")
                continue
            v = " ".join(val.split())
            if "record" in f:
                rs = gg.load_schema(f["record"])
                im = re.fullmatch(r"([\w.!]+)\((.*)\)", v, re.S)
                if im:  # written inline: a record of its own, NAME_FIELD
                    sub = name + "_" + re.sub(r"(?<!^)([A-Z])", r"_\1", field).upper()
                    queue.append((sub, rs, im.group(1), im.group(2), path, []))
                    entries.append((field, f"'{sub}'", alead, trail))
                    inline.add((name, field))
                    continue
                need(v, rs)
            t, note = to_toml(field, val, f)
            entries.append((field, t, alead, trail if trail else note))
        records.append({"name": name, "schema": s, "base": base, "file": rel, "lead": lead, "entries": entries,
                        "src": body})

    # ported.toml, in dependency order (a base before what's built on it), each with the val's comments.
    order, placed = [], set()
    def place(r):
        if r["name"] in placed:
            return
        for b in records:
            if b["name"] == r["base"]:
                place(b)
        placed.add(r["name"])
        order.append(r)
    for r in sorted(records, key=lambda r: (r["schema"]["name"], r["name"])):
        place(r)
    out = ["# Generated by scripts/port_records.py from the app's Kotlin vals (plorpOS's Linux port) - rerun it,",
           "# don't edit: the records the Gen 3 configs name, for the 3DS / Linux target. `kotlin = false`: the app",
           "# keeps each val itself (gen_gamedata.py writes no Kotlin for these)."]
    for r in order:
        out.append("")
        out += [("# " + l).rstrip() for l in r["lead"]]
        out.append(f"# From {r['file']}.")
        out.append(f"[record.{r['name']}]")
        out.append(f"schema = '{r['schema']['name']}'")
        if r["base"]:
            out.append(f"base = '{r['base']}'")
        out.append("kotlin = false")
        for field, t, alead, trail in r["entries"]:
            out += [("# " + l).rstrip() for l in alead]
            out.append(f"{field} = {t}" + (f"  # {trail}" if trail else ""))
    open(OUT, "w", encoding="utf-8").write("\n".join(out) + "\n")

    # The round trip: what gen_gamedata reads back, as Kotlin, against the source val.
    back = gg.load_records()
    for r in order:
        b = back[r["name"]]
        head = f"{b['base']}.copy" if b["base"] else b["schema"]["class"]
        kt = gg.kt_call(head, b["entries"], b["end"], b["schema"], "")
        gm = re.fullmatch(r"[\w.]+\((.*)\)", kt, re.S)
        got = {n: norm(v) for n, v, *_ in cv.parse_args(gm.group(1))[0]}
        src = {n: norm(v) for n, v, *_ in cv.parse_args(r["src"])[0]}
        for k in [k for k in src if (r["name"], k) in inline]:
            src.pop(k), got.pop(k, None)
        if got != src:
            diff = sorted(k for k in set(got) | set(src) if got.get(k) != src.get(k))
            problems += [f"{r['name']}: round trip differs at {k}: {src.get(k)!r} -> {got.get(k)!r}" for k in diff]
    print(f"port_records: {len(order)} records -> {os.path.relpath(OUT, ROOT)}")
    for p in problems:
        print("  !! " + p)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
