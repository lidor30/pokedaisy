captured: 2026-11-16 (headless native-capture/nds_dump, by hand; mainram.bin = the DS's 4 MB main RAM at 0x02000000,
  zeroed but for what Gen4Reader read of it (each range rounded to 16 bytes): sSaveDataPtr 0x021D2228, the SaveData's
  sections, the FieldSystem and the player's map object) - the rest is unrelated memory, and zeros keep the file tiny in git.
rom: Pokemon Heartfelt Gold (v1.0.15).nds (~/Downloads/Game ROMs & Emulation/nds/, sha1 b2b27aeb..., the v1.0.15 xdelta
  on the US HeartGold; its title screen names it).
save: the player's own, from the release's download (~/Documents/RetroArch/saves/mGBA/Pokemon Heartfelt Gold (v1.0.15).sav).
boot: from the .sav: wait 600, then START (held 3) + wait 30 + A (held 3) + wait 150, eight times (title, CONTINUE, then
  the field's X menu), dump: in a Pokémon Center (map header 68, CHERRYGROVE CITY) at (4, 7), the X menu up.
what the game showed: its party TORCHIC Lv7 23/23 (SCRATCH, GROWL, EMBER, QUICK ATTACK); its bag Items EXP. SHARE,
  LUCKY EGG, BLAZIKENITE; Key Items EXP. CHARM, APRICORN BOX, MEGA RING; Medicine POTION x5; Poké Balls POKé BALL x5.
pokedex (2026-11-16): its Pokédex section (0x704 bytes at the SaveData body + 0x14A8) added from a dump of the same boot, the
  same frame's party and pointers; the game's own Pokédex (POKéDEX on the X menu) showed NATIONAL POKéDEX, SEEN 012, OBTAINED 001.
