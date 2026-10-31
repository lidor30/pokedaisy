#!/usr/bin/env python3
"""The melonDS submodule holds nothing but our patch.

The build applies third_party/patches/melonds-pokedaisy.patch to the submodule's working tree (apply_melonds_patch.cmake),
so .gitmodules sets `ignore = dirty` for it and `git status` stays quiet. That would also hide an edit inside melonDS
that never reached the patch, so this check (part of `make check`) passes only a submodule that is clean or changed by
exactly the patch, compared by `git patch-id` (formatting and diff settings don't matter). To keep an edit:

    git -C third_party/melonds diff > third_party/patches/melonds-pokedaisy.patch
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MELONDS = ROOT / "third_party" / "melonds"
PATCH = ROOT / "third_party" / "patches" / "melonds-pokedaisy.patch"
FIX = "git -C third_party/melonds diff > third_party/patches/melonds-pokedaisy.patch"


def git(*args: str, stdin: bytes | None = None) -> bytes:
    return subprocess.run(["git", "-C", str(MELONDS), *args], input=stdin, capture_output=True, check=True).stdout


def patch_id(diff: bytes) -> str:
    out = git("patch-id", "--stable", stdin=diff).split()
    return out[0].decode() if out else ""


def main() -> int:
    if not (MELONDS / ".git").exists():
        print("melonDS patch: no third_party/melonds checkout, skipped (make submodules)")
        return 0
    untracked = [line[3:] for line in git("status", "--porcelain", "--untracked-files=all").decode().splitlines()
                 if line.startswith("??")]
    if untracked:
        print("melonDS patch: files in third_party/melonds the patch doesn't have:")
        for name in untracked:
            print(f"  {name}")
        print(f"Delete them, or add them to the patch: git -C third_party/melonds add -N <file>, then {FIX}")
        return 1
    diff = git("diff", "--no-color", "--no-ext-diff", "HEAD")
    if not diff:
        print("melonDS patch: submodule clean (the build applies the patch)")
        return 0
    if patch_id(diff) == patch_id(PATCH.read_bytes()):
        print("melonDS patch: submodule holds exactly the patch")
        return 0
    print("melonDS patch: third_party/melonds has changes that aren't the patch:")
    print(git("diff", "--stat", "HEAD").decode().rstrip())
    print(f"To keep them: {FIX}")
    print("To drop them: git -C third_party/melonds checkout . (the next build applies the patch again)")
    return 1


if __name__ == "__main__":
    sys.exit(main())
