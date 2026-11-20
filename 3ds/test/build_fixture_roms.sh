#!/bin/bash
# Builds the fixture test ROMs (see fixture_rom.c) into 3ds/build/: run inside
# the devkitARM image with the repo mounted at /repo - `make fixture-roms`.
set -euo pipefail
cd /repo/3ds
mkdir -p build
FIXTURES=/repo/test-fixtures/resources/fixtures
GCC="$DEVKITARM/bin/arm-none-eabi-gcc"
OBJCOPY="$DEVKITARM/bin/arm-none-eabi-objcopy"
GBAFIX=/opt/devkitpro/tools/bin/gbafix

# fixture key, game code, revision, the decomp build standing in for retail.
# emerald_trainer is made by `make preview` (a trainer battle built from
# emerald_de_battle) into build/fixtures/, skipped until it's there.
for spec in "firered_vanilla BPRE 1 pokefirered/pokefirered_rev1.gba" \
            "emerald_vanilla BPEE 0 pokeemerald/pokeemerald.gba" \
            "emerald_de_battle BPEE 0 pokeemerald/pokeemerald.gba" \
            "emerald_trainer BPEE 0 pokeemerald/pokeemerald.gba"; do
    set -- $spec
    src="$FIXTURES/$1"
    [ -d "$src" ] || src="$PWD/build/fixtures/$1"
    [ -f "$src/ewram.bin" ] || continue
    out="build/fixture-$1"
    "$GCC" -mthumb -mcpu=arm7tdmi -O2 -specs=gba.specs \
        test/fixture_rom.c \
        -x assembler-with-cpp \
        -DFIXTURE_EWRAM="\"$src/ewram.bin\"" -DFIXTURE_IWRAM="\"$src/iwram.bin\"" \
        test/fixture_data.s \
        -o "$out.elf"
    "$OBJCOPY" -O binary "$out.elf" "$out.gba"
    "$GBAFIX" "$out.gba" -tPOKEDAISYTST -c"$2" -m01 -r"$3" > /dev/null
    rm "$out.elf"
    echo "$out.gba ($2 rev $3)"
    # With the decomp built (make decomps), a second ROM for the tabs that read
    # the game's tables and art (MAP, POKéDEX, GUIDE): the decomp's ROM with
    # this one written over its start. The loader and the fixture's RAM take
    # ~300 KB there - game code the loader never runs; the tables and art all
    # sit past 0x08200000. Derived from a game ROM, so build/ only.
    decomp="build/decomps/$4"
    if [ -f "$decomp" ]; then
        cp "$decomp" "$out-rom.gba"
        dd if="$out.gba" of="$out-rom.gba" conv=notrunc status=none
        echo "$out-rom.gba ($2 rev $3 over $4)"
    fi
done
