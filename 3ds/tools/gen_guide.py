#!/usr/bin/env python3
"""Writes 3ds/core/pd_guide_gen.c: the GUIDE's hand-written pages, bosses and
area data for FireRed, LeafGreen and Emerald, read from the app's own sources
so the two never drift:

- GuideFireRed.kt (built twice: leafGreen = false / true) and GuideEmerald.kt
  - pages, sections and entries, their `Have` checks and `areas` tags, the
  bosses (Boss(...) with their done flag and trainer);
- GuideAreas{FireRed,LeafGreen,Emerald}Gen.kt - what each area holds.

The guide files are a small Kotlin DSL (page / section / entry calls, string
concatenation, a ${prizeMons(leafGreen)} template, named arguments), parsed
here with a tiny tokenizer rather than regexes.

    python3 3ds/tools/gen_guide.py
"""
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DATA = os.path.join(ROOT, "app/src/main/kotlin/com/pokedaisy/app/companion/data")
OUT = os.path.join(ROOT, "3ds/core/pd_guide_gen.c")

HACK_GUIDES = [
    ("gaia", "Gaia", "GAIA"), ("rocket_edition", "RocketEdition", "ROCKET_EDITION"), ("ash_gray", "AshGray", "ASH_GRAY"),
    ("ghost_grey", "GhostGrey", "GHOST_GREY"), ("gs_chronicles", "GsChronicles", "GS_CHRONICLES"),
    ("radical_red", "RadicalRed", "RADICAL_RED"), ("orange_islands", "OrangeIslands", "ORANGE_ISLANDS"),
    ("odyssey", "Odyssey", "ODYSSEY"), ("celia", "Celia", "CELIA"), ("sword_shield", "SwordShield", "SWORD_SHIELD"),
    ("unbound", "Unbound", "UNBOUND"), ("amethyst", "Amethyst", "AMETHYST"), ("soulgold", "SoulGold", "SOULGOLD"),
    ("imperium", "Imperium", "IMPERIUM"), ("quetzal", "Quetzal", "QUETZAL"),
    ("recharged_emerald", "RechargedEmerald", "RECHARGED_EMERALD"), ("recharged_yellow", "RechargedYellow", "RECHARGED_YELLOW"),
    ("tmt2", "Tmt2", "TMT2"), ("seaglass", "Seaglass", "SEAGLASS"), ("lazarus", "Lazarus", "LAZARUS"),
    ("heart_and_soul", "HeartAndSoul", "HEART_AND_SOUL"), ("glazed", "Glazed", "GLAZED"), ("mariomon", "Mariomon", "MARIOMON"),
    ("dreamstone", "Dreamstone", "DREAMSTONE"), ("wishes", "Wishes", "WISHES"), ("team_rocket", "TeamRocket", "TEAM_ROCKET"),
    ("elite_redux", "EliteRedux", "ELITE_REDUX"), ("ultra_violet", "UltraViolet", "ULTRA_VIOLET"),
    ("lets_go", "LetsGo", "LETS_GO"), ("monomyth", "Monomyth", "MONOMYTH"),
]
# Hacks whose guide has no area file in the app (their WHERE IS is empty here).
AREAS_OPTIONAL = {"soulgold", "imperium", "quetzal", "recharged_emerald", "tmt2", "seaglass", "lazarus", "glazed", "mariomon",
                  "dreamstone", "wishes", "team_rocket", "elite_redux", "monomyth"}
AREA_KINDS = ["ITEM", "HIDDEN", "GIFT", "KEY", "MON", "EGG", "TRADE"]


def read(name):
    with open(os.path.join(DATA, name), encoding="utf-8") as f:
        return f.read()


# --- a tokenizer and parser for the Kotlin the guides use ---

TOKEN = re.compile(r'\s+|//[^\n]*|/\*.*?\*/|(?P<str>")|(?P<num>0x[0-9A-Fa-f]+|\d+)|(?P<id>[A-Za-z_][A-Za-z0-9_]*)|(?P<p>[(),=+.{}\[\]:<>\-*/])', re.S)


def tokenize(text, pos=0, end=None):
    end = len(text) if end is None else end
    toks = []
    while pos < end:
        m = TOKEN.match(text, pos)
        if not m:
            raise SyntaxError(f"gen_guide: can't read {text[pos:pos + 40]!r}")
        if m.group("str"):
            parts, pos = read_string(text, m.end())
            toks.append(("str", parts))
            continue
        if m.group("num"):
            toks.append(("num", int(m.group("num"), 0)))
        elif m.group("id"):
            toks.append(("id", m.group("id")))
        elif m.group("p"):
            toks.append(("p", m.group("p")))
        pos = m.end()
    return toks


def read_string(text, pos):
    """A "..." literal from just after its quote: a list of str / ('tmpl', expr text) parts."""
    parts, buf = [], ""
    while True:
        c = text[pos]
        if c == "\\":
            esc = text[pos + 1]
            buf += {"n": "\n", "t": "\t", '"': '"', "\\": "\\", "$": "$"}.get(esc, esc)
            pos += 2
        elif c == '"':
            parts.append(buf)
            return parts, pos + 1
        elif c == "$" and re.match(r"[A-Za-z_]", text[pos + 1]):  # $name, a bare template
            name = re.match(r"[A-Za-z_]\w*", text[pos + 1:]).group(0)
            parts.append(buf)
            buf = ""
            parts.append(("tmpl", name))
            pos += 1 + len(name)
        elif text.startswith("${", pos):
            depth, j = 1, pos + 2
            while depth:
                depth += {"{": 1, "}": -1}.get(text[j], 0)
                j += 1
            parts.append(buf)
            buf = ""
            parts.append(("tmpl", text[pos + 2:j - 1]))
            pos = j
        else:
            buf += c
            pos += 1


class Parser:
    """Expressions: calls (positional + named args), string concatenation,
    integer sums of constants, a.b member access."""

    def __init__(self, toks, env):
        self.t, self.i, self.env = toks, 0, env

    def peek(self, k=0):
        return self.t[self.i + k] if self.i + k < len(self.t) else ("eof", None)

    def take(self, kind=None, value=None):
        tok = self.peek()
        if (kind and tok[0] != kind) or (value is not None and tok[1] != value):
            raise SyntaxError(f"gen_guide: expected {kind} {value}, got {tok}")
        self.i += 1
        return tok

    def expr(self):
        left = self.term()
        while self.peek() == ("p", "+"):
            self.take()
            right = self.term()
            left = left + right  # str + str or int + int
        return left

    def term(self):
        kind, value = self.peek()
        if kind == "str":
            self.take()
            return "".join(p if isinstance(p, str) else self.env["template"](p[1]) for p in value)
        if kind == "num":
            self.take()
            return value
        if kind == "id":
            self.take()
            name = value
            while self.peek() == ("p", "."):
                self.take()
                name += "." + self.take("id")[1]
            if self.peek() == ("p", "("):
                return self.call(name)
            if name in self.env["consts"]:
                return self.env["consts"][name]
            if name in self.env.get("strings", {}):  # a guide's own string locals (Ruby / Sapphire's legend)
                return self.env["strings"][name]
            if name in ("true", "false"):
                return name == "true"
            return ("name", name)
        raise SyntaxError(f"gen_guide: unexpected {self.peek()}")

    def call(self, name):
        self.take("p", "(")
        args, kwargs = [], {}
        while self.peek() != ("p", ")"):
            if self.peek()[0] == "id" and self.peek(1) == ("p", "="):
                key = self.take()[1]
                self.take()
                kwargs[key] = self.expr()
            else:
                args.append(self.expr())
            if self.peek() == ("p", ","):
                self.take()
        self.take("p", ")")
        return ("call", name, args, kwargs)


def consts(text):
    """`const val X = 0x860` and sums over earlier ones (`SYS_FLAGS + 0x20`)."""
    out = {}
    for name, expr in re.findall(r"const val (\w+) = ([^\n/]+)", text):
        total = 0
        for term in expr.split("+"):
            term = term.strip()
            if re.fullmatch(r"0x[0-9A-Fa-f]+|\d+", term):
                total += int(term, 0)
            elif term in out:
                total += out[term]
            else:
                break
        else:
            out[name] = total
    return out


def matching_paren(text, open_at):
    depth, i, in_str = 0, open_at, False
    while True:
        c = text[i]
        if in_str:
            if c == "\\":
                i += 1
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1


