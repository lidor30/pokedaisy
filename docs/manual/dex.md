---
title: Pokédex
summary: Your Pokédex with seen and caught marks, and full entries with stats, evolutions, where to catch each Pokémon and the moves it learns.
section: companion
order: 7
keywords: [pokedex, dex, entry, seen, caught, owned, national, regional, evolution, evolve, how to evolve, where to catch, location, encounter, learnset, level up moves, tm, base stats, ability, egg group, catch rate]
---

The `DEX` tab is your Pokédex, read from your save, with entries that go further than the game's own: how each Pokémon evolves, where to catch it and which moves it learns, all from your game's own data. It's the page to check when you're wondering "does this one evolve?" or "where do I find that?".

## The list

The left side shows your counts and filters:

- `SEEN` and `OWNED`: how many you've seen and caught in the dex you're looking at.
- A filter list with the count for each: `ALL`, `CAUGHT`, `SEEN ONLY` (seen but not caught yet) and `NOT SEEN`. Tap one to narrow the list.
- A button that flips between the regional dex and the `NATIONAL DEX`, in games that have both. It starts on the one your save is using: regional until the game gives you the national one.

The right side is the numbered list. A red ball marks a Pokémon you've caught, a grey one a Pokémon you've only seen. Ones you haven't seen are greyed out.

## An entry

Tap a row to open its entry. The left side has the number, name and caught mark, the Pokémon's picture, its footprint (in games that have them), its category, its types, and its height (`HT`) and weight (`WT`) in the game's own units.

The right side has up to four sections, switched with the chips at the top:

- `INFO`: the dex text, base stats with their `TOTAL`, `ABILITY` (and `HIDDEN` ability in games that have them), `EGG GROUP`, `GENDER` ratio and `CATCH RATE`.
- `EVOLVE`: the whole family, both ways, and how each step happens.
- `AREA`: where it's found in the wild, and any gifts or trades.
- `MOVES`: what it learns by leveling up, and by TM.

Only the sections a game has data for appear; `INFO` is always there. The section you pick stays open as you move between entries, so you can step through the dex comparing evolutions, for example.

The arrow buttons at the bottom step to the previous and next entry in the list as you've filtered it (the counter beside them shows where you are). The blue button, or BACK, returns to the list.

> [!NOTE]
> Every entry opens, including ones you haven't seen yet. If you want to discover the dex the way the game intends, stick to the `SEEN ONLY` and `CAUGHT` filters.

## EVOLVE

`EVOLVE` lists the family grouped by the Pokémon that evolves, each step with what it needs: a level (`LV 16`), an item to use or hold, a trade, high friendship, a time of day, a certain place, and so on. Hacks with new evolution methods show those in plain words too. A step whose condition PokeDaisy can't put into words reads "Special condition". A Pokémon that doesn't evolve says so.

Tap another member of the family to jump to its entry.

## AREA

`AREA` lists every place the Pokémon appears in the wild, grouped by place. Each line says how you meet it (walking in grass or a cave, on water, smashing rocks, or fishing with each rod), the level range and the chance in percent. In games with a day and night cycle, a line can also say what time of day it appears.

Gifts, eggs and in-game trades for that Pokémon are listed too, where the game's [guide](guide.md) has the area data. "Not found in the wild" means there's no wild encounter for it in this game.

## MOVES

`MOVES` starts with `LEVEL UP`: each level and the move learned there (`EVO.` for a move learned on evolving). Below that is `TM / HM`, the machines it can use. In newer hack engines the list is `TM / TUTOR` instead.

Some hacks only have their level-up moves read so far, and say so under the list.

## What comes from your ROM

None of this is bundled with the app. Names, pictures, dex text, stats, evolutions, encounters and learnsets are all read from the ROM you're playing, so a hack's changes show up as that hack has them, and names come in your game's language. The very first time you open an entry it can take a moment to read.

That also means what you see depends on the game:

- The **original games** (FireRed, LeafGreen, Ruby, Sapphire, Emerald) have every section.
- **Hacks** vary. Most get the full entry; some don't have `EVOLVE`, `AREA` or the TM half of `MOVES` yet. A hack with only one dex has no regional / national button.
- **Pokémon Red, Blue and Yellow** (and PureRed and PureBlue) show their five stats with one `SPECIAL`, and no abilities, egg groups or genders, since that generation had none.
- **Pokémon Gold, Silver, Crystal** and **Crystal Clear** show all 251 in number order, with six stats and the gender split, but no abilities or egg groups on the page, and no `EVOLVE`, `AREA` or `MOVES` yet.
- **Polished Crystal** shows its 289 in National order, with heights and weights in meters and kilograms. A regional form shows as its species there. The page lists the abilities and the hidden ability (the faithful builds show their own), but no egg groups, and no `EVOLVE`, `AREA` or `MOVES` yet.
- **Pokémon Prism** shows its own Pokédex: its 253 Pokémon in the game's order, numbered the way the game numbers them, with heights and weights in meters and kilograms as the game shows them by default. Like Crystal, the page has no abilities or egg groups, and no `EVOLVE`, `AREA` or `MOVES` yet.

If a game has no Pokédex data at all, the `DEX` tab doesn't appear.
