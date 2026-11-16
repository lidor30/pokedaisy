# PokeDaisy for iPad

The Home Screen icon uses the existing PokeDaisy flower and blue lattice. Its opaque 1024px asset is copied from
`docs/brand/avatar.png` by `node scripts/gen_brand_images.mjs`; Xcode supplies the icon sizes and corner mask.

The iPad app hosts the native Metal game surface, audio, controllers and keyboard with the same Compose library,
setup, settings and companion screens Android uses. The library fills the window when no game is running. Games
picked from Files are copied into Documents/roms, with battery saves in Documents/saves; both are visible in Files.
LOAD SAVE uses a shared confirmation and keeps the previous save as a dated backup. The on-screen pad and its editor
are shared too; there is no separate UIKit pad or Swift game list.

## Battles

The companion's buttons drive the game's own menus in single battles (actions, moves and BACK), including a party
switch where its menu is mapped. SETTINGS has ONE-TAP MOVES, BATTLE VIEW, BATTLE STYLE, BATTLE INFO and BATTLE FOCUS.
The controller is shared with Android; the iPad runs it once per game frame and navigates by key presses.

## ROM-detection recovery

On iPhone and iPad, the shared WHICH GAME IS THIS? picker, match notices and SETTINGS > PLAYS AS connect to
TelemetryStore.gamePick. BestEffortStore uses the app's files directory, so automatic matches, manual picks and
NONE OF THESE survive reopening, keyed by ROM-content SHA-1. The existing sampler still verifies choices and omits
features it could not verify; blocked games retain their notice without a picker. Library INFO actions and report
sharing are not connected on iOS.

## Speed and sound

SETTINGS has FF (a persistent toggle), FF SPEED (including INFINITE), GAME SPEED (kept per game), GAME VOLUME and MUTE GAME.
SMART mode and per-scene speeds use Android's shared playback policy and existing memory watchers. Sound is pitched
and sped up; INFINITE is silent and yields between bounded batches so the main thread handles touch and the companion.
Steady music remains later parity work.

REWIND is off by default. The existing native diff ring keeps the same 600 snapshots as Android, sampled every two
frames. REWIND HOLD and the shared touch extra have independent hold owners. Rewind ignores FF pacing, flushes/mutes
sound and bypasses queued battle keys; state loads start a new history. Its native diagnostics verify GB/GBA rewind
and that newer battery-save data survives it.

## Game options and bindings

Supported GBA games offer INSTANT TEXT, REUSABLE TMS, NO HMS NEEDED, EXP SHARE, LEVEL CAP, AUTO-RUN, RUN INDOORS and WILD ENCOUNTERS through the existing
shared finders and portable core policies. Unsupported games show N/A; Game Boy field options remain future work.

GAME BUTTONS uses the same binding rows, capture model and input mapper as Android, including TURBO A/B and optional
X/Y bindings. The shared touch pad also exposes X AND Y: these emit the controller's canonical keys through those
same bindings and hotkeys. Existing unbound X/Y mappings stay unbound; configure START 2 / SELECT 2 or a hotkey first.
Files live in Documents/bindings, in the shared properties format. Existing iOS keyboard/trigger defaults
are preserved. The native adapter maps GameController codes and axes; shared models own binding and capture behavior.
Held buttons are quarantined across capture, modal and background transitions. Library capture polls without a core.

HOTKEYS uses the shared chord engine and capture UI, with the speed, rewind, state, slot, exit/restart, companion and mute
actions iOS currently supports. It starts off, preserving the old trigger mappings. Hardware and touch FF holds
have independent ownership; shared playback policy prioritizes FF, then half speed, then scene/base speed. Chord
and hold state resets at capture, modal and background boundaries. Other actions are omitted until their feature
is ported. Bindings use Documents/bindings/hotkeys.properties, the same format as Android.

## Save states

MORE > STATES has ten slots, screenshots, delete and both undo actions, through the shared screen and slot controller.
Files are in Documents/states/<ROM SHA-256>. A manual load restores battery data too, so the current save is backed
up in Documents/saves/pokedaisy-backups first. State operations run between game frames and reset queued audio/input.

RESUME GAMES (companion SETTINGS > APP, on by default) captures a resume state on background/close. An initial open
loads the newest resume/manual snapshot only after the core verifies its embedded battery data matches the save.
Changed/unverifiable automatic states are parked; normal foreground returns retain the paused core. Import and
RESTART GAME commit a persistent fresh-boot marker before changing saves/opening the core, cleared only by a new
successful resume capture. Turning resume off keeps this marker and removes its automatic snapshot.

## Build and run

