# PokéDaisy collection for NXRedux

The TrimUI Brick package puts two native libretro cores behind one collection.
NXRedux chooses mGBA for `.gb`, `.gbc` and `.gba`, and DSperate for `.nds`, using
the ROM's original path for its save and resume keys. Extract archives before
adding games.

Extract the package at the SD root and place ROMs under
`Roms/PokeDaisy (PDAI)/`. The launcher lives in `Emus/tg5040/PDAI.pak`.
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
| L2 | Open or close the companion |
| Select + L2 | Open the quick menu |
| R2 | Cycle selected layout, bottom only, top only |
| Select + R2 | Toggle the floating touch panel in One Screen or Picture in Picture |
| Select + D-pad / A | Move the stylus / press the stylus |
| L1 / R1 in companion | Change tabs |
| A / B in companion | Select / back |

In Settings, choose **One Screen** for full-screen Platinum gameplay and
automatic touch or menu windows. The other layouts are Top, Bottom, Side by
Side, Stacked, Large + Small, and Picture in Picture. Older AUTO preferences
become One Screen. Unrecognized ROMs use Picture in Picture when One Screen
is selected. The Platinum companion supports the USA revision 1 ROM.

The native battle panel remains accessible with the stylus. The DS party HUD
appears once the field is settled. The quick menu opens companion tabs and
toggles the HUD. The DS core has native viewport layouts and read-only companion
views. EXPAND, ZOOM OUT and field actions are features of the supported GB/GBA
games.

### Platinum One Screen battle strip

On Platinum USA revision 1, enable **Battle Strip** in the DS companion
Settings with **One Screen** selected. Use the D-pad and A to choose Fight,
Bag, Pokémon or Run. Fight opens the move grid with move types and current PP.
B returns from the move grid. Native Bag and party windows float over the
strip. Doubles and native prompts use the game's own controls.

Platinum's compact battle buttons use the native lettering and tiles without
the Fight sprite. The move cards retain the original type badges and
current/max PP. HGSS, BW and B2W2
need their own battle readers and visual profiles before enabling the strip.

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
