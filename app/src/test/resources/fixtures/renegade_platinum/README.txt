captured: 2026-11-06 (headless native-capture/nds_dump, by hand; mainram.bin = the DS's 4 MB main RAM at 0x02000000,
  zeroed but for what Gen4Reader reads: 0x021C0000-0x021C0FFF (sSaveDataPtr 0x021C0794, sFieldSystem 0x021C07DC), the
  SaveData at 0x0227E19C (+0x21000) and the few FieldSystem / player words it reads (each read range kept, rounded to
  16 bytes)) - the rest is unrelated memory, and zeros keep the file tiny in git.
rom: Pokemon Renegade Platinum (v1.3.0).nds (~/Downloads/Game ROMs & Emulation/nds/, sha1 d4ee068b..., Renegade Platinum v1.3.0: its title
  screen and intro name it), CPUE rev 1 like the retail cart.
save: a new game played headless (the user had no save): the player AAAAAAA (boy), given through the game's own script
  commands from the player's room (GivePokemon, AddItem, GiveMoney, GivePokedex, GiveRunningShoes, run by nds_dump's
  `script`): TURTWIG Lv 5, MISMAGIUS Lv 30 holding LEFTOVERS (234), FLYGON Lv 40, CLEFAIRY Lv 12, PIKACHU Lv 9, LUCARIO Lv 35;
  5 POTIONs, 10 POKé BALLs, 3 ORAN BERRYs (155), 2 REPELs (79), TM01, the BICYCLE (450); 12345 money on top of the 3000 a new game has.
  Saved through the X menu (SAVE, YES), written to ~/Documents/RetroArch/saves/mGBA/Pokemon Renegade Platinum (v1.3.0).sav.
boot: from that .sav: wait 600, then START (held 3) + wait 30 + A (held 3) + wait 150, ten times (title, CONTINUE),
  then dump: in TWINLEAF TOWN's player house 2F (map header 415) at (4, 6). The game's own save screen read
  TWINLEAF TOWN, BADGES 0, POKéDEX 5 (the Sinnoh dex: FLYGON isn't in it).
