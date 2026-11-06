# PokéDaisy collection for NXRedux

The TrimUI Brick package puts two native libretro cores behind one collection.
NXRedux chooses mGBA for `.gb`, `.gbc` and `.gba`, and DSperate for `.nds`, using
the ROM's original path for its save and resume keys. Extract archives before
adding games.

Extract the package at the SD root and put ROMs directly in
`Roms/PokeDaisy (PDAI)/`. System subfolders are optional and existing nested
ROMs still launch. The launcher lives in `Emus/tg5040/PDAI.pak`.
Use Aspect scaling on the Brick. The package sets the GB/GBA core's panel
size to 1024×768.

NXRedux manages saves, save states, fast-forward, shaders and quick resume.
Use distinct ROM basenames across systems because NXRedux uses the collection
tag and basename for SRAM filenames. To migrate from a different emulator
tag, copy a raw battery save to the filename NXRedux creates under PDAI.
Retain the original. Native states depend on the emulator and its version.
The two cores store companion preferences separately under the frontend save
directory in `pokedaisy/settings.ini` and `pokedaisy-ds/settings.ini`.

## Nintendo DS

| Control | Action |
| --- | --- |
| Tap L2 | Open or close the companion |
| Hold L2 or press Select + L2 | Open the quick menu |
| R2 | Cycle selected layout, bottom only, top only |
| Select + R2 | Toggle the floating touch panel in One Screen or Picture in Picture |
| Select + D-pad / A | Move the stylus / press the stylus |
| L1 / R1 in companion | Change tabs |
| A / B in companion | Select / back |

In Settings, choose **One Screen** for full-screen DS gameplay on the supported ROMs below and
automatic touch or menu windows. The other layouts are Top, Bottom, Side by
Side, Stacked, Large + Small, and Picture in Picture. Older AUTO preferences
become One Screen. Unrecognized ROMs use Picture in Picture when One Screen
is selected. Platinum uses the USA revision 1 ROM.

The native battle panel remains accessible with the stylus. The DS party HUD
appears once the field is settled. The quick menu opens companion tabs and
toggles the HUD. The DS core has native viewport layouts and read-only companion
views. EXPAND, ZOOM OUT and field actions are features of the supported GB/GBA
games.

In One Screen, use Platinum's own X menu to open Bag or Pokémon in the
overworld. Those native menus open over the field. The separate PokéDaisy
companion opens with L2 and uses L1/R1 to switch tabs. Select + R2 opens the
manual Pokétch panel. These are the default bindings.

This package defaults to DSperate Auto frameskip and 512×384 battle output.
Auto only skips rendering when the frontend reports that its audio buffer
is running low. Reload the ROM after changing frameskip.

### Platinum One Screen battle strip

On Platinum USA revision 1, **Battle Strip** defaults on with **One Screen**
selected. An existing Off preference is kept and can be changed in the DS
companion Settings. A new battle returns a temporary screen peek to One
Screen. Use the D-pad and A to choose Fight,
Bag, Pokémon or Run. Fight opens the move grid with move types and current PP.
B returns from the move grid. Native Bag and party windows float over the
strip. Doubles and native prompts use the game's own controls.

Platinum's compact battle buttons use the native lettering and tiles without
the Fight sprite. The move cards retain the original type badges and
current/max PP.

### Experimental Johto and Unova battle strips

The same One Screen controls are enabled for exact English retail revision 0
ROMs of HeartGold, SoulSilver, Black, White, Black 2 and White 2. HeartGold
and SoulSilver have split-color buttons and gold or silver message rims.
Black and White use charcoal plates and colored command labels. Black 2
and White 2 use striped plates with colored rims.

These themes use PokéDaisy's bitmap font and app-owned move data. The
native move-card artwork remains specific to Platinum. The new battle paths
need live retail ROM testing.

### Johto and Unova companion

| Games | Exact revisions | Companion and Quick Menu |
| --- | --- | --- |
| HeartGold, SoulSilver | USA rev 0 | Party, read-only Bag, Map, Dex, Settings, HUD toggle |
| Black, White, Black 2, White 2 | USA/Europe rev 0 | Party, read-only Bag, Map, Dex, Settings, HUD toggle |

Party shows HP, status, moves, PP and held items. A opens the selected party
member's summary or Bag item's details. Left and right in Bag change pockets.
To turn on the HUD, hold L2, select HUD and press A. It appears after the
overworld settles in a layout with only the top screen visible. The companion's
Settings control Minimal HUD, Poké Balls, Repel Steps and the Minimap. Minimal hides the
party cards. The ball counter shows up to three ball types by quantity and
Repel Steps appears only while a Repel is active. The Dex lists seen and
caught species. The Map and Minimap share the cartridge's art. Use the D-pad
to move the cursor in Map and A to recenter on the player. The Minimap is
hidden in Minimal HUD mode.

On an open overworld, the Quick Menu has a Repel action. Select a Repel
from the list and press A to use one. The game counts steps down and handles
expiry. Repel cannot stack with one already active.

The Party and Bag readers and companion navigation were tested against
upstream RAM captures for all six titles. ROM icon extraction, live HUD
updates, and native battle/menu transitions still need retail-ROM testing
on the Brick.

In One Screen, title or setup can show both DS screens. The overworld
uses the top screen, with the native X menu and touch-only apps floating
over it. Apps that need both screens use a side-by-side panel. Use
**Select + R2** to open the touch screen manually and **Select + D-pad / A**
to move or tap the stylus.

## Build the Brick package

Run from the repository root with CMake, Ninja and Python 3.12 or later.
`linux/setup_trimui_sdk.py` prints the absolute SDK path to export as
`NEXTUI_TOOLCHAIN`.

```sh
git submodule update --init third_party/mgba
git submodule update --init --recursive third_party/dsperate-lr
python3 linux/setup_trimui_sdk.py /path/to/trimui-sdk
export NEXTUI_TOOLCHAIN=/path/to/trimui-sdk/cross/x86_64-aarch64-nextui-linux-gnu
cmake -S libretro -B build/gb -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$PWD/linux/trimui-toolchain.cmake"
cmake -S linux/libretro-ds -B build/ds -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$PWD/linux/trimui-toolchain.cmake" -DBUILD_TESTING=OFF
cmake --build build/gb --target pokedaisy_libretro -j4
cmake --build build/ds --target pokedaisy_ds_libretro -j4
python3 linux/libretro-unified/package.py build/gb/pokedaisy_libretro.so \
  build/ds/pokedaisy_ds_libretro.so PokeDaisy-NXRedux.zip \
  --strip "$NEXTUI_TOOLCHAIN/bin/aarch64-nextui-linux-gnu-strip" --source-ref "$(git rev-parse HEAD)"
```
