# iPhone and iPad (draft)

Manual text for the iPhone and iPad app, kept out of the live manual until an iOS build ships. Each part says which page it belongs to.

## From bag.md

The same option works on iPhone and iPad. PokeDaisy checks the game's code first, so it may briefly show `N/A`
while the game opens. Your choice is remembered and never changes the ROM file. A game that already keeps its
TMs shows `ON` with the option greyed out.

On iPhone and iPad, supported GBA games also have the bag's `USE`, `GIVE`, `TAKE` and `PC` actions. They use the same game code and checks as Android. The PC closes a companion window so you can work it with the game's controls. Game Boy field actions and TM teaching are coming separately.

## From battle.md

### On iPad

In a single battle, the companion's battle buttons can choose an action or move and return with `BACK`.
When the game's party menu is supported, you can also choose who to send in. `ONE-TAP MOVES`, `BATTLE STYLE`,
`BATTLE VIEW` and `BATTLE INFO` are in the companion's `SETTINGS`, alongside the hints and foe IVs.

These buttons press the game's own keys. A choice may take a few frames while the game's cursor moves.
For a double battle or a party menu the companion cannot control yet, use the game buttons.

## From cheats.md

### On iPhone and iPad

While a GBA game is running, open `SETTINGS` > `CHEATS` in the companion. Turn on the master switch to apply this
game's enabled codes. `ADD CODE` uses the same name, code and type fields as Android. Unreadable lines are shown
before anything is added. A code you add starts on, but the master switch still decides whether it runs.

`IMPORT FILE` opens Files for a RetroArch `.cht` or mGBA `.cheats` file. Imported codes start off. Tap one to turn it
on; `REMOVE` changes the list into codes you can remove after confirmation. Codes and their on/off choices are
kept for this ROM when you reopen the game. Game Boy cheats are not supported by the current engine.

## From controls.md

#### iPhone and iPad bindings

On iPhone and iPad, `GAME BUTTONS` uses the same binding rows and button capture. Connect a controller or hardware keyboard, tap a row, press its new key, then release it. `CANCEL` or Escape leaves the binding alone. The D-pad and left stick always move. You can bind another key to a D-pad row, `TURBO A`, `TURBO B`, `START 2` or `SELECT 2`.

Your existing iOS defaults stay the same: X is A, Z is B, A and S are L and R, Return is START, and Backspace or right Shift is SELECT. A controller's shoulders and triggers press L and R. Extra X and Y bindings start empty. Changing a row replaces all its old keys. The keyboard letters keep working in text fields while the companion window is open.

Bindings are kept in `PokeDaisy/bindings/controls.properties` in Files. The keys use the same canonical names as Android, so you can copy a binding file between platforms. Buttons held while a capture or companion window closes need a release before they can reach the game again.

#### iPhone and iPad hotkeys

On iPhone and iPad, `HOTKEYS` starts off so your existing trigger buttons still press L and R. Turn it on to use the speed, state, slot, `EXIT GAME`, `RESTART GAME`, `COMPANION`, `QUICK MENU` and `MUTE` shortcuts. The defaults in the table apply to these actions. Actions that need a feature still being ported are omitted.

`FF HOLD` and `SLOWMO HOLD` stop when you release their keys, open a companion window or button capture, focus a text box, or put the app in the background. Fast-forward takes priority over half speed. The touch FF button keeps its own hold. On both platforms, releasing touch leaves a controller hold active, and releasing the controller leaves touch active. Changing bindings or turning hotkeys off releases the old hotkey holds. `SPEED CYCLE` goes through 1x, 1.5x, 2x, 3x and 4x, then back to 1x. From half or quarter speed it returns to 1x, using the same policy as Android.

Use the shared key-capture rows to change a shortcut or chord. Escape cancels a capture. SELECT and START alone keep their game action after release; a completed shortcut takes the combination. `COMPANION` opens the shared panel, and hides or reveals it in portrait. In a companion window, B or START closes it. While you type in a text box, keyboard shortcuts and game buttons leave those keys to the text box. The binding file is `PokeDaisy/bindings/hotkeys.properties` in Files.

### Touch controls on iPad

Open the companion's `SETTINGS` > `TOUCH CONTROLS` to change the same layouts, size, look, colors, opacity and D-pad
options as on Android. Changes apply to the controls straight away and are kept when you reopen the app.

Held upright, it works as on an Android phone. With `COMPANION` at `UNDER GAME` (the default), the game is at the top,
the companion right under it and the buttons in a space of their own along the bottom; the companion and then the game
get a little smaller to make that space. With `AT BOTTOM`, the companion goes along the bottom of the screen and the
buttons sit in the gap above it, or over the game when there's no gap, as on an iPad. Held sideways, the game is centered beside the companion, with touch controls over the game area.
The preview and `EDIT LAYOUT` use the same arrangement as the game screen.

