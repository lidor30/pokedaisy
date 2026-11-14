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
