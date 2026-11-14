# 3DS games in the app: plan (Pokemon X first)

Working notes for the branch. Not the homebrew port in `3ds/`: this is 3DS carts running inside the Android app,
on two screens and on one. Moves into docs/DEVELOPMENT.md (and the manual) once it ships.

## Decisions so far (2026-11-14)

- **Core: Azahar** (`third_party/azahar`, tag 2126.2, GPLv3+ like us). Linked into the APK with our own JNI front end,
  the way melonDS is (`app/src/main/cpp/n3ds/`), not its Android app and not its libretro core: the companion needs the
  emulated process's memory directly. Its `citra_libretro` stays the option for the Linux port later.
- **No keys, ever.** Azahar's CMake has `ENABLE_BUILTIN_KEYBLOB` ON by default (an obfuscated key blob in
  `src/core/hw/default_keys.h`). Our build forces it OFF, and a check (like `check_melonds_patch.py`) fails the build if
  it's on. Only decrypted dumps play (NCCH NoCrypto, which Pokemon X's is); an encrypted one gets a plain message.
- **Behind a feature flag** (`FeatureFlags.n3ds`, off; `-Ppokedaisy.n3ds=true` / local.properties / marker
  `files/features/n3ds`), like co-op, until it's ready for players. No NEW tags / manual / website until then.
- **Screens**: two screens = 3DS top on the top display, the touch screen on the bottom one, the companion swapping in
  over it (DS games' way). One screen = DS LAYOUT, default ONE SCREEN: the top screen fills the display and the touch
  screen floats in when the game wants it (rules per game, later) or by hand (HUD TOUCH, L2).
- **Files**: `.3ds` / `.cci` / `.cxi` played in place from linked folders, never copied (`LaunchActivity`'s copy limit
  stays). A zipped 3DS game: the library asks once, unpacks it into its own folder beside the zip and plays that file
  from then on.
- **Save**: a Pokemon X / Y `main` (0x65600 bytes, `FEEB` footer block, six in the party) at `~/Downloads/main`.
  Never committed; a fixture README names it by `~` path only.

## Milestone 1

1. Azahar's core libraries build for arm64-v8a inside our CMake (Qt, SDL2, web service, room, scripting, GDB stub,
   libusb, discord, tests, keyblob off; Vulkan + OpenGL ES on; dynarmic's arm64 back end). Watch the APK size.
2. A headless dump tool (`native-capture/n3ds_dump`, nds_dump's commands: boot, press, frames, shot, ram dumps,
   states) built from the same sources on the Mac, for mapping Pokemon X without a device.
3. `pk_n3ds` behind the core interface: load, run a frame, both screens' pictures, input + touch, audio, battery save
   kept where the game's save lives (one file per cart, `SavesLocation`), backups before start, states (never carrying
   the save over the file, the GBA / DS rule), FF.