`EDIT LAYOUT` pauses the game and opens the shared editor over its picture. Drag controls, resize or hide them, then
choose `DONE`. Upright and sideways layouts are kept separately. `SHOW` > `AUTO` hides the controls while a gamepad is
connected; `ALWAYS` and `NEVER` let you choose for yourself.

You can enable `FF`, `QUICK MENU`, `TURBO A`, `TURBO B`, `SAVE STATE` and `LOAD STATE` under `EXTRA BUTTONS`. A short tap on `FF`
toggles it; holding it speeds up the game until you release it. The state buttons use the current slot in `STATES`.
`QUICK MENU` opens the same ring or grid as on Android. `REWIND` and `X AND Y` also use the shared bindings and controls. Skin import and zoom remain unavailable on iPad.

You can also open `TOUCH CONTROLS` from the library's `SETTINGS`, with no game running. The editor uses a stand-in of
the same game and controls panels. It keeps the layout for the orientation you are editing, ready for your next game.

On iPhone and iPad, the same menu offers `PC`, `FLY`, the keep-away spray, `WILD ENCOUNTERS`, the current state's save and load, and `FF` where supported. Held sideways, `COMPANION` opens the companion too. The game's own checks still decide when a field action can run. Other actions are omitted until their features are available on iOS.

On iPhone and iPad, `X AND Y` uses the same buttons, layouts and editor. Each touch goes through your `GAME BUTTONS` and `HOTKEYS` bindings, just like a controller's X or Y. Existing iOS bindings are kept: if these keys are unbound, assign `START 2`, `SELECT 2` or a hotkey before using them. Closing a window, changing bindings or opening the layout editor releases held touch keys.

Android and iPad use the same editor. Editing one orientation keeps the other one's layout. Controls that your
current game cannot show keep their saved positions too.

On iPhone and iPad, both options work in supported GBA games and remember your choices. Auto-run follows the same
rules: it leaves menus, conversations, battles, bikes, surfing and the game's own auto-run alone. On a new game,
walk around for a few seconds so the field can be recognized. Game Boy games show `N/A` for now.

On iPhone and iPad, the toggle works where the GBA game's field and encounter switch are known. It can briefly show
`N/A` while the game is checked. Your choice is remembered, including after resuming a saved state. Turning it back
on restores random encounters when you return to the free field. Game Boy support is still to come on iOS.

## From devices.md

### On iPhone and iPad

On supported GBA games, `NO HMS NEEDED`, `EXP SHARE` and `LEVEL CAP` use the same settings and game-option rules as Android. Unknown ROM layouts show N/A.

The game screen uses the same panel controls as Android. In portrait, drag the companion grip to resize it, or tap it to step through sizes and hide it. The grip stays visible when the companion is hidden, so you can bring it back.

In landscape, the companion starts docked beside the game. Drag the padlock tab to change its width, tap it to lock or unlock the panel, and use the edge tabs to close or reopen it. Your sizes and docking choice are remembered. `COMPANION VIEW`, in the companion's `SETTINGS` while held sideways, switches between the side panel and a window over the game.

The window keeps the game running and takes its buttons while open. Tap outside the window or its X to close it. On a controller, `B` goes back and closes the window when there's nothing left to go back from, `START` closes it, and `L` / `R` change tabs. Directions and `A` move through and use focused controls. A held button needs to be released before it returns to the game after the window closes. Keyboard typing stays with the window's text fields.

The edge button stays available on iPhone and iPad, so you can always reopen a hidden landscape companion. DS games, skins and fold layouts are still Android features.

## From fast-forward.md

### On iPhone and iPad

Use `FF` and `FF SPEED` in the companion's `SETTINGS` to switch fast-forward on and choose its pace. `GAME SPEED`
sets your pace with FF off and remembers it for each game. You can also change `GAME VOLUME` or `MUTE GAME` there.
Muting keeps your volume choice for when you turn the sound back on.

Open `SPEED OPTIONS` for `FF MODE` and `SCENE SPEEDS`. `SMART` is the default: menus, the region map and a battle
waiting for your command run at 1x, then your chosen speed returns. `NORMAL` keeps the chosen speed everywhere.
Shops stay fast, just as on Android.

`BATTLE SPEED` and `MENU SPEED` can have their own pace with FF off. On supported GBA games, `TEXT SPEED`,
`BIKE SPEED` and `SURF SPEED` work too. A row says `N/A` if the game's field or riding state cannot be read;
bike and surf become available once that state has been seen. `SAME` uses `GAME SPEED`.

