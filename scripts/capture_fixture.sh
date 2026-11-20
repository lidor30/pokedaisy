#!/usr/bin/env bash
# Captures a real-device memory snapshot for one game (full EWRAM + IWRAM, via
# PokeDaisyActivity's EXTRA_DUMP_FIXTURE debug hook - see
# dumpFixtureIfPending() in PokeDaisyActivity.kt) into
# test-fixtures/resources/fixtures/<key>/{ewram,iwram}.bin, for the JVM decode
# JVM decode tests to replay against (FixtureMemoryReader).
#
# Deliberately ONE game per run, not a batch-capture-everything loop: a
# fixture is a frozen "this is the known-correct output" baseline, so it
# should only be (re)captured right after you've confirmed - by eye, or via
# scripts/smoke_test.sh - that this specific ROM is actually decoding
# correctly right now. Capturing blind would happily freeze in a bug.
#
# Usage: scripts/capture_fixture.sh <key> [device-serial]
#   <key> must match the first column of roms.conf.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONF="$SCRIPT_DIR/roms.conf"
KEY="${1:-}"
SERIAL="${2:-}"
PKG="com.pokedaisy.app"
DEVICE_BASE="/storage/emulated/0/Android/data/$PKG/files"
OUT_ROOT="$SCRIPT_DIR/../test-fixtures/resources/fixtures"

if [[ -z "$KEY" ]]; then
    echo "Usage: $0 <key> [device-serial]" >&2
    echo "Known keys:" >&2
    grep -v '^#' "$CONF" | grep -v '^$' | cut -d'|' -f1 | sed 's/^/  /' >&2
    exit 2
fi

path="$(grep -v '^#' "$CONF" | awk -F'|' -v k="$KEY" '$1==k {print $3}')"
label="$(grep -v '^#' "$CONF" | awk -F'|' -v k="$KEY" '$1==k {print $2}')"
if [[ -z "$path" ]]; then
    echo "No entry for key '$KEY' in $CONF" >&2
    exit 2
fi

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
    ADB=(adb -s "$SERIAL")
fi

device_dump_dir="$DEVICE_BASE/fixtures/$KEY"
out_dir="$OUT_ROOT/$KEY"
mkdir -p "$out_dir"

echo "Capturing '$label' ($KEY)..."
"${ADB[@]}" shell mkdir -p "$device_dump_dir"
"${ADB[@]}" logcat -c

# `adb shell` reassembles multi-arg invocations into one command line for the
# DEVICE's /system/bin/sh, which chokes on the parens/spaces most of these ROM
# filenames have unless the path is quoted for THAT shell - single-quote it
# inside one shell string (see the identical comment in smoke_test.sh).
#
# If the app is already running, target it WITHOUT --es rom and WITHOUT a
# force-stop first: PokeDaisyActivity is singleTask, so this hits
# onNewIntent on the LIVE session instead of a fresh onCreate, letting it
# apply the dump request to whatever state (party, location) is already
# loaded right now. A cold relaunch discards that and re-lands on the title
# screen - fine for a ROM that auto-skips straight to the overworld, but
# stalls the 20s wait below forever for one that needs a manual Continue
# press first (bit Odyssey's first capture attempt).
already_running=0
if "${ADB[@]}" shell pidof "$PKG" >/dev/null 2>&1; then
    already_running=1
    echo "App already running - dumping the live session (no restart)..."
    "${ADB[@]}" shell "am start -n '$PKG/.PokeDaisyActivity' --es dumpFixture '$device_dump_dir'" >/dev/null
    for _ in $(seq 1 20); do
        sleep 1
        if "${ADB[@]}" logcat -d 2>/dev/null | grep -q "pokedaisy: fixture dump: wrote"; then
            break
        fi
    done
fi

if ! "${ADB[@]}" logcat -d 2>/dev/null | grep -q "pokedaisy: fixture dump: wrote"; then
    if [[ "$already_running" == "1" ]]; then
        echo "Live dump didn't land - falling back to a cold relaunch (will re-land on the title screen)." >&2
    fi
    "${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1
    "${ADB[@]}" logcat -c
    "${ADB[@]}" shell "am start -n '$PKG/.PokeDaisyActivity' --es rom '$path' --es dumpFixture '$device_dump_dir'" >/dev/null
    echo "Waiting for the dump to land (up to 20s)..."
    for _ in $(seq 1 20); do
        sleep 1
        if "${ADB[@]}" logcat -d 2>/dev/null | grep -q "pokedaisy: fixture dump: wrote"; then
            break
        fi
    done
fi

dump_line="$("${ADB[@]}" logcat -d 2>/dev/null | grep 'pokedaisy: fixture dump' || true)"
dump_line="$(echo "$dump_line" | tail -1)"
if [[ -z "$dump_line" ]]; then
    echo "FAILED: no fixture-dump log line seen - ROM may not have connected, or (cold path) needs a manual Continue press past the title screen. Try opening the ROM by hand in the app until the party/items are visible, then re-run this script - it'll dump the live session without restarting." >&2
    exit 1
fi
echo "$dump_line"

telemetry_line="$("${ADB[@]}" logcat -d 2>/dev/null | grep 'pokedaisy: telemetry' || true)"
telemetry_line="$(echo "$telemetry_line" | tail -1)"
echo "$telemetry_line"

"${ADB[@]}" pull "$device_dump_dir/ewram.bin" "$out_dir/ewram.bin"
"${ADB[@]}" pull "$device_dump_dir/iwram.bin" "$out_dir/iwram.bin"

{
    echo "captured: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "rom: $label ($path)"
    echo "$telemetry_line"
} >"$out_dir/README.txt"

echo "Wrote $out_dir/{ewram.bin,iwram.bin,README.txt}"
