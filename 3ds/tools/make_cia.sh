#!/bin/sh
# Packs the 3DS build's ELF into pokedaisy.cia, the HOME Menu install (FBI): the banner and its sound
# (3ds/ctr/cia/, from tools/gen_cia_banner.py), the HOME Menu icon (3ds/ctr/icon.png) and the permissions
# (3ds/ctr/cia/pokedaisy.rsf). Needs bannertool and makerom on the PATH (or BANNERTOOL / MAKEROM).
#   tools/make_cia.sh <pokedaisy.elf> <out.cia> <version, e.g. 1.6.0>
# 3ds/Makefile's cia runs it after the build; the release's comes from .github/workflows/3ds.yml.
set -eu
elf=$1
out=$2
version=$3
here=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
bannertool=${BANNERTOOL:-bannertool}
makerom=${MAKEROM:-makerom}

"$bannertool" makebanner -i "$here/ctr/cia/banner.png" -a "$here/ctr/cia/banner.wav" -o "$work/banner.bnr"
"$bannertool" makesmdh -s "PokeDaisy" -l "Gen 3 Pokemon companion" -p "PokeDaisy" -i "$here/ctr/icon.png" -o "$work/icon.icn"

# The title's version: major 0-63, minor 0-63, micro 0-15 (a fourth part, a hotfix's, is left out).
major=$(echo "$version" | cut -d. -f1)
minor=$(echo "$version" | cut -d. -f2)
micro=$(echo "$version" | cut -d. -f3)
[ "$micro" -gt 15 ] && micro=15
"$makerom" -f cia -o "$out" -elf "$elf" -rsf "$here/ctr/cia/pokedaisy.rsf" -target t -exefslogo \
    -icon "$work/icon.icn" -banner "$work/banner.bnr" -major "$major" -minor "$minor" -micro "${micro:-0}"
echo "wrote $out"
