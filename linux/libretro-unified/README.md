# PokéDaisy collection for NXRedux

Based on PokeDaisy by Lidor Itzhari - https://github.com/lidor30/pokedaisy

The TrimUI Brick package puts three native libretro cores behind one collection.
NXRedux chooses armGBA (`pokedaisy_gba`) for `.gba`, mGBA (`pokedaisy_gbc`) for `.gb` and `.gbc`, and DSperate
(`pokedaisy_ds`) for `.nds`, using
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
appears once the field is settled. The Quick Menu overlays the paused gameplay
view and opens the Companion from a single tile. Dimmed tiles explain why an
action cannot run. The DS core has native viewport layouts and read-only
companion inventory views. EXPAND and ZOOM OUT are features of supported GB/GBA games.

In One Screen, use Platinum's own X menu to open Bag or Pokémon in the
overworld. Those native menus open over the field. The separate PokéDaisy
companion opens with L2 and uses L1/R1 to switch tabs. Select + R2 opens the
manual Pokétch panel. These are the default bindings.

This package defaults to DSperate Auto frameskip and 512×384 battle output.
Auto only skips rendering when the frontend reports that its audio buffer
is running low. Reload the ROM after changing frameskip.

### Companion navigation on both cores

Use L1/R1 to cycle the tab bar, including **More** when a card is hidden.
The D-pad moves within the current tab. In Party, A opens the highlighted
Pokémon and switches between Moves and Stats. Left/Right or Up/Down changes
Pokémon in Moves and Stats. In Learn, Left/Right scrolls the list and
Up/Down changes Pokémon. Select opens or closes Learn where supported.
B returns to the highlighted Pokémon in the grid.

In Bag, Up/Down scrolls the item list and A opens an item's details. Left
moves to the pocket bar (**All** and the occupied pockets), on the open pocket;
with details open, Left closes them and goes to the bar in the same press. On
the bar, Up/Down moves the cursor and stops at the first and last pocket, A
opens the pocket under the cursor and returns to the list, and Right or B goes
back to the list without changing pocket. In the list, B returns from details.
Press Select while browsing to cycle **Default**, **Name**, and **Count**
(highest first). Sorting only changes the companion display.

To control the DS overworld HUD, turn **HUD** on and assign an unused button
under **HUD CURSOR BUTTON** in companion Settings. The binding starts at
**None** so native buttons keep their usual functions. Press the assigned
button over a visible HUD, move with the D-pad, and press A to open the
highlighted map, party summary, or Bag. Press B or the HUD button again to
return to gameplay. The cursor is available in the overworld, with a target
for each visible HUD part.

The shared tab order is **Party, Dex, Map, Bag, Guide**, with **More** and
**Settings** at the end when available. **Battle** takes the Party slot
during a supported battle.

In Settings, Up/Down follows the available rows as the list scrolls. Press
A to select and B to back out. Each core exposes its own supported settings.

On either map, use the D-pad to move and A to recenter. B moves focus to
**Places** and **Me**, then Left/Right chooses a button. Up returns to the
map, and a second B closes it. Select opens Places directly from the map.
In Places, Up/Down picks a location, A goes there, and B returns to the
map buttons.

### DS Battle companion

Tap the companion button (L2 by default) during a supported single battle to open
**Battle Info**. It shows the active matchup, your moves and PP, and type
effectiveness. Use the D-pad and A to choose **Suggestions**, which ranks
party members and their usable moves. In trainer battles, previously seen
opponents can be selected from the roster. B returns to Info or closes the
companion. The game remains paused while you browse.

### Platinum One Screen battle strip

On Platinum USA revision 1, **Battle Strip** defaults on with **One Screen**
selected. An existing Off preference is kept and can be changed in the DS
companion Settings. A new battle returns a temporary screen peek to One
Screen. Use the D-pad and A to choose Fight,
Bag, Pokémon or Run. Fight opens the move grid with move types and current/max
PP. B returns from the move grid. Native Bag and party windows open full screen
at twice the DS size, like the battle. Doubles and native prompts use the
game's own controls. The picked button has the games' own red corner brackets.

