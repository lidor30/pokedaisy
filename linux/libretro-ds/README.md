# PokéDaisy DS libretro core

The DS core uses DSperate v3.3.0 through the pinned dsperate-lr wrapper. The
Platinum adapter reads Pokémon Platinum USA revision 1 and supplies party,
bag, Pokédex and map data to the shared companion renderers. The app's existing
Kotlin tables generate the DS data header during the build. The ROM supplies
party icons and map art.

See [the combined NXRedux package](../libretro-unified/README.md) for builds,
controls and save paths.

Platinum's One Screen move cards draw the game's native lettering, type badge,
and PP at an integer scale in the 512×384 frame.

The One Screen battle strip follows each game's own touch screen: Platinum's
plates and cards are the game's pixels, HeartGold / SoulSilver's and the Unova
games' are drawn to match (the framed two-tone card; the hexagon plates, blunt
in Black / White and sharper in the sequels). Every card's lower row is the
type badge, "PP" and the count, at 2x; a move out of PP stays readable with a
red count. The cursor is Platinum's red corner brackets: it pulses, and a press
dips the plate under it. The party's ball row, and a trainer's, sit under the
prompt. A quick-menu action's answer ("No one in your party knows Fly.") is
drawn by the core for two seconds, since not every frontend shows a core's
messages.

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

## Timing

Set `PD_DS_PERF=1` in the frontend's environment and the core logs, every 300 frames, where a frame's time went:
DSperate's emulation, and PokéDaisy's own drawing split into layout, composing the screens, the RGB565 copy and the
HUD, then how many of those frames DSperate skipped.

The frame goes to the frontend as XRGB8888, DSperate's own format, and as RGB565 only to a frontend that takes nothing
else (the RGB565 figure is then 0). The field HUD's picture is kept and drawn again only when the save's snapshot, the
settings or the cursor change. A popup over the held field (the Bag, the Pokédex, the X menu) keeps its dimmed
backdrop in the frame, and only the live screens are drawn each frame.

## Frame skip

`dsperate_frameskip` leaves out a late frame's 3D raster and line output; the game, both CPUs and the sound run as
ever. The wrapper takes "late" from the frontend's audio buffer status callback. A frontend without one (NXRedux,
PokéDaisy's own Linux host) never said so, and nothing was ever skipped there. For those the core times itself: a
frame's work is the emulation and this core's drawing, not the time inside the frontend's video callback. Work past
the budget (15.2 ms of the DS's 16.7) adds up; past 4 ms owed, the next frame is skipped, at most two in a row, and a
frame inside the budget pays it back, so a game that keeps up never skips. `PD_DS_FRAME_BUDGET=<ms>` changes the
budget (less for a frontend that needs more of the frame for itself). `dsperate_frameskip = disabled` turns it off.
A frame DSperate skipped shows the last one again (no composing or HUD) when the layout hasn't changed, at most every
other frame.
