#!/usr/bin/env python3
"""Find the JVM test source sets matched by a Gradle `--tests` filter.

`make test-quick T='*FooTest'` uses this automatically. A filter matching
nothing answers `app` so Gradle still reports the invalid filter.
"""
import fnmatch
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE_SETS = {
    "app": ("test",),
    "shared": ("androidUnitTest", "commonTest"),
    "shared-ui": ("androidUnitTest", "commonTest"),
}


def matches_class(class_name, pattern):
    # The last component may select a method, rather than a test class.
    return (fnmatch.fnmatchcase(class_name, pattern)
            or fnmatch.fnmatchcase(class_name, pattern.rsplit(".", 1)[0])
            or fnmatch.fnmatchcase(class_name + ".x", pattern))


def modules(pattern):
    found = []
    for module, sets in SOURCE_SETS.items():
        for source_set in sets:
            source = ROOT / module / "src" / source_set / "kotlin"
            if any(matches_class(test.relative_to(source).as_posix()[:-3].replace("/", "."), pattern)
                   for test in source.rglob("*Test.kt")):
                found.append(module)
                break
    return found or ["app"]


if __name__ == "__main__":
    print(" ".join(modules(sys.argv[1] if len(sys.argv) > 1 else "")))
