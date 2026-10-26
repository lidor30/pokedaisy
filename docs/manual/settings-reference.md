---
title: Every setting
summary: A reference of every setting on the top screen's Settings and the companion's SETTINGS tab, with its choices, its default and where to read more.
section: help
order: 2
keywords: [options, preferences, configuration, config, defaults, menu, reference, list]
---

PokeDaisy has two settings screens. **Settings** on the top screen opens from the library (the gear) and has everything, including the things that need a keyboard or a file picker. **SETTINGS** on the companion is the gear tab on the bottom screen, there while you play; changes made there show on the game straight away.

Many rows are on both. They're the same setting, so changing it on one screen changes it on the other. The tables below list each screen's rows in the order the app shows them, with the default in **bold**. A row with an arrow at its right end opens a page of its own; the others flip, or open a short list to pick from. On a wide enough screen both lists have their groups as tabs down the left side: tap one to jump to that group, and the tab of the group you're in stays lit, following the cursor as you move through the list.

> [!TIP]
> The top screen's Settings has a search box. Type what you're after, even in other emulators' words ("rebind", "box art", "turbo"), and the matching rows show.

## Top screen Settings

### FF and speed

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `FF SPEED` | `INFINITE`, 1.5x, **2x**, 3x, 4x, 5x, 6x, 8x, 10x | How fast fast-forward runs. `INFINITE` means as fast as the device can go. | [Fast-forward](fast-forward.md) |
| `REWIND` | `ON`, **`OFF`** | Keeps the last 20 seconds or so, so the `REWIND HOLD` hotkey can play them backwards. Never touches your save file. | [Fast-forward](fast-forward.md) |
| `SPEED OPTIONS` | a page | `FF MODE` and `FF MUSIC`; see the next table. | [Fast-forward](fast-forward.md) |

The `SPEED OPTIONS` page:

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `FF MODE` | **`SMART`**, `NORMAL` | `SMART` drops to normal speed in menus, on the region map and while a battle waits for your command, and keeps the rest of a battle fast. `NORMAL` is fast everywhere. | [Fast-forward](fast-forward.md) |
| `FF MUSIC` | **`STEADY`**, `SPED-UP`, `OFF` | What you hear while fast-forwarding: the song at its normal tempo, the game's audio sped up, or silence. | [Fast-forward](fast-forward.md) |

