---
title: Nuzlocke
summary: The NUZLOCKE tab keeps score of a Nuzlocke run for you, with one catch per area and a Pokémon that faints out for good.
section: player
order: 6
keywords: [nuzlocke, challenge, hardcore, run, tracker, encounters, first encounter, route, permadeath, death, dead, fainted, graveyard, dupes clause, rules]
---

A Nuzlocke is a way to play that makes the game harder and every Pokémon matter. The usual rules: you may only catch the first wild Pokémon you meet in each area, and a Pokémon that faints counts as gone for the rest of the run. Keeping track of that by hand gets tedious. PokeDaisy can do it for you.

It only keeps score. It never changes your game or your save: nothing stops you from throwing a ball where you shouldn't, it just tells you.

## Turning it on

Open the `NUZLOCKE` tab under `MORE` and tap `TURN ON`, or turn on `NUZLOCKE` in the `GAMEPLAY` group of the companion's `SETTINGS`. It's per game, so a run in one game doesn't touch another. Add the tab to the tab bar in `SETTINGS`, `TAB BAR`, if you'd like it one tap away.

The run starts from the party you have when you turn it on. If you're starting a new game, turn it on before you pick your first Pokémon.

Turning `NUZLOCKE` off pauses the tracking, and the tab goes back to its `TURN ON` page. Your run is kept, and picks up where it was when you turn it back on.

## What it tracks

**Encounters.** The first wild battle in an area uses up that area. The area is whatever the game calls the place you're in: a route, a town, a cave, a forest. Trainer battles never count. If you catch that Pokémon, the area shows `CAUGHT`; if you run, it faints or it gets away, it shows `MISSED`. While the battle is on it shows `NOW`.

A catch counts whether the Pokémon joins your party or goes to the PC because your party is full. One case it can't tell apart: a Pokémon sent to the PC that you'd already caught one of before. That shows as `MISSED`, and you can set it right by hand (below).

**Faints.** A Pokémon whose HP hits zero is out of the run, even if you revive it or heal it at a Pokémon Center afterwards. It moves to the `GRAVEYARD`, and its party slot gets a little gravestone.

## The NUZLOCKE tab

The title shows how many Pokémon are still in the run and how many have fallen. Below it:

- **HERE**: the area you're standing in. `FREE` means its encounter is still waiting for you.
- **TEAM**: every Pokémon in the run that's still standing, with where it joined.
- **ENCOUNTERS**: one row for each area, newest first, with the Pokémon you met and what happened.
- **GRAVEYARD**: the fallen, with where they fainted and their level.
- **RULES**: the options below, and `NEW RUN`.

## Fixing things by hand

Anything the tracker got wrong, you can put right:

- Tap an encounter to set it to `CAUGHT` or `MISSED`, or `NOT USED` to give the area back (handy for a gift Pokémon or a battle you don't want to count).
- Tap a Pokémon in `TEAM` to mark it as fainted.
- Tap a Pokémon in the `GRAVEYARD` to bring it back.

## Rules and warnings

- `DUPES CLAUSE`: when it's on, a wild Pokémon you've already caught one of (going by your Pokédex) doesn't use up the area, so the next new one does. It's `OFF` to start with.
- `WARNINGS`: short notices on the game screen as the run goes: your first encounter in an area, an area you've already used ("no catching here"), a catch joining the run, a Pokémon fainting, and a fallen one back in your party. It's `ON` to start with.
- `NEW RUN`: forgets every encounter and fallen Pokémon. Your party right then starts the new run. Your rules stay as they were.

> [!NOTE]
> `NUZLOCKE` works in the Game Boy Advance games (the retail games and the hacks the companion reads battles for). In the Game Boy and DS games the setting shows `N/A` for now.
>
> Emerald Rogue shows `N/A` too, on purpose. It's a roguelike: every run takes you through the same areas again, so "one catch per area" can't work there.

> [!TIP]
> Other common house rules, like giving every catch a nickname or not using items in battle, are up to you. The tracker only follows encounters and faints.