4. Library: `.3ds` recognised by its NCSD header (`RomIdentity.looksLikeRom`), title / cover by title id, the zip offer.
5. Screen layouts: two screens, ONE SCREEN with the floating touch screen, the other DS LAYOUT choices.
6. Companion: PARTY / BAG / map name from RAM (Gen 6 structures, addresses from headless captures of the user's save),
   built engine-shared so Y and ORAS follow.

## How it's built (2026-11-14)

- `n3ds/`: an Android library module holding only CMake (`n3ds/src/main/cpp/CMakeLists.txt`: Azahar as a subdirectory +
  `pk_n3ds_jni.cpp`), CMake 3.31.6 from the SDK (Azahar wants 3.25+; the app's own native build stays on 3.22.1), minSdk
  29 (the app overrides it in its manifest and only loads the library on 10+). `settings.gradle.kts` includes it and the
  app depends on it only with `pokedaisy.n3ds=true`, so nobody else's build compiles Azahar. The JNI functions belong to
  the app's `com.pokedaisy.app.n3ds.N3dsNative`.
- `third_party/patches/azahar-pokedaisy.patch` (applied at configure time, `apply_azahar_patch.cmake`): Azahar's CMake
  read `CMAKE_SOURCE_DIR` as its own root and copied a git hook into `.git/hooks` (a file in a submodule).
  `scripts/check_azahar_patch.py` (in `make check`) passes only a submodule holding exactly the patch, and fails if the
  key blob lines leave the module's CMake.
- `native-capture/n3ds_dump`: the same libraries on the Mac with the software renderer, for headless captures.

## The first playable version (2026-11-14)

`N3dsActivity` (only with the flag): the library starts it for a supported cart (`N3dsCart`: NCSD header, title id,
NoCrypto; `CompanionSupport.isSupported`, `RomFolder` lists `.3ds` / `.cci` up to 4 GB, never hashed or copied).
`N3dsSaves.prepare` writes the save's format file (or refuses a game whose format isn't known) and copies the save to
`n3ds/backups/<title id>/<time>/` (newest 10 that differ) before every boot. Native (`pk_n3ds_jni.cpp`): Azahar's
Vulkan renderer on `PkWindow`s over Android surfaces (two at boot = top screen alone + touch screen alone, one = both,
LargeScreen), our own button / circle pad devices, touches on either surface, pause, states between two RunLoop calls,
speed, sound through cubeb. Test keys: L3 saves a state, R3 loads it, R2 held = 300%. Azahar's user folder is
`Android/data/com.pokedaisy.app/files/n3ds/` (the save: `sdmc/Nintendo 3DS/<0 x32>/<0 x32>/title/00040000/00055d00/
data/00000001/main`).

On the RP6 (2026-11-14): boots the user's save to the field at 100% speed (60 fps system, the field at its own 30 fps,
~5 ms of a 16.7 ms frame used at 1x), states save (13 MB) and load, buttons, D-pad and touch work, the format guard wrote
the save's format file and the backup was taken. Gotchas met on the way: Azahar's Android file code calls back into a Java
class (`N3dsAndroidUtils`, every method with its exact signature, or CheckJNI aborts) and reads every path relative to
the user folder it returns, unless the path starts with "!" (the cart's); `LaunchActivity` hands a `.3ds` to the 3DS
screen too (in place, never copied), which is how to start it from adb: `am start -n com.pokedaisy.app/.LaunchActivity
--es rom '<path>'`. On the Thor (same day): the top screen alone fills the top display (5:3, 1800 x 1080), the touch
screen alone the bottom one (black only during the title's intro), a touch there works, 100% speed (gpu 1.3 ms a frame at
3x). Screenshots of one display: `screencap -p -d <physical id>` (`dumpsys display`'s uniqueId numbers), not the
display id. Sound (cubeb) is right on the Thor, by ear.

ONE SCREEN (2026-11-14, checked on the RP6): with one display and DS LAYOUT's ONE SCREEN (or PICTURE IN PICTURE, for now),
the top screen alone fills the display and the touch screen is a second surface in a 4:3 window over a dim, brought in
and sent away by L2 or a TOUCH button (a tap on the dim closes it). Hidden, the window is moved off the screen, not
removed: Azahar draws to its surface every frame. SIDE BY SIDE / LARGE + SMALL / STACKED are Azahar's SideScreen /
LargeScreen / Default on the one surface. Not yet: the window coming in by itself when the game wants touch.

PARTY / BAG (2026-11-14, checked on the RP6 with the player's save): BACK swaps the companion in and out (over the touch
screen on a second display, else a window over the game; a held BACK leaves). `N3dsNative.readMemory` copies ranges of
the game's memory between two run loops; `Gen6Reader` decodes the live party (PK6 + the party stats at +0x158, whose
cipher starts again from the encryption constant) and the bag's five pockets; `Gen6Text` reads the names from the cart's
RomFS (GARC `a/0/7/<n>` by the save's language, MyStatus +0x2D: 2..8 English, French, Italian, German, Spanish, Korean;
Japanese 3). They fed the first screen's own PartyScreen / ItemsScreen (`N3dsCompanion`, `LocalItemNames`); in the game screen
the regular companion shows them (below). Not yet: icons, summaries, types, the map,
medicine as its own tab.

## In the game screen (2026-11-15)

A 3DS game now plays in the regular game screen (`PokeDaisyActivity`), so it gets the app's own controls, companion,
HUD messages, hotkeys and DS LAYOUT instead of a screen of its own. `N3dsActivity` stays until the Thor's two screens
are checked through this path, then goes.

- `load3ds` / `endN3ds` (PokeDaisyActivity): `N3dsSession` (`n3ds/N3dsSession.kt`) owns Azahar's thread, its two
  SurfaceViews, input, states and the companion's samples. The GL game view stays laid out but invisible and an idle
  `EmulatorEngine` stands in, so the many `engine` paths keep working; `GameStageLayout.gameCover` puts the top screen's
  surface exactly over the game's box (5:3, `DsLayouts.N3DS_TOP_ASPECT`).
- Input: the pump reads `GbaInput` every 4 ms (the player's GAME BUTTONS bindings, the touch controls, the hat) and
  ticks the held-back START / SELECT taps once a 60 Hz frame (`GbaInput.tapMask`); the left stick is the circle pad.
- DS LAYOUT: `n3dsArrange` / `syncN3dsStage`. ONE SCREEN keeps the touch surface laid out over the middle at 82% of
  the height and moves it off the screen (translationX) while hidden: laid out off the screen from the start, its
  surface showed at the stage's top left. L2 / TOUCH bring it in, a tap on the dim sends it away.
- The touch screen by itself (ONE SCREEN, 2026-11-15): `N3dsSession`'s scene thread reads, ten times a second, the
  modules the game has loaded (`N3dsNative.modules`: the CRO chain from the static module's header at 0x012B6000, each
  header's name at +0x84 and next at +0x88) and the field's lower-screen object (0x08D033F0, the same heap address on
  every boot seen, headless and on the RP6). `Gen6Reader.sceneOf`: `DllField` with the object's flags 0x3D3 = the X menu
  (the touch screen floats in; 0x3D1 the field, 0x153 / 0xD3 while it loads); no `DllField` = a screen of the game's own
  (`DllPokeList` for the party and the bag, a battle...): both screens side by side, framed over a picture of the field
  (Azahar's screenshot once a second while on the field); the title's modules and the empty moments between screens keep
  the last scene. A change counts on two reads in a row. A window the player opened (L2) stays until they close it.
  Checked on the RP6: menu, party, bag, back. Not seen yet: a battle, a dialogue, the PSS's own screens.
- Two displays: the touch surface moves into the bottom display's `NdsBottomHost` (`screen`), the companion behind its
  COMPANION switch, as for a DS game; hidden by translationX there too. Not checked on the Thor yet.
- Companion: `GameKind.POKEMON_X`, the snapshot published through `TelemetryStore.publish`, the bar limited to PARTY,
  BAG and STATES (`N3DS_TABS`); the bag's names go through `OwnItemNames` (the cart's, not the GBA tables by id).
- States: the save / load hotkeys and STATES use `SaveStates`' files under the title id; a save writes a 160 px
  thumbnail from Azahar's own screenshot of the next frame (`N3dsNative.screenshot`: a PixelCopy of the surface came
  out see-through, Azahar's frames carry alpha 0). No undo copies yet.
- Checked on the RP6 (2026-11-15): START / A / B (the device's GAME BUTTONS swap them), L2's window, PARTY, BAG, STATES
  save / load with thumbnails. Not checked: upright (the RP6 won't rotate from adb), the side panel, the touch controls.

## Icons: what's known (2026-11-14, not in the app yet)

- Party icons: RomFS `a/0/9/3`, a GARC of 945 files, each LZ11-compressed (0x11 header) into a CLIM-footed file. The
  footer says 40 x 30 RGBA5551 but the data isn't: it's `u16 ?` (2), `u16 n` (colours), n RGBA5551 colours (alpha = bit
  0: a colour with it clear is transparent, whatever its index), then 4-bit pixels, high nibble first, in 8 x 8 tiles of
  Morton (Z) order over a 64 x 32 texture (the icon in its top-left 40 x 30). Decoded this way they're right (checked by
  eye on dozens).
- The order isn't species order: each species' extra forms come before its plain icon (Mega Venusaur 3, Venusaur 4;
  Mega X 7, Mega Y 8, Charizard 9), and some species have two identical icons (Clefairy, Clefable, Jigglypuff,
  Wigglytuff), so 945 files against the 885 that species + forms (personal `a/2/1/8`, 0x40 bytes, form count at +0x20)
  give. No species-to-icon table was found as u8 / u16 / u32 runs in `.code` (ExeFS, BLZ-compressed) or any `.cro`: the
  game computes it, or keeps it in another shape. Next: find the rule (the pairs may be gender; the personal data's
  gender / flags bytes), or read which icon the game loads (its party menu's texture).
- Item icons are probably `a/0/9/4` (579 files, 30 x 30, same container).
- The map: the save's Situation block isn't at 0x1E00 in XY (zeros there); the zone data with place-name indexes is one
  of the GARCs whose entries name places (text file 72), not pinned down yet.

## Pokemon X, first findings (n3ds_dump, 2026-11-14)

- Boots with the user's save and plays to the field (Lumiose, the save is Italian: the game follows the save's language).
  The software renderer runs the 2D screens at ~5 ms a frame and the 3D field at ~100 ms on the Mac.
- **A save needs its format file.** Azahar keeps a save's format beside its folder (`data/00000001.metadata`); without it
  the game's FS:GetFormatInfo fails and the game *formats the save again at boot*, deleting `main` (seen: the first run
  ended on the language screen of a new game). Pokemon X's own format, from a new save: 0x40000 bytes, 1 directory,
  1 file, duplicated. The app must write it before a save goes in (and never let a game start on a save without it).
- **States carry Azahar's revision** and are refused by another one: ours is the submodule's commit (`PK_AZAHAR_REV`).
  Moving the submodule invalidates every state, as a new Azahar does for its own users.
- Memory (the same on two boots that started the game at different frames):
  - live party: 0x08CE1CE8, 6 x 0x1E4 bytes: the first 0xE8 an encrypted PK6 (the Gen 4-6 scheme: LCRNG from the
    encryption constant, blocks shuffled by it), checked by its checksum on all six; what follows (level, HP, stats)
    not mapped yet;
  - the save's blocks are kept as the game's own struct, packed (not the file's 0x200 alignment): the party block at
    0x08C79DA8 (6 x 0x104), MyStatus (trainer, name at +0x48) at 0x08C79C2C;
  - bag pockets: items 0x08C67554, key items 0x08C67B94, TMs 0x08C67D14, medicine 0x08C67EBC, berries 0x08C67FBC
    (u16 item, u16 count each).

## Open

- Shader cache and pipeline compiles on first play (stutter): Azahar's disk cache under our files dir.
- Which devices: Azahar wants 64-bit Android 10+; the Thor / RP6 (Snapdragon 8 Gen 2) are the targets.
