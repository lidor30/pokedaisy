---
title: Controls, hotkeys and touch
summary: Remap the game's buttons, set up turbo, change or turn off the hotkeys, set up the touch controls or an external controller, and what the BACK button does.
section: player
order: 2
keywords: [remap, rebind, keybind, key bindings, buttons, mapping, controller, gamepad, joystick, bluetooth, 8bitdo, gamesir, xbox, keyboard, shortcut, chord, combo, turbo, autofire, rapid fire, triggers, l2, r2, on screen controls, virtual pad, touch pad, touch controls, layout editor, move buttons, resize buttons, opacity, haptics, vibration, wild encounters, encounter rate, random battles]
---

PokeDaisy works out of the box on handhelds like the AYN Thor: the face buttons, shoulders, START and SELECT are the GBA's, and a few button combinations do emulator things like saving a state. Everything here is about changing that: which button presses what, which shortcuts exist, and what happens when you plug in a controller or play by touch.

The button settings live in the `CONTROLS` group, on both screens: the top screen's Settings (from the library) and the companion's `SETTINGS`. `AUTO-RUN`, `RUN INDOORS` and `WILD ENCOUNTERS` change the game itself, so they're in the companion's `GAMEPLAY` group, near the top.

## Game buttons

`GAME BUTTONS` sets which physical button presses each GBA button. Open it and you'll see a row per GBA button with the keys it's on now.

| GBA button | Default |
| --- | --- |
| `GBA A` and `GBA B` | A and B |
| `GBA L` and `GBA R` | L1 and R1 |
| `GBA START` and `GBA SELECT` | START and SELECT |
| `GBA START 2` and `GBA SELECT 2` | X and Y |
| `GBA TURBO A` and `GBA TURBO B` | Nothing |

`START 2` and `SELECT 2` are a second START and SELECT, so X opens the menu and Y uses your registered item, like in the DS games. Each GBA button also has a keyboard key (Z, X, A, S, Enter and so on) for when you play with one.

To change one, tap its row (it says `PRESS A BUTTON…`) and press the button you want on your device. That works the same on the top screen and on the companion: there, while a row waits, the game's screen shows a window asking for the button and the keys you hold. `CANCEL`, or `BACK`, leaves the button as it was. `TURBO A` and `TURBO B` also get `CLEAR`, which takes them off every button again.

The new button replaces everything that GBA button had, keyboard key included. A button can only do one GBA button: put it on a new one and it leaves the old one.

> [!NOTE]
> Buttons go by Android's names, which follow an Xbox controller's layout. On AYN and Retroid devices, `A` is the bottom face button even where the printed label says something else. If A and B feel swapped, swap them here.

The D-pad and the analog stick always move; they can't be remapped. First-time setup has a page listing these buttons too, and you change them right there the same way.

![The top screen's GAME BUTTONS page, a row for each GBA button with the keys it's on](shots/settings-buttons.png "GAME BUTTONS on the top screen")

## Turbo A and B

`TURBO A` and `TURBO B` press A or B over and over for as long as you hold them. They're good for mashing through text, or for anything that wants you to hammer a button. They aren't on any button until you put them on one in `GAME BUTTONS`.

Turbo counts in game time, so with fast-forward on it mashes faster along with the game. It pauses while the companion's battle buttons are pressing the game's buttons for you.

## Hotkeys

Hotkeys are shortcuts for things outside the game: save states, fast-forward, leaving the game. A hotkey can be one button or a **chord**, several buttons pressed together. SELECT + R1 is a chord: the second button fires it while the first is held.

Chords don't take a button away from the game. SELECT alone still works in game; only SELECT + R1 together saves a state.