def pages_of(text, env):
    """The `pages = listOf(...)` of a GameGuide, as [(title, [(heading, note, [entry dict])])]."""
    if "pages = emptyList()" in text:
        return []
    start = text.index("pages = listOf(") + len("pages = ")
    end = matching_paren(text, text.index("(", start))
    # A page kept in a val of its own (GuideCrystal.kt's CRYSTAL_TIPS, shared by two guides): its body spliced in.
    span = text[start:end + 1]
    for name in re.findall(r"\b([A-Z][A-Z0-9_]+)\b", span):
        m = re.search(r"^(?:private |internal )?val " + name + r" = (page\()", text, re.M)
        if m:
            body = text[m.start(1):matching_paren(text, m.end(1) - 1) + 1]
            span = re.sub(r"\b" + name + r"\b", lambda _: body, span)
    text, start, end = span, 0, len(span) - 1
    tree = Parser(tokenize(text, start, end + 1), env).expr()
    pages = []
    for page in tree[2]:
        assert page[1] == "page", page[1]
        title, *sections = page[2]
        out_sections = []
        for sec in sections:
            assert sec[1] == "section", sec[1]
            heading, *entries = sec[2]
            note = sec[3].get("note")
            # Randomized.ONLY / HIDE (Guide.kt): kept as a mark (0 either way, 1 unrandomized only, 2 randomized only) that
            # the C filters by the running game, as the app's shownPages.
            def marker(x):
                return x if isinstance(x, str) else x[1] if isinstance(x, tuple) and len(x) > 1 and isinstance(x[1], str) else None
            RND = {"Randomized.ANY": 0, "Randomized.HIDE": 1, "Randomized.ONLY": 2}
            smark = RND.get(marker(entries[0]), 0) if entries else 0
            entries = [e for e in entries if marker(e) not in RND]
            out_entries = []
            for e in entries:
                assert e[1] == "entry", e[1]
                args, kw = e[2], e[3]
                title_e, answer = args[0], args[1]
                hint = kw.get("hint", args[2] if len(args) > 2 else None)
                have = kw.get("have")
                areas = kw.get("areas")
                have_c = ("NONE", [])
                if have:
                    kind = have[1].split(".")[-1].upper()
                    have_c = (kind, have[2])
                area_list = [a for a in areas[2]] if areas else []
                out_entries.append(dict(title=title_e, answer=answer, hint=hint, have=have_c, areas=area_list,
                                        rnd=RND.get(marker(kw.get("randomized")), 0)))
            out_sections.append((heading, note, out_entries, smark))
        pages.append((title, out_sections))
    return pages


def bosses_of(text, decl, env):
    """Boss(title, where, doneFlag) { id } / trainer = league(a, b) / the champion's rival lambda."""
    body = text[text.index(f"val {decl} = listOf("):]
    body = body[:matching_paren(body, body.index("(")) + 1]
    out = []
    named = list(re.finditer(r'Boss\(("[^"]*"), ("[^"]*"), ([^,){]+?), trainer = ([a-z]\w*)\)', body))
    for m in named:
        # trainer = brawly: `private val brawly: (SaveProgress) -> Int = { p -> when { p.flag(F) -> N ... else -> M } }`
        lam = text[text.index(f"val {m.group(4)}:"):]
        lam = lam[:lam.index("\n}\n") + 2]
        alts = [(Parser(tokenize(f), env).expr(), int(n)) for f, n in re.findall(r"p\.flag\(([^)]+)\) -> (\d+)", lam)]
        default = int(re.search(r"else -> (\d+)", lam).group(1))
        title = Parser(tokenize(m.group(1)), env).expr()
        where = Parser(tokenize(m.group(2)), env).expr()
        flag = Parser(tokenize(m.group(3)), env).expr()
        out.append((m.start(), (title, where, flag, "BY_FLAGS", default, 0, alts, [], [])))
    plain = []
    for m in re.finditer(r'Boss\(("[^"]*"), ("[^"]*"), ([^,){]+?)(?:, doneAny = listOf\(([^)]*)\))?(?:, variants = (normalHard\(\d+, \d+\)|listOf\((?:"[^"]*" to \d+(?:, )?)+\)))?(?:, trainer = league\((\d+), (\d+)\))?\)\s*(\{[^}]*\})?', body):
        title = Parser(tokenize(m.group(1)), env).expr()
        where = Parser(tokenize(m.group(2)), env).expr()
        flag = Parser(tokenize(m.group(3)), env).expr()
        done_any = [Parser(tokenize(e.strip()), env).expr() for e in m.group(4).split(",")] if m.group(4) else []
        variants = []
        if m.group(5):
            v = m.group(5)
            if v.startswith("normalHard"):
                n, h = re.findall(r"\d+", v)
                variants = [("NORMAL", int(n)), ("HARD", int(h))]
            else:
                variants = [(l, int(n)) for l, n in re.findall(r'"([^"]*)" to (\d+)', v)]
        if m.group(6):
            plain.append((m.start(), (title, where, flag, "LEAGUE", int(m.group(6)), int(m.group(7)), [], variants, done_any)))
        else:
            lam = m.group(8) or ""
            champ = re.search(r"league\((\d+), (\d+)\)\(p\) \+ rivalOffset", body[m.end():m.end() + 300])
            if "rivalOffset" in lam or (champ and "val rivalOffset" in body[m.end():m.end() + 200]):
                plain.append((m.start(), (title, where, flag, "CHAMPION", int(champ.group(1)), int(champ.group(2)), [], variants, done_any)))
            else:
                plain.append((m.start(), (title, where, flag, "FIXED", int(re.search(r"\d+", lam).group()), 0, [], variants, done_any)))
    # In the list's order; every Boss( read, or the guide would quietly skip one.
    bosses = [b for _, b in sorted(out + plain, key=lambda e: e[0])]
    assert len(bosses) == body.count("Boss("), f"gen_guide: {decl}: read {len(bosses)} of {body.count('Boss(')} bosses"
    return bosses


def areas_of(name, decl):
    text = read(name)
    body = text[text.index(f"val {decl}"):]
    things = []
    for line in re.findall(r"^(\d+\|[A-Z]+\|.*)$", body, re.M):
        mapsec, kind, id_, flag, qty, wants, where = line.split("|", 6)
        things.append((int(mapsec), kind, int(id_), int(flag), int(qty), int(wants), where))
    things.sort(key=lambda t: t[0])
    return things


def c_str(s):
    if s is None:
        return "NULL"
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


def area_key(name):
    """The app's areaKey(): upper case, É -> E, letters and digits only."""
    return "".join(ch for ch in name.upper().replace("É", "E") if ch.isalnum())


def emit_guide(out, prefix, pages, bosses, verified, extra):
    for pi, (title, sections) in enumerate(pages):
        for si, (heading, note, entries, smark) in enumerate(sections):
            out.append(f"static const struct pd_guide_entry {prefix}_p{pi}_s{si}[] = {{")
            for e in entries:
                kind, vals = e["have"]
                vals = (vals + [0, 0, 0])[:3]
                areas = "|".join(area_key(a) for a in e["areas"])
                out.append(f"    {{ {c_str(e['title'])}, {c_str(e['answer'])}, {c_str(e['hint'])}, "
                           f"PD_HAVE_{kind}, {{ {vals[0]}, {vals[1]}, {vals[2]} }}, {c_str(areas or None)}, {e['rnd']} }},")
            out.append("};")
        out.append(f"static const struct pd_guide_section {prefix}_p{pi}[] = {{")
        for si, (heading, note, entries, smark) in enumerate(sections):
            out.append(f"    {{ {c_str(heading)}, {c_str(note)}, {prefix}_p{pi}_s{si}, {len(entries)}, {smark} }},")
        out.append("};")
    out.append(f"static const struct pd_guide_page {prefix}_pages[] = {{")
    for pi, (title, sections) in enumerate(pages):
        out.append(f"    {{ {c_str(title)}, {prefix}_p{pi}, {len(sections)} }},")
    out.append("};")
    for bi, (title, where, flag, kind, a, b, alts, variants, done_any, *_) in enumerate(bosses):
        if variants:
            vs = ", ".join(f"{{ {c_str(l)}, {t} }}" for l, t in variants)
            out.append(f"static const struct pd_variant {prefix}_vars{bi}[] = {{ {vs}, {{ NULL, 0 }} }};")
        if done_any:
            out.append(f"static const uint16_t {prefix}_any{bi}[] = {{ {', '.join(f'0x{f:X}' for f in done_any)}, 0 }};")
        if alts:
            if kind == "BY_COUNT":
                out.append(f"static const uint16_t {prefix}_alts{bi}[] = {{ {', '.join(f'0x{f:X}' for f in alts)}, 0 }};")
            else:
                pairs = ", ".join(f"0x{f:X}, {t}" for f, t in alts)
                out.append(f"static const uint16_t {prefix}_alts{bi}[] = {{ {pairs}, 0 }};")
        x = boss_extra(bosses[bi])
        if x.get("countTeams"):
            out.append(f"static const uint32_t {prefix}_count{bi}[] = {{ {', '.join(str(t) for t in x['countTeams'])} }};")
        if x.get("pickLabels"):
            out.append(f"static const char* const {prefix}_labels{bi}[] = {{ {', '.join(c_str(l) for l in x['pickLabels'])}, NULL }};")
    out.append(f"static const struct pd_boss {prefix}_bosses[] = {{")
    for bi, (title, where, flag, kind, a, b, alts, variants, done_any, *_) in enumerate(bosses):
        alt = f"{prefix}_alts{bi}" if alts else "NULL"
        var = f"{prefix}_vars{bi}" if variants else "NULL"
        anyf = f"{prefix}_any{bi}" if done_any else "NULL"
        x = boss_extra(bosses[bi])
        more = ""
        if x.get("doneVar"):
            more += f", .doneVar = 0x{x['doneVar']:X}, .doneVarMin = {x['doneVarMin']}"
        if x.get("countTeams"):
            more += f", .countTeams = {prefix}_count{bi}"
        if "addVar" in x:
            more += f", .addVar = 0x{x['addVar']:X}, .addMax = {x['addMax']}"
        if x.get("pickLabels"):
            more += f", .pickLabels = {prefix}_labels{bi}"
        more += f", .region = {x.get('region', 0)}"  # a designator, so the fields after it start at zero
        out.append(f"    {{ {c_str(title)}, {c_str(where)}, 0x{flag:X}, PD_BOSS_{kind}, {a}, {b}, {alt}, {var}, {anyf}{more} }},")
    out.append("};")
    out.append(f"const struct pd_guide {prefix} = {{")
    if ".regionOf" not in extra:
        extra += ", .regionOf = NULL"
    out.append(f"    {prefix}_pages, {len(pages)}, {prefix}_bosses, {len(bosses)}, {str(verified).lower()}, {extra},")
    out.append("};")
    out.append("")