Platinum's compact battle buttons use the native lettering and tiles without
the Fight sprite. The move cards retain the original type badges and
current/max PP.

### Experimental Johto and Unova battle strips

The same One Screen controls are enabled for exact English retail revision 0
ROMs of HeartGold, SoulSilver, Black, White, Black 2 and White 2. HeartGold
and SoulSilver keep the game's own message frame beside split-color buttons,
and their move cards follow the games': a type-colored frame, the move name,
the type badge and current/max PP. Black and White use charcoal plates and
colored command labels, Black 2 and White 2 striped plates with colored rims;
their angled move plates show current/max PP, and a move out of PP is greyed.

These themes use PokéDaisy's bitmap font and app-owned move data, and each
move's base PP from the cart. The native move-card artwork remains specific to
Platinum. The new battle paths need live retail ROM testing.

### Johto and Unova companion

| Games | Exact revisions | Companion and Quick Menu |
| --- | --- | --- |
| HeartGold, SoulSilver | USA rev 0 | Party, read-only Bag, Map, Guide, Dex, Settings, HUD cursor |
| Black, White, Black 2, White 2 | USA/Europe rev 0 | Party, read-only Bag, Map, Guide, Dex, Settings, HUD cursor |

Party shows HP, EXP to the next level, type matchups, moves, power and PP.
A opens the selected party member's summary or Bag item's details. In a party
summary, A switches between Moves and Stats. Select opens the level-up Learn
list and Left/Right scrolls it. Up/Down browses the party. In Bag, Left goes
to the pocket bar and A picks a pocket.
Turn on HUD in Companion Settings. In the overworld, hold L2 and choose HUD
to focus a HUD tile. Use the D-pad and A to open it, or B to cancel. It appears after the
overworld settles in a layout with only the top screen visible. In the companion's
Settings, HUD opens the same HUD page as the GB and GBA cores (on or off, LOOK,
SIZE and each part); Minimal HUD stays a row of its own and hides the party cards. The ball counter shows up to three ball types by quantity and
Repel Steps appears only while a Repel is active. The Dex lists seen and
caught species in a list beside Android's four filters. Press Left to focus
the filters and the regional/National switch at the bottom. Select cycles
filters from the list. Press A on a seen entry to open its cartridge front
sprite, typing, height, weight, description, and base stats. Select cycles
Info, Evolve, Area and Moves. Area lists ordinary wild encounters, with odds
per method and applicable times or seasons. Moves includes level-up moves
and cartridge TM/HM compatibility. Press A in Moves to jump between the two
groups. Up/Down scrolls details, Left/Right browses seen entries in the
current filter, and B returns to the highlighted list row. The Settings gear
offers Dark Mode for the DS companion.

The Map and Minimap share the cartridge's art. In Map, the D-pad moves the
cursor. Press B to focus **Places**, then use Left/Right to choose **Places**
or **Me** and A to confirm. Places jumps to a selected town or route, and Me
recenters on the player. Select and A still open Places or recenter directly
from the map cursor. The Minimap is hidden in Minimal HUD mode. Guide's **Here** page
lists local To Do hints, ordinary encounters for the current map, and items for
the surrounding area, including its buildings. Tips is available for supported
games, and Platinum and HGSS also have a Stuck? page. The device's local time selects the
encounter table. Picked-up items are marked Done using the save's flags.
GB/GBC/GBA and DS share the Guide controls. Press **Up/Down** in the sidebar
and **A** to choose **Here**, **Next Boss**, **Tips**, **Where Is**, or
**Stuck?** where the game supplies it. Press **Right** to enter the page and
**Left** to return to the sidebar. **Up/Down** moves through entries, and **A**
reveals the hint and then the answer. **B** closes an open reveal before
leaving the companion. The first visit shows the shared Guide notice.

