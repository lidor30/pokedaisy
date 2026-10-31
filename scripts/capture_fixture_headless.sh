#!/usr/bin/env bash
# Captures a real memory snapshot for one game via headless libmgba - NO
# Android device, emulator, or adb involved - into
# app/src/test/resources/fixtures/<key>/{ewram.bin,iwram.bin,README.txt},
# exactly like scripts/capture_fixture.sh does from a physical device. The
# two are interchangeable as far as the JVM tests are concerned; this one
# also runs unmodified on a Linux CI runner (e.g. GitHub Actions), since it's
# just a Docker container + libmgba, not anything Android-specific.
#
# Backed by native-capture/mgba_dump.c, built and run
# inside the pokedaisy-capture image (native-capture/Dockerfile, built on
# first use). Drives the ROM through a
# scripted boot sequence (native-capture/boot/default.txt by default) to
# clear the intro/title/quest-log-recap screens before dumping, since a save
# file alone doesn't put the game on the field - see that file's own comment.
#
# Usage: scripts/capture_fixture_headless.sh <key> [--script FILE]
#   <key> must match host_roms.conf's first column.
#   --script FILE overrides the default boot sequence (native-capture/boot/default.txt) -
#   use this if a particular save/hack needs a different number of recap presses.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CAPTURE_DIR="$SCRIPT_DIR/../native-capture"
CONF="$SCRIPT_DIR/host_roms.conf"
OUT_ROOT="$SCRIPT_DIR/../app/src/test/resources/fixtures"
IMAGE="pokedaisy-capture"

KEY="${1:-}"
BOOT_SCRIPT="$CAPTURE_DIR/boot/default.txt"
if [[ "${2:-}" == "--script" ]]; then
    BOOT_SCRIPT="$3"
fi

if [[ -z "$KEY" ]]; then
    echo "Usage: $0 <key> [--script FILE]" >&2
    echo "Known keys:" >&2
    grep -v '^#' "$CONF" | grep -v '^$' | cut -d'|' -f1 | sed 's/^/  /' >&2
    exit 2
fi

line="$(grep -v '^#' "$CONF" | awk -F'|' -v k="$KEY" '$1==k')"
if [[ -z "$line" ]]; then
    echo "No entry for key '$KEY' in $CONF" >&2
    exit 2
fi
label="$(cut -d'|' -f2 <<<"$line")"
rom_path="$(cut -d'|' -f3 <<<"$line")"; rom_path="${rom_path/#\~/$HOME}"
save_path="$(cut -d'|' -f4 <<<"$line")"; save_path="${save_path/#\~/$HOME}"

if [[ ! -f "$rom_path" ]]; then
    echo "ROM not found: $rom_path" >&2
    exit 1
fi

echo "Capturing '$label' ($KEY) via headless mgba_dump..."

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/out"
cp "$rom_path" "$work/rom.gba"
save_arg=()
if [[ -n "$save_path" ]]; then
    if [[ ! -f "$save_path" ]]; then
        echo "Save not found: $save_path (continuing without - fresh new game)" >&2
    else
        # A COPY, never the real file: mgba_dump opens it read-write and the
        # game can write to it during the boot sequence (autosave etc).
        cp "$save_path" "$work/save.sav"
        save_arg=("save.sav")
    fi
fi

cat "$BOOT_SCRIPT" >"$work/cmds.txt"
echo "dump /work/out" >>"$work/cmds.txt"

docker image inspect "$IMAGE" >/dev/null 2>&1 || docker build -q -t "$IMAGE" "$CAPTURE_DIR"
# Built from the repo root: mgba_dump compiles in the app's own C files (pk_wide.c, pk_cheats.c, ...).
docker run --rm -v "$CAPTURE_DIR/..:/repo" -v "$work:/work" -w /repo/native-capture "$IMAGE" \
    -c "gcc -O2 -Wall -o /work/mgba_dump mgba_dump.c -I/usr/include -L/usr/lib/aarch64-linux-gnu -lmgba -lm"

docker run --rm -v "$work:/work" -w /work "$IMAGE" \
    -c "./mgba_dump rom.gba ${save_arg[*]} < cmds.txt" 2>&1 | tail -5

if [[ ! -f "$work/out/ewram.bin" ]]; then
    echo "FAILED: no ewram.bin produced - check the boot sequence reached the field (try --verbose in the docker command by hand)." >&2
    exit 1
fi

out_dir="$OUT_ROOT/$KEY"
mkdir -p "$out_dir"
cp "$work/out/ewram.bin" "$out_dir/ewram.bin"
cp "$work/out/iwram.bin" "$out_dir/iwram.bin"
# The README is committed with the fixture: home paths as ~ (the repo is public).
tilde() { case "$1" in "$HOME"/*) printf '~%s' "${1#"$HOME"}" ;; *) printf '%s' "$1" ;; esac; }
{
    echo "captured: $(date -u +%Y-%m-%dT%H:%M:%SZ) (headless, scripts/capture_fixture_headless.sh)"
    echo "rom: $label ($(tilde "$rom_path"))"
    echo "save: $(tilde "${save_path:-<none - fresh new game>}")"
    echo "boot script: $(tilde "$BOOT_SCRIPT")"
} >"$out_dir/README.txt"

echo "Wrote $out_dir/{ewram.bin,iwram.bin,README.txt}"
