#!/bin/bash
# PokeDaisy as a PortMaster port (ROCKNIX, Knulli, muOS, AmberELEC and the other firmwares that
# run PortMaster): PortMaster's usual launcher. Put this file and the pokedaisy/ folder in your
# ports folder, and your games in pokedaisy/roms/ (or the firmware's ROM folders). Played on a GKD 350H (ROCKNIX).

XDG_DATA_HOME=${XDG_DATA_HOME:-$HOME/.local/share}
if [ -d "/opt/system/Tools/PortMaster/" ]; then
  controlfolder="/opt/system/Tools/PortMaster"
elif [ -d "/opt/tools/PortMaster/" ]; then
  controlfolder="/opt/tools/PortMaster"
elif [ -d "$XDG_DATA_HOME/PortMaster/" ]; then
  controlfolder="$XDG_DATA_HOME/PortMaster"
else
  controlfolder="/roms/ports/PortMaster"
fi
# shellcheck source=/dev/null
source "$controlfolder/control.txt"
# shellcheck source=/dev/null
[ -f "$controlfolder/mod_$CFW_NAME.txt" ] && source "$controlfolder/mod_$CFW_NAME.txt"
get_controls

# control.txt sets $directory (the firmware's roms root) and the controller mapping.
# shellcheck disable=SC2154
GAMEDIR="/$directory/ports/pokedaisy"
cd "$GAMEDIR" || exit 1
mkdir -p roms saves states
: > "$GAMEDIR/log.txt" && exec > >(tee "$GAMEDIR/log.txt") 2>&1

export POCKET_FULLSCREEN=1
# SELECT+START is PortMaster's exit (gptokeyb): the host menu opens with the Menu / Guide button here.
export POCKET_MENU_COMBO=off
# The game list also shows the firmware's own Game Boy / Color / Advance and Nintendo DS folders.
ROM_DIRS=""
for d in gba gb gbc nds "Game Boy Advance" "Game Boy Color" "Game Boy" "Nintendo DS"; do
  [ -d "/$directory/$d" ] && ROM_DIRS="$ROM_DIRS${ROM_DIRS:+:}/$directory/$d"
done
export POCKET_ROM_DIRS="$ROM_DIRS"
# shellcheck disable=SC2154
export SDL_GAMECONTROLLERCONFIG="$sdl_controllerconfig"
# gptokeyb gives PortMaster's exit hotkey; the game list's B and the game menu's quit work too.
if [ -n "$GPTOKEYB" ]; then
  $GPTOKEYB "pokedaisy-linux" &
fi
./pokedaisy-linux --data "$GAMEDIR"

if declare -f pm_finish > /dev/null; then
  pm_finish
else
  $ESUDO kill -9 "$(pidof gptokeyb)" 2> /dev/null
  printf "\033c" > /dev/tty0
fi
