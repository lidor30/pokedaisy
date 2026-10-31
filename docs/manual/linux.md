---
title: Linux handheld foundation
summary: Build and try the initial Linux host with physical controls and the existing portable companion.
section: start
order: 90
keywords: [linux, handheld, trimui, brick, smart pro, sdl, quick menu, wild encounters, fly, pc, bike, hud, tab bar]
---

You can build the initial Linux host from source. It runs Game Boy, Game Boy
Color and Game Boy Advance games with your own ROMs and saves. Its companion
uses the existing portable core for supported retail English Gen 3 games.
Other games have emulation only in this foundation.

Follow `linux/README.md` in the source repository for dependencies, native and
TrimUI builds, controls, save paths and test commands. Use a separate data
folder while testing. Existing emulator saves are never imported automatically.

The game and the companion both fit your screen at a sharp whole number scale,
so text stays about the same size on a 640x480, 720x720 or 1280x720 handheld.

Press Y while you play to open the quick menu over the game: open the PC from
wherever you stand, fly to a town you have been to, use the keep-away spray
from your bag, get on or off your bike, turn wild encounters off or back on,
save or load a state, fast forward or open the companion. Pick a tile with the
D-pad and press A; B closes it. Flying needs a Pokémon in your party that knows
the move and the badge the game asks for, just like in the game's own menu.

The HUD shows your party, a small map around you, the steps left on the spray
and your balls over the overworld, out of the way of the game's own text. Turn
it or any of its parts off in the companion's `SETTINGS` > `HUD`. In
`SETTINGS` > `TAB BAR` you pick which tabs sit on the bar; the rest open from
`MORE`.

Press the companion button to pause the game and open its companion. Use the
D-pad to focus an option, A to choose it and B to go back. The shoulder buttons
change tabs. Your game menu has cancel-first save, load and quit confirmations.

To see more of the map, set `ASPECT` in the companion's `SETTINGS` to `EXPAND`:
the overworld reaches the edges of your screen at the same sharp scale as the
game, for the games EXPAND knows on Android. `ZOOM OUT` shows even more of it,
one scale smaller. Battles and menus stay as the game draws them, and with
`AUTO ZOOM IN` on, so do text boxes.

On firmwares that run PortMaster (ROCKNIX, Knulli, muOS and others), the
PortMaster build goes in your `ports` folder like any other port, with your
games in its `roms` folder. Controllers can be plugged in while you play.

This early version does not include the Polished Crystal companion, the longer
summaries or firmware power handling. Earlier
builds ran on the Brick and Smart Pro, but this separated foundation still
needs fresh device testing. Other Linux handhelds are not verified.

An optional DSperate backend can be enabled with `POCKET_DSPERATE=ON`.
Its limited, read-only companion supports Platinum USA rev1, including party,
bag, dex and a ROM-derived Town Map. L2 opens the shared quick menu; select
COMPANION for its screens. R2 swaps the native DS screens. Optional battle
AUTO mode shows the bottom screen while choosing and the top for animations.

DS expansion and custom battle controls are not included. The Linux companion
and data bridge remain an initial implementation for upstream review; see
`linux/dsperate/README.md` for scope, builds, controls and remaining testing.
