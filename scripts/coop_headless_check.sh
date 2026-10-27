#!/usr/bin/env bash
# The co-op joined battles' headless checks (app/src/main/cpp/pk_coop*.c: the retail check, the link bridge, the lock): builds native-capture/mgba_dump from the repo
# (so it compiles in the app's pk_cheats.c, pk_wide.c and pk_coop*.c) in the pokedaisy-capture image, then runs each
# native-capture/coop/<name>.txt against retail English Emerald on the co-op test save (native-capture/README.md,
# "Test saves"): boot/emerald_coop.txt first (a cold boot to the field on Route 101), then the test's commands.
#
# A test passes when mgba_dump exits 0 (no `expect*` / `waittask` printed FAIL), its output holds no FAIL and no unknown
# command, and every `# want: TEXT` line of the test appears in the output. `$EMERALD_ROM` in a test stands for the ROM
# file as the container sees it (for `romcheck`).
#
# The ROM and the save are the player's own, never committed: EMERALD_ROM and EMERALD_SAVE name them (EMERALD_PARTNER_SAVE: the
# second player's save for the two-core `pair` tests of coop/link_*.txt, `$PARTNER_SAVE` in a test). The save is copied
# before every run (mgba_dump writes the game's save back when it exits), so the file itself is never touched.
#
# Usage: EMERALD_ROM=~/roms/emerald.gba EMERALD_SAVE=~/roms/emerald.sav scripts/coop_headless_check.sh [name...]
#   no names = every native-capture/coop/*.txt. Output of each run: $COOP_WORK/<name>.log (a temp dir by default).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
IMAGE="pokedaisy-capture"
: "${EMERALD_ROM:?set EMERALD_ROM to retail English Emerald (BPEE)}"
: "${EMERALD_SAVE:?set EMERALD_SAVE to the co-op test save}"
PARTNER_SAVE_SRC="${EMERALD_PARTNER_SAVE:-}"
WORK="${COOP_WORK:-$(mktemp -d)}"
mkdir -p "$WORK"

# Docker Desktop on Windows wants D:/... paths, and Git Bash mustn't rewrite the container's.
export MSYS_NO_PATHCONV=1
hostpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    docker build -t "$IMAGE" "$REPO/native-capture" || exit 1
fi

cp "$EMERALD_ROM" "$WORK/rom.gba" || exit 1
ARCH="$(docker run --rm "$IMAGE" -c "gcc -dumpmachine")"
echo "building mgba_dump ($ARCH)"
docker run --rm -v "$(hostpath "$REPO"):/repo" -v "$(hostpath "$WORK"):/work" -w /repo/native-capture "$IMAGE" \
    -c "gcc -O2 -Wall -o /work/mgba_dump mgba_dump.c -I/usr/include -L/usr/lib/$ARCH -lmgba -lm" || exit 1

if [ $# -eq 0 ]; then
    set -- $(cd "$REPO/native-capture/coop" && ls *.txt | sed 's/\.txt$//')
fi

# boot_one LABEL TESTFILE FRESH EXTRA: one boot of the co-op test save + TESTFILE; FRESH = 1 copies the save first, 0 plays
# on the file the run before wrote (a `# second:` test's reboot); EXTRA = more `# want:` lines, one per line. Prints
# nothing; leaves the reason it failed ("" = passed) in $why and the log's path in $log.
boot_one() {
    local label="$1" test="$2" fresh="$3" extra="$4" status
    [ "$fresh" = 1 ] && { cp "$EMERALD_SAVE" "$WORK/save.sav" || exit 1; }
    [ "$fresh" = 1 ] && [ -n "$PARTNER_SAVE_SRC" ] && { cp "$PARTNER_SAVE_SRC" "$WORK/partner.sav" || exit 1; }
    log="$WORK/$label.log"
    # `@boot` in a test: the cold boot to the field again, for the second core of a `pair` (after `on b`).
    cat "$REPO/native-capture/boot/emerald_coop.txt" "$test" | tr -d '\r' |
        awk -v boot="$REPO/native-capture/boot/emerald_coop.txt" '/^@boot$/ { while ((getline l < boot) > 0) print l; close(boot); next } { print }' |
        sed -e 's|\$EMERALD_ROM|/work/rom.gba|g' -e 's|\$PARTNER_SAVE|/work/partner.sav|g' |
        docker run --rm -i -v "$(hostpath "$REPO"):/repo" -v "$(hostpath "$WORK"):/work" -w /repo/native-capture "$IMAGE" \
            -c "/work/mgba_dump /work/rom.gba /work/save.sav" > "$log" 2>&1
    status=$?
    why=""
    [ $status -ne 0 ] && why="mgba_dump exited $status"
    grep -q "FAIL" "$log" && why="${why:+$why; }$(grep -m1 "FAIL" "$log")"
    grep -q "unknown command" "$log" && why="${why:+$why; }$(grep -m1 "unknown command" "$log")"
    while IFS= read -r want; do
        [ -z "$want" ] && continue
        grep -qF -- "$want" "$log" || why="${why:+$why; }missing \"$want\""
    done < <({ tr -d '\r' < "$test" | sed -n 's/^# want: //p'; printf '%s\n' "$extra"; })
}

# `ran` lists the tests already run in this invocation, so one is run once.
# `# second: NAME` in a test: a second boot from the save file its run left, with coop/second/NAME.txt (a save written in
# game holds the changes: after_save).
failed=0
ran=" "
run_one() {
    local name="$1" test second
    test="$REPO/native-capture/coop/$name.txt"
    if [ ! -f "$test" ]; then
        echo "$name: no such test ($test)"
        failed=1
        return
    fi
    case "$ran" in *" $name "*) return ;; esac
    ran="$ran$name "
    boot_one "$name" "$test" 1 ""
    second="$(tr -d '\r' < "$test" | sed -n 's/^# second: //p' | head -1)"
    if [ -z "$why" ] && [ -n "$second" ]; then
        boot_one "$name.$second" "$REPO/native-capture/coop/second/$second.txt" 0 ""
        [ -n "$why" ] && why="second boot ($second): $why"
    fi
    if [ -n "$why" ]; then
        echo "$name: FAIL - $why (log: $log)"
        failed=1
    else
        echo "$name: PASS"
    fi
}

for name in "$@"; do
    run_one "$name"
done
exit $failed
