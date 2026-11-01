captured: 2026-10-29 (headless native-capture/nds_dump, by hand; mainram.bin = the DS's 4 MB main RAM at 0x02000000,
  zeroed but for what Gen5Reader reads with GEN5_WHITE - the SaveControl pointer (0x020A72B8) and its chain, the
  GameSystem pointer (0x02146268) and its chain to the GameData's player record - plus the whole save image (0x24000
  bytes): the bytes a recording MemoryReader saw, kept by a throwaway test).
rom: Pokemon - White Version (USA, Europe) (NDSi Enhanced) (IRAO rev 0, sha1 bc696a0d...)
save: the player's Black save (8 badges, 93 h, saved at Gear Station), which White loads as its own: title, CONTINUE,
  "Some functions of the C-Gear will be restricted" YES, then about 600 frames on the Super Single Train's platform
  (zone 68), the field free.
