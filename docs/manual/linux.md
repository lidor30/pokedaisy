---
title: Linux, Steam Deck and handhelds (beta)
summary: Download, install and play the PokeDaisy Linux beta on a Steam Deck, a Linux desktop, TrimUI and PortMaster handhelds, or as libretro cores in your own frontend.
section: start
order: 90
keywords: [linux, steam deck, steamos, game mode, desktop mode, desktop, handheld, trimui, brick, smart pro, crossmix, nextui, nxredux, portmaster, rocknix, knulli, muos, gkd, retroarch, libretro, core, cores, aarch64, arm, x86_64, beta, install, download, zip, hotkeys, host menu, sdl]
---

PokeDaisy for Linux plays your Game Boy, Game Boy Color and Game Boy Advance games with the same companion, HUD,
quick menu and `EXPAND` as the libretro cores, on a Steam Deck, a Linux desktop and Linux handhelds. It reads your
own ROMs and saves; it never comes with games.

New to it? [Install on Linux, Steam Deck and handhelds](linux-install.md) takes you through the download and
install step by step, in every language the app has.

> [!WARNING]
> This is a beta. The Android app stays the full PokeDaisy, and some devices below haven't been tried yet. If
> something goes wrong, open an issue on [GitHub](https://github.com/lidor30/pokedaisy/issues) and say which file
> and which device you used.

## Which file to download

Every release has these files. Pick the one for your device.

| Your device | Download |
| --- | --- |
| Steam Deck, or a Linux PC (x86_64) | `pokedaisy-linux-x86_64.zip` |
| A TrimUI handheld, as an app | `pokedaisy-linux-aarch64.zip` |
| A handheld with PortMaster (ROCKNIX, Knulli, muOS and others) | `pokedaisy-portmaster-aarch64.zip` |
| A TrimUI Brick running NXRedux | `PokeDaisy-NXRedux.zip` |
| Your own frontend or RetroArch, on a PC | `pokedaisy-libretro-x86_64.zip` |
| Your own frontend or RetroArch, on an ARM handheld | `pokedaisy-libretro-aarch64.zip` |

The first three are the PokeDaisy program with its three cores beside it. The last three are the cores alone, for a
frontend that loads libretro cores.

## Steam Deck and Linux PCs

`pokedaisy-linux-x86_64.zip` runs on SteamOS and on Linux PCs with glibc 2.35 or newer and SDL2 (SteamOS and most
desktops have both). Inside its `pokedaisy-linux-x86_64` folder are the program (`pokedaisy-linux`), the GBA core
(`pokedaisy_gba_libretro.so`), the Game Boy core (`pokedaisy_gbc_libretro.so`), the DS core (`pokedaisy_ds_libretro.so`) and their licenses.

1. Extract the zip somewhere in your home folder. On a Steam Deck, do this in Desktop Mode, for example into a
   `PokeDaisy` folder in your home folder.
2. Put your games in the `roms` folder inside `pokedaisy-linux-x86_64` (it appears after the first start, or make it
   yourself).
3. Start it from a terminal in that folder with `./pokedaisy-linux --data .` (no game named: you get the game list).

The `--data` folder is where PokeDaisy keeps `roms`, `saves`, `states` and its settings. Without `--data` it uses
the folder you started it from, so a shortcut should always pass it.

### Game Mode

In Desktop Mode, open Steam and choose Games > Add a Non-Steam Game to My Library, browse to `pokedaisy-linux` and
add it. Steam starts it in its own folder, so no launch options are needed. In Game Mode PokeDaisy fills the whole
screen by itself, and the Deck's controls, touch screen and sound work as they do anywhere else. Steam's on-screen
keyboard shouldn't open at launch; if it does, you're on an older build, so download the newest one.

## Linux handhelds

`pokedaisy-linux-aarch64.zip` is the program as a TrimUI app, built for firmwares like CrossMix or NextUI, but it
should run other places too. Its `pokedaisy-linux-aarch64` folder holds the program, all three cores, `launch.sh` (what
the firmware runs), `config.json` and `icon.png` (the menu entry's name and icon). On CrossMix, copy the folder
into the SD card's `Apps` folder.

On [plorpOS](https://github.com/thwonp/plorpos) and on [spruceOS](https://github.com/spruceUI/spruceOS) nightly,
the Game Boy Advance, Game Boy and Game Boy Color cores come installed already, so there you don't need these files:
pick PokeDaisy as the core for your game.

The app keeps everything in its own folder: put your games in its `roms` folder. It runs fullscreen, and on
CrossMix it raises the CPU's lowest speed while you play and puts it back when you quit.

> [!NOTE]
> The program itself hasn't run as a standalone app yet (its cores have). Expect rough edges, and tell us how it goes.

## PortMaster build

`pokedaisy-portmaster-aarch64.zip` is an unpublished PortMaster port: `PokeDaisy.sh` beside a `pokedaisy` folder.
Copy both into your firmware's `ports` folder, then start PokeDaisy from the Ports list. It has been tried on a
GKD 350H running ROCKNIX.

- **Your games:** the game list shows `ports/pokedaisy/roms` and the firmware's own `gba`, `gb`, `gbc`, `nds`,
  `Game Boy Advance`, `Game Boy Color`, `Game Boy` and `Nintendo DS` folders, wherever they exist next to `ports`.
- **The menu:** press the Menu or Guide button. SELECT and START together quit the port, as in any PortMaster game.
- PortMaster tells PokeDaisy your device's buttons, so they work without setup.

## TrimUI Brick with NXRedux

`PokeDaisy-NXRedux.zip` puts all three cores behind one PokeDaisy collection on a Brick running NXRedux. Extract it
at the root of your SD card. Put your games (`.gb`, `.gbc`, `.gba` and `.nds`, not zipped) in
`Roms/PokeDaisy (PDAI)`; the launcher lives in `Emus/tg5040/PDAI.pak`. GBA games run on the GBA core, Game Boy and
Game Boy Color games on the Game Boy core, and DS games on the DS core. NXRedux keeps your saves, save states,
fast-forward, shaders and resume, from its own menu. Use a different file name for every game, because NXRedux
names saves by the file name alone.

### NXRedux collection and Platinum

In Platinum (USA, revision 1), L2 opens the companion and holding L2 opens the quick menu. Set `SETTINGS` >
`DS LAYOUT` to `ONE SCREEN` to play on the whole screen, with the touch screen and menus floating in when the game
wants them. R2 switches to the bottom or top screen by hand. Hold SELECT and press Y to show or hide a floating
touch screen in `ONE SCREEN` or `PICTURE IN PICTURE`. SELECT + D-pad moves the stylus and SELECT + A taps.

In a DS game's DEX entry, Left and Right step to the previous and next entry you have seen, going round at either
end.

Turn on `BATTLE STRIP` in the companion settings to choose battle commands with the D-pad. A selects a command or
move, and B goes back from the move list. The move cards show the game's current and maximum PP. The bag and party
open in the game's own floating panels. Doubles use the native battle controls.

## Cores for your own frontend

`pokedaisy-libretro-x86_64.zip` and `pokedaisy-libretro-aarch64.zip` hold the three cores, their licenses and
`SOURCE.txt` (where the code comes from), in a folder named after the zip:

| Core | Plays | Runs on |
| --- | --- | --- |
| `pokedaisy_gba_libretro.so` | Game Boy Advance | armGBA |
| `pokedaisy_gbc_libretro.so` | Game Boy, Game Boy Color and Game Boy Advance | mGBA |
| `pokedaisy_ds_libretro.so` | Nintendo DS | DSperate |

The ARM cores load on glibc 2.33 and newer, which takes in the TrimUI Brick; the PC cores need glibc 2.35. In
RetroArch, copy a core into RetroArch's cores folder and load it with your game: it shows up as PokeDaisy (the DS
core as PokeDaisy DS). Keep the licenses with the cores if you pass them on.

The companion keeps its settings in your frontend's save folder: `pokedaisy/settings.ini` for the Game Boy and GBA
cores, `pokedaisy-ds/settings.ini` for the DS core.

### As a core inside a launcher

On plorpOS, PokeDaisy runs as a core of the launcher's own emulator. The save is the same file the mGBA core uses,
and the launcher owns save states, fast-forward, rewind, shaders and leaving the game. Pick PokeDaisy in the Core
row of a Game Boy shelf.

In this form the quick menu (Y) keeps PC, FLY, the keep-away spray, BIKE, WILD ENCOUNTERS and COMPANION (with TAKE
OFF in the English FireRed and Emerald), adds a HUD tile that puts the cursor on the HUD, and the game pauses while it
is open. X opens the companion. SETTINGS lists only what the core does itself: ASPECT, HUD, QUICK MENU, FLY STYLE,
FLIGHT AREA and FLY ON (where there's TAKE OFF), three button rows and TAB BAR. QUICK MENU BUTTON,
COMPANION BUTTON and HUD CURSOR BUTTON step through X, Y, L2 and R2 (and NONE for the cursor) when you press A on
them, and a button another action already uses swaps over to it. L2 and R2 reach the core on every shelf (for other
Game Boy Advance cores the launcher still keeps them back).

In Polished Crystal 3.2.3 (the normal build) the core also has `EXPAND` and `ZOOM OUT`, and the quick menu gets PC,
FLY, the keep-away spray, BIKE and WILD ENCOUNTERS, each done through the game's own menus. In a battle, X opens the
companion's BATTLE tab: a command or move you pick there plays out in the game.

In the companion's BAG, Left moves to the pocket tabs (the one you're on is outlined, and with an item's description
open the same press closes it first), Up and Down choose one and `A` opens it; Right or `B` goes back to the list.

### The picture in the launcher

The core sizes its picture to the screen so the game is always shown at a whole scale. On the Brick (1024x768)
`EXPAND` draws the game at 4x with the map rebuilt out to the screen's edges, and `ZOOM OUT` at 3x, so you see more
of the map. `ORIGINAL` shows the game on its own. The companion and the quick menu always use the 3x picture.

`AUTO ZOOM IN` (Settings) works as in the app: with `ZOOM OUT` chosen, anything but the plain overworld (menus, the
START menu, battles, text boxes) steps in to the `EXPAND` picture, and it steps back out when you return to the
field. Building interiors are the overworld, so nothing steps in there. Turn `AUTO ZOOM IN` off to stay zoomed out
through menus.

Fast-forward rebuilds the map as normal play does, so the picture stays sharp out to the edges. When the device
can't keep up, a frame now and then shows the last picture again instead. After a state load, and all through
rewind, the map past the edges is there at once. Rewind keeps the aspect you chose, so `ZOOM OUT` stays zoomed out
while you rewind.

## Adding games

The program's game list shows the `.gb`, `.gbc`, `.gba` and `.nds` files in the `roms` folder of its data folder (and, on
PortMaster, the firmware's folders above). Pick one with the D-pad and A. Unzip your games first.

## Moving a save you already have

The program keeps a game's save in its data folder's `saves` folder, named after the whole ROM file name plus
`.sav`: for `FireRed.gba` that's `saves/FireRed.gba.sav`. It's the game's own battery save, so a save from another
emulator works when you rename it, a RetroArch `.srm` included. Keep a copy of your old save somewhere else first.

Before a game opens, PokeDaisy backs its save up into `saves/pokedaisy-backups` and keeps the newest 10 copies.
Save states are in `states` and belong to the core that made them: a GBA state from another emulator, or from the
other core, won't load.

## Controls

The program uses your controller's layout: the button on the right acts as the game's A and the one at the bottom
as B, as on a Nintendo controller. If that feels backwards on your device, set `A/B BUTTONS` to `SWAPPED` in the
menu. Controllers can be plugged in or pulled out while you play.

On a keyboard: arrows move, Z is A, X is B, Enter is START, Backspace is SELECT, Q and E are L and R, Tab is X and F
is Y. Hold Space to fast forward and R to rewind, F2 saves a state, F4 loads it, and Escape opens the menu. A touch
screen or a mouse reaches the companion and the HUD.

The companion and the quick menu work as in the cores everywhere: X opens the [companion](companion.md), Y opens the
quick menu over the game (PC, FLY, the keep-away spray, BIKE, WILD ENCOUNTERS, COMPANION), and both buttons can be
changed in the companion's `SETTINGS`. In the English FireRed and Emerald the quick menu also has `TAKE OFF`, to
[fly over the map](map.md#flying-over-the-map) on your Pokémon's bird (the companion's `MAP` has a `TAKE OFF` button
too). There `FLY` flies you over the map to the town you pick, unless `FLY STYLE` in the companion's `SETTINGS` is
`CLASSIC`, the game's own way. `FLY ON` swaps the bird for an air balloon, a helicopter or a zeppelin. `FLIGHT AREA`
keeps the flight to [places you've already been](map.md#where-you-can-fly) (`VISITED`, the default) or lets it go
`ANYWHERE`. The list of places is kept for each save, in `saves/pokedaisy/flight-area`. In the English FireRed,
LeafGreen, Ruby, Sapphire and Emerald the quick menu has [`FLASH`](controls.md#the-quick-menu) too, to light up a dark
cave once a Pokémon in your party knows the move; it's greyed out where it isn't dark. The
[HUD](hud.md) shows your party and a small map over the overworld, and [EXPAND](screen.md#expand-more-of-the-map)
is `ASPECT` in the companion's `SETTINGS`, with `ZOOM OUT` for even more of the map. The quick menu's tiles follow your HUD look, in two shades with an orange outline on the
one you're on, and the HUD's panels have a battle-style shadow, with an HP bar and room for a status tag on each
party Pokémon.

In a DS game the DS has every face and shoulder button, so L2 opens the companion and R2 switches the screens, and the
game starts on one screen. The DS HUD has the same PC, BAG, WILD and COMPANION buttons as a GBA game's, and hides
while the game shows text. `HUD` in its `SETTINGS` opens the same HUD page as a GBA game's, with `LOOK`, `SIZE`
(`SMALL`, `NORMAL` or `LARGE`) and which parts show, and the quick menu's `HUD` tile moves the cursor onto the HUD.

## The menu

Press START and SELECT together (or the Guide or Menu button, or Escape) to open the menu over the game. The game
pauses while it's open.

| Row | What it does |
| --- | --- |
| `RESUME` | Back to the game. |
| `SAVE STATE`, `LOAD STATE` | One save state for the game. `LOAD STATE` asks first. Game Boy Advance states made before this version don't load. |
| `HOTKEYS` | Buttons for fast-forward, rewind and states (below). |
| `REWIND` | `ON` keeps the last 20 seconds or so to rewind through. |
| `FF SPEED` | How fast fast-forward goes: `MAX`, or 2x to 8x. |
| `LCD FILTER` | A screen texture like a handheld's. |
| `A/B BUTTONS` | `NORMAL` or `SWAPPED`, for devices whose A and B are the other way round. |
| `QUIT` | Leave the game (`QUIT TO GAME LIST` when you picked it from the list). It asks first. |

### Hotkeys

Hotkeys work while you hold SELECT, so their buttons stay the game's the rest of the time. `HOTKEYS` gives these a
button each:

- `FAST FORWARD`: the game runs fast while you hold SELECT and the button. R2 to start with.
- `REWIND`: the game runs backwards while you hold SELECT and the button, with `REWIND` set to `ON`. L2 to start with.
- `SAVE STATE` and `LOAD STATE`: one press each, with SELECT held. Off to start with.
- `MODIFIER`: the button you hold for the hotkeys. SELECT to start with. Press START or a hotkey button to use that
  instead, or press SELECT to turn it off; then each hotkey is its button alone, and that button never reaches the game.

A short press of SELECT on its own still reaches the game when you let go.

Pick a row, then press the button you want. B cancels and SELECT turns the hotkey off. L1, R1, L2, R2, X, Y, either
stick's click and back buttons can be hotkeys; A, B, START, SELECT and the D-pad stay the game's. A button that
another hotkey had moves over to the new one, and the `MODIFIER` can't be a hotkey's button too.

DS games keep hotkeys of their own, also SELECT + R2 and SELECT + L2 to start with. With the `MODIFIER` off they move
to R3 and L3, since R2 and L2 alone are the DS's screen and companion buttons. On a Steam Deck, the back buttons reach
PokeDaisy only when Steam passes them on.

## For advanced users

The program reads a few settings from the environment:

- `POCKET_FULLSCREEN=1` fills the screen.
- `POCKET_WINDOW=1280x800` opens a window of that size, to try a handheld's screen on a PC.
- `POCKET_ROM_DIRS` adds folders to the game list, separated by colons.
- `POCKET_REWIND_MB` sets how much memory rewind may use (32 to start with).
- `POCKET_CORE_DIR` loads the cores from another folder.

To build it yourself, follow `linux/README.md` in the source repository.

## What isn't tested yet

- More devices
- PortMaster firmwares other than ROCKNIX
- DS games in the program on a TrimUI handheld
