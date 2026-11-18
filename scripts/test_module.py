#!/usr/bin/env python3
"""Which Gradle module(s) hold the unit tests a `--tests` filter names: `app`, `shared` or `app shared`.

`make test-quick T='*FooTest'` asks this, so a focused run needs no MODULE= (the Makefile's MODULE= still forces one).
The tests all live in app/src/test; those in companion/data and companion/i18n run with :shared, but the root
build.gradle.kts's appOnlyTests, which run with :app. A filter matching nothing answers `app`, so Gradle still fails on
a misspelled filter ("No tests found for given includes").
"""
import fnmatch
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TESTS = ROOT / "app/src/test/kotlin"
SHARED = ("com/pokedaisy/app/companion/data/", "com/pokedaisy/app/companion/i18n/")


def app_only():
    m = re.search(r'appOnlyTests\s+by\s+extra\(\s*listOf\((.*?)\)', (ROOT / "build.gradle.kts").read_text(), re.S)
    return set(re.findall(r'"(\w+)"', m.group(1))) if m else set()


def modules(pattern):
    only = app_only()
    found = set()
    for f in TESTS.rglob("*Test.kt"):
        rel = f.relative_to(TESTS).as_posix()
        fqn = rel[:-3].replace("/", ".")
        # A pattern may name a method too (com.x.FooTest.bar, *FooTest.bar*): match the class part as well.
        if not (fnmatch.fnmatchcase(fqn, pattern) or fnmatch.fnmatchcase(fqn, pattern.rsplit(".", 1)[0])
                or fnmatch.fnmatchcase(fqn + ".x", pattern)):
            continue
        found.add("shared" if rel.startswith(SHARED) and f.stem not in only else "app")
    for module in ("shared", "shared-ui"):
        for f in (ROOT / module / "src/commonTest/kotlin").rglob("*Test.kt"):
            fqn = f.relative_to(ROOT / module / "src/commonTest/kotlin").as_posix()[:-3].replace("/", ".")
            if (fnmatch.fnmatchcase(fqn, pattern) or fnmatch.fnmatchcase(fqn, pattern.rsplit(".", 1)[0])
                    or fnmatch.fnmatchcase(fqn + ".x", pattern)):
                found.add(module)
    return [m for m in ("app", "shared", "shared-ui") if m in found] or ["app"]


if __name__ == "__main__":
    print(" ".join(modules(sys.argv[1] if len(sys.argv) > 1 else "")))
