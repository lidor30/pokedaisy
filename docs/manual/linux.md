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
For the ROM hacks the companion reads the DEX of most of them, the TRAINER CARD
of Unbound, Lazarus, SoulGold and Recharged Yellow, and the GUIDE of a few,
from your own ROM and save. `docs/ROM_FEATURES.md` lists which build of which
game shows which tab; a build that is not listed there has emulation only.

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
change tabs. While a list or dialog is open, the D-pad stays inside it.
Your game menu has cancel-first save, load and quit confirmations.

In `MAP`, move the orange cursor across the grid with the D-pad. Towns, routes
and landmarks are named as you move; empty tiles have no location name.
B moves focus to `PLACES`. Use the D-pad to reach the other map
buttons, then press A to choose one. Down from the buttons returns to the
map cursor. L/R change tabs and X returns to the game.

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

## As a core inside a launcher

On plorpOS, PokeDaisy can also run as a core of the launcher's own emulator.
The save is the same file the mGBA core uses, and the launcher owns save
states, fast-forward, rewind, shaders and leaving the game. Pick PokeDaisy in
the Core row of a Game Boy shelf.

In this form the quick menu (Y) keeps PC, FLY, the keep-away spray, BIKE, WILD ENCOUNTERS and
COMPANION, adds a HUD tile that puts the cursor on the HUD, and the game pauses
while it is open. X opens the companion. SETTINGS lists only what the core does
itself: ASPECT, HUD, QUICK MENU, three button rows and TAB BAR. QUICK MENU BUTTON,
COMPANION BUTTON and HUD CURSOR BUTTON step through X, Y, L2 and R2 (and NONE for
the cursor) when you press A on them, and a button another action already uses
swaps over to it. L2 and R2 reach the core on every shelf (for other Game Boy
Advance cores the launcher still keeps them back). The settings file sits in the
`pokedaisy` folder inside the launcher's save folder.

In Polished Crystal 3.2.3 (the normal build) the core also has `EXPAND` and
`ZOOM OUT`, and the quick menu gets PC, FLY, the keep-away spray, BIKE and WILD
ENCOUNTERS, each done through the game's own menus. In a battle, X opens the
companion's BATTLE tab: a command or move you pick there plays out in the game.

### The picture in the launcher

The core sizes its picture to the screen so the game is always shown at a whole
scale. On the Brick (1024x768) `EXPAND` draws the game at 4x with the map rebuilt
out to the screen's edges, and `ZOOM OUT` at 3x, so you see more of the map.
`ORIGINAL` shows the game on its own. The companion and the quick menu always use
the 3x picture.

`AUTO ZOOM IN` (Settings) works as in the app: with `ZOOM OUT` chosen, anything
but the plain overworld (menus, the START menu, battles, text boxes) steps in to
the `EXPAND` picture, and it steps back out when you return to the field.
Building interiors are the overworld, so nothing steps in there. Turn `AUTO ZOOM
IN` off to stay zoomed out through menus.

Fast-forward rebuilds the map as normal play does, so the picture stays sharp
out to the edges. When the device can't keep up, a frame now and then shows the
last picture again instead. Fast-forward is silent. Rewind keeps the aspect you
chose, so `ZOOM OUT` stays zoomed out while you rewind.

### NXRedux collection and Platinum

The optional Brick package uses one PokeDaisy collection for Game Boy, Game
Boy Color, Game Boy Advance and Nintendo DS. See
`linux/libretro-unified/README.md` for build, install and save migration steps.

In Platinum (USA, revision 1), L2 opens the companion and SELECT + L2 opens
the quick menu. Set `SETTINGS` > `DS LAYOUT` to `ONE SCREEN` to play on the
whole screen, with the touch screen and menus floating in when the game wants
them. R2 switches to the bottom or top screen by hand. Hold SELECT and press R2
to show or hide a floating touch screen in `ONE SCREEN` or `PICTURE IN PICTURE`.
SELECT + D-pad moves the stylus and SELECT + A taps. Saves, states and speed are
NXRedux's own menus and shortcuts.

Turn on `BATTLE STRIP` in the companion settings to choose battle commands
with the D-pad. A selects a command or move, and B goes back from the move
list. The move cards show the game's current and maximum PP. The bag and
party open in the game's own floating panels. Doubles use the native battle
controls.