On a Mac with Xcode 16, a JDK 17 and the Android SDK (Gradle configures the shared modules' Android side too; `sdk.dir` in `local.properties`):

```sh
brew install xcodegen
cd iosApp
xcodegen
open PokeDaisy.xcodeproj
```

Pick an iPad (or the simulator) and run. The first build runs Gradle (`:shared-ui:embedAndSignAppleFrameworkForXcode`),
which takes a few minutes; later builds only rebuild what changed. For a device, set your own team under Signing &
Capabilities (a free Apple ID works for seven days).

From the command line (no signing, the simulator):

```sh
xcodebuild -project iosApp/PokeDaisy.xcodeproj -scheme PokeDaisy -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

## Cheats

A running GBA game offers CHEATS with a master switch, per-code toggles, ADD CODE, IMPORT FILE and confirmed removal.
The entry form is the same Compose form Android Settings uses. The shared models normalize and validate codes,
parse RetroArch/mGBA files and store by original ROM CRC; the existing native engine applies only enabled codes.
Files imports are bounded to 1 MiB, start disabled and pause the game/pad while the native picker is open. Code
entry uses shared keyboard-focus ownership, so typing does not trigger gameplay or hotkeys. GB cheats stay hidden.

## Shared touch customization

The companion's TOUCH CONTROLS page, preview and EDIT LAYOUT are the existing shared Compose screens. Preferences use
TouchStyleCodec, also used by Android, backed by NSUserDefaults. The game, controls and companion share one Compose host;
Swift only switches between the library and game controllers. An editor uses the same safe-area layout bounds as the
game screen, pauses the core and keeps separate orientation layouts. Controller connection changes update AUTO visibility. FF (tap/hold),
turbo and state buttons use the existing playback/slot bridges; unsupported actions and skins are omitted.

## Shared application screens

The library, setup and app-settings presenters are the same Compose components Android uses. Swift switches between
controllers; Compose embeds the native Metal game surface and owns the panel layout. There is no separate Swift list,
menus, settings UI or touch pad. The library is full-window when no game is open, with grid/list, search, sorting, favorites/recent views and save import. Settings
and touch layout editing work before a game starts. Setup uses Files to copy games and keeps saves in Documents/saves;
Android folder linking and platform-specific media/tool services remain outside shared UI.

## Game screen layout

Android and iOS now run the same `GameScreen(GameScreenState, ...)` composition. Swift only switches library/game
controllers; UIKit embeds the existing Metal controller in the common `GamePicture` slot. There is no separate Swift
panel layout or legacy three-slot game-screen assembly.

Portrait reuses `PortraitLayout`, including the resize grip, size cycling/hiding, UNDER GAME / AT BOTTOM and the controls'
own strip. Landscape reuses window, unlocked/docked panel, open/close tabs, resizing and snapping. iOS preserves its
existing docked default and stores choices in NSUserDefaults under Android's preference keys. The edge open button
cannot be disabled on iOS because it is the touch recovery path when the companion is hidden.

`IosGameScreen` supplies preferences, native controller input and focus ownership; it does not reimplement panel
placement or gestures. Modal windows block manual controller/keyboard/touch keys while the core and scripted battle
input keep running. Held keys remain suppressed until released after closing. The editor is a separate input owner,
so closing a window cannot release its capture; touch ownership changes cancel old pointer gestures. Controllers use
the shared companion keys and focus actions. Keyboard letters remain text input, rather than controller shortcuts.

The live screen and in-game editor/rotated preview use measured common frames, including saved sizes and hidden state.
Game Boy games keep 10:9; GBA games keep 3:2. DS, shaders, skins and fold/device-window features remain Android-only.

For XCUITest, launch with `POKEDAISY_UI_TEST=1` in the app's environment to synchronize Compose accessibility
semantics without enabling VoiceOver. Normal launches keep Compose's service-driven accessibility synchronization.

## Scene speeds and SMART fast-forward

SPEED OPTIONS uses the shared settings page and `PlaybackPolicy` from Android. `IosGamePlayback` drives the same
menu, shop and map watchers per emulated frame. The portable C API observes existing GBA field tables without
enabling patches, for text, bike and surf speeds. Unknown field layouts remain N/A; Game Boy still supports battle
speed through the shared battle controller. A scene change stops a fast frame batch immediately. Menu callback
learning and scene-speed choices persist in NSUserDefaults. Steady music and speed hotkeys remain later work.

## Game-option patches

INSTANT TEXT, REUSABLE TMS, NO HMS NEEDED, FULL EXP SHARE and LEVEL CAP use the existing shared code finders, followed by `GameOptionPatches`, which Android
also runs. File scanning happens off the main thread; results from a closed game are discarded. Applying or
removing edits happens between frames through the same locked, expected-byte-checked native patch API as Android.
All option groups share rollback rules, so a failed patch preserves other complete features and partial groups are removed.
Party EXP updates and the next-boss level-cap worker use the same shared owner on both platforms; retired workers cannot publish into a replacement game. ROM files are never rewritten. Unsupported and blocked games remain unavailable; games with their own
reusable TMs show the existing built-in state. Choices persist in NSUserDefaults.

AUTO-RUN, RUN INDOORS and WILD ENCOUNTERS use the existing GBA field tables and encounter-switch finder.
`AutoRunPolicy` is shared with Android, before battle automation, so modal input ownership and scripts keep their
keys. Only the game's free field can invert B; menus, dialogue, battles, bikes, surfing, sneaking and built-in
auto-run are preserved. Ruby/Sapphire indoor rules remain unavailable. The native field step owns the write lock;
field observation does not change encounter ownership or its RNG. Unknown tables and Game Boy stay N/A.
