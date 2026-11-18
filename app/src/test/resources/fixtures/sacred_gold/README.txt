captured: 2026-11-18 (headless native-capture/nds_dump, by hand; mainram.bin = the DS's 4 MB main RAM at 0x02000000, kept
  only in the 16-byte blocks the heartgold fixture keeps (the same RAM layout: sSaveDataPtr 0x021D2228 -> 0x0227C28C,
  sFieldSysPtr 0x021D4158 -> 0x022A0274 in both), zeroed elsewhere - zeros keep the file tiny in git.
rom: Pokemon Sacred Gold (v1.05) (Complete).nds (~/Downloads/Game ROMs & Emulation/nds/, sha1 ddb83b93...): Drayano's v1.05
  "SacredGoldComplete.patch" (xdelta 1, from his GBAtemp thread's MediaFire link) over the US HeartGold; its output matched
  the patch's own target MD5.
save: the player's HeartGold save (~/Downloads/Game ROMs & Emulation/nds/backups/rp6-20261103-201146/, a copy)
boot: wait 600, then START (held 3) + wait 30 + A (held 3) + wait 150, twelve times (title, CONTINUE, the X menu), dump:
  in GOLDENROD CITY (map header 76) at (352, 369), the X menu up - the field read (`fieldinfo`) as HeartGold's.