Where Is has hints for HMs and key items. Next Boss reads badges and event
flags to start at the first unfinished battle in Android's guide order.
Each section compares the opposing team's highest level with your party's
highest level. A reveals a Pokémon's types, then its species, moves and held
item. Version-specific teams and the sequels' Normal and Challenge variants
follow Android's data and progress rules.

On an open overworld, the Quick Menu has a Repel action. Select a Repel
from the list and press A to use one. The game counts steps down and handles
expiry. Repel cannot stack with one already active.

On the supported DS revisions above, hold **L2** in the overworld and
choose **PC** to open the game's own Pokémon Storage System. The shortcut
uses the same computer icon as the GB/GBC/GBA Quick Menu. The game handles
the PC menus and box changes, and the shortcut is hidden during battles
or while another field menu is open.

On Black 2 USA/Europe rev 0, the overworld Quick Menu also offers **Fly**.
A party member must know Fly. Pick a visited destination with the D-pad
and A. PokéDaisy opens the game's Fly map and highlights that destination.
Check it and press A to travel, or press B to cancel. The game checks
whether you can fly from the current location.

The Party and Bag readers and companion navigation were tested against
upstream RAM captures for all six titles. ROM icon extraction, live HUD
updates, and native battle/menu transitions still need retail-ROM testing
on the Brick.

In One Screen, title or setup can show both DS screens. The overworld
uses the top screen, with the native X menu and touch-only apps floating
over it. Apps that need both screens use a side-by-side panel. Use
**Select + R2** to open the touch screen manually and **Select + D-pad / A**
to move or tap the stylus.

From the overworld, hold **L2** to open the DS Quick Menu. **Wild** toggles
encounter rates and shows an OFF badge while suppressed. **Fly** lists unlocked
destinations for a party member who knows Fly. Black 2 opens the native map for
confirmation. **Auto-Run** in Companion Settings follows Android's controls.
Hold **B** while moving to walk. Menus, scripts, battles, bikes and surfing keep
their native controls.

## Polished Crystal 3.2.3

The normal release supports **Expand** and **Zoom Out** in Companion Settings
→ Aspect. From the overworld, **Y** opens the quick menu with **PC**, **Fly**,
**Repel**, **Bike** and **Wild**. PC uses the original storage menu from any
direction when the player is free. Fly needs the Storm Badge and a party member who knows
Fly. Choose the destination on the game's map. Repel offers the types held
and shows the remaining steps on the HUD. Bike uses the game's Bicycle.
Wild Off suppresses random field encounters until turned back on. Scripted
battles still happen.

Open Companion with **X**. In its Bag, **R2** hands control to the original
Bag or opens the action menu of the selected ordinary or key item. R2 must
be free of other Companion button assignments. To use a Potion on a party
member from Companion, open the item's details, choose **Use**, then pick
the target. **B** cancels a field shortcut while it is navigating. Fly,
Bike, Repel, Potion, and Bag use the shared input controller. It reads the
native menu and cursor before picking a row and waits for the destination
screen or the item effect before reporting success.

In a battle, **X** opens the shared Battle tab with Fight, PKMN, Pack, Run,
Info and Suggestions. Use the D-pad and A to choose a move or action.
Selections that act in the game return to its native battle, party or Pack.
The controls and shortcuts above are enabled for the exact standard 3.2.3
ROM. Other mapped revisions, Faithful and debug builds retain their
existing companion support.

## GB, GBC and GBA companion backport checklist

In the Dex, **Select** cycles the four filters. Press **A** on a seen entry to
open its details where supported. In a Dex entry, **Select** cycles the
available Info, Evolve, Area and Moves pages. **Left/Right** browses seen entries,
wrapping from the last to the first, and **Up/Down** scrolls the page. Press **A** in Moves to jump between
level-up and machine or teachable groups. Both cores share the list, portrait,
detail tabs, footer, and evolution rows. Exact mapped Polished Crystal carts
supply their own Dex art, Info page, and level-up moves.

