#!/bin/sh
# The frontend retains the real ROM path and therefore its native save/resume key.
CORES_PATH=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROM=$1
[ -n "$ROM" ] && [ -f "$ROM" ] || exit 1
case "$ROM" in
  *.[nN][dD][sS]) CORE=pokedaisy_ds_libretro.so ;;
  *.[gG][bB][aA]) CORE=pokedaisy_gba_libretro.so ;;
  *.[gG][bB]|*.[gG][bB][cC]) CORE=pokedaisy_gbc_libretro.so ;;
  *) echo "PokeDaisy: extract archives first; supported files are .gb, .gbc, .gba and .nds" >&2; exit 2 ;;
esac
[ -f "$CORES_PATH/$CORE" ] || { echo "PokeDaisy: missing $CORE" >&2; exit 1; }
cd "$USERDATA_PATH" || exit 1
exec minarch.elf "$CORES_PATH/$CORE" "$ROM" > "$LOGS_PATH/pokedaisy-unified.txt" 2>&1
