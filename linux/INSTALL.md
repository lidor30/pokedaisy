<!-- Made from docs/manual/linux-install.md by website/scripts/install-guides.mjs: edit that page, not this file. -->

# Install on Linux, Steam Deck and handhelds

*Pick the right download, install PokeDaisy on a Steam Deck, a Linux PC or a Linux handheld (or its cores in RetroArch), add your games and bring your saves.*

**English** · [日本語](INSTALL.ja.md) · [한국어](INSTALL.ko.md) · [Français](INSTALL.fr.md) · [Deutsch](INSTALL.de.md) · [Italiano](INSTALL.it.md) · [Español](INSTALL.es.md) · [Português (Brasil)](INSTALL.pt-br.md) · [Português (Portugal)](INSTALL.pt.md)

Also on the website: https://pokedaisy.com/docs/linux-install/

PokeDaisy also runs on Linux: on a Steam Deck, a Linux PC and Linux handhelds such as TrimUI's, either as its own program or as libretro cores you load in RetroArch or your handheld's frontend. Either way you get the same companion, HUD, quick menu and `EXPAND`. This page takes you from the download to your first game; [Linux, Steam Deck and handhelds](https://pokedaisy.com/docs/linux/) covers the controls, the menu and everything else once you're playing.

> [!WARNING]
> The Linux version is a beta. Its menus are in English for now, and some devices on this page haven't been tried yet. If something goes wrong, [tell us](https://pokedaisy.com/feedback/#bug) which file and which device you used.

## What you need

- **A Steam Deck, a Linux PC (x86_64) or a 64-bit ARM Linux handheld.** On a PC: a Linux with glibc 2.35 or newer and SDL2 (SteamOS, Ubuntu 22.04 and newer and most current desktops have both). On a handheld: glibc 2.33 or newer, which takes in the TrimUI Brick.
- **A way to copy files to it:** the device itself, or its SD card in a computer.
- **Your own games.** PokeDaisy comes with no games. Use ROMs you dumped yourself from cartridges you own: `.gba`, `.gb`, `.gbc` or `.nds` files, unzipped.

## Which file to download

Open the [PokeDaisy releases page](https://github.com/lidor30/pokedaisy/releases) and, under the newest release that has Linux files, download the one for your device:

| Your device | Download |
| --- | --- |
| Steam Deck, or a Linux PC | `pokedaisy-linux-x86_64.zip` |
| A TrimUI handheld (CrossMix, NextUI), as an app | `pokedaisy-linux-aarch64.zip` |
| A handheld with PortMaster (ROCKNIX, Knulli, muOS and others) | `pokedaisy-portmaster-aarch64.zip` |
| A TrimUI Brick running NXRedux | `PokeDaisy-NXRedux.zip` |
| RetroArch or another frontend, on a PC | `pokedaisy-libretro-x86_64.zip` |
| RetroArch or another frontend, on an ARM handheld | `pokedaisy-libretro-aarch64.zip` |

The first three hold the PokeDaisy program with its three cores beside it: one for Game Boy Advance, one for Game Boy and Game Boy Color, one for DS. The last two are the cores alone. Then follow the part of this page for your device.

## Steam Deck and Linux PCs

1. Extract `pokedaisy-linux-x86_64.zip` somewhere in your home folder, for example into a `PokeDaisy` folder. On a Steam Deck, do this in Desktop Mode. You get a `pokedaisy-linux-x86_64` folder with the program, `pokedaisy-linux`, and the three cores.
2. In that folder, make a folder called `roms` and copy your games into it. (PokeDaisy makes it the first time it starts, if you'd rather start it once first.)
3. Open a terminal in the `pokedaisy-linux-x86_64` folder and start it with `./pokedaisy-linux --data .`

The game list opens: pick a game with the D-pad (or the arrow keys) and press A (Z on a keyboard). `--data .` tells PokeDaisy to keep `roms`, `saves`, `states` and its settings in that folder; a shortcut you make yourself should pass `--data` with the folder's full path.

### Play in Game Mode

1. In Desktop Mode, open Steam and choose **Games** > **Add a Non-Steam Game to My Library**.
2. Pick **Browse**, go to the `pokedaisy-linux-x86_64` folder, choose `pokedaisy-linux` and add it.
3. Go back to Game Mode and start PokeDaisy from your library.

Steam starts it in its own folder, so no launch options are needed. In Game Mode it fills the screen, and the Deck's controls, touch screen and sound work as usual.

## TrimUI handhelds

`pokedaisy-linux-aarch64.zip` is PokeDaisy as a TrimUI app, built for CrossMix and NextUI.

1. Extract the zip on your computer. You get a `pokedaisy-linux-aarch64` folder.
2. Put the SD card in your computer and copy that folder into the card's `Apps` folder (on CrossMix).
3. Copy your games into the `roms` folder inside it (make it if it isn't there).
4. Put the card back and start PokeDaisy from the Apps menu.

The app keeps everything in its own folder and runs fullscreen. On CrossMix it raises the CPU's lowest speed while you play and puts it back when you quit.

> [!NOTE]
> The program hasn't run as a standalone app on a TrimUI yet (its cores have). Expect rough edges, and tell us how it goes.

## Handhelds with PortMaster

1. Extract `pokedaisy-portmaster-aarch64.zip` on your computer. Inside are `PokeDaisy.sh` and a `pokedaisy` folder.
2. Copy both into your firmware's `ports` folder on the SD card.
3. Your games can stay where they are: the game list shows `ports/pokedaisy/roms` and the firmware's own `gba`, `gb`, `gbc` and `nds` folders (and the ones named `Game Boy Advance`, `Game Boy Color`, `Game Boy` and `Nintendo DS`).
4. Start PokeDaisy from the Ports list.

PortMaster tells PokeDaisy your device's buttons, so they work without setup. The Menu or Guide button opens PokeDaisy's menu, and SELECT and START together quit, as in any port. It has been played on a GKD 350H running ROCKNIX.

## TrimUI Brick with NXRedux

1. Extract `PokeDaisy-NXRedux.zip` at the top of your SD card. It adds `Emus/tg5040/PDAI.pak` and a `Roms/PokeDaisy (PDAI)` folder.
2. Put your games (`.gba`, `.gb`, `.gbc` and `.nds`, unzipped) in `Roms/PokeDaisy (PDAI)`.
3. Start them from the PokeDaisy collection in NXRedux.

NXRedux keeps your saves, save states, fast-forward, shaders and resume from its own menu. Give every game a different file name, because NXRedux names saves by the file name alone.

## RetroArch and other frontends

1. Extract `pokedaisy-libretro-x86_64.zip` (PC) or `pokedaisy-libretro-aarch64.zip` (ARM handheld). Its folder holds the three cores, their licenses and `SOURCE.txt`:
   - `pokedaisy_gba_libretro.so` plays Game Boy Advance games.
   - `pokedaisy_gbc_libretro.so` plays Game Boy and Game Boy Color games (and Game Boy Advance too).
   - `pokedaisy_ds_libretro.so` plays DS games.
2. Copy the cores into your frontend's cores folder. RetroArch shows where that is under **Settings** > **Directory** > **Cores**.
3. Load your game with the PokeDaisy core (the DS core shows as PokeDaisy DS).

Your frontend keeps the save, states and fast-forward as it does for any core. The companion's settings go in the frontend's save folder: `pokedaisy/settings.ini` for the Game Boy and Game Boy Advance cores, `pokedaisy-ds/settings.ini` for the DS core. Keep the licenses with the cores if you pass them on.

### Already installed: plorpOS and spruceOS

On [plorpOS](https://github.com/thwonp/plorpos) and the nightly builds of [spruceOS](https://github.com/spruceUI/spruceOS), the Game Boy, Game Boy Color and Game Boy Advance cores come installed already, so there's nothing to download: pick PokeDaisy as the core for your game (on plorpOS, in a Game Boy shelf's Core row).

## Bring your saves

In the PokeDaisy program (the first three downloads), a game's save is in the `saves` folder next to `roms`, named after the whole ROM file name plus `.sav`: for `FireRed.gba` that's `saves/FireRed.gba.sav`. A save from another emulator works when you copy it there under that name, a RetroArch `.srm` included. Keep a copy of your old save somewhere else first.

Before a game opens, PokeDaisy copies its save to `saves/pokedaisy-backups` and keeps the newest 10 copies, so an older one is there if you need it. Save states from another emulator don't load.

In RetroArch, NXRedux and the other frontends, the save is the frontend's own file, where it always keeps it.

## Updating

Download the newer zip and extract it over the old folder (on NXRedux, at the top of the SD card again). Your games, saves, states and settings aren't in the zip, so they stay as they are. In a frontend, copy the new cores over the old ones.

## If something's wrong

- **Permission denied when you start it:** the extractor lost the program's run permission. In the folder, run `chmod +x pokedaisy-linux`.
- **An error naming `GLIBC_2.35` or `libSDL2`:** your Linux is older than PokeDaisy needs, or SDL2 isn't installed. Install SDL2 from your distribution (on Ubuntu, `sudo apt install libsdl2-2.0-0`), or try a newer system.
- **A game isn't listed:** it has to be right in `roms` (not in a folder inside it), unzipped, and end in `.gba`, `.gb`, `.gbc` or `.nds`.
- **A and B are the wrong way round:** open the menu (START and SELECT together, or the Guide or Menu button) and set `A/B BUTTONS` to `SWAPPED`.
- **My save didn't load:** the save needs the ROM's whole file name with `.sav` added, in `saves`. Check `saves/pokedaisy-backups` for an older copy.
- **The controller does nothing:** SDL doesn't know it yet. Put a `gamecontrollerdb.txt` with its mapping in the data folder (next to `roms`).

Still stuck? [Tell us](https://pokedaisy.com/feedback/#bug) which device and file you used. Started from a terminal, PokeDaisy prints what happened there; the TrimUI app writes it to `launch.log` and the PortMaster port to `log.txt`, both in its folder.
