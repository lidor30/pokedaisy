captured: 2026-10-14 (headless mgba_dump, by hand; files as the prism fixture's)
rom / save: as the prism fixture.
boot: native-capture/boot/prism.txt, then a trainer battle started the way the game's scripts start one: loadtrainer's
  writes (wBattleScriptFlags D459 = 0x81, wOtherTrainerClass D22F = 1, wOtherTrainerID D231 = 1, wBattleType D230 = 0)
  and startbattle's routine (25:614A) called from the frame wait (mgba_dump's gbinject C002 25 614a 0f4e), then A
  through the intro: dumped on the battle menu (cursor on Fight, wBattleMode 2) - LEADER JOSIAH's CHARMANDER (Lv10)
  out, his two (CHARMANDER, SLUGMA) in wOTPartyMons, LARVITAR (Lv5) against it.
