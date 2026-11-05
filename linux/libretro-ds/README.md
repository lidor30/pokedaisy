# PokéDaisy DS libretro core

The DS core uses DSperate v3.3.0 through the pinned dsperate-lr wrapper. The
Platinum adapter reads Pokémon Platinum USA revision 1 and supplies party,
bag, Pokédex and map data to the shared companion renderers. The app's existing
Kotlin tables generate the DS data header during the build. The ROM supplies
party icons and map art.

See [the combined NXRedux package](../libretro-unified/README.md) for builds,
controls and save paths.

## Desktop checks

```sh
git submodule update --init --recursive third_party/dsperate-lr
cmake -S linux/libretro-ds -B build/ds -DCMAKE_BUILD_TYPE=Release
cmake --build build/ds -j4
ctest --test-dir build/ds --output-on-failure
```

These tests use a diagnostic cartridge and the existing Platinum RAM fixture.
They check layout timing and touch bounds, the libretro state contract, the
shared companion, and persistent settings. The DS core uses the wrapper's
native SRAM and state API. Linux builds use the regular DSperate core without
release PGO.
