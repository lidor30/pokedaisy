captured: 2026-10-16 (headless native-capture/nds_dump, by hand; mainram.bin = the DS's 4 MB main RAM at 0x02000000,
  zeroed but for what Gen4Reader reads: 0x021C0000-0x021C0FFF (sSaveDataPtr 0x021C0794) and the SaveData at
  0x0227E164 (+0x21000)) - the rest is unrelated memory, and zeros keep the file tiny in git.
rom: Pokemon - Platinum Version (USA) (Rev 1) (~/Downloads/Game ROMs & Emulation/nds/, sha1 0862ec35..., the build
  pret/pokeplatinum makes)
save: a new game played headless (the user had no Platinum save): AAA, TURTWIG from the briefcase on ROUTE 201, the
  first battle against the rival won (TURTWIG Lv 6), saved through the X menu in the player's house 1F. Poked into
  RAM before the dump, to have a bag to read: 3 POTIONs in MEDICINE (bag slot 327: 0x0227ECC4 = 17, +2 = 3) and 5
  POKé BALLs (slot 431: 0x0227EE64 = 4, +2 = 5).
boot: from the .sav: wait 600, then START (held 3) + wait 30 + A (held 3) + wait 150, ten times (title, CONTINUE),
  savestate; then loadstate, wait 10, the pokes, wait 2, dump: in TWINLEAF TOWN's player house 1F (map header 414)
  at (6, 8).