def boss_extra(boss):
    return boss[9] if len(boss) > 9 else {}


def emit_areas(out, prefix, things):
    out.append(f"const struct pd_area_thing {prefix}[] = {{")
    for mapsec, kind, id_, flag, qty, wants, where in things:
        out.append(f"    {{ {mapsec}, PD_AREA_{kind}, {id_}, {flag}, {qty}, {wants}, {c_str(where)} }},")
    out.append("};")
    out.append(f"const int {prefix}_count = {len(things)};")
    out.append("")


def expand_helpers(slug, text):
    """Rewrite a hack's boss-building helper calls into plain Boss(...) lines (what bosses_of reads)."""
    if slug == "unbound":  # gym(n, title, where, difficult, vanilla, expert, insane) -> a Boss with its four teams
        text = re.sub(r'gym\((\d+), ("[^"]*"), ("[^"]*"), (\d+), (\d+), (\d+), (\d+)\)',
                      lambda m: f'Boss({m.group(2)}, {m.group(3)}, FLAG_BADGE01_GET + {int(m.group(1)) - 1}, variants = listOf("VANILLA" to {m.group(5)}, "DIFFICULT" to {m.group(4)}, "EXPERT" to {m.group(6)}, "INSANE" to {m.group(7)})) {{ {m.group(4)} }}',
                      text.replace("private fun gym(", "private fun gym_unused("))
    elif slug == "gaia":  # elite(n, title, trainer): done by its own flag or once the game is cleared
        text = re.sub(r'elite\((\d+), ("[^"]*"), (\d+)\)',
                      lambda m: f'Boss({m.group(2)}, "POKéMON LEAGUE", FLAG_DEFEATED_E4_1 + {m.group(1)}, doneAny = listOf(FLAG_SYS_GAME_CLEAR)) {{ {m.group(3)} }}',
                      text.replace("private fun elite(", "private fun elite_unused("))
    elif slug == "imperium":  # elite(n, title, "LABEL" to id, ...): done by its own flag or once champion
        text = re.sub(r'(?m)^    elite\((\d+), ("[^"]*"), ((?:"[^"]*" to \d+(?:, )?)+)\),',
                      lambda m: f'    Boss({m.group(2)}, "POKéMON LEAGUE", 0, doneAny = listOf(FLAG_DEFEATED_ELITE_4_SIDNEY + {m.group(1)}, FLAG_IS_CHAMPION), variants = listOf({m.group(3)})) {{ {re.search(r"to (\d+)", m.group(3)).group(1)} }},',
                      text)
    elif slug in ("recharged_emerald", "recharged_yellow"):  # gym / elite / league helpers; HARD = the record's second party
        hard = lambda i: int(i) + 65536
        nh = lambda i: f'listOf("NORMAL" to {i}, "HARD" to {hard(i)})'
        text = re.sub(r'(?m)^    gym\((\d+), ("[^"]*"), ("[^"]*"), (\d+)\),',
                      lambda m: f'    Boss({m.group(2)}, {m.group(3)}, FLAG_BADGE01_GET + {m.group(1)}, variants = {nh(m.group(4))}) {{ {m.group(4)} }},', text)
        text = re.sub(r'(?m)^    elite\((\d+), ("[^"]*"), (\d+)\),',
                      lambda m: f'    Boss({m.group(2)}, "POKéMON LEAGUE", FLAG_DEFEATED_ELITE_4_SIDNEY + {m.group(1)}, doneAny = listOf(FLAG_IS_CHAMPION), variants = {nh(m.group(3))}) {{ {m.group(3)} }},', text)
        text = re.sub(r'Boss\(\s*"CHAMPION WALLACE", "POKéMON LEAGUE", FLAG_IS_CHAMPION, variants = nh\((\d+)\),\s*doneIf = [^\n]*\n\s*\) \{ \d+ \},',
                      lambda m: f'Boss("CHAMPION WALLACE", "POKéMON LEAGUE", FLAG_IS_CHAMPION, doneAny = listOf(FLAG_SYS_GAME_CLEAR), variants = {nh(m.group(1))}) {{ {m.group(1)} }},', text)
        text = re.sub(r'(?m)^    league\((\d+), ("[^"]*"), (\d+), (\d+)\),',
                      lambda m: f'    Boss({m.group(2)}, "POKéMON LEAGUE", FLAG_DEFEATED_LORELEI + {m.group(1)}, trainer = league({m.group(3)}, {m.group(4)})) {{ {m.group(3)} }},', text)
    elif slug == "monomyth":  # badge / league helpers, each with its Heroic mode's team (FLAG_HEROIC)
        lams = []
        def badge(m):
            name = f"mmTeam{len(lams)}"
            lams.append(f"private val {name}: (SaveProgress) -> Int = {{ p -> when {{ p.flag(FLAG_HEROIC) -> {m.group(5)}\n else -> {m.group(4)} }} }}\n}}\n")
            return f'Boss({m.group(1)}, {m.group(2)}, FLAG_BADGE01_GET + {m.group(3)}, trainer = {name})'
        text = re.sub(r'badge\(("[^"]*"), ("[^"]*"), (\d+), (\d+), (\d+)\)', badge, text.replace("private fun badge(", "private fun badge_unused("))
        def league(m):
            i, h = m.group(3), m.group(4) or m.group(3)
            if i == h:
                return f'Boss({m.group(1)}, {m.group(2)}, TRAINER_FLAGS_START + {i}) {{ {i} }}'
            return (f'Boss({m.group(1)}, {m.group(2)}, TRAINER_FLAGS_START + {i}, doneAny = listOf(TRAINER_FLAGS_START + {h}), '
                    f'variants = listOf("NORMAL" to {i}, "HEROIC" to {h})) {{ {i} }}')
        text = re.sub(r'league\(("[^"]*"), ("[^"]*"), (\d+)(?:, (\d+))?\)', league, text.replace("private fun league(", "private fun league_unused("))
        # HEM and LOCKE together: both teams, NORMAL and HEROIC.
        text = re.sub(r'Boss\(\s*"LEADERS HEM AND LOCKE".*?\n    \),\n',
                      'Boss("LEADERS HEM AND LOCKE", "FORT ELDERFLOWER", FLAG_BADGE01_GET + 7, variants = listOf("HEM" to 493, '
                      '"LOCKE" to 498, "HEM (HEROIC)" to 558, "LOCKE (HEROIC)" to 559)) { 493 },\n', text, flags=re.S)
        text += "\n" + "".join(lams)
    elif slug == "celia":  # BLAINE's eight one-on-one battles
        text = text.replace('(1..8).map { "BATTLE $it" to 502 + it }',
                            "listOf(" + ", ".join(f'"BATTLE {i}" to {502 + i}' for i in range(1, 9)) + ")")
    elif slug == "ghost_grey":  # SUMMER's team answers the starter: all three are shown, done when any is beaten
        text = re.sub(r'Boss\(\s*"BEST FRIEND SUMMER", "FUJI CATACOMBS", FLAG_BADGE01_GET \+ 7,.*?\n    \) \{ p -> when.*?\},\n',
                      'Boss("BEST FRIEND SUMMER", "FUJI CATACOMBS", TRAINER_FLAGS_START + 435, '
                      'doneAny = listOf(TRAINER_FLAGS_START + 436, TRAINER_FLAGS_START + 437), '
                      'variants = listOf("LITWICK" to 435, "SPIRITLE" to 436, "ROWLET" to 437)) { 437 },\n', text, flags=re.S)
    return text


# --- hacks whose bosses come from Kotlin helpers / lambdas: their calls read straight into boss tuples ---
# A boss tuple: (title, where, doneFlag, kind, a, b, alts, variants, doneAny, extra); extra = boss_extra()'s keys.
HARD = 1 << 16


def _ev(env, expr):
    return Parser(tokenize(expr.strip()), env).expr()


def _str(env, expr):
    return _ev(env, expr)


def _list_body(text, decl):
    body = text[text.index(f"val {decl} = listOf("):]
    return body[:matching_paren(body, body.index("(")) + 1]


def _fixed(title, where, flag, trainer, done_any=(), variants=(), **extra):
    return (title, where, flag, "FIXED", trainer, 0, [], list(variants), list(done_any), extra)


