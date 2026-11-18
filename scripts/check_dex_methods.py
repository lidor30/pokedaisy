#!/usr/bin/env python3
"""Fail on app methods too big for ART to compile.

ART's compilers (dex2oat at install, the JIT while running) skip any method over 10,000 dex code units (CompilerOptions'
huge-method threshold), so such a method always runs in the interpreter. 1.6.0's EmulatorEngine.loop was 7,784 in the
release and 18,500 in a debuggable build, where every game frame then ran interpreted (logcat: "Method exceeds compiler
instruction limit" every ~0.35 s). Run on a built APK:

    python3 scripts/check_dex_methods.py app/build/outputs/apk/release/*.apk

Data tables are left out: a generated table's static initialiser or lazy block runs once, interpreted or not.
"""
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

LIMIT = 10_000   # ART's default huge-method threshold
WARN = 8_000     # close enough to the limit that the next change may cross it

# Run-once data: static initialisers and lazy table blocks (`by lazy { ... }`) of generated tables.
DATA = re.compile(r"(<clinit>|_delegate\$lambda\$\d+|\$\d+;'? 'invoke)")


def dexdump_path() -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.expanduser("~/Library/Android/sdk")
    tools = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "dexdump")))
    if not tools:
        sys.exit("dexdump not found: set ANDROID_HOME")
    return tools[-1]


def methods(apk: str):
    dd = dexdump_path()
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            path = z.extract(name, tmp)
            out = subprocess.run([dd, "-d", path], capture_output=True, text=True, errors="replace").stdout
            cls = meth = None
            for line in out.splitlines():
                s = line.strip()
                if s.startswith("Class descriptor"):
                    cls = s.split(":", 1)[1].strip().strip("'")
                elif s.startswith("name") and ":" in s:
                    meth = s.split(":", 1)[1].strip().strip("'")
                elif s.startswith("insns size") and cls:
                    units = int(s.split(":", 1)[1].split()[0])
                    yield units, cls, meth


def main() -> int:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    over, near = [], []
    for units, cls, meth in methods(sys.argv[1]):
        if not cls.startswith("Lcom/pokedaisy/"):
            continue
        # Only the data layer's tables are left out: a UI lambda can look like a lazy block ($N.invoke).
        if meth == "<clinit>" or ("/companion/data/" in cls and DATA.search(f"{cls}' '{meth}")):
            continue
        if units > LIMIT:
            over.append((units, cls, meth))
        elif units > WARN:
            near.append((units, cls, meth))
    for units, cls, meth in sorted(near, reverse=True):
        print(f"near the limit: {units:6d} {cls}.{meth}")
    for units, cls, meth in sorted(over, reverse=True):
        print(f"TOO BIG (never compiled): {units:6d} {cls}.{meth}")
    if over:
        print(f"{len(over)} method(s) over {LIMIT} code units: split them (see EmulatorEngine.GameRun).")
        return 1
    print(f"OK: no app method over {LIMIT} code units ({len(near)} over {WARN}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
