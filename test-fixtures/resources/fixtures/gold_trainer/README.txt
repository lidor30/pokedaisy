captured: 2026-10-15 (headless mgba_dump, by hand; files as the gold fixture's)
rom / save: as the gold fixture.
boot: native-capture/boot/gold.txt, then a trainer battle started the way the game's scripts start one, on ROUTE 29:
  loadtrainer's writes (wBattleScriptFlags D180 = 0x81, wOtherTrainerClass D118 = 0x10, wOtherTrainerID D11B = 1,
  wBattleType D119 = 0) and Script_startbattle (25:72A8) called from the frame wait. mgba_dump's gbinject writes the
  pokecrystal-family trampoline (rst $08 with the bank and address inline), which Gold's FarCall (a = bank, hl = address)
  doesn't take, so it was overwritten before the next frame: gbinject C6E8 25 72a8 0333 (DelayFrame's halt), then gbpoke 0
  C6E8 F5 C5 D5 E5 3E 25 21 A8 72 CF E1 D1 C1 F1 C9 (wUnusedMapBuffer, free). Class 0x10 is CHAMPION (YOUNGSTER, meant,
  is 0x16): CHAMPION LANCE. A (held 3) through the intro, then 300 frames: dumped on the battle menu (cursor on FIGHT,
  wBattleMode 2) - his GYARADOS (Lv44) out (wCurOTMon 0), six in wOTPartyMons (wOTPartyCount 6: GYARADOS, DRAGONITE,
  DRAGONITE, AERODACTYL, CHARIZARD, DRAGONITE), CYNDAQUIL (Lv5, 17/20) against it.