def lambda_bosses(slug, text, env):
    """Boss tuples for the guides lambda / helper shapes build, or None (bosses_of reads the rest)."""
    E = lambda e: _ev(env, e)
    c = env["consts"]
    out = []
    if slug == "tmt2":
        body = _list_body(text, "BOSSES_TMT2")
        for m in re.finditer(r'Boss\(("[^"]*"), ("[^"]*"), ([^)]+?)\) \{ (\d+) \}|elite\((\d+), ("[^"]*")\) \{ ([^}]*) \}', body):
            if m.group(1):
                out.append(_fixed(E(m.group(1)), E(m.group(2)), E(m.group(3)), int(m.group(4))))
                continue
            n, title, lam = int(m.group(5)), E(m.group(6)), m.group(7)
            flag = c["FLAG_DEFEATED_ELITE_4_1"] + n
            r = re.fullmatch(r"\s*p -> (\w+) \+ p\.variable\((\w+)\)\.coerceIn\(0, (\d+)\)\s*", lam)
            if r:
                out.append((title, "POKéMON LEAGUE", flag, "VAR_ADD", c[r.group(1)], 0, [], [], [c["FLAG_IS_CHAMPION"]],
                            dict(addVar=c[r.group(2)], addMax=int(r.group(3)))))
            else:
                out.append(_fixed(title, "POKéMON LEAGUE", flag, int(lam), [c["FLAG_IS_CHAMPION"]]))
        assert len(out) == body.count("Boss(") + body.count("elite("), "tmt2: a boss not read"
        return out
    if slug == "seaglass":
        body = _list_body(text, "BOSSES_SEAGLASS")
        for m in re.finditer(r'Boss\(("[^"]*"), ("[^"]*"), ([^)]+?)\) \{ (\d+) \}|elite\((\d+), ("[^"]*"), (\d+)\)|'
                             r'Boss\(\s*("[^"]*"), ("[^"]*"), (\w+),\s*doneIf = \{ p -> p\.flag\((\w+)\) \|\| p\.flag\((\w+)\) \},\s*\) \{ (\d+) \}', body):
            if m.group(1):
                out.append(_fixed(E(m.group(1)), E(m.group(2)), E(m.group(3)), int(m.group(4))))
            elif m.group(5):
                out.append(_fixed(E(m.group(6)), "POKéMON LEAGUE", c["FLAG_DEFEATED_ELITE_4_SIDNEY"] + int(m.group(5)),
                                  int(m.group(7)), [c["FLAG_IS_CHAMPION"]]))
            else:
                out.append(_fixed(E(m.group(8)), E(m.group(9)), c[m.group(10)], int(m.group(13)), [c[m.group(12)]]))
        assert len(out) == body.count("Boss(") + body.count("elite("), "seaglass: a boss not read"
        return out
    if slug == "lazarus":
        body = _list_body(text, "BOSSES_LAZARUS")
        hard = c["FLAG_HARD_MODE"]
        for m in re.finditer(r'gym\((\d+), ("[^"]*"), ("[^"]*"), (\d+), (\d+)\)|ruins\((\d+), ("[^"]*"), (\d+), (\d+)\)|'
                             r'Boss\(\s*("[^"]*"), ("[^"]*"), (\w+),\s*doneIf = \{ p -> p\.flag\((\w+)\) \|\| p\.flag\((\w+)\) \}, trainer = pick\((\d+), (\d+)\),\s*\)', body):
            if m.group(1):
                t = (E(m.group(2)), E(m.group(3)), c["FLAG_BADGE01_GET"] + int(m.group(1)), int(m.group(4)), int(m.group(5)), [])
            elif m.group(6):
                t = (E(m.group(7)), "RUINS OF AHIYAWA", c["FLAG_BEAT_RUINS_1"] + int(m.group(6)), int(m.group(8)), int(m.group(9)),
                     [c["FLAG_BEAT_ANYALIOS"], c["FLAG_IS_CHAMPION"]])
            else:
                t = (E(m.group(10)), E(m.group(11)), c[m.group(12)], int(m.group(15)), int(m.group(16)), [c[m.group(14)]])
            title, where, flag, normal, hardId, any_ = t
            out.append((title, where, flag, "BY_FLAGS", normal, 0, [(hard, hardId)], [], any_))
        assert len(out) == body.count("Boss(") + body.count("gym(") + body.count("ruins("), "lazarus: a boss not read"
        return out
    if slug in ("soulgold", "heart_and_soul"):
        up = "SOULGOLD" if slug == "soulgold" else "HEART_AND_SOUL"
        body = _list_body(text, f"BOSSES_{up}")
        middle = {}
        for m in re.finditer(r"val (\w+) = middleGym\((\d+), (\d+), (\d+), (\w+), (\w+)\)", text):
            middle[m.group(1)] = ([int(m.group(i)) for i in (2, 3, 4)], [c[m.group(5)], c[m.group(6)]])
        league = re.search(r"fun league\(state: Int\).*?\{ p -> (.*?) \}", text, re.S).group(1)
        lflags = [c[f] for f in re.findall(r"p\.flag\((\w+)\)", league)]
        lvar = c[re.search(r"p\.variable\((\w+)\) >= state", league).group(1)]
        nh = ["NORMAL", "HARD"]
        pat = (r'johto\((\d+), ("[^"]*"), ("[^"]*"), (\d+)\)|middle\((\d+), ("[^"]*"), ("[^"]*"), (\w+)\)|'
               r'elite\((\d+), ("[^"]*"), (\d+)\)|kanto\((\d+), ("[^"]*"), ("[^"]*"), (\d+)\)|'
               r'Boss\(\s*("[^"]*"), ("[^"]*"), ([^,]+), variants = nh\((\d+)\),\s*doneIf = \{ (\w+ -> )?\w+\.flag\((\w+)\) \|\| \w+\.flag\(([^)]+)\) \},?\s*\) \{ \d+ \}|'
               r'Boss\(("[^"]*"), ("[^"]*"), ([^,]+), variants = nh\((\d+)\)\) \{ \d+ \}|'
               r'Boss\(("[^"]*"), ("[^"]*"), ([^,]+?)\) \{ (\d+) \}|'
               r'Boss\(("[^"]*"), ("[^"]*"), ([^,]+?),\s*trainer = middleGym\((\d+), (\d+), (\d+), (\w+), (\w+)\)\)|'
               r'Boss\(("[^"]*"), ("[^"]*"), 0, doneIf = league\((\d+)\)\) \{ (\d+) \}')
        for m in re.finditer(pat, body):
            g = m.groups()
            if g[0]:
                out.append(_fixed(E(g[1]), E(g[2]), c["FLAG_BADGE01_GET"] + int(g[0]), int(g[3]), pickLabels=nh))
            elif g[4]:
                teams, flags = middle[g[7]]
                out.append((E(g[5]), E(g[6]), c["FLAG_BADGE01_GET"] + int(g[4]), "BY_COUNT", 0, 0, flags, [], [],
                            dict(countTeams=teams, pickLabels=nh)))
            elif g[8]:
                out.append(_fixed(E(g[9]), "POKéMON LEAGUE", 0, int(g[10]), lflags, pickLabels=nh, doneVar=lvar, doneVarMin=int(g[8])))
            elif g[11]:
                out.append(_fixed(E(g[12]), E(g[13]), c["FLAG_DEFEATED_PEWTER_GYM"] + int(g[11]), int(g[14])))
            elif g[15]:
                out.append(_fixed(E(g[15]), E(g[16]), E(g[17]), int(g[18]), [E(g[21])], pickLabels=nh))
            elif g[22]:
                out.append(_fixed(E(g[22]), E(g[23]), E(g[24]), int(g[25]), pickLabels=nh))
            elif g[26]:
                out.append(_fixed(E(g[26]), E(g[27]), E(g[28]), int(g[29])))
            elif g[30]:
                out.append((E(g[30]), E(g[31]), E(g[32]), "BY_COUNT", 0, 0, [c[g[36]], c[g[37]]], [], [],
                            dict(countTeams=[int(g[33]), int(g[34]), int(g[35])])))
            else:
                out.append(_fixed(E(g[38]), E(g[39]), 0, int(g[41]), lflags, doneVar=lvar, doneVarMin=int(g[40])))
        n = body.count("Boss(") + body.count("johto(") + body.count("middle(") + body.count("elite(") + body.count("kanto(")
        assert len(out) == n, f"{slug}: read {len(out)} of {n} bosses"
        return out
    if slug == "glazed":
        names = re.findall(r'"(\w+)"', re.search(r"BOSSES_GLAZED = listOf\(([^)]*)\)", text).group(1))
        m = re.search(r'Boss\("LEADER \$name", "TUNOD GYM \$\{i \+ 1\}", FLAG_BADGE01_GET \+ i\) \{ (\d+) \+ i \}', text)
        return [_fixed(f"LEADER {n}", f"TUNOD GYM {i + 1}", c["FLAG_BADGE01_GET"] + i, int(m.group(1)) + i) for i, n in enumerate(names)]
    if slug == "mariomon":
        trip = re.findall(r'Triple\("([^"]*)", "([^"]*)", (\d+)\)', text)
        assert 'Boss("STAR ${i + 1} $name", where, FLAG_BADGE01_GET + i) { id }' in text
        return [_fixed(f"STAR {i + 1} {n}", w, c["FLAG_BADGE01_GET"] + i, int(t)) for i, (n, w, t) in enumerate(trip)]
    if slug == "amethyst":
        body = _list_body(text, "BOSSES_AMETHYST")
        modes = re.findall(r'"([^"]*)"', re.search(r"MODES = listOf\(([^)]*)\)", text).group(1))
        badges = [c["FLAG_BADGE01_GET"] + i for i in range(8)]
        for m in re.finditer(r'gym\((\d+), ("[^"]*"), ("[^"]*"), (\d+)\)|byBadges\((\d+), ("[^"]*"), ("[^"]*")\) \{ b -> ([^}]*) \}', body):
            if m.group(1):
                out.append(_fixed(E(m.group(2)), E(m.group(3)), c["FLAG_BADGE01_GET"] + int(m.group(1)) - 1, int(m.group(4)), pickLabels=modes))
                continue
            # `if (b <= 2) 105 else if (b == 3) 256 else 257`: the team for each badge count 0..8
            conds = re.findall(r"if \(b (<=|==) (\d+)\) (\d+)", m.group(8))
            last = int(re.search(r"else (\d+)\s*$", m.group(8)).group(1))
            def pick(b):
                for op, n, t in conds:
                    if (op == "<=" and b <= int(n)) or (op == "==" and b == int(n)):
                        return int(t)
                return last
            out.append((E(m.group(6)), E(m.group(7)), c["FLAG_BADGE01_GET"] + int(m.group(5)) - 1, "BY_COUNT", 0, 0, badges, [], [],
                        dict(countTeams=[pick(b) for b in range(9)], pickLabels=modes)))
        assert len(out) == body.count("gym(") + body.count("byBadges("), "amethyst: a boss not read"
        return out
    if slug == "dreamstone":
        body = _list_body(text, "BOSSES_DREAMSTONE")
        var = c["VAR_DIFFICULTY"]
        assert "if (p.variable(VAR_DIFFICULTY) == 0) id or (1 shl TRAINER_TABLE_SHIFT) else id" in text
        for m in re.finditer(r'badge\(("[^"]*"), ("[^"]*"), (\d+), (\d+)\)|cape\(("[^"]*"), (\d+)\)', body):
            if m.group(1):
                title, where, flag, id_ = E(m.group(1)), E(m.group(2)), c["FLAG_BADGE01_GET"] + int(m.group(3)), int(m.group(4))
            else:
                title, where, flag, id_ = E(m.group(5)), "VICTORY CAPE", c["TRAINER_FLAGS_START"] + int(m.group(6)), int(m.group(6))
            out.append((title, where, flag, "VAR_IS", id_, id_ | HARD, [], [], [], dict(addVar=var, addMax=0)))
        assert len(out) == body.count("badge(") + body.count("cape("), "dreamstone: a boss not read"
        return out
    if slug == "quetzal":
        johto = lambda i: HARD | (i - 0x1000)
        kanto = lambda i: 2 * HARD | (i - 0x2000)
        envq = dict(env)
        def tid(e):
            e = e.strip()
            m = re.fullmatch(r"(johto|kanto)\((0x[0-9A-Fa-f]+)\)", e)
            return (johto if m.group(1) == "johto" else kanto)(int(m.group(2), 16)) if m else int(e, 0)
        def done_of(lam):
            if not lam:
                return [], {}
            m = re.fullmatch(r"johtoLeague\((\d+)\)", lam)
            if m:
                return [c["FLAG_JOHTO_CHAMPION"]], dict(doneVar=c["VAR_JOHTO_LEAGUE"], doneVarMin=int(m.group(1)))
            m = re.fullmatch(r"kantoLeague\((\w+)\)", lam)
            if m:
                f = m.group(1)
                return [c[f] if f in c else int(f, 0), c["FLAG_KANTO_CHAMPION"]], {}
            m = re.fullmatch(r"\{ p -> p\.flag\((\w+) \+ (\d+)\) \|\| p\.variable\((\w+)\) >= (\d+) \}", lam)
            if m:
                return [c[m.group(1)] + int(m.group(2))], dict(doneVar=c[m.group(3)], doneVarMin=int(m.group(4)))
            raise SyntaxError(f"quetzal: doneIf {lam}")
        def region_list(decl, region):
            body = _list_body(text, decl)
            res = []
            for m in re.finditer(r'boss\(\s*("[^"]*"), ("[^"]*"), ([^,]+), ((?:johto|kanto)\(0x\w+\)|\d+), ((?:johto|kanto)\(0x\w+\)|\d+)(?:,\s*doneIf = (johtoLeague\(\d+\)|kantoLeague\(\w+\)|\{[^}]*\}))?,?\s*\)', body):
                any_, x = done_of(m.group(6))
                flag = E(m.group(3))
                x["region"] = region
                res.append(_fixed(E(m.group(1)), E(m.group(2)), flag, tid(m.group(4)), any_,
                                  [("NORMAL", tid(m.group(4))), ("HARD", tid(m.group(5)))], **x))
            return res, body
        hoenn, hb = region_list("HOENN", 0)
        # Hoenn's Elite Four (a mapIndexed over four names) and WALLACE come after its gyms' listOf
        tail = text[text.index("val HOENN = listOf("):text.index("private fun johtoLeague")]
        e4 = re.findall(r'"(\w+)" to (\d+)', re.search(r'\+ listOf\(("[A-Z]+" to \d+(?:, )?)+\)', tail).group(0))
        base = int(re.search(r"id, (\d+) \+ n,", tail).group(1))
        for n, (name, id_) in enumerate(e4):
            hoenn.append(_fixed(f"ELITE FOUR {name}", "POKéMON LEAGUE", 0, int(id_), [c["FLAG_DEFEATED_SIDNEY"] + n, c["FLAG_DEFEATED_WALLACE"]],
                                [("NORMAL", int(id_)), ("HARD", base + n)], region=0))
        w = re.search(r'\} \+ boss\("CHAMPION WALLACE", "POKéMON LEAGUE", (\w+), (\d+), (\d+)\)', tail)
        hoenn.append(_fixed("CHAMPION WALLACE", "POKéMON LEAGUE", c[w.group(1)], int(w.group(2)), [],
                            [("NORMAL", int(w.group(2))), ("HARD", int(w.group(3)))], region=0))
        johto_l, jb = region_list("JOHTO", 1)
        kanto_l, kb = region_list("KANTO", 2)
        assert len(johto_l) == jb.count("boss(") and len(kanto_l) == kb.count("boss(") and len(hoenn) == hb.count("boss(") + 5
        return hoenn + johto_l + kanto_l
    return None