### Screen

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `ASPECT` | **`ORIGINAL`**, `STRETCH`, `EXPAND` | The GBA's 3:2, stretched to fill the screen, or more of the map drawn past the game's edges. Opens a preview to pick from. | [Screen](screen.md) |
| `AUTO ZOOM IN` | **`ON`**, `OFF` | Only with `ASPECT` on `EXPAND`: conversations, signs and menus ease the zoom back in. Battles always do. | [Screen](screen.md) |
| `HUD` | **`ON`**, `OFF` (a page) | Your party, a minimap, your Poké Balls and `BAG` / `WILD` buttons on the game while you walk around, with `LAYOUT`, `LOOK`, `SIZE` and which parts show. | [HUD](hud.md) |
| `BATTLE BUTTONS` | **`ON`**, `OFF` | Only on a device with one screen (or with `SINGLE SCREEN` on): `FIGHT`, `POKéMON` and `RUN` at the top right, beside the battle. | [Battle](battle.md) |
| `DISPLAY OPTIONS` | a page | `STATUS BAR`, `SHADERS`, `OVERLAY`, `LIGHT COMPANION` and, on a phone, `COMPANION`; see the next table. | [Screen](screen.md) |
| `COMPANION VIEW` | **`WINDOW`**, `SIDE PANEL` | Only on a device with one screen (or with `SINGLE SCREEN` on): the companion as a window in the middle, over the game, or as a panel on the right, when you hold it sideways. | [Devices](devices.md) |
| `COMPANION BUTTON` | **`ON`**, `OFF` | Where `COMPANION VIEW` shows: the small button at the bottom of the screen's right edge that opens the companion. Off, `BACK` and the HUD's `COMPANION` button still do. | [Devices](devices.md) |
| `SWAP SCREENS` | `ON`, **`OFF`** | Only on a device with two screens: the game on the second screen and the companion on the main one. With a TV as the second screen it starts `ON`: the game on the TV. | [Devices](devices.md) |
| `SINGLE SCREEN` | `ON`, **`OFF`** | Only on a device with two screens: play on one, the second screen free for other apps and the companion as a window over the game. | [Devices](devices.md#single-screen) |
| `THEME` | **`POKéDAISY`**, `FIRERED`, `LEAFGREEN`, `EMERALD`, `RUBY`, `SAPPHIRE`, `PLATINUM`, `HEARTGOLD`, `SOULSILVER` | The backdrop of the library, Settings and setup. Nothing changes in game. | [Screen](screen.md) |
| `DARK MODE` | **`OFF`**, `ON`, `AUTO` | Dark windows on both screens. `AUTO` follows the device. | [Screen](screen.md) |
| `FPS / CPU` | `ON`, **`OFF`** | Frames per second and the app's CPU use in the game's corner. Tap it for more. | [Screen](screen.md) |

The `DISPLAY OPTIONS` page:

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `STATUS BAR` | **`OFF`**, `GAME`, `COMPANION` | A strip with the game, location, money, clock and battery, over the game or over the companion's tabs. | [Screen](screen.md) |
| `SHADERS` | a page (shows the current filter) | `FILTER`, `GRID`, `SMOOTHING`, `GBA COLORS`, `UNLIT SCREEN` and `ON COMPANION`; see the next table. | [Screen](screen.md) |
| `OVERLAY` | **`NONE`**, `DAISY`, `BEZEL`, your imports (a page) | A frame around the game. Import your own RetroArch overlays here with `IMPORT FILE`. | [Screen](screen.md) |
| `LIGHT COMPANION` | **`AUTO`**, `ON`, `OFF` | For slow devices: the companion redraws less and without animations, so your buttons don't reach the game late. `AUTO` turns it on where it's needed. | [Screen](screen.md#light-companion-for-slow-devices) |
| `COMPANION` | `AT BOTTOM`, **`UNDER GAME`** | Only on a device with one screen: where the companion sits when you hold it upright. With `EXPAND`, `UNDER GAME` gives the touch controls their own strip at the bottom, and it's greyed out without touch controls. | [Devices](devices.md) |

The `SHADERS` page:

| Setting | Choices | What it does |
| --- | --- | --- |
| `FILTER` | **`NONE`**, `LCD`, `LCD PAPER`, `SCANLINES`, `CRT` | The screen effect over the game. Each tap steps to the next one. |
| `GRID` | `SOFT`, **`MEDIUM`**, `STRONG` | How strong the grid is for `LCD` and `LCD PAPER`. Greyed out for the others. |
| `SMOOTHING` | **`OFF`**, `xBRZ`, `xBR`, `SCALEFX` | Smooths the pixel art's edges on the game (never the companion). Shows `N/A` on devices that can't run it. |
| `GBA COLORS` | `ON`, **`OFF`** | The colours as the GBA's own screen showed them. Works with any filter. |
| `UNLIT SCREEN` | `ON`, **`OFF`** | The first GBA's screen with no light behind it: white shows the screen's grey green backing, and dark pixels cast a soft shadow. Works with any filter. |
| `ON COMPANION` | **`ON`**, `OFF` | `FILTER` and `GBA COLORS` on the companion and the status bar too (Android 13 and newer). |

### Library

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `RESUME GAMES` | **`ON`**, `OFF` | On, a game opens right where you left it. Off, it starts at the title screen and loads your save. | [Saves](saves.md) |
| `DUAL-SCREEN LAUNCHER` | **`ON`**, `OFF` | With two screens, the library shows the game list on one and the selected game's media on the other. Off, the plain library. | [Library](library.md#the-dual-screen-launcher) |
| `COVER ART` | `ON` once a key is saved, else **`OFF`** (a page) | Your own free keys for RetroAchievements box art and SteamGridDB icons (`GET KEY` opens the page to make one). `REPLACE ALL` fetches every cover again. | [Library](library.md) |
| `FOLDERS` | a page | Your linked ROMs folder (`CHOOSE FOLDER`, `RESCAN`, `UNLINK`), the folder saves go to (`CHANGE FOLDER`, `USE DEFAULT`), `ALSO LOOK IN` for other emulators' save folders, and where save states are kept. | [Library](library.md), [Saves](saves.md) |
| `HIDDEN GAMES` | **`NONE`**, or how many | Games you hid from the library with `HIDE`. Tap one to `SHOW` it again, or `SHOW ALL`. | [Library](library.md) |
| `CHEATS` | `ON`, **`OFF`** (a page) | The master switch for cheat codes, and each game's codes: `ADD CODE`, `IMPORT FILE`, `REMOVE`. | [Cheats](cheats.md) |

### Online

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `RetroAchievements` | your user name, or **`OFF`** (tagged `BETA`) | Sign in to earn achievements (softcore). Signed in means on; `SIGN OUT` turns it off. | [Achievements](achievements.md) |

### Controls

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `GAME BUTTONS` | a page | Which button presses A, B, L, R, `START` and `SELECT`, plus `X`, `Y`, `TURBO A` and `TURBO B`. Press the button you want on each row. | [Controls](controls.md) |
| `HOTKEYS` | **`ON`**, `OFF` (a page) | Shortcuts for save states, fast-forward, slow motion, rewind, `ZOOM`, opening the companion and leaving the game. Each can be a combination of buttons, or cleared with `CLEAR`. Off, every button goes to the game. | [Controls](controls.md) |
| `QUICK MENU` | a page | Its `STYLE` (**`RING`**, `GRID`), `LABELS` (**`ON`**, `OFF`) and which actions it shows (all of them to start with). Its button is the `QUICK MENU` hotkey. | [Controls](controls.md) |
| `TOUCH CONTROLS` | a page | The on-screen buttons: `SHOW` (**`AUTO`**, `ALWAYS`, `NEVER`), `LAYOUT` (**`SIDES`**, `CLASSIC`, `COMPACT`, `CUSTOM`), `EDIT LAYOUT`, `POSITION` (`TOP`, **`MIDDLE`**, `BOTTOM`), `COMPANION` (upright, one-screen devices), `SIZE`, `OPACITY`, `D-PAD`, `FADE WHEN IDLE`, `VIBRATION` and the extra buttons (`FF`, `QUICK MENU`, turbo, save and load state). A preview shows the result. | [Controls](controls.md#touch-controls) |

### App

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `VERSION` | the version number | Tap to look for a newer version now. | [Install](install.md) |
| `RUN SETUP` | | Back to the library with the first-time setup open again (ROMs folder, saves folder, game buttons, cover art). | [Install](install.md) |
| `CRASH REPORTS` | `ON`, **`OFF`** | Sends a short report when the app crashes, only if you turn it on. Only in official builds. | [Troubleshooting](troubleshooting.md#what-does-pokedaisy-send-over-the-internet) |
| `LICENSES` | a page | The licenses of PokeDaisy and everything it includes. | |
| `LANGUAGE` | **`AUTO`**, `ENGLISH`, `日本語`, `FRANÇAIS`, `DEUTSCH`, `ITALIANO`, `ESPAÑOL` | The app's own text. `AUTO` follows the game's language in game and the device's in the library. Game names (Pokémon, moves, items) stay as the game has them. | [The companion](companion.md) |
| `HELP` | a page | This manual, with a search box. It keeps a copy for reading offline. | [Welcome](welcome.md) |

## Companion SETTINGS

The companion's list puts what you change while you play first: speed, then `GAMEPLAY` (a group of its own), the screen and the companion itself, with the buttons and the app's language last. `CLOSE GAME` and `RESTART GAME` sit at the bottom of the list. Tabs you've taken off the tab bar are in the `MORE` tab, left of the gear.

### Game (for an unknown ROM)

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `PLAYS AS` | the game it's read as | Only for a ROM PokeDaisy doesn't know but reads as one it does. Opens **WHICH GAME IS THIS?** to pick another. | [Supported games](supported-games.md#which-game-is-this) |

### FF and speed

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `FF` | `ON`, **`OFF`** | Fast-forward on or off, like the `FF TOGGLE` hotkey. | [Fast-forward](fast-forward.md) |
| `FF SPEED` | as on the top screen | How fast fast-forward runs. | [Fast-forward](fast-forward.md) |
| `GAME SPEED` | 0.25x, 0.5x, **1x**, 1.5x, 2x, 3x, 4x | A pace that stays, saved for each game. Below 1x the sound is muted. Fast-forward, when on, wins. | [Fast-forward](fast-forward.md) |
| `REWIND` | as on the top screen | Keep the last seconds to rewind. | [Fast-forward](fast-forward.md) |
| `SPEED OPTIONS` | a page | `SCENE SPEEDS`, then `FF MODE` and `FF MUSIC`; see the next table. | [Fast-forward](fast-forward.md) |

The `SPEED OPTIONS` page:

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `SCENE SPEEDS` | each **`SAME`** | `BATTLE SPEED`, `TEXT SPEED`, `MENU SPEED`, `BIKE SPEED` and `SURF SPEED`: a speed of their own while fast-forward is off. | [Fast-forward](fast-forward.md) |
| `FF MODE` | as on the top screen | Where fast-forward slows down. | [Fast-forward](fast-forward.md) |
| `FF MUSIC` | as on the top screen | What fast-forward sounds like. | [Fast-forward](fast-forward.md) |

### Makeover

At the top of SETTINGS, in a blue frame, in the games where any of it works.

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `PLAYER LOOK` | **`OFF`**, another game's hero, or a Pokémon in your party | Plays as the hero of another FireRed, LeafGreen, Ruby, Sapphire or Emerald game (or Heart and Soul) you have, taken from your own copy, picked from tiles that show each one. With a game whose Pokémon follow you (Heart and Soul, R.O.W.E., Recharged Yellow or Emerald Seaglass), your party's Pokémon too. `N/A` until one is found. | [Companion](companion.md#player-look) |
| `RIVAL LOOK` | **`ON`**, `OFF` | Your rival wears the other hero of the game you picked. Ruby, Sapphire and Emerald only; `N/A` until you pick a `PLAYER LOOK`. | [Companion](companion.md#rival-look) |
| `TEXT LOOK` | **`OFF`**, or the other game | FireRed's text boxes and letters in Emerald, or Emerald's in FireRed and LeafGreen, from your own copy of that game. Shows once that game is found. | [Companion](companion.md#text-look) |
| `FOLLOWER` | `ON`, **`OFF`** | Your first Pokémon walks behind you on the map, drawn over the game. Needs Heart and Soul, R.O.W.E., Recharged Yellow or Emerald Seaglass too (their sprites); not in games whose Pokémon follow you already. | [Companion](companion.md#follower) |
| `DAY AND NIGHT` | `ON`, **`OFF`** | The map outdoors follows your clock: dawn, dusk and night tints. Text boxes, menus, buildings and battles keep their colours. Every GBA game but those with a day and night of their own. | [Companion](companion.md#day-and-night) |

### Gameplay

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `WILD ENCOUNTERS` | **`ON`**, `OFF` | Wild Pokémon jumping out in grass, caves and water, or none. `N/A` in a game where it can't work. | [Controls](controls.md) |
| `REUSABLE TMS` | `ON`, **`OFF`** | A TM stays in your bag after it teaches a move, like an HM. `ON` and greyed out where the game already keeps its TMs, `N/A` where it can't work. | [Bag](bag.md) |
| `AUTO-RUN` | `ON`, **`OFF`** | Walking on the field runs without holding B; holding B walks instead. | [Controls](controls.md) |
| `RUN INDOORS` | `ON`, **`OFF`** | Lets you run inside buildings too. `N/A` on Ruby and Sapphire. | [Controls](controls.md) |
| `CHEATS` | **`OFF`**, or how many are on (a page) | The master switch, then this game's cheats, each flipped live. Codes are added on the top screen. | [Cheats](cheats.md) |

### Screen

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `ASPECT` | as on the top screen | A pick-list here. `EXPAND` is tagged `NOT YET` in a game that doesn't have it. | [Screen](screen.md) |
| `AUTO ZOOM IN` | as on the top screen | Only with `ASPECT` on `EXPAND`. | [Screen](screen.md) |
| `HUD` | a page | The same rows as on the top screen; the game changes as you tap. | [HUD](hud.md) |
| `BATTLE BUTTONS` | as on the top screen | Only on a device with one screen (or with `SINGLE SCREEN` on): `FIGHT`, `POKéMON` and `RUN` beside the battle. | [Battle](battle.md) |
| `DISPLAY OPTIONS` | a page | As on the top screen. | [Screen](screen.md) |
| `COMPANION VIEW` | as on the top screen | Only on one screen held sideways (or with `SINGLE SCREEN` on). Switches right away. | [Devices](devices.md) |
| `COMPANION BUTTON` | as on the top screen | With `COMPANION VIEW`. Switches right away. | [Devices](devices.md) |
| `SWAP SCREENS` | as on the top screen | Only with two screens. | [Devices](devices.md) |
| `SINGLE SCREEN` | as on the top screen | Only with two screens. Switches right away, and the companion opens over the game. | [Devices](devices.md#single-screen) |
| `DARK MODE` | as on the top screen | Dark windows on both screens. | [Screen](screen.md) |
| `FPS / CPU` | as on the top screen | The performance readout. | [Screen](screen.md) |

The `DISPLAY OPTIONS` page:

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `STATUS BAR` | as on the top screen | The strip with game, location, money, clock and battery. | [Screen](screen.md) |
| `SHADERS` | a page | The same five rows as on the top screen; the game changes as you tap. | [Screen](screen.md) |
| `OVERLAY` | as on the top screen | Pick a frame. Importing happens on the top screen. | [Screen](screen.md) |
| `COMPANION` | as on the top screen | Only on a phone held upright. | [Devices](devices.md) |

### Companion

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `BATTLE HINTS` | **`ON`**, `OFF` | Move ratings, the foe's weaknesses and suggestions during battles. | [Battle](battle.md) |
| `ONE-TAP MOVES` | `ON`, **`OFF`** | Puts your moves on the battle's first menu, so one tap picks FIGHT and the move. | [Battle](battle.md) |
| `FOE IVS` | `ON`, **`OFF`** | A STATS page in battle with the foe's IVs, EVs and nature. | [Battle](battle.md) |
| `BATTLE FOCUS` | `ON`, **`OFF`** | Battle `INFO` as one page with no scrolling: one Pokémon's ability, item, stats, moves and matchups, a button to turn from the foe to yours, the foe's team down the side. No `SUGGESTIONS` or `STATS` there. | [Battle](battle.md) |
| `TAB BAR` | **5 tabs** (a page) | Which tabs sit next to the gear: up to 5, `SHOWN` or `HIDDEN`. By default `PARTY`, `DEX`, `MAP`, `BAG` and `GUIDE`. A tab the game doesn't have gives its place to another of the game's tabs. The rest open from `MORE`, left of the gear. | [The companion](companion.md) |
| `CLICK SOUND` | **`ON`**, `OFF` | The game's own menu click on every companion button. | [The companion](companion.md) |
| `TWEAKS` | a page, each **`ON`** | `PARTY ICONS MOVE`, `MAP CURSOR BLINK`, `TAB ANIMATIONS` and `JUMP TO BATTLE` (a battle opens the battle tab by itself). | [The companion](companion.md) |

### Controls

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `GAME BUTTONS` | a page | As on the top screen: press the button you want on each row. | [Controls](controls.md) |
| `HOTKEYS` | a page | As on the top screen: turn hotkeys on or off, and press new buttons for each. | [Controls](controls.md) |
| `QUICK MENU` | a page | As on the top screen. | [Controls](controls.md) |
| `TOUCH CONTROLS` | a page | As on the top screen; its `EDIT LAYOUT` opens over the game. | [Controls](controls.md#touch-controls) |

### App

| Setting | Choices | What it does | More |
| --- | --- | --- | --- |
| `LANGUAGE` | as on the top screen | The app's own text, on both screens at once. | [The companion](companion.md) |
| `HELP` | a page | This manual, without the search box (the bottom screen has no keyboard). | [Welcome](welcome.md) |

### Buttons at the bottom

| Button | What it does |
| --- | --- |
| `CLOSE GAME` | Back to the library. With `RESUME GAMES` on, your place is kept; off, anything since your last in-game save is lost. |
| `RESTART GAME` | Reboots the game from its save file. Unsaved progress is lost. |
| `CLOSE COMPANION` | On two screens: frees the companion's screen (the top one with `SWAP SCREENS` on) for other apps while the game keeps running. Press BACK on the game to bring the companion back. With `SWAP SCREENS` on, BACK opens it over the game instead, and its `SETTINGS` shows `OPEN COMPANION` here to put it back on the top screen. |

See [The companion](companion.md) for the tabs themselves.
