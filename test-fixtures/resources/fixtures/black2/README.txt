captured: 2026-10-30 (headless native-capture/nds_dump, by hand; mainram.bin = the DS's 4 MB main RAM at 0x02000000,
  zeroed but for what Gen5Reader reads with GEN5_BLACK2: the SaveControl pointer (0x0209A338) and its chain, the whole
  save image (0x022055E4, 0x26000 bytes: the save file's first copy, live), the GameSystem pointer (0x021410F4), its proc
  system and running proc, the GameData's player record - kept by a script that follows those pointers).
rom: Pokemon - Black Version 2 (USA, Europe) (NDSi Enhanced) (IREO rev 0, sha1 e51e6dfb...)
save: the player's "Pokemon Black 2 Version.sav" (8 badges, 22 h, saved in Humilau City): title, CONTINUE, "Launch
  C-Gear communications?" NO, then about 300 frames outside Humilau City's Pokemon Center (zone 465), the field free.
