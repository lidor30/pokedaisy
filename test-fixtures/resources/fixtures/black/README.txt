captured: 2026-10-27 (headless native-capture/nds_dump, by hand; mainram.bin = the DS's 4 MB main RAM at 0x02000000,
  zeroed but for what Gen5Reader reads: the SaveControl pointer (0x020A7298) and its chain to the save image, the save
  image itself (0x0221BBAC, 0x24000 bytes: the save file's first copy, live), the GameSystem pointer (0x02146248), the
  GameSystem, its proc system and the running proc, the GameData's player record (zone and position) - kept by a
  script that follows those pointers).
rom: Pokemon - Black Version (USA, Europe) (NDSi Enhanced) (IRBO rev 0, sha1 26ad0b99...)
save: the player's own (8 badges, 93 h, saved at Gear Station): title, CONTINUE, "Launch C-Gear communications?" NO,
  then about 600 frames on the Super Single Train's platform (zone 68), the field free.