Sound speeds up with the game; `INFINITE` is silent while running uncapped. Steady music and speed hotkeys
are not available yet.

On iPhone and iPad, `INSTANT TEXT` is available in the same `GAMEPLAY` group. PokeDaisy checks the game's code
before enabling it, so the row can briefly say `N/A` while a game opens. Your choice is remembered. Turning it off
restores the game's normal text routine; the ROM file stays unchanged.

## From library.md

### On iPad

On your Home Screen, look for the PokeDaisy flower icon on its blue patterned background to open the app.

The library, setup and application settings use the same styled screens as Android. The library fills the window
when no game is open. Use the header to add games from Files, search, change the order, switch between a list and a
grid, or refresh after changing files. Hold a game to mark it as a favorite or choose `LOAD SAVE`.

`FAVORITES` and `RECENT` keep the games you want close by. Your choices, display mode and game history are kept when
you reopen the app. The platform views show the consoles your library contains.

Favorites come first in every sort order, and `RECENT` lists the games you played most recently first.
Search ignores case and accents, whether you search by the displayed name or the file name.

Open `SETTINGS` from the library to change appearance, language, playback defaults and touch controls before playing.
`RUN SETUP` reopens the shared setup pages. On iPad, games picked in Files are copied into PokeDaisy's `roms` folder,
and battery saves stay in `saves`. You can reach both folders through Files.
Setup also includes `GAME BUTTONS`: tap a row and press a controller or keyboard button to bind it,
or keep the defaults and choose `DONE`. These are the same bindings used in Settings and while playing.

To return to the library while playing, open the companion's `SETTINGS` and choose `CLOSE GAME`. Confirm to close it.

Only actions supported on iPad are offered. Folder linking, cover downloads and several Android tools still need
platform support.

## From map.md

On iPhone and iPad, supported GBA games can use `FLY` from the map with the game's own cut-in and landing. The same badge, party, destination and field checks apply as on Android. Scenic flight is coming with the expanded-rendering support.

## From saves.md

### On iPad

Open `STATES` from `MORE`, or put it on your tab bar in `SETTINGS` > `TAB BAR`. You have ten slots with screenshots.
Use `SAVE` or `LOAD`, `UNDO SAVE` to restore a slot's previous contents, and `UNDO LOAD` to return to the moment before
your last load. Hold a saved screenshot to delete its slot, then confirm.

Loading a state also restores the battery save it contains. Your current save is copied into `saves/pokedaisy-backups`
before a manual load. The latest ten distinct copies are kept. You can find both saves and `states` in the app's
folder in Files. Keep saving through the game's own menu too.

`RESUME GAMES` is on by default. You can change it in the companion's `SETTINGS`, under `APP`. Closing a game or
putting the app in the background saves a resume snapshot. Opening that game again picks up from the snapshot only
when its battery data still matches your save file. If the save changed, or the snapshot cannot be checked, the game
starts from your save file instead. Returning to a game still open in memory carries on from its paused moment.

`LOAD SAVE` and `RESTART GAME` always start from the battery save, even if an older snapshot has matching save data.
With `RESUME GAMES` off, `GAMES` and `CLOSE GAME` warn before closing, because progress since your last in-game save
will be lost. If resume data cannot be updated, `CLOSE GAME` and `RESTART GAME` leave the game open so you can save
through its own menu before leaving.

Rewind also works on iPhone and iPad for GBA and Game Boy games. Turn on `REWIND`, then hold the touch controls' rewind button, or enable `HOTKEYS` and bind `REWIND HOLD` to your keyboard or controller. You can hide or move the touch button under `TOUCH CONTROLS`. Pausing, loading a state, opening the layout editor or leaving the game releases the hold. Rewind runs at normal speed even with FF on.

## From supported-games.md

#### On iPhone and iPad

When an unfamiliar hack offers `WHICH GAME IS THIS?`, you can choose its base game on iPhone and iPad too.
`NONE OF THESE` leaves the companion at `NOT SUPPORTED`. Your choice is kept for that ROM when you reopen the app,
including a choice to decline every candidate. Renaming the ROM keeps its choice; a different ROM has its own.

Use the match notice's `WRONG GAME?` or the companion's `SETTINGS` > `PLAYS AS` to choose again. After
`NONE OF THESE`, open the picker from the companion's `NOT SUPPORTED` page. A choice does not add support for
features the app could not verify. The iOS library does not offer the `INFO` match controls described above.

## From welcome.md

This works the same on Android and iPad. A failed refresh keeps your last readable copy. Pictures you have opened
stay available offline until a newer manual replaces them.