| Hotkey | What it does | Controller | Keyboard |
| --- | --- | --- | --- |
| `SAVE STATE` | Saves a state in the current slot | SELECT + R1 | F1 |
| `LOAD STATE` | Loads the current slot's state | SELECT + L1 | F2 |
| `UNDO SAVE` | Puts back the state your last save replaced | | F3 |
| `UNDO LOAD` | Returns to where you were before your last load | | F4 |
| `SLOT NEXT` | Next save state slot | | ] |
| `SLOT PREV` | Previous save state slot | | [ |
| `FF HOLD` | Fast-forward while held | R2 | Space |
| `FF TOGGLE` | Fast-forward on or off | | Tab |
| `SPEED CYCLE` | Steps the game speed through 1x, 1.5x, 2x, 3x and 4x | L2 | = |
| `SLOWMO HOLD` | Half speed while held | | minus |
| `REWIND HOLD` | Plays the last moments backwards (needs `REWIND` on) | | R |
| `ZOOM` | Steps the zoom (needs `ASPECT` set to `EXPAND`) | R3 (shown as `THUMBR`) | C |
| `SOFT RESET` | The game's own soft reset: holds A + B + SELECT + START, back to the title screen. Anything since your last in game save is lost, as on the console | | H |
| `EXIT GAME` | Leaves the game, back to the library | SELECT + START | Esc |
| `RESTART GAME` | Starts the game again from its save, like `RESTART GAME` in the companion's `SETTINGS` | | |
| `CLOSE APP` | Leaves the game (it saves as usual) and closes PokeDaisy completely, so it's gone from your recent apps | | |
| `QUICK MENU` | Opens the [quick menu](#the-quick-menu), or closes it | L3 (shown as `THUMBL`) | |
| `COMPANION` | Opens the companion over the game on one screen, where you can hide its edge button with `COMPANION BUTTON`. Also brings it back after `CLOSE COMPANION` | | |

`RESTART GAME`, `CLOSE APP` and `COMPANION` have no buttons until you give them some.

The speed hotkeys are explained in [Fast-forward and game speed](fast-forward.md), states and rewind in [Saves](saves.md), and zoom in [Screen](screen.md).

![The top screen's HOTKEYS page: the HOTKEYS switch, then each hotkey with its buttons](shots/settings-hotkeys.png "HOTKEYS on the top screen")

### Changing a hotkey

Tap a hotkey, under Settings > `HOTKEYS` on the top screen or `SETTINGS` > `HOTKEYS` on the companion; it says `PRESS KEYS…`. Press one button, or hold several together and release, to set it. While a hotkey waits for keys:

- `CLEAR` leaves it with no button at all (it shows `-`).
- `CANCEL`, or `BACK`, keeps it as it was.

On the companion, the game's screen shows a window while a hotkey waits, with the buttons you're holding, since that's where your presses go. The game doesn't see them meanwhile, and neither do the other hotkeys.

![Over the game while a companion hotkey waits: SAVE STATE, the button held so far, CLEAR SAVE STATE and CANCEL](shots/game-capture.png "The game's screen while the companion waits for keys")

The new buttons replace the hotkey's whole binding, keyboard key included.

![The companion's HOTKEYS page with the HOTKEYS switch and every hotkey's buttons](shots/companion-settings-hotkeys.png "HOTKEYS on the companion")

![The companion's HOTKEYS page with LOAD STATE waiting for keys: SELECT + R1 held, CLEAR LOAD STATE and CANCEL above the list](shots/companion-settings-hotkeys-capture.png "A hotkey waiting for keys on the companion")

### When a button is already taken

If you pick exactly the buttons another hotkey already has (the same buttons in any order), you get `KEY IN USE`. Tap `MOVE` to take them from the other hotkey, or cancel to leave both as they were. Sharing only some buttons is fine: SELECT + R1 and SELECT + L1 can both exist, that's how chords work.

### Turning hotkeys off

The `HOTKEYS` switch at the top of the page turns every hotkey off at once. The bindings stay as they are (greyed out) and every button goes straight to the game. It's handy if a game needs a combination that clashes with a hotkey, and you'd rather not rebind anything.

## The quick menu

The quick menu puts the things you'd otherwise dig through menus for under one button. Press L3 (the left stick, pushed in) and your actions pop up over the game, as a ring or in rows (see `STYLE` below). The rest of the HUD fades back while it's open, and any HUD panel you had open (a summary, the map, the bag, the wild list) closes:

| Action | What it does |
| --- | --- |
| `PC` | Opens your game's own PC right where you stand, as in the [bag](bag.md). |
| `FLY` | Lists the places you can fly to; pick one and off you go. Needs a Pokémon in your party that knows the move. |
| The keep-away spray | Uses an item from your bag that keeps wild Pokémon away (the tile shows its icon and name). With more than one kind in your bag, it lists them with how many you have and how many steps each lasts, so you pick. While one is still working it's greyed out and says how many steps are left. |
| `BIKE` | Gets on your bike. While you're riding it says `GET OFF` and gets you off. |
| `ROD` | Casts your best fishing rod. Face the water first. |
| `WILD` | Turns `WILD ENCOUNTERS` off, and back on the next time. An `OFF` tag on its corner says they're off. It shows in games where `WILD ENCOUNTERS` works. |
| `SAVE STATE`, `LOAD STATE` | Saves or loads the current state slot. |
| `FF` | Turns fast-forward on or off. |
| `COMPANION` | Opens the companion (on a device with one screen). |

Press L3 and the menu stays open: move with the D-pad or the left stick (on the ring, point the stick at an action), press `A` to use it, and `B` (or L3 again) closes it. You can also tap an action on the screen. While the menu is open your buttons work it, not the game, and the game keeps running underneath.

An action you can't use right now is greyed out and says why, like a battle going on or nothing for it in your bag. `BIKE` and `ROD` use the game's own way of using a registered item, so the game decides the rest: where you can't ride or fish, it tells you so. They work in FireRed, LeafGreen, Ruby, Sapphire and Emerald, and in Emerald Kaizo and Ultra Violet, which run on those games. `PC`, `FLY` and the spray show only in games where the [bag](bag.md) and [map](map.md) offer them.

Pick which actions show, and turn the rest off, in `QUICK MENU` (in the `CONTROLS` group of Settings on either screen). Its `STYLE` picks how they're laid out (tap it to switch):

- `RING` (to start with): the actions round a circle, like the weapon wheel in a modern game, each the same distance from the next. Point the left stick at one, and its name shows in the middle, with a light tick of vibration each time you move onto another one. The D-pad steps round it too.
- `GRID`: rows of tiles, all the same size. The D-pad steps through them.

`LABELS` (on to start with) puts each action's name under its icon. Turn it off for icons alone, smaller and closer together: the highlighted one is then named under the row, or in the middle of the ring.

To use another button, change the `QUICK MENU` hotkey.

## Touch controls

`TOUCH CONTROLS` puts on-screen buttons over the game, for when you have no real ones: a phone, or a tablet without a controller. It's a page of its own in the `CONTROLS` group, on both screens. At its top, a picture of your screen shows the buttons as they are now, and it changes as soon as you change a setting. Upright it shows the part of the screen around the buttons, so they come out bigger. The button under it says which way the phone is held in the picture (`UPRIGHT` or `SIDEWAYS`); tap it to see the other. Tap any setting on the page to step to its next choice.

![TOUCH CONTROLS on a phone held upright: the preview of the screen with the buttons, then the settings](shots/settings-touch-phone.png "TOUCH CONTROLS on a phone")

- **`SHOW`**: `AUTO` (the default) shows them only when no controller is connected, and hides them as soon as you press a real button. `ALWAYS` keeps them on screen, `NEVER` never shows them. On a phone with nothing plugged in, `AUTO` shows them.
- **`LAYOUT`**: where the buttons sit.
  - `SIDES` (the default): everything in the black bars beside the game when you hold the phone sideways, so nothing covers the picture. When the bars are too narrow for the buttons (an older 16:9 phone, or a phone held upright) it works like `CLASSIC`.
  - `CLASSIC`: the D-pad bottom left, A and B bottom right, `START` and `SELECT` in the middle, L and R in the top corners.
  - `COMPACT`: like `CLASSIC`, smaller and tucked into the corners, with `SELECT` above the D-pad and `START` above A and B.
  - `CUSTOM`: the one you made in `EDIT LAYOUT`. It shows up once you've made one, and picking a ready-made layout keeps it for later.
- **`EDIT LAYOUT`**: move the buttons yourself (see below).
- **`POSITION`**: with the phone held upright, where the buttons sit in their space: `TOP`, `MIDDLE` (the default) or `BOTTOM`. Their space is the gap between the game and the companion, or the game itself when there's no gap. It doesn't move a `CUSTOM` layout, which keeps your own places. With `EXPAND` and `UNDER GAME` the buttons get a space that fits them exactly, so it's greyed out then.
- **`COMPANION`**: with the phone held upright, the companion `AT BOTTOM` of the screen (the default) or right `UNDER GAME`, which leaves the space at the bottom of the screen for the buttons. It's the same setting as the one in `DISPLAY OPTIONS`, and only on phones and other one-screen devices. With `EXPAND` on, the game fills everything above the companion, so `UNDER GAME` gives the buttons a strip of their own along the bottom of the screen instead, under the companion, where they never cover the game. With no touch controls on screen there's nothing to choose then, and the setting is greyed out.
- **`SIZE`**: `SMALL`, `NORMAL`, `LARGE` or `X-LARGE`, for every button at once.
- **`OPACITY`**: how solid the buttons are, from 25% to 100%. 50% to start with.
- **`D-PAD`**: `8-WAY` (the default) lets you press diagonals; `4-WAY` presses one direction at a time, which helps if you keep walking the wrong way in a menu.
- **`FADE WHEN IDLE`**: the buttons fade to a faint outline a few seconds after you stop touching them, and come back with your next touch.
- **`VIBRATION`**: a light tick each time you press something. Off to start with. It follows your phone's own touch vibration setting.
- **`RESET LAYOUT`**: forgets `CUSTOM` and goes back to the ready-made layout. Only there once you've made one.

On a phone held upright the buttons sit between the game and the companion when there's room; see [Devices](devices.md). Your thumb can slide from one button to the next without lifting: from one direction to another on the D-pad, or from B onto A. Touching between A and B presses both.

### Extra buttons

The `EXTRA BUTTONS` group adds buttons the GBA doesn't have. Turn each one on or off:

- **`FF`** (on to start with): a tap turns fast-forward on or off; hold it for fast-forward only while you hold it. Its word lights up while fast-forward is on.
- **`QUICK MENU`** (on to start with): opens the [quick menu](#the-quick-menu). A phone has no button for it otherwise. Tap a tile to run it; a tap anywhere else closes it.
- **`TURBO A`** and **`TURBO B`**: press A or B over and over while you hold them, like the [turbo buttons](#turbo-a-and-b).
- **`SAVE STATE`** and **`LOAD STATE`**: save or load a state in the current slot, like the hotkeys. If you load one by mistake, the `UNDO LOAD` hotkey takes you back.

### Moving and resizing buttons: EDIT LAYOUT

`EDIT LAYOUT` shows every button at its real size with a dashed outline. In a game it opens over the game itself (from the companion's `SETTINGS`); from the top screen's Settings it opens over a picture of the game's screen.

![EDIT LAYOUT on a phone held sideways: the buttons with dashed outlines in the bars beside the game, the bar with DONE, SNAP ON and RESET at the top](shots/settings-touch-edit.png "EDIT LAYOUT")

- **Move a button:** drag it.
- **Resize a button:** pinch it with two fingers, or tap it and use `-` and `+` in the bar at the top. Each button goes from half to twice its usual size, on top of `SIZE`.
- **Hide a button:** tap it, then `HIDE`. `HIDDEN` brings it back.
- **`SNAP ON`** lines the buttons up on a small grid; tap it for `SNAP OFF` to place them freely.
- **`RESET`** puts every button back where the ready-made layout has it.
- **`DONE`**, or `BACK`, keeps your layout and makes `LAYOUT` read `CUSTOM`.

A phone held upright and held sideways each get their own layout: you edit the one for the way you're holding the phone, so turn it to edit the other. A layout stays put on another phone or with the status bar on: each button keeps its distance from the nearest edge.

## Running: AUTO-RUN and RUN INDOORS

`AUTO-RUN` runs for you. Once your game lets you run, walking on the field runs without you holding B, and holding B walks instead. It leaves B alone everywhere else: menus, battles, conversations, and while you ride a bike, where B does something of its own. The first time you play a game it starts working after you've walked around for a few seconds. On Heart and Soul, SoulGold, Too Many Types 2, Emerald Rogue and Quetzal it can't always tell yet that you're on a bike. Elite Redux has an auto run of its own on the R button: use that one or this one, not both.

`RUN INDOORS` lets you run inside buildings too, where the games normally make you walk. It works on games built on FireRed or Emerald. On Ruby and Sapphire it shows `N/A`: those games decide where you can run in a way PokeDaisy can't change safely.

Both show `N/A` on a game PokeDaisy can't read the field of yet.

## WILD ENCOUNTERS

`WILD ENCOUNTERS` turns wild Pokémon jumping out at you in grass, caves and water on or off. It's in the companion's `GAMEPLAY` group:

- **`ON`** (the default) leaves the game's own rate.
- **`OFF`** turns them off completely.

Only random encounters change. Fishing, moves that lure wild Pokémon out and battles the story sets up still happen, and items that keep wild Pokémon away work as usual on top. The change applies at once, no restart needed.

It works on the Game Boy Advance originals in every language and on almost every supported hack, including the ones rebuilt from newer source code like SoulGold, Lazarus and Recharged Yellow. On the Game Boy it works in Red, Blue, Yellow (in every language) and Crystal. On Emerald Rogue, Quetzal and the other Game Boy games it shows `N/A`.

## External controllers

Bluetooth and USB controllers (8BitDo, GameSir, Xbox and so on) work, and you can connect one while a game is running without restarting it. A short message on the game screen says when a controller connects or goes away. Its buttons follow `GAME BUTTONS` and `HOTKEYS` like the built-in ones, and diagonals on its D-pad press both directions.

A controller's HOME or guide button, unless you've bound it to something, works like a tap of `BACK` (see below).

> [!NOTE]
> External controllers haven't been tested widely yet. If one doesn't behave, please report it with the controller's name.

## Analog L2 and R2

Some handhelds and controllers (Retroid's, for example) send their L2 and R2 triggers as analog values rather than button presses. PokeDaisy treats a trigger pulled past half way as a press of L2 or R2. That means the triggers follow your settings like any other button: by default R2 is `FF HOLD` and L2 is `SPEED CYCLE`, they stop when `HOTKEYS` is off, and you can bind them to anything, including GBA buttons. Hotkeys get first go at them, then the game.

## The BACK button

`BACK` does two different things depending on how long you press it:

- **A tap** is the companion's back. It closes whatever is open on the companion (a pick-list, a Pokémon's summary, a sub-page of `SETTINGS`) and steps back from there. With nothing left to close it does nothing, so you can't leave a game by accident. If you closed the companion with `CLOSE COMPANION`, a tap brings it back.
- **A hold** leaves the game and returns to the library (or to the frontend that launched it).

This works from either screen. On a device with one screen, a tap also slides the companion panel in and out; see [Devices](devices.md).
