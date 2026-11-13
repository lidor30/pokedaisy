---
title: Supported games
summary: Which games the companion can read, how to check your ROM, and what happens with a hack or version PokeDaisy doesn't know.
section: help
order: 1
keywords: [randomizer, randomized, randomiser, universal randomizer, upr, upr zx, nds, nintendo ds, platinum, heartgold, soulsilver, black, unova, gen 5, purered, pureblue, purergb, compatibility, compatible, rom hack, hacks, version, sha1, sha-1, checksum, unsupported, best effort, partially supported, request]
---

Any GBA, Game Boy or DS game plays in PokeDaisy. "Supported" is about the companion on the bottom screen: to show your party, bag, map and battles, it has to know exactly which game you're running and where that game keeps its data. A game it doesn't know still plays fine on the top screen; the companion shows a notice instead of your party.

## What's supported

**The original Game Boy Advance games:**

- Pokémon FireRed, LeafGreen, Emerald, Ruby and Sapphire.
- The English (USA / Europe) releases and the Spanish, German, French, Italian and Japanese ones, every revision.
- English FireRed (both revisions) and Emerald also count when you've run them through Universal Pokemon Randomizer ZX (version 4.6.1), with any settings and any seed. The library calls them "Pokémon FireRed (Randomized)" and "Pokémon Emerald (Randomized)". The companion reads what the randomizer changed from your ROM: each Pokémon's types, the moves' power and type, the type chart, the trainers' teams, the wild Pokémon, evolutions, learnsets and TMs, and the items lying around. The [guide](guide.md#randomized-games) leaves out what it can't know. A randomized copy of another game, or one made with another randomizer, isn't recognized.
- English FireRed and Emerald also count when the file comes from a collection that changed it a little: the empty space at its end cut off or filled with something of its own, or patched to run on a flash cartridge (its save code changed so it saves without the original flash chip, often with the "internal battery has run dry" message reworded). Any change to the game itself makes it a hack the companion doesn't know.

**Game Boy and Game Boy Color:**

- Pokémon Red and Blue: the USA / Europe releases, drawn in the game's own look: the colours they show on a Super Game Boy.
- PureRed and PureBlue, the PureRGB hack of Red and Blue: version 2.7.6, drawn in the game's own look (the colours of its default OG setting), with its own types, moves, items, places and Pokédex. A save from the original Red or Blue works too, once the game itself has updated it: pick CONTINUE and answer its questions.
- Pokémon Yellow: the USA / Europe release and the German, French, Italian and Spanish ones, drawn in the game's own look. A German, French, Italian or Spanish copy shows its own names and Pokédex text. Item descriptions are only shown in the English one, as these games have none of their own.
- Pokémon Yellow Legacy: version 1.0.10, drawn in the game's own look, with its own moves, trainers, wild Pokémon, party icons and town map. Make it from your English Yellow with `PATCH A ROM` and the release's patch. Start a new game: a save from the original Yellow doesn't load correctly in it.
- Pokémon Gold and Silver: the USA / Europe releases and the German ones (Goldene Edition and Silberne Edition), drawn in the game's own look. The German copies show their own names and Pokédex text.
- Pokémon Crystal: the USA release, the USA / Europe revision 1, the Australian one and the German one (Kristall), drawn in the game's own look. The German copy shows its own names and Pokédex text.
- Pokémon Crystal Legacy: version 1.3.1, drawn in the game's own look, with its own moves, items, trainers, wild Pokémon, coloured party icons and maps. Make it from your Crystal (the USA release or revision 1) with `PATCH A ROM` and the release's matching patch. A save from the original Crystal starts, but its bag comes out wrong, so start a new game.
- Pokémon Crystal Clear, the open world hack of Crystal: versions 2.5.11 and 2.6.0, each in its Standard Colors and its Color Filter build, drawn in the game's own look.
- Pokémon Prism, built on Crystal with regions of its own: version 0.95 build 254 (Hotfix 5), drawn in the game's own look, with its own Pokédex.
- Polished Crystal: versions 3.2.0 to 3.2.3, each in its normal and its faithful build (and their debug builds), drawn in the game's own look, with its own Pokédex. The faithful builds keep the original types and moves, and the companion shows them that way.

**Nintendo DS:**

- Pokémon Platinum: the USA release (revision 1), and the French and Spanish ones (revision 0).
- Pokémon HeartGold: the USA, German, French, Spanish, Italian, Japanese and Korean releases (revision 0), and the English one sold in Europe (revision 10).
- Pokémon SoulSilver: the USA, German, French, Spanish, Italian, Japanese and Korean releases (revision 0), and the English one sold in Europe (revision 10).
- Pokémon Black: the USA / Europe, French, German, Spanish, Italian, Japanese and Korean releases.
- Pokémon White: the USA / Europe, French, German, Spanish, Italian, Japanese and Korean releases.
- Pokémon Black 2: the USA / Europe, French, German, Spanish, Italian and Korean releases, and the Japanese one (revision 1).
- Pokémon White 2: the USA / Europe, French, German, Spanish, Italian and Korean releases, and the Japanese one (revision 1).

The companion shows each game's Pokémon, moves, items and places in your cart's own language, read from the cart.
- Pokémon Origin HeartGold, the hack: version 4.0.3 with its English patch 1.0.0.
- Pokémon Renegade Platinum, the hack: versions 1.2.0 and 1.3.0, and 1.3.0 with its Classic patch.
- Pokémon Blaze Black 2 Redux and Volt White 2 Redux, the hacks: version 1.4.1, also with their Classic, EVless and Classic EVless patches.

The companion reads their party, bag, map, Pokédex and guide, with pictures, Pokédex pages and the map read from your cart. The battles of Platinum, Black, White, Black 2 and White 2 show in the `BATTLE` tab; HeartGold's and SoulSilver's don't yet. In Black, White, Black 2 and White 2 the `MAP` is the game's own Town Map: the towns you've arrived at marked as the game marks them, the others left plain. `PARTY`, `BAG` and `DEX` take each game's own colours: the cut-corner slots on a navy grid of Black, White and their sequels, Black's and White's green Pokédex grid, Black 2's and White 2's red one, Platinum's blue slots on slate stripes. Origin HeartGold has its own species (every region's, and their forms), moves, Fairy type and battle system: the companion reads its party, bag, map and Pokédex (all 1,025, with its own stats, evolutions and moves) with the names, types and pictures from your cart, and its battles show in the `BATTLE` tab (`INFO` and `SUGGESTIONS`; you choose your moves on the game's own touch screen). It has no guide yet. Renegade Platinum plays as Platinum does, with its own types (the Fairy type among them), moves and type chart read from your cart, so the `BATTLE` tab's advice and the `DEX` follow the hack's changes; it has no guide yet either. Blaze Black 2 Redux and Volt White 2 Redux play as Black 2 and White 2 do, with their own types (the Fairy type among them), moves, type chart and machines read from your cart, and no guide yet. A save from the original Black 2 or White 2 keeps working in them. [Screens and devices](devices.md#ds-games) has how a DS game uses your screens.

**ROM hacks**, each in the versions listed:

| Hack | Version |
| --- | --- |
| Pokémon Unbound | 2.1.1.1, and its French translation |
| Pokémon Radical Red | 4.1 |
| Pokémon Gaia | 3.2 |
| Pokémon Odyssey | 4.1.1 |
| Pokémon Heart and Soul | 2.0.6 |
| Pokémon Amethyst | 1.3.0 and 1.4.1 |
| Celia's Stupid Romhack | 1.1.4 |
| Pokémon Orange Islands | Beta 5.7, and an older beta |
| Pokémon Ultra Violet | 1.22 |
| Pokémon FireRed Rocket Edition | its one release |
| Pokémon Ash Gray | 4.5.3, and its Fork Bug Fix 1.0 build |
| Pokémon Let's Go (both versions) | its English translation, and each version's Translation Only build |
| Sword and Shield Ultimate Plus | 1.2.1.2, plus its three patches |
| Pokémon GS Chronicles | 2.7.6 |
| Pokémon Recharged Yellow | 1.8.0, 1.9.0, 1.9.4 and 1.9.7 |
| Pokémon Lazarus | 2.0 |
| Emerald Seaglass | 3.0 |
| Too Many Types 2 | 1.5.2 |
| Pokémon Glazed | 9.2.0 |
| Emerald Imperium | 1.3.1 |
| Pokémon Emerald Kaizo | 2.0 |
| Pokémon Emerald Legacy | 1.1.4 |
| Pokémon Quetzal | English and Spanish Alpha 9 v1, English Alpha 9 v0, Spanish Alpha 9 v0, English and Spanish Alpha 0.8.4 |
| Moemon Quetzal | English and Spanish Alpha 0.8.4 Hotfix |
| Super Mariomon | 1.2.1 to 1.3, 1.5.1 and 1.5.2 |
| Pokémon Recharged Emerald | 2.2.5 and 2.2.8, and Rebalanced 2.2.8 |
| Pokémon Modern Emerald | 3.6 (types follow the settings you picked when you started your game) |
| Pokémon Elite Redux | 2.65.3b and 2.65.4.1b |
| Pokémon Team Rocket Edition: Dragonsden Version | 1.5, and the 2026 edition's 3.0.9 and 3.1 in Spanish (its START menu shows the version) |
| Pokémon Wishes of Tomorrow | 1.2.6 |
| Pokémon Dreamstone Mysteries | its one release (the game shows no version) |
| Pokémon Monomyth | 1.0 and 1.1 |
| Emerald Rogue | 2.2.1-EX |
| Pokémon SoulGold | 1.1.4, three builds of 1.2, and two of 1.2.1 |
| Pokémon R.O.W.E. | 2.1.9.1 Experimental |

Not every game gets every tab yet. Some hacks have the guide's live pages only, a few have no guide, and battle panes in many hacks haven't been checked in every kind of battle. The full table, tab by tab, is on the [compatibility page](https://pokedaisy.com/compatibility/) and in the [README](https://github.com/lidor30/pokedaisy#supported-games).

## Games removed at their makers' request

Some games aren't read by PokeDaisy because the people who made them asked for that. They still play, as plain games: no companion, no `EXPAND`, no HUD and none of the other extras. Instead of your party, the companion says why and links to where you can ask the game's makers about it.

| Game | Why |
|---|---|
| Pokémon Ghost Grey | Its developer asked for it to be removed. Ask them on the Ghost Grey Discord if you'd like it back. |

## Support is for the exact version

PokeDaisy recognises a game by its file, using the ROM's SHA-1 checksum (a fingerprint of every byte). So "Radical Red" means Radical Red 4.1, the exact file that version's patch produces. An older or newer version of a hack moves things around in memory, and reading it with the wrong map would show wrong names, wrong sprites or nonsense.

That's why the version matters:

- If you patched a hack yourself, make sure you used the right patch on the right base ROM (most hacks want a clean, unmodified US FireRed or Emerald). [Patch a ROM hack](library.md#patch-a-rom-hack) finds the right base ROM by itself.
- If the hack has a newer version than the one listed, it may not be recognised until it's added. [Best effort](#best-effort-for-games-pokedaisy-doesnt-know) may still read it.

## Check your ROM before you play

The website's [ROM check](https://pokedaisy.com/compatibility/) tells you whether a file is supported. Pick the ROM and it runs the same checks the app does, right in your browser. **The ROM never leaves your device**: nothing is uploaded.

In the app, the library tags games the companion can't read as `NOT SUPPORTED`, and ones it reads only in part as `PARTIALLY SUPPORTED`. A game's `INFO` (long-press it in the library) tells you more.

## Best effort for games PokeDaisy doesn't know

When you play a ROM PokeDaisy doesn't recognise, but its header says it's built on FireRed, LeafGreen, Emerald, Ruby or Sapphire, the companion doesn't give up. It tries reading the game as each game it knows, by itself, while you play, and checks what it would show against the ROM: species, move and item names, the map, your party.

What happens next depends on how well a game fits:

- **A clear match.** A different build of a supported game is then fully supported. A hack that shares a supported game's memory gets party, bag, map and battles. Anything that didn't check out (the Pokédex, the guide, item descriptions, sprites) is switched off, so you get no sprite rather than a wrong one. The library tags it `PARTIALLY SUPPORTED`.
- **A likely match before you have a party.** The companion uses its best guess and confirms it once it can read a Pokémon.
- **No clear winner.** The companion asks you (see below).

The match is remembered for that ROM. To undo it, long-press the game in the library, open `INFO` and tap `FORGET MATCH`.

### Hacks that kept FireRed or Emerald's own code

Many hacks are made by editing the English FireRed or Emerald directly, with a map editor or a patch tool, and leave the game's own code alone. PokeDaisy checks the code of such a ROM against the original game's when it starts. If it still holds what the features below need, the hack gets them even though PokeDaisy doesn't know it by name:

- [EXPAND](screen.md#expand-more-of-the-map) and the field features that read the overworld (TEXT and the other scene speeds, AUTO-RUN, RUN INDOORS). If the hack moved the tables of the people standing further off, those people aren't drawn past the screen's edge.
- Quick battle taps (a tap on `FIGHT`, `BAG`, `RUN` or a move goes straight to it) and [switching during a battle](battle.md#switching-during-a-battle), once best effort reads the game as FireRed or Emerald.

A hack that rebuilt the game from its source code doesn't pass this check, and nothing changes for it.

## WHICH GAME IS THIS?

When more than one game could fit, or none fits clearly, the companion shows **WHICH GAME IS THIS?** in place of your party. The likely ones come first, tagged `LIKELY`. Pick the game your ROM is a version of, or the base game the hack was built on.

- If you're not sure, play on: once you have a Pokémon in your party, PokeDaisy checks again by itself.
- `NONE OF THESE` tells it the ROM isn't any of them. The companion then shows its `NOT SUPPORTED` page.
- After you pick, PokeDaisy checks the pick once it can read your party. If it doesn't fit, a `WRONG GAME?` notice lets you `PICK AGAIN` or `KEEP` it.

You can open the picker again any time:

- On the companion: `SETTINGS` > `PLAYS AS` (at the top, in the `GAME` group, only for a ROM read this way).
- In the library: long-press the game, open `INFO` and tap `PICK A GAME`.
- From the match notice: `WRONG GAME?`.

## NOT SUPPORTED and ASK FOR SUPPORT

When the companion can't read a game at all, every tab except `SETTINGS` shows a `NOT SUPPORTED` page. The game itself keeps running normally on the top screen; you lose the companion, not the game.

Want it supported? Tap `ASK FOR SUPPORT`, on that page or in the game's library `INFO`. It opens the game request form on the [feedback page](https://pokedaisy.com/feedback/), filled in with the file's name, game code, size and SHA-1, so the right version can be found. You don't need a GitHub account. It never sends the ROM itself.

Every request goes up in [Game requests](https://github.com/lidor30/pokedaisy/discussions/categories/game-requests) on GitHub. Upvote the games you want: the most wanted ones help decide what comes next.

> [!TIP]
> Before you ask, run the file through the [ROM check](https://pokedaisy.com/compatibility/). If it's a supported hack in a different version, getting the listed version is the quickest fix.

## Sharing a best effort match (optional)

When best effort finds a match for a ROM, the notice may ask **SHARE THIS MATCH?** Sharing helps a future release support that ROM properly. It's entirely up to you: nothing is sent unless you tap `SHARE`, and `NO THANKS` sends nothing.

The notice lists every value it would send, and that's all of it: the ROM's SHA-1, size, game code and revision, the game it was read as and how well it fit, and the app version. No file name, no account and no device details. It goes to PokeDaisy's own database, where it can't be read back by anyone through the app or the site. If you're offline, it's sent the next time the app starts.
