captured: 2026-10-18 (headless native-capture/nds_dump, by hand; mainram.bin = the DS's 4 MB main RAM at 0x02000000,
  zeroed but for what Gen4Reader reads: 0x021D2000-0x021D4FFF (sSaveDataPtr 0x021D2228, sFieldSysPtr 0x021D4158) and the SaveData at
  0x0227C28C (+0x6A10: through the roamer chunk, REPEL's steps; and its Pokégear chunk's first words at +0xBAF0), the FieldSystem, PlayerAvatar and the player's map object (FLY's region)) - the rest is unrelated memory, and zeros keep the file tiny in git.
rom: Pokemon - SoulSilver Version (USA) (~/Downloads/Game ROMs & Emulation/nds/, sha1 f8dc38ea..., the build
  pret/pokeheartgold makes)
save: the player's own, beside the ROM (a copy: nds_dump writes back to the save it's given).
boot: from the .sav: wait 600, then START (held 3) + wait 30 + A (held 3) + wait 150, twelve times (title, CONTINUE,
  then the field's X menu opens and closes), dump: in FUCHSIA CITY (map header 56) at (1209, 440), the menu up.