# --- Game Boy (Gen 1): GuideGen1Gen.kt's boss lines -> teams + bosses (RAM flags are packed into one array, see below) ---
GB_GAMES = [("yellow", "G1Y"), ("red", "G1R"), ("blue", "G1B"), ("yellow_legacy", "G1L")]
# The snapshot's flags array for a Gen 1 game: event flags, then the missable-object, hidden-item, trade and badge banks.
GB_BANK_BIT = {0: 0, 1: 320 * 8, 2: 320 * 8 + 256, 3: 320 * 8 + 256 + 112, 4: 320 * 8 + 256 + 112 + 16}


def gb_flag(f):
    return GB_BANK_BIT[f >> 16] + (f & 0xFFFF)


def emit_gb(out):
    text = read("GuideGen1Gen.kt")
    for slug, pre in GB_GAMES:
        m = re.search(rf'val {pre}_BOSSES_RAW: List<String> = listOf\(\s*"""(.*?)"""', text, re.S)
        lines = [l for l in m.group(1).split("\n") if l.strip()]
        teams, bosses = [], []  # bosses: [key, title, where, done, [(var or 0, team index)]]
        for l in lines:
            key, title, where, done, cond, mons = l.split("|")
            var = int(cond.split("=")[1]) if cond else -1
            ms = []
            for mon in [x for x in mons.split(";") if x.strip()]:
                p = mon.split(" ")
                moves = [int(x) for x in (p[3].split(",") if len(p) > 3 else []) if x]
                ms.append((int(p[0]), int(p[1]), int(p[2]), (moves + [0, 0, 0, 0])[:4]))
            assert 1 <= len(ms) <= 6, l
            teams.append((var, ms))
            if bosses and bosses[-1][0] == key:
                bosses[-1][4].append((var, len(teams) - 1))
            else:
                bosses.append([key, title, where, [gb_flag(int(x, 0)) for x in done.split("/") if x], [(var, len(teams) - 1)]])
        out.append(f"const struct pd_gb_team pd_gb_teams_{slug}[] = {{")
        for var, ms in teams:
            mm = ", ".join(f"{{ {sp}, {it}, {lv}, {{ {', '.join(map(str, mv))} }} }}" for lv, sp, it, mv in ms)
            out.append(f"    {{ {var}, {len(ms)}, {{ {mm} }} }},")
        out.append("    { 0, 0, { { 0, 0, 0, { 0, 0, 0, 0 } } } },")
        out.append("};")
        for bi, (key, title, where, done, ts) in enumerate(bosses):
            if len(ts) > 1:
                vs = ", ".join(f'{{ "", {ti} }}' for _, ti in ts)
                out.append(f"static const struct pd_variant pd_gb_{slug}_vars{bi}[] = {{ {vs}, {{ NULL, 0 }} }};")
                al = ", ".join(f"{v}, {ti}" for v, ti in ts)
                out.append(f"static const uint16_t pd_gb_{slug}_alts{bi}[] = {{ {al}, 0 }};")
            if len(done) > 1:
                out.append(f"static const uint16_t pd_gb_{slug}_any{bi}[] = {{ {', '.join(map(str, done[1:]))}, 0 }};")
        out.append(f"static const struct pd_boss pd_gb_{slug}_bosses[] = {{")
        for bi, (key, title, where, done, ts) in enumerate(bosses):
            multi = len(ts) > 1
            kind = "PD_BOSS_TEAMVAR" if multi else "PD_BOSS_FIXED"
            alts = f"pd_gb_{slug}_alts{bi}" if multi else "NULL"
            var = f"pd_gb_{slug}_vars{bi}" if multi else "NULL"
            anyf = f"pd_gb_{slug}_any{bi}" if len(done) > 1 else "NULL"
            out.append(f"    {{ {c_str(title)}, {c_str(where)}, {done[0]}, {kind}, {ts[0][1]}, 0, {alts}, {var}, {anyf}, .region = 0 }},")
        out.append("};")
        out.append(f"const struct pd_guide pd_guide_gb_{slug} = {{ NULL, 0, pd_gb_{slug}_bosses, {len(bosses)}, false, 0, 0, .regionOf = NULL }};")
        out.append("")
        emit_gb_wild(out, slug, pre, text)
        # HERE / WHERE IS's things (G1x_AREAS_RAW, keyed by wCurMap), each under its gen1Area: the first map with its town
        # map name, so a town's houses count with the town; flags packed like the bosses'.
        m = re.search(rf'val {pre}_AREAS_RAW: List<String> = listOf\(\s*"""(.*?)"""', text, re.S)
        things = []
        for line in [l for l in m.group(1).split("\n") if l.strip()]:
            mp, kind, id_, flag, qty, wants, where = line.split("|", 6)
            f = int(flag)
            things.append((gen1_area(int(mp)), kind, int(id_), gb_flag(f) if f else 0, int(qty), int(wants), where))
        things.sort(key=lambda t: t[0])
        emit_areas(out, f"pd_areas_gb_{slug}", things)
    # gen1Area for the snapshot: every wCurMap's area
    out.append("const uint8_t pd_gb_gen1_area[256] = {")
    out.append("    " + ", ".join(str(gen1_area(m)) for m in range(256)) + ",")
    out.append("};")
    out.append("")


