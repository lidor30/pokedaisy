captured: 2026-11-02 (headless native-capture/nds_dump, by hand; mainram.bin = the DS's 4 MB main RAM at 0x02000000,
  zeroed but for what Gen4Reader reads: 0x021D1000-0x021D3FFF (sSaveDataPtr 0x021D11B4, sFieldSysPtr 0x021D31F4), the
  SaveData at 0x02271F70 through its Pokégear chunk (+0xBCA0), the FieldSystem, the PlayerAvatar and the player's map object)
  - the rest is unrelated memory, and zeros keep the file tiny in git.
rom: Pokemon Origin HeartGold (v4.0.3) (EN 1.0.0-rc8) (~/Downloads/Game ROMs & Emulation/nds/, sha1 a54a6475..., the English patch
  1.0.0-rc8 applied to the US HeartGold)
save: none exists for it (the US HeartGold save isn't taken: the game starts a new one). A new game played headless:
  wait 600, then A (held 3) + wait 90, forty times through its notice pages, NO INFO NEEDED (touch 128 152), A through
  the intro (DOWN x3 + A on OK at the outfit, A through the name entry), then field scripts run with nds_dump's `script`:
  GiveMon (137) x6 - 155 Lv12, 700 Lv20 holding 234, 25 Lv9, 1028 Lv30 (a form's own id), 445 Lv40, 887 Lv35 -,
  GivePokedex (291), GiveRunningShoes (293), then GiveItem (125) x10: 17 x5, 4 x10, 328, 155 x3, 55 x2, 450, 780, 1,
  137, 26 x4; then `action 3 0 49 1040 368` (FLY to Pallet Town). dump: in PALLET TOWN (map header 49) at (1033, 364).
