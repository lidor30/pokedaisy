#!/usr/bin/env python3
"""The Azahar submodule holds nothing but our patch, and our build never takes its key blob.

Like check_melonds_patch.py: the build applies third_party/patches/azahar-pokedaisy.patch to the submodule's working tree
(apply_azahar_patch.cmake), .gitmodules sets `ignore = dirty`, and this check (part of `make check`) passes only a
submodule that is clean or changed by exactly the patch. To keep an edit:

    git -C third_party/azahar diff > third_party/patches/azahar-pokedaisy.patch

Azahar's CMake turns ENABLE_BUILTIN_KEYBLOB on by default (console keys, obfuscated, in src/core/hw/default_keys.h). We
never ship keys: n3ds/src/main/cpp/CMakeLists.txt forces it off and stops the configure if it comes back on, and this
check fails if either line goes.
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AZAHAR = ROOT / "third_party" / "azahar"
PATCH = ROOT / "third_party" / "patches" / "azahar-pokedaisy.patch"
BUILD = ROOT / "n3ds" / "src" / "main" / "cpp" / "CMakeLists.txt"
FIX = "git -C third_party/azahar diff > third_party/patches/azahar-pokedaisy.patch"
KEYBLOB_LINES = (
    'set(ENABLE_BUILTIN_KEYBLOB OFF CACHE BOOL "" FORCE)',
    "if(ENABLE_BUILTIN_KEYBLOB)",
)


def git(*args: str, stdin: bytes | None = None) -> bytes:
    return subprocess.run(["git", "-C", str(AZAHAR), *args], input=stdin, capture_output=True, check=True).stdout


def patch_id(diff: bytes) -> str:
    out = git("patch-id", "--stable", stdin=diff).split()
    return out[0].decode() if out else ""


def keyblob_off() -> int:
    if not BUILD.exists():
        print(f"Azahar keys: {BUILD.relative_to(ROOT)} missing")
        return 1
    text = BUILD.read_text()
    missing = [line for line in KEYBLOB_LINES if line not in text]
    if missing:
        print(f"Azahar keys: {BUILD.relative_to(ROOT)} must force ENABLE_BUILTIN_KEYBLOB off and refuse it on; missing:")
        for line in missing:
            print(f"  {line}")
        return 1
    print("Azahar keys: the build forces the key blob off")
    return 0


def patch_only() -> int:
    if not (AZAHAR / ".git").exists():
        print("Azahar patch: no third_party/azahar checkout, skipped (make submodules)")
        return 0
    untracked = [line[3:] for line in git("status", "--porcelain", "--untracked-files=all", "--ignore-submodules=all")
                 .decode().splitlines() if line.startswith("??")]
    if untracked:
        print("Azahar patch: files in third_party/azahar the patch doesn't have:")
        for name in untracked:
            print(f"  {name}")
        print(f"Delete them, or add them to the patch: git -C third_party/azahar add -N <file>, then {FIX}")
        return 1
    diff = git("diff", "--no-color", "--no-ext-diff", "--ignore-submodules=all", "HEAD")
    if not diff:
        print("Azahar patch: submodule clean (the build applies the patch)")
        return 0
    if patch_id(diff) == patch_id(PATCH.read_bytes()):
        print("Azahar patch: submodule holds exactly the patch")
        return 0
    print("Azahar patch: third_party/azahar has changes that aren't the patch:")
    print(git("diff", "--stat", "--ignore-submodules=all", "HEAD").decode().rstrip())
    print(f"To keep them: {FIX}")
    print("To drop them: git -C third_party/azahar checkout . (the next build applies the patch again)")
    return 1


if __name__ == "__main__":
    sys.exit(keyblob_off() | patch_only())