Both cores use the same split Party summary and Pokémon switcher. **A** opens
the summary and switches between Moves and Stats when stats are available.
**Select** toggles Learn when the ROM supplies a learnset. **Left/Right**
scrolls Learn and **Up/Down** changes party members. On DS, **Settings → Tab
Bar** edits the visible tabs. **Settings → More Tabs** opens a hidden tab.

| Feature | Backport and validation |
| --- | --- |
| Compact Party summary | Moves, Stats, Learn and the switcher share a renderer. The cores supply stats and learnsets from their own game data. |
| Dex navigation and details | Both cores use the same list and split detail layout. Polished Crystal has its own cartridge Info reader. Other Gen 2 detail readers and the remaining tabs need adapters. |
| Evolution and level-up Moves | Stock mapped GBA ROMs supply Evolve links. GBA level-up uses existing Guide or expansion learnsets. Exact Polished Crystal 3.2.0–3.2.3 releases supply their own level-up learnsets. The handheld Evolve page still needs adapters for GB/GBC and hack-specific GBA rules. |
| Dex Area and TM/HM | GBA Moves shows checked cartridge TM/HM bits or the expansion game’s teachable list. Area uses the stock GBA encounter reader where the ROM maps its encounter tables. |
| Guide | The Guide sidebar, page and row focus, completion markers, first-visit notice, and hint/answer flow are shared across both libretro cores. Here, Next Boss, Tips, Where Is, and Stuck? use each game's existing area and guide data. DS has item balls and hidden items plus authored To Do entries, with no generated NPC gift and trade data for a separate People section. |
| Companion Dark Mode | Reuse the shared window palette. Add GB ink/paper and GBA Bag dark variants, persist the setting in the GB/GBA core, and compare each look against Android. |
| Overworld controls | Compare the existing GB/GBA Wild and Fly actions with the DS controls. Check Auto-Run parity for games with a run button, and test Wild restoration across resume on games that support the toggle. |

## Build the Brick package

Run from the repository root with CMake, Ninja and Python 3.12 or later.
`linux/setup_trimui_sdk.py` prints the absolute SDK path to export as
`NEXTUI_TOOLCHAIN`.

```sh
git submodule update --init third_party/mgba third_party/armgba third_party/zlib
git submodule update --init --recursive third_party/dsperate-lr
python3 linux/setup_trimui_sdk.py /path/to/trimui-sdk
export NEXTUI_TOOLCHAIN=/path/to/trimui-sdk/cross/x86_64-aarch64-nextui-linux-gnu
cmake -S libretro -B build/gb -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$PWD/linux/trimui-toolchain.cmake"
# armGBA needs a newer GCC than the toolchain's 8.3: a distribution's aarch64 cross GCC (Ubuntu 22.04's
# gcc-aarch64-linux-gnu); libretro/armgba/glibc_compat.* keep the core loadable on the Brick's glibc.
cmake -S libretro/armgba -B build/gba -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$PWD/libretro/aarch64.cmake"
cmake -S linux/libretro-ds -B build/ds -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$PWD/linux/trimui-toolchain.cmake" -DBUILD_TESTING=OFF
cmake --build build/gb --target pokedaisy_gbc_libretro -j4
cmake --build build/gba --target pokedaisy_gba_libretro -j4
cmake --build build/ds --target pokedaisy_ds_libretro -j4
python3 linux/libretro-unified/package.py build/gb/pokedaisy_gbc_libretro.so build/gba/pokedaisy_gba_libretro.so \
  build/ds/pokedaisy_ds_libretro.so PokeDaisy-NXRedux.zip \
  --strip "$NEXTUI_TOOLCHAIN/bin/aarch64-nextui-linux-gnu-strip" --source-ref "$(git rev-parse HEAD)"
```

`--cores-only` makes the release's `pokedaisy-libretro-<arch>.zip` instead: the three cores and their notices in one
folder named after the zip, no NXRedux launcher or ROM folder. CI's `linux.yml` builds both.