def gen1_names():
    """mapSecDataYellow: wCurMap -> its town map name (the app's gen1Area reads Yellow's for all three)."""
    text = read("MapSecDataYellow.kt")
    body = text[text.index("val mapSecDataYellow"):]
    body = body[:body.index("\n)\n")]
    return {int(k): n for k, n in re.findall(r'(\d+) to MapSecInfo\("([^"]*)"', body)}


def gen1_area(mp):
    names = gen1_names()
    if mp not in names:
        return mp
    return min(k for k, n in names.items() if n == names[mp])


def emit_gb_wild(out, slug, pre, text):
    """G1x_WILD_RAW: G|map|rate|lv:sp x10, W|map|rate|lv:sp x10, S|map|odds:sp:lv ..., OLD|..., GOOD|... (Gen1World)."""
    m = re.search(rf'val {pre}_WILD_RAW: List<String> = listOf\(\s*"""(.*?)"""', text, re.S)
    maps, old, good = {}, [], []
    def rows(sv):
        return [tuple(int(x) for x in p.split(":")) for p in sv.split(" ") if p]
    for line in [l for l in m.group(1).split("\n") if l.strip()]:
        f = line.split("|")
        if f[0] in ("G", "W"):
            if int(f[2]) > 0:
                maps.setdefault(int(f[1]), {})[f[0]] = [tuple(int(x) for x in p.split(":")) for p in f[3].split(" ") if p]
        elif f[0] == "S":
            maps.setdefault(int(f[1]), {})["S"] = rows(f[2])
        elif f[0] == "OLD":
            old = rows(f[1])
        elif f[0] == "GOOD":
            good = rows(f[1])
    def slots(sl):  # {level, species} x10
        sl = (sl + [(0, 0)] * 10)[:10]
        return "{ " + ", ".join(f"{{ {lv}, {sp} }}" for lv, sp in sl) + " }"
    def fish(sl):  # {odds, species, level}
        sl = (sl + [(0, 0, 0)] * 10)[:10]
        return "{ " + ", ".join(f"{{ {o}, {sp}, {lv} }}" for o, sp, lv in sl) + " }"
    out.append(f"const struct pd_gb_wild pd_gb_wild_{slug}[] = {{")
    for mp in sorted(maps):
        e = maps[mp]
        out.append(f"    {{ {mp}, {len(e.get('G', []))}, {len(e.get('W', []))}, {len(e.get('S', []))}, "
                   f"{slots(e.get('G', []))}, {slots(e.get('W', []))}, {fish(e.get('S', []))} }},")
    out.append("    { -1, 0, 0, 0, { { 0, 0 } }, { { 0, 0 } }, { { 0, 0, 0 } } },")
    out.append("};")
    out.append(f"const struct pd_gb_fish pd_gb_old_rod_{slug} = {{ {len(old)}, {fish(old)} }};")
    out.append(f"const struct pd_gb_fish pd_gb_good_rod_{slug} = {{ {len(good)}, {fish(good)} }};")


# --- Crystal (GuideCrystal.kt over GuideCrystalGen.kt, the app's CrystalWorld) ---
# The snapshot's flags for Crystal: wEventFlags (2048), then the badge bank (0x10000+, 16) and the trade bank (0x20000+).
CR_BANK_BIT = {0: 0, 1: 2048, 2: 2048 + 16}


def cr_flag(f):
    return CR_BANK_BIT[f >> 16] + (f & 0xFFFF)


class _Shift:
    """A match read one group later (its first group is the boss function's Scaled)."""
    def __init__(self, m):
        self.m = m

    def group(self, i):
        return self.m.group(i + 1)


