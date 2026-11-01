---
title: PC boxes and save trades
summary: Rearrange a game's PC, change its bag and moves, or trade Pokémon between two of your games' saves, straight in the save files.
section: player
order: 8
keywords: [save editor, edit save, pc boxes, boxes, trade saves, trade, trading, transfer, move pokemon between games, bag, money, coins, held item, moves, save file]
---

PokeDaisy can open a game's save file while the game is closed: you can tidy its PC, change what it holds, or move Pokémon between two of your games, the way a trade would. It works with FireRed, LeafGreen, Emerald, Ruby and Sapphire saves (and ROM hacks that kept their save layout), in English and the other Western languages.

## PC boxes

In the library, open a game's menu (long-press it) and pick `PC BOXES`. You see the game's boxes and your party, read from its save.

- Tap a Pokémon to see it. `MOVE` puts it in another slot, in any box.
- Tap its held item or a move to change it. You can only pick what the game itself allows: items the bag can hold, and moves that Pokémon could learn.
- `BAG` shows the bag, your money and your coins, which you can change the same way. Key items and HMs stay as the game gave them.
- `SAVE` writes your changes. `UNDO ALL` drops them.

## Trade between saves

Tap `TOOLS` in the library (or open Settings > `TOOLS`), then `TRADE SAVES`, and pick the game to trade from and then the one to trade with. (From a game's `PC BOXES`, `TRADE` does the same.) The two games' boxes are side by side: pick a slot on each side, a Pokémon for a Pokémon, or one for an empty slot. `SAVE` writes both saves together.

The games' own trade rules apply. FireRed and LeafGreen only trade with Ruby, Sapphire and Emerald after the island part of their story, and a game without its National Pokédex yet only takes Pokémon from its own regional one. A Pokémon that evolves when traded evolves when it arrives, as it would with a cable. If you'd rather trade inside the games themselves, see [Link play](link-play.md).

## Your saves stay safe

Each save is backed up before it's changed (see [Backups and RESTORE BACKUP](saves.md#backups-and-restore-backup)), and the change is only written if the file is still exactly what PokeDaisy read. The game then starts from the new save rather than resuming, so an old resume can't put the old save back. A save state you made before the change still holds the old save, so don't load one afterwards.

> [!TIP]
> Close the game before you edit its save: the library has to be in front.
