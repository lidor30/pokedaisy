#!/usr/bin/env python3
"""Holds the C port's Game Boy dex pages, pictures and wild lists against the app's own, entry for entry.

    python3 3ds/test/gb_check.py <gb_check binary> <game export dir> <ROMs folder>

The export (ui-preview's gameExport) writes gbcheck/dex-<crc>.json and gbcheck/wild-<guide>.json through the app's
readers; gb_check prints the same from the C. Any line that differs is printed, and the script fails.
"""
import json
import os
import subprocess
import sys


def main():
    binary, export, roms = sys.argv[1:4]
    files = {}
    for root, _, names in os.walk(roms):
        for n in names:
            files[n] = os.path.join(root, n)
    games = [g for g in json.load(open(os.path.join(export, "games.json"))) if g.get("gb")]
    bad = 0
    wild_done = set()
    for g in games:
        rom = files[g["file"]]
        path = os.path.join(export, "gbcheck", "dex-%08x.json" % g["crc32"])
        if os.path.exists(path):
            want = json.load(open(path))
            run = subprocess.run([binary, rom], capture_output=True, text=True, errors="replace")
            if run.returncode:
                print(g["file"], "exit", run.returncode)
                bad += 1
                continue
            got = run.stdout.splitlines()
            wrong = 0
            if len(got) != len(want):
                print(g["file"], "entries", len(got), "want", len(want))
                wrong += 1
            for line, w in zip(got, want):
                f = line.split("|")
                dex, species, cat, dm, hg, desc, t1, t2, stats, hin, lbs, ht, wt, ok, h = w
                exp = [str(dex), str(species), cat.replace("|", "/"), str(dm), str(hg), desc.replace("|", "/").replace("\n", "~"),
                       str(t1), str(t2), ",".join(map(str, stats)), str(hin), str(lbs), ht, wt, str(ok), str(h if ok else 0)]
                if f != exp:
                    wrong += 1
                    if wrong <= 3:
                        for a, b in zip(f, exp):
                            if a != b:
                                print(f"  {g['file']} No.{dex}: {a!r} want {b!r}")
            print("ok  " if not wrong else "FAIL", g["file"], len(got), "pages")
            bad += wrong
        gid = g.get("guide")
        wpath = os.path.join(export, "gbcheck", f"wild-{gid}.json")
        if gid and gid not in wild_done and os.path.exists(wpath):
            wild_done.add(gid)
            want = {}
            for tod, badges, group, num, lists in json.load(open(wpath)):
                if gid in ("YELLOW", "RED", "BLUE", "YELLOW_LEGACY"):
                    group = 0
                key = f"{tod} {badges} {group} {num}"
                want[key] = " ".join("|" + "".join(f" {a}:{b}:{c}:{d}" for a, b, c, d in l) for l in lists)
            run = subprocess.run([binary, rom, "wild"], capture_output=True, text=True)
            got = {}
            for line in run.stdout.splitlines():
                p = line.split(" ", 4)
                got[" ".join(p[:4])] = p[4]
            wrong = sum(1 for k in set(want) | set(got) if want.get(k) != got.get(k))
            for k in sorted(set(want) | set(got)):
                if want.get(k) != got.get(k) and wrong:
                    print(" ", gid, k, "\n    got ", got.get(k), "\n    want", want.get(k))
                    break
            print("ok  " if not wrong else "FAIL", gid, len(want), "wild lists")
            bad += wrong
    print("all equal" if not bad else f"{bad} differ")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