def emit_crystal(out, slug="crystal", gen_file="GuideCrystalGen.kt", pre="CR", bosses_val="BOSSES_CRYSTAL", fn="cr"):
    """Crystal and Crystal Legacy (the app's CrystalEngineWorld over each one's generated data)."""
    gen = read(gen_file)
    text = read("GuideCrystal.kt")
    c = consts(gen)

    def raw(decl):
        m = re.search(rf'val {decl}: List<String> = listOf\(\s*"""(.*?)"""', gen, re.S)
        return [l for l in m.group(1).split("\n") if l.strip()]
    # teams: KEY|class|party|Name|lv sp item m,m,m,m;...
    keys, teams = {}, []
    for line in raw(f"{pre}_BOSSES_RAW"):
        f = line.split("|")
        ms = []
        for mon in [x for x in f[4].split(";") if x.strip()]:
            p = mon.split(" ")
            moves = [int(x) for x in (p[3].split(",") if len(p) > 3 else []) if x]
            ms.append((int(p[0]), int(p[1]), int(p[2]), (moves + [0, 0, 0, 0])[:4]))
        keys[f[0]] = len(teams)
        teams.append(ms[:6])
    out.append(f"const struct pd_gb_team pd_gb_teams_{slug}[] = {{")
    for ms in teams:
        mm = ", ".join(f"{{ {sp}, {it}, {lv}, {{ {', '.join(map(str, mv))} }} }}" for lv, sp, it, mv in ms)
        out.append(f"    {{ -1, {len(ms)}, {{ {mm} }} }},")
    out.append("    { 0, 0, { { 0, 0, 0, { 0, 0, 0, 0 } } } },")
    out.append("};")
    # bosses: crBoss("TITLE", "WHERE", FLAG or 0, "KEY"[, crLeague(OWN)]) and RED's lambda (both flags)
    body = text[text.index(f"val {bosses_val} = listOf("):]
    body = body[:matching_paren(body, body.index("(")) + 1]
    bosses = []
    for m in re.finditer(fn + r'(Scaled)?Boss\(("[^"]*"), ("[^"]*"), (\w+), "(\w+)"(?:, ' + fn + r'League\((\w+)\))?\)(?: \{ p -> p\.flag\((\w+)\) && p\.flag\((\w+)\) \})?', body):
        scaled = bool(m.group(1))
        m = _Shift(m)
        title, where = Parser(tokenize(m.group(1)), {"consts": {}}).expr(), Parser(tokenize(m.group(2)), {"consts": {}}).expr()
        flag = 0 if m.group(3) == "0" else c[m.group(3)]
        any_ = [c[f"{pre}_EVENT_BEAT_ELITE_FOUR"], c[m.group(5)]] if m.group(5) else []
        all_ = [c[m.group(6)], c[m.group(7)]] if m.group(6) else []
        # A gym taken in any order scales by badge count (Crystal Legacy's clScaledBoss): 5 owned bring <key>_6, 6 <key>_7.
        counts = [keys.get(f"{m.group(4)}_6" if n == 5 else f"{m.group(4)}_7" if n == 6 else m.group(4), keys[m.group(4)])
                  for n in range(17)] if scaled else None
        bosses.append((title, where, flag, keys[m.group(4)], any_, all_, counts))
    assert len(bosses) == len(re.findall(fn + r"(?:Scaled)?Boss\(", body)), f"{slug}: a boss not read"
    badges = ", ".join(str(cr_flag(0x10000 + i)) for i in range(16))
    for bi, (title, where, flag, team, any_, all_, counts) in enumerate(bosses):
        if any_:
            out.append(f"static const uint16_t pd_gb_{slug}_any{bi}[] = {{ {', '.join(str(cr_flag(f)) for f in any_)}, 0 }};")
        if all_:
            out.append(f"static const uint16_t pd_gb_{slug}_all{bi}[] = {{ {', '.join(str(cr_flag(f)) for f in all_)}, 0 }};")
        if counts:
            out.append(f"static const uint16_t pd_gb_{slug}_badges{bi}[] = {{ {badges}, 0 }};")
            out.append(f"static const uint32_t pd_gb_{slug}_counts{bi}[] = {{ {', '.join(map(str, counts))} }};")
    out.append(f"static const struct pd_boss pd_gb_{slug}_bosses[] = {{")
    for bi, (title, where, flag, team, any_, all_, counts) in enumerate(bosses):
        more = f", .doneAll = pd_gb_{slug}_all{bi}" if all_ else ""
        anyf = f"pd_gb_{slug}_any{bi}" if any_ else "NULL"
        kind, alts = "PD_BOSS_FIXED", "NULL"
        if counts:
            kind, alts = "PD_BOSS_BY_COUNT", f"pd_gb_{slug}_badges{bi}"
            more += f", .countTeams = pd_gb_{slug}_counts{bi}"
        out.append(f"    {{ {c_str(title)}, {c_str(where)}, {cr_flag(flag) if flag else 0}, {kind}, {team}, 0, {alts}, NULL, {anyf}, .region = 0{more} }},")
    out.append("};")
    env = {"consts": {}, "template": lambda e: (_ for _ in ()).throw(SyntaxError(e))}
    pages = pages_of(text, env)
    emit_guide_pages(out, f"pd_gb_{slug}", pages)
    out.append(f"const struct pd_guide pd_guide_gb_{slug} = {{ pd_gb_{slug}_pages, {len(pages)}, pd_gb_{slug}_bosses, {len(bosses)}, false, 0, 0, .regionOf = NULL }};")
    out.append("")
    # wild: G|g|n|m,d,n rates|lv:sp x21; W|g|n|rate|lv:sp x3; F|g|n|group; R|g|n|set; FG|group|old|good|super; TG|group|d|n; TS|set|common|rare
    grass, water, fishMap, rockMap, treeMap, fish, timeFish, trees, rare = {}, {}, {}, {}, {}, {}, {}, {}, {}
    for line in raw(f"{pre}_WILD_RAW"):
        f = line.split("|")
        k = lambda: int(f[1]) << 8 | int(f[2])
        if f[0] == "G":
            grass[k()] = ([int(x) for x in f[3].split(",")], [tuple(int(y) for y in p.split(":")) for p in f[4].split(" ") if p])
        elif f[0] == "W":
            water[k()] = (int(f[3]), [tuple(int(y) for y in p.split(":")) for p in f[4].split(" ") if p])
        elif f[0] == "F":
            fishMap[k()] = int(f[3])
        elif f[0] == "R":
            rockMap[k()] = int(f[3])
        elif f[0] == "T":
            treeMap[k()] = int(f[3])
        elif f[0] == "FG":
            fish[int(f[1])] = [[tuple(int(y) for y in p.split(":")) for p in r.split(" ") if p] for r in f[2:]]
        elif f[0] == "TG":
            timeFish[int(f[1])] = [tuple(int(y) for y in x.split(":")) for x in f[2:4]]
        elif f[0] == "TS":
            # a headbutt / rock set: its common rows (rock smash, headbutt) and its rare trees'
            trees[int(f[1])] = [tuple(int(y) for y in p.split(":")) for p in f[2].split(" ") if p]
            rare[int(f[1])] = [tuple(int(y) for y in p.split(":")) for p in (f[3] if len(f) > 3 else "").split(" ") if p]
    mapkeys = sorted(set(grass) | set(water) | set(fishMap) | set(rockMap) | set(treeMap))
    def pairs(sl, n):
        sl = (sl + [(0, 0)] * n)[:n]
        return "{ " + ", ".join(f"{{ {a}, {b} }}" for a, b in sl) + " }"
    def triples(sl, n):
        sl = (sl + [(0, 0, 0)] * n)[:n]
        return "{ " + ", ".join(f"{{ {a}, {b}, {cc} }}" for a, b, cc in sl) + " }"
    out.append(f"const struct pd_gb2_wild pd_gb_wild_{slug}[] = {{")
    for k in mapkeys:
        gr = grass.get(k, ([0, 0, 0], []))
        wa = water.get(k, (0, []))
        out.append(f"    {{ 0x{k:04X}, {{ {', '.join(map(str, gr[0]))} }}, {pairs(gr[1], 21)}, {wa[0]}, {pairs(wa[1], 3)}, "
                   f"{fishMap.get(k, -1)}, {rockMap.get(k, -1)}, {treeMap.get(k, -1)} }},")
    out.append("    { -1, { 0, 0, 0 }, { { 0, 0 } }, 0, { { 0, 0 } }, -1, -1, -1 },")
    out.append("};")
    out.append(f"const struct pd_gb2_fish pd_gb_fish_{slug}[] = {{")
    for g in sorted(fish):
        rods = (fish[g] + [[], [], []])[:3]
        out.append(f"    {{ {g}, {{ {', '.join(triples(r, 4) for r in rods)} }}, {{ {', '.join(str(len(r)) for r in rods)} }} }},")
    out.append("    { -1, { { { 0, 0, 0 } } }, { 0 } },")
    out.append("};")
    out.append(f"const uint8_t pd_gb_time_fish_{slug}[][4] = {{  // day species, level; night species, level")
    for g in range(max(timeFish) + 1 if timeFish else 0):
        d, n = timeFish.get(g, [(0, 0), (0, 0)])
        out.append(f"    {{ {d[0]}, {d[1]}, {n[0]}, {n[1]} }},")
    out.append("};")
    out.append(f"const int pd_gb_time_fish_{slug}_count = {len(timeFish)};")
    out.append(f"const struct pd_gb2_rocks pd_gb_rocks_{slug}[] = {{")
    for t in sorted(trees):
        out.append(f"    {{ {t}, {len(trees[t])}, {triples(trees[t], 6)}, {len(rare[t])}, {triples(rare[t], 6)} }},")
    out.append("    { -1, 0, { { 0, 0, 0 } }, 0, { { 0, 0, 0 } } },")
    out.append("};")
    # landmarks: wMapGroup * 256 + wMapNumber -> landmark (MapSecDataCrystal.kt mapLandmarksCrystal; Crystal Legacy's map is Crystal's)
    if slug != "crystal":
        things = []
        for line in raw(f"GUIDE_AREAS_{slug.upper()}_RAW"):
            mp, kind, id_, flag, qty, wants, where = line.split("|", 6)
            f = int(flag)
            things.append((int(mp), kind, int(id_), cr_flag(f) if f else 0, int(qty), int(wants), where))
        things.sort(key=lambda t: t[0])
        emit_areas(out, f"pd_areas_gb_{slug}", things)
        return
    ml = read("MapSecDataCrystal.kt")
    b = ml[ml.index("val mapLandmarksCrystal"):]
    b = b[:b.index("\n)\n")]
    marks = sorted((int(k, 0), int(v)) for k, v in re.findall(r"(0x[0-9A-Fa-f]+|\d+) to (\d+)", b))
    out.append("const uint16_t pd_gb_landmarks_crystal[][2] = {")
    out += [f"    {{ 0x{k:04X}, {v} }}," for k, v in marks]
    out.append("};")
    out.append(f"const int pd_gb_landmarks_crystal_count = {len(marks)};")
    # areas by landmark, flags packed
    things = []
    for line in raw(f"GUIDE_AREAS_{slug.upper()}_RAW"):
        mp, kind, id_, flag, qty, wants, where = line.split("|", 6)
        f = int(flag)
        things.append((int(mp), kind, int(id_), cr_flag(f) if f else 0, int(qty), int(wants), where))
    things.sort(key=lambda t: t[0])
    emit_areas(out, f"pd_areas_gb_{slug}", things)


# --- Polished Crystal: its hand-written pages (GuidePolishedCrystal.kt); HERE and NEXT BOSS come from its world
# (pd_gb_world_gen.c, 3ds/tools/gen_gb_world.py: levels by badges, the evening's lists, the faithful builds' teams) ---
def emit_polished(out):
    text = read("GuidePolishedCrystal.kt")
    env = {"consts": consts(read("GuidePolishedCrystalGen.kt")), "template": lambda e: (_ for _ in ()).throw(SyntaxError(e))}
    pages = pages_of(text, env)
    emit_guide_pages(out, "pd_gb_polished", pages)
    out.append(f"const struct pd_guide pd_guide_gb_polished = {{ pd_gb_polished_pages, {len(pages)}, NULL, 0, false, 0, 0, .regionOf = NULL }};")
    out.append("")


def emit_guide_pages(out, prefix, pages):
    """The page arrays of emit_guide, alone (a Game Boy guide's TIPS)."""
    for pi, (title, sections) in enumerate(pages):
        for si, (heading, note, entries, smark) in enumerate(sections):
            out.append(f"static const struct pd_guide_entry {prefix}_p{pi}_s{si}[] = {{")
            for e in entries:
                kind, vals = e["have"]
                vals = (vals + [0, 0, 0])[:3]
                out.append(f"    {{ {c_str(e['title'])}, {c_str(e['answer'])}, {c_str(e['hint'])}, PD_HAVE_{kind}, "
                           f"{{ {vals[0]}, {vals[1]}, {vals[2]} }}, NULL, {e['rnd']} }},")
            out.append("};")
        out.append(f"static const struct pd_guide_section {prefix}_p{pi}[] = {{")
        for si, (heading, note, entries, smark) in enumerate(sections):
            out.append(f"    {{ {c_str(heading)}, {c_str(note)}, {prefix}_p{pi}_s{si}, {len(entries)}, {smark} }},")
        out.append("};")
    out.append(f"static const struct pd_guide_page {prefix}_pages[] = {{")
    for pi, (title, sections) in enumerate(pages):
        out.append(f"    {{ {c_str(title)}, {prefix}_p{pi}, {len(sections)} }},")
    out.append("};")


