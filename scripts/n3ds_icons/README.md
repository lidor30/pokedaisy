# Pokemon X's party icons, read off the game

Which entry of the cart's icon archive (RomFS `a/0/9/3`) the game draws for each species, form and gender isn't a
table in the cart that could be found, so it was read off the game's own party screen on a device (the RP6,
2026-11-15; docs/3DS_GAMES_PLAN.md). The result is `pokemon_x_icons.tsv`; `gen_icon_table.py` turns it into
`app/src/main/kotlin/com/pokedaisy/app/n3ds/Gen6IconTable.kt`. Only numbers: the pictures stay in the player's cart.

How it was read (a debug build with the n3ds flag, the game in the field with its X menu open on "Pokemon"):

- `read_icons.py CART` writes five Pokemon at a time into the live party and the save's party in memory (the first
  slot keeps a Bulbasaur: the selected slot moves), through the debug builds' `files/debug/n3ds_cmd` (`poke`, `shot`:
  `N3dsSession.debugCommands`), opens the party screen with A, takes the touch screen at 320 x 240, backs out with B,
  and matches each slot against every icon (`match.py`; `drive.py` drives adb, `pk6tool.py` builds the records from
  one of the player's own party records). Results go to `$N3DS_ICONS_WORK/iconmap.tsv`, resumable.
- Before it: `template.bin` (a party slot's 0xE8 stored bytes, from a dump), `ims.pkl` (`icons6.icon` of every
  archive entry) and `offs.pkl` (`match.py` on a shot of six Bulbasaurs: where each slot draws its icon) in
  `$N3DS_ICONS_WORK`. The buttons are the RP6's: A is `BTN_EAST` there (its GAME BUTTONS swap A and B).
- Nothing is saved in the game: a fresh start has the player's own party back.
