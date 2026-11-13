# Simplified Chinese game data: sources, scope and licensing note

This document accompanies the `zh-Hans` localization. It exists so a reviewer can
see exactly where every Chinese string in the game-data tables came from, and
make an informed decision about third-party text before merging.

## What is translated

| Table | Entries | What it covers |
| --- | --- | --- |
| `Tr*.kt` (9 tables) | 1,708 keys | The app's own UI: labels, buttons, settings, hints. |
| `GuideText*.kt` (5 tables) | 643 lines | The GUIDE's written pages. |
| `GameNamesZh.species` | 385 | Pokémon names, keyed by the ROM's internal species id (Gen 3's 252–276 gap is unused, so Hoenn starts at 277). |
| `GameNamesZh.moves` | 354 | Move names (Gen 3). |
| `GameNamesZh.items` / `itemDescs` | 263 / 249 | Item names and bag descriptions; TM/HM descriptions are generated from the move they teach. |
| `GameNamesZh.abilities` | 80 | Ability names (ids 1–80, covering all of Gen 3). |
| `GameNamesZh.places` | 453 | Map section names across six retail map datasets (FRLG, Emerald, Crystal/GSC, RGBY, Yellow). |
| `DexTextZh.genera` / `flavors` | 386 / 386 | Official Pokédex categories and flavour text for the whole National Dex 1–386. |

`DexTextZh` and `GameNamesZh` are **fallbacks**, not overrides. Display text is
still read from the running ROM first. A fan-translated ROM usually keeps its
strings in a custom glyph encoding that `Gen3Text.decode` cannot read; those
strings are detected with `String.readsAsText()` and replaced with the built-in
Chinese text. Every Chinese lookup is additionally gated on
`L10n.language == AppLanguage.ZH` (or on the text being junk), so English,
Japanese, Korean and European carts keep their own text and their own pixel
fonts. Trainer-card parsing is deliberately untouched.

## Where the Chinese came from

- **UI and GUIDE strings** — machine-assisted first pass, reviewed by hand.
  Placeholders (`{0}`) and casing were preserved; `scripts/check_translations.py`
  validates them (1,708 entries, 0 problems).
- **Official names** (species, moves, items, abilities, types, places) —
  cross-checked against [PokéAPI](https://pokeapi.co/) (`zh-hans` language key,
  BSD 3-Clause; already listed in Settings > LICENSES) and the Chinese
  fan-translation ROM the user supplied for terminology consistency.
- **Pokédex categories and flavour text** — PokéAPI's `genera` for the
  categories; flavour text was collected from the
  [52poke wiki](https://wiki.52poke.com/) pages for all 386 species, preferring
  the Emerald wording, then Ruby/Sapphire, then FireRed/LeafGreen. PokéAPI has
  no `zh-hans` flavour text for the Hoenn Pokédex, which is why the wiki was
  used.
- **Pixel fonts** — `PixelOperator.ttf` (Jayvee Enaguas, CC0 1.0) and
  `PixelCJK.ttf` (Fusion Pixel Font 12px zh_hans, TakWolf, SIL OFL 1.1).
  `PixelTypeface.kt` adds the latter to its fallback chain so Japanese, Korean
  and Chinese stay pixel art where `Typeface.CustomFallbackBuilder` (Android 10+)
  is available. Both are listed in Settings > LICENSES.

## Licensing note for maintainers

Pokémon names, move/item names, ability names, place names and Pokédex flavour
text are trademarks and copyrighted text belonging to Nintendo / Game Freak /
The Pokémon Company. 52poke's pages reproduce that text. The app already
bundles English equivalents of much of this data (item descriptions, move
names), but the Simplified Chinese tables add a substantial body of third-party
text to the repository.

If that is not acceptable upstream, the localization splits cleanly:

1. Commit 1 (UI strings + GUIDE + fonts) contains no game-data text and can
   land on its own.
2. The game-data tables can stay in a fork, or be replaced by a runtime lookup
   against a user-supplied file.

The intermediate scrape data (`zh-i18n/`, wiki HTML, JSON) is **not** part of
this repository; only the generated Kotlin tables and the build script are.

## Fonts and Android versions

Chinese UI text uses the device's system font at a reduced size, not a bundled
pixel face. A merged Pixel Operator + PixelCJK font was tried (one TTF for the
three paths that cannot use `CustomFallbackBuilder`: `GbaText`'s CJK branch,
`PartyScreen`'s nickname and `GbaPartySlot.nameText`), but its 12 px grid lands on
half pixels at these sizes and thin diagonal strokes render grey. It was dropped
in favour of the sharper vector system face.