def main():
    fr_text = read("GuideFireRed.kt")
    em_text = read("GuideEmerald.kt")
    fr_consts = consts(fr_text)
    em_consts = consts(em_text)

    # ${prizeMons(leafGreen)}: the one template, `if (leafGreen) { "LG" } else { "FR" }`.
    prize = re.search(r'fun prizeMons\(leafGreen: Boolean\) = if \(leafGreen\) \{\s*"([^"]*)"\s*\} else \{\s*"([^"]*)"', fr_text)

    def fr_env(leaf_green):
        def template(expr):
            if expr.strip() == "prizeMons(leafGreen)":
                return prize.group(1) if leaf_green else prize.group(2)
            raise SyntaxError(f"gen_guide: unknown template {expr}")
        return {"consts": fr_consts, "template": template}

    def no_template(expr):
        raise SyntaxError(f"gen_guide: unknown template {expr}")

    em_env = {"consts": em_consts, "template": no_template}

    # Ruby and Sapphire: one text built per version (rubySapphireGuide(ruby)), its locals by version.
    rs_text = read("GuideRubySapphire.kt")
    rs_consts = consts(rs_text)

    def rs_env(ruby):
        local = {"team": "TEAM MAGMA" if ruby else "TEAM AQUA", "legend": "GROUDON" if ruby else "KYOGRE",
                 "roamer": "LATIOS" if ruby else "LATIAS"}
        nums = dict(rs_consts, legendNo=383 if ruby else 382, roamerNo=381 if ruby else 380)

        def template(expr):
            if expr.strip() in local:
                return local[expr.strip()]
            raise SyntaxError(f"gen_guide: unknown template {expr}")
        return {"consts": nums, "template": template, "strings": local}

    rs_bosses = bosses_of(rs_text, "BOSSES_RUBY_SAPPHIRE", rs_env(True))

    # Emerald Legacy: its own bosses (BRAWLY by flags), no hand-written WHERE IS (built from its areas at run time).
    el_text = read("GuideEmeraldLegacy.kt")
    el_env = {"consts": consts(el_text), "template": no_template}
    el_bosses = bosses_of(el_text, "BOSSES_EMERALD_LEGACY", el_env)

    # The League's rematch flag and the starter var (the champion's team).
    league_flag = fr_consts["FLAG_SYS_CAN_LINK_WITH_RS"]
    starter_var = fr_consts["VAR_STARTER_MON"]
    fr_bosses = bosses_of(fr_text, "BOSSES_FIRERED", fr_env(False))
    em_bosses = bosses_of(em_text, "BOSSES_EMERALD", em_env)
    em_verified = "verified = false" not in em_text

    # The hacks whose guide is the same DSL with plain bosses (no NORMAL/HARD variants, no custom lambdas): slug, Kotlin
    # name, constant name.
    hack_guides = []
    for slug, cls, up in HACK_GUIDES:
        text = read(f"Guide{cls}.kt")
        env = {"consts": consts(text), "template": no_template}
        try:
            text = expand_helpers(slug, text)
            env = {"consts": consts(text), "template": no_template}
            bosses = lambda_bosses(slug, text, env)
            if bosses is None and "bosses = BOSSES_FIRERED" in text:
                bosses = "FIRERED"  # FireRed's own (Ultra Violet), filled in below
            elif bosses is None and f"val BOSSES_{up}" not in text:
                bosses = []  # a guide with no NEXT BOSS (HERE and TIPS only)
            if bosses is None:
                bosses = bosses_of(text, f"BOSSES_{up}", env)
                lst = text[text.index(f"val BOSSES_{up}"):]
                lst = lst[lst.index("\n"):lst.index("\n)\n")]
                helpers = re.findall(r"^\s+(?!Boss\()([a-z]\w*)\(", lst, re.M)
                assert not helpers, f"bosses built by helper {helpers[0]}()"
            pages_of(text, env)
        except (AssertionError, SyntaxError, KeyError, ValueError) as e:
            print(f"gen_guide: {slug} left out: {e}")
            continue
        hack_guides.append((slug, cls, up, text, env, bosses))

    out = [
        "// Generated by 3ds/tools/gen_guide.py from the app's GuideFireRed.kt, GuideEmerald.kt, GuideRubySapphire.kt, GuideEmeraldLegacy.kt",
        "// and GuideAreas{FireRed,LeafGreen,Emerald,RubySapphire,EmeraldLegacy}Gen.kt - do not hand-edit.",
        '#include "pd_guide.h"',
        "",
    ]
    emit_guide(out, "pd_guide_firered", pages_of(fr_text, fr_env(False)), fr_bosses, True,
               f"0x{league_flag:X}, 0x{starter_var:X}")
    emit_guide(out, "pd_guide_leafgreen", pages_of(fr_text, fr_env(True)), fr_bosses, True,
               f"0x{league_flag:X}, 0x{starter_var:X}")
    emit_guide(out, "pd_guide_emerald", pages_of(em_text, em_env), em_bosses, em_verified, "0, 0")
    emit_guide(out, "pd_guide_ruby", pages_of(rs_text, rs_env(True)), rs_bosses,
               "verified = false" not in rs_text, "0, 0")
    emit_guide(out, "pd_guide_sapphire", pages_of(rs_text, rs_env(False)), rs_bosses,
               "verified = false" not in rs_text, "0, 0")
    emit_guide(out, "pd_guide_emerald_legacy", pages_of(el_text, el_env), el_bosses,
               "verified = false" not in el_text, "0, 0")
    for slug, cls, up, text, env, bosses in hack_guides:
        # Recharged Yellow's League rematch teams come once the game is cleared (its own flag, no starter var).
        extra = f"0x{env['consts']['FLAG_SYS_GAME_CLEAR']:X}, 0" if slug == "recharged_yellow" else "0, 0"
        if bosses == "FIRERED":
            bosses, extra = fr_bosses, f"0x{league_flag:X}, 0x{starter_var:X}"
        verified = "verified = false" not in text
        if slug == "quetzal":  # three campaigns, the player's region's first; Alpha 0.8.4 is Hoenn's alone
            emit_guide(out, "pd_guide_quetzal_v084", [], [b for b in bosses if not boss_extra(b).get("region")], verified, "0, 0")
            extra = "0, 0, .regionOf = pd_quetzal_region, .regionOrder = pd_quetzal_order"
        emit_guide(out, f"pd_guide_{slug}", pages_of(text, env), bosses, verified, extra)
        if os.path.exists(os.path.join(DATA, f"GuideAreas{cls}Gen.kt")):
            emit_areas(out, f"pd_areas_{slug}", areas_of(f"GuideAreas{cls}Gen.kt", f"GUIDE_AREAS_{up}_RAW"))
        else:
            assert slug in AREAS_OPTIONAL, slug
    # Releases with the same guide text but their own area data (the app's AMETHYST_V141, RECHARGED_YELLOW_V1xx GuideIds).
    for slug, cls, up in [("amethyst_v141", "AmethystV141", "AMETHYST_V141"), ("recharged_yellow_v180", "RechargedYellowV180", "RECHARGED_YELLOW_V180"),
                          ("recharged_yellow_v190", "RechargedYellowV190", "RECHARGED_YELLOW_V190"),
                          ("recharged_yellow_v194", "RechargedYellowV194", "RECHARGED_YELLOW_V194")]:
        emit_areas(out, f"pd_areas_{slug}", areas_of(f"GuideAreas{cls}Gen.kt", f"GUIDE_AREAS_{up}_RAW"))
    emit_gb(out)
    emit_crystal(out)
    emit_crystal(out, "crystal_legacy", "GuideCrystalLegacyGen.kt", "CL", "BOSSES_CRYSTAL_LEGACY", "cl")
    emit_polished(out)
    emit_areas(out, "pd_areas_emerald_legacy", areas_of("GuideAreasEmeraldLegacyGen.kt", "GUIDE_AREAS_EMERALD_LEGACY_RAW"))
    emit_areas(out, "pd_areas_ruby_sapphire", areas_of("GuideAreasRubySapphireGen.kt", "GUIDE_AREAS_RUBY_SAPPHIRE_RAW"))
    emit_areas(out, "pd_areas_firered", areas_of("GuideAreasFireRedGen.kt", "GUIDE_AREAS_FIRERED_RAW"))
    emit_areas(out, "pd_areas_leafgreen", areas_of("GuideAreasLeafGreenGen.kt", "GUIDE_AREAS_LEAFGREEN_RAW"))
    emit_areas(out, "pd_areas_emerald", areas_of("GuideAreasEmeraldGen.kt", "GUIDE_AREAS_EMERALD_RAW"))

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    print(f"wrote {os.path.relpath(OUT, ROOT)}: FR {len(fr_bosses)} bosses, EM {len(em_bosses)} bosses")


if __name__ == "__main__":
    main()
